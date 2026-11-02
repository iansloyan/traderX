# Research: CDM Lifecycle, Party and Security Model

## Objective

Capture the implementation research context for state `017-cdm-lifecycle-mapping` as a transition from
`009-order-management-matcher` on the `functional` track: FINOS CDM becomes the system of record for
parties, persons and securities, and the order/trade lifecycle is mirrored into lineage-linked CDM
objects.

## Inputs Reviewed

- `spec.md`
- `plan.md`
- `tasks.md`
- `system/architecture.md`
- `system/runtime-topology.md`
- Design: [`design/cdm-lifecycle-integration-design.md`](design/cdm-lifecycle-integration-design.md)

## CDM Version

- Pinned artifact: `org.finos.cdm:cdm-java:7.8.0`.
- 7.8.0 is the latest stable release on Maven Central as of 2026-10-27. `8.0.0` exists only as a
  development line and is not used.
- Runtime wiring uses the Guice module `org.finos.cdm.CdmRuntimeModule`.

## Confirmed CDM Types and Functions

These classes were confirmed in `cdm-java` 7.8.0 and are the ones the mapping uses.

| Area | Class / function |
| --- | --- |
| Execution instruction | `cdm.event.common.ExecutionInstruction` |
| Business event | `cdm.event.common.BusinessEvent` |
| Trade state | `cdm.event.common.TradeState` |
| Execution details | `cdm.event.common.ExecutionDetails` |
| Workflow action | `cdm.event.common.ActionEnum` |
| Workflow step | `cdm.event.workflow.WorkflowStep`, `WorkflowStepApproval` (venue approval), `Workflow` (the `cdm:direct` resolve answer) |
| Execution type | `cdm.event.common.ExecutionTypeEnum` (`OnVenue`, `Electronic`) |
| Workflow step functions | `cdm.event.workflow.functions.Create_ProposedWorkflowStep`, `Create_AcceptedWorkflowStep`, `Create_RejectedWorkflowStep` |
| Event functions | `cdm.event.common.functions.Create_Execution`, `Create_BusinessEvent`, `Create_TradeState`, `Create_NonTransferableProduct` |
| Qualification | `cdm.event.qualification.functions.Qualify_Execution` |
| Product | `cdm.product.template.SettlementPayout` |
| Security | `cdm.base.staticdata.asset.common.Security`, `AssetIdentifier`, `AssetIdTypeEnum` |
| Parties | `cdm.base.staticdata.party.Party`, `NaturalPerson` |
| Positions | `cdm.event.position.PortfolioState` |
| Runtime | `org.finos.cdm.CdmRuntimeModule` |

## Key Decisions

1. Keep state deltas explicit and reviewable in this feature pack: changes to inherited components are
   captured in `generation/patches/0001-state-overlay.patch`; the new `cdm-service` source lives in
   `generation/runtime-overrides/cdm-service/` and is copied in by the render script.
2. Preserve compatibility with predecessor state unless this pack explicitly changes behavior. New
   fields (`cdmRef`) are optional; existing REST payloads, NATS subjects and UI routes are unchanged.
3. Keep generation and runtime steps deterministic and scriptable.
4. `cdmRef` format: `cdm:<kind>:<externalKey>` with kind in
   `party|person|security|order|trade|direct|position`, derived deterministically from TraderX ids.
   `direct` (a direct trade's workflow) is a cdm-service lookup address, not a CDM concept.

## Demo Identifier Scheme

Seeded securities carry synthetic identifiers. They are demo data, not real ISIN/FIGI assignments, and
the UI labels them "demo identifier".

- ISIN: `XX` + 9-character base-36 hash of the ticker + ISIN (Luhn) check digit. The `XX` prefix is not
  an assigned ISO 3166 country code, so the values cannot collide with real ISINs.
- FIGI: `BBG` + 8 characters drawn from a consonants-plus-digits alphabet (no vowels, matching the
  OpenFIGI character rules) derived from a hash of the ticker + FIGI check digit. These are explicitly
  synthetic demo values, not issued by OpenFIGI/Bloomberg, and must never be presented as real FIGIs.
- Exact derivation (`finos.traderx.cdmservice.security.DemoIdentifiers`): take the SHA-256 of the UTF-8
  string `isin:<ticker>` (or `figi:<ticker>`), read it as an unsigned big-endian integer, and write its
  low-order 9 (or 8) digits, most significant first, in the target alphabet. That is base-36
  `0-9A-Z` for the ISIN, and `0123456789BCDFGHJKLMNPQRSTVWXYZ` (31 characters) for the FIGI.
  - ISIN check digit (ISO 6166): letters are converted to two-digit values (`A`=10 … `Z`=35), then
    Luhn runs over the digit string, doubling from the rightmost digit. Tested against `US4592001014`
    (IBM), `US0378331005` (Apple) and `GB0002634946`.
  - FIGI check digit (OpenFIGI): the character values (`A`=10 … `Z`=35) at the 2nd, 4th, … positions
    are doubled, the decimal digits of all values are summed, and the check digit is
    `(10 − sum mod 10) mod 10`. Tested against `BBG000BLNNH6` (IBM) and `BBG000B9XRY4` (Apple).
  - The pinned demo values are `IBM` → ISIN `XXIMQIJLGP45`, FIGI `BBG821ZC5JC3`. An independent Python
    implementation cross-checked them. The values are persisted in the Security masters, so changing
    the scheme re-keys every stored Security.
  - The ISIN and FIGI identifiers carry the scheme `https://traderx.finos.org/demo-identifier`.
- Party identifier scheme: `https://traderx.finos.org/account-id`.
- Person identifier scheme: `https://traderx.finos.org/logon-id`.
- Venue party: external key `traderx-matching-engine`, name "TraderX Matching Engine".

## Security Mapping (cdm-java 7.8.0)

Each TraderX stock is mapped to a `cdm.base.staticdata.asset.common.Security` with these values:

- `assetType = Security`. `AssetBase.assetType` is required and the `Security` condition `AssetType`
  demands it.
- `securityType = Equity`.
- `equityType.equityType = Ordinary`. Every seeded stock is a common share.
- `identifier` (`AssetIdentifier`, `identifierType` from `AssetIdTypeEnum`):
  - **Ticker: `AssetIdTypeEnum.EXCHANGE_CODE`** (Rosetta `ExchangeCode`). It is documented as "the
    identifier follows the symbology set by the exchange which lists the asset", which is what a
    TraderX ticker is.
    - `BBGTICKER` was rejected because it names Bloomberg's own ticker symbology.
    - `RIC` was rejected because it names Refinitiv's.
  - `ISIN`, `FIGI`: the demo values above.
  - Company name: `AssetIdTypeEnum.NAME` ("The name of the product"). CDM's `Security`/`AssetBase` has
    no name attribute.
- Stored in `CdmStore` under `cdm:security:<ticker>`. The ticker is matched exactly and is
  case-sensitive, as reference-data has always matched it.
- Seeding follows the stock list reference-data built before 017: CSV order, `FB` → `META`, preferred
  names, and supplemental tickers, 509 in all. An end-to-end run checked that the list is identical,
  order included.
- Validation result: every Security (and every Party and NaturalPerson of the party master) is
  stored with the result of `CdmValidator` (see CDM Validation below). Early builds used a placeholder
  validator that recorded `VALID` with the message `not validated: placeholder until CdmValidator (Task 8)`;
  rows still carrying it are re-validated when the masters seed (Ruling R16).

## CDM Validation (cdm-java 7.8.0, rune-common 12.23.0)

`finos.traderx.cdmservice.validation.CdmValidator` is the single validator of cdm-service.

- `validate(object)` runs rune-common's `com.regnosys.rosetta.common.validation.RosettaTypeValidator`,
  obtained from `cdmInjector` (so its `ValidatorFactory` is the one `CdmRuntimeModule` binds). It walks
  the whole object tree and, for each model object, applies the generated cardinality validator
  (`<Type>Validator`), the type-format validator (`<Type>TypeFormatValidator`) and every data-rule
  condition of the type (`RosettaMetaData.dataRules`). This is the 017 bar: type/cardinality plus
  data-rule conditions (`<Type>OnlyExistsValidator` is not part of it).
- Result: `VALID` with no messages, or `INVALID` with one message per failure, formatted
  `<path>: <validation type> <rule>: <reason>`, for example
  `Party: CARDINALITY Party: 'partyId' is a required field but does not exist.` or
  `Security: DATA_RULE SecurityDebtSubType: [Security->getDebtType] does exist and is DebtTypeImpl`.
  An exception inside the Rune runtime becomes `INVALID` (`validation error: ...`), never a thrown error.
- `validateExecution(BusinessEvent)` also evaluates `cdm.event.qualification.functions.Qualify_Execution`
  (true when the event has no intent and its primitive instructions are only `execution`, or
  `contractFormation` and `execution`). If it is false the result is `NOT_QUALIFIED` with
  `Qualify_Execution=false` as the first message, followed by any validation failures.
- Every seeded Security, Party (accounts, venue) and NaturalPerson (directory and identifier-only)
  validates as `VALID` (checked by `CdmValidatorTest` over the full seed).
- Re-validation (Ruling R16): `put`/`putNext` treat identical content as a retry and write nothing, so
  they cannot record a new result for unchanged content. `CdmStore.revalidate(ref, type, globalKey,
  result)` does: under the ref's lock, if the latest version still has `globalKey` and a different
  result, it inserts a row with the same content, global key and `previous_global_key` as the row it
  re-validates (a repeat of that version with a new result; never a self-link, so lineage stays
  acyclic and a `put` retry of that version stays idempotent).
  Masters call it, through `master.Revalidation`, for rows loaded at seed time that still carry the
  placeholder message. Validation status is data, not a gate: an `INVALID` result or a failure to
  record it never stops a master becoming ready.

## Party Mapping (cdm-java 7.8.0)

TraderX accounts, account users and persons map to `cdm.base.staticdata.party` types. The party
master is in cdm-service's `party` package (`PartyMapper`, `PartyMaster`).

**Account → `Party`** (stored under `cdm:party:<accountId>`):

- `partyId`: one `PartyIdentifier` whose `identifier` is the account id, with scheme
  `https://traderx.finos.org/account-id`. `identifierType` is left empty: `PartyIdentifierTypeEnum`
  has only `BIC`, `LEI` and `MIC`.
- `name`: the display name (absent when the display name is null, which 009 allowed).
- `meta.externalKey`: the account id.
- `Party.account` (`cdm...party.Account`) is not used. It would only repeat the id and name, and the
  CDM docs scope it to "a contract or legal document".

**Account user → `Party.person` + `Party.personRole`.** This is the idiomatic CDM 7.8.0 construct
for "persons acting for a party". `Party` is documented as able to "associate a person (or set of
persons)", with `person` = "the person(s) who might be associated with the party" and `personRole` =
"the role of the person(s)".

