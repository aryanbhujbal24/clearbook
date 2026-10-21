#!/usr/bin/env bash
# Prove the database enforces the ledger invariants on its own.
#
# These rules are the reason the ledger can be trusted, so they are checked
# against a real PostgreSQL rather than asserted in a comment. Every case runs
# in its own transaction, because the balance rule is a DEFERRABLE constraint
# trigger that fires at COMMIT — testing it any other way tests something else.
#
#   ./tools/verify-schema.sh [postgres-url]
#
# Defaults to the local database the Makefile sets up.

set -uo pipefail

URL="${1:-postgresql://postgres@127.0.0.1:5432/clearbook_verify}"
PASS=0
FAIL=0

psql_q() { psql "$URL" -v ON_ERROR_STOP=1 -tAq "$@" 2>&1; }

# ok <name> <sql> — the statements must commit.
ok() {
    local name="$1" sql="$2" out
    out=$(psql "$URL" -v ON_ERROR_STOP=1 -tAq -c "BEGIN; $sql COMMIT;" 2>&1)
    if [ $? -eq 0 ]; then
        printf '  \033[32mpass\033[0m  %s\n' "$name"; PASS=$((PASS + 1))
    else
        printf '  \033[31mFAIL\033[0m  %s\n        expected success, got: %s\n' \
            "$name" "$(echo "$out" | head -2 | tr '\n' ' ')"
        FAIL=$((FAIL + 1))
    fi
}

# rejects <name> <expected-substring> <sql> — the statements must be refused.
rejects() {
    local name="$1" want="$2" sql="$3" out
    out=$(psql "$URL" -v ON_ERROR_STOP=1 -tAq -c "BEGIN; $sql COMMIT;" 2>&1)
    if [ $? -eq 0 ]; then
        printf '  \033[31mFAIL\033[0m  %s\n        the database ALLOWED it\n' "$name"
        FAIL=$((FAIL + 1))
    elif echo "$out" | grep -qi -- "$want"; then
        printf '  \033[32mpass\033[0m  %s\n' "$name"; PASS=$((PASS + 1))
    else
        printf '  \033[31mFAIL\033[0m  %s\n        rejected, but for the wrong reason: %s\n' \
            "$name" "$(echo "$out" | head -2 | tr '\n' ' ')"
        FAIL=$((FAIL + 1))
    fi
}

echo
echo "clearbook — schema invariants against $(psql "$URL" -tAqc 'SHOW server_version' 2>/dev/null || echo '?')"
echo

# ---------------------------------------------------------------------------
# fixtures
# ---------------------------------------------------------------------------
psql "$URL" -v ON_ERROR_STOP=1 -q <<'SQL'
TRUNCATE journal_line, journal_entry, balance_checkpoint, mark_price, account RESTART IDENTITY CASCADE;
INSERT INTO account (id, code, kind, owner_id) VALUES
  (1, 'CLIENT:ARY:CASH', 'CLIENT_CASH',         'ARY'),
  (2, 'CLIENT:ARY:SEC',  'CLIENT_SECURITIES',   'ARY'),
  (3, 'SUSPENSE',        'SETTLEMENT_SUSPENSE',  NULL),
  (4, 'BROKERAGE',       'BROKERAGE_INCOME',     NULL),
  (5, 'ROUNDING',        'ROUNDING_RESIDUE',     NULL);
ALTER TABLE account ALTER COLUMN id RESTART WITH 100;
SQL

echo "the zero-sum rule"

ok "a balanced cash entry commits" "
INSERT INTO journal_entry (id, kind, trade_id, business_date)
  VALUES (1, 'TRADE', 'T-001', DATE '2026-03-02');
INSERT INTO journal_line (entry_id, account_id, asset, amount) VALUES
  (1, 1, 'INR', -100000),
  (1, 3, 'INR',  100000);
"

