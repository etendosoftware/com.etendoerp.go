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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.webhookevents.data.DefinedWebHook;
import com.etendoerp.webhookevents.data.DefinedwebhookRole;

/**
 * Grants a freshly onboarded tenant's admin role access to the {@code SFWindowAccessMap} Defined
 * Webhook.
 *
 * <p>{@code SMFWHE_DEFINEDWEBHOOK_ROLE} is a per-(client, role) ACL enforced by {@code
 * com.etendoerp.webhookevents} itself: a role with no active grant row for a given webhook gets a
 * hard 401 calling it, regardless of what that webhook's own internal logic would have returned.
 * GOClient's reference roles are granted via {@code
 * referencedata/sampledata/GOClient/SMFWHE_DEFINEDWEBHOOK_ROLE.xml}. Nothing provisions an
 * equivalent grant for a freshly onboarded tenant's own auto-created admin role — so every real
 * onboarded tenant's frontend gets a 401 calling {@code GET /webhooks/SFWindowAccessMap}, which
 * {@code AuthContext} (ETP-4520, {@code app-shell-core}) correctly treats as fail-closed (empty
 * {@code windowAccess}/{@code capabilities} maps), blocking every generated window's detail/create/
 * edit view for that tenant.
 *
 * <p>This service closes that gap for {@code SFWindowAccessMap} specifically — the one Defined
 * Webhook a fail-closed frontend access-control feature actually depends on being reachable.
 * {@code SFListMenu} has the identical onboarding gap (also GOClient-only), but its own consumer
 * fails open (an unreachable menu-filter map just leaves the sidebar unfiltered, per ETP-4598), so
 * it is not a hard blocker the way {@code SFWindowAccessMap} is — deliberately out of this
 * service's scope; broader onboarding webhook-grant provisioning belongs to Phase 7 (ETP-4515).
 */
public class OnboardingWebhookAccessService extends OnboardingContextSupport {

  private static final Logger log = LogManager.getLogger(OnboardingWebhookAccessService.class);

  /** {@code SMFWHE_DEFINEDWEBHOOK.NAME} for the webhook this service grants access to. */
  private static final String WEBHOOK_NAME = "SFWindowAccessMap";

  /** {@code AD_Org_ID} used for the grant row — matches the "*" org scope of the GOClient reference grants. */
  private static final String STAR_ORG_ID = "0";

  /**
   * Grants {@code adminRoleId} access to the {@code SFWindowAccessMap} webhook on {@code
   * clientId}, unless it already has an active grant. Idempotent: safe to call on every
   * onboarding run (including a resumed/retried one) without creating duplicate rows.
   *
   * @param clientId    target client identifier
   * @param adminUserId administrator user for DAL context
   * @param adminRoleId administrator role for DAL context — the role the grant is created for
   */
  public void wire(String clientId, String adminUserId, String adminRoleId) {
    requirePresent(clientId, "client");
    requirePresent(adminUserId, "admin user");
    requirePresent(adminRoleId, "admin role");
    OBContext previousContext = captureCurrentContext();
    applyExecutionContext(adminUserId, adminRoleId, clientId, STAR_ORG_ID);
    try {
      enterAdminMode();
      try {
        if (hasActiveGrant(adminRoleId)) {
          log.debug("Role {} already has an active SFWindowAccessMap grant; skipping", adminRoleId);
          return;
        }
        DefinedWebHook webhook = resolveWebhook();
        if (webhook == null) {
          throw new OBException(
              "Webhook '" + WEBHOOK_NAME + "' not found — cannot grant tenant access");
        }
        Role role = resolveRole(adminRoleId);
        if (role == null) {
          throw new OBException("Admin role not found for webhook-access wiring: " + adminRoleId);
        }
        createGrant(clientId, role, webhook);
        flushChanges();
      } finally {
        exitAdminMode();
      }
    } finally {
      restoreExecutionContext(previousContext);
    }
  }

  /** Seam for tests: whether {@code roleId} already has an active grant for the webhook. */
  protected boolean hasActiveGrant(String roleId) {
    OBCriteria<DefinedwebhookRole> criteria = OBDal.getInstance().createCriteria(DefinedwebhookRole.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(DefinedwebhookRole.PROPERTY_ROLE + ".id", roleId));
    criteria.add(Restrictions.eq(DefinedwebhookRole.PROPERTY_ACTIVE, true));
    criteria.createAlias(DefinedwebhookRole.PROPERTY_SMFWHEDEFINEDWEBHOOK, "webhook");
    criteria.add(Restrictions.eq("webhook.name", WEBHOOK_NAME));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }

  /** Seam for tests: resolves the {@code SFWindowAccessMap} Defined Webhook definition row. */
  protected DefinedWebHook resolveWebhook() {
    OBCriteria<DefinedWebHook> criteria = OBDal.getInstance().createCriteria(DefinedWebHook.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(DefinedWebHook.PROPERTY_NAME, WEBHOOK_NAME));
    criteria.setMaxResults(1);
    return (DefinedWebHook) criteria.uniqueResult();
  }

  /** Seam for tests: resolves the target admin role by id. */
  protected Role resolveRole(String roleId) {
    return OBDal.getInstance().get(Role.class, roleId);
  }

  /** Seam for tests: builds and saves the new grant row. */
  protected void createGrant(String clientId, Role role, DefinedWebHook webhook) {
    Client client = OBDal.getInstance().get(Client.class, clientId);
    Organization starOrg = resolveOrganization(STAR_ORG_ID);
    DefinedwebhookRole grant = OBProvider.getInstance().get(DefinedwebhookRole.class);
    grant.setClient(client);
    grant.setOrganization(starOrg);
    grant.setActive(true);
    grant.setRole(role);
    grant.setSmfwheDefinedwebhook(webhook);
    grant.setModuleID(webhook.getModule());
    OBDal.getInstance().save(grant);
  }

  private void requirePresent(String value, String label) {
    if (value == null || value.isEmpty()) {
      throw new OBException("Missing " + label + " for " + contextSubject());
    }
  }

  @Override
  protected String contextSubject() {
    return "webhook-access wiring";
  }
}
