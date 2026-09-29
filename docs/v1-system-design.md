# v1 System Design — Order System as First Shipped

**Status:** Complete · B ✅ · D ✅ · A/C/E ✅ · F ✅ · G ✅ · H ✅ · trace check ✅ · **v1 designed** · 2026-09-29
**Scope source:** `temporal-sa-architecture-v1-v2.md` §9 (locked). Architecture: `architecture-v1.svg` (locked).
**Principle:** size v1 by what the cut-in narratives need, not by how complete it looks. Each item stops at its §9.2 "stop when" rule.
**Order:** B → D → A/C/E → F → G → H → trace check.

---

## B. Event Catalog

One topic, `order-events`, keyed by order ID (details in C). The Order service consumes every event to maintain the status view; it is listed once here, not repeated per row.

### B.1 Events on the hero flow (steps 1–6)

| # | Event | Producer | Produced when | v1 consumers → reaction | Cuts |
|---|---|---|---|---|---|
| 1 | `OrderPlaced` | Order service | Place-order API writes the order (G, E) | Inventory → reserve stock | 2, 6 |
| 2 | `StockReserved` | Inventory | Reservation written | Payment → authorize card (async, L7) | 1, 3 |
| 3 | `PaymentAuthorized` | Payment | Gateway approves the authorization | Fraud → screen (L10) | 1, 3, 5 |
| 4 | `ReviewApproved` (`mode: auto \| manual`) | Fraud | Auto-approve (low value) or analyst approves (high value) | Shipping → book shipment | 4, 5 |
| 5 | `ShipmentBooked` | Shipping | Carrier confirms booking | Payment → capture (L7) | 1, 4 |
| 6 | `PaymentCaptured` | Payment | Gateway confirms capture | — (status only) | 4 |

### B.2 Events off the hero flow

| # | Event | Producer | Produced when | v1 consumers → reaction | Deliberately missing (becomes a cut) | Cuts |
|---|---|---|---|---|---|---|
| 7 | `PaymentDeclined` | Payment | Gateway declines | Notification → "payment declined" email | Inventory does **not** release the reservation | 1, 6 |
| 8 | `ReviewRejected` (`mode`) | Fraud | Analyst rejects | — (status only) | Nobody voids the hold or releases the stock | 5 (L10) |
| 9 | `ShipmentFailed` | Shipping | Carrier rejects booking (e.g., bad address) | — (status only) | Nobody voids the hold or releases the stock | 1 |
| 10 | `OrderCancelled` | Order service | Cancel API sets status CANCELLED (G) | Inventory → release stock · Payment → void hold · Shipping → mark shipment cancelled **if not yet booked** | No consumer checks current state before acting; each reacts independently | 4 |

### B.3 How the gaps land in the cuts

- **Cut 1:** `ShipmentFailed` and `PaymentDeclined` have no compensating consumer → hold and reservation stay in place.
- **Cut 4 (the race, with per-partition ordering):** Shipping is mid-call to the carrier for `ReviewApproved` when the customer cancels. `OrderCancelled` is published **during** the call, so on the partition it lands **before** `ShipmentBooked`. Booking completes → `ShipmentBooked`. Shipping then reads `OrderCancelled`, sees "already booked", logs and skips (v1 has no cancel-shipment). Payment reads in partition order: `OrderCancelled` → **voids**, then `ShipmentBooked` → **capture on a voided authorization** → `AUTH_VOIDED` (permanent) → retried in place forever (C) → another poison pill; the parcel ships **unpaid**. Inventory releases stock for an order that is shipping. *(Other timing: cancel accepted after booking but before the status catches up → capture first, then `void` gets `ALREADY_CAPTURED` → customer charged for a "cancelled" order.)* The Order service shows CANCELLED, then `ShipmentBooked` arrives and overwrites it (last writer wins, no row versioning) → status drift.
- **Cut 5:** a manual `ReviewApproved` / `ReviewRejected` may never be produced; nothing reacts to its absence.
- **Cut 6:** `PaymentDeclined` → Notification sends the decline after checkout already said "placed".

### B.4 Checkpoint vs. §9.1

