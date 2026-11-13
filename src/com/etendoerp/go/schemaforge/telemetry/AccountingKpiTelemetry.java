/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.telemetry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.database.ExternalConnectionPool;

import com.etendoerp.go.common.ConfigPropertyReader;
import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

/**
 * Accounting-calendar KPI telemetry (ETP-6230): {@code backend_productive_environment_created},
 * {@code backend_calendar_review_completed}, {@code backend_first_document_processed} and
 * {@code backend_period_refused}.
 *
 * <p><b>Nothing here runs SQL, or anything else that can fail, on a request thread.</b> The hooks
 * only enqueue a small value ({@code clientId}, source, entity, operation hint); a single daemon
 * sender thread then does the gate, the probe, the emit and the once-only marker on its own
 * connection from {@link ExternalConnectionPool} (the way {@code McpUsageLogger} writes its rows),
 * so the business transaction is never touched. Every failure is logged at debug level and
 * forgotten; when the queue is full the work is dropped.</p>
 *
 * <p><b>Population.</b> Review, first-document and signup events are only emitted for tenants that
 * carry {@code ETGO_CalendarReviewRequired} (productive tenants onboarded after ETP-5577; the
 * marker is permanent). {@code backend_period_refused} goes out for every tenant.</p>
 *
 * <p><b>Once-only markers</b> are client preferences ({@code ETGO_KpiReviewFirstStepsSent},
 * {@code ETGO_KpiReviewManualOpenSent}, {@code ETGO_KpiFirstDocSent}), written by the sender after
 * the event is emitted (a lost marker can only cost a duplicate event, never a lost one). Reports
 * count distinct {@code account_id}, so a rare duplicate is harmless.</p>
 *
 * <p><b>First document</b> is detected by an outcome probe, not by per-path hooks: after a
 * successful write the sender asks whether any period-controlled document of the tenant is now
 * {@code Processed='Y' OR Posted='Y'}. The table list below is this KPI's definition of
 * "document"; no hook ever branches on the entity a request touched. Orders are excluded on
 * purpose (their completion has no period check). Known limitation: a completion that happens
 * outside a request (async batch worker, Etendo Classic, background posting) is seen on the
 * tenant's NEXT NEO write and carries that write's {@code source}/{@code operation}.</p>
 *
 * <p><b>{@code internal}</b> is {@code true} when the tenant owner's e-mail domain is in
 * {@value #PROP_INTERNAL_DOMAINS} (default {@value #DEFAULT_INTERNAL_DOMAINS}). The owner is the
 * earliest-created active user of the tenant with an e-mail. The address is read on the sender
 * thread, reduced to a boolean, cached per tenant, and never leaves the server.</p>
 *
 * <p>No personal data: only the tenant id as {@code account_id}.</p>
 *
 * <p>Follow-up ETP-6072: add {@code tenant_type} when it lands. Follow-up ETP-6228: add
 * {@code ad_client_id} when the common identity lands.</p>
 */
public final class AccountingKpiTelemetry {

  private static final Logger log = LogManager.getLogger(AccountingKpiTelemetry.class);

  /** {@code source}: the SPA (any REST call that is not a batch). */
  public static final String SOURCE_SPA = "spa";
  /** {@code source}: an MCP tool call. */
  public static final String SOURCE_MCP = "mcp";
  /** {@code source}: REST batch, MCP batch or action batch. One rule for every batch. */
  public static final String SOURCE_BATCH = "batch";
  /** {@code source} of a review done in Primeros pasos. */
  public static final String SOURCE_FIRST_STEPS = "first_steps";
  /** {@code source} of a manual period open from the Calendar window (REST). */
  public static final String SOURCE_CALENDAR_WINDOW = "calendar_window";

  /** {@code type}: the owner confirmed the calendar as it was. */
  public static final String TYPE_CONFIRM = "confirm";
  /** {@code type}: the owner changed the calendar. */
  public static final String TYPE_MODIFY = "modify";

  /** {@code operation}: a document was completed. */
  public static final String OPERATION_COMPLETE = "complete";
  /** {@code operation}: a document was posted. */
  public static final String OPERATION_POST = "post";

