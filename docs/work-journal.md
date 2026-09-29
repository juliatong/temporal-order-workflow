# Temporal SA Technical Exercise — Work Journal

**Use case:** E-commerce order workflow
**Last updated:** 2026-09-29
**Status:** Complete — analysis (this journal) → architecture and v1 design → concrete cut-ins → Temporal code.

---

## 1. Decisions Log

| # | Decision | Rationale |
|---|---|---|
| D1 | Use case: **E-commerce order** | Universally understood; saga story is compelling; maps to the most differentiating Temporal values. Must differentiate through depth (saga rigor, idempotency, live failure demo), not breadth. |
| D2 | **Prep runs values-first; presentation runs problems-first** | No real customer here — the audience is a Temporal SA evaluating Temporal understanding, so values are the fixed target. The story must still feel organic: the customer has the pain, Temporal happens to be the answer. |
| D3 | **Guardrail:** every selected problem must stand on its own without Temporal in the sentence | Prevents reverse-engineering from showing. "Customer charged for nothing" is a problem; "we lack durable execution" is a feature wearing a problem costume. |
| D4 | Values are ranked by **differentiation**; problems are ranked by **business cost** | "Well known" and "notorious" are proxies. Differentiation = hard to get from what the customer already has. Business cost = money, trust, engineering hours. |
| D5 | Use **two comparison frames**: vs. homegrown and vs. other durable execution platforms | Durable execution is no longer unique (AWS Lambda durable functions, Step Functions, Inngest, Restate, etc.). Homegrown frame drives the demo; platform frame drives the Q&A. |
| D6 | Showcase **3–4 matches**, not a complete mapping | Each match = one story beat + one demo moment. More dilutes the message and the time budget. |
| D7 | Problem 2 (process dies mid-flow) is **not its own beat** — it is the stress test applied to every beat | Every other problem has a crash variant. "Kill the worker" proves each guarantee holds, instead of being a one-off trick. |
| D8 | *Clarifies D7 (2026-09-29):* in the **v1 → v2 story**, the crash **is** its own incident — cut 2, the dual write that loses an event. In the **Temporal demo**, D7 holds: "kill the worker" is applied across beats (cuts 1, 2, 5). | The cut index (architecture doc) made cut 2 a beat of its own; the Kafka system needs a specific mechanism (outbox, CDC) to explain, while Temporal's answer to it is the same in every beat. |
| D9 | *Clarifies D6 (2026-09-29):* D6 is about the **presentation**. The **build** covers all 7 cut-ins, in business-cost order, so the story can stop after any cut. | Building all 7 cost little once v1 was designed, and it lets a walkthrough follow the reviewer's questions. |

---

## 2. The Process

1. List Temporal's values and rank them by differentiation. ✅
2. For each top value, find the order system problem it solves; rank by business cost; apply the guardrail. ✅
3. Pick one hero flow (the life of one order) and draw the system a team builds without Temporal: v1 as first shipped, v2 after incidents — [`temporal-sa-architecture-v1-v2.md`](temporal-sa-architecture-v1-v2.md). ✅
4. Size v1 by what the cut-in narratives need, then design its behavior and trace every cut through it — [`v1-system-design.md`](v1-system-design.md). ✅
5. Make each cut-in concrete on v1: incident → the team's patch → what the patch costs → Temporal's answer → demo moment. The patches add up to v2′ — [`cut-ins.md`](cut-ins.md). ✅
6. Build the Temporal version test-first: the hero flow, then one increment per cut-in, each with a demo — [`README`](../README.md). ✅

The chain each match must survive:

```
Problem (business cost)
   → How teams usually solve it
      → Why that breaks
         → Temporal value that removes the failure mode
            → Demo moment that proves it live
```

---

## 3. Step 1 — Temporal's Values, Ranked by Differentiation (revised)

**Tier key:** Tier 1 = the demo is built on it. Tier 2 = appears naturally inside the demo. Tier 3 = invisible in a local demo; core answer to "why Temporal over Step Functions / Lambda durable functions?"

