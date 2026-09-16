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

package com.etendoerp.go.mcp;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;

/**
 * What was observed about one MCP HTTP exchange, as the telemetry path needs it.
 *
 * <p>These four values travel together and are read together: they are the exchange itself — the
 * request it came in on, the bytes in, the bytes out, and when the clock started. Splitting them
 * across a parameter list said nothing that this type does not say better, and it is what pushed
 * {@code McpServlet#recordToolCall} past the parameter limit (java:S107).</p>
 *
 * <p>Not a parameter bag: every derived value the row needs is computed here —
 * {@link #sessionKey()}, {@link #reqBytes()}, {@link #respBytes()}, {@link #durationMs()} — so the
 * servlet no longer carries byte-counting or header-reading of its own. {@link #durationMs()} reads
 * the clock when it is called, which is correct because it is called once, after the response has
 * been written.</p>
 *
 * <p>Built positionally, unlike {@link McpUsageRow}, and deliberately so. That record needs a
 * builder because 17 mostly-{@code String} components make a silent argument swap likely; four
 * components of three distinct types, constructed at the two call sites that produced them and from
 * locals already named {@code body} and {@code rendered}, do not. The two {@code String} components
 * are the one place to be careful: <b>request body first, response body second</b>.</p>
 *
 * @param request        the servlet request, read only for its MCP session header
 * @param requestBody    the raw JSON-RPC request body, measured but never stored
 * @param responseBody   the rendered JSON-RPC response body, measured but never stored
 * @param startedAtNanos {@code System.nanoTime()} taken before dispatch
 */
record McpCallObservation(HttpServletRequest request, String requestBody, String responseBody,
    long startedAtNanos) {

  /** @return the MCP session this exchange belongs to, or null when the client echoes no header */
  String sessionKey() {
    return request == null ? null
        : StringUtils.trimToNull(request.getHeader(McpUsageTelemetry.HEADER_SESSION_ID));
  }

  /** @return size of the request payload in bytes, or null when there was none */
  Long reqBytes() {
    return byteLength(requestBody);
  }

  /** @return size of the response payload in bytes, or null when there was none */
  Long respBytes() {
    return byteLength(responseBody);
  }

  /** @return wall-clock duration from {@link #startedAtNanos} to now */
  long durationMs() {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
  }

  private static Long byteLength(String value) {
    return value == null ? null : (long) value.getBytes(StandardCharsets.UTF_8).length;
  }
}
