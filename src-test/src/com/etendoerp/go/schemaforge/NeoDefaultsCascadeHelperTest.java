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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Unit tests for {@link NeoDefaultsCascadeHelper}.
 *
 * <p>Covers the callout cascade, interactive cascade, FK cleanup,
 * identifier propagation, safe type defaults, entity resolution,
 * and protected-field semantics.</p>
 */
public class NeoDefaultsCascadeHelperTest {

  // -------------------------------------------------------------------
  // Reflection helpers
  // -------------------------------------------------------------------

  private static Method getPrivateMethod(String methodName, Class<?>... paramTypes)
      throws Exception {
    Method m = NeoDefaultsCascadeHelper.class.getDeclaredMethod(methodName, paramTypes);
    m.setAccessible(true);
    return m;
  }

  private static Method mergeCalloutUpdatesMethod() throws Exception {
    return getPrivateMethod("mergeCalloutUpdates",
        JSONObject.class, JSONObject.class, JSONObject.class,
        Set.class, Tab.class,
        NeoDefaultsService.CalloutCascadeResult.class,
        Set.class, Set.class);
  }

  private static Method wouldClearExistingValueMethod() throws Exception {
    return getPrivateMethod("wouldClearExistingValue", Object.class, Object.class);
  }

  private static Method valueChangedMethod() throws Exception {
    return getPrivateMethod("valueChanged", Object.class, Object.class);
  }

  private static Method shouldKeepExistingValueMethod() throws Exception {
    return getPrivateMethod("shouldKeepExistingValue",
        JSONObject.class, String.class, Set.class);
  }

  private static Method mergeCalloutCombosMethod() throws Exception {
    return getPrivateMethod("mergeCalloutCombos",
        JSONObject.class, JSONObject.class, JSONObject.class,
        NeoDefaultsService.CalloutCascadeResult.class);
  }

  private static Method propagateIdentifierMethod() throws Exception {
    return getPrivateMethod("propagateIdentifier",
        JSONObject.class, JSONObject.class, String.class);
  }

  private static Method removeEmptyFkValueForColumnMethod() throws Exception {
    return getPrivateMethod("removeEmptyFkValueForColumn",
        JSONObject.class, Column.class, Entity.class);
  }

  private static Method collectFieldsWithCalloutsMethod() throws Exception {
    return getPrivateMethod("collectFieldsWithCallouts",
        JSONObject.class, Set.class, Tab.class);
  }

  private static Method collectCalloutPendingFieldsMethod() throws Exception {
    return getPrivateMethod("collectCalloutPendingFields",
        JSONObject.class, JSONObject.class, Set.class, Tab.class);
  }

