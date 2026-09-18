/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

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
 * <p>Following {@link CheckoutRequestStore}, each method makes sure it runs in admin mode, and
 * opens a system {@link OBContext} <em>only when there is none</em> — the webhook path is matched
 * before the authentication chain and has no context at all, while the environment-list and
 * onboarding callers already hold one that must survive the call unchanged.
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
    openSystemContextWhenAbsent();
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
    openSystemContextWhenAbsent();
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
    if (StringUtils.isBlank(environmentClientId) || plan == null) {
      throw new IllegalArgumentException(
          "A subscription needs both a tenant and a plan to be opened");
    }
    OBContext.setAdminMode(true);
    openSystemContextWhenAbsent();
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
      subscription.setProviderPriceID(StringUtils.trimToNull(plan.getProviderPriceID()));
      subscription.setSnapshotAmount(plan.getDisplayPrice());
      subscription.setSnapshotCurrency(StringUtils.trimToNull(plan.getCurrencyCode()));
      OBDal.getInstance().save(subscription);
      log.info("Opened subscription for tenant {} on plan '{}'", environmentClientId,
          plan.getSearchKey());
      return subscription;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Opens a system {@link OBContext} only when the calling thread has none.
   *
   * <p>The webhook and background paths reach this class with no context at all and need one to
   * query. The environment-list and onboarding paths arrive holding a context the rest of their
   * work depends on, and {@code OBContext.setOBContext(...)} has no stack of its own —
   * {@code restorePreviousMode()} pops the admin-mode stack and nothing else — so overwriting it
   * unconditionally would silently hand the caller a system context it never asked for, for the
   * remainder of the request.
   */
  private static void openSystemContextWhenAbsent() {
    if (OBContext.getOBContext() == null) {
      OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    }
  }
}
