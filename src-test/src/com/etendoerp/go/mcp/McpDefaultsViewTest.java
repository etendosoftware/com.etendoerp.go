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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link McpDefaultsView} — the pure re-shaper behind the {@code neo_defaults}
 * {@code view} argument (IMP-7). No DAL/model access, so these run without a live instance.
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpDefaultsView")
class McpDefaultsViewTest {

  private static Set<String> editable(String... names) {
    return new HashSet<>(java.util.Arrays.asList(names));
  }

  /** A compliance-heavy defaults body: two writable fields, a FK label, two server flags. */
  private static JSONObject sampleResponse() throws JSONException {
    JSONObject defaults = new JSONObject();
    defaults.put("invoiceDate", "21-07-2026");
    defaults.put("paymentTerms", "30");
    defaults.put("paymentTerms$_identifier", "30 Días");
    defaults.put("aeatsiiIssent", false);
    defaults.put("docbasetype", "ARI");

    JSONObject metadata = new JSONObject();
    metadata.put("unresolvedFields", new org.codehaus.jettison.json.JSONArray());

    JSONObject response = new JSONObject();
    response.put("defaults", defaults);
    response.put("metadata", metadata);
    return response;
  }

  @Nested
  @DisplayName("isGroupingView / baseProperty")
  class Predicates {

    @Test
    @DisplayName("only grouped and minimal (case-insensitive) count as grouping views")
    void isGroupingView() {
      assertTrue(McpDefaultsView.isGroupingView("grouped"));
      assertTrue(McpDefaultsView.isGroupingView("MINIMAL"));
      assertFalse(McpDefaultsView.isGroupingView("full"));
      assertFalse(McpDefaultsView.isGroupingView(null));
    }

    @Test
    @DisplayName("baseProperty strips the $_identifier companion suffix")
    void baseProperty() {
      assertEquals("paymentTerms", McpDefaultsView.baseProperty("paymentTerms$_identifier"));
      assertEquals("invoiceDate", McpDefaultsView.baseProperty("invoiceDate"));
    }
  }

  @Nested
  @DisplayName("apply")
  class Apply {

    @Test
    @DisplayName("a null / full view returns the original response untouched")
    void fullViewIsNoOp() throws JSONException {
      JSONObject response = sampleResponse();
      assertSame(response, McpDefaultsView.apply(response, editable("invoiceDate"), null));
      assertSame(response, McpDefaultsView.apply(response, editable("invoiceDate"), "full"));
    }

    @Test
    @DisplayName("grouped splits writable defaults from server-managed flags, keeping metadata")
    void grouped() throws JSONException {
      JSONObject out = McpDefaultsView.apply(sampleResponse(),
          editable("invoiceDate", "paymentTerms"), "grouped");

      JSONObject confirm = out.getJSONObject(McpDefaultsView.GROUP_CONFIRM);
      JSONObject systemManaged = out.getJSONObject(McpDefaultsView.GROUP_SYSTEM_MANAGED);

      // the FK label travels with its base property into confirm
      assertTrue(confirm.has("invoiceDate"));
      assertTrue(confirm.has("paymentTerms"));
      assertTrue(confirm.has("paymentTerms$_identifier"));
      assertEquals(3, confirm.length());

      // compliance flags land in systemManaged
      assertTrue(systemManaged.has("aeatsiiIssent"));
      assertTrue(systemManaged.has("docbasetype"));
      assertEquals(2, systemManaged.length());

      assertTrue(out.has(McpDefaultsView.KEY_METADATA));
    }

    @Test
    @DisplayName("minimal returns only the confirm block plus metadata (no systemManaged)")
    void minimal() throws JSONException {
      JSONObject out = McpDefaultsView.apply(sampleResponse(),
          editable("invoiceDate", "paymentTerms"), "minimal");

      assertTrue(out.has(McpDefaultsView.GROUP_CONFIRM));
      assertFalse(out.has(McpDefaultsView.GROUP_SYSTEM_MANAGED));
      assertTrue(out.has(McpDefaultsView.KEY_METADATA));
      assertEquals(3, out.getJSONObject(McpDefaultsView.GROUP_CONFIRM).length());
    }

    @Test
    @DisplayName("an empty editable set puts everything in systemManaged")
    void noEditableFields() throws JSONException {
      JSONObject out = McpDefaultsView.apply(sampleResponse(), editable(), "grouped");
      assertEquals(0, out.getJSONObject(McpDefaultsView.GROUP_CONFIRM).length());
      assertEquals(5, out.getJSONObject(McpDefaultsView.GROUP_SYSTEM_MANAGED).length());
    }

    @Test
    @DisplayName("a response with no defaults object is returned unchanged")
    void noDefaultsObject() throws JSONException {
      JSONObject response = new JSONObject();
      response.put("metadata", new JSONObject());
      assertSame(response, McpDefaultsView.apply(response, editable("x"), "grouped"));
    }
  }

