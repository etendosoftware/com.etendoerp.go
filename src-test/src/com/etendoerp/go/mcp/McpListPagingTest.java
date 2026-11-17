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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Core restarts an empty page from row 0, so {@code etendo_list(offset:7)} over 7 rows answered
 * the same 7 rows (ETP-5837, QA CP-16). The MCP list must answer the empty page it was asked for,
 * keeping the count.
 *
 * @covers com.etendoerp.go.mcp.McpListPaging
 * @covers com.etendoerp.go.mcp.McpToolRouter
 */
class McpListPagingTest {

  private static JSONObject corePage(int startRow, int endRow, int totalRows, int rows)
      throws Exception {
    JSONArray data = new JSONArray();
    for (int i = 0; i < rows; i++) {
      data.put(new JSONObject().put("id", "R" + i));
    }
    return new JSONObject().put("response", new JSONObject()
        .put("status", 0).put("data", data)
        .put("startRow", startRow).put("endRow", endRow).put("totalRows", totalRows));
  }

  /** Columns: requested offset | core startRow, endRow, totalRows, rows | replaced? */
  @ParameterizedTest(name = "[{index}] offset {0}, core startRow {1} -> replaced {5}")
  @CsvSource({
      // the QA repro: offset == totalRows, core fell back to the first page
      "7,  0, 6, 7, 7, true",
      // far past the end, same fallback
      "60, 0, 6, 7, 7, true",
      // a real page in range is left alone
      "2,  2, 6, 7, 5, false",
      // offset 0 is never touched, even on an empty set
      "0,  0, 0, 0, 0, false"
  })
  @DisplayName("replaces only core's restart-from-zero page with an empty page at the offset")
  void replacesOnlyRestartedPage(int offset, int startRow, int endRow, int totalRows, int rows,
      boolean replaced) throws Exception {
    JSONObject response = corePage(startRow, endRow, totalRows, rows);

    assertEquals(replaced, McpListPaging.emptyPageWhenPastEnd(response, offset));

    JSONObject inner = response.getJSONObject("response");
    assertEquals(replaced ? 0 : rows, inner.getJSONArray("data").length());
    assertEquals(replaced ? offset : startRow, inner.getInt("startRow"));
    assertEquals(replaced ? offset - 1 : endRow, inner.getInt("endRow"));
    assertEquals(totalRows, inner.getInt("totalRows"), "the count is never changed");
  }

  @Test
  @DisplayName("a body without the core envelope or its startRow is left untouched")
  void leavesUnknownShapesAlone() throws Exception {
    JSONObject flat = new JSONObject().put("data", new JSONArray().put("x")).put("startRow", 0);
    JSONObject noStart = new JSONObject().put("response",
        new JSONObject().put("data", new JSONArray().put("x")));
    String flatBefore = flat.toString();
    String noStartBefore = noStart.toString();

    assertFalse(McpListPaging.emptyPageWhenPastEnd(flat, 5));
    assertFalse(McpListPaging.emptyPageWhenPastEnd(noStart, 5));
    assertFalse(McpListPaging.emptyPageWhenPastEnd(null, 5));
    assertEquals(flatBefore, flat.toString());
    assertEquals(noStartBefore, noStart.toString());
  }

  /** {@code handleList} needs a live DAL, so the wiring is guarded on the call site. */
  @Test
  @DisplayName("handleList applies it to the fetched page before the read hook and projection")
  void handleListAppliesItAfterTheFetch() {
    String body = McpSourceScanner.methodBody(
        McpSourceScanner.read("com/etendoerp/go/mcp/McpToolRouter.java"), "handleList");
    int fetch = body.indexOf("jsonService.fetch(");
    int fix = body.indexOf("McpListPaging.emptyPageWhenPastEnd(responseJson, offset)");
    int readHook = body.indexOf("McpHookExecutor.runReadHook(");
    assertTrue(fetch >= 0 && fix > fetch && readHook > fix,
        "handleList must correct core's restarted page right after jsonService.fetch and"
            + " before the read hook sees it (ETP-5837, QA CP-16)");
  }
}
