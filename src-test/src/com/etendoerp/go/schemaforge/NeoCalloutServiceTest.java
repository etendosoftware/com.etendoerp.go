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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link NeoCalloutService}.
 * Tests field name mapping, response transformation, and synthetic request.
 * These tests do not require a database connection.
 */
public class NeoCalloutServiceTest {

  private static final String DOCUMENT_NO = "DocumentNo";
  private static final String C_BPARTNER_ID = "C_BPartner_ID";
  private static final String INPC_BPARTNER_ID = "inpcBpartnerId";
  private static final String RESPONSE_UPDATES = "updates";
  private static final String RESPONSE_COMBOS = "combos";
  private static final String RESPONSE_MESSAGES = "messages";
  private static final String JSON_VALUE = "value";
  private static final String JSON_CLASSIC_VALUE = "classicValue";
  private static final String PRICE_LIST_VALUE = "PL-001";
  private static final String WAREHOUSE_VALUE = "WH-001";
  private static final String CREDIT_LIMIT_EXCEEDED = "Credit limit exceeded";
  private static final String INVALID_BUSINESS_PARTNER = "Invalid business partner";

  // ── toInpName tests ────────────────────────────────────────────────

  @Test
  /**
   * Verifies that a basic column name is converted to the expected input name.
   */
  public void testToInpNameBasicColumn() {
    assertEquals("inpdocumentno", NeoCalloutService.toInpName(DOCUMENT_NO));
  }

  @Test
  /**
   * Verifies that an identifier column is converted to the expected input name.
   */
  public void testToInpNameWithIdSuffix() {
    assertEquals(INPC_BPARTNER_ID, NeoCalloutService.toInpName(C_BPARTNER_ID));
  }

  @Test
  /**
   * Verifies that a warehouse identifier is converted to the expected input name.
   */
  public void testToInpNameWithWarehouse() {
    assertEquals("inpmWarehouseId", NeoCalloutService.toInpName("M_Warehouse_ID"));
  }

  @Test
  /**
   * Verifies that the active flag is converted to the expected input name.
   */
  public void testToInpNameIsActive() {
    assertEquals("inpisactive", NeoCalloutService.toInpName("IsActive"));
  }

  @Test
  /**
   * Verifies that an organization identifier is converted to the expected input name.
   */
  public void testToInpNameAdOrg() {
    assertEquals("inpadOrgId", NeoCalloutService.toInpName("AD_Org_ID"));
  }

  @Test
  /**
   * Verifies that a simple name is converted to the expected input name.
   */
  public void testToInpNameSimple() {
    assertEquals("inpname", NeoCalloutService.toInpName("Name"));
  }

  @Test
  /**
   * Verifies that names with multiple underscores are converted correctly.
   */
  public void testToInpNameMultipleUnderscores() {
    assertEquals("inpcOrderlineId", NeoCalloutService.toInpName("C_OrderLine_ID"));
  }

  @Test
  /**
   * Verifies that an empty name is converted to the base input prefix.
   */
  public void testToInpNameEmpty() {
    assertEquals("inp", NeoCalloutService.toInpName(""));
  }

  // ── transformColumnName tests ──────────────────────────────────────

  @Test
  /**
   * Verifies that a foreign key column name is transformed correctly.
   */
  public void testTransformColumnNameBasic() {
    assertEquals("cBpartnerId", NeoCalloutService.transformColumnName(C_BPARTNER_ID));
  }

  @Test
  /**
   * Verifies that a column name without underscores is normalized correctly.
   */
  public void testTransformColumnNameNoUnderscore() {
    assertEquals("documentno", NeoCalloutService.transformColumnName(DOCUMENT_NO));
  }

  @Test
  /**
   * Verifies that a single-character column name is preserved.
   */
  public void testTransformColumnNameSingleChar() {
    assertEquals("x", NeoCalloutService.transformColumnName("X"));
  }

