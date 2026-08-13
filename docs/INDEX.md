# Documentation Index

## Purpose
This directory stores module-specific technical documentation for `com.etendoerp.go`.

## Documents
- `neo-headless.md` — Neo Headless overview and architecture notes.
- `neo-headless-guide.md` — detailed Neo Headless development guide.
- `onboarding-sampledata-packaging.md` — how onboarding sampledata is staged into `WebContent/WEB-INF/classes` for WAR packaging.
- `package-architecture.md` — current Java package boundaries, selector policy split, OAuth2 support split, and PR-scoped Sonar workflow.
- `onboarding-flow.md` — the `ensureOnboardingDataset` step pipeline (dataset, accounting, periodControl, sequences, orgReady, fiscal, orgInfo, customer, bankConnectionSync, bpGroupAcctPatch, acctdimVisibility, baseline), service responsibilities, included-tables rationale, and NDJSON event format.
- `transactional-email-contracts.md` — runtime endpoint, executor/provider boundary, and server-side provider configuration for transactional email contracts.
- `document-email-contract-implementation.md` — step-by-step Java guide for adding document-send transactional email contracts.
- `aeat-303-submit-endpoint.md` — `POST /neo/fiscal303/submit` contract (AEAT Modelo 303 electronic submission, ETP-4456 Phase 2): request/response shapes, error codes, the idempotency guard, certificate flow, and known gaps.
- `STORED-COMPUTED-COLUMNS.md` — functional + technical guide to the stored computed column engine (EPL-1807): concepts, refresh modes, end-to-end flow, configuration, Oracle support, operations, and build validation. Illustrated with the `com.etendoerp.storedcomputedcolumn` pilot.
- `COLUMNAS-COMPUTADAS-ALMACENADAS.md` — Spanish version of the stored computed columns guide.
- `test-jvm-isolation.md` — why `OBBaseTest` subclasses run in their own JVM (`goIsolatedDalTest`), how to add a new one, and the unidentified pool leak behind it.
- `feature-flags-and-tenant-upgrade.md` — OpenFeature flag stack (currently a local configuration-backed provider, with the documented swap point for a hosted control plane), the `tenant-upgrade` onboarding paywall contract, and the tenant plan marker.
