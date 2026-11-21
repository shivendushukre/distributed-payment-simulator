package com.paymentsimulator.payment_service.integration;

import com.jayway.jsonpath.JsonPath;
import com.paymentsimulator.payment_service.Constants;
import com.paymentsimulator.payment_service.entity.OutboxEvent;
import com.paymentsimulator.payment_service.entity.Payment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level tests against the running application: security, validation, idempotency,
 * and the guarantee that a payment and its outbox event are written together or not at all.
 */
class PaymentApiIT extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    // ---------------------------------------------------------------- happy path

    @Test
    void createPayment_withValidToken_returns202_andStoresPaymentAndOutboxEventTogether() throws Exception {
        HttpResponse<String> response = post("/payments", paymentJson("key-happy", "100.00", "USD"),
                JwtTestSupport.validToken());

        assertThat(response.statusCode()).isEqualTo(202);
        String paymentId = JsonPath.read(response.body(), "$.id");
        assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo(Constants.INITIATED_STATUS);

        Payment payment = paymentRepository.findById(UUID.fromString(paymentId)).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(Constants.INITIATED_STATUS);
        assertThat(payment.getIdempotencyKey()).isEqualTo("key-happy");

        List<OutboxEvent> events = outboxRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getAggregateId()).isEqualTo(paymentId);
        assertThat(event.getEventType()).isEqualTo(Constants.PAYMENT_CREATED_EVENT);
        assertThat(event.getPublishedAt()).as("not published until the relay runs").isNull();
        assertThat(event.getAttempts()).isZero();
        assertThat(JsonPath.<String>read(event.getPayload(), "$.paymentId")).isEqualTo(paymentId);
        assertThat(JsonPath.<String>read(event.getPayload(), "$.currency")).isEqualTo("USD");
    }

    @Test
    void getPayment_withValidToken_returnsPayment() throws Exception {
        HttpResponse<String> created = post("/payments", paymentJson("key-get", "42.50", "EUR"),
                JwtTestSupport.validToken());
        String paymentId = JsonPath.read(created.body(), "$.id");

        HttpResponse<String> response = get("/payments/" + paymentId, JwtTestSupport.validToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(response.body(), "$.id")).isEqualTo(paymentId);
        assertThat(JsonPath.<String>read(response.body(), "$.currency")).isEqualTo("EUR");
    }

    @Test
    void getPayment_unknownId_returns404() throws Exception {
        HttpResponse<String> response = get("/payments/" + UUID.randomUUID(), JwtTestSupport.validToken());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(response.body(), "$.error")).isEqualTo("Not found");
    }

    // ---------------------------------------------------------------- validation & idempotency

    @Test
    void createPayment_withInvalidBody_returns400_andWritesNothing() throws Exception {
        HttpResponse<String> response = post("/payments", paymentJson("key-bad", "-5", "usd"),
                JwtTestSupport.validToken());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.error")).isEqualTo("Validation Failed");
        assertNothingWritten();
    }

    @Test
    void createPayment_sameIdempotencyKeyTwice_returns409_andCreatesOnlyOnePaymentAndOneEvent() throws Exception {
        HttpResponse<String> first = post("/payments", paymentJson("key-dup", "10.00", "USD"),
                JwtTestSupport.validToken());
        String firstId = JsonPath.read(first.body(), "$.id");

        HttpResponse<String> second = post("/payments", paymentJson("key-dup", "10.00", "USD"),
                JwtTestSupport.validToken());

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(second.body(), "$.existingPaymentId")).isEqualTo(firstId);
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- security

    @Test
    void createPayment_withoutToken_returns401_withJsonBody_andWritesNothing() throws Exception {
        HttpResponse<String> response = post("/payments", paymentJson("key-anon", "10.00", "USD"), null);

        assertUnauthorizedJson(response);
        assertNothingWritten();
    }

    @Test
    void createPayment_withMalformedToken_returns401_withJsonBody() throws Exception {
        HttpResponse<String> response = post("/payments", paymentJson("key-garbage", "10.00", "USD"),
                "this-is-not-a-jwt");

        assertUnauthorizedJson(response);
        assertNothingWritten();
    }

    @Test
    void createPayment_withExpiredToken_returns401_withJsonBody() throws Exception {
        HttpResponse<String> response = post("/payments", paymentJson("key-expired", "10.00", "USD"),
                JwtTestSupport.expiredToken());

        assertUnauthorizedJson(response);
        assertNothingWritten();
    }

    @Test
    void createPayment_withTokenSignedByUntrustedKey_returns401_withJsonBody() throws Exception {
        HttpResponse<String> response = post("/payments", paymentJson("key-forged", "10.00", "USD"),
                JwtTestSupport.tokenSignedWithUntrustedKey());

        assertUnauthorizedJson(response);
        assertNothingWritten();
    }

    @Test
    void createPayment_withTokenForAnotherAudience_returns401_withJsonBody() throws Exception {
        HttpResponse<String> response = post("/payments", paymentJson("key-aud", "10.00", "USD"),
                JwtTestSupport.tokenForAudience("some-other-service"));

        assertUnauthorizedJson(response);
        assertNothingWritten();
    }

    @Test
    void getPayment_withoutToken_returns401() throws Exception {
        HttpResponse<String> response = get("/payments/" + UUID.randomUUID(), null);

        assertUnauthorizedJson(response);
    }

    @Test
    void healthEndpoint_isPublic() throws Exception {
        HttpResponse<String> response = get("/actuator/health", null);

        assertThat(response.statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- atomicity

    @Test
    void whenOutboxInsertFails_thePaymentIsRolledBackToo() throws Exception {
        // Make every NEW row in outbox_events violate a constraint, so the outbox insert
        // fails after the payment insert has already been issued in the same transaction.
        execute("ALTER TABLE outbox_events ADD CONSTRAINT test_reject_inserts CHECK (false) NOT VALID");
        try {
            HttpResponse<String> response = post("/payments", paymentJson("key-atomic", "10.00", "USD"),
                    JwtTestSupport.validToken());

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(paymentRepository.count()).as("payment must not survive without its event").isZero();
            assertThat(outboxRepository.count()).isZero();
        } finally {
            execute("ALTER TABLE outbox_events DROP CONSTRAINT test_reject_inserts");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void assertUnauthorizedJson(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).contains("application/json"));
        // JsonPath throws if the body is not valid JSON, which also guards the entry point's output.
        assertThat(JsonPath.<String>read(response.body(), "$.error")).isEqualTo("Unauthorized");
    }

    private void assertNothingWritten() {
        assertThat(paymentRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
