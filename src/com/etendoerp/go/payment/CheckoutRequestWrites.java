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
 * ETP-5048 — the two checkout-request writes the billing reconciliation added: the onboarding
 * inputs recorded at purchase time, and the close of an abandoned request as
 * {@link CheckoutRequestStore#STATUS_EXPIRED}.
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
   * Records the onboarding inputs the purchaser's browser sent with the purchase, so the
   * environment can later be set up without that browser ({@code PaidProvisioningService}).
   *
   * <p>First write wins, and each value is kept only when it is well formed — a language such as
   * {@code es_ES}, a three-letter currency, a two-letter country — because a malformed one would
   * fail the server-side provisioning far from where it came from; a dropped value falls back to
   * the account draft and then the onboarding defaults. Best effort: never throws and never
   * changes the status, because it runs on the purchase path, which must not fail over it.
   *
   * @param requestId correlation id
   * @param language UI language, e.g. {@code es_ES}, may be null
   * @param currency ISO 4217 code, may be null
   * @param country ISO 3166-1 alpha-2 code, may be null
   */
  public void recordOnboardingInputs(String requestId, String language, String currency,
      String country) {
    String cleanLanguage = matching(language, LANGUAGE_PATTERN, false);
    String cleanCurrency = matching(currency, CURRENCY_PATTERN, true);
    String cleanCountry = matching(country, COUNTRY_PATTERN, true);
    if (cleanLanguage == null && cleanCurrency == null && cleanCountry == null) {
      return;
    }
    SystemContext.run("recording a checkout's onboarding inputs", () -> {
      try {
        CheckoutRequest request = CheckoutRequestStore.findByRequestId(requestId);
        if (request == null) {
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
      OBDal.getInstance().commitAndClose();
      return updated == 1;
    });
  }

  private static String matching(String value, Pattern pattern, boolean upperCase) {
    String trimmed = StringUtils.trimToNull(value);
    if (trimmed == null) {
      return null;
    }
    String normalized = upperCase ? trimmed.toUpperCase(Locale.ROOT) : trimmed;
    return pattern.matcher(normalized).matches() ? normalized : null;
  }
}
