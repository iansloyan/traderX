# Implementation Plan: 017-cdm-lifecycle-mapping

Design:
[`design/cdm-lifecycle-integration-design.md`](design/cdm-lifecycle-integration-design.md).

## Scope

- Transition from `009-order-management-matcher` to `017-cdm-lifecycle-mapping` on the `functional`
  track (lineage role `optional`).
- FINOS CDM 7.8.0 as the system of record for parties, persons and securities; the order and trade
  lifecycle mirrored into lineage-linked CDM objects; optional `cdmRef` on every projection.
- Additive only: 009 and earlier states are not changed.

## Deliverables

1. Requirement deltas in `requirements/` and FR/NFR traced to tests in `spec.md`.
2. Contract deltas in `contracts/contract-delta.md`, the mapping specification in
   `contracts/cdm-mapping.md`, golden CDM JSON in `contracts/examples/`, and per-service OpenAPI
   copies in `contracts/<service>/openapi.yaml`.
3. Supporting artifacts: `research.md`, `data-model.md`, `quickstart.md`.
4. Architecture and topology deltas in `system/`.
5. Generation: hook `pipeline/generate-state-017-cdm-lifecycle-mapping.sh`, render script
   `pipeline/render-state-017-cdm-lifecycle-mapping.sh`, patch `generation/patches/0001-state-overlay.patch`,
   and the new service in `generation/runtime-overrides/cdm-service/`.
6. Scenario tests in `scripts/test-state-017-cdm-lifecycle-mapping.sh`.

## Exit Criteria

- Spec and tasks are complete and reviewed.
- Generation hook produces the expected tree from a clean clone.
- Scenario tests (a)–(g) pass on a fresh stack.
- Repository quality gates pass.
- Publishing to `code/generated-state-017-cdm-lifecycle-mapping` (with GHCR images and a deployment
  bundle) is deferred to the upstream PR.
