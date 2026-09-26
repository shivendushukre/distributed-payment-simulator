package com.paymentsimulator.processor_service;

import com.paymentsimulator.processor_service.config.EnvironmentValidator;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableRabbit
public class ProcessorServiceApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(ProcessorServiceApplication.class);

		// Validates required env vars before the context even starts.
		// Fails fast with a clear message instead of a cryptic pool/connection error.
		app.addListeners(new EnvironmentValidator());
		app.run(args);
	}

}
