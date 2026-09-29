package com.example.orders.workflow;

import static com.example.orders.workflow.OrderTestHarness.order;
import static com.example.orders.workflow.OrderTestHarness.orderWithCard;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.orders.model.OrderStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Cut 4: the customer's cancel gets an honest answer — accepted and rolled back, or rejected once fulfillment started. */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class CancelTest {

    private static final long HIGH_VALUE = 1_500_00; // waits for analyst review
    private static final long LOW_VALUE = 250_00;    // auto-approved, goes straight to booking

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
    void cancellingWhileAwaitingReviewCancelsTheOrder() {
        OrderWorkflow workflow = harness.newWorkflow("cancel");
        WorkflowClient.start(workflow::placeOrder, order("cancel", HIGH_VALUE, "1 Main St"));

        assertEquals(OrderStatus.CANCELLED, workflow.cancel("changed my mind"));
        assertEquals(OrderStatus.CANCELLED, WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class));
    }

    @Test
    void cancellingWhileAwaitingReviewUndoesTheHoldAndTheReservation() {
        OrderWorkflow workflow = harness.newWorkflow("cancel");
        WorkflowClient.start(workflow::placeOrder, order("cancel", HIGH_VALUE, "1 Main St"));

        workflow.cancel("changed my mind");

        assertAll(
                () -> assertEquals(List.of("VOIDED"), harness.downstream("holds")),
                () -> assertEquals(List.of("RELEASED"), harness.downstream("reservations")));
    }

    @Test
    void cancellingAnAutoApprovedOrderBeforeBookingCancelsIt() {
        OrderWorkflow workflow = harness.newWorkflow("early-cancel");
        WorkflowClient.start(workflow::placeOrder, order("early-cancel", LOW_VALUE, "1 Main St"));

        OrderStatus answer = workflow.cancel("changed my mind"); // arrives several steps before booking

        assertAll(
                () -> assertEquals(OrderStatus.CANCELLED, answer),
                () -> assertEquals(OrderStatus.CANCELLED,
                        WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class)),
                () -> assertEquals(List.of(), harness.downstream("bookings")));
    }

    @Test
    void cancellingOnceBookingHasStartedIsRejectedAndTheOrderCompletes() throws InterruptedException {
        harness.control(Map.of("bookDelayOnceMillis", 700)); // keeps the booking in flight
        OrderWorkflow workflow = harness.newWorkflow("late-cancel");
        WorkflowClient.start(workflow::placeOrder, order("late-cancel", LOW_VALUE, "1 Main St"));
        awaitStatus(workflow, OrderStatus.APPROVED); // set just before booking starts

        WorkflowUpdateException rejected =
                assertThrows(WorkflowUpdateException.class, () -> workflow.cancel("too late?"));

        assertAll(
                () -> assertTrue(rootMessage(rejected).contains("fulfillment in progress"), rootMessage(rejected)),
                () -> assertEquals(OrderStatus.CAPTURED,
                        WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class)));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS) // an unanswered cancel shows up as this timeout
    void cancellingWhileTheOrderIsRollingBackGetsAnAnswer() throws InterruptedException {
        harness.control(Map.of("voidDelayOnceMillis", 700)); // keeps the rollback in progress
        OrderWorkflow workflow = harness.newWorkflow("closing");
        WorkflowClient.start(workflow::placeOrder, order("closing", HIGH_VALUE, "1 Main St"));
        awaitStatus(workflow, OrderStatus.AWAITING_REVIEW);

        workflow.rejectReview("card reported stolen");
        Thread.sleep(300); // the rejection's rollback is now voiding the hold

        WorkflowUpdateException rejected =
                assertThrows(WorkflowUpdateException.class, () -> workflow.cancel("changed my mind"));

        assertAll(
                () -> assertTrue(rootMessage(rejected).contains("already closing"), rootMessage(rejected)),
                () -> assertEquals(OrderStatus.REVIEW_REJECTED,
                        WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class)));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS) // an unanswered cancel shows up as this timeout
    void aCancelThatLosesToADeclineStillGetsAnAnswer() {
        OrderWorkflow workflow = harness.newWorkflow("cancel-vs-decline");
        WorkflowClient.start(workflow::placeOrder, orderWithCard("cancel-vs-decline", "tok_declined"));

        OrderStatus answer = workflow.cancel("changed my mind"); // accepted before the gateway declines

        assertAll(
                () -> assertEquals(OrderStatus.PAYMENT_DECLINED, answer), // the order's real ending, not a pretend CANCELLED
                () -> assertEquals(OrderStatus.PAYMENT_DECLINED,
                        WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class)));
    }

    private static void awaitStatus(OrderWorkflow workflow, OrderStatus expected) throws InterruptedException {
        while (workflow.getStatus() != expected) {
            Thread.sleep(20);
        }
    }

    private static String rootMessage(Throwable e) {
        while (e.getCause() != null) {
            e = e.getCause();
        }
        return String.valueOf(e.getMessage());
    }
}
