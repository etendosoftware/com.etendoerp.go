# Process/OBUIAPP Access Overlap-Corruption Guards — Full Trigger Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `AD_Process_Access` and `OBUIAPP_Process_Access` the same full add/edit/delete overlap-corruption protection `AD_Window_Access` already has, closing the real ADD-path ownership-corruption bug live-reproduced while building the original REMOVE-path-only guard.

**Architecture:** Port `WindowAccessOverlapCorruptionGuard`'s 3 CDI event handlers (`onSave`, `onUpdate`, `onDelete` — `onDelete` already exists for Process, built fresh for OBUIAPP) to two sibling guards, sharing only pure entity-agnostic logic (`com.etendoerp.go.roles.overlap`) while keeping CDI wiring and entity-specific queries duplicated per guard, exactly as the already-approved item 7a design established.

**Tech Stack:** Java 8, Openbravo DAL/Hibernate 5, CDI (Weld) `EntityPersistenceEventObserver`, JUnit 4 (`WeldBaseTest` integration tests) + JUnit 5 (plain unit tests), Gradle.

**Spec:** `/Users/gremiger/workspaces/etendogoclean/etendo/modules/com.etendoerp.go/docs/plans/2026-08-24-process-access-overlap-guards-full-parity-design.md`

## Global Constraints

- Commit message: first line `Feature ETP-4830: <description>` (<=80 chars), no `Co-Authored-By` trailer.
- Run tests via the ROOT Etendo project, never the module-scoped Gradle task: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "..."`.
- `WindowAccessOverlapCorruptionGuard.java`'s own 7-trigger behavior for `AD_Window_Access` must never change except at the 2 named delegation points (Task 2) — every existing test in `UserRoleCompositionServiceOverlapIntegrationTest`/`UserRoleCompositionServiceOverlapReverificationTest` must keep passing, unmodified, after Task 2.
- No generic adapter/engine shared between the Process and OBUIAPP guards — each is its own independent CDI observer class, per the approved design's "Approach" section.
- Test fixture discipline (learned the hard way building the original REMOVE-path guard): integration tests that grant access directly to system templates must use FRESH, throwaway system-client (`AD_Client_ID='0'`) template roles, never the 4 real `SystemRoleTemplates` — this shared dev DB has a real pre-existing role ("Classic Role") that already composes 3 of them, and granting to real templates fans out onto it unguarded until a test's own guard code is what's under test.
- `IsReport='Y'` processes need no special handling anywhere in this plan — `AD_Process_Access` covers reports and processes identically.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/com/etendoerp/go/roles/overlap/PropagationTrigger.java` | Create — shared enum (`NEW_GRANT`/`UPDATED_GRANT`), extracted from the window guard's own private enum |
| `src/com/etendoerp/go/roles/overlap/OverlapReconciliationCore.java` | Modify — add `findJustifyingFullGrant` |
| `src-test/src/com/etendoerp/go/roles/overlap/OverlapReconciliationCoreTest.java` | Modify — add tests for `findJustifyingFullGrant` |
| `src/com/etendoerp/go/roles/WindowAccessOverlapCorruptionGuard.java` | Modify — delegate own `PropagationTrigger`/`findActiveTemplateGrantingFullAccess` to the 2 shared pieces above (behavior-preserving) |
| `src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuard.java` | Modify (twice — Tasks 3, 4) — add `onSave`, then `onUpdate` |
| `src-test/src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuardIntegrationTest.java` | Modify (twice) — add 5 new test methods |
| `src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuard.java` | Create (across Tasks 5, 6, 7) — full guard, replaces the retired `ObuiappProcessAccessDuplicateGuard` idea |
| `src-test/src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest.java` | Create (across Tasks 5, 6, 7) — 6 test methods total |

---

### Task 1: Shared core — `PropagationTrigger` + `findJustifyingFullGrant`

**Files:**
- Create: `src/com/etendoerp/go/roles/overlap/PropagationTrigger.java`
- Modify: `src/com/etendoerp/go/roles/overlap/OverlapReconciliationCore.java`
- Test: `src-test/src/com/etendoerp/go/roles/overlap/OverlapReconciliationCoreTest.java`

**Interfaces:**
- Produces: `com.etendoerp.go.roles.overlap.PropagationTrigger` (enum, values `NEW_GRANT`, `UPDATED_GRANT`). `OverlapReconciliationCore.findJustifyingFullGrant(List<GrantCandidate> candidatesOrderedBySeqNoDescending, String excludedTemplateId)` → `String` (winning template id) or `null`. Tasks 2, 3, 4, 5, 6, 7 all consume both.

- [ ] **Step 1: Write the failing tests**

Add these methods to the existing `src-test/src/com/etendoerp/go/roles/overlap/OverlapReconciliationCoreTest.java` (inside the existing `class OverlapReconciliationCoreTest { ... }` body, alongside the existing `computeWinner` tests — do not remove those):

```java
  @Test
  void findJustifyingFullGrantReturnsNullForNullList() {
    assertNull(OverlapReconciliationCore.findJustifyingFullGrant(null, null));
  }

  @Test
  void findJustifyingFullGrantReturnsNullWhenNoCandidateGrantsFullAccess() {
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("template-a", false),
        new GrantCandidate("template-b", false));
    assertNull(OverlapReconciliationCore.findJustifyingFullGrant(candidates, null));
  }

  @Test
  void findJustifyingFullGrantReturnsFirstFullGrantorInOrder() {
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("readonly-first", false),
        new GrantCandidate("full-second", true),
        new GrantCandidate("full-third", true));
    assertEquals("full-second",
        OverlapReconciliationCore.findJustifyingFullGrant(candidates, null));
  }

  @Test
  void findJustifyingFullGrantSkipsExcludedTemplateEvenWhenItGrantsFullAccess() {
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("full-excluded", true),
        new GrantCandidate("full-other", true));
    assertEquals("full-other",
        OverlapReconciliationCore.findJustifyingFullGrant(candidates, "full-excluded"));
  }

  @Test
  void findJustifyingFullGrantReturnsNullWhenOnlyFullGrantorIsExcluded() {
    List<GrantCandidate> candidates = Collections.singletonList(
        new GrantCandidate("full-excluded", true));
    assertNull(OverlapReconciliationCore.findJustifyingFullGrant(candidates, "full-excluded"));
  }
```

`java.util.Collections` needs a new import at the top of the file (`import java.util.Collections;`) if not already present — check the existing import block first.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*OverlapReconciliationCoreTest*"`
Expected: FAIL — `findJustifyingFullGrant` does not exist yet.