  /** Review kind: Primeros pasos. */
  public static final String REVIEW_FIRST_STEPS = "first_steps";
  /** Review kind: manual period open. */
  public static final String REVIEW_MANUAL_OPEN = "manual_open";

  static final String MARKER_REVIEW_FIRST_STEPS = "ETGO_KpiReviewFirstStepsSent";
  static final String MARKER_REVIEW_MANUAL_OPEN = "ETGO_KpiReviewManualOpenSent";
  static final String MARKER_FIRST_DOC = "ETGO_KpiFirstDocSent";
  static final String REVIEW_REQUIRED_ATTRIBUTE = "ETGO_CalendarReviewRequired";

  static final String PROP_INTERNAL_DOMAINS = "etendo.go.kpi.internal.email.domains";
  static final String ENV_INTERNAL_DOMAINS = "ETGO_KPI_INTERNAL_EMAIL_DOMAINS";
  static final String DEFAULT_INTERNAL_DOMAINS = "etendo.software,etendo.ai,smfconsulting.es";

  /**
   * The period-controlled document tables: every one has {@code Processed} and {@code Posted}
   * (verified on the AD). The KPI's own definition of "document" — see the class javadoc.
   */
  /** One constant query per period-controlled table; each binds only the tenant id. */
  static final List<String> PROBE_QUERIES = List.of(
      "SELECT 1 FROM c_invoice WHERE ad_client_id = ? AND (processed = 'Y' OR posted = 'Y') LIMIT 1",
      "SELECT 1 FROM m_inout WHERE ad_client_id = ? AND (processed = 'Y' OR posted = 'Y') LIMIT 1",
      "SELECT 1 FROM fin_payment WHERE ad_client_id = ? AND (processed = 'Y' OR posted = 'Y') LIMIT 1",
      "SELECT 1 FROM gl_journal WHERE ad_client_id = ? AND (processed = 'Y' OR posted = 'Y') LIMIT 1",
      "SELECT 1 FROM m_movement WHERE ad_client_id = ? AND (processed = 'Y' OR posted = 'Y') LIMIT 1",
      "SELECT 1 FROM m_inventory WHERE ad_client_id = ? AND (processed = 'Y' OR posted = 'Y') LIMIT 1",
      "SELECT 1 FROM fin_bankstatement WHERE ad_client_id = ? AND (processed = 'Y' OR posted = 'Y') LIMIT 1");

  private static final int QUEUE_CAPACITY = 1000;
  private static final int MAX_DEPTH = 6;
  private static final int MAX_REFUSALS_PER_BODY = 50;

  private static final ThreadPoolExecutor SENDER = buildSender();
  private static final AtomicReference<NeoTelemetryService> TELEMETRY_SERVICE =
      new AtomicReference<>();

  /** Tenants whose first document was already reported (positive-only; the marker is permanent). */
  private static final Set<String> FIRST_DOC_DONE = ConcurrentHashMap.newKeySet();
  /** Tenants with a probe already queued: a burst of writes costs one probe. */
  private static final Map<String, Long> PROBE_LAST = new ConcurrentHashMap<>();
  /** Tenants found NOT review-required, until the stored expiry (the marker can be set later). */
  private static final Map<String, Long> NOT_REQUIRED_UNTIL = new ConcurrentHashMap<>();
  /** At most one probe per tenant per this long (the probe scans tables without a client index). */
  static final long PROBE_MIN_INTERVAL_MS = 60_000L;
  static final long NOT_REQUIRED_TTL_MS = 600_000L;
  /** Lets the request's own transaction commit before the probe looks. */
  static final long PROBE_DELAY_SECONDS = 5L;
  private static volatile long probeDelayMillis = TimeUnit.SECONDS.toMillis(PROBE_DELAY_SECONDS);
  private static final java.util.concurrent.ScheduledThreadPoolExecutor DELAYER = buildDelayer();
  /** Tenant to expiry (epoch ms) of its cached {@code internal} answer; {@code true} never expires. */
  private static final Map<String, Long> INTERNAL_UNTIL = new ConcurrentHashMap<>();
  private static final Set<String> INTERNAL_TRUE = ConcurrentHashMap.newKeySet();
  static final long INTERNAL_FALSE_TTL_MS = 3_600_000L;
  private static final String PROP_SOURCE = "source";