- Each user is an **identifier-only** `NaturalPerson` in `Party.person`. It carries only the logon
  id identifier and `meta.externalKey` = logon id (`PartyMapper.identifiedPerson`).
- Each user also has a `NaturalPersonRole` in `Party.personRole`:
  - `personReference.externalReference` = the logon id. A Rune `[metadata reference]` to the
    person's key.
  - `role = [NaturalPersonRoleEnum.TRADER]`. A TraderX account user is a person who may trade the
    account.
- Persons and roles are sorted by logon id, so the same set of users is always the same document
  (same global key).

Alternatives rejected:

- `PartyRole`/`Counterparty`: these give a party a role in a trade. They do not relate a person to
  a party.
- `RelatedParty`: this relates parties, not persons.
- `NaturalPersonRoleEnum` values `DECISION_MAKER` / `EXECUTION_WITHIN_FIRM`: these are MiFIR
  reporting roles for a specific transaction, not standing permissions.

**Single person authority (Ruling R19).** `cdm:person:<logonId>` is the only place a person's data
lives. An account Party never embeds a copy of person data: it holds identifier-only persons. So
when a person changes, no account Party has to be rewritten, and no Party can show stale data.

- `GET /cdm/parties/{id}` resolves each embedded person against the person master at read time
  (`PartyMaster.resolvedParty`) and returns the full persons.
- A username that has no people-service record (009's `PUT /accountuser/` allowed this) is stored
  in the person master as an identifier-only `NaturalPerson`, so its `cdmRef` `cdm:person:<name>`
  always resolves. The seed does the same for table usernames that are missing from
  `people.json`. Such a person is valid: the `NameOrIdChoice` condition is met by `personId`.

**Length limits.** 009's columns limited `accounts.displayname` to `VARCHAR(50)` and
`accountusers.username` to `VARCHAR(15)`. The party master still writes those rows, so it checks
both limits before writing anything. A longer value is rejected with 400, and account-service
passes the 400 on.

**Person → `NaturalPerson`** (stored under `cdm:person:<logonId>`, seeded from people-service's
`MockDirectory/people.json`, copied to `cdm-service/src/main/resources/seed/people.json`):

- `meta.externalKey` = logon id.
- `personId` (`PersonIdentifier`):
  - the logon id, with scheme `https://traderx.finos.org/logon-id`
  - the employee id, with `identifierType = EMPL` ("Employee Identification Number")
  - the department, with scheme `https://traderx.finos.org/department`

  The department is a **TraderX extension**: neither `NaturalPerson` nor `ContactInformation` has
  a department. `BusinessUnit` belongs to a `Party`, not a person, and a standalone person
  document has no party.
- `firstName` / `surname`: `fullName` is split at its first space and joined back with one space, so
  it round-trips exactly. A single-word name goes in `firstName`.
- `contactInformation.email` = `[email]`.
- `photoUrl`: a `personId` with scheme `https://traderx.finos.org/photo-url`. This is a **TraderX
  extension**, stored the same way as the department: CDM has no person photo.
  `ContactInformation.webPage` was rejected, because a photo is not the person's web page and a
  real web page would then be ambiguous with it. Projection-loss table (`contracts/cdm-mapping.md` section 12): `photoUrl` →
  TraderX-scheme `PersonIdentifier`, lossless round trip, not a CDM concept.

**people-service as a projection.** cdm-service serves the people-service directory
from the person master:

- `GET /projections/people` lists persons.
- `GET /projections/people/{logonId}` returns one person, or 404.
- `?search=<text>`: logon id or full name contains the text, ordinal and case-sensitive like 009.
- `?employeeId=<id>`: a list of zero or one.

Each entry is `PersonProjection(logonId, fullName, email, employeeId, department, photoUrl,
cdmRef)`. Lists are ordered by logon id, which is the `people.json` order. people-service keeps
no person data of its own, and its `MockDirectory/people.json` and `JsonDirectoryService` are
removed. It calls these endpoints (`CDM_SERVICE_URL`, default `http://cdm-service:18120`), applies
`Take` itself, adds `cdmRef` to `Person`, and answers 503 when cdm-service is unreachable, not
ready or failing.

**Directory membership (Ruling R20).** Only persons with full directory details (full name, email
and employee id, as every `people.json` person has) are in the directory. Identifier-only persons
(account users that have no directory record, Ruling R19) are not listed, not found by search or
employee id, and `GetPerson`/`ValidatePerson` answer 404 for them. This keeps 009's directory
semantics: account-service's `POST /accountuser/` still validates users against people-service
and rejects them, while `PUT /accountuser/` still accepts any username.

A database seeded by an earlier 017 build holds persons without a photo URL. The seed does not rewrite
stored persons, so those persons project `photoUrl` as `""`. A fresh volume is needed (`--reset-data`, see
`quickstart.md`), as for the R19 change.

**Venue → `Party`** under `cdm:party:traderx-matching-engine`:

- `partyId`: `traderx-matching-engine` with scheme `https://traderx.finos.org/venue-id`.
  `MIC` was not used because the matcher has no real market identifier code.
- `name`: "TraderX Matching Engine".
- `meta.externalKey`: `traderx-matching-engine`.

**Account ids.** account-service's semantics are kept:

- With no id (or an id ≤ 0), the next value of the shared `accounts_seq` is used (start 65000).
  cdm-service now calls `nextval`.
- With a client-supplied id, the account is updated, or created with that id (upsert).

**Seeding.** On every start the party master works through the 009 `accounts`/`accountusers` rows
and `people.json`:

- It creates only the objects that are missing from the store, and loads the rest.
- It also loads every other stored account party, found with `CdmStore.refs(Party.class)`, so
  accounts created in 017 survive a restart.
- After the first seed the store wins: a renamed account is not reverted from the table.
- Readiness is all or nothing, with retries and backoff (`master.SeedRetry`, shared with
  `SecurityMaster`).
- `/health` is `ok` only when both masters are ready (Ruling R18).

**The account tables as foreign-key anchors (Ruling R19).** `trades.accountid` and
`positions.accountid` reference `accounts(id)`, and `accountusers.accountid` references it too.
The party master in cdm-service writes these rows; account-service no longer touches either table.

- The anchor row is written **first**: `createAccount` and `updateAccount` write the `accounts`
  row, and `addUser` writes the `accountusers` row. The CDM Party is written with `putNext` after
  that.
- If the row write fails, no CDM Party is written.
- If the CDM write fails, a row this call inserted is deleted again (best effort). A row that
  stays behind is not an orphan account: the next seed creates its CDM Party from it.
- So every CDM account has its anchor row, and a trade on it never fails the foreign key. The rows
  are never served from; they are read only to seed missing parties.

**Account ids never collide.** `createAccount` skips any `accounts_seq` value that is already an
account in the index, in the CDM store or in the `accounts` table. This can happen when a client
chose that id earlier with `PUT /account/`. Skipped ids are never overwritten. After 20 taken ids
in a row the answer is 409.

## Lifecycle Mapping (cdm-java 7.8.0)

The mappers are in cdm-service's `lifecycle` package (`WorkflowMapper`, `ExecutionMapper`,
`CdmProducts`). They are pure: every timestamp and id comes from the TraderX input, and CDM global
keys (`meta.globalKey`) are computed from content by rune-common's `GlobalKeyProcessStep`, so the
same input always gives the same JSON. Golden examples are in `contracts/examples/` (see below).

