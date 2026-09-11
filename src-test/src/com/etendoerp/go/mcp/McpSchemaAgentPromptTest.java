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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers the entity-level {@code agentPrompt} ({@code ETGO_SF_ENTITY.AGENT_PROMPT}) that ETP-5184
 * added to {@code neo_schema}, in both shapes it is emitted in: the full dump and
 * {@code view:"create"}.
 *
 * <p>This is the half of the change with no live evidence behind it. The field-level prompt was
 * verified against a running instance; the entity-level column is populated on 2 entities in the
 * whole database and on none of {@code contacts}, so nothing outside these tests has ever
 * exercised the path. Two properties matter and are asserted for every shape: the prompt reaches
 * the response when it is there, and the key is <b>absent</b> — not empty, not null — when the
 * column is blank, so the response stays byte-for-byte as before for the 285 entities that carry
 * no prompt.</p>
 *
 * <p>Key ordering is deliberately not asserted. The intent recorded in the source is that the
 * prompt sits alongside {@code spec}/{@code entity}/{@code table} so an agent reads it before the
 * field list, but Jettison's {@code JSONObject} is backed by a hash map, so emission order is not a
 * property this class can pin.</p>
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("neo_schema entity-level agentPrompt (ETP-5184)")
class McpSchemaAgentPromptTest {

  private static final String KEY = "agentPrompt";
  private static final String PROMPT =
      "This entity is handler-backed: locationAddress is created server-side and any id sent for "
          + "it is discarded.";

  /**
   * One suppliable field and one that is not, so the create view has something to group and a
   * missing prompt cannot be confused with an empty response.
   */
  private static JSONArray fields() throws JSONException {
    JSONArray fields = new JSONArray();
    JSONObject required = new JSONObject();
    required.put("name", "businessPartner");
    required.put("type", "string");
    required.put(McpSchemaFieldBuilder.KEY_VISIBILITY,
        McpSchemaFieldBuilder.VISIBILITY_EDITABLE);
    required.put(McpSchemaFieldBuilder.KEY_READ_ONLY, false);
    required.put(McpSchemaFieldBuilder.KEY_USER_REQUIRED, true);
    fields.put(required);

    JSONObject systemField = new JSONObject();
    systemField.put("name", "createdBy");
    systemField.put("type", "string");
    systemField.put(McpSchemaFieldBuilder.KEY_VISIBILITY, McpSchemaFieldBuilder.VISIBILITY_SYSTEM);
    systemField.put(McpSchemaFieldBuilder.KEY_READ_ONLY, false);
    systemField.put(McpSchemaFieldBuilder.KEY_USER_REQUIRED, false);
    fields.put(systemField);
    return fields;
  }

