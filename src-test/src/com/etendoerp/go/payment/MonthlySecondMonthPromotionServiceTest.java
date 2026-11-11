package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.*;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/** @covers com.etendoerp.go.payment.MonthlySecondMonthPromotionService */
class MonthlySecondMonthPromotionServiceTest {
  @Test void paidCheckoutInstallsOnceAndDuplicateMakesNoProviderCall() throws Exception {
    Fixture f = new Fixture();
    f.service.applyPaidCheckout(f.session);
    assertEquals(1, f.stripe.creates);
    assertEquals(1, f.stripe.updates);
    assertEquals(java.util.List.of("promotion:req:schedule", "promotion:req:phases"), f.stripe.keys);
    int calls = f.stripe.reads;
    f.service.applyPaidCheckout(f.session);
    assertEquals(calls, f.stripe.reads);
    assertEquals(1, f.stripe.updates);
    assertTrue(f.ledger.rows.containsKey(MonthlySecondMonthPromotionService.APPLIED_TYPE));
  }

  @Test void timeoutAfterScheduleCreationRecoversSameScheduleWithoutDoubleGift() throws Exception {
    Fixture f = new Fixture();
    f.stripe.timeoutCreate = true;
    assertThrows(IllegalStateException.class, () -> f.service.applyPaidCheckout(f.session));
    assertFalse(f.ledger.rows.containsKey(MonthlySecondMonthPromotionService.APPLIED_TYPE));
    f.service.applyPaidCheckout(f.session);
    assertEquals(1, f.stripe.creates, "idempotent retry must not create a second schedule");
    assertEquals(1, f.stripe.updates);
    assertEquals(java.util.List.of("promotion:req:schedule", "promotion:req:schedule", "promotion:req:phases"), f.stripe.keys);
  }

  @Test void unrelatedScheduleAndElapsedWindowFailWithoutChangingProviderPhases() throws Exception {
    Fixture foreign = new Fixture();
    foreign.stripe.hasSchedule = true;
    foreign.stripe.foreignSchedule = true;
    assertThrows(IllegalStateException.class, () -> foreign.service.applyPaidCheckout(foreign.session));
    assertEquals(0, foreign.stripe.updates);
    Fixture late = new Fixture();
    late.service.clock = Clock.fixed(Instant.ofEpochSecond(1740700801L), ZoneOffset.UTC);
    assertThrows(IllegalStateException.class, () -> late.service.applyPaidCheckout(late.session));
    assertEquals(0, late.stripe.updates);
  }

  @Test void yearlyLegacyAndBusyWorkerNeverCreateProviderPromotion() throws Exception {
    Fixture f = new Fixture();
    f.terms.put("secondMonthFree", false);
    f.service.applyPaidCheckout(f.session);
    assertEquals(0, f.stripe.reads);
    f.terms.put("secondMonthFree", true);
    f.service.locks = request -> { throw new IllegalStateException("busy"); };
    assertThrows(IllegalStateException.class, () -> f.service.applyPaidCheckout(f.session));
    assertEquals(0, f.stripe.reads);
    f.session.getJSONObject("metadata").remove("promotion_version");
    f.service.applyPaidCheckout(f.session);
    assertEquals(0, f.stripe.reads);
  }

  @Test void preservesExpandedTaxAndDiscountReferencesAndDoesNotRepeatInvoiceItems() throws Exception {
    JSONObject phase = phase();
    phase.put("default_tax_rates", new JSONArray().put(new JSONObject().put("id", "txr_1")));
    phase.put("discounts", new JSONArray().put(new JSONObject().put("coupon", "old_coupon")
        .put("discount", "di_old")));
    phase.put("default_payment_method", new JSONObject().put("id", "pm_old"));
    phase.put("add_invoice_items", new JSONArray().put(new JSONObject().put("price", "setup")));
    String form = MonthlySecondMonthPromotionService.phaseForm(phase, "req", "gift");
    assertTrue(form.contains("phases%5B0%5D%5Bdefault_tax_rates%5D%5B0%5D=txr_1"), form);
    assertTrue(form.contains("phases%5B0%5D%5Bdefault_payment_method%5D=pm_old"), form);
    assertTrue(form.contains("%5Bdiscount%5D=di_old"), form);
    assertFalse(form.contains("old_coupon"), form);
    assertFalse(form.contains("phases%5B1%5D%5Badd_invoice_items%5D"), form);
    assertFalse(form.contains("phases%5B2%5D%5Badd_invoice_items%5D"), form);
  }

