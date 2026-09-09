package ru.ticketcraft.dto;

/**
 * Жизненный цикл заказа в системе TicketCraft.
 */
public enum OrderState {

    /**
     * Заказ создан.
     */
    CREATED,

    /**
     * Билеты успешно зарезервированы.
     */
    TICKETS_RESERVED,

    /**
     * Ожидание результата оплаты.
     */
    PAYMENT_PENDING,

    /**
     * Оплата завершилась ошибкой.
     */
    PAYMENT_FAILED,

    /**
     * Заказ подтверждён после успешной оплаты.
     */
    CONFIRMED,

    /**
     * Заказ отменён.
     */
    CANCELED,

    /**
     * Билеты доставлены клиенту.
     */
    DELIVERED
}