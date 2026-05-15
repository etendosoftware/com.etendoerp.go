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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.model.ad.ui.Tab;

/**
 * Regression tests for {@link NeoDefaultsCascadeHelper#mergeCalloutUpdates(...)}.
 *
 * <p>Guards the contract that when a callout response carries a fresh
 * {@code _identifier} alongside a new {@code value} for an FK field, the
 * cascade helper propagates it as {@code {field}$_identifier} on the defaults
 * payload. Without this, the prior identifier — resolved from the now-
 * overwritten value — lingers in the response and the UI shows a label that
 * does not match the underlying ID (e.g. dashboard rendering AED on an EUR
 * record after the priceList callout corrects the currency).</p>
 */
public class NeoDefaultsCascadeHelperTest {

  private static Method mergeCalloutUpdatesMethod() throws Exception {
    Method m = NeoDefaultsCascadeHelper.class.getDeclaredMethod(
        "mergeCalloutUpdates",
        JSONObject.class, JSONObject.class, JSONObject.class,
        Set.class, Tab.class,
        NeoDefaultsService.CalloutCascadeResult.class,
        Set.class, Set.class);
    m.setAccessible(true);
    return m;
  }

  /**
   * Build a callout response shaped like the real {@code SL_Invoice_PriceList}
   * one that exposed the original bug:
   * {@code {"updates": {"currency": {"value": "102", "_identifier": "EUR"}}}}.
   */
  private static JSONObject calloutBodyWith(String field, Object value, String identifier)
      throws Exception {
    JSONObject inner = new JSONObject();
    inner.put("value", value);
    if (identifier != null) {
      inner.put("_identifier", identifier);
    }
    JSONObject updates = new JSONObject();
    updates.put(field, inner);
    JSONObject body = new JSONObject();
    body.put("updates", updates);
    return body;
  }

  private static Object invokeMerge(JSONObject calloutBody, JSONObject formState,
      JSONObject defaults) throws Exception {
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();
    Set<String> seqFields = new HashSet<>();
    Set<String> nextPending = new HashSet<>();
    Set<String> protectedFields = new HashSet<>();
    return mergeCalloutUpdatesMethod().invoke(null,
        calloutBody, formState, defaults, seqFields,
        null /* Tab — not exercised when resolveCallout is mocked to null */,
        result, nextPending, protectedFields);
  }

  @Test
  public void testIdentifierPropagatedWhenCalloutReturnsBoth() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString())).thenReturn(null);

      JSONObject defaults = new JSONObject();
      defaults.put("currency", "238");
      defaults.put("currency$_identifier", "AED");
      JSONObject formState = new JSONObject();
      formState.put("currency", "238");
      JSONObject calloutBody = calloutBodyWith("currency", "102", "EUR");

      invokeMerge(calloutBody, formState, defaults);

      assertEquals("Value must be overwritten with the callout's new value",
          "102", defaults.get("currency"));
      assertEquals("Identifier must be propagated alongside the new value — "
          + "without this, the response keeps the stale 'AED' label "
          + "while the value is already EUR's ID (102)",
          "EUR", defaults.get("currency$_identifier"));
    }
  }

  @Test
  public void testIdentifierPropagatedEvenWhenAbsentBefore() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString())).thenReturn(null);

      JSONObject defaults = new JSONObject();
      JSONObject formState = new JSONObject();
      JSONObject calloutBody = calloutBodyWith("currency", "102", "EUR");

      invokeMerge(calloutBody, formState, defaults);

      assertEquals("102", defaults.get("currency"));
      assertEquals("EUR", defaults.get("currency$_identifier"));
    }
  }

  /**
   * When the callout returns a value but no {@code _identifier}, the helper
   * must NOT clobber an existing companion. The previous identifier may still
   * be valid, and overwriting it with null would render the field labelless.
   */
  @Test
  public void testExistingIdentifierPreservedWhenCalloutOmitsIt() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString())).thenReturn(null);

      JSONObject defaults = new JSONObject();
      defaults.put("currency", "238");
      defaults.put("currency$_identifier", "AED");
      JSONObject formState = new JSONObject();
      formState.put("currency", "238");
      JSONObject calloutBody = calloutBodyWith("currency", "102", null);

      invokeMerge(calloutBody, formState, defaults);

      assertEquals("102", defaults.get("currency"));
      // Helper does not touch the identifier when callout omits it; existing
      // value is left in place rather than nulled out.
      assertEquals("AED", defaults.get("currency$_identifier"));
    }
  }

  /**
   * A null/JSONObject.NULL identifier from the callout must be ignored — it
   * is a "no opinion" signal, not a "clear it" instruction.
   */
  @Test
  public void testNullIdentifierIsIgnored() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString())).thenReturn(null);

      JSONObject defaults = new JSONObject();
      defaults.put("currency$_identifier", "AED");
      JSONObject formState = new JSONObject();
      JSONObject inner = new JSONObject();
      inner.put("value", "102");
      inner.put("_identifier", JSONObject.NULL);
      JSONObject updates = new JSONObject();
      updates.put("currency", inner);
      JSONObject calloutBody = new JSONObject();
      calloutBody.put("updates", updates);

      invokeMerge(calloutBody, formState, defaults);

      assertEquals("102", defaults.get("currency"));
      assertEquals("Existing identifier must be left untouched when the "
          + "callout sends an explicit null _identifier",
          "AED", defaults.get("currency$_identifier"));
    }
  }

  /**
   * Sanity check: empty-string callout values that would clear a present field
   * are skipped (existing safeguard). Identifier propagation must NOT kick in
   * when the value update itself is rejected.
   */
  @Test
  public void testEmptyValueSkipsBothValueAndIdentifier() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString())).thenReturn(null);

      JSONObject defaults = new JSONObject();
      defaults.put("currency", "102");
      defaults.put("currency$_identifier", "EUR");
      JSONObject formState = new JSONObject();
      formState.put("currency", "102");
      JSONObject calloutBody = calloutBodyWith("currency", "", "SOMETHING");

      invokeMerge(calloutBody, formState, defaults);

      assertEquals("Existing value preserved when callout sends empty string",
          "102", defaults.get("currency"));
      assertEquals("Identifier preserved when value update was rejected",
          "EUR", defaults.get("currency$_identifier"));
    }
  }

  @Test
  public void testNoUpdatesObjectIsNoOp() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString())).thenReturn(null);

      JSONObject defaults = new JSONObject();
      defaults.put("currency", "102");
      JSONObject formState = new JSONObject();
      JSONObject calloutBody = new JSONObject(); // no "updates" key

      invokeMerge(calloutBody, formState, defaults);

      assertEquals("102", defaults.get("currency"));
      assertFalse(defaults.has("currency$_identifier"));
      assertTrue(defaults.length() == 1);
    }
  }
}