| Tier | Rank | vs. homegrown | vs. other platforms | What it means | What the alternatives give you |
|---|---|---|---|---|---|
| **1: Demo core** | **1. Durable execution** | Very high | Table stakes | Workflow progress and local state survive crashes, deploys, and restarts; execution resumes from the event history exactly where it stopped. | **Homegrown:** a status column plus outbox tables, persisted manually after every step, with custom recovery for every "stuck in step X" case. **Platforms:** Lambda durable functions offer the same checkpoint-and-replay model, capped at one year. Step Functions persists state per transition. |
| **1: Demo core** | **2. Saga compensation as code** | Very high | High vs. Step Functions | Undo logic is a try/catch with a stack of compensations, readable top to bottom in one function. | **Homegrown:** choreographed events across services, with no single place that shows the whole flow or its rollback. **Platforms:** Step Functions expresses it through catch states in ASL (verbose and awkward for sagas). Lambda durable functions are code-first, so comparable here. |
| **1: Demo core** | **3. Durable timers** | Very high | Medium | "Wait 24h for fraud review, or react to an approval signal, whichever comes first" is a few lines; the wait costs nothing and survives crashes. | **Homegrown:** cron jobs polling a DB for expired rows, or delayed queue messages (SQS caps delays at 15 minutes). **Platforms:** Step Functions wait states and Lambda durable waits exist, but both have a one-year execution cap. |
| **1: Demo core** | **4. Signals / Queries / Updates / Update-With-Start** | High | High | Running workflows can receive events (Signal), expose state (Query), and handle validated request-response calls (Update). Update-With-Start creates the workflow if needed and can return synchronously while the rest continues asynchronously. | **Homegrown:** DB flags the process polls, plus a separate status table that drifts from reality. **Platforms:** callback/task-token patterns exist, but without a synchronous, validated update with built-in concurrency handling. |
| **2: Supporting** | **5. Visibility and event history** | High | Medium | Every execution has a full, step-by-step event history in the UI; search attributes let you find executions by business keys (e.g., all orders for a customer). | **Homegrown:** correlating logs across services by trace ID, reconstructing what happened after the fact. **Platforms:** Step Functions console and AWS execution history are comparable within AWS. |
| **2: Supporting** | **6. Code-first, testable workflows** | Medium | High vs. Step Functions | Workflows are ordinary code: unit-testable, reviewable, debuggable. Time-skipping tests make a 24h timer run in milliseconds; replay tests catch non-determinism before deploy. | **Homegrown:** logic spread across handlers, cron jobs, and consumers; nothing coherent to test end to end. **Platforms:** Step Functions logic lives in JSON/YAML ASL, separate from business code. Lambda durable functions are code-first with a testing SDK, so comparable. |
| **2: Supporting** | **7. Retries and timeouts as policy** | Medium | Low | Declarative retry policies per activity (backoff, max attempts, non-retryable error types) plus bounded timeouts; retries continue across worker crashes. | **Homegrown:** retry libraries exist everywhere, but retries die with the process. **Platforms:** Step Functions and Lambda durable functions both offer built-in per-step retries. |
| **3: Q&A answer** | **8. Portability, polyglot, no duration cap, Nexus, versioning** | Low (invisible in a demo) | High | Self-hosted or Temporal Cloud, any cloud, many SDK languages. Workflows can run for years via Continue-As-New. Nexus provides service contracts across teams/namespaces; Worker Versioning (GA at Replay 2026) handles safe deploys with in-flight workflows. | **Homegrown:** not applicable. **Platforms:** Step Functions and Lambda durable functions are AWS-only, capped at one year, fewer languages. Cross-team composition requires custom API proxies and auth. |

### Honest constraints (not values — for Step 6 and Q&A)

- **Activities are at-least-once.** A worker can complete an activity and crash before reporting; the activity is retried. Downstream calls must be idempotent.
- **Workflow code must be deterministic** — the price of replay. All I/O, time, randomness go through activities or SDK-deterministic APIs.
- **Event history limits:** hard cap 51,200 events or 50 MB (warning at 10,240 / 10 MB); practical guidance is a few thousand events per execution. Use Continue-As-New for long-lived workflows.
- **Versioning is required** when changing workflow logic with executions in flight (Patching or Worker Versioning).
- **Operational burden** of running workers / self-hosting is the most common adoption objection. (Serverless Workers — Pre-release, 2026 — and Temporal Cloud address this.)
- **Large payloads** don't belong in history. External Payload Storage (Public Preview, 2026) routes them to S3 or custom drivers.

---

## 4. Step 2 — Order System Problems, Ranked by Business Cost