  private static final ThreadLocal<RestRequest> REST_REQUEST = new ThreadLocal<>();

  private AccountingKpiTelemetry() {
  }

  /** What the REST dispatcher knew about the request being served on this thread. */
  private static final class RestRequest {
    final String specName;
    final String entityName;
    final String method;
    /** The action segment of an action path, or {@code null}. */
    final String action;
    boolean batch;

    RestRequest(String specName, String entityName, String method, String action) {
      this.specName = specName;
      this.entityName = entityName;
      this.method = method;
      this.action = action;
    }

    /** What a refusal reports as {@code operation}: the action, else the HTTP method. */
    String operation() {
      return action != null ? action : method;
    }
  }

  // ── REST hooks (request thread: enqueue only) ────────────────────────────────────────────────

  /**
   * Remembers the coordinates of the REST request now being served, for the refusal and probe
   * hooks. Pair with {@link #endRestRequest()} in a {@code finally}. Never throws.
   *
   * @param specName the spec of the path, may be {@code null}
   * @param entityName the entity of the path, may be {@code null}
   * @param method the HTTP method
   * @param actionName the action segment of an action path, or {@code null}
   */
  public static void beginRestRequest(String specName, String entityName, String method,
      String actionName) {
    try {
      REST_REQUEST.set(new RestRequest(specName, entityName,
          StringUtils.lowerCase(method, Locale.ROOT), StringUtils.trimToNull(actionName)));
    } catch (RuntimeException e) {
      log.debug("Accounting KPI telemetry: could not note the REST request: {}", e.getMessage());
    }
  }

  /** Forgets the request noted by {@link #beginRestRequest}; the servlet thread is pooled. */
  public static void endRestRequest() {
    REST_REQUEST.remove();
  }

  /** Marks the REST request on this thread as the synchronous or async batch endpoint. */
  public static void markRestBatch() {
    RestRequest request = REST_REQUEST.get();
    if (request != null) {
      request.batch = true;
    }
  }

  /**
   * A REST CRUD / ACTION / batch dispatch answered without error: queues the first-document probe.
   * The caller passes only the HTTP status; the hook branches on nothing about the entity. Reads
   * (GET) never reach here. Never throws.
   *
   * @param httpStatus the status the dispatch answered
   */
  public static void restWriteSucceeded(int httpStatus) {
    RestRequest request = REST_REQUEST.get();
    if (request == null || httpStatus >= 400 || "get".equals(request.method)) {
      return;
    }
    enqueueFirstDocumentProbe(currentClientId(), request.batch ? SOURCE_BATCH : SOURCE_SPA,
        request.batch ? null : request.specName, request.batch ? null : request.entityName,
        request.batch ? null : request.action);
  }

  /**
   * A REST answer is about to be written: queues {@code backend_period_refused} for each period
   * refusal in it. Looks for {@code messageKeys} flat, under {@code error} and under
   * {@code error.detail.error} (the batch envelope). Never throws.
   *
   * @param httpStatus the status being written
   * @param body the JSON body, may be {@code null}
   */
  public static void observeRestResponse(int httpStatus, JSONObject body) {
    if (httpStatus < 400 || body == null) {
      return;
    }
    RestRequest request = REST_REQUEST.get();
    boolean batch = request != null && request.batch;
    observeRefusal(currentClientId(), batch ? SOURCE_BATCH : SOURCE_SPA,
        request != null && !batch ? request.specName : null,
        request != null && !batch ? request.entityName : null,
        request != null ? request.operation() : null, body, null);
  }

