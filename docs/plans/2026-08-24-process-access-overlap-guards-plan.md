# Process/OBUIAPP Access Overlap-Corruption Guards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close ETP-4830 item 7 — extend `WindowAccessOverlapCorruptionGuard`'s proven REMOVE-path reconciliation (the "sixth trigger" fix) to `AD_Process_Access` (crash-capable, real unique constraint) and `OBUIAPP_Process_Access` (dedup-only, no unique constraint), via a small shared, Hibernate-free reconciliation core plus two new entity-specific guards.

**Architecture:** Extract exactly the three access-type-agnostic pieces of `WindowAccessOverlapCorruptionGuard`'s REMOVE-side mechanism (the SeqNo-descending winner/level decision, the "which templates does this role actively inherit from" query, and the same-flush `TEMPLATES_BEING_REMOVED` marker) into a new `com.etendoerp.go.roles.overlap` package. Refactor `WindowAccessOverlapCorruptionGuard` to delegate to it (behavior-preserving). Build two new, materially simpler sibling guards on top: `ProcessAccessOverlapCorruptionGuard` (prevention, mirrors the window guard's REMOVE path exactly) and `ObuiappProcessAccessDuplicateGuard` (cleanup-only, since there is nothing to crash).

**Tech Stack:** Java 8, Openbravo DAL/Hibernate 5, CDI (Weld) `EntityPersistenceEventObserver`, JUnit 4 (`WeldBaseTest` integration tests) + JUnit 5 (plain unit tests), Gradle.

**Spec:** `/Users/gremiger/workspaces/etendogoclean/etendo/etendo_schema_forge/santo_4830_roles.md`, sections "### 7. NEW, NOT YET FIXED — structural duplicate-INSERT race on process/report access, no guard exists" and "### 7a. Design (brainstormed 2026-08-24 with the human, approach approved — ready for an implementation plan)". Read both before starting — this plan implements exactly that approved design (approach C: share only the pure reconciliation core, keep entity-specific event wiring separate).

## Global Constraints

