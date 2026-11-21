package com.paymentsimulator.payment_service.integration;

import com.jayway.jsonpath.JsonPath;
import com.paymentsimulator.payment_service.Constants;
import com.paymentsimulator.payment_service.config.RabbitMQConfig;
import com.paymentsimulator.payment_service.dto.PaymentEvent;
import com.paymentsimulator.payment_service.dto.PaymentRequest;
import com.paymentsimulator.payment_service.dto.PaymentResponse;
import com.paymentsimulator.payment_service.entity.OutboxEvent;
import com.paymentsimulator.payment_service.service.OutboxRelay;
import com.paymentsimulator.payment_service.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the outbox relay against a real database and a real broker. The relay's scheduler is
 * effectively disabled (see AbstractIntegrationTest), so each test triggers it explicitly.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "outbox.relay.poll-interval-ms=3600000",
                "outbox.relay.confirm-timeout-ms=3000",
                "outbox.relay.batch-size=5"   // small, so the concurrency test needs several batches
        })
class OutboxRelayIT extends AbstractIntegrationTest {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private PaymentService paymentService;

    // ---------------------------------------------------------------- happy path

    @Test
    void relay_publishesPendingEvent_marksItPublished_andMessageMatchesTheWireFormat() {
        PaymentResponse payment = createPayment("key-1");
        OutboxEvent pending = onlyOutboxEvent();

        relay.relayPendingEvents();

        OutboxEvent published = onlyOutboxEvent();
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getAttempts()).isZero();
        assertThat(published.getLastError()).isNull();

