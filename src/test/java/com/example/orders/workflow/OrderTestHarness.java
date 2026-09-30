package com.example.orders.workflow;

import com.example.orders.activities.FraudActivitiesImpl;
import com.example.orders.activities.InventoryActivitiesImpl;
import com.example.orders.activities.MockServicesClient;
import com.example.orders.activities.PaymentActivitiesImpl;
import com.example.orders.activities.ShippingActivitiesImpl;
import com.example.orders.mocks.MockServicesApp;
import com.example.orders.model.LineItem;
import com.example.orders.model.OrderRequest;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The seam every workflow test uses: the workflow with its real activities, against the mock
 * services over HTTP on a free port. Downstream effects are read through the mocks' /state endpoint.
 */
final class OrderTestHarness implements AutoCloseable {

    private final MockServicesApp mocks;
    private final MockServicesClient client;
    private final TestWorkflowEnvironment env;
    private final List<ScheduledActivity> scheduled = Collections.synchronizedList(new ArrayList<>());

    /** An activity the workflow asked the Temporal Service to run, with the summary the UI shows for it. */
    record ScheduledActivity(String name, String summary) {}

    OrderTestHarness() throws IOException {
        mocks = MockServicesApp.startOn(0);
        client = new MockServicesClient("http://localhost:" + mocks.port(), Duration.ofSeconds(1));

        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder()
                        .setWorkerInterceptors(new ScheduledActivityRecorder())
                        .build())
                .build());
        env.registerSearchAttribute(OrderWorkflow.ORDER_STATUS.getName(), IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
        Worker worker = env.newWorker(OrderWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new InventoryActivitiesImpl(client),
                new PaymentActivitiesImpl(client),
                new FraudActivitiesImpl(client),
                new ShippingActivitiesImpl(client));
        env.start();
    }

    OrderWorkflow newWorkflow(String orderId) {
        return env.getWorkflowClient().newWorkflowStub(OrderWorkflow.class, OrderWorkflow.options(orderId));
    }

    /** The order's OrderStatus search attribute, as the Temporal Service reports it (describe — no worker involved). */
    String searchableStatus(String orderId) {
        return env.getWorkflowClient().newUntypedWorkflowStub(OrderWorkflow.workflowId(orderId))
                .describe().getTypedSearchAttributes().get(OrderWorkflow.ORDER_STATUS);
    }

    /** Every activity scheduled so far, in order, as the workflow sent it (replays not counted twice). */
    List<ScheduledActivity> scheduledActivities() {
        synchronized (scheduled) {
            return List.copyOf(scheduled);
        }
    }

    /** Advances test time (skipped, not waited) — e.g. to just before a deadline. */
    void sleep(Duration testTime) {
        env.sleep(testTime);
    }

    /** Sets the mock services' failure modes, e.g. {@code gatewayFailNext: 3}. */
    void control(Map<String, Object> modes) {
        client.post("/control", modes);
    }

    /** Statuses of everything downstream of one kind: "holds", "reservations" or "bookings". */
    List<String> downstream(String kind) {
        List<String> statuses = new ArrayList<>();
        client.post("/state", Map.of()).get(kind).forEach(status -> statuses.add(status.asText()));
        return statuses;
    }

    static OrderRequest order(String orderId, long totalCents, String address) {
        return order(orderId, totalCents, address, 0);
    }

    /** A $250 order paid with the given card token; the mock gateway declines {@code tok_declined}. */
    static OrderRequest orderWithCard(String orderId, String cardToken) {
        return new OrderRequest(orderId, "customer-42", List.of(new LineItem("SKU-123", 1)),
                250_00, cardToken, "1 Main St", 0);
    }

    static OrderRequest order(String orderId, long totalCents, String address, long reviewDeadlineSeconds) {
        return new OrderRequest(orderId, "customer-42", List.of(new LineItem("SKU-123", 1)),
                totalCents, "tok_visa", address, reviewDeadlineSeconds);
    }

    @Override
    public void close() {
        env.close();
        mocks.stop();
    }

    /** Sees each activity call on its way from the workflow to the Temporal Service, with its options. */
    private final class ScheduledActivityRecorder extends WorkerInterceptorBase {
        @Override
        public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
            return new WorkflowInboundCallsInterceptorBase(next) {
                @Override
                public void init(WorkflowOutboundCallsInterceptor outbound) {
                    super.init(new WorkflowOutboundCallsInterceptorBase(outbound) {
                        @Override
                        public <R> ActivityOutput<R> executeActivity(ActivityInput<R> input) {
                            if (!Workflow.isReplaying()) {
                                scheduled.add(new ScheduledActivity(input.getActivityName(), input.getOptions().getSummary()));
                            }
                            return super.executeActivity(input);
                        }
                    });
                }
            };
        }
    }
}
