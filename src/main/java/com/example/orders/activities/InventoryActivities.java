package com.example.orders.activities;

import com.example.orders.model.OrderRequest;
import com.example.orders.model.Reservation;
import io.temporal.activity.ActivityInterface;

/** Hero flow step 3. */
@ActivityInterface
public interface InventoryActivities {
    Reservation reserve(OrderRequest order);

    /** Compensation for {@link #reserve}: put the stock back on sale. Safe to repeat. */
    void release(String orderId, Reservation reservation);
}
