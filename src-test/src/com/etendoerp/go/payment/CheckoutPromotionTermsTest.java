package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.*;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/** @covers com.etendoerp.go.payment.CheckoutPromotionTerms */
class CheckoutPromotionTermsTest {
  @Test void monthlyTermsChargeTodaySkipSecondMonthAndResumeThird() throws Exception {
    JSONObject terms = CheckoutPromotionTerms.describe("req", "price", 1100, "EUR", "month", true);
    assertTrue(terms.getBoolean("secondMonthFree"));
    assertEquals(3, terms.getInt("recurringFromMonth"));
    assertEquals(1100, terms.getLong("initialAmountMinor"));
    assertEquals("monthly-second-month-v1", terms.getString("version"));
  }
  @Test void yearlyAndReactivationNeverGetAnotherDiscount() throws Exception {
    assertFalse(CheckoutPromotionTerms.describe("req", "price", 11000, "EUR", "year", true)
        .getBoolean("secondMonthFree"));
    assertFalse(CheckoutPromotionTerms.describe("req", "price", 1100, "EUR", "month", false)
        .getBoolean("secondMonthFree"));
    assertTrue(CheckoutPromotionTerms.formFields(null).isEmpty());
    assertNull(CheckoutPromotionTerms.publicTerms(null));
  }

  @Test void persistedIntentWinsOnReopenEvenWhenPriceAndEligibilityChange() throws Exception {
    CheckoutPromotionTerms store = new CheckoutPromotionTerms();
    java.util.Map<String, String> ledger = new java.util.HashMap<>();
    store.events = new BillingEventStore() {
      @Override public String latestReconciliationSummary(String type, String request, String result) {
        return ledger.get(request);
      }
      @Override public boolean recordReconciliation(String id, String type, String request,
          String result, String reason, String summary) {
        assertEquals("promotion.intent", type);
        ledger.putIfAbsent(request, summary);
        return true;
      }
    };
    StripePriceService.Price price = StripePriceService.parsePrice(new JSONObject()
        .put("id", "monthly").put("active", true).put("unit_amount", 1100).put("currency", "eur")
        .put("recurring", new JSONObject().put("interval", "month").put("interval_count", 1)));
    JSONObject original = store.recordIntent("req", price, true, "es_ES");
    JSONObject resumed = store.recordIntent("req", price, false, "en_US");
    assertEquals(original.toString(), resumed.toString());
    assertTrue(resumed.getBoolean("secondMonthFree"));
    assertTrue(CheckoutPromotionTerms.formFields(resumed).get("custom_text[submit][message]").startsWith("Pagas"));
    assertFalse(CheckoutPromotionTerms.publicTerms(resumed).has("priceId"));
  }

  @Test void oneTimeCheckoutNeverSendsSubscriptionOnlyParameters() {
    JSONObject terms = CheckoutPromotionTerms.describe("req", "price", 1100, "EUR", "once", true);
    assertFalse(CheckoutPromotionTerms.formFields(terms).keySet().stream().anyMatch(k -> k.startsWith("subscription_data")));
  }
}
