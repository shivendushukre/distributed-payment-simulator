package com.paymentsimulator.processor_service.service;

import com.paymentsimulator.processor_service.Constants;
import com.paymentsimulator.processor_service.dto.PaymentEvent;
import com.paymentsimulator.processor_service.entity.Payment;
import com.paymentsimulator.processor_service.repository.PaymentServiceRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PaymentEventConsumer{

    private PaymentServiceRepository paymentServiceRepository;

    public PaymentEventConsumer(PaymentServiceRepository paymentServiceRepository) {
        this.paymentServiceRepository = paymentServiceRepository;
    }

    @RabbitListener(queues = "payment.created.queue")
    public void processPayment(PaymentEvent paymentEvent) {
        System.out.println("Received payment " + paymentEvent.getPaymentId());

        UUID paymentID = UUID.fromString(paymentEvent.getPaymentId());

        Payment payment = paymentServiceRepository.findById(paymentID).orElseThrow();

        // already processed
        if (!Constants.PAYMENT_INITIATED_STATUS.equals(payment.getStatus())) {
            return;
        }

        // simulate payment processing
        boolean success = Math.random() > 0.2;

        if(success) {
            payment.setStatus(Constants.PAYMENT_SUCCESS_STATUS);
        } else {
            payment.setStatus(Constants.PAYMENT_FAILURE_STATUS);
            payment.setFailureReason("Payment rejected by Bank");
        }

        payment.setUpdatedAt(LocalDateTime.now());

        paymentServiceRepository.save(payment);

    }

}
