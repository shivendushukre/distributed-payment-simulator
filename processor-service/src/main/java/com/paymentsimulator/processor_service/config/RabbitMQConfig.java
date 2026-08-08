package com.paymentsimulator.processor_service.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // Queue and exchange names — must match payment-service exactly
    public static final String EXCHANGE      = "payment.exchange";
    public static final String QUEUE         = "payment.queue";
    public static final String ROUTING_KEY   = "payment.routing";
    public static final String RETRY_QUEUE   = "payment.retry.queue";
    public static final String RETRY_ROUTING = "payment.retry.routing";
    public static final String DLQ           = "payment.dlq";
    public static final String DLQ_ROUTING   = "payment.dlq.routing";

    public static final int MAX_RETRIES    = 3;
    public static final int RETRY_DELAY_MS = 5000;

    // Passive declaration — tells Spring "this queue already exists, don't create it"
    // Fails fast at startup if payment-service hasn't run first
    @Bean
    public Queue mainQueue() {
        Queue queue = new Queue(QUEUE,true);
        queue.setAdminsThatShouldDeclare();
        return queue;
    }

    @Bean
    public JacksonJsonMessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    // Wire the converter into the listener container
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            JacksonJsonMessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        return factory;
    }
}
