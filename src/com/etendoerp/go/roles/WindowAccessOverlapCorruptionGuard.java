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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Priority;
import javax.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

/**
 * ETP-4906 (Task B6, REDESIGNED 2026-08-16) — widens ETP-4852's cross-template
 * {@code AD_Window_Access} overlap-corruption fix beyond the single, actively-composed role
 * {@link UserRoleCompositionService} manages in one {@code assignTemplateRoles} call. See that
 * class's own class javadoc for the full root-cause write-up; summarized here only as far as
 * needed to explain why THIS class exists and why it works this way.
 *
 * <p><b>This is the SECOND design for this class — the first (a reactive, correction-based
 * {@code EntityPersistenceEventObserver} that tried to fix a corrupted row's client/organization
 * AFTER Hibernate decided to write it, via {@code EntityPersistenceEvent#setCurrentState}) does
 * NOT work, confirmed both by reading core source and by a live reproduction against a real
 * running Etendo instance (see ETP-4906's plan doc, "B6 Findings — Root Cause"). Two structural
 * reasons, traced into {@code OBInterceptor}/{@code SecurityChecker}:</b>
 * <ol>
 *   <li>{@code SecurityChecker.checkWriteAccess(Object)} reads the client id via a LIVE getter
 *   call on the actual Java entity instance ({@code ((ClientEnabled) obj).getClient().getId()})
 *   — never Hibernate's {@code currentState[]} dirty-check array a {@code setCurrentState} call
 *   corrects. Correcting {@code currentState[]} therefore never changes what the check reads.</li>
 *   <li>Independently fatal even if (1) were fixed: {@code OBInterceptor.onFlushDirty()}/{@code
 *   onSave()} both call {@code doEvent()} — which runs {@code checkWriteAccess()} — BEFORE they
 *   ever invoke the CDI listener chain an {@code EntityPersistenceEventObserver} hooks into. The
 *   security check always runs before any observer gets a chance to react, for both the UPDATE
 *   and CREATE paths — a reactive "correct after the write starts" observer cannot win that
 *   race, structurally, no matter what it corrects or how.</li>
 * </ol>
 *
 * <p><b>This design is a PREVENTION strategy instead — the only kind proven to work</b> (it is
 * exactly what {@link UserRoleCompositionService#preventWindowAccessOverlapCorruption(Role,
 * Role)} already does for the ONE role it actively manages): proactively DELETE a dependent
 * role's conflicting {@code AD_Window_Access} row BEFORE core's propagation ever tries to UPDATE
 * it, forcing core onto the safe CREATE path instead (per {@code SecurityChecker.checkWriteAccess}
 * itself: a brand-new row saved under {@code copyRoleAccess}'s own local {@code
 * OBContext.setAdminMode(false)}/{@code (true)} bypass sequencing never observes the corrupted
 * client at all, because the CREATE path's own security check runs while that bypass — or lack of
 * one — is still consistent, unlike the UPDATE path's check, which fires at a LATER flush after
 * any protective bypass has already been popped).
 *
 * <p><b>The gap this closes, beyond {@code UserRoleCompositionService}'s own two helpers.</b>
 * {@code preventWindowAccessOverlapCorruption}/{@code reconcileWindowAccessAfterComposition} only
 * ever look at the ONE {@code personalRole} passed into a given {@code assignTemplateRoles} call.
 * Core's own propagation (({@code RoleInheritanceManager#propagateNewAccess}, triggered by {@code
 * InheritedAccessEnabledEventHandler#onSave} whenever a NEW {@code AD_Window_Access} row is
 * granted directly on a template role), however, is NOT scoped to that one role — it iterates
 * EVERY role whose {@code AD_Role_Inheritance} points {@code InheritFrom} at whichever template
 * just gained the new grant. So any OTHER already-existing role that also inherits from that
 * template — reachable from ANY entry point, including a raw Etendo Classic UI edit to the
 * template's own Window Access tab, with ZERO {@code UserRoleCompositionService} code in the call
 * stack (live-reproduced, see the plan doc) — gets swept into the exact same corrupting write,
 * with zero protection from those two role-scoped helpers.
 *
 * <p><b>Why an event observer instead of widening the two service-layer helpers.</b> The
 * corruption is triggered by a write to the TEMPLATE's own {@code AD_Window_Access} — a write that
 * may never go through {@code assignTemplateRoles} (or any code in this module) at all. The only
 * mechanism that can defend every entry point uniformly is one that watches the TEMPLATE side's
 * own persistence events directly — the same extension point {@code ContactNameSyncHandler}
 * already uses elsewhere in this module. No core patch: a plain module-level {@link
 * EntityPersistenceEventObserver}.
 *
 * <p><b>Why {@code @Priority} matters here.</b> Core's own propagation trigger, {@code
 * InheritedAccessEnabledEventHandler#onSave}/{@code #onUpdate}, is ALSO a CDI observer of the
 * exact same {@code EntityNewEvent}/{@code EntityUpdateEvent} this class observes (both fire
 * through {@code PersistenceEventOBInterceptor}, which fans a single Hibernate interceptor
 * callback out to every registered {@code EntityPersistenceEventObserver} via a synchronous CDI
 * event). This class MUST run its own delete-before-write BEFORE core's handler starts
 * propagating, or the race is lost. There is no earlier module-level extension point available
 * (core's {@code preFlush}/{@code postFlush} interceptor hooks are never forwarded to CDI
 * observers — see {@code PersistenceEventOBInterceptor}, which only fires {@code EntityNewEvent}/
 * {@code EntityUpdateEvent}/{@code EntityDeleteEvent}/{@code TransactionBeginEvent}/{@code
 * TransactionCompletedEvent}), so ordering the SAME event via {@code @Priority} (CDI 2.0 spec
 * §10.4.2 — observers carrying a priority are notified, in ascending priority order, before any
 * unprioritized observer; Weld 3.1, in use here, implements this) is the only lever available.
 * {@code InheritedAccessEnabledEventHandler} declares no priority, so any priority value on this
 * class's observer methods is enough to guarantee it runs first.
 *
 * <p><b>What it actually does</b> (mirrors {@code preventWindowAccessOverlapCorruption}'s own
 * proven mechanism, generalized). Observes save AND update events for {@code AD_Window_Access}.
 * Whenever the row belongs to a TEMPLATE role (the exact trigger {@code
 * InheritedAccessEnabledEventHandler} itself checks for before propagating): for every OTHER role
 * currently, actively inheriting from that template, looks up that role's own active {@code
 * AD_Window_Access} row for the SAME window; if one exists and is not ALREADY sourced from this
 * same template (i.e. it is either manually granted or inherited from a DIFFERENT template — the
 * exact precondition core's own {@code RoleInheritanceManager#handleAccess}/{@code isPrecedent}
 * treats as "needs overriding", which is what routes it onto the corrupting UPDATE path), deletes
 * it — under the SAME {@code OBContext.setAdminMode(false)} bypass core's own {@code
 * deleteRoleAccess} uses for removing a cross-client-owned inherited row — BEFORE control returns
 * to core's own propagation logic. With no existing row left to find, core's {@code handleAccess}
 * takes the safe CREATE path for that dependent role and window, exactly as it already does for
 * the one row {@code preventWindowAccessOverlapCorruption} defends.
 *
 * <p>Deliberately narrow: only reacts when the SAVED/UPDATED row's OWNING role is itself a
 * template (the one signal core's own event handler uses to decide whether to propagate at all —
 * see {@code InheritedAccessEnabledEventHandler#doAction}), and only ever deletes an OTHER role's
 * row for the SAME window as the template row that triggered the event — never touches the
 * template's own row, never widens/narrows any grant level, never runs at all for a template-owned
 * row's grant LEVEL change alone. {@code UserRoleCompositionService#reconcileWindowAccessAfterComposition}
 * remains the most-permissive-wins union authority for the role it is actively composing; this
 * class's only job is making sure core never gets the chance to corrupt a BYSTANDER role's
 * ownership fields in the first place.
 *
 * <p><b>A second, symmetric trigger: a role gaining a brand-new {@code AD_Role_Inheritance} from
 * an already-overlapping template.</b> Empirically found while re-verifying this redesign (not
 * merely theorized): this environment's REAL Finance/Sales system templates now genuinely overlap
 * on a real window (drifted since ETP-4852/ETP-4878 was written — both templates currently grant
 * window {@code 143}), so simply adding both real templates' {@code AD_Role_Inheritance} rows to
 * ANY role that never goes through {@code assignTemplateRoles} — exactly what a bystander-role
 * test (or a raw Classic UI "add inheritance" edit) does — ALSO drives core's {@code
 * RoleInheritanceEventHandler#onSave} → {@code RoleInheritanceManager#applyNewInheritance} into
 * the identical corrupting-UPDATE risk this class defends against on the {@code AD_Window_Access}
 * side, just via a different entity. This is the SAME underlying core bug (undifferentiated
 * {@code getSkippedProperties()}), reachable through a second door — so this class also observes
 * {@code RoleInheritance} save events (there is no meaningful update path to guard: core's own
 * {@code RoleInheritanceEventHandler#onUpdate} unconditionally rejects editing an existing
 * inheritance row) and applies the exact same delete-before-write logic, scoped to the ONE role
 * gaining the ONE new inheritance, before core's own (also unprioritized) handler propagates the
 * newly-inherited template's entire grant set onto it.
 */
