package com.commercecore.outbox;

import com.commercecore.checkout.OrderPaymentWorkflowConsumer;
import com.commercecore.checkout.OrderWorkflowEventReceipt;
import com.commercecore.checkout.OrderWorkflowEventReceiptRepository;
import com.commercecore.kafka.KafkaEventReceipt;
import com.commercecore.kafka.KafkaEventReceiptRepository;
import com.commercecore.shared.BusinessRuleViolation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only composition of persisted outbox and consumer-receipt evidence. */
@Service
public class EventInspectionService {

    public static final String PROOF_CONSUMER = "commercecore-proof-consumer";

    private final OutboxEventRepository outboxRepository;
    private final KafkaEventReceiptRepository proofReceiptRepository;
    private final OrderWorkflowEventReceiptRepository workflowReceiptRepository;
    private final ObjectMapper objectMapper;

    public EventInspectionService(OutboxEventRepository outboxRepository,
        KafkaEventReceiptRepository proofReceiptRepository,
        OrderWorkflowEventReceiptRepository workflowReceiptRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.proofReceiptRepository = proofReceiptRepository;
        this.workflowReceiptRepository = workflowReceiptRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<EventSummaryResponse> newestEvents() {
        List<OutboxEvent> events = outboxRepository.findTop100ByOrderByCreatedAtDescIdDesc();
        List<UUID> ids = events.stream().map(OutboxEvent::getId).toList();
        Map<UUID, KafkaEventReceipt> proofReceipts = proofReceiptRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(KafkaEventReceipt::getEventId, Function.identity()));
        Map<UUID, OrderWorkflowEventReceipt> workflowReceipts = workflowReceiptRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(OrderWorkflowEventReceipt::getEventId, Function.identity()));

        return events.stream().map(event -> new EventSummaryResponse(
            event.getId(), event.getEventType().name(), event.getAggregateType().name(), event.getAggregateId(),
            relatedOrderId(event), event.getCreatedAt(), event.getPublishedAt() != null, event.getPublishedAt(), event.getAttemptCount(),
            consumerEvidence(event, proofReceipts.get(event.getId()), workflowReceipts.get(event.getId()))
        )).toList();
    }

    @Transactional(readOnly = true)
    public EventInspectionResponse inspect(UUID eventId) {
        OutboxEvent event = outboxRepository.findById(eventId).orElseThrow(() -> new BusinessRuleViolation(
            HttpStatus.NOT_FOUND, "event_not_found",
            "CommerceCore has no persisted inspection evidence for event " + eventId));
        KafkaEventReceipt proofReceipt = proofReceiptRepository.findById(eventId).orElse(null);
        OrderWorkflowEventReceipt workflowReceipt = workflowReceiptRepository.findById(eventId).orElse(null);
        return new EventInspectionResponse(
            event.getId(), event.getEventType().name(), event.getAggregateType().name(), event.getAggregateId(),
            relatedOrderId(event), event.getCreatedAt(), event.getPublishedAt() != null, event.getPublishedAt(), event.getAttemptCount(),
            payload(event), consumerEvidence(event, proofReceipt, workflowReceipt));
    }

    private List<EventInspectionResponse.ConsumerEvidence> consumerEvidence(OutboxEvent event,
        KafkaEventReceipt proofReceipt, OrderWorkflowEventReceipt workflowReceipt) {
        List<EventInspectionResponse.ConsumerEvidence> evidence = new ArrayList<>();
        evidence.add(new EventInspectionResponse.ConsumerEvidence(
            PROOF_CONSUMER, "Technical delivery proof", proofReceipt != null,
            proofReceipt == null ? null : proofReceipt.getConsumedAt(),
            proofReceipt == null ? null : "PERSISTENT_RECEIPT_RECORDED"));
        if (event.getEventType() == OutboxEventType.PAYMENT_AUTHORIZED
            || event.getEventType() == OutboxEventType.PAYMENT_FAILED) {
            evidence.add(new EventInspectionResponse.ConsumerEvidence(
                OrderPaymentWorkflowConsumer.GROUP_ID, "Order and reservation workflow", workflowReceipt != null,
                workflowReceipt == null ? null : workflowReceipt.getProcessedAt(),
                workflowReceipt == null ? null : "WORKFLOW_TRANSACTION_COMMITTED"));
        }
        return List.copyOf(evidence);
    }

    private UUID relatedOrderId(OutboxEvent event) {
        if (event.getAggregateType() == AggregateType.ORDER) {
            return event.getAggregateId();
        }
        JsonNode orderId = payload(event).get("orderId");
        if (orderId == null || !orderId.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(orderId.textValue());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private JsonNode payload(OutboxEvent event) {
        try {
            return objectMapper.readTree(event.getPayload());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Persisted payload is invalid JSON for event " + event.getId(), e);
        }
    }
}
