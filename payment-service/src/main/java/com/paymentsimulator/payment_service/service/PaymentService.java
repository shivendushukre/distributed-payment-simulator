package com.paymentsimulator.payment_service.service;


import com.paymentsimulator.payment_service.dto.PaymentRequest;
import com.paymentsimulator.payment_service.dto.PaymentResponse;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

public interface PaymentService {
    PaymentResponse createPayment(PaymentRequest paymentRequestDTO);

    @Nullable PaymentResponse getPaymentById(UUID paymentId);
}
