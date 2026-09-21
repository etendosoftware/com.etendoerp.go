# Open and notable topics — recurring billing block (ETP-5045 … ETP-5053)

A living register of the decisions still owed, the operational constraints that are not visible
from the code, and the traps that would otherwise be rediscovered the hard way.

**Scope:** the whole billing development — ETP-5045 (durable checkout state), ETP-5050 (usage
measurement), ETP-5046 (plan catalog + subscriptions), and what they hand to ETP-5047/5048/5051/5053.

**Status key:** 🔴 decision owed · 🟠 constraint to respect · 🟡 known issue, worked around · 🟢 closed

---

## 1. Decisions still owed

### 🔴 1.1 `activeUsers` — data source, and flow versus stock (ETP-5050)

The significant unresolved issue in the usage design, and it decides whether `activeUsers` can be
implemented at all. `AD_SESSION` is explicitly ruled out.

A **flow** happens *on* a day (three invoices dated 14 March count as 3 on 14 March, forever). A
**stock** *exists on* a day (a tenant with 12 active users has 12 every day until something
changes). **A stock cannot be expressed declaratively**, because there are no rows dated that day
to count — which is exactly why the strategy SPI exists.

The problem is that **a stock has no history to recompute from**. Asked "how many active users on
2010-01-01?", `AD_USER` can only answer "how many are active *now*". Observed directly: a trial
backfill of a stock resource over 2010–2027 wrote 74,520 rows carrying essentially one figure
repeated across seventeen years. The mechanism worked as designed; the numbers were meaningless
for every day but the current one. Neither the settling window nor the aggregation engine can fix
this — the information is not in the database.

Three options, and the choice is a product decision:

1. **Keep a dated history** (audit/snapshot table) so a past day has a real answer. Most faithful, most expensive.
2. **Only ever record the current day**, never backfill. Cheap and honest; history begins when measurement is switched on.
3. **Redefine the resource as a flow** — e.g. "users created that day" rather than "users active that day". A *different quantity*, so this is a product call, not a technical shortcut.

Full analysis: `schema_forge/docs/usage-measurement.md` §4.

### 🔴 1.2 `productiveEnvironments` is a flow wearing a stock's name

Specified as `Client` bucketed by `creationDate` — which is option 3 above *by accident*. It
counts clients **created** that day (a flow) while the billable quantity is how many exist (a
stock). With no rollup yet the difference does not bite, but **storing a flow under a stock's name
makes every later reading of it wrong**, including any quota built on it in ETP-5051.

### 🔴 1.3 Blank `planKey` on checkout — which error?

A blank `planKey` currently answers `400 INVALID_REQUEST`, mirroring the existing
`clientName is required` treatment. An *unknown or inactive* key answers `400 PLAN_NOT_AVAILABLE`,
and deliberately does not distinguish the two so the endpoint cannot be used to enumerate which
plan keys exist. Open question: collapse blank into `PLAN_NOT_AVAILABLE` for one uniform answer,
or keep the distinction because blank is a client bug and unknown is a tampering signal.

---

## 2. Deployment — constraints that are not visible in the code

### 🟠 2.1 A priced plan row must exist BEFORE the code goes live

`etendo.go.checkout.price.id` is deleted, with **no fallback** — a fallback price is a price
nobody reviewed, selected exactly when the intended configuration is missing. The moment the
branch deploys, checkout answers `CHECKOUT_NOT_CONFIGURED` until an operator creates an
`ETGO_PLAN` row carrying a real Stripe price id.

**No sourcedata row can supply it**, because a real price id is environment-specific (test vs live
Stripe). This is a pre-deploy step, not a code problem.

### 🟠 2.2 The module and the app-shell must ship together

`planKey` is required on checkout with no default-plan property. The app-shell learns keys from
`GET /sws/go/plans`. A module deployed ahead of the app-shell breaks the upgrade flow; an
app-shell deployed ahead of the module sends a field nothing reads. There is no version-skew
bridge, by design.

### 🟠 2.3 Run the backfill AFTER the deploy, never before