  @Test
  /**
   * Verifies that an empty column name is normalized to an empty string.
   */
  public void testTransformColumnNameEmpty() {
    assertEquals("", NeoCalloutService.transformColumnName(""));
  }

  @Test
  /**
   * Verifies that a null column name is normalized to an empty string.
   */
  public void testTransformColumnNameNull() {
    assertEquals("", NeoCalloutService.transformColumnName(null));
  }

  // ── toCleanFieldName tests ─────────────────────────────────────────

  @Test
  /**
   * Verifies that a foreign key field name is cleaned correctly.
   */
  public void testToCleanFieldNameForeignKey() {
    // C_BPartner_ID -> strip _ID -> C_BPartner -> strip prefix C_ -> BPartner -> camelCase
    assertEquals("bpartner", NeoCalloutService.toCleanFieldName(C_BPARTNER_ID));
  }

  @Test
  /**
   * Verifies that a warehouse field name is cleaned correctly.
   */
  public void testToCleanFieldNameWarehouse() {
    // M_Warehouse_ID -> strip _ID -> M_Warehouse -> strip prefix M_ -> Warehouse -> camelCase
    assertEquals("warehouse", NeoCalloutService.toCleanFieldName("M_Warehouse_ID"));
  }

  @Test
  /**
   * Verifies that a field name without suffixes is cleaned correctly.
   */
  public void testToCleanFieldNameNoSuffix() {
    assertEquals("documentno", NeoCalloutService.toCleanFieldName(DOCUMENT_NO));
  }

  @Test
  /**
   * Verifies that an organization field name is cleaned correctly.
   */
  public void testToCleanFieldNameAdOrg() {
    // AD_Org_ID -> strip _ID -> AD_Org -> strip prefix AD_ -> Org -> camelCase
    assertEquals("org", NeoCalloutService.toCleanFieldName("AD_Org_ID"));
  }

  @Test
  /**
   * Verifies that a null field name is normalized to an empty string.
   */
  public void testToCleanFieldNameNull() {
    assertEquals("", NeoCalloutService.toCleanFieldName(null));
  }

  // ── Response transformation tests ──────────────────────────────────

  @Test
  /**
   * Verifies that an empty callout response produces the expected empty structure.
   */
  public void testTransformResponseEmpty() throws Exception {
    JSONObject result = NeoCalloutService.transformResponse(null, null);

    assertNotNull(result);
    assertTrue(result.has(RESPONSE_UPDATES));
    assertTrue(result.has(RESPONSE_COMBOS));
    assertTrue(result.has(RESPONSE_MESSAGES));
    assertEquals(0, result.getJSONObject(RESPONSE_UPDATES).length());
    assertEquals(0, result.getJSONObject(RESPONSE_COMBOS).length());
    assertEquals(0, result.getJSONArray(RESPONSE_MESSAGES).length());
  }

  @Test
  /**
   * Verifies that a simple field update is mapped into the updates section.
   */
  public void testTransformResponseSimpleFieldUpdate() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject fieldValue = new JSONObject();
    fieldValue.put(JSON_VALUE, PRICE_LIST_VALUE);
    fieldValue.put(JSON_CLASSIC_VALUE, PRICE_LIST_VALUE);
    calloutResult.put("inppricelist", fieldValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    JSONObject updates = result.getJSONObject(RESPONSE_UPDATES);
    assertTrue(updates.has("pricelist"));
    assertEquals(PRICE_LIST_VALUE, updates.getJSONObject("pricelist").getString(JSON_VALUE));
  }

  @Test
  /**
   * Verifies that combo data is mapped into the combos section.
   */
  public void testTransformResponseComboUpdate() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject comboValue = new JSONObject();
    comboValue.put(JSON_VALUE, WAREHOUSE_VALUE);
    comboValue.put(JSON_CLASSIC_VALUE, WAREHOUSE_VALUE);