rejects "an unbalanced entry is refused at commit" "does not balance" "
INSERT INTO journal_entry (id, kind, trade_id, business_date)
  VALUES (2, 'TRADE', 'T-002', DATE '2026-03-02');
INSERT INTO journal_line (entry_id, account_id, asset, amount) VALUES
  (2, 1, 'INR', -100000),
  (2, 3, 'INR',   99999);
"

rejects "balancing in rupees does not excuse unbalanced shares" "does not balance" "
INSERT INTO journal_entry (id, kind, trade_id, business_date)
  VALUES (3, 'TRADE', 'T-003', DATE '2026-03-02');
INSERT INTO journal_line (entry_id, account_id, asset, amount) VALUES
  (3, 1, 'INR',        -100000),
  (3, 3, 'INR',         100000),
  (3, 2, 'EQ:RELIANCE',     10);
"

ok "a multi-asset trade that balances per asset commits" "
INSERT INTO journal_entry (id, kind, trade_id, business_date)
  VALUES (4, 'TRADE', 'T-004', DATE '2026-03-02');
INSERT INTO journal_line (entry_id, account_id, asset, amount) VALUES
  (4, 1, 'INR',        -100000),
  (4, 3, 'INR',         100000),
  (4, 2, 'EQ:RELIANCE',     10),
  (4, 3, 'EQ:RELIANCE',    -10);
"

rejects "deleting one line of a balanced entry is refused" "does not balance" "
DELETE FROM journal_line WHERE entry_id = 4 AND asset = 'INR' AND amount < 0;
"

echo
echo "idempotency"

rejects "the same trade id cannot post twice" "duplicate key" "
INSERT INTO journal_entry (id, kind, trade_id, business_date)
  VALUES (5, 'TRADE', 'T-001', DATE '2026-03-02');
INSERT INTO journal_line (entry_id, account_id, asset, amount) VALUES
  (5, 1, 'INR', -1), (5, 3, 'INR', 1);
"

rejects "a trade without an idempotency key is refused" "journal_entry_trade_has_id" "
INSERT INTO journal_entry (id, kind, trade_id, business_date)
  VALUES (6, 'TRADE', NULL, DATE '2026-03-02');
"

rejects "a non-trade carrying a trade id is refused" "journal_entry_trade_has_id" "
INSERT INTO journal_entry (id, kind, trade_id, business_date)
  VALUES (7, 'ADJUSTMENT', 'T-999', DATE '2026-03-02');
"

echo
echo "line and account shape"

rejects "a zero-amount line is refused" "journal_line_amount_nonzero" "
INSERT INTO journal_entry (id, kind, business_date)
  VALUES (8, 'ADJUSTMENT', DATE '2026-03-02');
INSERT INTO journal_line (entry_id, account_id, asset, amount) VALUES (8, 1, 'INR', 0);
"

rejects "a malformed asset code is refused" "journal_line_asset_shape" "
INSERT INTO journal_entry (id, kind, business_date)
  VALUES (9, 'ADJUSTMENT', DATE '2026-03-02');
INSERT INTO journal_line (entry_id, account_id, asset, amount) VALUES
  (9, 1, 'rupees', -1), (9, 3, 'rupees', 1);
"

rejects "a client account without an owner is refused" "account_owner_consistent" "
INSERT INTO account (code, kind, owner_id) VALUES ('CLIENT:X:CASH', 'CLIENT_CASH', NULL);
"

rejects "a house account with an owner is refused" "account_owner_consistent" "
INSERT INTO account (code, kind, owner_id) VALUES ('HOUSE:X', 'HOUSE_CASH', 'X');
"

rejects "an unknown account kind is refused" "account_kind_valid" "
INSERT INTO account (code, kind, owner_id) VALUES ('WAT', 'SLUSH_FUND', NULL);
"

echo
echo "derived balances"

