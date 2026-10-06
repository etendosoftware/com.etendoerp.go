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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.stat.Statistics;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;

/**
 * ETP-5507 — several templates added in ONE {@code assignTemplateRoles} call. The service adds
 * them in descending precedence (highest {@code SeqNo} first), and the add-path clears skip a row
 * that is already sourced from a higher-precedence template of the same call, at least as
 * permissive as the incoming grant, and visible to core. Core then resolves that item to {@code
 * ACCESS_NOT_CHANGED} instead of the item being copied, deleted and copied again once per
 * template that grants it.
 *
 * <p>Runs against the 4 real system templates as they exist in the test DB; every expected count
 * is derived from the templates' own current grants, never hardcoded.
 */
public class AssignTemplateRolesPrecedenceSkipIntegrationTest
    extends AbstractTemplateAssignmentIntegrationTest {

  private static final String SYSTEM_ID = "0";

  /**
   * 0 → 4 in one call must create each item once, plus one extra create per item where a lower
   * precedence template grants full access over a read-only higher one (the permissiveness
   * fallback). Before ETP-5507 every template row was created: the sum over the 4 templates.
   */
  @Test
  public void testAddingFourTemplatesAtOnceCreatesEachItemOnce() {
    setTestUserContext();
    UserRoleCompositionService service = new UserRoleCompositionService();
    assign(service, List.of());

    Map<String, Long> expected = expectedCreatesInDescendingPrecedence(ALL_TEMPLATES);
    Map<String, Long> summed = summedTemplateRows(ALL_TEMPLATES);
    assertTrue("Precondition: the 4 templates must overlap, otherwise there is nothing to skip",
        total(expected) < total(summed));

    Map<String, Long> inserts = countInserts(service, ALL_TEMPLATES);

    assertEquals("Rows created per access type (expected " + expected + ", sum of template rows "
        + summed + ")", expected, inserts);
  }

  /**
   * Golden equivalence, windows included: one call with the 4 templates must leave exactly the
   * access set of one template per call — {@code InheritedFrom} of window rows too, which the
   * ascending add order used to get wrong (a full window row stayed sourced from a read-only
   * grantor).
   */
  @Test
  public void testAssigningFourTemplatesAtOnceMatchesOneByOneIncludingWindowSources() {
    setTestUserContext();
    UserRoleCompositionService service = new UserRoleCompositionService();
    assign(service, List.of());

    AssignmentOutcome cumulative = null;
    for (int count = 1; count <= ALL_TEMPLATES.size(); count++) {
      cumulative = assign(service, ALL_TEMPLATES.subList(0, count));
    }
    assertNotNull(cumulative);
    Map<String, String> oneByOne = snapshot(cumulative.personalRole());

    assign(service, List.of());
    AssignmentOutcome atOnce = assign(service, ALL_TEMPLATES);

    assertEquals(oneByOne, snapshot(atOnce.personalRole()));
  }

  /**
   * Rule 3 for processes (no real template data covers it: every template process grant is full).
   * A process the higher-precedence template grants read-only and a lower one grants full must end
   * full and sourced from the lower one — the same outcome as one template per call. Processes
   * have no final most-permissive pass, so a wrongly skipped row would stay read-only.
   */
  @Test
  public void testReadOnlyProcessOnTheHigherTemplateStillEndsFullFromTheLowerOne() {
    setTestUserContext();
    UserRoleCompositionService service = new UserRoleCompositionService();
    assign(service, List.of());
    String lower = SystemRoleTemplates.SALES_ROLE_ID;
    String higher = SystemRoleTemplates.FINANCE_ROLE_ID;
    String processId = firstSharedFullProcess(lower, higher);
    downgradeTemplateProcessWithoutEvents(higher, processId);

    assign(service, List.of(lower));
    AssignmentOutcome oneByOne = assign(service, List.of(lower, higher));
    Map<String, String> expected = snapshot(oneByOne.personalRole());

    assign(service, List.of());
    AssignmentOutcome atOnce = assign(service, List.of(lower, higher));

    Object[] row = processRow(atOnce.personalRoleId(), processId);
    assertEquals("The process must be sourced from the full-granting lower template", lower,
        row[1]);
    assertEquals("The process must resolve most-permissive-wins", Boolean.TRUE, row[2]);
    assertEquals(expected, snapshot(atOnce.personalRole()));
  }

  /**
   * Rule 4: when the caller's context cannot read the personal role's client (a System
   * Administrator composing a tenant user), core's own {@code findAccess} is blind to the rows, so
   * nothing may be skipped — otherwise core would INSERT a duplicate — and the templates must be
   * added in request order: added in descending order with nothing kept, each shared item would
   * end sourced from the LOWEST template. The reconciliation must succeed, take the old
   * delete-and-copy path (every template row created) and leave the same access set as one
   * template per call.
   *
   * <p>Drives {@link RoleInheritanceReconciliationService} directly: the full {@code
   * assignTemplateRoles} already fails in this context for an unrelated reason (its {@code
   * AD_User_Roles} sync cannot see the existing row and INSERTs a duplicate).
   */
  @Test
  public void testBlindCallerContextSkipsNothingAndStillSucceeds() {
    setTestUserContext();
    UserRoleCompositionService service = new UserRoleCompositionService();
    assign(service, List.of());
    AssignmentOutcome cumulative = null;
    for (int count = 1; count <= ALL_TEMPLATES.size(); count++) {
      cumulative = assign(service, ALL_TEMPLATES.subList(0, count));
    }
    assertNotNull(cumulative);
    Map<String, String> oneByOne = snapshot(cumulative.personalRole());
    String personalRoleId = assign(service, List.of()).personalRoleId();
    String tenantClientId = OBDal.getInstance().get(Role.class, personalRoleId).getClient().getId();

    OBContext.setOBContext(SYSTEM_ID, SYSTEM_ID, SYSTEM_ID, SYSTEM_ID);
    assertFalse("Precondition: the system context must not read the tenant's client",
        Arrays.asList(OBContext.getOBContext().getReadableClients()).contains(tenantClientId));
    Statistics statistics = statistics();
    statistics.setStatisticsEnabled(true);
    statistics.clear();
    Map<String, Long> inserts;
    OBContext.setAdminMode(true);
    try {
      List<Role> templates = new ArrayList<>();
      for (String templateId : ALL_TEMPLATES) {
        templates.add(OBDal.getInstance().get(Role.class, templateId));
      }
      new RoleInheritanceReconciliationService().reconcileInheritances(
          OBDal.getInstance().get(Role.class, personalRoleId), templates);
      OBDal.getInstance().flush();
      inserts = insertsPerAccessType(statistics);
    } finally {
      OBContext.restorePreviousMode();
      statistics.setStatisticsEnabled(false);
    }

    assertEquals("A blind caller must copy every template row, like before ETP-5507",
        summedTemplateRows(ALL_TEMPLATES), inserts);
    OBDal.getInstance().getSession().clear();
    setTestUserContext();
    Role personalRole = OBDal.getInstance().get(Role.class, personalRoleId);
    assertMatchesTemplateUnion(personalRole, ALL_TEMPLATES);
    assertEquals("A blind caller must keep request order, so the highest template still wins",
        oneByOne, snapshot(personalRole));
  }

  /**
   * Keep one, remove one and add two in the same call: [Sales, Purchasing] → [Purchasing,
   * Finance, Inventory]. Same end state as removing Sales, then adding Finance, then Inventory,
   * one call each (both paths give Purchasing 20, Finance 30, Inventory 40).
   */
  @Test
  public void testKeepingRemovingAndAddingTwoInOneCallMatchesOneStepPerCall() {
    setTestUserContext();
    UserRoleCompositionService service = new UserRoleCompositionService();
    List<String> start = Arrays.asList(SystemRoleTemplates.SALES_ROLE_ID,
        SystemRoleTemplates.PURCHASING_ROLE_ID);
    List<String> templates = Arrays.asList(SystemRoleTemplates.PURCHASING_ROLE_ID,
        SystemRoleTemplates.FINANCE_ROLE_ID, SystemRoleTemplates.INVENTORY_ROLE_ID);
    Map<String, String> oneStepPerCall = snapshotAfter(service, start,
        List.of(SystemRoleTemplates.PURCHASING_ROLE_ID), templates.subList(0, 2), templates);

    assign(service, List.of());
    assign(service, start);
    AssignmentOutcome outcome = assign(service, templates);

    assertEquals(2, outcome.result().addedCount);
    assertEquals(1, outcome.result().removedCount);
    assertMatchesTemplateUnion(outcome.personalRole(), templates);
    assertEquals(oneStepPerCall, snapshot(outcome.personalRole()));
  }

  /**
   * Three templates added onto one the role already has: the pre-existing template has the lowest
   * {@code SeqNo}, so its rows are never kept for the new ones (rule 2) and take the delete path,
   * while the new ones skip among themselves.
   */
  @Test
  public void testAddingThreeOntoAnExistingTemplateMatchesOneByOne() {
    setTestUserContext();
    UserRoleCompositionService service = new UserRoleCompositionService();
    Map<String, String> oneByOne = snapshotAfter(service, ALL_TEMPLATES.subList(0, 1),
        ALL_TEMPLATES.subList(0, 2), ALL_TEMPLATES.subList(0, 3), ALL_TEMPLATES);

    assign(service, List.of());
    assign(service, ALL_TEMPLATES.subList(0, 1));
    AssignmentOutcome atOnce = assign(service, ALL_TEMPLATES);

    assertEquals(3, atOnce.result().addedCount);
    assertEquals(oneByOne, snapshot(atOnce.personalRole()));
  }

  /**
   * Precedence follows the request order, not the template: [Inventory, Finance, Purchasing,
   * Sales] at once must match adding them one by one in that same order.
   */
  @Test
  public void testReversedRequestOrderMatchesOneByOneInThatOrder() {
    setTestUserContext();
    UserRoleCompositionService service = new UserRoleCompositionService();
    List<String> reversed = new ArrayList<>(ALL_TEMPLATES);
    Collections.reverse(reversed);
    Map<String, String> oneByOne = snapshotAfter(service, reversed.subList(0, 1),
        reversed.subList(0, 2), reversed.subList(0, 3), reversed);

    assign(service, List.of());
    AssignmentOutcome atOnce = assign(service, reversed);

    assertEquals(oneByOne, snapshot(atOnce.personalRole()));
    assertMatchesTemplateUnion(atOnce.personalRole(), reversed);
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  /**
   * Rows each access type should create when {@code templates} (in request order, so ascending
   * {@code SeqNo}) are added to an empty role in descending precedence: an item is created the
   * first time it is seen, and again only when a lower template grants it full over a read-only
   * row.
   */
  private Map<String, Long> expectedCreatesInDescendingPrecedence(List<String> templates) {
    List<String> descending = new ArrayList<>(templates);
    Collections.reverse(descending);
    Map<String, Long> creates = new HashMap<>();
    for (String entityName : ITEM_PROPERTY_BY_ENTITY.keySet()) {
      Map<String, Boolean> levelByItem = new HashMap<>();
      long count = 0;
      for (String templateId : descending) {
        for (Map.Entry<String, Boolean> grant : grantsByItem(entityName, templateId).entrySet()) {
          Boolean current = levelByItem.get(grant.getKey());
          if (current == null || (!current && grant.getValue())) {
            count++;
            levelByItem.put(grant.getKey(), grant.getValue());
          }
        }
      }
      creates.put(entityName, count);
    }
    return creates;
  }

  /** Resets the role to no templates, runs one call per step, and snapshots the result. */
  @SafeVarargs
  private final Map<String, String> snapshotAfter(UserRoleCompositionService service,
      List<String>... steps) {
    assign(service, List.of());
    AssignmentOutcome last = null;
    for (List<String> step : steps) {
      last = assign(service, step);
    }
    assertNotNull(last);
    return snapshot(last.personalRole());
  }

  private Map<String, Long> summedTemplateRows(List<String> templates) {
    Map<String, Long> summed = new HashMap<>();
    for (String entityName : ITEM_PROPERTY_BY_ENTITY.keySet()) {
      long count = 0;
      for (String templateId : templates) {
        count += grantsByItem(entityName, templateId).size();
      }
      summed.put(entityName, count);
    }
    return summed;
  }

  private static long total(Map<String, Long> counts) {
    return counts.values().stream().mapToLong(Long::longValue).sum();
  }

  /** Entity INSERTs per guarded access type during one {@code assignTemplateRoles} call. */
  private Map<String, Long> countInserts(UserRoleCompositionService service,
      List<String> templates) {
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().clear();
    Statistics statistics = statistics();
    statistics.setStatisticsEnabled(true);
    statistics.clear();
    try {
      service.assignTemplateRoles(TEST_USER_ID, templates);
      OBDal.getInstance().flush();
      return insertsPerAccessType(statistics);
    } finally {
      statistics.setStatisticsEnabled(false);
      OBDal.getInstance().getSession().clear();
      endSimulatedRequest();
    }
  }

  private static Map<String, Long> insertsPerAccessType(Statistics statistics) {
    Map<String, Long> inserts = new HashMap<>();
    for (String entityName : ITEM_PROPERTY_BY_ENTITY.keySet()) {
      inserts.put(entityName, statistics.getEntityStatistics(entityName).getInsertCount());
    }
    return inserts;
  }

  private String firstSharedFullProcess(String templateA, String templateB) {
    Map<String, Boolean> grantsB = grantsByItem(ProcessAccess.ENTITY_NAME, templateB);
    for (Map.Entry<String, Boolean> grant : grantsByItem(ProcessAccess.ENTITY_NAME, templateA)
        .entrySet()) {
      if (grant.getValue() && Boolean.TRUE.equals(grantsB.get(grant.getKey()))) {
        return grant.getKey();
      }
    }
    throw new AssertionError("Precondition: the two templates must share a full classic process");
  }

  /**
   * Bulk HQL, so no {@code EntityUpdateEvent} fires and the template edit does not propagate to
   * any dependent role: only the template's own grant changes, inside the test transaction.
   */
  private void downgradeTemplateProcessWithoutEvents(String templateId, String processId) {
    int updated = OBDal.getInstance().getSession()
        .createQuery("update " + ProcessAccess.ENTITY_NAME
            + " set editableField = false where role.id = :role and process.id = :process")
        .setParameter("role", templateId)
        .setParameter("process", processId)
        .executeUpdate();
    assertEquals(1, updated);
    OBDal.getInstance().getSession().clear();
  }

  private Object[] processRow(String roleId, String processId) {
    for (Object[] row : rowsOf(ProcessAccess.ENTITY_NAME, roleId)) {
      if (processId.equals(row[0])) {
        return row;
      }
    }
    throw new AssertionError("Process " + processId + " is missing from role " + roleId);
  }
}
