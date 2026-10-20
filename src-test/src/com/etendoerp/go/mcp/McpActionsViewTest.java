/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
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
package com.etendoerp.go.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.schemaforge.GoodsReceiptHeaderHandler;
import com.etendoerp.go.schemaforge.GoodsShipmentHeaderHandler;
import com.etendoerp.go.schemaforge.PurchaseInvoiceHeaderHandler;
import com.etendoerp.go.schemaforge.PurchaseOrderHeaderHandler;
import com.etendoerp.go.schemaforge.ReturnMaterialReceiptHeaderHandler;
import com.etendoerp.go.schemaforge.ReturnToVendorShipmentHeaderHandler;
import com.etendoerp.go.schemaforge.SalesInvoiceHeaderHandler;
import com.etendoerp.go.schemaforge.SalesOrderHeaderHandler;
import com.etendoerp.go.schemaforge.SalesQuotationHeaderHandler;
import com.etendoerp.go.schemaforge.util.NeoActionAvailability;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for {@link McpActionsView} — the pure re-shaper behind
 * {@code etendo_schema({view:"actions"})} (IMP-6). No DAL/model access, so these run without a live
 * instance.
 *
 * @covers com.etendoerp.go.mcp.McpActionsView
 * @covers com.etendoerp.go.mcp.McpActionAvailabilityContext
 */
// Test methods live in the @Nested inner classes below; S2187 only inspects
// the outer class for @Test methods, hence the suppression.
@SuppressWarnings("java:S2187")
@DisplayName("McpActionsView")
class McpActionsViewTest {

  private static JSONObject field(String name, String type) throws JSONException {
    JSONObject field = new JSONObject();
    field.put("name", name);
    field.put("type", type);
    return field;
  }

  private static JSONObject buttonField(String name, String processName) throws JSONException {
    JSONObject field = field(name, "button");
    field.put("invokeVia", "etendo_action");
    field.put("action", name);
    field.put("processType", "OBUIAPP");
    field.put("processName", processName);
    field.put("processId", "ABC123");
    return field;
  }

  /** A mixed schema fields array: two plain columns and two buttons. */
  private static JSONArray sampleFields() throws JSONException {
    JSONArray fields = new JSONArray();
    fields.put(field("documentNo", "string"));
    fields.put(buttonField("completeAction", "Complete"));
    fields.put(field("grandTotal", "number"));
    fields.put(buttonField("cancelAction", "Cancel Document"));
    return fields;
  }

  @Nested
  @DisplayName("isActionsView")
  class Predicate {

    @Test
    @DisplayName("only \"actions\" (case-insensitive) requests the actions-only projection")
    void isActionsView() {
      assertTrue(McpActionsView.isActionsView("actions"));
      assertTrue(McpActionsView.isActionsView("ACTIONS"));
      assertFalse(McpActionsView.isActionsView("full"));
      assertFalse(McpActionsView.isActionsView(null));
    }
  }

  @Nested
  @DisplayName("apply")
  class Apply {

    @Test
    @DisplayName("filters the field array down to type:\"button\" entries, preserving order")
    void filtersButtonFields() throws JSONException {
      JSONArray actions = McpActionsView.apply(sampleFields());

      assertEquals(2, actions.length());
      assertEquals("completeAction", actions.getJSONObject(0).getString("name"));
      assertEquals("cancelAction", actions.getJSONObject(1).getString("name"));
    }

    @Test
    @DisplayName("a fields array with no buttons yields an empty actions array")
    void noButtons() throws JSONException {
      JSONArray fields = new JSONArray();
      fields.put(field("documentNo", "string"));
      fields.put(field("grandTotal", "number"));

      assertEquals(0, McpActionsView.apply(fields).length());
    }

    @Test
    @DisplayName("a null fields array yields an empty (never null) actions array")
    void nullFields() throws JSONException {
      assertEquals(0, McpActionsView.apply(null).length());
    }
  }

  @Nested
  @DisplayName("buildResponse")
  class BuildResponse {

    @Test
    @DisplayName("shapes {spec, entity, actions, actionCount}, dropping the full field dump")
    void shapesResponse() throws JSONException {
      JSONObject response = McpActionsView.buildResponse("sales-order", "header", sampleFields());

      assertEquals("sales-order", response.getString("spec"));
      assertEquals("header", response.getString("entity"));
      assertEquals(2, response.getInt("actionCount"));
      assertEquals(2, response.getJSONArray(McpActionsView.KEY_ACTIONS).length());
      assertFalse(response.has("fields"));
    }

