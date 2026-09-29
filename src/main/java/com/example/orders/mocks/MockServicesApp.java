package com.example.orders.mocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Stand-ins for Inventory, Fraud, the card gateway and the carrier, in their own process so their
 * state survives worker restarts. {@code GET /state} shows what exists downstream (holds, reservations).
 */
public class MockServicesApp {

    public static final int PORT = 8081;
    static final long REVIEW_THRESHOLD_CENTS = 100_000; // orders of $1,000 or more go to an analyst

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, String> reservations = new ConcurrentHashMap<>(); // reservationId -> status
    private final Map<String, String> holds = new ConcurrentHashMap<>();        // authId -> status
    private final Map<String, String> bookings = new ConcurrentHashMap<>();     // carrierRef -> status
    private final Map<String, Map<String, Object>> authorizeResults = new ConcurrentHashMap<>(); // idempotencyKey -> result

    private final AtomicInteger gatewayFailNext = new AtomicInteger(); // failure mode, set via /control
    private final AtomicInteger authorizeDelayOnceSeconds = new AtomicInteger(); // failure mode, set via /control
    private volatile String captureError; // failure mode, set via /control: e.g. HOLD_EXPIRED
    private final AtomicInteger bookDelayOnceMillis = new AtomicInteger(); // failure mode, set via /control
    private final AtomicInteger voidDelayOnceMillis = new AtomicInteger(); // failure mode, set via /control

    private HttpServer server;

    public static void main(String[] args) throws IOException {
        startOn(PORT);
    }

