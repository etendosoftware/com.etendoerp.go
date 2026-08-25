# Process/OBUIAPP Access Overlap-Corruption Guards — Full Trigger Parity Design

**Status:** approved, ready for an implementation plan.

**Supersedes/extends:** `2026-08-24-process-access-overlap-guards-plan.md` (the original ETP-4830
item 7 implementation plan). That plan's Tasks 1–3 (shared `overlap` core, and the refactor of
`WindowAccessOverlapCorruptionGuard` to delegate to it) are DONE, reviewed, and unaffected — this
design builds on top of them, not around them. Task 4 (`ProcessAccessOverlapCorruptionGuard`,
REMOVE-path only) is mid-implementation; its `onDelete` handler is already the FINAL, fully-fixed
version (not a historical intermediate one — it was extracted verbatim from the current, already-
battle-tested `WindowAccessOverlapCorruptionGuard#guardRemovedInheritance`) and is kept as-is, only
extended with the handlers below. **The original Task 5 design (a `TransactionCompletedEvent`-based
post-commit dedup sweep for `OBUIAPP_Process_Access`) is retired entirely** — this design replaces
it with a full-parity guard using the same mechanism as `AD_Process_Access`.

## Why this exists

While building Task 4's REMOVE-path test, a real ADD-path ownership-corruption bug was live-
reproduced in this environment: granting `AD_Process_Access` to a real system template propagates
(unguarded) onto a real pre-existing dependent role that already composes 2+ other templates,
hitting the identical `OBSecurityException` class of bug `WindowAccessOverlapCorruptionGuard`'s own
7-round history already fixed for `AD_Window_Access`. The human asked to close this properly: full
add/edit/delete trigger parity for `AD_Process_Access` AND `OBUIAPP_Process_Access`, not just the
REMOVE-path slice originally approved.

**Scope note on "report access":** classic Etendo reports are `AD_Process` rows with `IsReport='Y'`,
granted through the same `AD_Process_Access` table via the same mechanism — there is no separate
report-access table. Extending the guard for `AD_Process_Access` covers reports and processes
uniformly; no report-specific code exists anywhere in this design.

## Approach

