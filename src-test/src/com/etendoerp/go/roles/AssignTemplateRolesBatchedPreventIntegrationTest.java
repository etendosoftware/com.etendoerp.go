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
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
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
 * <p>Helpers, and the {@code WeldBaseTest} reasoning, live in {@link
 * AbstractTemplateAssignmentIntegrationTest}.
 */
public class AssignTemplateRolesBatchedPreventIntegrationTest
    extends AbstractTemplateAssignmentIntegrationTest {

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
}