    /** Starts the mock services; port 0 picks a free port (used by tests). */
    public static MockServicesApp startOn(int port) throws IOException {
        MockServicesApp app = new MockServicesApp();
        app.start(port);
        return app;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    private void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool()); // a slow response must not block the retry
        route(server, "/inventory/reserve", this::reserve);
        route(server, "/inventory/release", this::release);
        route(server, "/fraud/screen", this::screen);
        route(server, "/gateway/authorize", this::authorize);
        route(server, "/gateway/capture", this::capture);
        route(server, "/gateway/void", this::voidHold);
        route(server, "/carrier/book", this::book);
        route(server, "/carrier/cancel", this::cancelBooking);
        route(server, "/state", body -> state());
        route(server, "/control", this::control);
        server.start();
        System.out.println("mock-services listening on http://localhost:" + port());
    }

    private Map<String, Object> reserve(JsonNode body) {
        String reservationId = "res-" + UUID.randomUUID();
        reservations.put(reservationId, "RESERVED");
        return Map.of("reservationId", reservationId);
    }

    private Map<String, Object> release(JsonNode body) {
        reservations.put(body.get("reservationId").asText(), "RELEASED"); // idempotent
        return Map.of("status", "RELEASED");
    }

    private Map<String, Object> screen(JsonNode body) {
        boolean highValue = body.get("totalCents").asLong() >= REVIEW_THRESHOLD_CENTS;
        return Map.of("decision", highValue ? "NEEDS_REVIEW" : "AUTO_APPROVED");
    }

    private Map<String, Object> authorize(JsonNode body) {
        gatewayOutage();
        // Like real gateways: a repeated idempotency key returns the original result instead of a new hold.
        String key = body.path("idempotencyKey").asText(null);
        if (key != null && authorizeResults.containsKey(key)) {
            return authorizeResults.get(key);
        }
        if ("tok_declined".equals(body.path("cardToken").asText())) {
            return Map.of("status", "DECLINED", "reason", "insufficient funds"); // a business answer: no hold placed
        }
        String authId = "auth-" + UUID.randomUUID();
        holds.put(authId, "AUTHORIZED");
        String expiresAt = Instant.now().plus(7, ChronoUnit.DAYS).toString();
        Map<String, Object> result = Map.of("status", "APPROVED", "authId", authId, "holdExpiresAt", expiresAt);
        if (key != null) {
            authorizeResults.put(key, result);
        }
        // The hold exists from here on; a slow response makes the caller time out without knowing that.
        sleepSeconds(authorizeDelayOnceSeconds.getAndSet(0));
        return result;
    }

    private Map<String, Object> capture(JsonNode body) {
        gatewayOutage();
        if (captureError != null) {
            return Map.of("status", "FAILED", "error", captureError); // permanent: the hold can't be captured
        }
        holds.put(body.get("authId").asText(), "CAPTURED");
        return Map.of("status", "CAPTURED");
    }

    private Map<String, Object> voidHold(JsonNode body) {
        gatewayOutage();
        sleepMillis(voidDelayOnceMillis.getAndSet(0)); // a slow void keeps a rollback in progress
        holds.put(body.get("authId").asText(), "VOIDED"); // idempotent: voiding twice leaves it voided
        return Map.of("status", "VOIDED");
    }

    private Map<String, Object> book(JsonNode body) {
        sleepMillis(bookDelayOnceMillis.getAndSet(0)); // a slow carrier: the booking is in flight meanwhile
        if (body.get("address").asText().contains("INVALID")) {
            return Map.of("status", "REJECTED", "reason", "invalid address");
        }
        String carrierRef = "ship-" + UUID.randomUUID();
        bookings.put(carrierRef, "BOOKED");
        return Map.of("status", "BOOKED", "carrierRef", carrierRef);
    }

    /**
     * Sets failure modes. {@code gatewayFailNext: N} — the next N gateway calls return 503.
     * {@code authorizeDelayOnceSeconds: S} — the next authorize places the hold, then answers S seconds late.
     * {@code captureError: HOLD_EXPIRED | AUTH_VOIDED} — every capture fails permanently ({@code null} clears).
     * {@code bookDelayOnceMillis: M} — the next booking takes M milliseconds.
     * {@code voidDelayOnceMillis: M} — the next void takes M milliseconds.
     */
    private Map<String, Object> control(JsonNode body) {
        if (body.has("gatewayFailNext")) {
            gatewayFailNext.set(body.get("gatewayFailNext").asInt());
        }
        if (body.has("authorizeDelayOnceSeconds")) {
            authorizeDelayOnceSeconds.set(body.get("authorizeDelayOnceSeconds").asInt());
        }
        if (body.has("voidDelayOnceMillis")) {
            voidDelayOnceMillis.set(body.get("voidDelayOnceMillis").asInt());
        }
        if (body.has("bookDelayOnceMillis")) {
            bookDelayOnceMillis.set(body.get("bookDelayOnceMillis").asInt());
        }
        if (body.has("captureError")) {
            captureError = body.get("captureError").isNull() ? null : body.get("captureError").asText(); // null clears
        }
        return Map.of("status", "OK");
    }

    private static void sleepSeconds(int seconds) {
        sleepMillis(seconds * 1000L);
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void gatewayOutage() {
        if (gatewayFailNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new HttpError(503, "gateway unavailable");
        }
    }

    private Map<String, Object> cancelBooking(JsonNode body) {
        bookings.put(body.get("carrierRef").asText(), "CANCELLED"); // idempotent
        return Map.of("status", "CANCELLED");
    }

    private Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("reservations", reservations);
        state.put("holds", holds);
        state.put("bookings", bookings);
        return state;
    }

    private static void route(HttpServer server, String path, Function<JsonNode, Map<String, Object>> handler) {
        server.createContext(path, exchange -> {
            // Close only after the response is written — including error responses.
            try {
                JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
                respond(exchange, 200, JSON.writeValueAsBytes(handler.apply(body)));
            } catch (HttpError e) {
                respond(exchange, e.status, e.getMessage().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                respond(exchange, 500, e.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        });
    }

    /** A deliberate error response from a mock (e.g., 503 during a simulated outage). */
    private static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static void respond(HttpExchange exchange, int code, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, body.length);
        exchange.getResponseBody().write(body);
    }
}
