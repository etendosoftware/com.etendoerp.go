/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EtendoGoJwtServlet#sanitizeFirstSteps(JSONObject)} — what actually
 * reaches {@code ETGO_ACCOUNT.FIRST_STEPS} when a client POSTs the checklist state.
 *
 * <p>Pure function of its argument, no servlet harness required (same shape as
 * {@code EtendoGoJwtServletMaskEmailTest}).
 *
 * <p>The point of this sanitizer is that the column stores a CLIENT-SUPPLIED string: whatever
 * is written here is handed straight back to every future session of that account. So the
 * assertions below are about what it REFUSES to carry, not only about what it keeps.
 */
class EtendoGoJwtServletFirstStepsTest {

  private static final String V = "v";
  private static final String SEEN = "seen";
  private static final String DISMISSED = "dismissed";
  private static final String COMPLETED = "completed";

  private static JSONObject sanitize(JSONObject input) throws JSONException {
    return EtendoGoJwtServlet.sanitizeFirstSteps(input);
  }

  @Test
  @DisplayName("stamps the current version whatever the client claimed")
  void forcesVersion() throws JSONException {
    assertEquals(1, sanitize(new JSONObject().put(V, 99)).getInt(V));
    assertEquals(1, sanitize(new JSONObject()).getInt(V));
  }

  @Test
  @DisplayName("coerces `seen` and `dismissed` to real booleans")
  void coercesBooleans() throws JSONException {
    JSONObject clean = sanitize(new JSONObject().put(SEEN, true).put(DISMISSED, true));
    assertTrue(clean.getBoolean(SEEN));
    assertTrue(clean.getBoolean(DISMISSED));

    // `optBoolean` does accept the STRING "true"; what must never happen is a non-boolean
    // being stored verbatim and read back as truthy by the client.
    JSONObject fromNumbers = sanitize(new JSONObject().put(SEEN, 7).put(DISMISSED, 7));
    assertFalse(fromNumbers.getBoolean(SEEN));
    assertFalse(fromNumbers.getBoolean(DISMISSED));
  }

  @Test
  @DisplayName("ETP-5364 — defaults `dismissed` to false when the key is absent")
  void dismissedDefaultsToFalse() throws JSONException {
    // Every state stored before ETP-5364 has no such key. The flag HIDES a menu entry, so the
    // only safe default is "still visible" — this is the server half of the same rule
    // `normalizeFirstStepsState` applies in the browser.
    JSONObject clean = sanitize(new JSONObject().put(SEEN, true));
    assertTrue(clean.has(DISMISSED));
    assertFalse(clean.getBoolean(DISMISSED));
  }

  @Test
  @DisplayName("ETP-5364 — `dismissed` is independent of the completed list")
  void dismissedIsNotDerivedFromCompletion() throws JSONException {
    // Finishing every step and choosing to put the checklist away are two different acts.
    // Deriving one from the other here would take the sidebar entry from a user who never
    // asked, and there would be no way left to un-tick a step.
    JSONObject full = new JSONObject()
        .put(COMPLETED, new JSONArray()
            .put("company-data").put("fiscal-config").put("products")
            .put("contacts").put("invoice-sequence").put("team"));
    assertFalse(sanitize(full).getBoolean(DISMISSED));

    JSONObject dismissedOnly = new JSONObject().put(DISMISSED, true);
    assertTrue(sanitize(dismissedOnly).getBoolean(DISMISSED));
    assertEquals(0, sanitize(dismissedOnly).getJSONArray(COMPLETED).length());
  }

  @Test
  @DisplayName("drops step ids that are not on the allowlist")
  void dropsUnknownStepIds() throws JSONException {
    JSONObject clean = sanitize(new JSONObject().put(COMPLETED,
        new JSONArray().put("products").put("create-account").put("../../etc/passwd")));
    JSONArray completed = clean.getJSONArray(COMPLETED);
    assertEquals(1, completed.length());
    assertEquals("products", completed.getString(0));
  }

  @Test
  @DisplayName("de-duplicates and re-orders into allowlist order")
  void deduplicatesAndOrders() throws JSONException {
    // The client may send anything; the stored array is canonical so two clients that ticked
    // the same steps in different orders read back identical.
    JSONArray completed = sanitize(new JSONObject().put(COMPLETED,
        new JSONArray().put("team").put("products").put("team"))).getJSONArray(COMPLETED);
    assertEquals(2, completed.length());
    assertEquals("products", completed.getString(0));
    assertEquals("team", completed.getString(1));
  }

  @Test
  @DisplayName("ignores non-string entries rather than storing them")
  void ignoresNonStringEntries() throws JSONException {
    JSONArray completed = sanitize(new JSONObject().put(COMPLETED,
        new JSONArray().put(42).put(true).put("products"))).getJSONArray(COMPLETED);
    assertEquals(1, completed.length());
    assertEquals("products", completed.getString(0));
  }

  @Test
  @DisplayName("keeps nothing the client invented outside the known shape")
  void keepsOnlyTheKnownShape() throws JSONException {
    JSONObject clean = sanitize(new JSONObject()
        .put(SEEN, true)
        .put("script", "<img onerror=alert(1)>")
        .put("completedCount", 99));
    assertEquals(4, clean.length());
    assertTrue(clean.has(V));
    assertTrue(clean.has(SEEN));
    assertTrue(clean.has(DISMISSED));
    assertTrue(clean.has(COMPLETED));
  }

  @Test
  @DisplayName("an empty payload is a valid, fully defaulted state")
  void emptyPayloadIsDefaulted() throws JSONException {
    JSONObject clean = sanitize(new JSONObject());
    assertEquals(1, clean.getInt(V));
    assertFalse(clean.getBoolean(SEEN));
    assertFalse(clean.getBoolean(DISMISSED));
    assertEquals(0, clean.getJSONArray(COMPLETED).length());
  }
}
