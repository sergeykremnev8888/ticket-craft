package ru.ticketcraft.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ru.ticketcraft.dto.OrderEvent;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public void process(OrderEvent event) {
        log.info(
                ">>>> [УВЕДОМЛЕНИЕ] Пользователь №{} успешно забронировал билеты {} "
                        + "на мероприятие {}. Итоговая сумма: {} руб.",
                event.getUserId(), event.getTicketIds(), event.getEventId(), event.getTotalPrice());
    }
}
