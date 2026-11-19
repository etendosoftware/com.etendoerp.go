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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.module.sii.utils.SIIUtils;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Shared stubs and checks for the {@code sifDefaults} read action every invoice-creating header
 * declares (ETP-6179): sales order, quotation, goods shipment, purchase order, goods receipt and
 * both returns. Each header's own test drives the action through its {@code handle()}; this
 * holds what would otherwise be seven copies of the same static mocks and of the response-shape
 * assertions, which would drift the moment the preview gains a key.
 */
final class InvoiceSifPreviewTestSupport {

  static final String ORG_ID = "org-sif";
  static final String SII_DESCRIPTION = "Venta de mercaderías";
  static final String OPERATION_DATE = "2026-11-05";

  private InvoiceSifPreviewTestSupport() {
  }

  /**
   * The static boundaries the preview crosses: the DAL, admin mode, the three fiscal systems'
   * configuration (all three ACTIVE for the organization, so a purchase proves it ignores
   * VERI*FACTU and TicketBAI) and the SII configuration (none, so no authorization number).
   */
  static final class PreviewMocks implements AutoCloseable {
    final OBDal dal = mock(OBDal.class);
    private final MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
    private final MockedStatic<OBContext> contextMock = Mockito.mockStatic(OBContext.class);
    private final MockedStatic<RectificativeSupport> rectifMock =
        Mockito.mockStatic(RectificativeSupport.class, Mockito.CALLS_REAL_METHODS);
    private final MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
        Mockito.mockStatic(SalesRectificativeFiscalDefaults.class);
    private final MockedStatic<SIIUtils> siiMock = Mockito.mockStatic(SIIUtils.class);