    /**
     * IMP-21: the catalog lists every button the window has, but only the invokable ones carry
     * {@code invokeVia}. {@code invokableCount} is what tells an agent the split without walking
     * the array — on sales-invoice it reads 5 of 22.
     */
    @Test
    @DisplayName("invokableCount counts only the actions carrying invokeVia")
    void countsInvokableActions() throws JSONException {
      JSONArray fields = sampleFields();
      JSONObject discarded = buttonField("calculatePromotions", "Calculate Promotions");
      discarded.remove("invokeVia");
      discarded.put("invokable", false);
      discarded.put("notInvokableReason", "discarded: not part of the curated agent surface");
      fields.put(discarded);

      JSONObject response = McpActionsView.buildResponse("sales-invoice", "header", fields);

      assertEquals(3, response.getInt("actionCount"));
      assertEquals(2, response.getInt(McpActionsView.KEY_INVOKABLE_COUNT));
    }

    /** A catalog where nothing is callable reports 0, not a missing key. */
    @Test
    @DisplayName("invokableCount is 0 when no action is callable")
    void countsZeroWhenNothingInvokable() throws JSONException {
      JSONArray fields = new JSONArray();
      JSONObject blocked = buttonField("createLinesFrom", "");
      blocked.remove("invokeVia");
      blocked.put("invokable", false);
      fields.put(blocked);

      JSONObject response = McpActionsView.buildResponse("sales-invoice", "header", fields);

      assertEquals(1, response.getInt("actionCount"));
      assertEquals(0, response.getInt(McpActionsView.KEY_INVOKABLE_COUNT));
    }

    /**
     * ETP-5535: a window entity whose customization declares actions next to its AD buttons gets
     * them appended after the buttons. The real {@link SalesQuotationHeaderHandler} declaration is
     * used, so a drift in what the quotation publishes (or its order) shows up here.
     */
    @Test
    @DisplayName("4-arg: declared actions follow the buttons, are counted, and carry a hint")
    void appendsDeclaredActionsAfterButtons() throws JSONException {
      Map<String, NeoActionContract> declared =
          new SalesQuotationHeaderHandler().actionContracts();

      JSONObject response = McpActionsView.buildResponse("sales-quotation", "quotation",
          sampleFields(), declared);

      JSONArray actions = response.getJSONArray(McpActionsView.KEY_ACTIONS);
      List<String> names = new ArrayList<>();
      for (int i = 0; i < actions.length(); i++) {
        JSONObject action = actions.getJSONObject(i);
        names.add(action.has("name") ? action.getString("name") : action.getString("action"));
      }
      assertEquals(List.of("completeAction", "cancelAction", "createDraftInvoice",
          "rejectQuotation", "createRejectReason"), names);
      assertTrue(actions.getJSONObject(2).has("parameters"));
      assertEquals(5, response.getInt("actionCount"));
      assertEquals(5, response.getInt(McpActionsView.KEY_INVOKABLE_COUNT));
      assertTrue(response.getString("declaredActionsHint").contains("etendo_action"));
    }

    /** With nothing declared the 4-arg call is the 3-arg one: no extra entry, no hint. */
    @Test
    @DisplayName("4-arg: a null or empty declaration renders exactly the 3-arg response")
    void nullOrEmptyDeclarationIsTheThreeArgResponse() throws JSONException {
      String threeArg = McpActionsView.buildResponse("sales-order", "header", sampleFields())
          .toString();
      for (Map<String, NeoActionContract> declared : Arrays.asList(
          null, Collections.<String, NeoActionContract>emptyMap())) {
        JSONObject response = McpActionsView.buildResponse("sales-order", "header",
            sampleFields(), declared);
        assertEquals(threeArg, response.toString(), String.valueOf(declared));
        assertFalse(response.has("declaredActionsHint"));
      }
    }
  }

  // ── ETP-5714: availability ────────────────────────────────────────────────

  private static int sequence;
  private static final String DOCUMENT_STATUS = "documentStatus";
  private static final String POSTED = "posted";
  private static final String DOCUMENT_ACTION = "documentAction";

  private static McpActionAvailabilityContext record(Map<String, String> state,
      Map<String, String> derived) {
    return new McpActionAvailabilityContext("REC1", state::get, derived);
  }