- [ ] **Step 3: Create `PropagationTrigger.java`**

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
 * Which core propagation method will run AFTER a guard's own dependents-reconciliation method
 * returns, for the SAME access-grant event — determines whether it is safe to unconditionally
 * delete a dependent's conflicting row. Extracted from {@code
 * com.etendoerp.go.roles.WindowAccessOverlapCorruptionGuard}'s own private enum of this shape
 * (ETP-4906, Task B6, 7th round, finding "[B7]" — see that class's own class javadoc, "The
 * seventh trigger's own gap, found in REVIEW" section, for the full root-cause write-up) so
 * {@code ProcessAccessOverlapCorruptionGuard} and {@code ObuiappProcessAccessOverlapCorruption
 * Guard} (ETP-4830 item 7) share the identical reasoning instead of re-deriving it — the
 * reasoning depends only on which core method is about to run, never on which access-type table
 * is involved.
 */
public enum PropagationTrigger {
  /**
   * A template GAINED a brand-new grant. Core propagates via {@code RoleInheritanceManager
   * #propagateNewAccess}, which ALWAYS falls back to a CREATE when it finds no existing row for a
   * dependent — so unconditionally deleting a dependent's conflicting row first is always safe.
   */
  NEW_GRANT,
  /**
   * A template's OWN EXISTING grant had its access level changed. Core propagates via {@code
   * RoleInheritanceManager#propagateUpdatedAccess}, which has NO create fallback: it only ever
   * UPDATEs a dependent's row it can find, and silently does nothing otherwise. Unconditionally
   * deleting here would permanently lose the dependent's access with nothing left to restore it.
   */
  UPDATED_GRANT
}
```

- [ ] **Step 4: Add `findJustifyingFullGrant` to `OverlapReconciliationCore.java`**

Add this method inside the existing `public final class OverlapReconciliationCore { ... }`, after the existing `computeWinner` method:

```java
  /**
   * "Does some OTHER active template (excluding {@code excludedTemplateId}) still grant full
   * access to this item" — the survey both the ADD-path widening trigger and the update-path
   * most-permissive-wins survey need. Extracted from {@code WindowAccessOverlapCorruption
   * Guard#findActiveTemplateGrantingFullAccess}'s own loop (ETP-4906) — same SeqNo-descending
   * tie-break as {@link #computeWinner(List)}: returns the FIRST candidate (in list order) that
   * grants full access, after skipping {@code excludedTemplateId} — not necessarily the
   * highest-SeqNo grantor overall, just the highest-SeqNo one that is BOTH non-excluded AND full.
   *
   * <p>Returns {@code null} when {@code candidatesOrderedBySeqNoDescending} is {@code null}, or
   * when no non-excluded candidate grants full access.
   */
  public static String findJustifyingFullGrant(
      List<GrantCandidate> candidatesOrderedBySeqNoDescending, String excludedTemplateId) {
    if (candidatesOrderedBySeqNoDescending == null) {
      return null;
    }
    for (GrantCandidate candidate : candidatesOrderedBySeqNoDescending) {
      if (excludedTemplateId != null && excludedTemplateId.equals(candidate.getTemplateId())) {
        continue;
      }
      if (candidate.isFullAccess()) {
        return candidate.getTemplateId();
      }
    }
    return null;
  }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*OverlapReconciliationCoreTest*"`
Expected: PASS, all tests (existing `computeWinner` tests + the 5 new `findJustifyingFullGrant` ones).

- [ ] **Step 6: Commit**

```bash
cd /Users/gremiger/workspaces/etendogoclean/etendo/modules/com.etendoerp.go
git add src/com/etendoerp/go/roles/overlap/PropagationTrigger.java \
        src/com/etendoerp/go/roles/overlap/OverlapReconciliationCore.java \
        src-test/src/com/etendoerp/go/roles/overlap/OverlapReconciliationCoreTest.java
git commit -m "Feature ETP-4830: Add shared PropagationTrigger and full-grant survey"
```

---

### Task 2: Refactor `WindowAccessOverlapCorruptionGuard` to delegate (regression-safety)

**Files:**
- Modify: `src/com/etendoerp/go/roles/WindowAccessOverlapCorruptionGuard.java`

**Interfaces:**
- Consumes: `com.etendoerp.go.roles.overlap.PropagationTrigger` (Task 1), `OverlapReconciliationCore.findJustifyingFullGrant` (Task 1).
- Produces: no change to this class's own public/package API.

Pure, behavior-preserving refactor, same discipline as the original item 7 Task 3. Do it in 2 independent sub-steps, each verified by the full existing regression suite before moving to the next.

- [ ] **Step 1: Delegate the private `PropagationTrigger` enum**

In `WindowAccessOverlapCorruptionGuard.java`:
1. Delete the private enum block (currently at the class's own `private enum PropagationTrigger { NEW_GRANT, UPDATED_GRANT }` declaration, with its own javadoc).
2. Add the import: `import com.etendoerp.go.roles.overlap.PropagationTrigger;`
3. No other change needed — every existing reference (`guardDependentsOf(WindowAccess, PropagationTrigger)`'s parameter type, `onSave`'s `PropagationTrigger.NEW_GRANT`, `onUpdate`'s `PropagationTrigger.UPDATED_GRANT`) resolves identically via the import, since the shared enum has the exact same 2 values in the same order.

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*UserRoleCompositionServiceOverlapIntegrationTest*" --tests "*UserRoleCompositionServiceOverlapReverificationTest*"`
Expected: PASS, every test — same count as before this change.

- [ ] **Step 2: Delegate `findActiveTemplateGrantingFullAccess`'s decision to `findJustifyingFullGrant`**

Replace the body of the 3-argument overload:

```java
  private Role findActiveTemplateGrantingFullAccess(Role dependent, Window window,
      Role excludedTemplate) {
    Map<String, Role> templatesById = new LinkedHashMap<>();
    List<GrantCandidate> candidates = new ArrayList<>();
    for (Role template : findActiveTemplatesFor(dependent, null)) {
      templatesById.putIfAbsent(template.getId(), template);
      WindowAccess templateAccess = findActiveWindowAccess(template, window);
      if (templateAccess != null) {
        candidates.add(new GrantCandidate(template.getId(),
            Boolean.TRUE.equals(templateAccess.isEditableField())));
      }
    }
    String excludedTemplateId = excludedTemplate != null ? excludedTemplate.getId() : null;
    String winnerId =
        OverlapReconciliationCore.findJustifyingFullGrant(candidates, excludedTemplateId);
    return winnerId != null ? templatesById.get(winnerId) : null;
  }
```

Leave the no-exclusion 2-argument overload (`findActiveTemplateGrantingFullAccess(Role, Window)` → delegates to the 3-arg one with `null`) exactly as-is — it already just calls the 3-arg overload, no change needed. No new imports are needed (`Map`, `LinkedHashMap`, `List`, `ArrayList`, `GrantCandidate`, `OverlapReconciliationCore` are all already imported in this file from the original item 7 Task 3 refactor).

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*UserRoleCompositionServiceOverlapIntegrationTest*" --tests "*UserRoleCompositionServiceOverlapReverificationTest*"`
Expected: PASS, same count as Step 1 — in particular `testDowngradingOneOfTwoOverlappingTemplatesNeverDowngradesDependentWhenTheOtherStillGrantsFullAccess` and `testGainingReadOnlyTemplateInheritanceNeverDowngradesExistingFullAccess` (the two tests that most directly exercise this method) must still pass identically.

- [ ] **Step 3: Run the full module regression suite as a final check**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "com.etendoerp.go.roles.*"`
Expected: BUILD SUCCESSFUL. Ignore `*TestSuite > initializationError` (known `--tests`-filter noise per Global Constraints); confirm no OTHER failure by name.

- [ ] **Step 4: Commit**

```bash
cd /Users/gremiger/workspaces/etendogoclean/etendo/modules/com.etendoerp.go
git add src/com/etendoerp/go/roles/WindowAccessOverlapCorruptionGuard.java
git commit -m "Feature ETP-4830: Delegate window guard's trigger enum and survey"
```

---

### Task 3: `ProcessAccessOverlapCorruptionGuard` — `onSave` (ADD path)

**Files:**
- Modify: `src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuard.java`
- Modify: `src-test/src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuardIntegrationTest.java`

**Interfaces:**
- Consumes: `PropagationTrigger.NEW_GRANT` (Task 1). `guardDependentsOf(ProcessAccess, PropagationTrigger)` is called ONLY with `NEW_GRANT` in this task — the `UPDATED_GRANT` branch and `onUpdate` itself are Task 4's job.
- Produces: `void onSave(EntityNewEvent)` — new public method, consumed by no other task (it's a CDI observer entry point, discovered by Weld).

- [ ] **Step 1: Write the failing tests**

Add these 3 methods to the existing `src-test/src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuardIntegrationTest.java` (inside the existing class body, alongside the existing `testRemovingOneOfFourTemplatesLeavesTwoRemainingOverlappingTemplatesUnbroken` — do not remove it, and reuse its existing helper methods `createBystanderRole`, `addInheritance`, `grantProcessAccess`, `findInheritance`, `createThrowawaySystemTemplateRole`, `findProcessAccess`):

```java
  @Test
  public void testBystanderRoleNotPassedToAssignTemplateRolesIsAlsoProtected() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, UNUSED_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);

      grantProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      ProcessAccess bystanderAccess = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("The bystander role must have received the propagated access, not lost it",
          bystanderAccess);
      assertEquals("client must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getClient().getId(), bystanderAccess.getClient().getId());
      assertEquals("organization must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getOrganization().getId(), bystanderAccess.getOrganization().getId());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testGainingReadOnlyTemplateInheritanceNeverDowngradesExistingFullAccess()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, UNUSED_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      grantProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);

      ProcessAccess afterFinance = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("Sanity: Finance alone must have propagated the shared process", afterFinance);
      assertTrue("Sanity: Finance alone must grant full access",
          Boolean.TRUE.equals(afterFinance.isEditableField()));

      addInheritance(bystanderRole, salesTemplate, 20L);

      ProcessAccess afterSales = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("The shared process's access must survive gaining the second template",
          afterSales);
      assertEquals("client must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getClient().getId(), afterSales.getClient().getId());
      assertEquals("organization must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getOrganization().getId(), afterSales.getOrganization().getId());
      assertTrue("Most-permissive-wins: gaining a READ-ONLY template must never downgrade "
          + "already-existing FULL access", Boolean.TRUE.equals(afterSales.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testRemovingTheTemplateThatJustifiedAWidenedAccessLevelCorrectlyDowngrades()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, UNUSED_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      grantProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);

      ProcessAccess widened = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(widened);
      assertTrue("Sanity: most-permissive-wins must resolve to full",
          Boolean.TRUE.equals(widened.isEditableField()));
      assertEquals("InheritedFrom must point at the template that actually justifies the "
          + "widened value (Finance), not the template CREATE originally sourced the row from",
          financeTemplate.getId(),
          widened.getInheritedFrom() != null ? widened.getInheritedFrom().getId() : null);

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
          + "one remaining template (Sales)", afterRemoval);
      assertEquals("The process must now be re-derived from Sales, the one remaining template",
          salesTemplate.getId(),
          afterRemoval.getInheritedFrom() != null ? afterRemoval.getInheritedFrom().getId()
              : null);
      assertFalse("Removing the FULL template must downgrade to the remaining READ-ONLY "
          + "template's level, not stay stuck at full",
          Boolean.TRUE.equals(afterRemoval.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ProcessAccessOverlapCorruptionGuardIntegrationTest*"`
Expected: FAIL — no `onSave` handler exists yet, so ownership is never corrected and levels are never widened; `testBystanderRoleNotPassedToAssignTemplateRolesIsAlsoProtected` fails on the ownership assertions (or the row not existing / existing with the template's own client), and the other two fail on the level/`InheritedFrom` assertions. The pre-existing `testRemovingOneOfFourTemplatesLeavesTwoRemainingOverlappingTemplatesUnbroken` must still PASS (its own `onDelete` mechanism is untouched by this task).

- [ ] **Step 3: Implement `onSave` and its supporting methods**

In `src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuard.java`:

1. Add imports:
```java
import org.openbravo.client.kernel.event.EntityNewEvent;
import com.etendoerp.go.roles.overlap.PropagationTrigger;
```

2. Replace `resolveEntities()` to also observe `ProcessAccess`:
```java
  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[] {
          ModelProvider.getInstance().getEntity(ProcessAccess.ENTITY_NAME),
          ModelProvider.getInstance().getEntity(RoleInheritance.ENTITY_NAME) };
    }
    return entities;
  }
```

3. Add the `onSave` handler, right after `getObservedEntities()`:
```java
  /**
   * Mirrors {@code WindowAccessOverlapCorruptionGuard#onSave(EntityNewEvent)} — see that method's
   * own javadoc. A NEW {@code AD_Process_Access} row on a template, or a NEW {@code
   * AD_Role_Inheritance} row on any role, are the two places core's own propagation can start a
   * corrupting UPDATE against a role this class never even knows is at risk.
   */
  public void onSave(@Observes @Priority(RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS)
      EntityNewEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Object target = event.getTargetInstance();
    if (target instanceof ProcessAccess) {
      ProcessAccess access = (ProcessAccess) target;
      Role role = access.getRole();
      if (role != null && Boolean.TRUE.equals(role.isTemplate())) {
        guardDependentsOf(access, PropagationTrigger.NEW_GRANT);
      } else {
        correctInheritedOwnership(event, access);
        widenInheritedAccessLevelIfNeeded(event, access);
      }
    } else if (target instanceof RoleInheritance) {
      guardNewInheritance((RoleInheritance) target);
    }
  }
```

4. Add the ownership-correction and widening methods, mirroring `WindowAccessOverlapCorruptionGuard`'s own (see that class's own javadoc for the full "why `setCurrentState`, not a plain setter" and "why widening is a separate concern" rationale — cross-referenced here rather than repeated):
```java
  /**
   * Mirrors {@code WindowAccessOverlapCorruptionGuard#correctInheritedOwnership} — see that
   * method's own javadoc for the full rationale (why {@code event.setCurrentState}, not a plain
   * setter, is required).
   */
  private void correctInheritedOwnership(EntityNewEvent event, ProcessAccess access) {
    if (access.getInheritedFrom() == null) {
      return;
    }
    Role owner = access.getRole();
    if (owner == null) {
      return;
    }
    Entity paEntity = processAccessEntity();
    Property clientProperty = paEntity.getProperty(ProcessAccess.PROPERTY_CLIENT);
    Property organizationProperty = paEntity.getProperty(ProcessAccess.PROPERTY_ORGANIZATION);

    boolean clientWrong = owner.getClient() != null
        && !sameId(owner.getClient(), event.getCurrentState(clientProperty));
    boolean organizationWrong = owner.getOrganization() != null
        && !sameId(owner.getOrganization(), event.getCurrentState(organizationProperty));
    if (!clientWrong && !organizationWrong) {
      return;
    }
    if (clientWrong) {
      event.setCurrentState(clientProperty, owner.getClient());
    }
    if (organizationWrong) {
      event.setCurrentState(organizationProperty, owner.getOrganization());
    }
    log.info(
        "Corrected AD_Process_Access ownership on role {} process {}: pinned client/organization "
            + "back to the role's own (template-derived row, inherited from {})",
        owner.getId(), access.getProcess() != null ? access.getProcess().getId() : null,
        access.getInheritedFrom().getId());
  }

  private static Entity processAccessEntity() {
    return ModelProvider.getInstance().getEntity(ProcessAccess.ENTITY_NAME);
  }

  /**
   * Mirrors {@code WindowAccessOverlapCorruptionGuard#widenInheritedAccessLevelIfNeeded} — see
   * that method's own javadoc for the full rationale (most-permissive-wins, InheritedFrom
   * bookkeeping).
   */
  private void widenInheritedAccessLevelIfNeeded(EntityNewEvent event, ProcessAccess access) {
    if (access.getInheritedFrom() == null) {
      return;
    }
    Role owner = access.getRole();
    Process process = access.getProcess();
    if (owner == null || process == null) {
      return;
    }
    Entity paEntity = processAccessEntity();
    Property editableFieldProperty = paEntity.getProperty(ProcessAccess.PROPERTY_EDITABLEFIELD);
    if (Boolean.TRUE.equals(event.getCurrentState(editableFieldProperty))) {
      return;
    }
    Role justifyingTemplate = findActiveTemplateGrantingFullAccess(owner, process);
    if (justifyingTemplate == null) {
      return;
    }
    event.setCurrentState(editableFieldProperty, true);
    Property inheritedFromProperty = paEntity.getProperty(ProcessAccess.PROPERTY_INHERITEDFROM);
    Role originalSource = access.getInheritedFrom();
    event.setCurrentState(inheritedFromProperty, justifyingTemplate);
    log.info(
        "Widened AD_Process_Access on role {} process {} to full and repointed InheritedFrom "
            + "from {} to {}: another currently-inherited template already grants this process "
            + "full access",
        owner.getId(), process.getId(), originalSource.getId(), justifyingTemplate.getId());
  }

  private Role findActiveTemplateGrantingFullAccess(Role dependent, Process process) {
    return findActiveTemplateGrantingFullAccess(dependent, process, null);
  }

  /**
   * Mirrors {@code WindowAccessOverlapCorruptionGuard#findActiveTemplateGrantingFullAccess} —
   * already built here in its FINAL, delegating-to-the-shared-core shape (see {@code
   * OverlapReconciliationCore#findJustifyingFullGrant}'s own javadoc), not the original manual
   * loop {@code WindowAccessOverlapCorruptionGuard} started with and Task 2 later refactored away.
   */
  private Role findActiveTemplateGrantingFullAccess(Role dependent, Process process,
      Role excludedTemplate) {
    Map<String, Role> templatesById = new LinkedHashMap<>();
    List<GrantCandidate> candidates = new ArrayList<>();
    for (Role template : ActiveTemplateInheritance.findActiveTemplatesFor(dependent, null)) {
      templatesById.putIfAbsent(template.getId(), template);
      ProcessAccess templateAccess = findActiveProcessAccess(template, process);
      if (templateAccess != null) {
        candidates.add(new GrantCandidate(template.getId(),
            Boolean.TRUE.equals(templateAccess.isEditableField())));
      }
    }
    String excludedTemplateId = excludedTemplate != null ? excludedTemplate.getId() : null;
    String winnerId =
        OverlapReconciliationCore.findJustifyingFullGrant(candidates, excludedTemplateId);
    return winnerId != null ? templatesById.get(winnerId) : null;
  }
```

5. Add `guardNewInheritance`, `guardDependentsOf` (NEW_GRANT branch only in this task — see Task 4 for the UPDATED_GRANT branch), `clearConflictingAccessUnconditionally`, and `deleteForcingCreatePath`:
```java
  /**
   * Mirrors {@code WindowAccessOverlapCorruptionGuard#guardNewInheritance} — see that method's
   * own javadoc.
   */
  private void guardNewInheritance(RoleInheritance inheritance) {
    Role dependent = inheritance.getRole();
    Role template = inheritance.getInheritFrom();
    if (dependent == null || template == null || !Boolean.TRUE.equals(template.isTemplate())) {
      return;
    }
    for (ProcessAccess templateGrant : findActiveProcessAccess(template)) {
      Process process = templateGrant.getProcess();
      if (process == null) {
        continue;
      }
      clearConflictingAccessUnconditionally(dependent, process, template);
    }
  }

  /**
   * Mirrors {@code WindowAccessOverlapCorruptionGuard#guardDependentsOf} — see that method's own
   * javadoc. This task only ever calls this with {@link PropagationTrigger#NEW_GRANT} (from
   * {@link #onSave(EntityNewEvent)}); the {@code UPDATED_GRANT} branch is Task 4's own addition,
   * wired from a new {@code onUpdate} handler.
   */
  private void guardDependentsOf(ProcessAccess templateAccess, PropagationTrigger trigger) {
    Role role = templateAccess.getRole();
    if (role == null || !Boolean.TRUE.equals(role.isTemplate())) {
      return;
    }
    Process process = templateAccess.getProcess();
    if (process == null) {
      return;
    }
    if (trigger == PropagationTrigger.NEW_GRANT) {
      for (Role dependent : findActiveDependentRoles(role)) {
        clearConflictingAccessUnconditionally(dependent, process, role);
      }
    }
  }

  /**
   * Mirrors {@code WindowAccessOverlapCorruptionGuard#clearConflictingAccessUnconditionally} —
   * see that method's own javadoc for why "already correct" is not a reason to skip.
   */
  private void clearConflictingAccessUnconditionally(Role dependent, Process process,
      Role grantingTemplate) {
    ProcessAccess existing = findActiveProcessAccess(dependent, process);
    if (existing == null) {
      return;
    }
    deleteForcingCreatePath(existing, dependent, process, grantingTemplate,
        existing.getInheritedFrom());
  }

  /**
   * Mirrors {@code WindowAccessOverlapCorruptionGuard#deleteForcingCreatePath} — see that
   * method's own javadoc for the full bulk-HQL-vs-reentrant-flush rationale and the
   * refresh-not-evict collection-management reasoning.
   */
  private void deleteForcingCreatePath(ProcessAccess existing, Role dependent, Process process,
      Role template, Role previousSource) {
    OBContext.setAdminMode(false);
    try {
      OBDal.getInstance().getSession()
          .createQuery("delete from " + ProcessAccess.ENTITY_NAME + " where id = :id")
          .setParameter("id", existing.getId())
          .executeUpdate();
    } finally {
      OBContext.restorePreviousMode();
    }
    dependent.getADProcessAccessList().remove(existing);
    OBDal.getInstance().refresh(dependent);
    OBDal.getInstance().getSession().evict(existing);
    log.info(
        "Prevented cross-template AD_Process_Access overlap corruption: cleared role {} process "
            + "{} access (previously {}) before template {}'s own grant propagates, forcing core "
            + "onto the safe CREATE path",
        dependent.getId(), process.getId(),
        previousSource != null ? "inherited from " + previousSource.getId() : "manually granted",
        template.getId());
  }
```

6. Add the new imports this step needs at the top of the file: `java.util.LinkedHashMap`, `java.util.Map` (both already imported — check first), `com.etendoerp.go.roles.overlap.GrantCandidate` (already imported), `com.etendoerp.go.roles.overlap.ActiveTemplateInheritance` (already imported), `com.etendoerp.go.roles.overlap.OverlapReconciliationCore` (already imported). Only `org.openbravo.client.kernel.event.EntityNewEvent` and `com.etendoerp.go.roles.overlap.PropagationTrigger` are genuinely new — confirm the others by reading the file's current import block before adding duplicates.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ProcessAccessOverlapCorruptionGuardIntegrationTest*"`
Expected: PASS, all 4 tests (the 3 new ones plus the pre-existing REMOVE-path one).

- [ ] **Step 5: Run the window-guard regression suites (defense in depth)**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*UserRoleCompositionServiceOverlapIntegrationTest*" --tests "*UserRoleCompositionServiceOverlapReverificationTest*"`
Expected: PASS, same count as Task 2.

- [ ] **Step 6: Commit**

```bash
cd /Users/gremiger/workspaces/etendogoclean/etendo/modules/com.etendoerp.go
git add src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuard.java \
        src-test/src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuardIntegrationTest.java
git commit -m "Feature ETP-4830: Guard AD_Process_Access ADD-path ownership"
```

---

### Task 4: `ProcessAccessOverlapCorruptionGuard` — `onUpdate` (UPDATE path)

**Files:**
- Modify: `src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuard.java`
- Modify: `src-test/src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuardIntegrationTest.java`

**Interfaces:**
- Consumes: `PropagationTrigger.UPDATED_GRANT` (Task 1), `guardDependentsOf` (Task 3, extended here with its `UPDATED_GRANT` branch).
- Produces: `void onUpdate(EntityUpdateEvent)`.

- [ ] **Step 1: Write the failing tests**

Add these 2 methods and 1 new helper to `ProcessAccessOverlapCorruptionGuardIntegrationTest.java`:

```java
  @Test
  public void testUpdatingTemplatesOwnAccessLevelNeverDeletesAnAlreadyCorrectlySourcedDependentRow()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, UNUSED_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role template = createThrowawaySystemTemplateRole();
      grantProcessAccess(template, sharedProcess, true);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, template, 10L);

      ProcessAccess beforeUpdate = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(beforeUpdate);
      assertEquals(template.getId(),
          beforeUpdate.getInheritedFrom() != null ? beforeUpdate.getInheritedFrom().getId()
              : null);
      assertFalse(Boolean.TRUE.equals(beforeUpdate.isEditableField()));

      ProcessAccess templateAccess = findProcessAccess(template, sharedProcess);
      assertNotNull(templateAccess);
      updateProcessAccessLevel(templateAccess, false);

      ProcessAccess afterUpdate = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("The dependent's row must survive a routine UPDATE to the template's own "
          + "access level, not be silently deleted with nothing left to restore it", afterUpdate);
      assertEquals("client must always match the DEPENDENT role's own, never a template's",
          bystanderRole.getClient().getId(), afterUpdate.getClient().getId());
      assertEquals("organization must always match the DEPENDENT role's own, never a template's",
          bystanderRole.getOrganization().getId(), afterUpdate.getOrganization().getId());
      assertEquals(template.getId(),
          afterUpdate.getInheritedFrom() != null ? afterUpdate.getInheritedFrom().getId() : null);
      assertTrue("The dependent's access level must be corrected to match the template's new "
          + "(widened) value", Boolean.TRUE.equals(afterUpdate.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testDowngradingOneOfTwoOverlappingTemplatesNeverDowngradesDependentWhenTheOtherStillGrantsFullAccess()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, UNUSED_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role templateA = createThrowawaySystemTemplateRole();
      Role templateB = createThrowawaySystemTemplateRole();
      grantProcessAccess(templateA, sharedProcess, false);
      grantProcessAccess(templateB, sharedProcess, false);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, templateA, 10L);
      addInheritance(bystanderRole, templateB, 20L);

      ProcessAccess beforeUpdate = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(beforeUpdate);
      assertTrue(Boolean.TRUE.equals(beforeUpdate.isEditableField()));
      assertEquals("Sanity: sourced from templateB (higher SeqNo)", templateB.getId(),
          beforeUpdate.getInheritedFrom() != null ? beforeUpdate.getInheritedFrom().getId()
              : null);

      ProcessAccess templateBAccess = findProcessAccess(templateB, sharedProcess);
      assertNotNull(templateBAccess);
      updateProcessAccessLevel(templateBAccess, true);

      ProcessAccess afterUpdate = findProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(afterUpdate);
      assertTrue("MOST-PERMISSIVE-WINS: the dependent must STAY at full access — templateA "
          + "still actively grants this process full access", Boolean.TRUE.equals(
              afterUpdate.isEditableField()));
      assertEquals("InheritedFrom must repoint to the template that actually still justifies "
          + "full access (templateA)", templateA.getId(),
          afterUpdate.getInheritedFrom() != null ? afterUpdate.getInheritedFrom().getId() : null);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * UPDATEs (never creates) an existing template's own {@link ProcessAccess} row's level — the
   * B7 trigger's own entry point (core's {@code onUpdate}/{@code propagateUpdatedAccess}).
   */
  private void updateProcessAccessLevel(ProcessAccess access, boolean readOnly) {
    OBContext.setAdminMode();
    try {
      access.setEditableField(!readOnly);
      OBDal.getInstance().save(access);
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ProcessAccessOverlapCorruptionGuardIntegrationTest*"`
Expected: FAIL — no `onUpdate` handler exists yet, so a template's own UPDATE never propagates to the dependent at all; `afterUpdate` assertions fail (either the row stays at its stale level, or — since `guardDependentsOf`'s `UPDATED_GRANT` branch doesn't exist yet — nothing corrects it). The 4 previously-passing tests must still PASS.

- [ ] **Step 3: Implement `onUpdate` and its supporting method**

In `src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuard.java`:

1. Add import: `import org.openbravo.client.kernel.event.EntityUpdateEvent;`

2. Add the `onUpdate` handler, right after `onSave`:
```java
  /**
   * Mirrors {@code WindowAccessOverlapCorruptionGuard#onUpdate(EntityUpdateEvent)} — see that
   * method's own javadoc. Uses a DIFFERENT safe strategy than {@link #onSave(EntityNewEvent)}'s
   * {@code NEW_GRANT} trigger: core's own {@code propagateUpdatedAccess} (triggered here) has NO
   * create fallback, unlike {@code propagateNewAccess}.
   */
  public void onUpdate(@Observes @Priority(RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS)
      EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Object target = event.getTargetInstance();
    if (target instanceof ProcessAccess) {
      guardDependentsOf((ProcessAccess) target, PropagationTrigger.UPDATED_GRANT);
    }
  }
```

3. Extend `guardDependentsOf` (from Task 3) with the `UPDATED_GRANT` branch:
```java
  private void guardDependentsOf(ProcessAccess templateAccess, PropagationTrigger trigger) {
    Role role = templateAccess.getRole();
    if (role == null || !Boolean.TRUE.equals(role.isTemplate())) {
      return;
    }
    Process process = templateAccess.getProcess();
    if (process == null) {
      return;
    }
    for (Role dependent : findActiveDependentRoles(role)) {
      if (trigger == PropagationTrigger.NEW_GRANT) {
        clearConflictingAccessUnconditionally(dependent, process, role);
      } else {
        repointIfAlreadySourcedFromTemplate(dependent, process, role, templateAccess);
      }
    }
  }
```

4. Add `repointIfAlreadySourcedFromTemplate`, mirroring `WindowAccessOverlapCorruptionGuard`'s own (BUG-2-fixed) version:
```java
  /**
   * Mirrors {@code WindowAccessOverlapCorruptionGuard#repointIfAlreadySourcedFromTemplate} — see
   * that method's own javadoc for the full [B7]/BUG-2 root-cause write-up (why deleting is not
   * safe on this trigger, and why the most-permissive-wins survey against every OTHER actively-
   * inherited template is required before trusting {@code grantingTemplate}'s own new value).
   */
  private void repointIfAlreadySourcedFromTemplate(Role dependent, Process process,
      Role grantingTemplate, ProcessAccess templateAccess) {
    ProcessAccess existing = findActiveProcessAccess(dependent, process);
    if (existing == null) {
      return;
    }
    Role existingSource = existing.getInheritedFrom();
    if (existingSource == null || !sameId(existingSource, grantingTemplate)) {
      return;
    }
    boolean grantingTemplateNewLevel = Boolean.TRUE.equals(templateAccess.isEditableField());
    Role otherJustifyingTemplate =
        grantingTemplateNewLevel ? null
            : findActiveTemplateGrantingFullAccess(dependent, process, grantingTemplate);

    boolean finalLevel = grantingTemplateNewLevel || otherJustifyingTemplate != null;
    Role winner = otherJustifyingTemplate != null ? otherJustifyingTemplate : grantingTemplate;

    boolean sourceCorrect = sameId(existingSource, winner);
    boolean levelCorrect = Boolean.valueOf(finalLevel).equals(existing.isEditableField());
    if (sourceCorrect && levelCorrect) {
      return;
    }
    repointInPlace(existing, process, winner, finalLevel, existingSource);
  }
```

Note: `repointInPlace(ProcessAccess, Process, Role, boolean, Role)` already exists in this file from the original REMOVE-path task — it is reused here unchanged, no new method needed for it.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ProcessAccessOverlapCorruptionGuardIntegrationTest*"`
Expected: PASS, all 6 tests.

- [ ] **Step 5: Run the window-guard regression suites**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*UserRoleCompositionServiceOverlapIntegrationTest*" --tests "*UserRoleCompositionServiceOverlapReverificationTest*"`
Expected: PASS, unchanged.

- [ ] **Step 6: Commit**

```bash
cd /Users/gremiger/workspaces/etendogoclean/etendo/modules/com.etendoerp.go
git add src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuard.java \
        src-test/src/com/etendoerp/go/roles/ProcessAccessOverlapCorruptionGuardIntegrationTest.java
git commit -m "Feature ETP-4830: Guard AD_Process_Access UPDATE-path ownership"
```

---

### Task 5: `ObuiappProcessAccessOverlapCorruptionGuard` — skeleton + REMOVE path

**Files:**
- Create: `src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuard.java`
- Create: `src-test/src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest.java`

**Interfaces:**
- Consumes: `ActiveTemplateInheritance`, `TemplateRemovalTracker`, `GrantCandidate`, `OverlapReconciliationCore` (all Task 1/2 of the original plan, already built).
- Produces: the class itself, plus `onDelete`/`onTransactionComplete` — consumed by Tasks 6/7 (same file, extended further) and by nothing outside this file.

**Import gotcha, confirmed by reading the generated entity source directly — do not use the classic `Process` type here.** `org.openbravo.client.application.ProcessAccess#getObuiappProcess()` returns `org.openbravo.client.application.Process` — a DISTINCT class from `org.openbravo.model.ad.ui.Process` (the one `ProcessAccessOverlapCorruptionGuard`, Tasks 3/4, correctly uses for classic `AD_Process_Access`). The two `Process` classes are unrelated types in different packages that happen to share a simple name; every code block in Tasks 5–7 below already imports the correct one (`org.openbravo.client.application.Process`) — do not "fix" it to the `ad.ui` package by analogy with the Process guard, and do not import both in the same file (there is no need to — this file only ever touches the OBUIAPP one).

**Verified real fixture data** (live, read-only query, 2026-08-24): `OBUIAPP_Process` id `0662F6BC8D604AAEA5A2DD49E87F4B65` ("SII Invoices Query") is active. Since this task's tests use throwaway templates (per Global Constraints), the "unused by real templates" property that mattered for the original Process guard's fixture does not apply here — any active `OBUIAPP_Process` row works. Re-verify it still exists before running against a different environment:
```sql
SELECT obuiapp_process_id, name FROM obuiapp_process WHERE obuiapp_process_id = '0662F6BC8D604AAEA5A2DD49E87F4B65' AND isactive='Y';
```

- [ ] **Step 1: Write the failing test**

Create `src-test/src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest.java`:

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
import org.openbravo.client.application.ProcessAccess;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.User;
import org.openbravo.client.application.Process;
import org.openbravo.model.common.enterprise.Organization;

/**
 * ETP-4830 item 7 (full-parity expansion) — {@code OBUIAPP_Process_Access} equivalent of {@link
 * ProcessAccessOverlapCorruptionGuardIntegrationTest}. See {@link
 * ObuiappProcessAccessOverlapCorruptionGuard}'s own class javadoc for why this guard uses the
 * SAME repoint-in-place mechanism as {@link ProcessAccessOverlapCorruptionGuard} despite {@code
 * OBUIAPP_Process_Access} having no unique constraint.
 */
public class ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest extends WeldBaseTest {

  /** Verified (live DB check, 2026-08-24) active. Tests use throwaway templates, so no
   *  "unused by real templates" property is required of this fixture. */
  private static final String OBUIAPP_PROCESS_ID = "0662F6BC8D604AAEA5A2DD49E87F4B65";

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
      Process sharedProcess = OBDal.getInstance().get(Process.class, OBUIAPP_PROCESS_ID);
      assertNotNull("Test fixture must contain OBUIAPP_Process " + OBUIAPP_PROCESS_ID,
          sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      Role purchasingTemplate = createThrowawaySystemTemplateRole();
      Role inventoryTemplate = createThrowawaySystemTemplateRole();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      grantObuiappProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantObuiappProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();
      grantObuiappProcessAccess(purchasingTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);
      addInheritance(bystanderRole, purchasingTemplate, 30L);
      addInheritance(bystanderRole, inventoryTemplate, 40L);

      ProcessAccess beforeRemoval = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("Sanity: composing all 4 templates must have propagated the shared process",
          beforeRemoval);
      assertEquals("Sanity: Finance is the only full grantor among all 4", financeTemplate.getId(),
          beforeRemoval.getInheritedFrom() != null ? beforeRemoval.getInheritedFrom().getId()
              : null);
      assertTrue(Boolean.TRUE.equals(beforeRemoval.isEditableField()));

      RoleInheritance financeInheritance = findInheritance(bystanderRole, financeTemplate);
      assertNotNull(financeInheritance);
      OBDal.getInstance().remove(financeInheritance);
      OBContext.setAdminMode();
      try {
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }

      ProcessAccess afterRemoval = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("The shared process's access must survive the removal, re-derived from the "
          + "2 remaining overlapping templates, not silently dropped or duplicated", afterRemoval);
      assertEquals("client must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getClient().getId(), afterRemoval.getClient().getId());
      assertEquals("organization must always match the BYSTANDER role's own, never a template's",
          bystanderRole.getOrganization().getId(), afterRemoval.getOrganization().getId());
      assertEquals("Purchasing (highest-SeqNo among the 2 remaining grantors) must become source",
          purchasingTemplate.getId(),
          afterRemoval.getInheritedFrom() != null ? afterRemoval.getInheritedFrom().getId()
              : null);
      assertFalse("Neither remaining grantor (Sales, Purchasing) is full",
          Boolean.TRUE.equals(afterRemoval.isEditableField()));

      // No-duplicate confirmation — the mechanism this guard needs to prove for OBUIAPP
      // specifically, since OBUIAPP_Process_Access has no unique constraint to enforce it.
      assertEquals("Exactly ONE active row must exist for (bystander, process) — repoint-in-place "
          + "must have prevented core's own duplicate-INSERT race, not just avoided a crash", 1,
          findAllActiveObuiappProcessAccess(bystanderRole, sharedProcess).size());
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

  private Role createThrowawaySystemTemplateRole() {
    OBContext.setAdminMode();
    try {
      Client systemClient = OBDal.getInstance().get(Client.class, "0");
      Organization starOrg = OBDal.getInstance().get(Organization.class, "0");
      Role role = OBProvider.getInstance().get(Role.class);
      role.setNewOBObject(true);
      role.setClient(systemClient);
      role.setOrganization(starOrg);
      role.setActive(true);
      role.setName("ETP-4830 obuiapp-guard throwaway " + System.nanoTime());
      role.setUserLevel(SystemRoleTemplates.FIXED_ROLE_USER_LEVEL);
      role.setManual(true);
      role.setTemplate(true);
      role.setClientAdmin(false);
      OBDal.getInstance().save(role);
      OBDal.getInstance().flush();
      return role;
    } finally {
      OBContext.restorePreviousMode();
    }
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

  private void grantObuiappProcessAccess(Role role, Process process, boolean readOnly) {
    OBContext.setAdminMode();
    try {
      ProcessAccess access = OBProvider.getInstance().get(ProcessAccess.class);
      access.setNewOBObject(true);
      access.setClient(role.getClient());
      access.setOrganization(role.getOrganization());
      access.setActive(true);
      access.setRole(role);
      access.setObuiappProcess(process);
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
  private ProcessAccess findObuiappProcessAccess(Role role, Process process) {
    OBCriteria<ProcessAccess> criteria = OBDal.getInstance().createCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_OBUIAPPPROCESS, process));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (ProcessAccess) criteria.uniqueResult();
  }

  @SuppressWarnings("unchecked")
  private java.util.List<ProcessAccess> findAllActiveObuiappProcessAccess(Role role,
      Process process) {
    OBCriteria<ProcessAccess> criteria = OBDal.getInstance().createCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_OBUIAPPPROCESS, process));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    return criteria.list();
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest*"`
Expected: FAIL — `ObuiappProcessAccessOverlapCorruptionGuard` does not exist yet, so the removal has no guard reacting to it; core's own natural, unguarded behavior may leave the row missing, duplicated, or wrongly-owned/leveled.

- [ ] **Step 3: Create `ObuiappProcessAccessOverlapCorruptionGuard.java`**

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
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.application.ProcessAccess;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.TransactionCompletedEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.client.application.Process;

import com.etendoerp.go.roles.overlap.ActiveTemplateInheritance;
import com.etendoerp.go.roles.overlap.GrantCandidate;
import com.etendoerp.go.roles.overlap.OverlapReconciliationCore;
import com.etendoerp.go.roles.overlap.OverlapWinner;
import com.etendoerp.go.roles.overlap.TemplateRemovalTracker;

/**
 * ETP-4830 item 7 (full-parity expansion) — extends {@link WindowAccessOverlapCorruptionGuard}'s
 * and {@link ProcessAccessOverlapCorruptionGuard}'s full trigger set to {@code
 * OBUIAPP_Process_Access}.
 *
 * <p><b>Why this guard uses the SAME repoint-in-place mechanism as {@link
 * ProcessAccessOverlapCorruptionGuard}, not a lighter cleanup-only sweep</b> — see the
 * ETP-4830 item 7 full-parity design doc's own "Why OBUIAPP_Process_Access's guard is the same
 * mechanism" section for the full rationale, summarized here: the {@code OBSecurityException}
 * ownership-corruption crash is triggered by ANY entity write with the wrong {@code client}/
 * {@code organization} — it does not depend on the table having a unique constraint. {@code
 * OBUIAPP_Process_Access} (confirmed via {@code modules_core/org.openbravo.client.application/
 * src-db/database/model/tables/OBUIAPP_PROCESS_ACCESS.xml}: only non-unique indexes) only differs
 * from {@code AD_Process_Access} on the ONE sub-case where 2+ competing {@code copyRoleAccess}
 * INSERTs in the REMOVE-path's un-flushed multi-template walk would crash (Process: yes, via
 * {@code AD_PROCESS_ACCESS_UN_KEY}) vs. silently duplicate (OBUIAPP: no unique constraint) — and
 * that sub-case is already handled identically for both via repoint-in-place, which prevents the
 * duplicate from ever being created, making the distinction moot in practice.
 */
public class ObuiappProcessAccessOverlapCorruptionGuard extends EntityPersistenceEventObserver {

  private static final Logger log =
      LogManager.getLogger(ObuiappProcessAccessOverlapCorruptionGuard.class);

  private static final int RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS = 1;

  private static Entity[] entities;

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[] {
          ModelProvider.getInstance().getEntity(ProcessAccess.ENTITY_NAME),
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

  /**
   * Mirrors {@code ProcessAccessOverlapCorruptionGuard#guardRemovedInheritance} exactly (which
   * itself mirrors {@code WindowAccessOverlapCorruptionGuard}'s own already-final sixth-trigger
   * fix) — see those classes' own javadoc for the full root-cause write-up.
   */
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
      for (ProcessAccess grant : findActiveObuiappProcessAccess(remainingTemplate)) {
        Process process = grant.getObuiappProcess();
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
      OBDal.getInstance().refresh(dependent);
    }
  }

  private boolean repointProcessIfNeeded(Role dependent, Process process, OverlapWinner winner,
      Role winnerRole) {
    ProcessAccess existing = findActiveObuiappProcessAccess(dependent, process);
    if (existing == null) {
      return false;
    }
    Role existingSource = existing.getInheritedFrom();
    boolean sourceCorrect = existingSource != null && sameId(existingSource, winnerRole);
    boolean levelCorrect =
        Boolean.valueOf(winner.isWinnerLevel()).equals(existing.isEditableField());
    if (sourceCorrect && levelCorrect) {
      return false;
    }
    repointInPlace(existing, process, winnerRole, winner.isWinnerLevel(), existingSource);
    return true;
  }

  /**
   * Bulk HQL UPDATE, same technique and reasoning as {@code
   * ProcessAccessOverlapCorruptionGuard#repointInPlace}/{@code WindowAccessOverlapCorruption
   * Guard#repointInPlace} — see those classes' own javadoc for the full nested-flush rationale.
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
        "Prevented cross-template OBUIAPP_Process_Access overlap corruption (multi-remaining-"
            + "template removal case): repointed role {} process {} in place from {} to {} "
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

  private ProcessAccess findActiveObuiappProcessAccess(Role role, Process process) {
    OBCriteria<ProcessAccess> criteria = crossClientCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_OBUIAPPPROCESS, process));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (ProcessAccess) criteria.uniqueResult();
  }

  @SuppressWarnings("unchecked")
  private List<ProcessAccess> findActiveObuiappProcessAccess(Role role) {
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

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest*"`
Expected: PASS.

- [ ] **Step 5: Run all prior regression suites (defense in depth)**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "com.etendoerp.go.roles.*"`
Expected: BUILD SUCCESSFUL (known `*TestSuite` noise aside).

- [ ] **Step 6: Commit**

```bash
cd /Users/gremiger/workspaces/etendogoclean/etendo/modules/com.etendoerp.go
git add src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuard.java \
        src-test/src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest.java
git commit -m "Feature ETP-4830: Guard OBUIAPP_Process_Access REMOVE-path"
```

---

### Task 6: `ObuiappProcessAccessOverlapCorruptionGuard` — `onSave` (ADD path)

**Files:**
- Modify: `src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuard.java`
- Modify: `src-test/src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest.java`

**Interfaces:**
- Consumes: `PropagationTrigger.NEW_GRANT` (Task 1).
- Produces: `void onSave(EntityNewEvent)`.

This task is the OBUIAPP mirror of Task 3 — same code shape, `org.openbravo.client.application.ProcessAccess` instead of `org.openbravo.model.ad.access.ProcessAccess`, `setObuiappProcess`/`PROPERTY_OBUIAPPPROCESS` instead of `setProcess`/`PROPERTY_PROCESS`.

- [ ] **Step 1: Write the failing tests**

Add these 3 methods to `ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest.java` (reuse the existing helpers from Task 5):

```java
  @Test
  public void testBystanderRoleNotPassedToAssignTemplateRolesIsAlsoProtected() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, OBUIAPP_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);

      grantObuiappProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantObuiappProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      ProcessAccess bystanderAccess = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(bystanderAccess);
      assertEquals(bystanderRole.getClient().getId(), bystanderAccess.getClient().getId());
      assertEquals(bystanderRole.getOrganization().getId(),
          bystanderAccess.getOrganization().getId());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testGainingReadOnlyTemplateInheritanceNeverDowngradesExistingFullAccess()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, OBUIAPP_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      grantObuiappProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantObuiappProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);

      ProcessAccess afterFinance = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(afterFinance);
      assertTrue(Boolean.TRUE.equals(afterFinance.isEditableField()));

      addInheritance(bystanderRole, salesTemplate, 20L);

      ProcessAccess afterSales = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(afterSales);
      assertEquals(bystanderRole.getClient().getId(), afterSales.getClient().getId());
      assertEquals(bystanderRole.getOrganization().getId(), afterSales.getOrganization().getId());
      assertTrue("Most-permissive-wins: gaining a READ-ONLY template must never downgrade "
          + "already-existing FULL access", Boolean.TRUE.equals(afterSales.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testRemovingTheTemplateThatJustifiedAWidenedAccessLevelCorrectlyDowngrades()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, OBUIAPP_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role financeTemplate = createThrowawaySystemTemplateRole();
      Role salesTemplate = createThrowawaySystemTemplateRole();
      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      grantObuiappProcessAccess(financeTemplate, sharedProcess, false);
      OBDal.getInstance().flush();
      grantObuiappProcessAccess(salesTemplate, sharedProcess, true);
      OBDal.getInstance().flush();

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, financeTemplate, 10L);
      addInheritance(bystanderRole, salesTemplate, 20L);

      ProcessAccess widened = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(widened);
      assertTrue(Boolean.TRUE.equals(widened.isEditableField()));
      assertEquals(financeTemplate.getId(),
          widened.getInheritedFrom() != null ? widened.getInheritedFrom().getId() : null);

      RoleInheritance financeInheritance = findInheritance(bystanderRole, financeTemplate);
      assertNotNull(financeInheritance);
      OBDal.getInstance().remove(financeInheritance);
      OBContext.setAdminMode();
      try {
        OBDal.getInstance().flush();
      } finally {
        OBContext.restorePreviousMode();
      }

      ProcessAccess afterRemoval = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(afterRemoval);
      assertEquals(salesTemplate.getId(),
          afterRemoval.getInheritedFrom() != null ? afterRemoval.getInheritedFrom().getId()
              : null);
      assertFalse(Boolean.TRUE.equals(afterRemoval.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest*"`
Expected: FAIL on the 3 new tests (no `onSave` handler exists yet). The Task 5 REMOVE-path test must still PASS.

- [ ] **Step 3: Implement `onSave` and its supporting methods**

In `src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuard.java`:

1. Add imports:
```java
import org.openbravo.base.model.Property;
import org.openbravo.client.kernel.event.EntityNewEvent;
import com.etendoerp.go.roles.overlap.PropagationTrigger;
```

2. Replace `resolveEntities()` — it already observes both entities from Task 5, no change needed here.

3. Add the `onSave` handler, right after `getObservedEntities()`:
```java
  public void onSave(@Observes @Priority(RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS)
      EntityNewEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Object target = event.getTargetInstance();
    if (target instanceof ProcessAccess) {
      ProcessAccess access = (ProcessAccess) target;
      Role role = access.getRole();
      if (role != null && Boolean.TRUE.equals(role.isTemplate())) {
        guardDependentsOf(access, PropagationTrigger.NEW_GRANT);
      } else {
        correctInheritedOwnership(event, access);
        widenInheritedAccessLevelIfNeeded(event, access);
      }
    } else if (target instanceof RoleInheritance) {
      guardNewInheritance((RoleInheritance) target);
    }
  }

  private void correctInheritedOwnership(EntityNewEvent event, ProcessAccess access) {
    if (access.getInheritedFrom() == null) {
      return;
    }
    Role owner = access.getRole();
    if (owner == null) {
      return;
    }
    Entity paEntity = obuiappProcessAccessEntity();
    Property clientProperty = paEntity.getProperty(ProcessAccess.PROPERTY_CLIENT);
    Property organizationProperty = paEntity.getProperty(ProcessAccess.PROPERTY_ORGANIZATION);

    boolean clientWrong = owner.getClient() != null
        && !sameId(owner.getClient(), event.getCurrentState(clientProperty));
    boolean organizationWrong = owner.getOrganization() != null
        && !sameId(owner.getOrganization(), event.getCurrentState(organizationProperty));
    if (!clientWrong && !organizationWrong) {
      return;
    }
    if (clientWrong) {
      event.setCurrentState(clientProperty, owner.getClient());
    }
    if (organizationWrong) {
      event.setCurrentState(organizationProperty, owner.getOrganization());
    }
    log.info(
        "Corrected OBUIAPP_Process_Access ownership on role {} process {}: pinned client/"
            + "organization back to the role's own",
        owner.getId(), access.getObuiappProcess() != null ? access.getObuiappProcess().getId()
            : null);
  }

  private static Entity obuiappProcessAccessEntity() {
    return ModelProvider.getInstance().getEntity(ProcessAccess.ENTITY_NAME);
  }

  private void widenInheritedAccessLevelIfNeeded(EntityNewEvent event, ProcessAccess access) {
    if (access.getInheritedFrom() == null) {
      return;
    }
    Role owner = access.getRole();
    Process process = access.getObuiappProcess();
    if (owner == null || process == null) {
      return;
    }
    Entity paEntity = obuiappProcessAccessEntity();
    Property editableFieldProperty = paEntity.getProperty(ProcessAccess.PROPERTY_EDITABLEFIELD);
    if (Boolean.TRUE.equals(event.getCurrentState(editableFieldProperty))) {
      return;
    }
    Role justifyingTemplate = findActiveTemplateGrantingFullAccess(owner, process);
    if (justifyingTemplate == null) {
      return;
    }
    event.setCurrentState(editableFieldProperty, true);
    Property inheritedFromProperty = paEntity.getProperty(ProcessAccess.PROPERTY_INHERITEDFROM);
    Role originalSource = access.getInheritedFrom();
    event.setCurrentState(inheritedFromProperty, justifyingTemplate);
    log.info(
        "Widened OBUIAPP_Process_Access on role {} process {} to full and repointed "
            + "InheritedFrom from {} to {}",
        owner.getId(), process.getId(), originalSource.getId(), justifyingTemplate.getId());
  }

  private Role findActiveTemplateGrantingFullAccess(Role dependent, Process process) {
    return findActiveTemplateGrantingFullAccess(dependent, process, null);
  }

  private Role findActiveTemplateGrantingFullAccess(Role dependent, Process process,
      Role excludedTemplate) {
    Map<String, Role> templatesById = new LinkedHashMap<>();
    List<GrantCandidate> candidates = new ArrayList<>();
    for (Role template : ActiveTemplateInheritance.findActiveTemplatesFor(dependent, null)) {
      templatesById.putIfAbsent(template.getId(), template);
      ProcessAccess templateAccess = findActiveObuiappProcessAccess(template, process);
      if (templateAccess != null) {
        candidates.add(new GrantCandidate(template.getId(),
            Boolean.TRUE.equals(templateAccess.isEditableField())));
      }
    }
    String excludedTemplateId = excludedTemplate != null ? excludedTemplate.getId() : null;
    String winnerId =
        OverlapReconciliationCore.findJustifyingFullGrant(candidates, excludedTemplateId);
    return winnerId != null ? templatesById.get(winnerId) : null;
  }

  private void guardNewInheritance(RoleInheritance inheritance) {
    Role dependent = inheritance.getRole();
    Role template = inheritance.getInheritFrom();
    if (dependent == null || template == null || !Boolean.TRUE.equals(template.isTemplate())) {
      return;
    }
    for (ProcessAccess templateGrant : findActiveObuiappProcessAccess(template)) {
      Process process = templateGrant.getObuiappProcess();
      if (process == null) {
        continue;
      }
      clearConflictingAccessUnconditionally(dependent, process, template);
    }
  }

  private void guardDependentsOf(ProcessAccess templateAccess, PropagationTrigger trigger) {
    Role role = templateAccess.getRole();
    if (role == null || !Boolean.TRUE.equals(role.isTemplate())) {
      return;
    }
    Process process = templateAccess.getObuiappProcess();
    if (process == null) {
      return;
    }
    if (trigger == PropagationTrigger.NEW_GRANT) {
      for (Role dependent : findActiveDependentRoles(role)) {
        clearConflictingAccessUnconditionally(dependent, process, role);
      }
    }
  }

  private void clearConflictingAccessUnconditionally(Role dependent, Process process,
      Role grantingTemplate) {
    ProcessAccess existing = findActiveObuiappProcessAccess(dependent, process);
    if (existing == null) {
      return;
    }
    deleteForcingCreatePath(existing, dependent, process, grantingTemplate,
        existing.getInheritedFrom());
  }

  private void deleteForcingCreatePath(ProcessAccess existing, Role dependent, Process process,
      Role template, Role previousSource) {
    OBContext.setAdminMode(false);
    try {
      OBDal.getInstance().getSession()
          .createQuery("delete from " + ProcessAccess.ENTITY_NAME + " where id = :id")
          .setParameter("id", existing.getId())
          .executeUpdate();
    } finally {
      OBContext.restorePreviousMode();
    }
    dependent.getOBUIAPPProcessAccessList().remove(existing);
    OBDal.getInstance().refresh(dependent);
    OBDal.getInstance().getSession().evict(existing);
    log.info(
        "Prevented cross-template OBUIAPP_Process_Access overlap corruption: cleared role {} "
            + "process {} access (previously {}) before template {}'s own grant propagates",
        dependent.getId(), process.getId(),
        previousSource != null ? "inherited from " + previousSource.getId() : "manually granted",
        template.getId());
  }

  @SuppressWarnings("unchecked")
  private List<Role> findActiveDependentRoles(Role template) {
    OBCriteria<RoleInheritance> criteria = crossClientCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_INHERITFROM, template));
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ACTIVE, true));
    List<Role> dependents = new ArrayList<>();
    java.util.Set<String> seenRoleIds = new java.util.LinkedHashSet<>();
    for (RoleInheritance inheritance : (List<RoleInheritance>) criteria.list()) {
      Role dependent = inheritance.getRole();
      if (dependent != null && seenRoleIds.add(dependent.getId())) {
        dependents.add(dependent);
      }
    }
    return dependents;
  }
```

Note: `Role.getOBUIAPPProcessAccessList()` (confirmed at `src-gen/org/openbravo/model/ad/access/Role.java:996`) is the OBUIAPP equivalent of `getADProcessAccessList()`/`getADWindowAccessList()`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest*"`
Expected: PASS, all 4 tests.

- [ ] **Step 5: Run all prior regression suites**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "com.etendoerp.go.roles.*"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /Users/gremiger/workspaces/etendogoclean/etendo/modules/com.etendoerp.go
git add src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuard.java \
        src-test/src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest.java
git commit -m "Feature ETP-4830: Guard OBUIAPP_Process_Access ADD-path ownership"
```

---

### Task 7: `ObuiappProcessAccessOverlapCorruptionGuard` — `onUpdate` (UPDATE path)

**Files:**
- Modify: `src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuard.java`
- Modify: `src-test/src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest.java`

**Interfaces:**
- Consumes: `PropagationTrigger.UPDATED_GRANT` (Task 1).
- Produces: `void onUpdate(EntityUpdateEvent)`. This is the LAST task in this plan.

- [ ] **Step 1: Write the failing tests**

Add these 2 methods and 1 helper to `ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest.java`:

```java
  @Test
  public void testUpdatingTemplatesOwnAccessLevelNeverDeletesAnAlreadyCorrectlySourcedDependentRow()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, OBUIAPP_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role template = createThrowawaySystemTemplateRole();
      grantObuiappProcessAccess(template, sharedProcess, true);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, template, 10L);

      ProcessAccess beforeUpdate = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(beforeUpdate);
      assertEquals(template.getId(),
          beforeUpdate.getInheritedFrom() != null ? beforeUpdate.getInheritedFrom().getId()
              : null);
      assertFalse(Boolean.TRUE.equals(beforeUpdate.isEditableField()));

      ProcessAccess templateAccess = findObuiappProcessAccess(template, sharedProcess);
      assertNotNull(templateAccess);
      updateObuiappProcessAccessLevel(templateAccess, false);

      ProcessAccess afterUpdate = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull("The dependent's row must survive a routine UPDATE to the template's own "
          + "access level", afterUpdate);
      assertEquals(bystanderRole.getClient().getId(), afterUpdate.getClient().getId());
      assertEquals(bystanderRole.getOrganization().getId(),
          afterUpdate.getOrganization().getId());
      assertEquals(template.getId(),
          afterUpdate.getInheritedFrom() != null ? afterUpdate.getInheritedFrom().getId() : null);
      assertTrue(Boolean.TRUE.equals(afterUpdate.isEditableField()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void testDowngradingOneOfTwoOverlappingTemplatesNeverDowngradesDependentWhenTheOtherStillGrantsFullAccess()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Process sharedProcess = OBDal.getInstance().get(Process.class, OBUIAPP_PROCESS_ID);
      assertNotNull(sharedProcess);

      Role templateA = createThrowawaySystemTemplateRole();
      Role templateB = createThrowawaySystemTemplateRole();
      grantObuiappProcessAccess(templateA, sharedProcess, false);
      grantObuiappProcessAccess(templateB, sharedProcess, false);
      OBDal.getInstance().flush();

      User user = OBDal.getInstance().get(User.class, TEST_USER_ID);
      assertNotNull(user);

      Role bystanderRole = createBystanderRole(user);
      addInheritance(bystanderRole, templateA, 10L);
      addInheritance(bystanderRole, templateB, 20L);

      ProcessAccess beforeUpdate = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(beforeUpdate);
      assertTrue(Boolean.TRUE.equals(beforeUpdate.isEditableField()));
      assertEquals(templateB.getId(),
          beforeUpdate.getInheritedFrom() != null ? beforeUpdate.getInheritedFrom().getId()
              : null);

      ProcessAccess templateBAccess = findObuiappProcessAccess(templateB, sharedProcess);
      assertNotNull(templateBAccess);
      updateObuiappProcessAccessLevel(templateBAccess, true);

      ProcessAccess afterUpdate = findObuiappProcessAccess(bystanderRole, sharedProcess);
      assertNotNull(afterUpdate);
      assertTrue("MOST-PERMISSIVE-WINS: dependent must stay full — templateA still grants full",
          Boolean.TRUE.equals(afterUpdate.isEditableField()));
      assertEquals(templateA.getId(),
          afterUpdate.getInheritedFrom() != null ? afterUpdate.getInheritedFrom().getId() : null);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void updateObuiappProcessAccessLevel(ProcessAccess access, boolean readOnly) {
    OBContext.setAdminMode();
    try {
      access.setEditableField(!readOnly);
      OBDal.getInstance().save(access);
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest*"`
Expected: FAIL on the 2 new tests. The 4 previously-passing tests must still PASS.

- [ ] **Step 3: Implement `onUpdate` and its supporting method**

In `src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuard.java`:

1. Add import: `import org.openbravo.client.kernel.event.EntityUpdateEvent;`

2. Add the `onUpdate` handler, right after `onSave`:
```java
  public void onUpdate(@Observes @Priority(RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS)
      EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Object target = event.getTargetInstance();
    if (target instanceof ProcessAccess) {
      guardDependentsOf((ProcessAccess) target, PropagationTrigger.UPDATED_GRANT);
    }
  }
```

3. Extend `guardDependentsOf` (from Task 6) with the `UPDATED_GRANT` branch:
```java
  private void guardDependentsOf(ProcessAccess templateAccess, PropagationTrigger trigger) {
    Role role = templateAccess.getRole();
    if (role == null || !Boolean.TRUE.equals(role.isTemplate())) {
      return;
    }
    Process process = templateAccess.getObuiappProcess();
    if (process == null) {
      return;
    }
    for (Role dependent : findActiveDependentRoles(role)) {
      if (trigger == PropagationTrigger.NEW_GRANT) {
        clearConflictingAccessUnconditionally(dependent, process, role);
      } else {
        repointIfAlreadySourcedFromTemplate(dependent, process, role, templateAccess);
      }
    }
  }
```

4. Add `repointIfAlreadySourcedFromTemplate`:
```java
  private void repointIfAlreadySourcedFromTemplate(Role dependent, Process process,
      Role grantingTemplate, ProcessAccess templateAccess) {
    ProcessAccess existing = findActiveObuiappProcessAccess(dependent, process);
    if (existing == null) {
      return;
    }
    Role existingSource = existing.getInheritedFrom();
    if (existingSource == null || !sameId(existingSource, grantingTemplate)) {
      return;
    }
    boolean grantingTemplateNewLevel = Boolean.TRUE.equals(templateAccess.isEditableField());
    Role otherJustifyingTemplate =
        grantingTemplateNewLevel ? null
            : findActiveTemplateGrantingFullAccess(dependent, process, grantingTemplate);

    boolean finalLevel = grantingTemplateNewLevel || otherJustifyingTemplate != null;
    Role winner = otherJustifyingTemplate != null ? otherJustifyingTemplate : grantingTemplate;

    boolean sourceCorrect = sameId(existingSource, winner);
    boolean levelCorrect = Boolean.valueOf(finalLevel).equals(existing.isEditableField());
    if (sourceCorrect && levelCorrect) {
      return;
    }
    repointInPlace(existing, process, winner, finalLevel, existingSource);
  }
```

Note: `repointInPlace(ProcessAccess, Process, Role, boolean, Role)` already exists in this file from Task 5 — reused here unchanged.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "*ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest*"`
Expected: PASS, all 6 tests.

- [ ] **Step 5: Run the FULL package regression suite (final check for this plan)**

Run: `cd /Users/gremiger/workspaces/etendogoclean/etendo && ./gradlew test --tests "com.etendoerp.go.roles.*"`
Expected: BUILD SUCCESSFUL. Every test in the package green (known `*TestSuite` noise aside): `OverlapReconciliationCoreTest` (11 tests), `TemplateRemovalTrackerTest`, `UserRoleCompositionServiceOverlapIntegrationTest` (unchanged), `UserRoleCompositionServiceOverlapReverificationTest` (unchanged), `ProcessAccessOverlapCorruptionGuardIntegrationTest` (6 tests), `ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest` (6 tests), plus every other pre-existing test in the package.

- [ ] **Step 6: Commit**

```bash
cd /Users/gremiger/workspaces/etendogoclean/etendo/modules/com.etendoerp.go
git add src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuard.java \
        src-test/src/com/etendoerp/go/roles/ObuiappProcessAccessOverlapCorruptionGuardIntegrationTest.java
git commit -m "Feature ETP-4830: Guard OBUIAPP_Process_Access UPDATE-path ownership"
```

---

## After all 7 tasks land

1. Update `santo_4830_roles.md`'s item 7/7a section (in the sibling `etendo_schema_forge` repo) to reflect the full-parity expansion is closed, with the 7 tasks' commits, mirroring how every other closed item in that file is written up.
2. Update `modules/com.etendoerp.go/docs/neo-headless.md` if either guard's existence changes anything a reader of that doc needs to know about process/report access behavior — per this repo's own Documentation Freshness policy.
3. This remains local-only, not pushed — pushing and opening the PR is a separate, explicit step the human does themselves.
