package com.example.orders.workflow;

import com.example.orders.activities.FraudActivities;
import com.example.orders.activities.InventoryActivities;
import com.example.orders.activities.PaymentActivities;
import com.example.orders.activities.ShippingActivities;
import com.example.orders.model.Authorization;
import com.example.orders.model.Booking;
import com.example.orders.model.CheckoutResult;
import com.example.orders.model.FraudDecision;
import com.example.orders.model.OrderRequest;
import com.example.orders.model.OrderStatus;
import com.example.orders.model.Reservation;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.slf4j.Logger;

/**
 * Hero flow, steps 3–6, as one durable function. Workflow code must be deterministic:
 * all I/O happens in activities, and logging goes through {@link Workflow#getLogger}.
 */
public class OrderWorkflowImpl implements OrderWorkflow {

    private static final Logger log = Workflow.getLogger(OrderWorkflowImpl.class);
    private static final Duration DEFAULT_REVIEW_DEADLINE = Duration.ofHours(24);

    // One retry policy for every step: transient failures are retried with capped backoff (no retry storm when
    // a dependency recovers); permanent business failures are never retried and go straight to compensation.
    private final ActivityOptions options = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofSeconds(30))
                    .setDoNotRetry("ShipmentRejected", "HoldExpired", "AuthVoided")
                    .build())
            .build();

    private final InventoryActivities inventory = Workflow.newActivityStub(InventoryActivities.class, options);
    private final PaymentActivities payment = Workflow.newActivityStub(PaymentActivities.class, options);
    private final FraudActivities fraud = Workflow.newActivityStub(FraudActivities.class, options);
    private final ShippingActivities shipping = Workflow.newActivityStub(ShippingActivities.class, options);

    private OrderStatus status = OrderStatus.PLACED;
    private Boolean reviewApproved; // null until the analyst decides
    private boolean cancelRequested;
    private boolean fulfillmentStarted; // the point of no return: set just before booking is scheduled
    private boolean closing;            // the order is ending (rolling back or finishing): no cancel can change that
    private CheckoutResult checkoutResult; // fixed once the payment is decided; null until then
    private boolean finished;              // the final status is set: every waiting update can now answer

    // Undo steps run one after another, and a failed undo doesn't stop the rest.
    private final Saga saga = new Saga(new Saga.Options.Builder()
            .setParallelCompensation(false)
            .setContinueWithError(true)
            .build());

    @Override
    public OrderStatus placeOrder(OrderRequest order) {
        setStatus(OrderStatus.PLACED);
        Reservation reservation = inventory.reserve(order);
        saga.addCompensation(inventory::release, order.orderId(), reservation);
        setStatus(OrderStatus.STOCK_RESERVED);

        Authorization authorization = payment.authorize(order);
        if (authorization.isDeclined()) {
            checkoutResult = CheckoutResult.DECLINED; // answered while the customer can still try another card
            return rollBack(OrderStatus.PAYMENT_DECLINED);
        }
        saga.addCompensation(payment::voidAuthorization, order.orderId(), authorization);
        setStatus(OrderStatus.PAYMENT_AUTHORIZED);
        checkoutResult = CheckoutResult.PLACED; // checkout can now answer; the rest continues in the background

        if (fraud.screen(order) == FraudDecision.NEEDS_REVIEW) {
            setStatus(OrderStatus.AWAITING_REVIEW);
            log.info("Order {} waiting for analyst review", order.orderId());
            // A durable timer races the analyst and the customer: whichever comes first wins.
            boolean decided = Workflow.await(reviewDeadline(order), () -> reviewApproved != null || cancelRequested);
            if (!decided) {
                return rollBack(OrderStatus.REVIEW_TIMED_OUT); // fraud-safe default: nobody approved, so don't ship
            }
            if (!cancelRequested && !reviewApproved) {
                return rollBack(OrderStatus.REVIEW_REJECTED);
            }
        }

        // The gate: the last point a cancel can take effect, including one accepted while an earlier step ran.
        // Checked and closed in uninterrupted workflow code, so no cancel can slip in between it and the booking.
        if (cancelRequested) {
            return rollBack(OrderStatus.CANCELLED); // the cancel update answers only after this rollback
        }
        setStatus(OrderStatus.APPROVED);
        fulfillmentStarted = true;

        Booking booking;
        try {
            booking = shipping.book(order);
        } catch (ActivityFailure e) {
            return rollBack(OrderStatus.SHIPMENT_FAILED);
        }
        saga.addCompensation(shipping::cancelBooking, order.orderId(), booking);
        setStatus(OrderStatus.SHIPMENT_BOOKED);

        try {
            payment.capture(order.orderId(), authorization);
        } catch (ActivityFailure e) {
            // The hold can't be captured: don't ship unpaid — cancel the shipment and put the stock back.
            return rollBack(OrderStatus.PAYMENT_FAILED);
        }
        return finish(OrderStatus.CAPTURED);
    }

    @Override
    public void approveReview() {
        reviewApproved = true;
    }

    @Override
    public void rejectReview(String reason) {
        log.info("Review rejected: {}", reason);
        reviewApproved = false;
    }

    @Override
    public CheckoutResult checkout() {
        // Cannot hang: every way the order can end (cancel, review, carrier, capture) happens after authorize,
        // and authorize retries until it succeeds or declines — so the answer is always set before the order closes.
        // Keep it that way: a new early exit would need to set checkoutResult (or a validator) first.
        Workflow.await(() -> checkoutResult != null);
        return checkoutResult;
    }

    @Override
    public void validateCancel(String reason) {
        if (closing) {
            // The order is already ending another way; say so, rather than accept a cancel that can't take effect.
            throw ApplicationFailure.newNonRetryableFailure("Order is already closing", "CancelRejected");
        }
        if (fulfillmentStarted) {
            // Non-retryable: asking again will never succeed once the order is being fulfilled.
            throw ApplicationFailure.newNonRetryableFailure("Too late to cancel: fulfillment in progress", "CancelRejected");
        }
    }

    @Override
    public OrderStatus cancel(String reason) {
        log.info("Cancel requested: {}", reason);
        cancelRequested = true;
        // Answers when the order has ended, however it ended: usually CANCELLED, but an accepted cancel can lose to
        // another ending (e.g., the card is declined first). Waiting only for CANCELLED would hang on those paths.
        Workflow.await(() -> finished);
        return status;
    }

    /** Every status change goes through here, so the searchable OrderStatus never drifts from the real one. */
    private void setStatus(OrderStatus newStatus) {
        status = newStatus;
        Workflow.upsertTypedSearchAttributes(ORDER_STATUS.valueSet(newStatus.name()));
    }

    private static Duration reviewDeadline(OrderRequest order) {
        return order.reviewDeadlineSeconds() > 0 ? Duration.ofSeconds(order.reviewDeadlineSeconds()) : DEFAULT_REVIEW_DEADLINE;
    }

    /** Undoes every completed step (in reverse order), then ends the order. */
    private OrderStatus rollBack(OrderStatus finalStatus) {
        closing = true;
        saga.compensate();
        return finish(finalStatus);
    }

    /** Ends the order, but only after every in-flight update (e.g., cancel) has returned its answer. */
    private OrderStatus finish(OrderStatus finalStatus) {
        closing = true;
        setStatus(finalStatus);
        finished = true;
        Workflow.await(Workflow::isEveryHandlerFinished);
        return status;
    }

    @Override
    public OrderStatus getStatus() {
        return status;
    }
}
