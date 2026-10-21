#!/usr/bin/env bash
# Walk the reference scenario through a real PostgreSQL, with no Java at all.
#
# Everything the ledger claims about itself is a property of the schema —
# entries that must balance per asset, a trade id that cannot post twice,
# balances that are folded from the journal rather than stored. This script
# demonstrates each of them against a live database, so they can be checked
# without a build tool, a network, or Docker.
#
#   ./tools/demo-ledger.sh [postgres-url]
#
# The amounts are the ones computed by FeeSchedule and TradePostings for the
# same two trades; see docs/examples.md, which is generated from that code.

set -uo pipefail

URL="${1:-postgresql://postgres@127.0.0.1:5432/clearbook_demo}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
dim()  { printf '\033[2m%s\033[0m\n' "$1"; }
good() { printf '  \033[32m%s\033[0m\n' "$1"; }
bad()  { printf '  \033[31m%s\033[0m\n' "$1"; }

q() { psql "$URL" -v ON_ERROR_STOP=1 -q "$@"; }

echo
bold "clearbook — the reference scenario, against PostgreSQL"
dim  "no Java, no Maven, no Docker: just the schema doing its job"
echo

# ---------------------------------------------------------------------------
bold "1 · schema"
# ---------------------------------------------------------------------------
q -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" >/dev/null 2>&1
if q -f "$HERE/src/main/resources/db/migration/V1__ledger_core.sql" >/dev/null 2>&1; then
    good "migration applied"
else
    bad "could not apply the migration to $URL"
    exit 1
fi

q <<'SQL' >/dev/null
INSERT INTO account (code, kind, owner_id) VALUES
  ('CLIENT:ARY:CASH',  'CLIENT_CASH',        'ARY'),
  ('CLIENT:ARY:SEC',   'CLIENT_SECURITIES',  'ARY'),
  ('HOUSE_CASH',       'HOUSE_CASH',          NULL),
  ('SUSPENSE',         'SETTLEMENT_SUSPENSE', NULL),
  ('BROKERAGE_INCOME', 'BROKERAGE_INCOME',    NULL),
  ('TAX_PAYABLE',      'TAX_PAYABLE',         NULL),
  ('EXCHANGE_FEES',    'EXCHANGE_FEES',       NULL);
INSERT INTO mark_price (symbol, business_date, price) VALUES ('TCS', DATE '2026-03-02', 305000);
SQL
good "7 accounts opened, TCS marked at 3,050.00"

# ---------------------------------------------------------------------------
echo
bold "2 · funding, then two trades"
# ---------------------------------------------------------------------------

q <<'SQL' >/dev/null
-- Opening balance: 5,00,000.00 to the client, against house cash. Even a
-- deposit is double-entry; a single-sided entry would breach conservation.
WITH e AS (
  INSERT INTO journal_entry (kind, business_date, description)
  VALUES ('OPENING_BALANCE', DATE '2026-03-02', 'opening balance for ARY') RETURNING id)
INSERT INTO journal_line (entry_id, account_id, asset, amount)
SELECT e.id, a.id, 'INR', v.amount
  FROM e, (VALUES ('CLIENT:ARY:CASH', 50000000), ('HOUSE_CASH', -50000000))
          AS v(code, amount)
  JOIN account a ON a.code = v.code;
SQL
good "opening balance        CLIENT:ARY:CASH  +500,000.00"

q <<'SQL' >/dev/null
-- BUY 40 TCS @ 3,000.00. Turnover 12,000,000; charges 16,594.
WITH e AS (
  INSERT INTO journal_entry (kind, trade_id, business_date, description)
  VALUES ('TRADE', 'T-1', DATE '2026-03-02', 'BUY 40 TCS @ 3000.00') RETURNING id)
INSERT INTO journal_line (entry_id, account_id, asset, amount)
SELECT e.id, a.id, v.asset, v.amount
  FROM e, (VALUES
      ('CLIENT:ARY:CASH',  'INR',    -12016594),
      ('SUSPENSE',         'INR',     12000000),
      ('BROKERAGE_INCOME', 'INR',         2000),
      ('TAX_PAYABLE',      'INR',        14226),
      ('EXCHANGE_FEES',    'INR',          368),
      ('CLIENT:ARY:SEC',   'EQ:TCS',        40),
      ('SUSPENSE',         'EQ:TCS',       -40)
  ) AS v(code, asset, amount)
  JOIN account a ON a.code = v.code;
SQL
good "T-1  BUY  40 TCS @ 3,000.00    charges 165.94"

q <<'SQL' >/dev/null
-- SELL 10 TCS @ 3,100.00. Turnover 3,100,000; charges 4,309, taken OUT of the
-- proceeds rather than added to them.
WITH e AS (
  INSERT INTO journal_entry (kind, trade_id, business_date, description)
  VALUES ('TRADE', 'T-2', DATE '2026-03-02', 'SELL 10 TCS @ 3100.00') RETURNING id)
INSERT INTO journal_line (entry_id, account_id, asset, amount)
SELECT e.id, a.id, v.asset, v.amount
  FROM e, (VALUES
      ('CLIENT:ARY:CASH',  'INR',      3095691),
      ('SUSPENSE',         'INR',     -3100000),
      ('BROKERAGE_INCOME', 'INR',          930),
      ('TAX_PAYABLE',      'INR',         3284),
      ('EXCHANGE_FEES',    'INR',           95),
      ('CLIENT:ARY:SEC',   'EQ:TCS',       -10),
      ('SUSPENSE',         'EQ:TCS',        10)
  ) AS v(code, asset, amount)
  JOIN account a ON a.code = v.code;
