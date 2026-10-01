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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.roles.TemplateAccessPropagationService.ProcessGrantDiagnostic;
import com.etendoerp.go.roles.TemplateAccessPropagationService.SweepCounts;

/**
 * ETP-5565 — real-DB tests of {@link TemplateAccessPropagationService}: the sweep rule (the
 * plan's P1–P5 scenario, collisions, manual rows, idempotency), the purge, the stale-copy
 * detection, the fingerprint and the diagnostic.
 *
 * <p>Every fixture row (throwaway system templates, personal roles, inheritances, access rows) is
 * written with native SQL, so core's inheritance propagation never runs and each test controls
 * the exact starting state. Nothing is committed: {@link #rollbackChanges()} rolls everything
 * back.</p>
 */
public class TemplateAccessPropagationServiceIntegrationTest extends OBBaseTest {

  private static final String WINDOW = "ad_window_access";
  private static final String OBUIAPP = "obuiapp_process_access";

  private final TemplateAccessPropagationService service = new TemplateAccessPropagationService();

  private String windowA;
  private String windowB;

  @Before
  public void pickWindows() {
    setTestUserContext();
    OBContext.setAdminMode(true);
    List<String> windows = strings("SELECT ad_window_id FROM ad_window ORDER BY ad_window_id "
        + "LIMIT 2");
    windowA = windows.get(0);
    windowB = windows.get(1);
  }

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  /** The plan's scenario: "Asd" (windowA) removed from Sales (soft delete on the template). */
  @Test
  public void sweepRealignsEveryCombinationAfterATemplateRemoval() {
    String finance = template("Finance");
    String sales = template("Sales");
    String purchase = template("Purchase");
    grant(WINDOW, finance, windowA, "Y", "Y", null);
    grant(WINDOW, sales, windowA, "N", "Y", null); // soft-deleted on the template
    grant(WINDOW, purchase, windowA, "Y", "N", null);

    String p1 = personal("P1", finance, sales);
    grant(WINDOW, p1, windowA, "Y", "Y", sales);
    String p2 = personal("P2", sales);
    grant(WINDOW, p2, windowA, "Y", "Y", sales);
    String p3 = personal("P3", sales, purchase);
    grant(WINDOW, p3, windowA, "Y", "Y", sales);
    String p4 = personal("P4", sales);
    grant(WINDOW, p4, windowA, "Y", "N", null); // manual read-only grant
    String p5 = personal("P5", finance, sales);
    grant(WINDOW, p5, windowA, "N", "Y", sales); // inactive copy left by core composition

    service.sweepRoles(Arrays.asList(p1, p2, p3, p4, p5));

    assertEquals("Y|Y|" + finance, row(WINDOW, p1, windowA));
    assertNull(row(WINDOW, p2, windowA));
    assertEquals("Y|N|" + purchase, row(WINDOW, p3, windowA));
    assertEquals("Y|N|", row(WINDOW, p4, windowA));
    assertEquals("Y|Y|" + finance, row(WINDOW, p5, windowA));

    SweepCounts second = service.sweepRoles(Arrays.asList(p1, p2, p3, p4, p5));
    assertEquals("A second sweep must change nothing", 0, second.total());
  }

  @Test
  public void collisionTakesTheMostPermissiveLevelAndTheHighestSeqNoSource() {
    String low = template("Low");
    String mid = template("Mid");
    String high = template("High");
    grant(WINDOW, low, windowA, "Y", "N", null);
    grant(WINDOW, mid, windowA, "N", "Y", null); // soft-deleted full grant does not count
    grant(WINDOW, high, windowA, "Y", "Y", null);
    // SeqNo 10/20/30 in argument order: high is the lowest precedence here on purpose.
    String role = personal("C9", high, mid, low);

    service.sweepRoles(Collections.singletonList(role));

    assertEquals("full wins the level, and the source is a template that grants full",
        "Y|Y|" + high, row(WINDOW, role, windowA));
  }

  @Test
  public void equalLevelsTakeTheHighestSeqNoSource() {
    String low = template("EqLow");
    String high = template("EqHigh");
    grant(WINDOW, low, windowA, "Y", "N", null);
    grant(WINDOW, high, windowA, "Y", "N", null);
    String role = personal("Eq", low, high);

    service.sweepRoles(Collections.singletonList(role));

    assertEquals("Y|N|" + high, row(WINDOW, role, windowA));
  }

  @Test
  public void templateAdditionIsInsertedUnlessAManualRowExists() {
    String tpl = template("Adds");
    grant(WINDOW, tpl, windowA, "Y", "Y", null);
    grant(WINDOW, tpl, windowB, "Y", "N", null);
    String role = personal("Missing", tpl);
    grant(WINDOW, role, windowB, "N", "Y", null); // inactive manual row: manual always wins

    SweepCounts counts = service.sweepRoles(Collections.singletonList(role));

    assertEquals(1, counts.getInserted());
    assertEquals("Y|Y|" + tpl, row(WINDOW, role, windowA));
    assertEquals("N|Y|", row(WINDOW, role, windowB));
  }