`resolvePlan` reads the subscription first. A tenant provisioned between the schema landing and
the code shipping gets a preference and no subscription. The backfill's `@check` catches exactly
that tenant because it keys on the preference rather than on a date — but only if it runs
afterwards.

The transitional fallback (§3.2) means this ordering is no longer *load-bearing for uptime*, only
for completeness.

### 🟠 2.4 The sandbox attestation is per-environment and blocks merge

`R37`'s `@report` carries the verbatim outcome of a manual pre-check: *re-verify that production
Stripe checkout has not gone live since 2026-08-27; if it has, a real paying cohort exists that
the backfill would orphan and an adoption step is required first.*

The regression test **fails the build** while the `TODO-PRECHECK-R37` marker is present, so it
cannot ship unfilled. The check must be run against the **target** environment — verifying it on a
dev box proves nothing about staging. On the dev box (2026-09-18) the Stripe key is `sk_test`, so
the assumption holds *there*.

### 🟠 2.5 `./gradlew test` needs JDK 17, not the default 21

`build.gradle` targets Java 17; core's bundled Groovy/ASM cannot read Java 21 bytecode.
`:compileTestGroovy` dies with `Unsupported class file major version 65` while compiling core's
own Spock specs — so **no test runs at all**, and the failure looks unrelated to whatever you
changed. `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`. **Confirm what CI uses** before
trusting a green local run.

### 🟠 2.6 Gradle reports `UP-TO-DATE` and runs nothing

A re-run after an unrelated change silently executes zero tests while printing `BUILD SUCCESSFUL`.
Delete `build/test-results/{test,goIsolatedDalTest}` and pass `--rerun`, then read the task outcome
lines — not just the build result. This produced a false "verified" claim once during ETP-5046.

### 🟠 2.7 `Hooks-Verified` seals do NOT survive a rebase, despite claiming to

The trailer carries two fingerprints: one over the commit's tree, and — in `v2` — one over its
patch-id, so that *"a rebase/cherry-pick that replays the SAME diff onto another base keeps a valid
seal"*. In practice the patch-id half is broken for most existing commits.

Observed while merging the ETP-5045 fix through the chain: **four ETP-5050 commits carry the
identical fp2, `f99dd62f8d62`.** A patch-id fingerprint must be unique per diff; these were sealed
from a proof file whose patch-id was not refreshed between commits. Only the two newest ETP-5050
commits have correct values.

The consequence is invisible until someone rebases, because while the tree fingerprint matches the
fallback is never exercised. A rebase of `feature/ETP-5050` left **26 of 28 commits unverified**,
which `pre-push` rejects — forcing either `--no-verify` (which also skips the test/coverage/Sonar
gate) or re-sealing every commit through the hook.

**Practical guidance until it is fixed: merge rather than rebase when propagating a fix down a
chain of already-sealed branches.** A merge commit is explicitly exempt from verification
(`hooks_verify_commit` returns 0 for anything with more than one parent), rewrites nothing, and
needs no force-push. That is how the ETP-5045 context fix reached ETP-5050 and ETP-5046.

**Caveat that applies to either strategy:** a clean textual merge is not a working one. Merging
ETP-5045 into ETP-5046 produced zero conflicts in the test file and then failed to compile —
ETP-5045's new tests call `recordRequested` with four arguments, ETP-5046 widened it to five, and
the two edits sat in different regions so git saw no conflict. Always build after propagating.

---

## 3. Legacy plans and the cutover

### 🟠 3.1 The grandfathered plan has no price, deliberately

`legacy-productive` ships as module sourcedata with `PROVIDER_PRICE_ID`, `BILLING_INTERVAL`,
`DISPLAY_PRICE` and `CURRENCY_CODE` all NULL, and **zero quota rows**. That makes it unpurchasable
by construction (checkout refuses it), unlimited by definition, and a no-op for the price-derivation
handler.

A backfilled subscription is therefore **not a billing record**. Its two jobs are **access control**
(`resolvePlan` reads productive) and **webhook correlation** (ETP-5047 matches on
`stripe_subscription_id`, which the backfill copies where a checkout request exists). Neither needs
a price.

