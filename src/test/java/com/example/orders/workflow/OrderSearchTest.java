package com.example.orders.workflow;

import static com.example.orders.workflow.OrderTestHarness.order;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.orders.model.OrderStatus;
import io.temporal.client.WorkflowClient;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Cut 7: find orders by business state. The OrderStatus search attribute is kept by the Temporal Service,
 * so it is read without asking a worker (unlike the getStatus query).
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class OrderSearchTest {

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
    void anOrderWaitingForReviewIsFindableByItsStatus() throws InterruptedException {
        OrderWorkflow workflow = harness.newWorkflow("waiting");
        WorkflowClient.start(workflow::placeOrder, order("waiting", 1_500_00, "1 Main St"));
        while (workflow.getStatus() != OrderStatus.AWAITING_REVIEW) {
            Thread.sleep(20);
        }

        assertEquals("AWAITING_REVIEW", harness.searchableStatus("waiting"));
    }

    @Test
    void aFinishedOrderIsFindableByItsFinalStatus() {
        harness.newWorkflow("done").placeOrder(order("done", 250_00, "1 Main St"));

        assertEquals("CAPTURED", harness.searchableStatus("done"));
    }
}
