package com.paymentsimulator.payment_service.service;

import com.paymentsimulator.payment_service.config.RabbitMQConfig;
import com.paymentsimulator.payment_service.dto.PaymentEvent;
import com.paymentsimulator.payment_service.entity.OutboxEvent;
import com.paymentsimulator.payment_service.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OutboxRelay {

    private static final Logger logger = LoggerFactory.getLogger(OutboxRelay.class);

    private static final long BASE_BACKOFF_MS = 5_000;
    private static final long MAX_BACKOFF_MS = 300_000;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final long confirmTimeoutMs;
    private final int retentionDays;

    private final Set<String> returnedMessageIds = ConcurrentHashMap.newKeySet();

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       RabbitTemplate rabbitTemplate,
                       PlatformTransactionManager transactionManager,
                       @Value("${outbox.relay.batch-size:50}") int batchSize,
                       @Value("${outbox.relay.confirm-timeout-ms:5000}") long confirmTimeoutMs,
                       @Value("${outbox.cleanup.retention-days:7}") int retentionDays) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.batchSize = batchSize;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.retentionDays = retentionDays;

        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setTimeout(60);

        // With mandatory=true the broker returns unroutable messages and then acks them.
        // Track them so they are not mistaken for successful publishes.
        this.rabbitTemplate.setReturnsCallback(returned -> {
            String messageId = returned.getMessage().getMessageProperties().getMessageId();
            logger.error("Broker returned unroutable message {}: {} {} (exchange={}, routingKey={})",
                    messageId, returned.getReplyCode(), returned.getReplyText(),
                    returned.getExchange(), returned.getRoutingKey());
            if (messageId != null) {
                returnedMessageIds.add(messageId);
            }
        });
    }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:1000}")
    public void relayPendingEvents() {
        try {
            Integer published = transactionTemplate.execute(status -> publishBatch());
            if (published != null && published > 0) {
                logger.info("Outbox relay published {} event(s)", published);
            }
        } catch (Exception e) {
            logger.error("Outbox relay run failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${outbox.cleanup.cron:0 0 3 * * *}")
    public void purgePublishedEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        Integer deleted = transactionTemplate.execute(status ->
                outboxEventRepository.deletePublishedBefore(cutoff));
        logger.info("Outbox cleanup removed {} published event(s) older than {} day(s)", deleted, retentionDays);
    }

    private int publishBatch() {
        List<OutboxEvent> batch = outboxEventRepository.lockNextBatch(LocalDateTime.now(), batchSize);
        int published = 0;

        for (OutboxEvent event : batch) {
            try {
                send(event);
                event.setPublishedAt(LocalDateTime.now());
                published++;
            } catch (Exception e) {
                int attempts = event.getAttempts() + 1;
                event.setAttempts(attempts);
                event.setLastError(truncate(e));
                event.setNextAttemptAt(LocalDateTime.now().plus(backoffMs(attempts), ChronoUnit.MILLIS));
                logger.warn("Outbox event {} (payment {}) not published, attempt {}: {}",
                        event.getId(), event.getAggregateId(), attempts, e.getMessage());
                break; // broker likely down: don't wait out a timeout for every remaining row
            }
        }
        return published;
    }

    private void send(OutboxEvent event) {
        String messageId = event.getId().toString();
        Message message = buildMessage(event, messageId);

        returnedMessageIds.remove(messageId);

        rabbitTemplate.invoke(operations -> {
            operations.send(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, message);
            operations.waitForConfirmsOrDie(confirmTimeoutMs); // throws on nack or timeout
            return null;
        });

        if (returnedMessageIds.remove(messageId)) {
            throw new IllegalStateException(
                    "Broker could not route message " + messageId + " (no queue bound to "
                            + RabbitMQConfig.EXCHANGE + " / " + RabbitMQConfig.ROUTING_KEY + ")");
        }
    }

    private Message buildMessage(OutboxEvent event, String messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        properties.setType(event.getEventType());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("__TypeId__", PaymentEvent.class.getName());
        return new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), properties);
    }

    private static long backoffMs(int attempts) {
        long delay = BASE_BACKOFF_MS << Math.min(attempts - 1, 20);
        return Math.min(delay, MAX_BACKOFF_MS);
    }

    private static String truncate(Exception e) {
        String text = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }
}