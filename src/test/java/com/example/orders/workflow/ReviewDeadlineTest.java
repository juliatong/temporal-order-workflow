package com.example.orders.workflow;

import static com.example.orders.workflow.OrderTestHarness.order;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.orders.model.OrderStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Cut 5: a manual review has a deadline. The test environment skips time while the workflow only waits on a timer,
 * so the real 24-hour deadline runs in milliseconds. Waiting forever shows up as the timeout below.
 */
@Timeout(value = 5, unit = TimeUnit.SECONDS)
class ReviewDeadlineTest {

    private static final long HIGH_VALUE = 1_500_00; // waits for analyst review

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
    void noAnalystDecisionWithin24HoursTimesTheReviewOut() {
        OrderStatus result = harness.newWorkflow("no-decision").placeOrder(order("no-decision", HIGH_VALUE, "1 Main St"));

        assertEquals(OrderStatus.REVIEW_TIMED_OUT, result);
    }

    @Test
    void aTimedOutReviewUndoesTheHoldAndTheReservation() {
        harness.newWorkflow("no-decision").placeOrder(order("no-decision", HIGH_VALUE, "1 Main St"));

        assertAll(
                () -> assertEquals(List.of("VOIDED"), harness.downstream("holds")),
                () -> assertEquals(List.of("RELEASED"), harness.downstream("reservations")));
    }

    @Test
    void anApprovalAtHour23StillShips() {
        OrderWorkflow workflow = harness.newWorkflow("late-approval");
        WorkflowClient.start(workflow::placeOrder, order("late-approval", HIGH_VALUE, "1 Main St"));

        harness.sleep(Duration.ofHours(23));
        workflow.approveReview();

        assertEquals(OrderStatus.CAPTURED, WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class));
    }

    @Test
    void aShorterDeadlineInTheRequestIsTheOneThatApplies() {
        OrderWorkflow workflow = harness.newWorkflow("short-deadline");
        WorkflowClient.start(workflow::placeOrder, order("short-deadline", HIGH_VALUE, "1 Main St", 30));

        harness.sleep(Duration.ofSeconds(60));

        assertEquals(OrderStatus.REVIEW_TIMED_OUT, workflow.getStatus()); // not still waiting for the 24h default
    }
}
