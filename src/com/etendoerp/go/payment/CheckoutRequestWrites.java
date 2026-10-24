/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/**
 * ETP-5048 — the checkout-request writes the billing reconciliation added: the onboarding inputs
 * recorded at purchase time, the close of an abandoned request as
 * {@link CheckoutRequestStore#STATUS_EXPIRED}, and a recovered payment.
 *
 * <p><b>Pending variants.</b> {@link #recordPaidPending} and {@link #recordExpiredPending} flush
 * without committing, so the reconciliation commits the repair together with the billing-event
 * row that explains it ({@code BillingEventStore.recordReconciliation}): a repair is never
 * committed without its audit row, and a failed audit insert rolls the repair back too.
 *
 * <p>Kept beside {@link CheckoutRequestStore} rather than in it, which already carries the whole
 * checkout lifecycle; both follow its conventions — system context, rows at client {@code 0}, bulk
 * HQL naming the entity by {@link CheckoutRequest#ENTITY_NAME}.
 */
public class CheckoutRequestWrites {

  private static final Logger log = LogManager.getLogger(CheckoutRequestWrites.class);

  private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-z]{2,3}(_[A-Z]{2})?$");
  private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");
  private static final Pattern COUNTRY_PATTERN = Pattern.compile("^[A-Z]{2}$");
  private static final String PARAM_REQUEST_ID = "requestId";
  /**
   * The billing reconciliation's stable prefix: a value lost here surfaces later as a
   * server-side provisioning that fell back to the draft or the defaults.
   */
  private static final String LOG_PREFIX = "[BILLING-RECONCILE]";

  /**
   * Records the onboarding inputs the purchaser's browser sent with the purchase, so the
   * environment can later be set up without that browser ({@code PaidProvisioningService}).
   *
   * <p>First write wins, and each value is kept only when it is well formed — a language such as
   * {@code es_ES}, a three-letter currency, a two-letter country — because a malformed one would
   * fail the server-side provisioning far from where it came from; a dropped value falls back to
   * the account draft and then the onboarding defaults. Best effort: never throws and never
   * changes the status, because it runs on the purchase path, which must not fail over it.
   *
   * <p>Nothing is dropped silently: a supplied value that is malformed, and a request that does
   * not exist, are logged as a WARN with the billing prefix, so a later fallback can be explained.
   * A malformed value is reported by its length only, never echoed, since it is user input.
   *
   * <p><b>Transaction.</b> It runs in {@code HostedCheckoutService.createSession}'s
   * {@code beforeProvider} callback, after {@code CheckoutRequestStore.recordRequested} has
   * committed the row in its own transaction, so the commit or rollback here holds only these
   * writes, never the purchase flow's.
   *
   * @param requestId correlation id
   * @param language UI language, e.g. {@code es_ES}, may be null
   * @param currency ISO 4217 code, may be null
   * @param country ISO 3166-1 alpha-2 code, may be null
   */
  public void recordOnboardingInputs(String requestId, String language, String currency,
      String country) {
    String cleanLanguage = matching(requestId, "language", language, LANGUAGE_PATTERN, false);
    String cleanCurrency = matching(requestId, "currency", currency, CURRENCY_PATTERN, true);
    String cleanCountry = matching(requestId, "country", country, COUNTRY_PATTERN, true);
    if (cleanLanguage == null && cleanCurrency == null && cleanCountry == null) {
      return;
    }
    SystemContext.run("recording a checkout's onboarding inputs", () -> {
      try {
        CheckoutRequest request = CheckoutRequestStore.findByRequestId(requestId);
        if (request == null) {
          // The row was committed moments earlier in this same purchase: not finding it is a bug.
          log.warn("{} Checkout request {} not found while recording its onboarding inputs; a "
              + "server-side provisioning will fall back to the draft or the defaults",
              LOG_PREFIX, requestId);
          return;
        }
        if (request.getLanguage() == null) {
          request.setLanguage(cleanLanguage);
        }
        if (request.getCurrency() == null) {
          request.setCurrency(cleanCurrency);
        }
        if (request.getCountry() == null) {
          request.setCountry(cleanCountry);
        }
        OBDal.getInstance().save(request);
        OBDal.getInstance().flush();
        OBDal.getInstance().commitAndClose();
      } catch (RuntimeException e) {
        log.error("Could not record the onboarding inputs of checkout request '{}'", requestId, e);
        OBDal.getInstance().rollbackAndClose();
      }
    });
  }

  /**
   * Closes an abandoned request once the payment provider confirmed its session expired. A
   * conditional update: only a {@code CREATED} request moves, so a payment recorded in between
   * (the webhook racing the reconciliation) is never overwritten.
   *
   * @param requestId correlation id
   * @return whether this call closed the request
   */
  public boolean recordExpired(String requestId) {
    return SystemContext.call("closing an abandoned checkout request", () -> {
      boolean closed = recordExpiredPending(requestId);
      OBDal.getInstance().commitAndClose();
      return closed;
    });
  }

  /**
   * {@link #recordExpired} without the commit: the caller commits the close together with its
   * audit row.
   *
   * @param requestId correlation id
   * @return whether this call closed the request
   */
  public boolean recordExpiredPending(String requestId) {
    return SystemContext.call("closing an abandoned checkout request", () -> {
      Date now = new Date();
      int updated = OBDal.getInstance().getSession().createQuery(
              "update " + CheckoutRequest.ENTITY_NAME + " cr"
                  + " set cr.checkoutRequestStatus = :closedStatus, cr.expiredAt = :now,"
                  + "     cr.updated = :now"
                  + " where cr.request = :" + PARAM_REQUEST_ID
                  + " and cr.checkoutRequestStatus = :openStatus")
          .setParameter("closedStatus", CheckoutRequestStore.STATUS_EXPIRED)
          .setParameter("now", now)
          .setParameter(PARAM_REQUEST_ID, StringUtils.trimToEmpty(requestId))
          .setParameter("openStatus", CheckoutRequestStore.STATUS_CREATED)
          .executeUpdate();
      OBDal.getInstance().flush();
      return updated == 1;
    });
  }

  /**
   * Records a payment confirmed by the provider, like {@link CheckoutRequestStore#recordPaid}, but
   * leaves it pending: the caller commits it together with its audit row.
   *
   * @param requestId correlation id
   * @param stripeCustomerId {@code cus_...}, or null
   * @param stripeSubscriptionId {@code sub_...}, or null
   * @return whether the request exists and the payment was recorded on it
   */
  public boolean recordPaidPending(String requestId, String stripeCustomerId,
      String stripeSubscriptionId) {
    return SystemContext.call("recording a recovered payment", () -> {
      boolean found = applyPaid(requestId, stripeCustomerId, stripeSubscriptionId);
      if (found) {
        OBDal.getInstance().flush();
      }
      return found;
    });
  }

  /**
   * The one payment write, shared by {@link CheckoutRequestStore#recordPaid} (the webhook) and
   * {@link #recordPaidPending} (the reconciliation). Captures the provider ids, moves the request
   * to {@code PAID} when that is forward, and stamps {@code PAID_AT} once. Not committed; the
   * caller provides the system context.
   *
   * @return false when no request carries the id (logged)
   */
  static boolean applyPaid(String requestId, String stripeCustomerId,
      String stripeSubscriptionId) {
    CheckoutRequest request = CheckoutRequestStore.findByRequestId(requestId);
    if (request == null) {
      log.error("No checkout request found for '{}' while recording a confirmed payment",
          requestId);
      return false;
    }
    // Captured regardless of the status transition: these are the join keys every future
    // subscription and invoice event arrives on, and only this event carries them.
    if (request.getStripeCustomer() == null && StringUtils.isNotBlank(stripeCustomerId)) {
      request.setStripeCustomer(stripeCustomerId);
    }
    if (request.getStripeSubscription() == null && StringUtils.isNotBlank(stripeSubscriptionId)) {
      request.setStripeSubscription(stripeSubscriptionId);
    }
    if (CheckoutRequestStore.advance(request, CheckoutRequestStore.STATUS_PAID)
        && request.getPaidAt() == null) {
      request.setPaidAt(new Date());
    }
    OBDal.getInstance().save(request);
    return true;
  }

  private static String matching(String requestId, String field, String value, Pattern pattern,
      boolean upperCase) {
    String trimmed = StringUtils.trimToNull(value);
    if (trimmed == null) {
      return null;
    }
    String normalized = upperCase ? trimmed.toUpperCase(Locale.ROOT) : trimmed;
    if (pattern.matcher(normalized).matches()) {
      return normalized;
    }
    log.warn("{} Checkout request {}: the {} sent with the purchase is malformed ({} characters) "
        + "and is not recorded", LOG_PREFIX, requestId, field, trimmed.length());
    return null;
  }
}
