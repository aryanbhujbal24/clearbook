package com.aryanbhujbal.clearbook.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aryanbhujbal.clearbook.domain.Posting.EntryKind;
import com.aryanbhujbal.clearbook.domain.Posting.PostingLine;
import com.aryanbhujbal.clearbook.domain.Posting.UnbalancedPostingException;

/**
 * The invariants a posting carries before any database is involved.
 *
 * <p>These duplicate rules the schema also enforces, on purpose. The database
 * is the guarantee — nothing can bypass it — but it reports at COMMIT, by
 * which point the stack trace points at a flush rather than at the code that
 * built the lines. These checks are the ones that say what is wrong, where.
 */
class PostingTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

    @Test
    void aBalancedPostingIsAccepted() {
        assertThatCode(() -> new Posting(EntryKind.ADJUSTMENT, null, DAY, "fine",
                List.of(
                        new PostingLine("A", "INR", -100),
                        new PostingLine("B", "INR", 100))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("lines that do not net to zero are refused, and the message says by how much")
    void unbalancedIsRefused() {
        assertThatThrownBy(() -> new Posting(EntryKind.ADJUSTMENT, null, DAY, "bad",
                List.of(
                        new PostingLine("A", "INR", -100),
                        new PostingLine("B", "INR", 99))))
                .isInstanceOf(UnbalancedPostingException.class)
                .hasMessageContaining("INR nets to -1");
    }

    @Test
    @DisplayName("balancing in rupees does not excuse unbalanced shares")
    void balanceIsCheckedPerAsset() {
        // The whole reason the rule is scoped per asset: a trade moves money
        // and stock at once, and summing them together would be meaningless.
        assertThatThrownBy(() -> new Posting(EntryKind.ADJUSTMENT, null, DAY, "bad",
                List.of(
                        new PostingLine("A", "INR", -100),
                        new PostingLine("B", "INR", 100),
                        new PostingLine("A", "EQ:TCS", 5))))
                .isInstanceOf(UnbalancedPostingException.class)
                .hasMessageContaining("EQ:TCS nets to 5");
    }

    @Test
    @DisplayName("a multi-asset entry that balances in each asset is accepted")
    void multiAssetBalances() {
        assertThatCode(() -> new Posting(EntryKind.ADJUSTMENT, null, DAY, "a trade shape",
                List.of(
                        new PostingLine("A", "INR", -100),
                        new PostingLine("B", "INR", 100),
                        new PostingLine("A", "EQ:TCS", 5),
                        new PostingLine("B", "EQ:TCS", -5))))
                .doesNotThrowAnyException();
    }

    @Test
    void tradesMustCarryTheirIdempotencyKey() {
        assertThatThrownBy(() -> new Posting(EntryKind.TRADE, null, DAY, "x",
                List.of(new PostingLine("A", "INR", -1), new PostingLine("B", "INR", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotency key");
    }

    @Test
    void onlyTradesMayCarryOne() {
        assertThatThrownBy(() -> new Posting(EntryKind.ADJUSTMENT, "T-1", DAY, "x",
                List.of(new PostingLine("A", "INR", -1), new PostingLine("B", "INR", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only a TRADE posting");
    }

    @Test
    void anEntryWithNoLinesIsNotAnEntry() {
        assertThatThrownBy(() -> new Posting(EntryKind.ADJUSTMENT, null, DAY, "x", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroAmountLinesCarryNoInformation() {
        assertThatThrownBy(() -> new PostingLine("A", "INR", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero-amount");
    }

    @Test
    void assetCodesAreChecked() {
        assertThatThrownBy(() -> new PostingLine("A", "rupees", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new PostingLine("A", "EQ:TATA_MOTORS", 1))
                .doesNotThrowAnyException();
    }

    @Test
    void linesAreImmutableOnceTheEntryExists() {
        Posting posting = new Posting(EntryKind.ADJUSTMENT, null, DAY, "x",
                new java.util.ArrayList<>(List.of(
                        new PostingLine("A", "INR", -1),
                        new PostingLine("B", "INR", 1))));

        assertThatThrownBy(() -> posting.lines().add(new PostingLine("C", "INR", 5)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a trade posting is built from the side, not from the sign of a quantity")
    void tradeDirectionComesFromSide() {
        Trade buy = new Trade("T-1", "ARY", "TCS", Side.BUY, 10, Money.ofRupees(100), DAY);
        Trade sell = new Trade("T-2", "ARY", "TCS", Side.SELL, 10, Money.ofRupees(100), DAY);

        assertThat(buy.signedQuantity()).isEqualTo(10);
        assertThat(sell.signedQuantity()).isEqualTo(-10);

        Posting buyPosting = TradePostings.forTrade(buy, FeeSchedule.free().compute(Side.BUY, buy.turnover()));
        Posting sellPosting = TradePostings.forTrade(sell, FeeSchedule.free().compute(Side.SELL, sell.turnover()));

        // With no charges the two are exact mirrors, which is the cleanest
        // statement of "a sale is a purchase with the signs reversed".
        assertThat(cashOf(buyPosting, AccountCodes.clientCash("ARY")))
                .isEqualTo(-cashOf(sellPosting, AccountCodes.clientCash("ARY")));
    }

    private static long cashOf(Posting posting, String account) {
        return posting.lines().stream()
                .filter(l -> l.accountCode().equals(account) && Asset.isCash(l.asset()))
                .mapToLong(PostingLine::amount)
                .sum();
    }
}
