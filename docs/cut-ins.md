# Cut-ins — Concrete (Paper Round)

**Status:** Complete · cut 1 ✅ · cut 2 ✅ · cut 3 ✅ · cut 4 ✅ · cut 5 ✅ · cut 6 ✅ · cut 7 ✅ · all concrete · v2′ 🔒 locked · code: hero flow ✅ · cuts 1–7 ✅ · 2026-09-29
**Inputs:** `v1-system-design.md` (v1, complete), `temporal-sa-architecture-v1-v2.md` (cut index, v2 draft)
**Per cut:** incident (traced on v1) → patch (v2 delta) → patch tax (→ next cut) → Temporal answer → demo moment.
**After cut 7:** resolve the carried-over v2 checklist → v2 consistency pass → lock v2 → code track.

### Carried-over v2 checklist

| # | Item | Status |
|---|---|---|
| 1 | Wire all 5 outboxes to the CDC relay; service ↔ Kafka arrows consume-only | ✅ Decided in cut 2 · redraw in v2 pass |
| 2 | Order service consumes but has no inbox | ✅ Cut 2: no inbox — projection tolerates duplicates; ordering is cut 4 |
| 3 | Cut 4: how consumers check the cancel flag | ✅ Cut 4: sync calls to the Order service + fulfillment gate · redraw in v2 pass |
| 4 | Sweeper read path | ✅ Cut 2: Orders DB · cut 5: Fraud DB + call to Fraud reject endpoint |
| 5 | Cut 6 patch | ✅ Cut 6: synchronous reserve + authorize at checkout |
| 6 | Cut 5's sweeper deadline shorter than the hold expiry | ✅ Cut 5: 24h deadline vs. ~7-day hold |

### Code-track notes (collected from demo moments)

- Mock gateway with modes: 503 window, approve-then-timeout with an idempotency store (hold count), `HOLD_EXPIRED` on capture. (Cut 3)
- Activities with a configurable sleep to open a kill window or a cancel window. (Cuts 1, 2, 4)
- Configurable review deadline (30s for the live demo, 24h in the time-skipping test); analyst approve as a signal. (Cut 5)
- Checkout client that calls Update-With-Start and can resend the same order ID; mock gateway card that always declines. (Cut 6)
- Custom search attribute `OrderStatus`, upserted as the workflow moves (also answers status when no worker is running). (Cut 7)

---

## Cut 1 — Partial failure at step 6

**Cost rank 1** · Hero flow step 6 · Journal Problem 1

### Incident (traced on v1)

