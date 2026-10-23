/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import com.etendoerp.go.payment.TenantEnvironmentLifecycleService.LifecycleTarget;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Test-only access to the package-private {@link LifecycleTarget} factories, so a test outside
 * {@code com.etendoerp.go.payment} (the billing reconciliation's) can hand its mocked lifecycle
 * service real targets instead of mocks of a value class.
 */
public final class LifecycleTargetFixtures {

  private LifecycleTargetFixtures() {
  }

  /**
   * @return a target on a subscription row
   */
  public static LifecycleTarget row(String clientId, Subscription subscription,
      SubscriptionLifecycleApplier.StoredState storedState) {
    return LifecycleTarget.row(clientId, subscription, storedState);
  }

  /**
   * @return a target on the tenant preferences (no subscription row)
   */
  public static LifecycleTarget preferences(String clientId,
      SubscriptionLifecycleApplier.StoredState storedState) {
    return LifecycleTarget.preferences(clientId, storedState);
  }

  /**
   * @return a target the event must not be applied to
   */
  public static LifecycleTarget ignored(String clientId, String reason) {
    return LifecycleTarget.ignored(clientId, reason);
  }
}
