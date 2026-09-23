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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.usageevents.UsageEvent;
import com.etendoerp.go.usageevents.UsageEventRecorder;
import com.etendoerp.go.usageevents.UsageEventTypes;

/**
 * {@code POST /sws/neo/usage} — the UI's (and the AI BFF's) door into {@code ETGO_USAGE_EVENT}.
 *
 * <p>Parses a batch of events, validates and normalizes each one, and hands it to
 * {@link UsageEventRecorder}, which returns immediately; the INSERT happens on the recorder's own
 * writer thread. The response is {@code 202 {"accepted": n, "dropped": m}} and never waits for it.</p>
 *
 * <p><b>Who is always the caller.</b> Client, organization, user and role come from the
 * {@code OBContext} the NEO authenticator set up from the bearer token
 * ({@link UsageEvent.Builder#fromContext()}); the body has no way to name them. {@code source} is
 * read from the body but only within {@code {ui, ai-bff}}: it is a label, not a trust boundary (D3).
 * A user can inflate their own counts — acceptable for product insight, not for billing.</p>
 *
 * <p><b>Usage never fails the caller.</b> Only a body that is not a JSON object with an
 * {@code events} array is a {@code 400} (and a body past {@value #MAX_BODY_BYTES} bytes a
 * {@code 413}). Anything wrong with a single event drops that event, anything wrong with a single
 * property drops that property, and an unexpected failure after parsing answers {@code 202} with the
 * rest counted as dropped — never a {@code 500}, so an older or newer UI degrades to "not recorded".
 * An unknown {@code eventType} is still forwarded to the recorder, which drops it and logs it at
 * ERROR (D4); it is counted as dropped here and not logged a second time.</p>
 */
class NeoUsageEventEndpoint {

  private static final Logger log = LogManager.getLogger(NeoUsageEventEndpoint.class);

  /** Events accepted per request; the rest are dropped and counted. */
  static final int MAX_EVENTS_PER_REQUEST = 50;

  /**
   * Body cap. Fifty events at the {@link UsageEvent#PROPERTIES_MAX_BYTES} properties ceiling fit
   * well inside it; past it the request is refused before parsing instead of buffered whole.
   */
  static final int MAX_BODY_BYTES = 256 * 1024;

  /** Events per user and session key per {@link #RATE_WINDOW_MS}; the excess is dropped. */
  static final int RATE_LIMIT_EVENTS = 600;
  static final long RATE_WINDOW_MS = 60_000L;

  /** Bound on the rate limiter's map, so a stream of fresh session keys cannot grow it forever. */
  static final int RATE_LIMIT_MAX_KEYS = 10_000;

  /** How far back / ahead a client clock may place an event before it is clamped. */
  static final long MAX_PAST_MS = 24L * 60 * 60 * 1000;
  static final long MAX_FUTURE_MS = 5L * 60 * 1000;

  /** String property values are clipped to this length; keys past their own cap drop the entry. */
  static final int MAX_PROPERTY_STRING = 256;
  static final int MAX_PROPERTY_KEY = 64;

  /**
   * Deepest array/object nesting accepted before parsing. A valid body needs 3 (body, events,
   * event) plus one for properties; the headroom is for callers, not for the parser's stack.
   */
  static final int MAX_JSON_DEPTH = 32;

  private static final long FAILURE_LOG_INTERVAL_MS = 60_000L;

  private static final String KEY_EVENTS = "events";

  private final Consumer<UsageEvent> sink;
  private final LongSupplier clock;
  private final RateLimiter rateLimiter;
  private final AtomicLong lastFailureLogAt = new AtomicLong();

  NeoUsageEventEndpoint() {
    this(UsageEventRecorder::record, System::currentTimeMillis);
  }

  /**
   * @param sink  where built events go; {@link UsageEventRecorder#record} in production
   * @param clock epoch-millis source for clamping and rate limiting; a test seam
   */
  NeoUsageEventEndpoint(Consumer<UsageEvent> sink, LongSupplier clock) {
    this.sink = sink;
    this.clock = clock;
    this.rateLimiter = new RateLimiter(RATE_LIMIT_EVENTS, RATE_WINDOW_MS, RATE_LIMIT_MAX_KEYS,
        clock);
  }

