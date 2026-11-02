# Feature Specification: CDM Lifecycle, Party and Security Model

**Feature Branch**: `017-cdm-lifecycle-mapping`  
**Created**: 2026-10-29  
**Status**: Implemented  
**Input**: Transition delta from `009-order-management-matcher`; origin
[finos/traderX#379](https://github.com/finos/traderX/issues/379) (CDM-aligned trade/order lifecycle mapping)

FINOS CDM 7.8.0 becomes the system of record underneath TraderX for parties, persons and securities,
and every order and trade event is mirrored into lineage-linked CDM objects. The existing services keep
their simple shapes, each with an optional `cdmRef` back to its CDM source. The mapping itself is
specified in [`contracts/cdm-mapping.md`](contracts/cdm-mapping.md); API, event and schema changes in
[`contracts/contract-delta.md`](contracts/contract-delta.md).

## User Stories

- As a FINOS community member, I want to see TraderX's orders, trades, accounts, traders and securities
  as valid FINOS CDM objects, so I can judge how a real trading flow maps onto CDM.
- As a developer, I want every simple TraderX value to carry a reference to its CDM source, so nothing
  is lost when I need the full CDM object.
- As an analyst, I want to walk an order's lineage (proposal → acceptance → fills → cancel) with its
  parties and security resolved, in the UI and over REST.
- As an existing TraderX client, I want every 009 API, subject and screen to keep working unchanged.
- As a fork user, I want to generate, start and test this state with the same scripts as every other
  state, on a machine with only Docker.

## Functional Requirements

Each requirement lists the tests that pin it. Unit tests live in the cdm-service source
(`generation/runtime-overrides/cdm-service/src/test/`) or in the patched component; scenario tests are
in `scripts/test-state-017-cdm-lifecycle-mapping.sh` (see `tests/smoke/README.md`).

### G1: CDM `Party` is the party model

- FR-01701: Each TraderX account SHALL be a CDM `Party` stored under `cdm:party:<accountId>`, with the
  account id as a `PartyIdentifier` (scheme `https://traderx.finos.org/account-id`) and the display
  name as `name`. Tests: `PartyMapperTest.accountPartyCarriesTheAccountIdUnderTheTraderxScheme`,
  `PartyMasterTest.seedCreatesOnePartyPerAccountAndRelatesUsers`.
- FR-01702: Each person SHALL be a CDM `NaturalPerson` under `cdm:person:<logonId>` (logon id, employee
  id, department, photo URL, name, email), and each account user SHALL be an identifier-only person in
  the account Party with role `TRADER`; the person master is the single person authority (Ruling R19).
  Tests: `PartyMapperTest.personCarriesLogonIdEmployeeIdNameEmailAndDepartment`,
  `PartyMapperTest.relatedPersonsAreTradersReferencedByLogonId`,
  `PartyMasterTest.partyEmbedsIdentifierOnlyPersons`, `PartyMasterTest.unknownUserRefResolves`,
  `PartyEndpointsTest.cdmPartyEndpointResolvesFullPersonsFromMaster`.
- FR-01703: The order matcher SHALL be the venue `Party` `traderx-matching-engine` ("TraderX Matching
  Engine"), the `ExecutionFacility` of orders and fills and the approver of orders, never a
  counterparty (Ruling R69). Tests: `PartyMapperTest.venuePartyHasStableKey`,
  `PartyMasterTest.seedsTheVenueParty`,
  `WorkflowMapperTest.proposedStepIsDealerAgainstClientWithVenueAsFacility`.
- FR-01727: The TraderX group subsidiaries NYC, LDN and TKY (`seed/entities.json`) SHALL each be a CDM
  `Party` (`cdm:party:traderx-nyc`, …). Each account party SHALL carry a CDM `Account` (type `Client`)
  whose `servicingParty` is the subsidiary facing it, seeded per `entities.json` and changeable with
  `PUT /cdm/parties/{accountId}/servicing-party`; that subsidiary SHALL be `Party1` (roles
  `ExecutingEntity`, `BookingParty`) and the client `Party2` (role `Client`) of every order, fill and
  trade of the account, as at the time the order or direct trade is mapped; a fill SHALL keep its order's dealer (Rulings R69, R75). Tests:
  `PartyMasterTest.seedStoresSubsidiariesAndAssignsEachAccountItsFacingSubsidiary`,
  `.servicingEntityCanBeSwitchedAndUnknownOnesAreRejected`,
  `ExecutionMapperTest.fillKeepsItsOrdersDealerAfterTheAccountsSubsidiaryIsSwitched`,
  `WorkflowMapperTest.rejectedRequestWithoutAccountIdFacesTheDefaultSubsidiary`,
  `WorkflowMapperTest.proposedStepIsDealerAgainstClientWithVenueAsFacility`, scenario (h).
- FR-01704: account-service SHALL serve accounts and account users as projections of the CDM parties,
  each account with the subsidiary servicing it (`servicingPartyRef`, `servicingPartyName`), and
  account creation, update and add-user SHALL go through cdm-service, keeping 009's id semantics
  (sequence ids, upsert with a client id, never overwriting a taken id). Tests:
  `AccountServiceCdmTest.listAccountsReturnsProjectionWithCdmRef`, `.createAccountGoesThroughCdmService`,
  `.updateWithAnIdUpsertsThatAccountInCdm`, `.addAccountUserValidatesThePersonThenWritesToCdm`,
  `.getAccountByIdKeepsThe404`, `PartyMapperTest.accountProjectionCarriesTheServicingSubsidiary`, `PartyMasterTest.createAccountDrawsIdsFromAccountsSequenceAndSurvivesRestart`,
  `.createAccountNeverOverwritesExistingId`, `.createAccountAnswersConflictWhenEveryDrawnIdIsTaken`.
- FR-01705: people-service SHALL serve its directory from the CDM person master; identifier-only
  persons SHALL NOT be in the directory (Ruling R20). Tests: `PeopleControllerTests.GetPerson_ReturnsCdmRef`,
  `.GetMatchingPeople_SearchesInCdmServiceAndAppliesTake`, `.GetMatchingPeople_ValidationIsUnchanged`,
  `PeopleEndpointsTest.identifierOnlyPersonsAreNotInTheDirectory`,
  `PartyMasterTest.personDirectoryServesSeededPersonsWithPhotoUrlAndHidesIdentifierOnlyPersons`.

### G2: CDM `Security` is the instrument model

- FR-01706: Each reference-data stock SHALL be a CDM `Security` (`Equity`, `Ordinary`) under
  `cdm:security:<ticker>` with ticker (`ExchangeCode`), demo ISIN, demo FIGI and company name (`Name`)
  identifiers; ISIN and FIGI are synthetic, carry the `demo-identifier` scheme and are labelled "demo
  identifier" in the UI. Tests: `SecurityMapperTest.cdmSecurityCarriesTickerIsinFigiAndNameIdentifiers`,
  `.demoIdentifiersAreLabelledWithTheDemoScheme`, `DemoIdentifiersTest.isinCheckDigitMatchesRealIsins`,
  `.figiCheckDigitMatchesRealFigis`, `.demoValuesAreStableAcrossReleases`,
  `SecurityMasterTest.demoIdentifiersAreUniqueAcrossTheSeed`, `cdm-view.component.spec.ts`
  `showsDemoIdentifierLabel`.
- FR-01707: reference-data SHALL serve its stocks from the CDM security master with the same tickers,
  names, order and filters as 009. Tests: `SecurityMasterTest.seedKeepsReferenceDataTickersNamesAndOrder`,
  `.findByTickerIsCaseSensitiveLikeReferenceData`, `stocks.service.spec.ts` `mapsCdmProjection`,
  `keeps the supported-ticker and max-ticker filters`, `serves the existing paths and field names, plus cdmRef`.

### G3: Orders are CDM `ExecutionInstruction` proposals approved by the venue

- FR-01708: A new order SHALL produce a proposed `WorkflowStep` (an `ExecutionInstruction` of the
  order quantity at the limit price, the client as buyer or seller by side and the facing subsidiary
  as the other, the venue as execution facility, `executionType OnVenue`), and the matcher's acceptance SHALL be recorded as the venue's approval of
  that proposal (`WorkflowStepApproval` `approved: true`), not as an accepted step; no step SHALL carry
  an empty business event (Rulings R43, R44). Tests:
  `WorkflowMapperTest.proposedStepIsDealerAgainstClientWithVenueAsFacility`,
  `.sellMakesAccountSellerAndPayer`, `.approvedProposalCarriesVenueApprovalAndValidates`,
  `.venueApprovalIsNotAnAcceptance`, `.approvedRefusesFillAndRejectedRequestProposals`,
  `LifecycleAdapterTest.newOrderStoresProposalThenApproval`,
  `.newOrderAfterAPartialWriteStoresOnlyTheApproval`, scenario (b).
- FR-01709: An invalid order create SHALL still answer 400 and SHALL be recorded as a proposed +
  rejected workflow under its rejection id, with the reason, and the rejected step SHALL carry the
  venue's approval `approved: false` with the reason (Ruling R45). Tests:
  `OrderMatcherServiceCdmLinkTest.invalidCreatePublishesRejectedAndStillReturns400`,
  `.nullCreateRequestPublishesRejectionWithNullFields`, `.rejectionPublishFailureDoesNotChangeThe400`,
  `WorkflowMapperTest.rejectedCarriesReason`, `.rejectedStepIsCdmRejected`,
  `.rejectedRequestWithUnknownValuesIsMappedAndReportedInvalid`,
  `LifecycleAdapterTest.rejectedOrderStoredUnderRejectionId`, scenario (d).
- FR-01710: A cancel SHALL produce a correction (`Correct`) of the order's latest proposal version with
  `workflowState` `Cancelled`, keeping the instruction and stating the cancelled remainder and the
  executed quantity, and leaving the fills untouched; the matcher's `CANCELED` event carries
  `filledQuantity` and `canceledQuantity` (Rulings R25, R47, R48). Tests:
  `WorkflowMapperTest.cancelCorrectsTheLatestProposalVersionKeepingItsInstruction`,
  `.cancelWithNoFillsIsValidCorrection`, `.cancelAfterFillsStatesBothQuantities`,
  `.cancelQuantitiesReadBackFromTheCancelComment`, `.cancelCorrectionRefusesFillSteps`,
  `ExecutionMapperTest.cancelAfterPartialFillKeepsExecutionsAndCancelsRemainder`,
  `LifecycleAdapterTest.cancelIsACorrectionOfTheApprovedProposalAfterTheFills`,
  `.cancelWithNoFillsStoresValidCorrection`, `.cancelBeforeItsFillIsBookedIsAppliedAfterTheFill`,
  `.cancelBeforeAnyNewIsParkedThenStoredAfterTheApproval`,
  `OrderMatcherServiceCdmLinkTest.canceledEventCarriesFilledAndCanceledQuantity`, scenario (c).

### G4: Each fill is its own CDM workflow; each trade is a `TradeState`

- FR-01711: Each booked fill SHALL produce its own two-step workflow: a proposed step (action `New`, no
  previous step, a child `ExecutionInstruction` for the fill quantity at the fill price, identified by
  the order id and the trade id, Ruling R42) and an accepted step linked to it, carrying a
  `BusinessEvent` that satisfies `Qualify_Execution` and whose `after` `TradeState` has the booked
  price; both steps stored under the order's ref. The trade's `TradeState` SHALL be stored alone under
  `cdm:trade:<tradeId>` with a "produced by" reference to the accepted step, and no `BusinessEvent`
  SHALL be stored on its own. The order's executed quantity SHALL be the sum of its accepted fills.
  Tests: `ExecutionMapperTest.fillIsItsOwnTwoStepWorkflow`, `.fillCarriesOrderAndTradeIdentifiers`,
  `.everyExecutionQualifies`, `.noAcceptedStepHasAnEmptyEvent`, `.tradeLotPriceIsBookedPrice`,
  `.partialThenFullFillSumsToInstructionQuantity`, `.orderLinksProposalVersionsWhileFillsAreTheirOwnWorkflows`,
  `.fillRefusesAFillStepAsItsOrder`,
  `LifecycleAdapterTest.fillStoresTwoStepsUnderOrderAndTradeStateOnlyUnderTrade`,
  `.noBusinessEventStoredStandalone`, `.fillsNeverChainAcrossOrders`,
  `CdmStoreTest.putWithProducedByStoresItAndLinksVersionsWithinTheRef`, scenario (b).
- FR-01712: The product SHALL be a cash equity `SettlementPayout` on the `Security` with the stated
  defaults: USD, T+2 business days, delivery versus payment. Tests:
  `ExecutionMapperTest.goldenExamplesMatch` (goldens in `contracts/examples/`), scenario (f).
- FR-01713: Fills SHALL be linked to their order: the matcher submits the trade with its `orderId`,
  trade-service and trade-processor pass it through, and trade-processor publishes each committed trade
  on `/trades/booked`. Tests: `OrderMatcherServiceCdmLinkTest.fillSubmitsTradeWithOrderId`,
  `TradeOrderOrderIdPassThroughTest.orderIdFromMatcherIsPassedThroughToTradesTopic`,
  `.directTradeWithoutOrderIdStillPublishesUnchanged`,
  `TradeServiceBookedTopicTest.bookedTradeIsPublishedOnGlobalSubjectWithOrderId`,
  `.directTradeHasNullOrderId`.
- FR-01714: A direct trade (no `orderId`) SHALL produce a proposed → accepted workflow under
  `cdm:direct:<tradeId>` with the same step shapes as a fill, and its `TradeState` under
  `cdm:trade:<tradeId>` produced by that accepted step. Execution type SHALL be `OnVenue` for matcher
  fills and `OffFacility` for direct trades, which have no venue, with its meaning shown wherever the
  value is (Rulings R53, R70).
  Tests: `ExecutionMapperTest.directTradeIsProposedThenAcceptedWithItsExecution`,
  `.directTradeHasSameShapeAsAFill`, `.fillIsOnVenueDirectIsOffFacilityWithoutVenue`,
  `LifecycleAdapterTest.directTradeUsesDirectRefAndOffFacility`, `CdmRefTest.parsesDirect`,
  `LineageControllerTest.executionTypeMeaningPresent`, scenario (a).
- FR-01715: A trade's TraderX state SHALL map to `TradeState.state.positionState`: `New`,
  `Processing`, `Settled` → `Executed`; `Cancelled` → `Cancelled` (Rulings R35, R37). Tests:
  `ExecutionMapperTest.tradeStateCarriesPositionState`, scenario (e).
- FR-01716: Mismatched fills (side, security or account differing from the order) SHALL be stored as
  booked and flagged `INVALID`; a trade of another order SHALL be refused and recorded as a failure.
  Both fill steps SHALL carry the flag (Ruling R52). Tests: `ExecutionMapperTest.mismatchedFillIsFlagged`,
  `.mismatchedFillWorkflowIsFlagged`, `.tradeOfAnotherOrderIsRefused`.
- FR-01717: The order matcher's admin force-fill SHALL accept an optional `quantity` (absent: the whole
  remainder, as in 009). Tests: `OrderMatcherServiceForceFillTest.partialForceFillFillsRequestedQuantity`,
  `.forceFillWithoutQuantityIsUnchanged`, `.forceFillQuantityOutOfRangeIsBadRequest`, scenarios (c), (c').

### G5: Every projection carries `cdmRef`; round trips are lossless

- FR-01718: Account, account user, person, stock, order, trade and position projections SHALL carry
  an optional `cdmRef` formatted `cdm:<kind>:<externalKey>`. Tests: `CdmRefTest.roundTrips`,
  `.rejectsUnknownKind`, `AccountServiceCdmTest.listAccountsReturnsProjectionWithCdmRef`,
  `PeopleControllerTests.GetPerson_ReturnsCdmRef`, `stocks.service.spec.ts` `mapsCdmProjection`,
  `OrderMatcherServiceCdmLinkTest.orderResponseCarriesCdmRef`,
  `PositionTest.jsonKeepsThe009FieldsAndAddsCdmRef`, `.noCdmRefWithoutItsKey`.
- FR-01719: For every projection, simple → resolve `cdmRef` → re-project SHALL equal the original for
  the fields listed as lossless in `contracts/cdm-mapping.md` section 12; every other field is listed
  there with the reason. Tests: `PartyMapperTest.accountProjectionRoundTripIsLossless`,
  `PersonProjectionTest.personProjectionRoundTripIsLossless`,
  `SecurityMapperTest.projectionRoundTripIsLossless`,
  `LineageControllerTest.positionProjectionIsThe009ShapePlusCdmRef`,
  `PositionViewTest.positionProjectionMatchesPositionServiceShape`, scenario (e).
- FR-01720: Positions SHALL be a CDM `PortfolioState` derived from the account's trade states, equal to
  position-service's quantities and average cost; an account with no CDM trades SHALL answer 404
  pending (Ruling R30). Tests: `PositionViewTest.positionQuantityMatchesSumOfSignedExecutions`,
  `.shortPositionIsTheAccountPayingTheShares`, `.portfolioStateValidatesAndHasLineageToItsTradesAndInitialState`,
  `.tradesOfOtherAccountsAreNotCounted`, `.tradeStatesThatCannotBeCountedAreSkippedNotFatal`,
  `LineageControllerTest.cdmPositionsAreAValidatedPortfolioState`,
  `.accountWithoutCdmTradesIsPendingNotFound`, `SeedTradeBackfillTest.backfilledSeedPositionsEqualThe009SeedPositions`.

### G6: Lineage is navigable

- FR-01721: cdm-service SHALL resolve any `cdmRef` (404 pending when not yet stored, 400 when
  malformed; `cdm:order` to the order's own latest version, Ruling R54) and serve order lineage (the
  proposal versions with the venue's approval, the fills nested with their steps and trade states,
  numbered in booking order, Ruling R55, and the cancel), trade and trade lineage (the trade state
  first, then what produced it), positions (with the trade states behind each holding), rejections and
  a validation summary, exposing both `storeGlobalKey` and `cdmGlobalKey` (Ruling R29). Tests:
  `LineageControllerTest.resolveUnknownRefReturnsPendingNotFound`, `.resolveMalformedRefIsBadRequest`,
  `.resolveOrderReturnsItsLatestVersionAndLinksTheLineage`, `.resolveTradeReturnsItsTradeState`,
  `.resolveMastersAndPositions`, `.orderLineageNestsFillsWithTradeStates`,
  `.fillNumbersFollowBookingOrderNotArrival`, `.fillNumbersBreakTimeTiesByTradeId`,
  `.singleCompleteFillIsFullFill`, `.lineageOfCanceledOrderKeepsFillsAndCancelsTheRemainder`,
  `.canceledQuantityIsTheMatchersCancelledQuantity`, `.lineageOfRejectedRequest`,
  `.tradeLeadsWithTradeStateAndProducedBy`, `.directTradeProducedByDirectWorkflow`,
  `.positionTradesSumToQuantity`, `.refHelpersAgree`,
  `DiagnosticsControllerTest.rejectionsListedByAccount`, `.rejectionReasonIsTheVenuesRejectedReason`,
  `.validationSummaryCountsByStatus`, `.tradeLineageOfDirectTradeLeadsWithTradeStateThenItsWorkflow`,
  `.tradeLineageOfOrderFillGivesTheFillAndItsOrder`, `.tradeLineageOfUnlinkedTradeStateHasNoProducer`.
- FR-01722: The TraderX UI SHALL offer a CDM view (`/cdm`, `/cdm/order/<id>`, `/cdm/trade/<id>`): the
  order as a tree (the proposal with the venue's approval badge, the fills nested beneath with their
  trade states, the cancel), the trade page leading with its trade state and what produced it, the
  account's positions with the trade states behind each, the execution type with its meaning, party
  and security cards, validation badges, a pending state and the raw CDM JSON, and a CDM link per order
  and trade blotter row. Each client's servicing subsidiary SHALL be visible and changeable without
  opening an order or trade: a **Serviced by** column on the Account tab, and on the CDM overview a
  selector and a table of trading relationships (Ruling R74). Tests: `cdm-view.component.spec.ts` `rendersOrderAsTreeWithFillsNested`, `labelsTheCurrentServicerAndTheDealerOnThisOrder`, `showsAFillStepsEventIdentifierAndPutsStepFieldsFirstInTheJson`, `stepFieldsFirstLeavesOtherObjectsAlone`, `cdm-home.component.spec.ts` `showsTheSelectedAccountsServicingSubsidiaryAndSwitchesIt`, `listsTradingRelationshipsBySubsidiary`, `account.component.spec.ts` `shows the servicing subsidiary of each account`,
  `showsApprovalBadge`, `showsRejectionReasonInTheApprovalBadge`, `executionTypeCaptionShown`,
  `highlights the fill named in the query`, `tradePageLeadsWithTradeState`, `tradePageLinksProducingFill`,
  `directTradeShowsDirectWorkflow`, `showsInvalidBadgeWhenValidationInvalid`,
  `showsPendingStateFor404Pending`; `cdm-home.component.spec.ts`
  `positionsListTradeStatesSummingToQuantity`; `cdm.service.spec.ts`
  `maps 404 {"status":"pending"} to a pending result`.
- FR-01723: cdm-service SHALL publish each stored `WorkflowStep` on `cdm.workflow` and each stored
  `TradeState` on `cdm.tradestate` as `{cdmRef, globalKey, cdmType, validation, object}` with the stored
  JSON verbatim, and the execution `BusinessEvent` of each newly stored accepted step on `cdm.events`
  (with `stepGlobalKey`, Ruling R51). Tests: `LifecycleAdapterTest.publishedObjectIsTheStoredJsonVerbatim`.

### G7: Reproducible by anyone from a fork

- FR-01724: The state SHALL be generated, started, tested and stopped with
  `bash pipeline/generate-state.sh 017-cdm-lifecycle-mapping`,
  `./scripts/start-state-017-cdm-lifecycle-mapping-generated.sh [--reset-data]`,
  `./scripts/test-state-017-cdm-lifecycle-mapping.sh` and
  `./scripts/stop-state-017-cdm-lifecycle-mapping-generated.sh`. Tests: scenarios (a)–(g);
  `pipeline/validate-lifecycle-script-contract.sh`.
- FR-01725: Seed data SHALL load at startup: 509 securities, the 009 accounts, users and people as CDM
  masters; the matcher seeds only `NEW` demo orders (`ORDER_MATCHER_SEED_STATUSES=NEW`, Ruling R32); and
  cdm-service imports seed trades as direct trades, open matcher orders as approved proposals, fills
  booked while it was down as fill workflows of their orders, and missed cancels, storing the same
  objects as the live path (Rulings R28, R31, R41). Tests:
  `SecurityMasterTest.seedIfEmptyStoresOneSecurityPerSeedTicker`,
  `OrderMatcherServiceSeedTest.defaultSeedIsUnchanged`, `.seedStatusesPropertyLimitsSeededOrders`,
  `.unknownSeedStatusIsRejected`, `SeedTradeBackfillTest.seededTradesAreBackfilledOnceAsDirectTrades`,
  `.openMatcherOrdersAreBackfilledAsAcceptedProposals`, `.missedOrderFillsAreBackfilledIntoTheOrderChain`,
  `.orderFillBackfillIsIdempotent`, `.missedCancelIsBackfilled`,
  `.orderCreatedAndCanceledDuringOutageIsBackfilled`, `.backfillProducesSameDocumentsAsLive`, scenario (g).
- FR-01726: Existing 009 flows SHALL remain compatible: REST paths, subjects, payload fields and UI
  routes keep their 009 behaviour, and new fields are optional. Tests:
  `PositionTest.jsonKeepsThe009FieldsAndAddsCdmRef`, `AccountServiceCdmTest.addAccountUserToAnUnknownAccountIs404WithTheOldMessage`,
  `OrderMatcherServiceForceFillTest.forceFillWithoutQuantityIsUnchanged`,
  `TradeOrderOrderIdPassThroughTest.directTradeWithoutOrderIdStillPublishesUnchanged`; the inherited
  `scripts/test-state-009-order-management-matcher.sh` run with `COMPOSE_PROJECT_NAME=traderx-state-017`,
  which passes except its Loki checks on Docker Engines that reject API 1.42 (Ruling R33, a host
  limitation shared with 009). Evidence: on a fresh 017 data volume every check up to Loki passed
  (including the open-order, create, cancel, force-fill and auto-fill journeys), the run stopped at
  `expected non-empty Loki ingestion in last 5m, got 0`, and the checks after it, run directly, passed
  (details in `quickstart.md` section 3).

## Non-Functional Requirements

- NFR-01701 (non-blocking lifecycle mirroring): A failure in the CDM lifecycle mirroring SHALL never
  block or fail an order or trade. This covers the lifecycle path only: master data (accounts, people,
  reference data) is CDM-authoritative and depends on cdm-service being available (gap G8 in
  `contracts/cdm-mapping.md`: while cdm-service is down those services answer 503 and a trade submit
  fails with 500). Lifecycle CDM objects are built asynchronously from NATS events; mapper exceptions, undecodable messages and invalid
  objects are logged, counted (`traderx_cdm_failures_total`, `traderx_cdm_objects_total`), stored in
  `cdm_lifecycle_failures` or with their validation result, and never thrown to the TraderX caller.
  Tests: `LifecycleAdapterTest.mapperFailureIsRecordedAndDoesNotThrow`,
  `.invalidObjectIsStoredWithInvalidStatusAndDoesNotThrow`,
  `.throwingValidatorStoresInvalidRatherThanSkipping`,
  `NatsLifecycleListenerTest.undecodableMessageIsRecordedNotThrown`,
  `.adapterExceptionNeverEscapesTheHandler`,
  `TradeServiceBookedTopicTest.bookedTopicFailureDoesNotFailBookingOrOtherPublishes`,
  `OrderMatcherServiceCdmLinkTest.rejectionPublishFailureDoesNotChangeThe400`.
- NFR-01702 (idempotency): Redelivered events and repeated startup imports SHALL store nothing new;
  identical content with the same previous version is a no-op, so a restart or replay never forks the
  lineage. Tests: `CdmStoreTest.putIsIdempotentForIdenticalContent`,
  `.retryingTheSameVersionIsIdempotentButAChangedPreviousKeyIsNot`, `.putNextChainsFromTheLatestVersionAndSkipsUnchangedContent`,
  `LifecycleAdapterTest.redeliveredFillWritesNothing`, `.fillResumesAfterPartialWrite`,
  `SeedTradeBackfillTest.backfillIsIdempotentAcrossRestarts`,
  `.openOrderBackfillIsIdempotentAcrossRestarts`, `WorkflowMapperTest.mappingIsDeterministic`.
- NFR-01703 (prerequisites and ordering): Events SHALL be applied in lifecycle order even when they
  arrive out of order or after a cdm-service outage: fills wait for their order's approved proposal, cancels wait
  for their fills, and a missing proposal is recovered from the matcher (Ruling R26); parked items older
  than 5 minutes are recorded as failures. Tests:
  `LifecycleAdapterTest.fillBeforeOrderIsParkedThenReplayedInNewShape`,
  `.bookedTradeForUnknownOrderRecoversFromMatcher`, `.cancelForUnknownOrderRecoversFromMatcher`,
  `.sweepReplaysParkedTrades`, `.staleParkedTradeIsRecordedAsFailureOnce`,
  `.staleParkedCancelIsRecordedAsFailure`, `.rejectedResumesAfterPartialWrite`,
  `.directTradeResumesAfterPartialWrite`.
- NFR-01704 (readiness): cdm-service `/health` SHALL be 200 only when the security and party masters are
  completely seeded (503 `starting` before); masters serve nothing partial; the NATS listener subscribes
  only when every master is ready and again after a reconnect; dependants start after cdm-service is
  healthy. Tests: `CdmServiceApplicationTest.healthIsStartingUntilEveryMasterIsReady`,
  `SecurityMasterTest.seedIfEmptyReportsFailureAndStaysNotReady`,
  `PartyMasterTest.seedFailureKeepsMasterNotReadyUntilACompleteSeed`,
  `NatsLifecycleListenerTest.doesNotConnectUntilEveryMasterIsReady`,
  `.subscribesOnReconnectedWhenTheFirstConnectNeededRetries`.
- NFR-01705 (CDM validity): Every stored CDM object SHALL be validated with cdm-java type, cardinality
  and data-rule validation, and every execution event with `Qualify_Execution`; the result is stored,
  exported as a metric and shown in the CDM view. The smoke run SHALL find no `INVALID` or
  `NOT_QUALIFIED` object. Tests: `CdmValidatorTest.dataRuleConditionFailureIsReported`,
  `.nestedPathIsReportedInMessage`, `.nonExecutionEventIsNotQualified`,
  `.executionEventPassesQualification`, `.everySeededSecurityIsValid`, `.everySeededPartyAndPersonIsValid`,
  scenario (f).
- NFR-01706 (determinism): CDM JSON SHALL be a pure function of the TraderX input (timestamps and ids
  from the input, UTC, prices at scale 3), so goldens compare byte for byte. Tests:
  `ExecutionMapperTest.goldenExamplesMatch`, `CdmStoreTest.computedGlobalKeyIsSha256OfCanonicalJsonAndDeterministic`.
- NFR-01707 (dependency availability): account, people and reference data SHALL answer 503 (not 500 or
  empty data) when cdm-service is down or not ready, and pass client errors through as 400/404/409.
  Known gap: trade-service turns that 503 into a 500 on trade submit (Ruling R15). Tests:
  `AccountServiceCdmTest.returnsServiceUnavailableWhenCdmDown`, `.returnsServiceUnavailableWhenCdmIsNotReady`,
  `.badRequestFromCdmServiceIsReturnedAs400`, `.conflictFromCdmServiceIsReturnedAs409`,
  `PeopleControllerTests.ReturnsServiceUnavailableWhenCdmServiceIsUnreachable`,
  `stocks.service.spec.ts` `returnsServiceUnavailableWhenCdmDown`.
- NFR-01708 (runtime prerequisites): The runtime SHALL need only what 009 needs (Docker and
  `docker compose`, plus the generator's `rg`, `jq` and `perl`); cdm-service is Java 21 / Spring Boot
  4.0.8 with versions from `catalog/dependency-version-targets.json`. Checks:
  `pipeline/validate-generated-dependency-targets.sh` (run by `pipeline/generate-state.sh` on every
  generation, so cdm-service's versions are gated); the cdm-service image builds in Docker from
  `Dockerfile.compose` (no host JDK needed at runtime); a clean-clone run (fresh `git clone` of the
  branch, `bash pipeline/generate-state.sh 017-cdm-lifecycle-mapping` rc=0, start script with
  `--reset-data` rc=0, `scripts/test-state-017-cdm-lifecycle-mapping.sh` 55 passed, 0 failed).
- NFR-01709 (observability): cdm-service SHALL be scraped by Prometheus (`traderx-spring-boot-actuator`
  job) and probed by blackbox (`/health`), and export `traderx_cdm_objects_total{type,validation}`,
  `traderx_cdm_failures_total{stage}`, `traderx_cdm_pending_trades`, `traderx_cdm_pending_cancels`.
  Checks: `pipeline/render-state-017-cdm-lifecycle-mapping.sh` fails generation when the scrape target
  or the probe is missing after rendering; `scripts/status-state-017-cdm-lifecycle-mapping-generated.sh`
  reports `cdm-service-health`, `cdm-service-ingress` and `cdm-service-metrics`; on a live 017 stack
  Prometheus showed both targets `up` (`traderx-spring-boot-actuator cdm-service:18120` and
  `traderx-http-probe http://cdm-service:18120/health`). No automated test asserts the metric names (they are defined in
  `CdmMetrics`).
- NFR-01710: Runtime and topology are captured in `system/runtime-topology.md`; architecture in
  `system/architecture.model.json`, from which `system/architecture.md` is generated
  (`pipeline/generate-state-architecture-doc.sh`). Checks: `pipeline/validate-state-pack-artifacts.sh`
  (required pack files) and `bash pipeline/refresh-state-docs.sh --check` (run by
  `pipeline/verify-spec-coverage.sh`).
- NFR-01711: Publishing scope: the fork demo is run-from-source. Inherited GHCR image publishing,
  deployment bundles and the snapshot branch are not required for this state and are published
  by the maintainers when the state is merged. Check: the catalog keeps `generation.mode: planned` for this
  state (Ruling R39), so `pipeline/publish-generated-state-tree.sh`, `state-playbook.sh --all-implemented`
  and `validate-generated-branch-dependency-consistency.sh` (all select `generation.mode ==
  "implemented"`) skip it, while `status: implemented` marks the state as built.

## Acceptance Scenarios

Run by `scripts/test-state-017-cdm-lifecycle-mapping.sh` against a running stack. Details:
`tests/smoke/README.md`.

- **(a) Direct trade.** Given a running 017 stack, when a trade is booked from the trade ticket, then
  its trade lineage leads with a `VALID` `Executed` trade state under `cdm:trade:<tradeId>`, produced
  by the direct workflow `cdm:direct:<tradeId>` (`proposed → accepted`, both `VALID`, the accepted step
  carrying the qualified execution event), `executionType` `OffFacility` with its meaning, the
  account's subsidiary as `Party1`, the client as `Party2` and no venue.
- **(h) Facing subsidiaries.** Given account 22214 serviced by one subsidiary, when it is switched to
  another, then a trade booked afterwards has the new subsidiary as `Party1` and an earlier trade keeps
  the old one; an unknown subsidiary is rejected with 400.
- **(b) Order fills completely.** When an order of 1500 is placed with a limit 50% above market, then
  the order's proposal is approved by the venue, the matcher fills 750 and 750, and the lineage shows
  "Partial fill 1" and "Final fill 2", each its own `VALID` `fill-proposed → fill-accepted` workflow
  with its `VALID` trade state, `OnVenue`; status `filled`, executed 1500, remaining 0, no failures.
  Each trade leads with its trade state, produced by that fill of that order.
- **(c) Partial fill, then cancel.** When an out-of-the-money order of 1500 is force-filled for 750 and
  then cancelled, then the lineage has "Partial fill 1" of 750 and a `VALID` cancel with
  `workflowStatus` `Cancelled` linked to the approved version; the fill stands; `canceledQuantity` 750,
  remaining 0, status `canceled`. (c') A force-fill without quantity still fills the whole remainder,
  as one "Full fill". (c'') An order cancelled with no fills has a `VALID` `Cancelled` correction and
  no fills.
- **(d) Rejected order.** When an order with quantity 0 is submitted, then the matcher answers 400 and a
  `VALID` rejected step with the matcher's reason appears in `/cdm/rejections`, lineage
  `proposed → rejected`, with the venue's approval `approved: false` and its `rejectedReason`.
- **(e) Round trips.** For account 22214, person `user01`, stock IBM, the trade from (a) and the
  22214/IBM position: fetching the TraderX projection, resolving its `cdmRef` and re-projecting the CDM
  object gives the original lossless fields; the account's `PortfolioState` is `VALID`.
- **(f) Validation.** After the run, no stored CDM object is `INVALID` or `NOT_QUALIFIED`, no
  `BusinessEvent` is stored on its own, nothing is parked, and the run added no lifecycle failure rows.
- **(g) Outage recovery.** Orders, fills and cancels that happen while cdm-service is down (fills booked
  through a paused trade-processor, Ruling R62) are backfilled at its next start into the same shape as
  the live (c) and (c'') orders.

## Success Criteria

- SC-01701: Generation hook exists and is runnable (`pipeline/generate-state-017-cdm-lifecycle-mapping.sh`).
- SC-01702: `scripts/test-state-017-cdm-lifecycle-mapping.sh` passes scenarios (a)–(g) on a fresh
  stack.
- SC-01703: Generated snapshot branch and tag strategy are defined in the state catalog
  (`code/generated-state-017-cdm-lifecycle-mapping`); publishing is deferred to the upstream PR
  (NFR-01711).
- SC-01704: Every golden example in `contracts/examples/` validates `VALID`, every accepted step's
  execution qualifies, and no accepted step carries an empty business event.
- SC-01705: A fresh clone of the branch can generate, start and test the state with the commands in
  `quickstart.md`.
