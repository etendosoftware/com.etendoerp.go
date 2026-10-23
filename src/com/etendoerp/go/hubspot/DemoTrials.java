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

package com.etendoerp.go.hubspot;

import java.time.Instant;
import java.util.Optional;

import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;

/**
 * The trial of a demo environment, as the access policy sees it: the same answer the paywall and
 * the trial notices give, so HubSpot never calls a trial lost while the app still lets the owner in.
 */
class DemoTrials {

  private final TenantEnvironmentLifecycleService lifecycleService;
  private final EnvironmentAccessPolicy policy;

  DemoTrials() {
    this(new TenantEnvironmentLifecycleService(), new EnvironmentAccessPolicy());
  }

  DemoTrials(TenantEnvironmentLifecycleService lifecycleService, EnvironmentAccessPolicy policy) {
    this.lifecycleService = lifecycleService;
    this.policy = policy;
  }

  /**
   * @param clientId the demo's {@code AD_Client_ID}
   * @param now      the evaluation instant
   * @return its trial, empty when the environment is not a demo with a trial (any more)
   */
  Optional<Trial> find(String clientId, Instant now) {
    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot = lifecycleService
        .resolve(clientId);
    if (snapshot == null
        || snapshot.getType() != EnvironmentAccessPolicy.EnvironmentType.DEMO
        || snapshot.isAssociatedWithProductive()
        || snapshot.getTrialStartedAt() == null) {
      return Optional.empty();
    }
    EnvironmentAccessPolicy.Configuration configuration = lifecycleService.configuration();
    EnvironmentAccessPolicy.Environment environment = snapshot.toPolicyEnvironment();
    boolean expired = policy.evaluate(environment, true, snapshot.getSubscriptionStatus(), now,
        configuration) == EnvironmentAccessPolicy.Decision.DEMO_TRIAL_EXPIRED;
    return Optional.of(new Trial(snapshot.getTrialStartedAt(),
        policy.trialExpiresAt(environment, configuration), expired));
  }

  /**
   * @param startedAt when the trial started
   * @param endsAt    when it ends, or ended
   * @param expired   whether it is over with nothing else granting access
   */
  record Trial(Instant startedAt, Instant endsAt, boolean expired) {
  }
}
