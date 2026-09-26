package com.paymentsimulator.payment_service;

import com.paymentsimulator.payment_service.config.EnvironmentValidator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentServiceApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(PaymentServiceApplication.class);
		// Validates required env vars before the context even starts.
		// Fails fast with a clear message instead of a cryptic pool/connection error.
		app.addListeners(new EnvironmentValidator());
		app.run(args);
	}

}
