# Design Amendment: Fills as Child Execution Instructions, Venue Approval, Trade-State-First Views

Amends `2026-10-29-cdm-lifecycle-integration-design.md` §6.4–§6.6 for state `017-cdm-lifecycle-mapping`.
Status: proposed, for the owner's approval. Date: 2026-10-30.

## 1. Intent and Why

CDM is mostly used as a post-trade model. The owner's view is that it can model pre-trade order flow as
well, and that TraderX is a good place to show this to both the TraderX and FINOS CDM communities. Seeing
the first build running showed two problems with how an order's life reads in CDM today:

- **An empty acceptance event.** The order's "Accepted" step carries a `BusinessEvent` with only dates.
  CDM requires every `WorkflowStep` to carry exactly one of `businessEvent`, `counterpartyPositionBusinessEvent`
  or `proposedEvent` (condition `CounterpartyPositionBusinessEventOrBusinessEventOrProposedEventChoice`,
  cdm-java 7.8.0), and says "a workflow step is accepted if it contains a business event". Nothing has
  executed when the matcher takes an order onto the book, so the event is filler.
- **Repetition and the wrong emphasis.** Each fill's `BusinessEvent` is stored twice: inside its
  WorkflowStep and again under the trade's key. The CDM page draws step → event → trade state as three
  steps, when the step *contains* the event and the event's `after` *is* the trade state.

The owner's model of what happens at a venue, decided in conversation:

> The matcher accepts the instruction, then breaks it into workable orders, which are each instructions
> that eventually become accepted execution business events with a trade state instantiated for each trade.

For securities there is no lifecycle after execution: many executions give many trade states, and their
sum is the account's holding. The trade state is the operative record for a trade's state (in OTC it would
carry the lifecycle under one UTI); the workflow is how it came about.

## 2. CDM's workflow grammar (checked in cdm-java 7.8.0)

These function conditions shape the design; they are enforced when the `Create_*` functions run.

- `Create_ProposedWorkflowStep`, condition `CorrectAction`: if the previous step is a proposal with action
  New or Correct, the new step's action must be `Correct`. Condition `NewAction`: with no previous step, or
  a previous step holding a business event, the action must be `New`.
- `Create_WorkflowStep`, condition `PreviousBusinessEventExists`: the previous step must contain a business
  event ("Use Create_AcceptedWorkflowStep when the previous workflow step is a proposal").

So a CDM workflow runs: proposal → (corrections of that proposal) → one acceptance carrying a business
event → further steps. A new proposal cannot hang off another proposal, and nothing but an acceptance,
a rejection or a correction can follow a proposal.

## 3. Goals

1. The matcher's acceptance of an order is the **venue's approval of the order's proposal**
   (`WorkflowStep.approval`), not an accepted step with an empty event.
