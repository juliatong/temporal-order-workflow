package com.example.orders.activities;

import com.example.orders.model.Booking;
import com.example.orders.model.OrderRequest;
import io.temporal.activity.ActivityInterface;

/** Hero flow step 6: book the shipment with the carrier. */
@ActivityInterface
public interface ShippingActivities {
    Booking book(OrderRequest order);

    /** Compensation for {@link #book}: cancel the shipment before it leaves. Safe to repeat. */
    void cancelBooking(String orderId, Booking booking);
}
