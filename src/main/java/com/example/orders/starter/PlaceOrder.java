package com.example.orders.starter;

import com.example.orders.model.CheckoutResult;
import com.example.orders.model.LineItem;
import com.example.orders.model.OrderRequest;
import com.example.orders.workflow.OrderWorkflow;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WithStartWorkflowOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowServiceException;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.UUID;

/**
 * Checkout: places one order and waits for the honest answer — placed (card authorized) or declined.
 * One call starts the order's workflow and sends the checkout update (Update-With-Start); the rest of the order
 * (screening, shipping) continues in the background — follow it in the Temporal UI.
 *
 * <p>Usage: {@code PlaceOrder [totalDollars] [address] [orderId]} — $1,000 or more goes to analyst review; an address
 * containing INVALID is rejected by the carrier; resending a running order's ID returns the same answer; reusing a
 * completed order's ID is refused. Demo options: {@code -Ddemo.cardToken=tok_declined},
 * {@code -Ddemo.reviewDeadlineSeconds=30}.
 */
public class PlaceOrder {

    public static void main(String[] args) {
        long totalDollars = args.length > 0 ? Long.parseLong(args[0]) : 250;
        String address = args.length > 1 ? args[1] : "1 Main St, Springfield";
        String orderId = args.length > 2 ? args[2] : UUID.randomUUID().toString().substring(0, 8);
        String cardToken = System.getProperty("demo.cardToken", "tok_visa");
        long reviewDeadlineSeconds = Long.getLong("demo.reviewDeadlineSeconds", 0L); // 0 = the 24-hour default

        OrderRequest order = new OrderRequest(
                orderId, "customer-42", List.of(new LineItem("SKU-123", 1)),
                totalDollars * 100, cardToken, address, reviewDeadlineSeconds);

        WorkflowClient client = WorkflowClient.newInstance(WorkflowServiceStubs.newLocalServiceStubs());
        OrderWorkflow workflow = client.newWorkflowStub(OrderWorkflow.class, OrderWorkflow.options(orderId));
        String id = OrderWorkflow.workflowId(orderId);

        try {
            CheckoutResult answer = WorkflowClient.executeUpdateWithStart(
                    workflow::checkout,
                    UpdateOptions.<CheckoutResult>newBuilder().build(),
                    new WithStartWorkflowOperation<>(workflow::placeOrder, order));
            System.out.println(id + " ($" + totalDollars + "): " + switch (answer) {
                case PLACED -> "Order placed";
                case DECLINED -> "Card declined — try another card";
            });
        } catch (WorkflowServiceException e) {
            if (!OrderWorkflow.isAlreadyFinished(e)) {
                throw e;
            }
            System.out.println("Order " + orderId + " was already placed; not placing it again");
        }
        System.exit(0);
    }
}
