/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;

/**
 * ETP-5047 — the one way this module reads a string field from a Stripe object.
 *
 * <p>Jettison's {@code JSONObject.optString} does not fall back to its default when the key maps
 * to {@link JSONObject#NULL}: it stringifies the null marker into the literal text
 * {@code "null"}. Stripe sends JSON {@code null} for every unset optional field — the
 * {@code subscription} of a payment-mode checkout or of an invoice not tied to one, an unset price
 * {@code nickname} — so a bare {@code optString} silently turns "absent" into a value. A
 * payment-mode checkout once stored {@code "null"} as its Stripe subscription id that way.
 *
 * <p>Every nullable Stripe string must be read through here. This used to exist three times (in
 * {@code SubscriptionLifecycleApplier}, {@code StripeCustomerPortalService} and
 * {@code PlanPriceDerivationHandler}); public because the checkout webhook in
 * {@code com.etendoerp.go.rest} reads the checkout ids through it as well.
 */
public final class StripeJson {

  private StripeJson() {
  }

  /**
   * Reads a string field the way Stripe sends it, never as the literal word "null".
   *
   * @param json a provider object, may be null
   * @param key the field to read
   * @return the trimmed value, or an empty string when the object is null or the field is absent,
   *     JSON {@code null} or blank
   */
  public static String text(JSONObject json, String key) {
    if (json == null || !json.has(key) || json.isNull(key)) {
      return "";
    }
    return StringUtils.trimToEmpty(json.optString(key, ""));
  }
}
