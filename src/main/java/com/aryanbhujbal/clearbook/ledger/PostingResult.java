package com.aryanbhujbal.clearbook.ledger;

import com.aryanbhujbal.clearbook.domain.MarginPolicy.MarginSnapshot;

/**
 * What came of a posting attempt.
 *
 * <p>{@code alreadyPosted} is a success, not an error. A client retrying after
 * a timeout, or Kafka redelivering a fill, is asking for the same thing twice
 * and should get the same answer twice — the entry id of the posting that
 * already exists. Returning a 409 for that would push the deduplication
 * problem onto every caller, which is the problem an idempotency key exists
 * to solve.
 */
public record PostingResult(
        Long entryId,
        boolean alreadyPosted,
        int attempts,
        MarginSnapshot margin) {

    public static PostingResult posted(Long entryId, int attempts, MarginSnapshot margin) {
        return new PostingResult(entryId, false, attempts, margin);
    }

    public static PostingResult alreadyPosted(Long entryId) {
        return new PostingResult(entryId, true, 0, null);
    }
}
