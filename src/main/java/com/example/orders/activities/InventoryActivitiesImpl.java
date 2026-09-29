package com.example.orders.activities;

import com.example.orders.model.OrderRequest;
import com.example.orders.model.Reservation;
import java.time.Duration;
import java.util.Map;

public class InventoryActivitiesImpl implements InventoryActivities {

    private final MockServicesClient client;
    private final Duration releaseDelay;

    public InventoryActivitiesImpl(MockServicesClient client) {
        this(client, Duration.ZERO);
    }

    /** {@code releaseDelay} opens a window to kill the worker mid-compensation (demo only). */
    public InventoryActivitiesImpl(MockServicesClient client, Duration releaseDelay) {
        this.client = client;
        this.releaseDelay = releaseDelay;
    }

    @Override
    public Reservation reserve(OrderRequest order) {
        var body = client.post("/inventory/reserve", Map.of("orderId", order.orderId(), "items", order.items()));
        return new Reservation(body.get("reservationId").asText());
    }

    @Override
    public void release(String orderId, Reservation reservation) {
        DemoDelay.pause(releaseDelay);
        client.post("/inventory/release", Map.of("orderId", orderId, "reservationId", reservation.reservationId()));
    }
}
