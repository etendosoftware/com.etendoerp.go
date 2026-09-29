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
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Process;

/**
 * ETP-5503 — regression coverage for the batched ADD-path prevent in {@link
 * AbstractAccessOverlapCorruptionGuard}: one lookup, one bulk DELETE and one {@code
 * refresh(dependent)} per (guard, new inheritance) instead of one of each per conflicting row.
 *
 * <p>Runs against the 4 real system templates as they exist in the test DB. The assertions never
 * hardcode a row count: each test first proves the overlap it depends on exists (so a template
 * matrix change cannot turn it into a silent no-op), and then compares the outcome with a value
 * derived from the templates' own current grants.
 *
 * <p><b>Window rows: {@code InheritedFrom} is not asserted.</b> Windows are cleared by the
 * service ({@code RoleInheritanceReconciliationService#preventWindowAccessOverlapCorruption}), not
 * by the guard's batched path, and {@code reconcileWindowAccessAfterComposition} widens a window
 * to full without repointing its {@code InheritedFrom}. So, when several templates are added in
 * one call, a full window row can stay sourced from a read-only grantor, and its source differs
 * from the one-template-per-call path (e.g. window 123: Inventory vs Finance). This predates
 * ETP-5503 — the same 4 windows differ on the pre-ETP-5503 code — and the level, client,
 * organization and active flag of those rows still match. Process and OBUIAPP rows, which the
 * batched path does change, are compared in full.
 *
 * <p>Extends {@link WeldBaseTest}: under a plain {@code OBBaseTest} core's {@code
 * RoleInheritanceEventHandler} does not fire, so nothing propagates and every assertion here would
 * pass vacuously.
 */
public class AssignTemplateRolesBatchedPreventIntegrationTest extends WeldBaseTest {

  private static final String OBUIAPP_PROCESS_ACCESS =
      org.openbravo.client.application.ProcessAccess.ENTITY_NAME;

  /** Access entity name → the property of that entity pointing at the granted item. */
  private static final Map<String, String> ITEM_PROPERTY_BY_ENTITY = Map.of(
      WindowAccess.ENTITY_NAME, WindowAccess.PROPERTY_WINDOW,
      ProcessAccess.ENTITY_NAME, ProcessAccess.PROPERTY_PROCESS,
      OBUIAPP_PROCESS_ACCESS,
      org.openbravo.client.application.ProcessAccess.PROPERTY_OBUIAPPPROCESS);

  private static final List<String> ALL_TEMPLATES = Arrays.asList(
      SystemRoleTemplates.SALES_ROLE_ID, SystemRoleTemplates.PURCHASING_ROLE_ID,
      SystemRoleTemplates.FINANCE_ROLE_ID, SystemRoleTemplates.INVENTORY_ROLE_ID);

