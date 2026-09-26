package com.paymentsimulator.payment_service.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Validates that all required environment variables are present before the
 * application context starts. This gives a clear, actionable error message
 * instead of a cryptic NPE or HikariPool failure deep in startup.
 *
 * Skipped for the "local" and "test" profiles, which use
 * application-local.yaml / application-test.yaml with concrete values.
 */
public class EnvironmentValidator implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger logger = LoggerFactory.getLogger(EnvironmentValidator.class);

    private static final List<String> REQUIRED_VARS = List.of(
            "DB_URL",
            "DB_USERNAME",
            "DB_PASSWORD",
            "RABBITMQ_HOST",
            "RABBITMQ_USERNAME",
            "RABBITMQ_PASSWORD"
    );

    // Profiles where concrete values come from YAML — no env vars needed
    private static final List<String> EXEMPT_PROFILES = List.of("local", "test");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment env = event.getEnvironment();
        List<String> activeProfiles = Arrays.asList(env.getActiveProfiles());

        boolean isExempt = activeProfiles.stream().anyMatch(EXEMPT_PROFILES::contains);
        if (isExempt) {
            logger.info("[EnvironmentValidator] Profile {} is exempt from env-var check — using YAML values",
                    activeProfiles);
            return;
        }

        List<String> missing = new ArrayList<>();
        for (String var : REQUIRED_VARS) {
            String value = System.getenv(var);
            if (value == null || value.isBlank()) {
                missing.add(var);
            }
        }

        if (!missing.isEmpty()) {
            String message = """
                    ╔══════════════════════════════════════════════════════════════╗
                    ║  payment-service failed to start: missing environment vars  ║
                    ╠══════════════════════════════════════════════════════════════╣
                    ║  The following required variables are not set:              ║
                    ║                                                              ║
                    %s
                    ║                                                              ║
                    ║  See .env.example in the project root for the full list.    ║
                    ║  For local dev, run with: -Dspring.profiles.active=local    ║
                    ╚══════════════════════════════════════════════════════════════╝
                    """.formatted(missing.stream()
                    .map(v -> "║    ✗  " + v)
                    .reduce("", (a, b) -> a + b + "\n"));

            throw new IllegalStateException(message);
        }

        logger.info("[EnvironmentValidator] All required environment variables are present.");
    }
}