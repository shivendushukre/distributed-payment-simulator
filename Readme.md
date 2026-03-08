# 💳 Payment Simulator (Event-Driven Microservices)

A Spring Boot microservices project that simulates a simple **payment
processing system** using **RabbitMQ for asynchronous communication**
between services.

The project demonstrates **event-driven architecture**, where services
communicate through message queues instead of direct synchronous API
calls.

------------------------------------------------------------------------

# 🚀 Features

-   Event-driven microservice communication
-   RabbitMQ message broker integration
-   Producer--Consumer architecture
-   JSON message serialization with Jackson
-   Decoupled services
-   Simple payment processing simulation

------------------------------------------------------------------------

# 🏗 Architecture

Client sends a payment request to **PaymentService**.\
The service publishes a **PaymentEvent** to RabbitMQ.\
RabbitMQ routes the message to a queue which is consumed by
**ProcessorService**.

    Client
      |
      v
    PaymentService (Producer)
      |
      |  PaymentEvent
      v
    RabbitMQ Exchange
      |
      v
    Payment Queue
      |
      v
    ProcessorService (Consumer)
      |
      v
    Payment Processed

------------------------------------------------------------------------

# 🔁 Event Flow

1.  Client sends a payment request to **PaymentService**
2.  PaymentService publishes a **PaymentEvent** to **RabbitMQ Exchange**
3.  RabbitMQ routes the event to **payment.queue**
4.  **ProcessorService** consumes the event
5.  ProcessorService processes the payment

------------------------------------------------------------------------

# 🧩 Microservices

## Payment Service (Producer)

Responsible for: - Accepting payment requests - Creating
`PaymentEvent` - Publishing messages to RabbitMQ

Example Event:

``` json
{
  "paymentId": "12345",
  "amount": 100.00,
  "currency": "USD",
  "status": "INITIATED"
}
```

------------------------------------------------------------------------

## Processor Service (Consumer)

Responsible for: - Listening to `payment.queue` - Processing payment
events - Logging payment results

------------------------------------------------------------------------

# 🛠 Tech Stack

-   Java 21
-   Spring Boot 4.x
-   Spring AMQP
-   RabbitMQ 4.x
-   Jackson
-   Maven

------------------------------------------------------------------------

# 📦 Project Structure

    payment-simulator
    │
    ├── payment-service
    │   ├── controller
    │   ├── entity
    │   ├── repository
    │   ├── service
    │   ├── dto
    │   └── config
    │
    ├── processor-service
    │   ├── dto
    │   ├── config
    │   ├── entity
    │   ├── repository
    │   └── service
    │
    └── README.md

------------------------------------------------------------------------

# ⚙️ Setup & Installation

## 1️⃣ Clone Repository

``` bash
git clone https://github.com/yourusername/payment-simulator.git
cd payment-simulator
```

------------------------------------------------------------------------

## 2️⃣ Start PostgreSQL and RabbitMQ services using Docker compose file

``` bash
docker compose up -d
```

RabbitMQ Dashboard:

http://localhost:15672

------------------------------------------------------------------------

## 3️⃣ Run Services

### Start Payment Service

``` bash
cd payment-service
mvn spring-boot:run
```

### Start Processor Service

``` bash
cd processor-service
mvn spring-boot:run
```

------------------------------------------------------------------------

# 📡 Test the API

Example request:

    POST /payments

Example body:

``` json
{
  "amount": 150,
  "currency": "USD"
}
```

------------------------------------------------------------------------

# ⚠️ Limitations

The following production features are **not implemented yet**:

-   Dead Letter Queue (DLQ)
-   Retry mechanism
-   Exponential backoff retries
-   Idempotent message handling
-   Monitoring & observability
-   Database persistence

These features can be added to improve **reliability and fault
tolerance**.

------------------------------------------------------------------------

# 📈 Future Improvements

-   Implement Retry Queues
-   Add Dead Letter Queue support
-   Add database for payment records
-   Add Docker Compose setup
-   Add integration tests
-   Implement payment status tracking

------------------------------------------------------------------------

# 🧠 Learning Goals

This project demonstrates:

-   Event-driven microservices
-   RabbitMQ messaging concepts
-   Asynchronous processing
-   Spring Boot service communication

------------------------------------------------------------------------

⭐ If you found this project helpful, consider giving it a star!
