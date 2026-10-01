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
package com.etendoerp.go.rest;

import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.authentication.hashing.PasswordHash;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.access.UserRoles;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Location;

import com.etendoerp.go.onboarding.OnboardingProgressSink;
import com.etendoerp.go.onboarding.pool.TenantPoolConfig;
import com.etendoerp.go.onboarding.pool.TenantPoolStore;

/**
 * Hands a pre-provisioned tenant to an onboarding request (ETP-5389).
 *
 * <p>{@link #claim} answers {@code null} whenever the classic path must run instead — flag off,
 * a combination the pool does not build, an empty pool, or any failure while taking the tenant.
 * The servlet does not call it at all when the attempt left a half-built client to resume. The
 * company name plays no part: it may match any other environment (ETP-5548). The caller then provisions from scratch exactly as before; the user sees a slower
 * onboarding, never an error the classic path would not have produced.
 *
 * <p>The claim runs inside the onboarding request's own DAL transaction. The pool row is taken with
 * {@code FOR UPDATE SKIP LOCKED} (see {@link TenantPoolStore#claimReady}), so concurrent signups
 * never share a tenant, and it only becomes durable with the onboarding commit: a failure anywhere
 * later rolls the claim back together with the personalization and the tenant is READY again.
 *
 * <p>What the claim applies is deliberately small — only what identifies the tenant as the
 * signup's: client name, organization name/value/SocialName, and the admin user (username,
 * display name, email, password, active). Owner marking, org info (tax id), the warehouse address,
 * the paid upgrade and the data transfer then run through the servlet's existing calls. The names
 * the build derived from the placeholder (admin role, trees, ledger, chart of accounts, calendar)
 * are rewritten to the company name too (ETP-5548) — see {@code docs/onboarding-flow.md},
 * "Tenant pool".
 */
public class PooledTenantClaimService {

  private static final Logger log = LogManager.getLogger(PooledTenantClaimService.class);
  private static final String PROGRESS_CLIENT = OnboardingProvisioningChain.PROGRESS_CLIENT;
  private static final String COLUMN_NAME = "name";
  private static final String COLUMN_DESCRIPTION = "description";

  /**
   * Every name the pool build derived from the {@code POOL-<id>} placeholder outside the client,
   * organization and admin user (which {@link #personalize} sets directly), with the column
   * lengths the rewrite is truncated to. The classic path reuses it for its provisioning name
   * ({@code EtendoGoJwtServlet.applyRequestedClientName}), since both builds run the same chain.
   * {@code CLIENT_NAME} is capped at 40 characters, so the longest derived name
   * ("Arbol de cuentas " + name) fits; the truncation is only a guard.
   */
  static final List<String> PLACEHOLDER_DERIVED_NAME_UPDATES = List.of(
      placeholderRewrite("ad_role", COLUMN_NAME, 60, COLUMN_DESCRIPTION, 255),
      placeholderRewrite("ad_tree", COLUMN_NAME, 255, COLUMN_DESCRIPTION, 255),
      placeholderRewrite("c_acctschema", COLUMN_NAME, 60, null, 0),
      placeholderRewrite("c_element", COLUMN_NAME, 60, COLUMN_DESCRIPTION, 255),
      placeholderRewrite("c_calendar", COLUMN_NAME, 60, null, 0));

  private static String placeholderRewrite(String table, String nameColumn, int nameLength,
      String descriptionColumn, int descriptionLength) {
    StringBuilder sql = new StringBuilder("update ").append(table).append(" set ")
        .append(rewrite(nameColumn, nameLength));
    String match = "strpos(" + nameColumn + ", :placeholder) > 0";
    if (descriptionColumn != null) {
      sql.append(", ").append(rewrite(descriptionColumn, descriptionLength));
      match = "(" + match + " or strpos(" + descriptionColumn + ", :placeholder) > 0)";
    }
    return sql.append(", updated = now() where ad_client_id = :clientId and ").append(match)
        .toString();
  }

  private static String rewrite(String column, int length) {
    return column + " = left(replace(" + column + ", :placeholder, :clientName), " + length + ")";
  }

  TenantPoolStore store = new TenantPoolStore();

  /** The request data a claim needs. */
  public record ClaimRequest(String accountEmail, String clientName, String fullName,
      String currencyIso, String countryCode, String language, String address,
      String adminPassword) {
  }

  /**
   * Claims and personalizes one READY tenant when the request is eligible for pooling.
   *
   * @param sink progress sink used while personalizing the tenant
   * @param request account and tenant data supplied by the signup request
   * @return the claimed tenant's {@code AD_Client_ID}, or {@code null} to run the classic path
   */
  public String claim(OnboardingProgressSink sink, ClaimRequest request) {
    return claim(sink, request, UUID.randomUUID().toString());
  }

