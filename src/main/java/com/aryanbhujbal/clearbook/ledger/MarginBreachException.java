package com.aryanbhujbal.clearbook.ledger;

import com.aryanbhujbal.clearbook.domain.MarginPolicy.MarginSnapshot;

/**
 * Thrown when a posting would leave an account below its maintenance
 * requirement. Raised before the transaction commits, so the trade does not
 * happen rather than happening and being unwound.
 *
 * <p>Deliberately not retryable. An optimistic-lock failure means "try again,
 * someone moved first"; a margin breach means "no". Retrying it would burn
 * attempts on an answer that will not change.
 */
public class MarginBreachException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String accountCode;
    private final transient MarginSnapshot snapshot;

    public MarginBreachException(String accountCode, MarginSnapshot snapshot) {
        super("posting would breach maintenance margin on %s: equity %s against a requirement of %s"
                .formatted(accountCode, snapshot.equity(), snapshot.requirement()));
        this.accountCode = accountCode;
        this.snapshot = snapshot;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public MarginSnapshot getSnapshot() {
        return snapshot;
    }
}
