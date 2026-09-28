# Documentation Index

## Purpose
This directory stores module-specific technical documentation for `com.etendoerp.go`.

## Documents
- `neo-headless.md` — Neo Headless overview and architecture notes.
- `neo-headless-guide.md` — detailed Neo Headless development guide.
- `onboarding-sampledata-packaging.md` — how onboarding sampledata is staged into `WebContent/WEB-INF/classes` for WAR packaging.
- `package-architecture.md` — current Java package boundaries, selector policy split, OAuth2 support split, and PR-scoped Sonar workflow.
- `onboarding-flow.md` — the `ensureOnboardingDataset` step pipeline (dataset, accounting chart/name wiring, GL Item provisioning, entity posting-account provisioning, periodControl, sequences, orgReady, fiscal, orgInfo, customer, bankConnectionSync, bpGroupAcctPatch, acctdimVisibility, baseline), service responsibilities, included-tables rationale, NDJSON event format, and the pre-provisioned tenant pool (ETP-5389: flag, `ETGO_TENANT_POOL`, filler process, claim, placeholder gaps).
- `transactional-email-contracts.md` — runtime endpoint, executor/provider boundary, and server-side provider configuration for transactional email contracts.
- `document-email-contract-implementation.md` — step-by-step Java guide for adding document-send transactional email contracts.
- `aeat-303-submit-endpoint.md` — `POST /neo/fiscal303/submit` contract (AEAT Modelo 303 electronic submission, ETP-4456 Phase 2): request/response shapes, error codes, the idempotency guard, certificate flow, and known gaps.
- `STORED-COMPUTED-COLUMNS.md` — functional + technical guide to the stored computed column engine (EPL-1807): concepts, refresh modes, end-to-end flow, configuration, Oracle support, operations, and build validation. Illustrated with the `com.etendoerp.storedcomputedcolumn` pilot.
- `COLUMNAS-COMPUTADAS-ALMACENADAS.md` — Spanish version of the stored computed columns guide.
- `test-jvm-isolation.md` — why `OBBaseTest` subclasses run in their own JVM (`goIsolatedDalTest`), how to add a new one, and the unidentified pool leak behind it.
- `gradle-worktree-testing.md` — why `./gradlew test` from the Etendo root never sees a `git worktree` branch's own content, the `tasks.gradle` task-name collision this can cause, and the detach/remove/restore workaround to actually run Gradle against a worktree's code.
- `feature-flags-and-tenant-upgrade.md` — OpenFeature flag stack (currently a local configuration-backed provider with no flag declared, plus the documented swap point for a hosted control plane), the unconditional paid-environment paywall contract, and the tenant plan marker. The `tenant-upgrade` flag was retired in ETP-4966.
- Checkout and billing audit tables (ETP-5045) — `ETGO_CHECKOUT_REQUEST` (`CheckoutRequestStore`) and `ETGO_BILLING_EVENT` (`BillingEventStore`, the durable webhook idempotency gate) are described in the "Closed by ETP-5045" note of `feature-flags-and-tenant-upgrade.md`; both are readable from the read-only Classic windows **Checkout Request** (with its **Billing Event** child tab) and **Billing Event**. The local replay procedure lives in the functional repo (`schema_forge/docs/stripe-local-testing.md`).
- `mcp-usage-telemetry.md` — the `ETGO_MCP_USAGE` table and the fire-and-forget writer behind it (Track B1): why the write is out of the business transaction, why it records shape and never content, the minted `Mcp-Session-Id`, and the per-instance opt-out.
