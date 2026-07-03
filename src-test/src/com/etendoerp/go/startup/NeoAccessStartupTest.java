/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
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
package com.etendoerp.go.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link NeoAccessStartup}.
 *
 * <p>Covers: a target automatic role gains access to an SF window/process it was missing; existing
 * access is not duplicated (idempotency); a second run grants nothing; and the role criteria filters
 * to active + non-manual + non-system-client roles only.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NeoAccessStartupTest {

  private NeoAccessStartup startup;

  private OBDal obDal;
  private OBProvider obProvider;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBProvider> obProviderMock;
  private MockedStatic<OBContext> obContextMock;

  private Organization orgZero;
  private Client client;

  @BeforeEach
  void setUp() {
    startup = new NeoAccessStartup();
    obDal = mock(OBDal.class);
    obProvider = mock(OBProvider.class);

    obDalMock = mockStatic(OBDal.class);
    obProviderMock = mockStatic(OBProvider.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);

    orgZero = mock(Organization.class);
    client = mock(Client.class);
    when(obDal.get(Organization.class, "0")).thenReturn(orgZero);
  }

  @AfterEach
  void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (obProviderMock != null) {
      obProviderMock.close();
    }
    if (obContextMock != null) {
      obContextMock.close();
    }
  }

  @SuppressWarnings("unchecked")
  private OBCriteria<Role> stubRoleCriteria(List<Role> result) {
    OBCriteria<Role> criteria = mock(OBCriteria.class);
    when(criteria.list()).thenReturn(result);
    when(obDal.createCriteria(Role.class)).thenReturn(criteria);
    return criteria;
  }

  @SuppressWarnings("unchecked")
  private void stubSpecCriteria(List<SFSpec> windowSpecs, List<SFSpec> processSpecs) {
    // grantMissingAccess() calls activeSpecs("W") then activeSpecs("P") before iterating roles.
    OBCriteria<SFSpec> windowCriteria = mock(OBCriteria.class);
    when(windowCriteria.list()).thenReturn(windowSpecs);
    OBCriteria<SFSpec> processCriteria = mock(OBCriteria.class);
    when(processCriteria.list()).thenReturn(processSpecs);
    when(obDal.createCriteria(SFSpec.class)).thenReturn(windowCriteria, processCriteria);
  }

  @SuppressWarnings("unchecked")
  private void stubExistingAccess(List<WindowAccess> windowAccess, List<ProcessAccess> processAccess) {
    OBCriteria<WindowAccess> waCriteria = mock(OBCriteria.class);
    when(waCriteria.list()).thenReturn(windowAccess);
    when(obDal.createCriteria(WindowAccess.class)).thenReturn(waCriteria);
    OBCriteria<ProcessAccess> paCriteria = mock(OBCriteria.class);
    when(paCriteria.list()).thenReturn(processAccess);
    when(obDal.createCriteria(ProcessAccess.class)).thenReturn(paCriteria);
  }

  private SFSpec windowSpec(String windowId) {
    SFSpec spec = mock(SFSpec.class);
    Window window = mock(Window.class);
    when(window.getId()).thenReturn(windowId);
    when(spec.getADWindow()).thenReturn(window);
    return spec;
  }

  private SFSpec processSpec(String processId) {
    SFSpec spec = mock(SFSpec.class);
    Process process = mock(Process.class);
    when(process.getId()).thenReturn(processId);
    when(spec.getProcess()).thenReturn(process);
    return spec;
  }

  private Role targetRole() {
    Role role = mock(Role.class);
    when(role.getClient()).thenReturn(client);
    when(client.getId()).thenReturn("real-client");
    return role;
  }

  @Test
  @DisplayName("grants missing window and process access to a target automatic role")
  void grantsMissingAccessToTargetRole() {
    Role role = targetRole();
    stubRoleCriteria(List.of(role));
    stubSpecCriteria(List.of(windowSpec("win-new")), List.of(processSpec("proc-new")));
    stubExistingAccess(List.of(), List.of());

    WindowAccess wa = mock(WindowAccess.class);
    when(obProvider.get(WindowAccess.class)).thenReturn(wa);
    ProcessAccess pa = mock(ProcessAccess.class);
    when(obProvider.get(ProcessAccess.class)).thenReturn(pa);

    startup.grantMissingAccess();

    verify(wa).setNewOBObject(true);
    verify(wa).setClient(client);
    verify(wa).setOrganization(orgZero);
    verify(wa).setRole(role);
    verify(wa).setEditableField(true);
    verify(pa).setNewOBObject(true);
    verify(pa).setRole(role);
    verify(obDal).save(wa);
    verify(obDal).save(pa);
    verify(obDal).flush();
    verify(obDal).commitAndClose();
  }

  @Test
  @DisplayName("idempotent: does not duplicate access the role already has")
  void doesNotDuplicateExistingAccess() {
    Role role = targetRole();
    stubRoleCriteria(List.of(role));
    stubSpecCriteria(List.of(windowSpec("win-existing")), List.of(processSpec("proc-existing")));

    WindowAccess existingWa = mock(WindowAccess.class);
    Window existingWindow = mock(Window.class);
    when(existingWindow.getId()).thenReturn("win-existing");
    when(existingWa.getWindow()).thenReturn(existingWindow);

    ProcessAccess existingPa = mock(ProcessAccess.class);
    Process existingProcess = mock(Process.class);
    when(existingProcess.getId()).thenReturn("proc-existing");
    when(existingPa.getProcess()).thenReturn(existingProcess);

    stubExistingAccess(List.of(existingWa), List.of(existingPa));

    startup.grantMissingAccess();

    // Nothing new created — the role already had both.
    verify(obProvider, never()).get(WindowAccess.class);
    verify(obProvider, never()).get(ProcessAccess.class);
    verify(obDal, never()).save(any());
    verify(obDal).commitAndClose();
  }

  @Test
  @DisplayName("specs with null window/process references are skipped")
  void skipsNullReferences() {
    Role role = targetRole();
    stubRoleCriteria(List.of(role));

    SFSpec nullWindow = mock(SFSpec.class);
    when(nullWindow.getADWindow()).thenReturn(null);
    SFSpec nullProcess = mock(SFSpec.class);
    when(nullProcess.getProcess()).thenReturn(null);
    stubSpecCriteria(List.of(nullWindow), List.of(nullProcess));
    stubExistingAccess(List.of(), List.of());

    startup.grantMissingAccess();

    verify(obProvider, never()).get(WindowAccess.class);
    verify(obProvider, never()).get(ProcessAccess.class);
    verify(obDal, never()).save(any());
  }

  @Test
  @DisplayName("no target roles: commits an empty pass without creating access")
  void noTargetRolesCommitsEmptyPass() {
    stubRoleCriteria(List.of());
    stubSpecCriteria(List.of(windowSpec("win-new")), List.of(processSpec("proc-new")));

    startup.grantMissingAccess();

    verify(obProvider, never()).get(WindowAccess.class);
    verify(obProvider, never()).get(ProcessAccess.class);
    verify(obDal, never()).save(any());
    verify(obDal).commitAndClose();
  }

  @Test
  @DisplayName("system-client role (client '0') is skipped — trigger-managed access untouched")
  void skipsSystemClientRole() {
    Role systemRole = mock(Role.class);
    Client systemClient = mock(Client.class);
    when(systemClient.getId()).thenReturn("0");
    when(systemRole.getClient()).thenReturn(systemClient);

    stubRoleCriteria(List.of(systemRole));
    stubSpecCriteria(List.of(windowSpec("win-new")), List.of(processSpec("proc-new")));

    startup.grantMissingAccess();

    // The system role is filtered out before any access lookup or grant.
    verify(obDal, never()).createCriteria(WindowAccess.class);
    verify(obProvider, never()).get(WindowAccess.class);
    verify(obDal, never()).save(any());
    verify(obDal).commitAndClose();
  }

  @Test
  @DisplayName("two distinct missing windows grant two access rows for one role")
  void grantsAllMissingWindowsForRole() {
    Role role = targetRole();
    stubRoleCriteria(List.of(role));
    stubSpecCriteria(List.of(windowSpec("win-a"), windowSpec("win-b")), List.of());
    stubExistingAccess(List.of(), List.of());

    when(obProvider.get(WindowAccess.class))
        .thenReturn(mock(WindowAccess.class), mock(WindowAccess.class));

    startup.grantMissingAccess();

    verify(obProvider, times(2)).get(WindowAccess.class);
    verify(obDal, times(2)).save(any(WindowAccess.class));
  }
}
