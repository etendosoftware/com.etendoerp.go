/* Etendo License. */
package com.etendoerp.go.payment;

import java.net.URLEncoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Function;
import org.openbravo.database.ExternalConnectionPool;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/** Applies a snapshotted second-month gift once; transport failures must keep the webhook retryable. */
public class MonthlySecondMonthPromotionService {
  public static final String APPLIED_TYPE = "promotion.applied";
  static final String COUPON = "etgo_second_month_free_v1";
  private static final String METADATA = "metadata";
  private static final String PROMOTION_PREFIX = "promotion:";
  private static final String SCHEDULE_TYPE = "promotion.schedule";
  private static final String SCHEDULE_ID = "scheduleId";
  private static final String SCHEDULE_PATH = "/v1/subscription_schedules";
  private static final String PROVIDER_UNAVAILABLE = "Promotion provider unavailable";
  private static final String DISCOUNTS = "discounts";
  private static final String COUPON_FIELD = "coupon";
  StripeApiClient stripe = new HttpUrlConnectionStripeApiClient();
  CheckoutPromotionTerms termsStore = new CheckoutPromotionTerms();
  BillingEventStore events = new BillingEventStore();
  Clock clock = Clock.systemUTC();
  Function<String, AutoCloseable> locks = MonthlySecondMonthPromotionService::lock;

  /**
   * Applies the snapshotted promotion after recording payment and before applying its event.
   *
   * @param session the confirmed Stripe Checkout session, including promotion metadata
   */
  public void applyPaidCheckout(JSONObject session) {
    JSONObject metadata = session == null ? null : session.optJSONObject(METADATA);
    String request = metadata == null ? "" : metadata.optString("request_id", "");
    if (request.isBlank() || !CheckoutPromotionTerms.VERSION.equals(metadata.optString("promotion_version"))) return;
    try (AutoCloseable held = locks.apply(request)) {
      applyUnderLock(session);
    } catch (RuntimeException e) { throw e; }
    catch (Exception e) { throw new IllegalStateException("Cannot release promotion lock", e); }
  }

  private void applyUnderLock(JSONObject session) {
    JSONObject metadata = session == null ? null : session.optJSONObject(METADATA);
    String request = metadata == null ? "" : metadata.optString("request_id", "");
    JSONObject terms = termsStore.termsForRequest(request);
    if (terms == null || !terms.optBoolean("secondMonthFree")) return;
    validateConfirmedCheckout(session, metadata, request);
    if (events.latestReconciliationSummary(APPLIED_TYPE, request,
        BillingEventStore.RESULT_APPLIED) != null) return;
    String subscriptionId = reference(session, "subscription");
    if (subscriptionId.isBlank()) throw new IllegalStateException("Promotion requires a subscription");
    try {
      PromotionSchedule current = subscriptionSchedule(request, subscriptionId);
      JSONObject schedule = current.data();
      String scheduleId = current.id();
      if (alreadyConfigured(schedule, request)) {
        markApplied(request, scheduleId);
        return;
      }
      ensureOwnedSchedule(scheduleId, request, subscriptionId);
      JSONObject phase = phaseToExtend(schedule);
      validateItem(phase, terms);
      ensureCoupon();
      post(SCHEDULE_PATH + "/" + encode(scheduleId), phaseForm(phase, request, COUPON),
          PROMOTION_PREFIX + request + ":phases");
      markApplied(request, scheduleId);
    } catch (JSONException e) {
      throw new IllegalStateException("Provider returned incomplete promotion data", e);
    }
  }

  private static void validateConfirmedCheckout(JSONObject session, JSONObject metadata, String request) {
    if (!CheckoutPromotionTerms.VERSION.equals(metadata.optString("promotion_version"))
        || !request.equals(metadata.optString("promotion_request_id"))) {
      throw new IllegalStateException("Checkout promotion metadata does not match its snapshot");
    }
    String paid = session.optString("payment_status");
    if (!("paid".equals(paid) || "no_payment_required".equals(paid))) {
      throw new IllegalStateException("Promotion requires a confirmed checkout payment");
    }
  }

  private record PromotionSchedule(String id, JSONObject data) {}

  private PromotionSchedule subscriptionSchedule(String request, String subscriptionId) throws JSONException {
    JSONObject subscription = get("/v1/subscriptions/" + encode(subscriptionId));
    String scheduleId = reference(subscription, "schedule");
    if (!scheduleId.isBlank()) return new PromotionSchedule(scheduleId, get(SCHEDULE_PATH + "/" + encode(scheduleId)));
    // Stripe disallows phase settings when creating from_subscription. Replaying this
    // request-scoped key recovers a timeout after the provider created the schedule.
    JSONObject schedule = createSchedule(request, subscriptionId);
    scheduleId = schedule.getString("id");
    recordOwnedSchedule(request, scheduleId);
    return new PromotionSchedule(scheduleId, schedule);
  }

