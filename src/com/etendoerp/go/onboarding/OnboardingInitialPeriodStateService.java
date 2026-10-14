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

import java.time.Instant;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.payment.EnvironmentAccessPolicy;
import com.etendoerp.go.payment.TenantEnvironmentLifecycleService;

/**
 * Initial fiscal-period state of a new tenant (ETP-5577), applied after the onboarding commit.
 *
 * <p>The provisioning chain leaves every period never-opened, because it also builds pooled
 * tenants before anyone knows whether a demo or a productive onboarding will claim them. Which
 * periods start open is decided here, per tenant type:
 * <ul>
 *   <li><b>demo</b>: every period through the end of the trial (ETP-5575);</li>
 *   <li><b>productive</b>: none; the owner reviews the calendar in "Primeros pasos" and opens
 *       the periods they want.</li>
 * </ul>
 *
 * <p>Two client preferences drive it, both written by {@link #markPending} inside the onboarding
 * transaction, so they exist exactly when the tenant does:
 * <ul>
 *   <li>{@value #PENDING_ATTRIBUTE}: the state is still to be applied. Its value carries the
 *       organization, admin user and admin role the step runs as. {@link #applyIfPending} deletes it
 *       on success, so a late re-run never touches a tenant the user already acted on, and tenants
 *       created before this release, which never have it, are never touched at all;</li>
 *   <li>{@value #REVIEW_REQUIRED_ATTRIBUTE}: the tenant must review its calendar. The "Primeros
 *       pasos" step and the posting-error guidance key off it, so tenants onboarded before this
 *       release, whose periods were opened at onboarding, never see them.</li>
 * </ul>
 */
public class OnboardingInitialPeriodStateService {

  private static final Logger log = LogManager.getLogger(OnboardingInitialPeriodStateService.class);

  public static final String PENDING_ATTRIBUTE = "ETGO_InitialPeriodStatePending";
  public static final String REVIEW_REQUIRED_ATTRIBUTE = "ETGO_CalendarReviewRequired";
  private static final String VALUE_YES = "Y";
  private static final String PARAM_ATTRIBUTE = "attribute";
  private static final String PARAM_CLIENT_ID = "clientId";

  private final OnboardingPeriodControlService periodControlService;
  private final TenantEnvironmentLifecycleService lifecycleService;

  public OnboardingInitialPeriodStateService() {
    this(new OnboardingPeriodControlService(), new TenantEnvironmentLifecycleService());
  }

  OnboardingInitialPeriodStateService(OnboardingPeriodControlService periodControlService,
      TenantEnvironmentLifecycleService lifecycleService) {
    this.periodControlService = periodControlService;
    this.lifecycleService = lifecycleService;
  }

  /**
   * Records, inside the onboarding transaction, that the tenant's initial period state is pending
   * and whether its calendar must be reviewed.
   *
   * @param reviewRequired productive tenant: its periods start never-opened until the owner
   *                       reviews the calendar
   */
  public void markPending(String clientId, String orgId, String adminUserId, String adminRoleId,
      boolean reviewRequired) {
    Client client = OBDal.getInstance().get(Client.class, clientId);
    setClientPreference(PENDING_ATTRIBUTE, String.join(" ", orgId, adminUserId, adminRoleId),
        client);
    if (reviewRequired) {
      setClientPreference(REVIEW_REQUIRED_ATTRIBUTE, VALUE_YES, client);
    }
  }

  /** Whether the tenant's initial period state is still to be applied. */
  public boolean isPending(String clientId) {
    return readClientPreference(PENDING_ATTRIBUTE, clientId) != null;
  }

  /** Whether the tenant was onboarded with the calendar review required. */
  public boolean isReviewRequired(String clientId) {
    return VALUE_YES.equals(readClientPreference(REVIEW_REQUIRED_ATTRIBUTE, clientId));
  }

  /**
   * Applies the tenant's initial period state when it is still pending, and clears the pending
   * marker. Leaves transaction handling to the caller: it commits on success and rolls back on
   * failure, so the marker survives a failure and the next login retries.
   *
   * @return {@code true} when the state was applied, {@code false} when nothing was pending
   */
  public boolean applyIfPending(String clientId) {
    String pending = readClientPreference(PENDING_ATTRIBUTE, clientId);
    if (pending == null) {
      return false;
    }
    String[] ids = pending.split(" ");
    if (ids.length != 3) {
      throw new IllegalStateException("Malformed " + PENDING_ATTRIBUTE + " for client " + clientId);
    }
    log.info("ETP-5577 initial period state started for client {}", clientId);
    String orgId = ids[0];
    String adminUserId = ids[1];
    String adminRoleId = ids[2];
    TenantEnvironmentLifecycleService.EnvironmentSnapshot snapshot =
        lifecycleService.resolve(clientId);
    if (snapshot == null) {
      throw new IllegalStateException("Unknown environment type for client " + clientId);
    }
    if (snapshot.getType() == EnvironmentAccessPolicy.EnvironmentType.DEMO) {
      Instant trialStart = snapshot.getTrialStartedAt() != null ? snapshot.getTrialStartedAt()
          : Instant.now();
      periodControlService.openDemoTrialWindow(clientId, orgId, adminUserId, adminRoleId,
          trialStart, lifecycleService.configuration().getTrialDays());
    } else {
      periodControlService.applyProductiveInitialState(clientId, orgId, adminUserId, adminRoleId);
    }
    deleteClientPreference(PENDING_ATTRIBUTE, clientId);
    return true;
  }

  private static void setClientPreference(String attribute, String value, Client client) {
    Preferences.setPreferenceValue(attribute, value, false, client, null, null, null, null, null);
  }

  static String readClientPreference(String attribute, String clientId) {
    Preference preference = findClientPreference(attribute, clientId);
    return preference == null ? null : StringUtils.trimToNull(preference.getSearchKey());
  }

  private static void deleteClientPreference(String attribute, String clientId) {
    OBContext.setAdminMode(true);
    try {
      for (Preference preference : clientPreferenceQuery(attribute, clientId).list()) {
        OBDal.getInstance().remove(preference);
      }
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Admin mode: the login-time check runs as the user entering the environment. */
  private static Preference findClientPreference(String attribute, String clientId) {
    OBContext.setAdminMode(true);
    try {
      OBQuery<Preference> query = clientPreferenceQuery(attribute, clientId);
      query.setMaxResult(1);
      return query.uniqueResult();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static OBQuery<Preference> clientPreferenceQuery(String attribute, String clientId) {
    OBQuery<Preference> query = OBDal.getInstance().createQuery(Preference.class,
        "as p where p.attribute = :attribute and p.visibleAtClient.id = :clientId"
            + " and p.active = true");
    query.setNamedParameter(PARAM_ATTRIBUTE, attribute);
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query;
  }
}
