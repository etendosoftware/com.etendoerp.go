/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge.webhooks;

import static com.etendoerp.go.schemaforge.TestConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.client.application.Process;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.util.ReportAccessCatalog;

/**
 * Unit tests for {@link SFMyReportAccess}.
 *
 * <p>Covers: no-role/empty-map short-circuiting, admin/client-admin bypass (all 9 {@link
 * ReportAccessCatalog#ROWS} rows resolve to {@code full} with zero DB queries), a restricted role
 * with zero grants (empty map), a restricted role with exactly one grant (only that row appears),
 * and exception handling.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class SFMyReportAccessTest {

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private OBDal mockDal;
  private OBContext mockContext;
  private SFMyReportAccess webhook;
  private Map<String, String> parameters;
  private Map<String, String> responseVars;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);

    mockDal = mock(OBDal.class);
    mockContext = mock(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(mockDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(mockContext);

    webhook = new SFMyReportAccess();
    parameters = new HashMap<>();
    responseVars = new HashMap<>();
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  private void givenNoRole() {
    when(mockContext.getRole()).thenReturn(null);
  }

  private Role givenSystemAdminRole() {
    Role role = mock(Role.class);
    when(role.getId()).thenReturn("0");
    when(mockContext.getRole()).thenReturn(role);
    return role;
  }

  private Role givenRestrictedRole(String roleId) {
    Role role = mock(Role.class);
    when(role.getId()).thenReturn(roleId);
    when(mockContext.getRole()).thenReturn(role);
    return role;
  }

  /** Stubs all three access tables empty for every ReportAccessCatalog anchor query. */
  @SuppressWarnings("unchecked")
  private void stubAllAccessEmpty() {
    OBCriteria<WindowAccess> windowCriteria = mock(OBCriteria.class);
    when(windowCriteria.list()).thenReturn(Collections.emptyList());
    when(mockDal.createCriteria(WindowAccess.class)).thenReturn(windowCriteria);

    OBCriteria<ProcessAccess> classicCriteria = mock(OBCriteria.class);
    when(classicCriteria.list()).thenReturn(Collections.emptyList());
    when(mockDal.createCriteria(ProcessAccess.class)).thenReturn(classicCriteria);

    OBCriteria<org.openbravo.client.application.ProcessAccess> obuiappCriteria = mock(OBCriteria.class);
    when(obuiappCriteria.list()).thenReturn(Collections.emptyList());
    when(mockDal.createCriteria(org.openbravo.client.application.ProcessAccess.class))
        .thenReturn(obuiappCriteria);
  }

  @Test
  @DisplayName("No role assigned returns an empty reportAccess map without querying the DB")
  void testNoRoleReturnsEmptyMap() throws Exception {
    givenNoRole();

    webhook.get(parameters, responseVars);

    assertNull(responseVars.get(ERROR));
    JSONObject result = new JSONObject(responseVars.get(RESULT).toString());
    assertEquals(0, result.optJSONObject("reportAccess").length());
    verify(mockDal, never()).createCriteria(WindowAccess.class);
  }

  private JSONObject parseResult() throws Exception {
    return new JSONObject(responseVars.get(RESULT).toString());
  }

  @Test
  @DisplayName("Admin bypass resolves all 9 report rows to full with zero DB queries")
  void testAdminBypassResolvesAllRowsFull() throws Exception {
    givenSystemAdminRole();

    webhook.get(parameters, responseVars);

    assertNull(responseVars.get(ERROR));
    JSONObject reportAccess = parseResult().getJSONObject("reportAccess");
    assertEquals(ReportAccessCatalog.ROWS.size(), reportAccess.length());
    for (ReportAccessCatalog.Row row : ReportAccessCatalog.ROWS) {
      assertEquals(ReportAccessCatalog.FULL, reportAccess.getString(row.id));
    }
    verify(mockDal, never()).createCriteria(WindowAccess.class);
  }

  @Test
  @DisplayName("Restricted role with zero grants resolves an empty reportAccess map")
  void testRestrictedRoleWithNoGrantsResolvesEmptyMap() throws Exception {
    givenRestrictedRole("role-none");
    stubAllAccessEmpty();

    webhook.get(parameters, responseVars);

    assertNull(responseVars.get(ERROR));
    assertEquals(0, parseResult().getJSONObject("reportAccess").length());
  }

  @Test
  @DisplayName("Restricted role with one OBUIAPP process grant surfaces only that report row")
  void testRestrictedRoleWithOneGrantSurfacesOnlyThatRow() throws Exception {
    givenRestrictedRole("role-sales");
    stubAllAccessEmpty();

    org.openbravo.client.application.ProcessAccess agingGrant =
        mock(org.openbravo.client.application.ProcessAccess.class);
    Process agingProcess = mock(Process.class);
    when(agingProcess.getId()).thenReturn(ReportAccessCatalog.AGING_RECEIVABLE_PROCESS_ID);
    when(agingGrant.getObuiappProcess()).thenReturn(agingProcess);
    when(agingGrant.isActive()).thenReturn(true);
    when(agingGrant.isEditableField()).thenReturn(true);

    OBCriteria<org.openbravo.client.application.ProcessAccess> obuiappCriteria = mock(OBCriteria.class);
    when(obuiappCriteria.list()).thenReturn(
        (List<org.openbravo.client.application.ProcessAccess>) List.of(agingGrant));
    when(mockDal.createCriteria(org.openbravo.client.application.ProcessAccess.class))
        .thenReturn(obuiappCriteria);

    webhook.get(parameters, responseVars);

    assertNull(responseVars.get(ERROR));
    JSONObject reportAccess = parseResult().getJSONObject("reportAccess");
    assertEquals(1, reportAccess.length());
    assertEquals(ReportAccessCatalog.FULL, reportAccess.getString("aging-receivable"));
  }

  @Test
  @DisplayName("Exception sets error in response")
  void testExceptionSetsError() {
    givenRestrictedRole("role-error");
    // ReportAccessCatalog.ROWS' first row (tax-report) is CLASSIC_PROCESS-kind, so that's the
    // first criteria type resolveTierMap queries — stubbing WindowAccess.class here would never
    // be reached, since the exception fires before that row is even processed.
    when(mockDal.createCriteria(ProcessAccess.class)).thenThrow(new RuntimeException("DB error"));

    webhook.get(parameters, responseVars);

    assertEquals("DB error", responseVars.get(ERROR));
    assertNull(responseVars.get(RESULT));
  }
}
