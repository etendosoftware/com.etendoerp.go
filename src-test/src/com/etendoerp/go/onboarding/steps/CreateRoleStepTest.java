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
package com.etendoerp.go.onboarding.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
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

import com.etendoerp.go.onboarding.OnboardingContext;
import com.etendoerp.go.onboarding.OnboardingStepException;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link CreateRoleStep}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateRoleStepTest {

  private CreateRoleStep step;

  @Mock private OBDal obDal;
  @Mock private OBProvider obProvider;
  @Mock private Connection connection;
  @Mock private Client client;
  @Mock private Organization orgZero;
  @Mock private Role role;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBProvider> obProviderMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    step = new CreateRoleStep();
    obDalMock = mockStatic(OBDal.class);
    obProviderMock = mockStatic(OBProvider.class);
    obContextMock = mockStatic(OBContext.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
    when(obDal.getConnection()).thenReturn(connection);
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

  // --- name ---

  @Test
  @DisplayName("name() returns createRole")
  void nameReturnsCreateRole() {
    assertEquals("createRole", step.name());
  }

  // --- execute: error paths ---

  @Nested
  @DisplayName("execute error paths")
  class ExecuteErrors {

    @Test
    @DisplayName("null client throws OnboardingStepException")
    void nullClientThrowsOnboardingStepException() {
      OnboardingContext ctx = new OnboardingContext();
      ctx.setClientId("bad-client");
      when(obDal.get(Client.class, "bad-client")).thenReturn(null);

      OnboardingStepException ex = assertThrows(OnboardingStepException.class,
          () -> step.execute(ctx));
      assertTrue(ex.getMessage().contains("Client not found"));
    }

    @Test
    @DisplayName("no role found throws OnboardingStepException")
    void noRoleFoundThrowsOnboardingStepException() throws Exception {
      OnboardingContext ctx = new OnboardingContext();
      ctx.setClientId("client-1");
      when(obDal.get(Client.class, "client-1")).thenReturn(client);
      when(obDal.get(Organization.class, "0")).thenReturn(orgZero);

      // UPDATE statement
      PreparedStatement updatePs = mock(PreparedStatement.class);
      when(connection.prepareStatement(
          "UPDATE ad_role SET iswebserviceenabled = 'Y' WHERE ad_client_id = ?"))
          .thenReturn(updatePs);

      // SELECT role returns empty result set
      PreparedStatement selectPs = mock(PreparedStatement.class);
      when(connection.prepareStatement(
          "SELECT ad_role_id FROM ad_role WHERE ad_client_id = ? ORDER BY created LIMIT 1"))
          .thenReturn(selectPs);
      ResultSet emptyRs = mock(ResultSet.class);
      when(selectPs.executeQuery()).thenReturn(emptyRs);
      when(emptyRs.next()).thenReturn(false);

      OnboardingStepException ex = assertThrows(OnboardingStepException.class,
          () -> step.execute(ctx));
      assertTrue(ex.getMessage().contains("No role found"));
    }
  }

  // --- execute: happy path ---

  @Nested
  @DisplayName("execute happy path")
  class ExecuteHappyPath {

    @Test
    @DisplayName("successful execute with window and process specs")
    void successfulExecuteWithWindowAndProcessSpecs() throws Exception {
      OnboardingContext ctx = new OnboardingContext();
      ctx.setClientId("client-1");

      when(obDal.get(Client.class, "client-1")).thenReturn(client);
      when(obDal.get(Organization.class, "0")).thenReturn(orgZero);

      // JDBC: UPDATE webservice
      PreparedStatement updatePs = mock(PreparedStatement.class);
      when(connection.prepareStatement(
          "UPDATE ad_role SET iswebserviceenabled = 'Y' WHERE ad_client_id = ?"))
          .thenReturn(updatePs);

      // JDBC: SELECT role
      PreparedStatement selectRolePs = mock(PreparedStatement.class);
      when(connection.prepareStatement(
          "SELECT ad_role_id FROM ad_role WHERE ad_client_id = ? ORDER BY created LIMIT 1"))
          .thenReturn(selectRolePs);
      ResultSet roleRs = mock(ResultSet.class);
      when(selectRolePs.executeQuery()).thenReturn(roleRs);
      when(roleRs.next()).thenReturn(true);
      when(roleRs.getString(1)).thenReturn("role-123");

      when(obDal.get(Role.class, "role-123")).thenReturn(role);
      when(role.getId()).thenReturn("role-123");

      // JDBC: existing window access (returns "win-existing" as already granted)
      PreparedStatement existWinPs = mock(PreparedStatement.class);
      when(connection.prepareStatement(
          "SELECT ad_window_id FROM ad_window_access WHERE ad_role_id = ?"))
          .thenReturn(existWinPs);
      ResultSet existWinRs = mock(ResultSet.class);
      when(existWinPs.executeQuery()).thenReturn(existWinRs);
      when(existWinRs.next()).thenReturn(true, false);
      when(existWinRs.getString(1)).thenReturn("win-existing");

      // JDBC: existing process access (empty)
      PreparedStatement existProcPs = mock(PreparedStatement.class);
      when(connection.prepareStatement(
          "SELECT ad_process_id FROM ad_process_access WHERE ad_role_id = ?"))
          .thenReturn(existProcPs);
      ResultSet existProcRs = mock(ResultSet.class);
      when(existProcPs.executeQuery()).thenReturn(existProcRs);
      when(existProcRs.next()).thenReturn(false);

      // Window specs criteria: one new window + one already existing
      OBCriteria<SFSpec> windowCriteria = mock(OBCriteria.class);
      SFSpec windowSpec1 = mock(SFSpec.class);
      Window newWindow = mock(Window.class);
      when(newWindow.getId()).thenReturn("win-new");
      when(windowSpec1.getADWindow()).thenReturn(newWindow);

      SFSpec windowSpec2 = mock(SFSpec.class);
      Window existingWindow = mock(Window.class);
      when(existingWindow.getId()).thenReturn("win-existing");
      when(windowSpec2.getADWindow()).thenReturn(existingWindow);

      when(windowCriteria.list()).thenReturn(List.of(windowSpec1, windowSpec2));

      // Process specs criteria: one new process
      OBCriteria<SFSpec> processCriteria = mock(OBCriteria.class);
      SFSpec processSpec = mock(SFSpec.class);
      Process newProcess = mock(Process.class);
      when(newProcess.getId()).thenReturn("proc-new");
      when(processSpec.getProcess()).thenReturn(newProcess);

      when(processCriteria.list()).thenReturn(List.of(processSpec));

      // Return window criteria first, then process criteria
      when(obDal.createCriteria(SFSpec.class))
          .thenReturn(windowCriteria)
          .thenReturn(processCriteria);

      // OBProvider returns mocks for access entities
      WindowAccess windowAccess = mock(WindowAccess.class);
      when(obProvider.get(WindowAccess.class)).thenReturn(windowAccess);

      ProcessAccess processAccess = mock(ProcessAccess.class);
      when(obProvider.get(ProcessAccess.class)).thenReturn(processAccess);

      step.execute(ctx);

      // Verify WebService UPDATE was executed
      verify(updatePs).setString(1, "client-1");
      verify(updatePs).executeUpdate();

      // Verify setRoleId called on context
      assertEquals("role-123", ctx.getRoleId());

      // Verify WindowAccess created only for the new window (not the existing one)
      verify(obProvider, times(1)).get(WindowAccess.class);
      verify(windowAccess).setNewOBObject(true);
      verify(windowAccess).setClient(client);
      verify(windowAccess).setOrganization(orgZero);
      verify(windowAccess).setRole(role);
      verify(windowAccess).setWindow(newWindow);
      verify(windowAccess).setEditableField(true);

      // Verify ProcessAccess created for the new process
      verify(obProvider, times(1)).get(ProcessAccess.class);
      verify(processAccess).setNewOBObject(true);
      verify(processAccess).setClient(client);
      verify(processAccess).setOrganization(orgZero);
      verify(processAccess).setRole(role);
      verify(processAccess).setProcess(newProcess);

      // Verify both access objects were saved
      verify(obDal).save(windowAccess);
      verify(obDal).save(processAccess);
    }

    @Test
    @DisplayName("specs with null window/process are skipped")
    void specsWithNullReferencesAreSkipped() throws Exception {
      OnboardingContext ctx = new OnboardingContext();
      ctx.setClientId("client-1");

      when(obDal.get(Client.class, "client-1")).thenReturn(client);
      when(obDal.get(Organization.class, "0")).thenReturn(orgZero);

      // JDBC stubs
      PreparedStatement updatePs = mock(PreparedStatement.class);
      when(connection.prepareStatement(
          "UPDATE ad_role SET iswebserviceenabled = 'Y' WHERE ad_client_id = ?"))
          .thenReturn(updatePs);

      PreparedStatement selectRolePs = mock(PreparedStatement.class);
      when(connection.prepareStatement(
          "SELECT ad_role_id FROM ad_role WHERE ad_client_id = ? ORDER BY created LIMIT 1"))
          .thenReturn(selectRolePs);
      ResultSet roleRs = mock(ResultSet.class);
      when(selectRolePs.executeQuery()).thenReturn(roleRs);
      when(roleRs.next()).thenReturn(true);
      when(roleRs.getString(1)).thenReturn("role-456");

      when(obDal.get(Role.class, "role-456")).thenReturn(role);
      when(role.getId()).thenReturn("role-456");

      // Existing access queries: both empty
      PreparedStatement existWinPs = mock(PreparedStatement.class);
      when(connection.prepareStatement(
          "SELECT ad_window_id FROM ad_window_access WHERE ad_role_id = ?"))
          .thenReturn(existWinPs);
      ResultSet existWinRs = mock(ResultSet.class);
      when(existWinPs.executeQuery()).thenReturn(existWinRs);
      when(existWinRs.next()).thenReturn(false);

      PreparedStatement existProcPs = mock(PreparedStatement.class);
      when(connection.prepareStatement(
          "SELECT ad_process_id FROM ad_process_access WHERE ad_role_id = ?"))
          .thenReturn(existProcPs);
      ResultSet existProcRs = mock(ResultSet.class);
      when(existProcPs.executeQuery()).thenReturn(existProcRs);
      when(existProcRs.next()).thenReturn(false);

      // Window spec with null window
      OBCriteria<SFSpec> windowCriteria = mock(OBCriteria.class);
      SFSpec nullWindowSpec = mock(SFSpec.class);
      when(nullWindowSpec.getADWindow()).thenReturn(null);
      when(windowCriteria.list()).thenReturn(List.of(nullWindowSpec));

      // Process spec with null process
      OBCriteria<SFSpec> processCriteria = mock(OBCriteria.class);
      SFSpec nullProcessSpec = mock(SFSpec.class);
      when(nullProcessSpec.getProcess()).thenReturn(null);
      when(processCriteria.list()).thenReturn(List.of(nullProcessSpec));

      when(obDal.createCriteria(SFSpec.class))
          .thenReturn(windowCriteria)
          .thenReturn(processCriteria);

      step.execute(ctx);

      // No access objects should be created
      verify(obProvider, never()).get(WindowAccess.class);
      verify(obProvider, never()).get(ProcessAccess.class);
      verify(obDal, never()).save(any());
    }

    @Test
    @DisplayName("role not found in DAL throws OnboardingStepException")
    void roleNotFoundInDalThrowsException() throws Exception {
      OnboardingContext ctx = new OnboardingContext();
      ctx.setClientId("client-1");

      when(obDal.get(Client.class, "client-1")).thenReturn(client);
      when(obDal.get(Organization.class, "0")).thenReturn(orgZero);

      PreparedStatement updatePs = mock(PreparedStatement.class);
      when(connection.prepareStatement(
          "UPDATE ad_role SET iswebserviceenabled = 'Y' WHERE ad_client_id = ?"))
          .thenReturn(updatePs);

      PreparedStatement selectRolePs = mock(PreparedStatement.class);
      when(connection.prepareStatement(
          "SELECT ad_role_id FROM ad_role WHERE ad_client_id = ? ORDER BY created LIMIT 1"))
          .thenReturn(selectRolePs);
      ResultSet roleRs = mock(ResultSet.class);
      when(selectRolePs.executeQuery()).thenReturn(roleRs);
      when(roleRs.next()).thenReturn(true);
      when(roleRs.getString(1)).thenReturn("role-ghost");

      // Role found via JDBC but not via DAL
      when(obDal.get(Role.class, "role-ghost")).thenReturn(null);

      OnboardingStepException ex = assertThrows(OnboardingStepException.class,
          () -> step.execute(ctx));
      assertTrue(ex.getMessage().contains("Role not found"));
    }
  }
}
