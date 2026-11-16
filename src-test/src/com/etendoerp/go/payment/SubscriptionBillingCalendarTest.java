/* Etendo License. */
package com.etendoerp.go.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.etendoerp.go.rest.EtendoGoJwtServlet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

/** Exercises webhook and schedule contracts with simulated stores and Stripe transport.
 * @covers com.etendoerp.go.payment.MonthlySecondMonthPromotionService */
class SubscriptionBillingCalendarTest {
  private static final String PRICE_MONTH = "price_test_month";
  private static final String PRICE_YEAR = "price_test_year";

  @Test void signedPaidWebhookRunsErpPurchaseAndMonthlyPromotion() throws Exception {
    JSONObject terms = CheckoutPromotionTerms.describe("req", PRICE_MONTH, 1100, "EUR", "month", true);
    Harness h = new Harness(terms);
    CheckoutRequestStore purchases = mock(CheckoutRequestStore.class);
    BillingEventStore billing = mock(BillingEventStore.class);
    when(purchases.recordPaid("req", "cus_test", "sub_test")).thenReturn(true);
    EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();
    setField(servlet, "checkoutRequestStore", purchases);
    setField(servlet, "billingEventStore", billing);
    JSONObject object = session(CheckoutPromotionTerms.VERSION).put("customer", "cus_test");
    object.getJSONObject("metadata").put("account_email", "test@example.invalid");
    JSONObject event = new JSONObject().put("id", "evt_test").put("type", "checkout.session.completed")
        .put("created", 1794502900L).put("data", new JSONObject().put("object", object));
    String payload = event.toString();
    CheckoutWebhookProcessor processor = new CheckoutWebhookProcessor(id -> true, 300);
    CheckoutWebhookProcessor.Acceptance accepted = processor.evaluate(payload, sign(payload), "whsec_test", 1794502900L);
    assertEquals(CheckoutWebhookProcessor.Result.ACCEPTED, accepted.result());
    try (MockedConstruction<MonthlySecondMonthPromotionService> constructed = mockConstruction(
        MonthlySecondMonthPromotionService.class, withSettings().defaultAnswer(CALLS_REAL_METHODS),
        (mock, context) -> {
          mock.clock = h.service.clock;
          mock.locks = h.service.locks;
          mock.termsStore = h.service.termsStore;
          mock.events = h.service.events;
          mock.stripe = h.service.stripe;
        })) {
      Method apply = EtendoGoJwtServlet.class.getDeclaredMethod("applyCheckoutPaid", String.class,
          String.class, JSONObject.class);
      apply.setAccessible(true);
      apply.invoke(servlet, accepted.eventId(), "checkout.session.completed", accepted.event());
      assertEquals(1, constructed.constructed().size());
    }
    verify(purchases).recordPaid("req", "cus_test", "sub_test");
    assertNotNull(h.update, "ERP payment handler must invoke the real promotion logic");
    Map<String, String> form = decode(h.update);
    assertFalse(form.containsKey("phases[0][add_invoice_items]"),
        "Stripe rejects an empty add_invoice_items parameter; omit it");
    assertEquals(MonthlySecondMonthPromotionService.COUPON,
        form.get("phases[1][discounts][0][coupon]"));
    assertFalse(form.containsKey("phases[0][discounts][0][coupon]"));
    assertFalse(form.containsKey("phases[2][discounts][0][coupon]"));
    verify(billing).markApplied("evt_test");
  }

