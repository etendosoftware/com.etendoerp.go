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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.criterion.Restrictions;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.ClientEnabled;
import org.openbravo.base.structure.OrganizationEnabled;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.RoleInheritance;

/**
 * ETP-5507 — decides when the ADD-path clear may leave a dependent role's existing access row in
 * place for a new {@code AD_Role_Inheritance}, instead of deleting it to force core onto its
 * CREATE path. Shared by the overlap guards' {@code guardNewInheritance} (window, process, OBUIAPP
 * process) and the service's window pre-clear, so both apply the exact same rule.
 *
 * <p><b>Why a skip is safe again, under these conditions only.</b> The unconditional delete
 * (ETP-4906, "seventh trigger") exists because core's {@code RoleInheritanceManager#handleAccess}
 * cannot always SEE the dependent's row: its {@code findAccess} query filters by the caller's
 * readable clients and organizations. When it is blind it always CREATEs and hits the unique key;
 * when it sees a row it can take the corrupting UPDATE path. Neither happens when core sees a row
 * sourced from a template that precedes the new one: {@code isPrecedent(list, current, new)}
 * returns {@code false} when the current source has the higher {@code SeqNo}, so core resolves the
 * item to {@code ACCESS_NOT_CHANGED} and does not write at all. A row is kept only when ALL hold:
 * <ol>
 *   <li>its {@code InheritedFrom} is an ACTIVE template inheritance of the dependent (not one
 *   {@link TemplateRemovalTracker} reports as being removed);</li>
 *   <li>that inheritance's {@code SeqNo} is greater than the new inheritance's;</li>
 *   <li>the row is at least as permissive as the incoming grant — a read-only row facing a full
 *   grant is deleted as before, so core recreates it at full level (processes have no final
 *   most-permissive pass that would widen it later);</li>
 *   <li>core can see both the row and that inheritance: their client is in {@link
 *   OBContext#getReadableClients()} and their organization in {@link
 *   OBContext#getReadableOrganizations()}. Core also reads the inheritance list through the
 *   default readable filters ({@code getUpdatedRoleInheritancesList}); if the higher inheritance
 *   were invisible, {@code isPrecedent} would see the current source as unknown and UPDATE.</li>
 * </ol>
 *
 * <p><b>Rule 4 per row vs. per role.</b> The service decides the add order once, from the role's
 * own client and organization ({@link #isVisibleToCore(Role)}); rule 4 then re-checks each row
 * and its inheritance. Both give the same answer as long as every inherited row and inheritance is
 * pinned to the role's client/organization ({@code AbstractAccessOverlapCorruptionGuard
 * #correctInheritedOwnership} pins each new row; the service creates each inheritance with the
 * role's). If that pinning ever broke, a row failing rule 4 inside a descending batch would be
 * deleted and recreated by the lower template: safe (core takes its CREATE path), but sourced
 * from the lower template instead of the higher one.</p>
 *
 * <p>Only ever true for a batch of templates added in one call in descending precedence: a new
 * inheritance always gets the highest {@code SeqNo} of the role, so rule 2 never holds against a
 * pre-existing template. One instance per new inheritance: the dependent's inheritances and the
 * caller's readable sets are loaded once, never per row.
 */
public final class HigherPrecedenceSkip {

  private static final HigherPrecedenceSkip NEVER =
      new HigherPrecedenceSkip(Collections.emptyMap(), Collections.emptySet(),
          Collections.emptySet());

  private final Map<String, RoleInheritance> precedingInheritanceByTemplateId;
  private final Set<String> readableClientIds;
  private final Set<String> readableOrganizationIds;

  private HigherPrecedenceSkip(Map<String, RoleInheritance> precedingInheritanceByTemplateId,
      Set<String> readableClientIds, Set<String> readableOrganizationIds) {
    this.precedingInheritanceByTemplateId = precedingInheritanceByTemplateId;
    this.readableClientIds = readableClientIds;
    this.readableOrganizationIds = readableOrganizationIds;
  }