Continues the already-approved philosophy from the original item 7a design (do not re-litigate):
share only pure, entity-agnostic logic in `com.etendoerp.go.roles.overlap`; keep CDI event wiring,
entity-specific queries, and bulk-HQL glue duplicated per guard class. No generic adapter/engine —
considered (a matched-pair `ItemAccessOverlapCorruptionGuard<T>` for the 2 new guards, since they
turn out to need nearly identical mechanisms) and rejected: it introduces a pattern with no
precedent anywhere in this codebase, for a task that's already large enough. `WindowAccessOverlap
CorruptionGuard` itself is never touched by this design.

### The trigger-mapping insight

`WindowAccessOverlapCorruptionGuard`'s class javadoc documents 7 *historical, live-reproduced*
rounds, but its *final* shape (what the class actually does today, ignoring the discovery history)
is only 3 CDI event handlers:

| Handler | What it does | Historical trigger(s) it corresponds to |
|---|---|---|
| `onSave(EntityNewEvent)` — case (a) | A TEMPLATE gains a new grant → unconditionally clear every dependent's conflicting row (forces core's safe CREATE path) | base mechanism + trigger 7 (ADD half) |
| `onSave(EntityNewEvent)` — case (b) | A DEPENDENT's freshly-created inherited row → pin ownership to the dependent's own client/org, widen to most-permissive if another active template justifies it, repoint `InheritedFrom` to whichever template actually justifies the final value | triggers 4, 5 |
| `onSave(EntityNewEvent)` — case (c) | A role gains a NEW `RoleInheritance` from an already-overlapping template → clear the role's own conflicting rows for everything the new template grants | trigger 2 |
| `onUpdate(EntityUpdateEvent)` | A template's OWN existing grant changes level → survey every OTHER actively-inherited template, repoint the dependent's row IN PLACE (never delete — `propagateUpdatedAccess` has no create fallback) to the most-permissive-wins value | trigger 7 (UPDATE half, the "[B7]" gap) + BUG-2 (the most-permissive-wins survey fix) |
| `onDelete(EntityDeleteEvent)` | A role LOSES a `RoleInheritance` → recompute, ONCE per item across ALL remaining templates, the SeqNo-precedence winner + most-permissive-wins level; repoint in place if the existing row doesn't already match | triggers 3, 6 — **already built in Task 4, unchanged by this design** |
| `onTransactionComplete` | Clear the same-flush `TemplateRemovalTracker` marker | supports trigger 6 — **already built in Task 4** |

Everything in the first four rows is new work. The last two rows already exist and are untouched.

### New shared core additions (`com.etendoerp.go.roles.overlap`)

1. **`PropagationTrigger`** — a new pure enum, `NEW_GRANT` / `UPDATED_GRANT`. Extracted because the
   REASONING for why an `onSave`-triggered unconditional clear is safe (core's `propagateNewAccess`
   always has a CREATE fallback) but an `onUpdate`-triggered one is NOT (core's `propagateUpdated
   Access` has none) is entity-agnostic — all 3 guards (window, process, obuiapp) need the identical
   concept. `WindowAccessOverlapCorruptionGuard`'s own private enum of this shape becomes a thin
   delegate to this shared one (same behavior-preserving-refactor discipline as the original Task 3).
2. **`OverlapReconciliationCore.findJustifyingFullGrant(List<GrantCandidate> candidatesOrderedBy
   SeqNoDescending, String excludedTemplateId)`** → `String` (winning template id) or `null`. A small
   extension of the existing pure `computeWinner`: "does some OTHER active template (excluding one
   named one) still grant full access to this item" — the exact decision both the widening trigger
   (case (b) above) and the update-path survey (onUpdate) need. Pure, no Hibernate, unit-testable
   the same way `computeWinner` already is.

No other shared-core changes. `ActiveTemplateInheritance.findActiveTemplatesFor` and
`TemplateRemovalTracker` already correctly serve the new ADD-path widening trigger with zero
changes — the SeqNo-descending, `TemplateRemovalTracker`-aware query they already provide is exactly
what the widening decision needs, confirmed by re-reading both classes' existing javadoc/behavior.

`WindowAccessOverlapCorruptionGuard` is refactored (behavior-preserving, same discipline as the
original Task 3) to delegate its own `PropagationTrigger` enum and its own `findActiveTemplate
GrantingFullAccess` decision to the two new shared pieces above, so there is exactly ONE copy of
each going forward — not three independent copies plus a fourth in the window guard.

### Per-guard structure

`ProcessAccessOverlapCorruptionGuard` (extends the existing Task 4 class) and the new
`ObuiappProcessAccessOverlapCorruptionGuard` (net-new, replaces the retired
`ObuiappProcessAccessDuplicateGuard` idea) each get, mirroring `WindowAccessOverlapCorruptionGuard`'s
own private-method shape 1:1 but against their own entity type
(`org.openbravo.model.ad.access.ProcessAccess` / `org.openbravo.client.application.ProcessAccess`):

- `onSave(EntityNewEvent)` dispatching to `guardDependentsOf` (template gains grant) or
  `correctInheritedOwnership` + `widenInheritedAccessLevelIfNeeded` (dependent's fresh row) or
  `guardNewInheritance` (role gains inheritance) — mirroring the window guard's own `onSave` exactly.
- `onUpdate(EntityUpdateEvent)` dispatching to `guardDependentsOf(UPDATED_GRANT)` →
  `repointIfAlreadySourcedFromTemplate`.
- `onDelete`/`onTransactionComplete` — unchanged from Task 4 (Process) / built fresh but identical
  in shape (OBUIAPP).
- Private entity-specific glue: `findActiveXxxAccess(role, item)`/`findActiveXxxAccess(role)`,
  `findActiveDependentRoles(template)`, `crossClientCriteria`, `sameId`, `deleteForcingCreatePath`
  (bulk HQL DELETE), `repointInPlace` (bulk HQL UPDATE, already exists for Process from Task 4, new
  for OBUIAPP) — each a near-identical copy of the window guard's own private methods, adapted to
  the entity's own property names (`process`/`obuiappProcess` instead of `window`).

**Why `OBUIAPP_Process_Access`'s guard is the same mechanism as `AD_Process_Access`'s, not a
lighter/cleanup-only one** (correcting the original Task 5 design's assumption): the crash this
whole class of guard exists to prevent (`OBSecurityException` from `SecurityChecker.checkWriteAccess`
on a wrongly-owned row) is triggered by ANY entity write with the wrong `client`/`organization` —
it does not depend on the table having a unique constraint. The unique constraint
(`AD_PROCESS_ACCESS_UN_KEY`, absent on `OBUIAPP_Process_Access`) only differentiates ONE specific
sub-case: whether 2+ competing `copyRoleAccess` INSERTs in the REMOVE-path's un-flushed multi-
template walk crash (Process: yes) or silently leave a duplicate row (OBUIAPP: no) — and that one
case (trigger 6) is already handled identically for both via the SAME repoint-in-place mechanism
(which prevents the duplicate from ever being created in the first place, so the distinction is
moot in practice). Every other trigger (ownership correction, widening, the update-path survey)
applies to `OBUIAPP_Process_Access` exactly as much as to `AD_Process_Access`.

## Testing

Mirrors `UserRoleCompositionServiceOverlapIntegrationTest`'s own suite shape, once per new guard
(so twice total — Process and OBUIAPP), using the SAME "bystander role built via raw
`AD_Role_Inheritance`, never through `UserRoleCompositionService`" pattern already established:

1. Bystander role gaining 2 overlapping templates via raw inheritance (ADD-path ownership
   correction + ownership-pin assertions) — ports `testBystanderRoleNotPassedToAssignTemplateRoles
   IsAlsoProtected`.
2. Gaining a read-only template second never downgrades existing full access (most-permissive-wins
   widening) — ports `testGainingReadOnlyTemplateInheritanceNeverDowngradesExistingFullAccess`.
3. `InheritedFrom` bookkeeping: widening also repoints to the template that actually justifies the
   value, and a later removal of THAT template correctly re-triggers re-derivation — ports
   `testRemovingTheTemplateThatJustifiedAWidenedAccessLevelCorrectlyDowngrades`.
4. Updating a template's own existing grant level never deletes an already-correctly-sourced
   dependent's row (the "[B7]" gap) — ports
   `testUpdatingTemplatesOwnAccessLevelNeverDeletesAnAlreadyCorrectlySourcedDependentRow`, using a
   throwaway system-client template (same reasoning as the original: reusing a real template for a
   direct UPDATE cascades across every real dependent in this shared dev DB).
5. Downgrading one of two overlapping templates never downgrades a dependent when the other still
   grants full access (BUG-2, the update-path most-permissive-wins survey) — ports
   `testDowngradingOneOfTwoOverlappingTemplatesNeverDowngradesDependentWhenTheOtherStillGrantsFull
   Access`.
6. Task 4's existing REMOVE-path test (`testRemovingOneOfFourTemplatesLeavesTwoRemainingOverlapping
   TemplatesUnbroken`, already fixed to use throwaway templates per the earlier ruling) stays exactly
   as-is for Process; an equivalent is added fresh for OBUIAPP.

Plus: new unit tests for `OverlapReconciliationCore.findJustifyingFullGrant` (no DB, same style as
the existing `computeWinner` tests) and `PropagationTrigger` needs no dedicated test (it's a bare
enum, exercised entirely through the integration tests above).

**Regression-safety requirement, same discipline as the original Task 3:** after refactoring
`WindowAccessOverlapCorruptionGuard` to delegate its own `PropagationTrigger`/`findActiveTemplate
GrantingFullAccess` to the 2 new shared pieces, `UserRoleCompositionServiceOverlapIntegrationTest`
and `UserRoleCompositionServiceOverlapReverificationTest` must pass completely unchanged.

## Out of scope

- Any change to `AD_Window_Access` behavior beyond the 2 delegation points named above.
- A generic adapter/engine shared between the Process and OBUIAPP guards (considered, rejected —
  see Approach above).
- Anything for the classic-report subset already investigated and confirmed out of scope earlier in
  ETP-4830 (the 54 `AD_Menu` reports with no `ETGO_SF_SPEC` entry — irrelevant to Etendo GO, not a
  grant problem).