| Rank | Problem (stands alone) | Concrete scenario | Business cost | Value(s) it maps to | Guardrail |
|---|---|---|---|---|---|
| **1** | **Partial failure leaves the order inconsistent across services** | Card authorized and stock reserved, then shipment booking fails (e.g., carrier rejects the address) → nobody voids the hold or releases the stock. Or: stock reserved, payment declined, reservation never released. | **Very high** — reserved stock locked indefinitely (phantom out-of-stock, lost sales); customer sees a pending charge for days until the hold lapses (tickets, trust damage). Triggered by routine business failures, so it happens daily. | #2 Saga (on top of #1) | ✅ |
| **2** | **Orders get stuck or lost when a process dies mid-flow** | A pod is OOM-killed or a node is lost during a peak sale; in-flight orders stop halfway — card authorized but never sent to the warehouse; the hold lapses and the sale is lost. Nobody notices until customers complain. | **Very high** — authorized-but-unfulfilled orders, manual reconciliation, support load; worst at peak traffic. | #1 Durable execution | ✅ |
| **3** | **Transient downstream failures cause lost sales or duplicate charges** | Payment gateway blips for 30s: the order and every order behind it on the partition stall, and customers abandon; or a naive retry after a timeout places a second hold. On recovery, the backlog hammers the gateway. | **High** — a second hold shows the customer two pending charges and eats their available credit; if both are ever captured it becomes a real double charge, the most damaging trust failure in e-commerce. | #7 Retries — **partial fit** | ✅ |
| **4** | **Cancellations and status requests race with fulfillment** | Customer cancels while the shipping label is being created; the hold is voided, the package ships anyway, and the capture fails → parcel shipped unpaid. "Where is my order?" status doesn't match reality. | **Medium-high** — goods shipped unpaid, a stuck partition behind the failing capture, support tickets. | #4 Signals / Queries / Updates | ✅ |
| **5** | **Time-based business rules get missed** | Unpaid orders should auto-cancel after 30 min and release stock; the enforcing cron fails silently over a weekend and thousands of reservations sit on sellable stock. Also fraud review deadlines, shipping SLA breaches. | **Medium-high** — lost sales from held stock, SLA penalties. Often underestimated because it's invisible. | #3 Durable timers | ✅ |
| **6** | **Checkout is either slow or dishonest** | Checkout waits for every downstream step (slow, drop-off), or says "order placed" without validation and emails a failure an hour later. | **Medium-high** — conversion loss or trust loss. | #4b Update-With-Start (early return) | ✅ |
| **7** | **Nobody can quickly answer "what happened to order #123?"** | Support escalates; an engineer spends an hour correlating logs across four services. | **Medium** — engineering time, slow incident resolution; compounds every other problem. | #5 Visibility | ✅ |
| **8** | **Changing the order flow is risky** | Adding a fraud check or split shipments touches several services; refund edge paths break in production; in-flight orders behave inconsistently during deploy. | **Medium** — slower delivery, change-induced incidents. | #6 Code-first testing, #8 Versioning | ✅ |
| **9** | **The order flow crosses team boundaries with no clean contract** | Payments, inventory, shipping owned by different teams; glued with ad hoc APIs/webhooks; every failure becomes a blame discussion. | **Medium, organizational** — matters at scale. | #8 Nexus | ⚠️ Real, larger orgs only → Q&A |
| **—** | *"We're locked into one cloud vendor"* | — | — | #8 Portability | ❌ Buyer/platform concern, not an order problem → Q&A only |

### Observations

- **Value ranking ≠ problem ranking.** Durable execution is value #1, but its problem ranks #2. Saga's problem ranks #1 (locked stock plus a visible pending charge, triggered by routine failures, instantly recognized). The saga story *contains* the durability story: compensation is only reliable if the process survives crashes.
- **Problem 3 requires honesty.** Temporal solves the lost-sale half (durable retries) and helps with retry storms (backoff caps). It does **not** prevent double charges alone — that needs an idempotency key honored by the payment gateway. Present it as: "Temporal makes retries safe to reason about; here's the idempotency key that closes the gap."
- **Idempotency key nuance:** Temporal docs recommend Workflow Run ID + Activity ID. Run ID changes on reset/retry-as-new-run, so for payments a Workflow-ID-based key (order ID + step name) stays stable across runs. Know the default and why you'd deviate.
- **Problem 6 is the sleeper:** e-commerce-specific, newer feature (Update-With-Start), tied to conversion rate. It also corrects an earlier boundary claim — the accurate boundary is "keep *slow* steps out of the synchronous path," not "keep Temporal out of checkout."

---

## 5. How the Problems Fit Together (corrected diagram)

```
        Problem 2: process dies mid-flow  ◄── applies to every node below
                 Durable execution (#1)
          ┌──────────────┼──────────────────┐
          ▼              ▼                  ▼
   Saga (#2)       Timers (#3)      Signals/Updates (#4)
   Problem 1       Problem 5        Problems 4 & 6
      │
      └── Retries + idempotency (#7): Problem 3, at each activity call
          └──────────────┼──────────────────┘
                         ▼
          Visibility (#5): Problem 7, makes all of the above debuggable

   Outside the demo (Q&A only): Problem 8 (versioning, testing), Problem 9 (Nexus)
```

### Problem 2 is the crash variant of every other problem

| Problem | Its crash variant |
|---|---|
| 1. Partial failure | Process dies after charging but before running the refund — compensation exists but never executes. |
| 5. Missed time rules | The cron job or timer-holding process dies; the deadline passes silently. |
| 4. Cancel races | Service restarts while a cancel is in flight; the cancel is lost. |
| 6. Checkout honesty | Process dies after "order placed" but before fulfillment starts. |

**Demo implication:** "Kill the worker" is applied to every beat — mid-compensation (refund still completes), mid-wait (deadline still fires) — so it becomes the proof that every guarantee holds.

---

