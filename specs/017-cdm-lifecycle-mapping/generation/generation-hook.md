# Generation Hook: 017-cdm-lifecycle-mapping

- Hook script: `pipeline/generate-state-017-cdm-lifecycle-mapping.sh`
- Render script: `pipeline/render-state-017-cdm-lifecycle-mapping.sh`
- Feature pack: `specs/017-cdm-lifecycle-mapping`

This state follows the patch-set overlay model, plus one state-owned component copied in by the render
script.

## Patch-Set Inputs

- Parent state id: `009-order-management-matcher`
- Patch directory: `specs/017-cdm-lifecycle-mapping/generation/patches/`
- Canonical patch file: `0001-state-overlay.patch`
- Capture excludes: `specs/017-cdm-lifecycle-mapping/generation/patch-capture-excludes.txt`

## Ownership

| Generated path (under `generated/code/target-generated/`) | Source of truth |
| --- | --- |
| `cdm-lifecycle-mapping/` (state compose runtime, project `traderx-state-017`) | `0001-state-overlay.patch` |
| Changes to inherited components (order-matcher, trade-*, account/people/reference-data, UI, ingress) | `0001-state-overlay.patch` |
| `cdm-service/` (new component) | `generation/runtime-overrides/cdm-service/` (edited in place; copied by the render script; never captured into the patch) |
| `ingress/nginx.traderx.conf.template` `/cdm-service/` route, Prometheus scrape and blackbox probe of cdm-service | `pipeline/render-state-017-cdm-lifecycle-mapping.sh` (the ingress template is excluded from capture) |
| API explorer contracts (`api-explorer/contracts/*-openapi.yaml`) | `contracts/<service>/openapi.yaml` in this pack, preferred by `pipeline/install-generated-api-explorer.sh` over the baseline contracts (Ruling R17) |
| API explorer messaging subjects | `system/messaging-subject-map.md` |

The `cdm-lifecycle-mapping/` compose directory was bootstrapped as a copy of the state 009
`order-management-matcher/` compose directory with the compose project name, Postgres volume, Grafana
default password and Loki `compose_project` labels switched from `traderx-state-009` to
`traderx-state-017`. The 009 `order-management-matcher/` directory is still present in the generated
tree (inherited) but is not used by the 017 runtime scripts.

## Hook Responsibilities

1. Generate parent state output (`009-order-management-matcher`).
2. Apply all ordered patch files from this pack.
3. Run the render script (`pipeline/render-state-017-cdm-lifecycle-mapping.sh`): verify the compose
   project name, copy `cdm-service/` from `runtime-overrides/`, add the ingress `location
   /cdm-service/` route, and add cdm-service to the Prometheus `traderx-spring-boot-actuator` scrape
   job and the blackbox `/health` probe (all idempotent, with post-checks; Ruling R9).
4. Regenerate architecture docs from `system/architecture.model.json`.
5. Keep compatibility with lineage contracts unless explicitly changed.
6. Produce deterministic output suitable for branch publishing.

Runtime scripts:

- `scripts/start-state-017-cdm-lifecycle-mapping-generated.sh` (`--dry-run`, `--skip-build`,
  `--reset-data`, `--help`)
- `scripts/status-state-017-cdm-lifecycle-mapping-generated.sh`
- `scripts/stop-state-017-cdm-lifecycle-mapping-generated.sh`
- `scripts/test-state-017-cdm-lifecycle-mapping.sh` (CDM scenarios (a)-(g), see `tests/smoke/README.md`)

## Capture / Refresh Patch

Edits to inherited components are made in the generated tree and captured from it. Never re-run
`generate-state.sh` over uncaptured edits — it wipes the target tree.

```bash
# 1. Start from a fresh 017 tree.
bash pipeline/generate-state.sh 017-cdm-lifecycle-mapping
# 2. Edit files under generated/code/target-generated/ (not cdm-service/ — edit runtime-overrides instead).
# 3. Capture the edited tree as the child (the parent 009 is regenerated and diffed against it).
#    The script refuses to run unless ci/state-metadata.json says the tree is 017, never
#    overwrites the patch with an empty diff, and regenerates 017 from the new patch afterwards,
#    so the tree is left holding 017 output and repeated captures are safe.
TRADERX_PATCHSET_CHILD_FROM_TARGET=1 \
  bash pipeline/create-state-patchset.sh 017-cdm-lifecycle-mapping 009-order-management-matcher
```

Without `TRADERX_PATCHSET_CHILD_FROM_TARGET=1`, `create-state-patchset.sh` regenerates the child state
from this hook instead of reading the edited tree, so in-place edits would not be captured.

## Working on cdm-service

cdm-service is a plain Gradle project in `generation/runtime-overrides/cdm-service/`. Build and test it
in place (no generation needed):

```bash
cd specs/017-cdm-lifecycle-mapping/generation/runtime-overrides/cdm-service
./gradlew test                                                      # unit and Spring slice tests (in-memory H2; no Docker needed)
./gradlew test --tests '*lifecycle*' -Dcdm.writeGolden=true        # rewrite contracts/examples/*.json
```

Then regenerate (`bash pipeline/generate-state.sh 017-cdm-lifecycle-mapping`) to copy it into the
generated tree. Golden examples are compared byte for byte by `ExecutionMapperTest.goldenExamplesMatch`.
