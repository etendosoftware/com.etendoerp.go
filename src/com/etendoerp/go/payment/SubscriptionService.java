/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Reads and opens the per-tenant subscription rows of {@code ETGO_SUBSCRIPTION}, the record of
 * which tenant is on which plan, in which status, at which price.
 *
 * <p><b>"Open" is the whole vocabulary of this class.</b> A subscription row is open when its
 * {@code END_DATE} is null and it is active; a plan change (ETP-5053) closes one row and inserts
 * the next, so a tenant accumulates a price history and at most one row is open at a time. The
 * database enforces that with the partial unique index {@code etgo_sub_open_envclient_uq}
 * ({@code isactive = 'Y' AND end_date IS NULL}), so this class never has to pick "the" row —
 * either there is one open or there is none.
 *
 * <p>Rows live at {@code AD_CLIENT_ID = '0'} and name the tenant through
 * {@code ENVIRONMENT_CLIENT_ID}: they are client-{@code 0} rows <em>about</em> another client.
 * DAL row-level security therefore has nothing useful to filter on, so every query here disables
 * the readable-client and readable-organization filters explicitly and pins the tenant by named
 * parameter instead. Precedent: {@link TenantPlanService}'s preference lookup, which reads a
 * client-{@code 0} preference row about another tenant for exactly the same reason.
 *
 * <p>Each method runs in admin mode — which is what lets the NEO access check read a tenant's row
 * as a user whose role cannot read {@code ETGO_SUBSCRIPTION}, the same concern ETP-5488 fixed for
 * the lifecycle preferences — and never replaces the caller's {@link OBContext}. It used to open a
 * system context "when there was none", but {@code setAdminMode} had already installed an admin
 * context by then, so that branch never fired; it was removed. A caller with no context at all
 * (the Stripe webhook) installs and restores its own system context around the call.
 */
public class SubscriptionService {

  private static final Logger log = LogManager.getLogger(SubscriptionService.class);

  private static final String ZERO_ID = "0";

  /** The tenant is paying and current. */
  public static final String STATUS_ACTIVE = "active";
  /** The tenant is paying but an invoice failed; access is retained during the dunning window. */
  public static final String STATUS_PAST_DUE = "past_due";
  /** The subscription was terminated; the tenant is no longer entitled to the paid plan. */
  public static final String STATUS_CANCELED = "canceled";

  private static final String PARAM_CLIENT_ID = "environmentClientId";
  private static final String PARAM_CLIENT_IDS = "environmentClientIds";

  /**
   * Maximum number of ids bound into a single {@code in (:ids)} predicate. Oracle caps an
   * expression list at 1000 entries and every driver degrades on much larger lists, so the
   * caller's collection is chunked rather than passed through whole.
   */
  private static final int CHUNK_SIZE = 1000;

  private static final String OPEN_ROW_PREDICATE =
      " and sub." + Subscription.PROPERTY_ENDDATE + " is null"
          + " and sub." + Subscription.PROPERTY_ACTIVE + " = true";