  private static JSONObject catalogue(Map<String, NeoActionContract> declared,
      McpActionAvailabilityContext record) throws JSONException {
    return McpActionsView.buildResponse("spec", "entity", new JSONArray(), declared, null,
        Collections.emptySet(), record);
  }

  private static JSONObject entry(JSONObject response, String action) throws JSONException {
    JSONArray actions = response.getJSONArray(McpActionsView.KEY_ACTIONS);
    for (int i = 0; i < actions.length(); i++) {
      if (action.equals(actions.getJSONObject(i).optString("action"))) {
        return actions.getJSONObject(i);
      }
    }
    return null;
  }

  private static void assertNow(Map<String, NeoActionContract> declared, Map<String, String> state,
      String action, boolean expected) throws JSONException {
    JSONObject entry = entry(catalogue(declared, record(state, Map.of())), action);
    assertNotNull(entry, action + " must stay LISTED whatever the state");
    assertEquals(expected, entry.getBoolean("availableNow"), action + " in " + state);
    assertEquals(!expected, entry.has("unavailableReason"), action + " in " + state);
  }

  @Nested
  @DisplayName("availability (ETP-5714)")
  class Availability {

    @Test
    @DisplayName("the static catalogue shows when each action applies, with no availableNow")
    void staticAvailabilityIsRendered() throws JSONException {
      JSONObject response = catalogue(new GoodsReceiptHeaderHandler().actionContracts(), null);

      JSONObject complete = entry(response, DOCUMENT_ACTION);
      assertFalse(complete.has("availableWhen"), "documentAction declares values per status only");
      assertEquals("CO", complete.getJSONObject("valuesByStatus").getJSONObject("values")
          .getJSONArray("DR").getString(0));
      assertFalse(complete.has("availableNow"));
      assertFalse(response.has("recordId"));
      assertTrue(response.getString(McpActionsView.KEY_AVAILABILITY_HINT).contains("never blocks"));
    }

    @Test
    @DisplayName("an action that declares nothing carries no availability and no hint is added")
    void undeclaredActionSaysNothing() throws JSONException {
      Map<String, NeoActionContract> declared =
          Map.of("plain", NeoActionContract.write("plain", "x"));

      JSONObject response = catalogue(declared, record(Map.of(DOCUMENT_STATUS, "CO"), Map.of()));

      assertFalse(entry(response, "plain").has("availableNow"));
      assertFalse(response.has(McpActionsView.KEY_AVAILABILITY_HINT));
      assertEquals("REC1", response.getString("recordId"));
    }

    @Test
    @DisplayName("goods receipt: complete only a draft, invoice or return only a completed one")
    void goodsReceipt() throws JSONException {
      Map<String, NeoActionContract> declared = new GoodsReceiptHeaderHandler().actionContracts();
      Map<String, String> draft = Map.of(DOCUMENT_STATUS, "DR");
      Map<String, String> completed = Map.of(DOCUMENT_STATUS, "CO");

      assertNow(declared, draft, DOCUMENT_ACTION, true);
      assertNow(declared, completed, DOCUMENT_ACTION, false);
      assertNow(declared, draft, "importOrderLines", true);
      assertNow(declared, completed, "importOrderLines", false);
      assertNow(declared, draft, "createPurchaseInvoice", false);
      assertNow(declared, completed, "createPurchaseInvoice", true);
      assertNow(declared, completed, "createPurchaseReturn", true);
      assertNow(declared, draft, "createPurchaseReturn", false);
    }

