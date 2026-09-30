# Temporal Order Workflow

An e-commerce order (reserve stock → authorize card → fraud screening → book shipment → capture payment) as one durable Temporal workflow, in Java.

![Architecture — Temporal version](docs/architecture-temporal.svg)

Compare with the event-driven system it replaces, after its incident patches: [`docs/architecture-v2-prime.svg`](docs/architecture-v2-prime.svg) (13 reliability components in amber; here, none). Kafka leaves the order flow; it can stay for fan-out to analytics and search.

> Status: hero flow plus all seven cut-ins (compensation, crash recovery, retries, cancel, review deadline, checkout, order search), each added test-first, one at a time — see [`docs/cut-ins.md`](docs/cut-ins.md).

## How this was worked out

The code is the last step. The thinking behind it is in [`docs/`](docs), in the order it was done:

1. [`work-journal.md`](docs/work-journal.md): Temporal's values ranked by how hard they are to get elsewhere, order-system problems ranked by business cost, and the decisions log.
2. [`temporal-sa-architecture-v1-v2.md`](docs/temporal-sa-architecture-v1-v2.md): the event-driven system a team builds without Temporal (v1), the patches its incidents force (v2, v2′), and the cut-in index.
3. [`v1-system-design.md`](docs/v1-system-design.md): v1's behavior, sized to what the cut-ins need, traced end to end.
4. [`cut-ins.md`](docs/cut-ins.md): the seven incidents, each as incident → the team's patch → what the patch costs → Temporal's answer → demo moment.

## Prerequisites

- Java 21+
- [Temporal CLI](https://docs.temporal.io/cli) (`brew install temporal`)

Maven is not required; the wrapper (`./mvnw`) downloads it.

> **Register `OrderStatus` first.** Every order writes this search attribute as it moves. If the namespace doesn't have it (the dev server flag below, or namespace configuration on Temporal Cloud), those writes fail and orders stop making progress.

## Run

Four terminals, from the repo root:

```bash
# 1. Temporal dev server (UI at http://localhost:8233) with the OrderStatus search attribute registered
temporal server start-dev --search-attribute OrderStatus=Keyword

# 2. Mock downstream services (inventory, fraud, card gateway, carrier) on :8081
./mvnw -q compile exec:java -Dexec.mainClass=com.example.orders.mocks.MockServicesApp

# 3. Worker
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.worker.WorkerApp

# 4. Check out: places the order and waits for the answer (Order placed / Card declined)
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.starter.PlaceOrder -Dexec.args="250"    # auto-approved
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.starter.PlaceOrder -Dexec.args="1500"   # waits for review
# optional arguments: [totalDollars] [address] [orderId]
```

Approve the high-value order (checkout prints its workflow ID):

```bash
temporal workflow query  -w order-<id> --name getStatus       # AWAITING_REVIEW
temporal workflow signal -w order-<id> --name approveReview
```

Downstream state (holds, reservations, bookings): `curl -d '{}' localhost:8081/state`

## Cut-ins at a glance

Each demo below is one incident from the event-driven system, answered by one Temporal value (numbering from [`work-journal.md`](docs/work-journal.md)):

| Cut | Problem | Temporal value |
|---|---|---|
| 1 | Partial failure, nobody undoes | #2 Saga (built on #1) |
| 2 | Process dies mid-flow; the order is stuck or lost | #1 Durable execution |
| 3 | Gateway blips: lost sales or double charges | #7 Retries (partial fit: also needs an idempotency key) |
| 4 | Cancel races fulfillment | #4 Signals / Queries / Updates |
| 5 | Review deadline missed | #3 Durable timers |
| 6 | Checkout slow or dishonest | #4b Update-With-Start |
| 7 | "What happened to order #123?" | #5 Visibility |

## Demo: a late failure is rolled back (cut 1)

The carrier rejects any address containing `INVALID`, which is a permanent failure. The workflow fails fast and runs its compensations in reverse order: void the card hold, then release the stock.

```bash
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.starter.PlaceOrder -Dexec.args="250 INVALID"
curl -d '{}' localhost:8081/state      # hold VOIDED, reservation RELEASED, no booking
```

Checkout still answers `Order placed`: the card was authorized. The carrier's rejection comes later, and the workflow rolls the order back on its own.

**Crash mid-rollback:** start the worker with a slow release, then kill it once the hold shows `VOIDED`:

```bash
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.worker.WorkerApp -Ddemo.releaseDelaySeconds=8
# place an INVALID order, wait ~4s, Ctrl+C the worker, start it again
```

After the activity's 10s timeout, the release is retried on the new worker and completes. The history shows `VoidAuthorization` completed once; it is not re-run.

## Demo: nothing is lost when a process dies (cut 2)

**The order is recorded before any work runs.** Stop the worker, then check out:

```bash
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.starter.PlaceOrder -Dexec.args="250 Main-St demoA"
# checkout waits; in another terminal:
curl -d '{}' localhost:8081/state      # nothing downstream yet, but the workflow already exists in the UI
# start the worker → checkout answers "Order placed" and the order runs to CAPTURED
```

**The same order is never placed twice**, even after it completed (`REJECT_DUPLICATE`):

```bash
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.starter.PlaceOrder -Dexec.args="250 Main-St demoA"
# Order demoA was already placed; not placing it again
```

**A killed worker's order continues where it stopped.** Start the worker with a slow booking, kill it while the status query says `APPROVED`, and start it again:

```bash
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.worker.WorkerApp -Ddemo.bookDelaySeconds=8
temporal workflow query -w order-<id> --name getStatus   # APPROVED → Ctrl+C the worker, start it again
```

The history shows reserve, authorize and screen completed on attempt 1; only `Book` runs again (attempt 2).

## Demo: gateway failures (cut 3)

The mock services take failure modes on `/control`. One retry policy in the workflow handles them: transient errors are retried with capped backoff (1s, ×2, max 30s); `ShipmentRejected`, `HoldExpired` and `AuthVoided` are never retried.

**Outage:** the next 5 gateway calls return 503.

```bash
curl -d '{"gatewayFailNext":5}' localhost:8081/control
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.starter.PlaceOrder
temporal workflow describe -w order-<id>    # pending Authorize: Attempt 4, LastFailure "503 gateway unavailable"
```

The order completes once the gateway recovers. An order placed afterwards completes immediately: there is no queue to drain, and other orders never waited behind this one.

**Unknown outcome:** the gateway places the hold, then answers after the worker's 5s timeout. The retry sends the same idempotency key (`<workflowId>-authorize`) and gets the original hold back.

```bash
curl -d '{"authorizeDelayOnceSeconds":6}' localhost:8081/control
# place an order → Authorize completes on attempt 2; /state shows one new hold, not two
```

**Permanent error:** capture fails with `HOLD_EXPIRED`. No retry; the shipment is cancelled and the stock released, so nothing ships unpaid.

```bash
curl -d '{"captureError":"HOLD_EXPIRED"}' localhost:8081/control
# place an order → PAYMENT_FAILED; history: Capture failed on attempt 1, then CancelBooking, VoidAuthorization, Release
curl -d '{"captureError":null}' localhost:8081/control     # back to normal
```

## Demo: cancel gets an honest answer (cut 4)

Cancel is a workflow **Update**: the workflow itself decides, so there is nothing to race. It is accepted until booking starts; the answer comes back only after the rollback is done.

**Accepted:** cancel a high-value order while it waits for review.

```bash
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.starter.PlaceOrder -Dexec.args="1500"
temporal workflow update execute -w order-<id> --name cancel -i '"changed my mind"'   # Result: CANCELLED
curl -d '{}' localhost:8081/state      # hold VOIDED, reservation RELEASED, no booking
```

**Rejected:** start the worker with a slow booking, and cancel while the status query says `APPROVED`.

```bash
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.worker.WorkerApp -Ddemo.bookDelaySeconds=8
temporal workflow update execute -w order-<id> --name cancel -i '"too late?"'
# Error: ... Too late to cancel: fulfillment in progress
```

The order completes as `CAPTURED`. The rejected update leaves no trace in the history: the validator runs before anything is recorded.

## Demo: a review can't wait forever (cut 5)

A durable timer races the analyst's decision (and the customer's cancel): whichever comes first wins. With no decision by the deadline (24 hours by default), the order is rejected and rolled back. The timer lives in the workflow's history on the Temporal Service, so it survives worker crashes.

