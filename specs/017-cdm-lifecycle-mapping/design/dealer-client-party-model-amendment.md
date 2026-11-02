# Design Amendment: Dealer and Client Counterparties, Facing Subsidiaries, Venue as Execution Facility

Amends [`contracts/cdm-mapping.md`](../contracts/cdm-mapping.md) §3 and §6 (and the §1.1 lineage
diagram) for state `017-cdm-lifecycle-mapping`.
Status: approved by the owner, 2026-11-02 (recommendations in §7 accepted).

## 1. Intent and Why

017 currently models every order and trade as **the TraderX account (`Party1`) against the TraderX
Matching Engine (`Party2`)**, with the matcher also the `ExecutionFacility` and the approver of each
order. §6 of the mapping spec justifies this by the matcher filling against the price feed, with no
contra order. In review the owner pointed out that this is not how the system reads:

> TraderX is like a bank's trading system, booking trades on behalf of the entities and branches it
> trades for. The dealer is a party to the trade, the other party is the client (a fund or another type
> of client) buying or selling through the dealer, and the matcher or venue is a third party in another
> role. The matching system can still approve the trade against the market.

TraderX's users (`user01`, …) are a firm's traders acting on accounts, and most seeded accounts are
clients ("Big Corporate Fund", "Hedge Fund TXY1", "Private Clients Fund TTXX"). The venue-as-counterparty
reading is the first thing a CDM reviewer would question, so 017 should show the dealer model.

## 2. CDM facts this relies on (checked in cdm-java 7.8.0)

From `cdm/rosetta/base-staticdata-party-type.rosetta` and `base-staticdata-party-enum.rosetta`:

- A `Trade` has exactly two `counterparty` entries (`Party1`, `Party2`; cardinality 2..2 in
  `TradeValidator`). The labels carry no meaning of their own.
- `PartyRoleEnum` has the roles needed, defined as:
  - `Client`: "Client as defined under ESMA MIFIR. This is generally the investor or other client of an
    investment firm, and is synonymous with the Beneficiary in many circumstances."
  - `ExecutingEntity`: "Entity executing the transaction. …"
  - `BookingParty`: "The entity for which the organization supporting the trade's processing has
    booked/recorded the trade. … a firm still wants to specify their own side."
  - `ExecutionFacility`: "The facility, exchange, or market where the trade was executed. Synonym: …
    Execution Venue."
- `Party.account` (0..1) is an `Account` with `accountNumber`, `accountName`, `accountType`
  (`AccountTypeEnum`: `Client`, `House`, `AggregateClient`), `partyReference` and **`servicingParty`:
  "The reference to the legal entity that services the account, i.e. in the books of which the account
  is held."**
- `Party.businessUnit` (0..*) describes "organization units (e.g. trading desks) involved in a
  transaction".
- `ExecutionTypeEnum.OffFacility`: "Bilateral execution between counterparties not pursuant to the rules
  of a SEF or DCM." CDM's `ExecutionVenue` condition requires `executionVenue` only for `Electronic`.

## 3. Decided by the owner

- **D1. Dealer and client are the counterparties.** `Party1` = the dealer (the facing subsidiary, §4),
  `Party2` = the client (the TraderX account). Principal model: the client buys from or sells to the
  dealer.
- **D2. The venue is not a counterparty.** The matcher is a third party in `parties` with the role
  `ExecutionFacility`, and `executionDetails.executionVenue`, on orders and fills.
- **D3. The venue still approves the order.** The order proposal's `WorkflowStepApproval` stays with the
  matcher: it approves the order against the market (gap G14 unchanged).
- **D4. Direct trades are `OffFacility` with no venue.** A trade-ticket trade is a bilateral booking
  between the dealer and the client, with no `ExecutionFacility` party and no `executionVenue`.
- **D5. Several dealer entities: subsidiaries of the TraderX group.** Three TraderX subsidiaries,
  **NYC, LDN and TKY**, each a separate legal entity in the TraderX group, any of which can be chosen
  to face a client. (First described as branches; they are subsidiaries, which is what makes each a
  counterparty in its own right.)

## 4. Decided: facing subsidiaries

### 4.1 Subsidiaries as parties

Each subsidiary is a legal entity, so each is its own CDM `Party`, mastered by cdm-service like the venue:

| Subsidiary | `partyId` (scheme `https://traderx.finos.org/entity-id`) | name | stored under |
|---|---|---|---|
| New York | `traderx-nyc` | TraderX NYC | `cdm:party:traderx-nyc` |
| London | `traderx-ldn` | TraderX LDN | `cdm:party:traderx-ldn` |
| Tokyo | `traderx-tky` | TraderX TKY | `cdm:party:traderx-tky` |

No LEI: like the venue, the subsidiaries get a TraderX scheme only, so nothing looks like a real
identifier. CDM `Party` has no parent-entity field; group membership is stated in the name and this
document, and is not modelled further.

*Considered and rejected:* one legal-entity party "TraderX Bank" with three `businessUnit`s. That would
fit branches of a single legal entity, but the NYC, LDN and TKY entities are subsidiaries, so each is a
counterparty in its own right and `Account.servicingParty` (a legal entity) can point at it directly.

