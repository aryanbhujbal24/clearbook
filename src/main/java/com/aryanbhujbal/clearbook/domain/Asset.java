package com.aryanbhujbal.clearbook.domain;

/**
 * Asset codes as they appear on a journal line.
 *
 * <p>One ledger carries both rupees and shares, so every line has to say which
 * it is measured in. {@code INR} is cash in paise; {@code EQ:TCS} is a count
 * of whole shares. The zero-sum rule is scoped per asset, which is what lets a
 * single entry describe a whole trade — rupees netting to zero on one side,
 * shares on the other.
 */
public final class Asset {

    public static final String CASH = "INR";

    private static final String EQUITY_PREFIX = "EQ:";

    private Asset() {
    }

    public static String equity(String symbol) {
        if (symbol == null || !symbol.matches("[A-Z0-9&_-]{1,24}")) {
            throw new IllegalArgumentException("not a venue symbol: " + symbol);
        }
        return EQUITY_PREFIX + symbol;
    }

    public static boolean isCash(String asset) {
        return CASH.equals(asset);
    }

    public static boolean isEquity(String asset) {
        return asset != null && asset.startsWith(EQUITY_PREFIX);
    }

    /** {@code EQ:TCS} back to {@code TCS}. */
    public static String symbolOf(String asset) {
        if (!isEquity(asset)) {
            throw new IllegalArgumentException("not an equity asset: " + asset);
        }
        return asset.substring(EQUITY_PREFIX.length());
    }
}
