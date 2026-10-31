package com.paymentsimulator.payment_service.service;

import com.paymentsimulator.payment_service.entity.OutboxEvent;
import com.paymentsimulator.payment_service.repository.OutboxEventRepository;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class OutboxServiceImpl implements OutboxService{

    private final OutboxEventRepository outboxEventRepository;
    private final JacksonJsonMessageConverter messageConverter;

    public OutboxServiceImpl(OutboxEventRepository outboxEventRepository,
                                  JacksonJsonMessageConverter messageConverter) {
        this.outboxEventRepository = outboxEventRepository;
        this.messageConverter = messageConverter;
    }

    // MANDATORY: fails fast if called outside a transaction, which would reintroduce
    // the dual-write problem.
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregateId, String eventType, Object payload) {
        Message message = messageConverter.toMessage(payload, new MessageProperties());

        LocalDateTime now = LocalDateTime.now();
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setPayload(new String(message.getBody(), StandardCharsets.UTF_8));
        event.setAttempts(0);
        event.setCreatedAt(now);
        event.setNextAttemptAt(now);

        outboxEventRepository.save(event);
    }
}
