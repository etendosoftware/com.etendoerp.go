package com.etendoerp.go.portal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.transaction.Status;
import javax.transaction.Synchronization;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Transaction;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.NeoBatchScope;
import com.etendoerp.go.schemaforge.QuotationDecisions;
import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryIdentity;
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryService;

/**
 * The customer portal's KPI telemetry (ETP-6209, PO metrics of etendo-ai#22): three backend events
 * through {@link NeoTelemetryService} (Mixpanel).
 *
 * <ul>
 *   <li>{@code backend_portal_document_sent} ({@code type}): the FIRST portal link of a document
 *       whose email left. A renewal, a decision link or a later re-send of the same document is
 *       not counted.</li>
 *   <li>{@code backend_portal_document_downloaded} ({@code type}): the FIRST successful portal
 *       download of a document that was sent with a portal link. "First" is an atomic stamp on the
 *       document's first sent link ({@code ETGO_PORTAL_LINK.FIRST_DOWNLOADED}): only the request
 *       whose update set it emits.</li>
 *   <li>{@code backend_quotation_decided} ({@code decision} {@code accepted|rejected},
 *       {@code source} {@code portal|app}): a quotation answered from the portal — once per
 *       quotation, its first confirmed portal decision, and only for a quotation that was emailed
 *       with a portal link — or decided in the app (rejected, or converted into an order or an invoice from
 *       Under evaluation; a conversion of a quotation the customer already accepted from the portal
 *       is not a second decision).</li>
 *   <li>{@code backend_portal_opened} ({@code third_party}, {@code entry_doc_type},
 *       {@code origin}): a portal visit opened from a link — the first valid {@code /me} of a link
 *       after {@value #OPEN_WINDOW_MINUTES} minutes without one, so a reload or the SPA's own
 *       re-checks inside a visit do not count again.</li>
 *   <li>{@code backend_portal_email_sent} ({@code piece}, {@code doc_type} for a document send):
 *       every portal email that actually left — a document send carrying a portal link, a link
 *       renewal, a decision confirmation (accept / reject).</li>
 * </ul>
 *
 * <p><b>Only after commit, once, never in the way.</b> The portal call sites run after their own
 * commit; an app write registers the event on its transaction ({@link #afterCommit}) or, inside a
 * NEO batch, on the batch's commit. Sending runs on one bounded background thread (queue 1000,
 * dropped when full), with no retry; the Mixpanel sink adds {@code $insert_id} and
 * {@code environment}. Every failure is logged and swallowed. <b>No record identifier leaves this
 * class</b>: the only id is the tenant as {@code account_id}; the customer appears only as
 * {@code third_party}, a keyed pseudonym that cannot be turned back into its id.</p>
 */
public final class PortalKpiTelemetry {

  private static final Logger log = LogManager.getLogger(PortalKpiTelemetry.class);

  /** {@code decision} of an accepted quotation. */
  public static final String DECISION_ACCEPTED = "accepted";
  /** {@code decision} of a rejected quotation. */
  public static final String DECISION_REJECTED = "rejected";
  /** {@code source} of a decision taken by the customer in the portal. */
  public static final String SOURCE_PORTAL = "portal";
  /** {@code source} of a decision taken by the tenant in the app (any channel). */
  public static final String SOURCE_APP = "app";

  /** {@code piece} of a sales document email carrying a portal link. */
  public static final String PIECE_DOCUMENT_SEND = "document_send";
  /** {@code piece} of a renewed portal link email. */
  public static final String PIECE_LINK_RENEWAL = "link_renewal";
  /** {@code piece} of a quotation acceptance confirmation email. */
  public static final String PIECE_DECISION_ACCEPT = "decision_accept";
  /** {@code piece} of a quotation rejection confirmation email. */
  public static final String PIECE_DECISION_REJECT = "decision_reject";

