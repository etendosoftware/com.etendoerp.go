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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code fields} section's payload contract — the half that needs no DAL.
 *
 * <p>The assertions that matter are the refusals. Every key of this section asserts that the
 * shared {@code ETGO_SF_FIELD} curation is wrong for agent use, and the failure mode of a
 * silently-dropped value is the one the section exists to remove: the field stays on its
 * uncurated default, the write verb stays hidden, and nothing anywhere says why. So an unknown
 * {@code visibility}, a quoted boolean and a missing {@code reason} all have to be reported
 * rather than ignored, and the message has to name the correction.</p>
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpFieldsSection")
class McpFieldsSectionTest {

  private static final String REASON = "\"reason\":\"C_Location rows carry no visibility\"";

  private static JSONObject body(String json) throws JSONException {
    return new JSONObject(json);
  }

  private static JSONObject withReason(String keyAndValue) throws JSONException {
    return body("{" + keyAndValue + "," + REASON + "}");
  }

  @Nested
  @DisplayName("declaration")
  class Declaration {

    @Test
    @DisplayName("declares the four documented keys and replace semantics")
    void declaresKeys() {
      McpConfigSection section = McpFieldsSection.declaration();
      assertEquals(McpFieldsSection.NAME, section.getName());
      assertEquals("fields", section.getName());
      assertEquals(McpConfigSection.Merge.REPLACE, section.getMerge());
      assertEquals(Set.of("visibility", "readOnly", "businessCritical", "reason"),
          section.getAllowedKeys());
    }

    @Test
    @DisplayName("a misspelled key is not silently accepted as configuration")
    void misspelledKeyIsNotAllowed() {
      // The unknown-key refusal lives in McpEntityConfig, which reads exactly this set. Without
      // it "readonly" would be indistinguishable from an absent key, and an absent key means
      // "unconfigured" — the reading that leaves the field on the default the override exists to
      // replace. McpFieldViewTest#anUnknownKeyInsideTheSectionIsNotActedOn pins the end to end.
      Set<String> allowed = McpFieldsSection.declaration().getAllowedKeys();
      assertFalse(allowed.contains("readonly"));
      assertFalse(allowed.contains("visibilty"));
      assertFalse(allowed.contains("isReadOnly"));
    }
  }

  @Nested
  @DisplayName("valid payloads")
  class Valid {

    @Test
    @DisplayName("each accepted visibility is accepted, and there are exactly four")
    void everyVisibilityValue() throws JSONException {
      for (String visibility : List.of("editable", "readOnly", "system", "discarded")) {
        assertTrue(McpFieldsSection.validate(withReason("\"visibility\":\"" + visibility + "\""))
            .isEmpty(), visibility + " must be accepted");
        assertEquals(visibility,
            McpFieldsSection.visibility(withReason("\"visibility\":\"" + visibility + "\"")));
      }
    }

    @Test
    @DisplayName("the accepted values are the schema builder's own constants, so they cannot drift")
    void visibilitiesTrackTheBuilderConstants() throws JSONException {
      // If either side is renamed, the section would start refusing the value the builder acts on.
      assertTrue(McpFieldsSection.validate(
          withReason("\"visibility\":\"" + McpSchemaFieldBuilder.VISIBILITY_EDITABLE + "\""))
          .isEmpty());
      assertTrue(McpFieldsSection.validate(
          withReason("\"visibility\":\"" + McpSchemaFieldBuilder.VISIBILITY_READ_ONLY + "\""))
          .isEmpty());
      assertTrue(McpFieldsSection.validate(
          withReason("\"visibility\":\"" + McpSchemaFieldBuilder.VISIBILITY_SYSTEM + "\""))
          .isEmpty());
      assertTrue(McpFieldsSection.validate(
          withReason("\"visibility\":\"" + McpSchemaFieldBuilder.VISIBILITY_DISCARDED + "\""))
          .isEmpty());
    }

    @Test
    @DisplayName("the booleans are accepted unquoted, either way round")
    void booleansAccepted() throws JSONException {
      assertTrue(McpFieldsSection.validate(
          withReason("\"readOnly\":false,\"businessCritical\":true")).isEmpty());
      assertTrue(McpFieldsSection.validate(
          withReason("\"readOnly\":true,\"businessCritical\":false")).isEmpty());
    }

    @Test
    @DisplayName("the first consumer's real payload is valid")
    void bpLocationPayload() throws JSONException {
      JSONObject payload = body("{\"visibility\":\"editable\",\"readOnly\":false,"
          + "\"businessCritical\":true," + REASON + "}");
      assertTrue(McpFieldsSection.validate(payload).isEmpty());
      assertEquals("editable", McpFieldsSection.visibility(payload));
      assertEquals(Boolean.FALSE, McpFieldsSection.readOnly(payload));
      assertEquals(Boolean.TRUE, McpFieldsSection.businessCritical(payload));
    }
  }

  @Nested
  @DisplayName("rejections")
  class Rejections {

    @Test
    @DisplayName("an empty body is refused rather than read as 'nothing reclassified'")
    void emptyBody() throws JSONException {
      // The contrast with the parent section is deliberate: there every key is optional, here an
      // empty section is a half-finished edit that would do nothing while looking configured.
      List<String> problems = McpFieldsSection.validate(body("{}"));
      assertEquals(1, problems.size());
      assertTrue(problems.get(0).contains("empty"));
      assertTrue(problems.get(0).contains("reason"), "the refusal should name the correction");
    }

