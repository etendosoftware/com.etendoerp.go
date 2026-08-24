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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
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
import com.etendoerp.go.roles.overlap.PropagationTrigger;
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
 * <p><b>Scope: full ADD/UPDATE/REMOVE-path parity with {@code
 * WindowAccessOverlapCorruptionGuard}.</b> {@link #onSave(EntityNewEvent)} covers the ADD path —
 * ownership correction for a newly-inherited dependent row, most-permissive-wins widening, and
 * unconditional dependent-clearing (safe here because core's {@code propagateNewAccess} always
 * falls back to a CREATE) when a template gains a brand-new grant or a role gains a new
 * inheritance ({@code guardNewInheritance}). {@link #onUpdate(EntityUpdateEvent)} covers the
 * UPDATE path — when a template's own existing grant changes access level, {@code
 * guardDependentsOf}'s {@code UPDATED_GRANT} branch repoints an already-correctly-sourced
 * dependent row in place ({@code repointIfAlreadySourcedFromTemplate}) rather than deleting it,
 * since core's {@code propagateUpdatedAccess} has no create fallback and would otherwise leave
 * the dependent with nothing to restore its access. {@link #onDelete(EntityDeleteEvent)} covers
 * the REMOVE path — the original duplicate-INSERT race documented above. All three mirror the
 * SAME failure signatures ({@code OBSecurityException}, {@code ConstraintViolationException} on
 * {@code AD_PROCESS_ACCESS_UN_KEY}, or a silently wrong access level) that {@code
 * WindowAccessOverlapCorruptionGuard} closes for {@code AD_Window_Access}.
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
          ModelProvider.getInstance().getEntity(ProcessAccess.ENTITY_NAME),
          ModelProvider.getInstance().getEntity(RoleInheritance.ENTITY_NAME) };
    }
    return entities;
  }

  @Override
  protected Entity[] getObservedEntities() {
    return resolveEntities();
  }

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
   * javadoc. Called with {@link PropagationTrigger#NEW_GRANT} from {@link
   * #onSave(EntityNewEvent)} and with {@link PropagationTrigger#UPDATED_GRANT} from {@link
   * #onUpdate(EntityUpdateEvent)}.
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
    for (Role dependent : findActiveDependentRoles(role)) {
      if (trigger == PropagationTrigger.NEW_GRANT) {
        clearConflictingAccessUnconditionally(dependent, process, role);
      } else {
        repointIfAlreadySourcedFromTemplate(dependent, process, role, templateAccess);
      }
    }
  }

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

  /**
   * Mirrors {@code WindowAccessOverlapCorruptionGuard#findActiveDependentRoles} — see that
   * method's own javadoc for the cross-client criteria rationale.
   */
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
   * result, which is declared {@code Object} — matches {@code
   * WindowAccessOverlapCorruptionGuard#sameId(BaseOBObject, Object)}'s own overload.
   */
  private static boolean sameId(BaseOBObject expected, Object actualState) {
    if (!(actualState instanceof BaseOBObject)) {
      return false;
    }
    return sameId(expected, (BaseOBObject) actualState);
  }
}