    JSONArray entries = new JSONArray();
    JSONObject entry1 = new JSONObject();
    entry1.put("id", WAREHOUSE_VALUE);
    entry1.put("_identifier", "Main Warehouse");
    entries.put(entry1);
    JSONObject entry2 = new JSONObject();
    entry2.put("id", "WH-002");
    entry2.put("_identifier", "Secondary Warehouse");
    entries.put(entry2);
    comboValue.put("entries", entries);

    calloutResult.put("inpmWarehouseId", comboValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    JSONObject combos = result.getJSONObject(RESPONSE_COMBOS);
    // inpmWarehouseId -> stripped "inp" = mWarehouseId (fallback since no tab)
    assertTrue(combos.length() > 0);

    // Check the combo has entries
    String comboKey = combos.keys().next().toString();
    JSONObject combo = combos.getJSONObject(comboKey);
    assertEquals(WAREHOUSE_VALUE, combo.getString("selected"));
    JSONArray comboEntries = combo.getJSONArray("entries");
    assertEquals(2, comboEntries.length());
    assertEquals(WAREHOUSE_VALUE, comboEntries.getJSONObject(0).getString("id"));
    assertEquals("Main Warehouse", comboEntries.getJSONObject(0).getString("identifier"));
  }

  @Test
  /**
   * Verifies that informational messages are mapped into the messages section.
   */
  public void testTransformResponseMessage() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject msgValue = new JSONObject();
    msgValue.put(JSON_VALUE, CREDIT_LIMIT_EXCEEDED);
    msgValue.put(JSON_CLASSIC_VALUE, CREDIT_LIMIT_EXCEEDED);
    calloutResult.put("WARNING", msgValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    JSONArray messages = result.getJSONArray(RESPONSE_MESSAGES);
    assertEquals(1, messages.length());
    assertEquals("WARNING", messages.getJSONObject(0).getString("type"));
    assertEquals(CREDIT_LIMIT_EXCEEDED, messages.getJSONObject(0).getString("text"));
  }

  @Test
  /**
   * Verifies that JSEXECUTE payloads are ignored during response transformation.
   */
  public void testTransformResponseSkipsJsExecute() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject jsValue = new JSONObject();
    jsValue.put(JSON_VALUE, "alert('test')");
    jsValue.put(JSON_CLASSIC_VALUE, "alert('test')");
    calloutResult.put("JSEXECUTE", jsValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    // JSEXECUTE should not appear in updates or combos
    assertEquals(0, result.getJSONObject(RESPONSE_UPDATES).length());
    assertEquals(0, result.getJSONObject(RESPONSE_COMBOS).length());
    assertEquals(0, result.getJSONArray(RESPONSE_MESSAGES).length());
  }