# Seed a known history: three entries, hand-totalled below. The journal is
# cleared first — the cases above left their own postings on these accounts,
# and a balance assertion is only meaningful against a history you control.
psql "$URL" -v ON_ERROR_STOP=1 -q <<'SQL'
BEGIN;
TRUNCATE journal_line, journal_entry, balance_checkpoint;
INSERT INTO journal_entry (id, kind, business_date) VALUES (20, 'OPENING_BALANCE', DATE '2026-03-01');
INSERT INTO journal_line (entry_id, account_id, asset, amount) VALUES
  (20, 1, 'INR', 50000000), (20, 3, 'INR', -50000000);
INSERT INTO journal_entry (id, kind, trade_id, business_date) VALUES (21, 'TRADE', 'T-100', DATE '2026-03-02');
INSERT INTO journal_line (entry_id, account_id, asset, amount) VALUES
  (21, 1, 'INR', -12000000), (21, 3, 'INR', 12000000),
  (21, 2, 'EQ:TCS', 40), (21, 3, 'EQ:TCS', -40);
INSERT INTO journal_entry (id, kind, trade_id, business_date) VALUES (22, 'TRADE', 'T-101', DATE '2026-03-03');
INSERT INTO journal_line (entry_id, account_id, asset, amount) VALUES
  (22, 1, 'INR', 3000000), (22, 3, 'INR', -3000000),
  (22, 2, 'EQ:TCS', -10), (22, 3, 'EQ:TCS', 10);
COMMIT;
SQL

check_eq() {
    local name="$1" want="$2" sql="$3" got
    got=$(psql_q -c "$sql" | tr -d '[:space:]')
    if [ "$got" = "$want" ]; then
        printf '  \033[32mpass\033[0m  %s\n' "$name"; PASS=$((PASS + 1))
    else
        printf '  \033[31mFAIL\033[0m  %s\n        expected %s, got %s\n' "$name" "$want" "$got"
        FAIL=$((FAIL + 1))
    fi
}

# Opening 500,000.00 less 120,000.00 plus 30,000.00 = 410,000.00, in paise.
check_eq "cash balance folds the journal" "41000000" \
  "SELECT COALESCE(SUM(amount),0) FROM journal_line WHERE account_id=1 AND asset='INR';"

check_eq "position folds the journal" "30" \
  "SELECT COALESCE(SUM(amount),0) FROM journal_line WHERE account_id=2 AND asset='EQ:TCS';"

check_eq "every asset nets to zero across the whole ledger" "0" \
  "SELECT COUNT(*) FROM (SELECT asset FROM journal_line GROUP BY asset HAVING SUM(amount) <> 0) x;"

check_eq "every individual entry nets to zero" "0" \
  "SELECT COUNT(*) FROM (SELECT entry_id, asset FROM journal_line GROUP BY entry_id, asset HAVING SUM(amount) <> 0) x;"

echo
echo "checkpoints are a memo, not a second truth"

psql "$URL" -v ON_ERROR_STOP=1 -q <<'SQL'
-- Checkpoint the cash account as at the first trade only.
INSERT INTO balance_checkpoint (account_id, asset, through_line_id, amount)
SELECT 1, 'INR', MAX(l.id), SUM(l.amount)
  FROM journal_line l
 WHERE l.account_id = 1 AND l.asset = 'INR' AND l.entry_id <= 21;
SQL

check_eq "checkpoint plus later lines equals the full fold" "41000000" "
SELECT c.amount + COALESCE((
         SELECT SUM(l.amount) FROM journal_line l
          WHERE l.account_id = c.account_id AND l.asset = c.asset
            AND l.id > c.through_line_id), 0)
  FROM balance_checkpoint c WHERE c.account_id = 1 AND c.asset = 'INR';"

check_eq "dropping every checkpoint changes no answer" "41000000" "
DELETE FROM balance_checkpoint;
SELECT COALESCE(SUM(amount),0) FROM journal_line WHERE account_id=1 AND asset='INR';"

echo
printf '  %d passed, %d failed\n\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
