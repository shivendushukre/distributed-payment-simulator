package com.paymentsimulator.payment_service.service;

public interface OutboxService {

    void record(String aggregateId, String eventType, Object payload);
}
