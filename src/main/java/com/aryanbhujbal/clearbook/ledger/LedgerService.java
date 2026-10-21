package com.aryanbhujbal.clearbook.ledger;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.aryanbhujbal.clearbook.domain.Posting;

/**
 * Posting, with the retry that optimistic locking requires.
 *
 * <p><b>Why this is a separate bean from {@link LedgerWriter}.</b> A retry has
 * to wrap the transaction, not sit inside it. Once a transaction has failed
 * its version check it is marked rollback-only and nothing can be salvaged
 * from within it — retrying inside the same boundary just fails again at
 * commit. Spring applies {@code @Transactional} and {@code @Retryable} through
 * proxies, and a call from one method of a bean to another on the same bean
 * does not pass through its own proxy, so "retry method calls transactional
 * method" only works when they live on different beans. Hence two classes.
 *
 * <p>The retry loop is written out rather than annotated with
 * {@code @Retryable} so that the attempt count can be reported back to the
 * caller and the backoff is visible at the point it matters.
 *
 * <p><b>What is retried, and what is not.</b> An optimistic-lock failure means
 * another posting to the same account got there first; the right response is
 * to re-read and try again, which is what the next attempt does because the
 * balance read happens inside the new transaction. A margin breach means the
 * answer is no, and retrying it would burn attempts on a result that will not
 * change. A duplicate trade id means the work is already done.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    /**
     * Enough attempts to clear realistic contention on one account, few enough
     * that a genuinely hot account fails fast rather than holding a request
     * open while it loses repeatedly.
     */
    static final int MAX_ATTEMPTS = 5;

    private static final long BASE_BACKOFF_MILLIS = 5;

    private final LedgerWriter writer;

    public LedgerService(LedgerWriter writer) {
        this.writer = writer;
    }

    public PostingResult post(Posting posting) {
        // Fast path. Not the guarantee — the UNIQUE constraint on trade_id is
        // that — but it turns the common redelivery case into one cheap read
        // instead of an insert that fails.
        if (posting.tradeId() != null) {
            Optional<Long> existing = writer.findPostedEntryId(posting.tradeId());
            if (existing.isPresent()) {
                return PostingResult.alreadyPosted(existing.get());
            }
        }

        ObjectOptimisticLockingFailureException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                PostingResult result = writer.postOnce(posting);
                return PostingResult.posted(result.entryId(), attempt, result.margin());

            } catch (ObjectOptimisticLockingFailureException collision) {
                // Someone else posted to one of these accounts first. The next
                // attempt opens a new transaction and re-reads the balance, so
                // its margin check is against the state they left behind.
                lastFailure = collision;
                log.debug("optimistic lock collision on attempt {} of {} for trade {}",
                        attempt, MAX_ATTEMPTS, posting.tradeId());
                backOff(attempt);

            } catch (DataIntegrityViolationException duplicate) {
                // Lost the race to insert this trade id. That is not an error:
                // the caller asked for the trade to be posted, and it is.
                if (posting.tradeId() == null) {
                    throw duplicate;
                }
                return writer.findPostedEntryId(posting.tradeId())
                        .map(PostingResult::alreadyPosted)
                        .orElseThrow(() -> duplicate);
            }
        }

        throw new PostingContentionException(posting.tradeId(), MAX_ATTEMPTS, lastFailure);
    }

    /**
     * Jittered exponential backoff.
     *
     * <p>Jittered because without it, two transactions that collide back off
     * by the same amount and collide again on the same schedule — a lockstep
     * that can repeat until both exhaust their attempts.
     */
    private void backOff(int attempt) {
        long ceiling = BASE_BACKOFF_MILLIS * (1L << (attempt - 1));
        long sleep = ThreadLocalRandomHolder.nextLong(1, ceiling + 1);
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying a posting", interrupted);
        }
    }

    /** Indirection so tests can make backoff deterministic. */
    static final class ThreadLocalRandomHolder {
        private ThreadLocalRandomHolder() {
        }

        static long nextLong(long origin, long bound) {
            return java.util.concurrent.ThreadLocalRandom.current().nextLong(origin, bound);
        }
    }

    /** Thrown when a posting cannot win its optimistic lock within the attempt budget. */
    public static class PostingContentionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public PostingContentionException(String tradeId, int attempts, Throwable cause) {
            super("gave up posting %s after %d attempts against concurrent activity on the same account"
                    .formatted(tradeId, attempts), cause);
        }
    }
}
