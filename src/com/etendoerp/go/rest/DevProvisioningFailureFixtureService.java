/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * *************************************************************************
 */
package com.etendoerp.go.rest;

import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.common.GoRuntimeProperties;
import com.etendoerp.go.onboarding.pool.TenantPoolConfig;
import com.etendoerp.go.onboarding.pool.TenantPoolStore;
import com.etendoerp.go.payment.CheckoutRequestStore;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/** Local-only fixture seam for the real pooled provisioning failure E2E. */
public final class DevProvisioningFailureFixtureService {
  public static final String ENVIRONMENT_PROPERTY = "etendo.go.runtime.environment";
  public static final String ENVIRONMENT_ENV = "ETGO_RUNTIME_ENVIRONMENT";

  private final CheckoutRequestStore checkoutRequests;
  private final TenantPoolStore pool;

  /** Uses the real checkout request store and tenant pool. */
  public DevProvisioningFailureFixtureService() {
    this(new CheckoutRequestStore(), new TenantPoolStore());
  }

  DevProvisioningFailureFixtureService(CheckoutRequestStore checkoutRequests,
      TenantPoolStore pool) {
    this.checkoutRequests = checkoutRequests;
    this.pool = pool;
  }

  /**
   * Tells whether the fixture exists in this runtime; it only does in a local one.
   *
   * @return whether this runtime is local
   */
  public static boolean isEnabled() {
    return "local".equalsIgnoreCase(GoRuntimeProperties.readValue(
        ENVIRONMENT_PROPERTY, ENVIRONMENT_ENV, ""));
  }

  /**
   * Creates a paid request and arms the real pooled finish step to fail once.
   *
   * @param account account that owns the fixture request
   * @param clientName company name of the request; a generated one when blank
   * @return the request id, company name, cleanup token and {@code retryAllowed}
   * @throws JSONException when the response cannot be built
   */
  public JSONObject create(Account account, String clientName) throws JSONException {
    requireEnabled();
    String name = StringUtils.defaultIfBlank(clientName, "E2E Pool Failure " + UUID.randomUUID());
    String requestId = "e2e-fixture-" + UUID.randomUUID();
    TenantPoolStore.Claim claim = reserveFixture(requestId);
    try {
      checkoutRequests.recordRequested(requestId, account.getId(), account.getEmail(), name);
      if (!checkoutRequests.recordPaid(requestId, null, null)) {
        throw new IllegalStateException("Could not mark local fixture checkout as paid");
      }
    } catch (RuntimeException e) {
      try {
        pool.restoreReady(claim.poolRowId(), claim.clientId(), requestId);
        checkoutRequests.deleteFixture(requestId, account.getId(), account.getEmail());
      } catch (RuntimeException cleanupFailure) {
        pool.rollback();
        e.addSuppressed(cleanupFailure);
      }
      throw e;
    }
    JSONObject result = new JSONObject();
    result.put("requestId", requestId);
    result.put("clientName", name);
    result.put("cleanupToken", requestId);
    result.put("retryAllowed", true);
    return result;
  }

  /**
   * Restores the reserved pool row; cleanup is idempotent and account-scoped.
   *
   * @param account account that owns the fixture request
   * @param cleanupToken request id returned by {@link #create}
   * @return {@code true} once the reservation is restored or was already gone
   */
  public boolean cleanup(Account account, String cleanupToken) {
    requireEnabled();
    if (!isFixtureRequest(cleanupToken)) {
      throw new SecurityException("Invalid fixture cleanup token");
    }
    CheckoutRequest checkout = checkoutRequests.find(cleanupToken, account.getId(),
        account.getEmail());
    if (checkout == null) return true;
    TenantPoolStore.Claim reservation = pool.findFixtureReservation(cleanupToken);
    if (reservation == null) {
      throw new IllegalStateException("Fixture reservation missing before cleanup");
    }
    try {
      if (!pool.restoreReady(reservation.poolRowId(), reservation.clientId(), cleanupToken)) {
        throw new IllegalStateException("Fixture reservation changed before cleanup");
      }
      // The checkout store commits the same DAL transaction, including the pool restore.
      checkoutRequests.deleteFixture(cleanupToken, account.getId(), account.getEmail());
    } catch (RuntimeException e) {
      pool.rollback();
      throw e;
    }
    return true;
  }

  /**
   * Tells whether the pooled finish step must fail for this onboarding: only while the fixture's
   * reservation exists.
   *
   * @param requestId checkout request id of the onboarding
   * @return whether the pooled finish step must fail for this request
   */
  public boolean shouldFail(String requestId) {
    return isFixtureRequest(requestId) && pool.findFixtureReservation(requestId) != null;
  }

  /**
   * Tells whether a checkout request id belongs to the local failure fixture.
   *
   * @param requestId checkout request id
   * @return whether the id names a fixture request in a local runtime
   */
  public static boolean isFixtureRequest(String requestId) {
    return isEnabled() && StringUtils.startsWith(requestId, "e2e-fixture-");
  }

  /**
   * Returns the reserved client only for the authenticated owner of this fixture.
   *
   * @param requestId fixture request id
   * @param accountId authenticated account id
   * @param accountEmail authenticated account email
   * @return the reserved client id, or {@code null} for any other request or account
   */
  public String reservedClientId(String requestId, String accountId, String accountEmail) {
    if (!isFixtureRequest(requestId)
        || checkoutRequests.find(requestId, accountId, accountEmail) == null) return null;
    TenantPoolStore.Claim claim = pool.findFixtureReservation(requestId);
    return claim == null ? null : claim.clientId();
  }

  private TenantPoolStore.Claim reserveFixture(String requestId) {
    String version = OnboardingProvisioningChain.provisioningVersion();
    TenantPoolStore.Claim claim = pool.claimFixture(requestId, version);
    if (claim != null) {
      pool.commit();
      return claim;
    }
    String poolRowId = pool.insertProvisioning(version);
    pool.commit();
    var outcome = new TenantPoolProvisioner().provision(
        TenantPoolConfig.PLACEHOLDER_PREFIX + poolRowId);
    if (!outcome.success()) {
      pool.markFailed(poolRowId, outcome.clientId(), outcome.error());
      pool.commit();
      throw new IllegalStateException("Could not provision a dedicated fixture tenant: "
          + outcome.error());
    }
    pool.markFixtureReady(poolRowId, outcome.clientId());
    claim = pool.claimFixture(requestId, version);
    if (claim == null || !poolRowId.equals(claim.poolRowId())) {
      pool.rollback();
      throw new IllegalStateException("Could not reserve the newly provisioned fixture tenant");
    }
    pool.retireOutdatedFixtures(version);
    pool.commit();
    return claim;
  }

  private static void requireEnabled() {
    if (!isEnabled()) throw new SecurityException("Provisioning fixture is disabled");
  }

}