| Cut | Needs from B | ✅ |
|---|---|---|
| 1 | `ShipmentFailed` with no compensating consumer | ✅ #9 |
| 2 | The event that gets lost | ✅ #1 `OrderPlaced` |
| 3 | What triggers the gateway call | ✅ #2 → Payment |
| 4 | `OrderCancelled` and its v1 consumers | ✅ #10, B.3 |
| 5 | `ReviewApproved` / `Rejected` with `mode` | ✅ #4, #8 |
| 6 | `PaymentDeclined` → Notification | ✅ #7 |
| 7 | — (A: order ID as correlation key) | n/a |

---

## D. Data Model

**Stop rule:** states + the fields a cut reads or changes. No full schemas, no outbox/inbox tables (those are v2).

### D.1 Order status (Orders DB, owned by Order service)

The status is a **projection**: the Order service sets it from each event it consumes, **last writer wins, no version column**. The cancel API writes it directly.

| Status | Set by | Cuts |
|---|---|---|
| `PLACED` | Place-order API | 2 (stuck here), 6 |
| `STOCK_RESERVED` | `StockReserved` | 1 |
| `PAYMENT_AUTHORIZED` | `PaymentAuthorized` — also what the customer sees during a manual review | 1, 5 |
| `APPROVED` | `ReviewApproved` | 4 |
| `SHIPMENT_BOOKED` | `ShipmentBooked` | 4 (overwrites `CANCELLED`) |
| `CAPTURED` | `PaymentCaptured` | 4 |
| `PAYMENT_DECLINED` | `PaymentDeclined` | 6 |
| `REVIEW_REJECTED` | `ReviewRejected` | — (L10) |
| `SHIPMENT_FAILED` | `ShipmentFailed` | 1 |
| `CANCELLED` | Cancel API | 4 |

No transition rules are enforced: any event overwrites any status. The status also **lags reality** by consumer lag (e.g., Shipping has booked, but `ShipmentBooked` is not yet consumed → status still `APPROVED`).

### D.2 Service records

| DB · record | Fields a cut touches | States | Deliberately missing | Cuts |
|---|---|---|---|---|
| Orders DB · `orders` | order ID, total (L10 threshold), status, updated at | D.1 | Row version | 2, 4 |
| Inventory DB · `reservations` | order ID, SKU, qty, status | `RESERVED` → `RELEASED` | Expiry — a reservation lives until something releases it | 1, 4 |
| Payment DB · `payments` (one row per order) | order ID, auth ID, amount, status, hold expires at (from gateway) | `AUTH_PENDING` → `AUTHORIZED` \| `DECLINED` → `CAPTURED` \| `VOIDED` | — | 1, 3, 4, 5 |
| Fraud DB · `review_cases` | order ID, mode (`AUTO` \| `MANUAL`), status, created at | `PENDING` → `APPROVED` \| `REJECTED` | Deadline | 5 |
| Shipping DB · `shipments` | order ID, status, carrier ref, failure reason | `BOOKING` → `BOOKED` \| `FAILED`; `CANCELLED` if cancelled before booking | — | 1, 4 |

### D.3 How the data lands in the cuts

- **Cut 1:** after `ShipmentFailed`: shipment `FAILED`, reservation still `RESERVED` (no expiry → locked indefinitely), payment still `AUTHORIZED` until `hold expires at`.
- **Cut 2:** order stays `PLACED`; no other DB has a row for it.
- **Cut 3:** payment row sits in `AUTH_PENDING` when the gateway call times out; the retry writes the second auth ID over the first → the first hold is orphaned (nobody knows to void it).
- **Cut 4:** shipment is `BOOKING` while the carrier call is in flight; the cancel API reads a lagging `APPROVED` and accepts; payment goes `VOIDED`, so the later capture fails and is retried forever; order status flips `CANCELLED` → `SHIPMENT_BOOKED`.
- **Cut 5:** review case `PENDING` with no deadline; meanwhile the payment's `hold expires at` passes.

### D.4 Checkpoint vs. §9.1

