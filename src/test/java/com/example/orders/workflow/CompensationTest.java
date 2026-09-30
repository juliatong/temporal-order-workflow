package com.example.orders.workflow;

import static com.example.orders.workflow.OrderTestHarness.order;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.orders.model.OrderStatus;
import com.example.orders.workflow.OrderTestHarness.ScheduledActivity;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.util.List;
import java.util.Map;
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
    void anOrderShowsItIsRollingBackWhileTheUndoRuns() throws InterruptedException {
        harness.control(Map.of("voidDelayOnceMillis", 700)); // keeps the rollback in progress
        OrderWorkflow workflow = harness.newWorkflow("bad-address");
        WorkflowClient.start(workflow::placeOrder, order("bad-address", 250_00, BAD_ADDRESS));

        awaitStatus(workflow, OrderStatus.ROLLING_BACK); // the test's timeout fails it if never seen

        assertEquals(OrderStatus.SHIPMENT_FAILED, WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class));
    }

    @Test
    void theUndoStepsAreLabelledAsCompensationInTheHistory() {
        harness.newWorkflow("bad-address").placeOrder(order("bad-address", 250_00, BAD_ADDRESS));

        assertEquals(List.of(
                        new ScheduledActivity("VoidAuthorization", "Compensation: void card hold"),
                        new ScheduledActivity("Release", "Compensation: release stock")),
                compensations());
    }

    @Test
    void cancellingTheShipmentIsLabelledAsCompensationToo() {
        harness.control(Map.of("captureError", "HOLD_EXPIRED")); // fails after the booking, so all three steps undo

        harness.newWorkflow("expired").placeOrder(order("expired", 250_00, "1 Main St"));

        assertEquals(List.of(
                        new ScheduledActivity("CancelBooking", "Compensation: cancel shipment"),
                        new ScheduledActivity("VoidAuthorization", "Compensation: void card hold"),
                        new ScheduledActivity("Release", "Compensation: release stock")),
                compensations());
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

    private List<ScheduledActivity> compensations() {
        return harness.scheduledActivities().stream()
                .filter(activity -> activity.summary() != null && activity.summary().startsWith("Compensation:"))
                .toList();
    }

    private static void awaitStatus(OrderWorkflow workflow, OrderStatus expected) throws InterruptedException {
        while (workflow.getStatus() != expected) {
            Thread.sleep(20);
        }
    }
}
