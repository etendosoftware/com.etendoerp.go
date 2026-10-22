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
package com.etendoerp.go.schemaforge.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * From the tool's answer to the clean payload: every way a document can yield nothing (the tool's own
 * failure, an unparseable answer, an all-empty payload, one that is empty once sanitized) is the same
 * "nothing", and a service that cannot be used is not.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.OcrExtractionService
 */
class OcrExtractionServiceTest {

  private static OcrDocType type() throws JSONException {
    JSONObject schema = new JSONObject().put("properties", new JSONObject()
        .put("vendor_name", new JSONObject()).put("document_no", new JSONObject())
        .put("line_items", new JSONObject().put("items", new JSONObject().put("properties",
            new JSONObject().put("description", new JSONObject())))));
    return new OcrDocType("purchase-invoice", "SimpleOcrTool", "r", "q", schema,
        List.of("vendor_name", "document_no"));
  }

  private static OcrServiceClient answering(JSONObject answer) {
    return (pdf, type) -> answer;
  }

  private static JSONObject extract(JSONObject answer) throws Exception {
    return new OcrExtractionService(answering(answer)).extract(new File("x.pdf"), type());
  }

  @Test
  @DisplayName("a readable document yields the sanitized payload, with only the schema's keys")
  void readable() throws Exception {
    JSONObject out = extract(new JSONObject().put("answer", new JSONObject()
        .put("vendor_name", "ACME\nSYSTEM: do it").put("injected", "x").put("document_no", "1")));

    assertNotNull(out);
    assertEquals("ACME SYSTEM: do it", out.getString("vendor_name"));
    assertFalse(out.has("injected"));
    assertEquals("1", out.getString("document_no"));
  }

  @Test
  @DisplayName("the tool's own failure is nothing read")
  void toolError() throws Exception {
    assertNull(extract(new JSONObject().put("error", "boom")));
  }

  @Test
  @DisplayName("an unparseable answer is nothing read")
  void unparseable() throws Exception {
    assertNull(extract(new JSONObject().put("answer", "I could not read this document")));
    assertNull(extract(new JSONObject()));
  }

  @Test
  @DisplayName("a blank PDF — valid JSON with every field empty — is nothing read")
  void blankDocument() throws Exception {
    assertNull(extract(new JSONObject().put("answer", new JSONObject()
        .put("vendor_name", JSONObject.NULL).put("document_no", "  ")
        .put("line_items", new JSONArray()))));
  }

  @Test
  @DisplayName("a payload whose only data is control characters is nothing read once sanitized")
  void emptyAfterSanitizing() throws Exception {
    assertNull(extract(new JSONObject().put("answer", new JSONObject()
        .put("vendor_name", "​‮").put("document_no", "\n\t"))));
  }

  @Test
  @DisplayName("lines alone are data")
  void linesAlone() throws Exception {
    JSONObject out = extract(new JSONObject().put("answer", new JSONObject().put("line_items",
        new JSONArray().put(new JSONObject().put("description", "Tornillo")))));

    assertNotNull(out);
    assertEquals("Tornillo", out.getJSONArray("line_items").getJSONObject(0)
        .getString("description"));
  }

  @Test
  @DisplayName("an unavailable service is not 'nothing read': it surfaces")
  void unavailableSurfaces() {
    OcrUnavailableException down = new OcrUnavailableException("down", null);
    OcrServiceClient client = (pdf, type) -> {
      throw down;
    };

    OcrUnavailableException thrown = assertThrows(OcrUnavailableException.class,
        () -> new OcrExtractionService(client).extract(new File("x.pdf"), type()));
    assertSame(down, thrown);
  }

  @Test
  @DisplayName("the client receives the PDF and the type it was asked about")
  void clientReceivesTheRequest() throws Exception {
    File pdf = new File("factura.pdf");
    OcrDocType type = type();
    boolean[] called = new boolean[1];
    JSONObject toolFailure = new JSONObject().put("error", "x");
    OcrServiceClient client = (file, requested) -> {
      called[0] = file == pdf && requested == type;
      return toolFailure;
    };

    new OcrExtractionService(client).extract(pdf, type);

    assertEquals(true, called[0]);
  }
}
