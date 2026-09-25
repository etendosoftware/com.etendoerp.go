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
import java.util.Optional;

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
import com.etendoerp.go.schemaforge.data.Subscription;

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
  private final SubscriptionService subscriptionService;

  /** Creates a lifecycle service backed by the default plan resolver. */
  public TenantEnvironmentLifecycleService() {
    this(new TenantPlanService(), new SubscriptionService());
  }

  TenantEnvironmentLifecycleService(TenantPlanService tenantPlanService) {
    this(tenantPlanService, new SubscriptionService());
  }

  TenantEnvironmentLifecycleService(TenantPlanService tenantPlanService,
      SubscriptionService subscriptionService) {
    this.tenantPlanService = tenantPlanService;
    this.subscriptionService = subscriptionService;
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
        return productiveSnapshot(clientId);
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
   * Builds the productive snapshot, reading the billing state from the subscription row.
   *
   * <p>ETP-5046 made {@code ETGO_SUBSCRIPTION} the single source of truth for whether a tenant is
   * paying and until when. The {@code ETGO_SubscriptionStatus} and {@code ETGO_SubscriptionDueAt}
   * preferences survive only as a transitional fallback for a tenant the R37 backfill has not
   * reached yet. The order matters: consulting the preferences first would let the access policy
   * and the Subscription Plan Catalog disagree about the same tenant, which is precisely what
   * this unification exists to prevent.
   *
   * <p>The Stripe lifecycle webhooks keep the row current: {@link #applySubscriptionEvent}
   * writes their outcome onto the row they resolved, so the row's {@code STATUS} and
   * {@code CURRENT_PERIOD_END} are what the access policy reads here.
   *
   * @param clientId environment client id, already known to be productive
   * @return the productive snapshot, never null
   */
  private EnvironmentSnapshot productiveSnapshot(String clientId) {
    // ETP-5047 — the open row, else the latest closed one: a canceled subscription is closed, and
    // must keep reading as EXPIRED rather than fall through to the preference fallback below.
    Optional<Subscription> latestSubscription = subscriptionService.findLatest(clientId);
    if (latestSubscription.isPresent()) {
      Subscription subscription = latestSubscription.get();
      Instant renewalDueAt = SubscriptionService.graceAnchorOf(subscription);
      return new EnvironmentSnapshot(EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE, null,
          subscriptionStatusOf(SubscriptionService.effectiveStatusOf(subscription)), renewalDueAt,
          false);
    }
    // ETP-5046-TRANSITIONAL-FALLBACK — no subscription row for this tenant at all, open or closed.
    // Delete this part of the condition together with the rest of the fallback in Phase F, once
    // every productive tenant has one.
    EnvironmentAccessPolicy.SubscriptionStatus subscriptionStatus = parseSubscriptionStatus(
        readPreference(SUBSCRIPTION_STATUS_ATTRIBUTE, clientId),
        EnvironmentAccessPolicy.SubscriptionStatus.LEGACY_ENTITLEMENT);
    Instant renewalDueAt = parseInstant(readPreference(SUBSCRIPTION_DUE_AT_ATTRIBUTE, clientId));
    return new EnvironmentSnapshot(EnvironmentAccessPolicy.EnvironmentType.PRODUCTIVE, null,
        subscriptionStatus, renewalDueAt, false);
  }

  /**
   * Maps an {@code ETGO_SUBSCRIPTION.STATUS} value onto the access policy's vocabulary.
   *
   * <p>An unrecognised status degrades to {@code LEGACY_ENTITLEMENT}, never to {@code NONE}: the
   * tenant demonstrably holds an open subscription row, so the safe reading of a status this build
   * does not know about is "entitled", not "locked out of the product".
   *
   * @param status the stored subscription status, may be null or blank
   * @return the matching access-policy status
   */
  static EnvironmentAccessPolicy.SubscriptionStatus subscriptionStatusOf(String status) {
    String normalized = StringUtils.lowerCase(StringUtils.trimToEmpty(status), Locale.ROOT);
    if (SubscriptionService.STATUS_ACTIVE.equals(normalized)) {
      return EnvironmentAccessPolicy.SubscriptionStatus.CURRENT;
    }
    if (SubscriptionService.STATUS_PAST_DUE.equals(normalized)) {
      return EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE;
    }
    if (SubscriptionService.STATUS_CANCELED.equals(normalized)) {
      return EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED;
    }
    return EnvironmentAccessPolicy.SubscriptionStatus.LEGACY_ENTITLEMENT;
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
   * ETP-5047 — where a lifecycle event for a Stripe subscription lands when an open
   * {@code ETGO_SUBSCRIPTION} row carries that subscription id (§5.2: the id is not unique, so
   * only the open row answers).
   *
   * @param stripeSubscriptionId {@code sub_...} the event names; blank finds nothing
   * @return the row target, or null when no open row carries the id — the caller then resolves
   *     the tenant through its checkout request and asks {@link #targetForTenant}
   */
  public LifecycleTarget targetForSubscription(String stripeSubscriptionId) {
    Optional<Subscription> row = subscriptionService.findOpenByStripeSubscription(
        stripeSubscriptionId);
    if (row.isEmpty() || row.get().getEnvironmentClient() == null) {
      return null;
    }
    Subscription subscription = row.get();
    String clientId = subscription.getEnvironmentClient().getId();
    return LifecycleTarget.row(clientId, subscription, rowState(subscription));
  }

  /**
   * ETP-5047 — where a lifecycle event lands for a tenant resolved without a row match (through
   * the checkout request that created it, or the Stripe customer).
   *
   * <ul>
   *   <li><b>No row at all</b> → the preference projection ({@code ETGO_SubscriptionStatus} /
   *       {@code ETGO_SubscriptionDueAt}), a tenant the R37 backfill has not reached
   *       ({@code ETP-5046-TRANSITIONAL-FALLBACK}).</li>
   *   <li><b>An open row naming no Stripe subscription, or this one</b> → that row (a backfilled
   *       row may carry no id).</li>
   *   <li><b>An open row naming a different subscription</b> → ignored: the event belongs to an
   *       older, replaced subscription and must not overwrite the current one.</li>
   *   <li><b>Only closed rows</b> → ignored: the tenant's subscription was canceled, and a late
   *       event for it must not revive it; a new purchase opens its own row.</li>
   * </ul>
   *
   * @param clientId the tenant, {@code AD_CLIENT_ID}
   * @param stripeSubscriptionId {@code sub_...} the event names, may be blank
   * @return the target, never null; {@link LifecycleTarget#isIgnored()} carries the audit reason
   */
  public LifecycleTarget targetForTenant(String clientId, String stripeSubscriptionId) {
    Optional<Subscription> latest = subscriptionService.findLatest(clientId);
    if (latest.isEmpty()) {
      // ETP-5046-TRANSITIONAL-FALLBACK — no subscription row: the preference projection.
      return LifecycleTarget.preferences(clientId, new SubscriptionLifecycleApplier.StoredState(
          parseSubscriptionStatus(readPreference(SUBSCRIPTION_STATUS_ATTRIBUTE, clientId), null),
          parseInstant(readPreference(SUBSCRIPTION_DUE_AT_ATTRIBUTE, clientId)),
          readEventWatermark(clientId)));
    }
    Subscription subscription = latest.get();
    String rowSubscriptionId = StringUtils.trimToNull(subscription.getStripeSubscription());
    String eventSubscriptionId = StringUtils.trimToNull(stripeSubscriptionId);
    boolean otherSubscription = rowSubscriptionId != null && eventSubscriptionId != null
        && !rowSubscriptionId.equals(eventSubscriptionId);
    if (subscription.getEndDate() != null) {
      // A late event of the canceled subscription, or the first event of a new purchase whose row
      // the onboarding has not opened yet: either way there is no open row it may write.
      return LifecycleTarget.ignored(clientId,
          otherSubscription ? "no open subscription row" : "subscription closed");
    }
    if (otherSubscription) {
      return LifecycleTarget.ignored(clientId, "event for another subscription");
    }
    return LifecycleTarget.row(clientId, subscription, rowState(subscription));
  }

  /**
   * The stored state of a row target, read from the row alone (ETP-5047): status, the grace
   * anchor ({@code GRACE_ANCHOR}, with the pre-split fallback of
   * {@link SubscriptionService#graceAnchorOf}) and the ordering watermark
   * ({@code LAST_EVENT_AT}). The row route reads no preference.
   */
  private static SubscriptionLifecycleApplier.StoredState rowState(Subscription subscription) {
    return new SubscriptionLifecycleApplier.StoredState(
        subscriptionStatusOf(subscription.getSubscriptionStatus()),
        SubscriptionService.graceAnchorOf(subscription),
        SubscriptionService.lastEventAtOf(subscription));
  }

  /**
   * The ordering watermark of a tenant with no subscription row: the
   * {@code ETGO_SubscriptionEventAt} preference ({@code ETP-5046-TRANSITIONAL-FALLBACK}).
   */
  private Instant readEventWatermark(String clientId) {
    return parseInstant(readPreference(SUBSCRIPTION_EVENT_AT_ATTRIBUTE, clientId));
  }

  /**
   * ETP-5047 — stores an applied lifecycle outcome where {@code target} says it belongs, together
   * with the event's ordering watermark.
   *
   * <p>A row target is written through {@link SubscriptionService#applyLifecycleOutcome} — status,
   * {@code GRACE_ANCHOR}, the billing period, the {@code LAST_EVENT_AT} watermark, and the close on
   * a terminating event, all on the row; a preference target keeps the
   * {@code ETGO_SubscriptionStatus} / {@code ETGO_SubscriptionDueAt} projection
   * ({@code ETP-5046-TRANSITIONAL-FALLBACK}). Writing only one store per tenant is what keeps the
   * two from disagreeing.
   *
   * <p>Not committed here: the webhook handler commits it with the event's ledger row, and rolls
   * both back on failure.
   *
   * @param target a non-ignored target from {@link #targetForSubscription} or
   *     {@link #targetForTenant}
   * @param outcome an applied outcome
   * @param eventAt the event's provider {@code created} instant; null leaves the watermark as is
   * @return true when the outcome was stored; false when the write failed (logged). On the
   *     preference route a failing watermark write throws instead, so the caller rolls back and
   *     the provider retries.
   */
  public boolean applySubscriptionEvent(LifecycleTarget target, SubscriptionEventOutcome outcome,
      Instant eventAt) {
    if (target == null || target.isIgnored() || outcome == null || outcome.isIgnored()) {
      return false;
    }
    if (!storeOutcome(target, outcome, eventAt)) {
      return false;
    }
    if (target.subscription() == null) {
      // The preference route's watermark. Outside the status write's catch on purpose: a
      // watermark that cannot be written propagates, so the webhook answers 500 and the provider
      // redelivers. On the row route the watermark is a column of the same row write.
      recordSubscriptionEventAt(target.clientId(), eventAt);
    }
    return true;
  }

  private boolean storeOutcome(LifecycleTarget target, SubscriptionEventOutcome outcome,
      Instant eventAt) {
    try {
      if (target.subscription() != null) {
        subscriptionService.applyLifecycleOutcome(target.subscription(), outcome, eventAt);
        return true;
      }
      Client client = OBDal.getInstance().get(Client.class, target.clientId());
      if (client == null) {
        return false;
      }
      setPreference(SUBSCRIPTION_STATUS_ATTRIBUTE, outcome.status().name(), client);
      setPreference(SUBSCRIPTION_DUE_AT_ATTRIBUTE,
          outcome.dueAt() == null ? "" : outcome.dueAt().toString(), client);
      return true;
    } catch (RuntimeException e) {
      log.error("Could not update subscription state for client {}", target.clientId(), e);
      return false;
    }
  }

  /**
   * Tenant-level form of {@link #applySubscriptionEvent}: stores a status and grace anchor for the
   * tenant's own subscription, with no Stripe subscription id to check and no watermark. Routed
   * exactly like a webhook event resolved through the tenant ({@link #targetForTenant}): the open
   * row, else the preference projection of a tenant that never had a row; a tenant whose only rows
   * are closed stores nothing.
   *
   * <p>Not committed here.
   *
   * @param clientId environment client id
   * @param status subscription status to store
   * @param renewalDueAt grace anchor (end of the paid period), or null to clear it
   * @return true when the outcome was stored
   */
  public boolean updateSubscriptionStatus(String clientId,
      EnvironmentAccessPolicy.SubscriptionStatus status, Instant renewalDueAt) {
    if (StringUtils.isBlank(clientId) || status == null) {
      return false;
    }
    return applySubscriptionEvent(targetForTenant(clientId, null),
        SubscriptionEventOutcome.apply(status, renewalDueAt), null);
  }

  /**
   * Tenant-level form of {@link LifecycleTarget#storedState()}: the stored state a lifecycle event
   * for this tenant is decided against — its open row, else the preference projection of a tenant
   * that never had a row; empty for a blank id or a tenant whose only rows are closed.
   *
   * @param clientId environment client id
   * @return stored status, grace anchor and last applied event instant
   */
  public SubscriptionLifecycleApplier.StoredState readSubscriptionState(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      return SubscriptionLifecycleApplier.StoredState.NONE;
    }
    return targetForTenant(clientId, null).storedState();
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
        applyDevelopmentStatusToSubscription(clientId, subscriptionStatus, renewalDueAt);
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
   * Mirrors a development-tool status onto the open subscription row, which is what
   * {@link #resolve} reads for a tenant that has one; without this the tool would stop affecting
   * every tenant with a subscription. Statuses the row cannot hold are left to the preferences.
   */
  private void applyDevelopmentStatusToSubscription(String clientId,
      EnvironmentAccessPolicy.SubscriptionStatus status, Instant renewalDueAt) {
    if (status == EnvironmentAccessPolicy.SubscriptionStatus.CURRENT
        || status == EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE
        || status == EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED) {
      subscriptionService.applyLifecycleStatus(clientId, status, renewalDueAt);
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
      // One admin-mode span covers both cross-client writes, so they call the unwrapped writer.
      OBContext.setAdminMode();
      try {
        setPreferenceValue(ASSOCIATED_PRODUCTIVE_ATTRIBUTE, productiveClientId, demo);
        setPreferenceValue(ASSOCIATED_DEMO_ATTRIBUTE, demoClientId, productive);
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

  /**
   * Writes a lifecycle preference, in admin mode for the same reason {@link #readPreference} reads
   * in it: these are system flags, and the callers include the Stripe webhook, which is matched
   * before the authentication chain and has no user context of its own. Admin mode is what lets
   * the lookup and the save run there without depending on a context some earlier call leaked.
   */
  private void setPreference(String attribute, String value, Client client) {
    OBContext.setAdminMode();
    try {
      setPreferenceValue(attribute, value, client);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void setPreferenceValue(String attribute, String value, Client client) {
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

  /**
   * ETP-5047 — where one Stripe lifecycle event lands: an {@code ETGO_SUBSCRIPTION} row, the
   * preference projection of a tenant with no row, or nowhere (ignored, with the audit reason),
   * plus the stored state the applier decides against.
   */
  public static final class LifecycleTarget {
    private final String clientId;
    private final Subscription subscription;
    private final SubscriptionLifecycleApplier.StoredState storedState;
    private final String ignoreReason;

    private LifecycleTarget(String clientId, Subscription subscription,
        SubscriptionLifecycleApplier.StoredState storedState, String ignoreReason) {
      this.clientId = clientId;
      this.subscription = subscription;
      this.storedState = storedState;
      this.ignoreReason = ignoreReason;
    }

    static LifecycleTarget row(String clientId, Subscription subscription,
        SubscriptionLifecycleApplier.StoredState storedState) {
      return new LifecycleTarget(clientId, subscription, storedState, null);
    }

    static LifecycleTarget preferences(String clientId,
        SubscriptionLifecycleApplier.StoredState storedState) {
      return new LifecycleTarget(clientId, null, storedState, null);
    }

    static LifecycleTarget ignored(String clientId, String reason) {
      return new LifecycleTarget(clientId, null, SubscriptionLifecycleApplier.StoredState.NONE,
          reason);
    }

    /** The tenant the event belongs to. */
    public String clientId() {
      return clientId;
    }

    /** The row to write, or null for the preference route (and for an ignored target). */
    public Subscription subscription() {
      return subscription;
    }

    /** The stored projection the applier evaluates the event against. */
    public SubscriptionLifecycleApplier.StoredState storedState() {
      return storedState;
    }

    public boolean isIgnored() {
      return ignoreReason != null;
    }

    /** Why the event is not applied; recorded verbatim in {@code ETGO_BILLING_EVENT}. */
    public String ignoreReason() {
      return ignoreReason;
    }
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
