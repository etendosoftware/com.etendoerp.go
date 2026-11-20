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

/**
 * Failure of the outbound Context7 docs lookup (network, timeout or non-2xx response).
 * Carries the HTTP status so callers can tell configuration problems from outages.
 */
class Context7DocsException extends McpToolException {

  /** Value of {@link #getStatusCode()} when there was no HTTP response. */
  static final int NO_STATUS = -1;

  private final int statusCode;

  Context7DocsException(String message, int statusCode, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  /**
   * Describe a throwable and its causes as {@code Class: message -> Class: message ...} down to
   * the root cause. A blank message becomes "no detail"; cause cycles are cut.
   *
   * @param t the outermost throwable
   * @return the compact cause chain
   */
  static String describeCauseChain(Throwable t) {
    StringBuilder sb = new StringBuilder();
    java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
        new java.util.IdentityHashMap<>());
    for (Throwable cur = t; cur != null && seen.add(cur); cur = cur.getCause()) {
      if (sb.length() > 0) {
        sb.append(" -> ");
      }
      String msg = cur.getMessage();
      sb.append(cur.getClass().getSimpleName()).append(": ")
          .append(msg == null || msg.isBlank() ? "no detail" : msg);
    }
    return sb.toString();
  }

  /**
   * @return the HTTP status returned by Context7, or {@link #NO_STATUS} when none was received
   */
  int getStatusCode() {
    return statusCode;
  }

  /**
   * Log level for a failed Context7 lookup: persistent configuration faults (401/403) stay at
   * ERROR so they remain in the error stream; transient failures are WARN.
   *
   * @param status the HTTP status, or {@link #NO_STATUS} when there was no response
   * @return the level to log at
   */
  static org.apache.logging.log4j.Level logLevel(int status) {
    return status == 401 || status == 403
        ? org.apache.logging.log4j.Level.ERROR : org.apache.logging.log4j.Level.WARN;
  }

  /**
   * Build the agent-facing text for this failure, depending on the HTTP status.
   *
   * @return an actionable message for the agent
   */
  String agentMessage() {
    switch (statusCode) {
      case 401:
      case 403:
        return "Context7 rejected the request (status " + statusCode
            + "): check the " + McpToolRouter.PREF_CONTEXT7_TOKEN + " preference.";
      case 429:
        return "Context7 rate limit reached (status 429); retry later or configure the "
            + McpToolRouter.PREF_CONTEXT7_TOKEN + " preference.";
      case 404:
        return "Context7 docs library or URL not found (status 404).";
      default:
        return "The documentation service (Context7) is unavailable right now; retry later. "
            + getMessage();
    }
  }
}