**CDM functions used.** `Create_ProposedWorkflowStep` (order proposal, its approved version, fill
proposals, the cancel correction, direct-trade proposals), `Create_AcceptedWorkflowStep` (fill and
direct-trade acceptances), `Create_RejectedWorkflowStep`, `Create_BusinessEvent` (which runs
`Create_TradeState` and `Create_Execution`) and `Create_NonTransferableProduct` (product). Only
their inputs are hand-assembled, plus the few attributes the functions have no input for (listed
under each decision). `Create_WorkflowStep`, which the first build used for its cancel step, is no
longer used.

**Product: cash equity.** `NonTransferableProduct` with one `SettlementPayout` from
`Create_NonTransferableProduct(underlier, payerReceiver)`:

- `underlier` = `Observable -> Asset -> Instrument -> Security` (the master's Security).
- `payerReceiver`: the dealer (the facing TraderX subsidiary) is `Party1`, the client (the account)
  `Party2` (Ruling R69; the first builds had the account `Party1` and the venue `Party2`). The payer
  of a settlement payout delivers the underlier (CDM's FX mapping does the same with the exchanged
  currency), so the seller is the payer: a client buy is payer `Party1`, receiver `Party2`; a sell
  the reverse.
- `settlementTerms`: `settlementType = Physical` (shares are delivered),
  `transferSettlementType = DeliveryVersusPayment`, `settlementCurrency = USD`, and
  `settlementDate.adjustableOrRelativeDate.relativeDate` = 2 business days
  (`periodMultiplier 2`, `period D`, `dayType Business`, `businessDayConvention NONE`) with
  `dateRelativeTo` the trade date (`externalReference "tradeDate"`, the external key on every
  `tradeDate`). No business centre calendar is given: TraderX has none, so the adjusted
  settlement date is not computed.
- `securitySettlementCentre = SecuritySettlementCentre`, `cashSettlementCentre =
  CashSettlementCentre`. The `SettlementPayout` condition `SettlementCentreRequired` demands
  both for a security underlier, and the `SettlementBase` conditions only allow these values (CDM
  types them as the role constants themselves), so they are a required default, not TraderX data.

**Prices and quantities.** One `PriceQuantity` per instruction or trade lot:

- `quantity` = shares, `unit.financialUnit = Share`, an integer.
- `price` = `PriceTypeEnum.AssetPrice`, `unit.currency = USD`, `perUnitOf.financialUnit = Share`,
  `priceExpression = AbsoluteTerms`, scale 3 (half-up).
- On executions the price also has `derivedQuantity` = the gross consideration in USD
  (price x shares, scale 3). CDM's `PriceQuantityTriangulation` checks this product.
- The meta-field wrappers (`FieldWithMetaPriceSchedule`, `FieldWithMetaNonNegativeQuantitySchedule`)
  are built explicitly. The `addPriceValue`/`setQuantityValue` convenience setters of these
  `[metadata location]` attributes leave an empty location key, which fails CDM `KEY` validation.

**Parties on instructions and trades.** (As of Ruling R69; the first builds had the account `Party1`
and the venue `Party2`, the venue taking the other side of every trade.)

- `counterparty`: `Party1` = the TraderX subsidiary servicing the account (its
  `account.servicingParty` when the event is mapped), `Party2` = the client (the account).
- `parties`: the subsidiary, the client and, on orders and fills, the venue, each with identifiers,
  name and external key only. The account users (`person`/`personRole`) are not copied: they are
  entitlements kept by the party master, and copying them would change every trade when a user is
  added.
- `partyRoles`: `Buyer`/`Seller` from the client's side (the subsidiary takes the other one), the
  client `Client`, the subsidiary `ExecutingEntity` and `BookingParty`, and on orders and fills the
  venue `ExecutionFacility`.
- References are `externalReference` = the party's external key (`22214`, `traderx-nyc`,
  `traderx-matching-engine`).
- `executionDetails`: `executionType = OnVenue` for orders and fills, `OffFacility` for direct trades
  (Ruling R70). `executionVenue` is set on orders and fills only: a `LegalEntity` named "TraderX
  Matching Engine" with entity identifier `traderx-matching-engine` (scheme
  `https://traderx.finos.org/venue-id`, `identifierType Other`).

**Identifiers and times.**

- Every TraderX id is an `Identifier`/`TradeIdentifier` with `issuer "TraderX"` and the value's
  scheme: `https://traderx.finos.org/order-id`, `.../trade-id`, `.../fill-id` or `.../rejection-id`.
- Workflow `eventIdentifier`: the order id (rejection id for a rejection, trade id under `trade-id`
  for a direct trade), version 1; the cancel correction is version 2 (CDM `ActionEnum.Correct`:
  "version greater than 1").
- **Fill steps carry two identifiers (Ruling R42):** the order id (scheme `order-id`), which links the
  fill to its order, and the trade id (scheme `fill-id`), which keeps every fill distinct; both version
  1, stable under any arrival order. The fill number shown to users is computed when read, in booking
  order (trade time, then trade id), and never stored. The trade's own `tradeIdentifier` stays the
  trade id under `trade-id`.
- Steps carry `meta.externalKey` `<orderId>:proposed|approved|cancel`, `<rejectionId>:proposed|rejected`
  or `<tradeId>:proposed|accepted`, so `as-key` references (`previousWorkflowStep`) have both a global
  and an external reference.
- Timestamps are `eventCreationDateTime` (`executionDateTime` on accepted fill and direct steps) in UTC
  (millisecond precision). Event dates and trade dates are the UTC date of the TraderX instant.
- The instruction also has `tradeTime` (UTC). `Create_Execution` does not copy `tradeTime` to the
  `Trade`, so the execution time is kept in the event's instruction only.

**Fill-instruction model (design amendment of 2026-10-30).** The first build stored an order as one
chain: proposed → accepted (a business event with dates only) → one execution step per fill →
`Cancel` step, and stored each fill's `BusinessEvent` a second time under the trade's ref. Seeing it
run showed an empty acceptance event and the same event stored twice. The amendment
([`design/fill-instruction-model-amendment.md`](design/fill-instruction-model-amendment.md)) replaced it with the
model below. CDM's workflow grammar, checked in cdm-java 7.8.0, shapes it:

- `Create_ProposedWorkflowStep`, condition `CorrectAction`: after a proposal with action `New` or
  `Correct`, the action must be `Correct`. Condition `NewAction`: with no previous step, or a previous
  step holding a business event, the action must be `New`. CDM's summary: "New -> Correct and
  Correct -> Cancel".
- `Create_WorkflowStep`, condition `PreviousBusinessEventExists`: the previous step must contain a
  business event.
- So a workflow runs proposal → (corrections) → one acceptance carrying a business event → further
  steps. A proposal cannot follow a proposal except as its correction.

**Order submitted (`NEW`): proposal.** `Create_ProposedWorkflowStep`, action `New`, no previous step.
The `proposedEvent` holds one `Instruction` whose only primitive is an `ExecutionInstruction`: the
order quantity at the limit price, `tradeIdentifier` = order id, `executionType OnVenue` (Ruling R44).
CDM has no limit-price concept, so the limit is the instruction's `AssetPrice`; fills carry the real
prices. `Min`/`Max` arithmetic-operator prices were rejected: they mean a formula on an observable, not
"no worse than".

**Order accepted by the matcher (`NEW`): venue approval (Ruling R43).**

- The proposal is stated again with one `approval`: a `WorkflowStepApproval` with `approved: true`,
  `party` = a reference to the venue, and a `timestamp`. It is built by re-running `Create_ProposedWorkflowStep` with
  the proposal's own inputs (same timestamps, the same version-1 identifiers, the same
  `proposedEvent`, action `New`, no previous step) plus its `approval` input, "the approval status of
  all parties on the proposed event". Stored after the proposal under the same ref, external key
  `<orderId>:approved`, store link to the submitted proposal. Validates `VALID`.
- `Correct` was rejected: CDM defines it as "a correction of a prior instance of the transaction
  event", and the venue's approval corrects nothing. With a `previousWorkflowStep`, `CorrectAction`
  would force `Correct`, so the approved version has none.
- Consequence: two version-1 instances with the same event identifier, told apart only by the approval
  (and their external keys). A CDM consumer keyed on (identifier, version) sees both.