  /**
   * Assert two responses carry the same keys with the same rendered values.
   *
   * <p>Key by key rather than {@code toString()}: Jettison's {@code JSONObject} is hash-backed, so
   * emission order is not part of the contract and comparing serialized forms would make this
   * assertion depend on it.</p>
   */
  private static void assertSameResponse(JSONObject expected, JSONObject actual, String message)
      throws JSONException {
    assertEquals(expected.length(), actual.length(), message);
    java.util.Iterator<?> keys = expected.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      assertTrue(actual.has(key), message + " — missing key " + key);
      assertEquals(String.valueOf(expected.get(key)), String.valueOf(actual.get(key)),
          message + " — key " + key);
    }
  }

  @Nested
  @DisplayName("view:\"create\" — McpSchemaCreateView.buildResponse")
  class CreateView {

    @Test
    @DisplayName("a prompt is emitted under agentPrompt")
    void promptIsEmitted() throws JSONException {
      JSONObject response = McpSchemaCreateView.buildResponse("contacts", "locationAddress",
          fields(), Set.of(), false, PROMPT);
      assertTrue(response.has(KEY));
      assertEquals(PROMPT, response.getString(KEY));
    }

    @Test
    @DisplayName("the prompt is trimmed, exactly as McpSupportInternals does for discover")
    void promptIsTrimmed() throws JSONException {
      JSONObject response = McpSchemaCreateView.buildResponse("contacts", "locationAddress",
          fields(), Set.of(), false, "  " + PROMPT + "\n");
      assertEquals(PROMPT, response.getString(KEY));
    }

    @Test
    @DisplayName("a null prompt omits the key — not an empty string, not JSON null")
    void nullPromptOmitsTheKey() throws JSONException {
      JSONObject response = McpSchemaCreateView.buildResponse("contacts", "locationAddress",
          fields(), Set.of(), false, null);
      assertFalse(response.has(KEY),
          "285 entities carry no prompt; their response must stay byte-for-byte as before");
    }

    @Test
    @DisplayName("a blank prompt omits the key too — a whitespace column is not guidance")
    void blankPromptOmitsTheKey() throws JSONException {
      for (String blank : List.of("", "   ", "\n\t")) {
        JSONObject response = McpSchemaCreateView.buildResponse("contacts", "locationAddress",
            fields(), Set.of(), false, blank);
        assertFalse(response.has(KEY), "a prompt of [" + blank + "] must not emit the key");
      }
    }

    @Test
    @DisplayName("the prompt does not disturb the grouping, the counts or the hint")
    void promptDoesNotDisturbTheRestOfTheResponse() throws JSONException {
      JSONObject without = McpSchemaCreateView.buildResponse("contacts", "locationAddress",
          fields(), Set.of(), false, null);
      JSONObject with = McpSchemaCreateView.buildResponse("contacts", "locationAddress",
          fields(), Set.of(), false, PROMPT);

      assertEquals(without.getInt("requiredCount"), with.getInt("requiredCount"));
      assertEquals(without.getInt("optionalCount"), with.getInt("optionalCount"));
      assertEquals(without.getString("hint"), with.getString("hint"));
      assertEquals(without.getString("spec"), with.getString("spec"));
      assertEquals(without.getString("entity"), with.getString("entity"));
      assertEquals(without.getJSONArray("required").toString(),
          with.getJSONArray("required").toString());
      // One extra key and one only: agentPrompt.
      assertEquals(without.length() + 1, with.length());
    }

    @Test
    @DisplayName("the prompt is an entity-level key, never copied onto a field descriptor")
    void promptStaysOnTheEntity() throws JSONException {
      JSONObject response = McpSchemaCreateView.buildResponse("contacts", "locationAddress",
          fields(), Set.of(), false, PROMPT);
      JSONArray required = response.getJSONArray("required");
      for (int i = 0; i < required.length(); i++) {
        assertFalse(required.getJSONObject(i).has(KEY),
            "the entity prompt must not be mistaken for the field-level one");
      }
    }

    @Test
    @DisplayName("the prompt survives the child-entity hint suffix")
    void promptCoexistsWithTheChildHint() throws JSONException {
      JSONObject response = McpSchemaCreateView.buildResponse("physical-inventory", "lines",
          fields(), Set.of(), true, PROMPT);
      assertEquals(PROMPT, response.getString(KEY));
      assertTrue(response.getString("hint").contains("parentId"),
          "the two are independent — one must not swallow the other");
    }

    @Test
    @DisplayName("the 5-arg overload still behaves as before: no prompt, hint unchanged")
    void fiveArgOverloadDelegatesWithNull() throws JSONException {
      JSONObject five = McpSchemaCreateView.buildResponse("contacts", "locationAddress",
          fields(), Set.of(), false);
      JSONObject six = McpSchemaCreateView.buildResponse("contacts", "locationAddress",
          fields(), Set.of(), false, null);
      assertFalse(five.has(KEY));
      assertSameResponse(six, five,
          "the 5-arg overload must be exactly the 6-arg one with a null prompt");
    }

    @Test
    @DisplayName("the 4-arg overload still behaves as before")
    void fourArgOverloadDelegatesWithNull() throws JSONException {
      JSONObject four =
          McpSchemaCreateView.buildResponse("contacts", "locationAddress", fields(), Set.of());
      JSONObject six = McpSchemaCreateView.buildResponse("contacts", "locationAddress",
          fields(), Set.of(), false, null);
      assertFalse(four.has(KEY));
      assertSameResponse(six, four, "the 4-arg overload must delegate with a null prompt");
    }
  }

  /**
   * The full-dump shape lives in {@code McpToolRouter.handleSchema}, which is private and needs an
   * {@code OBContext}, a live DAL, an {@code AD_Tab} and a {@code ModelProvider} entity, so it
   * cannot be invoked from a unit test. What can be pinned is that the emission is still wired —
   * the same class of guard as {@code McpWriteVerbCoercionCallSiteTest}, and the same failure mode:
   * a refactor that drops the {@code put} would leave every other test passing while the prompt
   * silently stopped reaching the response the agent reads immediately before writing.
   */
  @Nested
  @DisplayName("full dump — McpToolRouter.handleSchema")
  class FullDump {

    private static final String ROUTER = "com/etendoerp/go/mcp/McpToolRouter.java";

    @Test
    @DisplayName("handleSchema resolves the entity prompt and emits it in both shapes")
    void handleSchemaEmitsTheEntityPrompt() {
      String body = McpSourceScanner.methodBody(McpSourceScanner.read(ROUTER), "handleSchema");
      List<String> missing = new ArrayList<>();

      // Read off ETGO_SF_ENTITY, blank-checked the same way discover does, so an empty column
      // emits no key at all.
      if (!Pattern.compile("trimToNull\\s*\\(\\s*sfEntity\\s*\\.\\s*getAgentPrompt\\s*\\(\\s*\\)")
          .matcher(body).find()) {
        missing.add("StringUtils.trimToNull(sfEntity.getAgentPrompt())");
      }
      // The full dump, alongside spec/entity/table.
      if (!Pattern.compile("entitySchema\\s*\\.\\s*put\\s*\\(\\s*\"agentPrompt\"")
          .matcher(body).find()) {
        missing.add("entitySchema.put(\"agentPrompt\", …) in the full dump");
      }
      // view:"create", through the 6-arg overload.
      if (!Pattern.compile("buildResponse\\s*\\([^;]*entityAgentPrompt", Pattern.DOTALL)
          .matcher(body).find()) {
        missing.add("the entity prompt passed to McpSchemaCreateView.buildResponse");
      }
      if (!missing.isEmpty()) {
        fail("neo_schema no longer emits the entity-level agentPrompt: " + missing
            + ". Both shapes must carry it — view:\"create\" is what an agent reads immediately"
            + " before writing, and for a handler-backed entity the prompt is the only place the"
            + " advertised contract can be contradicted (ETP-5184).");
      }
    }

    @Test
    @DisplayName("the emission is guarded on a non-blank prompt, so a blank column adds no key")
    void theEmissionIsGuarded() {
      String body = McpSourceScanner.methodBody(McpSourceScanner.read(ROUTER), "handleSchema");
      assertTrue(Pattern.compile("if\\s*\\(\\s*entityAgentPrompt\\s*!=\\s*null\\s*\\)")
              .matcher(body).find(),
          "an unconditional put would emit agentPrompt:null for the 285 entities with no prompt,"
              + " changing every one of their responses");
    }
  }
}
