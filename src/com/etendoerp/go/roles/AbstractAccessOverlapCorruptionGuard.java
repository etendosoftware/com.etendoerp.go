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

import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.Query;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.client.kernel.event.TransactionCompletedEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.roles.overlap.ActiveTemplateInheritance;
import com.etendoerp.go.roles.overlap.GrantCandidate;
import com.etendoerp.go.roles.overlap.OverlapReconciliationCore;
import com.etendoerp.go.roles.overlap.OverlapWinner;
import com.etendoerp.go.roles.overlap.PropagationTrigger;
import com.etendoerp.go.roles.overlap.TemplateRemovalTracker;

/**
 * Template-method base for the 3 cross-template access-overlap-corruption guards (ETP-4830,
 * "base-class extraction" — SonarQube Duplicated Lines Density gate). {@link
 * WindowAccessOverlapCorruptionGuard}, {@link ProcessAccessOverlapCorruptionGuard}, and {@link
 * ObuiappProcessAccessOverlapCorruptionGuard} independently converged, across 7 live-reproduced
 * rounds of edge cases (see {@link WindowAccessOverlapCorruptionGuard}'s own class javadoc for the
 * FULL round-by-round root-cause history — it is NOT repeated here, only the resulting mechanism
 * is), on the IDENTICAL algorithm, differing only in which concrete Access/Item entity pair they
 * touch: {@code WindowAccess}/{@code Window}, classic {@code
 * org.openbravo.model.ad.access.ProcessAccess}/{@code org.openbravo.model.ad.ui.Process}, or
 * OBUIAPP {@code org.openbravo.client.application.ProcessAccess}/{@code Process}. This class
 * implements that algorithm exactly once; each concrete subclass supplies only the type-specific
 * accessors ({@code A} = the access entity, {@code G} = the granted item — window/process) needed
 * to run it against its own pair.
 *
 * <p><b>The two-{@code Process}-classes trap.</b> {@code org.openbravo.model.ad.ui.Process}/{@code
 * org.openbravo.model.ad.access.ProcessAccess} (classic) and {@code
 * org.openbravo.client.application.Process}/{@code org.openbravo.client.application.ProcessAccess}
 * (OBUIAPP) are UNRELATED types that merely share simple class names. This class never imports
 * either pair itself — it only ever refers to them via the generic {@code A}/{@code G} type
 * parameters — so there is no risk of this class itself cross-wiring them. Each CONCRETE subclass
 * is the only place that imports and binds one specific pair (e.g. {@code
 * AbstractAccessOverlapCorruptionGuard<ProcessAccess, Process>} where both type arguments resolve,
 * via that subclass's OWN imports, to the SAME package's pair) — never mix an import from one
 * package with a type argument meant for the other.
 *
 * <p><b>Why an event observer instead of a service-layer fix, and why {@code @Priority} matters.
 * </b> See {@link WindowAccessOverlapCorruptionGuard}'s own class javadoc, "This design is a
 * PREVENTION strategy instead" and "Why {@code @Priority} matters here" sections — the reasoning
 * is entity-agnostic and applies identically to all 3 concrete guards; not re-derived here.
 *
 * <p><b>The mechanism, summarized (see {@link WindowAccessOverlapCorruptionGuard}'s class javadoc
 * for the full live-reproduced write-up of every one of these, trigger by trigger):</b>
 * <ul>
 *   <li>{@link #onSave(EntityNewEvent)} — a template gaining a brand-new grant, or any role
 *   gaining a brand-new {@code AD_Role_Inheritance}, is defended via {@link
 *   #guardDependentsOf(BaseOBObject, PropagationTrigger)}/{@link
 *   #guardNewInheritance(RoleInheritance)} with {@link PropagationTrigger#NEW_GRANT} — safe to
 *   delete a dependent's conflicting row unconditionally, because core's {@code
 *   propagateNewAccess} always falls back to a CREATE. For a freshly-created inherited row on a
 *   non-template role, {@link #correctInheritedOwnership(EntityNewEvent, BaseOBObject)} pins
 *   {@code client}/{@code organization} back to the owning role's own, and {@link
 *   #widenInheritedAccessLevelIfNeeded(EntityNewEvent, BaseOBObject)} applies most-permissive-wins
 *   across every OTHER actively-inherited template.</li>
 *   <li>{@link #onUpdate(EntityUpdateEvent)} — a template's OWN existing grant changing level is
 *   defended via {@link #guardDependentsOf(BaseOBObject, PropagationTrigger)} with {@link
 *   PropagationTrigger#UPDATED_GRANT}, which — since core's {@code propagateUpdatedAccess} has NO
 *   create fallback — never deletes, only repoints an already-correctly-sourced row IN PLACE via
 *   {@link #repointIfAlreadySourcedFromTemplate}, itself surveying every OTHER actively-inherited
 *   template before trusting the one template's own new value in isolation.</li>
 *   <li>{@link #onDelete(EntityDeleteEvent)} — a role LOSING an {@code AD_Role_Inheritance} is
 *   defended via {@link #guardRemovedInheritance(RoleInheritance)}, which computes, ONCE per item
 *   across ALL remaining templates together (never once per remaining template — that per-template
 *   loop is exactly what reopens a duplicate-INSERT race when 2+ remaining templates overlap on
 *   the same item), the single winning {@code InheritedFrom} (highest {@code
 *   AD_Role_Inheritance.SeqNo} among remaining grantors) and the most-permissive-wins level, then
 *   corrects the dependent's existing row for that item IN PLACE via {@link #repointInPlace} —
 *   never a delete+recreate.</li>
 *   <li>{@link #onTransactionComplete(TransactionCompletedEvent)} clears {@link
 *   TemplateRemovalTracker} once per transaction — the marker must outlive {@link
 *   #guardRemovedInheritance(RoleInheritance)}'s own stack frame, see that tracker's own javadoc.
 *   </li>
 * </ul>
 *
 * <p><b>Why {@link #correctInheritedOwnership} uses {@code event.setCurrentState(Property,
 * Object)}, never a plain entity setter.</b> See {@link WindowAccessOverlapCorruptionGuard#
 * correctInheritedOwnership(EntityNewEvent, org.openbravo.model.ad.access.WindowAccess)}'s own
 * javadoc for the full empirical write-up (confirmed the hard way: a plain setter only mutates the
 * Java object, never Hibernate's own {@code state[]} array the eventual INSERT's bound values are
 * read from). This constraint is entity-agnostic — {@code
 * PersistenceEventOBInterceptor#sendNewEvent} builds the SAME kind of {@code EntityNewEvent} for
 * every entity type — so it applies identically here, for whichever {@code A} a concrete subclass
 * binds.
 *
 * <p><b>Why {@link #deleteForcingCreatePath} uses a direct bulk HQL {@code DELETE}, and why it
 * refreshes (never evicts) the owning role.</b> See {@link WindowAccessOverlapCorruptionGuard#
 * deleteForcingCreatePath}'s own javadoc for the full empirical write-up ({@code FlushMode.COMMIT}
 * never auto-flushes a pending {@code OBDal.remove()} into query visibility; a reentrant {@code
 * OBDal.flush()} corrupts the outer flush's own action queue; evicting the owning role strips its
 * live session, breaking a later lazy collection re-initialization). Entity-agnostic reasoning,
 * reused verbatim here.
 *
 * <p><b>Why {@link #repointInPlace} uses a bulk HQL {@code UPDATE} instead of {@link
 * #deleteForcingCreatePath}, and why it refreshes (never evicts) the corrected row itself.</b> See
 * {@link WindowAccessOverlapCorruptionGuard#repointInPlace}'s own javadoc for the full write-up:
 * deleting here would reopen the exact duplicate-INSERT race this method exists to close whenever
 * 2+ remaining templates overlap on the same item (REMOVE path), or would permanently lose a
 * dependent's row on a core propagation path with no create fallback (UPDATE path, the "[B7]"
 * fix). Evicting the row itself (rather than refreshing) collides with Hibernate's own
 * flush-time collection-reachability walk when reached from {@code onUpdate}'s {@code
 * Interceptor#onFlushDirty} — refresh avoids the collision on both callers.
 *
 * @param <A>
 *          the concrete access entity type this guard observes ({@code WindowAccess}, classic
 *          {@code ProcessAccess}, or OBUIAPP {@code ProcessAccess})
 * @param <G>
 *          the concrete granted-item type referenced by {@code A} ({@code Window} or one of the
 *          two unrelated {@code Process} types — see the class javadoc's "two-{@code
 *          Process}-classes trap" section)
 */