  /**
   * Loads, once, the active template inheritances of {@code dependent} whose {@code SeqNo} is
   * greater than {@code newSequenceNumber}, and the caller's readable clients and organizations.
   *
   * @param dependent
   *          the role gaining the new inheritance
   * @param newSequenceNumber
   *          the {@code SeqNo} of the inheritance being added; {@code null} disables every skip
   * @return the rule for that inheritance; one that never skips when nothing can precede it
   */
  @SuppressWarnings("unchecked")
  public static HigherPrecedenceSkip forNewInheritance(Role dependent, Long newSequenceNumber) {
    OBContext context = OBContext.getOBContext();
    if (dependent == null || newSequenceNumber == null || context == null) {
      return NEVER;
    }
    OBCriteria<RoleInheritance> criteria =
        ActiveTemplateInheritance.crossClientCriteria(RoleInheritance.class);
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ROLE, dependent));
    criteria.add(Restrictions.eq(RoleInheritance.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.gt(RoleInheritance.PROPERTY_SEQUENCENUMBER, newSequenceNumber));
    Map<String, RoleInheritance> preceding = new HashMap<>();
    for (RoleInheritance inheritance : (List<RoleInheritance>) criteria.list()) {
      Role template = inheritance.getInheritFrom();
      if (template != null && Boolean.TRUE.equals(template.isTemplate())
          && !TemplateRemovalTracker.isBeingRemoved(template.getId())) {
        preceding.put(template.getId(), inheritance);
      }
    }
    if (preceding.isEmpty()) {
      return NEVER;
    }
    return new HigherPrecedenceSkip(preceding, idSet(context.getReadableClients()),
        idSet(context.getReadableOrganizations()));
  }

  /**
   * Whether core can see {@code dependent}'s own rows from the current context (rule 4 at role
   * level). The service adds a batch of templates in descending precedence only when this holds:
   * otherwise nothing can be kept, and a descending add would leave each shared item sourced from
   * the LOWEST template instead of the highest, so the batch keeps today's ascending order.
   *
   * @param dependent
   *          the role gaining the new inheritances
   * @return {@code true} when {@code dependent}'s client and organization are readable
   */
  public static boolean isVisibleToCore(Role dependent) {
    OBContext context = OBContext.getOBContext();
    return dependent != null && context != null && isVisible(dependent,
        idSet(context.getReadableClients()), idSet(context.getReadableOrganizations()));
  }

  /**
   * Whether the dependent's {@code existing} row may stay in place for the new inheritance.
   *
   * @param existing
   *          the dependent's active access row for the item
   * @param existingSource
   *          its {@code InheritedFrom}, or {@code null} for a manually granted row
   * @param existingEditable
   *          its {@code editableField}
   * @param incomingEditable
   *          the new template's {@code editableField} for the same item
   * @return {@code true} only when all 4 rules in the class javadoc hold
   */
  public boolean keepsExisting(BaseOBObject existing, Role existingSource,
      Boolean existingEditable, Boolean incomingEditable) {
    if (existingSource == null) {
      return false;
    }
    RoleInheritance preceding = precedingInheritanceByTemplateId.get(existingSource.getId());
    if (preceding == null) {
      return false;
    }
    if (Boolean.TRUE.equals(incomingEditable) && !Boolean.TRUE.equals(existingEditable)) {
      return false;
    }
    return isVisible(existing, readableClientIds, readableOrganizationIds)
        && isVisible(preceding, readableClientIds, readableOrganizationIds);
  }

  private static boolean isVisible(BaseOBObject row, Set<String> readableClientIds,
      Set<String> readableOrganizationIds) {
    if (!(row instanceof ClientEnabled) || !(row instanceof OrganizationEnabled)) {
      return false;
    }
    BaseOBObject client = ((ClientEnabled) row).getClient();
    BaseOBObject organization = ((OrganizationEnabled) row).getOrganization();
    return client != null && organization != null
        && readableClientIds.contains((String) client.getId())
        && readableOrganizationIds.contains((String) organization.getId());
  }

  private static Set<String> idSet(String[] ids) {
    return ids == null ? Collections.emptySet() : new HashSet<>(Arrays.asList(ids));
  }
}
