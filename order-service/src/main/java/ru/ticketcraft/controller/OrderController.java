package ru.ticketcraft.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.ticketcraft.dto.OrderRequest;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.service.OrderService;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	/**
	 * Эндпоинт для создания нового заказа. Принимает JSON, вызывает транзакционный
	 * сервис с пессимистической блокировкой и возвращает созданный заказ со
	 * статусом 211 (Created).
	 */
	@PostMapping
	public ResponseEntity<Order> create(@RequestBody OrderRequest request) {
		try {
			Order order = orderService.createOrder(request.userId(), request.eventId(), request.ticketId(),
					request.price());
			return ResponseEntity.status(HttpStatus.CREATED).body(order);
		} catch (IllegalStateException e) {
			// Если билет уже забронирован (бизнес-исключение из OrderService)
			return ResponseEntity.status(HttpStatus.CONFLICT).body(null);
		} catch (Exception e) {
			// Общая обработка непредвиденных системных ошибок
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
		}
	}
}