        Message message = receiveOne();
        assertThat(message).as("message on payment.queue").isNotNull();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(pending.getId().toString());
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getType()).isEqualTo(Constants.PAYMENT_CREATED_EVENT);
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getHeaders().get("__TypeId__"))
                .isEqualTo(PaymentEvent.class.getName());

        String body = body(message);
        assertThat(JsonPath.<String>read(body, "$.paymentId")).isEqualTo(payment.getId().toString());
        assertThat(JsonPath.<String>read(body, "$.idempotencyKey")).isEqualTo("key-1");
        assertThat(JsonPath.<String>read(body, "$.currency")).isEqualTo("USD");
    }

    @Test
    void relay_secondRunPublishesNothingNew() {
        createPayment("key-1");

        relay.relayPendingEvents();
        relay.relayPendingEvents();

        assertThat(receiveOne()).isNotNull();
        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUEUE, 500)).as("no duplicate").isNull();
    }

    @Test
    void relay_skipsEventsWhoseRetryTimeHasNotArrived() {
        createPayment("key-1");
        OutboxEvent event = onlyOutboxEvent();
        event.setNextAttemptAt(LocalDateTime.now().plusHours(1));
        outboxRepository.save(event);

        relay.relayPendingEvents();

        assertThat(onlyOutboxEvent().getPublishedAt()).isNull();
        assertThat(onlyOutboxEvent().getAttempts()).isZero();
        assertThat(rabbitTemplate.receive(RabbitMQConfig.QUEUE, 500)).isNull();
    }

    // ---------------------------------------------------------------- failure and recovery

    @Test
    void relay_whenBrokerRejectsTheMessage_keepsEventPending_backsOffExponentially_thenDeliversAfterRecovery() {
        createPayment("key-1");
        // Publishing to an exchange that does not exist makes the broker close the channel.
        rabbitAdmin.deleteExchange(RabbitMQConfig.EXCHANGE);

        relay.relayPendingEvents();

        OutboxEvent afterFirstFailure = onlyOutboxEvent();
        assertThat(afterFirstFailure.getPublishedAt()).isNull();
        assertThat(afterFirstFailure.getAttempts()).isEqualTo(1);
        assertThat(afterFirstFailure.getLastError()).isNotBlank();
        assertThat(delayUntilNextAttempt(afterFirstFailure))
                .isGreaterThan(Duration.ofSeconds(3)).isLessThan(Duration.ofSeconds(6));   // ~5s

        makeDueNow(afterFirstFailure);
        relay.relayPendingEvents();

        OutboxEvent afterSecondFailure = onlyOutboxEvent();
        assertThat(afterSecondFailure.getAttempts()).isEqualTo(2);
        assertThat(delayUntilNextAttempt(afterSecondFailure))
                .isGreaterThan(Duration.ofSeconds(8)).isLessThan(Duration.ofSeconds(11));  // ~10s

        // Broker recovers: topology is back, retry time arrives.
        rabbitAdmin.initialize();
        makeDueNow(afterSecondFailure);
        relay.relayPendingEvents();

        assertThat(onlyOutboxEvent().getPublishedAt()).isNotNull();
        assertThat(receiveOne()).as("event delivered after recovery").isNotNull();
    }

    @Test
    void relay_whenNoQueueIsBound_treatsTheReturnedMessageAsAFailure_notAsSuccess() {
        createPayment("key-1");
        // Exchange still exists, but nothing is bound to the routing key: the broker will
        // return the message (mandatory flag) and then ack it.
        rabbitAdmin.removeBinding(mainQueueBinding());

        relay.relayPendingEvents();

        OutboxEvent event = onlyOutboxEvent();
        assertThat(event.getPublishedAt()).as("an unroutable message must not count as published").isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("could not route");

        // Binding restored: the same event is delivered on the next attempt.
        rabbitAdmin.initialize();
        makeDueNow(event);
        relay.relayPendingEvents();

        assertThat(onlyOutboxEvent().getPublishedAt()).isNotNull();
        assertThat(receiveOne()).isNotNull();
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    void twoRelaysRunningAtOnce_publishEveryEventExactlyOnce() throws Exception {
        int total = 30;
        for (int i = 0; i < total; i++) {
            createPayment("key-" + i);
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) {
                runs.add(pool.submit(() -> {
                    for (int i = 0; i < 15; i++) {
                        relay.relayPendingEvents();
                    }
                }));
            }
            for (Future<?> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outboxRepository.findAll()).hasSize(total)
                .allSatisfy(event -> assertThat(event.getPublishedAt()).isNotNull());

        Set<String> messageIds = new HashSet<>();
        int received = 0;
        Message message;
        while ((message = rabbitTemplate.receive(RabbitMQConfig.QUEUE, 1000)) != null) {
            received++;
            messageIds.add(message.getMessageProperties().getMessageId());
        }
        assertThat(received).as("messages on the queue").isEqualTo(total);
        assertThat(messageIds).as("distinct message ids").hasSize(total);
    }

    // ---------------------------------------------------------------- housekeeping

    @Test
    void purge_deletesOnlyPublishedEventsOlderThanTheRetentionWindow() {
        LocalDateTime now = LocalDateTime.now();
        OutboxEvent oldPublished = outboxEvent(now.minusDays(10), now.minusDays(10));
        OutboxEvent recentPublished = outboxEvent(now.minusDays(1), now.minusDays(1));
        OutboxEvent oldButStillPending = outboxEvent(now.minusDays(10), null);
        outboxRepository.saveAll(List.of(oldPublished, recentPublished, oldButStillPending));

        relay.purgePublishedEvents();   // default retention: 7 days

        assertThat(outboxRepository.findAll()).extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(recentPublished.getId(), oldButStillPending.getId());
    }

    // ---------------------------------------------------------------- helpers

    private PaymentResponse createPayment(String idempotencyKey) {
        return paymentService.createPayment(new PaymentRequest(idempotencyKey, new BigDecimal("25.00"), "USD"));
    }

    private OutboxEvent onlyOutboxEvent() {
        List<OutboxEvent> all = outboxRepository.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private void makeDueNow(OutboxEvent event) {
        event.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        outboxRepository.save(event);
    }

    private static Duration delayUntilNextAttempt(OutboxEvent event) {
        return Duration.between(LocalDateTime.now(), event.getNextAttemptAt());
    }

    private Message receiveOne() {
        return rabbitTemplate.receive(RabbitMQConfig.QUEUE, 5_000);
    }

    private static String body(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    private static Binding mainQueueBinding() {
        return BindingBuilder.bind(new Queue(RabbitMQConfig.QUEUE))
                .to(new DirectExchange(RabbitMQConfig.EXCHANGE))
                .with(RabbitMQConfig.ROUTING_KEY);
    }

    private static OutboxEvent outboxEvent(LocalDateTime createdAt, LocalDateTime publishedAt) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setAggregateId(UUID.randomUUID().toString());
        event.setEventType(Constants.PAYMENT_CREATED_EVENT);
        event.setPayload("{}");
        event.setAttempts(0);
        event.setCreatedAt(createdAt);
        event.setNextAttemptAt(createdAt);
        event.setPublishedAt(publishedAt);
        return event;
    }
}
