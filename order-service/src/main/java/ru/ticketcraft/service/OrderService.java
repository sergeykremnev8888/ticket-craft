package ru.ticketcraft.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.OrderState;
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
	 * Бизнес-метод создания заказа и резервирования места. Аннотация @Transactional
	 * гарантирует, что если внутри метода произойдет сбой, все изменения в БД
	 * (включая блокировку FOR UPDATE) откатятся.
	 */
	@Transactional
	public Order createOrder(Long userId, Long eventId, Long ticketId, BigDecimal price) {
		// 1. Делегируем блокировку и проверку владельцу данных — catalog-service
        boolean reserved = catalogClient.reserveTicket(ticketId);
        if (!reserved) {
            throw new IllegalStateException("Извините, данное место уже забронировано!");
        }
		
        // 2. Сохраняем заказ в локальную базу order_db
        Order order = new Order(null, userId, eventId, ticketId, price, OrderState.CREATED, Instant.now());
        Order savedOrder = orderRepository.save(order);

        // 3. Отправляем событие в Kafka
        OrderEvent event = new OrderEvent(
                UUID.randomUUID().toString(),
                savedOrder.getId(),
                savedOrder.getUserId(),
                savedOrder.getEventId(),
                List.of(ticketId),
                savedOrder.getTotalPrice(),
                savedOrder.getStatus(),
                savedOrder.getCreatedAt()
        );

		// 4: Асинхронно отправляем событие в брокер Kafka.
		// В качестве Message Key передаем userId в виде строки.
		// Это железно гарантирует, что все события данного пользователя попадут в ОДНУ
		// партицию Kafka.
		kafkaTemplate.send(TOPIC, savedOrder.getUserId().toString(), event);

		return savedOrder;
	}
}