  /** A visit ends after this long without a valid {@code /me} of the same link. */
  static final long OPEN_WINDOW_MINUTES = 30;
  private static final long OPEN_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(OPEN_WINDOW_MINUTES);
  /** Above this many tracked links, the stale ones are swept before tracking another. */
  private static final int MAX_TRACKED_OPENS = 10_000;
  /** Last valid {@code /me} per link id, this node only (one node in production; see the docs). */
  private static final Map<String, Long> LAST_OPEN = new ConcurrentHashMap<>();
  /** Signing purpose of the {@code third_party} pseudonym: valid for nothing else. */
  static final String THIRD_PARTY_PURPOSE = "kpi-third-party:v1";

  private static final String KEY_ACCOUNT = "account_id";
  private static final String KEY_TYPE = "type";
  private static final String PARAM_RECORD = "record";
  private static final String PARAM_CLIENT = "client";
  private static final int QUEUE_CAPACITY = 1000;

  /** Whether the document was emailed with a portal link: a confirmed link of a send. */
  private static final String EMAILED_WITH_LINK_SQL = "SELECT count(*) FROM etgo_portal_link"
      + " WHERE ad_client_id = :client AND record_id = :record AND link_origin = '"
      + PortalLink.ORIGIN_SEND + "' AND sent_at IS NOT NULL";

  /**
   * The quotation's first confirmed portal decision. Confirmations of one quotation are serialized
   * by its row lock ({@code PortalDecisionService#confirm}) and a confirmed decision never changes
   * state again, so exactly one decision is ever first: only its confirmation emits.
   */
  private static final String FIRST_CONFIRMED_DECISION_SQL = "SELECT etgo_portal_decision_id"
      + " FROM etgo_portal_decision WHERE ad_client_id = :client AND c_order_id = :record"
      + " AND status = 'CONFIRMED' ORDER BY confirmed_at, etgo_portal_decision_id LIMIT 1";

  /** Other sent links of the same document by an email send (renewals and decision links aside). */
  private static final String OTHER_SENT_SENDS_SQL = "SELECT count(*) FROM etgo_portal_link"
      + " WHERE ad_client_id = :client AND record_id = :record AND link_origin = '"
      + PortalLink.ORIGIN_SEND + "' AND sent_at IS NOT NULL AND etgo_portal_link_id <> :link";

  /**
   * Stamps the document's first sent link, only when no download was stamped yet. A concurrent
   * download waits on the row lock and then finds it stamped, so exactly one request updates it.
   */
  private static final String STAMP_FIRST_DOWNLOAD_SQL = "UPDATE etgo_portal_link"
      + " SET first_downloaded = now(), updated = now() WHERE first_downloaded IS NULL"
      + " AND etgo_portal_link_id = (SELECT l.etgo_portal_link_id FROM etgo_portal_link l"
      + " WHERE l.ad_client_id = :client AND l.record_id = :record AND l.link_origin = '"
      + PortalLink.ORIGIN_SEND + "' AND l.sent_at IS NOT NULL ORDER BY l.sent_at, l.created"
      + " LIMIT 1)";

  private static final ThreadPoolExecutor SENDER = buildSender();
  private static final AtomicReference<NeoTelemetryService> TELEMETRY_SERVICE =
      new AtomicReference<>();

  private PortalKpiTelemetry() {
  }

  /**
   * A portal link's email left (after its confirmation's commit): emits
   * {@code backend_portal_document_sent} when it is the document's first sent link of an email
   * send. Never throws.
   *
   * @param link the confirmed link
   */
  static void linkSent(PortalLink link) {
    try {
      if (link == null || !PortalLink.ORIGIN_SEND.equals(link.getOrigin())
          || link.getDocumentKind() == null || hasEarlierSend(link)) {
        return;
      }
      send(NeoTelemetryEvents.BACKEND_PORTAL_DOCUMENT_SENT, typeProperties(link.getClientId(),
          link.getDocumentKind()), link.getClientId(), identityOf(link.getClientId()));
    } catch (RuntimeException e) {
      log.debug("Portal KPI telemetry: the sent event failed: {}", e.getMessage());
    }
  }

