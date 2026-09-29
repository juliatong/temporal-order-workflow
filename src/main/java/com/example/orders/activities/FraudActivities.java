package com.example.orders.activities;

import com.example.orders.model.FraudDecision;
import com.example.orders.model.OrderRequest;
import io.temporal.activity.ActivityInterface;

/** Hero flow step 5: screen every order. */
@ActivityInterface
public interface FraudActivities {
    FraudDecision screen(OrderRequest order);
}
