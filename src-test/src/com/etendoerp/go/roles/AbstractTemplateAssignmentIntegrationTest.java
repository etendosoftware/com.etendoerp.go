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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.hibernate.stat.Statistics;
import org.junit.After;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;

import com.etendoerp.go.roles.overlap.TemplateRemovalTracker;

/**
 * Shared fixtures for the real-template {@code assignTemplateRoles} integration tests (ETP-5503,
 * ETP-5507): one-call-per-request assignment, Hibernate statement counting, and the access-set
 * assertions derived from the templates' own current grants.
 *
 * <p>Extends {@link WeldBaseTest}: under a plain {@code OBBaseTest} core's {@code
 * RoleInheritanceEventHandler} does not fire, so nothing propagates and every assertion built on
 * these helpers would pass vacuously.
 */
public abstract class AbstractTemplateAssignmentIntegrationTest extends WeldBaseTest {

  protected static final String OBUIAPP_PROCESS_ACCESS =
      org.openbravo.client.application.ProcessAccess.ENTITY_NAME;

  /** Access entity name → the property of that entity pointing at the granted item. */
  protected static final Map<String, String> ITEM_PROPERTY_BY_ENTITY = Map.of(
      WindowAccess.ENTITY_NAME, WindowAccess.PROPERTY_WINDOW,
      ProcessAccess.ENTITY_NAME, ProcessAccess.PROPERTY_PROCESS,
      OBUIAPP_PROCESS_ACCESS,
      org.openbravo.client.application.ProcessAccess.PROPERTY_OBUIAPPPROCESS);

  protected static final List<String> ALL_TEMPLATES = Arrays.asList(
      SystemRoleTemplates.SALES_ROLE_ID, SystemRoleTemplates.PURCHASING_ROLE_ID,
      SystemRoleTemplates.FINANCE_ROLE_ID, SystemRoleTemplates.INVENTORY_ROLE_ID);

  private static final int MAX_ADMIN_MODE_POPS = 10;