  @Test
  public void obuiappAccessFollowsTheSameRuleWithoutDuplicates() {
    String process = string("SELECT obuiapp_process_id FROM obuiapp_process "
        + "ORDER BY obuiapp_process_id LIMIT 1");
    String tpl = template("Obuiapp");
    grant(OBUIAPP, tpl, process, "Y", "Y", null);
    grant(OBUIAPP, tpl, process, "N", "Y", null); // a deduplicated (inactive) template row
    String role = personal("Obuiapp", tpl);

    service.sweepRoles(Collections.singletonList(role));
    service.sweepRoles(Collections.singletonList(role));

    assertEquals("Y|Y|" + tpl, row(OBUIAPP, role, process));
    assertEquals(1, count("SELECT count(*) FROM obuiapp_process_access WHERE ad_role_id = '"
        + role + "'"));
  }

  @Test
  public void duplicateInheritedObuiappCopiesCollapseToTheOldest() {
    String process = string("SELECT obuiapp_process_id FROM obuiapp_process "
        + "ORDER BY obuiapp_process_id LIMIT 1");
    String tpl = template("DupCopies");
    grant(OBUIAPP, tpl, process, "Y", "Y", null);
    String role = personal("DupCopies", tpl);
    grant(OBUIAPP, role, process, "Y", "Y", tpl);
    grant(OBUIAPP, role, process, "N", "Y", tpl);
    String oldest = string("SELECT obuiapp_process_access_id FROM obuiapp_process_access "
        + "WHERE ad_role_id = '" + role + "' ORDER BY created LIMIT 1");

    service.sweepRoles(Collections.singletonList(role));

    assertEquals(oldest, string("SELECT obuiapp_process_access_id FROM obuiapp_process_access "
        + "WHERE ad_role_id = '" + role + "'"));
    assertEquals("Y|Y|" + tpl, row(OBUIAPP, role, process));
  }

  /**
   * Core's role trigger refuses to deactivate a template while an inheritance depends on it, so
   * only the fingerprint half can be exercised; the stale-copy half is a defensive guard.
   */
  @Test
  public void deactivatingATemplateChangesItsFingerprint() {
    String tpl = template("Deactivated");
    grant(WINDOW, tpl, windowA, "Y", "Y", null);
    String before = service.fingerprints().get(tpl);

    exec("UPDATE ad_role SET isactive = 'N' WHERE ad_role_id = '" + tpl + "'");

    assertNotEquals(before, service.fingerprints().get(tpl));
  }

  @Test
  public void roleInheritingANonSystemTemplateIsNotEligible() {
    String tpl = template("Sys");
    String other = template("Tenant");
    exec("UPDATE ad_role SET ad_client_id = '" + TEST_CLIENT_ID + "' WHERE ad_role_id = '"
        + other + "'");
    String mixed = personal("Mixed", tpl, other);
    String plain = personal("Plain", tpl);

    assertEquals(Collections.singletonList(plain),
        service.eligibleRoles(Arrays.asList(mixed, plain)));
  }

  @Test
  public void staleCopiesAreDetectedUntilSwept() {
    String tpl = template("Stale");
    grant(WINDOW, tpl, windowA, "N", "Y", null);
    String role = personal("Stale", tpl);
    grant(WINDOW, role, windowA, "Y", "Y", tpl);

    assertTrue(service.rolesWithStaleCopies().contains(role));
    service.sweepRoles(Collections.singletonList(role));
    assertFalse(service.rolesWithStaleCopies().contains(role));
  }

  @Test
  public void purgeDeletesOnlyInactiveTemplateRowsOlderThanTheGracePeriod() {
    String tpl = template("Purge");
    grant(WINDOW, tpl, windowA, "N", "Y", null);
    grant(WINDOW, tpl, windowB, "N", "Y", null);
    exec("UPDATE ad_window_access SET updated = now() - interval '8 days' WHERE ad_role_id = '"
        + tpl + "' AND ad_window_id = '" + windowA + "'");

    assertTrue(service.hasPurgeableTemplateRows(7));
    service.purgeInactiveTemplateRows(7);

    assertNull(row(WINDOW, tpl, windowA));
    assertEquals("N|Y|", row(WINDOW, tpl, windowB));
    assertFalse(service.hasPurgeableTemplateRows(7));
  }

  @Test
  public void fingerprintChangesWithActiveGrantsOnly() {
    String tpl = template("Fingerprint");
    grant(WINDOW, tpl, windowA, "Y", "Y", null);
    String before = service.fingerprints().get(tpl);

    grant(WINDOW, tpl, windowB, "N", "Y", null);
    assertEquals("An inactive row must not change the fingerprint", before,
        service.fingerprints().get(tpl));

    exec("UPDATE ad_window_access SET isreadwrite = 'N' WHERE ad_role_id = '" + tpl
        + "' AND ad_window_id = '" + windowA + "'");
    assertNotEquals("A level change must change the fingerprint", before,
        service.fingerprints().get(tpl));
  }

