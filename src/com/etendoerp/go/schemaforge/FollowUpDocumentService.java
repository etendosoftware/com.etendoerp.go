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
package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/**
 * Generic "create the follow-up document for what is still pending" service (ETP-5576).
 *
 * <p>Owns only what is the same whatever the source and target are: enforcing the resolver's
 * verdict on the create path (lock → load → available? → create), and the GET annotation that
 * tells a client, per record, which follow-ups are available. It works only against
 * {@link FollowUpFlow}. It does NOT decide what "pending" means — that is each flow's
 * {@link PendingResolver#loadSources} verdict. {@link #pendingQuantity} is an opt-in arithmetic
 * helper for line-quantity resolvers.
 *
 * <p>This class never names an entity, spec or table, and must not start to: a new flow gets a
 * new resolver and/or creator, never a branch here.
 */
final class FollowUpDocumentService {

  private static final Logger log = LogManager.getLogger(FollowUpDocumentService.class);

  /**
   * GET annotation root:
   * <pre>
   * followUp: {
   *   available: ["shipment", …],          // keys with needed=true, in registration order
   *   &lt;key&gt;: { needed, reason, pendingLines, action, targetSpec, targetEntity },
   *   …                                     // one entry per registered flow, always present
   * }</pre>
   */
  static final String FIELD_FOLLOW_UP = "followUp";
  static final String KEY_AVAILABLE = "available";
  static final String KEY_NEEDED = "needed";
  /** {@link FollowUpException.Reason#getCode()} when not needed, {@code null} when needed. */
  static final String KEY_REASON = "reason";
  /** Number of lines the follow-up would carry — a count, not a quantity sum (UOMs may differ). */
  static final String KEY_PENDING_LINES = "pendingLines";
  static final String KEY_ACTION = "action";
  static final String KEY_TARGET_SPEC = "targetSpec";
  static final String KEY_TARGET_ENTITY = "targetEntity";

  /**
   * Annotation-only {@code reason}: the resolver's lookup failed, so availability is unknown and
   * reported as not needed. Not a {@link FollowUpException.Reason} — nothing is ever thrown or
   * answered over HTTP with it, so it has no status code.
   */
  static final String REASON_LOOKUP_FAILED = "FOLLOW_UP_LOOKUP_FAILED";

  private FollowUpDocumentService() {
  }