  @Test void yearlyPaidWebhookRecordsPaymentWithoutCreatingMonthlyPromotion() throws Exception {
    Harness h = new Harness(CheckoutPromotionTerms.describe("req", PRICE_YEAR, 11000, "EUR", "year", true));
    CheckoutRequestStore purchases = mock(CheckoutRequestStore.class);
    BillingEventStore billing = mock(BillingEventStore.class);
    when(purchases.recordPaid("req", "cus_test", "sub_test")).thenReturn(true);
    EtendoGoJwtServlet servlet = new EtendoGoJwtServlet();
    setField(servlet, "checkoutRequestStore", purchases);
    setField(servlet, "billingEventStore", billing);
    JSONObject object = session("standard-v1").put("customer", "cus_test");
    object.getJSONObject("metadata").put("account_email", "test@example.invalid");
    JSONObject event = new JSONObject().put("id", "evt_annual").put("type", "checkout.session.completed")
        .put("created", 1794502900L).put("data", new JSONObject().put("object", object));
    String payload = event.toString();
    CheckoutWebhookProcessor.Acceptance accepted = new CheckoutWebhookProcessor(id -> true, 300)
        .evaluate(payload, sign(payload), "whsec_test", 1794502900L);
    assertEquals(CheckoutWebhookProcessor.Result.ACCEPTED, accepted.result());
    try (MockedConstruction<MonthlySecondMonthPromotionService> constructed = mockConstruction(
        MonthlySecondMonthPromotionService.class, withSettings().defaultAnswer(CALLS_REAL_METHODS),
        (mock, context) -> {
          mock.clock = h.service.clock;
          mock.locks = h.service.locks;
          mock.termsStore = h.service.termsStore;
          mock.events = h.service.events;
          mock.stripe = h.service.stripe;
        })) {
      Method apply = EtendoGoJwtServlet.class.getDeclaredMethod("applyCheckoutPaid", String.class,
          String.class, JSONObject.class);
      apply.setAccessible(true);
      apply.invoke(servlet, accepted.eventId(), "checkout.session.completed", accepted.event());
      assertEquals(1, constructed.constructed().size());
    }
    verify(purchases).recordPaid("req", "cus_test", "sub_test");
    verify(billing).markApplied("evt_annual");
    assertEquals(0, h.providerCalls);
  }

