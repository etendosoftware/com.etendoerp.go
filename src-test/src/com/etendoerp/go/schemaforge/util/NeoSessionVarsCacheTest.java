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
import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.secureApp.LoginUtils;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.database.ConnectionProvider;

/**
 * Unit tests for {@link NeoSessionVarsCache}.
 *
 * <p>Each test mocks {@link LoginUtils#fillSessionArguments} so it populates a
 * deterministic set of session keys on the {@link VariablesSecureApp} passed
 * in. {@link LoginUtils#readNumberFormat} is also mocked because the real
 * implementation parses {@code Format.xml} from disk via
 * {@code RequestContext.getServletContext()}, which is not available in unit
 * tests.</p>
 */
public class NeoSessionVarsCacheTest {

  @Before
  public void resetCache() {
    NeoSessionVarsCache.clear();
  }

  @After
  public void cleanup() {
    NeoSessionVarsCache.clear();
  }

  /**
   * Stub {@code LoginUtils.fillSessionArguments} so it injects a known set of
   * session variables on the VSA. {@code readNumberFormat} is stubbed to a
   * no-op so the test does not rely on a real {@code Format.xml}.
   */
  private static void stubLoginUtils(MockedStatic<LoginUtils> loginMock) {
    loginMock.when(() -> LoginUtils.fillSessionArguments(
        any(ConnectionProvider.class), any(VariablesSecureApp.class),
        anyString(), anyString(), anyString(),
        anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(inv -> {
          VariablesSecureApp vars = inv.getArgument(1);
          vars.setSessionValue("#AD_User_ID", inv.getArgument(2));
          vars.setSessionValue("#AD_Role_ID", inv.getArgument(5));
          vars.setSessionValue("#AD_Client_ID", inv.getArgument(6));
          vars.setSessionValue("#AD_Org_ID", inv.getArgument(7));
          vars.setSessionValue("#M_Warehouse_ID", inv.getArgument(8));
          vars.setSessionValue("#AD_Language", inv.getArgument(3));
          return Boolean.TRUE;
        });
    loginMock.when(() -> LoginUtils.readNumberFormat(any(VariablesSecureApp.class), anyString()))
        .thenAnswer(inv -> null);
  }

  @Test
  public void testGetOrLoadCachesSnapshotAcrossCalls() {
    try (MockedStatic<LoginUtils> loginMock = mockStatic(LoginUtils.class)) {
      stubLoginUtils(loginMock);

      Map<String, String> first = NeoSessionVarsCache.getOrLoad(
          "u", "r", "c", "o", "w", "es_ES");
      Map<String, String> second = NeoSessionVarsCache.getOrLoad(
          "u", "r", "c", "o", "w", "es_ES");

      assertSame("Cache must return the same instance on a hit", first, second);
      assertEquals("u", first.get("#AD_User_ID"));
      assertEquals("c", first.get("#AD_Client_ID"));
      // Slow-path utilities run only once for the same identity.
      loginMock.verify(() -> LoginUtils.fillSessionArguments(
          any(), any(), anyString(), anyString(), anyString(),
          anyString(), anyString(), anyString(), anyString()), times(1));
    }
  }

  @Test
  public void testDifferentIdentitiesDoNotCollide() {
    try (MockedStatic<LoginUtils> loginMock = mockStatic(LoginUtils.class)) {
      stubLoginUtils(loginMock);

      Map<String, String> sales = NeoSessionVarsCache.getOrLoad(
          "u1", "r", "c", "o", "w", "es_ES");
      Map<String, String> purchase = NeoSessionVarsCache.getOrLoad(
          "u2", "r", "c", "o", "w", "es_ES");

      assertEquals("u1", sales.get("#AD_User_ID"));
      assertEquals("u2", purchase.get("#AD_User_ID"));
      // Cold-load runs once per distinct identity.
      loginMock.verify(() -> LoginUtils.fillSessionArguments(
          any(), any(), anyString(), anyString(), anyString(),
          anyString(), anyString(), anyString(), anyString()), times(2));
    }
  }

  @Test
  public void testSnapshotIsUnmodifiable() {
    try (MockedStatic<LoginUtils> loginMock = mockStatic(LoginUtils.class)) {
      stubLoginUtils(loginMock);

      Map<String, String> snapshot = NeoSessionVarsCache.getOrLoad(
          "u", "r", "c", "o", "w", "es_ES");

      assertThrows(UnsupportedOperationException.class,
          () -> snapshot.put("#AD_User_ID", "tampered"));
    }
  }

  @Test
  public void testClearForcesReloadOnNextCall() {
    try (MockedStatic<LoginUtils> loginMock = mockStatic(LoginUtils.class)) {
      stubLoginUtils(loginMock);

      NeoSessionVarsCache.getOrLoad("u", "r", "c", "o", "w", "es_ES");
      NeoSessionVarsCache.clear();
      NeoSessionVarsCache.getOrLoad("u", "r", "c", "o", "w", "es_ES");

      loginMock.verify(() -> LoginUtils.fillSessionArguments(
          any(), any(), anyString(), anyString(), anyString(),
          anyString(), anyString(), anyString(), anyString()), times(2));
    }
  }

  @Test
  public void testReplayIntoCopiesEveryEntry() {
    try (MockedStatic<LoginUtils> loginMock = mockStatic(LoginUtils.class)) {
      stubLoginUtils(loginMock);

      Map<String, String> snapshot = NeoSessionVarsCache.getOrLoad(
          "u", "r", "c", "o", "w", "es_ES");
      VariablesSecureApp fresh = new VariablesSecureApp("u", "c", "o", "r", "es_ES");
      NeoSessionVarsCache.replayInto(fresh, snapshot);

      assertEquals("u", fresh.getSessionValue("#AD_User_ID"));
      assertEquals("c", fresh.getSessionValue("#AD_Client_ID"));
      assertEquals("o", fresh.getSessionValue("#AD_Org_ID"));
      assertEquals("w", fresh.getSessionValue("#M_Warehouse_ID"));
    }
  }

  /**
   * The cache key is intentionally identity-only — IsSOTrx is applied
   * per-tab on the materialized VSA in {@code NeoCalloutService.buildVars}.
   * This test guards the snapshot itself does not carry any IsSOTrx value
   * that could leak across windows when callers replay it.
   */
  @Test
  public void testSnapshotDoesNotCarrySoTrx() {
    try (MockedStatic<LoginUtils> loginMock = mockStatic(LoginUtils.class)) {
      stubLoginUtils(loginMock);
      // Even if a previous (unrelated) call had stamped IsSOTrx on a VSA,
      // the snapshot loader only captures the IDENTITY_KEYS / FORMAT_NAMES
      // listed in NeoSessionVarsCache.
      Map<String, String> snapshot = NeoSessionVarsCache.getOrLoad(
          "u", "r", "c", "o", "w", "es_ES");

      assertEquals(null, snapshot.get("IsSOTrx"));
      assertEquals(null, snapshot.get("isSOTrx"));
    }
    // Second identity confirms the cache is not contaminated by the first.
    try (MockedStatic<LoginUtils> loginMock = mockStatic(LoginUtils.class)) {
      stubLoginUtils(loginMock);
      Map<String, String> other = NeoSessionVarsCache.getOrLoad(
          "u2", "r", "c", "o", "w", "es_ES");
      assertEquals(null, other.get("IsSOTrx"));
      assertEquals("u2", other.get("#AD_User_ID"));
    }
  }
}