  /**
   * A document's PDF was streamed through the portal: stamps and emits
   * {@code backend_portal_document_downloaded} for the document's first download. Runs after the
   * stream, commits its own stamp. Never throws.
   *
   * @param session the portal session that downloaded it
   * @param kind the document's kind
   * @param documentId the document
   */
  static void documentDownloaded(PortalSession session, PortalDocumentKind kind,
      String documentId) {
    if (session == null || kind == null || StringUtils.isBlank(documentId)) {
      return;
    }
    try {
      if (stampFirstDownload(session.getClientId(), documentId)) {
        send(NeoTelemetryEvents.BACKEND_PORTAL_DOCUMENT_DOWNLOADED,
            typeProperties(session.getClientId(), kind), session.getClientId(),
            identityOf(session.getClientId()));
      }
    } catch (RuntimeException e) {
      log.debug("Portal KPI telemetry: the download stamp failed: {}", e.getMessage());
      discard();
    }
  }

  /**
   * Emits {@code backend_quotation_decided} with {@code source = portal}, unconditionally: the
   * gate is {@link #portalQuotationConfirmed}, the only production caller. Never throws.
   *
   * @param accepted whether the customer accepted the quotation (else rejected it)
   * @param clientId the quotation's tenant
   */
  static void portalQuotationDecided(boolean accepted, String clientId) {
    try {
      send(NeoTelemetryEvents.BACKEND_QUOTATION_DECIDED,
          decidedProperties(accepted, SOURCE_PORTAL, clientId), clientId, identityOf(clientId));
    } catch (RuntimeException e) {
      log.debug("Portal KPI telemetry: the decided event failed: {}", e.getMessage());
    }
  }

  /**
   * A portal page validated a link ({@code /me} answered {@code valid}): emits
   * {@code backend_portal_opened} when it opens a new visit of that link. Never throws.
   *
   * @param link the link the page was opened with
   */
  static void portalOpened(PortalLink link) {
    try {
      if (link == null || !isNewVisit(link.getId(), System.currentTimeMillis())) {
        return;
      }
      String clientId = link.getClientId();
      Map<String, Object> properties = accountProperties(clientId);
      thirdPartyOf(link.getBusinessPartnerId()).ifPresent(id -> properties.put("third_party", id));
      if (link.getDocumentKind() != null) {
        properties.put("entry_doc_type", docTypeOf(link));
      }
      if (link.getOrigin() != null) {
        properties.put("origin", link.getOrigin().toLowerCase(Locale.ROOT));
      }
      send(NeoTelemetryEvents.BACKEND_PORTAL_OPENED, properties, clientId, identityOf(clientId));
    } catch (RuntimeException e) {
      log.debug("Portal KPI telemetry: the opened event failed: {}", e.getMessage());
    }
  }

  /**
   * A portal link's email left (after its confirmation's commit): emits
   * {@code backend_portal_email_sent} — {@code document_send} with its {@code doc_type} for a
   * document send, {@code link_renewal} for a renewal. Never throws.
   *
   * @param link the confirmed link
   */
  static void linkEmailSent(PortalLink link) {
    try {
      if (link == null) {
        return;
      }
      boolean renewal = PortalLink.ORIGIN_RENEWAL.equals(link.getOrigin());
      if (!renewal && !PortalLink.ORIGIN_SEND.equals(link.getOrigin())) {
        return;
      }
      String docType = renewal || link.getDocumentKind() == null ? null : docTypeOf(link);
      emailSent(renewal ? PIECE_LINK_RENEWAL : PIECE_DOCUMENT_SEND, docType,
          link.getClientId());
    } catch (RuntimeException e) {
      log.debug("Portal KPI telemetry: the email sent event failed: {}", e.getMessage());
    }
  }

