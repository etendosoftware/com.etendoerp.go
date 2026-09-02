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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.payment.TenantPlanService;

/**
 * Forces SII/TicketBAI/VeriFactu submissions into their test/sandbox environments for a
 * freshly-provisioned Demo/free tenant (ETP-5117, gap N1), by writing a per-Client {@code
 * ETSG_ForceTestMode} preference row (owned by {@code com.etendoerp.sif.general}).
 *
 * <h3>Why this cannot reuse {@link com.etendoerp.go.payment.TenantPlanService#markProductive}'s
 * pattern</h3>
 * {@code TenantPlanService} writes {@code ETGO_TenantPlan} via {@code
 * Preferences.setPreferenceValue(...)}, whose insert branch <b>always</b> pins the new row's own
 * {@code AD_Client_ID}/{@code AD_Org_ID} to the System client {@code '0'} and encodes the target
 * tenant only in {@code VisibleAtClient} — because {@code TenantPlanService#resolvePlan} reads it
 * back via {@code Preference.visibleAtClient}, that shape is exactly right for that preference.
 * {@code ETSG_ForceTestMode} is read back completely differently: {@code
 * com.etendoerp.verifactu.eventhandler.ForceTestModeEventHandler} (and its SII/TicketBAI
 * siblings) resolve the effective value with a plain {@link OBCriteria} filtered on {@code
 * Preference.client} (the row's own {@code AD_Client_ID} column) — never {@code VisibleAtClient},
 * and deliberately not {@code Preferences.getPreferenceValue()} at all (see that class's own
 * javadoc: reusing the standard precedence engine there previously corrupted Hibernate's shared
 * session state across many tenants in one request). So a row written the {@code
 * setPreferenceValue} way would carry {@code AD_Client_ID='0'} and be entirely invisible to
 * these handlers' lookup. This service instead builds and saves the {@link Preference} entity
 * directly, with {@link Preference#setClient} pinned to the <b>tenant itself</b> — never the
 * System client — mirroring the shape already used by every {@code ETSG_ForceTestMode} row an
 * operator creates by hand via the Classic Preference window.
 *
 * <h3>Non-negotiable: never touches the System-level default row</h3>
 * The bundled default ({@code AD_Preference_ID 6DCB1CD4A0414D78BB97441626B62835}, {@code
 * AD_Client_ID='0'}, {@code VALUE='N'}) is the fallback every tenant without its own override
 * reads through {@code ForceTestModeEventHandler#isForceTestModeActiveForClient}. This service
 * always <b>inserts a brand-new, per-Client</b> row for the tenant being onboarded; it never
 * updates that System row (which would force test mode onto every tenant in the instance) and
 * never updates another tenant's row.
 *
 * <h3>Scope: Demo/free tenants only</h3>
 * Gated by {@link TenantPlanService#resolvePlan(String)} — a tenant provisioned through the paid
 * upgrade flow ({@link TenantPlanService#PLAN_PRODUCTIVE}) is left untouched; a productive tenant
 * defaults to real submissions like any Classic-provisioned client. Absence of the plan
 * preference reads back as {@link TenantPlanService#PLAN_FREE} (see that class's own javadoc), so
 * every ordinary self-registered tenant is in scope.
 *
 * <h3>Idempotency</h3>
 * A tenant that already carries its own active {@code ETSG_ForceTestMode} row (from a previous
 * onboarding pass, or from an operator's own manual choice in Classic) is left alone — this
 * service only ever <em>inserts</em>, never overwrites an existing row, so a retried/resumed
 * onboarding pass (ETP-4428 reconcile model) is a true no-op, and a deliberate manual override is
 * never clobbered.
 *
 * <h3>No cascade needed for a brand-new tenant</h3>
 * {@code ForceTestModeEventHandler}'s cascade to already-existing {@code VerifactuConfig} rows
 * fires only on <b>update</b> of the {@code Preference} row, never on insert (see that class's
 * own javadoc) — irrelevant here, since a freshly-provisioned tenant has zero SII/TicketBAI/
 * VeriFactu config rows yet. Whenever the tenant's first such row is later created (through the
 * app, not onboarding), each handler's own Observer B resolves {@code IsDevEnv}/{@code
 * EntornoDeProduccin}/{@code ProductionEnv} from this preference at that row's own save time — no
 * further action needed here.
 *
 * <p>Lockstep corrective twin for already-onboarded Demo tenants: {@code
 * 20260901T120000Z__R31-force-test-mode-demo-tenants.sql} in {@code
 * schema_forge/cli/src/data-fixes/sql/} — which, unlike this service, ALSO directly backfills any
 * pre-existing config rows' own columns, since a corrective fix cannot rely on the update-cascade
 * either (a data-fix runs as plain SQL, which never goes through Hibernate/DAL at all, so it can
 * never fire any of these observers).</p>
 */
