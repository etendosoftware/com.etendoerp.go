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
package com.etendoerp.go.mcp;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The MCP protocol revisions this server speaks, and how one is chosen for a request (ETP-5639).
 *
 * <p>Two eras are served (ETP-5640). The {@code initialize}-based revisions ({@link #SUPPORTED},
 * the "legacy" era) and the stateless revisions ({@link #MODERN_SUPPORTED}), where every request
 * carries its version in {@code params._meta}. Which era a request belongs to is decided by
 * {@link McpRequestEra}; the rules below are the legacy era's.</p>
 *
 * <ul>
 *   <li>{@code initialize}: the client's {@code protocolVersion} is answered as is when supported,
 *       otherwise the latest (MCP lifecycle rule).</li>
 *   <li>Later requests: the {@value #HEADER} header. Missing means {@value #HEADER_FALLBACK}, as the
 *       spec prescribes for a client that predates the header. An unsupported value is served with
 *       the session's negotiated version (or the latest) and logged once per request as a
 *       {@code WARN} — <b>lenient on purpose</b>: answering {@code 400} would cut off a client for a
 *       header it may be getting wrong while everything else works. It turns strict when the
 *       2026-07-28 era is added, where era detection depends on the header.</li>
 * </ul>
 */
final class McpProtocolVersion {

  private static final Logger log = LogManager.getLogger(McpProtocolVersion.class);

  /** The header a client sends on every request after {@code initialize} (MCP 2025-06-18). */
  static final String HEADER = "MCP-Protocol-Version";
  /** The newest revision served, answered to a client asking for one we do not know. */
  static final String LATEST = "2025-11-25";
  /** What a request without the header is taken to speak (Streamable HTTP, 2025-06-18). */
  static final String HEADER_FALLBACK = "2025-03-26";
  /** Every {@code initialize}-based (legacy) revision served, oldest first. */
  static final List<String> SUPPORTED =
      List.of("2024-11-05", HEADER_FALLBACK, "2025-06-18", LATEST);
  /** The newest stateless (modern) revision served. */
  static final String MODERN_LATEST = "2026-07-28";
  /** Every stateless (modern) revision served, oldest first. */
  static final List<String> MODERN_SUPPORTED = List.of(MODERN_LATEST);
  /**
   * Every revision served, newest first, as {@code server/discover} and an
   * {@code UnsupportedProtocolVersionError} list them. The legacy entries tell a dual-era client it
   * can also fall back to {@code initialize}.
   */
  static final List<String> ALL_SUPPORTED =
      List.of(MODERN_LATEST, LATEST, "2025-06-18", HEADER_FALLBACK, "2024-11-05");

  /** Bound on how much of an unexpected header value reaches the log. */
  private static final int MAX_LOGGED_VALUE = 40;

  private McpProtocolVersion() {
  }

  /**
   * The version to answer an {@code initialize} with.
   *
   * @param requested the client's {@code params.protocolVersion}, may be {@code null}
   * @return {@code requested} when supported, else {@link #LATEST}
   */
  static String negotiate(String requested) {
    String value = StringUtils.trimToNull(requested);
    return value != null && SUPPORTED.contains(value) ? value : LATEST;
  }

  /**
   * The version a non-{@code initialize} request is served with.
   *
   * @param header     the {@value #HEADER} header value, may be {@code null}
   * @param negotiated the version this session negotiated in {@code initialize}, may be
   *                   {@code null}
   * @param clientName the client's name, for the log line
   * @return the effective version; never refuses the request
   */
  static String forRequest(String header, String negotiated, String clientName) {
    String value = StringUtils.trimToNull(header);
    if (value == null) {
      return HEADER_FALLBACK;
    }
    if (SUPPORTED.contains(value)) {
      return value;
    }
    String served = negotiated != null ? negotiated : LATEST;
    log.warn("MCP client sent unsupported {} '{}' (client={}) session={} — served as {}", HEADER,
        StringUtils.abbreviate(value, MAX_LOGGED_VALUE), clientName,
        McpUsageTelemetry.sessionForLog(), served);
    return served;
  }
}
