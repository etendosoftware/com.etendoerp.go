/* Etendo License. */
package com.etendoerp.go.payment;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.exception.ConstraintViolationException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.BillingEvent;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/**
 * Durable idempotency gate for provider webhook events, backed by {@code ETGO_BILLING_EVENT}.
 *
 * <p>The claim is the table's unique constraint on {@code EVENT_ID} ({@code ETGO_BILLEVT_EVENT_UQ})
 * and nothing else: the first delivery inserts the row, every later delivery of the same id hits
 * the constraint. That is what makes a Stripe retry after a Tomcat restart, or on a second node,
 * arrive as a duplicate instead of being applied again — the state lives in the database, never
 * in the process.
 *
 * <p>One row per event id. Redeliveries do not add rows; they bump {@code DUPLICATE_COUNT} and
 * {@code LAST_DUPLICATE_AT} on the row that won the claim, so the audit trail stays one line per
 * event and still shows how often the provider knocked.
 *
 * <p>Lifecycle: {@code RECEIVED} → {@code APPLIED} | {@code IGNORED} | {@code FAILED}.
 * {@code APPLIED} and {@code IGNORED} are terminal. {@code FAILED} is deliberately not: the next
 * delivery of the same id flips the row back to {@code RECEIVED} atomically and is treated as a
 * fresh claim, so the provider's own retry schedule repairs a transient handler failure without
 * anyone touching the database. {@code APPLIED} therefore stays strictly once-only.
 *
 * <p>Same conventions as {@link CheckoutRequestStore}: every method opens the system context
 * ({@code "0","0","0","0"} plus admin mode) and restores it in a {@code finally}, because the
 * webhook is matched before the authentication chain and has no {@link OBContext} at all; rows are
 * stored at client and organization {@code 0}; bulk HQL names the entity as
 * {@link BillingEvent#ENTITY_NAME} (the Java simple name is not registered and throws at runtime).
 *
 * <p>No card data and no full provider payload is ever written here. {@link #summarize} is the
 * only path into {@code PAYLOAD_SUMMARY} and it is an allow-list.
 */
public class BillingEventStore implements CheckoutWebhookProcessor.EventStore {
  private static final Logger log = LogManager.getLogger();

  private static final String ZERO_ID = "0";
  private static final String UNIQUE_EVENT_CONSTRAINT = "ETGO_BILLEVT_EVENT_UQ";
  /** PostgreSQL SQLSTATE for a unique-constraint violation. */
  private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

  static final String PROVIDER_STRIPE = "stripe";
  /** Recorded when a caller claims through the id-only {@link #claim(String)} form. */
  static final String EVENT_TYPE_UNKNOWN = "unknown";

  static final String RESULT_RECEIVED = "RECEIVED";
  static final String RESULT_APPLIED = "APPLIED";
  static final String RESULT_IGNORED = "IGNORED";
  static final String RESULT_FAILED = "FAILED";

  private static final int EVENT_TYPE_WIDTH = 120;
  private static final int REQUEST_ID_WIDTH = 64;
  private static final int REASON_WIDTH = 255;
  private static final int SUMMARY_WIDTH = 2000;

  /** The only {@code data.object} keys that may reach {@code PAYLOAD_SUMMARY}. */
  private static final List<String> SUMMARY_KEYS = Arrays.asList("id", "customer",
      "subscription", "livemode", "payment_status", "amount_total", "currency", "mode");

  /**
   * Id-only claim, kept for the {@link CheckoutWebhookProcessor.EventStore} contract.
   *
   * <p>{@code EVENT_TYPE} is mandatory on the row, so this form records it as
   * {@value #EVENT_TYPE_UNKNOWN}. Production goes through the rich form.
   */
  @Override
  public boolean claim(String eventId) {
    return claim(eventId, null, null, null);
  }