  @After
  public void rollbackChanges() {
    statistics().setStatisticsEnabled(false);
    // Bounded: once the stack is empty, restorePreviousMode() only logs an "unbalanced" warning
    // and never flips isInAdministratorMode() back (e.g. after a test swapped the OBContext), so
    // an unbounded loop spins forever — see
    // RoleInheritanceReconciliationServiceSystemContextIntegrationTest#rollbackChanges.
    for (int pops = 0; pops < MAX_ADMIN_MODE_POPS && OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode(); pops++) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  protected static final class AssignmentOutcome {
    private final UserRoleCompositionService.AssignmentResult result;

    AssignmentOutcome(UserRoleCompositionService.AssignmentResult result) {
      this.result = result;
    }

    UserRoleCompositionService.AssignmentResult result() {
      return result;
    }

    String personalRoleId() {
      return result.personalRoleId;
    }

    Role personalRole() {
      return OBDal.getInstance().get(Role.class, result.personalRoleId);
    }
  }

  /**
   * One {@code assignTemplateRoles} call in a fresh session (flushed and cleared first), so each
   * call starts with no loaded role collections, like a separate webhook request.
   */
  protected AssignmentOutcome assign(UserRoleCompositionService service, List<String> templates) {
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().clear();
    AssignmentOutcome outcome = new AssignmentOutcome(
        service.assignTemplateRoles(TEST_USER_ID, templates));
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().clear();
    endSimulatedRequest();
    return outcome;
  }

  /**
   * What {@code onTransactionComplete} does at the end of a real request. A test never commits,
   * so without it a template removed by one call would still count as "being removed" when a
   * later call adds it back, and every guard would ignore it (see {@link TemplateRemovalTracker}).
   */
  protected static void endSimulatedRequest() {
    TemplateRemovalTracker.clear();
  }

  /**
   * Prepared-statement count of one {@link #assign} call. Hibernate statistics are
   * SessionFactory-global and off by default, so they are only switched on around this single,
   * single-threaded call.
   */
  protected long countStatements(UserRoleCompositionService service, List<String> templates) {
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().clear();
    Statistics statistics = statistics();
    statistics.setStatisticsEnabled(true);
    statistics.clear();
    try {
      service.assignTemplateRoles(TEST_USER_ID, templates);
      return statistics.getPrepareStatementCount();
    } finally {
      statistics.setStatisticsEnabled(false);
      endSimulatedRequest();
    }
  }

  protected static Statistics statistics() {
    return OBDal.getInstance().getSession().getSessionFactory().getStatistics();
  }

  /** Classic + OBUIAPP process rows {@code role} already has for an item {@code templateId} also
   *  grants: the rows the guard has to clear when {@code templateId} is added. */
  protected int countOverlappingProcessRows(Role role, String templateId) {
    int overlapping = 0;
    for (String entityName : Arrays.asList(ProcessAccess.ENTITY_NAME, OBUIAPP_PROCESS_ACCESS)) {
      Set<String> templateItems = grantsByItem(entityName, templateId).keySet();
      for (String item : activeItems(entityName, role.getId())) {
        if (templateItems.contains(item)) {
          overlapping++;
        }
      }
    }
    return overlapping;
  }

  /**
   * Asserts {@code role}'s rows of the 3 guarded types are exactly the union of {@code
   * templateIds}' grants: one active row per item, owned by {@code role}'s client/org, at the most
   * permissive level any of them grants, sourced from a template that grants the item at that
   * level.
   */
  protected void assertMatchesTemplateUnion(Role role, List<String> templateIds) {
    for (Map.Entry<String, String> type : ITEM_PROPERTY_BY_ENTITY.entrySet()) {
      String entityName = type.getKey();
      Map<String, Map<String, Boolean>> expected = new TreeMap<>();
      for (String templateId : templateIds) {
        for (Map.Entry<String, Boolean> grant : grantsByItem(entityName, templateId).entrySet()) {
          expected.computeIfAbsent(grant.getKey(), key -> new TreeMap<>())
              .put(templateId, grant.getValue());
        }
      }

      List<Object[]> rows = rowsOf(entityName, role.getId());
      Set<String> seen = new HashSet<>();
      for (Object[] row : rows) {
        String item = (String) row[0];
        String source = (String) row[1];
        Boolean editable = (Boolean) row[2];
        if (source == null) {
          continue;
        }
        assertTrue(entityName + " item " + item + " appears twice on the role", seen.add(item));
        Map<String, Boolean> grantors = expected.get(item);
        assertNotNull(entityName + " item " + item + " is not granted by any assigned template",
            grantors);
        boolean mostPermissive = grantors.containsValue(Boolean.TRUE);
        assertEquals(entityName + " item " + item + " must resolve most-permissive-wins",
            mostPermissive, Boolean.TRUE.equals(editable));
        assertTrue(entityName + " item " + item + " is sourced from " + source
            + ", which does not grant it", grantors.containsKey(source));
        assertEquals(entityName + " item " + item + " is sourced from a template whose level "
            + "does not justify the row's level", mostPermissive,
            Boolean.TRUE.equals(grantors.get(source)));
        assertEquals(entityName + " item " + item + " client", role.getClient().getId(), row[3]);
        assertEquals(entityName + " item " + item + " organization",
            role.getOrganization().getId(), row[4]);
        assertEquals(entityName + " item " + item + " active", Boolean.TRUE, row[5]);
      }
      assertEquals(entityName + ": every template-granted item must be on the role",
          expected.keySet(), seen);
    }
  }

  /** item id → "inheritedFrom|editable|client|org|active" for the 3 guarded types. */
  protected Map<String, String> snapshot(Role role) {
    Map<String, String> snapshot = new TreeMap<>();
    for (String entityName : ITEM_PROPERTY_BY_ENTITY.keySet()) {
      for (Object[] row : rowsOf(entityName, role.getId())) {
        String key = entityName + ":" + row[0];
        String value = row[1] + "|" + row[2] + "|" + row[3] + "|" + row[4] + "|" + row[5];
        assertFalse(key + " appears twice on the role", snapshot.containsKey(key));
        snapshot.put(key, value);
      }
    }
    return snapshot;
  }

  /** item, inheritedFrom, editableField, client, organization, active — for every row. */
  @SuppressWarnings("unchecked")
  protected List<Object[]> rowsOf(String entityName, String roleId) {
    String itemProperty = ITEM_PROPERTY_BY_ENTITY.get(entityName);
    return new ArrayList<>(OBDal.getInstance().getSession()
        .createQuery("select a." + itemProperty + ".id, source.id, a.editableField, a.client.id, "
            + "a.organization.id, a.active from " + entityName
            + " a left join a.inheritedFrom source where a.role.id = :role")
        .setParameter("role", roleId)
        .list());
  }

  @SuppressWarnings("unchecked")
  protected List<String> activeItems(String entityName, String roleId) {
    return OBDal.getInstance().getSession()
        .createQuery("select a." + ITEM_PROPERTY_BY_ENTITY.get(entityName) + ".id from "
            + entityName + " a where a.role.id = :role and a.active = true")
        .setParameter("role", roleId)
        .list();
  }

  /** item id → editableField of {@code templateId}'s active grants. */
  @SuppressWarnings("unchecked")
  protected Map<String, Boolean> grantsByItem(String entityName, String templateId) {
    Map<String, Boolean> grants = new TreeMap<>();
    List<Object[]> rows = OBDal.getInstance().getSession()
        .createQuery("select a." + ITEM_PROPERTY_BY_ENTITY.get(entityName) + ".id, "
            + "a.editableField from " + entityName + " a where a.role.id = :role "
            + "and a.active = true")
        .setParameter("role", templateId)
        .list();
    for (Object[] row : rows) {
      grants.put((String) row[0], Boolean.TRUE.equals(row[1]));
    }
    return grants;
  }
}