  /** @return the tenant of the request on this thread, or {@code null} */
  public static String currentClientId() {
    try {
      OBContext context = OBContext.getOBContext();
      return context != null && context.getCurrentClient() != null
          ? context.getCurrentClient().getId() : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  // ── Shared hooks (REST, MCP, async batch) ────────────────────────────────────────────────────

  /**
   * Queues the first-document probe after a successful write. Never throws.
   *
   * @param clientId the tenant
   * @param source {@link #SOURCE_SPA}, {@link #SOURCE_MCP} or {@link #SOURCE_BATCH}
   * @param specName the request's spec, may be {@code null}
   * @param entity the request's entity, may be {@code null}
   * @param operationHint the request's ACTION name only (never an HTTP method or tool verb), or
   *     {@code null}; {@code post} maps to {@link #OPERATION_POST}, anything else (including none)
   *     to {@link #OPERATION_COMPLETE}
   */
  public static void enqueueFirstDocumentProbe(String clientId, String source, String specName,
      String entity, String operationHint) {
    try {
      if (StringUtils.isBlank(clientId) || FIRST_DOC_DONE.contains(clientId)
          || System.currentTimeMillis() < NOT_REQUIRED_UNTIL.getOrDefault(clientId, 0L)
          || !tryClaimProbeSlot(clientId)) {
        return;
      }
      String operation = "post".equalsIgnoreCase(operationHint) ? OPERATION_POST
          : OPERATION_COMPLETE;
      // shortcut: the delay only makes the probe normally run after DalRequestFilter commits the
      // request; a commit slower than PROBE_DELAY_SECONDS is seen on the tenant's next probe.
      DELAYER.schedule(() -> {
        if (!submit(() -> runProbe(clientId, source, specName, entity, operation))) {
          PROBE_LAST.remove(clientId);
        }
      }, probeDelayMillis, TimeUnit.MILLISECONDS);
    } catch (RuntimeException e) {
      PROBE_LAST.remove(clientId);
      log.debug("Accounting KPI telemetry: could not queue the probe: {}", e.getMessage());
    }
  }

  /**
   * Queues {@code backend_period_refused} for each period refusal found in {@code body} (or, when
   * there is no JSON body, recognised in the prose {@code text}). Never throws.
   *
   * @param clientId the tenant
   * @param source {@link #SOURCE_SPA}, {@link #SOURCE_MCP} or {@link #SOURCE_BATCH}
   * @param specName the request's spec, may be {@code null}
   * @param entity the request's entity, may be {@code null}
   * @param operation the action name or verb, may be {@code null}
   * @param body the parsed answer, or {@code null}
   * @param text the raw answer, used only when {@code body} is {@code null}
   */
  public static void observeRefusal(String clientId, String source, String specName,
      String entity, String operation, JSONObject body, String text) {
    try {
      if (StringUtils.isBlank(clientId)) {
        return;
      }
      List<String> keys = body != null ? periodRefusalKeys(body)
          : NeoMessageTranslator.recognizeMessageKeys(text, NeoMessageTranslator.PERIOD_REFUSAL_KEYS);
      for (String key : keys) {
        Map<String, Object> props = new LinkedHashMap<>();
        putIfPresent(props, "specName", specName);
        putIfPresent(props, "entity", entity);
        putIfPresent(props, "operation", operation);
        props.put(PROP_SOURCE, source);
        props.put("errorCode", key);
        submit(() -> emit(NeoTelemetryEvents.BACKEND_PERIOD_REFUSED, clientId, props));
      }
    } catch (RuntimeException e) {
      log.debug("Accounting KPI telemetry: could not observe a refusal: {}", e.getMessage());
    }
  }

  /**
   * One row of an async batch job, as the worker stored it: queues {@code backend_period_refused}
   * for a refusal in it. Source is always {@link #SOURCE_BATCH}. Never throws.
   *
   * @param row the row envelope ({@code committed}, {@code error}, …)
   * @param clientId the job owner's tenant
   */
  public static void observeBatchRow(JSONObject row, String clientId) {
    if (row != null && !row.optBoolean("committed", false)) {
      observeRefusal(clientId, SOURCE_BATCH, null, null, null, row, null);
    }
  }

  // ── Event hooks (enqueue only) ───────────────────────────────────────────────────────────────

  /**
   * The tenant was just onboarded as productive (call it after the onboarding commit). Never
   * throws.
   *
   * @param clientId the new tenant
   */
  public static void productiveEnvironmentCreated(String clientId) {
    try {
      if (StringUtils.isNotBlank(clientId)) {
        submit(() -> emit(NeoTelemetryEvents.BACKEND_PRODUCTIVE_ENVIRONMENT_CREATED, clientId,
            new LinkedHashMap<>()));
      }
    } catch (RuntimeException e) {
      log.debug("Accounting KPI telemetry: could not queue the signup: {}", e.getMessage());
    }
  }

  /**
   * The owner reviewed the calendar. Emitted at most once per tenant and kind, and only for
   * tenants that require the review. Never throws.
   *
   * @param clientId the tenant
   * @param kind {@link #REVIEW_FIRST_STEPS} or {@link #REVIEW_MANUAL_OPEN}
   * @param type {@link #TYPE_CONFIRM} or {@link #TYPE_MODIFY}
   * @param source {@link #SOURCE_FIRST_STEPS}, {@link #SOURCE_CALENDAR_WINDOW},
   *     {@link #SOURCE_MCP} or {@link #SOURCE_BATCH}
   */
  public static void calendarReviewCompleted(String clientId, String kind, String type,
      String source) {
    try {
      if (StringUtils.isBlank(clientId)) {
        return;
      }
      String marker = REVIEW_FIRST_STEPS.equals(kind) ? MARKER_REVIEW_FIRST_STEPS
          : MARKER_REVIEW_MANUAL_OPEN;
      submit(() -> runReview(clientId, marker, type, source));
    } catch (RuntimeException e) {
      log.debug("Accounting KPI telemetry: could not queue a review: {}", e.getMessage());
    }
  }

  // ── Sender thread ────────────────────────────────────────────────────────────────────────────

  private static void runReview(String clientId, String marker, String type, String source) {
    try (Connection conn = openConnection()) {
      if (conn == null || !isReviewRequired(conn, clientId) || hasPreference(conn, marker, clientId)) {
        return;
      }
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("type", type);
      props.put(PROP_SOURCE, source);
      emit(NeoTelemetryEvents.BACKEND_CALENDAR_REVIEW_COMPLETED, clientId, props, conn);
      setMarker(conn, marker, clientId);
    } catch (Exception e) {
      log.debug("Accounting KPI telemetry: review event failed: {}", e.getMessage());
    }
  }

  /** @return {@code true} when this call won the tenant's slot for the next 60 s */
  private static boolean tryClaimProbeSlot(String clientId) {
    long now = System.currentTimeMillis();
    boolean[] won = new boolean[1];
    PROBE_LAST.compute(clientId, (id, last) -> {
      if (last != null && now - last < PROBE_MIN_INTERVAL_MS) {
        return last;
      }
      won[0] = true;
      return now;
    });
    return won[0];
  }

  private static void runProbe(String clientId, String source, String specName, String entity,
      String operation) {
    try (Connection conn = openConnection()) {
      if (conn == null || FIRST_DOC_DONE.contains(clientId)) {
        return;
      }
      if (hasPreference(conn, MARKER_FIRST_DOC, clientId)) {
        FIRST_DOC_DONE.add(clientId);
        return;
      }
      if (!isReviewRequired(conn, clientId)) {
        NOT_REQUIRED_UNTIL.put(clientId, System.currentTimeMillis() + NOT_REQUIRED_TTL_MS);
        return;
      }
      if (!hasProcessedDocument(conn, clientId)) {
        return;
      }
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("operation", operation);
      props.put(PROP_SOURCE, source);
      putIfPresent(props, "specName", specName);
      putIfPresent(props, "entity", entity);
      emit(NeoTelemetryEvents.BACKEND_FIRST_DOCUMENT_PROCESSED, clientId, props, conn);
      setMarker(conn, MARKER_FIRST_DOC, clientId);
      FIRST_DOC_DONE.add(clientId);
    } catch (Exception e) {
      log.debug("Accounting KPI telemetry: first-document probe failed: {}", e.getMessage());
    }
  }

  private static void emit(String event, String clientId, Map<String, Object> props) {
    try {
      if (INTERNAL_TRUE.contains(clientId) || System.currentTimeMillis()
          < INTERNAL_UNTIL.getOrDefault(clientId, 0L)) {
        emit(event, clientId, props, null);
        return;
      }
      try (Connection conn = openConnection()) {
        emit(event, clientId, props, conn);
      }
    } catch (Exception e) {
      log.debug("Accounting KPI telemetry: {} failed: {}", event, e.getMessage());
    }
  }

  private static void emit(String event, String clientId, Map<String, Object> props,
      Connection conn) {
    props.put("account_id", clientId);
    props.put("internal", isInternal(conn, clientId));
    telemetry().emit(event, props, clientId);
  }

  private static boolean hasProcessedDocument(Connection conn, String clientId)
      throws SQLException {
    for (String sql : PROBE_QUERIES) {
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, clientId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean isReviewRequired(Connection conn, String clientId) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM ad_preference WHERE attribute = ?"
        + " AND visibleat_client_id = ? AND isactive = 'Y' AND value = 'Y' LIMIT 1")) {
      ps.setString(1, REVIEW_REQUIRED_ATTRIBUTE);
      ps.setString(2, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static boolean hasPreference(Connection conn, String attribute, String clientId)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM ad_preference WHERE attribute = ?"
        + " AND visibleat_client_id = ? AND isactive = 'Y' LIMIT 1")) {
      ps.setString(1, attribute);
      ps.setString(2, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /** Same row shape the DAL writes for a client-visible preference; autocommit, own connection. */
  private static void setMarker(Connection conn, String attribute, String clientId) {
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO ad_preference (ad_preference_id, ad_client_id, ad_org_id, isactive, created,"
            + " createdby, updated, updatedby, attribute, value, ispropertylist, visibleat_client_id,"
            + " selected) SELECT ?, '0', '0', 'Y', now(), '0', now(), '0', ?, ?, 'N', ?, 'N'"
            + " WHERE NOT EXISTS (SELECT 1 FROM ad_preference WHERE attribute = ?"
            + " AND visibleat_client_id = ?)")) {
      ps.setString(1, UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT));
      ps.setString(2, attribute);
      ps.setString(3, Instant.now().toString());
      ps.setString(4, clientId);
      ps.setString(5, attribute);
      ps.setString(6, clientId);
      ps.executeUpdate();
    } catch (SQLException e) {
      log.debug("Accounting KPI telemetry: could not write marker {}: {}", attribute,
          e.getMessage());
    }
  }

  /** Owner e-mail domain in the configured list; cached per tenant, the address is not kept. */
  private static boolean isInternal(Connection conn, String clientId) {
    if (INTERNAL_TRUE.contains(clientId)) {
      return true;
    }
    if (System.currentTimeMillis() < INTERNAL_UNTIL.getOrDefault(clientId, 0L)) {
      return false;
    }
    boolean internal = false;
    if (conn != null) {
      try (PreparedStatement ps = conn.prepareStatement("SELECT email FROM ad_user WHERE ad_client_id = ?"
          + " AND isactive = 'Y' AND email IS NOT NULL ORDER BY created, ad_user_id LIMIT 1")) {
        ps.setString(1, clientId);
        try (ResultSet rs = ps.executeQuery()) {
          internal = rs.next() && isInternalDomain(rs.getString(1));
        }
      } catch (SQLException e) {
        log.debug("Accounting KPI telemetry: could not read the owner: {}", e.getMessage());
        return false; // not cached: try again next time
      }
      if (internal) {
        INTERNAL_TRUE.add(clientId);
      } else {
        // a tenant with no e-mail user yet may get one: re-read after the TTL
        INTERNAL_UNTIL.put(clientId, System.currentTimeMillis() + INTERNAL_FALSE_TTL_MS);
      }
    }
    return internal;
  }

  static boolean isInternalDomain(String email) {
    String domain = StringUtils.lowerCase(StringUtils.substringAfterLast(StringUtils.trimToEmpty(email), "@"),
        Locale.ROOT);
    if (domain.isEmpty()) {
      return false;
    }
    String configured = ConfigPropertyReader.readConfigValue(PROP_INTERNAL_DOMAINS,
        ENV_INTERNAL_DOMAINS, DEFAULT_INTERNAL_DOMAINS);
    for (String candidate : StringUtils.split(StringUtils.lowerCase(configured, Locale.ROOT), ",")) {
      if (domain.equals(candidate.trim())) {
        return true;
      }
    }
    return false;
  }

  private static Connection openConnection() throws SQLException {
    ExternalConnectionPool pool = ExternalConnectionPool.getInstance();
    if (pool == null) {
      return null;
    }
    Connection conn = pool.getConnection();
    try {
      conn.setAutoCommit(true);
    } catch (SQLException | RuntimeException e) {
      try {
        conn.close();
      } catch (SQLException ignored) {
        // the original failure is the one that matters
      }
      throw e;
    }
    return conn;
  }

  // ── Refusal detection ────────────────────────────────────────────────────────────────────────

  /**
   * The period-refusal keys found in {@code body}, one per refusing object, at any depth up to a
   * small limit: flat, under {@code error}, under {@code error.detail.error}, or in the items of
   * an array (one entry per failed item of a batch).
   *
   * @param body the parsed answer
   * @return the keys, possibly empty, never {@code null}
   */
  static List<String> periodRefusalKeys(JSONObject body) {
    List<String> found = new ArrayList<>();
    collect(body, 0, found);
    return found;
  }

  private static void collect(Object node, int depth, List<String> found) {
    if (depth > MAX_DEPTH || found.size() >= MAX_REFUSALS_PER_BODY) {
      return;
    }
    if (node instanceof JSONObject) {
      JSONObject object = (JSONObject) node;
      String own = periodKeyOf(object.optJSONArray("messageKeys"));
      if (own != null) {
        found.add(own);
        return;
      }
      java.util.Iterator<?> names = object.keys();
      while (names.hasNext()) {
        collect(object.opt(String.valueOf(names.next())), depth + 1, found);
      }
    } else if (node instanceof JSONArray) {
      JSONArray array = (JSONArray) node;
      for (int i = 0; i < array.length(); i++) {
        collect(array.opt(i), depth + 1, found);
      }
    }
  }

  private static String periodKeyOf(JSONArray keys) {
    if (keys == null) {
      return null;
    }
    for (int i = 0; i < keys.length(); i++) {
      String key = keys.optString(i);
      if (NeoMessageTranslator.PERIOD_REFUSAL_KEYS.contains(key)) {
        return key;
      }
    }
    return null;
  }

  // ── Plumbing ─────────────────────────────────────────────────────────────────────────────────

  private static void putIfPresent(Map<String, Object> props, String key, String value) {
    if (StringUtils.isNotBlank(value)) {
      props.put(key, value);
    }
  }

  private static boolean submit(Runnable work) {
    try {
      SENDER.execute(work);
      return true;
    } catch (RejectedExecutionException e) {
      log.debug("Accounting KPI telemetry queue full; dropping work");
      return false;
    }
  }

  /** Package-private seam for tests; pass {@code null} to restore the runtime service. */
  static void setTelemetryService(NeoTelemetryService service) {
    TELEMETRY_SERVICE.set(service);
  }

  /** Package-private seam for tests: the probe delay in milliseconds. */
  static void setProbeDelayMillisForTests(long millis) {
    probeDelayMillis = millis;
  }

  /** Package-private seam for tests: clears the per-tenant state and restores the 5 s delay. */
  static void resetStateForTests() {
    PROBE_LAST.clear();
    NOT_REQUIRED_UNTIL.clear();
    FIRST_DOC_DONE.clear();
    INTERNAL_UNTIL.clear();
    INTERNAL_TRUE.clear();
    probeDelayMillis = TimeUnit.SECONDS.toMillis(PROBE_DELAY_SECONDS);
  }

  private static NeoTelemetryService telemetry() {
    NeoTelemetryService local = TELEMETRY_SERVICE.get();
    if (local == null) {
      synchronized (AccountingKpiTelemetry.class) {
        local = TELEMETRY_SERVICE.get();
        if (local == null) {
          local = NeoTelemetryService.runtime();
          TELEMETRY_SERVICE.set(local);
        }
      }
    }
    return local;
  }

  private static java.util.concurrent.ScheduledThreadPoolExecutor buildDelayer() {
    java.util.concurrent.ScheduledThreadPoolExecutor executor =
        new java.util.concurrent.ScheduledThreadPoolExecutor(1, runnable -> {
          Thread thread = new Thread(runnable, "accounting-kpi-probe-delay");
          thread.setDaemon(true);
          return thread;
        });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  private static ThreadPoolExecutor buildSender() {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
          Thread thread = new Thread(runnable, "accounting-kpi-telemetry");
          thread.setDaemon(true);
          return thread;
        });
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }
}