public class OnboardingForceTestModeService {

  private static final Logger log = LogManager.getLogger(OnboardingForceTestModeService.class);

  /** {@code AD_Preference.Property} key owned by {@code com.etendoerp.sif.general}. */
  public static final String FORCE_TEST_MODE_PROPERTY = "ETSG_ForceTestMode";

  private static final String YES = "Y";

  private final TenantPlanService tenantPlanService;

  public OnboardingForceTestModeService() {
    this(new TenantPlanService());
  }

  /** Visible for tests — inject a mock/stub {@link TenantPlanService}. */
  OnboardingForceTestModeService(TenantPlanService tenantPlanService) {
    this.tenantPlanService = tenantPlanService;
  }

  /**
   * Forces {@code ETSG_ForceTestMode='Y'} for {@code clientId} when (and only when) it resolves
   * as a Demo/free tenant and does not already carry its own override row.
   *
   * @param clientId the tenant just onboarded
   * @param orgId    the tenant's real business organization (visibility scope for the new row;
   *                 not read by any consumer's lookup, kept only to mirror the shape of an
   *                 operator-created row and to satisfy the NOT NULL column)
   */
  public void forceTestModeForFreeTenant(String clientId, String orgId) {
    if (StringUtils.isBlank(clientId)) {
      throw new OBException("Cannot force test mode: onboarding context has no client id");
    }

    String plan = tenantPlanService.resolvePlan(clientId);
    if (!TenantPlanService.PLAN_FREE.equals(plan)) {
      log.info("Tenant '{}' resolved as plan '{}' — leaving ETSG_ForceTestMode untouched", clientId,
          plan);
      return;
    }

    if (hasOwnForceTestModeRow(clientId)) {
      log.info("Tenant '{}' already has its own ETSG_ForceTestMode row — conserved, not overwritten",
          clientId);
      return;
    }

    Client client = OBDal.getInstance().get(Client.class, clientId);
    if (client == null) {
      throw new OBException("Cannot force test mode: client " + clientId + " not found");
    }
    Organization organization = StringUtils.isBlank(orgId) ? null
        : OBDal.getInstance().get(Organization.class, orgId);
    if (organization == null) {
      throw new OBException("Cannot force test mode: organization " + orgId + " not found for "
          + "client " + clientId);
    }

    Preference preference = OBProvider.getInstance().get(Preference.class);
    // Client-scoped, non-negotiable: never the System client ('0'), never another tenant's row.
    preference.setClient(client);
    preference.setOrganization(organization);
    preference.setActive(true);
    preference.setPropertyList(true);
    preference.setProperty(FORCE_TEST_MODE_PROPERTY);
    preference.setSearchKey(YES);
    OBDal.getInstance().save(preference);
    OBDal.getInstance().flush();

    log.info("Forced ETSG_ForceTestMode='Y' for Demo tenant '{}'", clientId);
  }

  /**
   * @return {@code true} when {@code clientId} already owns an active {@code ETSG_ForceTestMode}
   *     row of its own (never the System default, since this filters on the row's own {@code
   *     Client}, matching exactly what {@code ForceTestModeEventHandler#findPreference} reads).
   */
  private boolean hasOwnForceTestModeRow(String clientId) {
    OBCriteria<Preference> criteria = OBDal.getInstance().createCriteria(Preference.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(Preference.PROPERTY_PROPERTY, FORCE_TEST_MODE_PROPERTY));
    criteria.add(Restrictions.eq(Preference.PROPERTY_CLIENT + ".id", clientId));
    criteria.add(Restrictions.eq(Preference.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }
}
