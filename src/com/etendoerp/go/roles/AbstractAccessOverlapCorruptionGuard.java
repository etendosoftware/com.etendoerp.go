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
 * Object)}, never a plain entity setter.</b> See {@link #correctInheritedOwnership}'s own javadoc
 * for the full empirical write-up (confirmed the hard way: a plain setter only mutates the
 * Java object, never Hibernate's own {@code state[]} array the eventual INSERT's bound values are
 * read from). This constraint is entity-agnostic — {@code
 * PersistenceEventOBInterceptor#sendNewEvent} builds the SAME kind of {@code EntityNewEvent} for
 * every entity type — so it applies identically here, for whichever {@code A} a concrete subclass
 * binds.
 *
 * <p><b>Why {@link #deleteForcingCreatePath} uses a direct bulk HQL {@code DELETE}, and why it
 * refreshes (never evicts) the owning role.</b> See {@link #deleteForcingCreatePath}'s own javadoc
 * for the full empirical write-up ({@code FlushMode.COMMIT} never auto-flushes a pending {@code
 * OBDal.remove()} into query visibility; a reentrant {@code OBDal.flush()} corrupts the outer
 * flush's own action queue; evicting the owning role strips its live session, breaking a later
 * lazy collection re-initialization). Entity-agnostic reasoning, reused verbatim here.
 *
 * <p><b>Why {@link #repointInPlace} uses a bulk HQL {@code UPDATE} instead of {@link
 * #deleteForcingCreatePath}, and why it refreshes (never evicts) the corrected row itself.</b> See
 * {@link #repointInPlace}'s own javadoc for the full write-up: deleting here would reopen the
 * exact duplicate-INSERT race this method exists to close whenever 2+ remaining templates overlap
 * on the same item (REMOVE path), or would permanently lose a dependent's row on a core
 * propagation path with no create fallback (UPDATE path, the "[B7]" fix). Evicting the row itself
 * (rather than refreshing) collides with Hibernate's own flush-time collection-reachability walk
 * when reached from {@code onUpdate}'s {@code Interceptor#onFlushDirty} — refresh avoids the
 * collision on both callers.
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
   * grants {@code item} full ("&#x2713;") access — read fresh from each template's own access
   * rows, mirroring {@code UserRoleCompositionService#mostPermissiveWindowAccess} (same
   * source-of-truth choice: the templates' own current grants, not whatever single row core's
   * per-item propagation happened to leave behind). Returns {@code null} if none does.
   *
   * <p><b>Tie-break when 2+ OTHER active templates both grant full access.</b> {@link
   * com.etendoerp.go.roles.overlap.ActiveTemplateInheritance#findActiveTemplatesFor(Role, String)}
   * returns templates ordered by their {@code AD_Role_Inheritance.SeqNo} DESCENDING, so the first
   * match this method finds is the highest-sequence-number one — deliberately mirroring the exact
   * tie-break core's own {@code RoleInheritanceManager#propagateDeletedAccess} already uses when
   * it has to pick ONE surviving template to re-source a row from after a removal ("retrieve the
   * list of templates, ordered by sequence number descending, to update the access with the first
   * one available"). Picking the SAME tie-break core itself uses means this method's choice of
   * "the" justifying template stays consistent with whatever core would independently re-derive if
   * the row were deleted and recreated from scratch — not a novel rule invented for this method.
   *
   * <p>Also reused by {@link #repointIfAlreadySourcedFromTemplate} (the BUG-2 fix, ETP-4906 QA
   * final coverage pass, 2026-08-18) via the {@code excludedTemplate} overload below, so the
   * {@code onUpdate}/{@code UPDATED_GRANT} trigger surveys every OTHER actively-inherited template
   * before applying a downgrade — the same most-permissive-wins pattern this method already gives
   * the ADD-path trigger.
   */
  private Role findActiveTemplateGrantingFullAccess(Role dependent, G item) {
    return findActiveTemplateGrantingFullAccess(dependent, item, null);
  }

  /**
   * Overload of {@link #findActiveTemplateGrantingFullAccess(Role, BaseOBObject)} that skips one
   * specific template — used by {@link #repointIfAlreadySourcedFromTemplate} to survey every
   * OTHER actively-inherited template ({@code excludedTemplate} is the one whose own row just
   * changed and is being handled separately, from the caller's already-updated in-memory value).
   * Same SeqNo-descending tie-break as the no-exclusion overload — see that method's own javadoc.
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
   * #clearConflictingAccessUnconditionally} — see {@link PropagationTrigger}'s own javadoc and the
   * class javadoc's "[B7]" section for why deleting is NOT safe on this trigger. Restricted to the
   * only case core's own {@code propagateUpdatedAccess} actually acts on: {@code dependent}'s
   * existing row for {@code item} is ALREADY sourced from {@code grantingTemplate}. For that case,
   * corrects the row's {@code editableField} IN PLACE to match {@code templateAccess}'s own
   * (already-updated, pre-flush) value directly — via the same bulk-HQL-UPDATE technique {@link
   * #repointInPlace} already uses for the sixth trigger — rather than deleting it, so the
   * dependent's row is guaranteed to survive regardless of whether core's own {@code
   * propagateUpdatedAccess} manages to find and update it afterward.
   *
   * <p>A row NOT sourced from {@code grantingTemplate} (manually granted, or sourced from a
   * DIFFERENT template) is left entirely untouched: core's own {@code findInheritedAccess} only
   * ever matches a dependent's row already sourced from the SAME template whose grant just
   * changed, so it would not have acted on this row either — nothing to prevent, nothing to
   * correct.
   *
   * <p><b>[BUG-2 fix, ETP-4906 QA final coverage pass, 2026-08-18] Never blindly trusts {@code
   * grantingTemplate}'s new value in isolation.</b> The original version of this method copied
   * {@code templateAccess}'s new {@code editableField} onto the dependent's row unconditionally
   * whenever the row was sourced from {@code grantingTemplate} — silently violating
   * most-permissive-wins whenever {@code dependent} ALSO actively inherits from a DIFFERENT
   * template that still grants {@code item} full access: downgrading {@code grantingTemplate}'s
   * own access (a routine Etendo Classic admin action) would drag the dependent's row down too,
   * even though the other template still justified full access. Empirically reproduced live (QA's
   * throwaway probe, see the plan doc's "QA Findings — Final Coverage Pass" section) — first
   * attempt, no flakiness. Fixed by surveying every OTHER actively-inherited template via {@link
   * #findActiveTemplateGrantingFullAccess(Role, BaseOBObject, Role)} BEFORE applying anything — the
   * exact same most-permissive-wins survey {@link #widenInheritedAccessLevelIfNeeded(EntityNewEvent,
   * BaseOBObject)} (ADD path) and {@link #collectItemGrantors(List)} (REMOVE path) already run,
   * just applied to the one trigger that skipped it. The final level is the MAX of {@code
   * grantingTemplate}'s own new value and every other active grantor's current value; {@code
   * InheritedFrom} is repointed to whichever template actually justifies that final value —
   * {@code grantingTemplate} itself when its own new value already suffices, otherwise the
   * OTHER still-active full grantor — mirroring {@code widenInheritedAccessLevelIfNeeded}'s own
   * "repoint to whoever actually justifies the value" rule so a LATER removal of either template
   * correctly re-triggers re-derivation instead of leaving the row pointed at a template that no
   * longer backs its own value.
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
   * Shared by both {@code NEW_GRANT}-safe ADD-side triggers ({@link
   * #guardDependentsOf(BaseOBObject, PropagationTrigger)}'s {@code NEW_GRANT} case and {@link
   * #guardNewInheritance(RoleInheritance)}) — NOT called for {@link
   * #guardDependentsOf(BaseOBObject, PropagationTrigger)}'s {@code UPDATED_GRANT} case, which uses
   * {@link #repointIfAlreadySourcedFromTemplate} instead (see that method's own javadoc and {@link
   * PropagationTrigger}'s javadoc for why). If {@code dependent} has an active access row for
   * {@code item}, deletes it via {@link #deleteForcingCreatePath} UNCONDITIONALLY — even when the
   * row is ALREADY correctly sourced from {@code grantingTemplate}. See the class javadoc's "A
   * seventh trigger" section for the full root-cause write-up; summarized here.
   *
   * <p><b>Why "already correct" is no longer a reason to skip — found empirically (ETP-4906,
   * Task B6, 7th round, 2026-08-17).</b> Both prior ADD-side implementations (rounds 1-2) treated
   * "the existing row is already sourced from the SAME template that just gained the grant" as a
   * safe no-op, reasoning that core's own {@code RoleInheritanceManager#handleAccess}/{@code
   * isPrecedent} would independently reach the identical conclusion (its own {@code
   * ACCESS_NOT_CHANGED} branch) and leave the row alone. That reasoning assumed core's own {@code
   * getAccess()}/{@code AccessTypeInjector#findAccess} lookup can actually SEE the row — it
   * cannot, whenever the dependent's client is not in the ambient {@code OBContext}'s own
   * readable-clients list: {@code findAccess}'s generated query filters by {@code AD_Client_ID in
   * (...)} using the CALLING context's readable clients (confirmed via SQL trace: a role
   * belonging to a tenant client not in that list is invisible to this query, full stop — the
   * row-level filter is not admin-mode-gated, exactly like {@link
   * com.etendoerp.go.roles.overlap.ActiveTemplateInheritance#crossClientCriteria(Class)}'s own
   * javadoc already documents for OUR OWN queries). When blind, {@code handleAccess} ALWAYS
   * evaluates {@code access == null} and takes the CREATE branch — REGARDLESS of whether a
   * correctly-sourced row already exists — so ANY pre-existing row for that (role, item), no
   * matter how correct, is a duplicate-INSERT collision waiting to happen the instant core's own
   * propagation reaches it. Live-reproduced (for the {@code WindowAccess}/{@code Window} case):
   * {@code
   * UserRoleCompositionServiceOverlapReverificationTest#testRealMatrixOverlapSalesAndPurchasingOnProductCategoryStaysReadOnly}
   * — a bystander role ({@code F238CDA0}, "Personal – CompositionUser") already had a correctly-
   * sourced, correctly-leveled row for the template's newly-granted window; the OLD "skip when
   * already correct" branch left it in place; core's own blind {@code copyRoleAccess} then tried
   * to INSERT a second row for the identical {@code (AD_Role_ID, AD_Window_ID)} key and crashed
   * with the same {@code ad_window_access_un_key} violation this mechanism exists to prevent for
   * every entity pair it now defends.
   *
   * <p><b>Why deletion (not {@link #repointInPlace}) is the correct lever here — a DIFFERENT
   * conclusion from {@link #guardRemovedInheritance(RoleInheritance)}'s own "never delete, always
   * repoint in place" rule, for a DIFFERENTLY-SHAPED bug.</b> {@code guardRemovedInheritance}'s
   * duplicate-INSERT race (the "sixth trigger") is about core's {@code calculateAccesses} walking
   * 2+ REMAINING TEMPLATES in ONE call with no flush between passes — repointing in place removes
   * the underlying trigger entirely, because core's OWN {@code isPrecedent} check, once it can
   * see a row sourced from the highest-{@code SeqNo} template, correctly resolves to
   * ACCESS_NOT_CHANGED and never attempts a competing write. That reasoning requires core to be
   * ABLE to see the row. Here, core's blindness is the root cause itself — repointing a row's
   * fields in place changes nothing about whether the row PHYSICALLY EXISTS, so core's blind
   * {@code copyRoleAccess} would still attempt an INSERT against the identical key regardless of
   * what values the surviving row holds. The ONLY way to guarantee core's own (blind) CREATE
   * lands on an empty slot is to ensure no row exists at all before returning control to it —
   * exactly what {@link #deleteForcingCreatePath} already does for the "needs correction" case.
   * Applying it unconditionally simply closes the gap left by the old "already correct" shortcut.
   *
   * <p>Trades a small amount of churn (a correctly-sourced row is deleted and recreated with a
   * fresh id/audit columns instead of being left untouched) for guaranteed safety — acceptable
   * given the alternative is a 500 error on the real {@code SFAssignUserRoles} webhook. The
   * recreated row is corrected right back to the exact same values by {@link
   * #correctInheritedOwnership}/{@link #widenInheritedAccessLevelIfNeeded}, which already run on
   * EVERY freshly-created inherited row regardless of how it was triggered.
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
   * {@code handleAccess}/{@code findInheritedAccess} lookup for (role={@code dependent}, item=
   * {@code item}) finds nothing and takes the CREATE path instead of the corrupting UPDATE path.
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
   *   ever created to replace it (reproduced live: every item this method needed to clear ended
   *   up simply absent from the dependent role afterward, confirmed against the actual DB rows).
   *   </li>
   *   <li>Calling {@code OBDal.flush()} explicitly from here to force visibility is a REENTRANT
   *   {@code Session.flush()} call — one flush() invoked from inside another, still-in-progress
   *   one on the SAME session. This corrupts the OUTER flush's own in-progress action-queue
   *   bookkeeping: reproduced live as a {@code StaleStateException} ("actual row count: 0;
   *   expected: 1") on an UPDATE for a row that demonstrably still existed moments earlier in the
   *   very same flush cycle.</li>
   * </ol>
   * A direct {@code session.createQuery("delete from " + accessEntityName() + " ...")
   * .executeUpdate()} sidesteps BOTH: a bulk HQL DML statement is executed as a single SQL
   * statement immediately, on the current connection/transaction — it does not go through the
   * session's flush/action-queue machinery at all (no {@code EntityDeleteEvent} fires either, so
   * core's own inherited-access delete-protection check (e.g. {@code
   * InheritedAccessEnabledEventHandler}'s "NotDeleteInheritedAccess" check for the {@code
   * WindowAccess} case) — which exists to protect exactly this {@code inheritedFrom != null} case
   * from a NORMAL entity-level delete — never runs, and does not need to: we WANT this specific
   * case deleted regardless of {@code inheritedFrom}). The row is gone from the DB immediately,
   * visible to any subsequent SELECT on the same transaction (including core's), with no
   * reentrant flush anywhere.
   *
   * <p><b>Also refreshes {@code dependent} (the OWNING role) — {@code OBDal.refresh}, NOT {@code
   * evict}.</b> {@code dependent}'s own access collection (see {@link
   * #removeFromOwnerCollection(Role, BaseOBObject)}, e.g. {@code getADWindowAccessList()} for the
   * {@code WindowAccess} case) is frequently ALREADY loaded and cached in this session by the time
   * this method runs — typically because an EARLIER, separate top-level flush already
   * force-initialized it (core's own access-injector equivalent, e.g. {@code
   * WindowAccessInjector#setParent}, calls {@code role.get<Access>List().add(...)} for every row
   * it ever creates, which lazily loads the collection the first time). That cached Java list
   * still holds a reference to {@code existing} even after the bulk delete above — a raw SQL
   * statement does not know or care about Hibernate's separate collection-snapshot bookkeeping.
   * THREE outcomes were tried empirically here, in order:
   * <ol>
   *   <li>Leave the stale reference alone: the next time core touches this SAME collection (e.g.
   *   appending its own newly-created replacement row for a different, non-conflicting item),
   *   Hibernate re-examines the whole collection and finds a member with no valid snapshot —
   *   {@code OBInterceptor} logs "detected as not new... but it does not have a current state in
   *   the database" and still schedules an UPDATE for it, which then fails with {@code
   *   StaleStateException} ("actual row count: 0") once the row's absence surfaces at SQL
   *   execution time.</li>
   *   <li>Remove {@code existing} from the collection explicitly ({@link
   *   #removeFromOwnerCollection(Role, BaseOBObject)}): Hibernate's own orphan-removal cascade
   *   (this collection mapping cascades deletes) detects the missing element against its loaded
   *   snapshot and schedules its OWN {@code session.delete()} for it — which DOES run through
   *   {@code OBInterceptor.onDelete}/{@code SecurityChecker.checkDeleteAllowed}, reproduced live
   *   as the exact {@code OBSecurityException} this whole class exists to prevent in the first
   *   place, just relocated from an update to a delete.</li>
   *   <li>Fully {@code evict(dependent)} (detach the whole entity, not just the collection):
   *   avoids both of the above, but ALSO strips {@code dependent} of its live Hibernate session —
   *   the very next time core's access-injector equivalent calls {@code
   *   role.get<Access>List().add(...)} to register ITS OWN newly-created replacement row, the (now
   *   fully detached) collection cannot lazily re-initialize itself at all, reproduced live as
   *   {@code LazyInitializationException}: "could not initialize proxy - no Session".</li>
   * </ol>
   * {@code OBDal.refresh(dependent)} is the one operation that does what is actually needed:
   * {@code dependent} stays ATTACHED/managed (so a subsequent lazy collection access still has a
   * live session to reload through — no {@code LazyInitializationException}), while its cached
   * collection snapshot is discarded and will be re-fetched from the database on next access —
   * correctly excluding {@code existing}, which the bulk delete above already removed there, with
   * no stale reference left for Hibernate to misinterpret as a pending update OR an orphan needing
   * its own cascade delete. This does not interfere with core's own CREATE for the replacement row
   * either way — {@code copyRoleAccess} (or its Process/OBUIAPP equivalent) persists the new
   * access row via a DIRECT {@code OBDal.save(newAccess)} call, never via cascade from the parent
   * collection; the access-injector's own {@code .add(...)} call is only ever a convenience for
   * keeping the in-memory list accurate for the REST of this same request, not what actually
   * persists the row.
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
   * Originally the SIXTH-trigger fix's own mechanism (see the class javadoc's "A sixth trigger"
   * section for the full root-cause write-up) — now ALSO reused by the seventh trigger's own B7
   * fix, {@link #repointIfAlreadySourcedFromTemplate} (see the class javadoc's "The seventh
   * trigger's own gap, found in REVIEW" section). Corrects {@code existing}'s {@code
   * inheritedFrom} and {@code editableField} to the pre-computed {@code winner}/{@code
   * winnerLevel} DIRECTLY, IN PLACE — the SAME row, SAME primary key — instead of {@link
   * #deleteForcingCreatePath} deleting it and relying on core's OWN propagation to recreate it.
   * Deliberately does NOT reuse {@code deleteForcingCreatePath}: for the sixth trigger, deleting
   * would reopen the exact duplicate-INSERT race this method exists to close (see the class
   * javadoc) whenever 2+ remaining templates both grant {@code item} — core's {@code
   * RoleInheritanceManager#applyRemoveInheritance} walks EVERY remaining template's own grants in
   * ONE {@code calculateAccesses} call, and with {@code existing} deleted, each remaining template
   * covering the same item independently finds no row and issues its OWN {@code copyRoleAccess}
   * INSERT, none of them aware of the others' still-unflushed one ({@code FlushMode.COMMIT} never
   * auto-flushes a query into visibility — see {@link #deleteForcingCreatePath}'s own javadoc for
   * the proof of that same limitation on the delete side); for the seventh trigger's B7 fix,
   * deleting would reopen the ORIGINAL "[B7]" bug this method's caller exists to close — core's
   * {@code propagateUpdatedAccess} has no create fallback at all, so a deleted row here is simply
   * gone forever.
   *
   * <p>Same bulk-DML-bypasses-the-session technique as {@link #deleteForcingCreatePath} (an
   * {@code executeUpdate()} HQL bulk UPDATE, not an entity-level setter + flush), for the identical
   * reason: this runs nested inside an already-in-progress flush under {@code FlushMode.COMMIT},
   * so an entity-level {@code existing.setInheritedFrom(winner)} would only mutate the in-memory
   * Java object — never Hibernate's own dirty-check bookkeeping in a way guaranteed to survive
   * being invoked mid-flush from an unrelated entity's delete-event callback — and a reentrant
   * {@code OBDal.flush()} to force it through is independently unsafe (see {@link
   * #deleteForcingCreatePath}'s javadoc, point 2). A bulk UPDATE writes the SQL immediately on the
   * current connection, visible to every subsequent SELECT in this transaction (including core's
   * own {@code getAccess()} lookups during {@code calculateAccesses}), with zero risk to the outer
   * flush's action queue.
   *
   * <p>Because {@code existing}'s {@code inheritedFrom} is corrected to {@code winner} — by
   * construction ALWAYS the remaining template with the numerically HIGHEST {@code
   * AD_Role_Inheritance.SeqNo} among every remaining template granting {@code item}, regardless
   * of that template's own access level (see the class javadoc's "Why InheritedFrom must track
   * core's own SeqNo precedence" paragraph for why a most-permissive-based pick here is NOT safe
   * — {@code winnerLevel} is where most-permissive-wins is actually applied, decoupled from this)
   * — core's OWN {@code
   * isPrecedent(inheritanceInheritFromIdList, currentInheritedFromId, newInheritedFromId)} check
   * can never find {@code winner}'s index smaller than any OTHER remaining template's index (its
   * index is, by construction, the largest among templates granting this item), so every one of
   * core's own per-template passes over this item resolves to {@code ACCESS_NOT_CHANGED} —
   * core touches this row ZERO times during its own recalculation (sixth-trigger case) or, for the
   * seventh-trigger's B7 case, core's {@code propagateUpdatedAccess}'s own {@code updateRoleAccess}
   * call against this SAME row afterward is a same-value, idempotent no-op. Either way, no {@code
   * EntityNewEvent}/{@code EntityUpdateEvent} is left for {@link #correctInheritedOwnership}/{@link
   * #widenInheritedAccessLevelIfNeeded} to react to — this method does both jobs itself, up front,
   * in one step.
   *
   * <p>{@code client}/{@code organization} are deliberately NOT touched here — {@code existing}
   * already belongs to {@code dependent} (it is an update to an ALREADY-correctly-owned row, never
   * a copy from a template's own row), so there is nothing to re-pin, unlike {@link
   * #correctInheritedOwnership}'s CREATE-path concern.
   *
   * <p><b>Refreshes {@code existing} afterward — {@code OBDal.refresh}, NOT {@code
   * session.evict()} — found empirically (ETP-4906, REVIEW round, "[B7]" fix, 2026-08-17).</b> An
   * earlier version of the seventh-trigger's B7 fix reused {@code session.evict(existing)} here
   * unchanged (the same call the sixth trigger's own {@code onDelete}-triggered caller uses safely)
   * — reproduced live (for the {@code WindowAccess} case) as {@code HibernateException: Don't
   * change the reference to a collection with delete-orphan enabled :
   * ADWindowAccess.aDTabAccessList} at the OUTER flush, specifically on the B7 fix's own {@code
   * onUpdate}-triggered path, never on the sixth trigger's {@code onDelete} path. Root cause
   * (confirmed by bisection — the crash disappeared when the {@code evict} call was removed, and
   * reappeared only once {@code repointInPlace} was called again from the {@code onUpdate}
   * trigger): {@code Interceptor#onFlushDirty} (which is how {@code onUpdate}'s {@code
   * EntityUpdateEvent} reaches this class) fires FROM WITHIN Hibernate's own {@code
   * AbstractFlushingEventListener#flushEntities} loop — the SAME loop that walks every OTHER
   * managed entity's collection-valued properties for reachability, {@code existing} included, in
   * the SAME pass. Evicting {@code existing} mid-loop rips it out of the persistence context while
   * Hibernate's own flush-time bookkeeping still expects to examine it, corrupting the collection
   * tracking. {@code Interceptor#onSave} (how the sixth trigger's sibling ADD-side triggers and
   * {@code deleteForcingCreatePath} reach this class) fires synchronously at {@code save()}/{@code
   * delete()} call time, OUTSIDE that loop entirely — evicting there never collides with it, which
   * is why the identical {@code evict()} call is safe on those paths but not here. {@code
   * OBDal.refresh(existing)} avoids the collision entirely: it re-syncs {@code existing}'s scalar
   * fields from the DB (reflecting the bulk UPDATE above, visible on the same connection/
   * transaction) while keeping it ATTACHED and managed, so the flush's own collection-reachability
   * bookkeeping for it stays consistent throughout — confirmed safe for BOTH callers by re-running
   * the full existing regression suite in this class after the change.
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
