package com.aryanbhujbal.clearbook.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A complete, balanced journal entry, before it touches a database.
 *
 * <p>The zero-sum rule is checked here <em>and</em> by a constraint trigger in
 * PostgreSQL. That is not redundancy for its own sake: the database check is
 * the one that cannot be bypassed by any present or future code path, and the
 * one here is the one that produces a diagnosable error — it can say which
 * asset is out and by how much, at the point where the lines were built,
 * instead of surfacing as a constraint violation at commit with a stack trace
 * pointing at a flush.
 */
public record Posting(
        EntryKind kind,
        String tradeId,
        LocalDate businessDate,
        String description,
        List<PostingLine> lines) {

    public enum EntryKind {
        TRADE, SETTLEMENT, FEE, ADJUSTMENT, OPENING_BALANCE
    }

    public Posting {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (businessDate == null) {
            throw new IllegalArgumentException("businessDate is required");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("a posting with no lines is not an entry");
        }
        // Mirrors the database's own rule: trades carry an idempotency key,
        // nothing else does.
        boolean hasTradeId = tradeId != null && !tradeId.isBlank();
        if ((kind == EntryKind.TRADE) != hasTradeId) {
            throw new IllegalArgumentException(kind == EntryKind.TRADE
                    ? "a TRADE posting must carry its tradeId — it is the idempotency key"
                    : "only a TRADE posting may carry a tradeId, got kind " + kind);
        }

        lines = List.copyOf(lines);
        requireBalanced(lines);
    }

    private static void requireBalanced(List<PostingLine> lines) {
        Map<String, Long> byAsset = new LinkedHashMap<>();
        for (PostingLine line : lines) {
            byAsset.merge(line.asset(), line.amount(), Math::addExact);
        }
        List<String> offending = new ArrayList<>();
        byAsset.forEach((asset, total) -> {
            if (total != 0) {
                offending.add(asset + " nets to " + total);
            }
        });
        if (!offending.isEmpty()) {
            throw new UnbalancedPostingException(
                    "posting does not balance: " + String.join("; ", offending));
        }
    }

    /** Thrown when lines do not net to zero for every asset they touch. */
    public static class UnbalancedPostingException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public UnbalancedPostingException(String message) {
            super(message);
        }
    }

    /** One leg: an account, the asset it is denominated in, and a signed amount. */
    public record PostingLine(String accountCode, String asset, long amount) {

        public PostingLine {
            if (accountCode == null || accountCode.isBlank()) {
                throw new IllegalArgumentException("accountCode is required");
            }
            if (asset == null || !asset.matches("INR|EQ:[A-Z0-9&_-]{1,24}")) {
                throw new IllegalArgumentException("not an asset code: " + asset);
            }
            if (amount == 0) {
                throw new IllegalArgumentException(
                        "a zero-amount line carries no information: " + accountCode);
            }
        }

        public static PostingLine cash(String accountCode, Money amount) {
            return new PostingLine(accountCode, Asset.CASH, amount.paise());
        }

        public static PostingLine shares(String accountCode, String symbol, long quantity) {
            return new PostingLine(accountCode, Asset.equity(symbol), quantity);
        }
    }
}