This is acceptable *only because* every existing Stripe subscription is Test Mode. If production
checkout has gone live, the answer is not a priceless row — it is the adoption step: read each real
subscription's price from Stripe and record it. The `StripeApiClient` built in ETP-5046 makes that
cheap; it was left out of scope while the sandbox assumption holds.

### 🟠 3.2 The preference is retired per tenant, not fleet-wide

A tenant's `ETGO_TenantPlan` preference is deleted **at the moment it gains a live subscription** —
in `R37`'s `@apply` (same transaction as the INSERT, guarded on an open subscription existing) and
in `applyPaidUpgradeSideEffects` (on a successful subscription write).

A **transitional read fallback** (`TenantPlanPreferenceFallback`) covers the gap: no open
subscription → fall back to the preference, so no paying tenant is ever stranded as free. It applies
to both resolution paths — the single-tenant one and the `EnvironmentPlanCache` bulk one — because
if only one had it, the environment list and `resolvePlan` would disagree about the same tenant,
which is worse than the original bug.

**The observable end condition**, which is the point of doing it this way:

```sql
select count(*) from ad_preference where attribute = 'ETGO_TenantPlan';   -- reaches 0
```

plus the fallback's WARN lines ceasing. When both hold, everything marked
`ETP-5046-TRANSITIONAL-FALLBACK` plus `markProductive` and `PREFERENCE_ATTRIBUTE` can be deleted.
That is a query, not a judgement call.

The WARN is deliberate: a silent fallback would let the backfill be forgotten indefinitely.

### 🟠 3.3 The preference is scoped by `VISIBLEAT_CLIENT_ID`, never `AD_CLIENT_ID`

`Preferences.setPreferenceValue` writes the row at `ad_client_id = '0'` and puts the tenant in
`VISIBLEAT_CLIENT_ID`. Filtering on `ad_client_id` matches **zero rows for every tenant** —
verified on live data: `via_visibleat = 6, via_adclient = 0`.

This mistake nearly shipped twice on ETP-5046. On the read side it silently reclassifies every
paying tenant as free; on the retirement side it is a silent no-op that would block the §3.2 end
condition forever with nothing reporting why.

Note `ETSG_ForceTestMode` uses the **opposite** shape — the row's own `AD_CLIENT_ID` *is* the
tenant, because its handlers resolve on `Preference.client`. The two preferences are not
interchangeable.

### 🟠 3.4 Ordering invariant: R31/R32 versus R37 — a fiscal-compliance stake

`R31` (force test mode on demo/free tenants) and `R32` (its inverse) read `ETGO_TenantPlan`
directly in SQL and know nothing about `etgo_subscription`. If `R31` ever ran for a tenant **after**
`R37` deleted its preference, it would read a paying tenant as free and force
`ETSG_ForceTestMode='Y'` — routing their real SII/TicketBAI/VeriFactu submissions to the tax
authority's **test** endpoints. That is a compliance failure, not a config nit.

Two independent mechanisms prevent it:

- The runner's watermark is a **strict date** (`Math.max` over PROCESSED fix timestamps, "skip
  everything at or before it, no look-back"). Once `R37` (2026-09-18) is processed, `R31`
  (2026-09-01) is skipped forever.
- If `R31` had `FAILED`, the runner halts that tenant's chain, so `R37` could never run for it.

`R31`/`R32` were **not edited** — an applied data-fix is immutable per the framework README and is
superseded by a new dated file, never modified in place.

> **Any FUTURE data-fix must key on `etgo_subscription`, not on `ETGO_TenantPlan`.** After `R37`
> the preference is present only for tenants the backfill has not reached.

---

### 🟠 3.5 An unrecognised subscription status means ENTITLED, never locked out