public abstract class AbstractAccessOverlapCorruptionGuard<A extends BaseOBObject,
    G extends BaseOBObject> extends EntityPersistenceEventObserver {

  /**
   * Any priority value makes Weld notify this observer before core's own unprioritized {@code
   * InheritedAccessEnabledEventHandler}/{@code RoleInheritanceEventHandler} — see the class
   * javadoc's "Why an event observer..." pointer to {@link WindowAccessOverlapCorruptionGuard}'s
   * own "Why {@code @Priority} matters here" section. The literal value is otherwise arbitrary.
   */
  protected static final int RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS = 1;

  private static final String PROPERTY_ROLE = "role";
  private static final String PROPERTY_ACTIVE = "active";
  private static final String PROPERTY_CLIENT = "client";
  private static final String PROPERTY_ORGANIZATION = "organization";
  private static final String PROPERTY_EDITABLEFIELD = "editableField";
  private static final String PROPERTY_INHERITEDFROM = "inheritedFrom";

  private Entity[] entities;

  // ---------------------------------------------------------------------
  // Type-specific accessors — the only thing each concrete subclass supplies.
  // ---------------------------------------------------------------------

  /** This guard's own logger — kept per-subclass so log lines carry the correct class name. */
  protected abstract Logger log();

  /** The concrete access-entity Java class this guard observes ({@code WindowAccess.class} etc). */
  protected abstract Class<A> accessClass();

  /** The access entity's {@code ENTITY_NAME} constant (e.g. {@code WindowAccess.ENTITY_NAME}). */
  protected abstract String accessEntityName();

  /** The access entity's item-reference property name (e.g. {@code WindowAccess.PROPERTY_WINDOW},
   *  {@code ProcessAccess.PROPERTY_PROCESS}, or {@code ProcessAccess.PROPERTY_OBUIAPPPROCESS}). */
  protected abstract String itemProperty();

  /** {@code access.getRole()}. */
  protected abstract Role getRole(A access);

  /** The granted item on this row ({@code access.getWindow()}/{@code getProcess()}/{@code
   *  getObuiappProcess()}). */
  protected abstract G getGrantedItem(A access);

  /** {@code access.getInheritedFrom()}. */
  protected abstract Role getInheritedFrom(A access);

  /** {@code access.isEditableField()} — the raw, possibly-{@code null}, boxed value; callers apply
   *  the same {@code Boolean.TRUE.equals(...)}/{@code Boolean.valueOf(...).equals(...)} null-safe
   *  comparisons the original 3 classes used, unchanged. */
  protected abstract Boolean getEditableField(A access);

  /** Removes {@code access} from {@code owner}'s own in-memory collection ({@code
   *  owner.getADWindowAccessList()}/{@code getADProcessAccessList()}/{@code
   *  getOBUIAPPProcessAccessList()}) — see {@link #deleteForcingCreatePath}'s own javadoc for why
   *  this must happen alongside the bulk delete. */
  protected abstract void removeFromOwnerCollection(Role owner, A access);

  /** Display name for log messages (e.g. {@code "AD_Window_Access"}). */
  protected abstract String entityLogLabel();

  /** Display name for the granted item in log messages (e.g. {@code "window"}, {@code
   *  "process"}). */
  protected abstract String itemLogLabel();

  // ---------------------------------------------------------------------
  // Observed entities
  // ---------------------------------------------------------------------

  @Override
  protected Entity[] getObservedEntities() {
    if (entities == null) {
      entities = new Entity[] {
          ModelProvider.getInstance().getEntity(accessEntityName()),
          ModelProvider.getInstance().getEntity(RoleInheritance.ENTITY_NAME) };
    }
    return entities;
  }

  private Entity accessEntity() {
    return ModelProvider.getInstance().getEntity(accessEntityName());
  }

  // ---------------------------------------------------------------------
  // Event entry points
  // ---------------------------------------------------------------------

  /**
   * Defends BOTH corrupting triggers on the SAVE path — see the class javadoc's bullet list. A
   * NEW access row on a template, and a NEW {@code AD_Role_Inheritance} row on any role, are the
   * two places core's own propagation can start a corrupting UPDATE against a role this class
   * never even knows is at risk; both are guarded here, before core's own (unprioritized)
   * handlers run.
   */
  public void onSave(@Observes @Priority(RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS)
      EntityNewEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Object target = event.getTargetInstance();
    if (accessClass().isInstance(target)) {
      A access = accessClass().cast(target);
      Role role = getRole(access);
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

  /**
   * Defends the {@code propagateUpdatedAccess} trigger — a DIFFERENT safe strategy than {@link
   * #onSave(EntityNewEvent)}'s {@code NEW_GRANT} trigger, since core's own {@code
   * propagateUpdatedAccess} has NO create fallback. {@code RoleInheritance} has no meaningful
   * update path to guard — core's own {@code RoleInheritanceEventHandler#onUpdate} unconditionally
   * rejects it.
   */
  public void onUpdate(@Observes @Priority(RUNS_BEFORE_UNPRIORITIZED_CORE_OBSERVERS)
      EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    Object target = event.getTargetInstance();
    if (accessClass().isInstance(target)) {
      guardDependentsOf(accessClass().cast(target), PropagationTrigger.UPDATED_GRANT);
    }
  }

  /**
   * Defends the REMOVE-side corruption trigger. {@code RoleInheritance} has the only meaningful
   * delete path to guard here; a delete of the access row itself is not a corruption vector this
   * class needs to react to (a manually- or template-deleted row is a plain entity delete on the
   * OWNING role's own row, never a cross-role blind-copy write).
   */
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

  /**
   * Cleanup counterpart to {@link TemplateRemovalTracker} — see that class's own javadoc for why
   * the marker it holds is deliberately NOT cleared inside {@link
   * #guardRemovedInheritance(RoleInheritance)} itself. {@code TransactionCompletedEvent} fires
   * once per transaction, on BOTH commit and rollback, always strictly after every flush the
   * transaction could have triggered — a safe, simple point to reset the marker for the next
   * transaction on this thread.
   */
  public void onTransactionComplete(@Observes TransactionCompletedEvent event) {
    TemplateRemovalTracker.clear();
  }

  // ---------------------------------------------------------------------
  // ADD-path mechanism
  // ---------------------------------------------------------------------

  /**
   * Corrects {@code access}'s {@code client}/{@code organization} to match its OWNING role's own
   * values, for a freshly-{@code onSave}d row that is NOT a template's own. See the class
   * javadoc's "Why {@link #correctInheritedOwnership} uses {@code event.setCurrentState}" section.
   */
  private void correctInheritedOwnership(EntityNewEvent event, A access) {
    if (getInheritedFrom(access) == null) {
      // Manually-granted row, never template-derived — ownership is whatever the grantor set,
      // not this class's business.
      return;
    }
    Role owner = getRole(access);
    if (owner == null) {
      return;
    }
    Entity entity = accessEntity();
    Property clientProperty = entity.getProperty(PROPERTY_CLIENT);
    Property organizationProperty = entity.getProperty(PROPERTY_ORGANIZATION);

    boolean clientWrong = owner.getClient() != null
        && !ActiveTemplateInheritance.sameId(owner.getClient(),
            event.getCurrentState(clientProperty));
    boolean organizationWrong = owner.getOrganization() != null
        && !ActiveTemplateInheritance.sameId(owner.getOrganization(),
            event.getCurrentState(organizationProperty));
    if (!clientWrong && !organizationWrong) {
      return;
    }
    if (clientWrong) {
      event.setCurrentState(clientProperty, owner.getClient());
    }
    if (organizationWrong) {
      event.setCurrentState(organizationProperty, owner.getOrganization());
    }
    G item = getGrantedItem(access);
    log().info(
        "Corrected {} ownership on role {} {} {}: pinned client/organization back to the role's "
            + "own (template-derived row, inherited from {})",
        entityLogLabel(), owner.getId(), itemLogLabel(), item != null ? item.getId() : null,
        getInheritedFrom(access).getId());
  }

  /**
   * Widens {@code access}'s access level to full whenever ANY OTHER template {@code
   * getRole(access)} is currently, actively inheriting from ALSO grants the same item full access
   * — most-permissive-wins, generalized to ANY newly-created inherited row. Never narrows (matches
   * the existing rule: a full grant, once resolved, always wins) — only ever flips {@code false}
   * to {@code true}. Also repoints {@code InheritedFrom} to the template that actually justifies
   * the widened value — see {@link WindowAccessOverlapCorruptionGuard}'s class javadoc,
   * "InheritedFrom bookkeeping" section, for why leaving it pointed at the originally
   * CREATE-sourced template breaks a later removal of the justifying template.
   */
  private void widenInheritedAccessLevelIfNeeded(EntityNewEvent event, A access) {
    if (getInheritedFrom(access) == null) {
      return;
    }
    Role owner = getRole(access);
    G item = getGrantedItem(access);
    if (owner == null || item == null) {
      return;
    }
    Entity entity = accessEntity();
    Property editableFieldProperty = entity.getProperty(PROPERTY_EDITABLEFIELD);
    if (Boolean.TRUE.equals(event.getCurrentState(editableFieldProperty))) {
      // Already the most permissive value possible — nothing to widen, and InheritedFrom already
      // names a template that genuinely justifies the current value (see
      // WindowAccessOverlapCorruptionGuard's own javadoc for the full reasoning).
      return;
    }
    Role justifyingTemplate = findActiveTemplateGrantingFullAccess(owner, item);
    if (justifyingTemplate == null) {
      return;
    }
    event.setCurrentState(editableFieldProperty, true);
    Property inheritedFromProperty = entity.getProperty(PROPERTY_INHERITEDFROM);
    Role originalSource = getInheritedFrom(access);
    event.setCurrentState(inheritedFromProperty, justifyingTemplate);
    log().info(
        "Widened {} on role {} {} {} to full and repointed InheritedFrom from {} to {}: another "
            + "currently-inherited template already grants this {} full access",
        entityLogLabel(), owner.getId(), itemLogLabel(), item.getId(), originalSource.getId(),
        justifyingTemplate.getId(), itemLogLabel());
  }

  /**
   * The single OTHER template {@code dependent} is currently, actively inheriting from that
   * grants {@code item} full access — see {@link WindowAccessOverlapCorruptionGuard#
   * findActiveTemplateGrantingFullAccess(Role, org.openbravo.model.ad.ui.Window)}'s own javadoc
   * for the SeqNo-descending tie-break rationale (mirrors core's own {@code
   * RoleInheritanceManager#propagateDeletedAccess} heuristic).
   */
  private Role findActiveTemplateGrantingFullAccess(Role dependent, G item) {
    return findActiveTemplateGrantingFullAccess(dependent, item, null);
  }

  /**
   * Overload that skips one specific template — used by {@link
   * #repointIfAlreadySourcedFromTemplate} to survey every OTHER actively-inherited template. Same
   * SeqNo-descending tie-break as the no-exclusion overload.
   */
  private Role findActiveTemplateGrantingFullAccess(Role dependent, G item, Role excludedTemplate) {
    Map<String, Role> templatesById = new LinkedHashMap<>();
    List<GrantCandidate> candidates = new ArrayList<>();
    for (Role template : ActiveTemplateInheritance.findActiveTemplatesFor(dependent, null)) {
      templatesById.putIfAbsent(template.getId(), template);
      A templateAccess = findActiveAccess(template, item);
      if (templateAccess != null) {
        candidates.add(
            new GrantCandidate(template.getId(), Boolean.TRUE.equals(getEditableField(templateAccess))));
      }
    }
    String excludedTemplateId = excludedTemplate != null ? excludedTemplate.getId() : null;
    String winnerId = OverlapReconciliationCore.findJustifyingFullGrant(candidates, excludedTemplateId);
    return winnerId != null ? templatesById.get(winnerId) : null;
  }

  /**
   * If {@code inheritance} points a role at a template, proactively clears that role's OWN
   * conflicting active access for every item the NEW template also grants. See the class
   * javadoc's bullet list.
   */
  private void guardNewInheritance(RoleInheritance inheritance) {
    Role dependent = inheritance.getRole();
    Role template = inheritance.getInheritFrom();
    if (dependent == null || template == null || !Boolean.TRUE.equals(template.isTemplate())) {
      return;
    }
    for (A templateGrant : findActiveAccessList(template)) {
      G item = getGrantedItem(templateGrant);
      if (item == null) {
        continue;
      }
      clearConflictingAccessUnconditionally(dependent, item, template);
    }
  }

  /**
   * If {@code templateAccess} belongs to a template role, proactively reconciles every OTHER
   * role's conflicting active access for the same item BEFORE returning control to core's own
   * propagation — using a DIFFERENT strategy depending on {@code trigger}, since the two core
   * propagation methods this guards behave asymmetrically. See {@link PropagationTrigger}'s own
   * javadoc.
   */
  private void guardDependentsOf(A templateAccess, PropagationTrigger trigger) {
    Role role = getRole(templateAccess);
    if (role == null || !Boolean.TRUE.equals(role.isTemplate())) {
      // Not a template's own row — nothing to propagate, core will not react to this one either.
      return;
    }
    G item = getGrantedItem(templateAccess);
    if (item == null) {
      return;
    }
    for (Role dependent : ActiveTemplateInheritance.findActiveDependentRoles(role)) {
      if (trigger == PropagationTrigger.NEW_GRANT) {
        clearConflictingAccessUnconditionally(dependent, item, role);
      } else {
        repointIfAlreadySourcedFromTemplate(dependent, item, role, templateAccess);
      }
    }
  }

  /**
   * The {@code onUpdate}/{@code UPDATED_GRANT} counterpart to {@link
   * #clearConflictingAccessUnconditionally} — deleting is NOT safe on this trigger (see {@link
   * PropagationTrigger#UPDATED_GRANT}'s own javadoc). Restricted to the only case core's own
   * {@code propagateUpdatedAccess} actually acts on: {@code dependent}'s existing row for {@code
   * item} is ALREADY sourced from {@code grantingTemplate}. Never blindly trusts {@code
   * grantingTemplate}'s new value in isolation — surveys every OTHER actively-inherited template
   * first (the "BUG-2" fix — see {@link WindowAccessOverlapCorruptionGuard#
   * repointIfAlreadySourcedFromTemplate}'s own javadoc for the full live-reproduced write-up), so
   * most-permissive-wins holds even when the template whose OWN grant just changed is not the only
   * one justifying full access.
   */
  private void repointIfAlreadySourcedFromTemplate(Role dependent, G item, Role grantingTemplate,
      A templateAccess) {
    A existing = findActiveAccess(dependent, item);
    if (existing == null) {
      // No existing row — core's own propagateUpdatedAccess would find nothing here either and
      // do nothing; consistent, nothing for this mechanism to do.
      return;
    }
    Role existingSource = getInheritedFrom(existing);
    if (existingSource == null || !ActiveTemplateInheritance.sameId(existingSource, grantingTemplate)) {
      // Not sourced from THIS template — out of scope for core's own propagateUpdatedAccess too.
      return;
    }
    // grantingTemplate's own NEW value is read directly from the caller's already-updated
    // (pre-flush, in-memory) entity, not re-queried.
    boolean grantingTemplateNewLevel = Boolean.TRUE.equals(getEditableField(templateAccess));
    Role otherJustifyingTemplate = grantingTemplateNewLevel ? null
        : findActiveTemplateGrantingFullAccess(dependent, item, grantingTemplate);

    boolean finalLevel = grantingTemplateNewLevel || otherJustifyingTemplate != null;
    Role winner = otherJustifyingTemplate != null ? otherJustifyingTemplate : grantingTemplate;

    boolean sourceCorrect = ActiveTemplateInheritance.sameId(existingSource, winner);
    boolean levelCorrect = Boolean.valueOf(finalLevel).equals(getEditableField(existing));
    if (sourceCorrect && levelCorrect) {
      return;
    }
    repointInPlace(existing, item, winner, finalLevel, existingSource);
  }

  /**
   * Shared by both {@code NEW_GRANT}-safe ADD-side triggers. If {@code dependent} has an active
   * row for {@code item}, deletes it via {@link #deleteForcingCreatePath} UNCONDITIONALLY — even
   * when the row is ALREADY correctly sourced from {@code grantingTemplate} — see {@link
   * WindowAccessOverlapCorruptionGuard#clearConflictingAccessUnconditionally}'s own javadoc for
   * why "already correct" is not a safe reason to skip (core's blind lookup across clients cannot
   * always SEE the row before its own CREATE-branch decision).
   */
  private void clearConflictingAccessUnconditionally(Role dependent, G item, Role grantingTemplate) {
    A existing = findActiveAccess(dependent, item);
    if (existing == null) {
      // No conflicting row at all — core will safely CREATE one, nothing to prevent.
      return;
    }
    deleteForcingCreatePath(existing, dependent, item, grantingTemplate, getInheritedFrom(existing));
  }

  /**
   * Removes {@code existing} — the dependent role's OWN conflicting row — so core's subsequent
   * lookup for (role={@code dependent}, item={@code item}) finds nothing and takes the CREATE path
   * instead of the corrupting UPDATE path. See the class javadoc's own section on why this is a
   * direct bulk HQL {@code DELETE}, and why {@code dependent} is refreshed (never evicted)
   * afterward.
   */
  private void deleteForcingCreatePath(A existing, Role dependent, G item, Role template,
      Role previousSource) {
    OBContext.setAdminMode(false);
    try {
      OBDal.getInstance().getSession()
          .createQuery("delete from " + accessEntityName() + " where id = :id")
          .setParameter("id", existing.getId())
          .executeUpdate();
    } finally {
      OBContext.restorePreviousMode();
    }
    removeFromOwnerCollection(dependent, existing);
    OBDal.getInstance().refresh(dependent);
    OBDal.getInstance().getSession().evict(existing);
    log().info(
        "Prevented cross-template {} overlap corruption: cleared role {} {} {} access "
            + "(previously {}) before template {}'s own grant propagates, forcing core onto the "
            + "safe CREATE path",
        entityLogLabel(), dependent.getId(), itemLogLabel(), item.getId(),
        previousSource != null ? "inherited from " + previousSource.getId() : "manually granted",
        template.getId());
  }

  // ---------------------------------------------------------------------
  // REMOVE-path mechanism
  // ---------------------------------------------------------------------

  /**
   * Per-item candidates {@link #collectItemGrantors} builds and {@link #repointItemIfNeeded}
   * feeds into {@link OverlapReconciliationCore#computeWinner(List)}. A non-static inner class so
   * it can share this guard's own {@code A}/{@code G} type parameters directly.
   */
  private final class ItemGrantors {
    private final Map<String, G> itemsById = new LinkedHashMap<>();
    private final Map<String, Role> templatesById = new LinkedHashMap<>();
    private final Map<String, List<GrantCandidate>> candidatesByItemId = new LinkedHashMap<>();
  }

  /**
   * Builds, across ALL of {@code remainingTemplates} in one pass, the per-item {@link
   * GrantCandidate} lists {@link #repointItemIfNeeded} feeds into {@link
   * OverlapReconciliationCore#computeWinner(List)}. {@code remainingTemplates} is already SeqNo
   * DESCENDING ({@link ActiveTemplateInheritance#findActiveTemplatesFor}), and that relative order
   * is preserved per item here.
   */
  private ItemGrantors collectItemGrantors(List<Role> remainingTemplates) {
    ItemGrantors grantors = new ItemGrantors();
    for (Role remainingTemplate : remainingTemplates) {
      grantors.templatesById.putIfAbsent(remainingTemplate.getId(), remainingTemplate);
      for (A templateGrant : findActiveAccessList(remainingTemplate)) {
        G item = getGrantedItem(templateGrant);
        if (item == null) {
          continue;
        }
        String itemId = (String) item.getId();
        grantors.itemsById.putIfAbsent(itemId, item);
        grantors.candidatesByItemId.computeIfAbsent(itemId, key -> new ArrayList<>())
            .add(new GrantCandidate(remainingTemplate.getId(),
                Boolean.TRUE.equals(getEditableField(templateGrant))));
      }
    }
    return grantors;
  }

  /**
   * Corrects {@code dependent}'s existing row for {@code item} in place when it does not already
   * match {@link OverlapReconciliationCore#computeWinner(List)}'s verdict; returns whether a
   * correction was made (so the caller knows whether {@code dependent} needs refreshing
   * afterward).
   */
  private boolean repointItemIfNeeded(Role dependent, G item, ItemGrantors grantors) {
    String itemId = (String) item.getId();
    OverlapWinner winner = OverlapReconciliationCore.computeWinner(grantors.candidatesByItemId.get(itemId));
    if (winner == null) {
      return false;
    }
    Role winnerRole = grantors.templatesById.get(winner.getWinnerTemplateId());

    A existing = findActiveAccess(dependent, item);
    if (existing == null) {
      // No existing row to correct in place — same residual, pre-existing, theoretical gap
      // documented in WindowAccessOverlapCorruptionGuard's own class javadoc ("A sixth trigger").
      return false;
    }
    Role existingSource = getInheritedFrom(existing);
    boolean sourceCorrect =
        existingSource != null && ActiveTemplateInheritance.sameId(existingSource, winnerRole);
    boolean levelCorrect = Boolean.valueOf(winner.isWinnerLevel()).equals(getEditableField(existing));
    if (sourceCorrect && levelCorrect) {
      return false;
    }
    repointInPlace(existing, item, winnerRole, winner.isWinnerLevel(), existingSource);
    return true;
  }

  /**
   * Defends the REMOVE-side corruption trigger — see the class javadoc's bullet list and {@link
   * WindowAccessOverlapCorruptionGuard}'s own class javadoc ("A third trigger"/"A sixth trigger"
   * sections) for the full live-reproduced root-cause write-up.
   */
  private void guardRemovedInheritance(RoleInheritance inheritance) {
    Role dependent = inheritance.getRole();
    Role removedTemplate = inheritance.getInheritFrom();
    if (dependent == null || removedTemplate == null) {
      return;
    }
    // Marks removedTemplate as "being removed" for the REST of this transaction — see
    // TemplateRemovalTracker's own javadoc for why the marker must outlive this method's own
    // stack frame.
    TemplateRemovalTracker.markRemoved(removedTemplate.getId());

    List<Role> remainingTemplates =
        ActiveTemplateInheritance.findActiveTemplatesFor(dependent, inheritance.getId());
    ItemGrantors grantors = collectItemGrantors(remainingTemplates);

    boolean anyCorrected = false;
    for (G item : grantors.itemsById.values()) {
      anyCorrected |= repointItemIfNeeded(dependent, item, grantors);
    }

    if (anyCorrected) {
      // Mirrors deleteForcingCreatePath's own OBDal.refresh(dependent) call — same
      // stale-collection-snapshot reasoning, see that method's own javadoc.
      OBDal.getInstance().refresh(dependent);
    }
  }

  /**
   * Corrects {@code existing}'s {@code inheritedFrom} and {@code editableField} to the
   * pre-computed {@code winner}/{@code winnerLevel} DIRECTLY, IN PLACE — the SAME row, SAME
   * primary key — instead of {@link #deleteForcingCreatePath} deleting it and relying on core's
   * OWN propagation to recreate it. See the class javadoc's own section on why a bulk HQL {@code
   * UPDATE} is required here, and why {@code existing} is refreshed (never evicted) afterward.
   * {@code client}/{@code organization} are deliberately NOT touched here — {@code existing}
   * already belongs to {@code dependent}'s own role, so there is nothing to re-pin, unlike {@link
   * #correctInheritedOwnership}'s CREATE-path concern.
   */
  private void repointInPlace(A existing, G item, Role winner, boolean winnerLevel,
      Role previousSource) {
    // updatedBy is an entity reference (AD_User), not a plain id string — binding a String here
    // throws ClassCastException from Hibernate's EntityType#nullSafeSet. Falls back to leaving
    // updatedBy/updated untouched when no user is available on the context, mirroring how
    // deleteForcingCreatePath's own bulk DELETE never touches audit columns on the row it removes
    // either.
    User currentUser = OBContext.getOBContext() != null ? OBContext.getOBContext().getUser() : null;
    OBContext.setAdminMode(false);
    try {
      StringBuilder hql = new StringBuilder("update ").append(accessEntityName())
          .append(" set inheritedFrom = :winner, editableField = :level");
      if (currentUser != null) {
        hql.append(", updated = :updated, updatedBy = :updatedBy");
      }
      hql.append(" where id = :id");
      Query<?> query = OBDal.getInstance().getSession().createQuery(hql.toString());
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
    Role existingRole = getRole(existing);
    log().info(
        "Prevented cross-template {} overlap corruption (multi-remaining-template removal case): "
            + "repointed role {} {} {} in place from {} to {} (editableField={}) without deleting "
            + "the row — avoids core's own calculateAccesses independently re-creating this item "
            + "from 2+ remaining templates within the same flush",
        entityLogLabel(), existingRole != null ? existingRole.getId() : null, itemLogLabel(),
        item.getId(), previousSource != null ? previousSource.getId() : "manually granted",
        winner.getId(), winnerLevel);
  }

  // ---------------------------------------------------------------------
  // Queries
  // ---------------------------------------------------------------------

  /**
   * {@code crossClientCriteria}-based lookups — see {@link ActiveTemplateInheritance#
   * crossClientCriteria(Class)}'s own javadoc for why the readable-client/organization filters
   * must be disabled here.
   */
  private A findActiveAccess(Role role, G item) {
    OBCriteria<A> criteria = ActiveTemplateInheritance.crossClientCriteria(accessClass());
    criteria.add(Restrictions.eq(PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(itemProperty(), item));
    criteria.add(Restrictions.eq(PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return accessClass().cast(criteria.uniqueResult());
  }

  @SuppressWarnings("unchecked")
  private List<A> findActiveAccessList(Role role) {
    OBCriteria<A> criteria = ActiveTemplateInheritance.crossClientCriteria(accessClass());
    criteria.add(Restrictions.eq(PROPERTY_ROLE, role));
    criteria.add(Restrictions.eq(PROPERTY_ACTIVE, true));
    return criteria.list();
  }
}
