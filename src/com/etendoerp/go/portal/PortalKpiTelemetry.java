package com.etendoerp.go.portal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
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
import com.etendoerp.go.schemaforge.telemetry.NeoTelemetryEvents;
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
 *       {@code source} {@code portal|app}): a quotation answered from the portal (a confirmed
 *       decision) or decided in the app (rejected, or converted into an order or an invoice from
 *       Under evaluation; a conversion of a quotation the customer already accepted from the portal
 *       is not a second decision).</li>
 * </ul>
 *
 * <p><b>Only after commit, once, never in the way.</b> The portal call sites run after their own
 * commit; an app write registers the event on its transaction ({@link #afterCommit}) or, inside a
 * NEO batch, on the batch's commit. Sending runs on one bounded background thread (queue 1000,
 * dropped when full), with no retry; the Mixpanel sink adds {@code $insert_id} and
 * {@code environment}. Every failure is logged and swallowed. <b>No record identifier leaves this
 * class</b>: the only id is the tenant as {@code account_id}.</p>
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

  private static final String KEY_ACCOUNT = "account_id";
  private static final String KEY_TYPE = "type";
  private static final String PARAM_RECORD = "record";
  private static final String PARAM_CLIENT = "client";
  private static final int QUEUE_CAPACITY = 1000;

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
          link.getDocumentKind()), link.getClientId());
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
            typeProperties(session.getClientId(), kind), session.getClientId());
      }
    } catch (RuntimeException e) {
      log.debug("Portal KPI telemetry: the download stamp failed: {}", e.getMessage());
      discard();
    }
  }

  /**
   * A portal decision was applied (after the confirmation's commit): emits
   * {@code backend_quotation_decided} with {@code source = portal}. Never throws.
   *
   * @param accepted whether the customer accepted the quotation (else rejected it)
   * @param clientId the quotation's tenant
   */
  static void portalQuotationDecided(boolean accepted, String clientId) {
    try {
      send(NeoTelemetryEvents.BACKEND_QUOTATION_DECIDED,
          decidedProperties(accepted, SOURCE_PORTAL, clientId), clientId);
    } catch (RuntimeException e) {
      log.debug("Portal KPI telemetry: the decided event failed: {}", e.getMessage());
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
      afterCommit(() -> send(NeoTelemetryEvents.BACKEND_QUOTATION_DECIDED, properties, clientId));
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

  private static void send(String event, Map<String, Object> properties, String clientId) {
    try {
      SENDER.execute(() -> {
        try {
          telemetry().emit(event, properties, clientId);
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
