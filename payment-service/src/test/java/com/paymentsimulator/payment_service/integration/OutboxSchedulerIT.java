package com.paymentsimulator.payment_service.integration;

import com.jayway.jsonpath.JsonPath;
import com.paymentsimulator.payment_service.config.RabbitMQConfig;
import com.paymentsimulator.payment_service.dto.PaymentRequest;
import com.paymentsimulator.payment_service.dto.PaymentResponse;
import com.paymentsimulator.payment_service.entity.OutboxEvent;
import com.paymentsimulator.payment_service.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End to end with the relay's real @Scheduled polling switched on and nobody triggering it.
 *
 * @DirtiesContext closes this application context when the class finishes. Otherwise its
 * fast-polling relay would stay alive in Spring's context cache and start publishing rows
 * created by other test classes, which expect to control the relay themselves.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "outbox.relay.poll-interval-ms=200",
                "outbox.relay.confirm-timeout-ms=3000"
        })
@DirtiesContext
class OutboxSchedulerIT extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Test
    void event_isPublishedAutomatically_afterThePaymentIsCreated() {
        PaymentResponse payment = paymentService.createPayment(
                new PaymentRequest("key-scheduled", new BigDecimal("10.00"), "USD"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(outboxRepository.findAll()).hasSize(1).first()
                        .extracting(OutboxEvent::getPublishedAt).isNotNull());

        Message message = rabbitTemplate.receive(RabbitMQConfig.QUEUE, 5_000);
        assertThat(message).isNotNull();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        assertThat(JsonPath.<String>read(body, "$.paymentId")).isEqualTo(payment.getId().toString());
    }
}
