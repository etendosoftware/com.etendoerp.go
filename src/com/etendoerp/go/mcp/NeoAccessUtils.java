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

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * RBAC access check utilities for the MCP tool registry.
 * <p>
 * Delegates to {@link NeoAccessHelper} — the canonical implementation shared
 * across the NEO Headless servlet and MCP layers.
 * <p>
 * Checks are based on AD_Window_Access / AD_Process_Access records for the
 * current role. The System Administrator role (id "0") bypasses all checks.
 */
public final class NeoAccessUtils {

  private NeoAccessUtils() {
    // utility class
  }

  /**
   * Check if the current role has (read) access to the given AD_Window.
   * <p>
   * Equivalent to {@link #hasWindowAccess(String, String)} with a {@code GET} method —
   * use this only for visibility/discovery checks (e.g. "is this window in the tool
   * catalog at all"), never to gate an actual write operation.
   *
   * @param windowId AD_Window_ID to check
   * @return true if the role has an active WindowAccess record, or is System Admin
   */
  public static boolean hasWindowAccess(String windowId) {
    return NeoAccessHelper.hasWindowAccess(windowId);
  }

  /**
   * Check if the current role has access to the given AD_Window for the given HTTP-method
   * equivalent, enforcing the read-only vs. full-access tiering (ETP-4510). MCP tools that
   * mutate data (create/update/delete/batch) must call this with the write-intent method
   * ({@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE}) instead of the bare 1-arg
   * overload, so a read-only {@code AD_Window_Access} row is correctly denied.
   *
   * @param windowId   AD_Window_ID to check
   * @param httpMethod the HTTP-method equivalent of the MCP operation (e.g. {@code "POST"}
   *                   for {@code neo_create}, {@code "GET"} for read-only tools)
   * @return true if the role is allowed to perform {@code httpMethod} on {@code windowId}
   */
  public static boolean hasWindowAccess(String windowId, String httpMethod) {
    return NeoAccessHelper.hasWindowAccess(windowId, httpMethod);
  }

  /**
   * Check if the current role has access to the given AD_Process.
   *
   * @param processId AD_Process_ID to check
   * @return true if the role has an active ProcessAccess record, or is System Admin
   */
  public static boolean hasProcessAccess(String processId) {
    return NeoAccessHelper.hasProcessAccess(processId);
  }
}
