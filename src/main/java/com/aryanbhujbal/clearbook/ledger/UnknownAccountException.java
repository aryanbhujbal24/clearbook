package com.aryanbhujbal.clearbook.ledger;

/**
 * Thrown when a posting names an account that does not exist.
 *
 * <p>Refused rather than created on demand. An account appearing because
 * somebody mistyped a client id is how money ends up somewhere nobody is
 * reconciling, and the ledger would still balance perfectly while it happened.
 */
public class UnknownAccountException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String accountCode;

    public UnknownAccountException(String accountCode) {
        super("no such account: " + accountCode);
        this.accountCode = accountCode;
    }

    public String getAccountCode() {
        return accountCode;
    }
}