    @Test
    @DisplayName("an unknown visibility is refused and the message lists every accepted value")
    void unknownVisibility() throws JSONException {
      List<String> problems = McpFieldsSection.validate(withReason("\"visibility\":\"writable\""));
      assertEquals(1, problems.size());
      String problem = problems.get(0);
      assertTrue(problem.contains("writable"), "the refusal must name the offending value");
      assertTrue(problem.contains("editable") && problem.contains("readOnly")
              && problem.contains("system") && problem.contains("discarded"),
          "an operator fixing this needs the accepted values, not just a rejection: " + problem);
    }

    @Test
    @DisplayName("a blank visibility is refused, not treated as absent")
    void blankVisibility() throws JSONException {
      List<String> problems = McpFieldsSection.validate(withReason("\"visibility\":\"   \""));
      assertEquals(1, problems.size());
      assertTrue(problems.get(0).contains("blank"));
    }

    @Test
    @DisplayName("a quoted boolean is refused — this section's payloads must be predictable")
    void quotedBooleanRefused() throws JSONException {
      // The dangerous one: some JSON libraries read "false" as false and others as true, so the
      // literal type is required rather than coerced.
      List<String> problems = McpFieldsSection.validate(withReason("\"readOnly\":\"false\""));
      assertEquals(1, problems.size());
      assertTrue(problems.get(0).contains("readOnly"));
      assertTrue(problems.get(0).contains("JSON boolean"));
      assertTrue(problems.get(0).contains("\"false\""), "the message should quote what was written");
    }

    @Test
    @DisplayName("a non-boolean of any type is refused for both boolean keys")
    void nonBooleanRefused() throws JSONException {
      assertFalse(McpFieldsSection.validate(withReason("\"readOnly\":1")).isEmpty());
      assertFalse(McpFieldsSection.validate(withReason("\"businessCritical\":\"true\"")).isEmpty());
      assertFalse(McpFieldsSection.validate(withReason("\"businessCritical\":[]")).isEmpty());
    }

    @Test
    @DisplayName("both boolean keys are reported, not just the first")
    void bothBooleansReported() throws JSONException {
      List<String> problems = McpFieldsSection.validate(
          withReason("\"readOnly\":\"false\",\"businessCritical\":\"true\""));
      assertEquals(2, problems.size(), "one round trip should surface every problem: " + problems);
    }

    @Test
    @DisplayName("a missing reason is refused, so the reclassification stays auditable")
    void reasonRequired() throws JSONException {
      List<String> problems = McpFieldsSection.validate(body("{\"visibility\":\"editable\"}"));
      assertEquals(1, problems.size());
      assertTrue(problems.get(0).contains("reason"));
      assertTrue(problems.get(0).contains("auditable"));
    }

    @Test
    @DisplayName("a blank reason is refused too — it is a leftover, not a justification")
    void blankReasonRefused() throws JSONException {
      assertFalse(McpFieldsSection.validate(
          body("{\"visibility\":\"editable\",\"reason\":\"   \"}")).isEmpty());
      assertFalse(McpFieldsSection.validate(
          body("{\"visibility\":\"editable\",\"reason\":\"\"}")).isEmpty());
    }

    @Test
    @DisplayName("a reason alone is accepted — it documents without reclassifying")
    void reasonOnlyIsNotAnError() throws JSONException {
      // Not a rejection: the body is non-empty and asserts nothing about the field, so there is
      // nothing to be wrong about. It is inert, and the empty-body refusal above is what catches
      // the genuinely empty case.
      assertTrue(McpFieldsSection.validate(body("{" + REASON + "}")).isEmpty());
      assertNull(McpFieldsSection.visibility(body("{" + REASON + "}")));
    }
  }

  @Nested
  @DisplayName("accessors")
  class Accessors {

    @Test
    @DisplayName("a null body reads as unconfigured, never an NPE")
    void nullBodyIsSafe() {
      assertNull(McpFieldsSection.visibility(null));
      assertNull(McpFieldsSection.readOnly(null));
      assertNull(McpFieldsSection.businessCritical(null));
      assertNull(McpFieldsSection.reason(null));
    }

    @Test
    @DisplayName("an absent boolean answers null, which is not a configured false")
    void absentBooleanIsNotFalse() throws JSONException {
      JSONObject onlyVisibility = withReason("\"visibility\":\"editable\"");
      assertNull(McpFieldsSection.readOnly(onlyVisibility),
          "an absent key must not overwrite the SFField row's own value with false");
      assertNull(McpFieldsSection.businessCritical(onlyVisibility));
    }

    @Test
    @DisplayName("a malformed boolean answers null rather than reading as false")
    void malformedBooleanIsNotFalse() throws JSONException {
      // Unreachable in production (validate refuses the body first) but belt and braces: a
      // non-boolean must never resolve to the permissive value.
      assertNull(McpFieldsSection.readOnly(withReason("\"readOnly\":\"true\"")));
      assertNull(McpFieldsSection.businessCritical(withReason("\"businessCritical\":7")));
    }

    @Test
    @DisplayName("declared values are trimmed")
    void valuesAreTrimmed() throws JSONException {
      assertEquals("editable", McpFieldsSection.visibility(withReason("\"visibility\":\" editable \"")));
      assertEquals("because", McpFieldsSection.reason(body("{\"reason\":\"  because  \"}")));
    }
  }
}
