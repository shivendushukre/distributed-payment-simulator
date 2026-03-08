Design and implement a distributed payment processing system that:

Accepts payment requests via REST API

Guarantees idempotent processing

Processes payments asynchronously

Retries transient failures with exponential backoff

Moves permanently failing payments to a Dead Letter Queue (DLQ)

Maintains consistent and safe payment state transitions

Exposes observability metrics for monitoring and debugging

The system must ensure that duplicate requests do not result in duplicate financial processing, even under concurrent access and failure scenarios.

State Transitions

INITIATED → PROCESSING → SUCCESS
                     ↘
                      FAILED
					  
					  
					  
CREATE TABLE payments (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INT DEFAULT 0,
    failure_reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT unique_idempotency_key UNIQUE (idempotency_key)
);