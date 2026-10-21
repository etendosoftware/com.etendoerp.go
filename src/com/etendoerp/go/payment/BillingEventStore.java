/* Etendo License. */
package com.etendoerp.go.payment;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.exception.ConstraintViolationException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
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
 * {@code APPLIED} is terminal and enforced as such: no later write may move a row out of it, so a
 * late failure on a redelivery cannot overwrite the fact that the payment was applied.
 * {@code IGNORED} is terminal by intent but not locked, because a later delivery of the same id
 * may legitimately carry the correlation the ignored one lacked. {@code FAILED} is deliberately
 * not terminal: the next delivery of the same id flips the row back to {@code RECEIVED}
 * atomically and is treated as a fresh claim, so the provider's own retry schedule repairs a
 * transient handler failure without anyone touching the database.
 *
 * <p>Same conventions as {@link CheckoutRequestStore}: every method opens the system context
 * ({@code "0","0","0","0"} plus admin mode) and restores it in a {@code finally}, because the
 * webhook is matched before the authentication chain and has no {@link OBContext} at all; rows are
 * stored at client and organization {@code 0}; bulk HQL names the entity as
 * {@link BillingEvent#ENTITY_NAME} (the Java simple name is not registered and throws at runtime).
 *
 * <p>No card data and no full provider payload is ever written here.
 * {@link WebhookPayloadSummary#summarize} is the only path into {@code PAYLOAD_SUMMARY} and it is
 * an allow-list.
 *
 * <p><b>Accepted crash window — at-most-once, not exactly-once.</b> The claim commits before the
 * handler runs, so a JVM kill in between leaves a committed {@code RECEIVED} row with nothing
 * applied. The next delivery reads that row as a duplicate and answers 200, so the payment is
 * never applied <em>and</em> never retried; it is visible only as a row stuck in {@code RECEIVED}.
 * Committing after the handler instead would trade this for double-application, which is worse for
 * a payment, so the window is accepted deliberately. ETP-5048's billing reconciliation closes it:
 * it re-reads the provider state such an event could have moved, then marks a row left
 * {@code RECEIVED} past a threshold ({@link #findStaleReceived}) {@code IGNORED} as superseded —
 * but only one whose own request or subscription it re-read in full; any other it parks for
 * review. It never replays the event through this at-most-once path. The narrower lost-write
 * variant is noted on {@link #markFailed}.
 */
public class BillingEventStore implements CheckoutWebhookProcessor.EventStore {
  private static final Logger log = LogManager.getLogger();

  private static final String ZERO_ID = "0";
  private static final String UNIQUE_EVENT_CONSTRAINT = "ETGO_BILLEVT_EVENT_UQ";
  /** PostgreSQL SQLSTATE for a unique-constraint violation. */
  private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

  static final String PROVIDER_STRIPE = "stripe";
  /** ETP-5048 — the {@code PROVIDER} of a row the billing reconciliation wrote. */
  public static final String PROVIDER_RECONCILER = "reconciler";
  /** Recorded when a caller claims through the id-only {@link #claim(String)} form. */
  static final String EVENT_TYPE_UNKNOWN = "unknown";

  static final String RESULT_RECEIVED = "RECEIVED";
  public static final String RESULT_APPLIED = "APPLIED";
  public static final String RESULT_IGNORED = "IGNORED";
  public static final String RESULT_FAILED = "FAILED";

  private static final int EVENT_TYPE_WIDTH = 120;
  private static final int REQUEST_ID_WIDTH = 64;
  private static final int REASON_WIDTH = 255;
  private static final int EVENT_ID_WIDTH = 255;
  /**
   * Opening clause shared by every bulk update in this class. The entity is named by
   * {@link BillingEvent#ENTITY_NAME} rather than the Java simple name, which Etendo does not
   * register and which therefore throws at runtime — see the note on
   * {@link CheckoutRequestStore#claimForProvisioning}.
   */
  private static final String UPDATE_BILLING_EVENT =
      "update " + BillingEvent.ENTITY_NAME + " be";
  /** Name of the event-id parameter bound by every one of those updates. */
  private static final String PARAM_EVENT_ID = "eventId";
  private static final int SUMMARY_WIDTH = WebhookPayloadSummary.MAX_LENGTH;
  /** ETP-5048 — the rows of one type the billing reconciliation wrote. */
  private static final String RECONCILIATION_ROWS =
      "as be where be.provider = :provider and be.eventType = :eventType";
  private static final String PARAM_PROVIDER = "provider";
  private static final String PARAM_EVENT_TYPE = "eventType";
  private static final String PARAM_REQUEST_ID = "requestId";
  private static final String PARAM_RESULT = "result";

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
   *       {@code false}. If that update matches <em>no</em> row the premise was wrong — the insert
   *       failed on something other than the event-id unique constraint — and the original failure
   *       is rethrown rather than reported as a duplicate.
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
    return runAsSystem(() -> {
      RuntimeException insertFailure = insertReceived(id, eventType, requestId, summary, now);
      if (insertFailure == null) {
        return true;
      }
      if (reclaimFailed(id, now)) {
        log.info("Billing event '{}' re-claimed after a failed delivery", id);
        return true;
      }
      recordDuplicate(id, now, insertFailure);
      return false;
    });
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
   * ETP-5048 — one Stripe event a crash left {@code RECEIVED}.
   *
   * @param eventId provider event id
   * @param eventType provider event type
   * @param requestId the correlation id the event carried, or null
   * @param summary the allow-listed {@code PAYLOAD_SUMMARY}, or null
   */
  public record StaleEvent(String eventId, String eventType, String requestId, String summary) {

    /**
     * A stuck event without its summary.
     *
     * @param eventId provider event id
     * @param eventType provider event type
     * @param requestId the correlation id the event carried, or null
     */
    public StaleEvent(String eventId, String eventType, String requestId) {
      this(eventId, eventType, requestId, null);
    }
  }

  /**
   * ETP-5048 — the provider events still {@code RECEIVED} that were received before
   * {@code receivedBefore}, oldest first: the crash window this class documents, where an event was
   * claimed and never applied. Rows this store wrote for the reconciliation are never returned.
   *
   * @param receivedBefore only events received before this instant
   * @param max how many at most
   * @return the stuck events
   */
  public List<StaleEvent> findStaleReceived(Date receivedBefore, int max) {
    return findStaleReceived(receivedBefore, null, null, 0, max);
  }

  /**
   * ETP-5048 — one page of the provider events still {@code RECEIVED} that were received before
   * {@code receivedBefore}, oldest first, narrowed for the billing reconciliation's sweep.
   *
   * @param receivedBefore only events received before this instant
   * @param types only these event types; null for every type
   * @param reviewIdPrefix leave out an event for which a row {@code <prefix><event id>} exists (one
   *     parked for review); null to leave none out
   * @param offset how many matching events to skip
   * @param max how many at most
   * @return the stuck events
   */
  public List<StaleEvent> findStaleReceived(Date receivedBefore, Collection<String> types,
      String reviewIdPrefix, int offset, int max) {
    if (types != null && types.isEmpty()) {
      return new ArrayList<>();
    }
    return runAsSystem(() -> {
      StringBuilder where = new StringBuilder("as be where be.eventResult = :received"
          + " and be.provider = :provider and be.receivedAt < :before");
      if (types != null) {
        where.append(" and be.eventType in (:types)");
      }
      if (reviewIdPrefix != null) {
        where.append(" and not exists (select 1 from ").append(BillingEvent.ENTITY_NAME)
            .append(" rv where rv.event = concat(:reviewPrefix, be.event))");
      }
      OBQuery<BillingEvent> query = OBDal.getInstance().createQuery(BillingEvent.class,
              where.append(" order by be.receivedAt asc").toString())
          .setNamedParameter("received", RESULT_RECEIVED)
          .setNamedParameter(PARAM_PROVIDER, PROVIDER_STRIPE)
          .setNamedParameter("before", receivedBefore)
          .setFilterOnReadableClients(false)
          .setFilterOnReadableOrganization(false)
          .setFirstResult(Math.max(0, offset))
          .setMaxResult(Math.max(1, max));
      if (types != null) {
        query.setNamedParameter("types", types);
      }
      if (reviewIdPrefix != null) {
        query.setNamedParameter("reviewPrefix", reviewIdPrefix);
      }
      List<StaleEvent> stale = new ArrayList<>();
      for (BillingEvent event : query.list()) {
        stale.add(new StaleEvent(event.getEvent(), event.getEventType(), event.getRequestID(),
            event.getPayloadSummary()));
      }
      return stale;
    });
  }

  /**
   * ETP-5048 — whether a row with this event id exists. The billing reconciliation uses
   * deterministic ids, so an existing row means that repair or alert was already recorded.
   *
   * @param eventId the event id
   * @return whether a row exists
   */
  public boolean exists(String eventId) {
    String id = StringUtils.trimToNull(eventId);
    if (id == null) {
      return false;
    }
    return runAsSystem(() -> !OBDal.getInstance().createQuery(BillingEvent.class,
            "as be where be.event = :eventId")
        .setNamedParameter(PARAM_EVENT_ID, id)
        .setFilterOnReadableClients(false)
        .setFilterOnReadableOrganization(false)
        .setMaxResult(1)
        .list()
        .isEmpty());
  }

  /**
   * ETP-5048 — whether the billing reconciliation holds an open ({@code FAILED}) row of this type
   * for a request: for {@code reconciliation.needs-review}, a purchase parked for review. An
   * operator releases it by setting the row to {@code IGNORED}.
   *
   * @param eventType a reconciliation event type
   * @param requestId the checkout correlation id
   * @return whether such a row exists
   */
  public boolean hasOpenReconciliation(String eventType, String requestId) {
    return runAsSystem(() -> reconciliationRows(eventType, requestId, RESULT_FAILED)
        .setMaxResult(1).count() > 0);
  }

  /**
   * ETP-5048 — how many rows of this type the billing reconciliation wrote for a request, whatever
   * their result.
   *
   * @param eventType a reconciliation event type
   * @param requestId the checkout correlation id
   * @return the count
   */
  public int countReconciliations(String eventType, String requestId) {
    return runAsSystem(() -> reconciliationRows(eventType, requestId, null).count());
  }

  /**
   * ETP-5048 — how many open ({@code FAILED}) rows of this type the billing reconciliation holds,
   * across requests: for {@code reconciliation.needs-review}, the purchases awaiting review.
   *
   * @param eventType a reconciliation event type
   * @return the count
   */
  public int countOpenReconciliations(String eventType) {
    return runAsSystem(() -> reconciliationRows(eventType, null, RESULT_FAILED).count());
  }

  /**
   * ETP-5048 — the {@code PAYLOAD_SUMMARY} of the newest row of this type and result the billing
   * reconciliation wrote for a request.
   *
   * @param eventType a reconciliation event type
   * @param requestId the checkout correlation id
   * @param result the row result, e.g. {@link #RESULT_IGNORED} for a released review
   * @return the summary, or null when there is no such row or it has none
   */
  public String latestReconciliationSummary(String eventType, String requestId, String result) {
    return runAsSystem(() -> {
      List<BillingEvent> rows = OBDal.getInstance().createQuery(BillingEvent.class,
              RECONCILIATION_ROWS + " and be.requestID = :requestId and be.eventResult = :result"
                  + " order by be.receivedAt desc")
          .setNamedParameter(PARAM_PROVIDER, PROVIDER_RECONCILER)
          .setNamedParameter(PARAM_EVENT_TYPE, eventType)
          .setNamedParameter(PARAM_REQUEST_ID, requestId)
          .setNamedParameter(PARAM_RESULT, result)
          .setFilterOnReadableClients(false)
          .setFilterOnReadableOrganization(false)
          .setMaxResult(1)
          .list();
      return rows.isEmpty() ? null : rows.get(0).getPayloadSummary();
    });
  }

  /**
   * ETP-5048 — refreshes a reconciliation marker row, or records it ({@link #RESULT_FAILED}) when
   * there is none yet: {@code LAST_DUPLICATE_AT} becomes now, {@code DUPLICATE_COUNT} grows by one
   * and the reason is replaced. One row per marker, however often it is touched. Commits.
   *
   * <p>Update first, insert only when no row matched, so a refresh never logs a duplicate-key
   * error. Race-safe: an insert that loses to a concurrent one (the deterministic id is unique)
   * falls back to the update.
   *
   * @param eventId deterministic id
   * @param eventType e.g. {@code reconciliation.retry-later}
   * @param requestId the checkout correlation id
   * @param reason operationally safe explanation
   */
  public void touchReconciliation(String eventId, String eventType, String requestId,
      String reason) {
    if (refreshMarker(eventId, reason) > 0) {
      return;
    }
    if (!recordReconciliation(eventId, eventType, requestId, RESULT_FAILED, reason, null)) {
      refreshMarker(eventId, reason); // a concurrent caller inserted it first
    }
  }

  /** @return how many rows the refresh updated, 0 or 1; committed */
  private int refreshMarker(String eventId, String reason) {
    Date now = new Date();
    return runAsSystem(() -> {
      int updated = OBDal.getInstance().getSession().createQuery(UPDATE_BILLING_EVENT
              + "   set be.duplicateCount = be.duplicateCount + 1,"
              + "       be.lastDuplicateAt = :now,"
              + "       be.failureReason = :reason,"
              + "       be.updated = :now"
              + " where be.event = :eventId")
          .setParameter("now", now)
          .setParameter("reason", StringUtils.abbreviate(StringUtils.trimToNull(reason),
              REASON_WIDTH))
          .setParameter(PARAM_EVENT_ID, StringUtils.trim(eventId))
          .executeUpdate();
      flushAndCommit();
      return updated;
    });
  }

  /**
   * ETP-5048 — renames a reconciliation marker row, which keeps it as history and frees its
   * deterministic id for the next occurrence (a pause episode that ended). Commits.
   *
   * @param eventId the current id
   * @param newEventId the id it is kept under
   * @param reason operationally safe note, stored as the row's reason
   * @return whether a row was renamed
   */
  public boolean renameReconciliationMarker(String eventId, String newEventId, String reason) {
    Date now = new Date();
    return runAsSystem(() -> {
      int renamed = OBDal.getInstance().getSession().createQuery(UPDATE_BILLING_EVENT
              + "   set be.event = :newEventId,"
              + "       be.failureReason = :reason,"
              + "       be.updated = :now"
              + " where be.event = :eventId and be.provider = :provider")
          .setParameter("newEventId", StringUtils.abbreviate(newEventId, EVENT_ID_WIDTH))
          .setParameter("reason", StringUtils.abbreviate(StringUtils.trimToNull(reason),
              REASON_WIDTH))
          .setParameter("now", now)
          .setParameter(PARAM_EVENT_ID, StringUtils.trim(eventId))
          .setParameter(PARAM_PROVIDER, PROVIDER_RECONCILER)
          .executeUpdate();
      flushAndCommit();
      return renamed > 0;
    });
  }

  private static OBQuery<BillingEvent> reconciliationRows(
      String eventType, String requestId, String result) {
    StringBuilder where = new StringBuilder(RECONCILIATION_ROWS);
    if (requestId != null) {
      where.append(" and be.requestID = :requestId");
    }
    if (result != null) {
      where.append(" and be.eventResult = :result");
    }
    var query = OBDal.getInstance().createQuery(BillingEvent.class, where.toString())
        .setNamedParameter(PARAM_PROVIDER, PROVIDER_RECONCILER)
        .setNamedParameter(PARAM_EVENT_TYPE, eventType)
        .setFilterOnReadableClients(false)
        .setFilterOnReadableOrganization(false);
    if (requestId != null) {
      query.setNamedParameter(PARAM_REQUEST_ID, requestId);
    }
    if (result != null) {
      query.setNamedParameter(PARAM_RESULT, result);
    }
    return query;
  }

  /**
   * ETP-5048 — records one repair or alert of the billing reconciliation as its own row, so it is
   * visible in the Billing Event window (and, linked by {@code requestId}, under its Checkout
   * Request) rather than only in a log.
   *
   * <p><b>Commits the caller's pending changes with it.</b> A repair and its audit row are one
   * transaction: the repair is never committed without the row that explains it, and a failed
   * insert rolls both back. A row that already exists (the ids are deterministic) answers
   * {@code false} after rolling back.
   *
   * @param eventId deterministic id, e.g. {@code reconcile:checkout-expired:<requestId>}
   * @param eventType e.g. {@code reconciliation.checkout-expired}
   * @param requestId the checkout correlation id, or null when the repair names none
   * @param result {@link #RESULT_APPLIED}, {@link #RESULT_IGNORED} or {@link #RESULT_FAILED}
   * @param reason operationally safe explanation, never customer or card data; may be null
   * @param summary allow-listed facts (provider ids, statuses), may be null
   * @return true when this call wrote the row
   */
  public boolean recordReconciliation(String eventId, String eventType, String requestId,
      String result, String reason, String summary) {
    String id = StringUtils.trimToNull(eventId);
    if (id == null) {
      throw new IllegalArgumentException("A reconciliation row needs an event id");
    }
    Date now = new Date();
    return runAsSystem(() -> {
      String correlation = StringUtils.abbreviate(StringUtils.trimToNull(requestId),
          REQUEST_ID_WIDTH);
      try {
        BillingEvent event = OBProvider.getInstance().get(BillingEvent.class);
        event.setClient(OBDal.getInstance().get(Client.class, ZERO_ID));
        event.setOrganization(OBDal.getInstance().get(Organization.class, ZERO_ID));
        event.setEvent(id);
        event.setEventType(StringUtils.abbreviate(StringUtils.trim(eventType), EVENT_TYPE_WIDTH));
        event.setProvider(PROVIDER_RECONCILER);
        event.setEventResult(result);
        event.setRequestID(correlation);
        event.setEtgoCheckoutRequest(CheckoutRequestStore.findByRequestId(correlation));
        event.setPayloadSummary(StringUtils.abbreviate(StringUtils.trimToNull(summary),
            SUMMARY_WIDTH));
        event.setFailureReason(StringUtils.abbreviate(StringUtils.trimToNull(reason),
            REASON_WIDTH));
        event.setReceivedAt(now);
        event.setProcessedAt(now);
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
    });
  }

  /**
   * Builds the allow-listed summary that may be stored in {@code PAYLOAD_SUMMARY}.
   *
   * <p>A thin delegate to {@link WebhookPayloadSummary#summarize}, which owns the policy: the
   * allow-list is a pure JSON function shared with {@link CheckoutWebhookProcessor} and must not
   * depend on this store. Kept here because the column it guards is this store's, and because the
   * specs that pin what may never be written read more honestly next to it.
   *
   * @param event parsed provider event
   * @return compact JSON text, or null when nothing allow-listed is present
   */
  public static String summarize(JSONObject event) {
    return WebhookPayloadSummary.summarize(event);
  }

  /**
   * Step 1 of the claim.
   *
   * <p>Returns null when the row was inserted. A suspected unique violation on the event id is
   * returned rather than swallowed, so that step 3 can rethrow it if the row it implies turns out
   * not to exist; any other failure propagates immediately after the session is rolled back.
   *
   * @return null on success, or the failure that looked like a duplicate
   */
  private RuntimeException insertReceived(String eventId, String eventType, String requestId,
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
      return null;
    } catch (RuntimeException e) {
      OBDal.getInstance().rollbackAndClose();
      if (isUniqueEventViolation(e)) {
        return e;
      }
      throw e;
    }
  }

  /** Step 2 of the claim: atomically re-opens a {@code FAILED} row. */
  private boolean reclaimFailed(String eventId, Date now) {
    int reclaimed = OBDal.getInstance()
        .getSession()
        .createQuery(UPDATE_BILLING_EVENT
            + "   set be.eventResult = :received,"
            + "       be.processedAt = null,"
            + "       be.failureReason = null,"
            + "       be.updated = :now"
            + " where be.event = :eventId"
            + "   and be.eventResult = :failed")
        .setParameter("received", RESULT_RECEIVED)
        .setParameter("failed", RESULT_FAILED)
        .setParameter("now", now)
        .setParameter(PARAM_EVENT_ID, eventId)
        .executeUpdate();
    flushAndCommit();
    return reclaimed == 1;
  }

  /**
   * Step 3 of the claim: counts the redelivery on the row that won.
   *
   * <p>Matching no row disproves the reason we are here. {@link #isUniqueEventViolation} accepts a
   * {@link ConstraintViolationException} whose constraint name the driver did not report, which a
   * violation of {@code ETGO_BILLEVT_CHKREQ_FK} or {@code ETGO_BILLEVT_RESULT_CHK} can also
   * produce — and in those cases nothing was ever inserted. Returning {@code false} there would
   * make the servlet answer 200 to an event that was never recorded and never applied, and the
   * provider would never retry it. So the original failure is rethrown: the request fails, the
   * provider retries, and the event survives.
   *
   * @param insertFailure the failure that was read as a duplicate, rethrown when no row matches
   */
  private void recordDuplicate(String eventId, Date now, RuntimeException insertFailure) {
    int counted = OBDal.getInstance()
        .getSession()
        .createQuery(UPDATE_BILLING_EVENT
            + "   set be.duplicateCount = be.duplicateCount + 1,"
            + "       be.lastDuplicateAt = :now,"
            + "       be.updated = :now"
            + " where be.event = :eventId")
        .setParameter("now", now)
        .setParameter(PARAM_EVENT_ID, eventId)
        .executeUpdate();
    flushAndCommit();
    if (counted == 0) {
      log.error("Billing event '{}' looked like a duplicate but no row exists — the insert failed "
          + "on something other than the event-id unique constraint", eventId, insertFailure);
      throw insertFailure;
    }
  }

  /**
   * Shared terminal-state write. {@code PROCESSED_AT} is first-write-wins via {@code coalesce} so
   * it keeps meaning "when the result was first decided"; the reason column is overwritten when
   * one is given and left alone otherwise.
   *
   * <p>Every result other than {@code APPLIED} carries an {@code eventResult <> 'APPLIED'} guard,
   * which is what makes {@code APPLIED} genuinely terminal rather than merely intended to be. The
   * reachable case is the servlet's own failure path: a redelivery of an already-applied event
   * that throws while re-checking it would otherwise rewrite the row to {@code FAILED}, which in
   * turn makes the next delivery re-claim it and apply the payment a second time.
   * {@code markApplied} needs no guard — rewriting {@code APPLIED} over {@code APPLIED} changes
   * nothing, and {@code coalesce} keeps the first timestamp.
   */
  private void updateResult(String eventId, String result, String reason) {
    String id = StringUtils.trimToNull(eventId);
    if (id == null) {
      return;
    }
    runAsSystem(() -> {
      try {
        String trimmedReason = StringUtils.abbreviate(StringUtils.trimToNull(reason), REASON_WIDTH);
        String setReason = trimmedReason == null ? "" : ", be.failureReason = :reason";
        boolean guardApplied = !RESULT_APPLIED.equals(result);
        org.hibernate.query.Query<?> update = OBDal.getInstance()
            .getSession()
            .createQuery(UPDATE_BILLING_EVENT
                + "   set be.eventResult = :result,"
                + "       be.processedAt = coalesce(be.processedAt, :now),"
                + "       be.updated = :now"
                + setReason
                + " where be.event = :eventId"
                + (guardApplied ? "   and be.eventResult <> :applied" : ""))
            .setParameter("result", result)
            .setParameter("now", new Date())
            .setParameter(PARAM_EVENT_ID, id);
        if (trimmedReason != null) {
          update.setParameter("reason", trimmedReason);
        }
        if (guardApplied) {
          update.setParameter("applied", RESULT_APPLIED);
        }
        int updated = update.executeUpdate();
        flushAndCommit();
        if (updated == 0) {
          // Either there is no such row, or it is already APPLIED and the guard refused the write.
          // Both are ordinary on a redelivery, and neither is actionable for the caller.
          log.warn("No billing event row was updated for '{}' while marking it {} — the row is "
              + "missing or already {}", id, result, RESULT_APPLIED);
        }
      } catch (RuntimeException e) {
        OBDal.getInstance().rollbackAndClose();
        throw e;
      }
    });
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

  /**
   * Runs {@code body} as the system user ({@code "0","0","0","0"}) with admin mode on, and hands
   * the caller back exactly the execution context it arrived with.
   *
   * <p>Restoring is the part that is easy to get wrong, and this class got it wrong until this
   * change, exactly as {@code CheckoutRequestStore} did (ETP-5045):
   * {@link OBContext#restorePreviousMode()} pops the <em>admin-mode stack</em>, it does not undo
   * {@link OBContext#setOBContext(String, String, String, String)}. So every method used to leave
   * the system context installed on the calling thread.
   *
   * <p>It was invisible while the only caller was the Stripe webhook, which is matched before the
   * authentication chain and has no context to lose. It stops being invisible for any caller that
   * is in the middle of a unit of work: everything it ran afterwards would silently continue as
   * system instead of as the identity it had established.
   *
   * <p><b>{@code null} is a legitimate previous context, not a missing one.</b> The webhook
   * genuinely has none, so "no context" must be restored as no context;
   * {@link OBContext#setOBContext(OBContext)} clears the thread-local when handed {@code null},
   * which is the wanted behaviour — substituting a system context would leave the thread more
   * privileged than it was found.
   *
   * <p>Delegates to {@link SystemContext#call(String, Supplier)}, the one implementation of this
   * capture / install / unwind sequence.
   *
   * @param body the work to run as system
   * @param <T> the body's result type
   * @return whatever the body returned
   */
  private <T> T runAsSystem(Supplier<T> body) {
    return SystemContext.call("a billing-event store operation", body);
  }

  /**
   * Void form of {@link #runAsSystem(Supplier)}, for the methods that only write.
   *
   * @param body the work to run as system
   */
  private void runAsSystem(Runnable body) {
    runAsSystem(() -> {
      body.run();
      return null;
    });
  }

  private void flushAndCommit() {
    OBDal.getInstance().flush();
    OBDal.getInstance().commitAndClose();
  }
}
