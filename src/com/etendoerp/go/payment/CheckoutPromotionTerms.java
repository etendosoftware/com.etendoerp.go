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
  private static final String REQUEST_ID_FIELD = "requestId";
  BillingEventStore events = new BillingEventStore();

  /**
   * Applies the shared server-side promotion policy used by catalog and purchase creation.
   *
   * @param plan catalog plan to assess, or {@code null} when none was resolved
   * @return whether the plan is a non-legacy monthly plan with a positive display price
   */
  public static boolean isEligiblePlan(com.etendoerp.go.schemaforge.data.Plan plan) {
    return plan != null && !PlanCatalogService.LEGACY_PLAN_KEY.equals(plan.getSearchKey())
        && "month".equals(plan.getBillingInterval()) && plan.getDisplayPrice() != null
        && plan.getDisplayPrice().signum() > 0;
  }

  /**
   * Records an immutable commercial snapshot with the default English checkout language.
   *
   * @param requestId server-generated purchase request identifier
   * @param price validated provider price selected for the purchase
   * @param eligible whether the server-side promotion policy permits the offer
   * @return the authoritative snapshot persisted for this request
   */
  public JSONObject recordIntent(String requestId, StripePriceService.Price price, boolean eligible) {
    return recordIntent(requestId, price, eligible, "en_US");
  }

  /**
   * Persists the first commercial snapshot independently of verified Stripe webhook events.
   * A repeated or competing call returns the existing snapshot instead of changing its terms.
   *
   * @param requestId server-generated purchase request identifier
   * @param price validated provider price selected for the purchase
   * @param eligible whether the server-side promotion policy permits the offer
   * @param language checkout offer language; {@code null} selects {@code en_US}
   * @return the authoritative snapshot persisted for this request
   */
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

  /**
   * Reads the original commercial snapshot without deriving a new offer for an old purchase.
   *
   * @param requestId server-generated purchase request identifier
   * @return the persisted terms, or {@code null} when the request has no promotion snapshot
   */
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
    int firstPaymentCoverageMonths = "year".equals(interval) ? 12 : 1;
    if (free) firstPaymentCoverageMonths = 2;
    try {
      return new JSONObject().put("version", free ? VERSION : "standard-v1")
          .put(REQUEST_ID_FIELD, request).put("priceId", price).put("initialAmountMinor", amount)
          .put("currency", currency).put("billingInterval", interval)
          .put("secondMonthFree", free).put("recurringFromMonth", free ? 3 : 0)
          .put("firstPaymentCoverageMonths", firstPaymentCoverageMonths)
          .put("yearlyDiscountAlreadyInPrice", "year".equals(interval));
    } catch (JSONException e) { throw new IllegalStateException("Cannot build promotion terms", e); }
  }

  /**
   * Builds provider metadata and checkout offer text from the server-owned snapshot.
   *
   * @param terms persisted commercial terms, or {@code null} for a historical purchase
   * @return provider form fields, empty when no snapshot exists
   */
  public static Map<String, String> formFields(JSONObject terms) {
    Map<String, String> fields = new LinkedHashMap<>();
    if (terms == null) return fields;
    for (String prefix : new String[]{"metadata", "subscription_data[metadata]"}) {
      if ("once".equals(terms.optString("billingInterval")) && prefix.startsWith("subscription")) continue;
      fields.put(prefix + "[promotion_version]", terms.optString("version"));
      fields.put(prefix + "[promotion_request_id]", terms.optString(REQUEST_ID_FIELD));
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

  /**
   * Copies the commercial terms for the buyer without exposing provider or request identifiers.
   * Yearly prices already contain their discount and receive no additional reduction here.
   *
   * @param terms persisted commercial terms, or {@code null} when none were recorded
   * @return the public copy, or {@code null} when no snapshot exists
   */
  public static JSONObject publicTerms(JSONObject terms) {
    if (terms == null) return null;
    try {
      JSONObject result = new JSONObject(terms.toString());
      result.remove("priceId");
      result.remove(REQUEST_ID_FIELD);
      return result;
    } catch (JSONException e) { throw new IllegalStateException("Cannot read promotion terms", e); }
  }
}
