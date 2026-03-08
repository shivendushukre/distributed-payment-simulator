package com.paymentsimulator.payment_service.service;

import com.paymentsimulator.payment_service.config.RabbitMQConfig;
import com.paymentsimulator.payment_service.dto.PaymentEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class PaymentEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public PaymentEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishCreatedPayment(PaymentEvent event) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.PAYMENT_QUEUE, event);
    }
}
