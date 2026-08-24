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
import org.openbravo.client.application.ProcessAccess;
import org.openbravo.client.kernel.event.EntityDeleteEvent;
import org.openbravo.client.kernel.event.EntityNewEvent;
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
import com.etendoerp.go.roles.overlap.PropagationTrigger;
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

  /**
   * Mirrors {@code ProcessAccessOverlapCorruptionGuard#onSave(EntityNewEvent)} — see that
   * method's own javadoc. A NEW {@code OBUIAPP_Process_Access} row on a template, or a NEW
   * {@code AD_Role_Inheritance} row on any role, are the two places core's own propagation can
   * start a corrupting UPDATE against a role this class never even knows is at risk.
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
   * Mirrors {@code ProcessAccessOverlapCorruptionGuard#correctInheritedOwnership} — see that
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
            + "organization back to the role's own (template-derived row, inherited from {})",
        owner.getId(), access.getObuiappProcess() != null ? access.getObuiappProcess().getId()
            : null, access.getInheritedFrom().getId());
  }

  private static Entity obuiappProcessAccessEntity() {
    return ModelProvider.getInstance().getEntity(ProcessAccess.ENTITY_NAME);
  }

  /**
   * Mirrors {@code ProcessAccessOverlapCorruptionGuard#widenInheritedAccessLevelIfNeeded} — see
   * that method's own javadoc for the full rationale (most-permissive-wins, InheritedFrom
   * bookkeeping).
   */
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
            + "InheritedFrom from {} to {}: another currently-inherited template already grants "
            + "this process full access",
        owner.getId(), process.getId(), originalSource.getId(), justifyingTemplate.getId());
  }

  private Role findActiveTemplateGrantingFullAccess(Role dependent, Process process) {
    return findActiveTemplateGrantingFullAccess(dependent, process, null);
  }

  /**
   * Mirrors {@code ProcessAccessOverlapCorruptionGuard#findActiveTemplateGrantingFullAccess} —
   * see that method's own javadoc.
   */
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

  /**
   * Mirrors {@code ProcessAccessOverlapCorruptionGuard#guardNewInheritance} — see that method's
   * own javadoc.
   */
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

  /**
   * Mirrors {@code ProcessAccessOverlapCorruptionGuard#guardDependentsOf} — see that method's own
   * javadoc. Called with {@link PropagationTrigger#NEW_GRANT} from {@link
   * #onSave(EntityNewEvent)}. Unlike the sibling class, there is no {@code onUpdate} handler yet
   * (Task 7), so {@link PropagationTrigger#UPDATED_GRANT} is never passed here.
   */
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

  /**
   * Mirrors {@code ProcessAccessOverlapCorruptionGuard#clearConflictingAccessUnconditionally} —
   * see that method's own javadoc for why "already correct" is not a reason to skip.
   */
  private void clearConflictingAccessUnconditionally(Role dependent, Process process,
      Role grantingTemplate) {
    ProcessAccess existing = findActiveObuiappProcessAccess(dependent, process);
    if (existing == null) {
      return;
    }
    deleteForcingCreatePath(existing, dependent, process, grantingTemplate,
        existing.getInheritedFrom());
  }

  /**
   * Mirrors {@code ProcessAccessOverlapCorruptionGuard#deleteForcingCreatePath} — see that
   * method's own javadoc for the full bulk-HQL-vs-reentrant-flush rationale and the
   * refresh-not-evict collection-management reasoning. Uses {@code
   * getOBUIAPPProcessAccessList()} (confirmed at {@code
   * src-gen/org/openbravo/model/ad/access/Role.java:996}) — the OBUIAPP equivalent of {@code
   * getADProcessAccessList()}.
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
    dependent.getOBUIAPPProcessAccessList().remove(existing);
    OBDal.getInstance().refresh(dependent);
    OBDal.getInstance().getSession().evict(existing);
    log.info(
        "Prevented cross-template OBUIAPP_Process_Access overlap corruption: cleared role {} "
            + "process {} access (previously {}) before template {}'s own grant propagates, "
            + "forcing core onto the safe CREATE path",
        dependent.getId(), process.getId(),
        previousSource != null ? "inherited from " + previousSource.getId() : "manually granted",
        template.getId());
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

  /**
   * Mirrors {@code ProcessAccessOverlapCorruptionGuard#findActiveDependentRoles} — see that
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
   * ProcessAccessOverlapCorruptionGuard#sameId(BaseOBObject, Object)}'s own overload.
   */
  private static boolean sameId(BaseOBObject expected, Object actualState) {
    if (!(actualState instanceof BaseOBObject)) {
      return false;
    }
    return sameId(expected, (BaseOBObject) actualState);
  }
}
