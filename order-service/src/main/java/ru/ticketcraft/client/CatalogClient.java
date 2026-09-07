package ru.ticketcraft.client;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import ru.ticketcraft.config.CatalogProperties;

@Component
public class CatalogClient {

    private final RestClient restClient;

    public CatalogClient(CatalogProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.url())
                .build();
    }

    public boolean reserveTicket(UUID ticketId) {
        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri("/api/v1/catalog/tickets/{ticketId}/reserve", ticketId)
                    .retrieve()
                    .toBodilessEntity();
            return response.getStatusCode().is2xxSuccessful();
        } catch (HttpClientErrorException.Conflict e) {
            return false; // Место занято (409 Conflict)
        }
    }
}
