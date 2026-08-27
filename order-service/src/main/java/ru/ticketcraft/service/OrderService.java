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
	private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

	// Имя топика Kafka, в который отправляем события
	private static final String TOPIC = "order-events";

	public OrderService(OrderRepository orderRepository, KafkaTemplate<String, OrderEvent> kafkaTemplate) {
		this.orderRepository = orderRepository;
		this.kafkaTemplate = kafkaTemplate;
	}

	/**
	 * Бизнес-метод создания заказа и резервирования места. Аннотация @Transactional
	 * гарантирует, что если внутри метода произойдет сбой, все изменения в БД
	 * (включая блокировку FOR UPDATE) откатятся.
	 */
	@Transactional
	public Order createOrder(Long userId, Long eventId, Long ticketId, BigDecimal price) {

		// ШАГ 1: Пессимистическая блокировка строки билета в БД.
		// Метод выполнит: SELECT is_available FROM tickets WHERE id = ? FOR UPDATE
		Boolean isAvailable = orderRepository.checkAvailabilityAndLock(ticketId);

		// Если билет не найден или уже продан (is_available = false)
		if (isAvailable == null || !isAvailable) {
			throw new IllegalStateException("Извините, данное место уже забронировано или недоступно!");
		}

		// ШАГ 2: Меняем статус билета на "занято" (is_available = false)
		orderRepository.updateTicketStatus(ticketId, false);

		// ШАГ 3: Создаем и сохраняем сам заказ в таблицу orders
		Order order = new Order(null, // ID сгенерируется базой данных (Postgres) автоматически
				userId, eventId, price, OrderState.CREATED, Instant.now());
		Order savedOrder = orderRepository.save(order);

		// ШАГ 4: Формируем иммутабельный OrderEvent для отправки в Kafka
		OrderEvent event = new OrderEvent(
	            UUID.randomUUID().toString(), // messageId
	            savedOrder.getId(),           // orderId
	            savedOrder.getUserId(),         // userId
	            savedOrder.getEventId(),        // eventId
	            List.of(ticketId),            // ticketIds
	            savedOrder.getTotalPrice(),   // totalPrice
	            savedOrder.getStatus(),       // state
	            savedOrder.getCreatedAt()     // createdAt
	        );

		// ШАГ 5: Асинхронно отправляем событие в брокер Kafka.
		// В качестве Message Key передаем userId в виде строки.
		// Это железно гарантирует, что все события данного пользователя попадут в ОДНУ
		// партицию Kafka.
		kafkaTemplate.send(TOPIC, savedOrder.getUserId().toString(), event);

		return savedOrder;
	}
}