  /**
   * Claims and personalizes a tenant while attaching a caller supplied correlation id to the
   * performance log entries.
   *
   * @param sink progress sink used while personalizing the tenant
   * @param request account and tenant data supplied by the signup request
   * @param correlationId identifier shared by the claim and residual onboarding log entries
   * @return the claimed tenant's {@code AD_Client_ID}, or {@code null} to run the classic path
   */
  public String claim(OnboardingProgressSink sink, ClaimRequest request, String correlationId) {
    return claim(sink, request, correlationId, null, null);
  }

  /**
   * Claims only the request's reserved tenant when a guarded local fixture is active; otherwise
   * behaves as {@link #claim(OnboardingProgressSink, ClaimRequest, String)}.
   *
   * @param sink progress sink used while personalizing the tenant
   * @param request account and tenant data supplied by the signup request
   * @param correlationId identifier shared by the claim and residual onboarding log entries
   * @param fixtureClientId tenant reserved by the local fixture, or blank for a regular claim
   * @param fixtureRequestId fixture request that owns that reservation
   * @return the claimed tenant's {@code AD_Client_ID}, or {@code null} to run the classic path
   */
  public String claim(OnboardingProgressSink sink, ClaimRequest request, String correlationId,
      String fixtureClientId, String fixtureRequestId) {
    boolean fixture = StringUtils.isNotBlank(fixtureClientId);
    if (!fixture && !isEligible(request)) {
      return null;
    }
    long claimStartedAt = System.nanoTime();
    TenantPoolStore.Claim claim;
    try {
      long storeStartedAt = System.nanoTime();
      claim = fixture ? store.lockFixture(fixtureClientId, fixtureRequestId)
          : store.claimReady(OnboardingProvisioningChain.provisioningVersion());
      log.info("[ONBOARDING-PERF] phase=pool_claim_store correlationId={} elapsedMs={}",
          correlationId, elapsedMillis(storeStartedAt));
    } catch (RuntimeException e) {
      log.error("Could not read the tenant pool; onboarding falls back to the classic path", e);
      EtendoGoDalHelper.rollbackDalChanges("tenant pool claim", e, log);
      if (fixture) throw new OBException("Could not lock the dedicated E2E fixture tenant", e);
      return null;
    }
    if (claim == null) {
      if (fixture) throw new OBException("Dedicated E2E fixture tenant is unavailable");
      log.info("[ONBOARDING-PERF] phase=pool_claim outcome=empty mode=classic correlationId={} "
          + "elapsedMs={}", correlationId, elapsedMillis(claimStartedAt));
      return null;
    }
    sink.progress(PROGRESS_CLIENT, OnboardingProvisioningChain.PROGRESS_IN_PROGRESS,
        "Preparing your environment: " + request.clientName() + "...");
    try {
      long personalizeStartedAt = System.nanoTime();
      personalize(claim.clientId(), request);
      log.info("[ONBOARDING-PERF] phase=pool_personalization mode=pool correlationId={} "
          + "clientId={} poolRowId={} elapsedMs={}", correlationId, claim.clientId(),
          claim.poolRowId(), elapsedMillis(personalizeStartedAt));
    } catch (RuntimeException e) {
      log.error("Claimed pooled tenant {} (pool row {}) could not be personalized; onboarding "
          + "falls back to the classic path", claim.clientId(), claim.poolRowId(), e);
      EtendoGoDalHelper.rollbackDalChanges("pooled tenant personalization", e, log);
      if (fixture) throw new OBException("Could not personalize the dedicated E2E fixture tenant", e);
      retireBrokenTenantBestEffort(claim, e);
      return null;
    }
    sink.progress(PROGRESS_CLIENT, OnboardingProvisioningChain.PROGRESS_DONE,
        "Client created successfully");
    log.info("Onboarding '{}' claimed pooled tenant {} (pool row {})", request.clientName(),
        claim.clientId(), claim.poolRowId());
    log.info("[ONBOARDING-PERF] phase=pool_claim outcome=claimed mode=pool correlationId={} "
        + "clientId={} poolRowId={} elapsedMs={}", correlationId, claim.clientId(),
        claim.poolRowId(), elapsedMillis(claimStartedAt));
    return claim.clientId();
  }

