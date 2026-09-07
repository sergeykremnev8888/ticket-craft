package ru.ticketcraft.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.client.CatalogClient;
import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.OrderState;
import ru.ticketcraft.exception.OrderConflictException;
import ru.ticketcraft.idempotency.CanonicalOrderRequest;
import ru.ticketcraft.model.IdempotencyKey;
import ru.ticketcraft.model.IdempotencyStatus;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.OrderRepository;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CatalogClient catalogClient;
    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;
    private final IdempotencyService idempotencyService;
    private final RequestHashService requestHashService;

    // Имя топика Kafka, в который отправляем события
    private static final String TOPIC = "order-events";

    public OrderService(OrderRepository orderRepository, CatalogClient catalogClient,
            KafkaTemplate<String, OrderEvent> kafkaTemplate, IdempotencyService idempotencyService,
            RequestHashService requestHashService) {
        this.orderRepository = orderRepository;
        this.catalogClient = catalogClient;
        this.kafkaTemplate = kafkaTemplate;
        this.idempotencyService = idempotencyService;
        this.requestHashService = requestHashService;
    }

    /**
     * Создаёт заказ после успешного резервирования билета в catalog-service.
     *
     * Транзакция {@code @Transactional} охватывает только изменения в базе данных
     * order-service. Резервирование билета выполняется в catalog-service
     * в рамках отдельной транзакции.
     */
    @Transactional
    public Order createOrder(String idempotencyKey, Long userId, UUID eventId, UUID ticketId, BigDecimal price) {
        CanonicalOrderRequest request = new CanonicalOrderRequest(userId, eventId, ticketId, price);
        String requestHash = requestHashService.hash(request);
        IdempotencyKey key = idempotencyService.checkAndRegister(idempotencyKey, userId, requestHash);

        if (key.getStatus() == IdempotencyStatus.COMPLETED) {
            return orderRepository.findById(key.getOrderId()).orElseThrow(
                    () -> new IllegalStateException("Order not found for idempotency key: " + idempotencyKey));
        }

        boolean reserved = catalogClient.reserveTicket(ticketId);
        if (!reserved) {
            throw new OrderConflictException("Ticket is already reserved: " + ticketId);
        }

        Order order = new Order(null, userId, eventId, ticketId, price, OrderState.CREATED, Instant.now());
        Order savedOrder = orderRepository.save(order);

        idempotencyService.complete(idempotencyKey, savedOrder.getId());

        OrderEvent event = new OrderEvent(UUID.randomUUID().toString(), savedOrder.getId(), savedOrder.getUserId(),
                savedOrder.getEventId(), List.of(ticketId), savedOrder.getTotalPrice(), savedOrder.getStatus(),
                savedOrder.getCreatedAt());

        // 4: Асинхронно отправляем событие в брокер Kafka.
        // В качестве Message Key передаем userId в виде строки.
        // Это железно гарантирует, что все события данного пользователя попадут в ОДНУ
        // партицию Kafka.
        kafkaTemplate.send(TOPIC, savedOrder.getUserId().toString(), event);

        return savedOrder;
    }
}