  @Test
  public void diagnosticClassifiesTemplateProcessGrants() {
    String tpl = template("Diagnostic");
    String unexplained = string("SELECT obuiapp_process_id FROM obuiapp_process "
        + "ORDER BY obuiapp_process_id LIMIT 1");
    grant(OBUIAPP, tpl, unexplained, "Y", "Y", null);

    List<ProcessGrantDiagnostic> grants =
        service.diagnoseProcessGrants(Collections.singletonList(tpl));

    assertEquals(1, grants.size());
    assertEquals(ProcessGrantDiagnostic.UNEXPLAINED, grants.get(0).getCategory());
    assertEquals(unexplained, grants.get(0).getProcessId());
  }

  @Test
  public void ownersAreResolvedFromEverySource() {
    String tpl = template("Owner");
    String role = personal("Owner", tpl);
    exec("UPDATE ad_role SET em_etgo_personal_owner_id = '" + TEST_USER_ID
        + "' WHERE ad_role_id = '" + role + "'");

    Map<String, String> fingerprints = service.fingerprints();
    assertTrue(fingerprints.containsKey(tpl));
    assertEquals(Collections.singletonList(TEST_USER_ID),
        service.ownersOf(Collections.singletonList(role)));
  }

  // ---------------------------------------------------------------------
  // Fixtures (native SQL, so core's propagation never runs)
  // ---------------------------------------------------------------------

  private int seq;

  private String template(String name) {
    String id = uuid();
    exec("INSERT INTO ad_role (ad_role_id, ad_client_id, ad_org_id, isactive, created, createdby, "
        + "updated, updatedby, name, userlevel, ismanual, is_client_admin, istemplate) VALUES ('"
        + id + "', '0', '0', 'Y', now(), '0', now(), '0', 'T5565 " + name + " " + id.substring(0, 8)
        + "', '  O', 'Y', 'N', 'Y')");
    return id;
  }

  /** A personal role of the test client inheriting {@code templates}, SeqNo 10, 20, … in order. */
  private String personal(String name, String... templates) {
    String id = uuid();
    exec("INSERT INTO ad_role (ad_role_id, ad_client_id, ad_org_id, isactive, created, createdby, "
        + "updated, updatedby, name, userlevel, ismanual, is_client_admin, istemplate) VALUES ('"
        + id + "', '" + TEST_CLIENT_ID + "', '0', 'Y', now(), '0', now(), '0', 'P5565 "
        + name + " " + id.substring(0, 8) + "', '  O', 'Y', 'N', 'N')");
    for (int i = 0; i < templates.length; i++) {
      exec("INSERT INTO ad_role_inheritance (ad_role_inheritance_id, ad_client_id, ad_org_id, "
          + "isactive, created, createdby, updated, updatedby, seqno, inherit_from, ad_role_id) "
          + "VALUES ('" + uuid() + "', '" + TEST_CLIENT_ID + "', '0', 'Y', now(), '0', now(), "
          + "'0', " + ((i + 1) * 10) + ", '" + templates[i] + "', '" + id + "')");
    }
    return id;
  }

  private void grant(String table, String roleId, String elementId, String active,
      String readWrite, String inheritedFrom) {
    String element = WINDOW.equals(table) ? "ad_window_id" : "obuiapp_process_id";
    String client = string("SELECT ad_client_id FROM ad_role WHERE ad_role_id = '" + roleId + "'");
    seq++;
    exec("INSERT INTO " + table + " (" + table + "_id, ad_client_id, ad_org_id, isactive, "
        + "created, createdby, updated, updatedby, ad_role_id, " + element + ", isreadwrite, "
        + "inherited_from) VALUES ('" + uuid() + "', '" + client + "', '0', '" + active
        + "', now() + interval '" + seq + " seconds', '0', now(), '0', '" + roleId + "', '"
        + elementId + "', '" + readWrite + "', "
        + (inheritedFrom == null ? "NULL" : "'" + inheritedFrom + "'") + ")");
  }

  /** {@code isactive|isreadwrite|inherited_from} of the role's only row for the element. */
  private String row(String table, String roleId, String elementId) {
    String element = WINDOW.equals(table) ? "ad_window_id" : "obuiapp_process_id";
    List<String> rows = strings("SELECT isactive || '|' || isreadwrite || '|' || "
        + "COALESCE(inherited_from, '') FROM " + table + " WHERE ad_role_id = '" + roleId
        + "' AND " + element + " = '" + elementId + "'");
    assertTrue("At most one row per role and element", rows.size() <= 1);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static String uuid() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase();
  }

  private static void exec(String sql) {
    OBDal.getInstance().getSession().createNativeQuery(sql).executeUpdate();
  }

  private static String string(String sql) {
    return (String) OBDal.getInstance().getSession().createNativeQuery(sql).getSingleResult();
  }

  private static int count(String sql) {
    return ((Number) OBDal.getInstance().getSession().createNativeQuery(sql).getSingleResult())
        .intValue();
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(String sql) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    return (List<String>) query.list();
  }
}
