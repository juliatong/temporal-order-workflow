package com.example.orders.workflow;

import com.example.orders.model.CheckoutResult;
import com.example.orders.model.OrderRequest;
import com.example.orders.model.OrderStatus;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.SearchAttributeKey;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** One workflow per order; the workflow ID is {@code "order-" + orderId}. */
@WorkflowInterface
public interface OrderWorkflow {

    String TASK_QUEUE = "orders";

    /**
     * Search attribute kept by the Temporal Service, e.g. {@code OrderStatus="AWAITING_REVIEW"}. Must be registered
     * on the namespace (dev server: {@code --search-attribute OrderStatus=Keyword}), or every upsert fails.
     */
    SearchAttributeKey<String> ORDER_STATUS = SearchAttributeKey.forKeyword("OrderStatus");

    static String workflowId(String orderId) {
        return "order-" + orderId;
    }

    /**
     * How every client starts an order.
     * Conflict policy (the order is still running): USE_EXISTING — a retried checkout attaches to the same order
     * and gets the same answer; required for Update-With-Start.
     * Reuse policy (the order already completed): REJECT_DUPLICATE — the same order ID can never start a second run
     * (the default would allow it — a second order, a second hold).
     */
    static WorkflowOptions options(String orderId) {
        return WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId(orderId))
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();
    }

    /** True if starting failed because this order ID already completed (the REJECT_DUPLICATE reuse policy). */
    static boolean isAlreadyFinished(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof StatusRuntimeException grpc && grpc.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                return true;
            }
        }
        return false;
    }

    @WorkflowMethod
    OrderStatus placeOrder(OrderRequest order);

    /** The analyst's decision on a high-value order (hero flow step 5, manual branch). */
    @SignalMethod
    void approveReview();

    @SignalMethod
    void rejectReview(String reason);

    /**
     * Checkout's answer, sent together with the start (Update-With-Start): PLACED once the card is authorized,
     * DECLINED if the gateway declines. The rest of the order continues after this returns.
     */
    @UpdateMethod
    CheckoutResult checkout();

    /**
     * The customer cancels. Returns the order's final status once it has ended and been rolled back: CANCELLED,
     * unless another ending won first (e.g., the card was declined) — then that ending, honestly.
     */
    @UpdateMethod
    OrderStatus cancel(String reason);

    /** Rejects the cancel, before anything is recorded, once the order is ending or fulfillment has started. */
    @UpdateValidatorMethod(updateName = "cancel")
    void validateCancel(String reason);

    @QueryMethod
    OrderStatus getStatus();
}