  // -------------------------------------------------------------------
  // Helper: build a callout response body
  // -------------------------------------------------------------------

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
        null, result, nextPending, protectedFields);
  }

  private static Tab mockTabWithTable(String tableId) {
    Tab adTab = mock(Tab.class);
    Table table = mock(Table.class);
    when(adTab.getTable()).thenReturn(table);
    when(table.getId()).thenReturn(tableId);
    return adTab;
  }

  // ===================================================================
  // mergeCalloutUpdates — identifier propagation
  // ===================================================================

  @Test
  public void testIdentifierPropagatedWhenCalloutReturnsBoth() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      JSONObject defaults = new JSONObject();
      defaults.put("currency", "238");
      defaults.put("currency$_identifier", "AED");
      JSONObject formState = new JSONObject();
      formState.put("currency", "238");
      JSONObject calloutBody = calloutBodyWith("currency", "102", "EUR");

      invokeMerge(calloutBody, formState, defaults);

      assertEquals("102", defaults.get("currency"));
      assertEquals("EUR", defaults.get("currency$_identifier"));
    }
  }

  @Test
  public void testIdentifierPropagatedEvenWhenAbsentBefore() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      JSONObject defaults = new JSONObject();
      JSONObject formState = new JSONObject();
      JSONObject calloutBody = calloutBodyWith("currency", "102", "EUR");

      invokeMerge(calloutBody, formState, defaults);

      assertEquals("102", defaults.get("currency"));
      assertEquals("EUR", defaults.get("currency$_identifier"));
    }
  }

  @Test
  public void testExistingIdentifierPreservedWhenCalloutOmitsIt() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      JSONObject defaults = new JSONObject();
      defaults.put("currency", "238");
      defaults.put("currency$_identifier", "AED");
      JSONObject formState = new JSONObject();
      formState.put("currency", "238");
      JSONObject calloutBody = calloutBodyWith("currency", "102", null);

      invokeMerge(calloutBody, formState, defaults);

      assertEquals("102", defaults.get("currency"));
      assertEquals("AED", defaults.get("currency$_identifier"));
    }
  }

  @Test
  public void testNullIdentifierIsIgnored() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

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
      assertEquals("AED", defaults.get("currency$_identifier"));
    }
  }

  @Test
  public void testEmptyValueSkipsBothValueAndIdentifier() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      JSONObject defaults = new JSONObject();
      defaults.put("currency", "102");
      defaults.put("currency$_identifier", "EUR");
      JSONObject formState = new JSONObject();
      formState.put("currency", "102");
      JSONObject calloutBody = calloutBodyWith("currency", "", "SOMETHING");

      invokeMerge(calloutBody, formState, defaults);

      assertEquals("102", defaults.get("currency"));
      assertEquals("EUR", defaults.get("currency$_identifier"));
    }
  }

  @Test
  public void testNoUpdatesObjectIsNoOp() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      JSONObject defaults = new JSONObject();
      defaults.put("currency", "102");
      JSONObject formState = new JSONObject();
      JSONObject calloutBody = new JSONObject();

      invokeMerge(calloutBody, formState, defaults);

      assertEquals("102", defaults.get("currency"));
      assertFalse(defaults.has("currency$_identifier"));
      assertEquals(1, defaults.length());
    }
  }

  // ===================================================================
  // mergeCalloutUpdates — protected fields
  // ===================================================================

  @Test
  public void testProtectedFieldPreservesExistingValue() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      JSONObject defaults = new JSONObject();
      defaults.put("documentNo", "1000001");
      JSONObject formState = new JSONObject();
      formState.put("documentNo", "1000001");

      Set<String> protectedFields = new HashSet<>();
      protectedFields.add("documentNo");
      NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();

      JSONObject calloutBody = calloutBodyWith("documentNo", "OVERWRITE", null);

      mergeCalloutUpdatesMethod().invoke(null,
          calloutBody, formState, defaults, new HashSet<>(),
          null, result, new HashSet<>(), protectedFields);

      assertEquals("Protected field must not be overwritten",
          "1000001", defaults.get("documentNo"));
    }
  }

  @Test
  public void testProtectedFieldAllowsUpdateWhenCurrentIsNull() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      JSONObject defaults = new JSONObject();
      defaults.put("documentNo", JSONObject.NULL);
      JSONObject formState = new JSONObject();

      Set<String> protectedFields = new HashSet<>();
      protectedFields.add("documentNo");
      NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();

      JSONObject calloutBody = calloutBodyWith("documentNo", "NEW_VALUE", null);

      mergeCalloutUpdatesMethod().invoke(null,
          calloutBody, formState, defaults, new HashSet<>(),
          null, result, new HashSet<>(), protectedFields);

      assertEquals("Protected field with null current should accept update",
          "NEW_VALUE", defaults.get("documentNo"));
    }
  }

  @Test
  public void testProtectedFieldAllowsUpdateWhenCurrentIsEmptyString() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      JSONObject defaults = new JSONObject();
      defaults.put("documentNo", "   ");
      JSONObject formState = new JSONObject();

      Set<String> protectedFields = new HashSet<>();
      protectedFields.add("documentNo");
      NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();

      JSONObject calloutBody = calloutBodyWith("documentNo", "NEW_VALUE", null);

      mergeCalloutUpdatesMethod().invoke(null,
          calloutBody, formState, defaults, new HashSet<>(),
          null, result, new HashSet<>(), protectedFields);

      assertEquals("Protected field with blank string should accept update",
          "NEW_VALUE", defaults.get("documentNo"));
    }
  }

  // ===================================================================
  // mergeCalloutCombos
  // ===================================================================

  @Test
  public void testMergeCalloutCombosAppliesSelectedValue() throws Exception {
    JSONObject comboEntry = new JSONObject();
    comboEntry.put("selected", "VAL1");
    JSONObject combos = new JSONObject();
    combos.put("paymentMethod", comboEntry);
    JSONObject calloutBody = new JSONObject();
    calloutBody.put("combos", combos);

    JSONObject formState = new JSONObject();
    JSONObject defaults = new JSONObject();
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();

    mergeCalloutCombosMethod().invoke(null, calloutBody, formState, defaults, result);

    assertEquals("VAL1", defaults.get("paymentMethod"));
    assertEquals("VAL1", formState.get("paymentMethod"));
  }

  @Test
  public void testMergeCalloutCombosSkipsNullSelected() throws Exception {
    JSONObject comboEntry = new JSONObject();
    comboEntry.put("selected", JSONObject.NULL);
    JSONObject combos = new JSONObject();
    combos.put("paymentMethod", comboEntry);
    JSONObject calloutBody = new JSONObject();
    calloutBody.put("combos", combos);

    JSONObject formState = new JSONObject();
    JSONObject defaults = new JSONObject();
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();

    mergeCalloutCombosMethod().invoke(null, calloutBody, formState, defaults, result);

    assertFalse("Null selected should not be applied",
        defaults.has("paymentMethod"));
  }

  @Test
  public void testMergeCalloutCombosSkipsEntryWithoutSelected() throws Exception {
    JSONObject comboEntry = new JSONObject();
    comboEntry.put("entries", new JSONArray());
    JSONObject combos = new JSONObject();
    combos.put("paymentMethod", comboEntry);
    JSONObject calloutBody = new JSONObject();
    calloutBody.put("combos", combos);

    JSONObject formState = new JSONObject();
    JSONObject defaults = new JSONObject();
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();

    mergeCalloutCombosMethod().invoke(null, calloutBody, formState, defaults, result);

    assertFalse(defaults.has("paymentMethod"));
  }

  @Test
  public void testMergeCalloutCombosNullCombosIsNoOp() throws Exception {
    JSONObject calloutBody = new JSONObject();
    JSONObject formState = new JSONObject();
    JSONObject defaults = new JSONObject();
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();

    mergeCalloutCombosMethod().invoke(null, calloutBody, formState, defaults, result);

    assertEquals(0, defaults.length());
  }

  // ===================================================================
  // wouldClearExistingValue
  // ===================================================================

  @Test
  public void testWouldClearExistingValueReturnsTrueForEmptyOverPresent() throws Exception {
    Method m = wouldClearExistingValueMethod();

    assertTrue((Boolean) m.invoke(null, "", "existing"));
    assertTrue((Boolean) m.invoke(null, null, "existing"));
    assertTrue((Boolean) m.invoke(null, JSONObject.NULL, "existing"));
  }

  @Test
  public void testWouldClearExistingValueReturnsFalseWhenOldIsEmpty() throws Exception {
    Method m = wouldClearExistingValueMethod();

    assertFalse((Boolean) m.invoke(null, "", ""));
    assertFalse((Boolean) m.invoke(null, "", null));
    assertFalse((Boolean) m.invoke(null, null, null));
  }

  @Test
  public void testWouldClearExistingValueReturnsFalseWhenNewHasValue() throws Exception {
    Method m = wouldClearExistingValueMethod();

    assertFalse((Boolean) m.invoke(null, "newVal", "oldVal"));
    assertFalse((Boolean) m.invoke(null, "newVal", null));
  }

  // ===================================================================
  // valueChanged
  // ===================================================================

  @Test
  public void testValueChangedBothNull() throws Exception {
    assertFalse((Boolean) valueChangedMethod().invoke(null, (Object) null, (Object) null));
  }

  @Test
  public void testValueChangedOneNull() throws Exception {
    assertTrue((Boolean) valueChangedMethod().invoke(null, null, "val"));
    assertTrue((Boolean) valueChangedMethod().invoke(null, "val", null));
  }

  @Test
  public void testValueChangedSameStrings() throws Exception {
    assertFalse((Boolean) valueChangedMethod().invoke(null, "abc", "abc"));
  }

  @Test
  public void testValueChangedDifferentStrings() throws Exception {
    assertTrue((Boolean) valueChangedMethod().invoke(null, "abc", "xyz"));
  }

  @Test
  public void testValueChangedNumericComparison() throws Exception {
    assertFalse((Boolean) valueChangedMethod().invoke(null, 100, "100"));
  }

  // ===================================================================
  // shouldKeepExistingValue
  // ===================================================================

  @Test
  public void testShouldKeepExistingValueNullProtectedFields() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("field1", "val");
    assertFalse((Boolean) shouldKeepExistingValueMethod().invoke(null,
        defaults, "field1", null));
  }

  @Test
  public void testShouldKeepExistingValueFieldNotProtected() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("field1", "val");
    Set<String> protectedFields = new HashSet<>();
    protectedFields.add("otherField");
    assertFalse((Boolean) shouldKeepExistingValueMethod().invoke(null,
        defaults, "field1", protectedFields));
  }

  @Test
  public void testShouldKeepExistingValueProtectedWithValue() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("field1", "val");
    Set<String> protectedFields = new HashSet<>();
    protectedFields.add("field1");
    assertTrue((Boolean) shouldKeepExistingValueMethod().invoke(null,
        defaults, "field1", protectedFields));
  }

  @Test
  public void testShouldKeepExistingValueProtectedWithNull() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("field1", JSONObject.NULL);
    Set<String> protectedFields = new HashSet<>();
    protectedFields.add("field1");
    assertFalse((Boolean) shouldKeepExistingValueMethod().invoke(null,
        defaults, "field1", protectedFields));
  }

  @Test
  public void testShouldKeepExistingValueProtectedWithEmptyString() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("field1", "  ");
    Set<String> protectedFields = new HashSet<>();
    protectedFields.add("field1");
    assertFalse("Blank string should not count as a kept value",
        (Boolean) shouldKeepExistingValueMethod().invoke(null,
            defaults, "field1", protectedFields));
  }

  @Test
  public void testShouldKeepExistingValueProtectedWithNonStringObject() throws Exception {
    JSONObject defaults = new JSONObject();
    defaults.put("field1", 42);
    Set<String> protectedFields = new HashSet<>();
    protectedFields.add("field1");
    assertTrue("Non-string objects should be kept",
        (Boolean) shouldKeepExistingValueMethod().invoke(null,
            defaults, "field1", protectedFields));
  }

  // ===================================================================
  // propagateIdentifier
  // ===================================================================

  @Test
  public void testPropagateIdentifierSetsCompanionField() throws Exception {
    JSONObject updateObj = new JSONObject();
    updateObj.put("_identifier", "My Label");
    JSONObject defaults = new JSONObject();

    propagateIdentifierMethod().invoke(null, updateObj, defaults, "currency");

    assertEquals("My Label", defaults.get("currency$_identifier"));
  }

  @Test
  public void testPropagateIdentifierSkipsWhenNotPresent() throws Exception {
    JSONObject updateObj = new JSONObject();
    updateObj.put("value", "102");
    JSONObject defaults = new JSONObject();

    propagateIdentifierMethod().invoke(null, updateObj, defaults, "currency");

    assertFalse(defaults.has("currency$_identifier"));
  }

  @Test
  public void testPropagateIdentifierSkipsNullValue() throws Exception {
    JSONObject updateObj = new JSONObject();
    updateObj.put("_identifier", JSONObject.NULL);
    JSONObject defaults = new JSONObject();
    defaults.put("currency$_identifier", "OLD");

    propagateIdentifierMethod().invoke(null, updateObj, defaults, "currency");

    assertEquals("OLD", defaults.get("currency$_identifier"));
  }

  // ===================================================================
  // executeCalloutCascade
  // ===================================================================

  @Test
  public void testExecuteCalloutCascadeNoFieldsWithCallouts() {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      Tab adTab = mock(Tab.class);
      NeoContext ctx = mock(NeoContext.class);
      JSONObject defaults = new JSONObject();
      try {
        defaults.put("field1", "val1");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.executeCalloutCascade(
              ctx, adTab, defaults, new HashSet<>());

      assertNotNull(result);
      assertFalse("No fields with callouts means no results", result.hasResults());
      assertEquals(0, result.chainDepth);
      assertFalse(result.truncated);
    }
  }

  @Test
  public void testExecuteCalloutCascadeSkipsSeqFields() {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inpfield1", "Field1");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("field1")))
          .thenReturn(info);
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("field2")))
          .thenReturn(null);

      Tab adTab = mock(Tab.class);
      NeoContext ctx = mock(NeoContext.class);
      JSONObject defaults = new JSONObject();
      try {
        defaults.put("field1", "val1");
        defaults.put("field2", "val2");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      Set<String> seqFields = new HashSet<>();
      seqFields.add("field1");

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.executeCalloutCascade(
              ctx, adTab, defaults, seqFields);

      assertNotNull(result);
      assertFalse(result.hasResults());
    }
  }

  @Test
  public void testExecuteCalloutCascadeSkipsNullValues() {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inpfield1", "Field1");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("field1")))
          .thenReturn(info);

      Tab adTab = mock(Tab.class);
      NeoContext ctx = mock(NeoContext.class);
      JSONObject defaults = new JSONObject();
      try {
        defaults.put("field1", JSONObject.NULL);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.executeCalloutCascade(
              ctx, adTab, defaults, new HashSet<>());

      assertNotNull(result);
    }
  }

  @Test
  public void testExecuteCalloutCascadeSingleIteration() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<NeoSelectorService> selectorMock = mockStatic(NeoSelectorService.class);
         MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {

      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inpbusinessPartner", "C_BPartner_ID");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("businessPartner")))
          .thenReturn(info);
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("paymentMethod")))
          .thenReturn(null);

      JSONObject calloutResponseBody = new JSONObject();
      JSONObject updateEntry = new JSONObject();
      updateEntry.put("value", "PM001");
      JSONObject updates = new JSONObject();
      updates.put("paymentMethod", updateEntry);
      calloutResponseBody.put("updates", updates);
      NeoResponse calloutResponse = NeoResponse.ok(calloutResponseBody);
      calloutMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(calloutResponse);

      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);
      when(mockProvider.getEntityByTableId(anyString())).thenReturn(null);

      Tab adTab = mockTabWithTable("100");
      NeoContext ctx = mock(NeoContext.class);
      JSONObject defaults = new JSONObject();
      defaults.put("businessPartner", "BP001");

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.executeCalloutCascade(
              ctx, adTab, defaults, new HashSet<>());

      assertNotNull(result);
      assertTrue("Should have updates from the callout", result.hasResults());
      assertEquals(1, result.chainDepth);
      assertFalse(result.truncated);
    }
  }

  // ===================================================================
  // executeCalloutCascade — ETP-4531: accountingDate independence
  // ===================================================================

  /**
   * Regression test for the REVIEW blocker: the trigger-field-aware accountingDate guard
   * must hold on the GET /defaults cascade path (new-record form bootstrap via
   * {@code NeoDefaultsService#applyCascadeAndResolveTab}), not just on the interactive
   * {@code afterCallout()} hooks. Mirrors the live M_InOut.MovementDate ->
   * SL_InOut_AccountingDate coupling (same shape for C_Invoice.DateInvoiced ->
   * SifInvoiceOperationDateCallout).
   */
  @Test
  public void testExecuteCalloutCascadeBlocksAccountingDateFromMovementDateTrigger()
      throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {

      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "org.openbravo.erpCommon.ad_callouts.SL_InOut_AccountingDate",
          "inpmovementdate", "MovementDate");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("movementDate")))
          .thenReturn(info);
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("accountingDate")))
          .thenReturn(null);

      JSONObject calloutResponseBody = new JSONObject();
      JSONObject updateEntry = new JSONObject();
      updateEntry.put("value", "2026-01-01"); // callout tries to push movementDate's value
      JSONObject updates = new JSONObject();
      updates.put("accountingDate", updateEntry);
      calloutResponseBody.put("updates", updates);
      calloutMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(NeoResponse.ok(calloutResponseBody));

      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);
      when(mockProvider.getEntityByTableId(anyString())).thenReturn(null);

      Tab adTab = mockTabWithTable("150");
      NeoContext ctx = mock(NeoContext.class);
      JSONObject defaults = new JSONObject();
      defaults.put("movementDate", "2026-02-02");
      defaults.put("accountingDate", "2026-02-02"); // independently pre-resolved own default

      NeoDefaultsCascadeHelper.executeCalloutCascade(ctx, adTab, defaults, new HashSet<>());

      assertEquals("accountingDate must stay independent from movementDate on the "
              + "GET /defaults cascade",
          "2026-02-02", defaults.get("accountingDate"));
    }
  }

  @Test
  public void testExecuteCalloutCascadeKeepsAccountingDateWhenItIsTheTrigger() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {

      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "org.openbravo.erpCommon.ad_callouts.SE_Invoice_TaxDate",
          "inpdateacct", "DateAcct");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("accountingDate")))
          .thenReturn(info);

      JSONObject calloutResponseBody = new JSONObject();
      JSONObject updateEntry = new JSONObject();
      updateEntry.put("value", "2026-03-03");
      JSONObject updates = new JSONObject();
      updates.put("accountingDate", updateEntry);
      calloutResponseBody.put("updates", updates);
      calloutMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(NeoResponse.ok(calloutResponseBody));

      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);
      when(mockProvider.getEntityByTableId(anyString())).thenReturn(null);

      Tab adTab = mockTabWithTable("151");
      NeoContext ctx = mock(NeoContext.class);
      JSONObject defaults = new JSONObject();
      defaults.put("accountingDate", "2026-01-01");

      NeoDefaultsCascadeHelper.executeCalloutCascade(ctx, adTab, defaults, new HashSet<>());

      assertEquals("A callout triggered by accountingDate itself must still be able to "
              + "update accountingDate",
          "2026-03-03", defaults.get("accountingDate"));
    }
  }

  @Test
  public void testExecuteCalloutCascadeWithProtectedFields() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {

      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inpfield", "FieldCol");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("triggerField")))
          .thenReturn(info);
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("protectedField")))
          .thenReturn(null);

      JSONObject calloutResponseBody = new JSONObject();
      JSONObject updateEntry = new JSONObject();
      updateEntry.put("value", "OVERWRITTEN");
      JSONObject responseUpdates = new JSONObject();
      responseUpdates.put("protectedField", updateEntry);
      calloutResponseBody.put("updates", responseUpdates);
      NeoResponse calloutResponse = NeoResponse.ok(calloutResponseBody);
      calloutMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(calloutResponse);

      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);
      when(mockProvider.getEntityByTableId(anyString())).thenReturn(null);

      Tab adTab = mockTabWithTable("100");
      NeoContext ctx = mock(NeoContext.class);
      JSONObject defaults = new JSONObject();
      defaults.put("triggerField", "trigger_val");
      defaults.put("protectedField", "ORIGINAL");

      Set<String> protectedFields = new HashSet<>();
      protectedFields.add("protectedField");

      NeoDefaultsCascadeHelper.executeCalloutCascade(
              ctx, adTab, defaults, new HashSet<>(), protectedFields);

      assertEquals("Protected field must keep its original value",
          "ORIGINAL", defaults.get("protectedField"));
    }
  }

  @Test
  public void testExecuteCalloutCascadeOverloadWithoutProtectedFields() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      Tab adTab = mock(Tab.class);
      NeoContext ctx = mock(NeoContext.class);
      JSONObject defaults = new JSONObject();
      defaults.put("field1", "val1");

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.executeCalloutCascade(
              ctx, adTab, defaults, new HashSet<>());

      assertNotNull(result);
      assertFalse(result.hasResults());
    }
  }

  @Test
  public void testExecuteCalloutCascadeCalloutReturnsNon200() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {

      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inpfield1", "Field1");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("field1")))
          .thenReturn(info);

      NeoResponse errorResponse = NeoResponse.error(500, "Server error");
      calloutMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(errorResponse);

      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);
      when(mockProvider.getEntityByTableId(anyString())).thenReturn(null);

      Tab adTab = mockTabWithTable("100");
      NeoContext ctx = mock(NeoContext.class);
      JSONObject defaults = new JSONObject();
      defaults.put("field1", "val");

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.executeCalloutCascade(
              ctx, adTab, defaults, new HashSet<>());

      assertNotNull(result);
      assertEquals(1, result.chainDepth);
    }
  }

  @Test
  public void testExecuteCalloutCascadeCalloutReturnsNull() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {

      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inpfield1", "Field1");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("field1")))
          .thenReturn(info);

      calloutMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(null);

      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);
      when(mockProvider.getEntityByTableId(anyString())).thenReturn(null);

      Tab adTab = mockTabWithTable("100");
      NeoContext ctx = mock(NeoContext.class);
      JSONObject defaults = new JSONObject();
      defaults.put("field1", "val");

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.executeCalloutCascade(
              ctx, adTab, defaults, new HashSet<>());

      assertNotNull(result);
      assertEquals(1, result.chainDepth);
    }
  }

  @Test
  public void testExecuteCalloutCascadeWithMessages() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {

      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inpfield1", "Field1");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("field1")))
          .thenReturn(info);

      JSONObject calloutResponseBody = new JSONObject();
      calloutResponseBody.put("updates", new JSONObject());
      JSONArray messages = new JSONArray();
      JSONObject msg = new JSONObject();
      msg.put("type", "WARNING");
      msg.put("text", "Test warning");
      messages.put(msg);
      calloutResponseBody.put("messages", messages);
      NeoResponse calloutResponse = NeoResponse.ok(calloutResponseBody);
      calloutMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(calloutResponse);

      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);
      when(mockProvider.getEntityByTableId(anyString())).thenReturn(null);

      Tab adTab = mockTabWithTable("100");
      NeoContext ctx = mock(NeoContext.class);
      JSONObject defaults = new JSONObject();
      defaults.put("field1", "val");

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.executeCalloutCascade(
              ctx, adTab, defaults, new HashSet<>());

      assertNotNull(result);
      assertTrue("Messages should be merged", result.hasResults());
    }
  }

  // ===================================================================
  // cascadeInteractiveCallout
  // ===================================================================

  @Test
  public void testCascadeInteractiveCalloutNullCtx() {
    NeoDefaultsService.CalloutCascadeResult result =
        NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
            null, mock(Tab.class), "field", new JSONObject(), new JSONObject());
    assertNotNull(result);
    assertFalse(result.hasResults());
  }

  @Test
  public void testCascadeInteractiveCalloutNullTab() {
    NeoDefaultsService.CalloutCascadeResult result =
        NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
            mock(NeoContext.class), null, "field", new JSONObject(), new JSONObject());
    assertNotNull(result);
    assertFalse(result.hasResults());
  }

  @Test
  public void testCascadeInteractiveCalloutNullResponse() {
    NeoDefaultsService.CalloutCascadeResult result =
        NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
            mock(NeoContext.class), mock(Tab.class), "field", new JSONObject(), null);
    assertNotNull(result);
    assertFalse(result.hasResults());
  }

  @Test
  public void testCascadeInteractiveCalloutNullOriginalFormState() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      JSONObject calloutResponse = new JSONObject();
      JSONObject updates = new JSONObject();
      JSONObject updateEntry = new JSONObject();
      updateEntry.put("value", "newVal");
      updates.put("someField", updateEntry);
      calloutResponse.put("updates", updates);

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
              mock(NeoContext.class), mock(Tab.class), "trigger",
              null, calloutResponse);

      assertNotNull(result);
      assertFalse(result.hasResults());
    }
  }

  @Test
  public void testCascadeInteractiveCalloutNoUpdatesInResponse() throws Exception {
    JSONObject calloutResponse = new JSONObject();

    NeoDefaultsService.CalloutCascadeResult result =
        NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
            mock(NeoContext.class), mock(Tab.class), "trigger",
            new JSONObject(), calloutResponse);

    assertNotNull(result);
    assertFalse(result.hasResults());
  }

  @Test
  public void testCascadeInteractiveCalloutSkipsTriggerField() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inptrigger", "Trigger");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("trigger")))
          .thenReturn(info);
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("otherField")))
          .thenReturn(null);

      JSONObject calloutResponse = new JSONObject();
      JSONObject updates = new JSONObject();
      JSONObject triggerUpdate = new JSONObject();
      triggerUpdate.put("value", "newTriggerVal");
      updates.put("trigger", triggerUpdate);
      JSONObject otherUpdate = new JSONObject();
      otherUpdate.put("value", "otherVal");
      updates.put("otherField", otherUpdate);
      calloutResponse.put("updates", updates);

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
              mock(NeoContext.class), mock(Tab.class), "trigger",
              new JSONObject(), calloutResponse);

      assertNotNull(result);
      assertFalse(result.hasResults());
    }
  }

  @Test
  public void testCascadeInteractiveCalloutSkipsEmptyValues() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      JSONObject calloutResponse = new JSONObject();
      JSONObject updates = new JSONObject();
      JSONObject emptyUpdate = new JSONObject();
      emptyUpdate.put("value", "");
      updates.put("someField", emptyUpdate);
      JSONObject nullUpdate = new JSONObject();
      nullUpdate.put("value", JSONObject.NULL);
      updates.put("anotherField", nullUpdate);
      calloutResponse.put("updates", updates);

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
              mock(NeoContext.class), mock(Tab.class), "trigger",
              new JSONObject(), calloutResponse);

      assertNotNull(result);
      assertFalse(result.hasResults());
    }
  }

  @Test
  public void testCascadeInteractiveCalloutWithCascade() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {

      NeoCalloutService.CalloutInfo cascadeInfo = new NeoCalloutService.CalloutInfo(
          "com.example.CascadeCallout", "inpcascadeField", "CascadeField");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("cascadeField")))
          .thenReturn(cascadeInfo);
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("resultField")))
          .thenReturn(null);

      JSONObject initialResponse = new JSONObject();
      JSONObject initialUpdates = new JSONObject();
      JSONObject cascadeUpdate = new JSONObject();
      cascadeUpdate.put("value", "cascadeVal");
      initialUpdates.put("cascadeField", cascadeUpdate);
      initialResponse.put("updates", initialUpdates);

      JSONObject cascadeResponseBody = new JSONObject();
      JSONObject cascadeUpdates = new JSONObject();
      JSONObject resultUpdate = new JSONObject();
      resultUpdate.put("value", "finalResult");
      cascadeUpdates.put("resultField", resultUpdate);
      cascadeResponseBody.put("updates", cascadeUpdates);
      NeoResponse cascadeCalloutResponse = NeoResponse.ok(cascadeResponseBody);
      calloutMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(cascadeCalloutResponse);

      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);
      when(mockProvider.getEntityByTableId(anyString())).thenReturn(null);

      Tab adTab = mockTabWithTable("200");
      NeoContext ctx = mock(NeoContext.class);

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
              ctx, adTab, "trigger", new JSONObject(), initialResponse);

      assertNotNull(result);
      assertTrue("Should have cascade results", result.hasResults());
    }
  }

  // ===================================================================
  // executeCalloutCascadeForCreate
  // ===================================================================

  @Test
  public void testExecuteCalloutCascadeForCreatePopulatesProtectedFields() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      Tab adTab = mock(Tab.class);
      NeoContext ctx = mock(NeoContext.class);
      JSONObject body = new JSONObject();
      body.put("documentNo", "DOC-001");
      body.put("businessPartner", "BP001");

      NeoDefaultsCascadeHelper.executeCalloutCascadeForCreate(ctx, adTab, body);
    }
  }

  @Test
  public void testExecuteCalloutCascadeForCreateEmptyBody() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      Tab adTab = mock(Tab.class);
      NeoContext ctx = mock(NeoContext.class);
      JSONObject body = new JSONObject();

      NeoDefaultsCascadeHelper.executeCalloutCascadeForCreate(ctx, adTab, body);
    }
  }

  @Test
  public void testExecuteCalloutCascadeForCreateHandlesException() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenThrow(new RuntimeException("Simulated error"));

      Tab adTab = mock(Tab.class);
      NeoContext ctx = mock(NeoContext.class);
      JSONObject body = new JSONObject();
      body.put("field1", "val1");

      NeoDefaultsCascadeHelper.executeCalloutCascadeForCreate(ctx, adTab, body);
    }
  }

  /**
   * Regression test for the REVIEW blocker: the trigger-field-aware accountingDate guard
   * must also hold on POST create ({@code executeCalloutCascadeForCreate}). The pre-existing
   * "protectedFields = keys already in body" mechanism only protects a field if it was
   * ALREADY a key in the create payload before the cascade ran — it gives no protection when
   * accountingDate is absent from the payload and gets introduced purely as a callout side
   * effect of movementDate/invoiceDate, which is exactly this scenario.
   */
  @Test
  public void testExecuteCalloutCascadeForCreateBlocksAccountingDateFromMovementDate()
      throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {

      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "org.openbravo.erpCommon.ad_callouts.SL_InOut_AccountingDate",
          "inpmovementdate", "MovementDate");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("movementDate")))
          .thenReturn(info);
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("accountingDate")))
          .thenReturn(null);

      JSONObject calloutResponseBody = new JSONObject();
      JSONObject updateEntry = new JSONObject();
      updateEntry.put("value", "2026-03-03");
      JSONObject updates = new JSONObject();
      updates.put("accountingDate", updateEntry);
      calloutResponseBody.put("updates", updates);
      calloutMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(NeoResponse.ok(calloutResponseBody));

      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);
      when(mockProvider.getEntityByTableId(anyString())).thenReturn(null);

      Tab adTab = mockTabWithTable("152");
      NeoContext ctx = mock(NeoContext.class);
      JSONObject body = new JSONObject();
      // accountingDate is NOT yet a key in the create payload — it must not be introduced
      // by movementDate's callout side effect.
      body.put("movementDate", "2026-03-03");

      NeoDefaultsCascadeHelper.executeCalloutCascadeForCreate(ctx, adTab, body);

      assertFalse("Callout-driven accountingDate must never be introduced on create by "
              + "movementDate's callout",
          body.has("accountingDate"));
    }
  }

  // ===================================================================
  // collectFieldsWithCallouts
  // ===================================================================

  @Test
  @SuppressWarnings("unchecked")
  public void testCollectFieldsWithCalloutsFiltersCorrectly() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      Tab adTab = mock(Tab.class);
      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inpf1", "F1");

      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("field1")))
          .thenReturn(info);
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("field2")))
          .thenReturn(null);
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("field3")))
          .thenReturn(info);

      JSONObject defaults = new JSONObject();
      defaults.put("field1", "val1");
      defaults.put("field2", "val2");
      defaults.put("field3", JSONObject.NULL);

      Set<String> seqFields = new HashSet<>();

      List<String> result = (List<String>) collectFieldsWithCalloutsMethod()
          .invoke(null, defaults, seqFields, adTab);

      assertTrue("field1 has callout and value", result.contains("field1"));
      assertFalse("field2 has no callout", result.contains("field2"));
      assertFalse("field3 has null value", result.contains("field3"));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCollectFieldsWithCalloutsExcludesSeqFields() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      Tab adTab = mock(Tab.class);
      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inpf1", "F1");

      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("seqField")))
          .thenReturn(info);

      JSONObject defaults = new JSONObject();
      defaults.put("seqField", "SEQ001");

      Set<String> seqFields = new HashSet<>();
      seqFields.add("seqField");

      List<String> result = (List<String>) collectFieldsWithCalloutsMethod()
          .invoke(null, defaults, seqFields, adTab);

      assertFalse("Seq fields must be excluded", result.contains("seqField"));
    }
  }

  // ===================================================================
  // collectCalloutPendingFields
  // ===================================================================

  @Test
  @SuppressWarnings("unchecked")
  public void testCollectCalloutPendingFieldsWithUpdates() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      Tab adTab = mock(Tab.class);
      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inpf", "F");

      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("fieldA")))
          .thenReturn(info);
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("fieldB")))
          .thenReturn(null);

      JSONObject calloutResponse = new JSONObject();
      JSONObject updates = new JSONObject();
      JSONObject entryA = new JSONObject();
      entryA.put("value", "valA");
      updates.put("fieldA", entryA);
      JSONObject entryB = new JSONObject();
      entryB.put("value", "valB");
      updates.put("fieldB", entryB);
      calloutResponse.put("updates", updates);

      JSONObject cascadeFormState = new JSONObject();
      Set<String> skipFields = new HashSet<>();

      Set<String> result = (Set<String>) collectCalloutPendingFieldsMethod()
          .invoke(null, calloutResponse, cascadeFormState, skipFields, adTab);

      assertTrue("fieldA has a callout", result.contains("fieldA"));
      assertFalse("fieldB has no callout", result.contains("fieldB"));
      assertEquals("cascadeFormState should be updated", "valA",
          cascadeFormState.get("fieldA"));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCollectCalloutPendingFieldsSkipsEmptyValues() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      Tab adTab = mock(Tab.class);
      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inpf", "F");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(info);

      JSONObject calloutResponse = new JSONObject();
      JSONObject updates = new JSONObject();
      JSONObject emptyEntry = new JSONObject();
      emptyEntry.put("value", "");
      updates.put("emptyField", emptyEntry);
      JSONObject nullEntry = new JSONObject();
      nullEntry.put("value", JSONObject.NULL);
      updates.put("nullField", nullEntry);
      calloutResponse.put("updates", updates);

      JSONObject cascadeFormState = new JSONObject();
      Set<String> skipFields = new HashSet<>();

      Set<String> result = (Set<String>) collectCalloutPendingFieldsMethod()
          .invoke(null, calloutResponse, cascadeFormState, skipFields, adTab);

      assertTrue("Empty and null values should be skipped", result.isEmpty());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCollectCalloutPendingFieldsSkipsSkipFields() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      Tab adTab = mock(Tab.class);
      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inpf", "F");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(info);

      JSONObject calloutResponse = new JSONObject();
      JSONObject updates = new JSONObject();
      JSONObject entry = new JSONObject();
      entry.put("value", "someVal");
      updates.put("skipMe", entry);
      calloutResponse.put("updates", updates);

      JSONObject cascadeFormState = new JSONObject();
      Set<String> skipFields = new HashSet<>();
      skipFields.add("skipMe");

      Set<String> result = (Set<String>) collectCalloutPendingFieldsMethod()
          .invoke(null, calloutResponse, cascadeFormState, skipFields, adTab);

      assertFalse("Skip fields should be excluded", result.contains("skipMe"));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCollectCalloutPendingFieldsNoUpdatesKey() throws Exception {
    JSONObject calloutResponse = new JSONObject();
    JSONObject cascadeFormState = new JSONObject();
    Set<String> skipFields = new HashSet<>();
    Tab adTab = mock(Tab.class);

    Set<String> result = (Set<String>) collectCalloutPendingFieldsMethod()
        .invoke(null, calloutResponse, cascadeFormState, skipFields, adTab);

    assertTrue("No updates key should return empty set", result.isEmpty());
  }

  // ===================================================================
  // resolveDalEntity
  // ===================================================================

  @Test
  public void testResolveDalEntityWithNullTab() {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getADTab()).thenReturn(null);

    Entity result = NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity);
    assertNull(result);
  }

  @Test
  public void testResolveDalEntityWithNullTable() {
    SFEntity sfEntity = mock(SFEntity.class);
    Tab adTab = mock(Tab.class);
    when(sfEntity.getADTab()).thenReturn(adTab);
    when(adTab.getTable()).thenReturn(null);

    Entity result = NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity);
    assertNull(result);
  }

  @Test
  public void testResolveDalEntitySuccess() {
    try (MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {
      SFEntity sfEntity = mock(SFEntity.class);
      Tab adTab = mock(Tab.class);
      Table table = mock(Table.class);
      when(sfEntity.getADTab()).thenReturn(adTab);
      when(adTab.getTable()).thenReturn(table);
      when(table.getId()).thenReturn("123");

      Entity mockEntity = mock(Entity.class);
      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);
      when(mockProvider.getEntityByTableId("123")).thenReturn(mockEntity);

      Entity result = NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity);
      assertEquals(mockEntity, result);
    }
  }

  @Test
  public void testResolveDalEntityHandlesException() {
    SFEntity sfEntity = mock(SFEntity.class);
    when(sfEntity.getADTab()).thenThrow(new RuntimeException("Simulated"));

    Entity result = NeoDefaultsCascadeHelper.resolveDalEntity(sfEntity);
    assertNull(result);
  }

  // ===================================================================
  // resolvePropertyName
  // ===================================================================

  @Test
  public void testResolvePropertyNameWithEntity() {
    Entity entity = mock(Entity.class);
    Property prop = mock(Property.class);
    when(entity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop);
    when(prop.getName()).thenReturn("businessPartner");

    String result = NeoDefaultsCascadeHelper.resolvePropertyName(entity, "C_BPartner_ID");
    assertEquals("businessPartner", result);
  }

  @Test
  public void testResolvePropertyNameWithNullEntity() {
    String result = NeoDefaultsCascadeHelper.resolvePropertyName(null, "C_BPartner_ID");
    assertEquals(NeoCalloutService.toCleanFieldName("C_BPartner_ID"), result);
  }

  @Test
  public void testResolvePropertyNamePropertyNotFound() {
    Entity entity = mock(Entity.class);
    when(entity.getPropertyByColumnName("Unknown_Col"))
        .thenThrow(new RuntimeException("No property"));

    String result = NeoDefaultsCascadeHelper.resolvePropertyName(entity, "Unknown_Col");
    assertEquals(NeoCalloutService.toCleanFieldName("Unknown_Col"), result);
  }

  @Test
  public void testResolvePropertyNameNullProperty() {
    Entity entity = mock(Entity.class);
    when(entity.getPropertyByColumnName("Some_Col")).thenReturn(null);

    String result = NeoDefaultsCascadeHelper.resolvePropertyName(entity, "Some_Col");
    assertEquals(NeoCalloutService.toCleanFieldName("Some_Col"), result);
  }

  // ===================================================================
  // injectSafeTypeDefault
  // ===================================================================

  @Test
  public void testInjectSafeTypeDefaultNumericRef22() throws Exception {
    JSONObject body = new JSONObject();
    Column col = mock(Column.class);
    Reference ref = mock(Reference.class);
    when(col.getReference()).thenReturn(ref);
    when(ref.getId()).thenReturn("22");

    NeoDefaultsCascadeHelper.injectSafeTypeDefault(body, "amount", col);

    assertEquals(0, body.get("amount"));
  }

  @Test
  public void testInjectSafeTypeDefaultNumericRef29() throws Exception {
    JSONObject body = new JSONObject();
    Column col = mock(Column.class);
    Reference ref = mock(Reference.class);
    when(col.getReference()).thenReturn(ref);
    when(ref.getId()).thenReturn("29");

    NeoDefaultsCascadeHelper.injectSafeTypeDefault(body, "qty", col);

    assertEquals(0, body.get("qty"));
  }

  @Test
  public void testInjectSafeTypeDefaultNumericRef12() throws Exception {
    JSONObject body = new JSONObject();
    Column col = mock(Column.class);
    Reference ref = mock(Reference.class);
    when(col.getReference()).thenReturn(ref);
    when(ref.getId()).thenReturn("12");

    NeoDefaultsCascadeHelper.injectSafeTypeDefault(body, "price", col);

    assertEquals(0, body.get("price"));
  }

  @Test
  public void testInjectSafeTypeDefaultNumericRef11() throws Exception {
    JSONObject body = new JSONObject();
    Column col = mock(Column.class);
    Reference ref = mock(Reference.class);
    when(col.getReference()).thenReturn(ref);
    when(ref.getId()).thenReturn("11");

    NeoDefaultsCascadeHelper.injectSafeTypeDefault(body, "intField", col);

    assertEquals(0, body.get("intField"));
  }

  @Test
  public void testInjectSafeTypeDefaultBooleanRef20() throws Exception {
    JSONObject body = new JSONObject();
    Column col = mock(Column.class);
    Reference ref = mock(Reference.class);
    when(col.getReference()).thenReturn(ref);
    when(ref.getId()).thenReturn("20");

    NeoDefaultsCascadeHelper.injectSafeTypeDefault(body, "active", col);

    assertEquals(false, body.get("active"));
  }

  @Test
  public void testInjectSafeTypeDefaultUnknownRef() throws Exception {
    JSONObject body = new JSONObject();
    Column col = mock(Column.class);
    Reference ref = mock(Reference.class);
    when(col.getReference()).thenReturn(ref);
    when(ref.getId()).thenReturn("99");

    NeoDefaultsCascadeHelper.injectSafeTypeDefault(body, "field", col);

    assertFalse("Unknown reference should not inject anything",
        body.has("field"));
  }

  @Test
  public void testInjectSafeTypeDefaultNullReference() throws Exception {
    JSONObject body = new JSONObject();
    Column col = mock(Column.class);
    when(col.getReference()).thenReturn(null);

    NeoDefaultsCascadeHelper.injectSafeTypeDefault(body, "field", col);

    assertFalse("Null reference should not inject anything",
        body.has("field"));
  }

  // ===================================================================
  // removeEmptyFkValues
  // ===================================================================

  @Test
  public void testRemoveEmptyFkValuesNullBody() {
    NeoDefaultsCascadeHelper.removeEmptyFkValues(null, mock(Tab.class));
  }

  @Test
  public void testRemoveEmptyFkValuesNullTab() {
    NeoDefaultsCascadeHelper.removeEmptyFkValues(new JSONObject(), null);
  }

  @Test
  public void testRemoveEmptyFkValuesNullTable() {
    Tab adTab = mock(Tab.class);
    when(adTab.getTable()).thenReturn(null);
    NeoDefaultsCascadeHelper.removeEmptyFkValues(new JSONObject(), adTab);
  }

  @Test
  public void testRemoveEmptyFkValuesNullEntity() {
    try (MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {
      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);
      when(mockProvider.getEntityByTableId(anyString())).thenReturn(null);

      Tab adTab = mockTabWithTable("100");
      Table table = adTab.getTable();
      when(table.getADColumnList()).thenReturn(Collections.emptyList());

      NeoDefaultsCascadeHelper.removeEmptyFkValues(new JSONObject(), adTab);
    }
  }

  @Test
  public void testRemoveEmptyFkValuesRemovesEmptyStringFK() throws Exception {
    try (MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {
      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);

      Entity dalEntity = mock(Entity.class);
      when(mockProvider.getEntityByTableId("100")).thenReturn(dalEntity);

      Property prop = mock(Property.class);
      when(prop.getName()).thenReturn("businessPartner");
      when(dalEntity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop);

      Column col = mock(Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.isMandatory()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("C_BPartner_ID");

      List<Column> columns = new ArrayList<>();
      columns.add(col);

      Tab adTab = mockTabWithTable("100");
      Table table = adTab.getTable();
      when(table.getADColumnList()).thenReturn(columns);

      JSONObject body = new JSONObject();
      body.put("businessPartner", "  ");

      NeoDefaultsCascadeHelper.removeEmptyFkValues(body, adTab);

      assertFalse("Empty FK value should be removed", body.has("businessPartner"));
    }
  }

  @Test
  public void testRemoveEmptyFkValuesKeepsNonEmptyFK() throws Exception {
    try (MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {
      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);

      Entity dalEntity = mock(Entity.class);
      when(mockProvider.getEntityByTableId("100")).thenReturn(dalEntity);

      Property prop = mock(Property.class);
      when(prop.getName()).thenReturn("businessPartner");
      when(dalEntity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop);

      Column col = mock(Column.class);
      when(col.isActive()).thenReturn(true);
      when(col.isMandatory()).thenReturn(true);
      when(col.getDBColumnName()).thenReturn("C_BPartner_ID");

      List<Column> columns = new ArrayList<>();
      columns.add(col);

      Tab adTab = mockTabWithTable("100");
      Table table = adTab.getTable();
      when(table.getADColumnList()).thenReturn(columns);

      JSONObject body = new JSONObject();
      body.put("businessPartner", "VALID_ID_123");

      NeoDefaultsCascadeHelper.removeEmptyFkValues(body, adTab);

      assertTrue("Non-empty FK value should be kept", body.has("businessPartner"));
      assertEquals("VALID_ID_123", body.get("businessPartner"));
    }
  }

  // ===================================================================
  // removeEmptyFkValueForColumn
  // ===================================================================

  @Test
  public void testRemoveEmptyFkValueForColumnSkipsInactive() throws Exception {
    Column col = mock(Column.class);
    when(col.isActive()).thenReturn(false);
    when(col.isMandatory()).thenReturn(true);

    JSONObject body = new JSONObject();
    body.put("field", " ");
    Entity dalEntity = mock(Entity.class);

    removeEmptyFkValueForColumnMethod().invoke(null, body, col, dalEntity);

    assertTrue("Inactive column should be skipped", body.has("field"));
  }

  @Test
  public void testRemoveEmptyFkValueForColumnSkipsNonMandatory() throws Exception {
    Column col = mock(Column.class);
    when(col.isActive()).thenReturn(true);
    when(col.isMandatory()).thenReturn(false);

    JSONObject body = new JSONObject();
    body.put("field", " ");
    Entity dalEntity = mock(Entity.class);

    removeEmptyFkValueForColumnMethod().invoke(null, body, col, dalEntity);

    assertTrue("Non-mandatory column should be skipped", body.has("field"));
  }

  @Test
  public void testRemoveEmptyFkValueForColumnSkipsNonIdColumn() throws Exception {
    Column col = mock(Column.class);
    when(col.isActive()).thenReturn(true);
    when(col.isMandatory()).thenReturn(true);
    when(col.getDBColumnName()).thenReturn("DocumentNo");

    JSONObject body = new JSONObject();
    body.put("documentNo", " ");
    Entity dalEntity = mock(Entity.class);

    removeEmptyFkValueForColumnMethod().invoke(null, body, col, dalEntity);

    assertTrue("Non-_ID column should be skipped", body.has("documentNo"));
  }

  @Test
  public void testRemoveEmptyFkValueForColumnSkipsWhenPropertyNotInBody() throws Exception {
    Column col = mock(Column.class);
    when(col.isActive()).thenReturn(true);
    when(col.isMandatory()).thenReturn(true);
    when(col.getDBColumnName()).thenReturn("C_BPartner_ID");

    Property prop = mock(Property.class);
    when(prop.getName()).thenReturn("businessPartner");
    Entity dalEntity = mock(Entity.class);
    when(dalEntity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop);

    JSONObject body = new JSONObject();

    removeEmptyFkValueForColumnMethod().invoke(null, body, col, dalEntity);

    assertEquals(0, body.length());
  }

  @Test
  public void testRemoveEmptyFkValueForColumnSkipsNullProperty() throws Exception {
    Column col = mock(Column.class);
    when(col.isActive()).thenReturn(true);
    when(col.isMandatory()).thenReturn(true);
    when(col.getDBColumnName()).thenReturn("C_BPartner_ID");

    Entity dalEntity = mock(Entity.class);
    when(dalEntity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(null);

    JSONObject body = new JSONObject();
    body.put("businessPartner", " ");

    removeEmptyFkValueForColumnMethod().invoke(null, body, col, dalEntity);

    assertTrue("Null property should be skipped", body.has("businessPartner"));
  }

  @Test
  public void testRemoveEmptyFkValueForColumnKeepsNonStringValue() throws Exception {
    Column col = mock(Column.class);
    when(col.isActive()).thenReturn(true);
    when(col.isMandatory()).thenReturn(true);
    when(col.getDBColumnName()).thenReturn("C_BPartner_ID");

    Property prop = mock(Property.class);
    when(prop.getName()).thenReturn("businessPartner");
    Entity dalEntity = mock(Entity.class);
    when(dalEntity.getPropertyByColumnName("C_BPartner_ID")).thenReturn(prop);

    JSONObject body = new JSONObject();
    body.put("businessPartner", 12345);

    removeEmptyFkValueForColumnMethod().invoke(null, body, col, dalEntity);

    assertTrue("Non-string value should be kept", body.has("businessPartner"));
    assertEquals(12345, body.get("businessPartner"));
  }

  // ===================================================================
  // Multiple updates in mergeCalloutUpdates
  // ===================================================================

  @Test
  public void testMergeCalloutUpdatesMultipleFields() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      JSONObject defaults = new JSONObject();
      JSONObject formState = new JSONObject();

      JSONObject calloutBody = new JSONObject();
      JSONObject updates = new JSONObject();

      JSONObject entry1 = new JSONObject();
      entry1.put("value", "val1");
      entry1.put("_identifier", "Label 1");
      updates.put("field1", entry1);

      JSONObject entry2 = new JSONObject();
      entry2.put("value", "val2");
      updates.put("field2", entry2);

      calloutBody.put("updates", updates);

      invokeMerge(calloutBody, formState, defaults);

      assertEquals("val1", defaults.get("field1"));
      assertEquals("Label 1", defaults.get("field1$_identifier"));
      assertEquals("val2", defaults.get("field2"));
      assertFalse(defaults.has("field2$_identifier"));
    }
  }

  @Test
  public void testMergeCalloutUpdatesSkipsEntryWithoutValue() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      JSONObject defaults = new JSONObject();
      JSONObject formState = new JSONObject();

      JSONObject calloutBody = new JSONObject();
      JSONObject updates = new JSONObject();
      JSONObject entryNoValue = new JSONObject();
      entryNoValue.put("_identifier", "Label");
      updates.put("field1", entryNoValue);
      calloutBody.put("updates", updates);

      invokeMerge(calloutBody, formState, defaults);

      assertFalse("Entry without value key should be skipped",
          defaults.has("field1"));
    }
  }

  @Test
  public void testMergeCalloutUpdatesSkipsNullUpdateObject() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class)) {
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(null);

      JSONObject defaults = new JSONObject();
      JSONObject formState = new JSONObject();

      JSONObject calloutBody = new JSONObject();
      JSONObject updates = new JSONObject();
      updates.put("field1", "not_a_json_object");
      calloutBody.put("updates", updates);

      invokeMerge(calloutBody, formState, defaults);

      assertFalse("Non-JSONObject update entry should be skipped",
          defaults.has("field1"));
    }
  }

  // ===================================================================
  // Cascade depth limit
  // ===================================================================

  @Test
  public void testExecuteCalloutCascadeMaxDepthReached() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {

      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.InfiniteCallout", "inpfield", "Field");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), anyString()))
          .thenReturn(info);

      calloutMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenAnswer(invocation -> {
            JSONObject responseBody = new JSONObject();
            JSONObject responseUpdates = new JSONObject();
            JSONObject entry = new JSONObject();
            entry.put("value", "cascaded_" + System.nanoTime());
            responseUpdates.put("cascadeField", entry);
            responseBody.put("updates", responseUpdates);
            return NeoResponse.ok(responseBody);
          });

      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);
      when(mockProvider.getEntityByTableId(anyString())).thenReturn(null);

      Tab adTab = mockTabWithTable("100");
      NeoContext ctx = mock(NeoContext.class);
      JSONObject defaults = new JSONObject();
      defaults.put("startField", "initial");

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.executeCalloutCascade(
              ctx, adTab, defaults, new HashSet<>());

      assertNotNull(result);
      assertEquals("Should reach max depth of 5", 5, result.chainDepth);
      assertTrue("Should be truncated", result.truncated);
    }
  }

  // ===================================================================
  // CalloutCascadeResult aggregation
  // ===================================================================

  @Test
  public void testCalloutCascadeResultHasResults() throws Exception {
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();
    assertFalse("Fresh result should have no results", result.hasResults());

    JSONObject updates = new JSONObject();
    updates.put("field1", new JSONObject().put("value", "x"));
    result.mergeUpdates(updates);
    assertTrue("After merging updates, should have results", result.hasResults());
  }

  @Test
  public void testCalloutCascadeResultUpdatedFieldCount() throws Exception {
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();
    assertEquals(0, result.updatedFieldCount());

    JSONObject updates = new JSONObject();
    updates.put("f1", "v1");
    updates.put("f2", "v2");
    result.mergeUpdates(updates);
    assertEquals(2, result.updatedFieldCount());
  }

  @Test
  public void testCalloutCascadeResultMergeMessages() throws Exception {
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();
    JSONArray messages = new JSONArray();
    messages.put(new JSONObject().put("type", "WARNING").put("text", "warn1"));
    messages.put(new JSONObject().put("type", "ERROR").put("text", "err1"));
    result.mergeMessages(messages);
    assertTrue(result.hasResults());
  }

  @Test
  public void testCalloutCascadeResultMergeCombos() throws Exception {
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();
    JSONObject combos = new JSONObject();
    combos.put("paymentMethod", new JSONObject().put("selected", "PM1"));
    result.mergeCombos(combos);
    assertTrue(result.hasResults());
  }

  @Test
  public void testCalloutCascadeResultToJSON() throws Exception {
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();
    JSONObject updates = new JSONObject();
    updates.put("f1", "v1");
    result.mergeUpdates(updates);

    JSONObject json = result.toJSON();
    assertNotNull(json);
    assertTrue(json.has("updates"));
    assertTrue(json.has("combos"));
    assertTrue(json.has("messages"));
  }

  @Test
  public void testCalloutCascadeResultMergeUpdatesNull() {
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();
    result.mergeUpdates(null);
    assertFalse(result.hasResults());
  }

  @Test
  public void testCalloutCascadeResultMergeCombosNull() {
    NeoDefaultsService.CalloutCascadeResult result = new NeoDefaultsService.CalloutCascadeResult();
    result.mergeCombos(null);
    assertFalse(result.hasResults());
  }

  // ===================================================================
  // Edge case: callout returns 200 with null body
  // ===================================================================

  @Test
  public void testExecuteCalloutCascadeCalloutReturnsNullBody() throws Exception {
    try (MockedStatic<NeoCalloutService> calloutMock = mockStatic(NeoCalloutService.class);
         MockedStatic<ModelProvider> providerMock = mockStatic(ModelProvider.class)) {

      NeoCalloutService.CalloutInfo info = new NeoCalloutService.CalloutInfo(
          "com.example.Callout", "inpfield1", "Field1");
      calloutMock.when(() -> NeoCalloutService.resolveCallout(any(), eq("field1")))
          .thenReturn(info);

      NeoResponse okWithNullBody = new NeoResponse(200, null);
      calloutMock.when(() -> NeoCalloutService.executeCallout(any(), any()))
          .thenReturn(okWithNullBody);

      ModelProvider mockProvider = mock(ModelProvider.class);
      providerMock.when(ModelProvider::getInstance).thenReturn(mockProvider);
      when(mockProvider.getEntityByTableId(anyString())).thenReturn(null);

      Tab adTab = mockTabWithTable("100");
      NeoContext ctx = mock(NeoContext.class);
      JSONObject defaults = new JSONObject();
      defaults.put("field1", "val");

      NeoDefaultsService.CalloutCascadeResult result =
          NeoDefaultsCascadeHelper.executeCalloutCascade(
              ctx, adTab, defaults, new HashSet<>());

      assertNotNull(result);
      assertEquals(1, result.chainDepth);
      assertFalse("Null body should not produce results", result.hasResults());
    }
  }

  // ===================================================================
  // Private constructor coverage
  // ===================================================================

  @Test
  public void testPrivateConstructor() throws Exception {
    java.lang.reflect.Constructor<NeoDefaultsCascadeHelper> constructor =
        NeoDefaultsCascadeHelper.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    NeoDefaultsCascadeHelper instance = constructor.newInstance();
    assertNotNull(instance);
  }
}
