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

/** ETP-5852: the {@code defaults} section of {@code MCP_CONFIG} and its catalog line. */
class McpDefaultsSectionTest {

  @Test
  void validDeclarationRendersTheCatalogLine() throws Exception {
    JSONObject body = new JSONObject(
        "{\"context\":[\"businessPartner\"],\"reason\":\"Terms come from the partner.\"}");

    assertTrue(McpDefaultsSection.validate(body).isEmpty());
    assertEquals("sales-invoice/header: businessPartner — Terms come from the partner.",
        McpDefaultsSection.catalogLine("sales-invoice", "header", body));
  }

  @Test
  void reasonIsOptional() throws Exception {
    JSONObject body = new JSONObject("{\"context\":[\"businessPartner\",\"warehouse\"]}");

    assertEquals("sales-order/header: businessPartner, warehouse",
        McpDefaultsSection.catalogLine("sales-order", "header", body));
  }

  @Test
  void emptyOrMalformedContextIsAProblemAndPublishesNothing() throws Exception {
    for (String raw : new String[] { "{}", "{\"context\":[]}", "{\"context\":[\"\"]}",
        "{\"context\":\"businessPartner\"}", "{\"context\":[\"x\"],\"reason\":3}" }) {
      JSONObject body = new JSONObject(raw);
      assertFalse(McpDefaultsSection.validate(body).isEmpty(), raw);
      assertNull(McpDefaultsSection.catalogLine("s", "e", body), raw);
    }
  }
}
