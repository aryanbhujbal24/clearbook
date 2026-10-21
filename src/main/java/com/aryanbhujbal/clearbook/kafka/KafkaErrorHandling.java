package com.aryanbhujbal.clearbook.kafka;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.aryanbhujbal.clearbook.domain.Posting.UnbalancedPostingException;
import com.aryanbhujbal.clearbook.ledger.MarginBreachException;
import com.aryanbhujbal.clearbook.ledger.UnknownAccountException;

/**
 * What happens to a fill that cannot be settled.
 *
 * <p>The distinction that matters is between failures worth retrying and
 * failures that will fail identically forever. Retrying the second kind is not
 * merely wasteful — the consumer stops making progress on the partition while
 * it does, so one malformed message can stall every well-formed message behind
 * it. That is how a single bad record takes a settlement system down.
 *
 * <p>So these go straight to the dead-letter topic on the first failure:
 *
 * <ul>
 *   <li><b>Malformed messages</b> — an unparseable side, a negative quantity,
 *   a sub-paise price. Permanent by definition.</li>
 *   <li><b>Margin breaches</b> — the ledger has decided. Retrying asks the
 *   same question and gets the same answer.</li>
 *   <li><b>Unknown accounts</b> — needs an operator to open the account, which
 *   will not happen within the retry window.</li>
 *   <li><b>Unbalanced postings</b> — a bug in this service. It should be
 *   quarantined loudly, not retried quietly.</li>
 * </ul>
 *
 * <p>Everything else — a dropped connection, a lock timeout, a brief outage —
 * gets two attempts a second apart, then the dead-letter topic. The dead
 * letter is never a discard: the record keeps its key and its headers, so the
 * original partitioning survives and a fixed message can be replayed onto the
 * main topic, where idempotency makes replaying it safe.
 */
@Configuration
public class KafkaErrorHandling {

    private static final long RETRY_INTERVAL_MILLIS = 1_000;
    private static final long RETRY_ATTEMPTS = 2;

    @Bean
    public DefaultErrorHandler tradeErrorHandler(KafkaOperations<String, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                // Keep the partition: ordering per account is a property worth
                // preserving into the dead-letter topic, so a replay does not
                // reorder a client's fills.
                (record, exception) -> new TopicPartition(
                        record.topic() + ".dlq", record.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(RETRY_INTERVAL_MILLIS, RETRY_ATTEMPTS));

        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                MarginBreachException.class,
                UnknownAccountException.class,
                UnbalancedPostingException.class);

        return handler;
    }
}
