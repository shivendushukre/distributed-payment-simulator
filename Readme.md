# 💳 Payment Simulator (Event-Driven Microservices)

A Spring Boot microservices project that simulates a **payment processing system** using **RabbitMQ for asynchronous communication** between services.

The project demonstrates **event-driven architecture**, where services communicate through message queues instead of direct synchronous API calls.

---

## 🚀 Features

- Event-driven microservice communication via RabbitMQ
- Dead Letter Queue (DLQ) for permanently failed payments
- Retry queue with configurable delay (TTL-based) and max retry limit
- Idempotent payment creation (duplicate requests return the existing payment)
- Payment status tracking (`INITIATED` → `RETRYING` → `SUCCESS` / `FAILED`)
- Database persistence via PostgreSQL (both services share one DB)
- Input validation with Jakarta Bean Validation
- Global exception handling with structured error responses
- JSON message serialization with Jackson

---

## 🏗 Architecture

```
Client
  |
  v
PaymentService (Producer) — port 8083
  |  saves payment (status: INITIATED)
  |  publishes PaymentEvent
  v
payment.exchange (DirectExchange)
  |
  v
payment.queue
  |
  v
ProcessorService (Consumer) — port 8081
  |
  |── success  → status: SUCCESS
  |
  └── failure  → payment.retry.queue (TTL: 5s)
                    |
                    └── back to payment.queue (up to 3 retries)
                              |
                              └── retries exhausted → payment.dlq
                                                        status: FAILED
```

---

## 🔁 Event Flow

1. Client sends a `POST /payments` request to **PaymentService**
2. PaymentService validates the request and saves the payment to PostgreSQL with status `INITIATED`
3. PaymentService publishes a **PaymentEvent** to `payment.exchange`
4. RabbitMQ routes the event to `payment.queue`
5. **ProcessorService** consumes the event and attempts processing (70% simulated success rate)
6. On **success** → payment status updated to `SUCCESS`
7. On **failure** → payment forwarded to `payment.retry.queue` with a 5-second TTL; retry count incremented, status set to `RETRYING`
8. After **3 failed retries** → payment forwarded to `payment.dlq`, status set to `FAILED` with failure reason stored

---

## 📡 API

### Create Payment

```
POST /payments
```

**Request body:**

```json
{
  "idempotencyKey": "txn-001",
  "amount": 150.00,
  "currency": "USD"
}
```

