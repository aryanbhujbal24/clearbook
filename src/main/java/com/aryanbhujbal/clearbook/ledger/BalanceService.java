package com.aryanbhujbal.clearbook.ledger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.aryanbhujbal.clearbook.domain.Asset;
import com.aryanbhujbal.clearbook.domain.Money;

/**
 * Balances, folded from the journal.
 *
 * <p>Nothing here stores a balance. A balance is the sum of an account's
 * lines, optionally short-circuited by a checkpoint — which is a cached prefix
 * of that same sum, never an independent figure. The consequence worth having
 * is that every number on a statement can be traced to the entries that
 * produced it, and no reconciliation between "the balance" and "the entries"
 * is ever needed, because there is only one of them.
 *
 * <p>The cost is that a naive implementation is O(history) on the critical
 * path of every margin check, which is what checkpointing addresses. Its
 * correctness rests on postings to a single account being serialised by the
 * optimistic lock in {@link AccountEntity} — see the note there.
 */
@Service
public class BalanceService {

    private final JournalLineRepository lines;
    private final BalanceCheckpointRepository checkpoints;

    public BalanceService(JournalLineRepository lines, BalanceCheckpointRepository checkpoints) {
        this.lines = lines;
        this.checkpoints = checkpoints;
    }

    /** The balance of one account in one asset. */
    @Transactional(readOnly = true)
    public Money balance(Long accountId, String asset) {
        return Money.ofPaise(rawBalance(accountId, asset));
    }

    /** A share count rather than an amount of money. */
    @Transactional(readOnly = true)
    public long position(Long accountId, String symbol) {
        return rawBalance(accountId, Asset.equity(symbol));
    }

    private long rawBalance(Long accountId, String asset) {
        Optional<BalanceCheckpointEntity> checkpoint =
                checkpoints.findByKey(new BalanceCheckpointEntity.Key(accountId, asset));

        return checkpoint
                .map(c -> c.getAmount() + lines.sumAfter(accountId, asset, c.getThroughLineId()))
                .orElseGet(() -> lines.sumFor(accountId, asset));
    }

    /**
     * Every asset the account holds a non-zero amount of.
     *
     * <p>Goes to the journal directly rather than through checkpoints: this is
     * a statement-time query, not a critical-path one, and correctness by
     * construction is worth more here than the saved milliseconds.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> allBalances(Long accountId) {
        Map<String, Long> balances = new HashMap<>();
        for (Object[] row : lines.balancesByAsset(accountId)) {
            balances.put((String) row[0], ((Number) row[1]).longValue());
        }
        return balances;
    }

    /** Positions only, keyed by symbol rather than asset code. */
    @Transactional(readOnly = true)
    public Map<String, Long> positions(Long accountId) {
        Map<String, Long> positions = new HashMap<>();
        allBalances(accountId).forEach((asset, amount) -> {
            if (Asset.isEquity(asset)) {
                positions.put(Asset.symbolOf(asset), amount);
            }
        });
        return positions;
    }

    /**
     * Rolls the checkpoint for one account and asset up to its latest line.
     *
     * <p>Runs in its own transaction: a checkpoint is a performance memo, and
     * a failure to take one must never roll back the posting that triggered
     * it. The worst outcome of a failed checkpoint is a slower balance query.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkpoint(Long accountId, String asset) {
        long throughLineId = lines.highestLineId(accountId, asset);
        if (throughLineId == 0) {
            return;
        }
        long amount = rawBalance(accountId, asset);

        BalanceCheckpointEntity.Key key = new BalanceCheckpointEntity.Key(accountId, asset);
        BalanceCheckpointEntity existing = checkpoints.findByKey(key).orElse(null);
        if (existing == null) {
            checkpoints.save(new BalanceCheckpointEntity(accountId, asset, throughLineId, amount));
        } else if (throughLineId > existing.getThroughLineId()) {
            existing.advanceTo(throughLineId, amount);
            checkpoints.save(existing);
        }
    }

    /**
     * Whether the ledger as a whole still conserves every asset.
     *
     * <p>Should always be empty. If it is not, something has written lines
     * that do not net to zero and the ledger can no longer be trusted — which
     * is why this is surfaced as a health check rather than left to a report
     * somebody reads on Monday.
     */
    @Transactional(readOnly = true)
    public List<String> assetsOutOfBalance() {
        return lines.assetsThatDoNotNetToZero().stream()
                .map(row -> row[0] + " nets to " + row[1])
                .toList();
    }
}
