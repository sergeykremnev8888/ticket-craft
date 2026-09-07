package ru.ticketcraft;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Главный класс для запуска микросервиса Каталога мероприятий (Catalog Service)
 * в экосистеме TicketCraft.
 */
@SpringBootApplication
@EnableJpaRepositories(basePackages = "ru.ticketcraft.repository")
@EntityScan(basePackages = "ru.ticketcraft.model")
public class CatalogApplication {

    public static void main(String[] args) {
        // Запуск приложения в контейнере Spring Boot
        SpringApplication.run(CatalogApplication.class, args);
    }
}