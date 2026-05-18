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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.Mockito;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for selector context params and diagnostics features in
 * {@link McpSelectorContextHelper}.
 *
 * <p>These tests call package-private helpers and mock the AD_Tab/AD_Window
 * dependencies. They do not require a database connection.</p>
 */
public class SelectorContextParamsTest {

  // ── buildSelectorContextParams — recordContext mapping ─────────────────

  @Test
  public void testBuildSelectorContextParamsMapsBusinessPartner() throws Exception {
    JSONObject args = new JSONObject();
    JSONObject recordContext = new JSONObject();
    recordContext.put("businessPartner", "BP-001");
    args.put(McpConstants.PARAM_RECORD_CONTEXT, recordContext);

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, null);

    assertEquals("BP-001", result.get("C_BPartner_ID"));
  }

  @Test
  public void testBuildSelectorContextParamsMapsPartnerAddress() throws Exception {
    JSONObject args = new JSONObject();
    JSONObject recordContext = new JSONObject();
    recordContext.put("partnerAddress", "LOC-001");
    args.put(McpConstants.PARAM_RECORD_CONTEXT, recordContext);

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, null);

    assertEquals("LOC-001", result.get("C_BPartner_Location_ID"));
  }

  @Test
  public void testBuildSelectorContextParamsMapsPriceList() throws Exception {
    JSONObject args = new JSONObject();
    JSONObject recordContext = new JSONObject();
    recordContext.put("priceList", "PL-001");
    args.put(McpConstants.PARAM_RECORD_CONTEXT, recordContext);

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, null);

    assertEquals("PL-001", result.get("priceList"));
    assertEquals("PL-001", result.get("PriceList"));
  }

  @Test
  public void testBuildSelectorContextParamsMapsInvoiceDate() throws Exception {
    JSONObject args = new JSONObject();
    JSONObject recordContext = new JSONObject();
    recordContext.put("invoiceDate", "2026-05-12");
    args.put(McpConstants.PARAM_RECORD_CONTEXT, recordContext);

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, null);

    assertEquals("12-05-2026", result.get("DateInvoiced"));
    assertEquals("12-05-2026", result.get("dateInvoiced"));
  }

  @Test
  public void testBuildSelectorContextParamsMapsOrderDate() throws Exception {
    JSONObject args = new JSONObject();
    JSONObject recordContext = new JSONObject();
    recordContext.put("orderDate", "2026-05-12");
    args.put(McpConstants.PARAM_RECORD_CONTEXT, recordContext);

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, null);

    assertEquals("12-05-2026", result.get("DateOrdered"));
    assertEquals("12-05-2026", result.get("dateOrdered"));
  }

  @Test
  public void testBuildSelectorContextParamsPreservesClassicDateFormat() throws Exception {
    JSONObject args = new JSONObject();
    JSONObject recordContext = new JSONObject();
    recordContext.put("invoiceDate", "12-05-2026");
    args.put(McpConstants.PARAM_RECORD_CONTEXT, recordContext);

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, null);

    assertEquals("12-05-2026", result.get("DateInvoiced"));
  }

  @Test
  public void testBuildSelectorContextParamsMapsParentContext() throws Exception {
    JSONObject args = new JSONObject();
    JSONObject parentContext = new JSONObject();
    parentContext.put("businessPartner", "BP-PARENT");
    parentContext.put("orderDate", "2026-05-12");
    parentContext.put("priceList", "PL-PARENT");
    args.put(McpConstants.PARAM_PARENT_CONTEXT, parentContext);

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, null);

    assertEquals("BP-PARENT", result.get("C_BPartner_ID"));
    assertEquals("12-05-2026", result.get("DateOrdered"));
    assertEquals("PL-PARENT", result.get("priceList"));
    assertEquals("PL-PARENT", result.get("PriceList"));
    assertEquals("PL-PARENT", result.get("M_PriceList_ID"));
  }

  @Test
  public void testRecordContextOverridesParentContext() throws Exception {
    JSONObject args = new JSONObject();
    JSONObject parentContext = new JSONObject();
    parentContext.put("businessPartner", "BP-PARENT");
    JSONObject recordContext = new JSONObject();
    recordContext.put("businessPartner", "BP-RECORD");
    args.put(McpConstants.PARAM_PARENT_CONTEXT, parentContext);
    args.put(McpConstants.PARAM_RECORD_CONTEXT, recordContext);

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, null);

    assertEquals("BP-RECORD", result.get("C_BPartner_ID"));
  }

  @Test
  public void testBuildSelectorContextParamsMapsRawContextAliases() throws Exception {
    JSONObject args = new JSONObject();
    JSONObject recordContext = new JSONObject();
    recordContext.put("C_BPartner_ID", "BP-RAW");
    recordContext.put("C_BPartner_Location_ID", "LOC-RAW");
    recordContext.put("M_PriceList_ID", "PL-RAW");
    recordContext.put("DateInvoiced", "2026-05-12");
    args.put(McpConstants.PARAM_RECORD_CONTEXT, recordContext);

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, null);

    assertEquals("BP-RAW", result.get("C_BPartner_ID"));
    assertEquals("LOC-RAW", result.get("C_BPartner_Location_ID"));
    assertEquals("PL-RAW", result.get("priceList"));
    assertEquals("PL-RAW", result.get("PriceList"));
    assertEquals("PL-RAW", result.get("M_PriceList_ID"));
    assertEquals("12-05-2026", result.get("DateInvoiced"));
  }

  @Test
  public void testBuildSelectorContextParamsMapsParentId() throws Exception {
    JSONObject args = new JSONObject();
    args.put(McpConstants.PARAM_PARENT_ID, "ORDER-001");

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, null);

    assertEquals("ORDER-001", result.get("parentId"));
  }

  @Test
  public void testBuildSelectorContextParamsEmptyWhenNoRecordContext() throws Exception {
    JSONObject args = new JSONObject();

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, null);

    assertTrue(result.isEmpty());
  }

  // ── buildSelectorContextParams — isSOTrx derivation from Tab ──────────

  @Test
  public void testBuildSelectorContextParamsDerivesIsSOTrxFromSalesTab() throws Exception {
    Tab salesTab = Mockito.mock(Tab.class);
    Window salesWindow = Mockito.mock(Window.class);
    when(salesTab.getWindow()).thenReturn(salesWindow);
    when(salesWindow.isSalesTransaction()).thenReturn(true);

    JSONObject args = new JSONObject();

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, salesTab);

    assertEquals("Y", result.get("IsSOTrx"));
    assertEquals("Y", result.get("isSOTrx"));
    assertEquals("Y", result.get("isCustomer"));
  }

  @Test
  public void testBuildSelectorContextParamsDerivesIsSOTrxFromPurchaseTab() throws Exception {
    Tab purchaseTab = Mockito.mock(Tab.class);
    Window purchaseWindow = Mockito.mock(Window.class);
    when(purchaseTab.getWindow()).thenReturn(purchaseWindow);
    when(purchaseWindow.isSalesTransaction()).thenReturn(false);

    JSONObject args = new JSONObject();

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, purchaseTab);

    assertEquals("N", result.get("IsSOTrx"));
    assertEquals("N", result.get("isSOTrx"));
    assertEquals("Y", result.get("isVendor"));
  }

  @Test
  public void testBuildSelectorContextParamsSkipsIsSOTrxWhenNullTab() throws Exception {
    JSONObject args = new JSONObject();

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, null);

    assertFalse(result.containsKey("IsSOTrx"));
    assertFalse(result.containsKey("isSOTrx"));
  }

  @Test
  public void testBuildSelectorContextParamsPreservesExplicitIsSOTrx() throws Exception {
    Tab salesTab = Mockito.mock(Tab.class);
    Window salesWindow = Mockito.mock(Window.class);
    when(salesTab.getWindow()).thenReturn(salesWindow);
    when(salesWindow.isSalesTransaction()).thenReturn(true);

    JSONObject args = new JSONObject();
    JSONObject recordContext = new JSONObject();
    recordContext.put("isSOTrx", "N");
    args.put(McpConstants.PARAM_RECORD_CONTEXT, recordContext);

    Map<String, String> result = McpSelectorContextHelper.buildSelectorContextParams(args, salesTab);

    assertEquals("N", result.get("isSOTrx"));
    assertEquals("N", result.get("IsSOTrx"));
    assertEquals("Y", result.get("isVendor"));
  }

  // ── copyContextIfPresent ──────────────────────────────────────────────

  @Test
  public void testCopyContextIfPresentCopiesValue() throws Exception {
    JSONObject recordContext = new JSONObject();
    recordContext.put("sourceKey", "value123");
    Map<String, String> target = new HashMap<>();

    McpSelectorContextHelper.copyContextIfPresent(recordContext, "sourceKey", target, "targetKey");

    assertEquals("value123", target.get("targetKey"));
  }

  @Test
  public void testCopyContextIfPresentSkipsMissingKey() throws Exception {
    JSONObject recordContext = new JSONObject();
    Map<String, String> target = new HashMap<>();

    McpSelectorContextHelper.copyContextIfPresent(recordContext, "missingKey", target, "targetKey");

    assertFalse(target.containsKey("targetKey"));
  }

  @Test
  public void testCopyContextIfPresentSkipsBlankValue() throws Exception {
    JSONObject recordContext = new JSONObject();
    recordContext.put("sourceKey", "   ");
    Map<String, String> target = new HashMap<>();

    McpSelectorContextHelper.copyContextIfPresent(recordContext, "sourceKey", target, "targetKey");

    assertFalse(target.containsKey("targetKey"));
  }

  // ── neoResponseToMcpResultWithDiagnostics ─────────────────────────────

  @Test
  public void testDiagnosticsSuggestsBPartnerForLocationSelector() throws Exception {
    JSONObject body = new JSONObject();
    body.put("items", new JSONArray());
    body.put("totalCount", 0);
    NeoResponse response = NeoResponse.ok(body);

    Map<String, String> contextParams = new HashMap<>();

    NeoResponse result = McpSelectorContextHelper.withDiagnostics(response,
        "C_BPartner_Location_ID", contextParams);

    JSONObject responseBody = result.getBody();
    assertTrue(responseBody.has("diagnostics"));

    JSONObject diagnostics = responseBody.getJSONObject("diagnostics");
    JSONArray missingContext = diagnostics.getJSONArray("missingContext");
    assertEquals(1, missingContext.length());

    JSONObject missing = missingContext.getJSONObject(0);
    assertEquals("C_BPartner_ID", missing.getString("param"));
    assertEquals("businessPartner", missing.getString("field"));
  }

  @Test
  public void testDiagnosticsSuggestsDateForTaxSelector() throws Exception {
    JSONObject body = new JSONObject();
    body.put("items", new JSONArray());
    body.put("totalCount", 0);
    NeoResponse response = NeoResponse.ok(body);

    Map<String, String> contextParams = new HashMap<>();

    NeoResponse result = McpSelectorContextHelper.withDiagnostics(response,
        "C_Tax_ID", contextParams);

    JSONObject responseBody = result.getBody();
    assertTrue(responseBody.has("diagnostics"));

    JSONObject diagnostics = responseBody.getJSONObject("diagnostics");
    JSONArray missingContext = diagnostics.getJSONArray("missingContext");

    boolean foundDateSuggestion = false;
    for (int i = 0; i < missingContext.length(); i++) {
      JSONObject missing = missingContext.getJSONObject(i);
      if ("DateInvoiced".equals(missing.getString("param"))) {
        foundDateSuggestion = true;
        break;
      }
    }
    assertTrue("Should suggest DateInvoiced for tax selector", foundDateSuggestion);
  }

  @Test
  public void testNoDiagnosticsWhenResultsExist() throws Exception {
    JSONObject body = new JSONObject();
    JSONArray items = new JSONArray();
    items.put(new JSONObject().put("id", "1").put("name", "Test"));
    body.put("items", items);
    body.put("totalCount", 1);
    NeoResponse response = NeoResponse.ok(body);

    Map<String, String> contextParams = new HashMap<>();

    NeoResponse result = McpSelectorContextHelper.withDiagnostics(response,
        "C_Tax_ID", contextParams);

    JSONObject responseBody = result.getBody();
    assertFalse("Should not have diagnostics when results exist",
        responseBody.has("diagnostics"));
  }

  @Test
  public void testNoDiagnosticsWhenContextProvided() throws Exception {
    JSONObject body = new JSONObject();
    body.put("items", new JSONArray());
    body.put("totalCount", 0);
    NeoResponse response = NeoResponse.ok(body);

    Map<String, String> contextParams = new HashMap<>();
    contextParams.put("C_BPartner_ID", "BP-001");
    contextParams.put("IsSOTrx", "Y");
    contextParams.put("DateInvoiced", "2026-05-12");

    NeoResponse result = McpSelectorContextHelper.withDiagnostics(response,
        "C_BPartner_Location_ID", contextParams);

    JSONObject responseBody = result.getBody();

    if (responseBody.has("diagnostics")) {
      JSONObject diagnostics = responseBody.getJSONObject("diagnostics");
      if (diagnostics.has("missingContext")) {
        JSONArray missingContext = diagnostics.getJSONArray("missingContext");
        assertEquals("No missing context suggestions when all params provided",
            0, missingContext.length());
      }
    }
  }
}
