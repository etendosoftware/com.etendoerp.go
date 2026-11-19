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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * New unit: no existing test covers the stored failure identifier of the invoice inbox (ETP-6051).
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxFailureDetail
 */
class InvoiceInboxFailureDetailTest {

  @Test
  @DisplayName("build and parse round trip keeps the code, the params and the English text")
  void roundTrip() {
    InvoiceInboxFailureDetail.Detail built = InvoiceInboxFailureDetail.of(
        InvoiceInboxFailureDetail.STEP_FAILED, "step", "line", "n", 2, "reason",
        "Required fields are missing", "missing", List.of("unitPrice", "tax"));

    Optional<InvoiceInboxFailureDetail.Detail> parsed =
        InvoiceInboxFailureDetail.parse(built.stored());

    assertTrue(parsed.isPresent());
    assertEquals("step_failed", parsed.get().code());
    assertEquals("line", parsed.get().params().get("step"));
    assertEquals(2, parsed.get().params().get("n"));
    assertEquals("Required fields are missing", parsed.get().params().get("reason"));
    assertEquals(List.of("unitPrice", "tax"), parsed.get().params().get("missing"));
    assertEquals("line 2: Required fields are missing (unitPrice, tax)", parsed.get().text());
    assertEquals(built.text(), parsed.get().text());
  }

  @Test
  @DisplayName("the stored value is compact JSON with version, code, params and text")
  void storedShape() throws Exception {
    JSONObject stored = new JSONObject(InvoiceInboxFailureDetail.of(
        InvoiceInboxFailureDetail.NO_FILE).stored());

    assertEquals(1, stored.getInt("v"));
    assertEquals("no_file", stored.getString("code"));
    assertEquals(0, stored.getJSONObject("params").length());
    assertTrue(stored.getString("text").contains("no longer available"));
  }

  @Test
  @DisplayName("every code has an English text and a null param is left out")
  void everyCodeHasAText() {
    String[] codes = { "no_lookups", "completion_error", "no_vendor_category",
        "no_product_sequence", "no_file", "attach_failed", "currency_mismatch", "total_mismatch",
        "step_failed", "search_key_exists", "refused" };
    for (String code : codes) {
      assertFalse(InvoiceInboxFailureDetail.of(code, "message", "m", "reason", "r", "step",
          "vendor").text().isBlank(), code);
    }
    assertEquals("The period is closed", InvoiceInboxFailureDetail.ofRefusal("The period is closed")
        .text());
    assertFalse(InvoiceInboxFailureDetail.of("step_failed", "name", null).params()
        .containsKey("name"));
    assertEquals("new product 'Cable': the search key already exists",
        InvoiceInboxFailureDetail.of(InvoiceInboxFailureDetail.SEARCH_KEY_EXISTS, "step",
            "product", "name", "Cable").text());
    assertEquals("an unknown reason", InvoiceInboxFailureDetail.of("brand_new", "reason",
        "an unknown reason").text());
    assertEquals("brand_new", InvoiceInboxFailureDetail.of("brand_new").text());
  }

  @Test
  @DisplayName("an oversized value is shortened in its text and params and stays parseable JSON of at most 2000 characters")
  void truncationKeepsTheJsonValid() throws Exception {
    String huge = "é\"\\ \n x".repeat(1000);
    String[] stored = {
        InvoiceInboxFailureDetail.ofRefusal(huge).stored(),
        InvoiceInboxFailureDetail.of(InvoiceInboxFailureDetail.STEP_FAILED, "step", "product",
            "name", huge, "reason", huge, "missing", List.of("a", "b")).stored(),
        InvoiceInboxFailureDetail.of(InvoiceInboxFailureDetail.STEP_FAILED, "step", "line", "n",
            1, "reason", "r", "missing", java.util.Collections.nCopies(500, "field")).stored() };

    for (String value : stored) {
      assertTrue(value.length() <= InvoiceInboxFailureDetail.MAX_STORED, "length " + value.length());
      JSONObject parsed = new JSONObject(value);
      assertEquals(1, parsed.getInt("v"));
      assertTrue(InvoiceInboxFailureDetail.parse(value).isPresent());
    }
    JSONObject first = new JSONObject(stored[0]);
    assertEquals("refused", first.getString("code"));
    assertTrue(first.getString("text").endsWith("..."));
    assertTrue(first.getJSONObject("params").getString("message").length() > 100);
  }

  @Test
  @DisplayName("a value that fits is stored untouched")
  void aShortValueIsNotTouched() {
    InvoiceInboxFailureDetail.Detail detail = InvoiceInboxFailureDetail.ofRefusal("short");

    assertEquals("{\"v\":1,\"code\":\"refused\",\"params\":{\"message\":\"short\"},"
        + "\"text\":\"short\"}", detail.stored());
  }

  @Test
  @DisplayName("legacy free text, blank, null, malformed JSON and JSON that is not ours do not parse")
  void notOurs() {
    for (String value : new String[] { null, "", "   ", "line 2: tax is required",
        "The period is closed {x}", "{not json", "{\"foo\":1}", "{\"code\":\"x\"}", "{\"v\":1}",
        "{\"v\":1,\"code\":\"\"}", "[1,2]", "\"{\"" }) {
      assertTrue(InvoiceInboxFailureDetail.parse(value).isEmpty(), String.valueOf(value));
    }
  }

  @Test
  @DisplayName("a value with surrounding blanks and without params or text still parses")
  void lenientEnvelope() {
    Optional<InvoiceInboxFailureDetail.Detail> parsed = InvoiceInboxFailureDetail.parse(
        "  {\"v\":1,\"code\":\"no_file\"}  ");

    assertTrue(parsed.isPresent());
    assertEquals("no_file", parsed.get().code());
    assertTrue(parsed.get().params().isEmpty());
    assertTrue(parsed.get().text().contains("no longer available"));
  }
}
