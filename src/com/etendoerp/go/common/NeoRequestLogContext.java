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

package com.etendoerp.go.common;

import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.ThreadContext;

/**
 * ETP-5646 — the per-request logging context (log4j2 {@link ThreadContext}, the "MDC") of a
 * first-party NEO request: request id, validated feature key, and the authenticated opaque user
 * and tenant ids.
 *
 * <p>Every key is set by {@link NeoRequestContextFilter} and removed in its {@code finally}, so a
 * pooled Tomcat thread never carries one request's context into the next, whether the request
 * ends normally, with an error status or with an exception. A thread started by a request does
 * not inherit it: wrap its work with {@link #wrap(Runnable)}, which restores the snapshot for the
 * task and clears it afterwards.</p>
 *
 * <p>Values are internal identifiers only (no name, e-mail, payload or secret). The Datadog Java
 * agent writes its own {@code dd.trace_id} / {@code dd.span_id} keys into the same context when
 * it is attached; this class never sets or clears them.</p>
 */
public final class NeoRequestLogContext {

  /** Id of the request: the caller's well-formed {@code X-Request-Id}, else a random UUID. */
  public static final String REQUEST_ID = "request_id";
  /** Validated feature key ({@link NeoFeatureContext}); attribution only. */
  public static final String FEATURE_KEY = "feature_key";
  /** Coarse operation of the request: {@code read} or {@code write}. */
  public static final String OPERATION = "operation";
  /** Authenticated {@code AD_User_ID}, bound by the server-side authentication. */
  public static final String AD_USER_ID = "ad_user_id";
  /** Authenticated {@code AD_Client_ID}, bound by the server-side authentication. */
  public static final String AD_CLIENT_ID = "ad_client_id";
  /** W3C trace id of the browser's {@code traceparent}, when well formed. */
  public static final String FRONTEND_TRACE_ID = "frontend_trace_id";

  /** The keys this class owns, and the only ones it clears. */
  static final String[] KEYS = { REQUEST_ID, FEATURE_KEY, OPERATION, AD_USER_ID, AD_CLIENT_ID,
      FRONTEND_TRACE_ID };

  private static final Pattern OPAQUE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

  private NeoRequestLogContext() {
  }

  /**
   * Binds the authenticated identity of the current request. Called from the server-side
   * authentication, never from request input. An id that is not a plain opaque identifier is
   * skipped. A no-op outside a request that {@link NeoRequestContextFilter} opened.
   *
   * @param userId the {@code AD_User_ID}
   * @param clientId the {@code AD_Client_ID}
   */
  public static void bindIdentity(String userId, String clientId) {
    if (ThreadContext.get(REQUEST_ID) == null) {
      return;
    }
    putOpaque(AD_USER_ID, userId);
    putOpaque(AD_CLIENT_ID, clientId);
  }

  static void put(String key, String value) {
    if (StringUtils.isNotEmpty(value)) {
      ThreadContext.put(key, value);
    }
  }

  private static void putOpaque(String key, String value) {
    if (value != null && OPAQUE_ID.matcher(value).matches()) {
      ThreadContext.put(key, value);
    } else {
      ThreadContext.remove(key);
    }
  }

  /** Removes every key this class owns from the current thread. */
  public static void clear() {
    for (String key : KEYS) {
      ThreadContext.remove(key);
    }
  }

  /**
   * Wraps work handed to another thread: the request's keys are restored for the task and
   * removed afterwards, also when it throws.
   *
   * @param task the work
   * @return the wrapped work
   */
  public static Runnable wrap(Runnable task) {
    Map<String, String> snapshot = ThreadContext.getImmutableContext();
    return () -> {
      for (String key : KEYS) {
        String value = snapshot.get(key);
        if (value != null) {
          ThreadContext.put(key, value);
        }
      }
      try {
        task.run();
      } finally {
        clear();
      }
    };
  }
}
