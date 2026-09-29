package com.example.orders.activities;

import com.example.orders.model.Authorization;
import com.example.orders.model.OrderRequest;
import io.temporal.activity.ActivityInterface;

/** Hero flow step 4 (authorize) and step 6 (capture, after the shipment is booked — L7). */
@ActivityInterface
public interface PaymentActivities {
    Authorization authorize(OrderRequest order);

    void capture(String orderId, Authorization authorization);

    /** Compensation for {@link #authorize}: release the card hold. Safe to repeat. */
    void voidAuthorization(String orderId, Authorization authorization);
}
