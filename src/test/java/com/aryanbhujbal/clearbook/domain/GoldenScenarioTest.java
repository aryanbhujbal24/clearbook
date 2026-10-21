package com.aryanbhujbal.clearbook.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.aryanbhujbal.clearbook.domain.Posting.PostingLine;

/**
 * The reference scenario: two trades whose every figure was worked out by hand
 * before any of this code existed.
 *
 * <p>This is the test the rest of the system is built on top of. Not because
 * two trades are thorough, but because it is the only test here whose expected
 * values come from somewhere other than the code under test. Everything else —
 * property tests, concurrency tests, the Testcontainers suite — checks that
 * the system is self-consistent. This one checks that it is <em>right</em>.
 *
 * <p>The arithmetic, in paise:
 *
 * <pre>
 *   opening                                              50,000,000
 *
 *   BUY 40 TCS @ 3,000.00        turnover                12,000,000
 *     brokerage   0.03%  = 3,600, capped at                   2,000
 *     STT         0.1%                                       12,000
 *     exchange    0.00297% = 356.40 ->                          356
 *     regulator   0.0001%                                         12
 *     stamp duty  0.015%, buy only                            1,800
 *     GST         18% of (2,000 + 356 + 12) = 426.24 ->         426
 *                                            charges       16,594
 *     client cash                                      -12,016,594
 *
 *   SELL 10 TCS @ 3,100.00       turnover                 3,100,000
 *     brokerage   0.03%, under the cap                          930
 *     STT         0.1%                                        3,100
 *     exchange    0.00297% = 92.07 ->                            92
 *     regulator   0.0001%  = 3.10  ->                             3
 *     stamp duty  none on a sale                                  0
 *     GST         18% of (930 + 92 + 3) = 184.50 ->             184
 *                                            charges        4,309
 *     client cash                                       +3,095,691
 *
 *   closing cash   50,000,000 - 12,016,594 + 3,095,691 = 41,079,097
 *   closing position                              40 - 10 =      30
 * </pre>
 *
 * <p>The GST figure on the sale is 184.50 exactly — a deliberate choice, so
 * the scenario exercises a true half-even tie rather than only rounding down.
 */
class GoldenScenarioTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);
    private static final String CLIENT = "ARY";

    private final FeeSchedule schedule = FeeSchedule.defaults();

    private final Trade buy = new Trade(
            "T-1", CLIENT, "TCS", Side.BUY, 40, Money.ofRupees("3000.00"), DAY);
    private final Trade sell = new Trade(
            "T-2", CLIENT, "TCS", Side.SELL, 10, Money.ofRupees("3100.00"), DAY);

    @Nested
    @DisplayName("charges on the purchase")
    class BuyCharges {

        private final FeeBreakdown fees = schedule.compute(Side.BUY, buy.turnover());

        @Test
        void turnoverIsQuantityTimesPrice() {
            assertThat(buy.turnover()).isEqualTo(Money.ofPaise(12_000_000));
        }

        @Test
        @DisplayName("brokerage hits the per-order cap")
        void brokerageIsCapped() {
            // 0.03% of 12,000,000 is 3,600 paise; the cap is 2,000.
            assertThat(fees.brokerage()).isEqualTo(Money.ofRupees(20));
        }

        @Test
        void statutoryCharges() {
            assertThat(fees.securitiesTransactionTax()).isEqualTo(Money.ofPaise(12_000));
            assertThat(fees.exchangeTransaction()).isEqualTo(Money.ofPaise(356));
            assertThat(fees.regulatorTurnover()).isEqualTo(Money.ofPaise(12));
            assertThat(fees.stampDuty()).isEqualTo(Money.ofPaise(1_800));
        }

        @Test
        @DisplayName("GST is charged on components that were already rounded")
        void gstOnRoundedComponents() {
            // 18% of (2,000 + 356 + 12) = 426.24, not 18% of some unrounded ideal.
            assertThat(fees.goodsAndServicesTax()).isEqualTo(Money.ofPaise(426));
        }

        @Test
        void totalIsTheSumOfTheParts() {
            assertThat(fees.total()).isEqualTo(Money.ofPaise(16_594));
            assertThat(fees.total()).isEqualTo(fees.components().stream()
                    .map(FeeBreakdown.Component::amount)
                    .reduce(Money.ZERO, Money::plus));
        }
    }

    @Nested
    @DisplayName("charges on the sale")
    class SellCharges {

        private final FeeBreakdown fees = schedule.compute(Side.SELL, sell.turnover());

        @Test
        void brokerageUnderTheCap() {
            assertThat(fees.brokerage()).isEqualTo(Money.ofPaise(930));
        }

        @Test
        void noStampDutyOnASale() {
            assertThat(fees.stampDuty()).isEqualTo(Money.ZERO);
        }

        @Test
        @DisplayName("GST lands on an exact .5 tie and breaks to even")
        void gstTieBreaksToEven() {
            // 18% of 1,025 = 184.50 exactly. Half-up would give 185.
            assertThat(fees.goodsAndServicesTax()).isEqualTo(Money.ofPaise(184));
        }

        @Test
        void totalCharges() {
            assertThat(fees.total()).isEqualTo(Money.ofPaise(4_309));
        }
    }

    @Nested
    @DisplayName("the postings")
    class Postings {

        private final Posting buyPosting =
                TradePostings.forTrade(buy, schedule.compute(Side.BUY, buy.turnover()));
        private final Posting sellPosting =
                TradePostings.forTrade(sell, schedule.compute(Side.SELL, sell.turnover()));

        @Test
        @DisplayName("a purchase debits consideration plus charges, credits consideration only")
        void purchaseLines() {
            assertThat(amountOn(buyPosting, AccountCodes.clientCash(CLIENT), Asset.CASH))
                    .isEqualTo(-12_016_594);
            assertThat(amountOn(buyPosting, AccountCodes.SETTLEMENT_SUSPENSE, Asset.CASH))
                    .isEqualTo(12_000_000);
            assertThat(amountOn(buyPosting, AccountCodes.BROKERAGE_INCOME, Asset.CASH))
                    .isEqualTo(2_000);
            assertThat(amountOn(buyPosting, AccountCodes.TAX_PAYABLE, Asset.CASH))
                    .isEqualTo(14_226);   // 12,000 + 1,800 + 426
            assertThat(amountOn(buyPosting, AccountCodes.EXCHANGE_FEES, Asset.CASH))
                    .isEqualTo(368);      // 356 + 12
        }

        @Test
        @DisplayName("charges work against the client on a sale too")
        void saleLines() {
            assertThat(amountOn(sellPosting, AccountCodes.clientCash(CLIENT), Asset.CASH))
                    .isEqualTo(3_095_691);  // proceeds less charges, not plus
        }

        @Test
        void sharesMoveBetweenClientAndSuspense() {
            assertThat(amountOn(buyPosting, AccountCodes.clientSecurities(CLIENT), "EQ:TCS"))
                    .isEqualTo(40);
            assertThat(amountOn(buyPosting, AccountCodes.SETTLEMENT_SUSPENSE, "EQ:TCS"))
                    .isEqualTo(-40);
            assertThat(amountOn(sellPosting, AccountCodes.clientSecurities(CLIENT), "EQ:TCS"))
                    .isEqualTo(-10);
        }

        @Test
        @DisplayName("every posting nets to zero for every asset it touches")
        void postingsBalance() {
            // The Posting constructor refuses an unbalanced set, so reaching
            // this point already proves it; asserting it makes the intent plain.
            for (Posting posting : List.of(buyPosting, sellPosting)) {
                Map<String, Long> byAsset = new LinkedHashMap<>();
                posting.lines().forEach(l -> byAsset.merge(l.asset(), l.amount(), Long::sum));
                assertThat(byAsset.values()).allMatch(total -> total == 0L);
            }
        }
    }

    @Test
    @DisplayName("folding both trades over the opening balance reproduces the hand-computed close")
    void closingState() {
        Map<String, Long> ledger = new LinkedHashMap<>();
        post(ledger, AccountCodes.clientCash(CLIENT), Asset.CASH, 50_000_000);
        post(ledger, "HOUSE_CASH", Asset.CASH, -50_000_000);
        apply(ledger, TradePostings.forTrade(buy, schedule.compute(Side.BUY, buy.turnover())));
        apply(ledger, TradePostings.forTrade(sell, schedule.compute(Side.SELL, sell.turnover())));

        assertThat(ledger.get(key(AccountCodes.clientCash(CLIENT), Asset.CASH)))
                .isEqualTo(41_079_097);
        assertThat(ledger.get(key(AccountCodes.clientSecurities(CLIENT), "EQ:TCS")))
                .isEqualTo(30L);
        assertThat(ledger.get(key(AccountCodes.BROKERAGE_INCOME, Asset.CASH)))
                .isEqualTo(2_930L);
        assertThat(ledger.get(key(AccountCodes.TAX_PAYABLE, Asset.CASH)))
                .isEqualTo(17_510L);
        assertThat(ledger.get(key(AccountCodes.EXCHANGE_FEES, Asset.CASH)))
                .isEqualTo(463L);
        assertThat(ledger.get(key(AccountCodes.SETTLEMENT_SUSPENSE, Asset.CASH)))
                .isEqualTo(8_900_000L);

        // Conservation: nothing is created or destroyed, in either asset.
        assertThat(sumOf(ledger, Asset.CASH)).isZero();
        assertThat(sumOf(ledger, "EQ:TCS")).isZero();
    }

    @Test
    @DisplayName("margin on the closing position")
    void marginSnapshot() {
        MarginPolicy.MarginSnapshot snapshot = MarginPolicy.defaults().evaluate(
                Money.ofPaise(41_079_097),
                Map.of("TCS", 30L),
                Map.of("TCS", Money.ofRupees("3050.00")));

        assertThat(snapshot.marketValue()).isEqualTo(Money.ofPaise(9_150_000));
        assertThat(snapshot.equity()).isEqualTo(Money.ofPaise(50_229_097));
        assertThat(snapshot.requirement()).isEqualTo(Money.ofPaise(1_830_000));
        assertThat(snapshot.headroom()).isEqualTo(Money.ofPaise(48_399_097));
        assertThat(snapshot.isSolvent()).isTrue();
    }

    // -- helpers ------------------------------------------------------------

    private static String key(String account, String asset) {
        return account + "|" + asset;
    }

    private static void post(Map<String, Long> ledger, String account, String asset, long amount) {
        ledger.merge(key(account, asset), amount, Long::sum);
    }

    private static void apply(Map<String, Long> ledger, Posting posting) {
        for (PostingLine line : posting.lines()) {
            post(ledger, line.accountCode(), line.asset(), line.amount());
        }
    }

    private static long sumOf(Map<String, Long> ledger, String asset) {
        return ledger.entrySet().stream()
                .filter(e -> e.getKey().endsWith("|" + asset))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    private static long amountOn(Posting posting, String account, String asset) {
        return posting.lines().stream()
                .filter(l -> l.accountCode().equals(account) && l.asset().equals(asset))
                .mapToLong(PostingLine::amount)
                .sum();
    }
}
