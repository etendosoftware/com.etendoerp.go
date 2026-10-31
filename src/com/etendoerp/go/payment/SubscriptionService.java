/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.time.Duration;
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
 * <p><b>"Open" is the core vocabulary of this class.</b> A subscription row is open when its
 * {@code END_DATE} is null and it is active; a plan change (ETP-5053) closes one row and inserts
 * the next, so a tenant accumulates a price history and at most one row is open at a time. The
 * database enforces that with the partial unique index {@code etgo_sub_open_envclient_uq}
 * ({@code isactive = 'Y' AND end_date IS NULL}), so this class never has to pick "the" row —
 * either there is one open or there is none.
 *
 * <p><b>A cancellation closes its row too</b> (ETP-5047), so "no open row" no longer means "never
 * subscribed". {@link #findLatest} is the question "what is this tenant's subscription state":
 * the open row, else the most recently closed one — which, with no open successor, can only be a
 * canceled subscription.
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

  /**
   * ETP-5053 — how far ahead of this server's clock a closing instant may be and still count as
   * "now" (provider clock skew); see {@link #requireNotFuture}.
   */
  static final Duration END_DATE_SKEW_TOLERANCE = Duration.ofMinutes(5);

  private static final String PARAM_CLIENT_ID = "environmentClientId";
  private static final String PARAM_CLIENT_IDS = "environmentClientIds";

  /**
   * Maximum number of ids bound into a single {@code in (:ids)} predicate. Oracle caps an
   * expression list at 1000 entries and every driver degrades on much larger lists, so the
   * caller's collection is chunked rather than passed through whole.
   */
  private static final int CHUNK_SIZE = 1000;

  /** The HQL alias and {@code where} every row query of this class starts with. */
  private static final String WHERE_SUB = "as sub where sub.";

  /** The {@code .id = :} fragment that binds an id property to a named parameter. */
  private static final String ID_EQUALS_PARAM = ".id = :";

  private static final String OPEN_ROW_PREDICATE =
      " and sub." + Subscription.PROPERTY_ENDDATE + " is null"
          + " and sub." + Subscription.PROPERTY_ACTIVE + " = true";

  private static final String CLOSED_ROW_PREDICATE =
      " and sub." + Subscription.PROPERTY_ENDDATE + " is not null"
          + " and sub." + Subscription.PROPERTY_ACTIVE + " = true";

  private static final String LATEST_CLOSED_ORDER =
      " order by sub." + Subscription.PROPERTY_ENDDATE + " desc, sub."
          + Subscription.PROPERTY_CREATIONDATE + " desc";

  private static final String PARAM_STRIPE_SUBSCRIPTION_ID = "stripeSubscriptionId";

  private final StripeSubscriptionPeriodReader periodReader;

  /** A service that reads a new row's billing period from the configured payment provider. */
  public SubscriptionService() {
    this(new StripeSubscriptionPeriodReader());
  }

  /**
   * A service over an injected provider period reader, for tests.
   *
   * @param periodReader reads a Stripe subscription's current billing period
   */
  SubscriptionService(StripeSubscriptionPeriodReader periodReader) {
    this.periodReader = periodReader;
  }

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
          WHERE_SUB + Subscription.PROPERTY_ENVIRONMENTCLIENT + ID_EQUALS_PARAM
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
   * ETP-5047 — returns the open subscription row that carries a Stripe subscription id.
   *
   * <p>{@code STRIPE_SUBSCRIPTION_ID} is deliberately <b>not unique</b>: a plan change (ETP-5053)
   * keeps the Stripe subscription and opens a new local row, so the closed predecessor and its
   * open successor share the id. Resolving to the open row ({@code END_DATE IS NULL}) is what
   * makes a lifecycle event land on the row that is current, never on history.
   *
   * @param stripeSubscriptionId {@code sub_...}; blank finds nothing
   * @return the open row naming that subscription, or {@link Optional#empty()}
   */
  public Optional<Subscription> findOpenByStripeSubscription(String stripeSubscriptionId) {
    if (StringUtils.isBlank(stripeSubscriptionId)) {
      return Optional.empty();
    }
    OBContext.setAdminMode(true);
    try {
      OBQuery<Subscription> query = OBDal.getInstance().createQuery(Subscription.class,
          WHERE_SUB + Subscription.PROPERTY_STRIPESUBSCRIPTION + " = :"
              + PARAM_STRIPE_SUBSCRIPTION_ID + OPEN_ROW_PREDICATE);
      query.setNamedParameter(PARAM_STRIPE_SUBSCRIPTION_ID,
          StringUtils.trimToEmpty(stripeSubscriptionId));
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setMaxResult(1);
      return Optional.ofNullable(query.uniqueResult());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-5047 — returns the tenant's open subscription, or, when it has none, its most recently
   * closed one.
   *
   * <p>A canceled subscription is closed ({@code END_DATE}), so "the open row" alone no longer
   * answers "what is this tenant's subscription state": a canceled tenant has no open row, yet it
   * must read as {@code canceled} (access {@code EXPIRED}, plan {@code free}) — never fall through
   * to the pre-subscription preference fallback, which exists only for tenants that never had a
   * row. A plan change (ETP-5053) always leaves an open successor, so the closed branch is reached
   * only after a cancellation.
   *
   * @param environmentClientId {@code AD_CLIENT_ID} of the tenant
   * @return the open row, else the latest closed row, else {@link Optional#empty()}
   */
  public Optional<Subscription> findLatest(String environmentClientId) {
    Optional<Subscription> open = findOpen(environmentClientId);
    if (open.isPresent() || StringUtils.isBlank(environmentClientId)) {
      return open;
    }
    OBContext.setAdminMode(true);
    try {
      OBQuery<Subscription> query = OBDal.getInstance().createQuery(Subscription.class,
          WHERE_SUB + Subscription.PROPERTY_ENVIRONMENTCLIENT + ID_EQUALS_PARAM
              + PARAM_CLIENT_ID + CLOSED_ROW_PREDICATE + LATEST_CLOSED_ORDER);
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
   * ETP-5047 — bulk form of {@link #findLatest}: the open subscription of each tenant, or its
   * most recently closed one when it has none. At most two queries per chunk, the second only for
   * the tenants the first did not answer.
   *
   * @param environmentClientIds tenant ids to look up; null, blank and duplicate entries are
   *     ignored
   * @return the latest subscription of every tenant that has one, keyed by {@code AD_CLIENT_ID}
   */
  public Map<String, Subscription> findLatestForClients(Collection<String> environmentClientIds) {
    Map<String, Subscription> byClientId = findOpenForClients(environmentClientIds);
    if (environmentClientIds == null) {
      return byClientId;
    }
    Set<String> missing = new LinkedHashSet<>();
    for (String id : environmentClientIds) {
      String trimmed = StringUtils.trimToNull(id);
      if (trimmed != null && !byClientId.containsKey(trimmed)) {
        missing.add(trimmed);
      }
    }
    if (missing.isEmpty()) {
      return byClientId;
    }
    OBContext.setAdminMode(true);
    try {
      for (List<String> chunk : chunks(missing)) {
        OBQuery<Subscription> query = OBDal.getInstance().createQuery(Subscription.class,
            WHERE_SUB + Subscription.PROPERTY_ENVIRONMENTCLIENT + ".id in (:"
                + PARAM_CLIENT_IDS + ")" + CLOSED_ROW_PREDICATE + LATEST_CLOSED_ORDER);
        query.setNamedParameter(PARAM_CLIENT_IDS, chunk);
        query.setFilterOnReadableClients(false);
        query.setFilterOnReadableOrganization(false);
        // Ordered latest first, so the first row seen per tenant is the one to keep.
        for (Subscription subscription : query.list()) {
          Client tenant = subscription.getEnvironmentClient();
          if (tenant != null) {
            byClientId.putIfAbsent(tenant.getId(), subscription);
          }
        }
      }
      return byClientId;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Whether the tenant has ANY subscription row: open or closed, active or not.
   *
   * <p>This is the "is this tenant on the row model at all" test — the same one R37's retirement
   * branch uses. A closed or canceled row still means the tenant's plan is decided by its rows, so
   * the paid upgrade must record a new payment as a row for it even while the environment's
   * subscription model is off ({@link SubscriptionModelSwitch}). Deliberately not
   * {@link #findOpen}: that would miss a tenant whose only row is closed.
   *
   * @param environmentClientId {@code AD_CLIENT_ID} of the tenant, may be null or blank
   * @return {@code true} when at least one row names the tenant
   */
  public boolean hasAnySubscription(String environmentClientId) {
    if (StringUtils.isBlank(environmentClientId)) {
      return false;
    }
    OBContext.setAdminMode(true);
    try {
      OBQuery<Subscription> query = OBDal.getInstance().createQuery(Subscription.class,
          WHERE_SUB + Subscription.PROPERTY_ENVIRONMENTCLIENT + ID_EQUALS_PARAM
              + PARAM_CLIENT_ID);
      query.setNamedParameter(PARAM_CLIENT_ID, StringUtils.trimToEmpty(environmentClientId));
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setFilterOnActive(false);
      query.setMaxResult(1);
      return query.uniqueResult() != null;
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
        WHERE_SUB + Subscription.PROPERTY_ENVIRONMENTCLIENT + ".id in (:"
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
   * idempotent instead of turning it into a constraint violation. <b>Except a canceled one</b>
   * (ETP-5047): a tenant buying again after a cancellation gets a fresh row, and the canceled one
   * is closed. A cancellation normally closes its row already (see
   * {@link #applyLifecycleOutcome}); this covers the case where that event never arrived.
   *
   * <p>The row opens without a billing period. The period is read from the provider afterwards, by
   * {@link #fillPeriodFromProviderAfterCommit}, once the onboarding has committed (ETP-5047).
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
   * <p><b>Written as system, whoever calls.</b> The row is System-owned (client {@code 0}), and
   * the paid onboarding calls this after it has switched the thread to the new tenant.
   * {@code SecurityChecker.checkWriteAccess} requires a client-enabled row's client to equal the
   * <em>current</em> client, and {@code setAdminMode(true)} keeps that check on, so from the
   * tenant's context the save is refused — and the refusal also marks the whole request for
   * rollback. {@link SystemContext} makes client {@code 0} current for the write and hands the
   * caller its own context back. The row is still flushed and committed by the caller's
   * transaction, exactly as before.
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
    return SystemContext.call("opening a subscription", () -> openAsSystem(environmentClientId,
        plan, account, stripeCustomerId, stripeSubscriptionId, chargedPriceId));
  }

  private Subscription openAsSystem(String environmentClientId, Plan plan, Account account,
      String stripeCustomerId, String stripeSubscriptionId, String chargedPriceId) {
    Optional<Subscription> existing = findOpen(environmentClientId);
    if (existing.isPresent() && !isCanceled(existing.get())) {
      log.info("Tenant {} already has an open subscription; leaving its price snapshot intact",
          environmentClientId);
      return existing.get();
    }
    if (existing.isPresent()) {
      // ETP-5047 — a re-subscription. The open row is a canceled subscription whose close never
      // arrived (a lost customer.subscription.deleted); it is history now, so it is closed and a
      // fresh row opened. The close must reach the database before the new row's insert does:
      // Hibernate runs inserts before updates at flush, and the partial unique index
      // etgo_sub_open_envclient_uq would reject the new open row while the old one still reads as
      // open. So that one row is closed by a targeted statement now, not by a session flush — a
      // flush here would write the caller's whole pending onboarding as system.
      closeInDatabase(existing.get());
      log.info("Tenant {} re-subscribed; closed its canceled subscription {}",
          environmentClientId, existing.get().getId());
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
    saveOrEvict(subscription);
    log.info("Opened subscription for tenant {} on plan '{}'", environmentClientId,
        plan.getSearchKey());
    return subscription;
  }

  /**
   * ETP-5053 — rolls a tenant's open row over to another plan: closes it now and opens its
   * successor in the same transaction, so the tenant keeps a price history and at most one row is
   * ever open ({@code etgo_sub_open_envclient_uq}).
   *
   * <p><b>What the successor inherits.</b> Everything that describes the same provider
   * subscription — tenant, account, Stripe customer and subscription ids, status, billing period,
   * grace anchor, event watermark, cancel-at-period-end flag — plus the <b>quota-year anchor</b>
   * the caller decided ({@code QUOTA_YEAR_START}): the predecessor's for an upgrade or a
   * downgrade, so usage already counted this year carries over against the new limit; the new
   * provider period start for a billing-interval change, where the provider restarted the cycle.
   * The successor starts a fresh price snapshot from the price the item is now on, and clears
   * {@code PENDING_PLAN_ID} / {@code PENDING_EFFECTIVE_DATE}: whatever was pending has either
   * just applied or was superseded by this change.
   *
   * <p><b>Order of writes.</b> The predecessor is closed by a targeted statement before the
   * successor is saved, for the reason {@link #closeInDatabase} gives: Hibernate flushes inserts
   * before updates, and the partial unique index would reject the new open row while the old one
   * still reads as open. {@code END_DATE} is "now" — past tense, never the future (§5.13).
   *
   * <p>Written as system, like {@link #openSubscription}: the rows are client-{@code 0} rows about
   * a tenant. Not committed; the caller (the lifecycle webhook, or the reconciliation) commits it
   * with the event that caused it.
   *
   * @param open the tenant's open row, never null
   * @param target the plan the provider subscription is now on, never null
   * @param chargedPriceId the Stripe price the item is on, may be null (then the plan's)
   * @param quotaYearStart the anchor of the successor's quota year, may be null (then its start)
   * @return the open successor row
   */
  public Subscription rollToPlan(Subscription open, Plan target, String chargedPriceId,
      Date quotaYearStart) {
    if (open == null || target == null) {
      throw new IllegalArgumentException("A plan change needs the open row and the target plan");
    }
    return SystemContext.call("rolling a subscription over to another plan",
        () -> rollAsSystem(open, target, chargedPriceId, quotaYearStart));
  }

  private Subscription rollAsSystem(Subscription open, Plan target, String chargedPriceId,
      Date quotaYearStart) {
    closeInDatabase(open);
    Subscription successor = OBProvider.getInstance().get(Subscription.class);
    successor.setClient(OBDal.getInstance().get(Client.class, ZERO_ID));
    successor.setOrganization(OBDal.getInstance().get(Organization.class, ZERO_ID));
    successor.setEnvironmentClient(open.getEnvironmentClient());
    successor.setEtendoGoAccount(open.getEtendoGoAccount());
    successor.setPlan(target);
    successor.setSubscriptionStatus(open.getSubscriptionStatus());
    Date now = new Date();
    successor.setStartDate(now);
    successor.setEndDate(null);
    successor.setQuotaYearStart(quotaYearStart != null && quotaYearStart.after(now) ? now
        : quotaYearStart);
    successor.setCurrentPeriodStart(open.getCurrentPeriodStart());
    successor.setCurrentPeriodEnd(open.getCurrentPeriodEnd());
    successor.setGraceAnchor(open.getGraceAnchor());
    successor.setLastEventAt(open.getLastEventAt());
    successor.setCancelAtPeriodEnd(open.isCancelAtPeriodEnd());
    successor.setStripeCustomer(open.getStripeCustomer());
    successor.setStripeSubscription(open.getStripeSubscription());
    successor.setPendingPlan(null);
    successor.setPendingEffectiveDate(null);
    String planPriceId = StringUtils.trimToNull(target.getProviderPriceID());
    String charged = StringUtils.defaultIfBlank(StringUtils.trimToNull(chargedPriceId),
        planPriceId);
    successor.setProviderPriceID(charged);
    if (StringUtils.equals(charged, planPriceId)) {
      successor.setSnapshotAmount(target.getDisplayPrice());
      successor.setSnapshotCurrency(StringUtils.trimToNull(target.getCurrencyCode()));
    }
    saveOrEvict(successor);
    log.info("Rolled subscription {} of tenant {} over to plan '{}' as {} (quota year anchored on"
        + " {})", open.getId(), open.getEnvironmentClient() == null ? null
            : open.getEnvironmentClient().getId(), target.getSearchKey(), successor.getId(),
        successor.getQuotaYearStart());
    return successor;
  }

  /**
   * ETP-5053 — records a scheduled downgrade on the open row: the plan it moves to and when
   * ({@code PENDING_PLAN_ID}, {@code PENDING_EFFECTIVE_DATE}). The provider holds the schedule;
   * these columns exist so the Classic window, the account page and the reconciliation see the
   * pending change without calling Stripe. A future intention lives here and only here — never
   * on {@code END_DATE} (§5.13).
   *
   * <p>Written as system (the row is a client-{@code 0} row) and not committed: the caller
   * commits it with the provider call that scheduled the change.
   *
   * @param open the tenant's open row, never null
   * @param target the plan the subscription moves to at {@code effectiveAt}, never null
   * @param effectiveAt the end of the current billing period, never null
   */
  public void writePendingChange(Subscription open, Plan target, Date effectiveAt) {
    if (open == null || target == null || effectiveAt == null) {
      throw new IllegalArgumentException(
          "A pending plan change needs the open row, the target plan and its effective date");
    }
    SystemContext.run("recording a pending plan change", () -> {
      open.setPendingPlan(target);
      open.setPendingEffectiveDate(new Date(effectiveAt.getTime()));
      saveOrEvict(open);
      log.info("Subscription {} will move to plan '{}' on {}", open.getId(),
          target.getSearchKey(), effectiveAt);
    });
  }

  /**
   * ETP-5053 — clears a pending change: the schedule was released (an upgrade superseded the
   * downgrade) or the downgrade applied and the row rolled over. A row with nothing pending is
   * left untouched.
   *
   * @param open the tenant's open row, never null
   * @return true when something was cleared
   */
  public boolean clearPendingChange(Subscription open) {
    if (open == null || (open.getPendingPlan() == null && open.getPendingEffectiveDate() == null)) {
      return false;
    }
    SystemContext.run("clearing a pending plan change", () -> {
      open.setPendingPlan(null);
      open.setPendingEffectiveDate(null);
      saveOrEvict(open);
      log.info("Pending plan change of subscription {} cleared", open.getId());
    });
    return true;
  }

  /**
   * ETP-5047 — fills the billing period of the tenant's open row from the payment provider, when
   * the row names a Stripe subscription and has no period yet.
   *
   * <p><b>Why.</b> The first {@code invoice.paid} and {@code customer.subscription.updated} of a new
   * purchase arrive before onboarding has opened the row; they are recorded as ignored
   * ("unresolved subscription") and Stripe never redelivers them, so without this read the row
   * would stay periodless until its first renewal, about a month later.
   *
   * <p><b>Two short transactions, and no connection held across the network.</b> The open row is
   * read first; when the provider has to be asked, that read transaction is committed and the
   * thread's DAL session closed <em>before</em> the call, so no pooled connection sits idle in a
   * transaction while Stripe answers (up to the reader's connect and read timeouts, plus DNS).
   * Only the row id and the Stripe subscription id cross the call. The write then runs in a fresh
   * session that the DAL opens on demand, and is left uncommitted: the caller commits it on
   * {@code true} and rolls it back on a throw. The early exits (no open row, no Stripe id, a period
   * already set) ask the provider nothing and leave the read transaction to the caller.
   *
   * <p><b>So it must be called with nothing pending in the thread's DAL session</b> — hence
   * "AfterCommit" in the name: closing the session commits whatever the caller had flushed or
   * pending in it. The paid onboarding calls it once its commit and the checkout bookkeeping that
   * follows have each committed, where the session is empty. The precondition is not checked at
   * runtime on purpose: Hibernate's {@code Session.isDirty()} runs the flush-time dirty check,
   * which fires {@code OBInterceptor.onFlushDirty} (audit stamping and the DAL write-access check)
   * on whatever is pending, and still cannot see work that was flushed but not committed — so it
   * would be neither cheap nor reliable. The subscription row is already committed either way, so
   * nothing here can undo it.
   *
   * <p><b>Never overwrites a period.</b> A lifecycle event that resolved once the row existed may
   * have written one, before this call or while the provider was answering, and the event's period
   * wins. So the row is skipped when it already has either boundary, and the write is a single
   * conditional {@code UPDATE} guarded by both boundaries being null — atomic against a concurrent
   * webhook, which a read-then-save would not be. Only the period and the audit columns are
   * written: never {@code STATUS}, {@code GRACE_ANCHOR} or the {@code LAST_EVENT_AT} watermark,
   * which belong to the lifecycle events.
   *
   * <p>Best-effort on the provider side: a provider failure is logged by
   * {@link StripeSubscriptionPeriodReader} and answers {@code false}.
   *
   * @param environmentClientId {@code AD_CLIENT_ID} of the tenant whose row was just opened
   * @return true when a period was written and needs committing; false when nothing was written
   */
  public boolean fillPeriodFromProviderAfterCommit(String environmentClientId) {
    Optional<Subscription> open = findOpen(environmentClientId);
    if (open.isEmpty()) {
      return false;
    }
    Subscription subscription = open.get();
    String subscriptionRowId = subscription.getId();
    String stripeSubscriptionId = StringUtils.trimToNull(subscription.getStripeSubscription());
    if (stripeSubscriptionId == null) {
      // A payment-mode checkout, or a provider subscription id that was never recorded: there is
      // nothing to read the period of.
      log.info("Subscription {} of tenant {} names no Stripe subscription; no billing period to "
          + "read", subscriptionRowId, environmentClientId);
      return false;
    }
    if (subscription.getCurrentPeriodStart() != null
        || subscription.getCurrentPeriodEnd() != null) {
      return false;
    }
    // Ends the read transaction and hands the connection back to the pool before the network
    // call; the row entity is detached from here on, and only its two ids are used.
    OBDal.getInstance().commitAndClose();
    SubscriptionLifecycleApplier.BillingPeriod period =
        periodReader.read(environmentClientId, stripeSubscriptionId);
    if (period == null) {
      return false;
    }
    boolean written = SystemContext.call("filling a subscription billing period",
        () -> writePeriodIfMissing(subscriptionRowId, period));
    if (written) {
      log.info("Subscription {} of tenant {} got its billing period from the provider",
          subscriptionRowId, environmentClientId);
    } else {
      log.info("Subscription {} of tenant {} got a billing period from a lifecycle event first; "
          + "the provider's was not written", subscriptionRowId, environmentClientId);
    }
    return written;
  }

  /**
   * ETP-5047 — the conditional write behind {@link #fillPeriodFromProviderAfterCommit}: one
   * {@code UPDATE} of that row alone, applied only while both period boundaries are still null,
   * stamping {@code UPDATED}/{@code UPDATEDBY} as the DAL interceptor would. Like
   * {@link #closeInDatabase}, an HQL bulk update bypasses the DAL entity events. It runs in a
   * session that has not loaded the row, so there is no in-session copy to refresh; a later read
   * gets what the database holds.
   *
   * @param subscriptionRowId {@code ETGO_SUBSCRIPTION_ID} of the row to fill
   * @return true when the row was updated
   */
  private static boolean writePeriodIfMissing(String subscriptionRowId,
      SubscriptionLifecycleApplier.BillingPeriod period) {
    int updated = OBDal.getInstance().getSession()
        .createQuery("update " + Subscription.ENTITY_NAME
            + " set " + Subscription.PROPERTY_CURRENTPERIODSTART + " = :periodStart, "
            + Subscription.PROPERTY_CURRENTPERIODEND + " = :periodEnd, "
            + Subscription.PROPERTY_UPDATED + " = :updated, "
            + Subscription.PROPERTY_UPDATEDBY + " = :updatedBy"
            + " where " + Subscription.PROPERTY_ID + " = :id"
            + " and " + Subscription.PROPERTY_CURRENTPERIODSTART + " is null"
            + " and " + Subscription.PROPERTY_CURRENTPERIODEND + " is null")
        .setParameter("periodStart", Date.from(period.start()))
        .setParameter("periodEnd", Date.from(period.end()))
        .setParameter("updated", new Date())
        .setParameter("updatedBy", OBContext.getOBContext().getUser())
        .setParameter("id", subscriptionRowId)
        .executeUpdate();
    return updated == 1;
  }

  /**
   * Saves a new row, and takes it back out of the session when the save is refused.
   *
   * <p>A save refused inside Hibernate's save event leaves the object in the persistence context
   * with an id but no database state. The caller's catch then thinks the failure is contained, but
   * the next flush of the same transaction — in the paid onboarding, the organization setup —
   * issues an {@code UPDATE} for a row that was never inserted and fails with a
   * {@code StaleStateException}, taking the whole onboarding down with it. Evicting the refused
   * row keeps the failure where it happened.
   *
   * @param subscription the new row
   */
  private static void saveOrEvict(Subscription subscription) {
    try {
      OBDal.getInstance().save(subscription);
    } catch (RuntimeException e) {
      try {
        OBDal.getInstance().getSession().evict(subscription);
      } catch (RuntimeException evictFailure) {
        log.error("Could not evict the refused subscription row from the session", evictFailure);
      }
      throw e;
    }
  }

  /**
   * Applies a Stripe lifecycle outcome to the tenant's open subscription row.
   *
   * <p>Used by the development lifecycle tool
   * ({@link TenantEnvironmentLifecycleService#updateDevelopmentState}). The Stripe webhooks go
   * through {@link #applyLifecycleOutcome} on the row they resolved instead.
   *
   * <ul>
   *   <li>{@code STATUS}: {@code CURRENT → active}, {@code PAST_DUE → past_due},
   *       {@code EXPIRED → canceled}. This method never closes the row; the webhook uses
   *       {@link #applyLifecycleOutcome}, which does on a terminating event. It stays for the
   *       development lifecycle tool, which must be able to flip a tenant back.</li>
   *   <li>{@code GRACE_ANCHOR}: the outcome's grace anchor — for {@code PAST_DUE} the end of
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
      writeStatus(open.get(), storedStatus, graceAnchor);
      OBDal.getInstance().save(open.get());
      log.info("Subscription of tenant {} moved to '{}' by a lifecycle event",
          environmentClientId, storedStatus);
      return true;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-5047 — applies a Stripe lifecycle outcome to one known subscription row: the row the
   * webhook resolved by {@code STRIPE_SUBSCRIPTION_ID} (or through the checkout request), rather
   * than "whichever row is open for the tenant".
   *
   * <p>Writes {@code STATUS} and {@code GRACE_ANCHOR} exactly like {@link #applyLifecycleStatus};
   * the provider billing period into {@code CURRENT_PERIOD_START/END} when the event reports one
   * (an event that reports none leaves the stored period alone); the event's {@code created}
   * instant into {@code LAST_EVENT_AT}, the ordering watermark; and closes the row
   * ({@code END_DATE}) when the outcome terminates the subscription
   * ({@code customer.subscription.deleted}, or an update to Stripe's terminal {@code canceled}).
   * A closed canceled row still answers for the tenant — {@link #findLatest} — so the access
   * policy keeps reading it as {@code EXPIRED}, and the next purchase opens a fresh row.
   *
   * <p>Does not commit; the webhook commits it with the event's ledger row.
   *
   * @param subscription the row to update, never null
   * @param outcome an applied outcome ({@code CURRENT}, {@code PAST_DUE} or {@code EXPIRED})
   * @param eventAt the event's provider {@code created} instant; null leaves the watermark as is
   * @throws IllegalArgumentException for a status no lifecycle event produces
   */
  public void applyLifecycleOutcome(Subscription subscription, SubscriptionEventOutcome outcome,
      Instant eventAt) {
    String storedStatus = storedStatusOf(outcome.status());
    OBContext.setAdminMode(true);
    try {
      writeStatus(subscription, storedStatus, outcome.dueAt());
      writePeriod(subscription, outcome);
      writeCancelAtPeriodEnd(subscription, outcome.cancelAtPeriodEnd());
      writeEventWatermark(subscription, eventAt);
      if (outcome.closesSubscription() && subscription.getEndDate() == null) {
        // END_DATE is the provider's end instant, never before START_DATE, "now" when none.
        subscription.setEndDate(closingDateOf(subscription, outcome.endedAt()));
      }
      OBDal.getInstance().save(subscription);
      log.info("Subscription {} moved to '{}'{} by a lifecycle event", subscription.getId(),
          storedStatus, subscription.getEndDate() == null ? "" : " and was closed");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-5047 — {@code STATUS} and the grace anchor. The anchor has its own column,
   * {@code GRACE_ANCHOR}; before ETP-5047 it lived in {@code CURRENT_PERIOD_END}, which now holds
   * the provider billing period only. {@code null} clears the anchor.
   */
  private static void writeStatus(Subscription subscription, String storedStatus,
      Instant graceAnchor) {
    subscription.setSubscriptionStatus(storedStatus);
    subscription.setGraceAnchor(graceAnchor == null ? null : Date.from(graceAnchor));
  }

  /**
   * ETP-5047 — the provider billing period, written as a pair so {@code ETGO_SUB_PERIOD_CHK}
   * ({@code END >= START}) always holds; {@link SubscriptionEventOutcome#withPeriod} only carries
   * a whole, ordered period.
   */
  private static void writePeriod(Subscription subscription, SubscriptionEventOutcome outcome) {
    if (outcome.periodStart() == null || outcome.periodEnd() == null) {
      return;
    }
    subscription.setCurrentPeriodStart(Date.from(outcome.periodStart()));
    subscription.setCurrentPeriodEnd(Date.from(outcome.periodEnd()));
  }

  /**
   * ETP-5051 (O-1b) — the provider's {@code cancel_at_period_end}, display-only: it feeds the
   * environment list's {@code renewalAt} and nothing else — no access, grace or status decision
   * reads it. {@code null} (the event does not report it) leaves the stored flag alone.
   *
   * @param subscription the row to update
   * @param cancel the provider flag; {@link CancelAtPeriodEnd#NOT_REPORTED} (or null) writes nothing
   * @return true when the stored flag changed
   */
  public static boolean writeCancelAtPeriodEnd(Subscription subscription,
      CancelAtPeriodEnd cancel) {
    if (cancel == null || cancel.leavesUnchanged(subscription.isCancelAtPeriodEnd())) {
      return false;
    }
    subscription.setCancelAtPeriodEnd(cancel.isSet());
    return true;
  }

  /**
   * ETP-5047 — the ordering watermark on the row. It only moves forward: the applier already
   * ignores an older event as stale, and an event from the same second must not move it back.
   */
  private static void writeEventWatermark(Subscription subscription, Instant eventAt) {
    if (eventAt == null) {
      return;
    }
    Date stored = subscription.getLastEventAt();
    if (stored == null || stored.toInstant().isBefore(eventAt)) {
      subscription.setLastEventAt(Date.from(eventAt));
    }
  }

  /**
   * ETP-5047 — the grace anchor the access policy counts the grace days from.
   *
   * <p>{@code GRACE_ANCHOR} when set. Otherwise, for a {@code past_due} row in the pre-ETP-5047
   * shape — anchor in {@code CURRENT_PERIOD_END}, {@code CURRENT_PERIOD_START} null, as the old
   * lifecycle write and the R37 backfill left it — {@code CURRENT_PERIOD_END}. Without that
   * fallback every such tenant would read as past due with no anchor, which the policy treats as
   * zero grace: blocked on deploy. The new write never produces that shape (a period is always
   * written as a pair, a past-due outcome always carries an anchor), so the fallback only ever
   * sees old data, and the first lifecycle event moves the row off it.
   *
   * @param subscription a subscription row, may be null
   * @return the anchor, or null when the row has none
   */
  public static Instant graceAnchorOf(Subscription subscription) {
    if (subscription == null) {
      return null;
    }
    if (subscription.getGraceAnchor() != null) {
      return subscription.getGraceAnchor().toInstant();
    }
    boolean legacyShape = STATUS_PAST_DUE.equalsIgnoreCase(
        StringUtils.trimToEmpty(effectiveStatusOf(subscription)))
        && subscription.getCurrentPeriodStart() == null
        && subscription.getCurrentPeriodEnd() != null;
    return legacyShape ? subscription.getCurrentPeriodEnd().toInstant() : null;
  }

  /**
   * ETP-5047 — the provider {@code created} instant of the last lifecycle event applied to this
   * row, or null.
   *
   * @param subscription a subscription row, may be null
   * @return the watermark, or null
   */
  public static Instant lastEventAtOf(Subscription subscription) {
    return subscription == null || subscription.getLastEventAt() == null ? null
        : subscription.getLastEventAt().toInstant();
  }

  private static Date closingDateOf(Subscription subscription, Instant endedAt) {
    Date now = new Date();
    Date end = endedAt == null ? now : Date.from(endedAt);
    requireNotFuture(subscription, end, now);
    Date start = subscription.getStartDate();
    return start != null && end.before(start) ? start : end;
  }

  /**
   * ETP-5053 — {@code END_DATE} records what happened, never what will happen
   * ({@code open-and-notable-topics.md} §5.13, option 1): every writer sets it to the moment the
   * row stopped being the tenant's current row, and a future intention lives only in
   * {@code PENDING_PLAN_ID} / {@code PENDING_EFFECTIVE_DATE}.
   *
   * <p>Why a guard and not a convention: "open" is {@code END_DATE IS NULL}, so a future-dated end
   * would close the row <em>immediately</em> — the tenant would read as canceled, and free, inside
   * a period it paid for — and nothing in the schema rejects that shape. A provider instant a few
   * seconds ahead of this server's clock is still "now"; {@link #END_DATE_SKEW_TOLERANCE} is the
   * allowance. Anything beyond it is a programming error or a misread payload, and the write is
   * refused rather than silently clamped so it is seen.
   *
   * @throws IllegalArgumentException when {@code end} is later than {@code now} plus the tolerance
   */
  static void requireNotFuture(Subscription subscription, Date end, Date now) {
    if (end.getTime() > now.getTime() + END_DATE_SKEW_TOLERANCE.toMillis()) {
      throw new IllegalArgumentException("END_DATE records what happened, never what will happen:"
          + " refused the future closing instant " + end.toInstant() + " for subscription "
          + (subscription == null ? null : subscription.getId()));
    }
  }

  /**
   * ETP-5047 — {@link #close} for an open row that must read as closed in the database
   * <em>before</em> the session flushes: one {@code UPDATE} of that row alone, setting what the
   * DAL would write for a {@code close(row, null)} — {@code END_DATE} ("now", never before
   * {@code START_DATE}), {@code UPDATED} and {@code UPDATEDBY} (the current user, as the DAL
   * interceptor stamps them). The session runs with {@code FlushMode.COMMIT}, so the statement
   * flushes nothing else; the caller's pending changes stay pending, in the caller's context.
   *
   * <p>The row is then refreshed from the database, so the in-session entity reads as closed and
   * a later flush has nothing of it left to write. The {@code END_DATE IS NULL} guard makes the
   * statement a no-op for a row something else closed in the meantime.
   *
   * <p>Being an HQL bulk update, the statement bypasses {@code OBInterceptor} and the DAL entity
   * events: nothing observes {@code Subscription} events today, but a future listener would not
   * fire on this close.
   *
   * @param subscription the open row to close
   */
  private static void closeInDatabase(Subscription subscription) {
    int closed = OBDal.getInstance().getSession()
        .createQuery("update " + Subscription.ENTITY_NAME
            + " set " + Subscription.PROPERTY_ENDDATE + " = :endDate, "
            + Subscription.PROPERTY_UPDATED + " = :updated, "
            + Subscription.PROPERTY_UPDATEDBY + " = :updatedBy"
            + " where " + Subscription.PROPERTY_ID + " = :id"
            + " and " + Subscription.PROPERTY_ENDDATE + " is null")
        .setParameter("endDate", closingDateOf(subscription, null))
        .setParameter("updated", new Date())
        .setParameter("updatedBy", OBContext.getOBContext().getUser())
        .setParameter("id", subscription.getId())
        .executeUpdate();
    if (closed == 0) {
      log.info("Subscription {} was no longer open when the re-subscription closed it",
          subscription.getId());
    }
    OBDal.getInstance().getSession().refresh(subscription);
  }

  private static boolean isCanceled(Subscription subscription) {
    return STATUS_CANCELED.equalsIgnoreCase(
        StringUtils.trimToEmpty(subscription.getSubscriptionStatus()));
  }

  /**
   * ETP-5047 — the status a row stands for: its {@code STATUS} while it is open, and
   * {@value #STATUS_CANCELED} once it is closed, whatever {@code STATUS} still says. A closed row
   * is history, not entitlement — a row a plan change (ETP-5053) superseded can still read
   * {@code active} — so every reader that may be handed a closed row by {@link #findLatest} must
   * go through here, never read {@code STATUS} directly.
   *
   * @param subscription a subscription row, may be null
   * @return the effective status, or null for a null row
   */
  public static String effectiveStatusOf(Subscription subscription) {
    if (subscription == null) {
      return null;
    }
    return subscription.getEndDate() != null ? STATUS_CANCELED
        : subscription.getSubscriptionStatus();
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
