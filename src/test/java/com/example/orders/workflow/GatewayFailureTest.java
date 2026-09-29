package com.example.orders.workflow;

import static com.example.orders.workflow.OrderTestHarness.order;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.orders.model.OrderStatus;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Cut 3: gateway failures — transient ones are retried, unknown outcomes don't duplicate, permanent ones fail fast. */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class GatewayFailureTest {

    private OrderTestHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = new OrderTestHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void aGatewayBlipIsRetriedUntilTheOrderCompletes() {
        harness.control(Map.of("gatewayFailNext", 3));

        OrderStatus result = harness.newWorkflow("blip").placeOrder(order("blip", 250_00, "1 Main St"));

        assertAll(
                () -> assertEquals(OrderStatus.CAPTURED, result),
                () -> assertEquals(List.of("CAPTURED"), harness.downstream("holds")));
    }

    @Test
    void aTimeoutAfterTheGatewayApprovedLeavesOneHold() {
        harness.control(Map.of("authorizeDelayOnceSeconds", 2)); // the client gives up after 1s

        harness.newWorkflow("timeout").placeOrder(order("timeout", 250_00, "1 Main St"));

        assertEquals(List.of("CAPTURED"), harness.downstream("holds"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS) // retrying a permanent error forever shows up as this timeout
    void aPermanentCaptureErrorFailsTheOrderFast() {
        harness.control(Map.of("captureError", "HOLD_EXPIRED"));

        OrderStatus result = harness.newWorkflow("expired").placeOrder(order("expired", 250_00, "1 Main St"));

        assertEquals(OrderStatus.PAYMENT_FAILED, result);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void aPermanentCaptureErrorCancelsTheShipmentAndReleasesTheStock() {
        harness.control(Map.of("captureError", "HOLD_EXPIRED"));

        harness.newWorkflow("expired").placeOrder(order("expired", 250_00, "1 Main St"));

        assertAll(
                () -> assertEquals(List.of("CANCELLED"), harness.downstream("bookings")),
                () -> assertEquals(List.of("RELEASED"), harness.downstream("reservations")));
    }
}
