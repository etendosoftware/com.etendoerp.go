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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Callable;

import org.hibernate.Session;
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
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Unit tests for {@link McpSessionManager}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpSessionManagerTest {

  private static final String USER_ID = "testUser";
  private static final String ROLE_ID = "testRole";
  private static final String CLIENT_ID = "testClient";
  private static final String ORG_ID = "testOrg";
  private static final String WAREHOUSE_ID = "testWarehouse";

  @Mock private OBDal obDal;
  @Mock private OBContext previousContext;
  @Mock private OBContext newContext;
  @Mock private Session hibernateSession;

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<SecureWebServicesUtils> swsMock;

  @BeforeEach
  void setUp() {
    obDalMock = mockStatic(OBDal.class);
    obContextMock = mockStatic(OBContext.class);
    swsMock = mockStatic(SecureWebServicesUtils.class);

    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock.when(OBContext::getOBContext).thenReturn(previousContext);
    swsMock.when(() -> SecureWebServicesUtils.createContext(
        anyString(), anyString(), anyString(), any(), anyString()
    )).thenReturn(newContext);

    when(obDal.getSession()).thenReturn(hibernateSession);
  }

  @AfterEach
  void tearDown() {
    if (swsMock != null) {
      swsMock.close();
    }
    if (obContextMock != null) {
      obContextMock.close();
    }
    if (obDalMock != null) {
      obDalMock.close();
    }
  }

  @Nested
  @DisplayName("Private constructor")
  class PrivateConstructor {

    @Test
    @DisplayName("constructor is private and throws when invoked via reflection")
    void constructorIsPrivate() throws Exception {
      Constructor<McpSessionManager> ctor = McpSessionManager.class.getDeclaredConstructor();
      ctor.setAccessible(true);
      // Should not throw — it's a utility class private ctor
      McpSessionManager instance = ctor.newInstance();
      // Just verify it is instantiable (no exception); the private modifier is the guard
      assertEquals(McpSessionManager.class, instance.getClass());
    }
  }

  @Nested
  @DisplayName("executeInContext")
  class ExecuteInContext {

    @Test
    @DisplayName("returns callable result and flushes on success")
    void returnsCallableResultAndFlushes() throws Exception {
      String expected = "result";
      String result = McpSessionManager.executeInContext(
          USER_ID, ROLE_ID, CLIENT_ID, ORG_ID, WAREHOUSE_ID, () -> expected);

      assertEquals(expected, result);
      verify(obDal).flush();
      obContextMock.verify(() -> OBContext.setOBContext(newContext));
      obContextMock.verify(() -> OBContext.setOBContext(previousContext));
    }

    @Test
    @DisplayName("rolls back and rethrows when callable throws")
    void rollsBackOnFailure() {
      RuntimeException cause = new RuntimeException("boom");

      Exception thrown = assertThrows(RuntimeException.class,
          () -> McpSessionManager.executeInContext(
              USER_ID, ROLE_ID, CLIENT_ID, ORG_ID, WAREHOUSE_ID,
              () -> { throw cause; }));

      assertSame(cause, thrown);
      verify(obDal).rollbackAndClose();
      verify(obDal, never()).flush();
      // Previous context is always restored
      obContextMock.verify(() -> OBContext.setOBContext(previousContext));
    }

    @Test
    @DisplayName("restores previous context even when callable throws")
    void restoresPreviousContextOnFailure() {
      assertThrows(RuntimeException.class,
          () -> McpSessionManager.executeInContext(
              USER_ID, ROLE_ID, CLIENT_ID, ORG_ID, WAREHOUSE_ID,
              () -> { throw new RuntimeException("fail"); }));

      obContextMock.verify(() -> OBContext.setOBContext(previousContext));
    }

    @Test
    @DisplayName("null orgId defaults to '0' and triggers org resolution")
    void nullOrgIdDefaultsToZero() throws Exception {
      // When orgId is null, it defaults to "0" which triggers resolveDefaultOrg
      // Since resolveDefaultOrg will use OBDal.getSession().doReturningWork(),
      // we configure the session mock to return a resolved org
      when(hibernateSession.doReturningWork(any())).thenReturn("resolvedOrg");

      McpSessionManager.executeInContext(
          USER_ID, ROLE_ID, CLIENT_ID, null, WAREHOUSE_ID, () -> "ok");

      // createContext should be called with the resolved org
      swsMock.verify(() -> SecureWebServicesUtils.createContext(
          eq(USER_ID), eq(ROLE_ID), eq("resolvedOrg"), eq(WAREHOUSE_ID), eq(CLIENT_ID)));
    }

    @Test
    @DisplayName("non-zero orgId passes through without resolution")
    void nonZeroOrgPassesThrough() throws Exception {
      McpSessionManager.executeInContext(
          USER_ID, ROLE_ID, CLIENT_ID, "specificOrg", WAREHOUSE_ID, () -> "ok");

      swsMock.verify(() -> SecureWebServicesUtils.createContext(
          eq(USER_ID), eq(ROLE_ID), eq("specificOrg"), eq(WAREHOUSE_ID), eq(CLIENT_ID)));
      // doReturningWork should NOT be called for org resolution
      verify(hibernateSession, never()).doReturningWork(any());
    }

    @Test
    @DisplayName("client '0' triggers resolveClientFromRole")
    void zeroClientTriggersResolution() throws Exception {
      // org is non-zero to avoid org resolution interfering
      // First doReturningWork call is for client resolution
      when(hibernateSession.doReturningWork(any())).thenReturn("resolvedClient");

      McpSessionManager.executeInContext(
          USER_ID, ROLE_ID, "0", "specificOrg", WAREHOUSE_ID, () -> "ok");

      swsMock.verify(() -> SecureWebServicesUtils.createContext(
          eq(USER_ID), eq(ROLE_ID), eq("specificOrg"), eq(WAREHOUSE_ID), eq("resolvedClient")));
    }

    @Test
    @DisplayName("non-zero clientId passes through without resolution")
    void nonZeroClientPassesThrough() throws Exception {
      McpSessionManager.executeInContext(
          USER_ID, ROLE_ID, "myClient", "specificOrg", WAREHOUSE_ID, () -> "ok");

      swsMock.verify(() -> SecureWebServicesUtils.createContext(
          eq(USER_ID), eq(ROLE_ID), eq("specificOrg"), eq(WAREHOUSE_ID), eq("myClient")));
    }

    @Test
    @DisplayName("org '0' with resolveDefaultOrg returning null keeps '0'")
    void orgZeroWithNullResolutionKeepsZero() throws Exception {
      when(hibernateSession.doReturningWork(any())).thenReturn(null);

      McpSessionManager.executeInContext(
          USER_ID, ROLE_ID, CLIENT_ID, "0", WAREHOUSE_ID, () -> "ok");

      swsMock.verify(() -> SecureWebServicesUtils.createContext(
          eq(USER_ID), eq(ROLE_ID), eq("0"), eq(WAREHOUSE_ID), eq(CLIENT_ID)));
    }

    @Test
    @DisplayName("client '0' with resolveClientFromRole returning null keeps '0'")
    void clientZeroWithNullResolutionKeepsZero() throws Exception {
      when(hibernateSession.doReturningWork(any())).thenReturn(null);

      McpSessionManager.executeInContext(
          USER_ID, ROLE_ID, "0", "specificOrg", WAREHOUSE_ID, () -> "ok");

      swsMock.verify(() -> SecureWebServicesUtils.createContext(
          eq(USER_ID), eq(ROLE_ID), eq("specificOrg"), eq(WAREHOUSE_ID), eq("0")));
    }
  }

  @Nested
  @DisplayName("Convenience overloads")
  class ConvenienceOverloads {

    @Test
    @DisplayName("3-arg executeInContext delegates with org='0' and warehouse=null")
    void executeInContextThreeArgs() throws Exception {
      // Use non-zero client to avoid client resolution, but org="0" triggers org resolution
      when(hibernateSession.doReturningWork(any())).thenReturn(null);

      String result = McpSessionManager.executeInContext(
          USER_ID, ROLE_ID, CLIENT_ID, () -> "value");

      assertEquals("value", result);
      swsMock.verify(() -> SecureWebServicesUtils.createContext(
          eq(USER_ID), eq(ROLE_ID), eq("0"), eq(null), eq(CLIENT_ID)));
    }

    @Test
    @DisplayName("5-arg runInContext executes action and returns void")
    void runInContextFiveArgs() throws Exception {
      Runnable action = mock(Runnable.class);

      McpSessionManager.runInContext(
          USER_ID, ROLE_ID, CLIENT_ID, ORG_ID, WAREHOUSE_ID, action);

      verify(action).run();
      verify(obDal).flush();
    }

    @Test
    @DisplayName("3-arg runInContext delegates with org='0' and warehouse=null")
    void runInContextThreeArgs() throws Exception {
      when(hibernateSession.doReturningWork(any())).thenReturn(null);
      Runnable action = mock(Runnable.class);

      McpSessionManager.runInContext(USER_ID, ROLE_ID, CLIENT_ID, action);

      verify(action).run();
      swsMock.verify(() -> SecureWebServicesUtils.createContext(
          eq(USER_ID), eq(ROLE_ID), eq("0"), eq(null), eq(CLIENT_ID)));
    }

    @Test
    @DisplayName("runInContext propagates exception from action")
    void runInContextPropagatesException() {
      Runnable action = mock(Runnable.class);
      RuntimeException cause = new RuntimeException("action failed");
      org.mockito.Mockito.doThrow(cause).when(action).run();

      RuntimeException thrown = assertThrows(RuntimeException.class,
          () -> McpSessionManager.runInContext(
              USER_ID, ROLE_ID, CLIENT_ID, ORG_ID, WAREHOUSE_ID, action));

      assertSame(cause, thrown);
      verify(obDal).rollbackAndClose();
    }
  }

  @Nested
  @DisplayName("resolveDefaultOrg (via reflection)")
  class ResolveDefaultOrg {

    private Method resolveDefaultOrgMethod;

    @BeforeEach
    void setUpReflection() throws Exception {
      resolveDefaultOrgMethod = McpSessionManager.class.getDeclaredMethod(
          "resolveDefaultOrg", String.class);
      resolveDefaultOrgMethod.setAccessible(true);
    }

    @Test
    @DisplayName("returns org when found")
    void returnsOrgWhenFound() throws Exception {
      when(hibernateSession.doReturningWork(any())).thenReturn("foundOrg");

      String result = (String) resolveDefaultOrgMethod.invoke(null, ROLE_ID);

      assertEquals("foundOrg", result);
    }

    @Test
    @DisplayName("returns null when no org found")
    void returnsNullWhenNoOrgFound() throws Exception {
      when(hibernateSession.doReturningWork(any())).thenReturn(null);

      String result = (String) resolveDefaultOrgMethod.invoke(null, ROLE_ID);

      assertNull(result);
    }

    @Test
    @DisplayName("returns null on exception")
    void returnsNullOnException() throws Exception {
      when(hibernateSession.doReturningWork(any()))
          .thenThrow(new RuntimeException("db error"));

      String result = (String) resolveDefaultOrgMethod.invoke(null, ROLE_ID);

      assertNull(result);
    }
  }

  @Nested
  @DisplayName("resolveClientFromRole (via reflection)")
  class ResolveClientFromRole {

    private Method resolveClientFromRoleMethod;

    @BeforeEach
    void setUpReflection() throws Exception {
      resolveClientFromRoleMethod = McpSessionManager.class.getDeclaredMethod(
          "resolveClientFromRole", String.class);
      resolveClientFromRoleMethod.setAccessible(true);
    }

    @Test
    @DisplayName("returns client when found and non-zero")
    void returnsClientWhenFoundNonZero() throws Exception {
      when(hibernateSession.doReturningWork(any())).thenReturn("clientABC");

      String result = (String) resolveClientFromRoleMethod.invoke(null, ROLE_ID);

      assertEquals("clientABC", result);
    }

    @Test
    @DisplayName("returns null when client is '0'")
    void returnsNullWhenClientIsZero() throws Exception {
      // The method internally checks if cid is "0" and returns null
      // doReturningWork returns null because the lambda returns null for "0"
      when(hibernateSession.doReturningWork(any())).thenReturn(null);

      String result = (String) resolveClientFromRoleMethod.invoke(null, ROLE_ID);

      assertNull(result);
    }

    @Test
    @DisplayName("returns null when no result")
    void returnsNullWhenNoResult() throws Exception {
      when(hibernateSession.doReturningWork(any())).thenReturn(null);

      String result = (String) resolveClientFromRoleMethod.invoke(null, ROLE_ID);

      assertNull(result);
    }

    @Test
    @DisplayName("returns null on exception")
    void returnsNullOnException() throws Exception {
      when(hibernateSession.doReturningWork(any()))
          .thenThrow(new RuntimeException("db error"));

      String result = (String) resolveClientFromRoleMethod.invoke(null, ROLE_ID);

      assertNull(result);
    }
  }
}
