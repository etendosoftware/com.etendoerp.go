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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers the refusals ETP-5184 added: the three former silent filter drops, and the child-entity
 * parent requirement.
 *
 * <p>What matters in each case is not that a 422 comes back but that the envelope carries the
 * correction. The bug these replace was never "the query failed" — it was a 200 with the whole
 * table on it, which the caller had no way to recognize as wrong. A refusal that says only "bad
 * request" would fix the false success and leave the caller just as stuck, so every assertion here
 * is about the self-correcting half: {@code available}, {@code parentField}, {@code parentEntity},
 * and a hint that names the next call.</p>
 *
 * <p>Also pins the WARN line the router logs for each refusal (ETP-5639): it is keyed on the
 * refusal's own code, where it used to say "addressed something that does not exist" for all.</p>
 *
 * @covers com.etendoerp.go.mcp.McpRoutingException
 * @covers com.etendoerp.go.mcp.McpToolRouter
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpRoutingException — ETP-5184 refusals")
class McpRoutingExceptionRefusalTest {

  static Stream<Arguments> refusals() throws JSONException {
    return Stream.of(
        Arguments.of(McpRoutingException.specNotFound("nope"), McpConstants.ERROR_NOT_FOUND),
        Arguments.of(McpRoutingException.entityNotFound("nope", "sales-order", List.of("lines")),
            McpConstants.ERROR_NOT_FOUND),
        Arguments.of(McpRoutingException.notCrudCapable("not crud"),
            McpConstants.ERROR_VALIDATION),
        Arguments.of(McpRoutingException.methodNotAllowed("Entity x does not enable POST"),
            McpConstants.ERROR_METHOD_NOT_ALLOWED),
        Arguments.of(McpRoutingException.unknownFilterField("k", "lines", List.of("a")),
            McpConstants.ERROR_UNKNOWN_FILTER_FIELD),
        Arguments.of(McpRoutingException.unknownSelectorColumn("c", "lines", List.of("a")),
            McpConstants.ERROR_UNKNOWN_SELECTOR_COLUMN),
        Arguments.of(McpRoutingException.readOnlyField("documentNo", "header"),
            McpConstants.ERROR_READ_ONLY_FIELD),
        Arguments.of(McpRoutingException.fieldNotAllowed("x", "header", List.of("a")),
            McpConstants.ERROR_FIELD_NOT_ALLOWED),
        Arguments.of(McpRoutingException.schemaViewRequired(null),
            McpConstants.ERROR_VIEW_REQUIRED),
        Arguments.of(McpRoutingException.unknownArgument("x", "neo_list", List.of("spec")),
            McpConstants.ERROR_UNKNOWN_ARGUMENT),
        Arguments.of(McpRoutingException.parentRequired("sales-order", "lines", "header",
            "salesOrder"), McpConstants.ERROR_PARENT_REQUIRED),
        Arguments.of(McpRoutingException.parentUnresolvable("payment-out", "lines", "header",
            null), McpConstants.ERROR_PARENT_UNRESOLVABLE),
        Arguments.of(McpRoutingException.missingArgument("Missing id", "id"),
            McpConstants.ERROR_VALIDATION));
  }