| Cut | Needs from D | ✅ |
|---|---|---|
| 1 | Reservation, payment (auth ID, hold expiry), shipment records | ✅ D.2 |
| 2 | Order status | ✅ `PLACED` |
| 4 | Status state machine, no row versioning | ✅ D.1 |
| 5 | Review case (created at, no deadline) | ✅ D.2 |

## A. Event Envelope

| Field | Meaning | Cuts |
|---|---|---|
| `eventId` | UUID, **generated by the producer at publish time** — a re-publish gets a new ID | 2, 3 |
| `orderId` | Partition key; also the correlation key (no separate correlation ID) | 3, 7 |
| `type` | Event name from B | all |
| `occurredAt` | Producer timestamp | 7 |

Payload per event: not specified (schema details out of scope).

## C. Topic and Consumer Semantics

| Rule | v1 behavior | Consequence | Cuts |
|---|---|---|---|
| Topic | One topic `order-events`, keyed by `orderId` (L1) | All events of one order are in order on one partition, behind every other order on that partition | 3, 4 |
| Consumer groups | One per service; each reads every event, handles the types it cares about (B) | — | — |
| Processing | Handler runs inside the poll loop, **including sync third-party calls** (L6) | The partition waits while a carrier or gateway call is in flight | 3, 4 |
| Offset commit | **After** the handler finishes (at-least-once) | A crash before commit → the event is redelivered and re-processed | 2, 3 |
| Failure | **Retry in place**, fixed backoff, no attempt limit (L3) | A 30s gateway blip stalls every order behind it; a message that never succeeds stalls the partition forever (poison pill) | 3 |
| Dedup | **None** — handlers do not check whether they already processed an event or webhook | Redelivery repeats every side effect | 2, 3 |
| Webhooks | Gateway and carrier call an HTTP endpoint on the service; handled directly, no dedup | Redelivered webhooks are processed twice | 2 |

## E. Write-then-Publish

Every producing service (L8, L9) follows the same sequence and shares one Kafka producer setup: **`send()` is fire-and-forget** — it drops the event into the producer's in-memory buffer and returns; the client library ships the buffer to Kafka a few milliseconds later. Nobody waits for Kafka's acknowledgement (client default, chosen for latency). Payment shown, since it has all the steps:

```
1. Receive trigger        StockReserved from Kafka
2. Write DB               payments row → AUTH_PENDING          (commit)
3. Call third party       gateway.authorize(...)               (sync)
4. Write DB               payments row → AUTHORIZED + auth ID  (commit)
5. Publish                send(PaymentAuthorized)              (into buffer, not awaited)
6. Commit offset          handler returned → StockReserved marked consumed
   … a few ms later       buffer flushed to Kafka
```

The Order service's place-order: API request → write order `PLACED` → `send(OrderPlaced)` → respond `201` → buffer flushed a few ms later.

A normal shutdown flushes the buffer. Losing it takes a **hard kill**: OOM kill, node loss, shutdown grace period exceeded.

### E.1 Crash points — two outcomes

| Where it crashes | API-driven producer (Order service) | Event-driven producer (Inventory, Payment, Fraud, Shipping) |
|---|---|---|
| **Hard kill after the reply / offset commit, before the buffer flushes** | **Event lost; the customer already holds `201 Order placed`.** Order sits in `PLACED` forever. → **Cut 2** | **Event lost; the offset is already committed**, so Kafka never redelivers. The order stalls mid-flow — e.g., `AUTHORIZED`, never screened or shipped; the hold lapses. → **Cut 2** |
| Between DB write (4) and offset commit (6) | Before the reply: customer sees an error, retries, gets a working order; the first is a harmless leftover `PLACED` row | Offset not committed → **redelivered** → handler re-runs with no dedup → **side effects repeat** (second reservation, second authorization) and the event is published again with a new `eventId` |
| Between third-party call (3) and DB write (4) | n/a | Gateway approved, DB still `AUTH_PENDING` → redelivered → **authorizes again** → orphaned hold (cut 3) |

