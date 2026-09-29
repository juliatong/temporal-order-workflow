package com.example.orders.activities;

import com.example.orders.model.Authorization;
import com.example.orders.model.OrderRequest;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import java.util.Map;

public class PaymentActivitiesImpl implements PaymentActivities {

    private final MockServicesClient client;

    public PaymentActivitiesImpl(MockServicesClient client) {
        this.client = client;
    }

    @Override
    public Authorization authorize(OrderRequest order) {
        var body = client.post("/gateway/authorize", Map.of(
                "orderId", order.orderId(),
                "amountCents", order.totalCents(),
                "cardToken", order.cardToken(),
                "idempotencyKey", idempotencyKey("authorize")));
        if ("DECLINED".equals(body.path("status").asText())) {
            return Authorization.declined(body.get("reason").asText()); // a result the workflow branches on, not a failure
        }
        return new Authorization(body.get("authId").asText(), body.get("holdExpiresAt").asText(), null);
    }

    /**
     * Activities run at least once: a retry after a timeout must not place a second hold.
     * Workflow ID + step stays the same across retries and resets (a run ID would change on reset).
     */
    private static String idempotencyKey(String step) {
        return Activity.getExecutionContext().getInfo().getWorkflowId() + "-" + step;
    }

    @Override
    public void capture(String orderId, Authorization authorization) {
        var body = client.post("/gateway/capture", Map.of("orderId", orderId, "authId", authorization.authId()));
        if ("FAILED".equals(body.path("status").asText())) {
            // Typed, so the workflow's retry policy decides: HOLD_EXPIRED -> HoldExpired, AUTH_VOIDED -> AuthVoided.
            String error = body.get("error").asText();
            throw ApplicationFailure.newFailure("Capture failed: " + error, failureType(error));
        }
    }

    private static String failureType(String gatewayError) {
        return switch (gatewayError) {
            case "HOLD_EXPIRED" -> "HoldExpired";
            case "AUTH_VOIDED" -> "AuthVoided";
            default -> "CaptureFailed";
        };
    }

    @Override
    public void voidAuthorization(String orderId, Authorization authorization) {
        client.post("/gateway/void", Map.of("orderId", orderId, "authId", authorization.authId()));
    }
}
