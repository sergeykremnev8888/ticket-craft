package ru.ticketcraft.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.dto.OrderState;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public void process(OrderEvent event) {
        if (event.getState() != OrderState.CONFIRMED) {
            throw new IllegalArgumentException("Unsupported order event state: " + event.getState());
        }

        log.info("Order confirmed notification: orderId={}, userId={}, eventId={}, ticketIds={}, totalPrice={}",
                event.getOrderId(), event.getUserId(), event.getEventId(), event.getTicketIds(), event.getTotalPrice());
    }
}
