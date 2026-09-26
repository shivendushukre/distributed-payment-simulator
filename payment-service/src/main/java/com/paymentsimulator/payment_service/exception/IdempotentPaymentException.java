package com.paymentsimulator.payment_service.exception;

import com.paymentsimulator.payment_service.entity.Payment;
import lombok.Getter;

@Getter
public class IdempotentPaymentException extends RuntimeException {

  private final Payment payment;

  public IdempotentPaymentException(Payment payment) {
    super("Idempotent replay");
    this.payment = payment;
  }

}
