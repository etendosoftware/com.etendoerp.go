package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.*;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/** @covers com.etendoerp.go.payment.CheckoutPromotionTerms */
class CheckoutPromotionTermsTest {
  @Test void monthlyTermsChargeNothingTodayAndGrantThirtyFreeDays() throws Exception {
    JSONObject terms = CheckoutPromotionTerms.describe("req", "price", 1100, "EUR", "month", true);
    assertEquals(CheckoutPromotionTerms.TRIAL_VERSION, terms.getString("version"));
    assertEquals(30, CheckoutPromotionTerms.freeTrialDays(terms));
    assertEquals(0, terms.getLong("amountDueTodayMinor"));
    assertEquals(1100, terms.getLong("initialAmountMinor"));
    assertFalse(terms.getBoolean("secondMonthFree"));
    java.util.Map<String, String> fields = CheckoutPromotionTerms.formFields(terms);
    assertEquals(CheckoutPromotionTerms.TRIAL_VERSION,
        fields.get("subscription_data[metadata][promotion_version]"));
    assertTrue(fields.get("custom_text[submit][message]").startsWith("Nothing is charged today"));
  }

  @Test void yearlyReactivationAndAccountsThatAlreadyPaidGetNoTrial() throws Exception {
    JSONObject yearly = CheckoutPromotionTerms.describe("req", "price", 11000, "EUR", "year", true);
    assertEquals(0, CheckoutPromotionTerms.freeTrialDays(yearly));
    assertEquals(11000, yearly.getLong("amountDueTodayMinor"));
    assertEquals(12, yearly.getInt("firstPaymentCoverageMonths"));
    assertFalse(CheckoutPromotionTerms.formFields(yearly).containsKey("custom_text[submit][message]"));
    assertEquals(0, CheckoutPromotionTerms.freeTrialDays(
        CheckoutPromotionTerms.describe("req", "price", 1100, "EUR", "month", false)));
    assertEquals(0, CheckoutPromotionTerms.freeTrialDays(null));
    assertTrue(CheckoutPromotionTerms.formFields(null).isEmpty());
    assertNull(CheckoutPromotionTerms.publicTerms(null));

    com.etendoerp.go.schemaforge.data.Plan monthly =
        org.mockito.Mockito.mock(com.etendoerp.go.schemaforge.data.Plan.class);
    org.mockito.Mockito.when(monthly.getSearchKey()).thenReturn("core");
    org.mockito.Mockito.when(monthly.getBillingInterval()).thenReturn("month");
    org.mockito.Mockito.when(monthly.getDisplayPrice()).thenReturn(new java.math.BigDecimal("25"));
    assertTrue(CheckoutPromotionTerms.isEligible(monthly, false));
    assertFalse(CheckoutPromotionTerms.isEligible(monthly, true));
  }

  /** ETP-6216 purchases keep their recorded terms after the offer changed. */
  @Test void aRecordedSecondMonthOfferKeepsItsCheckoutText() throws Exception {
    JSONObject legacy = MonthlySecondMonthPromotionServiceTest.secondMonthFreeTerms("req",
        "price", 1100);
    legacy.put("language", "es_ES");
    assertEquals(0, CheckoutPromotionTerms.freeTrialDays(legacy));
    assertTrue(CheckoutPromotionTerms.formFields(legacy).get("custom_text[submit][message]")
        .startsWith("Pagas la primera mensualidad"));
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
    assertEquals(30, CheckoutPromotionTerms.freeTrialDays(resumed));
    assertTrue(CheckoutPromotionTerms.formFields(resumed).get("custom_text[submit][message]")
        .startsWith("Hoy no se cobra nada"));
    assertFalse(CheckoutPromotionTerms.publicTerms(resumed).has("priceId"));
  }

  @Test void oneTimeCheckoutNeverSendsSubscriptionOnlyParameters() {
    JSONObject terms = CheckoutPromotionTerms.describe("req", "price", 1100, "EUR", "once", true);
    assertFalse(CheckoutPromotionTerms.formFields(terms).keySet().stream().anyMatch(k -> k.startsWith("subscription_data")));
  }
}