```bash
# a 30s deadline instead of 24h, for the demo
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.starter.PlaceOrder -Ddemo.reviewDeadlineSeconds=30 -Dexec.args="1500"
# don't approve; optionally Ctrl+C the worker for a while and start it again
# ~30s later: REVIEW_TIMED_OUT; history shows TimerFired, then VoidAuthorization and Release
```

Approve within the deadline and it ships as usual (`temporal workflow signal -w order-<id> --name approveReview`). An approval after the deadline fails at the client: the workflow has already completed.

The real 24-hour deadline is covered by `ReviewDeadlineTest`, which runs in milliseconds: the test environment skips time while the workflow only waits on a timer.

## Demo: checkout answers honestly (cut 6)

Checkout is one call, **Update-With-Start**: it starts the order's workflow and waits on the `checkout` update. The answer is `Order placed` only once the card is authorized, or `Card declined` while the customer can still try another card. Screening and shipping continue in the background.

```bash
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.starter.PlaceOrder                                    # Order placed
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.starter.PlaceOrder -Ddemo.cardToken=tok_declined      # Card declined
curl -d '{}' localhost:8081/state      # the declined order: no hold, reservation RELEASED
```

**A retried checkout is the same order.** Send the same order ID twice while it is still running (a high-value order waits for review): both answers are `Order placed`, and there is one hold. That is the conflict policy `USE_EXISTING`; a completed order's ID is refused by the reuse policy `REJECT_DUPLICATE`.

```bash
./mvnw -q exec:java -Dexec.mainClass=com.example.orders.starter.PlaceOrder -Dexec.args="1500 Main-St tw1"   # run twice
```

**The honest limit:** the answer needs a worker. With no worker running, checkout waits and answers once one starts.

## Demo: what happened to order #123? (cut 7)

Every order writes its business status to the `OrderStatus` search attribute as it moves (one `setStatus()` helper, so it can't drift from the real status). The Temporal Service indexes it, so orders are findable by state, with or without a worker running.

```bash
temporal workflow list --query 'OrderStatus="AWAITING_REVIEW"'                          # every order waiting on an analyst
temporal workflow list --query 'OrderStatus="AWAITING_REVIEW" AND StartTime < "2026-09-29T00:00:00Z"'   # ...since before a date
```

**What is it waiting on right now?** `temporal workflow describe -w order-<id>` (or the UI) shows the pending step, its attempt count and last error. During the cut 3 outage, for example: `OrderStatus=STOCK_RESERVED`, pending `Authorize`, attempt 5, last error `503: gateway unavailable`. The full event history shows every step, retry, timer, signal and update in order.

**Search vs. query:** stop the worker, and `temporal workflow query ... --name getStatus` hangs (a query is answered by a worker), while `workflow list --query` still answers (search is answered by the Temporal Service). The search index is eventually consistent: it can trail the workflow by a moment.

## Test

```bash
./mvnw test
```
