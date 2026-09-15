package ru.ticketcraft.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import ru.ticketcraft.dto.OrderRequest;
import ru.ticketcraft.dto.OrderResponse;
import ru.ticketcraft.model.Order;
import ru.ticketcraft.security.JwtUserIdResolver;
import ru.ticketcraft.service.OrderService;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;
    private final JwtUserIdResolver jwtUserIdResolver;

    public OrderController(OrderService orderService, JwtUserIdResolver jwtUserIdResolver) {
        this.orderService = orderService;
        this.jwtUserIdResolver = jwtUserIdResolver;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody OrderRequest request) {

        Long userId = jwtUserIdResolver.resolve(jwt);
        Order order = orderService.createOrder(idempotencyKey, userId, request.ticketId());

        return ResponseEntity.created(URI.create("/api/v1/orders/" + order.getId())).body(toResponse(order));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable("orderId") Long orderId, @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwtUserIdResolver.resolve(jwt);
        return ResponseEntity.ok(toResponse(orderService.getOrder(orderId, userId)));
    }

    private OrderResponse toResponse(Order order) {
        return new OrderResponse(order.getId(), order.getUserId(), order.getEventId(), order.getTicketId(),
                order.getTotalPrice(), order.getStatus(), order.getCreatedAt());
    }
}
