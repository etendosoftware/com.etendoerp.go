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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The OCR configuration both the MCP tools and the application's upload panel read: what it declares,
 * that the schema is the strict one the model needs, and that a reader cannot change it for the next.
 *
 * @covers com.etendoerp.go.schemaforge.ocr.OcrConfig
 * @covers com.etendoerp.go.schemaforge.ocr.OcrDocType
 */
class OcrConfigTest {

  private static OcrDocType purchaseInvoice() {
    return OcrConfig.get().forSpec("purchase-invoice").orElseThrow(
        () -> new AssertionError("purchase-invoice is not in the packaged OCR configuration"));
  }

  @Test
  @DisplayName("the packaged configuration declares purchase-invoice and nothing else supports OCR")
  void declaresPurchaseInvoice() {
    assertEquals(List.of("purchase-invoice"), OcrConfig.get().specs());
    assertTrue(OcrConfig.get().forSpec("sales-invoice").isEmpty());
    assertTrue(OcrConfig.get().forSpec(null).isEmpty());
    assertEquals("SimpleOcrTool", purchaseInvoice().getToolName());
    assertEquals("purchase-invoice", purchaseInvoice().getResolver());
  }

  @Test
  @DisplayName("the question is the one the application has always asked")
  void question() {
    assertTrue(purchaseInvoice().getQuestion().startsWith("Extract all invoice fields: vendor name,"));
    assertTrue(purchaseInvoice().getQuestion().endsWith("Return strict JSON."));
  }

  @Test
  @DisplayName("the schema is strict: every object closed, every property required, nullable values")
  void strictSchema() throws JSONException {
    assertStrict(purchaseInvoice().getSchema());
    JSONObject properties = purchaseInvoice().getSchema().getJSONObject("properties");
    for (String key : List.of("vendor_name", "tax_id", "document_no", "invoice_date", "issuer",
        "receiver", "vendor_address", "vendor_postal_code", "vendor_city", "vendor_country",
        "vendor_email", "vendor_phone", "line_items")) {
      assertTrue(properties.has(key), "schema property " + key);
    }
    JSONObject line = properties.getJSONObject("line_items").getJSONObject("items");
    for (String key : List.of("description", "quantity", "unit_price", "tax_label", "tax_rate")) {
      assertTrue(line.getJSONObject("properties").has(key), "line property " + key);
    }
    assertFalse(properties.has("total"), "no totals are read");
  }

  private static void assertStrict(JSONObject schema) throws JSONException {
    if (!schema.has("properties")) {
      return;
    }
    assertEquals(false, schema.getBoolean("additionalProperties"));
    JSONObject properties = schema.getJSONObject("properties");
    JSONArray required = schema.getJSONArray("required");
    assertEquals(properties.length(), required.length());
    Iterator<?> keys = properties.keys();
    while (keys.hasNext()) {
      String key = (String) keys.next();
      boolean listed = false;
      for (int i = 0; i < required.length(); i++) {
        listed = listed || key.equals(required.getString(i));
      }
      assertTrue(listed, key + " must be required");
      JSONObject child = properties.getJSONObject(key);
      if (child.has("items")) {
        assertStrict(child.getJSONObject("items"));
      } else {
        assertStrict(child);
      }
    }
  }

  @Test
  @DisplayName("the data keys are properties of the schema, and the receiver block carries its tax id")
  void dataKeys() throws JSONException {
    JSONObject properties = purchaseInvoice().getSchema().getJSONObject("properties");
    assertEquals(List.of("vendor_name", "tax_id", "document_no", "invoice_date"),
        purchaseInvoice().getDataKeys());
    for (String key : purchaseInvoice().getDataKeys()) {
      assertTrue(properties.has(key), key);
    }
    assertTrue(properties.getJSONObject("receiver").getJSONObject("properties")
        .has("tax_id_raw"));
    assertTrue(properties.getJSONObject("issuer").getJSONObject("properties").has("tax_id_raw"));
  }

