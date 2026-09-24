/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"); you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * *************************************************************************
 */

package com.etendoerp.go.payment;

import java.time.Instant;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.common.GoRuntimeProperties;

/**
 * Persists the minimum lifecycle metadata needed to enforce demo access.
 *
 * <p>Preferences are used as a compatibility-first persistence adapter: they are already scoped
 * by client, require no schema migration, and can be replaced by a dedicated repository later.
 * The service never rewrites a demo's start instant, so retries and configuration changes cannot
 * restart an existing trial.
 */
public class TenantEnvironmentLifecycleService {

  public static final String ENVIRONMENT_TYPE_ATTRIBUTE = "ETGO_EnvironmentType";
  public static final String DEMO_TRIAL_STARTED_ATTRIBUTE = "ETGO_DemoTrialStartedAt";
  public static final String SUBSCRIPTION_STATUS_ATTRIBUTE = "ETGO_SubscriptionStatus";
  public static final String SUBSCRIPTION_DUE_AT_ATTRIBUTE = "ETGO_SubscriptionDueAt";
  public static final String SUBSCRIPTION_EVENT_AT_ATTRIBUTE = "ETGO_SubscriptionEventAt";
  public static final String LEGACY_TRANSITION_STARTED_ATTRIBUTE = "ETGO_LegacyTransitionStartedAt";
  public static final String ASSOCIATED_DEMO_ATTRIBUTE = "ETGO_AssociatedDemoClientId";
  public static final String ASSOCIATED_PRODUCTIVE_ATTRIBUTE = "ETGO_AssociatedProductiveClientId";
  public static final String TYPE_DEMO = "DEMO";
  public static final String TYPE_PRODUCTIVE = "PRODUCTIVE";
  public static final int DEFAULT_TRIAL_DAYS = 15;
  public static final String TRIAL_DAYS_PROPERTY = "etendo.go.demo.trial.days";
  public static final String TRIAL_DAYS_ENV = "ETGO_DEMO_TRIAL_DAYS";
  public static final String GRACE_DAYS_PROPERTY = "etendo.go.billing.grace.days";
  public static final String GRACE_DAYS_ENV = "ETGO_BILLING_GRACE_DAYS";
  public static final int DEFAULT_GRACE_DAYS = 15;
  public static final String LEGACY_TRANSITION_ACTIVATION_PROPERTY =
      "etendo.go.demo.transition.activation.at";
  public static final String LEGACY_TRANSITION_ACTIVATION_ENV = "ETGO_DEMO_TRANSITION_ACTIVATION_AT";

  private static final String PARAM_ATTRIBUTE = "attribute";
  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String PREFERENCE_CLIENT_PREDICATE = " and pref.";
  private static final Logger log = LogManager.getLogger(TenantEnvironmentLifecycleService.class);

  private final TenantPlanService tenantPlanService;

  /** Creates a lifecycle service backed by the default plan resolver. */
  public TenantEnvironmentLifecycleService() {
    this(new TenantPlanService());
  }

  TenantEnvironmentLifecycleService(TenantPlanService tenantPlanService) {
    this.tenantPlanService = tenantPlanService;
  }

  /**
   * Records a demo as ready, preserving the first successful start timestamp.
   * @param clientId environment client id
   * @param trialStartedAt first trial start instant
   * @return true when lifecycle metadata was stored
   */
  public boolean markDemoReady(String clientId, Instant trialStartedAt) {
    if (StringUtils.isBlank(clientId) || trialStartedAt == null) {
      return false;
    }
    try {
      Client client = OBDal.getInstance().get(Client.class, clientId);
      if (client == null) {
        log.warn("Could not initialize demo lifecycle: client {} not found", clientId);
        return false;
      }
      if (readPreference(DEMO_TRIAL_STARTED_ATTRIBUTE, clientId) == null) {
        setPreference(DEMO_TRIAL_STARTED_ATTRIBUTE, trialStartedAt.toString(), client);
      }
      setPreference(ENVIRONMENT_TYPE_ATTRIBUTE, TYPE_DEMO, client);
      return true;
    } catch (RuntimeException e) {
      log.error("Could not initialize demo lifecycle for client {}", clientId, e);
      return false;
    }
  }

