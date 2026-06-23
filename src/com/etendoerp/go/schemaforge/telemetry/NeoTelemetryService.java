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

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Backend observability facade for NEO authoritative events and timings.
 */
public class NeoTelemetryService {

  private static final Logger log = LogManager.getLogger(NeoTelemetryService.class);
  private static final String BACKEND_PREFIX = "backend_";
  private static final String PROP_DURATION_MS = "durationMs";
  private static final String PROP_HTTP_STATUS = "httpStatus";
  private static final String STATUS_FAILED = "failed";
  private static final String STATUS_SUCCESS = "success";

  private static final Set<String> DENYLISTED_PROPERTY_KEYS = new HashSet<>(Arrays.asList(
      "authCode",
      "authorization",
      "businessPartner",
      "businessPartnerName",
      "code",
      "documentId",
      "documentNo",
      "hash",
      "id",
      "label",
      "name",
      "oauthState",
      "query",
      "rawUrl",
      "recordId",
      "search",
      "state",
      "token",
      "url"));

  private static final Set<String> SAFE_PROPERTY_KEYS = new HashSet<>(Arrays.asList(
      "accuracy",
      "attempt",
      "category",
      "count",
      PROP_DURATION_MS,
      "entity",
      PROP_HTTP_STATUS,
      "operation",
      "position",
      "score",
      "source",
      "specName",
      "status",
      "step",
      "supportRequested",
      "type",
      "value"));

  private static final Map<String, NumericBounds> NUMERIC_PROPERTY_BOUNDS =
      buildNumericBounds();

  private final NeoTelemetrySink sink;
  private final LongSupplier nanoClock;
  private final Clock clock;

  /**
   * Creates the default logging backend telemetry service.
   *
   * @return logging telemetry service
   */
  public static NeoTelemetryService logging() {
    return new NeoTelemetryService(new LogNeoTelemetrySink(), System::nanoTime, Clock.systemUTC());
  }

  /**
   * Creates a service with custom dependencies.
   *
   * @param sink event sink
   * @param nanoClock monotonic clock for durations
   */
  public NeoTelemetryService(NeoTelemetrySink sink, LongSupplier nanoClock) {
    this(sink, nanoClock, Clock.systemUTC());
  }

