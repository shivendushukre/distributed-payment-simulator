package com.paymentsimulator.payment_service.integration;

import com.paymentsimulator.payment_service.config.RabbitMQConfig;
import com.paymentsimulator.payment_service.repository.OutboxEventRepository;
import com.paymentsimulator.payment_service.repository.PaymentServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Base class for payment-service integration tests.
 *
 * Starts ONE PostgreSQL and ONE RabbitMQ container for the whole test JVM (singleton
 * pattern: started in a static initializer, reaped by Testcontainers' Ryuk when the JVM
 * exits), and boots the real application against them.
 *
 * The outbox relay is effectively switched off here (1 hour poll interval). Tests call
 * OutboxRelay.relayPendingEvents() themselves so outcomes are deterministic. A subclass
 * that needs different settings must re-declare @SpringBootTest in full.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "outbox.relay.poll-interval-ms=3600000",
                "outbox.relay.confirm-timeout-ms=3000"
        })
abstract class AbstractIntegrationTest {

    // Same images as docker-compose.yml
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:15"));
    protected static final RabbitMQContainer RABBIT =
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
        // The project has no migration tool, so let Hibernate build the schema from the entities.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");

        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);

        // Real Spring Boot resource-server decoder, trusting only the test public key.
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
                JwtTestSupport::publicKeyLocation);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences",
                () -> JwtTestSupport.AUDIENCE);
    }

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected PaymentServiceRepository paymentRepository;

    @Autowired
    protected OutboxEventRepository outboxRepository;

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    @Autowired
    protected RabbitAdmin rabbitAdmin;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void resetState() {
        // Restore topology first: an earlier test may have deleted the exchange or a binding.
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(RabbitMQConfig.QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMQConfig.RETRY_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMQConfig.DLQ, false);

        outboxRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    protected HttpResponse<String> post(String path, String json, String bearerToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> get(String path, String bearerToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static String paymentJson(String idempotencyKey, String amount, String currency) {
        return """
                {"idempotencyKey":"%s","amount":%s,"currency":"%s"}"""
                .formatted(idempotencyKey, amount, currency);
    }
}