  /**
   * Returns the tenant's open subscription, if it has one.
   *
   * @param environmentClientId {@code AD_CLIENT_ID} of the tenant
   * @return the open subscription row, or {@link Optional#empty()} when the tenant has none
   */
  public Optional<Subscription> findOpen(String environmentClientId) {
    if (StringUtils.isBlank(environmentClientId)) {
      return Optional.empty();
    }
    OBContext.setAdminMode(true);
    try {
      OBQuery<Subscription> query = OBDal.getInstance().createQuery(Subscription.class,
          "as sub where sub." + Subscription.PROPERTY_ENVIRONMENTCLIENT + ".id = :"
              + PARAM_CLIENT_ID + OPEN_ROW_PREDICATE);
      query.setNamedParameter(PARAM_CLIENT_ID, StringUtils.trimToEmpty(environmentClientId));
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setMaxResult(1);
      return Optional.ofNullable(query.uniqueResult());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Returns the open subscription of each of the given tenants, keyed by tenant id.
   *
   * <p><b>One query, not one per tenant.</b> The environment list sorts paid tenants first and
   * then renders each one's plan, so a per-tenant lookup is a query inside a comparator —
   * O(n log n) round trips for a single page render. Tenants without an open subscription are
   * simply absent from the returned map rather than present with a null value, so a caller that
   * asks for a missing key gets the same answer either way.
   *
   * @param environmentClientIds tenant ids to look up; null, blank and duplicate entries are
   *     ignored
   * @return the open subscription of every tenant that has one, keyed by {@code AD_CLIENT_ID}
   */
  public Map<String, Subscription> findOpenForClients(Collection<String> environmentClientIds) {
    Map<String, Subscription> byClientId = new LinkedHashMap<>();
    if (environmentClientIds == null || environmentClientIds.isEmpty()) {
      return byClientId;
    }
    Set<String> ids = new LinkedHashSet<>();
    for (String id : environmentClientIds) {
      if (StringUtils.isNotBlank(id)) {
        ids.add(StringUtils.trimToEmpty(id));
      }
    }
    if (ids.isEmpty()) {
      return byClientId;
    }
    OBContext.setAdminMode(true);
    try {
      for (List<String> chunk : chunks(ids)) {
        for (Subscription subscription : queryOpenForChunk(chunk)) {
          Client tenant = subscription.getEnvironmentClient();
          if (tenant != null) {
            byClientId.put(tenant.getId(), subscription);
          }
        }
      }
      return byClientId;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private List<Subscription> queryOpenForChunk(List<String> chunk) {
    OBQuery<Subscription> query = OBDal.getInstance().createQuery(Subscription.class,
        "as sub where sub." + Subscription.PROPERTY_ENVIRONMENTCLIENT + ".id in (:"
            + PARAM_CLIENT_IDS + ")" + OPEN_ROW_PREDICATE);
    query.setNamedParameter(PARAM_CLIENT_IDS, chunk);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    return query.list();
  }

  private static List<List<String>> chunks(Set<String> ids) {
    List<String> all = new ArrayList<>(ids);
    List<List<String>> chunked = new ArrayList<>();
    for (int from = 0; from < all.size(); from += CHUNK_SIZE) {
      chunked.add(all.subList(from, Math.min(from + CHUNK_SIZE, all.size())));
    }
    return chunked;
  }

  /**
   * Opens a subscription for a tenant that has just paid.
   *
   * <p><b>The price is snapshotted, not referenced.</b> {@code PROVIDER_PRICE_ID},
   * {@code SNAPSHOT_AMOUNT} and {@code SNAPSHOT_CURRENCY} are copied from the plan here and are
   * never rewritten afterwards, so re-pricing a plan changes what new buyers pay and leaves every
   * existing subscriber grandfathered on the price they bought. That mirrors Stripe, where a
   * Price object is immutable and an existing Subscription Item keeps pointing at the Price it was
   * created with — but it makes the guarantee locally visible instead of something the reader has
   * to trust the provider for. Re-pricing an existing subscriber is a plan <em>change</em>
   * (ETP-5053): it closes this row and inserts a new one.
   *
   * <p>Does not commit. It is called from inside the onboarding transaction, so the row commits
   * with the tenant it belongs to — a subscription that survived a rolled-back environment would
   * describe a tenant that does not exist.
   *
   * <p>An already-open subscription is returned untouched rather than re-snapshotted. The partial
   * unique index would reject a second open row anyway; returning the existing one keeps a
   * re-entered onboarding (the {@code ?checkout=success} URL stays live for the whole run)
   * idempotent instead of turning it into a constraint violation.
   *
   * @param environmentClientId {@code AD_CLIENT_ID} of the tenant the subscription is for
   * @param plan the purchased plan, whose price is snapshotted onto the row
   * @param account the Etendo Go account that paid, may be null
   * @param stripeCustomerId {@code cus_...}, may be null
   * @param stripeSubscriptionId {@code sub_...}, may be null
   * @return the open subscription for the tenant, created or pre-existing
   */
  public Subscription openSubscription(String environmentClientId, Plan plan, Account account,
      String stripeCustomerId, String stripeSubscriptionId) {
    return openSubscription(environmentClientId, plan, account, stripeCustomerId,
        stripeSubscriptionId, null);
  }

  /**
   * Opens a subscription for a tenant that has just paid, snapshotting the price actually charged.
   *
   * <p>The checkout request records the Stripe price it charged, which is not always the plan's:
   * under the legacy price fallback the grandfathered plan has no price of its own and the buyer
   * was charged the configured legacy price. That charged price id is snapshotted into
   * {@code PROVIDER_PRICE_ID}; the amount and currency are copied from the plan only when the plan
   * still names that same price, because a plan's display amount describes the plan's price and
   * nothing else. A null charged price falls back to the plan's price, as before.
   *
   * @param environmentClientId {@code AD_CLIENT_ID} of the tenant the subscription is for
   * @param plan the purchased plan
   * @param account the Etendo Go account that paid, may be null
   * @param stripeCustomerId {@code cus_...}, may be null
   * @param stripeSubscriptionId {@code sub_...}, may be null
   * @param chargedPriceId the Stripe price id stored on the checkout request, may be null
   * @return the open subscription for the tenant, created or pre-existing
   */
  public Subscription openSubscription(String environmentClientId, Plan plan, Account account,
      String stripeCustomerId, String stripeSubscriptionId, String chargedPriceId) {
    if (StringUtils.isBlank(environmentClientId) || plan == null) {
      throw new IllegalArgumentException(
          "A subscription needs both a tenant and a plan to be opened");
    }
    OBContext.setAdminMode(true);
    try {
      Optional<Subscription> existing = findOpen(environmentClientId);
      if (existing.isPresent()) {
        log.info("Tenant {} already has an open subscription; leaving its price snapshot intact",
            environmentClientId);
        return existing.get();
      }
      Subscription subscription = OBProvider.getInstance().get(Subscription.class);
      subscription.setClient(OBDal.getInstance().get(Client.class, ZERO_ID));
      subscription.setOrganization(OBDal.getInstance().get(Organization.class, ZERO_ID));
      subscription.setEnvironmentClient(
          OBDal.getInstance().get(Client.class, StringUtils.trimToEmpty(environmentClientId)));
      subscription.setPlan(plan);
      subscription.setEtendoGoAccount(account);
      subscription.setSubscriptionStatus(STATUS_ACTIVE);
      subscription.setStartDate(new Date());
      subscription.setEndDate(null);
      subscription.setStripeCustomer(StringUtils.trimToNull(stripeCustomerId));
      subscription.setStripeSubscription(StringUtils.trimToNull(stripeSubscriptionId));
      String planPriceId = StringUtils.trimToNull(plan.getProviderPriceID());
      String charged = StringUtils.defaultIfBlank(StringUtils.trimToNull(chargedPriceId),
          planPriceId);
      subscription.setProviderPriceID(charged);
      if (StringUtils.equals(charged, planPriceId)) {
        subscription.setSnapshotAmount(plan.getDisplayPrice());
        subscription.setSnapshotCurrency(StringUtils.trimToNull(plan.getCurrencyCode()));
      }
      OBDal.getInstance().save(subscription);
      log.info("Opened subscription for tenant {} on plan '{}'", environmentClientId,
          plan.getSearchKey());
      return subscription;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Applies a Stripe lifecycle outcome to the tenant's open subscription row.
   *
   * <p>The lifecycle webhooks ({@code invoice.paid}, {@code invoice.payment_failed},
   * {@code customer.subscription.updated/deleted}) are interpreted by
   * {@link SubscriptionLifecycleApplier}; this is where their result lands when the tenant has an
   * {@code ETGO_SUBSCRIPTION} row, so the row stays the single answer to "is this tenant paying and
   * until when". A tenant with no open row keeps the older preference projection instead — see
   * {@link TenantEnvironmentLifecycleService#updateSubscriptionStatus}.
   *
   * <ul>
   *   <li>{@code STATUS}: {@code CURRENT → active}, {@code PAST_DUE → past_due},
   *       {@code EXPIRED → canceled}. {@code canceled} does not close the row ({@code END_DATE}
   *       stays null): closing a row is how a plan change opens its successor (ETP-5053), and a
   *       cancellation is not one.</li>
   *   <li>{@code CURRENT_PERIOD_END}: the outcome's grace anchor — for {@code PAST_DUE} the end of
   *       the period the customer already paid for, which is what the access policy counts the
   *       grace days from. {@code null} clears it, exactly as it clears the preference projection's
   *       due date, so both stores produce the same access decision.</li>
   * </ul>
   *
   * <p>Does not commit: the webhook handler commits it together with the event's ledger row, and
   * rolls both back on failure.
   *
   * @param environmentClientId {@code AD_CLIENT_ID} of the tenant
   * @param status the access-policy status the event maps to; {@code CURRENT}, {@code PAST_DUE} or
   *     {@code EXPIRED}
   * @param graceAnchor the end of the paid period, or null to clear it
   * @return true when an open row was found and updated; false when the tenant has none
   * @throws IllegalArgumentException for a status no lifecycle event produces
   */
  public boolean applyLifecycleStatus(String environmentClientId,
      EnvironmentAccessPolicy.SubscriptionStatus status, Instant graceAnchor) {
    String storedStatus = storedStatusOf(status);
    Optional<Subscription> open = findOpen(environmentClientId);
    if (open.isEmpty()) {
      return false;
    }
    OBContext.setAdminMode(true);
    try {
      Subscription subscription = open.get();
      subscription.setSubscriptionStatus(storedStatus);
      Date periodEnd = graceAnchor == null ? null : Date.from(graceAnchor);
      if (periodEnd != null && subscription.getCurrentPeriodStart() != null
          && periodEnd.before(subscription.getCurrentPeriodStart())) {
        // ETGO_SUB_PERIOD_CHK: a start after the anchor would reject the whole event. The start is
        // not written by anything today, so dropping it loses nothing the policy reads.
        subscription.setCurrentPeriodStart(null);
      }
      subscription.setCurrentPeriodEnd(periodEnd);
      OBDal.getInstance().save(subscription);
      log.info("Subscription of tenant {} moved to '{}' by a lifecycle event",
          environmentClientId, storedStatus);
      return true;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Maps an access-policy status onto the {@code ETGO_SUBSCRIPTION.STATUS} vocabulary. The
   * inverse of {@code TenantEnvironmentLifecycleService.subscriptionStatusOf}; the two must be
   * edited together with {@code ETGO_SUB_STATUS_CHK}.
   */
  static String storedStatusOf(EnvironmentAccessPolicy.SubscriptionStatus status) {
    if (status == EnvironmentAccessPolicy.SubscriptionStatus.CURRENT) {
      return STATUS_ACTIVE;
    }
    if (status == EnvironmentAccessPolicy.SubscriptionStatus.PAST_DUE) {
      return STATUS_PAST_DUE;
    }
    if (status == EnvironmentAccessPolicy.SubscriptionStatus.EXPIRED) {
      return STATUS_CANCELED;
    }
    throw new IllegalArgumentException("No subscription status is stored for " + status);
  }

}
