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
   * @return the HTTP status returned by Context7, or {@link #NO_STATUS} when none was received
   */
  int getStatusCode() {
    return statusCode;
  }
}
