package com.example.orders.worker;

import com.example.orders.activities.FraudActivitiesImpl;
import com.example.orders.activities.InventoryActivitiesImpl;
import com.example.orders.activities.MockServicesClient;
import com.example.orders.activities.PaymentActivitiesImpl;
import com.example.orders.activities.ShippingActivitiesImpl;
import com.example.orders.mocks.MockServicesApp;
import com.example.orders.workflow.OrderWorkflow;
import com.example.orders.workflow.OrderWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.time.Duration;

/** The process the demos kill. Its state lives in Temporal (workflow) and mock-services (downstream). */
public class WorkerApp {

    public static void main(String[] args) {
        WorkflowClient client = WorkflowClient.newInstance(WorkflowServiceStubs.newLocalServiceStubs());
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(OrderWorkflow.TASK_QUEUE);

        MockServicesClient mocks = new MockServicesClient("http://localhost:" + MockServicesApp.PORT);
        // Demo: -Ddemo.releaseDelaySeconds=8 slows the release compensation so the worker can be killed mid-rollback.
        // Keep it below the activities' 10s start-to-close timeout, or every attempt times out.
        Duration releaseDelay = Duration.ofSeconds(Integer.getInteger("demo.releaseDelaySeconds", 0));
        // Demo: -Ddemo.bookDelaySeconds=8 pauses before the carrier call, so the worker can be killed mid-order.
        Duration bookDelay = Duration.ofSeconds(Integer.getInteger("demo.bookDelaySeconds", 0));
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new InventoryActivitiesImpl(mocks, releaseDelay),
                new PaymentActivitiesImpl(mocks),
                new FraudActivitiesImpl(mocks),
                new ShippingActivitiesImpl(mocks, bookDelay));

        factory.start();
        System.out.println("worker polling task queue '" + OrderWorkflow.TASK_QUEUE + "'");
    }
}
