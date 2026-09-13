package ru.ticketcraft.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import ru.ticketcraft.dto.OrderRequest;
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
    public ResponseEntity<Order> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody OrderRequest request) {

        Long userId = jwtUserIdResolver.resolve(jwt);

        Order order = orderService.createOrder(idempotencyKey, userId, request.eventId(), request.ticketId(),
                request.price());

        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }
}
