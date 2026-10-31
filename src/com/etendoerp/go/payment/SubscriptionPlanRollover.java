/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.util.Date;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.schemaforge.data.Plan;
import com.etendoerp.go.schemaforge.data.Subscription;
import com.etendoerp.go.usage.quota.TenantQuotas;

/**
 * ETP-5053 — detects an applied plan change in a lifecycle event and rolls the subscription row
 * over to its successor before the event is written.
 *
 * <p><b>The one signal.</b> {@code customer.subscription.updated} carries the price the
 * subscription item is on ({@link SubscriptionEventOutcome#providerPriceId()}). When it no longer
 * matches the open row's {@code PROVIDER_PRICE_ID}, the provider has applied a plan change — an
 * upgrade made just now, or a downgrade phase that Stripe's schedule reached at the period
 * boundary — and the row must follow: the target plan is resolved by that price and the row
 * rolled over ({@link SubscriptionService#rollToPlan}). {@code subscription_schedule.*} events
 * are never consulted: they are observability, not a source of entitlement.
 *
 * <p><b>Never guessed.</b> A price no active plan carries is logged at ERROR with the price and
 * the row, and the event is otherwise applied as usual to the existing row (status, period,
 * watermark), so access is never wrong while an operator attaches the price to its plan in the
 * Plans window. A row with no stored price (a backfilled row the reconciliation has not filled
 * yet) compares nothing.
 *
 * <p><b>The quota year.</b> The successor inherits the predecessor's anchor
 * ({@link TenantQuotas#quotaYearAnchorOf}), so an upgrade or a downgrade never resets usage;
 * only a change of billing interval starts a new year, anchored on the new provider period start
 * the event reports (Stripe restarts the cycle when the interval changes). The same price moving
 * within the same plan — a rotated price id — updates the row's snapshot and rolls nothing.
 */
public class SubscriptionPlanRollover {

  private static final Logger log = LogManager.getLogger(SubscriptionPlanRollover.class);

  private final SubscriptionService subscriptionService;
  private final PlanCatalogService planCatalogService;

  /**
   * @param subscriptionService rolls the row over
   * @param planCatalogService resolves the target plan by its provider price
   */
  public SubscriptionPlanRollover(SubscriptionService subscriptionService,
      PlanCatalogService planCatalogService) {
    this.subscriptionService = subscriptionService;
    this.planCatalogService = planCatalogService;
  }

  /**
   * Rolls the row over when the event shows its item on another plan's price.
   *
   * @param row the open row the event resolved to, never null
   * @param outcome the applied outcome, carrying the item price when the event reports one
   * @return the row the outcome must be written to: the successor when a plan change applied,
   *     otherwise {@code row} itself
   */
  public Subscription rollIfPriceChanged(Subscription row, SubscriptionEventOutcome outcome) {
    String eventPrice = outcome == null ? null : StringUtils.trimToNull(outcome.providerPriceId());
    String rowPrice = StringUtils.trimToNull(row.getProviderPriceID());
    if (eventPrice == null || rowPrice == null || eventPrice.equals(rowPrice)
        || row.getEndDate() != null) {
      return row;
    }
    Optional<Plan> target = planCatalogService.findByProviderPrice(eventPrice);
    if (target.isEmpty()) {
      log.error("[BILLING-ALERT] Subscription {} (Stripe {}) is now on price {}, which no active"
          + " plan carries; the row stays on plan '{}' with price {} until an operator maps the"
          + " price to a plan in the Plans window. No plan was guessed.", row.getId(),
          row.getStripeSubscription(), eventPrice, planKeyOf(row.getPlan()), rowPrice);
      return row;
    }
    Plan current = row.getPlan();
    if (current != null && StringUtils.equals(current.getId(), target.get().getId())) {
      // The same plan under a new price id: a rotated price, not a plan change.
      row.setProviderPriceID(eventPrice);
      row.setSnapshotAmount(target.get().getDisplayPrice());
      row.setSnapshotCurrency(StringUtils.trimToNull(target.get().getCurrencyCode()));
      log.info("Subscription {} moved to price {} of its own plan '{}'; snapshot updated, no"
          + " rollover", row.getId(), eventPrice, planKeyOf(current));
      return row;
    }
    Date anchor = quotaYearAnchorFor(row, current, target.get(), outcome);
    Subscription successor = subscriptionService.rollToPlan(row, target.get(), eventPrice,
        anchor);
    log.info("Plan change applied at the provider: subscription {} of plan '{}' rolled over to {}"
        + " on plan '{}' (price {})", row.getId(), planKeyOf(current), successor.getId(),
        planKeyOf(target.get()), eventPrice);
    return successor;
  }

  /**
   * The successor's quota-year anchor: the predecessor's unless the billing interval changed, in
   * which case the new provider period start (or now, when the event reports none).
   */
  static Date quotaYearAnchorFor(Subscription row, Plan current, Plan target,
      SubscriptionEventOutcome outcome) {
    String fromInterval = current == null ? null
        : StringUtils.trimToNull(current.getBillingInterval());
    String toInterval = StringUtils.trimToNull(target.getBillingInterval());
    boolean intervalChanged = fromInterval != null && toInterval != null
        && !fromInterval.equals(toInterval);
    if (!intervalChanged) {
      return TenantQuotas.quotaYearAnchorOf(row);
    }
    return outcome.periodStart() == null ? new Date() : Date.from(outcome.periodStart());
  }

  private static String planKeyOf(Plan plan) {
    return plan == null ? null : plan.getSearchKey();
  }
}
