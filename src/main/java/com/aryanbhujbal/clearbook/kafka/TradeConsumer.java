package com.aryanbhujbal.clearbook.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.aryanbhujbal.clearbook.trade.TradeService;
import com.aryanbhujbal.clearbook.trade.TradeService.SettlementOutcome;

/**
 * Settles fills arriving on the exchange topic.
 *
 * <p><b>Why redelivery is safe.</b> Kafka gives at-least-once delivery: a
 * consumer that processes a record and dies before its offset commits will see
 * that record again. This consumer does nothing to prevent that, because
 * preventing it is impossible — instead the work itself is idempotent. The
 * trade id carries a UNIQUE constraint in the journal, so a second delivery
 * finds the entry already there and returns it rather than posting a second
 * one. Duplicate delivery becomes a cheap no-op instead of a double charge.
 *
 * <p><b>Why the topic is partitioned by account.</b> All fills for one client
 * land on one partition and are therefore consumed in order by one consumer.
 * That removes a whole class of interleaving before optimistic locking has to
 * deal with it — the lock is still there for the REST path and for
 * rebalances, but the common case does not contend at all.
 *
 * <p>Nothing here catches exceptions. A failure must propagate so the
 * container's error handler can decide between retry and dead-letter; catching
 * it would commit the offset and lose the record silently, which is the
 * failure mode worth engineering against in a system that moves money.
 */
@Component
public class TradeConsumer {

    private static final Logger log = LoggerFactory.getLogger(TradeConsumer.class);

    private final TradeService trades;

    public TradeConsumer(TradeService trades) {
        this.trades = trades;
    }

    @KafkaListener(
            topics = "${clearbook.kafka.trades-topic:fills.inbound}",
            groupId = "${clearbook.kafka.group-id:clearbook-settlement}")
    public void onFill(TradeMessage message) {
        SettlementOutcome outcome = trades.settle(message.toTrade());

        if (outcome.result().alreadyPosted()) {
            log.info("redelivery of trade {} ignored; already entry {}",
                    message.tradeId(), outcome.result().entryId());
        } else {
            log.info("settled trade {} from the feed as entry {}",
                    message.tradeId(), outcome.result().entryId());
        }
    }
}
