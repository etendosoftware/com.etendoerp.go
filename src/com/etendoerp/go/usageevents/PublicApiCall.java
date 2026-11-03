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

package com.etendoerp.go.usageevents;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

/**
 * ETP-6057 — what a public API request asked for and how it was answered, reduced to the bounded
 * dimensions a {@link PublicApiUsageCounter} bucket is keyed by.
 *
 * <p><b>No record id, no query, no body.</b> The spec, entity and action segments come from the
 * request path, which the caller controls, so {@link #of} normalizes them: only
 * {@code [A-Za-z0-9_.-]} survives, clipped to {@value #SEGMENT_MAX} characters, and an empty
 * result becomes {@code null}. That keeps a crafted path from shaping a row or growing the number
 * of buckets with junk; the counter caps the bucket count on top of this.</p>
 *
 * <p><b>The kind is never taken from the path.</b> What billing must tell apart — a NEO request, one
 * operation of a batch, a refused token exchange — is {@link #kind}, set only by the factory that
 * built the call ({@link #of}, {@link #batchOperation}, {@link #tokenExchange}) and stored as
 * {@code properties.kind}. The token exchange's {@link #TARGET_TOKEN} also contains a {@code /},
 * which {@link #of} strips, so no request path can produce that target either.</p>
 *
 * @param target     spec name (the first NEO path segment, or the spec of a batch operation), or
 *                   {@link #TARGET_TOKEN}; null when the path had none
 * @param entity     entity name (the second segment), or null
 * @param actionName action name of a NEO action route ({@code /{spec}/{entity}/{id}/action/{name}},
 *                   as {@code NeoServletSupport.parsePath} reads it), or null
 * @param method     HTTP method, upper case
 * @param status     HTTP status of the answer
 * @param kind       {@link #KIND_REQUEST}, {@link #KIND_BATCH_OPERATION} or
 *                   {@link #KIND_TOKEN_EXCHANGE}
 */
public record PublicApiCall(
    String target,
    String entity,
    String actionName,
    String method,
    int status,
    String kind) {

  /**
   * Target of a request refused at the OAuth2 token exchange (wrong key secret). Its {@code /} can
   * never survive {@link #segment}, so a path-derived target is never equal to it.
   */
  public static final String TARGET_TOKEN = "/oauth2/token";

  /** One NEO request. */
  public static final String KIND_REQUEST = "request";
  /** One operation of a {@code /sws/neo/batch} call: each counts as one request. */
  public static final String KIND_BATCH_OPERATION = "batchOperation";
  /** A key refused at the token exchange: recorded, never billed. */
  public static final String KIND_TOKEN_EXCHANGE = "tokenExchange";

  /** Spec, entity and action of a bucket created past the counter's bucket cap. */
  static final String OVERFLOW = "_other";

  /** Longest normalized path segment kept. */
  static final int SEGMENT_MAX = 60;

  /**
   * Build a NEO request from raw path segments, normalizing every segment as the class javadoc
   * describes.
   *
   * @param target     raw spec segment, possibly null
   * @param entity     raw entity segment, possibly null
   * @param actionName raw action segment, possibly null
   * @param method     HTTP method, possibly null
   * @param status     HTTP status
   * @return the normalized call, of kind {@link #KIND_REQUEST}; never null
   */
  public static PublicApiCall of(String target, String entity, String actionName, String method,
      int status) {
    return new PublicApiCall(segment(target), segment(entity), segment(actionName),
        method(method), status, KIND_REQUEST);
  }

  /**
   * One operation of a batch call. A batch operation is a create, so its method is {@code POST};
   * spec and entity come from the operation and are normalized like path segments.
   *
   * @param spec   the operation's raw {@code spec}
   * @param entity the operation's raw {@code entity}
   * @param status the HTTP status of the batch answer
   * @return the call, of kind {@link #KIND_BATCH_OPERATION}
   */
  public static PublicApiCall batchOperation(String spec, String entity, int status) {
    return new PublicApiCall(segment(spec), segment(entity), null, "POST", status,
        KIND_BATCH_OPERATION);
  }

  /**
   * A token exchange refused for a public key.
   *
   * @param status the HTTP status answered
   * @return the call, of kind {@link #KIND_TOKEN_EXCHANGE} and target {@link #TARGET_TOKEN}
   */
  public static PublicApiCall tokenExchange(int status) {
    return new PublicApiCall(TARGET_TOKEN, null, null, "POST", status, KIND_TOKEN_EXCHANGE);
  }

  /** @return true when the status is an error (4xx/5xx) */
  public boolean isError() {
    return status >= 400;
  }

  /** @return this call with its path dimensions replaced by {@link #OVERFLOW} */
  PublicApiCall overflow() {
    return new PublicApiCall(OVERFLOW, OVERFLOW, null, method, status, kind);
  }

  static String segment(String raw) {
    if (raw == null) {
      return null;
    }
    String kept = raw.replaceAll("[^A-Za-z0-9_.\\-]", "");
    return StringUtils.trimToNull(StringUtils.left(kept, SEGMENT_MAX));
  }

  private static String method(String raw) {
    return StringUtils.upperCase(StringUtils.left(segment(raw), 10), Locale.ROOT);
  }
}
