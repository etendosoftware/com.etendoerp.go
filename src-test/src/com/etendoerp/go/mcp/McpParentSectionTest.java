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

import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code parent} section's payload contract — the half that needs no DAL.
 *
 * <p>The safety-relevant assertions here are the ones about {@code optionalFor}: it must accept
 * reads, refuse writes, and demand a reason. A regression that silently accepted
 * {@code optionalFor:["create"]} would disable the write half of the gate without anyone noticing,
 * which is precisely the failure mode the design set out to avoid.</p>
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpParentSection")
class McpParentSectionTest {

  private static JSONObject body(String json) throws JSONException {
    return new JSONObject(json);
  }

  @Nested
  @DisplayName("declaration")
  class Declaration {

    @Test
    @DisplayName("declares the five documented keys and replace semantics")
    void declaresKeys() {
      McpConfigSection section = McpParentSection.declaration();
      assertEquals(McpParentSection.NAME, section.getName());
      assertEquals(McpConfigSection.Merge.REPLACE, section.getMerge());
      assertEquals(Set.of("field", "entity", "optionalFor", "reason", "mode"),
          section.getAllowedKeys());
    }

    @Test
    @DisplayName("a misspelled key is not silently accepted as configuration")
    void misspelledKeyIsNotAllowed() {
      assertFalse(McpParentSection.declaration().getAllowedKeys().contains("parentField"));
    }
  }

  @Nested
  @DisplayName("valid payloads")
  class Valid {

    @Test
    @DisplayName("an empty body is valid — every key is optional")
    void emptyBody() throws JSONException {
      assertTrue(McpParentSection.validate(body("{}")).isEmpty());
    }

    @Test
    @DisplayName("the minimal form is a single field")
    void minimalForm() throws JSONException {
      assertTrue(McpParentSection.validate(body("{\"field\":\"salesOrder\"}")).isEmpty());
    }

    @Test
    @DisplayName("read verbs relaxed with a reason")
    void relaxedReadsWithReason() throws JSONException {
      JSONObject b = body("{\"optionalFor\":[\"list\",\"get\"],\"reason\":\"singleton parent\"}");
      assertTrue(McpParentSection.validate(b).isEmpty());
      assertEquals(Set.of("list", "get"), McpParentSection.optionalVerbs(b));
      assertEquals("singleton parent", McpParentSection.reason(b));
    }

    @Test
    @DisplayName("sameRecord mode")
    void sameRecordMode() throws JSONException {
      JSONObject b = body("{\"mode\":\"sameRecord\"}");
      assertTrue(McpParentSection.validate(b).isEmpty());
      assertTrue(McpParentSection.isSameRecord(b));
    }
  }

  @Nested
  @DisplayName("optionalFor cannot relax writes")
  class WriteGateIsNotRelaxable {

    @Test
    @DisplayName("create is refused, not ignored")
    void createRefused() throws JSONException {
      List<String> problems = McpParentSection.validate(
          body("{\"optionalFor\":[\"create\"],\"reason\":\"x\"}"));
      assertEquals(1, problems.size());
      assertTrue(problems.get(0).contains("write verb 'create'"));
      assertTrue(problems.get(0).contains("orphan"), "the refusal should say why");
    }

    @Test
    @DisplayName("update and delete are refused too")
    void updateAndDeleteRefused() throws JSONException {
      assertFalse(McpParentSection.validate(
          body("{\"optionalFor\":[\"update\"],\"reason\":\"x\"}")).isEmpty());
      assertFalse(McpParentSection.validate(
          body("{\"optionalFor\":[\"delete\"],\"reason\":\"x\"}")).isEmpty());
    }

    @Test
    @DisplayName("a write verb smuggled in beside a read verb is still refused")
    void mixedListIsRefused() throws JSONException {
      List<String> problems = McpParentSection.validate(
          body("{\"optionalFor\":[\"list\",\"delete\"],\"reason\":\"x\"}"));
      assertFalse(problems.isEmpty(),
          "one valid verb must not launder an invalid one");
    }

    @Test
    @DisplayName("optionalVerbs never reports a write verb, even from a rejected payload")
    void optionalVerbsFiltersWrites() throws JSONException {
      Set<String> verbs = McpParentSection.optionalVerbs(
          body("{\"optionalFor\":[\"list\",\"create\"]}"));
      assertEquals(Set.of("list"), verbs,
          "belt and braces: the accessor must not leak a write relaxation either");
    }
  }

  @Nested
  @DisplayName("other rejections")
  class Rejections {

    @Test
    @DisplayName("optionalFor without a reason is refused, so exceptions stay auditable")
    void reasonRequired() throws JSONException {
      List<String> problems = McpParentSection.validate(body("{\"optionalFor\":[\"list\"]}"));
      assertEquals(1, problems.size());
      assertTrue(problems.get(0).contains("reason"));
    }

    @Test
    @DisplayName("an unknown mode is refused")
    void unknownMode() throws JSONException {
      assertFalse(McpParentSection.validate(body("{\"mode\":\"singletonParent\"}")).isEmpty(),
          "a mode that was considered and dropped must not silently do nothing");
    }

    @Test
    @DisplayName("optionalFor must be an array")
    void optionalForMustBeArray() throws JSONException {
      assertFalse(McpParentSection.validate(
          body("{\"optionalFor\":\"list\",\"reason\":\"x\"}")).isEmpty());
    }