  @Test
  /**
   * Verifies that mixed response content is split into updates and messages correctly.
   */
  public void testTransformResponseMixedContent() throws Exception {
    JSONObject calloutResult = new JSONObject();

    // Simple field update
    JSONObject priceValue = new JSONObject();
    priceValue.put(JSON_VALUE, "100.00");
    priceValue.put(JSON_CLASSIC_VALUE, "100.00");
    calloutResult.put("inpgrandtotal", priceValue);

    // Message
    JSONObject msgValue = new JSONObject();
    msgValue.put(JSON_VALUE, "Total recalculated");
    msgValue.put(JSON_CLASSIC_VALUE, "Total recalculated");
    calloutResult.put("INFO", msgValue);

    // JS Execute (should be filtered)
    JSONObject jsValue = new JSONObject();
    jsValue.put(JSON_VALUE, "reloadGrid()");
    jsValue.put(JSON_CLASSIC_VALUE, "reloadGrid()");
    calloutResult.put("JSEXECUTE", jsValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    assertEquals(1, result.getJSONObject(RESPONSE_UPDATES).length());
    assertEquals(0, result.getJSONObject(RESPONSE_COMBOS).length());
    assertEquals(1, result.getJSONArray(RESPONSE_MESSAGES).length());
  }

  @Test
  /**
   * Verifies that error messages are mapped into the messages section.
   */
  public void testTransformResponseErrorMessage() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject errorValue = new JSONObject();
    errorValue.put(JSON_VALUE, INVALID_BUSINESS_PARTNER);
    errorValue.put(JSON_CLASSIC_VALUE, INVALID_BUSINESS_PARTNER);
    calloutResult.put("ERROR", errorValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    JSONArray messages = result.getJSONArray(RESPONSE_MESSAGES);
    assertEquals(1, messages.length());
    assertEquals("ERROR", messages.getJSONObject(0).getString("type"));
    assertEquals(INVALID_BUSINESS_PARTNER, messages.getJSONObject(0).getString("text"));
  }

  // ── SyntheticHttpServletRequest tests ──────────────────────────────

  @Test
  /**
   * Verifies that request parameters can be retrieved from the synthetic request.
   */
  public void testSyntheticRequestGetParameter() {
    java.util.Map<String, String[]> params = new java.util.HashMap<>();
    params.put("inpTabId", new String[]{ "TAB123" });
    params.put(INPC_BPARTNER_ID, new String[]{ "BP-001" });

    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(params, null);

    assertEquals("TAB123", request.getParameter("inpTabId"));
    assertEquals("BP-001", request.getParameter(INPC_BPARTNER_ID));
    assertNull(request.getParameter("nonexistent"));
  }

  @Test
  /**
   * Verifies that the synthetic request exposes the full parameter map.
   */
  public void testSyntheticRequestParameterMap() {
    java.util.Map<String, String[]> params = new java.util.HashMap<>();
    params.put("key1", new String[]{ "val1" });
    params.put("key2", new String[]{ "val2" });

    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(params, null);

    assertEquals(2, request.getParameterMap().size());
  }

  @Test
  /**
   * Verifies that session attributes are available through the synthetic request.
   */
  public void testSyntheticRequestSession() {
    java.util.Map<String, Object> sessionAttrs = new java.util.HashMap<>();
    sessionAttrs.put("#AD_User_ID", "USER-001");
    sessionAttrs.put("#AD_Role_ID", "ROLE-001");

    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, sessionAttrs);

    assertNotNull(request.getSession());
    // Session attributes are stored with uppercase keys
    assertEquals("USER-001", request.getSession().getAttribute("#AD_USER_ID"));
    assertEquals("ROLE-001", request.getSession().getAttribute("#AD_ROLE_ID"));
  }

  @Test
  /**
   * Verifies that session attribute lookups are case-insensitive as expected.
   */
  public void testSyntheticRequestSessionAttributeUppercase() {
    java.util.Map<String, Object> sessionAttrs = new java.util.HashMap<>();
    sessionAttrs.put("#AD_Client_ID", "CLIENT-001");

    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, sessionAttrs);

    // VariablesBase looks up with toUpperCase, so the session must handle it
    assertEquals("CLIENT-001", request.getSession().getAttribute("#AD_CLIENT_ID"));
  }

  @Test
  /**
   * Verifies that the synthetic request behaves correctly when no inputs are provided.
   */
  public void testSyntheticRequestNullParams() {
    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, null);

    assertNull(request.getParameter("anything"));
    assertNotNull(request.getSession());
    assertEquals(0, request.getParameterMap().size());
  }

  @Test
  /**
   * Verifies that the synthetic request reports the expected HTTP method.
   */
  public void testSyntheticRequestMethod() {
    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, null);
    assertEquals("POST", request.getMethod());
  }

  @Test
  /**
   * Verifies that request attributes can be set, read, and removed.
   */
  public void testSyntheticRequestAttributes() {
    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, null);

    request.setAttribute("test", JSON_VALUE);
    assertEquals(JSON_VALUE, request.getAttribute("test"));

    request.removeAttribute("test");
    assertNull(request.getAttribute("test"));
  }
}
