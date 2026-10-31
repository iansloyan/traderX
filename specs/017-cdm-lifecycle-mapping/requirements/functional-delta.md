# Functional Delta: 017-cdm-lifecycle-mapping

Parent state: `009-order-management-matcher`

Requirement ids refer to `spec.md`. The CDM mapping is specified in `contracts/cdm-mapping.md`.

## Added

- New `cdm-service` (Java 21, Spring Boot, FINOS `cdm-java` 7.8.0) that owns a versioned CDM store in Postgres and is the system of record for parties, persons and securities (FR-01701 to FR-01707).
- Every order and trade event becomes lineage-linked CDM: a `WorkflowStep` chain proposed → accepted → one execution step per fill → cancel, with a qualified execution `BusinessEvent` and a `TradeState` per fill; rejected requests and direct trades use the same shapes (FR-01708 to FR-01716).
- Optional `cdmRef` (`cdm:<kind>:<externalKey>`) on account, account-user, person, stock, order, trade and position payloads, resolvable at `/cdm-service/cdm/resolve/{cdmRef}`; round trips through CDM are lossless for the documented fields (FR-01718, FR-01719).
- CDM read APIs (order and trade lineage, positions as a CDM `PortfolioState`, rejections, validation summary) and a CDM view in the UI at `/cdm` with lineage, party and security cards and raw CDM JSON (FR-01720 to FR-01722).
- New NATS subjects `/trades/booked`, `/orders/rejected` and `cdm.workflow`, `cdm.events`, `cdm.tradestate` (FR-01713, FR-01723).

## Changed

- account-service, people-service and reference-data serve their 009 shapes as projections of the CDM masters; account and account-user writes go through cdm-service (FR-01704, FR-01705, FR-01707).
- Order fills carry the matcher's `orderId` through trade-service and trade-processor; order events gain `filledQuantity` and `canceledQuantity`; admin force-fill takes an optional `quantity` (FR-01710, FR-01713, FR-01717).
- The matcher seeds only `NEW` demo orders in this state, and cdm-service imports seed trades, open orders and missed fills at startup (FR-01725).

## Removed

- None. people-service's own `MockDirectory/people.json` moved into cdm-service's seed; every 009 API, subject and UI route remains (FR-01726).

## Flow Impact

- Order create, fill, cancel and reject, and the trade ticket, are unchanged for the user and additionally produce CDM objects asynchronously (scenarios a–d).
- Account, people and reference-data reads depend on cdm-service and answer 503 while it is unavailable (NFR-01707).
