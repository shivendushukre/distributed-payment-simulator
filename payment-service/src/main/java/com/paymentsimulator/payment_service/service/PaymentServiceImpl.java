package com.paymentsimulator.payment_service.service;

import com.paymentsimulator.payment_service.dto.PaymentEvent;
import com.paymentsimulator.payment_service.dto.PaymentRequest;
import com.paymentsimulator.payment_service.dto.PaymentResponse;
import com.paymentsimulator.payment_service.entity.Payment;
import com.paymentsimulator.payment_service.repository.PaymentServiceRepository;
import jakarta.transaction.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PaymentServiceImpl implements PaymentService {

    private PaymentServiceRepository paymentServiceRepository;

    private PaymentEventPublisher paymentEventPublisher;

    public PaymentServiceImpl(PaymentServiceRepository paymentServiceRepository, PaymentEventPublisher paymentEventPublisher) {
        this.paymentServiceRepository = paymentServiceRepository;
        this.paymentEventPublisher = paymentEventPublisher;
    }


    @Override
    @Transactional
    public PaymentResponse createPayment(PaymentRequest paymentRequest) {
        try {
            Payment payment = new Payment();
            payment.setId(UUID.randomUUID());
            payment.setIdempotencyKey(paymentRequest.getIdempotencyKey());
            payment.setAmount(paymentRequest.getAmount());
            payment.setCurrency(paymentRequest.getCurrency());
            payment.setStatus("INITIATED");
            payment.setRetryCount(0);
            payment.setCreatedAt(LocalDateTime.now());
            payment.setUpdatedAt(LocalDateTime.now());

            Payment saved = paymentServiceRepository.save(payment);

            // publish event to RabbitMQ
            PaymentEvent event = new PaymentEvent(
                    saved.getId().toString(),
                    saved.getIdempotencyKey(),
                    saved.getAmount(),
                    saved.getCurrency()
            );

            paymentEventPublisher.publishCreatedPayment(event);

            return mapToResponse(saved);
        } catch (DataIntegrityViolationException e) {

            // Idempotency hit → fetch existing record
            Payment existingPayment = paymentServiceRepository.findByIdempotencyKey(paymentRequest.getIdempotencyKey()).orElseThrow(
                    () -> new RuntimeException("Payment exists but could not be retrieved")
            );
            return mapToResponse(existingPayment);
        }

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
