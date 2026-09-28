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

import java.util.Locale;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.onboarding.LoggingOnboardingProgressSink;
import com.etendoerp.go.onboarding.pool.TenantPoolConfig;
import com.etendoerp.go.onboarding.pool.TenantPoolFiller;

/**
 * Builds one pooled tenant (ETP-5389) by running the same {@link OnboardingProvisioningChain} the
 * onboarding endpoint runs — client, organization and the full reconcile chain — for the only
 * supported combination (EUR / ES / es_ES), under a placeholder name.
 *
 * <p>What makes a pooled tenant different from a classic one is only what the request would have
 * supplied: the name is {@code POOL-<pool row id>}, the admin username is that name lowercased, the
 * admin password is random and never stored, and there is no address, tax id or full name. The
 * admin user is left <b>inactive</b> so the tenant is not reachable until it is claimed; the claim
 * ({@link PooledTenantClaimService}) activates it.
 */
public class TenantPoolProvisioner implements TenantPoolFiller.Provisioner {

  private static final Logger log = LogManager.getLogger(TenantPoolProvisioner.class);
  private static final String SYSTEM = "0";

  private final OnboardingProvisioningChain chain;

  /** Creates a provisioner using the standard onboarding chain. */
  public TenantPoolProvisioner() {
    this(new OnboardingProvisioningChain());
  }

  TenantPoolProvisioner(OnboardingProvisioningChain chain) {
    this.chain = chain;
  }

  @Override
  public TenantPoolFiller.ProvisionOutcome provision(String placeholderName) {
    LoggingOnboardingProgressSink sink = new LoggingOnboardingProgressSink(placeholderName);
    String clientId = null;
    OBContext.setOBContext(SYSTEM, SYSTEM, SYSTEM, SYSTEM);
    OBContext.setAdminMode(true);
    try {
      VariablesSecureApp vars = new VariablesSecureApp(SYSTEM, SYSTEM, SYSTEM, SYSTEM,
          TenantPoolConfig.SUPPORTED_LANGUAGE);
      Currency currency =
          EtendoGoJwtDalHelper.findCurrencyByIsoCode(TenantPoolConfig.SUPPORTED_CURRENCY);
      if (currency == null) {
        return failed(null, "Currency " + TenantPoolConfig.SUPPORTED_CURRENCY + " not found");
      }
      clientId = chain.createClient(sink, vars, currency.getId(), placeholderName,
          placeholderName.toLowerCase(Locale.ROOT), randomPassword());
      if (clientId == null) {
        return failed(null, sink.getLastError());
      }
      OnboardingProvisioningChain.AdminContext admin = chain.resolveAdminContext(sink, clientId);
      if (admin == null
          || !chain.createOrganization(sink, placeholderName, clientId, admin.starOrgId,
              currency.getId())) {
        return failed(clientId, sink.getLastError());
      }
      String orgId = chain.resolveOrganizationId(clientId);
      if (orgId == null) {
        return failed(clientId, "Organization not found after creation");
      }
      if (!chain.ensureOnboardingDataset(sink, clientId, orgId, admin.adminUserId,
          admin.adminRoleId, new OnboardingProvisioningChain.OrgInfoInput(
              TenantPoolConfig.SUPPORTED_COUNTRY, "", ""))) {
        return failed(clientId, sink.getLastError());
      }
      deactivateAdmin(admin.adminUserId);
      EtendoGoDalHelper.commitDalChanges("tenant pool provisioning", log);
      return TenantPoolFiller.ProvisionOutcome.ok(clientId);
    } catch (Exception e) {
      log.error("Tenant pool provisioning failed for {}", placeholderName, e);
      EtendoGoDalHelper.rollbackDalChanges("tenant pool provisioning", e, log);
      return failed(clientId, StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getName()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** The pooled tenant must not be reachable before a signup claims it. */
  void deactivateAdmin(String adminUserId) {
    User admin = OBDal.getInstance().get(User.class, adminUserId);
    if (admin != null) {
      admin.setActive(false);
      OBDal.getInstance().save(admin);
      OBDal.getInstance().flush();
    }
  }

  private static TenantPoolFiller.ProvisionOutcome failed(String clientId, String error) {
    return TenantPoolFiller.ProvisionOutcome.failed(clientId,
        StringUtils.defaultIfBlank(error, "Provisioning did not complete"));
  }

  private static String randomPassword() {
    return UUID.randomUUID().toString();
  }
}