  /**
   * A decision confirmation email left (after its commit): emits {@code backend_portal_email_sent}
   * with {@code decision_accept} or {@code decision_reject}. Never throws.
   *
   * @param accepted whether the customer asked to accept (else to reject)
   * @param clientId the quotation's tenant
   */
  static void decisionEmailSent(boolean accepted, String clientId) {
    try {
      emailSent(accepted ? PIECE_DECISION_ACCEPT : PIECE_DECISION_REJECT, null, clientId);
    } catch (RuntimeException e) {
      log.debug("Portal KPI telemetry: the email sent event failed: {}", e.getMessage());
    }
  }

  private static void emailSent(String piece, String docType, String clientId) {
    Map<String, Object> properties = accountProperties(clientId);
    properties.put("piece", piece);
    if (docType != null) {
      properties.put("doc_type", docType);
    }
    send(NeoTelemetryEvents.BACKEND_PORTAL_EMAIL_SENT, properties, clientId,
        identityOf(clientId));
  }

  /**
   * Whether a valid {@code /me} of this link opens a new visit: none tracked, or the last one more
   * than {@value #OPEN_WINDOW_MINUTES} minutes ago. Every call slides the window, so a visit lasts
   * as long as the page keeps validating. In memory, per node: a deploy starts every visit anew.
   *
   * @param linkId the link
   * @param now the current time in milliseconds
   * @return {@code true} when this call opens a visit
   */
  static boolean isNewVisit(String linkId, long now) {
    if (StringUtils.isBlank(linkId)) {
      return false;
    }
    if (LAST_OPEN.size() >= MAX_TRACKED_OPENS) {
      LAST_OPEN.values().removeIf(last -> now - last > OPEN_WINDOW_MILLIS);
    }
    Long previous = LAST_OPEN.put(linkId, now);
    return previous == null || now - previous > OPEN_WINDOW_MILLIS;
  }

  /**
   * The {@code third_party} pseudonym of a business partner: an HMAC of its id under a purpose of
   * its own ({@link DocumentDownloadTokenService#signDetached}), stable so Mixpanel counts distinct
   * customers, and impossible to reverse or to link to any other identifier without the secret.
   *
   * @param businessPartnerId the customer the link was sent to
   * @return the pseudonym, or empty when there is no customer or no signing secret
   */
  static Optional<String> thirdPartyOf(String businessPartnerId) {
    return StringUtils.isBlank(businessPartnerId) ? Optional.empty()
        : DocumentDownloadTokenService.signDetached(THIRD_PARTY_PURPOSE, businessPartnerId);
  }

  /**
   * The document type of a link for the new events: {@code quotation}, {@code order},
   * {@code shipment}, {@code invoice} or {@code credit_note} (told apart from an invoice, unlike
   * {@link #typeOf}).
   */
  private static String docTypeOf(PortalLink link) {
    return link.getDocumentKind().copyKey(link.getRecordId()).toLowerCase(Locale.ROOT);
  }

  /**
   * A portal decision was applied (after the confirmation's commit): emits
   * {@code backend_quotation_decided} with {@code source = portal} only when the quotation was
   * emailed with a portal link (the KPI's denominator is {@code backend_portal_document_sent}, so a
   * quotation reached through a shared contact link or another document's link is outside it) and
   * this is the quotation's first confirmed portal decision (a re-opened quotation decided again
   * from the portal is not counted twice). Read-only checks after the commit, so the decision is
   * never affected. Never throws.
   *
   * @param accepted whether the customer accepted the quotation (else rejected it)
   * @param clientId the quotation's tenant
   * @param decisionId the decision just applied
   * @param quotationId the quotation
   */
  static void portalQuotationConfirmed(boolean accepted, String clientId, String decisionId,
      String quotationId) {
    try {
      if (StringUtils.isAnyBlank(clientId, decisionId, quotationId)
          || !countsAsPortalDecision(clientId, decisionId, quotationId)) {
        return;
      }
      portalQuotationDecided(accepted, clientId);
    } catch (RuntimeException e) {
      log.debug("Portal KPI telemetry: the decided check failed: {}", e.getMessage());
      discard();
    }
  }

