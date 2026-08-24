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
