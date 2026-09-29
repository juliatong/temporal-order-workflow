package com.example.orders.workflow;

import static com.example.orders.workflow.OrderTestHarness.order;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Cut 2: an order is recorded once; submitting it again must not place it again. */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class DuplicateOrderTest {

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
    void placingACompletedOrderAgainDoesNotPlaceItTwice() {
        harness.newWorkflow("same").placeOrder(order("same", 250_00, "1 Main St"));

        OrderWorkflow again = harness.newWorkflow("same");

        assertAll(
                () -> assertThrows(WorkflowExecutionAlreadyStarted.class,
                        () -> WorkflowClient.start(again::placeOrder, order("same", 250_00, "1 Main St"))),
                () -> assertEquals(List.of("CAPTURED"), harness.downstream("holds")),
                () -> assertEquals(List.of("BOOKED"), harness.downstream("bookings")));
    }
}
