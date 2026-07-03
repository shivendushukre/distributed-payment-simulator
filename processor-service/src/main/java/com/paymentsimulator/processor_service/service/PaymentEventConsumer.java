package com.paymentsimulator.processor_service.service;

import com.paymentsimulator.processor_service.Constants;
import com.paymentsimulator.processor_service.config.RabbitMQConfig;
import com.paymentsimulator.processor_service.dto.PaymentEvent;
import com.paymentsimulator.processor_service.entity.Payment;
import com.paymentsimulator.processor_service.repository.PaymentServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PaymentEventConsumer{

    private static final Logger logger = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final PaymentServiceRepository paymentServiceRepository;
    private final RabbitTemplate rabbitTemplate;

    public PaymentEventConsumer(PaymentServiceRepository paymentServiceRepository, RabbitTemplate rabbitTemplate) {
        this.paymentServiceRepository = paymentServiceRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void processPayment(PaymentEvent paymentEvent, Message message) {
        String paymentId = paymentEvent.getPaymentId();
        logger.info("Received payment with ID {}", paymentId);

        try {
            handlePayment(paymentEvent);
        } catch (Exception e) {
            logger.error("Failed to process payment with ID {} : {}", paymentId, e.getMessage());
            handleRetryOrDlq(message, paymentEvent, e);
        }
    }

    private void handlePayment(PaymentEvent paymentEvent) {
        UUID paymentID = UUID.fromString(paymentEvent.getPaymentId());

        Payment payment = paymentServiceRepository.findById(paymentID).orElseThrow( () ->
                new IllegalArgumentException(
                        "Payment not found: " + paymentEvent.getPaymentId()
                ));

        // already processed
        if (!Constants.PAYMENT_INITIATED_STATUS.equals(payment.getStatus())) {
            logger.info("Payment {} already processed, skipping...", paymentEvent.getPaymentId());
            return;
        }

        // simulate payment processing
        boolean success = Math.random() > 0.2;

        if(success) {
            payment.setStatus(Constants.PAYMENT_SUCCESS_STATUS);
        } else {
            // Simulate a transient failure to trigger retry logic
            throw new RuntimeException("Payment rejected by Bank");
        }

        payment.setUpdatedAt(LocalDateTime.now());
        paymentServiceRepository.save(payment);
        logger.info("Payment {} processed successfully.", paymentEvent.getPaymentId());
    }

    private void handleRetryOrDlq(Message message, PaymentEvent paymentEvent, Exception e) {
        int retryCount = getRetryCount(message);

        if(retryCount < RabbitMQConfig.MAX_RETRIES) {
            int nextRetry = retryCount + 1;
            logger.warn("Retrying payment {} - attempt {}/{}", paymentEvent.getPaymentId(), nextRetry, RabbitMQConfig.MAX_RETRIES);

            // update status in DB to retrying
            updatePaymentStatus(paymentEvent.getPaymentId(), Constants.PAYMENT_RETRYING_STATUS, null);

            // forward to retry queue with incremented retry count
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE,
                    RabbitMQConfig.RETRY_ROUTING,
                    paymentEvent,
                    msg -> {
                        msg.getMessageProperties().getHeaders().put("x-retry-count", nextRetry);
                        return msg;
                    }
            );
        } else {
            logger.error("Payment {} exhausted retries, sending to DLQ", paymentEvent.getPaymentId());

            // mark as permanently failed in DB
            updatePaymentStatus(paymentEvent.getPaymentId(), Constants.PAYMENT_FAILURE_STATUS, e.getMessage());

            // send to DLQ
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE,
                    RabbitMQConfig.DLQ_ROUTING,
                    paymentEvent
            );
        }
    }

    private int getRetryCount(Message message) {
        Object retryHeader = message.getMessageProperties().getHeaders().get("x-retry-count");
        if (retryHeader instanceof Integer count) return count;
        return 0;
    }

    private void updatePaymentStatus(String paymentId, String status, String failMsg) {
        try {
            UUID id = UUID.fromString(paymentId);

            paymentServiceRepository.findById(id).ifPresent(payment -> {
                payment.setStatus(status);
                if (failMsg != null) {
                    payment.setFailureReason(failMsg);
                }
                payment.setUpdatedAt(LocalDateTime.now());
                paymentServiceRepository.save(payment);
            });
        } catch (Exception e) {
            logger.error("Failed to update status of payment {}: {}", paymentId, e.getMessage());
        }
    }

}