  @After
  public void rollbackChanges() {
    statistics().setStatisticsEnabled(false);
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  /**
   * The statement count of an overlapping add must not scale with the number of rows the guard
   * has to clear. Before ETP-5503, each cleared row cost ~27 statements (its own lookup, plus a
   * {@code refresh(dependent)} cascading into every loaded access collection of the role): +Finance
   * onto Sales+Purchasing ran ~1500 statements against ~340 for +Finance onto an empty role.
   */
  @Test
  public void testOverlappingAddCostsAboutTheSameStatementsAsANonOverlappingAdd() {
    setTestUserContext();
    UserRoleCompositionService service = new UserRoleCompositionService();

    service.assignTemplateRoles(TEST_USER_ID, List.of());
    long baselineStatements = countStatements(service,
        List.of(SystemRoleTemplates.FINANCE_ROLE_ID));

    AssignmentOutcome base = assign(service, Arrays.asList(SystemRoleTemplates.SALES_ROLE_ID,
        SystemRoleTemplates.PURCHASING_ROLE_ID));
    int overlappingRows = countOverlappingProcessRows(base.personalRole(),
        SystemRoleTemplates.FINANCE_ROLE_ID);
    assertTrue("Precondition: Finance must share processes with Sales+Purchasing, otherwise this "
        + "test does not exercise the guard's prevent path", overlappingRows > 0);

    long overlapStatements = countStatements(service, Arrays.asList(
        SystemRoleTemplates.SALES_ROLE_ID, SystemRoleTemplates.PURCHASING_ROLE_ID,
        SystemRoleTemplates.FINANCE_ROLE_ID));

    assertTrue("Adding Finance over " + overlappingRows + " overlapping process rows took "
        + overlapStatements + " statements, against " + baselineStatements
        + " onto an empty role: the prevent path must cost less than one statement per cleared row",
        overlapStatements - baselineStatements < overlappingRows);
  }

  /**
   * +Finance onto Sales+Purchasing: every row the guard cleared must come back, exactly once,
   * owned by the personal role, at the most permissive level any of the 3 templates grants, and
   * sourced from a template that actually grants that item (at that level).
   */
  @Test
  public void testOverlappingAddLeavesTheExpectedAccessSet() {
    setTestUserContext();
    UserRoleCompositionService service = new UserRoleCompositionService();
    assign(service, Arrays.asList(SystemRoleTemplates.SALES_ROLE_ID,
        SystemRoleTemplates.PURCHASING_ROLE_ID));
    List<String> templates = Arrays.asList(SystemRoleTemplates.SALES_ROLE_ID,
        SystemRoleTemplates.PURCHASING_ROLE_ID, SystemRoleTemplates.FINANCE_ROLE_ID);
    AssignmentOutcome outcome = assign(service, templates);

    assertMatchesTemplateUnion(outcome.personalRole(), templates);
  }

  /**
   * Golden equivalence: assigning the 4 templates in ONE call must leave the same access set as
   * reaching them one template per call, each call in its own session like separate requests.
   * The one-call path is where the batched prevent clears the most rows per template, and no other
   * test assigns 3+ real templates in one call.
   */
  @Test
  public void testAssigningFourTemplatesAtOnceMatchesAssigningThemOneByOne() {
    setTestUserContext();
    UserRoleCompositionService service = new UserRoleCompositionService();
    service.assignTemplateRoles(TEST_USER_ID, List.of());

    AssignmentOutcome cumulative = null;
    for (int count = 1; count <= ALL_TEMPLATES.size(); count++) {
      cumulative = assign(service, ALL_TEMPLATES.subList(0, count));
    }
    assertNotNull(cumulative);
    Map<String, String> oneByOne = snapshot(cumulative.personalRole());

    assign(service, List.of());
    AssignmentOutcome atOnce = assign(service, ALL_TEMPLATES);
    assertEquals("Sanity: both paths must compose the same personal role",
        cumulative.personalRoleId(), atOnce.personalRoleId());
    assertEquals(4, atOnce.result().addedCount);

    assertEquals(oneByOne, snapshot(atOnce.personalRole()));
    assertMatchesTemplateUnion(atOnce.personalRole(), ALL_TEMPLATES);
  }

  /** 4 → 0 in one call: every template-derived row of the 3 guarded types must be gone. */
  @Test
  public void testRemovingAllFourTemplatesAtOnceLeavesNoInheritedRows() {
    setTestUserContext();
    UserRoleCompositionService service = new UserRoleCompositionService();
    assign(service, ALL_TEMPLATES);

    AssignmentOutcome outcome = assign(service, List.of());

    assertEquals(4, outcome.result().removedCount);
    for (String entityName : ITEM_PROPERTY_BY_ENTITY.keySet()) {
      Long inherited = (Long) OBDal.getInstance().getSession()
          .createQuery("select count(*) from " + entityName
              + " a where a.role.id = :role and a.inheritedFrom is not null")
          .setParameter("role", outcome.personalRoleId())
          .uniqueResult();
      assertEquals(entityName + " must keep no template-derived row", 0L, inherited.longValue());
    }
  }

  /**
   * A MANUALLY granted row (no {@code InheritedFrom}) for an item a newly added template also
   * grants is part of the batch too: it is deleted like any inherited one, and core recreates it
   * once, sourced from the template at the template's own level.
   */
  @Test
  public void testManuallyGrantedRowOverlappingANewTemplateIsReplacedByOneInheritedRow() {
    setTestUserContext();
    UserRoleCompositionService service = new UserRoleCompositionService();
    AssignmentOutcome empty = assign(service, List.of());

    Map<String, Boolean> financeGrants = grantsByItem(ProcessAccess.ENTITY_NAME,
        SystemRoleTemplates.FINANCE_ROLE_ID);
    assertFalse("Precondition: Finance must grant at least one classic process",
        financeGrants.isEmpty());
    String processId = financeGrants.keySet().iterator().next();
    Role personalRole = empty.personalRole();
    OBContext.setAdminMode(true);
    try {
      ProcessAccess manual = OBProvider.getInstance().get(ProcessAccess.class);
      manual.setNewOBObject(true);
      manual.setClient(personalRole.getClient());
      manual.setOrganization(personalRole.getOrganization());
      manual.setActive(true);
      manual.setRole(personalRole);
      manual.setProcess(OBDal.getInstance().get(Process.class, processId));
      manual.setEditableField(!financeGrants.get(processId));
      OBDal.getInstance().save(manual);
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }

    AssignmentOutcome outcome = assign(service, List.of(SystemRoleTemplates.FINANCE_ROLE_ID));

    List<Object[]> rows = new ArrayList<>();
    for (Object[] row : rowsOf(ProcessAccess.ENTITY_NAME, outcome.personalRoleId())) {
      if (processId.equals(row[0])) {
        rows.add(row);
      }
    }
    assertEquals("Exactly one row must be left for the overlapping process", 1, rows.size());
    assertEquals("The row must now be inherited from Finance",
        SystemRoleTemplates.FINANCE_ROLE_ID, rows.get(0)[1]);
    assertEquals("The row must carry Finance's own level", financeGrants.get(processId),
        Boolean.TRUE.equals(rows.get(0)[2]));
    assertMatchesTemplateUnion(outcome.personalRole(),
        List.of(SystemRoleTemplates.FINANCE_ROLE_ID));
  }

  /** Adding and removing in the same call: [Sales, Purchasing] → [Purchasing, Finance]. */
  @Test
  public void testAddingAndRemovingInOneCallLeavesTheExpectedAccessSet() {
    setTestUserContext();
    UserRoleCompositionService service = new UserRoleCompositionService();
    assign(service, Arrays.asList(SystemRoleTemplates.SALES_ROLE_ID,
        SystemRoleTemplates.PURCHASING_ROLE_ID));
    List<String> templates = Arrays.asList(SystemRoleTemplates.PURCHASING_ROLE_ID,
        SystemRoleTemplates.FINANCE_ROLE_ID);

    AssignmentOutcome outcome = assign(service, templates);

    assertEquals(1, outcome.result().addedCount);
    assertEquals(1, outcome.result().removedCount);
    assertMatchesTemplateUnion(outcome.personalRole(), templates);
  }

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  private static final class AssignmentOutcome {
    private final UserRoleCompositionService.AssignmentResult result;

    private AssignmentOutcome(UserRoleCompositionService.AssignmentResult result) {
      this.result = result;
    }

    private UserRoleCompositionService.AssignmentResult result() {
      return result;
    }

    private String personalRoleId() {
      return result.personalRoleId;
    }

    private Role personalRole() {
      return OBDal.getInstance().get(Role.class, result.personalRoleId);
    }
  }

  /**
   * One {@code assignTemplateRoles} call in a fresh session (flushed and cleared first), so each
   * call starts with no loaded role collections, like a separate webhook request.
   */
  private AssignmentOutcome assign(UserRoleCompositionService service, List<String> templates) {
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().clear();
    AssignmentOutcome outcome = new AssignmentOutcome(
        service.assignTemplateRoles(TEST_USER_ID, templates));
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession().clear();
    return outcome;
  }

  /**
   * Prepared-statement count of one {@link #assign} call. Hibernate statistics are
   * SessionFactory-global and off by default, so they are only switched on around this single,
   * single-threaded call.
   */
  private long countStatements(UserRoleCompositionService service, List<String> templates) {
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
    }
  }