**Context — two models met in the merge.** While ETP-5046 was putting subscription state into
`ETGO_SUBSCRIPTION`, `develop` shipped a parallel model for the same concept:
`EnvironmentAccessPolicy` + `TenantEnvironmentLifecycleService`, persisting to an
`ETGO_SubscriptionStatus` AD_Preference (its own javadoc calls preferences *"a compatibility-first
persistence adapter"* — the exact design ETP-5046 exists to retire). Both emitted a
`subscriptionStatus` field in the environment payload, with **different value vocabularies**.
Martin's call (2026-09-21): unify on the table. `TenantEnvironmentLifecycleService.productiveSnapshot()`
now reads the open subscription row, and `subscriptionStatusOf()` maps its `STATUS` onto the
access policy's enum.

| `ETGO_SUBSCRIPTION.STATUS` | `EnvironmentAccessPolicy.SubscriptionStatus` |
|---|---|
| `active` | `CURRENT` |
| `past_due` | `PAST_DUE` |
| `canceled` | `EXPIRED` |
| **anything else, including null/blank** | **`LEGACY_ENTITLEMENT`** |

**The default is the load-bearing part, and it is deliberately fail-OPEN.** This value reaches
`EnvironmentAccessPolicy`, which decides whether a user may enter the product at all. A tenant
reaching this mapping demonstrably holds an *open* subscription row, so the only safe reading of a
status the deployed build does not recognise is "entitled". `NONE` would lock a paying customer
out of their own environment purely because someone added a status value this build predates. The
asymmetry justifies it: a wrongly-admitted tenant is a billing discrepancy, a wrongly-excluded
paying tenant is an outage.

**The cost of that choice, and the trap:** a new `STATUS` value is **invisible** — no error, no
warning, no log line, it simply reads as `LEGACY_ENTITLEMENT` and the tenant keeps working. Any
ticket that adds a value to `ETGO_SUBSCRIPTION.STATUS` (ETP-5053's plan changes are the likely
first) **MUST extend `subscriptionStatusOf()` in the same change**, and the DDL check constraint
`ETGO_SUB_STATUS_CHK` is the place to notice it — the constraint and the mapping must be edited
together or they drift apart in silence.

**Two things deliberately NOT moved to the table:**

- `ENVIRONMENT_TYPE` stays a preference. It records DEMO versus PRODUCTIVE, which the subscription
  table does not carry, so `applyPaidUpgradeSideEffects` still marks the lifecycle projection
  whichever way the payment itself was recorded.
- The `ETGO_SubscriptionStatus` / `ETGO_SubscriptionDueAt` preferences are still read **when the
  tenant has no subscription row at all** (`ETP-5046-TRANSITIONAL-FALLBACK`). The order matters:
  consulting them first would let the access policy and the plan catalog disagree about the same
  tenant, which is the whole point of the unification. That block goes in Phase F with the rest of
  the fallback, once R37 has given every productive tenant a row.

**Asymmetry on the WRITE path, recorded rather than fixed.** `applyPaidUpgradeSideEffects` now
touches three stores: it opens the `ETGO_SUBSCRIPTION` row (the record), *deletes* the
`ETGO_TenantPlan` preference when that succeeds, and then calls
`tenantEnvironmentLifecycleService.markProductive()` **unconditionally**, which writes both
`ETGO_EnvironmentType` and `ETGO_SubscriptionStatus = CURRENT`. So the happy path retires one
preference while still writing another that, after the unification, is only ever *read* when no
subscription row exists.

That is defensible — it seeds the fallback, so a tenant whose subscription row is later lost reads
`CURRENT` rather than being locked out — but it is a side effect of the merge, not a designed
behaviour, and it means **`ETGO_SubscriptionStatus` can go stale against the table**: nothing
updates it when a subscription moves to `past_due` or `canceled`. Harmless while the table wins
every read that matters; actively misleading to anyone who inspects the preference to answer "is
this tenant paying". Either stop writing it on the subscription path, or keep it in step — the
Phase F cleanup should settle which.

---

## 4. Known issues — one fixed, the rest live

### 🟢 4.1 `CheckoutRequestStore` leaked its `OBContext` — FIXED on ETP-5045

Every method set `OBContext.setOBContext("0","0","0","0")` + admin mode, but its `finally` called
only `restorePreviousMode()` — which pops the **admin-mode stack, not the context**. The caller's
`OBContext` was silently replaced and never restored.

Harmless for the callers that existed when it was written (the webhook path holds no context at
all); **not** harmless from `applyPaidUpgradeSideEffects`, which runs after `prepareAdminContext`
mid-onboarding where every later provisioning step depends on the context it was given.

Fixed in `Feature ETP-5045: Restore the caller OBContext in CheckoutRequestStore`. All seven entry
points now run through one private `runAsSystem(...)` wrapper that captures the caller's context
and restores it in the `finally`. Two details worth keeping:

- **Admin mode is restored BEFORE the context, and the order is load-bearing.**
  `restorePreviousMode()` pops the stack and then inspects whichever context is current at that
  moment; if the stack empties while the *caller's* context is installed, it would clear it —
  reintroducing the same leak from the other end.
- **`null` is a legitimate previous context** and must survive as `null`. A contextless caller is
  left contextless, never upgraded to a system one.

Neither restorer can throw, so a restore failure cannot mask the body's exception.

**ETP-5046's caller-side workaround is now redundant but still correct** — the helper that captures
the context around the store call, and `SubscriptionService` opening a system context only when
there is none. Left in place deliberately: it is defensive rather than wrong, and unpicking it
during a merge would be churn. Remove it whenever that file is next touched for its own reasons.

### 🟡 4.2 `ETGO_SF_FIELD` rows with a dangling `AD_COLUMN` break `update.database`

Recurring. `ETGO_SF_FIELD` rows referencing a deleted `AD_COLUMN` fail `etgo_sf_fld_col_fk`. Fix:
delete the `<AD_COLUMN_ID>` **line**, not the record — the column is `required="false"` and the
field stays identified by its `JAVA_QUALIFIER`.

**`check-etgo-xml.sh` does not catch this** — it reports "Sin huérfanos" while the dangling
reference is present, because its referential-integrity pass does not validate
`ETGO_SF_FIELD.AD_COLUMN_ID` against core's `AD_COLUMN`. That silence is why it keeps resurfacing;
only a failed `update.database` finds it. **Worth adding to that script.** Sweep for all of them at
once rather than one per failed build — the error names only the first.

### 🟡 4.3 Behaviour change: `revertTestModeForProductiveTenantBestEffort` fires more often

It previously fired only when `markProductive` succeeded. It now fires whenever the tenant was
recorded productive by **either** store, so it also covers the success path — where it previously
reverted test mode only as a side effect of the preference write. Same intent (ETP-5117), wider
trigger. Given §3.4's stake, firing more often is the safe direction, but it is a behaviour change.

---

### 🟡 4.4 The at-sign guard is over-tested (ETP-5050)

`UsageMessages.atSafe` replaces `@` with `(at)` in any value interpolated into a user-facing
message, because `OBMessageUtils` treats `@token@` as a substitution and an unescaped at-sign in a
catalog search key, an HQL fragment or a Hibernate/CDI exception message can blank the message. The
guard is sound and has 22 call sites.

The **production surface is one line**:

```java
return StringUtils.isEmpty(text) ? text : StringUtils.replace(text, "@", "(at)");
```

The **test surface is roughly 30 assertions** — about 16 executed cases in `UsageMessagesTest`
(124 lines) plus ~16 `contains("@")` assertions across `UsageResourceValidatorTest`,
`UsageAggregationProcessTest`, `UsageRunLedgerTest` and `BillingResourceEventHandlerTest`.

Roughly ten of those cases test **`String.replace` semantics rather than any decision of ours**:

- `atSignsAtTheEdgesAreReplacedToo` (4 cases) — leading / trailing / adjacent at-signs have no
  distinct behaviour to discover; that is JDK contract.
- `textWithNoAtSignIsUnchanged` (5 cases) — one suffices; "no-op when nothing matches" is again
  `String.replace`.
- `emptyPassesThroughUnchanged` asserts `assertSame("", atSafe(""))` — a string-identity detail of
  `StringUtils`, brittle and carrying no information about our behaviour.

Two **do** earn their place and should survive any trim:

- `oneAtSignIsReplacedInPlace` pins **replace versus strip**. Stripping turns `a@b.com` into
  `ab.com`, which reads as a real value and is quietly wrong, whereas `a(at)b.com` is visibly a
  substitution. Asserting the exact output is what holds that distinction; a test that only checked
  "no at-sign remains" would accept the stripping version.
- `nullPassesThrough` — these run inside error handlers, and a guard that threw would convert a
  message about a failure into a second, more confusing failure.

The call-site assertions are legitimate in principle (a guard nobody calls is not a guard, and
there are 22 call sites) but over-applied at sixteen; three or four spot checks carry the same
information.

**Suggested shape:** ~4 unit cases plus 3–4 call-site spot checks, down from ~30. Low risk, no
behaviour change — the cost being paid is maintenance drag and reviewer attention, not correctness.
Raised by Martin on 2026-09-18; not yet actioned.

### 🟢 4.5 `BillingEventStore` had the identical `OBContext` leak — FIXED

`claim(...)` and `updateResult(...)` installed the system context and unwound with
`restorePreviousMode()` alone, exactly as §4.1 did. Fixed on ETP-5046 with the same `runAsSystem`
helper copied from `CheckoutRequestStore`, so the two classes now behave identically rather than
one merely claiming in its javadoc to follow the other.

Pinned by three specs in `BillingEventStoreIntegrationTest` ("Group 9"), mirroring Group 6 of the
`CheckoutRequestStore` suite: every entry point hands back the **same** `OBContext` instance
(`assertSame`, not an id comparison — `setOBContext(String,String,String,String)` builds a new
object each call, so an equal-looking context is still the wrong one), a contextless caller is left
contextless rather than upgraded to system, and a call that throws still restores. Each carries a
sanity assertion on committed state, so a store that "restored the context" by doing no work would
still fail.

### 🟡 4.6 `recordRequested` accepts a null plan that production cannot produce

`CheckoutRequestStore.recordRequested(..., Plan plan)` records the catalog row being bought, so the
subscription opened after payment reflects **what the buyer actually saw** rather than whatever the
plan says by then. `ETGO_CHECKOUT_REQUEST.ETGO_PLAN_ID` is nullable because rows predating ETP-5046
have no plan.

But the production path cannot pass null: `HostedCheckoutService` resolves the plan with
`orElseThrow` and then rejects one with no provider price. Null is therefore a **test-only** value —
six fixtures pass it — and the parameter currently accepts the one thing it exists to prevent. A
change that dropped the plan on the way in would write a row that is indistinguishable from a legacy
one, and the subscription would open not knowing what was bought.

**Suggested fix:** `Objects.requireNonNull(plan, ...)` and give the fixtures a real `Plan`.
`GrandfatheredSubscriptionIntegrationTest` already creates one, so the pattern exists. Not done
during the ETP-5045 merge because changing a method contract mid-merge is the wrong moment.

### 🟠 4.7 The rest of the `OBContext` survey — one real, one false alarm, one unread

Chasing §4.1 and §4.5 raised the obvious question: how many more of these are there? A module-wide
survey of every class that installs a system context, with the verdict for each. **The grep alone
lies in both directions** — it flags comments as calls and cannot see a guarded install — so each
line below is the result of reading the code, not of counting matches.

| Class | Verdict |
|---|---|
| `payment/CheckoutRequestStore` | ✅ fixed (§4.1) |
| `payment/BillingEventStore` | ✅ fixed (§4.5) |
| `payment/SubscriptionService` | ✅ correct by design — `openSystemContextWhenAbsent()` sets a context only when there is none, and its javadoc already spells out this hazard |
| `rest/TransactionalAuthEmailSender` | ✅ captures and restores |
| `rest/CompanyInvitationService` | ❌ **real, unfixed** — see below |
| `roles/RoleInheritanceReconciliationService` | ⚪ **false positive** — its only `setOBContext` match is prose in a comment (line 358) describing a *caller* that runs as system; there is no call |
| `rest/EtendoGoJwtServlet` | ❓ **unaudited** — 22 raw installs against a single capture/restore pair |

**`CompanyInvitationService` — the real one.** Two sites, both `restorePreviousMode()`-only:

- `resolveInvitation(...)` — installs at **509–510**, unwinds at **553–555**
- `withAdminMode(...)` — installs at **755–756**, unwinds at **759–761**

It is the cheapest of the three to fix, because `withAdminMode` is *already* the `runAsSystem`
shape — a wrapper every accept path funnels through (`acceptExistingAccountInAdminMode` at 576,
`registerAndAcceptInAdminMode` at 661). It simply never captures. `resolveInvitation` is the only
method opening the context inline, so routing it through `withAdminMode` would collapse the class
to one context site and fix both at once. The case to care about is
`registerAndAcceptInAdminMode`, which creates a user and accepts an invitation: anything continuing
on that thread afterwards runs as system. That is the shape of the hazard; no exploit has been
traced.

**`EtendoGoJwtServlet` — deliberately left as a question.** 22 installs and one capture/restore is
not evidence of 21 leaks, and it is not evidence of none either. A heuristic marked it "OK" on the
strength of that single site; nobody has read the other 21. Whoever picks this up should treat the
verdict as unknown rather than inherit an unearned pass.

**Left unfixed on purpose.** Neither belongs to ETP-5046, and widening an already large merge to
carry them would make it harder to review, not safer. They want their own ticket — and the fix is
mechanical once the pattern is recognised, which is the entire reason this section exists.

## 5. Handed forward to later tickets

### 🟠 5.1 ETP-5051: "no quota row" means UNLIMITED

`ETGO_PLAN_QUOTA.INCLUDED_QTY` is `required="true"` with **no default**, in the DDL *and* in
`AD_COLUMN.DEFAULTVALUE`. This deliberately breaks the module's own pattern — every comparable
required DECIMAL here carries `<default>0</default>`.

A `LEFT JOIN ... COALESCE(included_qty, 0)` in the evaluator silently caps every unquota'd resource
at zero. `PlanQuotaSchemaInvariantTest` guards the schema side (mutation-tested); **nothing can
guard the evaluator except this paragraph.** `ETGO_PLAN_QUOTA` is also the only new table with
`ISDELETEABLE='Y'`, because deleting the last quota row is how an operator restores unlimited.

### 🟠 5.2 ETP-5047: correlate to the OPEN row

`STRIPE_SUBSCRIPTION_ID` is deliberately **not unique**: a plan change updates the item on the same
Stripe subscription while opening a *new* Etendo row, so two local rows legitimately share one id.
Lifecycle webhooks must resolve to the row with `END_DATE IS NULL`, not to "the" row.

### 🟠 5.3 ETP-5053: a plan change opens a new row

Re-pricing a plan does **not** re-price existing subscribers — the subscription carries its own
`PROVIDER_PRICE_ID` and amount snapshot, never rewritten by a plan edit. A plan change closes the
current row (`END_DATE`) and inserts a successor, preserving price history. `PENDING_PLAN_ID` and
`PENDING_EFFECTIVE_DATE` already exist, nullable and hidden, so no second AD pass is needed.

---

## 6. Closed

### 🟢 6.1 `ETGO_BILLING_RESOURCE` has two `ISIDENTIFIER='Y'` columns

`Value` *and* `Name`. Raised during ETP-5046 as a possible latent defect; **confirmed fine** by
Martin on 2026-09-18 — two identifier columns are a supported and intentional shape. `ETGO_PLAN`
was given a single identifier (`Name`); adding `Value` alongside it would show the key in lookups
and is a cosmetic choice, not a correctness one.

---

## Related documents

- `plans/2026-09-18-etp-5046-plan-and-subscription-design.md` — the ETP-5046 design of record
- `plans/2026-09-18-etp-5046-stage-b-ad-handoff.md` — the AD authoring record
- `plans/2026-09-15-etp-5050-usage-measurement-design.md` — the usage engine design
- `schema_forge/docs/usage-measurement.md` — §4 is the full flow-versus-stock analysis
- `schema_forge/docs/plans/2026-08-27-recurring-billing-and-resource-limits-prd.md` — the governing PRD
- `feature-flags-and-tenant-upgrade.md` — paywall, plan marker, transitional fallback
