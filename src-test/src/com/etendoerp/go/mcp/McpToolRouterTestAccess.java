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

import org.codehaus.jettison.json.JSONObject;

/**
 * Test support: lets an integration test in another package run one MCP tool handler for real
 * ({@link McpToolRouter} keeps them package-private or private). Not a test itself.
 *
 * @covers com.etendoerp.go.mcp.McpToolRouter
 */
public final class McpToolRouterTestAccess {

  private McpToolRouterTestAccess() {
  }

  /**
   * Runs {@code etendo_action} as the router does once the tool call is authorized.
   *
   * @param specName the spec
   * @param args     the tool arguments ({@code entity}, {@code id}, {@code action},
   *                 {@code parameters})
   * @return the MCP result
   */
  public static JSONObject handleAction(String specName, JSONObject args) throws Exception {
    return new McpToolRouter().handleAction(specName, args);
  }

  /**
   * Runs {@code etendo_update} as the router does once the tool call is authorized ({@code
   * handleUpdate} is private, so it is reached by reflection; the router commits nothing itself).
   *
   * @param specName the spec
   * @param args     the tool arguments ({@code entity}, {@code id}, {@code fields},
   *                 {@code updated})
   * @return the MCP result
   */
  public static JSONObject handleUpdate(String specName, JSONObject args) throws Exception {
    java.lang.reflect.Method update = McpToolRouter.class.getDeclaredMethod("handleUpdate",
        String.class, JSONObject.class);
    update.setAccessible(true);
    try {
      return (JSONObject) update.invoke(new McpToolRouter(), specName, args);
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
    }
  }
}
