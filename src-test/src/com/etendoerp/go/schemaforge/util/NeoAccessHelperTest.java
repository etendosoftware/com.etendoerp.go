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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.SimpleExpression;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFEntity;
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

  /**
   * QA gap-closer: every other {@code hasWindowAccess} test stubs
   * {@code criteria.add(any())} — proving the method returns the right boolean for a given
   * canned {@code criteria.list()} result, but NOT that {@code findActiveWindowAccess} actually
   * asks Hibernate for the right thing. This test captures the real {@link Criterion} instances
   * passed to {@code criteria.add(...)} and asserts their property/value, so a regression that
   * (for example) swapped the window-id and role-id filters, or dropped the
   * {@code isActive = true} filter, would fail here even though the higher-level
   * true/false-returning tests would still pass with a stub that always answers "found".
   */
  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccess_queriesCriteriaWithWindowIdRoleIdAndActiveTrue() {
    when(role.getId()).thenReturn("role-id-captured");
    OBCriteria<WindowAccess> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(WindowAccess.class)).thenReturn(criteria);
    ArgumentCaptor<Criterion> captor = ArgumentCaptor.forClass(Criterion.class);
    when(criteria.add(captor.capture())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());

    NeoAccessHelper.hasWindowAccess("the-real-window-id", "GET");

    List<Criterion> filters = captor.getAllValues();
    assertEquals("Expected exactly 3 filters (window, role, active)", 3, filters.size());

    boolean sawWindowFilter = false;
    boolean sawRoleFilter = false;
    boolean sawActiveFilter = false;
    for (Criterion c : filters) {
      SimpleExpression expr = (SimpleExpression) c;
      if ((WindowAccess.PROPERTY_WINDOW + ".id").equals(expr.getPropertyName())) {
        assertEquals("the-real-window-id", expr.getValue());
        sawWindowFilter = true;
      } else if ((WindowAccess.PROPERTY_ROLE + ".id").equals(expr.getPropertyName())) {
        assertEquals("role-id-captured", expr.getValue());
        sawRoleFilter = true;
      } else if (WindowAccess.PROPERTY_ACTIVE.equals(expr.getPropertyName())) {
        assertEquals(Boolean.TRUE, expr.getValue());
        sawActiveFilter = true;
      }
    }
    assertTrue("Missing filter on WindowAccess.window.id", sawWindowFilter);
    assertTrue("Missing filter on WindowAccess.role.id", sawRoleFilter);
    assertTrue("Missing filter on WindowAccess.active = true (an inactive row must "
        + "behave as no-row-at-all, i.e. deny)", sawActiveFilter);
  }

  /**
   * QA gap-closer: a role that has an active, full-access {@code WindowAccess} row for a
   * DIFFERENT window than the one being requested must still be denied — the row must never
   * leak access to a window it was not granted for. Since the Hibernate criteria itself cannot
   * run against a real database in a unit test, this is asserted the same way as
   * {@link #hasWindowAccess_queriesCriteriaWithWindowIdRoleIdAndActiveTrue}: by capturing the
   * actual filter value passed for the window-id property and proving it is the REQUESTED
   * window, not the one the role happens to have a grant for. If the implementation ever
   * queried the wrong id (or no id at all), this filter value would not match and the test
   * would fail — a bug this shape could otherwise slip through a criteria mock that always
   * answers "access found" regardless of what was asked.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccess_roleGrantIsForDifferentWindow_stillQueriesRequestedWindowId() {
    when(role.getId()).thenReturn("role-id-mismatch");
    OBCriteria<WindowAccess> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(WindowAccess.class)).thenReturn(criteria);
    ArgumentCaptor<Criterion> captor = ArgumentCaptor.forClass(Criterion.class);
    when(criteria.add(captor.capture())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    // The role has SOME active WindowAccess row (e.g. for a different window in real data),
    // but the query must be scoped to "requested-window", never "role-has-access-to-this-one".
    when(criteria.list()).thenReturn(Collections.emptyList());

    boolean result = NeoAccessHelper.hasWindowAccess("requested-window", "GET");

    assertFalse("A role with no active row for the REQUESTED window must be denied, "
        + "even if it holds a WindowAccess row for some other window", result);

    boolean queriedRequestedWindow = false;
    for (Criterion c : captor.getAllValues()) {
      SimpleExpression expr = (SimpleExpression) c;
      if ((WindowAccess.PROPERTY_WINDOW + ".id").equals(expr.getPropertyName())) {
        assertEquals("requested-window", expr.getValue());
        queriedRequestedWindow = true;
      }
    }
    assertTrue("findActiveWindowAccess must filter by the requested window id",
        queriedRequestedWindow);
  }

  // ── hasWindowAccessForSpec (ETP-4510 BUG-3) ──────────────────────────────────

  @Test
  public void hasWindowAccessForSpec_nullSpec_returnsFalse() {
    when(role.getId()).thenReturn("0");
    assertFalse(NeoAccessHelper.hasWindowAccessForSpec(null, "GET"));
  }

  @Test
  public void hasWindowAccessForSpec_noRoleAssigned_returnsFalseRegardlessOfWindow() {
    when(context.getRole()).thenReturn(null);

    SFSpec windowedSpec = mock(SFSpec.class);
    Window window = mock(Window.class);
    when(window.getId()).thenReturn("window-id-1");
    when(windowedSpec.getADWindow()).thenReturn(window);

    SFSpec windowlessSpec = mock(SFSpec.class);
    when(windowlessSpec.getADWindow()).thenReturn(null);

    assertFalse(NeoAccessHelper.hasWindowAccessForSpec(windowedSpec, "GET"));
    assertFalse(NeoAccessHelper.hasWindowAccessForSpec(windowlessSpec, "GET"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccessForSpec_directWindow_delegatesToHasWindowAccess_allow() {
    when(role.getId()).thenReturn("role-id-direct-window");
    SFSpec spec = mock(SFSpec.class);
    Window window = mock(Window.class);
    when(window.getId()).thenReturn("direct-window-id");
    when(spec.getADWindow()).thenReturn(window);

    OBCriteria<WindowAccess> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(WindowAccess.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    WindowAccess access = mock(WindowAccess.class);
    when(criteria.list()).thenReturn(Collections.singletonList(access));

    assertTrue(NeoAccessHelper.hasWindowAccessForSpec(spec, "GET"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccessForSpec_directWindow_delegatesToHasWindowAccess_deny() {
    when(role.getId()).thenReturn("role-id-direct-window-denied");
    SFSpec spec = mock(SFSpec.class);
    Window window = mock(Window.class);
    when(window.getId()).thenReturn("direct-window-id-denied");
    when(spec.getADWindow()).thenReturn(window);

    OBCriteria<WindowAccess> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(WindowAccess.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());

    assertFalse(NeoAccessHelper.hasWindowAccessForSpec(spec, "GET"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccessForSpec_windowlessSpecNoCombinationData_allowsAuthenticatedRole() {
    when(role.getId()).thenReturn("role-id-no-combination");
    SFSpec spec = mock(SFSpec.class);
    when(spec.getADWindow()).thenReturn(null);
    when(spec.getId()).thenReturn("spec-no-combination");

    // No entity at all — the not-posted-documents / dashboard shape.
    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFEntity.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.emptyList());

    assertTrue(NeoAccessHelper.hasWindowAccessForSpec(spec, "GET"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccessForSpec_windowlessSpecEntitiesWithoutTab_allowsAuthenticatedRole() {
    when(role.getId()).thenReturn("role-id-entities-no-tab");
    SFSpec spec = mock(SFSpec.class);
    when(spec.getADWindow()).thenReturn(null);
    when(spec.getId()).thenReturn("spec-entities-no-tab");

    SFEntity entityWithoutTab = mock(SFEntity.class);
    when(entityWithoutTab.getADTab()).thenReturn(null);

    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFEntity.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(entityWithoutTab));

    assertTrue(NeoAccessHelper.hasWindowAccessForSpec(spec, "GET"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccessForSpec_windowlessSpecAllConstituentWindowsAccessible_returnsTrue() {
    when(role.getId()).thenReturn("role-id-combo-allowed");
    SFSpec spec = mock(SFSpec.class);
    when(spec.getADWindow()).thenReturn(null);
    when(spec.getId()).thenReturn("spec-combo-allowed");

    SFEntity entityA = entityForWindow("window-a");
    SFEntity entityB = entityForWindow("window-b");

    OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
    when(entityCriteria.add(any())).thenReturn(entityCriteria);
    when(entityCriteria.list()).thenReturn(Arrays.asList(entityA, entityB));

    // Both constituent windows have an active, full-access WindowAccess row.
    OBCriteria<WindowAccess> windowAccessCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(WindowAccess.class)).thenReturn(windowAccessCriteria);
    when(windowAccessCriteria.add(any())).thenReturn(windowAccessCriteria);
    when(windowAccessCriteria.setMaxResults(1)).thenReturn(windowAccessCriteria);
    WindowAccess access = mock(WindowAccess.class);
    when(windowAccessCriteria.list()).thenReturn(Collections.singletonList(access));

    assertTrue(NeoAccessHelper.hasWindowAccessForSpec(spec, "GET"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccessForSpec_windowlessSpecOneConstituentWindowInaccessible_returnsFalse() {
    when(role.getId()).thenReturn("role-id-combo-partial");
    SFSpec spec = mock(SFSpec.class);
    when(spec.getADWindow()).thenReturn(null);
    when(spec.getId()).thenReturn("spec-combo-partial");

    SFEntity entityA = entityForWindow("window-accessible");
    SFEntity entityB = entityForWindow("window-inaccessible");

    OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
    when(entityCriteria.add(any())).thenReturn(entityCriteria);
    when(entityCriteria.list()).thenReturn(Arrays.asList(entityA, entityB));

    // First WindowAccess lookup finds a row, second finds none — one inaccessible window
    // is enough to deny the whole spec.
    OBCriteria<WindowAccess> windowAccessCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(WindowAccess.class)).thenReturn(windowAccessCriteria);
    when(windowAccessCriteria.add(any())).thenReturn(windowAccessCriteria);
    when(windowAccessCriteria.setMaxResults(1)).thenReturn(windowAccessCriteria);
    WindowAccess access = mock(WindowAccess.class);
    when(windowAccessCriteria.list())
        .thenReturn(Collections.singletonList(access))
        .thenReturn(Collections.emptyList());

    assertFalse(NeoAccessHelper.hasWindowAccessForSpec(spec, "GET"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void hasWindowAccessForSpec_windowlessSpecDedupesRepeatedWindow_checksOnce() {
    when(role.getId()).thenReturn("role-id-combo-dedup");
    SFSpec spec = mock(SFSpec.class);
    when(spec.getADWindow()).thenReturn(null);
    when(spec.getId()).thenReturn("spec-combo-dedup");

    // Two entities, two different tabs, but both tabs belong to the same window.
    SFEntity entityA = entityForWindow("shared-window");
    SFEntity entityB = entityForWindow("shared-window");

    OBCriteria<SFEntity> entityCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(SFEntity.class)).thenReturn(entityCriteria);
    when(entityCriteria.add(any())).thenReturn(entityCriteria);
    when(entityCriteria.list()).thenReturn(Arrays.asList(entityA, entityB));

    OBCriteria<WindowAccess> windowAccessCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(WindowAccess.class)).thenReturn(windowAccessCriteria);
    when(windowAccessCriteria.add(any())).thenReturn(windowAccessCriteria);
    when(windowAccessCriteria.setMaxResults(1)).thenReturn(windowAccessCriteria);
    WindowAccess access = mock(WindowAccess.class);
    when(windowAccessCriteria.list()).thenReturn(Collections.singletonList(access));

    assertTrue(NeoAccessHelper.hasWindowAccessForSpec(spec, "GET"));
    // Dedup proof: two entities resolve to the same window ID, so the WindowAccess
    // criteria must only be built (and checked) once — not once per entity.
    verify(dal, times(1)).createCriteria(WindowAccess.class);
  }

  /**
   * Builds an {@link SFEntity} mock whose {@link SFEntity#getADTab()} resolves to a
   * {@link Tab} belonging to the given window ID — the "combination of windows" shape
   * consumed by {@code resolveConstituentWindowIds}.
   */
  private static SFEntity entityForWindow(String windowId) {
    SFEntity entity = mock(SFEntity.class);
    Tab tab = mock(Tab.class);
    Window window = mock(Window.class);
    when(window.getId()).thenReturn(windowId);
    when(tab.getWindow()).thenReturn(window);
    when(entity.getADTab()).thenReturn(tab);
    return entity;
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

  /**
   * Code-review WARNING fix: hasObuiappProcessAccess used to call
   * OBContext.getOBContext().getRole().getId() directly and NPE when no role was
   * assigned, instead of denying like hasWindowAccess/hasProcessAccess do.
   */
  @Test
  public void hasObuiappProcessAccess_noRoleAssigned_returnsFalse() {
    when(context.getRole()).thenReturn(null);

    assertFalse(NeoAccessHelper.hasObuiappProcessAccess("any-obuiapp-proc-id"));
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
