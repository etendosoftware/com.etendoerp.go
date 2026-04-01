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

  // ── toInpName tests ────────────────────────────────────────────────

  @Test
  public void testToInpNameBasicColumn() {
    assertEquals("inpdocumentno", NeoCalloutService.toInpName("DocumentNo"));
  }

  @Test
  public void testToInpNameWithIdSuffix() {
    assertEquals("inpcBpartnerId", NeoCalloutService.toInpName("C_BPartner_ID"));
  }

  @Test
  public void testToInpNameWithWarehouse() {
    assertEquals("inpmWarehouseId", NeoCalloutService.toInpName("M_Warehouse_ID"));
  }

  @Test
  public void testToInpNameIsActive() {
    assertEquals("inpisactive", NeoCalloutService.toInpName("IsActive"));
  }

  @Test
  public void testToInpNameAdOrg() {
    assertEquals("inpadOrgId", NeoCalloutService.toInpName("AD_Org_ID"));
  }

  @Test
  public void testToInpNameSimple() {
    assertEquals("inpname", NeoCalloutService.toInpName("Name"));
  }

  @Test
  public void testToInpNameMultipleUnderscores() {
    assertEquals("inpcOrderlineId", NeoCalloutService.toInpName("C_OrderLine_ID"));
  }

  @Test
  public void testToInpNameEmpty() {
    assertEquals("inp", NeoCalloutService.toInpName(""));
  }

  // ── transformColumnName tests ──────────────────────────────────────

  @Test
  public void testTransformColumnNameBasic() {
    assertEquals("cBpartnerId", NeoCalloutService.transformColumnName("C_BPartner_ID"));
  }

  @Test
  public void testTransformColumnNameNoUnderscore() {
    assertEquals("documentno", NeoCalloutService.transformColumnName("DocumentNo"));
  }

  @Test
  public void testTransformColumnNameSingleChar() {
    assertEquals("x", NeoCalloutService.transformColumnName("X"));
  }

  @Test
  public void testTransformColumnNameEmpty() {
    assertEquals("", NeoCalloutService.transformColumnName(""));
  }

  @Test
  public void testTransformColumnNameNull() {
    assertEquals("", NeoCalloutService.transformColumnName(null));
  }

  // ── toCleanFieldName tests ─────────────────────────────────────────

  @Test
  public void testToCleanFieldNameForeignKey() {
    // C_BPartner_ID -> strip _ID -> C_BPartner -> strip prefix C_ -> BPartner -> camelCase
    assertEquals("bpartner", NeoCalloutService.toCleanFieldName("C_BPartner_ID"));
  }

  @Test
  public void testToCleanFieldNameWarehouse() {
    // M_Warehouse_ID -> strip _ID -> M_Warehouse -> strip prefix M_ -> Warehouse -> camelCase
    assertEquals("warehouse", NeoCalloutService.toCleanFieldName("M_Warehouse_ID"));
  }

  @Test
  public void testToCleanFieldNameNoSuffix() {
    assertEquals("documentno", NeoCalloutService.toCleanFieldName("DocumentNo"));
  }

  @Test
  public void testToCleanFieldNameAdOrg() {
    // AD_Org_ID -> strip _ID -> AD_Org -> strip prefix AD_ -> Org -> camelCase
    assertEquals("org", NeoCalloutService.toCleanFieldName("AD_Org_ID"));
  }

  @Test
  public void testToCleanFieldNameNull() {
    assertEquals("", NeoCalloutService.toCleanFieldName(null));
  }

  // ── Response transformation tests ──────────────────────────────────

  @Test
  public void testTransformResponseEmpty() throws Exception {
    JSONObject result = NeoCalloutService.transformResponse(null, null);

    assertNotNull(result);
    assertTrue(result.has("updates"));
    assertTrue(result.has("combos"));
    assertTrue(result.has("messages"));
    assertEquals(0, result.getJSONObject("updates").length());
    assertEquals(0, result.getJSONObject("combos").length());
    assertEquals(0, result.getJSONArray("messages").length());
  }

  @Test
  public void testTransformResponseSimpleFieldUpdate() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject fieldValue = new JSONObject();
    fieldValue.put("value", "PL-001");
    fieldValue.put("classicValue", "PL-001");
    calloutResult.put("inppricelist", fieldValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    JSONObject updates = result.getJSONObject("updates");
    assertTrue(updates.has("pricelist"));
    assertEquals("PL-001", updates.getJSONObject("pricelist").getString("value"));
  }

  @Test
  public void testTransformResponseComboUpdate() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject comboValue = new JSONObject();
    comboValue.put("value", "WH-001");
    comboValue.put("classicValue", "WH-001");

    JSONArray entries = new JSONArray();
    JSONObject entry1 = new JSONObject();
    entry1.put("id", "WH-001");
    entry1.put("_identifier", "Main Warehouse");
    entries.put(entry1);
    JSONObject entry2 = new JSONObject();
    entry2.put("id", "WH-002");
    entry2.put("_identifier", "Secondary Warehouse");
    entries.put(entry2);
    comboValue.put("entries", entries);

    calloutResult.put("inpmWarehouseId", comboValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    JSONObject combos = result.getJSONObject("combos");
    // inpmWarehouseId -> stripped "inp" = mWarehouseId (fallback since no tab)
    assertTrue(combos.length() > 0);

    // Check the combo has entries
    String comboKey = combos.keys().next().toString();
    JSONObject combo = combos.getJSONObject(comboKey);
    assertEquals("WH-001", combo.getString("selected"));
    JSONArray comboEntries = combo.getJSONArray("entries");
    assertEquals(2, comboEntries.length());
    assertEquals("WH-001", comboEntries.getJSONObject(0).getString("id"));
    assertEquals("Main Warehouse", comboEntries.getJSONObject(0).getString("identifier"));
  }

  @Test
  public void testTransformResponseMessage() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject msgValue = new JSONObject();
    msgValue.put("value", "Credit limit exceeded");
    msgValue.put("classicValue", "Credit limit exceeded");
    calloutResult.put("WARNING", msgValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    JSONArray messages = result.getJSONArray("messages");
    assertEquals(1, messages.length());
    assertEquals("WARNING", messages.getJSONObject(0).getString("type"));
    assertEquals("Credit limit exceeded", messages.getJSONObject(0).getString("text"));
  }

  @Test
  public void testTransformResponseSkipsJsExecute() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject jsValue = new JSONObject();
    jsValue.put("value", "alert('test')");
    jsValue.put("classicValue", "alert('test')");
    calloutResult.put("JSEXECUTE", jsValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    // JSEXECUTE should not appear in updates or combos
    assertEquals(0, result.getJSONObject("updates").length());
    assertEquals(0, result.getJSONObject("combos").length());
    assertEquals(0, result.getJSONArray("messages").length());
  }

  @Test
  public void testTransformResponseMixedContent() throws Exception {
    JSONObject calloutResult = new JSONObject();

    // Simple field update
    JSONObject priceValue = new JSONObject();
    priceValue.put("value", "100.00");
    priceValue.put("classicValue", "100.00");
    calloutResult.put("inpgrandtotal", priceValue);

    // Message
    JSONObject msgValue = new JSONObject();
    msgValue.put("value", "Total recalculated");
    msgValue.put("classicValue", "Total recalculated");
    calloutResult.put("INFO", msgValue);

    // JS Execute (should be filtered)
    JSONObject jsValue = new JSONObject();
    jsValue.put("value", "reloadGrid()");
    jsValue.put("classicValue", "reloadGrid()");
    calloutResult.put("JSEXECUTE", jsValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    assertEquals(1, result.getJSONObject("updates").length());
    assertEquals(0, result.getJSONObject("combos").length());
    assertEquals(1, result.getJSONArray("messages").length());
  }

  @Test
  public void testTransformResponseErrorMessage() throws Exception {
    JSONObject calloutResult = new JSONObject();
    JSONObject errorValue = new JSONObject();
    errorValue.put("value", "Invalid business partner");
    errorValue.put("classicValue", "Invalid business partner");
    calloutResult.put("ERROR", errorValue);

    JSONObject result = NeoCalloutService.transformResponse(calloutResult, null);

    JSONArray messages = result.getJSONArray("messages");
    assertEquals(1, messages.length());
    assertEquals("ERROR", messages.getJSONObject(0).getString("type"));
    assertEquals("Invalid business partner", messages.getJSONObject(0).getString("text"));
  }

  // ── SyntheticHttpServletRequest tests ──────────────────────────────

  @Test
  public void testSyntheticRequestGetParameter() {
    java.util.Map<String, String[]> params = new java.util.HashMap<>();
    params.put("inpTabId", new String[]{ "TAB123" });
    params.put("inpcBpartnerId", new String[]{ "BP-001" });

    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(params, null);

    assertEquals("TAB123", request.getParameter("inpTabId"));
    assertEquals("BP-001", request.getParameter("inpcBpartnerId"));
    assertNull(request.getParameter("nonexistent"));
  }

  @Test
  public void testSyntheticRequestParameterMap() {
    java.util.Map<String, String[]> params = new java.util.HashMap<>();
    params.put("key1", new String[]{ "val1" });
    params.put("key2", new String[]{ "val2" });

    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(params, null);

    assertEquals(2, request.getParameterMap().size());
  }

  @Test
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
  public void testSyntheticRequestSessionAttributeUppercase() {
    java.util.Map<String, Object> sessionAttrs = new java.util.HashMap<>();
    sessionAttrs.put("#AD_Client_ID", "CLIENT-001");

    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, sessionAttrs);

    // VariablesBase looks up with toUpperCase, so the session must handle it
    assertEquals("CLIENT-001", request.getSession().getAttribute("#AD_CLIENT_ID"));
  }

  @Test
  public void testSyntheticRequestNullParams() {
    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, null);

    assertNull(request.getParameter("anything"));
    assertNotNull(request.getSession());
    assertEquals(0, request.getParameterMap().size());
  }

  @Test
  public void testSyntheticRequestMethod() {
    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, null);
    assertEquals("POST", request.getMethod());
  }

  @Test
  public void testSyntheticRequestAttributes() {
    SyntheticHttpServletRequest request = new SyntheticHttpServletRequest(null, null);

    request.setAttribute("test", "value");
    assertEquals("value", request.getAttribute("test"));

    request.removeAttribute("test");
    assertNull(request.getAttribute("test"));
  }
}
