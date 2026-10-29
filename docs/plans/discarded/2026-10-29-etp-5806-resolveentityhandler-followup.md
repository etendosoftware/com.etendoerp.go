# ETP-5806 follow-up: `resolveEntityHandler` has no `@NeoExtension` fallback at 2 call sites

**Discarded (2026-10-29):** closed by ETP-5588's migration, not by a dedicated fix.
ETP-5588 removed `McpHookExecutor.resolveEntityHandler` entirely and replaced both call sites
below with `NeoHandlerLookup.forEntity(sfEntity)`, which tries `NeoExtensionIndex.resolve` first
and falls back to the qualifier only second — exactly the annotation-first resolution this plan
asked for. Verified against the post-merge state of `McpWidgetHandler.java`,
`McpWriteRequestSupport.java`, and `NeoHandlerLookup.java`. Kept for history only; the problem
statement below no longer applies.

**Status:** active (not yet a Jira ticket — recorded here so the knowledge survives the agent
session that found it; a human/Clerk should open the follow-up ticket and link it here).

## Root cause

`McpHookExecutor.resolveEntityHandler(SFEntity)` is `NeoHandlerLookup.byQualifier` alone — a
lookup keyed on `ETGO_SF_ENTITY.Java_Qualifier` / `@Named`, with no `@NeoExtension` annotation
fallback. `NeoExtensionDispatcher.resolveOnly` is the annotation-aware equivalent (annotation
first, `Java_Qualifier` second) used everywhere else in the MCP hook pipeline.

Any handler bound **only** through `@NeoExtension` (no `Java_Qualifier` set) resolves to `null`
through `resolveEntityHandler`. `BpLocationHandler` is exactly this shape, and it is how the
ETP-5806 cycle found the bug: `McpHookExecutor.runReadHook`'s POST phase called
`resolveEntityHandler` directly, so `afterHandle()` never ran over an MCP `etendo_get`/
`etendo_list` read, and the structured SEPA fields leaked through MCP reads regardless of the
`structured-address-for-sepa` flag. Fixed in commit `f0543df32` by switching that one call site to
`NeoExtensionDispatcher.resolveOnly`.

## The 2 call sites not fixed in this cycle

1. `src/com/etendoerp/go/mcp/McpWidgetHandler.java:75` — resolves the entity's handler to let it
   customize widget rendering.
2. `src/com/etendoerp/go/mcp/McpWriteRequestSupport.java:91` — uses the resolved handler only as a
   `!= null` presence check, gating a write guard.

Both still call `resolveEntityHandler` directly and would return `null` for an
`@NeoExtension`-only handler, the same class of bug just fixed on the read path.

## Why not fixed now

- No `@NeoExtension`-only handler currently relies on being found through either site in
  production — the impact today is latent, not active.
- Switching either call site to `resolveOnly` changes resolution semantics for every
  qualifier-set handler these two sites currently serve as well (annotation would now take
  priority over qualifier where both exist), which was untested within the ETP-5806 window and
  risked an unrelated regression.
- Both the developer and both reviewers flagged this during review and agreed a follow-up ticket,
  not a same-cycle widening of the fix, was the right call.

## What the fix would look like

- Replace each direct `McpHookExecutor.resolveEntityHandler(sfEntity)` call with
  `NeoExtensionDispatcher.resolveOnly(request)` (building the appropriate `NeoExtensionRequest`
  for that call site's channel/context), mirroring commit `f0543df32`.
- Add a Mockito-level regression test per call site using an `@NeoExtension`-only handler fixture
  (as `McpHookExecutorTest` did for the read-hook fix), asserting the handler is no longer `null`.
- Re-run `make extension-parity` to confirm no new qualifier/annotation disagreement surfaces for
  the entities exercised through `McpWidgetHandler` and the `McpWriteRequestSupport` write guard.

## Pointers

- Narrative + rationale: `docs/neo-headless.md`, ETP-5806 section ("ETP-5806 follow-up —
  `resolveEntityHandler` has no `@NeoExtension` fallback").
- Fixed reference implementation: `src/com/etendoerp/go/mcp/McpHookExecutor.java` (`runReadHook`),
  commit `f0543df32`, test `McpHookExecutorTest`.
