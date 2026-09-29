package com.example.orders.activities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls the mock-services process over real HTTP, so the stand-ins' state (holds, reservations)
 * survives worker restarts and network timeouts are real.
 */
public class MockServicesClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String baseUrl;
    private final Duration requestTimeout;

    public MockServicesClient(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(5)); // below the activities' 10s start-to-close timeout
    }

    public MockServicesClient(String baseUrl, Duration requestTimeout) {
        this.baseUrl = baseUrl;
        this.requestTimeout = requestTimeout;
    }

    public JsonNode post(String path, Object body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(path + " returned " + response.statusCode() + ": " + response.body());
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            // Includes timeouts: the call may or may not have taken effect downstream.
            throw new IllegalStateException(path + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(path + " interrupted", e);
        }
    }
}