- The approval's timestamp is the matcher's `createdAt` (Ruling R50), on the live path and on
  recovery and backfill, so every path stores the same object.
- The first build's accepted step (`Create_AcceptedWorkflowStep` with a hand-assembled business event
  holding only dates, because `Create_AcceptedWorkflowStepFromInstruction` executes the instruction)
  is gone. No step carries an empty event.

**Fills (`PARTIALLY_FILLED`, `FILLED`): each fill is its own workflow.**

- **Fill proposed:** `Create_ProposedWorkflowStep`, action `New`, no previous step, identifiers =
  order id + trade id (above). The `proposedEvent` is a child `ExecutionInstruction`: the trade as booked
  (fill quantity at the fill price with the gross consideration, the same product, parties and trade
  date as the order), `executionType OnVenue`. External key `<tradeId>:proposed`.
- **Fill accepted:** `Create_AcceptedWorkflowStep` with `previousWorkflowStep` = the fill proposal, the
  same identifiers, an `executionDateTime` timestamp at the trade time, status `Accepted`, action `New`
  copied from the proposal, carrying `Create_BusinessEvent(proposal's instruction, no intent, event and
  effective date)` (Ruling R46). `positionState` on the after `TradeState` comes from the TraderX trade
  state (Rulings R35, R37). External key `<tradeId>:accepted`.
  `Create_AcceptedWorkflowStepFromInstruction` would build the same event but leaves no clean place to
  set the position state and the execution timestamp without rewriting its output.
- Why its own workflow: under the grammar a fill cannot hang off the order's proposal. As a proposal
  it would have to be a `Correct` of the order (misleading); as the order's acceptance there could be
  only one. The order link is the order id in the fill's identifiers. CDM has no field for a
  parent instruction (gap G13 in `contracts/cdm-mapping.md`).
- Both fill steps are stored under `cdm:order:<orderId>` so the order's lineage is read from one ref.
  The trade's record is its `TradeState` alone under `cdm:trade:<tradeId>`, with a stored "produced
  by" reference to the accepted step (Ruling R49). The `BusinessEvent` is not stored on its own.
- cdm-service reconstructs the fill proposal: the matcher publishes only booked trades, so the
  proposal and the acceptance are created together when the trade arrives.
- No `before` state: CDM's `Instruction` condition `NewTrade` forbids a before state with an
  execution. Each fill is one new CDM trade.
- Increasing one trade per order with a `quantityChange` for later fills was rejected: such an event
  qualifies as an increase, not an execution, which breaks the `Qualify_Execution` bar. A shared
  `order-id` *trade* identifier was rejected too: identical on every fill of an order, it would make a
  consumer that de-duplicates on trade identifiers merge separate fills. That is why the order id goes
  in the step's `eventIdentifier`, not in the trade's `tradeIdentifier`. `lotIdentifier` and a
  versioned order id were rejected (a lot is a part of one trade; an identifier's version means
  amended terms).
- `ExecutionMapper.fill(orderProposal, trade)` returns a `FillWorkflow` (the two steps plus
  mismatches). It maps against any proposal version of the order (as submitted, approved or
  corrected), refuses a fill step given as the order, and throws for a trade of another order. Side,
  security or account that differ from the order are mapped as booked and added to **both** steps'
  validation as `INVALID` messages (`fill does not match the order: ...`, Ruling R52).
- The order's executed quantity = Σ of its accepted fills' trade lot quantities. The remainder =
  instruction quantity − executed − cancelled.
- **Trade lot price = the booked trade price.** The matcher's `lastExecutionPrice` is **not**
  recorded in CDM. This is a documented gap. Every CDM 7.8.0 home considered misreads it:
  - `TradeState.valuationHistory` (`Valuation`, MarkToMarket) reads as a mark for PnL.
  - A second `AssetPrice` in the trade lot is ambiguous with the trade price.
  - A `valuation`/`observation` primitive breaks `Qualify_Execution`.
  - `ObservationEvent` only holds credit events and corporate actions.
  The value stays in TraderX's order record (`OrderResponse.lastExecutionPrice`).

