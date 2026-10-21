package com.aryanbhujbal.clearbook.domain;

/**
 * The account codes a posting refers to.
 *
 * <p>Codes rather than ids, so that a posting can be built, validated and
 * tested with no database in sight. Resolution to account rows happens once,
 * at the edge.
 */
public final class AccountCodes {

    public static final String SETTLEMENT_SUSPENSE = "SUSPENSE";
    public static final String BROKERAGE_INCOME = "BROKERAGE_INCOME";
    public static final String TAX_PAYABLE = "TAX_PAYABLE";
    public static final String EXCHANGE_FEES = "EXCHANGE_FEES";
    public static final String ROUNDING_RESIDUE = "ROUNDING_RESIDUE";

    private AccountCodes() {
    }

    public static String clientCash(String clientId) {
        return "CLIENT:" + requireClient(clientId) + ":CASH";
    }

    public static String clientSecurities(String clientId) {
        return "CLIENT:" + requireClient(clientId) + ":SEC";
    }

    private static String requireClient(String clientId) {
        if (clientId == null || !clientId.matches("[A-Z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("not a client id: " + clientId);
        }
        return clientId;
    }
}