  private static Statistics statistics() {
    return OBDal.getInstance().getSession().getSessionFactory().getStatistics();
  }

  /** Classic + OBUIAPP process rows {@code role} already has for an item {@code templateId} also
   *  grants: the rows the guard has to clear when {@code templateId} is added. */
  private int countOverlappingProcessRows(Role role, String templateId) {
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
  private void assertMatchesTemplateUnion(Role role, List<String> templateIds) {
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
        if (comparesSource(entityName)) {
          assertEquals(entityName + " item " + item + " is sourced from a template whose level "
              + "does not justify the row's level", mostPermissive,
              Boolean.TRUE.equals(grantors.get(source)));
        }
        assertEquals(entityName + " item " + item + " client", role.getClient().getId(), row[3]);
        assertEquals(entityName + " item " + item + " organization",
            role.getOrganization().getId(), row[4]);
        assertEquals(entityName + " item " + item + " active", Boolean.TRUE, row[5]);
      }
      assertEquals(entityName + ": every template-granted item must be on the role",
          expected.keySet(), seen);
    }
  }

  /** Whether {@code InheritedFrom} is asserted for {@code entityName} — see the class javadoc. */
  private static boolean comparesSource(String entityName) {
    return !WindowAccess.ENTITY_NAME.equals(entityName);
  }

  /** item id → "inheritedFrom|editable|client|org|active" for the 3 guarded types (without
   *  {@code inheritedFrom} for windows, see the class javadoc). */
  private Map<String, String> snapshot(Role role) {
    Map<String, String> snapshot = new TreeMap<>();
    for (String entityName : ITEM_PROPERTY_BY_ENTITY.keySet()) {
      for (Object[] row : rowsOf(entityName, role.getId())) {
        String key = entityName + ":" + row[0];
        String source = comparesSource(entityName) ? (String) row[1] : "-";
        String value = source + "|" + row[2] + "|" + row[3] + "|" + row[4] + "|" + row[5];
        assertFalse(key + " appears twice on the role", snapshot.containsKey(key));
        snapshot.put(key, value);
      }
    }
    return snapshot;
  }

  /** item, inheritedFrom, editableField, client, organization, active — for every row. */
  @SuppressWarnings("unchecked")
  private List<Object[]> rowsOf(String entityName, String roleId) {
    String itemProperty = ITEM_PROPERTY_BY_ENTITY.get(entityName);
    return new ArrayList<>(OBDal.getInstance().getSession()
        .createQuery("select a." + itemProperty + ".id, source.id, a.editableField, a.client.id, "
            + "a.organization.id, a.active from " + entityName
            + " a left join a.inheritedFrom source where a.role.id = :role")
        .setParameter("role", roleId)
        .list());
  }

  @SuppressWarnings("unchecked")
  private List<String> activeItems(String entityName, String roleId) {
    return OBDal.getInstance().getSession()
        .createQuery("select a." + ITEM_PROPERTY_BY_ENTITY.get(entityName) + ".id from "
            + entityName + " a where a.role.id = :role and a.active = true")
        .setParameter("role", roleId)
        .list();
  }

  /** item id → editableField of {@code templateId}'s active grants. */
  @SuppressWarnings("unchecked")
  private Map<String, Boolean> grantsByItem(String entityName, String templateId) {
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
