package com.example.orders.workflow;

import static com.example.orders.workflow.OrderTestHarness.order;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.orders.model.OrderStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Cut 1: a late failure must undo what earlier steps did downstream.
 * A wrongly retried permanent failure would hang, so every test has a timeout.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class CompensationTest {

    private static final String BAD_ADDRESS = "INVALID address";

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
    void carrierRejectingTheAddressFailsTheOrder() {
        OrderStatus result = harness.newWorkflow("bad-address").placeOrder(order("bad-address", 250_00, BAD_ADDRESS));

        assertEquals(OrderStatus.SHIPMENT_FAILED, result);
    }

    @Test
    void carrierRejectingTheAddressVoidsTheCardHold() {
        harness.newWorkflow("bad-address").placeOrder(order("bad-address", 250_00, BAD_ADDRESS));

        assertEquals(List.of("VOIDED"), harness.downstream("holds"));
    }

    @Test
    void carrierRejectingTheAddressReleasesTheStock() {
        harness.newWorkflow("bad-address").placeOrder(order("bad-address", 250_00, BAD_ADDRESS));

        assertEquals(List.of("RELEASED"), harness.downstream("reservations"));
    }

    @Test
    void analystRejectingTheReviewUndoesTheHoldAndTheReservation() {
        OrderWorkflow workflow = harness.newWorkflow("rejected");
        WorkflowClient.start(workflow::placeOrder, order("rejected", 1_500_00, "1 Main St"));
        workflow.rejectReview("card reported stolen");
        WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class);

        assertAll(
                () -> assertEquals(List.of("VOIDED"), harness.downstream("holds")),
                () -> assertEquals(List.of("RELEASED"), harness.downstream("reservations")));
    }
}
