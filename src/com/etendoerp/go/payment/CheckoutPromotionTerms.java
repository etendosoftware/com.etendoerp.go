/* Etendo License. */
package com.etendoerp.go.payment;

import java.util.LinkedHashMap;
import java.util.Map;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/** Immutable commercial terms recorded before the first provider call, never inferred on reopen. */
public class CheckoutPromotionTerms {
  public static final String INTENT_TYPE = "promotion.intent";
  public static final String VERSION = "monthly-second-month-v1";
  BillingEventStore events = new BillingEventStore();

  /** Single server-side eligibility policy used by catalog and purchase creation. */
  public static boolean isEligiblePlan(com.etendoerp.go.schemaforge.data.Plan plan) {
    return plan != null && !PlanCatalogService.LEGACY_PLAN_KEY.equals(plan.getSearchKey())
        && "month".equals(plan.getBillingInterval()) && plan.getDisplayPrice() != null
        && plan.getDisplayPrice().signum() > 0;
  }

  /** The ledger commits this internal intent separately from verified Stripe webhook events. */
  public JSONObject recordIntent(String requestId, StripePriceService.Price price, boolean eligible) {
    return recordIntent(requestId, price, eligible, "en_US");
  }

  public JSONObject recordIntent(String requestId, StripePriceService.Price price, boolean eligible,
      String language) {
    JSONObject existing = termsForRequest(requestId);
    if (existing != null) return existing;
    JSONObject terms = describe(requestId, price.getId(), price.getAmountMinor(),
        price.getCurrency(), price.getInterval(), eligible);
    try { terms.put("language", language == null ? "en_US" : language); }
    catch (JSONException e) { throw new IllegalStateException("Cannot record checkout language", e); }
    events.recordReconciliation("promotion-intent:" + requestId, INTENT_TYPE, requestId,
        BillingEventStore.RESULT_APPLIED, null, terms.toString());
    // A competing writer won the unique key: its first snapshot remains authoritative.
    JSONObject persisted = termsForRequest(requestId);
    if (persisted == null) throw new IllegalStateException("Promotion terms were not persisted");
    return persisted;
  }

  public JSONObject termsForRequest(String requestId) {
    String value = events.latestReconciliationSummary(INTENT_TYPE, requestId,
        BillingEventStore.RESULT_APPLIED);
    if (value == null) return null;
    try { return new JSONObject(value); }
    catch (JSONException e) { throw new IllegalStateException("Invalid persisted promotion terms", e); }
  }

  static JSONObject describe(String request, String price, long amount, String currency,
      String interval, boolean eligible) {
    boolean free = eligible && "month".equals(interval) && amount > 0;
    try {
      return new JSONObject().put("version", free ? VERSION : "standard-v1")
          .put("requestId", request).put("priceId", price).put("initialAmountMinor", amount)
          .put("currency", currency).put("billingInterval", interval)
          .put("secondMonthFree", free).put("recurringFromMonth", free ? 3 : 0)
          .put("firstPaymentCoverageMonths", free ? 2 : ("year".equals(interval) ? 12 : 1))
          .put("yearlyDiscountAlreadyInPrice", "year".equals(interval));
    } catch (JSONException e) { throw new IllegalStateException("Cannot build promotion terms", e); }
  }

  /** Provider metadata is generated here, never accepted from the browser. */
  public static Map<String, String> formFields(JSONObject terms) {
    Map<String, String> fields = new LinkedHashMap<>();
    if (terms == null) return fields;
    for (String prefix : new String[]{"metadata", "subscription_data[metadata]"}) {
      if ("once".equals(terms.optString("billingInterval")) && prefix.startsWith("subscription")) continue;
      fields.put(prefix + "[promotion_version]", terms.optString("version"));
      fields.put(prefix + "[promotion_request_id]", terms.optString("requestId"));
    }
    if (terms.optBoolean("secondMonthFree")) {
      fields.put("custom_text[submit][message]", terms.optString("language").startsWith("es")
          ? "Pagas la primera mensualidad hoy. El segundo mes calendario es gratis. "
              + "El cobro mensual habitual se reanuda desde el tercer mes."
          : "Pay the first monthly installment today. The second calendar month is free. "
              + "Normal monthly billing resumes in month three.");
    }
    return fields;
  }

  /** Public copy omits provider identifiers; yearly prices already contain their discount. */
  public static JSONObject publicTerms(JSONObject terms) {
    if (terms == null) return null;
    try {
      JSONObject result = new JSONObject(terms.toString());
      result.remove("priceId");
      result.remove("requestId");
      return result;
    } catch (JSONException e) { throw new IllegalStateException("Cannot read promotion terms", e); }
  }
}
