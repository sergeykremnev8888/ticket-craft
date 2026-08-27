package ru.ticketcraft;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Главный класс для запуска микросервиса уведомлений (Notification Service)
 * в экосистеме TicketCraft.
 * Работает в чисто фоновом режиме, обрабатывая события из Apache Kafka.
 */
@SpringBootApplication
public class NotificationApplication {

    public static void main(String[] args) {
        // Инициализация и фоновый запуск контекста Spring Boot
        SpringApplication.run(NotificationApplication.class, args);
    }
}