  private static long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000L;
  }

  /** The cheap checks, all before the pool is touched. */
  boolean isEligible(ClaimRequest request) {
    return isPoolEnabled(request.accountEmail())
        && TenantPoolConfig.supports(request.currencyIso(), request.countryCode(),
            request.language())
        && !TenantPoolConfig.isPlaceholderName(request.clientName());
  }

  boolean isPoolEnabled(String accountEmail) {
    return TenantPoolConfig.isEnabled(accountEmail);
  }

  /**
   * Makes the pooled tenant the signup's. Mirrors what the classic path writes: the client and org
   * values {@code InitialClientSetup}/{@code InitialOrgSetup} derive from the company name, the
   * {@code SocialName} {@code applySocialName} sets (ETP-4749), and the admin user fields
   * {@code InitialSetupUtility#insertUser}, {@code applyClientAdminDisplayName} and
   * {@code applyClientAdminEmail} (ETP-5019) set.
   */
  void personalize(String clientId, ClaimRequest request) {
    long personalizeStartedAt = System.nanoTime();
    String clientName = request.clientName();
    long lookupClientStartedAt = System.nanoTime();
    Client client = OBDal.getInstance().get(Client.class, clientId);
    long lookupClientElapsed = elapsedMillis(lookupClientStartedAt);
    long lookupOrgStartedAt = System.nanoTime();
    Organization org = EtendoGoJwtDalHelper.findFirstOrganization(clientId);
    long lookupOrgElapsed = elapsedMillis(lookupOrgStartedAt);
    long lookupAdminStartedAt = System.nanoTime();
    UserRoles adminRole = EtendoGoJwtDalHelper.findClientAdminUserRole(clientId);
    long lookupAdminElapsed = elapsedMillis(lookupAdminStartedAt);
    if (client == null || org == null || adminRole == null) {
      throw new OBException("Pooled tenant " + clientId + " is incomplete");
    }
    String placeholder = client.getName();
    client.setName(clientName);
    client.setSearchKey(clientName);
    client.setDescription(clientName);
    OBDal.getInstance().save(client);

    org.setName(clientName);
    org.setSearchKey(clientName);
    org.setSocialName(clientName);
    OBDal.getInstance().save(org);

    String username = EtendoGoJwtSupport.buildClientUsername(request.accountEmail(), clientName);
    User admin = adminRole.getUserContact();
    admin.setUsername(username);
    admin.setName(StringUtils.isNotBlank(request.fullName()) ? request.fullName() : username);
    admin.setDescription(username);
    admin.setEmail(request.accountEmail());
    admin.setPassword(PasswordHash.generateHash(request.adminPassword()));
    admin.setActive(true);
    OBDal.getInstance().save(admin);

    applySignupAddress(org, request.address());
    long beforeFlushElapsed = elapsedMillis(personalizeStartedAt);
    long flushStartedAt = System.nanoTime();
    OBDal.getInstance().flush();
    log.info("[ONBOARDING-PERF] phase=pool_personalization_detail clientId={} lookupClientMs={} "
        + "lookupOrgMs={} lookupAdminMs={} beforeFlushMs={} flushMs={}", clientId,
        lookupClientElapsed, lookupOrgElapsed, lookupAdminElapsed, beforeFlushElapsed,
        elapsedMillis(flushStartedAt));
    if (TenantPoolConfig.isPlaceholderName(placeholder)) {
      renamePlaceholderDerivedNames(clientId, placeholder, clientName);
    }
  }

  /**
   * Rewrites the placeholder in the names {@link #PLACEHOLDER_DERIVED_NAME_UPDATES} lists, so the
   * claimed tenant shows no {@code POOL-…} in its role, ledger, chart of accounts, calendar or trees.
   * Native SQL on purpose: these rows are not loaded, and the 17 trees alone would cost a DAL round
   * trip each. It runs in the claim's transaction, so a later failure rolls it back with the claim.
   *
   * @return rows rewritten
   */
  int renamePlaceholderDerivedNames(String clientId, String placeholder, String clientName) {
    int rewritten = 0;
    for (String sql : PLACEHOLDER_DERIVED_NAME_UPDATES) {
      rewritten += OBDal.getInstance().getSession().createNativeQuery(sql)
          .setParameter("placeholder", placeholder)
          .setParameter("clientName", clientName)
          .setParameter("clientId", clientId)
          .executeUpdate();
    }
    return rewritten;
  }

  /**
   * The pooled tenant's fiscal location was created with no street line (the pool has no address
   * to give it), and {@code OnboardingOrgInfoService} never touches a location that already
   * exists — so the signup's address is written here. The warehouse copy follows from the
   * servlet's {@code wireWarehouseAddress}, which always re-copies the fiscal location.
   */
  void applySignupAddress(Organization org, String address) {
    if (StringUtils.isBlank(address)) {
      return;
    }
    OrganizationInformation orgInfo =
        OBDal.getInstance().get(OrganizationInformation.class, org.getId());
    Location location = orgInfo != null ? orgInfo.getLocationAddress() : null;
    if (location != null && StringUtils.isBlank(location.getAddressLine1())) {
      location.setAddressLine1(address.trim());
      OBDal.getInstance().save(location);
    }
  }

  /**
   * A tenant that failed personalization would fail the next signup too, so it is taken out of
   * rotation in its own transaction. Best-effort: at worst it stays READY and fails again.
   */
  private void retireBrokenTenantBestEffort(TenantPoolStore.Claim claim, RuntimeException cause) {
    try {
      store.markFailed(claim.poolRowId(), claim.clientId(),
          "Personalization failed: " + cause.getMessage());
      store.commit();
    } catch (RuntimeException e) {
      log.warn("Could not mark pool row {} as failed", claim.poolRowId(), e);
    }
  }
}
