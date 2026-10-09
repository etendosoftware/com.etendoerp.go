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
 * An MCP {@code resources/read} for a URI that names no resource the caller can read (ETP-5640).
 *
 * <p>A client asking for something that is not there, not a server failure: the servlet answers it
 * without a stack trace, and in the modern era (MCP 2026-07-28) with {@code -32602} Invalid params,
 * the code that replaced {@code -32002}. An unknown spec and a spec the role cannot see are the
 * same answer on purpose, so the error does not reveal which specs exist.</p>
 *
 * <p>Extends {@link IllegalArgumentException} because that is what an unknown URI threw before
 * this type existed.</p>
 */
class McpResourceNotFoundException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  /**
   * @param uri   the URI the client asked for
   * @param cause the lower-level refusal, or {@code null}
   */
  McpResourceNotFoundException(String uri, Throwable cause) {
    super("Resource not found: " + uri, cause);
  }
}
