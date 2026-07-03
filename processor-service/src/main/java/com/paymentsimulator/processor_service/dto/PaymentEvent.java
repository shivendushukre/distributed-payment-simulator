package com.paymentsimulator.processor_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PaymentEvent {

    private String paymentId;
    private String idempotencyKey;
    private BigDecimal amount;
    private String currency;
}