**Response:**

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "idempotencyKey": "txn-001",
  "amount": 150.00,
  "currency": "USD",
  "status": "INITIATED",
  "createdAt": "2025-01-01T10:00:00",
  "updatedAt": "2025-01-01T10:00:00"
}
```

Sending the same `idempotencyKey` again returns the existing payment record without creating a duplicate.

### Get Payment by ID

```
GET /payments/{paymentId}
```

Returns the current payment record including its latest status. Returns `404` if not found.

### Validation

All fields are required. Invalid requests return `400` with field-level error details:

| Field            | Constraint                              |
|------------------|-----------------------------------------|
| `idempotencyKey` | Not blank                               |
| `amount`         | Not null, must be ≥ 0.01               |
| `currency`       | Not blank, must be 3-letter ISO code   |

---

## 💳 Payment Statuses

| Status      | Meaning                                          |
|-------------|--------------------------------------------------|
| `INITIATED` | Payment accepted, queued for processing          |
| `RETRYING`  | Processing failed, retry in progress             |
| `SUCCESS`   | Payment processed successfully                   |
| `FAILED`    | All retries exhausted, sent to DLQ               |

---

## 🐇 RabbitMQ Topology

| Resource                | Type           | Purpose                                      |
|-------------------------|----------------|----------------------------------------------|
| `payment.exchange`      | DirectExchange | Routes all messages                          |
| `payment.queue`         | Durable Queue  | Main processing queue; DLX → retry queue     |
| `payment.retry.queue`   | Durable Queue  | Holds failed messages for 5s then re-routes  |
| `payment.dlq`           | Durable Queue  | Terminal queue for permanently failed events |

**Retry configuration:**

- Max retries: **3**
- Retry delay: **5 seconds** (via `x-message-ttl`)
- Retry count tracked via `x-retry-count` message header

---

## 🧩 Microservices

### Payment Service (Producer) — port 8083

- Accepts and validates payment REST requests
- Persists payment to PostgreSQL with status `INITIATED`
- Publishes `PaymentEvent` to RabbitMQ
- Handles idempotency via unique constraint on `idempotency_key`
- Declares the full queue topology (main queue, retry queue, DLQ)

### Processor Service (Consumer) — port 8081

- Listens to `payment.queue`
- Skips payments already in terminal state (`SUCCESS`, `FAILED`) for idempotent consumption
- Simulates processing (70% success, 30% failure)
- On failure: routes to retry queue and updates DB status to `RETRYING`
- After max retries: routes to DLQ and updates DB status to `FAILED` with failure reason

---

## 🛠 Tech Stack

- Java 21
- Spring Boot 4.0.3
- Spring AMQP (RabbitMQ)
- Spring Data JPA
- PostgreSQL 15
- RabbitMQ 4.x
- Jackson
- Lombok
- Jakarta Bean Validation
- Maven 3.9

---

## 📦 Project Structure

```
payment-simulator
│
├── payment-service/                  # Producer (port 8083)
│   └── src/main/java/...
│       ├── controller/               # PaymentController (REST)
│       ├── service/                  # PaymentServiceImpl, PaymentEventPublisher
│       ├── entity/                   # Payment JPA entity
│       ├── repository/               # PaymentServiceRepository
│       ├── dto/                      # PaymentRequest, PaymentResponse, PaymentEvent
│       ├── config/                   # RabbitMQConfig (full topology)
│       ├── exception/                # GlobalExceptionHandler, PaymentNotFoundException
│       └── Constants.java
│
├── processor-service/                # Consumer (port 8081)
│   └── src/main/java/...
│       ├── service/                  # PaymentEventConsumer (listener + retry/DLQ logic)
│       ├── entity/                   # Payment JPA entity
│       ├── repository/               # PaymentServiceRepository
│       ├── dto/                      # PaymentEvent
│       ├── config/                   # RabbitMQConfig (passive queue declaration)
│       └── Constants.java            # Statuses + TERMINAL_STATUSES set
│
├── docker-compose.yml                # PostgreSQL + RabbitMQ
└── README.md
```

---

## ⚙️ Setup & Installation

### 1️⃣ Clone Repository

```bash
git clone https://github.com/yourusername/payment-simulator.git
cd payment-simulator
```

### 2️⃣ Start PostgreSQL and RabbitMQ

```bash
docker compose up -d
```

RabbitMQ Management UI: http://localhost:15672  
Default credentials: `username` / `password`

> **Important:** Start **PaymentService first**. It declares the full RabbitMQ topology (queues, exchange, bindings). ProcessorService uses a passive queue declaration and will fail to start if the queue doesn't exist yet.

### 3️⃣ Run Services

```bash
# Terminal 1 — start payment-service first
cd payment-service
mvn spring-boot:run

# Terminal 2 — start processor-service after
cd processor-service
mvn spring-boot:run
```

---

## 🗄 Database

Both services connect to the same PostgreSQL database (`payments`) and share the `payments` table. Schema management (`ddl-auto: none`) means **you must create the table manually** before running the services:

```sql
CREATE TABLE payments (
    id               UUID PRIMARY KEY,
    idempotency_key  VARCHAR(255) NOT NULL UNIQUE,
    amount           NUMERIC(19, 2) NOT NULL,
    currency         VARCHAR(3) NOT NULL,
    status           VARCHAR(50) NOT NULL,
    retry_count      INTEGER,
    failure_reason   TEXT,
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP NOT NULL
);
```

---

## 📈 Future Improvements

- Exponential backoff for retries
- Per-service databases (bounded context isolation)
- Monitoring & observability (Micrometer, Prometheus, Grafana)
- Integration tests with Testcontainers
- API authentication
- Docker Compose profiles for running all services together

---

⭐ If you found this project helpful, consider giving it a star!