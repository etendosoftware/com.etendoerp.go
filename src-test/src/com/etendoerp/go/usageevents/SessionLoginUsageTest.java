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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.usageevents;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;

/**
 * {@link SessionLoginUsage}: the {@code session.login} row carries the ids it is given (never the
 * system context), and recording can never fail the login that called it.
 */
class SessionLoginUsageTest {

  private static final String CLIENT = "A1B2C3D4E5F60718293A4B5C6D7E8F90";
  private static final String ORG = "0A1B2C3D4E5F60718293A4B5C6D7E8F9";
  private static final String USER = "F0E1D2C3B4A5968778695A4B3C2D1E0F";
  private static final String ROLE = "11112222333344445555666677778888";

  private MockedStatic<OBPropertiesProvider> propertiesStatic;
  private UsageEventWriter writer;

  @BeforeEach
  void isolate() {
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(new Properties());
    propertiesStatic = mockStatic(OBPropertiesProvider.class);
    propertiesStatic.when(OBPropertiesProvider::getInstance).thenReturn(provider);
    writer = mock(UsageEventWriter.class);
    UsageEventRecorder.setWriter(writer);
  }

  @AfterEach
  void restore() {
    UsageEventRecorder.setWriter(null);
    propertiesStatic.close();
  }

  @Test
  void theEventCarriesTheEnvironmentEnteredAndOnlyShape() {
    UsageEvent e = SessionLoginUsage.event(SessionLoginUsage.ACTION_COOKIE_LOGIN, CLIENT, ORG,
        USER, ROLE, "sso", 42L);

    assertAll(
        () -> assertEquals(UsageEventTypes.SESSION_LOGIN, e.eventType()),
        () -> assertTrue(UsageEventTypes.isKnown(e.eventType())),
        () -> assertEquals(UsageEvent.SOURCE_BACKEND, e.source()),
        () -> assertEquals(CLIENT, e.clientId()),
        () -> assertEquals(ORG, e.orgId()),
        () -> assertEquals(USER, e.userId()),
        () -> assertEquals(ROLE, e.roleId()),
        () -> assertEquals("environment", e.target()),
        () -> assertEquals("cookie-login", e.action()),
        () -> assertEquals(UsageEvent.OUTCOME_OK, e.outcome()),
        () -> assertEquals(42L, e.durationMs()),
        () -> assertNull(e.sessionKey()),
        () -> assertNull(e.errorCode()),
        () -> assertEquals("{\"authMethod\":\"sso\"}", e.properties()));
  }

  @ParameterizedTest
  @ValueSource(strings = { "password", "sso" })
  void aKnownAuthMethodIsKept(String method) {
    UsageEvent e = SessionLoginUsage.event(SessionLoginUsage.ACTION_LOGIN, CLIENT, ORG, USER,
        ROLE, method, 0L);
    assertEquals("{\"authMethod\":\"" + method + "\"}", e.properties());
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "saml", "user@example.com" })
  void anyOtherAuthMethodIsLeftOut(String method) {
    UsageEvent e = SessionLoginUsage.event(SessionLoginUsage.ACTION_LOGIN, CLIENT, ORG, USER,
        ROLE, method, 0L);
    assertNull(e.properties());
  }

  @Test
  void aMissingAuthMethodGivesNoProperties() {
    assertNull(SessionLoginUsage.event(SessionLoginUsage.ACTION_LOGIN, CLIENT, ORG, USER, ROLE,
        null, 0L).properties());
  }

  @Test
  void recordHandsTheEventToTheWriterWithAMeasuredDuration() {
    SessionLoginUsage.record(SessionLoginUsage.ACTION_LOGIN, CLIENT, ORG, USER, ROLE, null,
        System.nanoTime() - 5_000_000L);

    ArgumentCaptor<UsageEvent> offered = ArgumentCaptor.forClass(UsageEvent.class);
    verify(writer).offer(offered.capture());
    UsageEvent e = offered.getValue();
    assertEquals("login", e.action());
    assertEquals(CLIENT, e.clientId());
    assertTrue(e.durationMs() >= 5L, "duration measured from startNanos: " + e.durationMs());
  }

  @Test
  void aStartInTheFutureNeverGivesANegativeDuration() {
    SessionLoginUsage.record(SessionLoginUsage.ACTION_LOGIN, CLIENT, ORG, USER, ROLE, null,
        System.nanoTime() + 60_000_000_000L);

    ArgumentCaptor<UsageEvent> offered = ArgumentCaptor.forClass(UsageEvent.class);
    verify(writer).offer(offered.capture());
    assertEquals(0L, offered.getValue().durationMs());
  }

  @Test
  void aFailingWriterNeverReachesTheLogin() {
    doThrow(new IllegalStateException("writer down")).when(writer).offer(any());
    assertDoesNotThrow(() -> SessionLoginUsage.record(SessionLoginUsage.ACTION_COOKIE_LOGIN,
        CLIENT, ORG, USER, ROLE, "password", System.nanoTime()));
  }

  @Test
  void nullIdsAreAcceptedAndLeftToTheWriterDefaults() {
    assertDoesNotThrow(() -> SessionLoginUsage.record(SessionLoginUsage.ACTION_LOGIN, null, null,
        null, null, null, System.nanoTime()));
    verify(writer).offer(any());
  }
}