  NeoResponse handle(HttpServletRequest request) throws IOException {
    byte[] raw = readCapped(request);
    if (raw == null) {
      return NeoResponse.error(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
          "Usage body exceeds " + MAX_BODY_BYTES + " bytes");
    }
    JSONArray events = parseEvents(new String(raw, StandardCharsets.UTF_8));
    if (events == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Body must be a JSON object with an \"events\" array");
    }
    return record(events);
  }

  /**
   * Jettison recurses once per nesting level and, on some truncated inputs (a body that stops inside an array),
   * forever: both end in a {@link StackOverflowError} on the request thread, which the servlet's
   * {@code catch (Exception)} does not see. So the depth is bounded before jettison runs, and any
   * error it still throws is a malformed body, not a 500.
   *
   * @return the events array, or null when the body is malformed
   */
  static JSONArray parseEvents(String body) {
    if (StringUtils.isBlank(body) || exceedsDepth(body, MAX_JSON_DEPTH)) {
      return null;
    }
    try {
      Object events = new JSONObject(body).opt(KEY_EVENTS);
      return events instanceof JSONArray ? (JSONArray) events : null;
    } catch (JSONException | RuntimeException | StackOverflowError e) { // NOSONAR — see javadoc.
      return null;
    }
  }

  /**
   * Linear scan for array/object nesting deeper than {@code maxDepth}, ignoring brackets
   * inside JSON strings (escapes honoured). Cheap and total; it does not validate the JSON.
   */
  static boolean exceedsDepth(String body, int maxDepth) {
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
      } else if (c == '"') {
        inString = true;
      } else if (c == '[' || c == '{') {
        if (++depth > maxDepth) {
          return true;
        }
      } else if (c == ']' || c == '}') {
        depth--;
      }
    }
    return false;
  }

  private NeoResponse record(JSONArray events) {
    int total = events.length();
    int accepted = 0;
    try {
      long now = clock.getAsLong();
      int limit = Math.min(total, MAX_EVENTS_PER_REQUEST);
      for (int i = 0; i < limit; i++) {
        if (recordOne(events.opt(i), now)) {
          accepted++;
        }
      }
    } catch (Throwable t) { // NOSONAR — usage must never turn into a 500 for the caller.
      noteFailure(t);
    }
    return accepted(accepted, total - accepted);
  }

  /** @return true when the event was handed to the recorder as a known type */
  private boolean recordOne(Object candidate, long now) {
    if (!(candidate instanceof JSONObject)) {
      return false;
    }
    JSONObject json = (JSONObject) candidate;
    UsageEvent event = toEvent(json, now);
    if (!rateLimiter.tryAcquire(event.clientId() + '|' + event.userId() + '|'
        + StringUtils.defaultString(event.sessionKey()))) {
      return false;
    }
    // Forwarded even when unknown: the recorder is where an unknown type is dropped and logged at
    // ERROR (D4). Here it only counts as dropped, so the log line is not written twice.
    sink.accept(event);
    return UsageEventTypes.isKnown(event.eventType());
  }

  /** Map one body event to a {@link UsageEvent}. Who-columns come from the context, never the body. */
  UsageEvent toEvent(JSONObject json, long now) {
    return UsageEvent.builder()
        .fromContext()
        .eventType(StringUtils.trimToNull(string(json, "eventType")))
        .source(source(string(json, "source")))
        .target(string(json, "target"))
        .action(string(json, "action"))
        .outcome(outcome(string(json, "outcome")))
        .errorCode(string(json, "errorCode"))
        .durationMs(durationMs(json.opt("durationMs")))
        .occurredAt(occurredAt(string(json, "occurredAt"), now))
        .sessionKey(string(json, "sessionKey"))
        .appVersion(string(json, "appVersion"))
        .properties(properties(json.opt("properties")))
        .build();
  }

  /** @return the value when it is a JSON string, null otherwise (a number is not a string here) */
  private static String string(JSONObject json, String key) {
    Object value = json.opt(key);
    return value instanceof String ? (String) value : null;
  }

  /** Only the two sources a NEO bearer can speak for; anything else falls back to {@code ui}. */
  static String source(String value) {
    return UsageEvent.SOURCE_AI_BFF.equals(value) ? UsageEvent.SOURCE_AI_BFF : UsageEvent.SOURCE_UI;
  }

  static String outcome(String value) {
    return UsageEvent.OUTCOME_OK.equals(value) || UsageEvent.OUTCOME_ERROR.equals(value) ? value
        : null;
  }

  static Long durationMs(Object value) {
    if (!(value instanceof Number)) {
      return null;
    }
    long duration = ((Number) value).longValue();
    return duration >= 0 ? duration : null;
  }

  /** ISO-8601 clamped to {@code [now - 24h, now + 5min]}; missing or unparseable means now. */
  static Instant occurredAt(String value, long now) {
    long at = now;
    if (value != null) {
      try {
        at = Instant.parse(value.trim()).toEpochMilli();
      } catch (DateTimeParseException | ArithmeticException e) {
        at = now;
      }
    }
    return Instant.ofEpochMilli(Math.max(now - MAX_PAST_MS, Math.min(now + MAX_FUTURE_MS, at)));
  }

  /**
   * A flat object of string/number/boolean values. A nested object, an array, a null, or an
   * over-long key drops that property only; strings are clipped. Anything that is not an object
   * gives no properties. The 4 KB ceiling on the result is enforced by {@link UsageEvent}.
   */
  static Map<String, Object> properties(Object value) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (!(value instanceof JSONObject)) {
      return result;
    }
    JSONObject json = (JSONObject) value;
    Iterator<?> keys = json.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      Object property = json.opt(key);
      if (key.isEmpty() || key.length() > MAX_PROPERTY_KEY) {
        continue;
      }
      if (property instanceof String) {
        result.put(key, StringUtils.left((String) property, MAX_PROPERTY_STRING));
      } else if (property instanceof Number || property instanceof Boolean) {
        result.put(key, property);
      }
    }
    return result;
  }

  private static NeoResponse accepted(int accepted, int dropped) {
    JSONObject body = new JSONObject();
    try {
      body.put("accepted", accepted);
      body.put("dropped", dropped);
    } catch (JSONException e) {
      // put() with a literal key and an int cannot fail; keep the 202 regardless.
    }
    return new NeoResponse(HttpServletResponse.SC_ACCEPTED, body);
  }

  /** @return the body, or null when it exceeds {@link #MAX_BODY_BYTES} */
  private static byte[] readCapped(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      return null;
    }
    try (InputStream in = request.getInputStream()) {
      byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
      return bytes.length > MAX_BODY_BYTES ? null : bytes;
    }
  }

  /** Log an unexpected failure at WARN, at most once per interval, so it is visible but not noisy. */
  private void noteFailure(Throwable t) {
    long now = System.currentTimeMillis();
    long last = lastFailureLogAt.get();
    if (now - last >= FAILURE_LOG_INTERVAL_MS && lastFailureLogAt.compareAndSet(last, now)) {
      log.warn("Unexpected failure recording usage events; the rest of the batch is dropped.", t);
    } else {
      log.debug("Unexpected failure recording usage events.", t);
    }
  }

  /**
   * Fixed-window counter per key, in memory and per instance. Deliberately simple: it only has to
   * stop one runaway client from flooding the table, not to be exact across a cluster.
   */
  static final class RateLimiter {
    private final int limit;
    private final long windowMs;
    private final int maxKeys;
    private final LongSupplier clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    RateLimiter(int limit, long windowMs, int maxKeys, LongSupplier clock) {
      this.limit = limit;
      this.windowMs = windowMs;
      this.maxKeys = maxKeys;
      this.clock = clock;
    }

    boolean tryAcquire(String key) {
      long now = clock.getAsLong();
      if (windows.size() >= maxKeys && !windows.containsKey(key)) {
        evict(now);
      }
      Window window = windows.compute(key, (k, current) ->
          current == null || now - current.start >= windowMs ? new Window(now) : current);
      return window.count.incrementAndGet() <= limit;
    }

    /**
     * Drop the expired windows; when every key is still live, start over. Clearing lets the burst
     * through for one window — the cheaper failure than growing without bound.
     */
    private void evict(long now) {
      windows.values().removeIf(w -> now - w.start >= windowMs);
      if (windows.size() >= maxKeys) {
        windows.clear();
      }
    }

    int size() {
      return windows.size();
    }

    private static final class Window {
      private final long start;
      private final AtomicLong count = new AtomicLong();

      private Window(long start) {
        this.start = start;
      }
    }
  }
}
