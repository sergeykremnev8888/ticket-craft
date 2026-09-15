package ru.ticketcraft.config;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
@DependsOn("flywayInitializer")
public class LocalDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(LocalDataSeeder.class);

    public LocalDataSeeder(DataSource dataSource) {
        ClassPathResource seed = new ClassPathResource("db/local/seed.sql");

        log.info("Local seed resource exists: {}", seed.exists());

        try (Connection connection = dataSource.getConnection()) {
            log.info("Running local seed against database: {}, schema: {}", connection.getMetaData().getURL(),
                    connection.getSchema());
        } catch (SQLException e) {
            throw new RuntimeException("Unable to execute [%s]".formatted("db/local/seed.sql"));
        }

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(seed);
        populator.execute(dataSource);

        log.info("Local database seed completed");
    }
}