SQL
good "T-2  SELL 10 TCS @ 3,100.00    charges 43.09"

# ---------------------------------------------------------------------------
echo
bold "3 · balances, folded from the journal"
dim  "no balance is stored anywhere — each of these is SUM(amount) over the lines"
echo
# ---------------------------------------------------------------------------
psql "$URL" -q <<'SQL'
SELECT a.code AS account,
       l.asset,
       CASE WHEN l.asset = 'INR'
            THEN to_char(SUM(l.amount) / 100.0, 'FM999,999,999,990.00')
            ELSE SUM(l.amount)::text
       END AS balance
  FROM journal_line l JOIN account a ON a.id = l.account_id
 GROUP BY a.code, l.asset
HAVING SUM(l.amount) <> 0
 ORDER BY l.asset, a.code;
SQL

# ---------------------------------------------------------------------------
bold "4 · margin on the closing position"
echo
# ---------------------------------------------------------------------------
psql "$URL" -q <<'SQL'
WITH cash AS (
    SELECT COALESCE(SUM(l.amount), 0) AS paise
      FROM journal_line l JOIN account a ON a.id = l.account_id
     WHERE a.code = 'CLIENT:ARY:CASH' AND l.asset = 'INR'),
pos AS (
    SELECT COALESCE(SUM(l.amount), 0) AS qty
      FROM journal_line l JOIN account a ON a.id = l.account_id
     WHERE a.code = 'CLIENT:ARY:SEC' AND l.asset = 'EQ:TCS'),
mv AS (SELECT pos.qty * m.price AS paise FROM pos, mark_price m
        WHERE m.symbol = 'TCS' AND m.business_date = DATE '2026-03-02')
SELECT to_char(cash.paise / 100.0, 'FM999,999,990.00')                   AS cash,
       pos.qty                                                            AS position,
       to_char(mv.paise / 100.0, 'FM999,999,990.00')                      AS market_value,
       to_char((cash.paise + mv.paise) / 100.0, 'FM999,999,990.00')       AS equity,
       to_char(ROUND(mv.paise * 0.20) / 100.0, 'FM999,999,990.00')        AS requirement,
       (cash.paise + mv.paise) >= ROUND(mv.paise * 0.20)                  AS solvent
  FROM cash, pos, mv;
SQL

# ---------------------------------------------------------------------------
bold "5 · what the database refuses"
echo
# ---------------------------------------------------------------------------

refuses() {
    local what="$1" sql="$2" out
    out=$(psql "$URL" -v ON_ERROR_STOP=1 -tAq -c "BEGIN; $sql COMMIT;" 2>&1)
    if [ $? -eq 0 ]; then
        bad "ALLOWED  $what"
        return 1
    fi
    good "refused  $what"
    printf '           \033[2m%s\033[0m\n' \
        "$(echo "$out" | grep -oiE '(ERROR|DETAIL):.*' | head -1 | cut -c1-96)"
}

refuses "an entry that does not balance" "
INSERT INTO journal_entry (id, kind, business_date) VALUES (900, 'ADJUSTMENT', DATE '2026-03-02');
INSERT INTO journal_line (entry_id, account_id, asset, amount)
SELECT 900, a.id, 'INR', v.amount FROM (VALUES ('CLIENT:ARY:CASH', 10000), ('HOUSE_CASH', -9999))
AS v(code, amount) JOIN account a ON a.code = v.code;"

refuses "rupees balancing while shares do not" "
INSERT INTO journal_entry (id, kind, business_date) VALUES (901, 'ADJUSTMENT', DATE '2026-03-02');
INSERT INTO journal_line (entry_id, account_id, asset, amount)
SELECT 901, a.id, v.asset, v.amount FROM (VALUES
  ('CLIENT:ARY:CASH', 'INR', -10000), ('HOUSE_CASH', 'INR', 10000),
  ('CLIENT:ARY:SEC', 'EQ:TCS', 5)) AS v(code, asset, amount)
JOIN account a ON a.code = v.code;"

refuses "the same trade id posting twice" "
INSERT INTO journal_entry (kind, trade_id, business_date)
VALUES ('TRADE', 'T-1', DATE '2026-03-02');"

refuses "deleting one leg of a settled trade" "
DELETE FROM journal_line WHERE id = (
  SELECT l.id FROM journal_line l JOIN journal_entry e ON e.id = l.entry_id
   WHERE e.trade_id = 'T-1' AND l.asset = 'INR' ORDER BY l.id LIMIT 1);"

# ---------------------------------------------------------------------------
echo
bold "6 · conservation"
# ---------------------------------------------------------------------------
OUT_OF_BALANCE=$(psql "$URL" -tAq -c "
SELECT COUNT(*) FROM (SELECT asset FROM journal_line GROUP BY asset HAVING SUM(amount) <> 0) x;")

if [ "${OUT_OF_BALANCE// /}" = "0" ]; then
    good "every asset still nets to zero across the whole ledger"
else
    bad "$OUT_OF_BALANCE asset(s) out of balance — the ledger cannot be trusted"
    exit 1
fi
echo
