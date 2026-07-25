package com.paymentsimulator.processor_service.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "payment.exchange";
    public static final String QUEUE = "payment.queue";
    public static final String ROUTING_KEY = "payment.routing";

    public static final String RETRY_QUEUE = "payment.retry.queue";
    public static final String RETRY_ROUTING   = "payment.retry.routing";

    public static final String DLQ             = "payment.dlq";
    public static final String DLQ_ROUTING     = "payment.dlq.routing";

    public static final int    MAX_RETRIES     = 3;
    public static final int    RETRY_DELAY_MS  = 5000; // 5 seconds

    @Bean
    DirectExchange exchange() {
        return new DirectExchange(EXCHANGE);
    }

    // --- Main Queue ---
    // Declares where failed messages go: the retry exchange
    @Bean
    Queue mainQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RETRY_ROUTING)
                .build();
    }

    @Bean
    Binding binding(Queue queue, DirectExchange exchange) {
        return BindingBuilder
                .bind(queue)
                .to(exchange)
                .with(ROUTING_KEY);
    }

    // --- Retry Queue ---
    // TTL causes messages to expire and re-route back to the main queue
    @Bean
    Queue retryQueue() {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-dead-letter-exchange", EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY) // back to main
                .withArgument("x-message-ttl", RETRY_DELAY_MS)          // wait before retry
                .build();
    }

    @Bean
    Binding retryBinding(Queue retryQueue, DirectExchange exchange) {
        return BindingBuilder.bind(retryQueue)
                .to(exchange)
                .with(RETRY_ROUTING);
    }

    // --- Dead Letter Queue ---
    // Terminal queue — messages stay here for manual inspection
    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Binding dlqBinding(Queue deadLetterQueue, DirectExchange exchange) {
        return BindingBuilder.bind(deadLetterQueue).to(exchange).with(DLQ_ROUTING);
    }

    @Bean
    public JacksonJsonMessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