  /**
   * Records a productive environment without changing any existing Stripe fields.
   * @param clientId environment client id
   * @return true when lifecycle metadata was stored
   */
  public boolean markProductive(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      return false;
    }
    try {
      Client client = OBDal.getInstance().get(Client.class, clientId);
      if (client == null) {
        return false;
      }
      setPreference(ENVIRONMENT_TYPE_ATTRIBUTE, TYPE_PRODUCTIVE, client);
      setPreference(SUBSCRIPTION_STATUS_ATTRIBUTE,
          EnvironmentAccessPolicy.SubscriptionStatus.CURRENT.name(), client);
      return true;
    } catch (RuntimeException e) {
      log.error("Could not initialize productive lifecycle for client {}", clientId, e);
      return false;
    }
  }

  /**
   * Resolves a stored environment snapshot, falling back to the existing productive marker.
   * @param clientId environment client id
   * @return lifecycle snapshot, or null when metadata is unavailable
   */
  public EnvironmentSnapshot resolve(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      return null;
    }
    try {
      String type = readPreference(ENVIRONMENT_TYPE_ATTRIBUTE, clientId);
      if (TYPE_PRODUCTIVE.equalsIgnoreCase(type)
          || TenantPlanService.PLAN_PRODUCTIVE.equals(tenantPlanService.resolvePlan(clientId))) {
        String status = readPreference(SUBSCRIPTION_STATUS_ATTRIBUTE, clientId);
        EnvironmentAccessPolicy.SubscriptionStatus subscriptionStatus = parseSubscriptionStatus(
            status, EnvironmentAccessPolicy.SubscriptionStatus.LEGACY_ENTITLEMENT);
        Instant renewalDueAt = parseInstant(
            readPreference(SUBSCRIPTION_DUE_AT_ATTRIBUTE, clientId));
        return new EnvironmentSnapshot(EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE, null,
            subscriptionStatus, renewalDueAt, false);
      }
      String startedAt = readPreference(DEMO_TRIAL_STARTED_ATTRIBUTE, clientId);
      if (StringUtils.isBlank(startedAt)
          && TenantPlanService.PLAN_FREE.equals(tenantPlanService.resolvePlan(clientId))) {
        startedAt = ensureLegacyTransitionStart(clientId);
      }
      if (StringUtils.isBlank(startedAt)) {
        return null;
      }
      String associatedProductiveClientId = readPreference(ASSOCIATED_PRODUCTIVE_ATTRIBUTE, clientId);
      boolean associatedWithProductive = StringUtils.isNotBlank(associatedProductiveClientId);
      EnvironmentAccessPolicy.SubscriptionStatus subscriptionStatus =
          EnvironmentAccessPolicy.SubscriptionStatus.NONE;
      Instant renewalDueAt = null;
      if (StringUtils.isNotBlank(associatedProductiveClientId)) {
        EnvironmentSnapshot productive = resolve(associatedProductiveClientId);
        if (productive != null
            && productive.getType() == EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE) {
          subscriptionStatus = productive.getSubscriptionStatus();
          renewalDueAt = productive.getRenewalDueAt();
        }
      }
      return new EnvironmentSnapshot(EnvironmentAccessPolicy.EnvironmentType.DEMO,
          Instant.parse(startedAt), subscriptionStatus, renewalDueAt, associatedWithProductive);
    } catch (RuntimeException e) {
      log.warn("Could not resolve environment lifecycle for client {}", clientId, e);
      return null;
    }
  }

  /**
   * Returns the current trial and grace-period configuration.
   *
   * @return the configured trial and grace-period values
   */
  public EnvironmentAccessPolicy.Configuration configuration() {
    return new EnvironmentAccessPolicy.Configuration(
        GoRuntimeProperties.readInt(TRIAL_DAYS_PROPERTY, TRIAL_DAYS_ENV, DEFAULT_TRIAL_DAYS),
        GoRuntimeProperties.readInt(GRACE_DAYS_PROPERTY, GRACE_DAYS_ENV, DEFAULT_GRACE_DAYS));
  }

  /**
   * Assigns the configured rollout instant to an unmanaged free tenant once. An unset activation
   * instant deliberately leaves legacy data unresolved until the rollout is explicitly enabled.
   */
  private String ensureLegacyTransitionStart(String clientId) {
    String persisted = readPreference(LEGACY_TRANSITION_STARTED_ATTRIBUTE, clientId);
    if (StringUtils.isNotBlank(persisted)) {
      return persisted;
    }
    String configured = StringUtils.trimToNull(GoRuntimeProperties.readValue(
        LEGACY_TRANSITION_ACTIVATION_PROPERTY, LEGACY_TRANSITION_ACTIVATION_ENV, ""));
    if (configured == null) {
      return null;
    }
    Instant activation = parseInstant(configured);
    if (activation == null) {
      return null;
    }
    Client client = OBDal.getInstance().get(Client.class, clientId);
    if (client == null) {
      return null;
    }
    setPreference(LEGACY_TRANSITION_STARTED_ATTRIBUTE, activation.toString(), client);
    return activation.toString();
  }

  /**
   * Updates the local subscription projection; billing adapters supply the due date in UTC.
   * @param clientId environment client id
   * @param status subscription status to store
   * @param renewalDueAt subscription renewal due date
   * @return true when the projection was stored
   */
  public boolean updateSubscriptionStatus(String clientId,
      EnvironmentAccessPolicy.SubscriptionStatus status, Instant renewalDueAt) {
    if (StringUtils.isBlank(clientId) || status == null) {
      return false;
    }
    try {
      Client client = OBDal.getInstance().get(Client.class, clientId);
      if (client == null) {
        return false;
      }
      setPreference(SUBSCRIPTION_STATUS_ATTRIBUTE, status.name(), client);
      setPreference(SUBSCRIPTION_DUE_AT_ATTRIBUTE,
          renewalDueAt == null ? "" : renewalDueAt.toString(), client);
      return true;
    } catch (RuntimeException e) {
      log.error("Could not update subscription projection for client {}", clientId, e);
      return false;
    }
  }

  /**
   * Reads the stored subscription projection the lifecycle applier decides against.
   * @param clientId environment client id
   * @return stored status, grace anchor and last applied event instant; empty when none is stored
   */
  public SubscriptionLifecycleApplier.StoredState readSubscriptionState(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      return SubscriptionLifecycleApplier.StoredState.NONE;
    }
    return new SubscriptionLifecycleApplier.StoredState(
        parseSubscriptionStatus(readPreference(SUBSCRIPTION_STATUS_ATTRIBUTE, clientId), null),
        parseInstant(readPreference(SUBSCRIPTION_DUE_AT_ATTRIBUTE, clientId)),
        parseInstant(readPreference(SUBSCRIPTION_EVENT_AT_ATTRIBUTE, clientId)));
  }

  /**
   * Records the provider creation instant of the last applied lifecycle event, so an older event
   * delivered later can be recognised as stale.
   *
   * <p>Not committed here: the caller commits it with the status write, in one transaction.
   * Failures propagate so the caller can roll both back.
   * @param clientId environment client id
   * @param eventAt provider {@code created} instant; null leaves the stored value untouched
   */
  public void recordSubscriptionEventAt(String clientId, Instant eventAt) {
    if (StringUtils.isBlank(clientId) || eventAt == null) {
      return;
    }
    Client client = OBDal.getInstance().get(Client.class, clientId);
    if (client == null) {
      throw new IllegalStateException("Client not found while recording a subscription event");
    }
    setPreference(SUBSCRIPTION_EVENT_AT_ATTRIBUTE, eventAt.toString(), client);
  }

  /**
   * Updates lifecycle values for the local development test tool. The HTTP caller must gate this
   * method with {@code DevLifecycleToolService.isEnabled()} and ownership validation.
   * @param clientId environment client id
   * @param type environment type
   * @param trialStartedAt trial start instant for demo environments
   * @param subscriptionStatus subscription status to store
   * @param renewalDueAt renewal due date
   * @return true when the state was stored
   */
  public boolean updateDevelopmentState(String clientId, EnvironmentAccessPolicy.EnvironmentType type,
      Instant trialStartedAt, EnvironmentAccessPolicy.SubscriptionStatus subscriptionStatus,
      Instant renewalDueAt) {
    if (StringUtils.isBlank(clientId) || type == null) {
      return false;
    }
    try {
      Client client = OBDal.getInstance().get(Client.class, clientId);
      if (client == null) {
        return false;
      }
      setPreference(ENVIRONMENT_TYPE_ATTRIBUTE, type.name(), client);
      if (type == EnvironmentAccessPolicy.EnvironmentType.DEMO && trialStartedAt != null) {
        setPreference(DEMO_TRIAL_STARTED_ATTRIBUTE, trialStartedAt.toString(), client);
      }
      if (subscriptionStatus != null) {
        setPreference(SUBSCRIPTION_STATUS_ATTRIBUTE, subscriptionStatus.name(), client);
      }
      if (renewalDueAt != null) {
        setPreference(SUBSCRIPTION_DUE_AT_ATTRIBUTE, renewalDueAt.toString(), client);
      }
      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
      return true;
    } catch (RuntimeException e) {
      log.error("Could not update development lifecycle state for client {}", clientId, e);
      return false;
    }
  }

  /**
   * Associates one owned demo with one newly created productive environment.
   * @param demoClientId demo environment client id
   * @param productiveClientId productive environment client id
   * @return true when the association was stored
   */
  public boolean associateDemoWithProductive(String demoClientId, String productiveClientId) {
    if (StringUtils.isBlank(demoClientId) || StringUtils.isBlank(productiveClientId)) {
      return false;
    }
    try {
      Client demo = OBDal.getInstance().get(Client.class, demoClientId);
      Client productive = OBDal.getInstance().get(Client.class, productiveClientId);
      if (demo == null || productive == null) {
        return false;
      }
      OBContext.setAdminMode();
      try {
        setPreference(ASSOCIATED_PRODUCTIVE_ATTRIBUTE, productiveClientId, demo);
        setPreference(ASSOCIATED_DEMO_ATTRIBUTE, demoClientId, productive);
      } finally {
        OBContext.restorePreviousMode();
      }
      return true;
    } catch (RuntimeException e) {
      log.error("Could not associate demo {} with productive {}", demoClientId, productiveClientId,
          e);
      return false;
    }
  }

  /**
   * Evaluates tenant access without contacting a payment provider. A null result means the tenant
   * predates lifecycle metadata and must be handled by the controlled legacy transition flow.
   * @param clientId environment client id
   * @param activeMembership whether the caller belongs to the environment
   * @param now current instant
   * @return access decision, or null when lifecycle metadata is unavailable
   */
  public EnvironmentAccessPolicy.Decision evaluateAccess(String clientId, boolean activeMembership,
      Instant now) {
    EnvironmentSnapshot snapshot = resolve(clientId);
    if (snapshot == null) {
      // A legacy demo can have an association marker without a lifecycle start timestamp. The
      // association itself is enough to revoke demo access; a null snapshot would otherwise take
      // the compatibility path that allows tenants predating lifecycle metadata.
      if (StringUtils.isNotBlank(clientId)
          && StringUtils.isNotBlank(readPreference(ASSOCIATED_PRODUCTIVE_ATTRIBUTE, clientId))) {
        EnvironmentAccessPolicy policy = new EnvironmentAccessPolicy();
        return policy.evaluate(EnvironmentAccessPolicy.Environment.associatedDemo(null, null),
            activeMembership, EnvironmentAccessPolicy.SubscriptionStatus.NONE, now,
            configuration());
      }
      return null;
    }
    EnvironmentAccessPolicy.Environment environment = snapshot.toPolicyEnvironment();
    EnvironmentAccessPolicy.SubscriptionStatus subscription =
        snapshot.getSubscriptionStatus();
    return new EnvironmentAccessPolicy().evaluate(environment, activeMembership, subscription,
        now, configuration());
  }

  private EnvironmentAccessPolicy.SubscriptionStatus parseSubscriptionStatus(String value,
      EnvironmentAccessPolicy.SubscriptionStatus fallback) {
    if (StringUtils.isBlank(value)) {
      return fallback;
    }
    try {
      return EnvironmentAccessPolicy.SubscriptionStatus.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      log.warn("Ignoring unknown subscription status '{}'", value);
      return fallback;
    }
  }

  private Instant parseInstant(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      log.warn("Ignoring invalid subscription due timestamp");
      return null;
    }
  }

  private void setPreference(String attribute, String value, Client client) {
    OBQuery<Preference> query = OBDal.getInstance().createQuery(Preference.class,
        "as pref where pref." + Preference.PROPERTY_ATTRIBUTE + " = :" + PARAM_ATTRIBUTE
            + PREFERENCE_CLIENT_PREDICATE + Preference.PROPERTY_CLIENT + ".id = :" + PARAM_CLIENT_ID
            + PREFERENCE_CLIENT_PREDICATE + Preference.PROPERTY_ACTIVE + " = true");
    query.setNamedParameter(PARAM_ATTRIBUTE, attribute);
    query.setNamedParameter(PARAM_CLIENT_ID, client.getId());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    Preference preference = query.uniqueResult();
    if (preference == null) {
      preference = OBProvider.getInstance().get(Preference.class);
      preference.setClient(client);
      preference.setOrganization(null);
      preference.setActive(true);
      preference.setPropertyList(false);
      preference.setAttribute(attribute);
      preference.setSelected(true);
    }
    preference.setSearchKey(StringUtils.trimToEmpty(value));
    OBDal.getInstance().save(preference);
  }

  /**
   * Reads a lifecycle preference. These are system flags about the environment, not the caller's
   * data, and the access check reads them on every NEO request as the calling user: without admin
   * mode a role that cannot read {@code AD_Preference} (any non-admin role) fails here.
   */
  private String readPreference(String attribute, String clientId) {
    OBContext.setAdminMode();
    try {
      return readPreferenceValue(attribute, clientId);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private String readPreferenceValue(String attribute, String clientId) {
    OBQuery<Preference> query = OBDal.getInstance().createQuery(Preference.class,
        "as pref where pref." + Preference.PROPERTY_ATTRIBUTE + " = :" + PARAM_ATTRIBUTE
            + PREFERENCE_CLIENT_PREDICATE + Preference.PROPERTY_CLIENT + ".id = :" + PARAM_CLIENT_ID
            + PREFERENCE_CLIENT_PREDICATE + Preference.PROPERTY_ACTIVE + " = true");
    query.setNamedParameter(PARAM_ATTRIBUTE, attribute);
    query.setNamedParameter(PARAM_CLIENT_ID, clientId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    Preference preference = query.uniqueResult();
    return preference == null ? null : StringUtils.trimToNull(preference.getSearchKey());
  }

  /** Immutable lifecycle projection returned to access-policy callers. */
  public static final class EnvironmentSnapshot {
    private final EnvironmentAccessPolicy.EnvironmentType type;
    private final Instant trialStartedAt;
    private final EnvironmentAccessPolicy.SubscriptionStatus subscriptionStatus;
    private final Instant renewalDueAt;
    private final boolean associatedWithProductive;

    EnvironmentSnapshot(EnvironmentAccessPolicy.EnvironmentType type, Instant trialStartedAt,
        EnvironmentAccessPolicy.SubscriptionStatus subscriptionStatus, Instant renewalDueAt,
        boolean associatedWithProductive) {
      this.type = type;
      this.trialStartedAt = trialStartedAt;
      this.subscriptionStatus = subscriptionStatus;
      this.renewalDueAt = renewalDueAt;
      this.associatedWithProductive = associatedWithProductive;
    }

    public EnvironmentAccessPolicy.EnvironmentType getType() {
      return type;
    }

    public Instant getTrialStartedAt() {
      return trialStartedAt;
    }

    public EnvironmentAccessPolicy.SubscriptionStatus getSubscriptionStatus() {
      return subscriptionStatus;
    }

    public Instant getRenewalDueAt() {
      return renewalDueAt;
    }

    public boolean isAssociatedWithProductive() {
      return associatedWithProductive;
    }

    /**
     * Converts the stored projection to the provider-neutral policy input.
     *
     * @return the provider-neutral policy environment
     */
    public EnvironmentAccessPolicy.Environment toPolicyEnvironment() {
      if (type == EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE) {
        return EnvironmentAccessPolicy.Environment.productive(renewalDueAt);
      }
      return associatedWithProductive
          ? EnvironmentAccessPolicy.Environment.associatedDemo(trialStartedAt, renewalDueAt)
          : EnvironmentAccessPolicy.Environment.demo(trialStartedAt, renewalDueAt);
    }
  }
}
