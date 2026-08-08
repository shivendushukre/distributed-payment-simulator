package com.paymentsimulator.payment_service.service;

import com.paymentsimulator.payment_service.Constants;
import com.paymentsimulator.payment_service.dto.PaymentEvent;
import com.paymentsimulator.payment_service.dto.PaymentRequest;
import com.paymentsimulator.payment_service.dto.PaymentResponse;
import com.paymentsimulator.payment_service.entity.Payment;
import com.paymentsimulator.payment_service.exception.PaymentNotFoundException;
import com.paymentsimulator.payment_service.repository.PaymentServiceRepository;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PaymentServiceImpl implements PaymentService {

    private final PaymentServiceRepository paymentServiceRepository;

    private final PaymentEventPublisher paymentEventPublisher;

    private static final Logger logger = LoggerFactory.getLogger(PaymentServiceImpl.class);

    public PaymentServiceImpl(PaymentServiceRepository paymentServiceRepository, PaymentEventPublisher paymentEventPublisher) {
        this.paymentServiceRepository = paymentServiceRepository;
        this.paymentEventPublisher = paymentEventPublisher;
    }


    @Override
    @Transactional
    public PaymentResponse createPayment(PaymentRequest paymentRequest) {
        try {
            logger.info("Payment request initiated from payment service");
            Payment payment = new Payment();
            payment.setId(UUID.randomUUID());
            payment.setIdempotencyKey(paymentRequest.getIdempotencyKey());
            payment.setAmount(paymentRequest.getAmount());
            payment.setCurrency(paymentRequest.getCurrency());
            payment.setStatus(Constants.INITIATED_STATUS);
            payment.setRetryCount(0);
            // createdAt and updatedAt are now set automatically by @PrePersist

            Payment saved = paymentServiceRepository.save(payment);

            logger.info("Payment request saved in database with status {}", Constants.INITIATED_STATUS);

            logger.info("Payment publishing to the queue...");

            // publish event to RabbitMQ
            PaymentEvent event = new PaymentEvent(
                    saved.getId().toString(),
                    saved.getIdempotencyKey(),
                    saved.getAmount(),
                    saved.getCurrency()
            );

            paymentEventPublisher.publishCreatedPayment(event);

            logger.info("Payment published to the queue.");

            return mapToResponse(saved);
        } catch (DataIntegrityViolationException e) {
            logger.info("Idempotency key hit...");
            // Idempotency hit → fetch existing record
            Payment existingPayment = paymentServiceRepository.findByIdempotencyKey(paymentRequest.getIdempotencyKey()).orElseThrow(
                    () -> new RuntimeException("Payment exists but could not be retrieved")
            );
            return mapToResponse(existingPayment);
        }

    }

    @Override
    public PaymentResponse getPaymentById(UUID paymentId) {
        Payment payment = this.paymentServiceRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        return mapToResponse(payment);
    }

    private PaymentResponse mapToResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getIdempotencyKey(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
