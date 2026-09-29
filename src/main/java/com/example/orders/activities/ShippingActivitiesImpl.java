package com.example.orders.activities;

import com.example.orders.model.Booking;
import com.example.orders.model.OrderRequest;
import io.temporal.failure.ApplicationFailure;
import java.time.Duration;
import java.util.Map;

public class ShippingActivitiesImpl implements ShippingActivities {

    private final MockServicesClient client;
    private final Duration bookDelay;

    public ShippingActivitiesImpl(MockServicesClient client) {
        this(client, Duration.ZERO);
    }

    /** {@code bookDelay} pauses before the carrier call, opening a window to kill the worker mid-order (demo only). */
    public ShippingActivitiesImpl(MockServicesClient client, Duration bookDelay) {
        this.client = client;
        this.bookDelay = bookDelay;
    }

    @Override
    public Booking book(OrderRequest order) {
        DemoDelay.pause(bookDelay);
        var body = client.post("/carrier/book", Map.of("orderId", order.orderId(), "address", order.address()));
        if ("REJECTED".equals(body.get("status").asText())) {
            // Typed; the workflow's retry policy lists ShipmentRejected as never retried (the same address fails again).
            throw ApplicationFailure.newFailure(
                    "Carrier rejected the booking: " + body.get("reason").asText(), "ShipmentRejected");
        }
        return new Booking(body.get("carrierRef").asText());
    }

    @Override
    public void cancelBooking(String orderId, Booking booking) {
        client.post("/carrier/cancel", Map.of("orderId", orderId, "carrierRef", booking.carrierRef()));
    }
}
