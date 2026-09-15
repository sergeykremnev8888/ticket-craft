package ru.ticketcraft.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Запрос аутентифицированного пользователя на покупку конкретного билета.
 * userId берётся из JWT, а eventId и цена — из catalog-service после успешной
 * reservation, поэтому клиент не может подменить бизнес-данные каталога.
 */
public record OrderRequest(@NotNull UUID ticketId) {
}