    @Test
    @DisplayName("an empty optionalFor is refused rather than treated as 'nothing relaxed'")
    void emptyOptionalFor() throws JSONException {
      assertFalse(McpParentSection.validate(
          body("{\"optionalFor\":[],\"reason\":\"x\"}")).isEmpty());
    }

    @Test
    @DisplayName("an unknown verb is refused")
    void unknownVerb() throws JSONException {
      assertFalse(McpParentSection.validate(
          body("{\"optionalFor\":[\"read\"],\"reason\":\"x\"}")).isEmpty());
    }

    @Test
    @DisplayName("a blank value is a half-finished edit, not a value")
    void blankValues() throws JSONException {
      assertFalse(McpParentSection.validate(body("{\"field\":\"   \"}")).isEmpty());
      assertFalse(McpParentSection.validate(body("{\"entity\":\"\"}")).isEmpty());
    }
  }

  @Nested
  @DisplayName("accessors")
  class Accessors {

    @Test
    @DisplayName("a null body reads as unconfigured, never an NPE")
    void nullBodyIsSafe() {
      assertTrue(McpParentSection.optionalVerbs(null).isEmpty());
      assertFalse(McpParentSection.isSameRecord(null));
      assertEquals(null, McpParentSection.declaredField(null));
      assertEquals(null, McpParentSection.declaredEntity(null));
      assertEquals(null, McpParentSection.reason(null));
    }

    @Test
    @DisplayName("declared values are trimmed")
    void valuesAreTrimmed() throws JSONException {
      JSONObject b = body("{\"field\":\"  salesOrder \",\"entity\":\" header \"}");
      assertEquals("salesOrder", McpParentSection.declaredField(b));
      assertEquals("header", McpParentSection.declaredEntity(b));
    }

    @Test
    @DisplayName("every verb is covered by the gate by default")
    void allVerbs() {
      assertEquals(List.of("list", "get", "create", "update", "delete"),
          McpParentSection.ALL_VERBS);
    }

    @Test
    @DisplayName("a JSONArray of verbs round-trips")
    void arrayRoundTrip() throws JSONException {
      JSONObject b = new JSONObject();
      b.put("optionalFor", new JSONArray(List.of("get")));
      b.put("reason", "why");
      assertTrue(McpParentSection.validate(b).isEmpty());
      assertEquals(Set.of("get"), McpParentSection.optionalVerbs(b));
    }
  }

  @Nested
  @DisplayName("mode unparented")
  class Unparented {

    @Test
    @DisplayName("accepted with a reason")
    void accepted() throws JSONException {
      assertTrue(McpParentSection.validate(
          body("{\"mode\":\"unparented\",\"reason\":\"no FK to the config table\"}")).isEmpty());
      assertTrue(McpParentSection.isUnparented(
          body("{\"mode\":\"unparented\",\"reason\":\"x\"}")));
    }

    @Test
    @DisplayName("demands a reason — an unfiltered read must be justified, not just declared")
    void reasonRequired() throws JSONException {
      List<String> problems = McpParentSection.validate(body("{\"mode\":\"unparented\"}"));
      assertEquals(1, problems.size());
      assertTrue(problems.get(0).contains("reason"));
      assertTrue(problems.get(0).contains("auditable"));
    }

    @Test
    @DisplayName("refuses to sit alongside a parent.field")
    void contradictsField() throws JSONException {
      // The dangerous half is that whoever wrote both believes the one that is not honoured. A
      // resolver that silently picked a winner would make the payload's meaning depend on
      // implementation order.
      List<String> problems = McpParentSection.validate(
          body("{\"mode\":\"unparented\",\"reason\":\"x\",\"field\":\"invoice\"}"));
      assertEquals(1, problems.size());
      assertTrue(problems.get(0).contains("cannot both have and lack"));
    }

    @Test
    @DisplayName("refuses optionalFor, which would read as 'writes are still gated'")
    void contradictsOptionalFor() throws JSONException {
      List<String> problems = McpParentSection.validate(body(
          "{\"mode\":\"unparented\",\"reason\":\"x\",\"optionalFor\":[\"list\"]}"));
      assertTrue(problems.stream().anyMatch(p -> p.contains("meaningless")),
          "under unparented writes are refused outright, not relaxed — saying both misleads");
    }

    @Test
    @DisplayName("is not confused with sameRecord, and both stay listed on an unknown mode")
    void modesAreDistinct() throws JSONException {
      assertFalse(McpParentSection.isUnparented(body("{\"mode\":\"sameRecord\"}")));
      assertFalse(McpParentSection.isSameRecord(
          body("{\"mode\":\"unparented\",\"reason\":\"x\"}")));
      String problem = McpParentSection.validate(body("{\"mode\":\"nonsense\"}")).get(0);
      assertTrue(problem.contains("sameRecord") && problem.contains("unparented"),
          "the refusal should name every mode that exists, not just one");
    }

    @Test
    @DisplayName("an absent mode is still the default, not unparented")
    void absentModeIsNotUnparented() throws JSONException {
      // Guards the invariant that matters most: nothing about a plain payload may imply the
      // parent filter is absent. "I could not find the link" must withhold the entity, never
      // publish it unfiltered.
      assertFalse(McpParentSection.isUnparented(body("{}")));
      assertFalse(McpParentSection.isUnparented(body("{\"field\":\"invoice\"}")));
      assertFalse(McpParentSection.isUnparented((JSONObject) null));
    }
  }
}
