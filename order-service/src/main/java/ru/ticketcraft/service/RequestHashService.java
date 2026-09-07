package ru.ticketcraft.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Service;

import ru.ticketcraft.idempotency.CanonicalOrderRequest;

@Service
public class RequestHashService {

    public String hash(CanonicalOrderRequest request) {
        return hash(request.serialize());
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));

            StringBuilder result = new StringBuilder(hash.length * 2);

            for (byte b : hash) {
                result.append(String.format("%02x", b));
            }

            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }
}