  private JSONObject createSchedule(String request, String subscriptionId) {
    return post(SCHEDULE_PATH, "from_subscription=" + encode(subscriptionId),
        PROMOTION_PREFIX + request + ":schedule");
  }

  private static boolean alreadyConfigured(JSONObject schedule, String request) {
    JSONObject metadata = schedule.optJSONObject(METADATA);
    return metadata != null && request.equals(metadata.optString("etgo_promotion_request"))
        && CheckoutPromotionTerms.VERSION.equals(metadata.optString("etgo_promotion_version"));
  }

  private void ensureOwnedSchedule(String scheduleId, String request, String subscriptionId) throws JSONException {
    String owned = events.latestReconciliationSummary(SCHEDULE_TYPE, request, BillingEventStore.RESULT_APPLIED);
    if (owned == null) {
      // Never adopt an unrelated schedule: replay the original creation and compare ids.
      JSONObject replay = createSchedule(request, subscriptionId);
      if (scheduleId.equals(replay.optString("id"))) owned = recordOwnedSchedule(request, scheduleId);
    }
    if (owned == null || !scheduleId.equals(new JSONObject(owned).optString(SCHEDULE_ID))) {
      throw new IllegalStateException("Existing subscription schedule needs promotion review");
    }
  }

  private String recordOwnedSchedule(String request, String scheduleId) throws JSONException {
    String summary = new JSONObject().put(SCHEDULE_ID, scheduleId).toString();
    events.recordReconciliation("promotion-schedule:" + request, SCHEDULE_TYPE, request,
        BillingEventStore.RESULT_APPLIED, null, summary);
    return summary;
  }

  private JSONObject phaseToExtend(JSONObject schedule) throws JSONException {
    JSONArray phases = schedule.optJSONArray("phases");
    if (phases == null || phases.length() != 1) {
      throw new IllegalStateException("Promotion cannot replace an existing phase sequence");
    }
    JSONObject phase = phases.getJSONObject(0);
    if (phase.getLong("end_date") <= clock.instant().getEpochSecond()) {
      throw new IllegalStateException("Second-month promotion installation window has elapsed");
    }
    return phase;
  }