  @Test
  @DisplayName("the client configuration carries docType, tool, question and schema")
  void clientJson() throws JSONException {
    JSONObject json = purchaseInvoice().toClientJson();
    assertEquals("purchase-invoice", json.getString("docType"));
    assertEquals("SimpleOcrTool", json.getString("toolName"));
    assertEquals(purchaseInvoice().getQuestion(), json.getString("question"));
    assertEquals(purchaseInvoice().getSchema().toString(), json.getJSONObject("schema").toString());
  }

  @Test
  @DisplayName("a caller that changes the schema it got does not change it for the next caller")
  void schemaIsACopy() throws JSONException {
    purchaseInvoice().getSchema().put("properties", "tampered");
    assertTrue(purchaseInvoice().getSchema().get("properties") instanceof JSONObject);
  }

  @Test
  @DisplayName("a configuration text that is not the expected JSON is refused")
  void malformed() {
    assertThrows(JSONException.class, () -> OcrConfig.parse("{}"));
    assertThrows(JSONException.class, () -> OcrConfig.parse("not json"));
  }

  @Test
  @DisplayName("any number of document types can be declared and looked up by spec")
  void parsesSeveral() throws JSONException {
    String entry = "{\"id\":\"%s\",\"toolName\":\"T\",\"resolver\":\"r\",\"question\":\"q\","
        + "\"dataKeys\":[\"a\"],\"schema\":{\"type\":\"object\"}}";
    OcrConfig config = OcrConfig.parse("{\"docTypes\":[" + String.format(entry, "one") + ","
        + String.format(entry, "two") + "]}");
    assertEquals(List.of("one", "two"), config.specs());
    assertEquals("T", config.forSpec("two").orElseThrow().getToolName());
  }

  @Test
  @DisplayName("a configuration that is not on the classpath is a loud failure, not an empty config")
  void missingResourceIsLoud() {
    IllegalStateException e = assertThrows(IllegalStateException.class,
        () -> OcrConfig.load(OcrConfig.class, "no-such-config.json"));

    assertTrue(e.getMessage().contains("no-such-config.json"));
  }

  @Test
  @DisplayName("the packaged configuration is found on the classpath, so the deployment is not broken")
  void packagedConfigurationIsAvailable() {
    assertFalse(OcrConfig.get().isUnavailable());
    assertFalse(OcrConfig.get().specs().isEmpty());
  }

  /**
   * The keys the application's review reads from the extraction (extractFrom, createPrefilledFrom,
   * the line columns, the receiver check). The SPA's own vitest checks the same against the
   * file, but is skipped where the module is not checked out beside the SPA; this runs in the
   * module's CI, so a key removed here cannot ship unnoticed.
   */
  @Test
  @DisplayName("the schema emits every key the SPA reads")
  void schemaEmitsEveryKeyTheSpaReads() throws JSONException {
    JSONObject properties = purchaseInvoice().getSchema().getJSONObject("properties");
    for (String key : List.of("vendor_name", "tax_id", "document_no", "invoice_date",
        "vendor_address", "vendor_postal_code", "vendor_city", "vendor_country", "vendor_email",
        "vendor_phone", "issuer", "receiver", "line_items")) {
      assertTrue(properties.has(key), "header key " + key);
    }
    for (String party : List.of("issuer", "receiver")) {
      JSONObject block = properties.getJSONObject(party).getJSONObject("properties");
      for (String key : List.of("name", "tax_id_raw", "tax_id_label", "tax_id_type")) {
        assertTrue(block.has(key), party + "." + key);
      }
    }
    JSONObject line = properties.getJSONObject("line_items").getJSONObject("items")
        .getJSONObject("properties");
    for (String key : List.of("description", "quantity", "unit_price", "tax_label", "tax_rate")) {
      assertTrue(line.has(key), "line key " + key);
    }
  }

  @Test
  @DisplayName("the numeric line fields are typed as numbers, which the sanitizer relies on")
  void numericFieldsAreTypedAsNumbers() throws JSONException {
    JSONObject line = purchaseInvoice().getSchema().getJSONObject("properties")
        .getJSONObject("line_items").getJSONObject("items").getJSONObject("properties");
    for (String key : List.of("quantity", "unit_price", "tax_rate")) {
      assertTrue(line.getJSONObject(key).getJSONArray("type").toString().contains("number"), key);
    }
  }
}