  /**
   * Claims an event id, or records that it arrived again.
   *
   * <ol>
   *   <li>Insert a {@code RECEIVED} row. Success means this delivery won the claim: {@code true}.
   *   <li>A unique violation on {@code EVENT_ID} means a row already exists. The insert is rolled
   *       back and one conditional bulk update flips the row from {@code FAILED} back to
   *       {@code RECEIVED} (clearing {@code PROCESSED_AT} and {@code FAILURE_REASON}). One affected
   *       row means a previous delivery failed and this one re-claims it: {@code true}.
   *   <li>Otherwise the row is {@code RECEIVED}, {@code APPLIED} or {@code IGNORED} — a genuine
   *       duplicate. {@code DUPLICATE_COUNT} is incremented and {@code LAST_DUPLICATE_AT} set:
   *       {@code false}.
   * </ol>
   *
   * <p>Every step commits on its own, and step 2 is atomic without a row lock because the
   * affected-row count of a conditional update is the answer: two concurrent deliveries of a
   * {@code FAILED} event mean exactly one of them sees {@code true}.
   *
   * <p>When {@code requestId} names an existing {@code ETGO_CHECKOUT_REQUEST}, the row is linked
   * to it through {@code ETGO_CHECKOUT_REQUEST_ID} at claim time; the raw {@code REQUEST_ID}
   * string is always stored regardless, because an event may legitimately reference a request
   * this instance never issued. Duplicates and re-claims never touch either.
   *
   * @param eventId provider event identifier; must not be blank
   * @param eventType provider event type, blank recorded as {@value #EVENT_TYPE_UNKNOWN}
   * @param requestId {@code metadata.request_id} correlation, or null
   * @param summary allow-listed payload summary from {@link #summarize}, or null
   * @return true when this delivery must be processed, false when it is a duplicate
   * @throws IllegalArgumentException when {@code eventId} is blank — the processor turns a blank id
   *     into {@code INVALID_PAYLOAD} before ever consulting the store, so reaching this point with
   *     one is a caller bug and must not silently look like a duplicate
   */
  @Override
  public boolean claim(String eventId, String eventType, String requestId, String summary) {
    String id = StringUtils.trimToNull(eventId);
    if (id == null) {
      throw new IllegalArgumentException("A billing event cannot be claimed without an event id");
    }
    Date now = new Date();
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      if (insertReceived(id, eventType, requestId, summary, now)) {
        return true;
      }
      if (reclaimFailed(id, now)) {
        log.info("Billing event '{}' re-claimed after a failed delivery", id);
        return true;
      }
      recordDuplicate(id, now);
      return false;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Marks the claimed event as applied — its business effect was recorded.
   * @param eventId provider event identifier
   */
  public void markApplied(String eventId) {
    updateResult(eventId, RESULT_APPLIED, null);
  }

  /**
   * Marks the claimed event as deliberately not acted on.
   * @param eventId provider event identifier
   * @param reason operationally safe explanation, truncated to the column width
   */
  public void markIgnored(String eventId, String reason) {
    updateResult(eventId, RESULT_IGNORED, reason);
  }

  /**
   * Marks the claimed event as failed, making it re-claimable by the next delivery.
   *
   * <p>Never throws: the caller is already on a failure path, and a failed annotation must not
   * turn a recoverable problem into a second one. If this write is lost the row simply stays
   * {@code RECEIVED}, which the next delivery treats as a duplicate — the provider then stops
   * retrying and the event is visible in the Classic window as stuck, rather than applied twice.
   *
   * @param eventId provider event identifier
   * @param reason operationally safe failure reason, truncated to the column width
   */
  public void markFailed(String eventId, String reason) {
    try {
      updateResult(eventId, RESULT_FAILED, reason);
    } catch (RuntimeException e) {
      log.error("Could not mark billing event '{}' as failed", eventId, e);
    }
  }

  /**
   * Builds the allow-listed summary that may be stored alongside a claim.
   *
   * <p>Keeps {@code data.object.{id, customer, subscription, livemode, payment_status,
   * amount_total, currency, mode}} and {@code data.object.metadata.request_id}, nothing else. A
   * value that is itself an object (an expanded {@code customer}) contributes only its {@code id};
   * arrays are dropped. The raw body, card data, {@code payment_method_details} and anything not
   * named above never reach the column. The result is abbreviated to the column width, so a
   * pathological input can produce a truncated, non-parseable summary — it is an operator aid,
   * not a data source.
   *
   * @param event parsed provider event
   * @return compact JSON text, or null when nothing allow-listed is present
   */
  public static String summarize(JSONObject event) {
    if (event == null) {
      return null;
    }
    JSONObject data = event.optJSONObject("data");
    JSONObject object = data == null ? null : data.optJSONObject("object");
    if (object == null) {
      return null;
    }
    try {
      JSONObject summary = new JSONObject();
      for (String key : SUMMARY_KEYS) {
        Object value = scalarOrId(object.opt(key));
        if (value != null) {
          summary.put(key, value);
        }
      }
      JSONObject metadata = object.optJSONObject("metadata");
      String requestId = metadata == null ? null
          : StringUtils.trimToNull(metadata.optString("request_id", ""));
      if (requestId != null) {
        summary.put("metadata", new JSONObject().put("request_id", requestId));
      }
      return summary.length() == 0 ? null
          : StringUtils.abbreviate(summary.toString(), SUMMARY_WIDTH);
    } catch (JSONException e) {
      log.warn("Could not summarize a billing event payload", e);
      return null;
    }
  }

  /**
   * Reduces an allow-listed value to something safe to store: a scalar as-is, an object as its
   * {@code id}, anything else (arrays, null, JSON null) as nothing.
   */
  private static Object scalarOrId(Object value) {
    if (value == null || JSONObject.NULL.equals(value) || value instanceof JSONArray) {
      return null;
    }
    if (value instanceof JSONObject) {
      return StringUtils.trimToNull(((JSONObject) value).optString("id", ""));
    }
    return value;
  }

  /**
   * Step 1 of the claim. Returns false only for a unique violation on the event id; any other
   * failure propagates after the session is rolled back.
   */
  private boolean insertReceived(String eventId, String eventType, String requestId,
      String summary, Date now) {
    String correlation = StringUtils.abbreviate(StringUtils.trimToNull(requestId),
        REQUEST_ID_WIDTH);
    try {
      BillingEvent event = OBProvider.getInstance().get(BillingEvent.class);
      event.setClient(OBDal.getInstance().get(Client.class, ZERO_ID));
      event.setOrganization(OBDal.getInstance().get(Organization.class, ZERO_ID));
      event.setEvent(eventId);
      event.setEventType(StringUtils.abbreviate(
          StringUtils.defaultIfBlank(StringUtils.trim(eventType), EVENT_TYPE_UNKNOWN),
          EVENT_TYPE_WIDTH));
      event.setProvider(PROVIDER_STRIPE);
      event.setEventResult(RESULT_RECEIVED);
      event.setRequestID(correlation);
      event.setEtgoCheckoutRequest(CheckoutRequestStore.findByRequestId(correlation));
      event.setPayloadSummary(StringUtils.abbreviate(StringUtils.trimToNull(summary),
          SUMMARY_WIDTH));
      event.setReceivedAt(now);
      event.setDuplicateCount(0L);
      OBDal.getInstance().save(event);
      flushAndCommit();
      return true;
    } catch (RuntimeException e) {
      OBDal.getInstance().rollbackAndClose();
      if (isUniqueEventViolation(e)) {
        return false;
      }
      throw e;
    }
  }

  /** Step 2 of the claim: atomically re-opens a {@code FAILED} row. */
  private boolean reclaimFailed(String eventId, Date now) {
    int reclaimed = OBDal.getInstance()
        .getSession()
        .createQuery("update " + BillingEvent.ENTITY_NAME + " be"
            + "   set be.eventResult = :received,"
            + "       be.processedAt = null,"
            + "       be.failureReason = null,"
            + "       be.updated = :now"
            + " where be.event = :eventId"
            + "   and be.eventResult = :failed")
        .setParameter("received", RESULT_RECEIVED)
        .setParameter("failed", RESULT_FAILED)
        .setParameter("now", now)
        .setParameter("eventId", eventId)
        .executeUpdate();
    flushAndCommit();
    return reclaimed == 1;
  }

  /** Step 3 of the claim: counts the redelivery on the row that won. */
  private void recordDuplicate(String eventId, Date now) {
    int counted = OBDal.getInstance()
        .getSession()
        .createQuery("update " + BillingEvent.ENTITY_NAME + " be"
            + "   set be.duplicateCount = be.duplicateCount + 1,"
            + "       be.lastDuplicateAt = :now,"
            + "       be.updated = :now"
            + " where be.event = :eventId")
        .setParameter("now", now)
        .setParameter("eventId", eventId)
        .executeUpdate();
    flushAndCommit();
    if (counted == 0) {
      // The insert hit the unique constraint a moment ago, so the row exists; reaching here means
      // something deleted it in between. Not an error for the caller, but worth a trace.
      log.warn("Billing event '{}' was a duplicate but its row could not be found", eventId);
    }
  }

  /**
   * Shared terminal-state write. {@code PROCESSED_AT} is first-write-wins via {@code coalesce} so
   * it keeps meaning "when the result was first decided"; the reason column is overwritten when
   * one is given and left alone otherwise.
   */
  private void updateResult(String eventId, String result, String reason) {
    String id = StringUtils.trimToNull(eventId);
    if (id == null) {
      return;
    }
    OBContext.setOBContext(ZERO_ID, ZERO_ID, ZERO_ID, ZERO_ID);
    OBContext.setAdminMode(true);
    try {
      String trimmedReason = StringUtils.abbreviate(StringUtils.trimToNull(reason), REASON_WIDTH);
      String setReason = trimmedReason == null ? "" : ", be.failureReason = :reason";
      org.hibernate.query.Query<?> update = OBDal.getInstance()
          .getSession()
          .createQuery("update " + BillingEvent.ENTITY_NAME + " be"
              + "   set be.eventResult = :result,"
              + "       be.processedAt = coalesce(be.processedAt, :now),"
              + "       be.updated = :now"
              + setReason
              + " where be.event = :eventId")
          .setParameter("result", result)
          .setParameter("now", new Date())
          .setParameter("eventId", id);
      if (trimmedReason != null) {
        update.setParameter("reason", trimmedReason);
      }
      int updated = update.executeUpdate();
      flushAndCommit();
      if (updated == 0) {
        log.warn("No billing event row found for '{}' while marking it {}", id, result);
      }
    } catch (RuntimeException e) {
      OBDal.getInstance().rollbackAndClose();
      throw e;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Whether a failure is the event-id unique constraint firing, wherever the driver's exception
   * ended up in the cause chain ({@code OBException}, {@code PersistenceException} and
   * {@code ConstraintViolationException} all wrap it at different layers).
   *
   * <p>When Hibernate reports the constraint name it must be the event-id one; a violation of a
   * different constraint on the same table is a real error, not a duplicate.
   */
  static boolean isUniqueEventViolation(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException) {
        String constraint = ((ConstraintViolationException) cause).getConstraintName();
        return constraint == null
            || StringUtils.containsIgnoreCase(constraint, UNIQUE_EVENT_CONSTRAINT);
      }
      if (cause instanceof SQLException
          && SQLSTATE_UNIQUE_VIOLATION.equals(((SQLException) cause).getSQLState())) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }

  private void flushAndCommit() {
    OBDal.getInstance().flush();
    OBDal.getInstance().commitAndClose();
  }
}