    /**
     * @param rectificative whether the document type the creation would use is rectificative
     */
    PreviewMocks(boolean rectificative) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      rectifMock.when(() -> RectificativeSupport.isRectificative(any())).thenReturn(rectificative);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(anyString()))
          .thenReturn(true);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.tbaiConfigActive(anyString()))
          .thenReturn(true);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.verifactuConfigActive(anyString()))
          .thenReturn(true);
      siiMock.when(() -> SIIUtils.getSiiConfigFromOrg(any())).thenReturn(null);
    }

    @Override
    public void close() {
      siiMock.close();
      defaultsMock.close();
      rectifMock.close();
      contextMock.close();
      dalMock.close();
    }
  }

  /**
   * A source order whose order type links an invoice type (with a sequence) of category
   * {@code invoiceCategory} configuring VERI*FACTU type {@code verifactuType} and description
   * "Ventas"; the order carries its own SII description and operation date (2026-11-05).
   */
  static Order sourceOrder(String invoiceCategory, String verifactuType) throws Exception {
    DocumentType invoiceType = mock(DocumentType.class);
    when(invoiceType.getDocumentSequence()).thenReturn(mock(Sequence.class));
    when(invoiceType.getDocumentCategory()).thenReturn(invoiceCategory);
    when(invoiceType.getEtvfacInvType()).thenReturn(verifactuType);
    when(invoiceType.getEtvfacVerifacDesc()).thenReturn("Ventas");
    DocumentType orderType = mock(DocumentType.class);
    when(orderType.getDocumentTypeForInvoice()).thenReturn(invoiceType);
    Order order = mock(Order.class);
    Organization org = org();
    when(order.getOrganization()).thenReturn(org);
    when(order.getBusinessPartner()).thenReturn(mock(BusinessPartner.class));
    when(order.getTransactionDocument()).thenReturn(orderType);
    when(order.getAeatsiiDescripcionSii()).thenReturn(SII_DESCRIPTION);
    when(order.getAeatsiiFechaOperacion())
        .thenReturn(new SimpleDateFormat("yyyy-MM-dd").parse(OPERATION_DATE));
    return order;
  }

  /**
   * Asserts the sales preview of a normal invoice from {@link #sourceOrder}: SII and
   * VERI*FACTU (TicketBAI has no field on a normal invoice), the invoice type's VERI*FACTU type
   * and description, the customer's default SII key F1 and the order's SII description and
   * operation date.
   */
  static void assertNormalSalesPreview(JSONObject data, String verifactuType)
      throws JSONException {
    assertEquals(List.of("SII", "Verifactu"), systems(data));
    assertTrue(data.getBoolean("sales"));
    assertFalse(data.getBoolean("rectificative"));
    assertFalse(data.getBoolean("siiAuthorizationAvailable"));
    JSONObject fields = data.getJSONObject("fields");
    assertEquals(verifactuType, fields.getString(Invoice.PROPERTY_ETVFACINVTYPE));
    assertEquals("Ventas", fields.getString(Invoice.PROPERTY_ETVFACVERIFACDESC));
    assertEquals("F1", fields.getString(Invoice.PROPERTY_AEATSIICLAVETIPO));
    assertEquals(SII_DESCRIPTION, fields.getString(Invoice.PROPERTY_AEATSIIDESCRIPCIONSII));
    assertEquals(OPERATION_DATE, fields.getString(Invoice.PROPERTY_ETSGDATEOPERATION));
  }

  /**
   * Asserts the purchase preview of a normal invoice: SII only — even with VERI*FACTU and
   * TicketBAI configured — with the purchase SII key F1 and the accounting registration date.
   */
  static void assertNormalPurchasePreview(JSONObject data) throws JSONException {
    assertEquals(List.of("SII"), systems(data));
    assertFalse(data.getBoolean("sales"));
    assertFalse(data.getBoolean("rectificative"));
    JSONObject fields = data.getJSONObject("fields");
    assertEquals("F1", fields.getString(Invoice.PROPERTY_AEATSIICLAVETIPOFC));
    assertTrue(fields.has(Invoice.PROPERTY_AEATSIIFECHAREGCONT));
    assertNoVerifactuOrTicketBaiField(data);
  }

  /** The organization every source document of these tests belongs to. */
  static Organization org() {
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    return org;
  }

  /** {@code POST sifDefaults} on the record {@code recordId} of {@code specName}'s header. */
  static NeoContext sifDefaults(String specName, String recordId) {
    return NeoContext.builder()
        .specName(specName).entityName("header")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(InvoiceSifPreset.ACTION_SIF_DEFAULTS).recordId(recordId)
        .build();
  }

  /**
   * Asserts {@code response} is the 200 preview, with every documented key, a spec for every
   * previewed field and a previewed value for every spec; returns its {@code data}.
   */
  static JSONObject assertPreview(NeoResponse response) throws JSONException {
    assertNotNull("sifDefaults must be answered by the header", response);
    assertEquals(200, response.getHttpStatus());
    JSONObject data = response.getBody().getJSONObject("response").getJSONObject("data");
    for (String key : List.of("systems", "sales", "rectificative", "fields", "fieldSpecs",
        "siiAuthorizationAvailable")) {
      assertTrue("preview must carry " + key + ": " + data, data.has(key));
    }
    List<String> specNames = new ArrayList<>();
    JSONArray specs = data.getJSONArray("fieldSpecs");
    for (int i = 0; i < specs.length(); i++) {
      JSONObject spec = specs.getJSONObject(i);
      assertTrue(spec.toString(), spec.has("name") && spec.has("system") && spec.has("type"));
      if ("option".equals(spec.getString("type"))) {
        assertTrue(spec.toString(), spec.getJSONArray("options").length() > 0);
      }
      specNames.add(spec.getString("name"));
    }
    List<String> fieldNames = new ArrayList<>();
    Iterator<?> keys = data.getJSONObject("fields").keys();
    while (keys.hasNext()) {
      fieldNames.add(String.valueOf(keys.next()));
    }
    assertEquals("every previewed field has a spec, and only those",
        new java.util.TreeSet<>(specNames), new java.util.TreeSet<>(fieldNames));
    return data;
  }

  /** The systems the preview lists, in order. */
  static List<String> systems(JSONObject data) throws JSONException {
    JSONArray array = data.getJSONArray("systems");
    List<String> systems = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      systems.add(array.getString(i));
    }
    return systems;
  }

  /** A purchase preview never carries a VERI*FACTU or TicketBAI field. */
  static void assertNoVerifactuOrTicketBaiField(JSONObject data) throws JSONException {
    Iterator<?> keys = data.getJSONObject("fields").keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      assertFalse(key, key.startsWith("etvfac") || key.startsWith("tbai"));
    }
  }

  /**
   * The {@code sifDefaults} contract: a read action (not mutating) that creates nothing,
   * documenting its response shape.
   */
  static void assertReadOnlySifDefaultsContract(NeoActionContract contract) {
    assertNotNull("sifDefaults must be declared", contract);
    assertEquals(InvoiceSifPreset.ACTION_SIF_DEFAULTS, contract.getName());
    assertFalse("sifDefaults only reads", contract.isMutating());
    assertEquals("POST", contract.getHttpMethod());
    String description = contract.getDescription();
    assertTrue(description, description.contains("without creating anything"));
    for (String key : List.of("systems", "rectificative", "fields", "fieldSpecs",
        "siiAuthorizationAvailable", InvoiceSifPreset.PARAM_SIF_FIELDS)) {
      assertTrue(key + " must be documented: " + description, description.contains(key));
    }
  }
}
