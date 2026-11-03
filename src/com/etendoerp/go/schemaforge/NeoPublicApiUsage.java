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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.oauth2.PublicApiKeyAttribution;
import com.etendoerp.go.usageevents.PublicApiCall;
import com.etendoerp.go.usageevents.PublicApiCaller;
import com.etendoerp.go.usageevents.PublicApiUsageCounter;

/**
 * ETP-6057 — counts every NEO request made with a public API key, once it has been answered.
 *
 * <p>{@link NeoServlet} calls {@link #record} in a {@code finally} around the whole request, so the
 * status counted is the one the client got, whichever path wrote it: the 401/402/403 of
 * authentication, the RBAC 403, a handler's 4xx/5xx, a classified exception. A request is a public API
 * request when the authentication pipeline attached a {@link PublicApiCaller} to it
 * ({@link PublicApiKeyAttribution}); any other request costs one attribute read.</p>
 *
 * <p>Shared NEO code, so it decides on structure only: the scope of the credential (through the
 * attribute) and the shape of the path ({@link NeoServletSupport#parsePath}). It names no spec, entity
 * or action.</p>
 *
 * <h2>The action re-read is not counted twice</h2>
 *
 * <p>The public API gateway answers {@code POST /{resource}/{id}/actions/{action}} with two NEO
 * calls: the action, then a {@code GET} of the record, because a NEO action answers only
 * {@code {status, message}}. One public request must count once. So a successful action leaves a
 * single-use <i>re-read credit</i> for that key and record, valid {@value #REREAD_WINDOW_MS} ms; the
 * first {@code GET} of exactly that record by the same key inside the window consumes it and is not
 * counted. Nothing the client sends decides it — no header, no parameter: the credit exists only
 * because this instance answered the action. A client calling NEO directly gets at most one uncounted
 * read per successful action, the same as the gateway. The credit is per instance: a re-read that a
 * load balancer sends to another node is counted (an over-count, never an under-count). Credit keys
 * are SHA-256 digests, so a client-chosen record id (or spec, entity) never sits in memory at its own
 * length.</p>
 *
 * <p>The NEO action route is {@code /{spec}/{entity}/{recordId}/action/{name}}
 * ({@code NeoServletSupport.parseActionOrRecordPath}: the fourth segment is {@code action}, the
 * fifth the action name); the gateway's public route {@code /api/v1/{resource}/{id}/actions/{action}}
 * is translated to it.</p>
 *
 * <h2>A batch counts one request per operation</h2>
 *
 * <p>{@code POST /sws/neo/batch} runs many operations in one HTTP call. So that a batch cannot be used
 * to make fewer requests count, {@code BatchService} hands the operations' spec and entity over
 * ({@link #noteBatchOperations}) and each counts as one request of kind {@code batchOperation}, with
 * method {@code POST} (every operation is a create) and the status of the batch answer: a synchronous
 * batch is one transaction, so every operation shares its outcome; an asynchronous one answers
 * {@code 202} before any operation runs. A batch refused before its operations are read (an empty or
 * malformed body) counts as one request.</p>
 */
final class NeoPublicApiUsage {

  private static final Logger log = LogManager.getLogger(NeoPublicApiUsage.class);

  /** How long a successful action's re-read credit lasts. */
  static final long REREAD_WINDOW_MS = 30_000L;

  /** Most re-read credits held at once; past it a new credit is not granted (over-count). */
  static final int MAX_REREAD_CREDITS = 10_000;

  /** Request attribute holding the {@code [spec, entity]} pairs of a public API batch. */
  static final String BATCH_OPERATIONS_ATTRIBUTE = "com.etendoerp.go.publicapi.batchOperations";

  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";

  private static final NeoPublicApiUsage DEFAULT =
      new NeoPublicApiUsage(PublicApiUsageCounter.getDefault(), System::currentTimeMillis);

  private final PublicApiUsageCounter counter;
  private final LongSupplier clock;
  /** Re-read credit key → expiry (epoch ms). */
  private final Map<String, Long> rereadCredits = new ConcurrentHashMap<>();

  NeoPublicApiUsage(PublicApiUsageCounter counter, LongSupplier clock) {
    this.counter = counter;
    this.clock = clock;
  }

  static NeoPublicApiUsage getDefault() {
    return DEFAULT;
  }

