package com.paymentsimulator.processor_service.integration;

import com.jayway.jsonpath.JsonPath;
import com.paymentsimulator.processor_service.Constants;
import com.paymentsimulator.processor_service.config.RabbitMQConfig;
import com.paymentsimulator.processor_service.entity.Payment;
import com.paymentsimulator.processor_service.repository.PaymentServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the real processor-service against real PostgreSQL and RabbitMQ containers.
 *
 * Messages are built exactly the way payment-service's OutboxRelay builds them, including
 * the __TypeId__ header naming payment-service's PaymentEvent class, which does not exist in
 * this module. That makes these tests a contract test for the cross-service message format.
 *
 * Note: the success/failure outcome of processing is random in production code
 * (Math.random() > 0.3), so tests only assert what holds for either outcome.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PaymentProcessingIT {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:15"));
    private static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"));

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");

        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    /**
     * In production payment-service declares the exchange and queues; processor-service only
     * consumes. This copy of the topology lets the processor run on its own in a test. If
     * payment-service's RabbitMQConfig changes, update it here. The retry delay is shortened
     * so the retry/dead-letter tests run in seconds.
     */
    @TestConfiguration
    static class TestTopology {

        @Bean
        DirectExchange paymentExchange() {
            return new DirectExchange(RabbitMQConfig.EXCHANGE);
        }

        @Bean
        Queue paymentQueue() {
            return QueueBuilder.durable(RabbitMQConfig.QUEUE)
                    .withArgument("x-dead-letter-exchange", RabbitMQConfig.EXCHANGE)
                    .withArgument("x-dead-letter-routing-key", RabbitMQConfig.RETRY_ROUTING)
                    .build();
        }

        @Bean
        Binding paymentBinding(@Qualifier("paymentQueue") Queue queue, DirectExchange exchange) {
            return BindingBuilder.bind(queue).to(exchange).with(RabbitMQConfig.ROUTING_KEY);
        }

        @Bean
        Queue paymentRetryQueue() {
            return QueueBuilder.durable(RabbitMQConfig.RETRY_QUEUE)
                    .withArgument("x-dead-letter-exchange", RabbitMQConfig.EXCHANGE)
                    .withArgument("x-dead-letter-routing-key", RabbitMQConfig.ROUTING_KEY)
                    .withArgument("x-message-ttl", 500)
                    .build();
        }

        @Bean
        Binding paymentRetryBinding(@Qualifier("paymentRetryQueue") Queue queue, DirectExchange exchange) {
            return BindingBuilder.bind(queue).to(exchange).with(RabbitMQConfig.RETRY_ROUTING);
        }

        @Bean
        Queue paymentDeadLetterQueue() {
            return QueueBuilder.durable(RabbitMQConfig.DLQ).build();
        }

        @Bean
        Binding paymentDeadLetterBinding(@Qualifier("paymentDeadLetterQueue") Queue queue, DirectExchange exchange) {
            return BindingBuilder.bind(queue).to(exchange).with(RabbitMQConfig.DLQ_ROUTING);
        }
    }

    @Autowired
    private PaymentServiceRepository paymentRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void resetState() {
        rabbitAdmin.purgeQueue(RabbitMQConfig.QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMQConfig.RETRY_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMQConfig.DLQ, false);
        paymentRepository.deleteAll();
    }

    @Test
    void paymentThatExists_reachesATerminalStatus_fromAPaymentServiceFormattedMessage() {
        Payment payment = savePayment(Constants.PAYMENT_INITIATED_STATUS);

        publishLikePaymentService(payment.getId());

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(statusOf(payment.getId()))
                        .isIn(Constants.PAYMENT_SUCCESS_STATUS, Constants.PAYMENT_FAILURE_STATUS));

        Payment done = paymentRepository.findById(payment.getId()).orElseThrow();
        if (Constants.PAYMENT_FAILURE_STATUS.equals(done.getStatus())) {
            // Gave up after every retry: it must be recorded as such and parked on the DLQ.
            assertThat(done.getRetryCount()).isEqualTo(RabbitMQConfig.MAX_RETRIES);
            assertThat(done.getFailureReason()).isNotBlank();
            assertThat(rabbitTemplate.receive(RabbitMQConfig.DLQ, 5_000)).as("message on the DLQ").isNotNull();
        } else {
            assertThat(rabbitTemplate.receive(RabbitMQConfig.DLQ, 500)).as("nothing dead-lettered").isNull();
        }
    }

    @Test
    void paymentThatDoesNotExist_isRetried_thenDeadLettered() {
        UUID unknownPaymentId = UUID.randomUUID();

        publishLikePaymentService(unknownPaymentId);

        Message deadLettered = rabbitTemplate.receive(RabbitMQConfig.DLQ, 30_000);
        assertThat(deadLettered).as("message reaches the DLQ after retries are exhausted").isNotNull();
        assertThat(JsonPath.<String>read(new String(deadLettered.getBody(), StandardCharsets.UTF_8), "$.paymentId"))
                .isEqualTo(unknownPaymentId.toString());
    }

    @Test
    void paymentAlreadyInTerminalStatus_isNotReprocessed() throws Exception {
        Payment payment = savePayment(Constants.PAYMENT_FAILURE_STATUS);
        var before = paymentRepository.findById(payment.getId()).orElseThrow();

        publishLikePaymentService(payment.getId());

        // Wait until the message has been taken off the queue, then give the listener time to finish.
        await().atMost(Duration.ofSeconds(10)).until(() ->
                rabbitAdmin.getQueueInfo(RabbitMQConfig.QUEUE).getMessageCount() == 0);
        Thread.sleep(1_500);

        // Any reprocessing would have changed the row: success -> SUCCESS, failure -> RETRYING.
        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Constants.PAYMENT_FAILURE_STATUS);
        assertThat(after.getRetryCount()).isEqualTo(before.getRetryCount());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(rabbitTemplate.receive(RabbitMQConfig.DLQ, 500)).isNull();
        assertThat(rabbitAdmin.getQueueInfo(RabbitMQConfig.RETRY_QUEUE).getMessageCount()).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private String statusOf(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow().getStatus();
    }

    private Payment savePayment(String status) {
        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setIdempotencyKey("key-" + payment.getId());
        payment.setAmount(new BigDecimal("10.00"));
        payment.setCurrency("USD");
        payment.setStatus(status);
        payment.setRetryCount(0);
        return paymentRepository.save(payment);
    }

    /** Mirrors payment-service's OutboxRelay.buildMessage(...). Keep the two in sync. */
    private void publishLikePaymentService(UUID paymentId) {
        String json = """
                {"paymentId":"%s","idempotencyKey":"key-%s","amount":10.00,"currency":"USD"}"""
                .formatted(paymentId, paymentId);

        MessageProperties properties = new MessageProperties();
        properties.setMessageId(UUID.randomUUID().toString());
        properties.setType("PAYMENT_CREATED");
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        // payment-service's class name; there is no such class on this module's classpath.
        properties.setHeader("__TypeId__", "com.paymentsimulator.payment_service.dto.PaymentEvent");

        rabbitTemplate.send(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY,
                new Message(json.getBytes(StandardCharsets.UTF_8), properties));
    }
}