  private static String sign(String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("whsec_test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] bytes = mac.doFinal(("1794502900." + payload).getBytes(StandardCharsets.UTF_8));
    return "t=1794502900,v1=" + java.util.HexFormat.of().formatHex(bytes);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  @Test void monthlyCheckoutPreservesElevenEurosAndInstallsOnlySecondMonthFree() throws Exception {
    StripePriceService.Price price = price(PRICE_MONTH, 1100, "month");
    JSONObject terms = CheckoutPromotionTerms.describe("req", price.getId(), price.getAmountMinor(),
        price.getCurrency(), price.getInterval(), true);
    assertEquals(1100, terms.getLong("initialAmountMinor"));
    assertEquals(2, terms.getInt("firstPaymentCoverageMonths"));
    assertEquals(3, terms.getInt("recurringFromMonth"));

    Harness h = new Harness(terms);
    h.service.applyPaidCheckout(session(CheckoutPromotionTerms.VERSION));
    assertNotNull(h.update, "paid monthly checkout must install the provider schedule");
    Map<String, String> form = decode(h.update);
    assertEquals("1794502800", form.get("phases[0][start_date]"));
    assertEquals("1797094800", form.get("phases[0][end_date]"));
    for (int i = 0; i < 3; i++) {
      assertEquals(PRICE_MONTH, form.get("phases[" + i + "][items][0][price]"));
      assertEquals("1", form.get("phases[" + i + "][items][0][quantity]"));
    }
    assertEquals("1", form.get("phases[1][iterations]"));
    assertEquals("1", form.get("phases[2][iterations]"));
    assertFalse(form.containsKey("phases[0][discounts][0][coupon]"));
    assertEquals(MonthlySecondMonthPromotionService.COUPON,
        form.get("phases[1][discounts][0][coupon]"));
    assertFalse(form.containsKey("phases[2][discounts][0][coupon]"));
    assertEquals("release", form.get("end_behavior"));
    assertTrue(h.rows.containsKey(MonthlySecondMonthPromotionService.APPLIED_TYPE));
  }

  @Test void yearlyCheckoutChargesOneHundredTenEurosForTwelveMonthsWithoutMonthlySchedule() throws Exception {
    StripePriceService.Price price = price(PRICE_YEAR, 11000, "year");
    JSONObject terms = CheckoutPromotionTerms.describe("req", price.getId(), price.getAmountMinor(),
        price.getCurrency(), price.getInterval(), true);
    assertEquals(11000, terms.getLong("initialAmountMinor"));
    assertEquals("year", terms.getString("billingInterval"));
    assertEquals(12, terms.getInt("firstPaymentCoverageMonths"));
    assertFalse(terms.getBoolean("secondMonthFree"));
    assertTrue(terms.getBoolean("yearlyDiscountAlreadyInPrice"));
    Harness h = new Harness(terms);
    h.service.applyPaidCheckout(session("standard-v1"));
    assertEquals(0, h.providerCalls, "annual checkout must not install monthly phases or another discount");
  }

  private static StripePriceService.Price price(String id, int amount, String interval) throws Exception {
    return StripePriceService.parsePrice(new JSONObject().put("id", id).put("active", true)
        .put("unit_amount", amount).put("currency", "eur")
        .put("recurring", new JSONObject().put("interval", interval).put("interval_count", 1)));
  }

  private static JSONObject session(String version) throws Exception {
    return new JSONObject().put("payment_status", "paid").put("subscription", "sub_test")
        .put("metadata", new JSONObject().put("request_id", "req")
            .put("promotion_request_id", "req").put("promotion_version", version));
  }

  private static Map<String, String> decode(String body) {
    Map<String, String> fields = new HashMap<>();
    for (String pair : body.split("&")) {
      String[] kv = pair.split("=", 2);
      fields.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
          URLDecoder.decode(kv.length == 2 ? kv[1] : "", StandardCharsets.UTF_8));
    }
    return fields;
  }

  private static final class Harness {
    final MonthlySecondMonthPromotionService service = new MonthlySecondMonthPromotionService();
    final Map<String, String> rows = new HashMap<>();
    String update;
    int providerCalls;
    Harness(JSONObject terms) {
      service.clock = Clock.fixed(Instant.ofEpochSecond(1794502900), ZoneOffset.UTC);
      service.locks = ignored -> () -> {};
      service.termsStore = new CheckoutPromotionTerms() {
        @Override public JSONObject termsForRequest(String ignored) { return terms; }
      };
      service.events = new BillingEventStore() {
        @Override public String latestReconciliationSummary(String type, String request, String result) {
          return rows.get(type);
        }
        @Override public boolean recordReconciliation(String id, String type, String request,
            String result, String reason, String summary) { rows.put(type, summary); return true; }
      };
      service.stripe = new StripeApiClient() {
        @Override public StripeResponse get(String path) {
          providerCalls++;
          if (path.contains("/coupons/")) return new StripeResponse(200,
              "{\"percent_off\":100,\"duration\":\"forever\",\"valid\":true}");
          return new StripeResponse(200, "{\"id\":\"sub_test\",\"schedule\":null}");
        }
        @Override public StripeResponse postForm(String path, String body) throws StripeTransportException {
          return postForm(path, body, null);
        }
        @Override public StripeResponse postForm(String path, String body, String key) {
          providerCalls++;
          if (path.endsWith("/subscription_schedules")) return new StripeResponse(200,
              "{\"id\":\"sched_test\",\"metadata\":{},\"phases\":[{\"start_date\":1794502800,"
                  + "\"end_date\":1797094800,\"coupon\":null,\"discounts\":[],\"currency\":\"eur\","
                  + "\"add_invoice_items\":[],\"application_fee_percent\":null,\"billing_cycle_anchor\":null,"
                  + "\"billing_thresholds\":null,\"collection_method\":null,\"default_payment_method\":null,"
                  + "\"default_tax_rates\":[],\"description\":null,\"invoice_settings\":null,"
                  + "\"metadata\":{},\"on_behalf_of\":null,\"transfer_data\":null,\"trial_end\":null,"
                  + "\"proration_behavior\":\"create_prorations\","
                  + "\"items\":[{\"billing_thresholds\":null,\"plan\":\"price_test_month\","
                  + "\"price\":\"price_test_month\",\"quantity\":1,\"discounts\":[],"
                  + "\"tax_rates\":[],\"metadata\":{}}]}]}");
          update = body;
          return new StripeResponse(200, "{\"id\":\"sched_test\"}");
        }
      };
    }
  }
}