- **Scope is REMOVE-path only.** Do not build anything for the ADD path (a template gaining a new grant, or a role gaining a new inheritance) or the UPDATE path (a template's own existing grant changing level) for `AD_Process_Access`/`OBUIAPP_Process_Access`. Those are the other 6 triggers `WindowAccessOverlapCorruptionGuard` needed and are explicitly deferred — not proven necessary for process/report access yet. This applies to every task below; do not add `onSave`/`onUpdate` handling to either new guard.
- **Never touch `AD_Window_Access` behavior.** `WindowAccessOverlapCorruptionGuard`'s own 7 triggers stay exactly as they are; Task 3's refactor must be a pure delegation, not a behavior change — every existing test in `UserRoleCompositionServiceOverlapIntegrationTest` and `UserRoleCompositionServiceOverlapReverificationTest` must still pass, unmodified, after the refactor.
- **No hand-typed UUIDs.** Every AD record id used in test fixtures below was looked up via a live, read-only DB query (see each task) — never invent one.
- **All commits follow `Feature ETP-4830: <description>` (first line ≤ 80 chars), no `Co-Authored-By`.**
- **Run tests via the ROOT project**, not the module-scoped Gradle task: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ClassName*"`. The module-scoped `:modules:com.etendoerp.go:compileTestJava` reports a false `NO-SOURCE` in this environment — see `santo_4830_roles.md`'s own "Gradle note" for the full explanation. Ignore `*TestSuite > initializationError` failures from a `--tests`-filtered run; they are pre-existing suite-aggregator noise, not a regression signal.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/com/etendoerp/go/roles/overlap/GrantCandidate.java` | Create — pure data: one remaining template's grant (id + level) for one item |
| `src/com/etendoerp/go/roles/overlap/OverlapWinner.java` | Create — pure data: the computed winner template id + level for one item |
| `src/com/etendoerp/go/roles/overlap/OverlapReconciliationCore.java` | Create — pure static `computeWinner(List<GrantCandidate>)`, no Hibernate |
| `src/com/etendoerp/go/roles/overlap/TemplateRemovalTracker.java` | Create — shared same-flush-visibility marker (replaces `WindowAccessOverlapCorruptionGuard`'s private `TEMPLATES_BEING_REMOVED` field) |
| `src/com/etendoerp/go/roles/overlap/ActiveTemplateInheritance.java` | Create — shared `findActiveTemplatesFor(Role, String)` query (Hibernate, `Role`/`RoleInheritance` only, no access-type table) |
| `src/com/etendoerp/go/roles/WindowAccessOverlapCorruptionGuard.java` | Modify — delegate to the 3 pieces above; no behavior change |
| `src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuard.java` | Create — REMOVE-path guard for `AD_Process_Access` (crash prevention via in-place repoint) |
| `src/com/etendoerp/go/roles/ObuiappProcessAccessDuplicateGuard.java` | Create — post-commit dedup sweep for `OBUIAPP_Process_Access` |
| `src-test/src/com/etendoerp/go/roles/overlap/OverlapReconciliationCoreTest.java` | Create — plain JUnit 5 unit tests, no DB |
| `src-test/src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuardIntegrationTest.java` | Create — `WeldBaseTest`, real-DB reproduction |
| `src-test/src/com/etendoerp/go/roles/ObuiappProcessAccessDuplicateGuardIntegrationTest.java` | Create — `WeldBaseTest`, real-DB reproduction |

---

### Task 1: Shared pure reconciliation core

**Files:**
- Create: `src/com/etendoerp/go/roles/overlap/GrantCandidate.java`
- Create: `src/com/etendoerp/go/roles/overlap/OverlapWinner.java`
- Create: `src/com/etendoerp/go/roles/overlap/OverlapReconciliationCore.java`
- Test: `src-test/src/com/etendoerp/go/roles/overlap/OverlapReconciliationCoreTest.java`

**Interfaces:**
- Produces: `GrantCandidate(String templateId, boolean fullAccess)`, `.getTemplateId()`, `.isFullAccess()`. `OverlapWinner(String winnerTemplateId, boolean winnerLevel)`, `.getWinnerTemplateId()`, `.isWinnerLevel()`. `OverlapReconciliationCore.computeWinner(List<GrantCandidate> candidatesOrderedBySeqNoDescending)` → `OverlapWinner` or `null`. Task 3, 4 and 5 all call this static method.

- [ ] **Step 1: Write the failing tests**

Create `src-test/src/com/etendoerp/go/roles/overlap/OverlapReconciliationCoreTest.java`:

```java
/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.roles.overlap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Plain, DB-free unit tests for {@link OverlapReconciliationCore#computeWinner(List)} — the
 * SeqNo-descending winner + most-permissive-wins level decision extracted from {@code
 * WindowAccessOverlapCorruptionGuard}'s own sixth-trigger fix (ETP-4906) so {@code
 * ProcessAccessOverlapCorruptionGuard} and {@code ObuiappProcessAccessDuplicateGuard} reuse the
 * exact same, already-proven algorithm instead of re-deriving it (ETP-4830 item 7).
 */
class OverlapReconciliationCoreTest {

  @Test
  void emptyListReturnsNull() {
    assertNull(OverlapReconciliationCore.computeWinner(Collections.emptyList()));
  }

  @Test
  void nullListReturnsNull() {
    assertNull(OverlapReconciliationCore.computeWinner(null));
  }

  @Test
  void singleCandidateWinsAndKeepsItsOwnLevel() {
    List<GrantCandidate> candidates = Collections.singletonList(
        new GrantCandidate("template-a", true));

    OverlapWinner winner = OverlapReconciliationCore.computeWinner(candidates);

    assertEquals("template-a", winner.getWinnerTemplateId());
    assertTrue(winner.isWinnerLevel());
  }

  @Test
  void firstCandidateInListOrderWinsRegardlessOfItsOwnLevel() {
    // Caller is responsible for SeqNo-descending order — the FIRST entry is always the winner,
    // even when a LATER entry is the one granting full access. This is the exact rule
    // WindowAccessOverlapCorruptionGuard's own "Why InheritedFrom must track core's own SeqNo
    // precedence" section proves is required — picking the most-permissive one instead
    // reproduces the OBSecurityException that section documents.
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("highest-seqno-readonly", false),
        new GrantCandidate("lower-seqno-full", true));

    OverlapWinner winner = OverlapReconciliationCore.computeWinner(candidates);

    assertEquals("highest-seqno-readonly", winner.getWinnerTemplateId());
  }

  @Test
  void levelIsFullWhenAnyCandidateGrantsFullAccessRegardlessOfWinner() {
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("highest-seqno-readonly", false),
        new GrantCandidate("lower-seqno-full", true));

    OverlapWinner winner = OverlapReconciliationCore.computeWinner(candidates);

    assertTrue(winner.isWinnerLevel(),
        "Most-permissive-wins is independent of which candidate is the SeqNo winner");
  }

  @Test
  void levelIsReadOnlyWhenNoCandidateGrantsFullAccess() {
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("template-a", false),
        new GrantCandidate("template-b", false));

    OverlapWinner winner = OverlapReconciliationCore.computeWinner(candidates);

    assertFalse(winner.isWinnerLevel());
  }

  @Test
  void threeCandidatesWinnerIsAlwaysTheFirstInOrder() {
    // Mirrors the real ETP-4906 6th-round reproduction shape: 3 remaining templates all granting
    // the same item, winner must be the first (highest-SeqNo) regardless of how many follow.
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("purchasing", false),
        new GrantCandidate("sales", true),
        new GrantCandidate("inventory", false));

    OverlapWinner winner = OverlapReconciliationCore.computeWinner(candidates);

    assertEquals("purchasing", winner.getWinnerTemplateId());
    assertTrue(winner.isWinnerLevel());
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail with a compile error**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*OverlapReconciliationCoreTest*"`
Expected: FAIL — `GrantCandidate`/`OverlapWinner`/`OverlapReconciliationCore` do not exist yet.

- [ ] **Step 3: Create `GrantCandidate.java`**

```java
/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.roles.overlap;

/**
 * One remaining template's active grant for a single window/process/report item, as seen by
 * {@link OverlapReconciliationCore#computeWinner(java.util.List)}. Pure data — no Hibernate, no
 * entity reference — so the winner/level decision itself is unit-testable without a DB. See
 * {@code com.etendoerp.go.roles.WindowAccessOverlapCorruptionGuard}'s class javadoc for the full
 * root-cause write-up this type's shape comes from (ETP-4830 item 7 extraction).
 */
public final class GrantCandidate {

  private final String templateId;
  private final boolean fullAccess;

  public GrantCandidate(String templateId, boolean fullAccess) {
    this.templateId = templateId;
    this.fullAccess = fullAccess;
  }

  public String getTemplateId() {
    return templateId;
  }

  public boolean isFullAccess() {
    return fullAccess;
  }
}
```

- [ ] **Step 4: Create `OverlapWinner.java`**

```java
/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.roles.overlap;

/**
 * The verdict {@link OverlapReconciliationCore#computeWinner(java.util.List)} returns for one
 * window/process/report item: which template becomes {@code InheritedFrom}, and which access
 * level the dependent's row should end up at.
 */
public final class OverlapWinner {

  private final String winnerTemplateId;
  private final boolean winnerLevel;

  public OverlapWinner(String winnerTemplateId, boolean winnerLevel) {
    this.winnerTemplateId = winnerTemplateId;
    this.winnerLevel = winnerLevel;
  }

  public String getWinnerTemplateId() {
    return winnerTemplateId;
  }

  public boolean isWinnerLevel() {
    return winnerLevel;
  }
}
```

- [ ] **Step 5: Create `OverlapReconciliationCore.java`**

```java
/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.roles.overlap;

import java.util.List;

/**
 * Pure, Hibernate-free reconciliation decision shared by {@code
 * com.etendoerp.go.roles.WindowAccessOverlapCorruptionGuard}, {@code ProcessAccessOverlap
 * CorruptionGuard}, and {@code ObuiappProcessAccessDuplicateGuard} (ETP-4830 item 7). See {@code
 * WindowAccessOverlapCorruptionGuard}'s own class javadoc, "A sixth trigger" and "Why
 * InheritedFrom must track core's own SeqNo precedence" sections, for the full live-reproduced
 * root-cause write-up this method's rule comes from — extracted here verbatim, not re-derived,
 * so a future change to the rule only has to happen once.
 */
public final class OverlapReconciliationCore {

  private OverlapReconciliationCore() {
    // static utility
  }

  /**
   * {@code candidatesOrderedBySeqNoDescending} must contain, for ONE window/process/report item,
   * every remaining template that currently, actively grants it — in the SAME order {@link
   * ActiveTemplateInheritance#findActiveTemplatesFor(org.openbravo.model.ad.access.Role, String)}
   * already returns templates (highest {@code AD_Role_Inheritance.SeqNo} first), filtered down to
   * just the ones granting THIS item, with that relative order preserved. Returns {@code null}
   * when the list is empty — no remaining template grants this item at all, so there is nothing
   * to repoint; the caller either leaves the dependent's row untouched or falls back to core's
   * own natural CREATE.
   *
   * <p><b>Winner (who becomes {@code InheritedFrom}) is ALWAYS the first candidate in the
   * list</b> — by construction the highest-{@code SeqNo} remaining grantor, regardless of its own
   * access level. Picking anything else is NOT safe: core's own {@code calculateAccesses} walks
   * every remaining template ascending by {@code SeqNo} in ONE call, and its {@code isPrecedent}
   * check only ever compares list index (== {@code SeqNo} order), never access level —
   * repointing to a lower-index template leaves the row exposed to being overridden by core's own
   * later pass over the true highest-{@code SeqNo} grantor, reopening the exact ownership
   * corruption this mechanism exists to prevent.
   *
   * <p><b>Level ({@code winnerLevel}) is a SEPARATE, most-permissive-wins decision</b>,
   * independent of which candidate is the winner: {@code true} the moment ANY candidate in the
   * list grants full access, regardless of that candidate's own {@code SeqNo}.
   */
  public static OverlapWinner computeWinner(
      List<GrantCandidate> candidatesOrderedBySeqNoDescending) {
    if (candidatesOrderedBySeqNoDescending == null || candidatesOrderedBySeqNoDescending.isEmpty()) {
      return null;
    }
    String winnerTemplateId = candidatesOrderedBySeqNoDescending.get(0).getTemplateId();
    boolean winnerLevel = false;
    for (GrantCandidate candidate : candidatesOrderedBySeqNoDescending) {
      if (candidate.isFullAccess()) {
        winnerLevel = true;
        break;
      }
    }
    return new OverlapWinner(winnerTemplateId, winnerLevel);
  }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*OverlapReconciliationCoreTest*"`
Expected: PASS, 6/6.

- [ ] **Step 7: Commit**

```bash
cd /Users/gremiger/workspaces/etendogoclean/etendo/modules/com.etendoerp.go
git add src/com/etendoerp/go/roles/overlap/GrantCandidate.java \
        src/com/etendoerp/go/roles/overlap/OverlapWinner.java \
        src/com/etendoerp/go/roles/overlap/OverlapReconciliationCore.java \
        src-test/src/com/etendoerp/go/roles/overlap/OverlapReconciliationCoreTest.java
git commit -m "Feature ETP-4830: Extract shared overlap-winner reconciliation core"
```

---

### Task 2: Shared same-flush marker and active-templates query

**Files:**
- Create: `src/com/etendoerp/go/roles/overlap/TemplateRemovalTracker.java`
- Create: `src/com/etendoerp/go/roles/overlap/ActiveTemplateInheritance.java`

**Interfaces:**
- Consumes: nothing new (plain Hibernate DAL calls).
- Produces: `TemplateRemovalTracker.markRemoved(String templateId)`, `TemplateRemovalTracker.isBeingRemoved(String templateId)`, `TemplateRemovalTracker.clear()`. `ActiveTemplateInheritance.findActiveTemplatesFor(Role dependent, String excludedInheritanceId)` → `List<Role>`, ordered `AD_Role_Inheritance.SequenceNumber` descending, excluding the given inheritance id and every template `TemplateRemovalTracker.isBeingRemoved` currently reports. Task 3 (refactor) and Task 4 (new guard) both call these.

No dedicated unit test for this task: both methods require a live Hibernate session (`OBCriteria`) exactly like the method they are extracted from (`WindowAccessOverlapCorruptionGuard#findActiveTemplatesFor`), which itself has never had isolated coverage — it is exercised indirectly by every integration test in `UserRoleCompositionServiceOverlapIntegrationTest`/`UserRoleCompositionServiceOverlapReverificationTest` today, and will be exercised the same way by Task 3's regression run and Task 4's new integration test. Task 3's regression-safety step is what actually proves this extraction is behavior-preserving.

- [ ] **Step 1: Create `TemplateRemovalTracker.java`**

```java
/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.roles.overlap;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Template role ids currently being removed via an in-flight {@code RoleInheritance} deletion
 * within THIS transaction (one set per thread — Openbravo/Tomcat threads process one request's
 * transaction at a time, never concurrently). Extracted from {@code
 * com.etendoerp.go.roles.WindowAccessOverlapCorruptionGuard}'s own private {@code
 * TEMPLATES_BEING_REMOVED} field (ETP-4906, Task B6, 5th round) so {@code
 * ProcessAccessOverlapCorruptionGuard} shares the SAME marker instead of tracking its own,
 * separate one — a template being removed is being removed for every access type at once, not
 * independently per guard.
 *
 * <p>See the original field's own javadoc (git history, {@code WindowAccessOverlapCorruptionGuard}
 * before ETP-4830 item 7) for the exact empirically-confirmed race this closes: a template's own
 * {@code RoleInheritance} row is still DB-visible as {@code active=true} mid-flush, even while
 * being deleted in the SAME flush, because Hibernate's default action-queue execution order runs
 * entity Deletions LAST (after Insertions/Updates).
 *
 * <p>Deliberately NOT cleared by the guard method that populates it — by the time a nested CREATE
 * this field exists to protect against actually fires, that method's own stack frame has already
 * returned. Instead cleared once per transaction via each guard's own {@code
 * onTransactionComplete(TransactionCompletedEvent)} calling {@link #clear()} — safe because a
 * marker surviving until transaction end can only make callers MORE conservative, never less
 * correct. Multiple guards calling {@link #clear()} for the same transaction is harmless —
 * {@code ThreadLocal#remove()} is idempotent.
 */
public final class TemplateRemovalTracker {

  private static final ThreadLocal<Set<String>> BEING_REMOVED =
      ThreadLocal.withInitial(LinkedHashSet::new);

  private TemplateRemovalTracker() {
    // static utility
  }

  public static void markRemoved(String templateId) {
    BEING_REMOVED.get().add(templateId);
  }

  public static boolean isBeingRemoved(String templateId) {
    return BEING_REMOVED.get().contains(templateId);
  }

  public static void clear() {
    BEING_REMOVED.remove();
  }
}
```

- [ ] **Step 2: Create `ActiveTemplateInheritance.java`**

```java
/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.roles.overlap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;

/**
 * Every ACTIVE template a role currently inherits from — extracted from {@code
 * com.etendoerp.go.roles.WindowAccessOverlapCorruptionGuard#findActiveTemplatesFor(Role, String)}
 * (ETP-4830 item 7) because that method itself has zero {@code WindowAccess}-specific logic in
 * it: it only ever touches {@code Role}/{@code RoleInheritance}, so it is shared verbatim by
 * {@code ProcessAccessOverlapCorruptionGuard} instead of being duplicated.
 *
 * <p>Ordered by {@code AD_Role_Inheritance.SeqNo} DESCENDING — mirrors core's own {@code
 * RoleInheritanceManager#getRoleInheritancesList(Role, Role, boolean)} call from {@code
 * propagateDeletedAccess} (also descending), the tie-break authority {@link
 * OverlapReconciliationCore#computeWinner(java.util.List)} deliberately reuses. Excludes by id,
 * not by DB-visible state, mirroring core's own {@code
 * RoleInheritanceManager#getUpdatedRoleInheritancesList(RoleInheritance, boolean)} (the excluded
 * row may still be physically present at this point in the flush). ALSO excludes every template
 * {@link TemplateRemovalTracker#isBeingRemoved(String)} currently reports — see that class's own
 * javadoc for the exact same-flush-visibility race this closes.
 */
public final class ActiveTemplateInheritance {

  private ActiveTemplateInheritance() {
    // static utility
  }

  @SuppressWarnings("unchecked")
  public static List<Role> findActiveTemplatesFor(Role dependent, String excludedInheritanceId) {
    OBCriteria<RoleInheritance> criteria = crossClientCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ROLE, dependent));
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ACTIVE, true));
    if (excludedInheritanceId != null) {
      criteria.add(Restrictions.ne(RoleInheritance.PROPERTY_ID, excludedInheritanceId));
    }
    criteria.addOrderBy(RoleInheritance.PROPERTY_SEQUENCENUMBER, false);
    List<Role> templates = new ArrayList<>();
    Set<String> seenTemplateIds = new LinkedHashSet<>();
    for (RoleInheritance ri : (List<RoleInheritance>) criteria.list()) {
      Role template = ri.getInheritFrom();
      if (template != null && Boolean.TRUE.equals(template.isTemplate())
          && !TemplateRemovalTracker.isBeingRemoved(template.getId())
          && seenTemplateIds.add(template.getId())) {
        templates.add(template);
      }
    }
    return templates;
  }

  /**
   * Disables {@code OBCriteria}'s implicit client/organization filtering — REQUIRED here, same
   * reasoning as {@code WindowAccessOverlapCorruptionGuard}'s own private {@code
   * crossClientCriteria}: a template role is typically system client {@code "0"} while its
   * dependents are real tenant clients, so without this the query would silently return zero rows
   * whenever the ambient {@code OBContext}'s role does not have both clients in its own
   * readable-clients list.
   */
  private static <T extends BaseOBObject> OBCriteria<T> crossClientCriteria(Class<T> clazz) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(clazz);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria;
  }
}
```

- [ ] **Step 3: Compile-check (no behavior wired up yet, nothing to run)**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew :compileJava` (root project compile, since the module-scoped task is unreliable here per Global Constraints).
Expected: BUILD SUCCESSFUL, no compile errors.

- [ ] **Step 4: Commit**

```bash
cd /Users/gremiger/workspaces/etendogoclean/etendo/modules/com.etendoerp.go
git add src/com/etendoerp/go/roles/overlap/TemplateRemovalTracker.java \
        src/com/etendoerp/go/roles/overlap/ActiveTemplateInheritance.java
git commit -m "Feature ETP-4830: Extract shared template-removal marker and query"
```

---

### Task 3: Refactor `WindowAccessOverlapCorruptionGuard` to delegate (regression-safety)

**Files:**
- Modify: `src/com/etendoerp/go/roles/WindowAccessOverlapCorruptionGuard.java`

**Interfaces:**
- Consumes: `TemplateRemovalTracker` (Task 2), `ActiveTemplateInheritance` (Task 2), `OverlapReconciliationCore`/`GrantCandidate`/`OverlapWinner` (Task 1).
- Produces: no change to this class's own public/package API — `onSave`, `onUpdate`, `onDelete`, `onTransactionComplete` keep their exact signatures and behavior.

This is a pure, behavior-preserving refactor. Do it in 3 independent sub-steps, each verified by the FULL existing suite before moving to the next — if any sub-step breaks a test, stop and fix before continuing; do not proceed with a red suite.

- [ ] **Step 1: Delegate `TEMPLATES_BEING_REMOVED` to `TemplateRemovalTracker`**

In `WindowAccessOverlapCorruptionGuard.java`:
1. Delete the private field:
   ```java
   private static final ThreadLocal<Set<String>> TEMPLATES_BEING_REMOVED =
       ThreadLocal.withInitial(LinkedHashSet::new);

   private static Set<String> templatesBeingRemoved() {
     return TEMPLATES_BEING_REMOVED.get();
   }
   ```
2. Add the import: `import com.etendoerp.go.roles.overlap.TemplateRemovalTracker;`
3. In `guardRemovedInheritance(RoleInheritance)`, replace:
   ```java
   templatesBeingRemoved().add(removedTemplate.getId());
   ```
   with:
   ```java
   TemplateRemovalTracker.markRemoved(removedTemplate.getId());
   ```
4. In `onTransactionComplete(TransactionCompletedEvent)`, replace:
   ```java
   TEMPLATES_BEING_REMOVED.remove();
   ```
   with:
   ```java
   TemplateRemovalTracker.clear();
   ```
5. In `findActiveTemplatesFor(Role, String)`, replace the line:
   ```java
   Set<String> beingRemoved = templatesBeingRemoved();
   ```
   and the loop condition:
   ```java
   && !beingRemoved.contains(template.getId())
   ```
   with:
   ```java
   && !TemplateRemovalTracker.isBeingRemoved(template.getId())
   ```
   (remove the now-unused `beingRemoved` local variable and, if now unused, the `java.util.Set`/`LinkedHashSet` imports — check first, `Set`/`LinkedHashSet` are still used elsewhere in this file for `seenTemplateIds`/`WindowGrantors`, so only remove an import if a compile warning confirms it is genuinely unused).

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*UserRoleCompositionServiceOverlapIntegrationTest*" --tests "*UserRoleCompositionServiceOverlapReverificationTest*"`
Expected: PASS, every test — same count as before this change (no test added or removed yet).

- [ ] **Step 2: Delegate `findActiveTemplatesFor`'s query body to `ActiveTemplateInheritance`**

Replace the body of `findActiveTemplatesFor(Role dependent, String excludedInheritanceId)` with:
```java
private List<Role> findActiveTemplatesFor(Role dependent, String excludedInheritanceId) {
  return ActiveTemplateInheritance.findActiveTemplatesFor(dependent, excludedInheritanceId);
}
```
Add the import: `import com.etendoerp.go.roles.overlap.ActiveTemplateInheritance;`. Leave the method itself in place (still called by `findOtherActiveTemplates` and `findActiveTemplateGrantingFullAccess`) — only its body changes, so every existing caller is untouched. The `@SuppressWarnings("unchecked")` annotation on the old body can now be removed (the cast lives inside `ActiveTemplateInheritance` now); remove it only if the compiler no longer warns without it.

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*UserRoleCompositionServiceOverlapIntegrationTest*" --tests "*UserRoleCompositionServiceOverlapReverificationTest*"`
Expected: PASS, same count as Step 1.

- [ ] **Step 3: Delegate the winner/level decision in `collectWindowGrantors`/`repointWindowIfNeeded` to `OverlapReconciliationCore`**

Replace the `WindowGrantors` inner class and `collectWindowGrantors`/`repointWindowIfNeeded` with a version that builds `List<GrantCandidate>` per window (preserving the exact same SeqNo-descending iteration order `remainingTemplates` already provides) and calls `OverlapReconciliationCore.computeWinner`:

```java
private static final class WindowGrantors {
  private final Map<String, Window> windowsById = new LinkedHashMap<>();
  private final Map<String, Role> templatesById = new LinkedHashMap<>();
  private final Map<String, List<GrantCandidate>> candidatesByWindowId = new LinkedHashMap<>();
}

private WindowGrantors collectWindowGrantors(List<Role> remainingTemplates) {
  WindowGrantors grantors = new WindowGrantors();
  for (Role remainingTemplate : remainingTemplates) {
    grantors.templatesById.putIfAbsent(remainingTemplate.getId(), remainingTemplate);
    for (WindowAccess templateGrant : findActiveWindowAccess(remainingTemplate)) {
      Window window = templateGrant.getWindow();
      if (window == null) {
        continue;
      }
      grantors.windowsById.putIfAbsent(window.getId(), window);
      grantors.candidatesByWindowId
          .computeIfAbsent(window.getId(), key -> new ArrayList<>())
          .add(new GrantCandidate(remainingTemplate.getId(),
              Boolean.TRUE.equals(templateGrant.isEditableField())));
    }
  }
  return grantors;
}

private boolean repointWindowIfNeeded(Role dependent, Window window, WindowGrantors grantors) {
  String windowId = window.getId();
  OverlapWinner winner = OverlapReconciliationCore.computeWinner(
      grantors.candidatesByWindowId.get(windowId));
  if (winner == null) {
    return false;
  }
  Role winnerRole = grantors.templatesById.get(winner.getWinnerTemplateId());

  WindowAccess existing = findActiveWindowAccess(dependent, window);
  if (existing == null) {
    // No existing row to correct in place — same residual, pre-existing, theoretical gap as
    // before this refactor. See the class javadoc's "A sixth trigger" section.
    return false;
  }
  Role existingSource = existing.getInheritedFrom();
  boolean sourceCorrect = existingSource != null && sameId(existingSource, winnerRole);
  boolean levelCorrect = Boolean.valueOf(winner.isWinnerLevel()).equals(existing.isEditableField());
  if (sourceCorrect && levelCorrect) {
    return false;
  }
  repointInPlace(existing, dependent, window, winnerRole, winner.isWinnerLevel(), existingSource);
  return true;
}
```

Add imports: `import com.etendoerp.go.roles.overlap.GrantCandidate;`, `import com.etendoerp.go.roles.overlap.OverlapReconciliationCore;`, `import com.etendoerp.go.roles.overlap.OverlapWinner;`. `guardRemovedInheritance`'s own body (which calls `collectWindowGrantors`/`repointWindowIfNeeded` in a loop over `grantors.windowsById.values()`) does not need to change — both methods keep their same names and signatures.

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*UserRoleCompositionServiceOverlapIntegrationTest*" --tests "*UserRoleCompositionServiceOverlapReverificationTest*"`
Expected: PASS, same count as Step 1 — in particular `testRemovingOneOfFourTemplatesLeavesTwoRemainingOverlappingTemplatesUnbroken` (the exact 3+-overlapping-template reproduction) must still pass with the delegated logic.

- [ ] **Step 4: Run the FULL module test suite as a final regression check**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "com.etendoerp.go.roles.*"`
Expected: BUILD SUCCESSFUL. Any `*TestSuite > initializationError` is the known `--tests`-filter noise (Global Constraints) — confirm no OTHER failure by name.

- [ ] **Step 5: Commit**

```bash
cd /Users/gremiger/workspaces/etendogoclean/etendo/modules/com.etendoerp.go
git add src/com/etendoerp/go/roles/WindowAccessOverlapCorruptionGuard.java
git commit -m "Feature ETP-4830: Delegate window guard to shared overlap core"
```

---

### Task 4: `ProcessAccessOverlapCorruptionGuard` (crash prevention for `AD_Process_Access`)

**Files:**
- Create: `src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuard.java`
- Test: `src-test/src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuardIntegrationTest.java`

**Interfaces:**
- Consumes: `ActiveTemplateInheritance.findActiveTemplatesFor` (Task 2), `TemplateRemovalTracker` (Task 2), `OverlapReconciliationCore.computeWinner`/`GrantCandidate`/`OverlapWinner` (Task 1).
- Produces: a plain CDI bean (no `@Named`, matches `WindowAccessOverlapCorruptionGuard`'s own un-annotated, implicitly-`@Dependent` shape — `beans.xml` here is `bean-discovery-mode="all"`, so no registration step is needed).

**Verified real fixture data** (live, read-only query against this environment's DB, `2026-08-24`): `AD_Process` id `017312F51139438A9665775E3B5392A1` ("Doubtful Debt Run Process") is active and currently has zero `AD_Process_Access` rows for any of the 4 real system templates (Finance `B88A34B5D1874F8685FA6F3C3A609412`, Sales `15ECC46CFBD74CF3A76D1F4DC8BA9F80`, Purchasing `5E279F5102F9410F9B8CCBA424741F46`, Inventory `73581A7B4F414A2C9059C83CE7BE97BF`) — safe to grant directly in a test without colliding with real grants. Re-verify with the same query before running against a different environment:

```sql
SELECT p.ad_process_id, p.name FROM ad_process p WHERE p.isactive='Y'
  AND NOT EXISTS (SELECT 1 FROM ad_process_access pa WHERE pa.ad_process_id = p.ad_process_id
    AND pa.ad_role_id IN ('B88A34B5D1874F8685FA6F3C3A609412','15ECC46CFBD74CF3A76D1F4DC8BA9F80',
      '5E279F5102F9410F9B8CCBA424741F46','73581A7B4F414A2C9059C83CE7BE97BF') AND pa.isactive='Y')
ORDER BY p.ad_process_id LIMIT 3;
```

- [ ] **Step 1: Write the failing integration test**

Create `src-test/src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuardIntegrationTest.java`:

```java
/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.roles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.Organization;

/**
 * ETP-4830 item 7 — deterministic, self-contained proof that {@link
 * ProcessAccessOverlapCorruptionGuard} extends {@code WindowAccessOverlapCorruptionGuard}'s own
 * proven sixth-trigger fix (ETP-4906) to {@code AD_Process_Access}, which carries the identical
 * {@code AD_PROCESS_ACCESS_UN_KEY} unique constraint shape as {@code AD_Window_Access}.
 *
 * <p>Mirrors {@code UserRoleCompositionServiceOverlapIntegrationTest
 * #testRemovingOneOfFourTemplatesLeavesTwoRemainingOverlappingTemplatesUnbroken} exactly, on
 * {@code AD_Process_Access} instead of {@code AD_Window_Access} — a "bystander" role composed
 * from all 4 real system templates via raw {@code AD_Role_Inheritance} rows, never through {@code
 * UserRoleCompositionService}, then losing one of them while 2 remaining templates both still
 * grant the same process.
 */
public class ProcessAccessOverlapCorruptionGuardIntegrationTest extends WeldBaseTest {

  /** Verified (live DB check, 2026-08-24) to have zero AD_Process_Access rows for any of the 4
   *  real system templates. */
  private static final String UNUSED_PROCESS_ID = "017312F51139438A9665775E3B5392A1";

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void testRemovingOneOfFourTemplatesLeavesTwoRemainingOverlappingTemplatesUnbroken()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, UNUSED_PROCESS_ID);
      assertNotNull("Test fixture must contain AD_Process " + UNUSED_PROCESS_ID, sharedProcess);

      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      Role purchasingTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.PURCHASING_ROLE_ID);
      Role inventoryTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.INVENTORY_ROLE_ID);
      assertNotNull("The real Finance system template must already exist", financeTemplate);
      assertNotNull("The real Sales system template must already exist", salesTemplate);
      assertNotNull("The real Purchasing system template must already exist", purchasingTemplate);
      assertNotNull("The real Inventory system template must already exist", inventoryTemplate);

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      // Finance grants the shared process FULL. Sales AND Purchasing BOTH ALSO grant it,
      // READ-ONLY — the "2+ REMAINING templates overlap on the same item" shape that can only
      // exist with 3+ templates composed. Inventory does not grant this process at all.
      grantProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();
      grantProcessAccess(purchasingTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);
      addInheritance(bystanderRole, purchasingTemplate, 30L);
      addInheritance(bystanderRole, inventoryTemplate, 40L);

      ProcessAccess beforeRemoval = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("Sanity: composing all 4 templates must have propagated the shared process",
          beforeRemoval);
      assertEquals("Sanity: Finance is the only full grantor among all 4, so it must be the "
          + "source before removal",
          financeTemplate.getId(),
          beforeRemoval.getInheritedFrom() != null ? beforeRemoval.getInheritedFrom().getId()
              : null);
      assertTrue("Sanity: most-permissive-wins must resolve to full before removal",
          Boolean.TRUE.equals(beforeRemoval.isEditableField()));

      // THE TRIGGER: remove Finance's inheritance. Sales AND Purchasing BOTH still grant the
      // shared process afterward. Before this guard exists, this would risk the identical
      // duplicate-key ConstraintViolationException WindowAccessOverlapCorruptionGuard's own
      // sixth trigger fixed for AD_Window_Access; must now succeed.
      RoleInheritance financeInheritance = findInheritance(bystanderRole, financeTemplate);
      assertNotNull(financeInheritance);
      OBDal.getInstance().remove(financeInheritance);
      OBContext.setAdminMode();
      try {
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }

      ProcessAccess afterRemoval = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("The shared process's access must survive the removal, re-derived from the "
          + "2 remaining overlapping templates, not silently dropped or duplicated", afterRemoval);
      assertEquals("client must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getClient().getId(), afterRemoval.getClient().getId());
      assertEquals("organization must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getOrganization().getId(), afterRemoval.getOrganization().getId());
      assertEquals("Purchasing (the highest-SeqNo template among the 2 remaining templates that "
          + "grant this process) must become the new source",
          purchasingTemplate.getId(),
          afterRemoval.getInheritedFrom() != null ? afterRemoval.getInheritedFrom().getId()
              : null);
      assertFalse("Neither remaining grantor (Sales, Purchasing) is full, so access must "
          + "downgrade to read-only, not stay stuck at Finance's old full value",
          Boolean.TRUE.equals(afterRemoval.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private Role createBystanderRole(User user) {
    Organization starOrg = OBDal.getInstance().get(Organization.class, "0");
    Role role = OBProvider.getInstance().get(Role.class);
    role.setNewOBObject(true);
    role.setClient(user.getClient());
    role.setOrganization(starOrg);
    role.setActive(true);
    role.setName("ETP-4830 item 7 process-guard bystander " + System.nanoTime());
    role.setUserLevel(SystemRoleTemplates.FIXED_ROLE_USER_LEVEL);
    role.setManual(true);
    role.setTemplate(false);
    role.setClientAdmin(false);
    OBDal.getInstance().save(role);
    OBDal.getInstance().flush();
    return role;
  }

  private void addInheritance(Role role, Role template, long seqno) {
    RoleInheritance inheritance = OBProvider.getInstance().get(RoleInheritance.class);
    inheritance.setNewOBObject(true);
    inheritance.setClient(role.getClient());
    inheritance.setOrganization(role.getOrganization());
    inheritance.setActive(true);
    inheritance.setRole(role);
    inheritance.setInheritFrom(template);
    inheritance.setSequenceNumber(seqno);
    OBDal.getInstance().save(inheritance);
    OBContext.setAdminMode();
    try {
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void grantProcessAccess(Role role, Process process, boolean readOnly) {
    OBContext.setAdminMode();
    try {
      ProcessAccess access = OBProvider.getInstance().get(ProcessAccess.class);
      access.setNewOBObject(true);
      access.setClient(role.getClient());
      access.setOrganization(role.getOrganization());
      access.setActive(true);
      access.setRole(role);
      access.setProcess(process);
      access.setEditableField(!readOnly);
      OBDal.getInstance().save(access);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @SuppressWarnings("unchecked")
  private RoleInheritance findInheritance(Role role, Role template) {
    OBCriteria<RoleInheritance> criteria = OBDal.getInstance()
        .createCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_INHERITFROM, template));
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (RoleInheritance) criteria.uniqueResult();
  }

  @SuppressWarnings("unchecked")
  private ProcessAccess findProcessAccess(Role role, Process process) {
    OBCriteria<ProcessAccess> criteria = OBDal.getInstance().createCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_PROCESS, process));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (ProcessAccess) criteria.uniqueResult();
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ProcessAccessOverlapCorruptionGuardIntegrationTest*"`
Expected: FAIL — either a compile error (`ProcessAccessOverlapCorruptionGuard` referenced nowhere yet is fine, this test does not reference the guard class directly) or, once it compiles, a `ConstraintViolationException`/`OBSecurityException` on the removal step, since nothing guards `AD_Process_Access` yet.

- [ ] **Step 3: Create `ProcessAccessOverlapCorruptionGuard.java`**

```java
/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.roles;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Priority;
import javax.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.TransactionCompletedEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.roles.overlap.ActiveTemplateInheritance;
import com.etendoerp.go.roles.overlap.GrantCandidate;
import com.etendoerp.go.roles.overlap.OverlapReconciliationCore;
import com.etendoerp.go.roles.overlap.OverlapWinner;
import com.etendoerp.go.roles.overlap.TemplateRemovalTracker;

/**
 * ETP-4830 item 7 — extends {@link WindowAccessOverlapCorruptionGuard}'s proven REMOVE-path
 * ("sixth trigger") fix from {@code AD_Window_Access} to {@code AD_Process_Access}, which carries
 * the identical {@code AD_PROCESS_ACCESS_UN_KEY} unique constraint on {@code (AD_Role_ID,
 * AD_Process_ID)} — confirmed via {@code src-db/database/model/tables/AD_PROCESS_ACCESS.xml} — so
 * the same duplicate-INSERT race {@code WindowAccessOverlapCorruptionGuard}'s own class javadoc
 * documents ("A sixth trigger") is structurally reachable here too: core's {@code
 * RoleInheritanceManager#applyRemoveInheritance}/{@code calculateAccesses} is generic across every
 * {@code AccessTypeInjector} (window/process/OBUIAPP-process), walking every REMAINING template in
 * one un-flushed pass regardless of which access type it is reconciling.
 *
 * <p><b>Scope: REMOVE path only.</b> Deliberately does NOT observe {@code AD_Process_Access}
 * {@code EntityNewEvent}/{@code EntityUpdateEvent} (the ADD/UPDATE-path ownership-correction and
 * most-permissive-wins-widening triggers {@code WindowAccessOverlapCorruptionGuard} also has) —
 * per the approved ETP-4830 item 7 design, those 6 other triggers are deferred, not yet proven
 * necessary for process access. Only the REMOVE-side duplicate-INSERT race is closed here; watch
 * for the SAME failure signatures ({@code OBSecurityException}, {@code
 * ConstraintViolationException} on {@code AD_PROCESS_ACCESS_UN_KEY}, or a silently wrong access
 * level) on the ADD/UPDATE paths, and extend this guard the same way {@code
 * WindowAccessOverlapCorruptionGuard} grew, if/when one is actually hit.
 *
 * <p>Reuses, rather than re-derives, the exact winner/level algorithm ({@link
 * OverlapReconciliationCore#computeWinner(java.util.List)}) and the "which templates does this
 * role actively inherit from" query ({@link ActiveTemplateInheritance}) already proven for window
 * access — see those classes' own javadoc for the full root-cause write-up.
 */
public class ProcessAccessOverlapCorruptionGuard extends EntityPersistenceEventObserver {

  private static final Logger log =
      LogManager.getLogger(ProcessAccessOverlapCorruptionGuard.class);

  /** Same rationale as {@code WindowAccessOverlapCorruptionGuard}'s own constant of this name —
   *  any priority value runs before core's own unprioritized {@code
   *  RoleInheritanceEventHandler#onDelete}. */
  private static final int RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS = 1;

  private static Entity[] entities;

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[] {
          ModelProvider.getInstance().getEntity(RoleInheritance.ENTITY_NAME) };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

  public void onDelete(@Observes @Priority(RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS)
      EntityDeleteEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Object target = event.getTargetInstance();
    if (target instanceof RoleInheritance) {
      guardRemovedInheritance((RoleInheritance) target);
    }
  }

  public void onTransactionComplete(@Observes TransactionCompletedEvent event) {
    TemplateRemovalTracker.clear();
  }

  private void guardRemovedInheritance(RoleInheritance inheritance) {
    Role dependent = inheritance.getRole();
    Role removedTemplate = inheritance.getInheritFrom();
    if (dependent == null || removedTemplate == null) {
      return;
    }
    TemplateRemovalTracker.markRemoved(removedTemplate.getId());

    List<Role> remainingTemplates =
        ActiveTemplateInheritance.findActiveTemplatesFor(dependent, inheritance.getId());

    Map<String, Process> processesById = new LinkedHashMap<>();
    Map<String, Role> templatesById = new LinkedHashMap<>();
    Map<String, List<GrantCandidate>> candidatesByProcessId = new LinkedHashMap<>();
    for (Role remainingTemplate : remainingTemplates) {
      templatesById.putIfAbsent(remainingTemplate.getId(), remainingTemplate);
      for (ProcessAccess grant : findActiveProcessAccess(remainingTemplate)) {
        Process process = grant.getProcess();
        if (process == null) {
          continue;
        }
        processesById.putIfAbsent(process.getId(), process);
        candidatesByProcessId
            .computeIfAbsent(process.getId(), key -> new ArrayList<>())
            .add(new GrantCandidate(remainingTemplate.getId(),
                Boolean.TRUE.equals(grant.isEditableField())));
      }
    }

    boolean anyCorrected = false;
    for (Process process : processesById.values()) {
      OverlapWinner winner =
          OverlapReconciliationCore.computeWinner(candidatesByProcessId.get(process.getId()));
      if (winner == null) {
        continue;
      }
      Role winnerRole = templatesById.get(winner.getWinnerTemplateId());
      anyCorrected |= repointProcessIfNeeded(dependent, process, winner, winnerRole);
    }

    if (anyCorrected) {
      // Mirrors WindowAccessOverlapCorruptionGuard's own OBDal.refresh(dependent) call — same
      // stale-collection-snapshot reasoning, see that class's own javadoc.
      OBDal.getInstance().refresh(dependent);
    }
  }

  private boolean repointProcessIfNeeded(Role dependent, Process process, OverlapWinner winner,
      Role winnerRole) {
    ProcessAccess existing = findActiveProcessAccess(dependent, process);
    if (existing == null) {
      // No existing row to correct in place — same residual, acceptable risk as
      // WindowAccessOverlapCorruptionGuard's own equivalent case (see that class's javadoc, "A
      // sixth trigger" section): a role composed for any length of time already has a row for
      // every process any of its active templates grants.
      return false;
    }
    Role existingSource = existing.getInheritedFrom();
    boolean sourceCorrect = existingSource != null && sameId(existingSource, winnerRole);
    boolean levelCorrect = Boolean.valueOf(winner.isWinnerLevel()).equals(existing.isEditableField());
    if (sourceCorrect && levelCorrect) {
      return false;
    }
    repointInPlace(existing, process, winnerRole, winner.isWinnerLevel(), existingSource);
    return true;
  }

  /**
   * Bulk HQL UPDATE, same technique and same reasoning as {@code
   * WindowAccessOverlapCorruptionGuard#repointInPlace} — this runs nested inside an
   * already-in-progress flush under {@code FlushMode.COMMIT}, so an entity-level setter call
   * would only mutate the in-memory Java object, never Hibernate's own dirty-check bookkeeping in
   * a way guaranteed to survive being invoked from an unrelated entity's delete-event callback.
   */
  private void repointInPlace(ProcessAccess existing, Process process, Role winner,
      boolean winnerLevel, Role previousSource) {
    org.openbravo.model.ad.access.User currentUser = OBContext.getOBContext() != null
        ? OBContext.getOBContext().getUser()
        : null;
    OBContext.setAdminMode(false);
    try {
      StringBuilder hql = new StringBuilder("update ").append(ProcessAccess.ENTITY_NAME)
          .append(" set inheritedFrom = :winner, editableField = :level");
      if (currentUser != null) {
        hql.append(", updated = :updated, updatedBy = :updatedBy");
      }
      hql.append(" where id = :id");
      org.hibernate.query.Query<?> query = OBDal.getInstance().getSession()
          .createQuery(hql.toString());
      query.setParameter("winner", winner);
      query.setParameter("level", winnerLevel);
      if (currentUser != null) {
        query.setParameter("updated", new Date());
        query.setParameter("updatedBy", currentUser);
      }
      query.setParameter("id", existing.getId());
      query.executeUpdate();
    } finally {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().refresh(existing);
    log.info(
        "Prevented cross-template AD_Process_Access overlap corruption (multi-remaining-template "
            + "removal case): repointed role {} process {} in place from {} to {} "
            + "(editableField={}) without deleting the row",
        existing.getRole() != null ? existing.getRole().getId() : null, process.getId(),
        previousSource != null ? previousSource.getId() : "manually granted", winner.getId(),
        winnerLevel);
  }

  private static <T extends BaseOBObject> OBCriteria<T> crossClientCriteria(Class<T> clazz) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(clazz);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria;
  }

  private ProcessAccess findActiveProcessAccess(Role role, Process process) {
    OBCriteria<ProcessAccess> criteria = crossClientCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_PROCESS, process));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (ProcessAccess) criteria.uniqueResult();
  }

  @SuppressWarnings("unchecked")
  private List<ProcessAccess> findActiveProcessAccess(Role role) {
    OBCriteria<ProcessAccess> criteria = crossClientCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    return criteria.list();
  }

  private static boolean sameId(BaseOBObject a, BaseOBObject b) {
    if (a == null || b == null) {
      return false;
    }
    String idA = (String) a.getId();
    String idB = (String) b.getId();
    return idA != null && idA.equals(idB);
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ProcessAccessOverlapCorruptionGuardIntegrationTest*"`
Expected: PASS.

- [ ] **Step 5: Run the Task 3 regression suites again (defense in depth — confirm the new guard's shared-code use didn't disturb the window guard)**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*UserRoleCompositionServiceOverlapIntegrationTest*" --tests "*UserRoleCompositionServiceOverlapReverificationTest*"`
Expected: PASS, same count as Task 3.

- [ ] **Step 6: Commit**

```bash
cd /Users/gremiger/workspaces/etendogoclean/etendo/modules/com.etendoerp.go
git add src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuard.java \
        src-test/src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuardIntegrationTest.java
git commit -m "Feature ETP-4830: Guard AD_Process_Access against overlap corruption"
```

---

### Task 5: `ObuiappProcessAccessDuplicateGuard` (post-commit dedup sweep for `OBUIAPP_Process_Access`)

**Files:**
- Create: `src/com/etendoerp/go/roles/ObuiappProcessAccessDuplicateGuard.java`
- Test: `src-test/src/com/etendoerp/go/roles/ObuiappProcessAccessDuplicateGuardIntegrationTest.java`

**Interfaces:**
- Consumes: nothing from Tasks 1–2 — `OBUIAPP_Process_Access` has no unique constraint (confirmed via `modules_core/org.openbravo.client.application/src-db/database/model/tables/OBUIAPP_PROCESS_ACCESS.xml`), so there is nothing to prevent and no winner/level decision to make; this guard only ever deletes extras, never repoints one in place.
- Produces: a plain CDI bean, same shape as Task 4.

**Design note — why this guard runs on `TransactionCompletedEvent`, not `onDelete`.** `@Priority` can only make an observer run BEFORE every unprioritized observer for the SAME event dispatch (confirmed in `WindowAccessOverlapCorruptionGuard`'s own class javadoc: "observers carrying a priority are notified... before any unprioritized observer" — there is no way to request "after"). A genuine "clean up whatever core's own reconciliation left duplicated" sweep therefore cannot run inside the same `EntityDeleteEvent` dispatch that triggers core's reconciliation — it needs a LATER hook, after the triggering flush has committed. `TransactionCompletedEvent` (fired from `OBInterceptor#afterTransactionCompletion`) is the only such hook Openbravo forwards to CDI observers. Precedent for doing real work there: `org.openbravo.client.application.event.DataPoolSelectionEventHandler#onTransactionCompleted`, which gates on `event.getTransaction().getStatus() == TransactionStatus.ROLLED_BACK` before acting — this guard uses the identical gate.

- [ ] **Step 1: Verify a real, currently-unused OBUIAPP process id**

Run (already executed once for this plan, 2026-08-24 — re-run before implementing against a different environment):
```sql
SELECT op.obuiapp_process_id, op.name FROM obuiapp_process op WHERE op.isactive='Y'
  AND NOT EXISTS (SELECT 1 FROM obuiapp_process_access pa
    WHERE pa.obuiapp_process_id = op.obuiapp_process_id
      AND pa.ad_role_id IN ('B88A34B5D1874F8685FA6F3C3A609412','15ECC46CFBD74CF3A76D1F4DC8BA9F80',
        '5E279F5102F9410F9B8CCBA424741F46','73581A7B4F414A2C9059C83CE7BE97BF') AND pa.isactive='Y')
ORDER BY op.obuiapp_process_id LIMIT 3;
```
Confirmed result for this environment: `0662F6BC8D604AAEA5A2DD49E87F4B65` ("SII Invoices Query").

- [ ] **Step 2: Write the failing integration test**

Create `src-test/src/com/etendoerp/go/roles/ObuiappProcessAccessDuplicateGuardIntegrationTest.java`:

```java
/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.roles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.client.application.ProcessAccess;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.Organization;

/**
 * ETP-4830 item 7 — deterministic, self-contained proof that {@link
 * ObuiappProcessAccessDuplicateGuard} cleans up the duplicate {@code OBUIAPP_Process_Access} rows
 * core's own generic {@code RoleInheritanceManager} reconciliation can leave behind when 2+
 * remaining templates both grant the same OBUIAPP process after a third template inheritance is
 * removed — the identical mechanism as {@code AD_Process_Access}'s crash-capable race ({@link
 * ProcessAccessOverlapCorruptionGuardIntegrationTest}), except {@code OBUIAPP_Process_Access} has
 * no unique constraint (confirmed via {@code modules_core/org.openbravo.client.application/
 * src-db/database/model/tables/OBUIAPP_PROCESS_ACCESS.xml}), so the failure mode is silent
 * duplicate rows, not a crash — nothing to PREVENT, only to CLEAN UP.
 */
public class ObuiappProcessAccessDuplicateGuardIntegrationTest extends WeldBaseTest {

  /** Verified (live DB check, 2026-08-24) to have zero OBUIAPP_Process_Access rows for any of
   *  the 4 real system templates. */
  private static final String UNUSED_OBUIAPP_PROCESS_ID = "0662F6BC8D604AAEA5A2DD49E87F4B65";

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  @Test
  public void testRemovingOneOfFourTemplatesLeavesNoDuplicateObuiappProcessAccessRows()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, UNUSED_OBUIAPP_PROCESS_ID);
      assertNotNull("Test fixture must contain OBUIAPP_Process " + UNUSED_OBUIAPP_PROCESS_ID,
          sharedProcess);

      Role financeTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.FINANCE_ROLE_ID);
      Role salesTemplate = OBDal.getInstance().get(Role.class, SystemRoleTemplates.SALES_ROLE_ID);
      Role purchasingTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.PURCHASING_ROLE_ID);
      Role inventoryTemplate = OBDal.getInstance().get(Role.class,
          SystemRoleTemplates.INVENTORY_ROLE_ID);
      assertNotNull(financeTemplate);
      assertNotNull(salesTemplate);
      assertNotNull(purchasingTemplate);
      assertNotNull(inventoryTemplate);

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      // Finance, Sales AND Purchasing all grant the shared OBUIAPP process; Inventory does not.
      grantObuiappProcessAccess(financeTemplate, sharedProcess);
      OBDal.getInstance().flush();
      grantObuiappProcessAccess(salesTemplate, sharedProcess);
      OBDal.getInstance().flush();
      grantObuiappProcessAccess(purchasingTemplate, sharedProcess);
      OBDal.getInstance().flush();

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);
      addInheritance(bystanderRole, purchasingTemplate, 30L);
      addInheritance(bystanderRole, inventoryTemplate, 40L);

      List<ProcessAccess> beforeRemoval = findActiveObuiappProcessAccess(bystanderRole,
          sharedProcess);
      assertEquals("Sanity: composing all 4 templates must have propagated exactly ONE row for "
          + "the shared process before any removal", 1, beforeRemoval.size());

      // THE TRIGGER: remove Finance's inheritance while Sales AND Purchasing both still grant
      // this process — the precondition that can leave core's own reconciliation with 2
      // independently-scheduled INSERTs for the same (role, process) pair.
      RoleInheritance financeInheritance = findInheritance(bystanderRole, financeTemplate);
      assertNotNull(financeInheritance);
      OBDal.getInstance().remove(financeInheritance);
      OBContext.setAdminMode();
      try {
        OBDal.getInstance().flush();
        // The guard runs on TransactionCompletedEvent (fires only on commit/rollback, not on a
        // mid-request flush) — commit explicitly here so the test observes its effect within
        // this same test method, matching how a real request's transaction would complete.
        OBDal.getInstance().commitAndClose();
      } finally {
        OBContext.restorePreviousMode();
      }

      List<ProcessAccess> afterRemoval = findActiveObuiappProcessAccess(bystanderRole,
          sharedProcess);
      assertEquals("Exactly ONE row must survive for the shared process — the guard must have "
          + "removed any duplicate core's own reconciliation left behind", 1, afterRemoval.size());
      assertTrue("The bystander role must still be able to see the surviving row",
          afterRemoval.get(0).getRole().getId().equals(bystanderRole.getId()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private Role createBystanderRole(User user) {
    Organization starOrg = OBDal.getInstance().get(Organization.class, "0");
    Role role = OBProvider.getInstance().get(Role.class);
    role.setNewOBObject(true);
    role.setClient(user.getClient());
    role.setOrganization(starOrg);
    role.setActive(true);
    role.setName("ETP-4830 item 7 obuiapp-guard bystander " + System.nanoTime());
    role.setUserLevel(SystemRoleTemplates.FIXED_ROLE_USER_LEVEL);
    role.setManual(true);
    role.setTemplate(false);
    role.setClientAdmin(false);
    OBDal.getInstance().save(role);
    OBDal.getInstance().flush();
    return role;
  }

  private void addInheritance(Role role, Role template, long seqno) {
    RoleInheritance inheritance = OBProvider.getInstance().get(RoleInheritance.class);
    inheritance.setNewOBObject(true);
    inheritance.setClient(role.getClient());
    inheritance.setOrganization(role.getOrganization());
    inheritance.setActive(true);
    inheritance.setRole(role);
    inheritance.setInheritFrom(template);
    inheritance.setSequenceNumber(seqno);
    OBDal.getInstance().save(inheritance);
    OBContext.setAdminMode();
    try {
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void grantObuiappProcessAccess(Role role, Process process) {
    OBContext.setAdminMode();
    try {
      ProcessAccess access = OBProvider.getInstance().get(ProcessAccess.class);
      access.setNewOBObject(true);
      access.setClient(role.getClient());
      access.setOrganization(role.getOrganization());
      access.setActive(true);
      access.setRole(role);
      access.setObuiappProcess(process);
      access.setEditableField(true);
      OBDal.getInstance().save(access);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @SuppressWarnings("unchecked")
  private RoleInheritance findInheritance(Role role, Role template) {
    OBCriteria<RoleInheritance> criteria = OBDal.getInstance()
        .createCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_INHERITFROM, template));
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (RoleInheritance) criteria.uniqueResult();
  }

  @SuppressWarnings("unchecked")
  private List<ProcessAccess> findActiveObuiappProcessAccess(Role role, Process process) {
    OBCriteria<ProcessAccess> criteria = OBDal.getInstance().createCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_OBUIAPPPROCESS, process));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    return criteria.list();
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ObuiappProcessAccessDuplicateGuardIntegrationTest*"`
Expected: FAIL on the "Exactly ONE row must survive" assertion — 2 rows exist (one per remaining overlapping template's independent INSERT), since nothing sweeps them yet. If instead only 1 row already existed even before this guard is built, note that in the task and treat the assertion as already-passing groundwork — do not weaken the assertion to force a red state; report the actual finding to the human before proceeding to Step 4, since it would mean the guard's own necessity for THIS specific test scenario needs re-confirming against the approved design's own "structurally possible" framing.

- [ ] **Step 4: Create `ObuiappProcessAccessDuplicateGuard.java`**

```java
/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.roles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.hibernate.resource.transaction.spi.TransactionStatus;
import org.openbravo.client.application.ProcessAccess;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;

/**
 * ETP-4830 item 7 — cleans up the duplicate {@code OBUIAPP_Process_Access} rows core's own
 * generic {@code RoleInheritanceManager} reconciliation can leave behind when 2+ remaining
 * templates both grant the same OBUIAPP process after a role loses one of 3+ overlapping template
 * inheritances — the same mechanism {@link ProcessAccessOverlapCorruptionGuard} PREVENTS for
 * {@code AD_Process_Access}. {@code OBUIAPP_Process_Access} has no unique constraint (confirmed
 * via {@code modules_core/org.openbravo.client.application/src-db/database/model/tables/
 * OBUIAPP_PROCESS_ACCESS.xml}: only non-unique indexes on {@code OBUIAPP_PROCESS_ID}/{@code
 * AD_ROLE_ID}), so there is nothing to CRASH — a duplicate INSERT here just leaves a
 * data-quality smell (2 active rows granting the same access), never a 500. This guard is a
 * post-reconciliation SWEEP, not a preemptive guard: materially simpler than {@link
 * ProcessAccessOverlapCorruptionGuard}, no repoint-in-place, no shared reconciliation core needed
 * — just "keep the first active row per (role, process), deactivate the rest."
 *
 * <p><b>Why this runs on {@code TransactionCompletedEvent}, not {@code onDelete}.</b> A
 * "prioritized" CDI observer can only run BEFORE every unprioritized observer for the same event
 * dispatch — never after (see {@code WindowAccessOverlapCorruptionGuard}'s own class javadoc,
 * "Why {@code @Priority} matters here" section: "observers carrying a priority are notified, in
 * ascending priority order, before any unprioritized observer"). Core's own {@code
 * RoleInheritanceEventHandler#onDelete} is itself unprioritized, so there is no {@code @Priority}
 * value that makes an observer of the SAME {@code EntityDeleteEvent} run strictly after it. A
 * genuine "sweep whatever core's reconciliation left duplicated" therefore needs a LATER hook —
 * {@code TransactionCompletedEvent} (fired from {@code OBInterceptor
 * #afterTransactionCompletion(Transaction)}) is the only one Openbravo forwards to CDI observers.
 * Gated on {@code TransactionStatus.ROLLED_BACK} the same way the existing precedent for doing
 * real work at this hook does — see {@code
 * org.openbravo.client.application.event.DataPoolSelectionEventHandler#onTransactionCompleted}.
 */
public class ObuiappProcessAccessDuplicateGuard {

  private static final Logger log =
      LogManager.getLogger(ObuiappProcessAccessDuplicateGuard.class);

  public void onTransactionComplete(@Observes org.openbravo.client.kernel.event.TransactionCompletedEvent event) {
    if (event.getTransaction() == null
        || event.getTransaction().getStatus() == TransactionStatus.ROLLED_BACK) {
      return;
    }
    sweepDuplicates();
  }

  private void sweepDuplicates() {
    OBContext.setAdminMode(false);
    try {
      List<ProcessAccess> allActive = crossClientCriteria(ProcessAccess.class)
          .add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true))
          .list();
      Map<String, ProcessAccess> keepByKey = new LinkedHashMap<>();
      for (ProcessAccess access : allActive) {
        Role role = access.getRole();
        org.openbravo.model.ad.ui.Process process = access.getObuiappProcess();
        if (role == null || process == null) {
          continue;
        }
        String key = role.getId() + ":" + process.getId();
        ProcessAccess kept = keepByKey.get(key);
        if (kept == null) {
          keepByKey.put(key, access);
        } else {
          OBDal.getInstance().remove(access);
          log.info(
              "Removed duplicate OBUIAPP_Process_Access row {} for role {} process {} — role {} "
                  + "already had an active grant for it ({})",
              access.getId(), role.getId(), process.getId(), role.getId(), kept.getId());
        }
      }
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @SuppressWarnings("unchecked")
  private <T extends org.openbravo.base.structure.BaseOBObject> OBCriteria<T> crossClientCriteria(
      Class<T> clazz) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(clazz);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria;
  }
}
```

**Known scale caveat, documented not silently ignored:** `sweepDuplicates()` scans EVERY active `OBUIAPP_Process_Access` row system-wide on every committed transaction, not just the ones touched by the transaction that just completed — acceptable for closing this ticket's specific reproduction (this table has no volume concern today), but if this table ever grows large, narrow the scan to rows touched by the completed transaction (e.g. by having `guardRemovedInheritance`-equivalent code record affected role ids in a `ThreadLocal`, the same pattern `TemplateRemovalTracker` already uses) instead of a full-table scan. Flag this to the human if `EXPLAIN ANALYZE` on `sweepDuplicates()`'s query ever shows it as slow in practice — not something to preemptively optimize now.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ObuiappProcessAccessDuplicateGuardIntegrationTest*"`
Expected: PASS. If it still fails because `sweepDuplicates()` never actually runs (e.g. the transaction commit inside the test doesn't fire `TransactionCompletedEvent` the way a real request's does under `WeldBaseTest`), that is a genuine finding — stop, report it to the human with the exact failure, and do NOT paper over it by calling `sweepDuplicates()` directly from the test (that would prove the METHOD works, not that the EVENT WIRING works, which is the actual thing this task needs to prove). The fallback design to propose in that case: move the sweep to run synchronously inside `ProcessAccessOverlapCorruptionGuard`'s own `guardRemovedInheritance` (same `onDelete`, same priority-1 timing) as a best-effort "also delete any OBUIAPP duplicate for a process this same removal affected" step, accepting that it runs BEFORE core's reconciliation rather than after (a duplicate could still theoretically reappear from core's own subsequent unprioritized pass, but this is materially better than no sweep at all) — do not implement this fallback speculatively; only if Step 5 proves the primary design does not fire in practice.

- [ ] **Step 6: Run every regression suite from Tasks 3 and 4 one final time**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "com.etendoerp.go.roles.*"`
Expected: BUILD SUCCESSFUL — every test in the `com.etendoerp.go.roles` package green (module-level `*TestSuite > initializationError` noise aside, per Global Constraints).

- [ ] **Step 7: Commit**

```bash
cd /Users/gremiger/workspaces/etendogoclean/etendo/modules/com.etendoerp.go
git add src/com/etendoerp/go/roles/ObuiappProcessAccessDuplicateGuard.java \
        src-test/src/com/etendoerp/go/roles/ObuiappProcessAccessDuplicateGuardIntegrationTest.java
git commit -m "Feature ETP-4830: Sweep duplicate OBUIAPP_Process_Access rows"
```

---

## After all 5 tasks land

1. Regenerate the compiled `.class` files that `EnsureSystemRoleTemplatesScript` needs checked into git if this work touches it — it does not (this plan never modifies `EnsureSystemRoleTemplatesScript`), so this step is not needed here; only flag it if a future change does touch that class.
2. Update `santo_4830_roles.md`'s own item 7/7a section to mark item 7 CLOSED, with the 5 commit messages/hashes, mirroring how every other closed item in that file is written up.
3. Update `modules/com.etendoerp.go/docs/neo-headless.md` if either new guard changes anything a reader of that doc would need to know about process/report access behavior (most likely: a short new subsection under wherever `WindowAccessOverlapCorruptionGuard`/ETP-4906 is already documented there, per this repo's own Documentation Freshness policy — code change + doc update is one atomic unit).
4. This is still local-only, not pushed — pushing and opening the PR is a separate, explicit step the human already said they will do themselves (per `santo_4830_roles.md`'s "Pipeline status" section), not part of this plan.
