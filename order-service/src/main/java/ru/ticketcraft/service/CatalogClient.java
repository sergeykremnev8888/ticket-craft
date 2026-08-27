package ru.ticketcraft.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class CatalogClient {

    private final RestClient restClient;

    public CatalogClient(@Value("${catalog.service.url:http://localhost:8081}") String catalogUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(catalogUrl)
                .build();
    }

    public boolean reserveTicket(Long ticketId) {
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
