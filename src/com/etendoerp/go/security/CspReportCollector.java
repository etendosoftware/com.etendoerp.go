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
package com.etendoerp.go.security;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;

/**
 * Core of the CSP report collector, free of servlet types so it can be tested directly. It reads
 * the body with a hard size cap, applies the rate limit, runs {@link CspReportSanitizer} and
 * records only the sanitized fields (one log line and one counter per classification). The raw
 * body is never logged, stored or echoed, including when it is rejected.
 */
final class CspReportCollector {

  private static final Logger log = LogManager.getLogger(CspReportCollector.class);

  private final CspReportRateLimiter limiter;
  private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

  CspReportCollector(CspReportRateLimiter limiter) {
    this.limiter = limiter;
  }

  /** Reads at most {@code max} chars; returns null when the body is longer (nothing is kept). */
  static String readBounded(Reader reader, int max) throws IOException {
    StringBuilder sb = new StringBuilder();
    char[] buf = new char[1024];
    int n;
    while ((n = reader.read(buf)) != -1) {
      if (sb.length() + n > max) {
        return null;
      }
      sb.append(buf, 0, n);
    }
    return sb.toString();
  }

  /**
   * Processes one report request. Never throws for bad input: the caller answers 204 regardless.
   *
   * @param client client key used for rate limiting
   * @param body the request reader; consumed with a size cap
   */
  void collect(String client, Reader body) {
    if (!limiter.tryAcquire(client)) {
      count("RATE_LIMITED");
      return;
    }
    String text;
    try {
      text = readBounded(body, CspReportSanitizer.MAX_BODY_CHARS);
    } catch (IOException e) {
      count("READ_ERROR");
      return;
    }
    if (text == null) {
      count("OVERSIZED");
      log.warn("CSP report dropped: oversized body");
      return;
    }
    Optional<JSONObject> report = CspReportSanitizer.sanitize(text);
    if (report.isEmpty()) {
      count("MALFORMED");
      log.warn("CSP report dropped: not a usable report ({} chars)", text.length());
      return;
    }
    count(report.get().optString("classification"));
    log.info("CSP report {}", report.get());
  }

  /** @return how many reports ended in {@code classification} (or a drop reason) so far */
  long total(String classification) {
    LongAdder adder = counters.get(classification);
    return adder == null ? 0 : adder.longValue();
  }

  private void count(String key) {
    counters.computeIfAbsent(key, k -> new LongAdder()).increment();
  }
}