  private static List<String> names(int count) {
    List<String> names = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      names.add("field" + i);
    }
    return names;
  }

  @Nested
  @DisplayName("unknownFilterField")
  class UnknownFilterField {

    @Test
    @DisplayName("answers 422 with the machine-detectable code, not a generic validation error")
    void codeAndStatus() throws JSONException {
      JSONObject envelope =
          McpRoutingException.unknownFilterField("bpartner", "salesOrder", names(3)).toEnvelope();
      assertEquals(422, envelope.getInt(McpConstants.KEY_STATUS));
      assertEquals(McpConstants.ERROR_UNKNOWN_FILTER_FIELD,
          envelope.getString(McpConstants.KEY_ERROR));
    }

    @Test
    @DisplayName("names the offending key in 'field' so the caller knows which one to fix")
    void namesTheKey() throws JSONException {
      JSONObject envelope =
          McpRoutingException.unknownFilterField("bpartner", "salesOrder", names(3)).toEnvelope();
      assertEquals("bpartner", envelope.getString(McpConstants.PARAM_FIELD));
      assertTrue(envelope.getString(McpConstants.KEY_DETAIL).contains("salesOrder"),
          "the entity belongs in the message — the same key is valid on other entities");
    }

    @Test
    @DisplayName("carries the valid names, which is the whole point of refusing")
    void carriesAvailable() throws JSONException {
      JSONObject envelope = McpRoutingException
          .unknownFilterField("bpartner", "salesOrder", List.of("businessPartner", "documentNo"))
          .toEnvelope();
      assertEquals(2, envelope.getJSONArray(McpConstants.KEY_AVAILABLE).length());
      assertEquals("businessPartner",
          envelope.getJSONArray(McpConstants.KEY_AVAILABLE).getString(0));
    }

    @Test
    @DisplayName("caps 'available' at MAX_AVAILABLE_NAMES rather than billing a wide entity")
    void capsAvailable() throws JSONException {
      JSONObject envelope =
          McpRoutingException.unknownFilterField("x", "salesOrder", names(150)).toEnvelope();
      assertEquals(McpConstants.MAX_AVAILABLE_NAMES,
          envelope.getJSONArray(McpConstants.KEY_AVAILABLE).length());
    }

    @Test
    @DisplayName("says the list is truncated, so a caller does not read a cap as the full set")
    void admitsTruncation() throws JSONException {
      String truncated =
          McpRoutingException.unknownFilterField("x", "e", names(150)).toEnvelope()
              .getString(McpConstants.KEY_HINT);
      assertTrue(truncated.contains("truncated"), "silence here would be the original bug again");
      assertTrue(truncated.contains("etendo_schema"), "and it must say where the rest live");

      String complete = McpRoutingException.unknownFilterField("x", "e", names(3)).toEnvelope()
          .getString(McpConstants.KEY_HINT);
      assertFalse(complete.contains("truncated"),
          "an untruncated list must not claim to be incomplete");
    }

    @Test
    @DisplayName("survives a null available list without inventing an empty answer")
    void nullAvailable() throws JSONException {
      JSONObject envelope =
          McpRoutingException.unknownFilterField("x", "e", null).toEnvelope();
      assertFalse(envelope.has(McpConstants.KEY_AVAILABLE),
          "an absent list is omitted, not emitted as []");
    }
  }

  @Nested
  @DisplayName("filter operators")
  class Operators {

    @Test
    @DisplayName("an unknown operator lists the ones that exist")
    void unknownOperator() throws JSONException {
      JSONObject envelope = McpRoutingException
          .unknownFilterOperator("amount", "greaterThan", McpBusinessFilters.operatorKeys())
          .toEnvelope();
      assertEquals(422, envelope.getInt(McpConstants.KEY_STATUS));
      assertEquals("amount", envelope.getString(McpConstants.PARAM_FIELD));
      assertTrue(envelope.getString(McpConstants.KEY_DETAIL).contains("greaterThan"));
      assertTrue(envelope.getJSONArray(McpConstants.KEY_AVAILABLE).length() >= 5,
          "gt/gte/lt/lte plus between");
    }

    @Test
    @DisplayName("operatorKeys() ends with 'between', which lives outside the operator map")
    void betweenIsListed() {
      List<String> keys = McpBusinessFilters.operatorKeys();
      assertEquals(McpBusinessFilters.OP_BETWEEN, keys.get(keys.size() - 1));
      assertTrue(keys.containsAll(List.of("gt", "gte", "lt", "lte")));
    }

    @Test
    @DisplayName("a malformed operator value says what shape was expected")
    void malformedOperator() throws JSONException {
      JSONObject envelope = McpRoutingException
          .malformedFilterOperator("amount", "between", "the 'between' operator takes a "
              + "two-element [from, to] array")
          .toEnvelope();
      assertEquals(422, envelope.getInt(McpConstants.KEY_STATUS));
      assertEquals(McpConstants.ERROR_VALIDATION, envelope.getString(McpConstants.KEY_ERROR));
      assertTrue(envelope.getString(McpConstants.KEY_DETAIL).contains("two-element"),
          "'malformed' alone does not tell the caller what to send instead");
    }
  }

  @Nested
  @DisplayName("parentRequired")
  class ParentRequired {

    @Test
    @DisplayName("explains why there is no global list, not merely that an argument is missing")
    void explainsTheModel() throws JSONException {
      String detail = McpRoutingException
          .parentRequired("sales-order", "lines", "header", "salesOrder").toEnvelope()
          .getString(McpConstants.KEY_DETAIL);
      assertTrue(detail.contains("no global"),
          "an agent that does not know Etendo needs the reason, or it will retry the same call");
      assertTrue(detail.contains(McpConstants.PARAM_PARENT_ID));
    }

    @Test
    @DisplayName("points 'field' at parentId, so the fix is one named argument")
    void namesTheArgument() throws JSONException {
      JSONObject envelope = McpRoutingException
          .parentRequired("sales-order", "lines", "header", "salesOrder").toEnvelope();
      assertEquals(McpConstants.PARAM_PARENT_ID, envelope.getString(McpConstants.PARAM_FIELD));
      assertEquals(McpConstants.ERROR_PARENT_REQUIRED, envelope.getString(McpConstants.KEY_ERROR));
    }

    @Test
    @DisplayName("carries parentEntity and parentField as extras, making the retry mechanical")
    void carriesExtras() throws JSONException {
      JSONObject envelope = McpRoutingException
          .parentRequired("sales-order", "lines", "header", "salesOrder").toEnvelope();
      assertEquals("header", envelope.getString("parentEntity"));
      assertEquals("salesOrder", envelope.getString("parentField"));
    }

    @Test
    @DisplayName("hints the exact etendo_list call that finds the parent")
    void hintsTheNextCall() throws JSONException {
      String hint = McpRoutingException
          .parentRequired("sales-order", "lines", "header", "salesOrder").toEnvelope()
          .getString(McpConstants.KEY_HINT);
      assertTrue(hint.contains("etendo_list"));
      assertTrue(hint.contains("sales-order"));
      assertTrue(hint.contains("header"));
    }

    @Test
    @DisplayName("degrades honestly when the parent entity has no name to offer")
    void unnamedParent() throws JSONException {
      // A RESOLVED scope whose parent tab is not an included entity of the spec: the FK is known
      // but there is no entity name the agent could call, so neither the detail nor the hint may
      // pretend otherwise.
      JSONObject envelope = McpRoutingException
          .parentRequired("sales-order", "lines", null, "salesOrder").toEnvelope();
      assertFalse(envelope.has("parentEntity"));
      assertEquals("salesOrder", envelope.getString("parentField"));
      assertFalse(envelope.getString(McpConstants.KEY_HINT).contains("etendo_list"),
          "offering a etendo_list on an entity that is not exposed would send the agent nowhere");
      assertFalse(envelope.getString(McpConstants.KEY_DETAIL).contains("null"),
          "never leak the absent name into the prose");
    }

    @Test
    @DisplayName("reads 'the id of its parent record' when the parent cannot be named")
    void unnamedParentSentenceReadsCorrectly() throws JSONException {
      String detail = McpRoutingException
          .parentRequired("payment-out", "lines", null, null).toEnvelope()
          .getString(McpConstants.KEY_DETAIL);
      assertTrue(detail.contains("the id of its parent record."), detail);
      assertFalse(detail.contains("the parent its parent"), detail);
    }

    @Test
    @DisplayName("names the parent entity in the sentence when it is known")
    void namedParentSentenceReadsCorrectly() throws JSONException {
      String detail = McpRoutingException
          .parentRequired("sales-order", "lines", "header", "salesOrder").toEnvelope()
          .getString(McpConstants.KEY_DETAIL);
      assertTrue(detail.contains("the id of the parent header record."), detail);
    }

    @Test
    @DisplayName("the envelope builder and the throwable spell the refusal the same way")
    void oneSourceOfTruth() throws JSONException {
      // McpParentScope.buildParentRequiredError delegates here on purpose: two hand-written copies
      // of this message are two things to keep in step, and the one that drifts is the one the
      // agent happens to hit.
      JSONObject fromException = McpRoutingException
          .parentRequired("sales-order", "lines", "header", "salesOrder").toEnvelope();
      assertEquals(McpConstants.ERROR_PARENT_REQUIRED,
          fromException.getString(McpConstants.KEY_ERROR));
      assertEquals(422, fromException.getInt(McpConstants.KEY_STATUS));
    }
  }

  @Nested
  @DisplayName("routing log line (ETP-5639)")
  class RoutingLogLine {

    @ParameterizedTest(name = "{1}")
    @MethodSource("com.etendoerp.go.mcp.McpRoutingExceptionRefusalTest#refusals")
    @DisplayName("is keyed on the refusal's error code and carries its detail")
    void keyedOnErrorCode(McpRoutingException refusal, String code) {
      assertEquals(code, refusal.getErrorCode());
      String line = McpToolRouter.routingRejectionLogLine("neo_update", refusal);
      assertEquals("MCP tool 'neo_update' rejected (" + code + "): " + refusal.getMessage()
          + " session=" + McpUsageTelemetry.NO_SESSION, line);
    }

    @Test
    @DisplayName("carries the request's session key, for correlation with its feedback")
    void carriesTheSessionKey() {
      McpUsageTelemetry.setCurrentSessionKey("sess-42");
      try {
        assertTrue(McpToolRouter.routingRejectionLogLine("neo_list",
            McpRoutingException.specNotFound("x")).endsWith(" session=sess-42"));
      } finally {
        McpUsageTelemetry.clearCurrentSessionKey();
      }
    }
  }
}