  NeoTelemetryService(NeoTelemetrySink sink, LongSupplier nanoClock, Clock clock) {
    this.sink = sink == null ? NeoTelemetrySink.noop() : sink;
    this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  /**
   * Emits a sanitized backend event. Sink failures are logged and swallowed.
   *
   * @param eventName backend event name
   * @param properties event properties
   */
  public void emit(String eventName, Map<String, ?> properties) {
    if (!isBackendEvent(eventName)) {
      return;
    }
    try {
      sink.emit(new NeoTelemetryEvent(eventName, sanitizeProperties(properties),
          Instant.now(clock)));
    } catch (Exception e) {
      log.warn("Backend telemetry sink failed for event {}: {}", eventName,
          e.getClass().getSimpleName());
    }
  }

  /**
   * Measures one NEO write operation and emits a completion event.
   *
   * @param context NEO request context
   * @param action business action
   * @return action result
   */
  public NeoResponse measureBackendOperation(
      NeoContext context, Supplier<NeoResponse> action) {
    long startedAt = nanoClock.getAsLong();
    try {
      NeoResponse response = action.get();
      emitWriteOperation(context, response, startedAt, null);
      return response;
    } catch (RuntimeException e) {
      emitWriteOperation(context, null, startedAt, e);
      throw e;
    }
  }

  /**
   * Returns whether the method mutates data and should be timed.
   *
   * @param method HTTP method
   * @return true for write methods
   */
  public static boolean isWriteMethod(String method) {
    String normalized = StringUtils.upperCase(method);
    return "POST".equals(normalized)
        || "PUT".equals(normalized)
        || "PATCH".equals(normalized)
        || "DELETE".equals(normalized);
  }

  private void emitWriteOperation(
      NeoContext context, NeoResponse response, long startedAt, RuntimeException error) {
    if (context == null || !isWriteMethod(context.getHttpMethod())) {
      return;
    }

    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("source", "neo");
    properties.put("specName", context.getSpecName());
    properties.put("entity", context.getEntityName());
    properties.put("operation", operationFor(context.getHttpMethod()));
    properties.put("status", statusFor(response, error));
    properties.put(PROP_DURATION_MS, durationMillis(startedAt, nanoClock.getAsLong()));
    if (response != null) {
      properties.put(PROP_HTTP_STATUS, response.getHttpStatus());
    }
    emit(NeoTelemetryEvents.BACKEND_WRITE_OPERATION_COMPLETED, properties);
  }

  private static boolean isBackendEvent(String eventName) {
    return StringUtils.isNotBlank(eventName) && eventName.startsWith(BACKEND_PREFIX);
  }

  private static String operationFor(String method) {
    String normalized = StringUtils.upperCase(method);
    switch (normalized) {
      case "POST":
        return "create";
      case "PUT":
      case "PATCH":
        return "update";
      case "DELETE":
        return "delete";
      default:
        return "unknown";
    }
  }

  private static String statusFor(NeoResponse response, RuntimeException error) {
    if (error != null) {
      return STATUS_FAILED;
    }
    if (response == null) {
      return STATUS_SUCCESS;
    }
    return response.getHttpStatus() >= 400 ? STATUS_FAILED : STATUS_SUCCESS;
  }

  private static long durationMillis(long startedAt, long endedAt) {
    long nanos = Math.max(0L, endedAt - startedAt);
    return Math.round(nanos / 1_000_000.0d);
  }

  private static Map<String, Object> sanitizeProperties(Map<String, ?> properties) {
    Map<String, Object> sanitized = new LinkedHashMap<>();
    if (properties == null) {
      return sanitized;
    }
    for (Map.Entry<String, ?> entry : properties.entrySet()) {
      Object value = sanitizeProperty(entry.getKey(), entry.getValue());
      if (value != null) {
        sanitized.put(entry.getKey(), value);
      }
    }
    return sanitized;
  }

  private static Object sanitizeProperty(String key, Object value) {
    if (DENYLISTED_PROPERTY_KEYS.contains(key) || !SAFE_PROPERTY_KEYS.contains(key)
        || value == null) {
      return null;
    }
    if (NUMERIC_PROPERTY_BOUNDS.containsKey(key)) {
      return isSafeNumber(key, value) ? value : null;
    }
    if (value instanceof Number) {
      return isSafeNumber(key, value) ? value : null;
    }
    if (value instanceof String || value instanceof Boolean) {
      return value;
    }
    return null;
  }

  private static boolean isSafeNumber(String key, Object value) {
    if (!(value instanceof Number)) {
      return false;
    }
    double numberValue = ((Number) value).doubleValue();
    if (!Double.isFinite(numberValue)) {
      return false;
    }
    NumericBounds bounds = NUMERIC_PROPERTY_BOUNDS.get(key);
    return bounds == null || bounds.contains(numberValue);
  }

  private static Map<String, NumericBounds> buildNumericBounds() {
    Map<String, NumericBounds> bounds = new HashMap<>();
    bounds.put("accuracy", new NumericBounds(0, 100));
    bounds.put("attempt", new NumericBounds(0, 100));
    bounds.put("count", new NumericBounds(0, 100000));
    bounds.put(PROP_DURATION_MS, new NumericBounds(0, 86400000));
    bounds.put(PROP_HTTP_STATUS, new NumericBounds(100, 599));
    bounds.put("position", new NumericBounds(0, 100));
    bounds.put("score", new NumericBounds(0, 10));
    bounds.put("step", new NumericBounds(0, 100));
    bounds.put("value", new NumericBounds(0, 1000000));
    return bounds;
  }

  private static final class NumericBounds {
    private final double min;
    private final double max;

    private NumericBounds(double min, double max) {
      this.min = min;
      this.max = max;
    }

    private boolean contains(double value) {
      return value >= min && value <= max;
    }
  }
}