**Execution type.** CDM's `ExecutionTypeEnum`: `OnVenue` ("Execution via a platform … The role and
details of the venue are included in the party attribute of the trade") for matcher fills and for the
order and rejected-request proposals, whose child fills are `OnVenue` (Ruling R44); `Electronic`
("Execution via electronic execution facility, derivatives contract market, or other electronic
message such as an instant message") for direct trades booked from the trade ticket. CDM's
`ExecutionVenue` condition requires `executionVenue` only for `Electronic`; it is set on both. The
first build used `Electronic` everywhere. The lineage API and the CDM view show the meaning next to
the value (`executionTypeMeaning`).

**Rejected (`/orders/rejected`).**

- A proposal of the request as received (`rejectedProposal`, `executionType OnVenue`). Values that
  are missing are left out, and such a proposal validates `INVALID`, which records the problem.
- A rejected step: `Create_RejectedWorkflowStep` with the reason in `workflowState.comment` and
  status `Rejected`. CDM documents `WorkflowState.comment` as "e.g. to specify why a transaction
  event was rejected by a party". `Create_RejectedWorkflowStep` has no reason input yet ("will
  be further developed to provide the reasons").
- The rejected step repeats the proposal's `proposedEvent`. CDM 7.8.0's `WorkflowStep` choice
  rule (`CounterpartyPositionBusinessEventOrBusinessEventOrProposedEventChoice`, a required
  choice) fails on the bare output of `Create_RejectedWorkflowStep`. `WorkflowStepStatus` allows
  a proposed event on a rejected step, and the rejected instruction is the content that fits.
- **The rejected step also carries the venue's `approval`**, with `approved: false`, `party` = the
  venue, the `rejectedReason` and a `timestamp` (Ruling R45). `WorkflowStep.approval` is allowed on any step and no
  condition objects; `Create_RejectedWorkflowStep` has no approval input, so it is added to the
  function's output. The first build rejected this because the approval would sit on the proposal;
  on the rejected step it records who refused the request and why, in the same field the approved
  order uses, while CDM tools still read the step as rejected.
- **What the matcher rejects:** a payload it cannot take (missing fields, quantity 0 or less). It does
  not check reference data, so an order for an unknown ticker or account is accepted (`NEW`), and
  cdm-service records a lifecycle failure for it instead of a CDM lineage (Ruling R56, gap G17).

**Cancel of the unfilled remainder (`CANCELED`): a correction with status `Cancelled` (Ruling R47).**

- `Create_ProposedWorkflowStep(previousWorkflowStep = the latest proposal version, action = Correct)`:
  the same `proposedEvent` (quantity not reduced), event identifier one version up (2 after an
  approved version 1), timestamp = the cancel, no approval, `workflowState` with status `Cancelled`
  and the comment "Unfilled remainder of N shares cancelled; M executed by fills, which stand". External key
  `<orderId>:cancel`. `CorrectAction` requires `Correct` after a proposal.
- The fills are their own workflows with their own trade states, so they are untouched, and the
  executed quantity stays their sum. The cancel never follows a fill step (guarded).
- The cancelled remainder is the matcher's `canceledQuantity` (Ruling R25). It has no structural field
  (the instruction is kept as it was), so the lineage API reads it back from the comment
  (`WorkflowMapper.canceledQuantity`, Ruling R48) and falls back to instruction − executed, with a
  `quantityWarning`, if it cannot.
- Alternatives rejected:
  - A `Correct` that reduces the instruction to the executed quantity: an order cancelled before any
    fill would get a zero-quantity instruction.
  - `Create_ProposedWorkflowStep` with action `Cancel`: allowed only after a `Correct` proposal.
  - `Create_WorkflowStep` with action `Cancel` (the first build's choice, with the remainder as its
    `proposedEvent`): its `PreviousBusinessEventExists` condition needs a previous step with a business
    event, which the order's proposal is not; the first build met it only by chaining after the
    accepted step with its empty event.
  - A `quantityChange` decrease on a trade: the order has no trade of its own, and the fills must not
    change.
  - Cancelling (or correcting) the execution events: a cancel of an execution means the trade is
    busted, which is the wrong meaning.

**Direct trade (trade ticket, no order).** `ExecutionMapper.direct(trade)` returns the same two steps
as a fill: the proposal (the trade at its booked price, identified by the trade id under `trade-id`,
`executionType Electronic`), then the accepted step with the execution event. They are stored under
`cdm:direct:<tradeId>` (a cdm-service lookup address, not a CDM concept, Ruling R53), and the
`TradeState` under `cdm:trade:<tradeId>` with "produced by" = the accepted step. `cdm:trade:<tradeId>`
therefore holds only the `TradeState` for every trade.

**Validation of the golden examples** (`CdmValidator`, type/cardinality + data rules; accepted steps
also `Qualify_Execution`): all `VALID`. That covers `order-proposed`, `order-approved`, both steps of
`order-rejected`, `fill-proposed`, `fill-accepted` (its event qualified as an execution),
`order-cancel-remainder`, and both steps of `direct-trade`.

**Golden examples** (`contracts/examples/`, Rosetta JSON written by `cdmObjectMapper`):

- `order-proposed`, `order-approved` (the same proposal with the venue's approval), `order-rejected`
  (`[proposal, rejection]`).
- `fill-proposed`, `fill-accepted`: fill 1 of the example order, 750 @ 179.500.
- `order-cancel-remainder`: the `Cancelled` correction after the approved version, 750 cancelled and
  750 executed.
- `direct-trade` (`[proposed, accepted]`, a 200-share sell).

The first build's `order-accepted`, `fill-partial` and `fill-final` were removed with the model they
showed. The goldens are regenerated by `./gradlew test --tests '*ExecutionMapperTest.goldenExamplesMatch'
-Dcdm.writeGolden=true` in the cdm-service source and compared byte for byte otherwise. The build
declares the examples directory as a test input (relative path sensitivity), so editing a golden reruns
the comparison.

## Read APIs, Positions and Backfill (cdm-java 7.8.0)

### Two kinds of global key (Ruling R29)

Two different keys are called "global key", and the APIs keep them apart:

- **`storeGlobalKey`** is the SHA-256 (lower-case hex) of an object's canonical JSON. It is the key
  `cdm_documents.global_key` and `previous_global_key` link versions by, and it is what the lineage,
  trade and resolve APIs navigate by.
  - Related names: `previousStoreGlobalKey`, `stepStoreGlobalKey`.
  - The `X-CDM-Global-Key` response header is always this store key.
- **`cdmGlobalKey`** is the object's Rune `meta.globalKey`, a short hash set by
  `GlobalKeyProcessStep`. CDM-internal references hold this key: `previousWorkflowStep`, and a
  `PortfolioState`'s `lineage.eventReference` and `lineage.tradeReference`.
  - Related names: `tradeCdmGlobalKey`, `executionStepCdmGlobalKey`.

Clients join CDM references on `cdmGlobalKey` and fetch stored versions by `storeGlobalKey`. Every
response that exposes a key carries both kinds:
- the order's proposal versions, cancel and fill steps, and each fill's trade state summary;
- the trade's state and the steps of the workflow that produced it;
- the positions envelope and each of its `trades[]` contributions.

### PortfolioState positions

Positions are derived on read from the latest `TradeState` of every `cdm:trade:` ref whose client
counterparty (`Party2` since Ruling R69) is the account:
- **Sign.** A trade counts plus when the account is the `Buyer` and minus when it is the `Seller`.
  A trade state with neither role for the account, or one that cannot be counted, is listed in
  `skipped`. It is never treated as a sale.
- **Quantity and average cost.** These follow 009 trade-processor's booking.
- **Long and short.** CDM quantities are non-negative (`NonNegativeQuantitySchedule`), so a short
  position is expressed by the product's direction: the account is the payer of the settlement
  payout.

CDM's `Initialisation` condition only allows positions in a state that has a previous state.
- **Linking.** The state with positions references an initial (empty) state through its external
  key, `<accountId>:initial`. Rune assigns no global key to content-less objects, so a global
  reference to the empty state would be null.
- **Validation.** A state with positions validates.

**CDM gap (Ruling R30): an empty PortfolioState cannot be valid in CDM 7.8.0.**
- `Initialisation` requires `positions` to be absent.
- `NonTransferable` (`positions -> product as NonTransferableProduct exists`) is false when
  positions are absent.
- So the initial state always fails `PortfolioStateNonTransferable`.

As a result, `/cdm/positions/{accountId}` and `/cdm/resolve/cdm:position:<accountId>` answer 404
`{"cdmRef","status":"pending"}` for an account with no CDM trades, rather than serving a state that
cannot validate. The mapping spec lists this gap (G4).

### Seed and missed direct trades (Ruling R28)

The 009 seed inserts `trades` and `Positions` rows by SQL, so they were never published on NATS.

**What the backfill does.** After every CDM master is ready, `SeedTradeBackfill` runs alongside
the NATS subscription. Every `trades` row with a null `orderid` whose `cdm:trade:<id>` has no stored
`TradeState` is sent through the lifecycle adapter's direct-trade path. That path is idempotent
and records its failures like live events.
- **Which rows.** It covers the seed rows and direct trades booked while cdm-service was down.
- **Times.** `created` is read as UTC.

**What it skips.**
- Rows in state `Cancelled` are skipped.
- Fills, which have an `orderid`, are not imported as direct trades (see the order-fill pass below).

**Result.** CDM positions of the seeded accounts equal the seeded `Positions` rows.

### Open orders, missed fills and missed cancels (Rulings R26, R31, R32, R41)

- **Open matcher orders (R32).** In 017 the matcher seeds only its `NEW` demo orders
  (`ORDER_MATCHER_SEED_STATUSES=NEW`): the seeded `PARTIALLY_FILLED` and `FILLED` orders have no booked
  trades, so their CDM lineage could never be consistent. At startup cdm-service reads the matcher's
  open orders (`GET {ORDER_MATCHER_URL}/orders`, retrying until the matcher answers) and stores the
  proposal and its venue approval for each order that has no approved proposal in CDM.
- **Missed order fills (R31).** Every `trades` row with an `orderid` and no CDM trade state (booked while
  cdm-service was down) goes through the adapter's booked-trade path, which stores it as a fill workflow
  of its order and recovers the order from the matcher when its proposal is missing.
- **Live recovery (R26).** A booked trade or cancel for an order with no stored proposal reads the order
  back from the matcher (`GET /orders/{orderId}`) and stores the proposal and its approval from it. A sweep
  every 30 s retries parked trades and cancels older than 10 s; an item parked for more than 5 minutes
  is recorded once as a failure.
- **Missed cancels (R41).** After the fills, cdm-service lists the matcher's canceled orders
  (`GET {ORDER_MATCHER_URL}/orders?status=canceled`; the listing's `status` filter accepts `open`, `all`
  or any order status) and, for each order with no `Cancelled` correction in CDM, sends the matcher's
  `CANCELED` response (with `filledQuantity` and `canceledQuantity`) through the adapter's cancel path.
  An order created and canceled entirely while cdm-service was down has no proposal either: its
  proposal and approval are stored from the same response first.
- **Backfill equals live.** Every pass goes through the adapter's live paths, so the backfill stores
  the same objects, with the same keys, links and producers, as the live events would have
  (`SeedTradeBackfillTest.backfillProducesSameDocumentsAsLive`). One listing covers both cases, so no
  per-order request is needed.
- Progress of the four passes (`directTrades`, `openOrders`, `orderFills`, `canceledOrders`) is reported
  in `/cdm/validation-summary` under `backfill`. What is still not recovered is listed under gap G9 in
  `contracts/cdm-mapping.md`.

## Risks and Mitigations

- Risk: behavior drift from predecessor state.
  - Mitigation: state smoke tests and conformance checks.
- Risk: a CDM failure blocking trading.
  - Mitigation: the CDM lifecycle is built asynchronously from NATS; failures are recorded, never
    thrown (NFR-01701).
    The one coupling left is that CDM is authoritative for accounts and securities, so trade submits
    fail while cdm-service is down (Ruling R15, gap G8 in `contracts/cdm-mapping.md`).
- Risk: unclear ownership of implementation deltas.
  - Mitigation: document deltas in requirements/contracts/system artifacts before code generation; the
    ownership table in `generation/generation-hook.md` states which generated paths come from the patch
    and which from `runtime-overrides/`.

## Decision log (R1–R76)

Code comments, tests and documents in this state cite "Ruling Rnn". Each ruling is listed here: the
decision, why it was taken, and its consequence. A few rulings were about the build process rather than
the state itself; they are kept so that every number resolves. Rulings replaced by the fill-instruction
model (R42 onwards) stay in place, marked **Superseded** or **Amended** with the ruling that replaced
them; none is deleted.

- **R1. cdm-service is not part of the state patch.** Patch capture excludes `cdm-service/`
  (`generation/patch-capture-excludes.txt`); its only source is `generation/runtime-overrides/cdm-service/`,
  copied in by the render step. *Why:* one source of truth. *Consequence:* edit cdm-service in
  `runtime-overrides/`, never in the generated tree.
- **R2. The 017 compose directory comes from the patch.** `cdm-lifecycle-mapping/` (compose and
  observability files, project `traderx-state-017`) starts as a copy of 009's `order-management-matcher/`
  and lives in `0001-state-overlay.patch`; the render step does not copy compose files. *Why:* a render
  copy would overwrite patched compose changes, and the patch is never empty. *Consequence:* the 017
  compose files can drift from 009's; changes to 009's compose must be ported by hand.
- **R3. Pending trades have their own table.** Booked trades waiting for their order's accepted step go
  to `cdm_pending_trades(trade_id, order_id, payload, received_at, reported_at)`, not `cdm_documents`.
  *Why:* the CDM store holds only CDM objects. *Consequence:* one small extra table and JPA entity.
  **Amended by R43:** trades now wait for the order's approved proposal; there is no accepted step.
- **R4. people-service (.NET) builds and tests in Docker.** `mcr.microsoft.com/dotnet/sdk:9.0` is used;
  no host .NET SDK is required. *Why:* keeps the runtime prerequisites at 009's. *Consequence:* none.
- **R5. Build-process ruling (review staffing).** It chose how implementation and review work was
  staffed. *Consequence:* no effect on the state's code or behaviour.
- **R6. Build-process ruling (workspace).** The work was done on a feature branch of the main checkout.
  *Consequence:* no effect on the state's code or behaviour.
- **R7. Lineage role `optional`.** The catalog entry uses `primaryLineageRole: optional`. *Why:* the
  catalog validator accepts only `prelude`, `canonical` or `optional`. *Consequence:* 017 is an optional
  branch off 009 until it is proposed upstream.
- **R8. Capturing edits to the generated tree.** Patch-owned edits are captured with
  `TRADERX_PATCHSET_CHILD_FROM_TARGET=1 bash pipeline/create-state-patchset.sh 017-cdm-lifecycle-mapping 009-order-management-matcher`.
  *Why:* the plain form regenerates the child state first and discards uncaptured edits; the variable
  captures the current tree (and checks that it is state 017). *Consequence:* never run
  `generate-state.sh` over uncaptured edits.
- **R9. Ingress route and Prometheus scrape come from the render script.**
  `pipeline/render-state-017-cdm-lifecycle-mapping.sh` adds the `/cdm-service/` ingress route, the
  Prometheus scrape target and the blackbox probe, and fails if they are missing afterwards. *Why:* the
  nested ingress template does not patch cleanly. *Consequence:* the ingress template is excluded from
  patch capture.
- **R10. The pipeline needs ripgrep.** The generator calls `rg`; it must be the real ripgrep on `PATH`.
  *Consequence:* listed with the generator prerequisites (`rg`, `jq`, `perl`).
- **R11. Demo FIGI scheme.** `BBG` + 8 characters from consonants and digits (no vowels, as in real
  FIGIs) + a check digit; synthetic, marked by scheme and labelled "demo identifier" in the UI. *Why:*
  realistic shape without pretending to be real reference data. *Consequence:* see "Demo Identifier
  Scheme" above.
- **R12. Dependency versions from the catalog.** Java services use
  `catalog/dependency-version-targets.json` (Spring Boot 4.0.8, Jackson 3 BOM 3.1.5, Tomcat 11.0.26,
  logback 1.6.3). *Why:* the generator's dependency gate enforces them. *Consequence:* cdm-service
  upgrades with the rest of the services.
- **R13. Guice 6.0.0 and two JSON mappers.** Guice is pinned to 6.0.0 because cdm-java 7.8.0 injects
  with `javax.inject` and Guice 7 silently leaves those fields null. CDM objects are written with
  `cdmObjectMapper` (Jackson 2, Rosetta), which is separate from Spring MVC's Jackson 3 mapper.
  *Consequence:* controllers serialise CDM objects explicitly and return them as `application/json` text.
- **R14. CDM store schema.** `cdm_documents` has a surrogate `id BIGSERIAL` key (also the write order);
  `global_key` is always the SHA-256 of the canonical JSON (never the Rune `meta.globalKey`), indexed but
  not unique; a write is skipped only if the latest version of that ref and type has the same key and
  the same previous key; writes per ref are serialised by an in-process lock. *Why:* a content key as
  primary key broke A→B→A histories, and the Rune key is a 32-bit hash. *Consequence:* one cdm-service
  instance per state; callers must not wrap store writes in their own transaction.
- **R15. No last-known-good cache.** trade-service is unchanged; when cdm-service is down, account and
  reference-data lookups fail and a trade submit answers 500. *Why:* additive-only policy, and CDM is
  authoritative for master data. *Consequence:* gap G8 in `contracts/cdm-mapping.md`; compose starts the
  dependants only once cdm-service is healthy.
- **R16. Re-validation of placeholder results.** At startup, stored master objects still carrying the
  early placeholder validation result are re-validated with the real validator. *Consequence:* no
  stored object keeps an unearned `VALID`.
- **R17. API explorer contracts include `cdmRef`.** The OpenAPI copies served at `/api/docs` for 017
  (`contracts/<service>/openapi.yaml`) carry the optional `cdmRef` and other additive fields.
- **R18. Readiness.** `/health` answers 200 `{"status":"ok"}` only when the security and party masters are
  seeded, else 503 `{"status":"starting"}`; the compose healthcheck uses it. *Consequence:* dependants
  start after seeding.
- **R19. Party master rules.** The party master writes the 009 `accounts`/`accountusers` foreign-key
  anchor rows before the CDM write; `displayName` over 50 characters is a 400; creating an account
  never overwrites a taken id (409 instead); account parties embed identifier-only persons, and
  `cdm:person:<logonId>` is the single person authority; an unknown username becomes an identifier-only
  person so its ref resolves. *Why:* a trade must never fail a foreign key, and person data must not be
  copied into every account. *Consequence:* older 017 data volumes need `--reset-data`.
- **R20. People directory shows directory persons only.** Identifier-only persons are not listed or
  returned by `GetPerson` (404). *Why:* keeps 009's directory semantics, which account-service relies
  on to validate users.
- **R21. Stale data volumes.** The start script detects a 017 data volume created by an older build
  (no `cdm_documents` table) and asks for `--reset-data`, which removes only the 017 volume.
- **R22. Lifecycle mapper signatures.** The mappers take what they need: `cancelRemainder` takes the
  accepted step and the cancel event (for its timestamp), `CdmProducts.cashEquity` takes the side, and
  a separate `rejectedProposal` builds the proposal of a rejected request. *Consequence:* none beyond
  the API shape. **Superseded by R47:** `cancelRemainder` was removed; the cancel is
  `cancelCorrection(latestProposal, canceledQuantity, executedQuantity, at)`.
- **R23. Lineage refinements.** Each execution is its own `WorkflowStep` chained to the previous step;
  the accepted step has workflow status `Accepted` and is not an execution; a cancel keeps the order's
  trade date; the matcher's observed price is omitted (gap G3); an execution whose side, security or
  account differs from the proposal is stored and flagged `INVALID`, never thrown. *Why:* CDM-native
  lineage and honest validation. *Consequence:* golden files in `contracts/examples/`.
  **Partly superseded:** the chained execution steps by fills as their own workflows (R42, R46); the
  accepted step and its empty, dates-only acceptance event by the venue's approval (R43). Still in
  force: the cancel keeps the trade date, the observed price is omitted, mismatched fills are flagged
  (now on both fill steps, R52).
- **R24. The lifecycle adapter never throws.** Every mapper call is guarded; failures are logged,
  counted and stored in `cdm_lifecycle_failures`; each order keeps its own proposed and latest step, so
  fills never chain across orders. **Partly superseded:** fills no longer chain at all (each is its own
  workflow, R42); the adapter still never throws.
- **R25. Matcher quantities.** `OrderResponse` gains optional `filledQuantity` and (on `CANCELED`)
  `canceledQuantity`. *Why:* the matcher sets `remainingQuantity` to 0 on cancel, so the remainder was
  otherwise lost. *Consequence:* additive field on every order event and REST answer.
- **R26. Recovery of missed order events.** The adapter subscribes only after the masters are ready. A
  fill or cancel for an order with no stored proposal reads the order back from the matcher
  (`GET /orders/{id}`) and rebuilds proposed + accepted (**amended by R43:** the proposal and its venue
  approval); a sweep every 30 s retries parked items older
  than 10 s; an item parked over 5 minutes is recorded once as a failure; partial writes resume from the
  last stored step. *Consequence:* a read-only REST dependency on the matcher.
- **R27. Superseded by R32.** It had disabled the matcher's demo order seeding in 017.
- **R28. Startup backfill of direct trades.** `trades` rows with no `orderid` and no CDM trade state are
  imported as direct trades. *Why:* the 009 seed inserts trades by SQL, never on NATS. *Consequence:* CDM
  positions equal position-service's for the seeded accounts.
- **R29. Two kinds of global key.** APIs name the SHA-256 store key `storeGlobalKey` (navigation and
  joins) and the Rune `meta.globalKey` `cdmGlobalKey` (what references inside CDM objects use). The
  `cdm.*` NATS payload's `globalKey` is the store key. See `contracts/cdm-mapping.md` section 8.2.
- **R30. Accounts without CDM trades are pending.** `/cdm/positions/{id}` and `cdm:position:<id>` answer
  404 `pending` for them. *Why:* an empty `PortfolioState` cannot validate in CDM 7.8.0 (gap G4).
- **R31. Startup backfill of missed fills.** `trades` rows with an `orderid` and no CDM execution are
  chained into their order in booking order. *Why:* fills booked while cdm-service was down.
  **Amended by R42:** they become fill workflows of their order (no chain); fill numbers follow booking
  order when read.
- **R32. Matcher seeds `NEW` orders only; open orders are backfilled.** `ORDER_MATCHER_SEED_STATUSES=NEW`
  in 017, and at startup the matcher's open orders without a CDM proposal get proposed + accepted
  (**amended by R43:** the proposal and its venue approval).
  *Why:* seeded partially filled orders have no booked trades, so their lineage could not be
  consistent, while 009's open-order checks still need open orders.
- **R33. Loki on newer Docker Engines.** The bundled promtail uses Docker API 1.42, which newer engines
  reject, so the inherited 009 Loki checks fail there. Shared with 009, documented in `quickstart.md`.
- **R34. Start and reset safety.** The reset and down paths of the 017 scripts are hard-wired to the
  `traderx-state-017` project; a `psql` error is not reported as a stale volume; the render step checks
  that the ingress route and Prometheus entries were inserted.
- **R35. Trade state in CDM (amended by R37).** TraderX's trade state is mapped into
  `TradeState.state.positionState`, and the round-trip test compares `state` and `created`.
- **R36. Deterministic partial fills.** `POST /orders/{id}/force-fill` takes an optional `quantity`
  (absent = whole remainder, as in 009). *Why:* the partial-fill scenario no longer races the matcher's
  tick.
- **R37. `positionState` is always `Executed`.** `New`, `Processing` and `Settled` all map to
  `Executed`; `Cancelled` to `Cancelled`. *Why:* TraderX has no settlement event, and `SETTLED` without a
  CDM transfer would misstate what happened. *Consequence:* the `Settled` label is a documented
  non-lossless field (section 12 of the mapping spec).
- **R38. Behaviour before documentation.** R31 and R37 were implemented before the mapping specification
  and guides were written, so the documents describe final behaviour.
- **R39. Catalog status.** 017 is `status: implemented` with `generation.mode: planned`. *Why:* no
  published generated-code branch exists until the upstream PR; `generate-state.sh` works in that mode.
- **R40. Getting-started link.** The link to the `code/generated-state-017-cdm-lifecycle-mapping` branch
  stays (the docs validator expects it for implemented states); it resolves once that branch is
  published with the upstream PR.
- **R41. Final review fixes.** Claims are scoped (lifecycle mirroring never blocks orders or trades;
  master data depends on cdm-service; "lossless" means the compared fields), this decision log is
  committed, missed cancels are backfilled at startup (G9), the venue-as-counterparty choice is
  explained with alternatives, position-service trade rows carry `cdmRef`, and the CDM view no longer
  claims an order chain it could not load. Deferred to the upstream discussion with the CDM
  maintainers: the `globalKey` name in the `cdm.*` NATS payload, the `cdm.*` subject naming style, and
  the shape of the accepted step's event. **Superseded in part:** the accepted step's event question
  was settled by the fill-instruction model (R43: no accepted step without an execution); the other two
  remain open.

### Fill-instruction model (R42–R68, 2026-10-30)

Rulings made while building the design amendment
[`design/fill-instruction-model-amendment.md`](design/fill-instruction-model-amendment.md). One line each: the
decision — why — the cost if it turns out wrong.

- **R42. Fill identifiers.** Each fill step carries two TraderX `eventIdentifier`s, version 1: the order id (scheme `https://traderx.finos.org/order-id`) and the trade id (scheme `https://traderx.finos.org/fill-id`); the fill number is computed at read time from booking order (trade time, then trade id), never stored; replaces the amendment's "order id plus fill number" — *why:* stable under any arrival order and identical on live and rebuilt data, while the order id is the only available link to the parent (gap G13) — *cost if wrong:* consumers must know the TraderX convention to find a fill's order, and a fill number is not addressable in CDM.
- **R43. The venue's approval re-states the proposal.** The approved version is `Create_ProposedWorkflowStep` re-run with the proposal's own inputs, action `New`, the same version-1 identifiers, plus `approval` (approved, venue, timestamp), and no CDM `previousWorkflowStep`; the store links it to the submitted proposal — *why:* `Correct` would claim the venue corrected the instruction, and a previous step would force `Correct` under `CorrectAction`; the amendment allows any construction that validates and reads truthfully — *cost if wrong:* CDM consumers keyed on (identifier, version) see two version-1 instances of the order's proposal (documented in `contracts/cdm-mapping.md` section 2.1 and gap G14).
- **R44. Orders are `OnVenue`.** The order proposal and a rejected request's proposal carry `executionType OnVenue`, like the fills (the amendment fixed only fills `OnVenue` and direct trades `Electronic`) — *why:* the parent instruction was sent to the venue, and `Electronic` on the parent would contradict its children — *cost if wrong:* one enum value on two step types; the lineage API states its meaning ("Sent to the TraderX matching engine") wherever it is shown.
- **R45. The rejected step carries the venue's approval `approved: false`.** Added to `Create_RejectedWorkflowStep`'s output (it has no approval input), with `rejectedReason`; the step stays CDM's rejected step — *why:* the venue's answer lives in the same field for approved and refused orders, and no CDM condition objects (validates `VALID`) — *cost if wrong:* one builder-added attribute to drop if CDM maintainers read approval as belonging only on proposals.
- **R46. Fill acceptance is `Create_AcceptedWorkflowStep` with an event from `Create_BusinessEvent`.** Not `Create_AcceptedWorkflowStepFromInstruction`; action `New` copied from the proposal — *why:* the same event, with a clean place to set the position state (R35, R37) and the `executionDateTime` timestamp without rewriting the function's output — *cost if wrong:* none in content; a reader may expect the `FromInstruction` variant.
- **R47. Cancel is a `Correct` of the latest proposal version with status `Cancelled`.** The same instruction (quantity not reduced), identifier version = latest + 1, no approval, the cancelled and executed quantities in the comment; never after a fill step — *why:* `CorrectAction` requires `Correct` after a proposal, `Cancel` may only follow a `Correct`, and a reduced instruction would be zero for an unfilled order; the approval belongs to the version it was given on — *cost if wrong:* the remainder has no structural field (R48, gap G15). Supersedes R22's `cancelRemainder`.
- **R48. `canceledQuantity` is read back from the cancel comment.** `WorkflowMapper.canceledQuantity` parses the comment its writer produces; if it cannot, the API derives instruction − executed and says so in `quantityWarning` — *why:* the correction keeps the original instruction, so CDM holds no structural cancelled quantity — *cost if wrong:* prose coupling between writer and reader; the alternative (a correction reducing the quantity) is recorded under gap G15.
- **R49. Store links follow the CDM workflows; "produced by" is its own field.** `previous_global_key` = the step a row follows in its workflow (none for a fill proposal, the fill proposal for its acceptance, the latest proposal version for the cancel, the submitted proposal for the approved version); a `TradeState` stores `produced_by_ref`/`produced_by_global_key`, not a cross-ref version link — *why:* every store link agrees with the CDM link where one exists, and version links stay within one ref so idempotency and "latest" keep their meaning — *cost if wrong:* two extra nullable columns; old volumes need `--reset-data` (R57).
- **R50. The approval's timestamp is the matcher's `createdAt`.** On the live path (the `NEW` event's time) and on recovery and backfill (the matcher's record) — *why:* live and backfill then store identical objects with identical keys (`SeedTradeBackfillTest.backfillProducesSameDocumentsAsLive`) — *cost if wrong:* the approval does not show when cdm-service observed the acceptance; for TraderX's synchronous matcher the two are the same moment.
- **R51. `cdm.events` carries the event taken from each accepted step; no `BusinessEvent` row is stored.** Payload `{cdmRef, globalKey, cdmType, validation, stepGlobalKey, object}`, published right after its step's `cdm.workflow` message; subject names unchanged — *why:* the amendment forbids storing the same CDM object twice, while consumers of `cdm.events` keep getting every execution — *cost if wrong:* the event's `globalKey` is computed, not a stored row's key.
- **R52. A mismatched fill is flagged `INVALID` on both of its steps.** — *why:* both steps carry the mismatching child instruction — *cost if wrong:* the same message counted twice in validation totals.
- **R53. Direct trades live under `cdm:direct:<tradeId>`.** Proposed → accepted under the new ref kind, failures recorded under it, and `resolve cdm:direct` answers a CDM `Workflow` of the two steps built on read (validation header = worst stored step status) — *why:* `cdm:trade` then holds only the `TradeState` for every trade, and `Workflow` is the real CDM type for "these steps" — *cost if wrong:* a cdm-service lookup address that is not a CDM concept, and an unstored resolve answer without `X-CDM-Global-Key`.
- **R54. `resolve cdm:order:<id>` returns the order's own latest version** (the cancel, else the latest proposal version), not the latest row under the ref — *why:* fill steps are stored under the order's ref and are not versions of the order (amendment section 7.1) — *cost if wrong:* callers that expected the latest row (the scenario script and docs were updated).
- **R55. Fill numbers and labels are computed on read.** Booking order (trade time, then trade id); the fill whose running total first reaches the instruction quantity is "Full fill" (if fill 1) or "Final fill N", all others "Partial fill N"; a fill whose accepted step is not stored yet is shown with `accepted: null` and adds nothing to the totals — *why:* one numbering shared by the order and trade pages and identical after a rebuild — *cost if wrong:* an over-filled order (never produced by the matcher) shows "Partial fill" after its "Final fill", explained by `quantityWarning`.
- **R56. Orders for an unknown ticker or account are not rejected in TraderX.** The matcher does not check reference data (inherited 009 behaviour); cdm-service records a lifecycle failure row instead of a CDM lineage; no `INVALID` rejected proposal exists for them, and scenario (f) requires zero `INVALID` and zero `NOT_QUALIFIED` objects — *why:* the review's premise of an `INVALID` rejected proposal for such orders did not hold in the running system — *cost if wrong:* those orders are visible only as failure rows; recorded as gap G17 and flagged to the owner (the matcher should validate ticker and account).
- **R57. No migration of first-build data.** The start script refuses a volume without the `produced_by_*` columns and asks for `--reset-data`; seed, live and backfill paths rebuild everything in the new shape — *why:* the amendment's non-goal; every path already produces the new shape — *cost if wrong:* a demo volume's history is lost on upgrade.
- **R58. Code tasks did not touch the generated api-explorer and ingress OpenAPI copies.** API documentation for the new shapes lives in `contracts/cdm-mapping.md`, `contracts/contract-delta.md`, `quickstart.md` and this state's `contracts/cdm-service/openapi.yaml` (updated with the docs) — *why:* those generated copies are capture noise under the patch rules — *cost if wrong:* an API explorer built before the docs update lacks the new fields (minor).
- **R59. The CDM order page is a tree.** Two top-level steps (order proposal, cancel); the proposal holds the approval badge, its versions and the fills; each fill shows its trade state inline and its proposed and accepted steps behind a toggle, open by default only when highlighted (`?fill=N`) or not `VALID`; the trade page leads with the `TradeState` and "Produced by" — *why:* containment shown as containment, not as a sequence (amendment anti-goal) — *cost if wrong:* a click to see a fill's steps.
- **R60. CDM view styles share `_cdm-common.scss`.** The view and home pages each have their own sheet using the shared one, and Bootstrap utilities replace small custom rules; the production `anyComponentStyle` budget (4 kB) is unchanged — *why:* the view's sheet (5.28 kB) failed the production build, which unit tests do not run — *cost if wrong:* small visual differences (badge sizing, one text colour); the sheet still warns above 2 kB.
- **R61. The admin order grid always fits its columns.** `sizeColumnsToFit` on every layout and again on `gridSizeChanged`, with a `minWidth` per column, instead of the plan's "fit only when wide enough" gate — *why:* ag-grid honours `minWidth` and scrolls, while the gate left 200 px defaults below about 1400 px — *cost if wrong:* none seen; columns shrink to their minimum and scroll (ACCOUNT and SECURITY headers still clip at 1440 px, left for the final review).
- **R62. The outage scenario (g) books its fills while cdm-service is down by pausing trade-processor.** Orders and force-fills are submitted while cdm-service is up; trade-processor is paused, cdm-service stopped, trade-processor unpaused so the fills are booked during the outage, and the cancel follows — *why:* with CDM as the security master, trade-service cannot accept a trade while cdm-service is down (gap G8) — *cost if wrong:* (g) needs the docker CLI; `TRADERX_CDM_SKIP_OUTAGE=1` skips it.
- **R63. Build process: deprecated mappers kept for one commit.** The first task kept the old `accepted()`/`cancelRemainder()` and execution paths, deprecated, and the next task removed them with their callers — *why:* every commit builds — *cost if wrong:* a dead method for one commit.
- **R64. Build process: the start script is edited in place.** `scripts/start-state-017-cdm-lifecycle-mapping-generated.sh` gets its volume check for the `produced_by_*` columns directly — *why:* it lives in the repository, not in the generated tree, so it needs no patch capture — *cost if wrong:* none.
- **R65. Build process: `NatsLifecycleListenerTest` left unchanged**, though the storage task's brief listed it — *why:* the listener only forwards subject and bytes, subject names did not change, and the published subjects are asserted in `LifecycleAdapterTest` — *cost if wrong:* a listener-level regression would show only in the live run.
- **R66. Build process: interim reader edits accepted.** The storage task changed the lineage readers just enough to keep their tests passing on the new layout; the API task then rewrote them — *why:* every commit keeps the full test suite green — *cost if wrong:* none after the API task.
- **R67. Build process: the UI task's live visual check moved to after the live scenario run.** — *why:* the running stack still served the old UI image until the scenario task rebuilt it, so an earlier check would have looked at the wrong build — *cost if wrong:* none.
- **R68. Build process: visual checks of the admin and CDM pages are done by the controller with a browser** after the live scenario run — *why:* the implementing agents had no browser — *cost if wrong:* none.
- **R69. Dealer and client are the counterparties; subsidiaries face clients.** `Party1` is the TraderX
  group subsidiary servicing the account (NYC, LDN or TKY; CDM `Account.servicingParty` on the client
  party, seeded from `seed/entities.json`), `Party2` the client; the matcher is only the
  `ExecutionFacility` and still approves orders. Amendment
  [`design/dealer-client-party-model-amendment.md`](design/dealer-client-party-model-amendment.md),
  decided by the owner — *why:* TraderX reads as a dealer's trading system booking for clients, not an
  exchange; the venue as principal counterparty was the first thing a CDM reviewer would question —
  *cost if wrong:* the party builder and five read-back sites change again, and stored data needs
  `--reset-data`.
- **R70. Direct trades are `OffFacility` with no venue.** — *why:* a trade-ticket trade is a bilateral
  booking between the subsidiary and the client; `Electronic` requires an `executionVenue` (CDM
  condition `ExecutionVenue`) and implied a venue that was not used — *cost if wrong:* one enum value
  and the absent venue on direct trades.
- **R71. Every seeded account is a `Client` account.** "Internal Trading Book" (10031) reads as a house
  book (`AccountTypeEnum.House`), but is treated as a client in 017 — *why:* a house book facing its
  own subsidiary would make the dealer trade with itself; modelling house books (fills facing the
  venue or the market) is left to a further state — *cost if wrong:* 10031's trades misdescribe a
  proprietary book as client business.
- **R72. The facing subsidiary is chosen per account, not per order.** — *why:* choosing it per order
  needs changes to 009's order and trade APIs and screens — *cost if wrong:* an account cannot split
  its business across subsidiaries until a further state adds per-order choice.
- **R73. Stored account parties without a servicing subsidiary get their seed assignment at startup.**
  The party master writes a new version with `account.servicingParty` — *why:* a volume from an
  earlier build stays readable for master data — *cost if wrong:* none for parties; stored orders and
  trades from earlier builds still have the old counterparties, so `--reset-data` is still advised.
- **R74. The servicing subsidiary is shown on the Account tab, via account-service.** account-service's
  `Account` gains optional `servicingPartyRef` and `servicingPartyName`, projected from the CDM party
  (the name joined from the subsidiary's own party); the Account tab shows a **Serviced by** column, and
  the CDM overview a selector and a trading-relationships table. Decided by the owner — *why:* the CDM
  account model is authoritative in 017, so the trading relationship belongs on the account screen,
  not only on order and trade pages — *cost if wrong:* two optional fields and one grid column to
  remove; no 009 field changes.
- **R75. A fill takes its dealer from its order, not from the account's current subsidiary.** The
  fill's `Party1` is the order instruction's `Party1`; only direct trades (and orders) read the
  account's current `servicingParty`. Found in review — *why:* switching an account's subsidiary while
  an order is open would otherwise give the order's later fills a different dealer from the order,
  and a fill write interrupted before the switch could never be resumed (its remapped steps would no
  longer match) — *cost if wrong:* none known; an order mapped before Ruling R69 falls back to the
  current subsidiary.
- **R76. A rejected request with no account id faces the default subsidiary.** Found in review —
  *why:* the dealer lookup took an `int` and a null account id threw, so such rejections were
  recorded only as lifecycle failures — *cost if wrong:* the rejection's dealer is the seed default
  (NYC) rather than unknown; the client party is already an "unknown" placeholder.

