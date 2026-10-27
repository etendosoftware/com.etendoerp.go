/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.ocr.OcrConfig;

/**
 * {@code GET /sws/neo/ocrconfig}: what the application's upload panel reads so that it asks the
 * extraction tool exactly what the MCP tools ask.
 *
 * @covers com.etendoerp.go.schemaforge.NeoOcrConfigEndpoint
 */
@DisplayName("ETP-5715 — NeoOcrConfigEndpoint")
class NeoOcrConfigEndpointTest {

  private static HttpServletRequest request(String docType) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("docType")).thenReturn(docType);
    return request;
  }

  @Test
  @DisplayName("serves the configuration of a document type: docType, tool, question and schema")
  void servesTheConfiguration() throws Exception {
    NeoResponse response = new NeoOcrConfigEndpoint().handle(request("purchase-invoice"));

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals("purchase-invoice", body.getString("docType"));
    assertEquals("SimpleOcrTool", body.getString("toolName"));
    assertEquals(OcrConfig.get().forSpec("purchase-invoice").orElseThrow().getQuestion(),
        body.getString("question"));
    assertEquals(OcrConfig.get().forSpec("purchase-invoice").orElseThrow().getSchema().toString(),
        body.getJSONObject("schema").toString());
  }

  @Test
  @DisplayName("without a doc type it is a 400; for a type with no OCR it is a 404 that names nothing")
  void refusals() {
    NeoOcrConfigEndpoint endpoint = new NeoOcrConfigEndpoint();

    assertEquals(400, endpoint.handle(request(null)).getHttpStatus());
    assertEquals(400, endpoint.handle(request("  ")).getHttpStatus());
    NeoResponse unknown = endpoint.handle(request("sales-order"));
    assertEquals(404, unknown.getHttpStatus());
    assertTrue(!unknown.getBody().toString().contains("purchase-invoice"),
        "the answer does not list the supported types");
  }

  @Test
  @DisplayName("the answer carries no tenant data: nothing but the four configuration keys")
  void carriesOnlyTheConfiguration() throws Exception {
    JSONObject body = new NeoOcrConfigEndpoint().handle(request("purchase-invoice")).getBody();

    assertEquals(4, body.length());
  }

  @Test
  @DisplayName("a configuration that is not packaged is a 500, not the 404 of 'this type has no OCR'")
  void unavailableConfigurationIsA500() {
    OcrConfig broken = mock(OcrConfig.class);
    when(broken.isUnavailable()).thenReturn(true);
    try (org.mockito.MockedStatic<OcrConfig> config = org.mockito.Mockito.mockStatic(OcrConfig.class)) {
      config.when(OcrConfig::get).thenReturn(broken);

      NeoResponse response = new NeoOcrConfigEndpoint().handle(request("purchase-invoice"));

      assertEquals(500, response.getHttpStatus());
    }
  }
}