**What this means for the cuts:** every producer can **lose** events (the unflushed buffer), and event-driven producers can also **repeat** side effects (redelivery). The outbox fixes the loss — the event is written in the same DB transaction and the relay retries until Kafka has it. The inbox (cut 2's patch tax) fixes the repeats — and it only works once the outbox makes `eventId` stable, since v1 mints a new one on every re-publish.

### A/C/E checkpoint vs. §9.1

| Cut | Needs | ✅ |
|---|---|---|
| 2 | A event ID · E sequence + crash point · C commit-after-process | ✅ A, E.1 row 1, C |
| 3 | A event ID, partition key · C commit-after-process, retry in place, no dedup | ✅ A, C, E.1 rows 1 and 3 |
| 7 | A order ID as correlation key | ✅ A |

## F. External Contracts

**Stop rule:** operations + the error classes each cut triggers. All third-party calls are synchronous (L6); webhooks come back asynchronously.

### F.1 Card gateway (called by Payment)

| Operation | Success | Errors | v1 handling | Cuts |
|---|---|---|---|---|
| `authorize(amount, card, idempotencyKey?)` | `APPROVED` + auth ID + hold expires at (typically ~7 days, varies by card network) | `DECLINED` (business outcome) · `5xx` (transient) · **timeout = unknown outcome** (the hold may exist) | Declined → `PaymentDeclined`. 5xx and timeout → exception → retry in place (C). **Idempotency key is supported, but v1 does not send one**, so a retry after a timeout creates a second hold | 1, 3, 6 |
| `capture(authId)` | `CAPTURED` | `5xx`, timeout · **`HOLD_EXPIRED`**, **`AUTH_VOIDED`** (both permanent) | Every error → retry in place | 4, 5 |
| `void(authId)` | `VOIDED` | **`ALREADY_CAPTURED`** · `HOLD_EXPIRED` | Log and skip | 1, 4 |
| Webhook `authorization.expired` | — | At-least-once, may redeliver | Payment logs it; no state change — nobody notices the hold lapsed | 1, 5 |

### F.2 Carrier API (called by Shipping)

| Operation | Success | Errors | v1 handling | Cuts |
|---|---|---|---|---|
| `book(address, parcel)` | `BOOKED` + carrier ref; takes seconds | **`REJECTED`** (invalid address, restricted item — permanent) · `5xx`, timeout | Rejected → `ShipmentFailed`. 5xx and timeout → retry in place | 1, 4 |
| `cancelBooking(carrierRef)` | `CANCELLED` | — | **Exists, never called in v1** (no cancel-shipment) | 4 |
| Webhook `tracking.updated` | — | At-least-once, may redeliver | Shipping updates carrier status on the shipment; beyond hero step 6, no cut uses it | — |

### F.3 Analyst console (Fraud review ← Ops analyst)

Internal UI over the Fraud DB. Lists `PENDING` manual cases; approve / reject → Fraud writes the case and publishes `ReviewApproved` / `ReviewRejected` (`mode: manual`). **No deadline, no escalation, no reminder**; analysts work business hours. → Cut 5.

### F.4 How the contracts land in the cuts

- **Cut 1:** carrier `REJECTED` → `ShipmentFailed`; `void` is never called, so the hold stays until `authorization.expired`, which v1 ignores.
- **Cut 3:** gateway timeout → retry in place stalls the partition; the retry authorizes again without an idempotency key → second hold, first one orphaned (D.3).
- **Cut 3, poison pill (from cut 5):** `capture` returns `HOLD_EXPIRED`, which is permanent, but v1 retries every error in place → the partition is stuck forever, and the parcel has already shipped unpaid.
- **Cut 4:** `void` from `OrderCancelled` runs first; `capture` after `ShipmentBooked` gets `AUTH_VOIDED` → retried forever → partition stuck, parcel ships unpaid; `cancelBooking` is never called. *(Other timing: capture first, `void` gets `ALREADY_CAPTURED` → logged, skipped.)*
- **Cut 5:** the analyst never acts; the hold lapses. If the case is approved later, Shipping books, and `capture` gets `HOLD_EXPIRED`.

### F.5 Checkpoint vs. §9.1

| Cut | Needs from F | ✅ |
|---|---|---|
| 1 | Gateway `void`, hold expiry; carrier error classes | ✅ F.1, F.2 |
| 3 | Decline vs. 5xx vs. timeout-unknown; idempotency key supported but unused | ✅ F.1 |
| 5 | Analyst console | ✅ F.3 |

## G. Customer-Facing APIs

**Stop rule:** request/response, including what each response promises. All three are synchronous calls to the Order service; everything behind them is asynchronous.

| API | Does | Responds | What the response promises vs. what is true | Cuts |
|---|---|---|---|---|
| `POST /orders` (place) | Write order `PLACED` → publish `OrderPlaced` (E) | `201` + order ID, "Order placed" — in milliseconds | **Promises:** your order is placed. **True:** nothing is checked yet — stock, card and fraud all run later. A decline reaches the customer by email after they have left the page: seconds normally, hours when consumers lag or a partition is stalled (cut 3) | 6 |
| `GET /orders/{id}` (status) | Read the status projection (D.1) | `200` + status, updated at | **Promises:** where your order is. **True:** the last event the Order service consumed — lagging, and overwritable out of order | 4 |
| `POST /orders/{id}/cancel` | If status is `PLACED`, `STOCK_RESERVED`, `PAYMENT_AUTHORIZED` or `APPROVED` → set `CANCELLED` → publish `OrderCancelled`; otherwise `409` | `200`, "Order cancelled" | **Promises:** it is cancelled. **True:** the check ran against a lagging status, and the cancel only takes effect when each consumer gets to it — Shipping may already be `BOOKING` | 4 |

Both write APIs follow the API-edge rule in E.1: a crash between the DB write and the publish loses the event (for cancel: the customer is told "cancelled", no service ever hears it).

### G.1 How the APIs land in the cuts

- **Cut 4:** customer cancels while Shipping is mid-call to the carrier; status still reads `APPROVED` → `200 Order cancelled`. Then B.3's race: voided, booked, capture fails forever, status flips to `SHIPMENT_BOOKED`. The customer was told "cancelled" and receives a parcel they never pay for; the partition is stuck behind the failing capture.
- **Cut 6:** `201 Order placed` → customer leaves → `PaymentDeclined` → decline email later.

### G.2 Checkpoint vs. §9.1

| Cut | Needs from G | ✅ |
|---|---|---|
| 4 | Cancel API | ✅ cancel row, G.1 |
| 6 | Place-order response and what "placed" promises | ✅ place row, G.1 |

## H. Explicit Absences

**Stop rule:** one line per cut. Each absence is already stated where it lives (B–G); H collects them and maps each to the v2 patch that fills it. This doubles as the **v2 − v1 = cut-ins** check: every v2 patch fills an absence, and every absence is filled by a cut's patch.

| Cut | What v1 does not have | Stated in | v2 patch that fills it |
|---|---|---|---|
| 1 | No compensation: nothing consumes `ShipmentFailed`, `PaymentDeclined` or `ReviewRejected` to void the hold or release the stock; `cancelBooking` never called; reservations never expire | B.2, D.2, F.1–F.2 | Compensating events (logic) |
| 2 | No outbox (API edge loses events; event-driven producers repeat side effects); no dedup of redelivered events or webhooks; nothing finds orders stuck in `PLACED` | C, E.1, G | 5 outboxes + CDC relay; 5 inboxes (patch tax); sweeper |
| 3 | No error classification (permanent errors retried like transient ones); no retry limit, retry topic or DLQ; no idempotency key to the gateway | C, F.1 | Retry topics + DLQ; idempotency keys (logic) |
| 4 | No consumer checks current state before acting (on `OrderCancelled` or before booking); no row version on the order status; cancel checks a lagging projection | B.3, D.1, G | Cancel check + row versioning (logic) |
| 5 | No review deadline, escalation or reminder; hold expiry (`authorization.expired`) ignored | D.2, F.1, F.3 | Sweeper |
| 6 | No check before "placed": authorization runs after the response | G, L7 | Decided in the cut-in round |
| 7 | No tracing, no order timeline; the only thread is grepping five services' logs by order ID | A | Distributed tracing + order timeline (logic) |

### H.1 Reverse check — every v2 component fills an absence

| v2 component (amber) | Count | Fills cut |
|---|---|---|
| Outbox | 5 | 2 |
| CDC relay | 1 | 2 |
| Inbox | 5 | 2 |
| Retry + DLQ | 1 | 3 |
| Sweeper | 1 | 2, 5 |
| **Total** | **13** | Every amber component maps to a cut ✅ |

## Trace Check

Each link names its trigger → DB write → outgoing event. **Assumption:** each event carries what its next consumer needs (total, card token, address, customer email); payloads are out of scope.

### Hero flow, steps 1–6

| Step | Trigger | DB write | Out | ✅ |
|---|---|---|---|---|
| 1–2 Place, record | `POST /orders` (G) | `orders` → `PLACED` | `OrderPlaced` → `201` | ✅ |
| 3 Reserve | `OrderPlaced` → Inventory | `reservations` → `RESERVED` | `StockReserved` | ✅ |
| 4 Authorize | `StockReserved` → Payment | `payments` → `AUTH_PENDING` → gateway `APPROVED` → `AUTHORIZED` | `PaymentAuthorized` | ✅ |
| 5 Screen | `PaymentAuthorized` → Fraud | `review_cases` → `APPROVED` (auto) or `PENDING` → analyst → `APPROVED` | `ReviewApproved (mode)` | ✅ |
| 6 Book, capture | `ReviewApproved` → Shipping; `ShipmentBooked` → Payment | `shipments` → `BOOKING` → carrier `BOOKED`; `payments` → `CAPTURED` | `ShipmentBooked`; `PaymentCaptured` | ✅ |

The Order service updates the status projection (D.1) on every event.

### Cuts

| Cut | Trace | Result |
|---|---|---|
| 1 | Carrier `REJECTED` → `shipments FAILED` → `ShipmentFailed` → status only; reservation stays `RESERVED`, payment `AUTHORIZED` until the hold lapses; `authorization.expired` ignored | ✅ |
| 2 | `POST /orders` → `PLACED` committed → `send(OrderPlaced)` into the buffer → `201 Order placed` → pod OOM-killed before the flush → event lost, customer holds a confirmation, order sits in `PLACED` forever. Mid-flow: Payment `AUTHORIZED` → `send(PaymentAuthorized)` → offset committed → killed before flush → no redelivery; never screened or shipped; hold lapses (journal Problem 2) | ✅ **Break 1 fixed** |
| 3 | `StockReserved` → `AUTH_PENDING` → gateway timeout → retry in place stalls the partition → re-authorize with no idempotency key → second auth ID overwrites the first → orphaned hold. Poison pill: `capture` → `HOLD_EXPIRED` → retried forever | ✅ |
| 4 | Customer cancels while Shipping is mid-call (status `APPROVED`) → `OrderCancelled` is published **during** the call, so on the partition it lands **before** `ShipmentBooked`. Payment reads `OrderCancelled` first → **voids**, then `ShipmentBooked` → **capture on a voided authorization**. → `AUTH_VOIDED`, retried forever; parcel ships unpaid (fixed in B.3, D.3, F.1, F.4, G.1) | ✅ **Break 2 fixed** |
| 5 | `review_cases PENDING`, no deadline → status shows `PAYMENT_AUTHORIZED` → hold lapses, ignored → late approval → book → `capture` → `HOLD_EXPIRED` → poison pill | ✅ |
| 6 | `201 Order placed` → customer leaves → `PaymentDeclined` → Notification emails the decline: seconds, or hours behind a stall | ✅ |
| 7 | The only thread is the order ID in five services' logs; nothing shows a lost event or what the order is waiting on | ✅ |

### Fixes applied

- **Break 1 (cut 2):** E switched to a fire-and-forget `send()` shared by every producer. Loss now happens at the API edge (customer holds `201`) and mid-flow (offset committed, event unflushed); redelivery duplicates still occur on earlier crashes. Partly reverses Finding 4 (arch. rev 9); v2 unchanged.
- **Break 2 (cut 4):** cancel published mid-call lands before `ShipmentBooked` → void first, then `capture` fails with permanent `AUTH_VOIDED`, retried forever → parcel ships unpaid, partition stuck. Fixed in B.3, D.3, F.1, F.4, G.1.

**Result:** hero flow 1–6 and all 7 cuts trace end to end. v1 is designed.
