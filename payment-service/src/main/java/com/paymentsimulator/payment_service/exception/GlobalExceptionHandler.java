package com.paymentsimulator.payment_service.exception;

import com.paymentsimulator.payment_service.dto.PaymentResponse;
import com.paymentsimulator.payment_service.entity.Payment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler{

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(PaymentNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "Not found",
                "message", exception.getMessage(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "Validation Failed",
                "details", details,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @ExceptionHandler(IdempotentPaymentException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotentRequests(IdempotentPaymentException idempotentPaymentException) {
        Payment p = idempotentPaymentException.getPayment();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Duplicate idempotency key",
                "message", "A payment with this idempotency key already exists",
                "existingPaymentId", p.getId().toString(),
                "status", p.getStatus(),
                "timestamp", LocalDateTime.now().toString()
        ));
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
