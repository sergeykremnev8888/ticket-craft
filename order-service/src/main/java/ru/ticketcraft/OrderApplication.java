package ru.ticketcraft;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

/**
 * Главный класс для запуска микросервиса обработки заказов (Order Service) в
 * экосистеме TicketCraft.
 */
@SpringBootApplication
@EnableJdbcRepositories(basePackages = "ru.ticketcraft.repository")
public class OrderApplication {

	public static void main(String[] args) {
		// Инициализация и запуск контекста Spring Boot
		SpringApplication.run(OrderApplication.class, args);
	}
}