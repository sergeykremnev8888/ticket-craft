package ru.ticketcraft.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.ticketcraft.dto.OrderEvent;
import ru.ticketcraft.repository.ProcessedEventRepository;

@Service
public class IdempotentNotificationProcessor {

    private static final Logger log = LoggerFactory.getLogger(IdempotentNotificationProcessor.class);

    private final ProcessedEventRepository processedEventRepository;
    private final NotificationService notificationService;

    public IdempotentNotificationProcessor(ProcessedEventRepository processedEventRepository,
            NotificationService notificationService) {
        this.processedEventRepository = processedEventRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public void process(OrderEvent event) {
        int inserted = processedEventRepository.insertIfAbsent(event.getMessageId());

        if (inserted == 0) {
            log.info("Пропускаем повторное событие. messageId={}", event.getMessageId());
            return;
        }

        notificationService.process(event);

        log.info("Событие обработано. messageId={}", event.getMessageId());
    }
}