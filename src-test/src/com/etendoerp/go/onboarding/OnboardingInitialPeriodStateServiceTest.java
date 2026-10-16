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
package com.etendoerp.go.onboarding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;

/**
 * ETP-5577 — which initial period state a new tenant gets after the onboarding commit, and the
 * pending / review-required markers that drive it.
 *
 * @covers com.etendoerp.go.onboarding.OnboardingInitialPeriodStateService
 */
class OnboardingInitialPeriodStateServiceTest {

  private static final String PENDING = OnboardingInitialPeriodStateService.PENDING_ATTRIBUTE;
  private static final String REVIEW =
      OnboardingInitialPeriodStateService.REVIEW_REQUIRED_ATTRIBUTE;
  private static final Instant STARTED = Instant.parse("2026-09-30T10:00:00Z");

  private final Map<String, Preference> preferences = new HashMap<>();
  private OnboardingPeriodControlService periods;
  private TenantEnvironmentLifecycleService lifecycle;
  private OnboardingInitialPeriodStateService service;
  private OBDal dal;
  private MockedStatic<OBDal> obDal;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<Preferences> prefs;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    periods = mock(OnboardingPeriodControlService.class);
    lifecycle = mock(TenantEnvironmentLifecycleService.class);
    when(lifecycle.configuration()).thenReturn(new EnvironmentAccessPolicy.Configuration(15, 7));
    service = new OnboardingInitialPeriodStateService(periods, lifecycle);
    dal = mock(OBDal.class);
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    obContext = mockStatic(OBContext.class);
    prefs = mockStatic(Preferences.class);
    when(dal.createQuery(eq(Preference.class), anyString())).thenAnswer(inv -> {
      OBQuery<Preference> query = mock(OBQuery.class);
      String[] attribute = new String[1];
      when(query.setNamedParameter(anyString(), any())).thenAnswer(set -> {
        if ("attribute".equals(set.getArgument(0))) {
          attribute[0] = set.getArgument(1);
        }
        return query;
      });
      when(query.uniqueResult()).thenAnswer(u -> preferences.get(attribute[0]));
      when(query.list()).thenAnswer(l -> preferences.containsKey(attribute[0])
          ? List.of(preferences.get(attribute[0])) : List.of());
      return query;
    });
  }

  @AfterEach
  void tearDown() {
    prefs.close();
    obContext.close();
    obDal.close();
  }

  @Test
  void marksPendingWithTheRunContextAndReviewOnlyWhenRequired() {
    Client client = mock(Client.class);
    when(dal.get(Client.class, "CLIENT")).thenReturn(client);

    service.markPending("CLIENT", "ORG", "USER", "ROLE", false);
    prefs.verify(() -> Preferences.setPreferenceValue(PENDING, "ORG USER ROLE", false, client,
        null, null, null, null, null));
    prefs.verify(() -> Preferences.setPreferenceValue(eq(REVIEW), anyString(), anyBoolean(),
        any(Client.class), isNull(), isNull(), isNull(), isNull(), isNull()), never());

    service.markPending("CLIENT", "ORG", "USER", "ROLE", true);
    prefs.verify(() -> Preferences.setPreferenceValue(REVIEW, "Y", false, client, null, null,
        null, null, null));
  }

  @Test
  void nothingPendingDoesNothing() {
    assertFalse(service.applyIfPending("CLIENT"));
    verify(periods, never()).applyProductiveInitialState(anyString(), anyString(), anyString(),
        anyString());
    verify(periods, never()).openDemoTrialWindow(anyString(), anyString(), anyString(),
        anyString(), any(Instant.class), anyInt());
  }

  @Test
  void demoOpensItsTrialWindowAndClearsThePendingMarker() {
    Preference pending = preference(PENDING, "ORG USER ROLE");
    snapshot(EnvironmentAccessPolicy.EnvironmentType.DEMO, STARTED);

    assertTrue(service.applyIfPending("CLIENT"));

    verify(periods).openDemoTrialWindow("CLIENT", "ORG", "USER", "ROLE", STARTED, 15);
    verify(dal).remove(pending);
  }

  @Test
  void productiveWithReviewOpensNothing() {
    preference(PENDING, "ORG USER ROLE");
    preference(REVIEW, "Y");
    snapshot(EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE, null);

    assertTrue(service.applyIfPending("CLIENT"));

    verify(periods).applyProductiveInitialState("CLIENT", "ORG", "USER", "ROLE");
    assertTrue(service.isReviewRequired("CLIENT"));
  }

  @Test
  void unknownEnvironmentTypeKeepsThePendingMarkerForTheNextLogin() {
    Preference pending = preference(PENDING, "ORG USER ROLE");

    assertThrows(IllegalStateException.class, () -> service.applyIfPending("CLIENT"));
    verify(dal, never()).remove(pending);
  }

  @Test
  void aMalformedMarkerFails() {
    preference(PENDING, "ORG-ONLY");
    assertThrows(IllegalStateException.class, () -> service.applyIfPending("CLIENT"));
  }

  private Preference preference(String attribute, String value) {
    Preference preference = mock(Preference.class);
    when(preference.getSearchKey()).thenReturn(value);
    preferences.put(attribute, preference);
    return preference;
  }

  private void snapshot(EnvironmentAccessPolicy.EnvironmentType type, Instant trialStart) {
    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        mock(TenantEnvironmentLifecycleService.EnvironmentSnapshot.class);
    when(snapshot.getType()).thenReturn(type);
    when(snapshot.getTrialStartedAt()).thenReturn(trialStart);
    when(lifecycle.resolve("CLIENT")).thenReturn(snapshot);
  }
}