    /**
     * ETP-5714: receipts, shipments and both returns declare {@code post} / {@code unpost} like the
     * invoices, so the catalogue lists them with their states instead of leaving an agent the blind
     * AD {@code Posted} toggle. Declaring them changes no routing: DocumentPostingService serves them.
     */
    @Test
    @DisplayName("goods documents declare post / unpost with their states and the costing outcomes")
    void goodsDocumentPosting() throws JSONException {
      for (Map<String, NeoActionContract> declared : List.of(
          new GoodsReceiptHeaderHandler().actionContracts(),
          new GoodsShipmentHeaderHandler().actionContracts(),
          new ReturnToVendorShipmentHeaderHandler().actionContracts(),
          new ReturnMaterialReceiptHeaderHandler().actionContracts())) {
        Map<String, String> draft = Map.of(DOCUMENT_STATUS, "DR", POSTED, "N");
        Map<String, String> completed = Map.of(DOCUMENT_STATUS, "CO", POSTED, "N");
        Map<String, String> booked = Map.of(DOCUMENT_STATUS, "CO", POSTED, "Y");
        Map<String, String> notCosted = Map.of(DOCUMENT_STATUS, "CO", POSTED, "NC");

        assertNow(declared, draft, "post", false);
        assertNow(declared, completed, "post", true);
        assertNow(declared, notCosted, "post", true);
        assertNow(declared, booked, "post", false);
        assertNow(declared, completed, "unpost", false);
        assertNow(declared, booked, "unpost", true);

        String post = declared.get("post").getDescription();
        assertTrue(post.contains("costNotCalculated") && post.contains("retryAfterSeconds"), post);
        assertTrue(post.contains("costCalculationStalled") && post.contains("do not retry"), post);
        assertEquals(0, declared.get("post").getParams().size());
        assertEquals(0, declared.get("unpost").getParams().size());
      }
    }

    @Test
    @DisplayName("goods shipment: same shape, sales side")
    void goodsShipment() throws JSONException {
      Map<String, NeoActionContract> declared = new GoodsShipmentHeaderHandler().actionContracts();

      assertNow(declared, Map.of(DOCUMENT_STATUS, "DR"), DOCUMENT_ACTION, true);
      assertNow(declared, Map.of(DOCUMENT_STATUS, "CO"), DOCUMENT_ACTION, false);
      assertNow(declared, Map.of(DOCUMENT_STATUS, "CO"), "createDraftInvoice", true);
      assertNow(declared, Map.of(DOCUMENT_STATUS, "DR"), "createDraftInvoice", false);
      assertNow(declared, Map.of(DOCUMENT_STATUS, "CO"), "createReturn", true);
      assertNow(declared, Map.of(DOCUMENT_STATUS, "DR"), "importOrderLines", true);
    }

    @Test
    @DisplayName("a completed goods document says it cannot be reactivated or voided, and offers no value")
    void completedGoodsDocumentExplainsItself() throws JSONException {
      for (Map<String, NeoActionContract> declared : List.of(
          new GoodsReceiptHeaderHandler().actionContracts(),
          new GoodsShipmentHeaderHandler().actionContracts(),
          new ReturnToVendorShipmentHeaderHandler().actionContracts(),
          new ReturnMaterialReceiptHeaderHandler().actionContracts())) {
        JSONObject completed = entry(
            catalogue(declared, record(Map.of(DOCUMENT_STATUS, "CO"), Map.of())), DOCUMENT_ACTION);
        JSONObject draft = entry(
            catalogue(declared, record(Map.of(DOCUMENT_STATUS, "DR"), Map.of())), DOCUMENT_ACTION);

        assertFalse(completed.getBoolean("availableNow"));
        assertTrue(completed.getString("unavailableReason")
            .contains("cannot be reactivated or voided through MCP"), completed.toString());
        assertTrue(completed.getString("unavailableReason").contains("return"));
        assertFalse(completed.has("availableValuesNow"));
        assertEquals("CO", draft.getJSONArray("availableValuesNow").getString(0));
      }
    }

    @Test
    @DisplayName("return documents: confirm a draft, invoice a completed one, derived rectificativa")
    void returnDocuments() throws JSONException {
      for (Map<String, NeoActionContract> declared : List.of(
          new ReturnToVendorShipmentHeaderHandler().actionContracts(),
          new ReturnMaterialReceiptHeaderHandler().actionContracts())) {
        assertNow(declared, Map.of(DOCUMENT_STATUS, "DR"), DOCUMENT_ACTION, true);
        assertNow(declared, Map.of(DOCUMENT_STATUS, "DR"), "createReturnInvoice", false);
        assertNow(declared, Map.of(DOCUMENT_STATUS, "CO"), "createReturnInvoice", true);
        String importAction = declared.containsKey("importReceiptLines") ? "importReceiptLines"
            : "importShipmentLines";
        assertNow(declared, Map.of(DOCUMENT_STATUS, "DR"), importAction, true);
        assertNow(declared, Map.of(DOCUMENT_STATUS, "CO"), importAction, false);

        JSONObject withRectificativa = entry(catalogue(declared, record(
            Map.of(DOCUMENT_STATUS, "CO"),
            Map.of("createReturnInvoice", "This return already has a factura rectificativa (R-1)."))),
            "createReturnInvoice");
        assertFalse(withRectificativa.getBoolean("availableNow"), "derived wins over static");
        assertTrue(withRectificativa.getString("unavailableReason").contains("R-1"));
      }
    }

