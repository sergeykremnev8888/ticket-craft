package ru.ticketcraft.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Входящий запрос от аутентифицированного клиента на покупку билета.
 *
 * userId намеренно отсутствует: идентификатор пользователя берётся только из
 * проверенного JWT, чтобы клиент не мог создать заказ от имени другого пользователя.
 */
public record OrderRequest(
        @NotNull
        UUID eventId,

        @NotNull
        UUID ticketId,

        @NotNull
        @Positive
        BigDecimal price) {
}
