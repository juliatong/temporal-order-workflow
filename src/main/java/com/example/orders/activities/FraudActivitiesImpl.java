package com.example.orders.activities;

import com.example.orders.model.FraudDecision;
import com.example.orders.model.OrderRequest;
import java.util.Map;

public class FraudActivitiesImpl implements FraudActivities {

    private final MockServicesClient client;

    public FraudActivitiesImpl(MockServicesClient client) {
        this.client = client;
    }

    @Override
    public FraudDecision screen(OrderRequest order) {
        var body = client.post("/fraud/screen", Map.of("orderId", order.orderId(), "totalCents", order.totalCents()));
        return FraudDecision.valueOf(body.get("decision").asText());
    }
}