    @Test
    @DisplayName("purchase and sales invoice: post needs a completed unposted invoice, unpost the opposite")
    void invoicePosting() throws JSONException {
      for (Map<String, NeoActionContract> declared : List.of(
          new PurchaseInvoiceHeaderHandler().actionContracts(),
          new SalesInvoiceHeaderHandler().actionContracts())) {
        Map<String, String> draft = Map.of(DOCUMENT_STATUS, "DR", POSTED, "N");
        Map<String, String> completed = Map.of(DOCUMENT_STATUS, "CO", POSTED, "N");
        Map<String, String> booked = Map.of(DOCUMENT_STATUS, "CO", POSTED, "Y");
        Map<String, String> notCosted = Map.of(DOCUMENT_STATUS, "CO", POSTED, "NC");

        assertNow(declared, draft, "post", false);
        assertNow(declared, completed, "post", true);
        assertNow(declared, notCosted, "post", true);
        assertNow(declared, booked, "post", false);
        assertNow(declared, completed, "unpost", false);
        assertNow(declared, booked, "unpost", true);
        assertNow(declared, draft, "importOrderLines", true);
        assertNow(declared, booked, "importOrderLines", false);
      }
    }

    @Test
    @DisplayName("purchase and sales order: create the follow-up document only from a completed order")
    void orderFollowUps() throws JSONException {
      Map<String, NeoActionContract> purchase = new PurchaseOrderHeaderHandler().actionContracts();
      Map<String, NeoActionContract> sales = new SalesOrderHeaderHandler().actionContracts();

      assertNow(purchase, Map.of(DOCUMENT_STATUS, "CO"), "createGoodsReceipt", true);
      assertNow(purchase, Map.of(DOCUMENT_STATUS, "DR"), "createGoodsReceipt", false);
      assertNow(purchase, Map.of(DOCUMENT_STATUS, "CO"), "createPurchaseInvoice", true);
      assertNow(sales, Map.of(DOCUMENT_STATUS, "CO"), "createShipment", true);
      assertNow(sales, Map.of(DOCUMENT_STATUS, "DR"), "createShipment", false);
      assertNow(sales, Map.of(DOCUMENT_STATUS, "CO"), "createDraftInvoice", true);
    }

    @Test
    @DisplayName("a property the record cannot give leaves the action without a verdict")
    void unreadablePropertyGivesNoVerdict() throws JSONException {
      JSONObject response = catalogue(new GoodsReceiptHeaderHandler().actionContracts(),
          record(Map.of(), Map.of()));

      assertFalse(entry(response, DOCUMENT_ACTION).has("availableNow"));
    }

    @Test
    @DisplayName("a derived action:value rules ONE value out and keeps the rest of the action")
    void derivedValueRulesOutOneValue() throws JSONException {
      JSONObject button = buttonField(DOCUMENT_ACTION, "Process Order");
      button.put("column", "DocAction");
      JSONArray fields = new JSONArray().put(button);
      McpActionsSection.View config = withAvailability(
          "{\"actions\":{\"valuesByStatus\":{\"documentAction\":{\"property\":"
              + "\"documentStatus\",\"values\":{\"DR\":[\"CO\",\"VO\",\"PR\"],"
              + "\"CO\":[\"CL\",\"RE\"]}}}}}");

      JSONObject response = McpActionsView.buildResponse("sales-order", "header", fields,
          Map.of(), config, Collections.emptySet(), record(Map.of(DOCUMENT_STATUS, "CO"),
              Map.of(NeoActionAvailability.valueKey(DOCUMENT_ACTION, "RE"), "Linked documents.")));

      JSONObject action = entry(response, DOCUMENT_ACTION);
      assertTrue(action.getBoolean("availableNow"), "Close still applies");
      assertEquals(1, action.getJSONArray("availableValuesNow").length());
      assertEquals("CL", action.getJSONArray("availableValuesNow").getString(0));
      assertEquals("Linked documents.", action.getJSONObject("unavailableValues").getString("RE"));
    }