  /**
   * Count the answered request when it is a public API request. Never throws, never blocks.
   *
   * @param request  the request
   * @param response the response, already written
   * @param method   the HTTP method the servlet dispatched
   */
  void record(HttpServletRequest request, HttpServletResponse response, String method) {
    try {
      PublicApiCaller caller = PublicApiKeyAttribution.of(request);
      if (caller == null) {
        return;
      }
      int status = response.getStatus();
      if (recordBatchOperations(request, caller, status)) {
        return;
      }
      String verb = method == null ? null : method.toUpperCase(Locale.ROOT);
      NeoServlet.NeoPathInfo path = NeoServletSupport.parsePath(request.getPathInfo());
      if (consumeRereadCredit(caller, path, verb)) {
        return;
      }
      counter.record(caller, PublicApiCall.of(path.specName, path.entityName, path.actionName,
          verb, status));
      if (path.isAction && METHOD_POST.equals(verb) && status >= 200 && status < 300) {
        grantRereadCredit(caller, path);
      }
    } catch (Throwable t) { // NOSONAR — counting must never reach the response or the caller.
      log.debug("Could not count a public API request.", t);
    }
  }

  /**
   * Note the operations of a batch call, for {@link #record}. Called by {@code BatchService} once
   * the body is parsed; does nothing for a request that is not a public API request. Never throws.
   *
   * @param request    the batch request
   * @param operations the batch's operations array
   */
  static void noteBatchOperations(HttpServletRequest request, JSONArray operations) {
    try {
      if (operations == null || PublicApiKeyAttribution.of(request) == null) {
        return;
      }
      List<String[]> pairs = new ArrayList<>(operations.length());
      for (int i = 0; i < operations.length(); i++) {
        JSONObject op = operations.optJSONObject(i);
        pairs.add(op == null ? new String[2]
            : new String[] { op.optString("spec", null), op.optString("entity", null) });
      }
      request.setAttribute(BATCH_OPERATIONS_ATTRIBUTE, pairs);
    } catch (Throwable t) { // NOSONAR — counting must never reach the batch.
      log.debug("Could not note the operations of a public API batch.", t);
    }
  }

  /** @return true when the request was a batch whose operations were counted one by one */
  private boolean recordBatchOperations(HttpServletRequest request, PublicApiCaller caller,
      int status) {
    Object noted = request.getAttribute(BATCH_OPERATIONS_ATTRIBUTE);
    if (!(noted instanceof List)) {
      return false;
    }
    for (Object pair : (List<?>) noted) {
      String[] specAndEntity = pair instanceof String[] ? (String[]) pair : new String[2];
      counter.record(caller,
          PublicApiCall.batchOperation(specAndEntity[0], specAndEntity[1], status));
    }
    return true;
  }

  private boolean consumeRereadCredit(PublicApiCaller caller, NeoServlet.NeoPathInfo path,
      String verb) {
    if (!METHOD_GET.equals(verb) || path.isAction || path.recordId == null) {
      return false;
    }
    Long expiry = rereadCredits.remove(creditKey(caller, path));
    return expiry != null && expiry >= clock.getAsLong();
  }

  private void grantRereadCredit(PublicApiCaller caller, NeoServlet.NeoPathInfo path) {
    if (path.recordId == null) {
      return;
    }
    long now = clock.getAsLong();
    if (rereadCredits.size() >= MAX_REREAD_CREDITS) {
      rereadCredits.values().removeIf(expiry -> expiry < now);
      if (rereadCredits.size() >= MAX_REREAD_CREDITS) {
        return;
      }
    }
    rereadCredits.put(creditKey(caller, path), now + REREAD_WINDOW_MS);
  }

  /**
   * Key, spec, entity and record: the record the gateway reads back. Entity is case-blind. Stored as
   * a SHA-256 digest, so the client-controlled parts cost a fixed 64 characters whatever their length.
   */
  static String creditKey(PublicApiCaller caller, NeoServlet.NeoPathInfo path) {
    String raw = caller.clientId() + '|' + caller.keyId() + '|' + path.specName + '|'
        + (path.entityName == null ? "" : path.entityName.toLowerCase(Locale.ROOT)) + '|'
        + path.recordId;
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // Every JVM ships SHA-256; clipping keeps the bound if one ever did not.
      return StringUtils.left(raw, 256);
    }
  }

  /** @return re-read credits currently held; for tests */
  int rereadCredits() {
    return rereadCredits.size();
  }
}