  // ---------------------------------------------------------------------------------------------
  // Opt-in arithmetic helper for line-quantity resolvers
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code max(0, min(sourceQty - movedQty, upstreamPendingQty))}, treating a {@code null}
   * {@code sourceQty}/{@code movedQty} as zero and a {@code null} {@code upstreamPendingQty} as
   * "no upstream cap". Pure, no I/O. A resolver that measures pending differently (by amount, by
   * draft presence…) does not call it.
   */
  static BigDecimal pendingQuantity(BigDecimal sourceQty, BigDecimal movedQty,
      BigDecimal upstreamPendingQty) {
    BigDecimal pending = zeroIfNull(sourceQty).subtract(zeroIfNull(movedQty));
    if (upstreamPendingQty != null && upstreamPendingQty.compareTo(pending) < 0) {
      pending = upstreamPendingQty;
    }
    return pending.signum() > 0 ? pending : BigDecimal.ZERO;
  }

  // ---------------------------------------------------------------------------------------------
  // Read side
  // ---------------------------------------------------------------------------------------------

  /**
   * The flow's verdict for one source; {@link FollowUpException.Reason#NOT_FOUND} when its
   * resolver does not know the id. Read-only.
   */
  static PendingResolver.Source evaluate(String sourceId, FollowUpFlow flow) {
    PendingResolver.Source source = flow.loadSources(Collections.singletonList(sourceId))
        .get(sourceId);
    return source != null ? source
        : PendingResolver.Source.unavailable(sourceId, FollowUpException.Reason.NOT_FOUND);
  }

  /**
   * Annotates {@value #FIELD_FOLLOW_UP} on every record of a GET page (list or single record).
   * One {@link PendingResolver#loadSources} call per flow for the whole page. For each
   * record, every registered flow gets an entry under its {@link FollowUpTarget#getKey()},
   * and {@code available} lists the keys whose {@code needed} is true, in the order the
   * flows were registered — so a client builds its option list from {@code available} and
   * hides the action when it is empty. An existing {@code followUp} object on the record is
   * extended, never replaced.
   *
   * <p><b>Degradation, per flow.</b> If one flow's lookup fails it is logged at ERROR and
   * only that key reads {@code needed: false, reason: FOLLOW_UP_LOOKUP_FAILED} — the lookup runs
   * in a savepoint, so the transaction stays usable; the other
   * flows and the parent GET are unaffected.
   */
  static void annotatePage(JSONArray dataArr, List<FollowUpFlow> flows)
      throws JSONException {
    if (dataArr == null || dataArr.length() == 0 || flows == null || flows.isEmpty()) {
      return;
    }
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < dataArr.length(); i++) {
      String id = dataArr.getJSONObject(i).optString("id", null);
      if (id != null && !id.isEmpty()) {
        ids.add(id);
      }
    }
    List<Map<String, PendingResolver.Source>> verdicts = new ArrayList<>(flows.size());
    for (FollowUpFlow flow : flows) {
      verdicts.add(loadForAnnotation(flow, ids));
    }
    for (int i = 0; i < dataArr.length(); i++) {
      annotateRecord(dataArr.getJSONObject(i), flows, verdicts);
    }
  }

  /**
   * Runs one flow's lookup for the annotation inside a SAVEPOINT on the OBDal connection
   * ({@link PendingResolver#loadSources} must query through {@code OBDal.getInstance()}). On
   * PostgreSQL a failed statement aborts the whole transaction, which would break every enricher
   * that runs after this one and the GET itself; rolling back to the savepoint confines the
   * failure to this flow's key (ETP-5576 review W3). The create path does not use this: there
   * a failure must abort the request.
   *
   * @return the verdicts, or {@code null} when the lookup failed (the page reads
   *     {@value #REASON_LOOKUP_FAILED} for this flow)
   */
  private static Map<String, PendingResolver.Source> loadForAnnotation(FollowUpFlow flow,
      List<String> ids) {
    if (ids.isEmpty()) {
      return Collections.emptyMap();
    }
    Connection conn = null;
    Savepoint savepoint = null;
    try {
      conn = OBDal.getInstance().getConnection();
      savepoint = conn.setSavepoint();
      Map<String, PendingResolver.Source> verdicts = flow.loadSources(ids);
      conn.releaseSavepoint(savepoint);
      return verdicts;
    } catch (Exception e) {
      rollbackTo(conn, savepoint);
      log.error("Could not compute follow-up '{}' for {} record(s); annotating needed=false",
          flow.target().getKey(), ids.size(), e);
      return null;
    }
  }

  private static void rollbackTo(Connection conn, Savepoint savepoint) {
    if (conn == null || savepoint == null) {
      return;
    }
    try {
      conn.rollback(savepoint);
    } catch (SQLException rollbackError) {
      log.error("Could not roll back to the follow-up annotation savepoint", rollbackError);
    }
  }

  private static void annotateRecord(JSONObject rec, List<FollowUpFlow> flows,
      List<Map<String, PendingResolver.Source>> verdicts) throws JSONException {
    JSONObject root = followUpRoot(rec);
    JSONArray available = availableKeys(root);
    String id = rec.optString("id", null);
    for (int s = 0; s < flows.size(); s++) {
      FollowUpTarget target = flows.get(s).target();
      Map<String, PendingResolver.Source> byId = verdicts.get(s);
      PendingResolver.Source source = byId != null ? byId.get(id) : null;
      String reason = unavailabilityReason(byId, source);
      boolean needed = reason == null;
      root.put(target.getKey(), followUpEntry(target, source, reason));
      if (needed) {
        available.put(target.getKey());
      }
    }
  }

  /** The record's existing {@value #FIELD_FOLLOW_UP} object, or a new one attached to it. */
  private static JSONObject followUpRoot(JSONObject rec) throws JSONException {
    JSONObject root = rec.optJSONObject(FIELD_FOLLOW_UP);
    if (root == null) {
      root = new JSONObject();
      rec.put(FIELD_FOLLOW_UP, root);
    }
    return root;
  }

  /** The root's existing {@value #KEY_AVAILABLE} array, or a new one attached to it. */
  private static JSONArray availableKeys(JSONObject root) throws JSONException {
    JSONArray available = root.optJSONArray(KEY_AVAILABLE);
    if (available == null) {
      available = new JSONArray();
      root.put(KEY_AVAILABLE, available);
    }
    return available;
  }

  /**
   * Why the follow-up is not needed for one record, or {@code null} when it is needed.
   *
   * @param byId the flow's verdicts for the page, {@code null} when its lookup failed
   * @param source the record's verdict, {@code null} when absent (or when {@code byId} is null)
   * @return {@value #REASON_LOOKUP_FAILED}, a {@link FollowUpException.Reason} code, or
   *     {@code null} when the source is available
   */
  private static String unavailabilityReason(Map<String, PendingResolver.Source> byId,
      PendingResolver.Source source) {
    if (byId == null) {
      return REASON_LOOKUP_FAILED;
    }
    if (source == null) {
      return FollowUpException.Reason.NOT_FOUND.getCode();
    }
    return source.isAvailable() ? null : source.getUnavailability().getCode();
  }

  /**
   * One flow's annotation entry for one record.
   *
   * @param target the flow's identity
   * @param source the record's verdict; non-null whenever {@code reason} is {@code null}
   * @param reason {@code null} when the follow-up is needed
   * @return the {@code {needed, reason, pendingLines, action, targetSpec, targetEntity}} entry
   */
  private static JSONObject followUpEntry(FollowUpTarget target, PendingResolver.Source source,
      String reason) throws JSONException {
    boolean needed = reason == null;
    JSONObject entry = new JSONObject();
    entry.put(KEY_NEEDED, needed);
    entry.put(KEY_REASON, needed ? JSONObject.NULL : reason);
    entry.put(KEY_PENDING_LINES, needed ? source.getLines().size() : 0);
    entry.put(KEY_ACTION, target.getActionName());
    entry.put(KEY_TARGET_SPEC, target.getSpec());
    entry.put(KEY_TARGET_ENTITY, target.getEntity());
    return entry;
  }

  // ---------------------------------------------------------------------------------------------
  // Write side
  // ---------------------------------------------------------------------------------------------

  /**
   * Creates the follow-up document of {@code flow} for {@code sourceId}.
   *
   * <p>Order of operations — everything that can reject runs BEFORE anything is persisted:
   * <ol>
   *   <li>lock the source row ({@link PendingResolver#lockSource});</li>
   *   <li>the resolver's verdict ({@link #evaluate}) — not available → its reason
   *       ({@code NOT_FOUND}, {@code NOT_COMPLETED}, {@code NOTHING_PENDING},
   *       {@code DRAFT_IN_PROGRESS}, …);</li>
   *   <li>available but no line to carry → {@link FollowUpException.Reason#NOTHING_PENDING}
   *       (a document with no line is never created, whatever the resolver measures);</li>
   *   <li>{@link TargetCreator#createTarget} (which resolves its own setup first).</li>
   * </ol>
   * The caller owns the transaction and must roll it back on any exception
   * ({@link FollowUpActionHandler} does).
   *
   * @throws FollowUpException on any business rejection
   */
  static TargetCreator.Result create(String sourceId, FollowUpFlow flow) {
    flow.lockSource(sourceId);
    PendingResolver.Source source = evaluate(sourceId, flow);
    if (!source.isAvailable()) {
      throw new FollowUpException(source.getUnavailability());
    }
    if (source.getLines().isEmpty()) {
      throw new FollowUpException(FollowUpException.Reason.NOTHING_PENDING);
    }
    return flow.createTarget(sourceId, source.getLines());
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }
}
