package ru.ticketcraft.catalog.controller;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/debug")
public class DebugController {

    @GetMapping("/slow")
    public ResponseEntity<String> slow(@RequestParam(name = "delayMs", defaultValue = "0") long delayMs,
            @RequestParam(name = "status", defaultValue = "200") int status) throws InterruptedException {
        if (delayMs < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "delayMs must be non-negative");
        }
        if (status < 100 || status > 599) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid HTTP status: " + status);
        }

        Thread.sleep(Duration.ofMillis(delayMs));

        return ResponseEntity.status(status).body("Simulated response");
    }
}