  private static JSONObject phase() throws Exception {
    return new JSONObject().put("start_date", 1738281600L).put("end_date", 1740700800L)
        .put("items", new JSONArray().put(new JSONObject().put("price", "price_month").put("quantity", 1)));
  }

  private static final class Ledger extends BillingEventStore {
    final Map<String, String> rows = new HashMap<>();
    @Override public String latestReconciliationSummary(String type, String request, String result) {
      return rows.get(type);
    }
    @Override public boolean recordReconciliation(String event, String type, String request,
        String result, String reason, String summary) { rows.put(type, summary); return true; }
  }

  private static final class Provider implements StripeApiClient {
    boolean hasSchedule, timeoutCreate, foreignSchedule;
    int creates, updates, reads;
    final java.util.List<String> keys = new java.util.ArrayList<>();
    JSONObject schedule;
    Provider() throws Exception {
      schedule = new JSONObject().put("id", "sched").put("metadata", new JSONObject())
          .put("phases", new JSONArray().put(phase()));
    }
    @Override public StripeResponse get(String path) {
      reads++;
      if (path.contains("/coupons/")) return new StripeResponse(200,
          "{\"percent_off\":100,\"duration\":\"forever\",\"valid\":true}");
      if (path.contains("/subscription_schedules/")) return new StripeResponse(200, schedule.toString());
      return new StripeResponse(200, hasSchedule ? "{\"schedule\":\"sched\"}" : "{}");
    }
    @Override public StripeResponse postForm(String path, String body) throws StripeTransportException {
      return postForm(path, body, null);
    }
    @Override public StripeResponse postForm(String path, String body, String key) throws StripeTransportException {
      keys.add(key);
      if (path.endsWith("/subscription_schedules")) {
        if (foreignSchedule) return new StripeResponse(400, "{}");
        if (!hasSchedule) { creates++; hasSchedule = true; }
        if (timeoutCreate) { timeoutCreate = false; throw new StripeTransportException("timeout"); }
      } else if (path.contains("/subscription_schedules/")) updates++;
      return new StripeResponse(200, schedule.toString());
    }
  }

  private static final class Fixture {
    final JSONObject terms = CheckoutPromotionTerms.describe("req", "price_month", 1100, "EUR", "month", true);
    final Ledger ledger = new Ledger();
    final Provider stripe;
    final MonthlySecondMonthPromotionService service = new MonthlySecondMonthPromotionService();
    final JSONObject session;
    Fixture() throws Exception {
      stripe = new Provider();
      service.stripe = stripe;
      service.events = ledger;
      service.termsStore = new CheckoutPromotionTerms() {
        @Override public JSONObject termsForRequest(String request) { return terms; }
      };
      service.locks = request -> () -> {};
      service.clock = Clock.fixed(Instant.ofEpochSecond(1739000000L), ZoneOffset.UTC);
      session = new JSONObject().put("payment_status", "paid").put("subscription", "sub")
          .put("metadata", new JSONObject().put("request_id", "req")
              .put("promotion_request_id", "req").put("promotion_version", CheckoutPromotionTerms.VERSION));
    }
  }
  @Test void freeMonthIsOnlySecondPhaseAndUsesProviderCalendarBoundaries() throws Exception {
    JSONObject phase = new JSONObject().put("start_date", 1738281600L).put("end_date", 1740700800L)
        .put("items", new JSONArray().put(new JSONObject().put("price", "price_month").put("quantity", 1)));
    String form = MonthlySecondMonthPromotionService.phaseForm(phase, "req", "coupon");
    assertTrue(form.contains("phases%5B0%5D%5Bend_date%5D=1740700800"), form);
    assertTrue(form.contains("phases%5B1%5D%5Bdiscounts%5D%5B0%5D%5Bcoupon%5D=coupon"), form);
    assertFalse(form.contains("phases%5B0%5D%5Bdiscounts%5D%5B0%5D%5Bcoupon%5D=coupon"), form);
    assertFalse(form.contains("phases%5B2%5D%5Bdiscounts%5D%5B0%5D%5Bcoupon%5D=coupon"), form);
    assertTrue(form.contains("end_behavior=release"), form);
    assertTrue(form.contains("proration_behavior=none"), form);
  }
}