2. **Each fill is its own CDM workflow**: a proposed step (a child `ExecutionInstruction` for the fill
   quantity), then an accepted step carrying the execution `BusinessEvent`, whose `after` is the fill's
   `TradeState`. Every accepted step has a real event. Fills link to their order by the order id
   (owner's choice A, 2026-10-30).
3. **The trade's record is its TradeState only**, stored once under `cdm:trade:<tradeId>`, with a
   "produced by" reference to the accepted fill step. The separate stored copy of the `BusinessEvent` goes.
4. **Trade-state-first views**: a trade page opens on its TradeState with a "produced by" link; an order
   page shows the order and its fills as a tree with each fill's trade state inline; a position page lists
   the TradeStates that sum to each holding.
5. The mapping spec explains the model as a **pre-trade use of CDM**, including exchange-messaging
   alignment and the gaps found (§7.6), so both communities can judge it.

## 4. Non-Goals

- OTC lifecycle (a TradeState chain per UTI via `before`): the structure must not block it; not built.
- Block allocation: noted in the spec as the securities case where trade-state lineage matters; not built.
- Changing the TraderX matcher's messages to emit fill proposals. cdm-service reconstructs them (§7.3).
- Migrating data written by the first build. `--reset-data` gives a clean baseline; seed and backfill
  paths rebuild everything in the new shape.

## 5. Anti-Goals

- Any CDM object that validates only because a field is filled with something meaningless.
- Calling a `Create_*` function with a misleading action just to satisfy a condition (e.g. a fill
  proposal marked `Correct` so it can chain onto the order proposal).
- Storing the same CDM object twice under different keys.
- Presenting containment ("this step contains this event, whose result is this trade state") as sequence.
- Claiming the matcher sends fill proposals, or that CDM links the fills to the order. The spec says
  plainly that cdm-service reconstructs the proposals and that the link is by identifier.

## 6. Constraints

Unchanged from the base design: cdm-java 7.8.0, objects built with CDM `Create_*` functions wherever one
exists, every stored version validated (`RosettaTypeValidator`, `Qualify_Execution` on executions), the
existing tables stay authoritative for orders and trades, TraderX's simple APIs and their `cdmRef`s
unchanged, runs from a fork with `generate` → `start` → `test`.

## 7. Decided Design

### 7.1 Order (parent instruction): `cdm:order:<orderId>`

The order's chain holds only versions of its proposal.

| TraderX event | CDM |
|---|---|
| Order submitted | Proposed `WorkflowStep` (`Create_ProposedWorkflowStep`, action New): `proposedEvent` = `EventInstruction` with the order's `ExecutionInstruction` (full quantity, limit as `AssetPrice`). Unchanged from today. |
| Order accepted by the matcher (`NEW`) | A new version of the proposal carrying `approval` = `[WorkflowStepApproval { approved: true, party: venue, timestamp }]`. |
| Order rejected (`/orders/rejected`) | The proposal of the request as received (as today), then CDM's rejected step (`Create_RejectedWorkflowStep`: `rejected` flag, `workflowStatus` Rejected, matcher's reason in the comment), also carrying the venue's `approval { approved: false, rejectedReason }` where CDM permits. |
| Cancel of the unfilled remainder | `Create_ProposedWorkflowStep` with action `Correct` after the latest proposal version: the same instruction, `workflowState` Cancelled, comment giving the cancelled remainder (matcher's `canceledQuantity`) and the executed quantity. CDM's `CorrectAction` condition requires `Correct` here. |

Status, executed, remaining and cancelled quantities stay derived (from the fills and the cancel), as today.

### 7.2 Fill (child instruction): its own workflow under the order

For each booked trade with an `orderId`, a two-step workflow:

1. **Fill proposed**: `Create_ProposedWorkflowStep`, action New, no `previousWorkflowStep`: an
   `ExecutionInstruction` for the fill quantity at the fill price (same product, parties and trade date as
   the order). Event identifier: the order id plus the fill number, under a fill scheme, so fills never
   share an identifier and every fill carries its order id.
2. **Fill accepted**: `Create_AcceptedWorkflowStep` (or `…FromInstruction`), `previousWorkflowStep` = the
   fill proposal, with the execution `BusinessEvent` built by `Create_BusinessEvent` from that child
   instruction. `after` = the fill's `TradeState` (`positionState` Executed). `Qualify_Execution` true.

Both steps are stored under the order's ref so the order page and lineage API read one ref. Fill numbers
follow booking order (trade time, then trade id), so a live run and a `--reset-data` rebuild number them
the same.

### 7.3 Reconstruction, said plainly

The TraderX matcher slices orders internally and publishes only booked trades. cdm-service therefore
creates each fill's proposal and acceptance together when the trade arrives, with near-identical
timestamps. The mapping spec says so. On a central limit order book this matches reality: the engine
proposes and accepts a match in one moment and reports only the fill.

### 7.4 Direct trade (trade ticket, no order): `cdm:direct:<tradeId>`

Proposed (the trade as an instruction) → accepted with the execution event and TradeState: CDM's canonical
two-step flow. The steps live under a new ref kind `cdm:direct:<tradeId>`, so `cdm:trade:<tradeId>` holds
only the TradeState for every trade. `cdm:direct` is a cdm-service lookup address, not a CDM concept.

**Execution type (owner-agreed).** CDM's `ExecutionTypeEnum` on the trade's `ExecutionDetails` describes,
for post-trade workflows, how the trade was sourced. Today every trade says `Electronic`. The amendment sets:

- matcher fills: `OnVenue` ("Execution via a platform … The role and details of the venue are included in
  the party attribute of the trade"; the matching engine is already Party2);
- direct trades: `Electronic` (booked electronically through the trade ticket, without the matcher).

The meaning must be stated wherever the value is shown: the mapping spec (with CDM's definitions quoted),
the lineage API (a short `executionTypeMeaning` text next to the value) and the CDM page (a tooltip or
caption such as "OnVenue — executed by the TraderX matching engine" / "Electronic — booked from the trade
ticket, not matched").

### 7.5 Storage, API, NATS and UI

- `cdm:trade:<tradeId>` holds only the TradeState. "Produced by" (the accepted fill step's ref and
  `storeGlobalKey`) is its own stored field, not the store's version link: version links stay within one
  ref, so idempotency and "latest version" keep their meaning.
- `cdm:position:<accountId>` (the `PortfolioState`) is unchanged in content; the API also returns, per
  security, the contributing TradeStates (trade id, `cdmRef`, quantity, price, side).
- Lineage API: order lineage returns the proposal versions (with approval and any cancel), then fills as a
  list of `{ fill number, proposed step, accepted step, trade state summary }`; trade lineage returns the
  TradeState first, then "produced by" (the fill step and its order, or the direct-trade workflow).
- NATS: `cdm.workflow` carries every step (now including fill proposals and approval versions);
  `cdm.events` carries the `BusinessEvent` taken from each accepted step; `cdm.tradestate` unchanged.
- UI (CDM tab):
  - Order page: a tree. Order proposal → venue approval badge (approved/rejected, reason) → fills nested
    beneath, each "Partial fill N / Final fill N / Full fill (qty @ price) · X of Y filled", showing its
    proposed and accepted steps and the resulting trade state inline (not as further chain steps) → cancel.
  - Trade page: the TradeState first (trade id, quantity, price, parties, security, position state), then
    "Produced by: fill N of order …" (or "direct trade") linking to the order page with that fill highlighted.
  - Position view: per security, the holding and the list of TradeStates that sum to it, each linking to
    its trade page.

### 7.6 Mapping spec additions

- A "CDM as a pre-trade model" section: order proposal → venue approval → child instructions → executions
  → trade states → positions, with CDM's workflow grammar (§2) and why each fill is its own workflow.
- Exchange-messaging alignment (FIX as the example): NewOrderSingle ↔ order proposed; execution report
  New ↔ approval `approved=true`; Rejected ↔ rejected step; Trade (partial/fill) ↔ fill proposed +
  accepted; Canceled ↔ the Cancelled correction. On RFQ, block and bilateral venues the fill's proposal and
  acceptance are two real messages (quote, then client acceptance); same CDM shape.
- Gaps for the CDM community, headline first: no way to link a child instruction to its parent instruction
  (`previousWorkflowStep` cannot do it under the workflow conditions); acceptance of an instruction that
  does not itself execute is expressible only as approval; cancelling an unexecuted proposal is a
  correction with status Cancelled. OTC lifecycle and allocation noted as where trade-state lineage leads.
- Golden examples, decision log and projection-loss table updated to the new shape.

## 8. Left to the Builder

- `Create_AcceptedWorkflowStep` vs `Create_AcceptedWorkflowStepFromInstruction` for fill acceptance.
- How the approved version of the order proposal is produced (`Create_ProposedWorkflowStep` with `approval`
  and action `Correct`, or a builder-copy adding `approval`); whichever validates and reads truthfully.
- Whether the rejected step can also carry an `approval` with `approved=false`; drop it if CDM objects.
- The fill identifier scheme URI and field names in the lineage API, keeping existing fields where they
  still mean the same thing.
- Backfill/recovery adjustments so every path (live, missed orders, missed fills, missed cancels, seeded
  trades) produces the new shape; the scenario tests assert it.
- If any step in §7 fails CDM validation in a way §2 did not predict, stop and report rather than bend it.

## 9. Calls Made by Claude (not confirmed by the owner)

- Rejections keep CDM's rejected step (so any CDM tool reads them as rejected), with the venue's
  `approved=false` approval added where CDM permits.
- Cancel is a `Correct` of the order proposal with status Cancelled, keeping the original instruction and
  stating the cancelled remainder in the comment (not reducing the quantity, which would hit zero for an
  unfilled order).
- Fill numbers follow booking order; fill identifiers are order id plus fill number under a fill scheme.
- "Produced by" is a stored field, not a cross-ref version link.
- Direct trades get their own ref kind `cdm:direct:<tradeId>`.
- No migration of first-build data; `--reset-data` is the documented path.