    @Test
    @DisplayName("an AD button gets its availability from MCP_CONFIG, static and judged")
    void buttonAvailabilityFromConfig() throws JSONException {
      McpActionsSection.View config = withAvailability(
          "{\"actions\":{\"valuesByStatus\":{\"DocAction\":{\"property\":\"documentStatus\","
              + "\"values\":{\"DR\":[\"CO\"],\"CO\":[\"RC\",\"RE\"]}}}}}");

      JSONObject withoutRecord = invoiceButtonCatalogue(config, null);
      JSONObject completed = invoiceButtonCatalogue(config,
          record(Map.of(DOCUMENT_STATUS, "CO"), Map.of()));
      JSONObject voided = invoiceButtonCatalogue(config,
          record(Map.of(DOCUMENT_STATUS, "VO"), Map.of()));

      assertTrue(entry(withoutRecord, DOCUMENT_ACTION).has("valuesByStatus"));
      assertFalse(entry(withoutRecord, DOCUMENT_ACTION).has("availableNow"));
      assertEquals("RC", entry(completed, DOCUMENT_ACTION).getJSONArray("availableValuesNow")
          .getString(0));
      assertFalse(entry(voided, DOCUMENT_ACTION).has("availableNow"),
          "a status the config never listed (VO) is not judged");
    }

    @Test
    @DisplayName("a voided goods document is not judged and gets no completed-document note")
    void voidedGoodsDocumentIsNotJudged() throws JSONException {
      JSONObject voided = entry(catalogue(new GoodsReceiptHeaderHandler().actionContracts(),
          record(Map.of(DOCUMENT_STATUS, "VO"), Map.of())), DOCUMENT_ACTION);

      assertFalse(voided.has("availableNow"), voided.toString());
      assertFalse(voided.has("unavailableReason"), voided.toString());
    }

    private JSONObject invoiceButtonCatalogue(McpActionsSection.View config,
        McpActionAvailabilityContext record) throws JSONException {
      JSONObject button = buttonField(DOCUMENT_ACTION, "Process Invoice");
      button.put("column", "DocAction");
      return McpActionsView.buildResponse("purchase-invoice", "header", new JSONArray().put(button),
          Map.of(), config, Collections.emptySet(), record);
    }

    @Test
    @DisplayName("an unavailable action stays listed and still counts as invokable (advisory)")
    void unavailableStaysListedAndInvokable() throws JSONException {
      JSONObject response = catalogue(new GoodsReceiptHeaderHandler().actionContracts(),
          record(Map.of(DOCUMENT_STATUS, "CO"), Map.of()));

      JSONObject complete = entry(response, DOCUMENT_ACTION);
      assertFalse(complete.getBoolean("availableNow"));
      assertEquals("etendo_action", complete.getString("invokeVia"));
      assertEquals(response.getInt("actionCount"), response.getInt("invokableCount"));
    }
  }

  // ── ETP-5714: the record must be visible to the caller's role ─────────────

  @Nested
  @DisplayName("record visibility (ETP-5714)")
  class Visibility {

    private org.openbravo.base.structure.BaseOBObject owned(String clientId, String orgId) {
      org.openbravo.base.structure.BaseOBObject record = org.mockito.Mockito.mock(
          org.openbravo.base.structure.BaseOBObject.class,
          org.mockito.Mockito.withSettings().extraInterfaces(
              org.openbravo.base.structure.ClientEnabled.class,
              org.openbravo.base.structure.OrganizationEnabled.class));
      org.openbravo.model.ad.system.Client client = org.mockito.Mockito.mock(
          org.openbravo.model.ad.system.Client.class);
      org.mockito.Mockito.when(client.getId()).thenReturn(clientId);
      org.openbravo.model.common.enterprise.Organization organisation = org.mockito.Mockito.mock(
          org.openbravo.model.common.enterprise.Organization.class);
      org.mockito.Mockito.when(organisation.getId()).thenReturn(orgId);
      org.mockito.Mockito.when(((org.openbravo.base.structure.ClientEnabled) record).getClient())
          .thenReturn(client);
      org.mockito.Mockito.when(
          ((org.openbravo.base.structure.OrganizationEnabled) record).getOrganization())
          .thenReturn(organisation);
      return record;
    }