public class WindowAccessOverlapCorruptionGuard extends EntityPersistenceEventObserver {

  private static final Logger log = LogManager.getLogger(WindowAccessOverlapCorruptionGuard.class);

  /**
   * Any priority value makes Weld notify this observer before {@code
   * InheritedAccessEnabledEventHandler}, which declares none — see the class javadoc's
   * "Why {@code @Priority} matters here" section. The literal value is otherwise arbitrary.
   */
  private static final int RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS = 1;

  private static Entity[] entities;

  private static Entity[] resolveEntities() {
    if (entities == null) {
      entities = new Entity[] {
          ModelProvider.getInstance().getEntity(WindowAccess.ENTITY_NAME),
          ModelProvider.getInstance().getEntity(RoleInheritance.ENTITY_NAME) };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

  /**
   * Defends BOTH corrupting triggers on the SAVE path — see the class javadoc's two mechanism
   * sections. A NEW {@code AD_Window_Access} row on a template ({@code propagateNewAccess}) and a
   * NEW {@code AD_Role_Inheritance} row on any role ({@code applyNewInheritance}) are the two
   * places core's own propagation can start a corrupting UPDATE against a role this class never
   * even knows is at risk; both are guarded here, before core's own (unprioritized) handlers run.
   */
  public void onSave(@Observes @Priority(RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS)
      EntityNewEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Object target = event.getTargetInstance();
    if (target instanceof WindowAccess) {
      WindowAccess access = (WindowAccess) target;
      Role role = access.getRole();
      if (role != null && Boolean.TRUE.equals(role.isTemplate())) {
        guardDependentsOf(access);
      } else {
        correctInheritedOwnership(event, access);
      }
    } else if (target instanceof RoleInheritance) {
      guardNewInheritance((RoleInheritance) target);
    }
  }

  /**
   * Defends the {@code propagateUpdatedAccess} trigger too — belt-and-braces. Core's own {@code
   * findInheritedAccess} scopes updates to rows already sourced from the SAME template, so this
   * path is not the corruption vector the live repro exercised, but guarding it costs nothing and
   * keeps both persistence-event entry points for a template's own row uniformly covered. {@code
   * RoleInheritance} has no meaningful update path to guard — core's own {@code
   * RoleInheritanceEventHandler#onUpdate} unconditionally rejects it.
   */
  public void onUpdate(@Observes @Priority(RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS)
      EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Object target = event.getTargetInstance();
    if (target instanceof WindowAccess) {
      guardDependentsOf((WindowAccess) target);
    }
  }

  /**
   * Corrects {@code access}'s {@code client}/{@code organization} to match its OWNING role's own
   * values, for a freshly-{@code onSave}d row that is NOT a template's own (i.e. a dependent
   * role's newly-propagated, {@code inheritedFrom != null} row — {@code copyRoleAccess}'s CREATE
   * output). Generalizes {@code UserRoleCompositionService#reconcileWindowAccessAfterComposition}'s
   * OWN ownership-pinning (currently scoped to the ONE role that class is actively composing) to
   * EVERY dependent role's freshly-created row, matching the ticket's own invariant: "client/
   * organization must always match the role's own, never a template's" — verified live: without
   * this, {@code copyRoleAccess} (see {@code RoleInheritanceManager}) copies EVERY field from the
   * template's own access row via {@code DalUtil.copy}, {@code client}/{@code organization}
   * included, and only ever corrects {@code role} afterward via {@code AccessTypeInjector#setParent}
   * — so a bystander role's brand-new inherited row silently ends up owned by the TEMPLATE's own
   * client (typically system client {@code "0"}) unless something fixes it, exactly like the row
   * {@link #deleteForcingCreatePath} just cleared away.
   *
   * <p><b>Why this MUST use {@code event.setCurrentState(Property, Object)}, not a plain {@code
   * access.setClient(...)} setter call — confirmed empirically, the hard way.</b> A first attempt
   * here called the entity's own setters directly, reasoning (correctly) that {@code
   * OBInterceptor}'s security check for a NEW entity fires synchronously, before this observer, so
   * there was no check left to out-race. That reasoning was right but incomplete: {@code
   * PersistenceEventOBInterceptor#sendNewEvent} builds {@code EntityNewEvent} from the {@code
   * Object[] state} array Hibernate itself already extracted from the entity BEFORE dispatching to
   * this listener chain — the eventual INSERT's bound values come from THAT array, not from
   * re-reading the entity's fields at execution time. A plain setter call only mutates the JAVA
   * OBJECT; it never touches Hibernate's own already-captured {@code state[]}, so the row still
   * got physically inserted with the template's own client/organization regardless of the setter
   * call (reproduced live: the "Corrected..." log line fired, confirming the setter WAS called,
   * yet the persisted row's client was still the template's). {@code
   * EntityPersistenceEvent#setCurrentState(Property, Object)} is the API specifically designed to
   * reach the bound values instead (see its own javadoc) — the SAME mechanism the class's first,
   * abandoned design already used correctly for this exact reason, just never generalized past the
   * one role it was reactively (and, for THAT use case, ineffectively) trying to fix.
   */
  private void correctInheritedOwnership(EntityNewEvent event, WindowAccess access) {
    if (access.getInheritedFrom() == null) {
      // Manually-granted row, never template-derived — ownership is whatever the grantor set;
      // not this class's business.
      return;
    }
    Role owner = access.getRole();
    if (owner == null) {
      return;
    }
    Entity waEntity = windowAccessEntity();
    Property clientProperty = waEntity.getProperty(WindowAccess.PROPERTY_CLIENT);
    Property organizationProperty = waEntity.getProperty(WindowAccess.PROPERTY_ORGANIZATION);

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
        "Corrected AD_Window_Access ownership on role {} window {}: pinned client/organization "
            + "back to the role's own (template-derived row, inherited from {})",
        owner.getId(), access.getWindow() != null ? access.getWindow().getId() : null,
        access.getInheritedFrom().getId());
  }

  private static Entity windowAccessEntity() {
    return ModelProvider.getInstance().getEntity(WindowAccess.ENTITY_NAME);
  }

  /**
   * If {@code inheritance} points a role at a template, proactively clears that role's OWN
   * conflicting active access for every window the NEW template also grants — the same mechanism
   * as {@link UserRoleCompositionService#preventWindowAccessOverlapCorruption(Role, Role)}, just
   * running for ANY role gaining ANY new inheritance, not only ones routed through {@code
   * assignTemplateRoles}. See the class javadoc's "A second, symmetric trigger" section.
   */
  private void guardNewInheritance(RoleInheritance inheritance) {
    Role dependent = inheritance.getRole();
    Role template = inheritance.getInheritFrom();
    if (dependent == null || template == null || !Boolean.TRUE.equals(template.isTemplate())) {
      return;
    }
    for (WindowAccess templateGrant : findActiveWindowAccess(template)) {
      Window window = templateGrant.getWindow();
      if (window == null) {
        continue;
      }
      WindowAccess existing = findActiveWindowAccess(dependent, window);
      if (existing == null) {
        continue;
      }
      Role existingSource = existing.getInheritedFrom();
      if (existingSource != null && sameId(existingSource, template)) {
        continue;
      }
      deleteForcingCreatePath(existing, dependent, window, template, existingSource);
    }
  }

  /**
   * If {@code templateAccess} belongs to a template role, proactively clears every OTHER role's
   * conflicting active access for the same window BEFORE returning control to core's own
   * propagation (which fires next, via the SAME CDI event, per this class's {@code @Priority}).
   */
  private void guardDependentsOf(WindowAccess templateAccess) {
    Role role = templateAccess.getRole();
    if (role == null || !Boolean.TRUE.equals(role.isTemplate())) {
      // Not a template's own row — nothing to propagate, core will not react to this one either.
      return;
    }
    Window window = templateAccess.getWindow();
    if (window == null) {
      return;
    }
    for (Role dependent : findActiveDependentRoles(role)) {
      WindowAccess existing = findActiveWindowAccess(dependent, window);
      if (existing == null) {
        // No conflicting row at all — core will safely CREATE one, nothing to prevent.
        continue;
      }
      Role existingSource = existing.getInheritedFrom();
      if (existingSource != null && sameId(existingSource, role)) {
        // Already correctly sourced from THIS SAME template — a normal, non-corrupting
        // re-propagation (core's own updateRoleAccess only ever touches same-source rows here).
        continue;
      }
      deleteForcingCreatePath(existing, dependent, window, role, existingSource);
    }
  }

  /**
   * Removes {@code existing} — the dependent role's OWN conflicting row — so core's subsequent
   * {@code handleAccess}/{@code findInheritedAccess} lookup for (role={@code dependent}, window=
   * {@code window}) finds nothing and takes the CREATE path instead of the corrupting UPDATE path.
   * Same GOAL as {@code UserRoleCompositionService#preventWindowAccessOverlapCorruption}, but a
   * DIFFERENT mechanism was required to reach it from this nested position — see below.
   *
   * <p><b>Why this is a direct bulk HQL {@code DELETE}, not {@code OBDal.remove()} +
   * {@code OBDal.flush()} (what {@code preventWindowAccessOverlapCorruption} itself safely does).
   * </b> This method runs NESTED inside a Hibernate {@code Session.flush()} that is already in
   * progress (this class's {@code onSave}/{@code onUpdate} fire from mid-flush — see the class
   * javadoc), and Openbravo's DAL layer sets every session's flush mode to {@code
   * FlushMode.COMMIT} (see {@code SessionHandler#setDefaultFlushMode}/its call site) — meaning
   * queries NEVER auto-flush pending entity-level changes, unlike the {@code FlushMode.AUTO}
   * default most Hibernate apps run under. Two consequences, both confirmed empirically while
   * verifying this redesign:
   * <ol>
   *   <li>{@code OBDal.remove(existing)} WITHOUT an explicit {@code flush()} is invisible to
   *   core's subsequent {@code findAccess}/{@code getAccessList} HQL queries — {@code
   *   FlushMode.COMMIT} means nothing auto-flushes before them. Core's query still finds the
   *   "existing" row, so {@code handleAccess} never reaches the CREATE path at all — worse, when
   *   the OUTER flush eventually executes OUR scheduled delete, the row is gone with NOTHING core
   *   ever created to replace it (reproduced live: every window this method needed to clear ended
   *   up simply absent from the dependent role afterward, confirmed against the actual DB rows).
   *   </li>
   *   <li>Calling {@code OBDal.flush()} explicitly from here to force visibility is a REENTRANT
   *   {@code Session.flush()} call — one flush() invoked from inside another, still-in-progress
   *   one on the SAME session. This corrupts the OUTER flush's own in-progress action-queue
   *   bookkeeping: reproduced live as a {@code StaleStateException} ("actual row count: 0;
   *   expected: 1") on an UPDATE for a row that demonstrably still existed moments earlier in the
   *   very same flush cycle.</li>
   * </ol>
   * A direct {@code session.createQuery("delete from " + WindowAccess.ENTITY_NAME + " ...")
   * .executeUpdate()} sidesteps BOTH: a bulk HQL DML statement is executed as a single SQL
   * statement immediately, on the current connection/transaction — it does not go through the
   * session's flush/action-queue machinery at all (no {@code EntityDeleteEvent} fires either, so
   * core's own {@code InheritedAccessEnabledEventHandler}'s "NotDeleteInheritedAccess" delete
   * check — which exists to protect exactly this {@code inheritedFrom != null} case from a
   * NORMAL entity-level delete — never runs, and does not need to: we WANT this specific case
   * deleted regardless of {@code inheritedFrom}). The row is gone from the DB immediately,
   * visible to any subsequent SELECT on the same transaction (including core's), with no
   * reentrant flush anywhere.
   *
   * <p><b>Also refreshes {@code dependent} (the OWNING role) — {@code OBDal.refresh}, NOT {@code
   * evict}.</b> {@code dependent.getADWindowAccessList()} is frequently ALREADY loaded and cached
   * in this session by the time this method runs — typically because an EARLIER, separate
   * top-level flush already force-initialized it (core's own {@code
   * WindowAccessInjector#setParent} calls {@code role.getADWindowAccessList().add(...)} for every
   * row it ever creates, which lazily loads the collection the first time). That cached Java list
   * still holds a reference to {@code existing} even after the bulk delete above — a raw SQL
   * statement does not know or care about Hibernate's separate collection-snapshot bookkeeping.
   * THREE outcomes were tried empirically here, in order:
   * <ol>
   *   <li>Leave the stale reference alone: the next time core touches this SAME collection (e.g.
   *   appending its own newly-created replacement row for a different, non-conflicting window),
   *   Hibernate re-examines the whole collection and finds a member with no valid snapshot —
   *   {@code OBInterceptor} logs "detected as not new... but it does not have a current state in
   *   the database" and still schedules an UPDATE for it, which then fails with {@code
   *   StaleStateException} ("actual row count: 0") once the row's absence surfaces at SQL
   *   execution time.</li>
   *   <li>Remove {@code existing} from the collection explicitly: Hibernate's own orphan-removal
   *   cascade (this collection mapping cascades deletes) detects the missing element against its
   *   loaded snapshot and schedules its OWN {@code session.delete()} for it — which DOES run
   *   through {@code OBInterceptor.onDelete}/{@code SecurityChecker.checkDeleteAllowed},
   *   reproduced live as the exact {@code OBSecurityException} this whole class exists to prevent
   *   in the first place, just relocated from an update to a delete.</li>
   *   <li>Fully {@code evict(dependent)} (detach the whole entity, not just the collection):
   *   avoids both of the above, but ALSO strips {@code dependent} of its live Hibernate session —
   *   the very next time core's {@code WindowAccessInjector#setParent} calls {@code
   *   role.getADWindowAccessList().add(...)} to register ITS OWN newly-created replacement row,
   *   the (now fully detached) collection cannot lazily re-initialize itself at all, reproduced
   *   live as {@code LazyInitializationException}: "could not initialize proxy - no Session".</li>
   * </ol>
   * {@code OBDal.refresh(dependent)} is the one operation that does what is actually needed:
   * {@code dependent} stays ATTACHED/managed (so a subsequent lazy collection access still has a
   * live session to reload through — no {@code LazyInitializationException}), while its cached
   * collection snapshot is discarded and will be re-fetched from the database on next access —
   * correctly excluding {@code existing}, which the bulk delete above already removed there, with
   * no stale reference left for Hibernate to misinterpret as a pending update OR an orphan needing
   * its own cascade delete. This does not interfere with core's own CREATE for the replacement row
   * either way — {@code copyRoleAccess} persists the new {@code WindowAccess} via a DIRECT {@code
   * OBDal.save(newAccess)} call, never via cascade from the parent collection; {@code
   * WindowAccessInjector#setParent}'s {@code .add(...)} call is only ever a convenience for
   * keeping the in-memory list accurate for the REST of this same request, not what actually
   * persists the row.
   */
  private void deleteForcingCreatePath(WindowAccess existing, Role dependent, Window window,
      Role template, Role previousSource) {
    OBContext.setAdminMode(false);
    try {
      OBDal.getInstance().getSession()
          .createQuery("delete from " + WindowAccess.ENTITY_NAME + " where id = :id")
          .setParameter("id", existing.getId())
          .executeUpdate();
    } finally {
      OBContext.restorePreviousMode();
    }
    dependent.getADWindowAccessList().remove(existing);
    OBDal.getInstance().refresh(dependent);
    OBDal.getInstance().getSession().evict(existing);
    log.info(
        "Prevented cross-template AD_Window_Access overlap corruption: cleared role {} window {} "
            + "access (previously {}) before template {}'s own grant propagates, forcing core "
            + "onto the safe CREATE path",
        dependent.getId(), window.getId(),
        previousSource != null ? "inherited from " + previousSource.getId() : "manually granted",
        template.getId());
  }

  /**
   * Disables {@code OBCriteria}'s implicit client/organization filtering — REQUIRED for every
   * query in this class. {@code OBCriteria#initialize()} adds a {@code Restrictions.in(...
   * readableClients/readableOrganizations)} filter UNCONDITIONALLY, regardless of {@code
   * OBContext.isInAdministratorMode()} (only the separate {@code checkReadable} ACCESS check is
   * admin-mode-gated — the row-level filter itself is not). A template role is typically system
   * client {@code "0"} while its dependents are real tenant clients, so without this every query
   * here would silently return zero rows whenever the ambient {@code OBContext}'s role does not
   * happen to have both the template's AND the dependent's client in its own readable-clients list
   * — exactly the failure mode this class hit empirically while verifying this redesign against a
   * role composed from real templates it did not itself create the {@code OBContext} for.
   */
  private static <T extends BaseOBObject> OBCriteria<T> crossClientCriteria(Class<T> clazz) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(clazz);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    return criteria;
  }

