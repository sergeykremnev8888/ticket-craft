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
import ru.ticketcraft.model.Order;
import ru.ticketcraft.repository.OrderRepository;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CatalogClient catalogClient;
    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    // Имя топика Kafka, в который отправляем события
    private static final String TOPIC = "order-events";

    public OrderService(OrderRepository orderRepository, CatalogClient catalogClient,
            KafkaTemplate<String, OrderEvent> kafkaTemplate) {
        this.orderRepository = orderRepository;
        this.catalogClient = catalogClient;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Создаёт заказ после успешного резервирования билета в catalog-service.
     *
     * Транзакция {@code @Transactional} охватывает только изменения в базе данных
     * order-service. Резервирование билета выполняется в catalog-service
     * в рамках отдельной транзакции.
     */
    @Transactional
    public Order createOrder(Long userId, UUID eventId, UUID ticketId, BigDecimal price) {
        // 1. Делегируем блокировку и проверку владельцу данных — catalog-service
        boolean reserved = catalogClient.reserveTicket(ticketId);
        if (!reserved) {
            throw new OrderConflictException("Ticket is already reserved: " + ticketId);
        }

        // 2. Сохраняем заказ в локальную базу order_db
        Order order = new Order(null, userId, eventId, ticketId, price, OrderState.CREATED, Instant.now());
        Order savedOrder = orderRepository.save(order);

        // 3. Отправляем событие в Kafka
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