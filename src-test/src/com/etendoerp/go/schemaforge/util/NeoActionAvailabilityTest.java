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

package com.etendoerp.go.schemaforge.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NeoActionAvailability} (ETP-5714): the declaration of when an action
 * applies, and the advisory judgement of one record against it. Pure, no DAL.
 *
 * @covers com.etendoerp.go.schemaforge.util.NeoActionAvailability
 */
// Test methods live in the @Nested inner classes below.
@SuppressWarnings("java:S2187")
@DisplayName("NeoActionAvailability")
class NeoActionAvailabilityTest {

  private static final String STATUS = "documentStatus";
  private static final String POSTED = "posted";

  private static java.util.function.Function<String, String> record(String... pairs) {
    Map<String, String> values = new java.util.HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      values.put(pairs[i], pairs[i + 1]);
    }
    return values::get;
  }

  @Nested
  @DisplayName("evaluate availableWhen")
  class AvailableWhen {

    @Test
    @DisplayName("a value in the allowed list applies; another one does not, and says why")
    void matchesAndRefuses() {
      NeoActionAvailability draftOnly = NeoActionAvailability.whenIn(STATUS, "DR");

      NeoActionAvailability.Verdict draft = draftOnly.evaluate(record(STATUS, "DR"));
      NeoActionAvailability.Verdict completed = draftOnly.evaluate(record(STATUS, "CO"));

      assertEquals(Boolean.TRUE, draft.available());
      assertNull(draft.reason());
      assertEquals(Boolean.FALSE, completed.available());
      assertTrue(completed.reason().contains("documentStatus is DR"), completed.reason());
      assertTrue(completed.reason().contains("this record's documentStatus is CO"),
          completed.reason());
    }

    @Test
    @DisplayName("every declared property must match (AND)")
    void allPropertiesMustMatch() {
      NeoActionAvailability unpostable = NeoActionAvailability.whenIn(STATUS, "CO")
          .andWhen(POSTED, "Y");

      assertEquals(Boolean.TRUE,
          unpostable.evaluate(record(STATUS, "CO", POSTED, "Y")).available());
      assertEquals(Boolean.FALSE,
          unpostable.evaluate(record(STATUS, "CO", POSTED, "N")).available());
      assertEquals(Boolean.FALSE,
          unpostable.evaluate(record(STATUS, "DR", POSTED, "Y")).available());
    }

    @Test
    @DisplayName("a value prefixed with ! means anything but that value")
    void negation() {
      NeoActionAvailability postable = NeoActionAvailability.whenIn(POSTED, "!Y");

      assertEquals(Boolean.TRUE, postable.evaluate(record(POSTED, "N")).available());
      assertEquals(Boolean.TRUE, postable.evaluate(record(POSTED, "NC")).available());
      NeoActionAvailability.Verdict posted = postable.evaluate(record(POSTED, "Y"));
      assertEquals(Boolean.FALSE, posted.available());
      assertTrue(posted.reason().contains("posted is not Y"), posted.reason());
    }

    @Test
    @DisplayName("a property the record cannot supply is UNKNOWN, never unavailable (advisory)")
    void unknownPropertyIsNotAVerdict() {
      NeoActionAvailability.Verdict verdict =
          NeoActionAvailability.whenIn("noSuchProperty", "X").evaluate(record(STATUS, "DR"));

      assertNull(verdict.available());
      assertNull(verdict.reason());
    }

    @Test
    @DisplayName("the note is appended to every not-available reason")
    void noteIsAppended() {
      NeoActionAvailability declared = NeoActionAvailability.whenIn(STATUS, "DR")
          .withNote("Correct it with a return.");

      assertTrue(declared.evaluate(record(STATUS, "CO")).reason().endsWith("Correct it with a return."));
    }
  }

  @Nested
  @DisplayName("evaluate valuesByStatus")
  class ValuesByStatus {

    private final NeoActionAvailability docAction = NeoActionAvailability.valuesByStatus(STATUS,
        Map.of("DR", List.of("CO", "VO"), "CO", List.of("CL", "RE")));

    @Test
    @DisplayName("lists the values that apply to the record's status")
    void valuesNow() {
      NeoActionAvailability.Verdict draft = docAction.evaluate(record(STATUS, "DR"));
      NeoActionAvailability.Verdict completed = docAction.evaluate(record(STATUS, "CO"));

      assertEquals(Boolean.TRUE, draft.available());
      assertEquals(List.of("CO", "VO"), draft.valuesNow());
      assertEquals(List.of("CL", "RE"), completed.valuesNow());
    }

    @Test
    @DisplayName("an explicitly empty list offers nothing, with a reason; a status never listed is NOT judged")
    void nothingApplies() {
      NeoActionAvailability.Verdict emptyList = NeoActionAvailability
          .valuesByStatus(STATUS, Map.of("CO", List.<String>of()))
          .withNote("Use a return.").evaluate(record(STATUS, "CO"));
      NeoActionAvailability.Verdict unlisted = docAction.evaluate(record(STATUS, "VO"));

      assertEquals(Boolean.FALSE, emptyList.available());
      assertTrue(emptyList.reason().endsWith("Use a return."), emptyList.reason());
      assertNull(unlisted.available(), "advisory data never says 'unavailable' for a state it did not describe");
      assertNull(unlisted.reason());
    }

    @Test
    @DisplayName("an unreadable status is UNKNOWN")
    void unknownStatus() {
      assertNull(docAction.evaluate(record()).available());
    }
  }

  @Nested
  @DisplayName("rendering and parsing")
  class Json {

    @Test
    @DisplayName("writeTo renders only the declared parts")
    void writesDeclaredParts() throws JSONException {
      JSONObject onlyWhen = new JSONObject();
      NeoActionAvailability.whenIn(STATUS, "DR").writeTo(onlyWhen);
      JSONObject both = new JSONObject();
      NeoActionAvailability.whenIn(STATUS, "CO")
          .withValuesByStatus(STATUS, Map.of("CO", List.of("RE"))).writeTo(both);

      assertEquals("DR", onlyWhen.getJSONObject("availableWhen").getJSONArray(STATUS).getString(0));
      assertFalse(onlyWhen.has("valuesByStatus"));
      assertEquals(STATUS, both.getJSONObject("valuesByStatus").getString("property"));
      assertEquals("RE", both.getJSONObject("valuesByStatus").getJSONObject("values")
          .getJSONArray("CO").getString(0));
    }

    @Test
    @DisplayName("fromJson reads the MCP_CONFIG form back into the same judgement")
    void fromJsonRoundTrip() throws JSONException {
      NeoActionAvailability parsed = NeoActionAvailability.fromJson(
          new JSONObject("{\"posted\":[\"!Y\"]}"),
          new JSONObject("{\"property\":\"documentStatus\",\"values\":{\"CO\":[\"RE\"]}}"));

      NeoActionAvailability.Verdict verdict =
          parsed.evaluate(record(POSTED, "N", STATUS, "CO"));

      assertEquals(Boolean.TRUE, verdict.available());
      assertEquals(List.of("RE"), verdict.valuesNow());
      assertNull(NeoActionAvailability.fromJson(null, null));
    }

    @Test
    @DisplayName("problemsOf reports malformed declarations and accepts a good one")
    void problems() throws JSONException {
      assertTrue(NeoActionAvailability.problemsOf(new JSONObject("{\"posted\":[\"!Y\"]}"),
          new JSONObject("{\"property\":\"s\",\"values\":{\"CO\":[]}}")).isEmpty());
      assertFalse(NeoActionAvailability
          .problemsOf(new JSONObject("{\"posted\":[]}"), null).isEmpty());
      assertFalse(NeoActionAvailability.problemsOf(null,
          new JSONObject("{\"values\":{\"CO\":[\"RE\"]}}")).isEmpty(), "property is required");
      assertFalse(NeoActionAvailability.problemsOf(null,
          new JSONObject("{\"property\":\"s\"}")).isEmpty(), "values are required");
    }

    @Test
    @DisplayName("valueKey names one value of an action")
    void valueKey() {
      assertEquals("documentAction:RE", NeoActionAvailability.valueKey("documentAction", "RE"));
    }
  }
}
