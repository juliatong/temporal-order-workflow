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

/** Hero flow, steps 3–6: both branches of fraud screening (L10). */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class OrderWorkflowTest {

    private static final String ADDRESS = "1 Main St";

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
    void lowValueOrderIsAutoApprovedAndCaptured() {
        OrderStatus result = harness.newWorkflow("low").placeOrder(order("low", 250_00, ADDRESS));

        assertAll(
                () -> assertEquals(OrderStatus.CAPTURED, result),
                () -> assertEquals(List.of("CAPTURED"), harness.downstream("holds")),
                () -> assertEquals(List.of("BOOKED"), harness.downstream("bookings")));
    }

    @Test
    void highValueOrderWaitsForAnalystApproval() {
        OrderWorkflow workflow = harness.newWorkflow("high");

        WorkflowClient.start(workflow::placeOrder, order("high", 1_500_00, ADDRESS));
        workflow.approveReview();

        OrderStatus result = WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class);

        assertAll(
                () -> assertEquals(OrderStatus.CAPTURED, result),
                () -> assertEquals(List.of("CAPTURED"), harness.downstream("holds")),
                () -> assertEquals(List.of("BOOKED"), harness.downstream("bookings")));
    }

    @Test
    void rejectedReviewStopsBeforeShipping() {
        OrderWorkflow workflow = harness.newWorkflow("rejected");

        WorkflowClient.start(workflow::placeOrder, order("rejected", 1_500_00, ADDRESS));
        workflow.rejectReview("card reported stolen");

        OrderStatus result = WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class);

        assertAll(
                () -> assertEquals(OrderStatus.REVIEW_REJECTED, result),
                () -> assertEquals(List.of(), harness.downstream("bookings")));
    }
}
