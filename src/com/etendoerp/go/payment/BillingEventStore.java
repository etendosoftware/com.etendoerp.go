/* Etendo License. */
package com.etendoerp.go.payment;

import java.sql.SQLException;
import java.util.Date;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
 * a payment, so the window is accepted deliberately. Closing it needs a sweeper that re-opens rows
 * left {@code RECEIVED} past a threshold — that belongs to the reconciliation job (ETP-5048), not
 * to this store. The narrower lost-write variant is noted on {@link #markFailed}.
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