  @SuppressWarnings("unchecked")
  private List<Role> findActiveDependentRoles(Role template) {
    OBCriteria<RoleInheritance> criteria = crossClientCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_INHERITFROM, template));
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ACTIVE, true));
    List<Role> dependents = new ArrayList<>();
    Set<String> seenRoleIds = new LinkedHashSet<>();
    for (RoleInheritance inheritance : (List<RoleInheritance>) criteria.list()) {
      Role dependent = inheritance.getRole();
      if (dependent != null && seenRoleIds.add(dependent.getId())) {
        dependents.add(dependent);
      }
    }
    return dependents;
  }

  private WindowAccess findActiveWindowAccess(Role role, Window window) {
    OBCriteria<WindowAccess> criteria = crossClientCriteria(WindowAccess.class);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_WINDOW, window));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return (WindowAccess) criteria.uniqueResult();
  }

  @SuppressWarnings("unchecked")
  private List<WindowAccess> findActiveWindowAccess(Role role) {
    OBCriteria<WindowAccess> criteria = crossClientCriteria(WindowAccess.class);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));
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

  /**
   * Overload for comparing against an {@code EntityPersistenceEvent#getCurrentState(Property)}
   * result, which is declared {@code Object} — defensively checks the runtime type rather than
   * casting, matching the same pattern this class's own predecessor design used for exactly this
   * comparison.
   */
  private static boolean sameId(BaseOBObject expected, Object actualState) {
    if (!(actualState instanceof BaseOBObject)) {
      return false;
    }
    return sameId(expected, (BaseOBObject) actualState);
  }
}
