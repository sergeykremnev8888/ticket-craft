package ru.ticketcraft.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JacksonConfiguration {

    @Bean
    JsonMapper objectMapper() {
        return JsonMapper.builder().build();
    }

}