package com.example.orders.workflow;

import static com.example.orders.workflow.OrderTestHarness.order;
import static com.example.orders.workflow.OrderTestHarness.orderWithCard;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.orders.model.CheckoutResult;
import com.example.orders.model.OrderRequest;
import com.example.orders.model.OrderStatus;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WithStartWorkflowOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowServiceException;
import io.temporal.client.WorkflowStub;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Cut 6: checkout answers honestly while the customer is still on the page — placed only once the card is authorized. */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class CheckoutTest {

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
    void checkoutAnswersPlacedOnceTheCardIsAuthorizedAndTheOrderContinues() {
        OrderWorkflow workflow = harness.newWorkflow("checkout");

        CheckoutResult answer = checkout(workflow, order("checkout", 250_00, "1 Main St"));

        assertEquals(CheckoutResult.PLACED, answer);
        assertEquals(OrderStatus.CAPTURED, WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void aDeclinedCardIsAnsweredAtCheckoutAndTheStockIsReleased() {
        OrderWorkflow workflow = harness.newWorkflow("declined");

        CheckoutResult answer = checkout(workflow, orderWithCard("declined", "tok_declined"));

        assertAll(
                () -> assertEquals(CheckoutResult.DECLINED, answer),
                () -> assertEquals(OrderStatus.PAYMENT_DECLINED,
                        WorkflowStub.fromTyped(workflow).getResult(OrderStatus.class)),
                () -> assertEquals(List.of("RELEASED"), harness.downstream("reservations")),
                () -> assertEquals(List.of(), harness.downstream("holds")));
    }

    @Test
    void aRetriedCheckoutGetsTheSameAnswerWithoutASecondHold() {
        OrderRequest order = order("retried", 1_500_00, "1 Main St"); // high value: still running, waiting for review

        CheckoutResult first = checkout(harness.newWorkflow("retried"), order);
        CheckoutResult retry = checkout(harness.newWorkflow("retried"), order); // e.g. the browser resent it

        assertAll(
                () -> assertEquals(CheckoutResult.PLACED, first),
                () -> assertEquals(CheckoutResult.PLACED, retry),
                () -> assertEquals(List.of("AUTHORIZED"), harness.downstream("holds")));
    }

    @Test
    void checkingOutACompletedOrderAgainIsRefused() {
        OrderWorkflow first = harness.newWorkflow("done");
        checkout(first, order("done", 250_00, "1 Main St"));
        WorkflowStub.fromTyped(first).getResult(OrderStatus.class); // completed

        // Update-With-Start reports it as a service error (ALREADY_EXISTS), not WorkflowExecutionAlreadyStarted.
        WorkflowServiceException refused = assertThrows(WorkflowServiceException.class,
                () -> checkout(harness.newWorkflow("done"), order("done", 250_00, "1 Main St")));
        assertTrue(OrderWorkflow.isAlreadyFinished(refused));
    }

    /** One call: start the order's workflow and wait for the checkout update's answer (Update-With-Start). */
    private static CheckoutResult checkout(OrderWorkflow workflow, OrderRequest order) {
        return WorkflowClient.executeUpdateWithStart(
                workflow::checkout,
                UpdateOptions.<CheckoutResult>newBuilder().build(),
                new WithStartWorkflowOperation<>(workflow::placeOrder, order));
    }
}