  private static boolean countsAsPortalDecision(String clientId, String decisionId,
      String quotationId) {
    OBContext.setAdminMode(true);
    try {
      List<?> emailed = OBDal.getInstance().getSession().createNativeQuery(EMAILED_WITH_LINK_SQL)
          .setParameter(PARAM_CLIENT, clientId)
          .setParameter(PARAM_RECORD, quotationId).list();
      if (emailed.isEmpty() || ((Number) emailed.get(0)).longValue() == 0) {
        return false;
      }
      List<?> first = OBDal.getInstance().getSession()
          .createNativeQuery(FIRST_CONFIRMED_DECISION_SQL)
          .setParameter(PARAM_CLIENT, clientId)
          .setParameter(PARAM_RECORD, quotationId).list();
      return !first.isEmpty() && decisionId.equals(String.valueOf(first.get(0)));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * A quotation was decided in the app (any channel: the SPA, REST, MCP): emits
   * {@code backend_quotation_decided} with {@code source = app} once the write is committed, and
   * never for a write that rolls back. Never throws.
   *
   * @param accepted whether the quotation was accepted (converted), else rejected
   * @param quotation the quotation (its tenant is the event's account)
   */
  public static void appQuotationDecided(boolean accepted, Order quotation) {
    try {
      String clientId = clientIdOf(quotation);
      Map<String, Object> properties = decidedProperties(accepted, SOURCE_APP, clientId);
      // Captured here, on the request thread: afterCommit runs in a Hibernate afterCompletion.
      NeoTelemetryIdentity identity = identityOf(clientId);
      afterCommit(() -> send(NeoTelemetryEvents.BACKEND_QUOTATION_DECIDED, properties, clientId,
          identity));
    } catch (RuntimeException e) {
      log.debug("Portal KPI telemetry: the decided event failed: {}", e.getMessage());
    }
  }

  /**
   * A quotation was converted in the app into an order or an invoice: an app acceptance, unless the
   * customer had already accepted it from the portal ({@code ETGO_AC}), whose decision was counted
   * when it was confirmed. Same commit rule as {@link #appQuotationDecided}. Never throws.
   *
   * @param statusBefore the quotation's {@code DocStatus} before the conversion
   * @param quotation the quotation (its tenant is the event's account)
   */
  public static void appQuotationConverted(String statusBefore, Order quotation) {
    if (!QuotationDecisions.STATUS_ACCEPTED.equals(statusBefore)) {
      appQuotationDecided(true, quotation);
    }
  }

  /**
   * The quotation a KPI is about, read so that a failure never reaches the business write.
   *
   * @param quotationId the quotation
   * @return the quotation, or {@code null} when it cannot be read
   */
  public static Order quotationOf(String quotationId) {
    try {
      return StringUtils.isBlank(quotationId) ? null
          : OBDal.getInstance().get(Order.class, quotationId);
    } catch (RuntimeException e) {
      log.debug("Portal KPI telemetry: the quotation could not be read: {}", e.getMessage());
      return null;
    }
  }

  /** The tenant of a record, or {@code null} when it cannot be read. */
  private static String clientIdOf(Order quotation) {
    Client client = quotation == null ? null : quotation.getClient();
    return client == null ? null : client.getId();
  }

  private static Map<String, Object> decidedProperties(boolean accepted, String source,
      String clientId) {
    Map<String, Object> properties = accountProperties(clientId);
    properties.put("decision", accepted ? DECISION_ACCEPTED : DECISION_REJECTED);
    properties.put("source", source);
    return properties;
  }

  /**
   * The event's {@code type}: the portal document kind in lower case ({@code invoice},
   * {@code quotation}, {@code order}, {@code shipment}). A credit note is an {@code invoice}, as
   * the portal kinds do not tell it apart.
   *
   * @param kind the kind
   * @return the type
   */
  static String typeOf(PortalDocumentKind kind) {
    return kind.name().toLowerCase(Locale.ROOT);
  }

  private static Map<String, Object> typeProperties(String clientId, PortalDocumentKind kind) {
    Map<String, Object> properties = accountProperties(clientId);
    properties.put(KEY_TYPE, typeOf(kind));
    return properties;
  }

  private static Map<String, Object> accountProperties(String clientId) {
    Map<String, Object> properties = new LinkedHashMap<>();
    if (clientId != null) {
      properties.put(KEY_ACCOUNT, clientId);
    }
    return properties;
  }

  private static boolean hasEarlierSend(PortalLink link) {
    OBContext.setAdminMode(true);
    try {
      List<?> rows = OBDal.getInstance().getSession().createNativeQuery(OTHER_SENT_SENDS_SQL)
          .setParameter(PARAM_CLIENT, link.getClientId())
          .setParameter(PARAM_RECORD, link.getRecordId())
          .setParameter("link", link.getId()).list();
      return !rows.isEmpty() && ((Number) rows.get(0)).longValue() > 0;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static boolean stampFirstDownload(String clientId, String documentId) {
    OBContext.setAdminMode(true);
    try {
      int updated = OBDal.getInstance().getSession().createNativeQuery(STAMP_FIRST_DOWNLOAD_SQL)
          .setParameter(PARAM_CLIENT, clientId)
          .setParameter(PARAM_RECORD, documentId)
          .executeUpdate();
      SessionHandler.getInstance().commitAndStart();
      return updated > 0;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static void discard() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (RuntimeException e) {
      log.debug("Portal KPI telemetry: rollback failed: {}", e.getMessage());
    }
  }

  /**
   * Runs {@code action} once the current write is durable: on a NEO batch's commit, on the active
   * transaction's commit (never on a rollback), or at once when there is no transaction to wait for.
   */
  static void afterCommit(Runnable action) {
    if (NeoBatchScope.current() != null) {
      NeoBatchScope.runAfterCommit(action);
      return;
    }
    Transaction transaction = activeTransaction();
    if (transaction == null) {
      action.run();
      return;
    }
    transaction.registerSynchronization(new Synchronization() {
      @Override
      public void beforeCompletion() {
        // Nothing to do before the commit: the event describes the committed write only.
      }

      @Override
      public void afterCompletion(int status) {
        if (status == Status.STATUS_COMMITTED) {
          action.run();
        }
      }
    });
  }

  private static Transaction activeTransaction() {
    try {
      Transaction transaction = OBDal.getInstance().getSession().getTransaction();
      return transaction != null && transaction.isActive() ? transaction : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * The identity of the calling thread's session (ETP-6228): the sender has none and must never
   * look anything up, so every public entry point captures it before any after-commit callback.
   */
  private static NeoTelemetryIdentity identityOf(String clientId) {
    return telemetry().captureIdentity(clientId, null);
  }

  private static void send(String event, Map<String, Object> properties, String clientId,
      NeoTelemetryIdentity identity) {
    try {
      SENDER.execute(() -> {
        try {
          NeoTelemetryIdentity.runWith(identity, () -> telemetry().emit(event, properties,
              clientId));
        } catch (RuntimeException e) {
          log.debug("Portal KPI telemetry: emit failed: {}", e.getMessage());
        }
      });
    } catch (RejectedExecutionException e) {
      log.debug("Portal KPI telemetry queue full; dropping {}", event);
    }
  }

  /**
   * Package-private seam for tests; pass {@code null} to restore the runtime service.
   *
   * @param service the service events are emitted to
   */
  static void setTelemetryService(NeoTelemetryService service) {
    TELEMETRY_SERVICE.set(service);
  }

  private static NeoTelemetryService telemetry() {
    NeoTelemetryService local = TELEMETRY_SERVICE.get();
    if (local == null) {
      synchronized (PortalKpiTelemetry.class) {
        local = TELEMETRY_SERVICE.get();
        if (local == null) {
          local = NeoTelemetryService.runtime();
          TELEMETRY_SERVICE.set(local);
        }
      }
    }
    return local;
  }

  private static ThreadPoolExecutor buildSender() {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
          Thread thread = new Thread(runnable, "portal-kpi-telemetry");
          thread.setDaemon(true);
          return thread;
        });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }
}
