package com.paymentsimulator.processor_service.repository;

import com.paymentsimulator.processor_service.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaymentServiceRepository extends JpaRepository<Payment, UUID> {
}
