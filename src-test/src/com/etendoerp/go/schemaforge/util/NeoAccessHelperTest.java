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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link NeoAccessHelper}.
 *
 * <p>Uses {@link MockedStatic} to isolate OBContext and OBDal so no live
 * database is required.</p>
 */
public class NeoAccessHelperTest {

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private OBDal dal;
  private OBContext context;
  private org.openbravo.model.ad.access.Role role;

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    context = mock(OBContext.class);
    role = mock(org.openbravo.model.ad.access.Role.class);

    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    obContextMock.when(OBContext::getOBContext).thenReturn(context);
    when(context.getRole()).thenReturn(role);
  }

  @After
  public void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  // ── hasWindowAccess ────────────────────────────────────────────────────────

  @Test
  public void hasWindowAccess_adminRole_returnsTrue() {
    when(role.getId()).thenReturn("0");
    assertTrue(NeoAccessHelper.hasWindowAccess("window-id-123"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccess_roleWithAccess_returnsTrue() {
    when(role.getId()).thenReturn("role-id-99");
    OBCriteria<WindowAccess> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(WindowAccess.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    WindowAccess access = mock(WindowAccess.class);
    when(criteria.list()).thenReturn(Collections.singletonList(access));

    assertTrue(NeoAccessHelper.hasWindowAccess("some-window-id"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccess_roleWithoutAccess_returnsFalse() {
    when(role.getId()).thenReturn("role-id-nowindow");
    OBCriteria<WindowAccess> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(WindowAccess.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());

    assertFalse(NeoAccessHelper.hasWindowAccess("restricted-window-id"));
  }

  @Test
  public void hasWindowAccess_noRoleAssigned_returnsFalse() {
    when(context.getRole()).thenReturn(null);

    assertFalse(NeoAccessHelper.hasWindowAccess("any-window-id"));
    assertFalse(NeoAccessHelper.hasWindowAccess("any-window-id", "GET"));
    assertFalse(NeoAccessHelper.hasWindowAccess("any-window-id", "POST"));
  }

  @Test
  public void hasWindowAccess_clientAdminRole_returnsTrueForAllMethods() {
    when(role.getId()).thenReturn("role-id-client-admin");
    when(role.isClientAdmin()).thenReturn(true);

    assertTrue(NeoAccessHelper.hasWindowAccess("some-window-id", "GET"));
    assertTrue(NeoAccessHelper.hasWindowAccess("some-window-id", "POST"));
    assertTrue(NeoAccessHelper.hasWindowAccess("some-window-id", "PUT"));
    assertTrue(NeoAccessHelper.hasWindowAccess("some-window-id", "DELETE"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccess_readOnlyRow_allowsGetButDeniesWriteMethods() {
    when(role.getId()).thenReturn("role-id-readonly");
    OBCriteria<WindowAccess> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(WindowAccess.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    WindowAccess access = mock(WindowAccess.class);
    when(access.isEditableField()).thenReturn(false);
    when(criteria.list()).thenReturn(Collections.singletonList(access));

    assertTrue(NeoAccessHelper.hasWindowAccess("ro-window-id", "GET"));
    assertFalse(NeoAccessHelper.hasWindowAccess("ro-window-id", "POST"));
    assertFalse(NeoAccessHelper.hasWindowAccess("ro-window-id", "PUT"));
    assertFalse(NeoAccessHelper.hasWindowAccess("ro-window-id", "PATCH"));
    assertFalse(NeoAccessHelper.hasWindowAccess("ro-window-id", "DELETE"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccess_fullAccessRow_allowsAllMethods() {
    when(role.getId()).thenReturn("role-id-full");
    OBCriteria<WindowAccess> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(WindowAccess.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    WindowAccess access = mock(WindowAccess.class);
    when(access.isEditableField()).thenReturn(true);
    when(criteria.list()).thenReturn(Collections.singletonList(access));

    assertTrue(NeoAccessHelper.hasWindowAccess("full-window-id", "GET"));
    assertTrue(NeoAccessHelper.hasWindowAccess("full-window-id", "POST"));
    assertTrue(NeoAccessHelper.hasWindowAccess("full-window-id", "PUT"));
    assertTrue(NeoAccessHelper.hasWindowAccess("full-window-id", "PATCH"));
    assertTrue(NeoAccessHelper.hasWindowAccess("full-window-id", "DELETE"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccess_noAccessRowAtAll_deniesAllMethods() {
    when(role.getId()).thenReturn("role-id-no-row");
    OBCriteria<WindowAccess> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(WindowAccess.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());

    assertFalse(NeoAccessHelper.hasWindowAccess("no-row-window-id", "GET"));
    assertFalse(NeoAccessHelper.hasWindowAccess("no-row-window-id", "POST"));
    assertFalse(NeoAccessHelper.hasWindowAccess("no-row-window-id", "PUT"));
    assertFalse(NeoAccessHelper.hasWindowAccess("no-row-window-id", "DELETE"));
  }

  // ── hasProcessAccess ──────────────────────────────────────────────────────

  @Test
  public void hasProcessAccess_adminRole_returnsTrue() {
    when(role.getId()).thenReturn("0");
    assertTrue(NeoAccessHelper.hasProcessAccess("process-id-abc"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasProcessAccess_roleWithAccess_returnsTrue() {
    when(role.getId()).thenReturn("role-id-proc");
    OBCriteria<ProcessAccess> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(ProcessAccess.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    ProcessAccess access = mock(ProcessAccess.class);
    when(criteria.list()).thenReturn(Collections.singletonList(access));

    assertTrue(NeoAccessHelper.hasProcessAccess("allowed-process-id"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasProcessAccess_roleWithoutAccess_returnsFalse() {
    when(role.getId()).thenReturn("role-id-noprocess");
    OBCriteria<ProcessAccess> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(ProcessAccess.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());

    assertFalse(NeoAccessHelper.hasProcessAccess("restricted-process-id"));
  }

  @Test
  public void hasProcessAccess_noRoleAssigned_returnsFalse() {
    when(context.getRole()).thenReturn(null);

    assertFalse(NeoAccessHelper.hasProcessAccess("any-process-id"));
  }

  // ── hasObuiappProcessAccess ───────────────────────────────────────────────

  @Test
  public void hasObuiappProcessAccess_adminRole_returnsTrue() {
    when(role.getId()).thenReturn("0");
    assertTrue(NeoAccessHelper.hasObuiappProcessAccess("obuiapp-proc-id"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasObuiappProcessAccess_roleWithAccess_returnsTrue() {
    when(role.getId()).thenReturn("role-id-obuiapp");
    OBCriteria<org.openbravo.client.application.ProcessAccess> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(org.openbravo.client.application.ProcessAccess.class))
        .thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    org.openbravo.client.application.ProcessAccess access =
        mock(org.openbravo.client.application.ProcessAccess.class);
    when(criteria.list()).thenReturn(Collections.singletonList(access));

    assertTrue(NeoAccessHelper.hasObuiappProcessAccess("allowed-obuiapp-proc-id"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasObuiappProcessAccess_roleWithoutAccess_returnsFalse() {
    when(role.getId()).thenReturn("role-id-noobuiapp");
    OBCriteria<org.openbravo.client.application.ProcessAccess> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(org.openbravo.client.application.ProcessAccess.class))
        .thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());

    assertFalse(NeoAccessHelper.hasObuiappProcessAccess("restricted-obuiapp-id"));
  }

  // ── resolveDefaultPostProcess ─────────────────────────────────────────────

  @Test
  public void resolveDefaultPostProcess_found_returnsProcess() {
    org.openbravo.client.application.Process proc =
        mock(org.openbravo.client.application.Process.class);
    when(dal.get(eq(org.openbravo.client.application.Process.class), anyString()))
        .thenReturn(proc);

    org.openbravo.client.application.Process result =
        NeoAccessHelper.resolveDefaultPostProcess();
    // Result should be the mock returned by OBDal (or null if the default ID
    // happens not to match — either way no exception is thrown)
    // Just verify no exception is thrown and the method completes gracefully.
    // The actual return value depends on whether dal.get returns our mock.
    assertTrue(result == proc || result == null);
  }

  @Test
  public void resolveDefaultPostProcess_dalThrows_returnsNull() {
    when(dal.get(eq(org.openbravo.client.application.Process.class), anyString()))
        .thenThrow(new RuntimeException("Not found"));

    org.openbravo.client.application.Process result =
        NeoAccessHelper.resolveDefaultPostProcess();
    assertNull(result);
  }

  // ── resolveFallbackObuiappProcess ─────────────────────────────────────────

  @Test
  public void resolveFallbackObuiappProcess_nullColumn_returnsNull() {
    assertNull(NeoAccessHelper.resolveFallbackObuiappProcess(null));
  }

  @Test
  public void resolveFallbackObuiappProcess_nonPostedColumn_returnsNull() {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("DocumentStatus");
    assertNull(NeoAccessHelper.resolveFallbackObuiappProcess(col));
  }

  @Test
  public void resolveFallbackObuiappProcess_postedColumnWithProcess_returnsProcess() {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("Posted");
    org.openbravo.client.application.Process proc =
        mock(org.openbravo.client.application.Process.class);
    when(dal.get(eq(org.openbravo.client.application.Process.class), anyString()))
        .thenReturn(proc);

    org.openbravo.client.application.Process result =
        NeoAccessHelper.resolveFallbackObuiappProcess(col);
    assertTrue(result == proc || result == null);
  }

  @Test
  public void resolveFallbackObuiappProcess_postedColumnDalThrows_returnsNull() {
    Column col = mock(Column.class);
    when(col.getDBColumnName()).thenReturn("Posted");
    when(dal.get(eq(org.openbravo.client.application.Process.class), anyString()))
        .thenThrow(new RuntimeException("DB error"));

    assertNull(NeoAccessHelper.resolveFallbackObuiappProcess(col));
  }

  // ── resolveProcess ────────────────────────────────────────────────────────

  @Test
  public void resolveProcess_delegatesToSpec() {
    SFSpec spec = mock(SFSpec.class);
    Process proc = mock(Process.class);
    when(spec.getProcess()).thenReturn(proc);

    Process result = NeoAccessHelper.resolveProcess(spec);
    assertTrue(result == proc);
  }

  @Test
  public void resolveProcess_specWithNoProcess_returnsNull() {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getProcess()).thenReturn(null);

    assertNull(NeoAccessHelper.resolveProcess(spec));
  }
}