  /** Dedicated connection: DAL ledger commits must not release this cross-process request lock. */
  private static AutoCloseable lock(String request) {
    Connection connection = ExternalConnectionPool.getInstance().getConnection();
    if (connection == null) throw new IllegalStateException("Promotion lock connection unavailable");
    try {
      connection.setAutoCommit(false);
      try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?, ?)")) {
        statement.setInt(1, 6216);
        statement.setInt(2, request.hashCode());
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next() || !result.getBoolean(1)) {
            throw new IllegalStateException("Another worker is applying this promotion");
          }
        }
      }
      return () -> release(connection);
    } catch (SQLException | RuntimeException e) {
      IllegalStateException failure = new IllegalStateException("Cannot acquire promotion lock", e);
      try { release(connection); }
      catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
      throw failure;
    }
  }

  static void release(Connection connection) {
    try (Connection held = connection) {
      held.rollback();
      held.setAutoCommit(true);
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot release promotion connection", e);
    }
  }

  private void markApplied(String request, String schedule) throws JSONException {
    events.recordReconciliation("promotion-applied:" + request, APPLIED_TYPE, request,
        BillingEventStore.RESULT_APPLIED, null, new JSONObject().put(SCHEDULE_ID, schedule).toString());
  }

  private void ensureCoupon() throws JSONException {
    StripeResponse response;
    try { response = stripe.get("/v1/coupons/" + COUPON); }
    catch (IOException e) { throw new IllegalStateException(PROVIDER_UNAVAILABLE, e); }
    JSONObject coupon;
    if (response.status() == 404) {
      coupon = post("/v1/coupons", "id=" + COUPON + "&percent_off=100&duration=forever"
          + "&name=Second%20calendar%20month%20free", PROMOTION_PREFIX + "coupon:v1");
    } else {
      if (!response.isSuccess()) throw new IllegalStateException("Cannot validate promotion coupon");
      coupon = response.json();
    }
    if (coupon.optDouble("percent_off", 0) != 100 || !"forever".equals(coupon.optString("duration"))
        || !coupon.optBoolean("valid")) {
      throw new IllegalStateException("Promotion coupon configuration does not match the contract");
    }
  }

  private static void validateItem(JSONObject phase, JSONObject terms) throws JSONException {
    JSONArray items = phase.getJSONArray("items");
    if (items.length() != 1) throw new IllegalStateException("Promotion requires exactly one plan item");
    JSONObject item = items.getJSONObject(0);
    if (!terms.getString("priceId").equals(reference(item, "price"))
        || item.optLong("quantity", 1) != 1 || !"month".equals(terms.getString("billingInterval"))) {
      throw new IllegalStateException("Schedule item does not match the purchased monthly plan");
    }
  }

  /** Preserve supported phase settings; never duplicate one-time invoice items in future phases. */
  static String phaseForm(JSONObject phase, String request, String coupon) {
    try {
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put("end_behavior", "release");
      fields.put("proration_behavior", "none");
      fields.put("metadata[etgo_promotion_request]", request);
      fields.put("metadata[etgo_promotion_version]", CheckoutPromotionTerms.VERSION);
      for (int index = 0; index < 3; index++) {
        JSONObject copy = new JSONObject(phase.toString());
        copy.remove("currency"); // Derived from items; not an update parameter.
        if (index > 0) {
          copy.remove("start_date");
          copy.remove("end_date");
          copy.remove("add_invoice_items");
          copy.remove("trial_end");
          copy.put("iterations", 1); // Compatible with the existing unversioned API contract.
        }
        copy.put("proration_behavior", "none");
        normalizeDiscounts(copy);
        if (index == 1) {
          JSONArray discounts = copy.optJSONArray(DISCOUNTS);
          if (discounts == null) discounts = new JSONArray();
          discounts.put(new JSONObject().put(COUPON_FIELD, coupon));
          copy.put(DISCOUNTS, discounts);
        }
        flatten(fields, "phases[" + index + "]", copy);
      }
      StringBuilder form = new StringBuilder();
      for (Map.Entry<String, String> entry : fields.entrySet()) {
        if (form.length() > 0) form.append('&');
        form.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
      }
      return form.toString();
    } catch (JSONException e) { throw new IllegalStateException("Cannot preserve schedule phase", e); }
  }

  private static void normalizeDiscounts(JSONObject phase) throws JSONException {
    JSONArray previous = phase.optJSONArray(DISCOUNTS);
    if (previous == null) return;
    JSONArray normalized = new JSONArray();
    for (int i = 0; i < previous.length(); i++) {
      JSONObject discount = previous.getJSONObject(i);
      for (String key : new String[]{"discount", "promotion_code", COUPON_FIELD}) {
        String id = reference(discount, key);
        if (!id.isBlank()) { normalized.put(new JSONObject().put(key, id)); break; }
      }
    }
    phase.put(DISCOUNTS, normalized);
  }

  private static void flatten(Map<String, String> out, String key, Object value) throws JSONException {
    if (value == null || value == JSONObject.NULL) return;
    if (value instanceof JSONObject object) {
      flattenObject(out, key, object);
    } else if (value instanceof JSONArray array) {
      if (array.length() == 0) out.put(key, "");
      for (int i = 0; i < array.length(); i++) flatten(out, key + "[" + i + "]", array.get(i));
    } else out.put(key, String.valueOf(value));
  }

  private static void flattenObject(Map<String, String> out, String key, JSONObject object) throws JSONException {
    Iterator<?> keys = object.keys();
    while (keys.hasNext()) {
      String name = keys.next().toString();
      if ("plan".equals(name)) continue; // Legacy alias of item.price, not a writable field.
      flatten(out, key + "[" + name + "]", writableReference(name, object.get(name)));
    }
  }

  private static Object writableReference(String name, Object value) throws JSONException {
    if (isSingleReference(name) && value instanceof JSONObject expanded) return expanded.getString("id");
    if (("tax_rates".equals(name) || "default_tax_rates".equals(name)) && value instanceof JSONArray refs) {
      JSONArray ids = new JSONArray();
      for (int i = 0; i < refs.length(); i++) {
        Object ref = refs.get(i);
        ids.put(ref instanceof JSONObject expanded ? expanded.getString("id") : ref);
      }
      return ids;
    }
    return value;
  }

  private static boolean isSingleReference(String name) {
    return "price".equals(name) || COUPON_FIELD.equals(name) || "discount".equals(name)
        || "promotion_code".equals(name) || "default_payment_method".equals(name)
        || "on_behalf_of".equals(name) || "destination".equals(name);
  }

  private JSONObject get(String path) {
    try { return checked(stripe.get(path)); }
    catch (IOException e) { throw new IllegalStateException(PROVIDER_UNAVAILABLE, e); }
  }
  private JSONObject post(String path, String body, String key) {
    try { return checked(stripe.postForm(path, body, key)); }
    catch (IOException e) { throw new IllegalStateException(PROVIDER_UNAVAILABLE, e); }
  }
  private static JSONObject checked(StripeResponse response) {
    if (!response.isSuccess()) throw new IllegalStateException("Provider refused promotion operation");
    return response.json();
  }
  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
  private static String reference(JSONObject object, String key) {
    JSONObject expanded = object == null ? null : object.optJSONObject(key);
    return expanded == null ? StripeJson.text(object, key) : StripeJson.text(expanded, "id");
  }
}