  /**
   * IMP-7's second half: a writable field the server resolved to a blank is a field the agent must
   * still supply, and belongs in {@code metadata.unresolvedFields} rather than in {@code confirm}
   * with an empty value. The live case is {@code partnerAddress} on {@code sales-invoice/header}.
   */
  @Nested
  @DisplayName("blank confirm values")
  class BlankValues {

    private static JSONObject bodyWithBlanks() throws JSONException {
      JSONObject defaults = new JSONObject();
      defaults.put("invoiceDate", "21-07-2026");
      defaults.put("partnerAddress", "");
      defaults.put("salesRepresentative", "   ");
      defaults.put("description", JSONObject.NULL);
      defaults.put("aeatsiiIssent", "");  // blank but NOT editable — must stay put
      JSONObject response = new JSONObject();
      response.put("defaults", defaults);
      return response;
    }

    private static Set<String> unresolvedNames(JSONObject out) throws JSONException {
      JSONArray array = out.getJSONObject(McpDefaultsView.KEY_METADATA)
          .getJSONArray(McpDefaultsView.KEY_UNRESOLVED_FIELDS);
      Set<String> names = new HashSet<>();
      for (int i = 0; i < array.length(); i++) {
        names.add(array.getString(i));
      }
      return names;
    }

    @Test
    @DisplayName("empty, whitespace and JSON-null writable defaults are reported as unresolved")
    void blanksMoveToUnresolvedFields() throws JSONException {
      JSONObject out = McpDefaultsView.apply(bodyWithBlanks(),
          editable("invoiceDate", "partnerAddress", "salesRepresentative", "description"),
          "grouped");

      JSONObject confirm = out.getJSONObject(McpDefaultsView.GROUP_CONFIRM);
      assertTrue(confirm.has("invoiceDate"));
      assertEquals(1, confirm.length(), "only the field that actually resolved stays in confirm");

      assertEquals(editable("partnerAddress", "salesRepresentative", "description"),
          unresolvedNames(out));
    }

    @Test
    @DisplayName("a blank in systemManaged is left alone — it is not the agent's problem")
    void blankSystemManagedIsNotReported() throws JSONException {
      JSONObject out = McpDefaultsView.apply(bodyWithBlanks(), editable("invoiceDate"), "grouped");

      assertTrue(out.getJSONObject(McpDefaultsView.GROUP_SYSTEM_MANAGED).has("aeatsiiIssent"));
      assertFalse(out.has(McpDefaultsView.KEY_METADATA),
          "no writable field was blank, so no metadata is invented");
    }

    @Test
    @DisplayName("a blank FK reports its base property once, not the $_identifier companion")
    void blankFkReportsBaseProperty() throws JSONException {
      JSONObject defaults = new JSONObject();
      defaults.put("partnerAddress", "");
      defaults.put("partnerAddress$_identifier", "");
      JSONObject response = new JSONObject();
      response.put("defaults", defaults);

      JSONObject out = McpDefaultsView.apply(response, editable("partnerAddress"), "minimal");

      assertEquals(0, out.getJSONObject(McpDefaultsView.GROUP_CONFIRM).length());
      assertEquals(editable("partnerAddress"), unresolvedNames(out));
    }

    @Test
    @DisplayName("existing metadata is preserved and unresolvedFields de-duplicated, not replaced")
    void mergesWithExistingMetadata() throws JSONException {
      JSONObject response = bodyWithBlanks();
      JSONArray existing = new JSONArray();
      existing.put("partnerAddress");   // already reported by NeoDefaultsService
      existing.put("documentNo");
      JSONObject metadata = new JSONObject();
      metadata.put(McpDefaultsView.KEY_UNRESOLVED_FIELDS, existing);
      metadata.put("sequenceFields", new JSONArray());
      response.put("metadata", metadata);

      JSONObject out = McpDefaultsView.apply(response,
          editable("partnerAddress", "description"), "grouped");

      assertEquals(editable("partnerAddress", "documentNo", "description"), unresolvedNames(out));
      assertTrue(out.getJSONObject(McpDefaultsView.KEY_METADATA).has("sequenceFields"),
          "sibling metadata keys survive the merge");
      // apply() must not mutate what it was handed
      assertEquals(2, existing.length());
    }

    @Test
    @DisplayName("isUnresolvedValue treats blank, whitespace and JSON null as unresolved")
    void predicate() {
      assertTrue(McpDefaultsView.isUnresolvedValue(null));
      assertTrue(McpDefaultsView.isUnresolvedValue(JSONObject.NULL));
      assertTrue(McpDefaultsView.isUnresolvedValue(""));
      assertTrue(McpDefaultsView.isUnresolvedValue("  \t "));
      assertFalse(McpDefaultsView.isUnresolvedValue("0"));
      assertFalse(McpDefaultsView.isUnresolvedValue(Boolean.FALSE));
      assertFalse(McpDefaultsView.isUnresolvedValue(0));
    }
  }
}
