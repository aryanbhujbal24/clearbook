package com.aryanbhujbal.clearbook.domain;

/** Which way a trade goes. */
public enum Side {
    BUY,
    SELL;

    /** +1 for a buy, -1 for a sell — the sign applied to a position change. */
    public int positionSign() {
        return this == BUY ? 1 : -1;
    }

    /** A buy costs cash, a sell raises it. */
    public int cashSign() {
        return this == BUY ? -1 : 1;
    }
}