    private boolean visible(org.openbravo.base.structure.BaseOBObject record, String[] clients,
        String[] orgs) {
      try (org.mockito.MockedStatic<org.openbravo.dal.core.OBContext> ctx =
          org.mockito.Mockito.mockStatic(org.openbravo.dal.core.OBContext.class)) {
        org.openbravo.dal.core.OBContext context =
            org.mockito.Mockito.mock(org.openbravo.dal.core.OBContext.class);
        ctx.when(org.openbravo.dal.core.OBContext::getOBContext).thenReturn(context);
        org.mockito.Mockito.when(context.getReadableClients()).thenReturn(clients);
        org.mockito.Mockito.when(context.getReadableOrganizations()).thenReturn(orgs);
        return McpActionAvailabilityContext.isVisibleToRole(record);
      }
    }

    @Test
    @DisplayName("a record of a readable client and organisation is visible")
    void readableRecordIsVisible() {
      assertTrue(visible(owned("C1", "O1"), new String[] { "C1" }, new String[] { "O1", "O2" }));
    }

    @Test
    @DisplayName("a record of an organisation the role cannot read is NOT visible (answered as missing)")
    void unreadableOrganisationIsHidden() {
      assertFalse(visible(owned("C1", "O9"), new String[] { "C1" }, new String[] { "O1" }));
    }

    @Test
    @DisplayName("a record of a client the role cannot read is NOT visible")
    void unreadableClientIsHidden() {
      assertFalse(visible(owned("C9", "O1"), new String[] { "C1" }, new String[] { "O1" }));
    }

    @Test
    @DisplayName("a record that carries neither client nor organisation has nothing to hide")
    void recordWithoutOwnerIsVisible() {
      assertTrue(visible(org.mockito.Mockito.mock(org.openbravo.base.structure.BaseOBObject.class),
          new String[0], new String[0]));
    }

    @Test
    @DisplayName("forRecord answers null — the router's 404 — for an invisible record, like a missing id")
    void forRecordAnswersNullForAnInvisibleRecord() {
      org.openbravo.base.structure.BaseOBObject invisible = owned("C1", "O9");
      org.openbravo.base.model.Entity dalEntity = org.mockito.Mockito.mock(org.openbravo.base.model.Entity.class);
      org.mockito.Mockito.when(dalEntity.getName()).thenReturn("Order");
      try (org.mockito.MockedStatic<org.openbravo.dal.service.OBDal> dalStatic =
          org.mockito.Mockito.mockStatic(org.openbravo.dal.service.OBDal.class);
          org.mockito.MockedStatic<org.openbravo.dal.core.OBContext> ctx =
              org.mockito.Mockito.mockStatic(org.openbravo.dal.core.OBContext.class)) {
        org.openbravo.dal.service.OBDal dal = org.mockito.Mockito.mock(org.openbravo.dal.service.OBDal.class);
        dalStatic.when(org.openbravo.dal.service.OBDal::getInstance).thenReturn(dal);
        org.mockito.Mockito.when(dal.get("Order", "X1")).thenReturn(invisible);
        org.mockito.Mockito.when(dal.get("Order", "NOPE")).thenReturn(null);
        org.openbravo.dal.core.OBContext context =
            org.mockito.Mockito.mock(org.openbravo.dal.core.OBContext.class);
        ctx.when(org.openbravo.dal.core.OBContext::getOBContext).thenReturn(context);
        org.mockito.Mockito.when(context.getReadableClients()).thenReturn(new String[] { "C1" });
        org.mockito.Mockito.when(context.getReadableOrganizations()).thenReturn(new String[] { "O1" });

        assertNull(McpActionAvailabilityContext.forRecord(null, dalEntity, "X1"));
        assertNull(McpActionAvailabilityContext.forRecord(null, dalEntity, "NOPE"),
            "identical to a nonexistent id");
      }
    }
  }

  /** A config view with the given {@code MCP_CONFIG}, through the real section parser. */
  private static McpActionsSection.View withAvailability(String mcpConfig) {
    com.etendoerp.go.schemaforge.data.SFEntity entity =
        org.mockito.Mockito.mock(com.etendoerp.go.schemaforge.data.SFEntity.class);
    org.mockito.Mockito.when(entity.get(McpEntityConfig.PROPERTY_MCP_CONFIG)).thenReturn(mcpConfig);
    org.mockito.Mockito.when(entity.getId()).thenReturn("avail-entity-" + (++sequence));
    org.mockito.Mockito.when(entity.getName()).thenReturn("entity");
    return McpActionsSection.forEntity(entity);
  }
}
