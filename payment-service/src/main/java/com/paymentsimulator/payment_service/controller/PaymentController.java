package com.paymentsimulator.payment_service.controller;

import com.paymentsimulator.payment_service.dto.PaymentRequest;
import com.paymentsimulator.payment_service.dto.PaymentResponse;
import com.paymentsimulator.payment_service.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("")
    public ResponseEntity<PaymentResponse> createPayment(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = paymentService.createPayment(request);
        return ResponseEntity.accepted().body(response);
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPaymentById(@PathVariable UUID paymentId) {
        return ResponseEntity.ok(this.paymentService.getPaymentById(paymentId));
    }
}