### 4.2 Which subsidiary faces a client: `Account.servicingParty`

Each client party gains its CDM `account`: `accountNumber` = the TraderX account id, `accountName` = the
display name, `accountType` = `Client`, and **`servicingParty` = the facing subsidiary**. That is CDM's own
field for "in the books of which the account is held", so the choice is visible on the client party, not
in a TraderX side table.

- **Seed assignment** (a new `entities.json` seed next to `people.json`, so it is data, not code):

  | Subsidiary | Accounts |
  |---|---|
  | NYC | 22214 Test Account 20, 52355 Big Corporate Fund, 44044 Trading Account 1 |
  | LDN | 11413 Private Clients Fund TTXX, 62654 Hedge Fund TXY1 |
  | TKY | 42422 Algo Execution Partners, 10031 Internal Trading Book (see §7, Q1) |

  An account created at runtime faces the configured default subsidiary (`NYC`).
- **Choosing a subsidiary:** `PUT /cdm-service/cdm/parties/{accountId}/servicing-party` with
  `{"entity": "traderx-ldn"}` stores a new version of the client party. The CDM tab's party card shows
  the facing subsidiary with a selector that calls it.
- **Trades keep the subsidiary they were booked with.** Each order and direct trade carries its own copy
  of the parties, taken when the event is mapped, and each fill takes its order's dealer (Ruling R75), so
  changing an account's subsidiary affects later orders and direct trades only.
  The lineage APIs report the subsidiary actually on the object.

## 5. Proposed: parties and roles per object

| Object | `Party1` | `Party2` | Buyer / Seller | Other roles | `executionType` | `executionVenue` |
|---|---|---|---|---|---|---|
| Order proposal (and approval, cancel) | facing subsidiary | client | client takes its side, subsidiary the opposite | client `Client`; subsidiary `ExecutingEntity`, `BookingParty`; matcher `ExecutionFacility` | `OnVenue` | matcher |
| Fill (proposed and accepted), its `TradeState` | as the order | client | as the order | as the order | `OnVenue` | matcher |
| Rejected order request | facing subsidiary | client | as requested, if any | as the order | `OnVenue` | matcher |
| Direct trade | facing subsidiary | client | as booked | client `Client`; subsidiary `ExecutingEntity`, `BookingParty` | `OffFacility` | none |

- `parties` lists the subsidiary, the client and (except on direct trades) the matcher.
- Settlement payout: the seller pays (delivers) and the buyer receives, by counterparty label as today,
  with the labels swapped.
- Positions stay the client's: `PositionView` finds the account as the `Party2` counterparty (or the
  `Client` role) instead of `Party1`, and the sign still comes from `Buyer`/`Seller`.
- `WorkflowStepApproval.party` stays the matcher (D3).

## 6. Impact

- **cdm-service:** subsidiary parties and the `entities.json` seed in the party master; `Party.account` on
  client parties; the counterparty and role builder (`CdmLifecycle`), settlement payer/receiver
  (`CdmProducts`), the execution-type choice for direct trades (`ExecutionMapper`); read-back in
  `PositionView`, `ExecutionMapper`, `DiagnosticsController`, `LineageController` and `CdmApiSupport`; the
  new `servicing-party` endpoint.
- **APIs:** lineage and trade responses gain `parties.dealer` (the facing subsidiary); `parties.venue` comes
  from the `ExecutionFacility` role and is absent on direct trades. `executionTypeMeaning` texts drop
  "Party2" and gain an `OffFacility` meaning. Documented in `contracts/contract-delta.md` and the
  cdm-service OpenAPI.
- **UI (overlay patch):** party card shows client, facing subsidiary (with the selector) and venue with their
  roles; trade-state page shows the dealer and the venue separately; spec tests updated.
- **Tests:** unit tests for the mappers, party master, position view, lineage and diagnostics; the seven
  golden CDM examples regenerated; scenario script checks for `Party1` = subsidiary, `Party2` = client and the
  venue only as `ExecutionFacility`, plus a subsidiary switch.
- **Data:** the stored objects change shape, so an existing 017 volume needs `--reset-data` once (as for
  the fill-instruction amendment, Ruling R57).
- **Docs:** mapping spec §1.1 diagram, §3, §6 (the "Why the venue is the counterparty" note becomes the
  dealer model, with the old reading kept as an alternative), §10, §11 and §12 where affected; README
  mapping table and screenshots; learning contract wording; new rulings R69 onwards in `research.md`.

## 7. Resolved questions

- **Q1. House accounts.** "Internal Trading Book" (10031) reads as the firm's own book, CDM
  `AccountTypeEnum.House`. **Decided: every seeded account is `Client` in 017, stated as a limitation.**
  Modelling 10031 as `House`, with its fills facing the venue or the market, is a further-state idea.
- **Q2. Per-order choice of subsidiary.** **Decided: chosen per account in 017.** Choosing it per order
  at the trade ticket needs changes to 009's order and trade APIs and screens; a further-state idea.
- **Q3. Subsidiaries or business units.** **Decided: subsidiaries, each its own party** (§4.1). Left for
  the CDM community: whether `Account.servicingParty` is the intended place for the facing entity
  (mapping-spec §11.1).
