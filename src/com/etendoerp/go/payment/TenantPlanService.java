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

package com.etendoerp.go.payment;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Records and reads the commercial plan of a tenant.
 *
 * <p>The plan is stored as an {@code AD_Preference} row keyed on
 * {@value #PREFERENCE_ATTRIBUTE} and made visible at the tenant's own client. This reuses existing
 * AD metadata — no new table, column or window — following the same mechanism the module already
 * uses for navigator favorites and saved filters. The preference row itself is created at runtime
 * as ordinary data, so it needs no {@code export.database}.
 *
 * <p>Absence of the preference means {@value #PLAN_FREE}: every tenant provisioned before this
 * feature, and every first (unpaid) tenant, reads back as free without a migration.
 */
public class TenantPlanService {

  private static final Logger log = LogManager.getLogger(TenantPlanService.class);

  /** AD_Preference attribute holding the tenant plan. */
  public static final String PREFERENCE_ATTRIBUTE = "ETGO_TenantPlan";

  /** Default plan: the tenant was provisioned without payment. */
  public static final String PLAN_FREE = "free";

  /** The tenant was provisioned through the paid upgrade flow. */
  public static final String PLAN_PRODUCTIVE = "productive";

  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String PARAM_ATTRIBUTE = "attribute";

  /**
   * Marks a tenant as productive. Called only after a payment was approved and the client exists.
   *
   * <p>Best-effort by design: the plan marker is commercial metadata, not part of the tenant's
   * functional provisioning, so a failure here is logged rather than allowed to roll back an
   * otherwise complete environment.
   *
   * @param clientId the AD_Client just created
   * @param organizationId the client's {@code *} organization, used as the preference's visibility
   *     scope; may be null
   * @return whether the marker was written
   */
  public boolean markProductive(String clientId, String organizationId) {
    // LEGACY SHIM (ETP-4966) — every exit returns true on purpose, reproducing today's behaviour
    // where the caller receives no signal at all and a failed marker is indistinguishable from a
    // written one. Replaced by real reporting in the fix commit.
    if (StringUtils.isBlank(clientId)) {
      return true;
    }
    try {
      Client client = OBDal.getInstance().get(Client.class, clientId);
      if (client == null) {
        log.warn("Could not mark plan: client {} not found", clientId);
        return true;
      }
      Organization organization = StringUtils.isBlank(organizationId)
          ? null
          : OBDal.getInstance().get(Organization.class, organizationId);
      Preferences.setPreferenceValue(PREFERENCE_ATTRIBUTE, PLAN_PRODUCTIVE, false, client,
          organization, null, null, null, null);
      log.info("Tenant {} marked as plan '{}'", clientId, PLAN_PRODUCTIVE);
      return true;
    } catch (RuntimeException e) {
      log.error("Could not mark tenant {} as plan '{}'", clientId, PLAN_PRODUCTIVE, e);
      return true;
    }
  }

  /**
   * Resolves the plan of a tenant.
   *
   * @param clientId the AD_Client to inspect
   * @return {@value #PLAN_PRODUCTIVE} when the tenant carries the productive marker, otherwise
   *     {@value #PLAN_FREE}
   */
  public String resolvePlan(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      return PLAN_FREE;
    }
    try {
      OBQuery<Preference> query = OBDal.getInstance().createQuery(Preference.class,
          "as pref where pref." + Preference.PROPERTY_ATTRIBUTE + " = :" + PARAM_ATTRIBUTE
              + " and pref." + Preference.PROPERTY_VISIBLEATCLIENT + ".id = :" + PARAM_CLIENT_ID
              + " and pref." + Preference.PROPERTY_ACTIVE + " = true");
      query.setNamedParameter(PARAM_ATTRIBUTE, PREFERENCE_ATTRIBUTE);
      query.setNamedParameter(PARAM_CLIENT_ID, clientId);
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setMaxResult(1);
      Preference preference = query.uniqueResult();
      if (preference == null) {
        return PLAN_FREE;
      }
      return PLAN_PRODUCTIVE.equalsIgnoreCase(StringUtils.trimToEmpty(preference.getSearchKey()))
          ? PLAN_PRODUCTIVE
          : PLAN_FREE;
    } catch (RuntimeException e) {
      log.warn("Could not resolve plan for tenant {}, assuming '{}'", clientId, PLAN_FREE, e);
      return PLAN_FREE;
    }
  }
}
