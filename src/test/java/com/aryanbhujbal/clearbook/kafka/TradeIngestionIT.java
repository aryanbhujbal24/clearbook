package com.aryanbhujbal.clearbook.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.aryanbhujbal.clearbook.domain.AccountCodes;
import com.aryanbhujbal.clearbook.domain.Asset;
import com.aryanbhujbal.clearbook.domain.Money;
import com.aryanbhujbal.clearbook.ledger.AccountRepository;
import com.aryanbhujbal.clearbook.ledger.BalanceService;
import com.aryanbhujbal.clearbook.support.LedgerFixture;
import com.aryanbhujbal.clearbook.support.PostgresIT;

/**
 * Settling fills that arrive over Kafka, against a real broker.
 *
 * <p>A real broker rather than a mocked listener, because the behaviour worth
 * testing is what happens around the listener rather than inside it:
 * serialisation of the message, redelivery of a record whose offset never
 * committed, and the error handler's decision between retrying and
 * dead-lettering. None of those exist in a unit test that calls the listener
 * method directly.
 */
class TradeIngestionIT extends PostgresIT {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);
    private static final String TOPIC = "fills.inbound";

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, Object> kafka;
    @Autowired
    private LedgerFixture fixture;
    @Autowired
    private BalanceService balances;
    @Autowired
    private AccountRepository accounts;

    private String client;

    @BeforeEach
    void openAccounts() {
        client = "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        fixture.openAndFund(client, Money.ofRupees(500_000), DAY);
        fixture.mark("TCS", DAY, Money.ofRupees("3050.00"));
    }

    @Test
    @DisplayName("a fill on the topic settles into the ledger")
    void fillsSettle() {
        kafka.send(TOPIC, client, new TradeMessage(
                "T-" + client + "-k1", client, "TCS", "BUY", 40, "3000.00", DAY));

        Long cashId = accountId(AccountCodes.clientCash(client));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(balances.balance(cashId, Asset.CASH))
                        .isEqualTo(Money.ofPaise(50_000_000 - 12_016_594)));
    }

    @Test
    @DisplayName("the same fill delivered twice is charged once")
    void redeliveryIsIdempotent() {
        TradeMessage fill = new TradeMessage(
                "T-" + client + "-k2", client, "TCS", "BUY", 10, "3000.00", DAY);

        // Exactly what an uncommitted offset produces after a consumer
        // restart: the identical record, again.
        kafka.send(TOPIC, client, fill);
        kafka.send(TOPIC, client, fill);
        kafka.send(TOPIC, client, fill);

        Long cashId = accountId(AccountCodes.clientCash(client));
        Money chargedOnce = Money.ofPaise(50_000_000 - 3_000_000 - 4_621);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(balances.balance(cashId, Asset.CASH))
                        .isEqualTo(chargedOnce));

        // And it stays that way: the later deliveries must not drift it.
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(balances.balance(cashId, Asset.CASH))
                        .isEqualTo(chargedOnce));
        assertThat(balances.assetsOutOfBalance()).isEmpty();
    }

    @Test
    @DisplayName("a malformed fill is dead-lettered instead of blocking the partition")
    void poisonMessagesDoNotStallTheConsumer() {
        // A side nobody can parse. Retrying it would fail identically forever
        // while every well-formed fill behind it waits.
        kafka.send(TOPIC, client, new TradeMessage(
                "T-" + client + "-bad", client, "TCS", "SIDEWAYS", 5, "3000.00", DAY));

        // The fill queued behind it must still settle.
        kafka.send(TOPIC, client, new TradeMessage(
                "T-" + client + "-good", client, "TCS", "BUY", 10, "3000.00", DAY));

        Long cashId = accountId(AccountCodes.clientCash(client));

        await().atMost(Duration.ofSeconds(45))
                .untilAsserted(() -> assertThat(balances.balance(cashId, Asset.CASH))
                        .as("the good fill must get through despite the poison one")
                        .isEqualTo(Money.ofPaise(50_000_000 - 3_000_000 - 4_621)));
    }

    private Long accountId(String code) {
        return accounts.findByCode(code).orElseThrow().getId();
    }
}
