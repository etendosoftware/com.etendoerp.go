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
package com.etendoerp.go.hubspot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.EnvironmentType;
import com.etendoerp.go.payment.EnvironmentAccessPolicy.SubscriptionStatus;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService.EnvironmentSnapshot;

/**
 * A demo's trial as HubSpot is told about it: the same answer the real access policy gives the
 * paywall, so a deal is never lost while the owner can still get in.
 *
 * @covers com.etendoerp.go.hubspot.DemoTrials
 */
class DemoTrialsTest {

  private static final Instant STARTED = Instant.parse("2026-10-01T10:00:00Z");
  private static final int TRIAL_DAYS = 14;

  private final TenantEnvironmentLifecycleService lifecycle =
      mock(TenantEnvironmentLifecycleService.class);
  private final DemoTrials trials = new DemoTrials(lifecycle, new EnvironmentAccessPolicy());

  private EnvironmentSnapshot snapshot(EnvironmentType type, Instant startedAt,
      SubscriptionStatus status, boolean associated) {
    EnvironmentSnapshot snapshot = mock(EnvironmentSnapshot.class);
    when(snapshot.getType()).thenReturn(type);
    when(snapshot.getTrialStartedAt()).thenReturn(startedAt);
    when(snapshot.getSubscriptionStatus()).thenReturn(status);
    when(snapshot.isAssociatedWithProductive()).thenReturn(associated);
    // Only a demo with a start converts; built before stubbing, as building one can throw.
    if (type == EnvironmentType.DEMO && startedAt != null) {
      EnvironmentAccessPolicy.Environment environment = associated
          ? EnvironmentAccessPolicy.Environment.associatedDemo(startedAt, null)
          : EnvironmentAccessPolicy.Environment.demo(startedAt);
      when(snapshot.toPolicyEnvironment()).thenReturn(environment);
    }
    when(lifecycle.resolve("C1")).thenReturn(snapshot);
    return snapshot;
  }

  private void configureTrialDays() {
    when(lifecycle.configuration())
        .thenReturn(new EnvironmentAccessPolicy.Configuration(TRIAL_DAYS, 3));
  }

  @Test
  void aRunningTrialIsNotExpired() {
    snapshot(EnvironmentType.DEMO, STARTED, SubscriptionStatus.NONE, false);
    configureTrialDays();

    Optional<DemoTrials.Trial> trial = trials.find("C1", STARTED.plus(Duration.ofDays(3)));

    assertTrue(trial.isPresent());
    assertFalse(trial.get().expired());
    assertEquals(STARTED, trial.get().startedAt());
    assertEquals(STARTED.plus(Duration.ofDays(TRIAL_DAYS)), trial.get().endsAt());
  }

  @Test
  void aTrialPastItsEndIsExpired() {
    snapshot(EnvironmentType.DEMO, STARTED, SubscriptionStatus.NONE, false);
    configureTrialDays();

    assertTrue(trials.find("C1", STARTED.plus(Duration.ofDays(TRIAL_DAYS + 1))).get().expired());
  }

  @Test
  void aCurrentSubscriptionKeepsTheTrialAlive() {
    snapshot(EnvironmentType.DEMO, STARTED, SubscriptionStatus.CURRENT, false);
    configureTrialDays();

    assertFalse(trials.find("C1", STARTED.plus(Duration.ofDays(60))).get().expired());
  }

  @Test
  void aProductiveEnvironmentHasNoTrial() {
    snapshot(EnvironmentType.PRODUCTIVE, null, SubscriptionStatus.CURRENT, false);

    assertFalse(trials.find("C1", STARTED).isPresent());
  }

  @Test
  void aDemoAlreadyReplacedByAProductiveIsNotATrialAnyMore() {
    snapshot(EnvironmentType.DEMO, STARTED, SubscriptionStatus.NONE, true);

    assertFalse(trials.find("C1", STARTED.plus(Duration.ofDays(60))).isPresent());
  }

  @Test
  void aDemoWithoutAStartIsNotATrial() {
    snapshot(EnvironmentType.DEMO, null, SubscriptionStatus.NONE, false);

    assertFalse(trials.find("C1", STARTED).isPresent());
  }

  @Test
  void anUnknownEnvironmentIsNotATrial() {
    when(lifecycle.resolve("C1")).thenReturn(null);

    assertFalse(trials.find("C1", STARTED).isPresent());
  }
}