1. Order passes steps 1–5: stock `RESERVED`, payment `AUTHORIZED`, `ReviewApproved`.
2. Shipping calls `book()`; the carrier returns `REJECTED` (invalid address) — permanent (F.2).
3. Shipping writes `shipments` → `FAILED`, publishes `ShipmentFailed` (B #9).
4. Only the Order service consumes it → status `SHIPMENT_FAILED` (D.1). **Nobody voids or releases** (B.2, H).
5. Reservation stays `RESERVED` — no expiry, so the stock is locked indefinitely (D.2). Payment stays `AUTHORIZED`; the customer sees a pending charge until the hold lapses; `authorization.expired` is ignored (F.1).

Same gap on the other failure events: `PaymentDeclined` leaves the reservation; `ReviewRejected` leaves both.

**Cost:** phantom out-of-stock (lost sales), a visible pending charge (tickets, trust). Triggered by routine carrier rejections, so it happens daily.

### Patch — choreographed compensation (logic, no new component)

Every service holding a resource subscribes to every failure event that ends the order:

| Failure event | Payment → `void(authId)` | Inventory → release reservation |
|---|---|---|
| `PaymentDeclined` | — (nothing authorized) | ✅ |
| `ReviewRejected` | ✅ | ✅ |
| `ShipmentFailed` | ✅ | ✅ |

Each compensation publishes its result (`PaymentVoided`, `StockReleased`) so the status view knows.

### Patch tax

- **Rollback logic is scattered.** 5 subscriptions across 2 services; no single place shows what "undo this order" means. Adding a step (e.g., gift wrapping) means every resource holder must learn a new failure event.
- **Compensation runs on the same machinery that fails.** Each void and release is another consume → call → write → fire-and-forget publish (E). A hard kill loses `ShipmentFailed` before Payment ever sees it, or loses `PaymentVoided` after the void happened.
- **Compensation can fail too.** `void` returns 5xx → retried in place → partition stalls (C); `void` times out → did it happen?
- **Nobody can see a half-finished rollback:** voided but not released looks the same as neither.

**→ Leads to cut 2:** the saga is only as reliable as every event in it arriving — and v1 loses events.

### Temporal answer

- **Saga in workflow code.** After each step succeeds, the workflow pushes its compensation onto a list; on failure it runs them in reverse — void, then release. The whole flow and its rollback read top to bottom in one method.
- **The carrier's `REJECTED` is a non-retryable activity failure**, so the workflow goes straight to compensation instead of retrying.
- **Compensations are activities** with their own retry policy; the workflow's durable execution means a rollback that starts will finish, across crashes.
- **Honest constraint:** activities are at-least-once, so `void` and release must be idempotent (the gateway's void is; release is a status update keyed by order ID).

### Demo moment

1. Place an order with a "bad address" → the book activity fails non-retryable → the UI's event history shows `void` then `release` executing.
2. **Crash variant:** the release activity sleeps a few seconds; kill the worker after `void` completes → restart → the history shows `void` completed once (not re-run) and `release` finishes.

### v2 delta

Logic only: 5 compensation subscriptions + `PaymentVoided`, `StockReleased` events. No diagram change.

---

## Cut 2 — Crash mid-flow: the event is never published

**Cost rank 2** · Hero flow step 2 (and every producing service) · Journal Problem 2

### Incident (traced on v1)

**API edge (step 2):**
1. `POST /orders` → `orders` → `PLACED` (commit) → `send(OrderPlaced)` into the producer buffer → `201 Order placed` (E, G).
2. Peak sale; the pod is OOM-killed before the buffer flushes → `OrderPlaced` never reaches Kafka (E.1).
3. Nothing downstream ever hears of the order. It sits in `PLACED` forever; the customer holds a confirmation.

**Mid-flow (any producing service):**
1. Payment authorizes → `payments` → `AUTHORIZED` → `send(PaymentAuthorized)` → handler returns → offset committed (C, E).
2. Killed before the flush → the event is lost, and Kafka will not redeliver `StockReserved` (offset committed).
3. Never screened, never shipped. Stock stays `RESERVED`; the hold lapses.

Cut 1's patch rides on the same machinery: a lost `ShipmentFailed` or `PaymentVoided` leaves a rollback half-done.

**Cost:** authorized-but-unfulfilled orders, found only when customers complain; manual reconciliation across five DBs; worst at peak, when OOM kills are most likely.

### Patch

| Piece | What it does | Count |
|---|---|---|
| **Outbox** | Business row + outbox row in one DB transaction, in every producing service. The outbox row ID becomes the stable `eventId` | 5 |
| **CDC relay** | Tails every outbox (e.g., Debezium) and publishes to Kafka until acknowledged | 1 |
| **Inbox** *(patch tax, see below)* | Each consumer records `eventId` in the same transaction as its side effect; repeats are skipped. Webhooks too (L5) | 5 |
| **Sweeper** | Scans the Orders DB for orders whose status hasn't changed within a threshold, then re-publishes the trigger for the step they're stuck on | 1 |

### Patch tax

- **The relay is at-least-once** (it re-publishes after its own restart), and redelivery already duplicates side effects (E.1) → the inbox becomes mandatory, not optional.
- **The inbox only protects our own DB.** The gateway call is outside the transaction: a consumer that authorizes and crashes before committing is redelivered, finds no inbox row, and authorizes again.
- **The sweeper re-drives with a new `eventId`**, so the inbox can't recognize it → duplicate side effects unless every handler is also idempotent by business key. It judges "stuck" from the lagging status projection (D.1), is late by up to one scan interval, and needs a lock so two sweeper instances don't both re-drive.
- **13 components now exist to move events reliably**: 5 outbox tables, a relay cluster to run and monitor (with publish lag), 5 inbox tables with cleanup jobs, a sweeper.

**→ Leads to cut 3:** the inbox protects our database, not the gateway — so a gateway blip still costs a second hold.

### Temporal answer

- **Durable execution.** The workflow's progress lives in its event history on the Temporal service, not in the worker. When a worker dies, another worker replays the history and continues from the last recorded step.
- **No outbox needed:** scheduling the next activity is recorded atomically with the workflow's state by the Temporal service — the outbox pattern, built in.
- **No sweeper for stuck orders:** a workflow never silently stops; a pending activity is visible, retrying, with its last error.
- **Duplicate starts:** workflow ID = order ID, so a second start for the same order is rejected while the first is running; the `REJECT_DUPLICATE` reuse policy extends that to completed orders (the default would allow a new run — a second order, a second hold).
- **Honest constraint:** an activity can finish and its worker die before reporting → the activity runs again. Same gap as E.1's third row → idempotency key (cut 3).

### Demo moment

1. **API edge:** stop all workers → place an order → the start is accepted and recorded (the `201` is honest) → start a worker → the order proceeds.
2. **Mid-flow:** add a short sleep in an activity; kill the worker mid-order → restart → the history shows completed steps are not re-run; the order continues from where it stopped.

### v2 delta

5 outboxes, 1 CDC relay, 5 inboxes, 1 sweeper (reads Orders DB). Resolves carried-over checklist items 1, 2 and 4 (see decisions) — diagram changes batched for the v2 consistency pass.

---

## Cut 3 — The gateway blips at step 4

**Cost rank 3** · Hero flow step 4 · Journal Problem 3

### Incident (traced on v1)

**Stall (the "lost sales" half):**
1. `StockReserved` → Payment → `payments` → `AUTH_PENDING` → `authorize()` → the gateway returns 5xx / times out for 30s (F.1).
2. Exception → **retry in place**, fixed backoff, no attempt limit (C). Payment's consumer is stuck on that one message.
3. Every order behind it on that partition waits at step 4 — their confirmations and declines arrive late (cut 6's "hours"). When the gateway recovers, the backlog hits it all at once.

**Second hold (the "duplicate charge" half):**
1. The gateway *approved* hold #1, but the response timed out → unknown outcome (F.1).
2. The retry calls `authorize()` again **without an idempotency key** → hold #2.
3. Hold #2's auth ID overwrites #1 in the one-row-per-order `payments` table (D.3) → hold #1 is orphaned; nothing will ever void it. The customer sees two pending charges and less available credit.

Cut 2's inbox doesn't help: the gateway call is outside our DB transaction.

**Poison pill (the permanent case):** `capture()` returns `HOLD_EXPIRED` (cut 5) or `AUTH_VOIDED` (cut 4) — permanent errors, retried in place forever → that partition never moves again.

**Cost:** delayed orders and abandoned carts during every gateway blip; a second pending charge on the customer's card (and a real double charge if both are ever captured); a permanently stuck partition.

### Patch

| Piece | What it does | Kind |
|---|---|---|
| **Error classification** | Permanent errors (`HOLD_EXPIRED`, `AUTH_VOIDED`, carrier `REJECTED`) skip retries and go straight to the DLQ; `DECLINED` is a business result, not an error | Logic |
| **Retry topics + DLQ** | A transient failure moves the message to a retry topic with backoff (e.g., 1 min, 10 min), then to the DLQ after N attempts; the main partition keeps flowing | Component (drawn) |
| **Idempotency key** | Every gateway call sends a key (order ID + operation); a retried `authorize()` gets the original result back, not a new hold | Logic |

### Patch tax

- **Per-order ordering breaks.** A message parked in a retry topic is overtaken by later events for the same order on the main topic: `OrderCancelled` can be processed before the authorization it was meant to cancel even happens → Payment has nothing to void, then authorizes later → a hold on a cancelled order.
- **The DLQ is a queue of broken orders someone must fix and replay** — hours later, when the hold may have lapsed or the customer cancelled.
- **Idempotency keys have a limited window** at the gateway; a replay from the DLQ after it lapses can create a second hold again.
- **An order's retry state is spread across three topics** — nobody can see "this order is on attempt 4 of authorize" (→ cut 7).

**→ Leads to cut 4:** once retries break per-order ordering, a cancel can overtake the step it was meant to stop.

### Temporal answer

- **Retry policy per activity:** exponential backoff with a cap (no retry storm after recovery), attempts that survive worker crashes, and **non-retryable error types** (`HOLD_EXPIRED`, `AUTH_VOIDED`, `REJECTED`) that fail fast into compensation.
- **Per-order isolation:** retries happen inside one order's workflow; no partition, so no other order waits. (Honest counterpart: a workflow stuck on a bug blocks only that workflow.)
- **`DECLINED` is a return value**, not an exception: the workflow branches on it.
- **Temporal does not solve the unknown outcome on its own** — activities are at-least-once. The idempotency key stays: workflow ID + step name, stable across retries and across runs (the default of run ID + activity ID changes on reset).
- **Ordering is preserved by construction:** the workflow decides the next step; a cancel is handled by the workflow, not raced by consumers (→ cut 4).

### Demo moment

1. **Blip:** the mock gateway returns 503 for ~30s → the UI shows the authorize activity's attempt count and last error climbing; a second order started meanwhile completes normally (isolation); the gateway recovers → the first order proceeds.
2. **Unknown outcome:** the mock approves, then times out once → the retry sends the same idempotency key → the mock returns the original auth ID → the mock's hold count shows **1**.
3. **Permanent error:** `capture()` returns `HOLD_EXPIRED` → non-retryable → no infinite loop; the workflow moves on to its failure path.

### v2 delta

Retry + DLQ (already drawn). Error classification and idempotency keys (logic).

---

## Cut 4 — Cancel races fulfillment; status drifts

**Cost rank 4** · Hero flow step 5–6 + status check · Journal Problem 4

### Incident (traced on v1)

1. `ReviewApproved` → Shipping → `shipments` → `BOOKING` → `book()` in flight (takes seconds) (F.2, C).
2. The customer cancels. The status projection still reads `APPROVED` (lag, D.1) → cancel API accepts → `CANCELLED` → `send(OrderCancelled)` → **`200 Order cancelled`** (G).
3. `OrderCancelled` lands on the partition **before** `ShipmentBooked` (published during the call).
4. Inventory releases the stock — for a parcel that is about to leave → stock count overstated, oversold.
5. Payment voids the hold → `VOIDED`.
6. Booking completes → `BOOKED` → `ShipmentBooked`. Shipping then reads `OrderCancelled`: already booked → log and skip; `cancelBooking` never called (F.2).
7. Payment reads `ShipmentBooked` → `capture()` → `AUTH_VOIDED` (permanent) → retried in place forever → partition stuck (C, cut 3's poison pill).
8. Order service: `CANCELLED`, then `ShipmentBooked` overwrites it → `SHIPMENT_BOOKED` (last writer wins).

**Cost:** the customer was told "cancelled" and receives a parcel they never pay for; stock oversold; a stuck partition; the status page contradicts the confirmation.
*(Other timing: cancel after booking but before the projection catches up → capture first, void gets `ALREADY_CAPTURED` → charged for a "cancelled" order.)*

### Patch

| Piece | What it does | Kind |
|---|---|---|
| **Fulfillment gate** | Before calling the carrier, Shipping calls the Order service (sync) to move the order `APPROVED → FULFILLING` with a **compare-and-set on a row version**. The cancel API uses the same compare-and-set, allowed only before `FULFILLING`. One wins; the loser gets `409` — Shipping skips booking, or the customer is told "too late to cancel" | Logic + new status `FULFILLING` + sync path Shipping → Order service |
| **Active-order check** | Inventory and Payment ask the Order service (sync) "is this order still active?" before a side effect (reserve, authorize, capture) | Logic + sync paths → Order service |
| **Projection transition rules** | Terminal statuses (`CANCELLED`, `SHIPMENT_FAILED`, …) can't be overwritten by later events; version column on the order row | Logic |

### Patch tax

- **Every step now makes a synchronous call to the Order service.** Its availability and latency become every step's; choreography's independence is gone.
- **Only the gate is race-free.** The active-order checks are still check-then-act: a cancel can land between "still active?" and `authorize()`.
- **The Order service is growing into a hand-built orchestrator** — a state machine, a gate, transition rules — without durability: its decisions still travel as fire-and-forget events (cut 2).
- **The rules are spread out:** transitions in the Order service, checks in three consumers.

**→ Leads to cut 5:** the gate settles a race between two actors who both show up. It does nothing when the other actor never does — the analyst who never reviews.

### Temporal answer

- **The workflow is the single owner of the order's state.** Cancel is a **workflow Update**: its handler runs inside the workflow, one step at a time with the main flow — there is no second actor to race.
- **A validator decides synchronously:** before fulfillment starts → accept, and the workflow runs its compensations (void, release); once the booking activity has started → reject "fulfillment in progress". The customer gets an honest answer in the response, not a promise.
- **Status comes from the workflow's own state** (Query, or a search attribute updated as it moves) — it can't drift, because nothing else writes it.
- **No gate, no sync calls between services, no transition table.** The ordering of steps is the code.

### Demo moment

1. Place an order, cancel before shipping → the Update is accepted → the history shows `void` and `release` running.
2. Make the book activity slow; cancel during booking → the validator rejects "fulfillment in progress" → the order completes normally.
3. Check status at each point → it always matches what the workflow is actually doing.

### v2 delta

New status `FULFILLING`; row version on orders; transition rules. Sync paths Inventory, Payment, Shipping → Order service (resolves checklist #3; redraw in the v2 pass). No new amber component.

---

## Cut 5 — The manual review never times out

**Cost rank 5** · Hero flow step 5 (manual queue) · Journal Problem 5

### Incident (traced on v1)

1. A high-value order is placed Friday evening → `PaymentAuthorized` → Fraud queues it: `review_cases` → `MANUAL`, `PENDING`, **no deadline** (D.2, L10).
2. Analysts work business hours; there is no reminder or escalation (F.3). Nobody acts over the weekend.
3. The customer's status page says `PAYMENT_AUTHORIZED` — the wait is invisible (D.1).
4. Meanwhile the stock stays `RESERVED` and the hold counts down to its expiry; `authorization.expired` is ignored (F.1).
5. If the case is approved after the hold lapsed → Shipping books → `capture()` → `HOLD_EXPIRED` → retried forever (cut 3's poison pill) → the parcel ships unpaid.

**Cost:** the most valuable orders wait silently; stock locked; lapsed holds turn into lost sales or unpaid shipments. Invisible until a customer complains.

### Patch — a second sweeper job

| Piece | What it does |
|---|---|
| **Review-deadline scan** | The sweeper (from cut 2) reads the Fraud DB for `PENDING` cases older than **24 hours** and calls Fraud's reject endpoint (the same one the analyst console uses) |
| **Timeout decision** | Fraud rejects the case → `ReviewRejected (mode: timeout)` → cut 1's compensation voids and releases |
| **Compare-and-set on the case** | `PENDING → decided` only once, so an analyst approving at the same moment as the sweeper rejecting can't both win |

24 hours is well inside the hold expiry (~7 days), so an order can no longer be approved after its hold lapsed (checklist #6).

### Patch tax

- **Time logic lives apart from the flow:** the deadline is in a sweeper query; the review is in Fraud; the consequence is in Payment and Inventory.
- **Late by up to one scan interval**, and the scan gets heavier as the table grows (needs an index, and a lock so only one sweeper runs).
- **It fails silently:** if the sweeper stops (bad deploy, cron misconfigured), nothing notices — the watchman needs a watchman.
- **Every new time rule is another sweeper query:** unpaid-order auto-cancel, shipping SLA, hold refresh.

**→ Leads to cut 6:** the customer who was told "Order placed" on Friday now gets "order cancelled" on Saturday. The problem was the promise made at checkout.

### Temporal answer

- **A durable timer racing a signal:** the workflow waits for the analyst's decision *or* 24 hours, whichever comes first. The timer is recorded in the workflow's history, survives crashes, and costs nothing while it waits.
- **The timeout is just the other branch:** reject → the saga's compensations (cut 1).
- **No race with the analyst:** the decision is a Signal (or Update) handled by the workflow itself; once the timer has fired and the workflow moved on, a late approval is rejected ("review closed").
- **The rule lives next to the flow** — one line in the same method. A new time rule is another timer, not another job.
- **Testable:** a time-skipping test runs the real 24 hours in milliseconds.

### Demo moment

1. Place a high-value order → the workflow waits → send the analyst's approve signal → it proceeds.
2. Configure a short deadline (e.g., 30s) → don't approve → the timer fires → reject → `void` and `release` in the history. **Kill the worker during the wait** → restart → the timer still fires on time.
3. Run the time-skipping test with the real 24-hour deadline.

### v2 delta

Sweeper gets a second job with a read path to the Fraud DB and a call to Fraud's reject endpoint (redraw in v2 pass). `ReviewRejected` gains `mode: timeout`. Compare-and-set on the review case. No new amber component.

---

## Cut 6 — Checkout says "placed"; the decline arrives later

**Cost rank 6** · Hero flow step 1 · Journal Problem 6

### Incident (traced on v1)

1. `POST /orders` → `PLACED` → `send(OrderPlaced)` → **`201 Order placed`** in milliseconds (G). Nothing has been checked (L7).
2. The customer sees the confirmation page and leaves.
3. Asynchronously: `OrderPlaced` → reserve → `StockReserved` → `authorize()` → `DECLINED` → `PaymentDeclined` (B).
4. Notification emails "payment declined" — seconds later normally, hours later behind a stalled partition (cut 3).

**Cost:** the moment the customer could have fixed it — still on the checkout page, another card in their wallet — is gone. A decline by email becomes a lost sale, and "placed, then declined" damages trust.

### Patch — authorize before confirming (closes checklist #5)

| Piece | What it does |
|---|---|
| **Synchronous checkout** | `POST /orders` → write `PLACED` → **sync call Inventory `reserve`** → **sync call Payment `authorize`** → respond `201 Order placed` or `402 Card declined — try another card` |
| **Event triggers move** | Inventory no longer reacts to `OrderPlaced`; Payment no longer reacts to `StockReserved`. Both still publish `StockReserved` / `PaymentAuthorized` through their outboxes; Fraud still starts on `PaymentAuthorized` |
| **Decline path** | On `402`, the Order service releases the reservation (sync) and sets `PAYMENT_DECLINED` |

### Patch tax

- **Checkout latency and availability now include Inventory, Payment and the gateway.** A 30-second gateway blip (cut 3) is now a 30-second spinner or a timeout at checkout.
- **A timed-out checkout gets retried by the customer** → a second order and a second hold, because `POST /orders` has no idempotency key (the item parked during G is now a real incident) → the patch needs a client idempotency key too.
- **The HTTP request is the only holder of the saga's state.** If the Order service dies after `authorize` succeeded but before it recorded the result, the reservation and the hold exist and nothing knows about them. Cut 2's outbox doesn't help: these are sync calls, not events. The sweeper sees `PLACED` but can't tell what happened.
- **The Order service now orchestrates steps 1–4 over HTTP**, gates step 6 (cut 4), and keeps transition rules — a hand-built orchestrator with no durable state.

**→ Leads to cut 7:** one order's story now spans an HTTP request, sync calls, five services' events, retry topics, a DLQ and two sweeper jobs. Who can answer "what happened to #123?"

### Temporal answer

- **Update-With-Start:** checkout sends one call that starts the order's workflow (workflow ID = order ID) *and* waits on an Update that returns once reserve and authorize have finished — `placed`, or `declined` while the customer is still on the page. The rest of the workflow (screening, shipping) continues asynchronously.
- **Durable from the first call:** the workflow exists before the first activity runs. A crash mid-checkout doesn't orphan a hold — the workflow resumes and either completes or compensates.
- **A retried checkout is the same workflow:** same order ID → the call attaches to the existing workflow (conflict policy: use existing) and gets the same answer. No second order, no second hold.
- **Honest constraint:** the response still waits for the gateway — a slow gateway is still a slow checkout, and if no worker is available the call times out. What changes is that nothing is lost or duplicated when it does.

### Demo moment

1. Check out with a good card → the response arrives after authorization → the history shows screening and shipping continuing afterwards.
2. Check out with a declining card → `declined` in the response → the history shows the reservation released.
3. Send the same checkout twice → one workflow, one hold (mock gateway's hold count = 1).
4. Kill the worker mid-checkout → the client retries → the answer arrives once the worker restarts.

### v2 delta

Sync paths Order service → Inventory and Order service → Payment (redraw in v2 pass). Inventory stops consuming `OrderPlaced`; Payment stops consuming `StockReserved`. `402` response and client idempotency key on `POST /orders`. No new amber component.

---

## Cut 7 — "What happened to order #123?"

**Cost rank 7** · Whole thread · Journal Problem 7

**Traced on the patched system (v1 + cuts 1–6)** — the one cut not traced on pure v1, because its cost is the sum of the others: every patch adds a place to look.

### Incident

A customer writes: "I was told my order was placed three days ago. My card shows a pending charge. Nothing has shipped."

1. Status page: `PAYMENT_AUTHORIZED` — lagging and overwritable (D.1), so support can't trust it.
2. An engineer greps five services' logs by order ID (A), then queries five DBs.
3. Is it waiting on an analyst? Parked in a retry topic or the DLQ (cut 3)? Stuck behind a poison pill? Was an event lost before it reached Kafka (cut 2) — which leaves **no trace anywhere**? Did the sweeper already re-drive it (cut 2), with a new event ID?
4. An hour later, a best guess.

**Cost:** engineering hours per ticket, slow incident response — and it compounds every other cut, because each one is hard to diagnose.

### Patch

| Piece | What it does | Kind |
|---|---|---|
| **Distributed tracing** | Trace context propagated through HTTP calls and Kafka headers; spans per handler | Logic; backend is org-provided, not drawn |
| **Order timeline** | The Order service appends every event it consumes to an `order_events` table and exposes a timeline per order | Logic; table in the Orders DB |

### Patch tax

- **Trace context has to survive every hop:** HTTP → outbox row → CDC relay → Kafka headers → consumer → retry topic → DLQ. One hop that drops it breaks the trace; a DLQ replay or a sweeper re-drive starts a new one.
- **Tracing shows what happened, not what didn't.** A lost event is a missing span. An order waiting on an analyst has no span at all.
- **Sampling** keeps a fraction of traces, and retention is short — the trace you need often isn't there.
- **The timeline is only what the Order service consumed** — the same lagging view as the status, with no retry, DLQ or sweeper state.
- **"What is it waiting on right now?" still has no answer.**

**End of the chain:** 13 amber components, sync paths from four services into the Order service, two sweeper jobs, a gate, transition rules and tracing — and still no single place that knows the order's state.

### Temporal answer

- **Event history per workflow:** every step, its input and output, every retry attempt with its last error, every timer, signal and update, in order — and **what it is waiting on now** (a pending activity on attempt 4, or a timer firing at 18:00).
- **Search attributes:** find orders by business key — order ID, customer, or a custom `OrderStatus` (e.g., all orders awaiting review for more than 12 hours).
- **Every execution, not a sample:** the history is the execution itself, kept for the namespace's retention period after the workflow closes.
- **Honest constraint:** what happened *inside* an activity (the gateway's raw response) is visible only as the activity's input, output or error; logs still matter there, and Temporal's SDKs offer OpenTelemetry interceptors to connect traces when needed.

### Demo moment

1. Open order #123 in the Temporal UI → the full timeline, with the authorize activity pending on attempt 4 and the last error "503" (reusing cut 3's blip).
2. Search with the custom search attribute `OrderStatus = "AWAITING_REVIEW"` → the list of waiting orders.
3. Contrast in one sentence: in v2, the same answer takes five logs, five DBs and three topics.

### v2 delta

Tracing (org-provided, not drawn). `order_events` table + timeline API in the Order service. No new amber component.
