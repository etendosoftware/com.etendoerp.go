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
 * a combination the pool does not build, a company name that already resolves to a client (resume
 * and name-collision handling stay where they are), an empty pool, or any failure while taking the
 * tenant. The caller then provisions from scratch exactly as before; the user sees a slower
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
 * the paid upgrade and the data transfer then run through the servlet's existing calls. Names
 * derived from the placeholder at provisioning time (role, trees, ledger, calendar) are NOT
 * renamed — see {@code docs/onboarding-flow.md}, "Tenant pool".
 */
public class PooledTenantClaimService {

  private static final Logger log = LogManager.getLogger(PooledTenantClaimService.class);
  private static final String PROGRESS_CLIENT = OnboardingProvisioningChain.PROGRESS_CLIENT;

  TenantPoolStore store = new TenantPoolStore();

  /** The request data a claim needs. */
  public record ClaimRequest(String accountEmail, String clientName, String fullName,
      String currencyIso, String countryCode, String language, String address,
      String adminPassword) {
  }

  /**
   * Claims and personalizes one READY tenant when the request is eligible for pooling.
   *
   * @return the claimed tenant's {@code AD_Client_ID}, or {@code null} to run the classic path
   */
  public String claim(OnboardingProgressSink sink, ClaimRequest request) {
    if (!isEligible(request)) {
      return null;
    }
    TenantPoolStore.Claim claim;
    try {
      claim = store.claimReady(OnboardingProvisioningChain.provisioningVersion());
    } catch (RuntimeException e) {
      log.error("Could not read the tenant pool; onboarding falls back to the classic path", e);
      EtendoGoDalHelper.rollbackDalChanges("tenant pool claim", e, log);
      return null;
    }
    if (claim == null) {
      log.info("Tenant pool is empty; onboarding '{}' takes the classic path",
          request.clientName());
      return null;
    }
    sink.progress(PROGRESS_CLIENT, OnboardingProvisioningChain.PROGRESS_IN_PROGRESS,
        "Preparing your environment: " + request.clientName() + "...");
    try {
      personalize(claim.clientId(), request);
    } catch (RuntimeException e) {
      log.error("Claimed pooled tenant {} (pool row {}) could not be personalized; onboarding "
          + "falls back to the classic path", claim.clientId(), claim.poolRowId(), e);
      EtendoGoDalHelper.rollbackDalChanges("pooled tenant personalization", e, log);
      retireBrokenTenantBestEffort(claim, e);
      return null;
    }
    sink.progress(PROGRESS_CLIENT, OnboardingProvisioningChain.PROGRESS_DONE,
        "Client created successfully");
    log.info("Onboarding '{}' claimed pooled tenant {} (pool row {})", request.clientName(),
        claim.clientId(), claim.poolRowId());
    return claim.clientId();
  }

  /** The cheap checks, all before the pool is touched. */
  boolean isEligible(ClaimRequest request) {
    return isPoolEnabled(request.accountEmail())
        && TenantPoolConfig.supports(request.currencyIso(), request.countryCode(),
            request.language())
        && !TenantPoolConfig.isPlaceholderName(request.clientName())
        && findClientIdByName(request.clientName()) == null;
  }

  boolean isPoolEnabled(String accountEmail) {
    return TenantPoolConfig.isEnabled(accountEmail);
  }

  String findClientIdByName(String clientName) {
    return EtendoGoJwtSupport.findClientIdByName(clientName);
  }

  /**
   * Makes the pooled tenant the signup's. Mirrors what the classic path writes: the client and org
   * values {@code InitialClientSetup}/{@code InitialOrgSetup} derive from the company name, the
   * {@code SocialName} {@code applySocialName} sets (ETP-4749), and the admin user fields
   * {@code InitialSetupUtility#insertUser}, {@code applyClientAdminDisplayName} and
   * {@code applyClientAdminEmail} (ETP-5019) set.
   */
  void personalize(String clientId, ClaimRequest request) {
    String clientName = request.clientName();
    Client client = OBDal.getInstance().get(Client.class, clientId);
    Organization org = EtendoGoJwtDalHelper.findFirstOrganization(clientId);
    UserRoles adminRole = EtendoGoJwtDalHelper.findClientAdminUserRole(clientId);
    if (client == null || org == null || adminRole == null) {
      throw new OBException("Pooled tenant " + clientId + " is incomplete");
    }
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
    OBDal.getInstance().flush();
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
