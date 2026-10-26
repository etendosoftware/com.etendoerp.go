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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the extraction tool's answer: fenced or prose-wrapped JSON, the tool's own error answer, and
 * the "is there anything usable" rule of the application's {@code hasExtractedData}.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.OcrExtractionParser
 */
class OcrExtractionParserTest {

  private static OcrDocType type() throws JSONException {
    return new OcrDocType("purchase-invoice", "SimpleOcrTool", "purchase-invoice", "q",
        new JSONObject(), List.of("vendor_name", "tax_id", "document_no", "invoice_date"));
  }

  private static String vendorOf(JSONObject response) throws JSONException {
    JSONObject payload = OcrExtractionParser.payloadOf(response);
    assertNotNull(payload, "the answer must yield a payload: " + response);
    return payload.getString("vendor_name");
  }

  @Test
  @DisplayName("an answer that is already an object is the payload")
  void objectAnswer() throws JSONException {
    assertEquals("ACME", vendorOf(new JSONObject()
        .put("answer", new JSONObject().put("vendor_name", "ACME"))));
  }

  @Test
  @DisplayName("an answer that is JSON text is parsed, fenced or not")
  void textAnswer() throws JSONException {
    assertEquals("ACME", vendorOf(new JSONObject()
        .put("answer", "{\"vendor_name\":\"ACME\"}")));
    assertEquals("ACME", vendorOf(new JSONObject()
        .put("answer", "```json\n{\"vendor_name\":\"ACME\"}\n```")));
    assertEquals("ACME", vendorOf(new JSONObject()
        .put("answer", "```{\"vendor_name\":\"ACME\"}```")));
  }

  @Test
  @DisplayName("JSON wrapped in prose is found between the first and the last brace")
  void proseWrapped() throws JSONException {
    assertEquals("ACME", vendorOf(new JSONObject()
        .put("answer", "Here you go: {\"vendor_name\":\"ACME\"} hope it helps")));
  }

  @Test
  @DisplayName("an unparseable, blank or empty answer yields no payload")
  void unparseable() throws JSONException {
    assertNull(OcrExtractionParser.payloadOf(new JSONObject().put("answer", "no json here")));
    assertNull(OcrExtractionParser.payloadOf(new JSONObject().put("answer", "")));
    assertNull(OcrExtractionParser.payloadOf(new JSONObject()));
    assertNull(OcrExtractionParser.payloadOf(null));
    assertNull(OcrExtractionParser.payloadOf(new JSONObject().put("answer", "{broken")));
  }

  @Test
  @DisplayName("falls back to answer.response, response, message and raw like the application")
  void fallbacks() throws JSONException {
    assertEquals("A", vendorOf(new JSONObject().put("answer",
        new JSONObject().put("response", "{\"vendor_name\":\"A\"}"))));
    assertEquals("B", vendorOf(new JSONObject()
        .put("response", "{\"vendor_name\":\"B\"}")));
    assertEquals("C", vendorOf(new JSONObject()
        .put("message", "{\"vendor_name\":\"C\"}")));
    assertEquals("D", vendorOf(new JSONObject()
        .put("raw", "{\"vendor_name\":\"D\"}")));
  }

  @Test
  @DisplayName("JSON nested deeper than the parser's limit is refused, not parsed")
  void deepNestingRefused() throws JSONException {
    StringBuilder deep = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      deep.append("{\"a\":");
    }
    deep.append("1");
    for (int i = 0; i < 200; i++) {
      deep.append('}');
    }
    assertNull(OcrExtractionParser.payloadOf(new JSONObject().put("answer", deep.toString())));
  }

  @Test
  @DisplayName("the tool's own failure is an error with no answer")
  void toolError() throws JSONException {
    assertTrue(OcrExtractionParser.isToolError(new JSONObject().put("error", "boom")));
    assertFalse(OcrExtractionParser.isToolError(new JSONObject().put("error", "x")
        .put("answer", "{}")));
    assertFalse(OcrExtractionParser.isToolError(new JSONObject().put("answer", "{}")));
    assertFalse(OcrExtractionParser.isToolError(null));
  }

  @Test
  @DisplayName("an all-null or blank payload has no data")
  void noData() throws JSONException {
    JSONObject empty = new JSONObject().put("vendor_name", JSONObject.NULL).put("tax_id", "  ")
        .put("document_no", "").put("invoice_date", JSONObject.NULL)
        .put("line_items", new org.codehaus.jettison.json.JSONArray());
    assertFalse(OcrExtractionParser.hasData(type(), empty));
    assertFalse(OcrExtractionParser.hasData(type(), null));
  }

  @Test
  @DisplayName("any data key filled, or at least one line, is data; other keys alone are not")
  void data() throws JSONException {
    assertTrue(OcrExtractionParser.hasData(type(), new JSONObject().put("document_no", "2024/1")));
    assertTrue(OcrExtractionParser.hasData(type(), new JSONObject().put("line_items",
        new org.codehaus.jettison.json.JSONArray().put(new JSONObject()))));
    assertTrue(OcrExtractionParser.hasData(type(), new JSONObject().put("tax_id", 5)));
    assertFalse(OcrExtractionParser.hasData(type(), new JSONObject().put("vendor_address", "C/ Mayor 1")));
  }
}
