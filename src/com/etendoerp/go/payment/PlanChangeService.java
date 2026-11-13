/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.PlanChange;
import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * ETP-5053 — moves a tenant between plans at any point of the billing period.
 *
 * <p><b>Upgrade, same interval</b> ({@link PlanChangeTiming#IMMEDIATE}): the subscription item is
 * updated to the target price, with {@code proration_behavior=always_invoice} — the commercial
 * choice this module makes: the difference is billed now on the saved payment method, so the next
 * invoice stays at the sticker price. {@link #previewChange} quotes that amount and the card
 * before the customer confirms, and the change is charged at the quoted proration instant.
 * {@code payment_behavior=pending_if_incomplete}: the plan changes only once that invoice is paid;
 * when the card is declined or the bank asks the customer to authenticate, the result is
 * {@link PlanChangeResult#paymentRequired()} with the provider's hosted invoice page, and the
 * provider drops the change after about 23 hours if it stays unpaid. {@code billing_cycle_anchor}
 * is never passed (PRD §8.2): it would move the customer's billing date forever. A
 * monthly-to-annual change takes the same call; in flexible billing mode (the provider's default
 * since API 2025-09-30) the billing date is kept and the new yearly period runs from it, and the
 * quota year follows the provider's period through the rollover. The provider's answer is applied at
 * once through the lifecycle path ({@link TenantEnvironmentLifecycleService#applySubscriptionEvent}
 * with a synthetic {@code customer.subscription.updated}), so the row is rolled over and the plan
 * is visible before the webhook arrives; the webhook then finds the price already matching and
 * rolls nothing.
 *
 * <p><b>Downgrade</b> ({@link PlanChangeTiming#PERIOD_END}): a Subscription Schedule holds the
 * target price in a phase that starts at the current period end, {@code proration_behavior=none};
 * the subscription keeps its plan until then, and the phase transition's
 * {@code customer.subscription.updated} rolls the row at the boundary. Locally only the pending
 * columns change. A second downgrade request replaces the schedule's target rather than creating
 * a second schedule; the same target again is a no-op that asks the provider nothing.
 *
 * <p><b>A downgrade is pending and the customer upgrades</b>: the schedule is released and the
 * pending columns cleared before the item is updated — one intention per subscription.
 *
 * <p><b>Refused, with a code</b>: no open row or no provider subscription, a plan key no plan
 * carries, a transition no active Allowed plan changes row offers, a subscription that is not
 * current (past due, canceled), a target plan with no provider price, a provider that is not
 * configured. A provider 4xx is {@code BILLING_PROVIDER_ERROR}; a transport failure propagates as
 * {@link StripeTransportException} for the caller to word.
 *
 * <p><b>A failed prorated charge changes nothing.</b> The update waits at the provider for its
 * invoice, the subscription stays current on its plan, and the invoice's
 * {@code invoice.payment_failed} is not a renewal failure ({@link SubscriptionLifecycleApplier}
 * ignores it). A downgrade that was pending is released before the upgrade is asked for, so it
 * stays released even when the upgrade is never paid.
 */
public class PlanChangeService {

  private static final Logger log = LogManager.getLogger(PlanChangeService.class);

  static final String PRORATION_UPGRADE = "always_invoice";
  static final String PRORATION_NONE = "none";
  static final String PAYMENT_PENDING_IF_INCOMPLETE = "pending_if_incomplete";
  private static final String SUBSCRIPTIONS = "/v1/subscriptions/";
  private static final String INVOICE_PREVIEW = "/v1/invoices/create_preview";
  private static final String SCHEDULES = "/v1/subscription_schedules";
  private static final String FIELD_SCHEDULE = "schedule";

  private final SubscriptionService subscriptionService;
  private final PlanCatalogService planCatalogService;
  private final PlanChangeCatalog planChangeCatalog;
  private final TenantEnvironmentLifecycleService lifecycleService;
  private final SubscriptionLifecycleApplier applier;
  private final StripeApiClient stripe;
  private final BooleanSupplier providerConfigured;
  private final Clock clock;

  /** Production wiring. */
  public PlanChangeService() {
    this(new SubscriptionService(), new PlanCatalogService(), new PlanChangeCatalog(),
        new TenantEnvironmentLifecycleService(), new HttpUrlConnectionStripeApiClient(),
        CheckoutConfiguration::isConfigured, Clock.systemUTC());
  }

  /**
   * Explicit wiring, for tests. The lifecycle applier is pure (no database, no clock), so it is
   * never a seam and is created here.
   */
  PlanChangeService(SubscriptionService subscriptionService, PlanCatalogService planCatalogService,
      PlanChangeCatalog planChangeCatalog, TenantEnvironmentLifecycleService lifecycleService,
      StripeApiClient stripe, BooleanSupplier providerConfigured, Clock clock) {
    this.subscriptionService = subscriptionService;
    this.planCatalogService = planCatalogService;
    this.planChangeCatalog = planChangeCatalog;
    this.lifecycleService = lifecycleService;
    this.applier = new SubscriptionLifecycleApplier();
    this.stripe = stripe;
    this.providerConfigured = providerConfigured;
    this.clock = clock;
  }

  /**
   * The transitions a tenant may ask for from its current plan: the active Allowed plan changes
   * rows of that plan whose target is active and sellable (carries a provider price).
   *
   * @param open the tenant's open row, may be null
   * @return the offered rows, never null; empty when there is no row, no plan or nothing allowed
   */
  public List<PlanChange> allowedChanges(Subscription open) {
    Plan from = fromPlanOf(open);
    if (from == null) {
      return List.of();
    }
    return planChangeCatalog.listAllowed(from).stream()
        .filter(change -> planCatalogService.hasProviderPrice(change.getTargetPlan()))
        .toList();
  }

  /**
   * Requests a plan change for a tenant.
   *
   * @param clientId {@code AD_CLIENT_ID} of the tenant
   * @param targetPlanKey the {@code VALUE} of the plan asked for
   * @return what happened: applied now, or scheduled for the period end
   * @throws PlanChangeRefusedException with one of its codes when the change is refused
   * @throws StripeTransportException when the provider did not answer
   */
  public PlanChangeResult requestChange(String clientId, String targetPlanKey)
      throws StripeTransportException {
    Subscription open = subscriptionService.findOpen(clientId).orElseThrow(
        () -> refused(PlanChangeRefusedException.NO_SUBSCRIPTION,
            "The environment has no open subscription"));
    return requestChange(open, targetPlanKey);
  }

  /**
   * Requests a plan change on a known open row (the Classic operator action resolves the row
   * itself).
   *
   * @param open the tenant's open row, may be null (then refused as {@code NO_SUBSCRIPTION})
   * @param targetPlanKey the {@code VALUE} of the plan asked for
   * @return what happened: applied now, or scheduled for the period end
   * @throws PlanChangeRefusedException with one of its codes when the change is refused
   * @throws StripeTransportException when the provider did not answer
   * @see #requestChange(String, String)
   */
  public PlanChangeResult requestChange(Subscription open, String targetPlanKey)
      throws StripeTransportException {
    return requestChange(open, targetPlanKey, null);
  }

  /**
   * Requests a plan change, charging an upgrade at the proration instant a
   * {@link #previewChange preview} quoted, so the customer pays exactly the amount shown.
   *
   * @param clientId {@code AD_CLIENT_ID} of the tenant
   * @param targetPlanKey the {@code VALUE} of the plan asked for
   * @param prorationDate epoch seconds of the preview; null, in the future or more than an hour
   *     old means now
   * @return what happened: applied now, waiting for its payment, or scheduled for the period end
   * @throws PlanChangeRefusedException with one of its codes when the change is refused
   * @throws StripeTransportException when the provider did not answer
   */
  public PlanChangeResult requestChange(String clientId, String targetPlanKey, Long prorationDate)
      throws StripeTransportException {
    Subscription open = subscriptionService.findOpen(clientId).orElseThrow(
        () -> refused(PlanChangeRefusedException.NO_SUBSCRIPTION,
            "The environment has no open subscription"));
    return requestChange(open, targetPlanKey, prorationDate);
  }

  private PlanChangeResult requestChange(Subscription open, String targetPlanKey,
      Long prorationDate) throws StripeTransportException {
    if (keepsCurrentPlan(open, targetPlanKey)) {
      return cancelPendingChange(open);
    }
    if (isAlreadyPending(open, targetPlanKey)) {
      // The same downgrade again (a double click, a retry): nothing to schedule. Checked before
      // the rules, which start from the pending plan and so never offer it to itself.
      log.info("Subscription {} already moves to plan '{}' on {}; nothing to schedule",
          open.getId(), StringUtils.trim(targetPlanKey), open.getPendingEffectiveDate());
      return new PlanChangeResult(PlanChangeTiming.PERIOD_END, open.getPendingPlan(),
          open.getPendingEffectiveDate().toInstant(), true);
    }
    Resolved change = resolve(open, targetPlanKey);
    Plan target = change.target();
    JSONObject live = readSubscription(change.subscriptionId());
    if (change.timing() == PlanChangeTiming.PERIOD_END) {
      return scheduleDowngrade(open, target, change.subscriptionId(), live);
    }
    return applyUpgrade(open, target, change.subscriptionId(), live,
        prorationInstant(prorationDate));
  }

  /**
   * What a plan change would do, read before the customer confirms it: for an upgrade the amount
   * the saved payment method is charged today and that payment method, for a downgrade the date it
   * takes effect. Refuses exactly what {@link #requestChange(String, String)} refuses, and changes
   * nothing, here or at the provider.
   *
   * @param clientId {@code AD_CLIENT_ID} of the tenant
   * @param targetPlanKey the {@code VALUE} of the plan asked for
   * @return the preview
   * @throws PlanChangeRefusedException with one of its codes when the change would be refused
   * @throws StripeTransportException when the provider did not answer
   */
  public PlanChangePreview previewChange(String clientId, String targetPlanKey)
      throws StripeTransportException {
    Subscription open = subscriptionService.findOpen(clientId).orElseThrow(
        () -> refused(PlanChangeRefusedException.NO_SUBSCRIPTION,
            "The environment has no open subscription"));
    if (keepsCurrentPlan(open, targetPlanKey)) {
      // Cancelling the scheduled downgrade charges nothing.
      return new PlanChangePreview(PlanChangeTiming.IMMEDIATE, open.getPlan(), clock.instant(),
          null, null, null, null, null);
    }
    Resolved change = resolve(open, targetPlanKey);
    JSONObject live = requireSuccess(stripe.get(SUBSCRIPTIONS + encode(change.subscriptionId())
        + "?expand%5B%5D=default_payment_method"), "read the subscription");
    if (change.timing() == PlanChangeTiming.PERIOD_END) {
      Instant periodEnd = SubscriptionLifecycleApplier.subscriptionPeriodBoundary(live,
          SubscriptionLifecycleApplier.CURRENT_PERIOD_END);
      return new PlanChangePreview(change.timing(), change.target(), periodEnd, null, null, null,
          null, null);
    }
    String itemId = firstItemIdOf(live);
    if (itemId == null) {
      throw refused(PlanChangeRefusedException.PROVIDER_REJECTED,
          "The provider subscription carries no item to update");
    }
    long prorationDate = clock.instant().getEpochSecond();
    StringBuilder form = new StringBuilder();
    add(form, "subscription", change.subscriptionId());
    add(form, "subscription_details[items][0][id]", itemId);
    add(form, "subscription_details[items][0][price]", change.target().getProviderPriceID());
    add(form, "subscription_details[proration_behavior]", PRORATION_UPGRADE);
    add(form, "subscription_details[proration_date]", String.valueOf(prorationDate));
    JSONObject invoice = requireSuccess(stripe.postForm(INVOICE_PREVIEW, form.toString()),
        "preview the plan change invoice");
    String currency = StringUtils.upperCase(StringUtils.trimToNull(invoice.optString("currency",
        "")));
    BigDecimal amountDue = currency == null ? null
        : BigDecimal.valueOf(invoice.optLong("amount_due", 0L))
            .movePointLeft(StripeCurrencyScale.exponent(currency));
    JSONObject card = cardOf(live.optJSONObject("default_payment_method"));
    return new PlanChangePreview(change.timing(), change.target(), clock.instant(), amountDue,
        currency, prorationDate, card == null ? null : StringUtils.trimToNull(card.optString(
            "brand", "")), card == null ? null : StringUtils.trimToNull(card.optString("last4", "")));
  }

  /**
   * The plan the offered changes start from: the plan a pending downgrade moves to — the customer
   * is already heading there, so what is offered is every plan reachable from it — else the plan
   * the subscription is on. From the pending plan, a {@code period_end} row replaces the scheduled
   * target, an {@code immediate} row applies now from the current plan (the schedule is released
   * first), and the current plan itself means keeping it ({@link #keepsCurrentPlan}).
   *
   * @param open the tenant's open row, may be null
   * @return the plan to read the Allowed plan changes rows of, or null
   */
  public static Plan fromPlanOf(Subscription open) {
    if (open == null) {
      return null;
    }
    return open.getPendingPlan() != null ? open.getPendingPlan() : open.getPlan();
  }

  /**
   * A request for the plan the subscription is on while a downgrade is pending: keep it. There is
   * no Allowed plan changes row from a plan to itself, so without a pending downgrade the same
   * request stays a {@code PLAN_CHANGE_NOT_ALLOWED} refusal.
   */
  static boolean keepsCurrentPlan(Subscription open, String targetPlanKey) {
    return open != null && open.getPendingPlan() != null && open.getPlan() != null
        && StringUtils.equals(open.getPlan().getSearchKey(), StringUtils.trim(targetPlanKey));
  }

  /**
   * Cancels the scheduled downgrade: the provider's schedule is released and the pending columns
   * cleared, so the subscription simply renews on its plan. Nothing is charged. The release must
   * succeed — clearing the columns while the schedule stays would show "no change" here while
   * the provider still downgrades at the period end.
   */
  private PlanChangeResult cancelPendingChange(Subscription open) throws StripeTransportException {
    String subscriptionId = StringUtils.trimToNull(open.getStripeSubscription());
    if (subscriptionId != null) {
      if (!providerConfigured.getAsBoolean()) {
        throw refused(PlanChangeRefusedException.NOT_CONFIGURED,
            "The payment provider is not configured");
      }
      String scheduleId = scheduleIdOf(readSubscription(subscriptionId));
      if (scheduleId != null) {
        requireSuccess(stripe.postForm(SCHEDULES + "/" + encode(scheduleId) + "/release", ""),
            "cancel the scheduled plan change");
      }
    }
    String cancelled = planKey(open.getPendingPlan());
    subscriptionService.clearPendingChange(open);
    log.info("Subscription {} of tenant {} keeps plan '{}'; the change to '{}' is cancelled",
        open.getId(), clientIdOf(open), planKey(open.getPlan()), cancelled);
    return new PlanChangeResult(PlanChangeTiming.IMMEDIATE, open.getPlan(), clock.instant(), false,
        false, null, true);
  }

  /** The {@code card} of an expanded payment method; null when it is not expanded or no card. */
  private static JSONObject cardOf(JSONObject paymentMethod) {
    return paymentMethod == null ? null : paymentMethod.optJSONObject("card");
  }

  /**
   * The proration instant of an upgrade: the previewed one when it is recent (at most an hour old
   * and not ahead of the clock), so the charge equals the amount the customer saw; now otherwise.
   */
  Instant prorationInstant(Long prorationDate) {
    Instant now = clock.instant();
    if (prorationDate == null) {
      return now;
    }
    Instant quoted = Instant.ofEpochSecond(prorationDate);
    return quoted.isAfter(now) || quoted.isBefore(now.minusSeconds(3600)) ? now : quoted;
  }

  /** A validated change: the target plan, its timing and the provider subscription it acts on. */
  private record Resolved(Plan target, PlanChangeTiming timing, String subscriptionId) {
  }

  /** Every refusal a change and its preview share, before anything is asked of the provider. */
  private Resolved resolve(Subscription open, String targetPlanKey) {
    if (open == null) {
      throw refused(PlanChangeRefusedException.NO_SUBSCRIPTION,
          "The environment has no open subscription");
    }
    if (!SubscriptionService.STATUS_ACTIVE.equalsIgnoreCase(
        StringUtils.trimToEmpty(SubscriptionService.effectiveStatusOf(open)))) {
      throw refused(PlanChangeRefusedException.SUBSCRIPTION_NOT_CURRENT,
          "Only a current subscription can change plan; this one is "
              + SubscriptionService.effectiveStatusOf(open));
    }
    Plan target = planCatalogService.findPurchasablePlan(targetPlanKey).orElseThrow(
        () -> refused(PlanChangeRefusedException.PLAN_NOT_FOUND,
            "No plan is sold under '" + StringUtils.trimToEmpty(targetPlanKey) + "'"));
    Plan from = fromPlanOf(open);
    PlanChange rule = planChangeCatalog.findAllowed(from, target).orElseThrow(
        () -> refused(PlanChangeRefusedException.PLAN_CHANGE_NOT_ALLOWED,
            "Changing from plan '" + planKey(from) + "' to '" + target.getSearchKey()
                + "' is not offered"));
    if (!planCatalogService.hasProviderPrice(target)) {
      throw refused(PlanChangeRefusedException.NOT_CONFIGURED,
          "Plan '" + target.getSearchKey() + "' carries no provider price");
    }
    String subscriptionId = StringUtils.trimToNull(open.getStripeSubscription());
    if (subscriptionId == null) {
      throw refused(PlanChangeRefusedException.NO_SUBSCRIPTION,
          "The subscription row names no provider subscription");
    }
    if (!providerConfigured.getAsBoolean()) {
      throw refused(PlanChangeRefusedException.NOT_CONFIGURED,
          "The payment provider is not configured");
    }
    return new Resolved(target, PlanChangeCatalog.timingOf(rule), subscriptionId);
  }

  /**
   * The Stripe interval ({@code day}, {@code week}, {@code month}, {@code year}) of one billing
   * cycle of the target plan, for the schedule phase that moves the subscription onto it. A plan
   * whose interval is not stored yet counts as monthly: the phase releases when it ends, so its
   * length never changes the price the subscription keeps, only when the schedule lets go.
   */
  static String durationIntervalOf(Plan target) {
    String interval = StringUtils.lowerCase(StringUtils.trimToNull(target.getBillingInterval()));
    return interval == null ? "month" : interval;
  }

  private static boolean isAlreadyPending(Subscription open, String targetPlanKey) {
    return open != null && open.getPendingPlan() != null && open.getPendingEffectiveDate() != null
        && StringUtils.equals(open.getPendingPlan().getSearchKey(), StringUtils.trim(targetPlanKey));
  }

  // ---------------------------------------------------------------- upgrade (immediate)

  private PlanChangeResult applyUpgrade(Subscription open, Plan target, String subscriptionId,
      JSONObject live, Instant prorationAt) throws StripeTransportException {
    String scheduleId = scheduleIdOf(live);
    if (scheduleId != null) {
      // A pending downgrade and now an upgrade: one intention per subscription (PRD §8.4).
      releaseSchedule(scheduleId);
      subscriptionService.clearPendingChange(open);
    }
    String itemId = firstItemIdOf(live);
    if (itemId == null) {
      throw refused(PlanChangeRefusedException.PROVIDER_REJECTED,
          "The provider subscription carries no item to update");
    }
    StringBuilder form = new StringBuilder();
    add(form, "items[0][id]", itemId);
    add(form, "items[0][price]", target.getProviderPriceID());
    add(form, "proration_behavior", PRORATION_UPGRADE);
    long prorationDate = prorationAt.getEpochSecond();
    add(form, "proration_date", String.valueOf(prorationDate));
    // The change applies only once its invoice is paid: a declined card or a bank asking the
    // customer to authenticate leaves the plan as it is instead of upgrading on credit.
    add(form, "payment_behavior", PAYMENT_PENDING_IF_INCOMPLETE);
    add(form, "expand[]", "latest_invoice");
    // Never billing_cycle_anchor (PRD §8.2). Idempotent per row, target and quoted proration
    // instant: a retried confirmation is the same provider operation, so there is never a second
    // proration, while a new preview is a new operation.
    StripeResponse response = stripe.postForm(SUBSCRIPTIONS + encode(subscriptionId),
        form.toString(), "plan-change:" + open.getId() + ":" + target.getId() + ":"
            + prorationDate);
    JSONObject updated = requireSuccess(response, "update the subscription item");
    Instant now = clock.instant();
    if (hasPendingUpdate(updated)) {
      String paymentUrl = hostedInvoiceUrlOf(updated);
      log.info("Upgrade of subscription {} of tenant {} to plan '{}' waits for its payment"
          + " (invoice page {})", open.getId(), clientIdOf(open), target.getSearchKey(),
          paymentUrl == null ? "not available" : "available");
      return new PlanChangeResult(PlanChangeTiming.IMMEDIATE, target, now, false, true,
          paymentUrl);
    }
    applyProviderAnswer(subscriptionId, updated, now);
    log.info("Subscription {} of tenant {} upgraded to plan '{}' (price {}), prorated {}",
        open.getId(), clientIdOf(open), target.getSearchKey(), target.getProviderPriceID(),
        PRORATION_UPGRADE);
    return new PlanChangeResult(PlanChangeTiming.IMMEDIATE, target, now, false);
  }

  /**
   * Applies the subscription object the provider answered with, through the same path the
   * webhook takes: the price moved, so the row is rolled over now rather than when the
   * {@code customer.subscription.updated} arrives. Written as system (client-{@code 0} rows).
   */
  private void applyProviderAnswer(String subscriptionId, JSONObject updated, Instant now) {
    SystemContext.run("applying a plan change", () -> {
      TenantEnvironmentLifecycleService.LifecycleTarget target =
          lifecycleService.targetForSubscription(subscriptionId);
      if (target == null || target.isIgnored()) {
        log.warn("Plan change applied at the provider for {} but no open row answers for it;"
            + " the webhook will roll the row", subscriptionId);
        return;
      }
      SubscriptionEventOutcome outcome = applier.evaluate(
          SubscriptionLifecycleApplier.SUBSCRIPTION_UPDATED,
          SubscriptionLifecycleApplier.syntheticSubscriptionUpdated(updated, now),
          target.storedState());
      if (outcome.isIgnored()) {
        log.warn("The provider's answer to the plan change of {} was not applied ({}); the webhook"
            + " will roll the row", subscriptionId, outcome.reason());
        return;
      }
      if (!lifecycleService.applySubscriptionEvent(target, outcome, now)) {
        throw new IllegalStateException("Could not store the plan change of " + subscriptionId);
      }
    });
  }

  // ---------------------------------------------------------------- downgrade (period end)

  private PlanChangeResult scheduleDowngrade(Subscription open, Plan target,
      String subscriptionId, JSONObject live) throws StripeTransportException {
    Instant periodEnd = epochSeconds(live, "current_period_end");
    Instant periodStart = epochSeconds(live, "current_period_start");
    if (periodEnd == null || periodStart == null) {
      // API 2025-03-31 moved the boundaries onto the item.
      periodStart = SubscriptionLifecycleApplier.subscriptionPeriodBoundary(live,
          SubscriptionLifecycleApplier.CURRENT_PERIOD_START);
      periodEnd = SubscriptionLifecycleApplier.subscriptionPeriodBoundary(live,
          SubscriptionLifecycleApplier.CURRENT_PERIOD_END);
    }
    String currentPrice = SubscriptionLifecycleApplier.itemPriceIdOf(live);
    if (periodEnd == null || periodStart == null || currentPrice == null) {
      throw refused(PlanChangeRefusedException.PROVIDER_REJECTED,
          "The provider subscription carries no current period or no item price");
    }
    String scheduleId = scheduleIdOf(live);
    if (scheduleId == null) {
      StringBuilder create = new StringBuilder();
      add(create, "from_subscription", subscriptionId);
      // Keyed per row and request second: a double submit cannot create two schedules, but a
      // later request is a new operation. A key per row alone made Stripe replay, for 24 hours,
      // the schedule an earlier downgrade created — released by a cancellation by then — and the
      // phase update of that released schedule failed.
      JSONObject schedule = requireSuccess(stripe.postForm(SCHEDULES, create.toString(),
          "plan-change-schedule:" + open.getId() + ":" + clock.instant().getEpochSecond()),
          "create the subscription schedule");
      scheduleId = StringUtils.trimToNull(schedule.optString("id", ""));
      if (scheduleId == null) {
        throw refused(PlanChangeRefusedException.PROVIDER_REJECTED,
            "The provider created a schedule without an id");
      }
    }
    // The whole phase list, every time: the current phase as it stands, then one iteration on
    // the target price from the period end. Updating in place is what makes a second downgrade
    // replace the pending target instead of creating a second schedule (PRD §8.4).
    StringBuilder phases = new StringBuilder();
    add(phases, "phases[0][items][0][price]", currentPrice);
    add(phases, "phases[0][start_date]", String.valueOf(periodStart.getEpochSecond()));
    add(phases, "phases[0][end_date]", String.valueOf(periodEnd.getEpochSecond()));
    add(phases, "phases[1][items][0][price]", target.getProviderPriceID());
    // One billing cycle of the target plan. Stripe removed phases[].iterations in API
    // 2025-09-30.clover and answers 400 parameter_unknown to it; duration replaces it. The
    // phase then releases, so the subscription simply carries on at the target price.
    add(phases, "phases[1][duration][interval]", durationIntervalOf(target));
    add(phases, "phases[1][duration][interval_count]", "1");
    add(phases, "proration_behavior", PRORATION_NONE);
    add(phases, "end_behavior", "release");
    requireSuccess(stripe.postForm(SCHEDULES + "/" + encode(scheduleId), phases.toString()),
        "schedule the plan change");
    subscriptionService.writePendingChange(open, target, Date.from(periodEnd));
    log.info("Subscription {} of tenant {} will move to plan '{}' on {} (schedule {})",
        open.getId(), clientIdOf(open), target.getSearchKey(), periodEnd, scheduleId);
    return new PlanChangeResult(PlanChangeTiming.PERIOD_END, target, periodEnd, false);
  }

  private void releaseSchedule(String scheduleId) throws StripeTransportException {
    StripeResponse response = stripe.postForm(SCHEDULES + "/" + encode(scheduleId) + "/release",
        "");
    if (!response.isSuccess()) {
      // A schedule already released or completed is not a refusal of the upgrade.
      log.warn("Could not release subscription schedule {} before the upgrade: {} ({})",
          scheduleId, response.status(), Objects.toString(response.errorCode(), "no code"));
    }
  }

  // ---------------------------------------------------------------- provider helpers

  private JSONObject readSubscription(String subscriptionId) throws StripeTransportException {
    return requireSuccess(stripe.get(SUBSCRIPTIONS + encode(subscriptionId)),
        "read the subscription");
  }

  private static JSONObject requireSuccess(StripeResponse response, String what) {
    if (!response.isSuccess()) {
      throw refused(PlanChangeRefusedException.PROVIDER_REJECTED, "The payment provider refused to "
          + what + ": " + response.status() + " (" + Objects.toString(response.errorCode(),
              "no code") + ")" + (StringUtils.isBlank(response.errorMessage()) ? ""
                  : " " + response.errorMessage()));
    }
    return response.json();
  }

  /** The schedule a subscription is under: {@code schedule} as an id or an expanded object. */
  static String scheduleIdOf(JSONObject subscription) {
    if (subscription == null || !subscription.has(FIELD_SCHEDULE)
        || subscription.isNull(FIELD_SCHEDULE)) {
      return null;
    }
    JSONObject expanded = subscription.optJSONObject(FIELD_SCHEDULE);
    return StringUtils.trimToNull(expanded != null ? expanded.optString("id", "")
        : subscription.optString(FIELD_SCHEDULE, ""));
  }

  /** True when the provider kept the update aside until its invoice is paid. */
  static boolean hasPendingUpdate(JSONObject subscription) {
    return subscription != null && subscription.optJSONObject("pending_update") != null;
  }

  /** The hosted page of the subscription's latest invoice, when {@code latest_invoice} is expanded. */
  static String hostedInvoiceUrlOf(JSONObject subscription) {
    JSONObject invoice = subscription == null ? null : subscription.optJSONObject("latest_invoice");
    return invoice == null ? null : StringUtils.trimToNull(invoice.optString(
        "hosted_invoice_url", ""));
  }

  static String firstItemIdOf(JSONObject subscription) {
    JSONObject items = subscription == null ? null : subscription.optJSONObject("items");
    JSONArray data = items == null ? null : items.optJSONArray("data");
    JSONObject first = data == null ? null : data.optJSONObject(0);
    return first == null ? null : StringUtils.trimToNull(first.optString("id", ""));
  }

  private static Instant epochSeconds(JSONObject object, String field) {
    long seconds = object == null ? 0L : object.optLong(field, 0L);
    return seconds > 0L ? Instant.ofEpochSecond(seconds) : null;
  }

  private static void add(StringBuilder form, String key, String value) {
    try {
      if (form.length() > 0) {
        form.append('&');
      }
      form.append(URLEncoder.encode(key, StandardCharsets.UTF_8.name())).append('=')
          .append(URLEncoder.encode(StringUtils.defaultString(value),
              StandardCharsets.UTF_8.name()));
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static PlanChangeRefusedException refused(String code, String message) {
    return new PlanChangeRefusedException(code, message);
  }

  private static String planKey(Plan plan) {
    return plan == null ? null : plan.getSearchKey();
  }

  private static String clientIdOf(Subscription open) {
    return open.getEnvironmentClient() == null ? null : open.getEnvironmentClient().getId();
  }
}
