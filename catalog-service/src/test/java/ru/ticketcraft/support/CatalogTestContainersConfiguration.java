package ru.ticketcraft.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class CatalogTestContainersConfiguration {

    @Bean
    @ServiceConnection
    @SuppressWarnings("resource")
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("catalog_db").withUsername("postgres")
                .withPassword("postgres");
    }

    @Bean
    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    }
}