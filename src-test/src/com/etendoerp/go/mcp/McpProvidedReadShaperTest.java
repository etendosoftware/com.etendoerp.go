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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * ETP-5852: a list served by an entity's customization reaches the agent in the generic list shape,
 * paged and projected like any other list.
 *
 * @covers com.etendoerp.go.mcp.McpProvidedReadShaper
 */
class McpProvidedReadShaperTest {

  private static JSONObject nestedThreeRows() throws Exception {
    return new JSONObject("{\"response\":{\"status\":0,\"startRow\":0,\"endRow\":2,"
        + "\"totalRows\":3,\"data\":[{\"id\":\"a\",\"qty\":1,\"warehouse\":\"w\","
        + "\"warehouse$_identifier\":\"W\"},{\"id\":\"b\",\"qty\":2,\"warehouse\":\"w\"},"
        + "{\"id\":\"c\",\"qty\":3,\"warehouse\":\"w\"}]}}");
  }

  @Test
  void nestedOrFlatBodiesBecomeTheCoreShape() throws Exception {
    assertTrue(McpProvidedReadShaper.wrapped(nestedThreeRows()).getJSONObject("response")
        .has("data"));
    JSONObject flat = new JSONObject("{\"data\":[{\"id\":\"a\"}],\"totalRows\":1}");
    assertEquals(1, McpProvidedReadShaper.wrapped(flat).getJSONObject("response")
        .getInt("totalRows"));
    assertNull(McpProvidedReadShaper.wrapped(new JSONObject("{\"kpi\":3}")),
        "a body with no row array is not a list");
  }

  @Test
  void aProviderThatIgnoredPagingIsPagedAndKeepsTheTotal() throws Exception {
    JSONObject response = nestedThreeRows().getJSONObject("response");

    McpProvidedReadShaper.page(response, 1, 1);

    assertEquals(1, response.getJSONArray("data").length());
    assertEquals("b", response.getJSONArray("data").getJSONObject(0).getString("id"));
    assertEquals(1, response.getInt("startRow"));
    assertEquals(1, response.getInt("endRow"));
    assertEquals(3, response.getInt("totalRows"));
  }

  @Test
  void aProviderThatPagedItselfIsLeftAlone() throws Exception {
    JSONObject response = nestedThreeRows().getJSONObject("response");

    McpProvidedReadShaper.page(response, 0, 10);

    assertEquals(3, response.getJSONArray("data").length());
    assertEquals(0, response.getInt("startRow"));
  }

  @Test
  void fieldsTrimRowsKeepCompanionsAndReportUnknownNames() throws Exception {
    JSONObject wrapped = McpProvidedReadShaper.wrapped(nestedThreeRows());

    McpProvidedReadShaper.project(wrapped,
        new JSONObject("{\"fields\":[\"warehouse\",\"nope\"]}"));

    JSONObject row = wrapped.getJSONObject("response").getJSONArray("data").getJSONObject(0);
    assertTrue(row.has("id") && row.has("warehouse") && row.has("warehouse$_identifier"));
    assertFalse(row.has("qty"));
    assertEquals("[\"nope\"]",
        wrapped.getJSONObject("response").getJSONArray("unknownFields").toString());
  }
}
