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
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.plm.ProductCategory;
import org.openbravo.model.common.uom.UOM;

import com.etendoerp.go.schemaforge.DefaultTaxResolver.Determination;
import com.etendoerp.go.schemaforge.DefaultTaxResolver.Result;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.ocr.OcrDocType;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.AddressState;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Candidate;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicates;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.TaxAnswer;
import com.etendoerp.go.schemaforge.ocr.OcrReviewAssembler;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.schemaforge.util.NeoTrl;

/**
 * The purchase-invoice lookups of the OCR review: each one must reach the SAME server code the
 * application's review reaches (the invoice header's own selectors, the similarity search, the
 * {@code defaultTax} rule), with the context the invoice form gives, and must answer "nothing" or
 * "unknown" — never throw — when that code fails. The server edges are mocked.
 *
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceOcrLookups
 * @covers com.etendoerp.go.schemaforge.OcrLookupsRegistry
 * @covers com.etendoerp.go.schemaforge.ocr.OcrProductMatch
 */
@DisplayName("ETP-5715 — PurchaseInvoiceOcrLookups")
class PurchaseInvoiceOcrLookupsTest {

  private MockedStatic<NeoServletSupport> supportMock;
  private MockedStatic<NeoSelectorService> selectorMock;
  private MockedStatic<NeoSimSearchEndpoint> simMock;
  private MockedStatic<PurchaseInvoiceLineHandler> taxMock;
  private MockedStatic<OBDal> dalMock;
  private MockedStatic<OBContext> contextMock;
  private MockedStatic<TenantOwnership> tenantMock;
  private OBDal dal;
  private OBDal readOnlyDal;
  private final PurchaseInvoiceOcrLookups lookups = new PurchaseInvoiceOcrLookups();

  @BeforeEach
  void setUp() {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("SPEC1");
    supportMock = mockStatic(NeoServletSupport.class);
    supportMock.when(() -> NeoServletSupport.findSpec("purchase-invoice")).thenReturn(spec);
    selectorMock = mockStatic(NeoSelectorService.class);
    simMock = mockStatic(NeoSimSearchEndpoint.class);
    taxMock = mockStatic(PurchaseInvoiceLineHandler.class);
    dal = mock(OBDal.class);
    readOnlyDal = mock(OBDal.class);
    dalMock = mockStatic(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
    dalMock.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);
    contextMock = mockStatic(OBContext.class);
    tenantMock = mockStatic(TenantOwnership.class);
  }

  @AfterEach
  void tearDown() {
    tenantMock.close();
    contextMock.close();
    dalMock.close();
    taxMock.close();
    simMock.close();
    selectorMock.close();
    supportMock.close();
  }

  private static NeoResponse items(JSONObject... rows) throws Exception {
    JSONArray array = new JSONArray();
    for (JSONObject row : rows) {
      array.put(row);
    }
    return NeoResponse.ok(new JSONObject().put("items", array));
  }

  private void selectorAnswers(NeoResponse answer) {
    selectorMock.when(() -> NeoSelectorService.querySelector(anyString(), anyString(), anyString(),
        any(), anyInt(), anyInt(), anyMap())).thenReturn(answer);
  }

  // ── vendor and address: the invoice header's own selectors ─────────────────

  @Nested
  @DisplayName("vendor and address")
  class VendorAndAddress {

    @Test
    @DisplayName("the vendor search is the header's C_BPartner_ID selector with the form's context")
    void vendorSearchIsTheHeaderSelector() throws Exception {
      selectorAnswers(items(new JSONObject().put("id", "V1").put("label", "Blanquiceleste S.A."),
          new JSONObject().put("label", "no id"), new JSONObject().put("id", "V2").put("name", "Otra")));

      List<Party> parties = lookups.searchVendors("Blanquiceleste", 20);

      assertEquals(2, parties.size());
      assertEquals("V1", parties.get(0).id());
      assertEquals("Blanquiceleste S.A.", parties.get(0).name());
      assertEquals("Otra", parties.get(1).name());
      @SuppressWarnings("unchecked")
      ArgumentCaptor<Map<String, String>> context = ArgumentCaptor.forClass(Map.class);
      selectorMock.verify(() -> NeoSelectorService.querySelector(eq("SPEC1"), eq("header"),
          eq("C_BPartner_ID"), eq("Blanquiceleste"), eq(20), eq(0), context.capture()));
      assertEquals(Map.of("isSOTrx", "N", "isVendor", "Y"), context.getValue());
    }

    @Test
    @DisplayName("a selector that fails, or a spec that is not there, is an empty search")
    void failuresAreEmpty() {
      selectorAnswers(NeoResponse.error(500, "boom"));
      assertTrue(lookups.searchVendors("x", 20).isEmpty());

      selectorMock.when(() -> NeoSelectorService.querySelector(anyString(), anyString(),
          anyString(), any(), anyInt(), anyInt(), anyMap())).thenThrow(new IllegalStateException("x"));
      assertTrue(lookups.searchVendors("x", 20).isEmpty());

      supportMock.when(() -> NeoServletSupport.findSpec("purchase-invoice")).thenReturn(null);
      assertTrue(lookups.searchVendors("x", 20).isEmpty());
    }

    @Test
    @DisplayName("the address is the header's C_BPartner_Location_ID selector for that vendor, first row")
    void addressIsTheFirstRowOfTheLocationSelector() throws Exception {
      selectorAnswers(items(new JSONObject().put("id", "LOC1").put("label", "Calle 1")));

      Address address = lookups.vendorAddress("V1");

      assertEquals(AddressState.PRESENT, address.state());
      assertEquals("LOC1", address.locationId());
      @SuppressWarnings("unchecked")
      ArgumentCaptor<Map<String, String>> context = ArgumentCaptor.forClass(Map.class);
      selectorMock.verify(() -> NeoSelectorService.querySelector(eq("SPEC1"), eq("header"),
          eq("C_BPartner_Location_ID"), isNull(), eq(1), eq(0), context.capture()));
      assertEquals(Map.of("isSOTrx", "N", "isVendor", "Y", "C_BPartner_ID", "V1"),
          context.getValue());
    }

    @Test
    @DisplayName("no rows is MISSING; a selector that failed, or an answer without items, is UNKNOWN")
    void missingTellsApartFromUnknown() throws Exception {
      selectorAnswers(items());
      assertEquals(AddressState.MISSING, lookups.vendorAddress("V1").state());
      assertNull(lookups.vendorAddress("V1").locationId());

      selectorAnswers(NeoResponse.error(500, "boom"));
      assertEquals(AddressState.UNKNOWN, lookups.vendorAddress("V1").state());

      selectorAnswers(NeoResponse.ok(new JSONObject()));
      assertEquals(AddressState.UNKNOWN, lookups.vendorAddress("V1").state());
    }

    @Test
    @DisplayName("a vendor by id is returned only when active, a vendor, and visible to the tenant")
    void vendorById() {
      BusinessPartner vendor = partner("V1", "Proveedor", true, true);
      when(dal.get(BusinessPartner.class, "V1")).thenReturn(vendor);
      tenantMock.when(() -> TenantOwnership.isVisibleToCurrentTenant(vendor)).thenReturn(true);
      assertEquals("Proveedor", lookups.vendorById("V1").orElseThrow().name());

      tenantMock.when(() -> TenantOwnership.isVisibleToCurrentTenant(vendor)).thenReturn(false);
      assertTrue(lookups.vendorById("V1").isEmpty(), "another tenant's partner");

      BusinessPartner customer = partner("C1", "Cliente", false, true);
      when(dal.get(BusinessPartner.class, "C1")).thenReturn(customer);
      tenantMock.when(() -> TenantOwnership.isVisibleToCurrentTenant(customer)).thenReturn(true);
      assertTrue(lookups.vendorById("C1").isEmpty(), "not a vendor");

      BusinessPartner inactive = partner("I1", "Baja", true, false);
      when(dal.get(BusinessPartner.class, "I1")).thenReturn(inactive);
      tenantMock.when(() -> TenantOwnership.isVisibleToCurrentTenant(inactive)).thenReturn(true);
      assertTrue(lookups.vendorById("I1").isEmpty(), "inactive");

      assertTrue(lookups.vendorById("nope").isEmpty());
      assertTrue(lookups.vendorById(" ").isEmpty());
      assertTrue(lookups.vendorById(null).isEmpty());
    }

    @Test
    @DisplayName("a DAL failure is an empty answer")
    void vendorByIdFailure() {
      when(dal.get(BusinessPartner.class, "V1")).thenThrow(new IllegalStateException("db"));

      assertTrue(lookups.vendorById("V1").isEmpty());
    }

    private BusinessPartner partner(String id, String name, boolean vendor, boolean active) {
      BusinessPartner partner = mock(BusinessPartner.class);
      when(partner.getId()).thenReturn(id);
      when(partner.getName()).thenReturn(name);
      when(partner.isVendor()).thenReturn(vendor);
      when(partner.isActive()).thenReturn(active);
      return partner;
    }
  }

  // ── duplicates ─────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("duplicates")
  class DuplicateInvoices {

    @SuppressWarnings("unchecked")
    private OBQuery<Invoice> query(Invoice... found) {
      OBQuery<Invoice> query = mock(OBQuery.class);
      when(query.list()).thenReturn(List.of(found));
      when(dal.createQuery(eq(Invoice.class), anyString())).thenReturn(query);
      return query;
    }

    @Test
    @DisplayName("same vendor, same supplier number case-insensitively, purchase side, not voided, at most 10")
    void queryShape() {
      Invoice existing = mock(Invoice.class);
      when(existing.getId()).thenReturn("INV9");
      when(existing.getDocumentNo()).thenReturn("1000007");
      OBQuery<Invoice> query = query(existing);

      Duplicates found = lookups.duplicates("V1", "  2024/000123 ");

      assertTrue(found.known());
      assertEquals("INV9", found.documents().get(0).id());
      assertEquals("1000007", found.documents().get(0).documentNo());
      ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
      verify(dal).createQuery(eq(Invoice.class), hql.capture());
      assertTrue(hql.getValue().contains("i.salesTransaction = false"), hql.getValue());
      assertTrue(hql.getValue().contains("i.businessPartner.id = :vendorId"));
      assertTrue(hql.getValue().contains("upper(trim(i.orderReference)) = :reference"));
      assertTrue(hql.getValue().contains("i.documentStatus <> :voided"));
      verify(query).setNamedParameter("vendorId", "V1");
      verify(query).setNamedParameter("reference", "2024/000123");
      verify(query).setNamedParameter("voided", "VO");
      verify(query).setMaxResult(10);
    }

    @Test
    @DisplayName("the supplier number is searched in upper case whatever case it was read in")
    void referenceIsUpperCased() {
      OBQuery<Invoice> query = query();

      lookups.duplicates("V1", "fa-001/ab");

      verify(query).setNamedParameter("reference", "FA-001/AB");
    }

    @Test
    @DisplayName("nothing found is a known, empty answer; a blank number or vendor is not asked at all")
    void emptyAndBlank() {
      query();
      assertTrue(lookups.duplicates("V1", "X1").known());
      assertTrue(lookups.duplicates("V1", "X1").documents().isEmpty());

      assertFalse(lookups.duplicates("V1", "  ").known());
      assertFalse(lookups.duplicates(null, "X1").known());
    }

    @Test
    @DisplayName("a failing query is unknown, not an error")
    void failure() {
      when(dal.createQuery(eq(Invoice.class), anyString())).thenThrow(new IllegalStateException("db"));

      Duplicates found = lookups.duplicates("V1", "X1");

      assertFalse(found.known());
      assertTrue(found.documents().isEmpty());
    }
  }

  // ── products ───────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("products")
  class Products {

    @Test
    @DisplayName("each description is one similarity search: Product, 3 candidates, from 50 %")
    void similaritySearchPerDescription() throws Exception {
      simMock.when(() -> NeoSimSearchEndpoint.searchTerm(anyString(), anyString(), anyInt(),
          anyString())).thenReturn(new JSONObject().put("data", new JSONArray()
              .put(new JSONObject().put("id", "P1").put("name", "Tornillo M8")
                  .put("similarity_percent", "100.0000%"))
              .put(new JSONObject().put("id", "P2").put("_identifier", "Tuerca M8"))));

      List<List<Candidate>> found = lookups.productCandidates(List.of("Tornillo M8", "Tuerca"));

      assertEquals(2, found.size());
      assertEquals("P1", found.get(0).get(0).id());
      assertEquals("Tornillo M8", found.get(0).get(0).name());
      assertEquals("100.0000", found.get(0).get(0).similarity());
      assertEquals("Tuerca M8", found.get(0).get(1).name());
      assertNull(found.get(0).get(1).similarity());
      simMock.verify(() -> NeoSimSearchEndpoint.searchTerm("Product", "Tornillo M8", 3, "50"));
      simMock.verify(() -> NeoSimSearchEndpoint.searchTerm("Product", "Tuerca", 3, "50"));
    }

    @Test
    @DisplayName("a row without an id is not a candidate, and a checked failure is per line too")
    void blankIdsAreFilteredAndCheckedFailuresAreCaught() throws Exception {
      simMock.when(() -> NeoSimSearchEndpoint.searchTerm(eq("Product"), eq("a"), anyInt(),
          anyString())).thenReturn(new JSONObject().put("data", new JSONArray()
              .put(new JSONObject().put("name", "no id"))
              .put(new JSONObject().put("id", "").put("name", "blank id"))
              .put(new JSONObject().put("id", "P1").put("name", "ok"))));
      simMock.when(() -> NeoSimSearchEndpoint.searchTerm(eq("Product"), eq("b"), anyInt(),
          anyString())).thenThrow(new ClassNotFoundException("Product"));

      List<List<Candidate>> found = lookups.productCandidates(List.of("a", "b"));

      assertEquals(1, found.get(0).size());
      assertEquals("P1", found.get(0).get(0).id());
      assertTrue(found.get(1).isEmpty());
    }

    @Test
    @DisplayName("the rows may also come under response.data, like the application reads them")
    void responseDataShape() throws Exception {
      simMock.when(() -> NeoSimSearchEndpoint.searchTerm(anyString(), anyString(), anyInt(),
          anyString())).thenReturn(new JSONObject().put("response", new JSONObject().put("data",
              new JSONArray().put(new JSONObject().put("id", "P1").put("name", "A")))));

      assertEquals("P1", lookups.productCandidates(List.of("A")).get(0).get(0).id());
    }

    @Test
    @DisplayName("a blank description, no answer or a failing search is no candidates for that line only")
    void failuresArePerLine() throws Exception {
      simMock.when(() -> NeoSimSearchEndpoint.searchTerm(eq("Product"), eq("good"), anyInt(),
          anyString())).thenReturn(new JSONObject().put("data", new JSONArray()
              .put(new JSONObject().put("id", "P1").put("name", "good"))));
      simMock.when(() -> NeoSimSearchEndpoint.searchTerm(eq("Product"), eq("bad"), anyInt(),
          anyString())).thenThrow(new IllegalStateException("x"));
      simMock.when(() -> NeoSimSearchEndpoint.searchTerm(eq("Product"), eq(""), anyInt(),
          anyString())).thenReturn(null);

      List<List<Candidate>> found = lookups.productCandidates(List.of("bad", "", "good"));

      assertTrue(found.get(0).isEmpty());
      assertTrue(found.get(1).isEmpty());
      assertEquals("P1", found.get(2).get(0).id());
    }

    private JSONObject simRow(String id, String name, String percent) throws Exception {
      return new JSONObject().put("id", id).put("name", name).put("similarity_percent", percent)
          .put("text_similarity_percent", "80.0000%").put("exact_match", false);
    }

    /** The product decision the review raises for one line whose similarity search answers rows. */
    private JSONObject reviewedProduct(JSONArray rows) throws Exception {
      simMock.when(() -> NeoSimSearchEndpoint.searchTerm(anyString(), anyString(), anyInt(),
          anyString())).thenReturn(new JSONObject().put("data", rows));
      OcrDocType type = new OcrDocType("purchase-invoice", "SimpleOcrTool", "purchase-invoice", "q",
          new JSONObject(), List.of("vendor_name"));
      JSONObject extraction = new JSONObject().put("line_items", new JSONArray().put(new JSONObject()
          .put("description", "Servicios web").put("quantity", 1).put("unit_price", 1)));
      JSONArray decisions = new OcrReviewAssembler(type, lookups).assemble("STAGED1", extraction, null)
          .getJSONArray("pendingDecisions");
      for (int i = 0; i < decisions.length(); i++) {
        if ("line_0_product".equals(decisions.getJSONObject(i).getString("id"))) {
          return decisions.getJSONObject(i);
        }
      }
      throw new AssertionError("the review must raise line_0_product");
    }

    @Test
    @DisplayName("the % of a real 96.7000% row is stripped, so the single top >= 90 is auto-matched")
    void percentSuffixIsStrippedAndSingleTopAutoMatches() throws Exception {
      JSONArray rows = new JSONArray().put(simRow("P1", "Servicio webs", "96.7000%"));

      assertEquals("96.7000", stripped(rows));

      JSONObject product = reviewedProduct(rows);
      assertEquals("auto_matched", product.getString("state"));
      assertEquals("P1", product.getJSONObject("match").getString("id"));
    }

    @Test
    @DisplayName("a tie of two 96.7000% rows is only suggested: the first row")
    void tieOfPercentRowsIsSuggested() throws Exception {
      JSONObject product = reviewedProduct(new JSONArray()
          .put(simRow("P1", "Servicio webs", "96.7000%")).put(simRow("P2", "Servicio web", "96.7000%")));
      assertEquals("suggested", product.getString("state"));
      assertEquals("P1", product.getJSONObject("suggestion").getString("id"));
    }

    private String stripped(JSONArray rows) throws Exception {
      simMock.when(() -> NeoSimSearchEndpoint.searchTerm(anyString(), anyString(), anyInt(),
          anyString())).thenReturn(new JSONObject().put("data", rows));
      return lookups.productCandidates(List.of("Servicios web")).get(0).get(0).similarity();
    }
  }

  // ── taxes ──────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("taxes")
  class Taxes {

    private Determination tax(String id, String name, String rate) {
      return new Determination(id, name, new BigDecimal(rate), false, "CAT", "Standard", true,
          "C", 1);
    }

    @Test
    @DisplayName("the tax is the defaultTax rule for the address, the date at start of day and the rate")
    void usesTheDefaultTaxRule() {
      Date expectedDate = Date.from(LocalDate.parse("2024-06-18").atStartOfDay(
          ZoneId.systemDefault()).toInstant());
      taxMock.when(() -> PurchaseInvoiceLineHandler.resolveOwned("LOC1", null, expectedDate,
          new BigDecimal("21"))).thenReturn(new Result(tax("T21", "IVA 21%", "21"),
              DefaultTaxResolver.SOURCE_RATE_MATCH, List.of(tax("T21b", "IVA 21% rec", "21"))));

      TaxAnswer answer = lookups.defaultTax("LOC1", "2024-06-18", new BigDecimal("21"));

      assertTrue(answer.known());
      assertEquals("T21", answer.chosen().id());
      assertEquals("IVA 21%", answer.chosen().name());
      assertEquals(0, new BigDecimal("21").compareTo(answer.chosen().rate()));
      assertEquals("T21b", answer.candidates().get(0).id());
    }

    @Test
    @DisplayName("no tax with that rate is a known answer with no chosen tax")
    void noTaxAtRate() {
      taxMock.when(() -> PurchaseInvoiceLineHandler.resolveOwned(anyString(), isNull(), any(Date.class),
          any(BigDecimal.class))).thenReturn(new Result(null, DefaultTaxResolver.SOURCE_NO_MATCH,
              Collections.emptyList()));

      TaxAnswer answer = lookups.defaultTax("LOC1", "2024-06-18", new BigDecimal("7"));

      assertTrue(answer.known());
      assertNull(answer.chosen());
    }

    @Test
    @DisplayName("an address that is not the tenant's, or a failure, is unknown")
    void unknownWhenNotAnswerable() {
      taxMock.when(() -> PurchaseInvoiceLineHandler.resolveOwned(anyString(), isNull(), any(Date.class),
          any(BigDecimal.class))).thenReturn(null);
      assertFalse(lookups.defaultTax("FOREIGN", "2024-06-18", BigDecimal.ONE).known());

      taxMock.when(() -> PurchaseInvoiceLineHandler.resolveOwned(anyString(), isNull(), any(Date.class),
          any(BigDecimal.class))).thenThrow(new IllegalStateException("x"));
      assertFalse(lookups.defaultTax("LOC1", "2024-06-18", BigDecimal.ONE).known());
    }

    @Test
    @DisplayName("a missing or malformed date is today, like defaultTax without invoiceDate")
    void dateFallsBackToToday() {
      taxMock.when(() -> PurchaseInvoiceLineHandler.resolveOwned(anyString(), isNull(), any(Date.class),
          any(BigDecimal.class))).thenReturn(new Result(null, "x", Collections.emptyList()));

      lookups.defaultTax("LOC1", null, BigDecimal.ONE);
      lookups.defaultTax("LOC1", "not a date", BigDecimal.ONE);

      ArgumentCaptor<Date> dates = ArgumentCaptor.forClass(Date.class);
      taxMock.verify(() -> PurchaseInvoiceLineHandler.resolveOwned(eq("LOC1"), isNull(),
          dates.capture(), eq(BigDecimal.ONE)), org.mockito.Mockito.times(2));
      long now = System.currentTimeMillis();
      for (Date date : dates.getAllValues()) {
        assertTrue(Math.abs(now - date.getTime()) < 60_000, "about now: " + date);
      }
    }
  }

  // ── vendors by tax id (ETP-6048) ───────────────────────────────────────────

  @Nested
  @DisplayName("vendors by tax id")
  class VendorsByTaxId {

    @SuppressWarnings("unchecked")
    private OBQuery<BusinessPartner> query(BusinessPartner... found) {
      OBQuery<BusinessPartner> query = mock(OBQuery.class);
      when(query.list()).thenReturn(List.of(found));
      when(dal.createQuery(eq(BusinessPartner.class), anyString())).thenReturn(query);
      return query;
    }

    private void sessionClient(String clientId) {
      org.openbravo.model.ad.system.Client client = mock(org.openbravo.model.ad.system.Client.class);
      when(client.getId()).thenReturn(clientId);
      OBContext context = mock(OBContext.class);
      when(context.getCurrentClient()).thenReturn(client);
      contextMock.when(OBContext::getOBContext).thenReturn(context);
    }

    private BusinessPartner partner(String id, String name) {
      BusinessPartner partner = mock(BusinessPartner.class);
      when(partner.getId()).thenReturn(id);
      when(partner.getName()).thenReturn(name);
      tenantMock.when(() -> TenantOwnership.isVisibleToCurrentTenant(partner)).thenReturn(true);
      return partner;
    }

    @Test
    @DisplayName("asks for active vendors of the session's client, by every spelling of the id, up to the limit")
    void queryShape() {
      sessionClient("CLIENT1");
      OBQuery<BusinessPartner> query = query(partner("V1", "Blanquiceleste"));

      List<Party> parties = lookups.vendorsByTaxId("ESB81639719", 10);

      assertEquals(1, parties.size());
      assertEquals("V1", parties.get(0).id());
      assertEquals("Blanquiceleste", parties.get(0).name());
      ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
      verify(dal).createQuery(eq(BusinessPartner.class), hql.capture());
      assertTrue(hql.getValue().contains("bp.vendor = true"), hql.getValue());
      assertTrue(hql.getValue().contains("bp.active = true"));
      assertTrue(hql.getValue().contains("bp.client.id = :clientId"));
      assertTrue(hql.getValue().contains("in (:spellings)"));
      verify(query).setNamedParameter("clientId", "CLIENT1");
      @SuppressWarnings("unchecked")
      ArgumentCaptor<java.util.Set<String>> spellings = ArgumentCaptor.forClass(java.util.Set.class);
      verify(query).setNamedParameter(eq("spellings"), spellings.capture());
      assertTrue(spellings.getValue().contains("ESB81639719"), "as read");
      assertTrue(spellings.getValue().contains("B81639719"), "without the prefix");
      verify(query).setMaxResult(10);
    }

    @Test
    @DisplayName("two vendors sharing a tax id are both returned, for the matcher to call it ambiguous")
    void sharedTaxIdReturnsBoth() {
      sessionClient("CLIENT1");
      query(partner("V1", "Alfa"), partner("V2", "Beta"));

      assertEquals(List.of("V1", "V2"), lookups.vendorsByTaxId("44666500V", 10).stream()
          .map(Party::id).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    @DisplayName("a partner the tenant cannot see is dropped from the answer")
    void foreignPartnersAreDropped() {
      sessionClient("CLIENT1");
      BusinessPartner foreign = mock(BusinessPartner.class);
      when(foreign.getId()).thenReturn("X1");
      tenantMock.when(() -> TenantOwnership.isVisibleToCurrentTenant(foreign)).thenReturn(false);
      query(partner("V1", "Alfa"), foreign);

      assertEquals(List.of("V1"), lookups.vendorsByTaxId("44666500V", 10).stream()
          .map(Party::id).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    @DisplayName("a blank id is not searched; a failing query is an empty answer, not an error")
    void blankAndFailure() {
      assertTrue(lookups.vendorsByTaxId("", 10).isEmpty());
      assertTrue(lookups.vendorsByTaxId(null, 10).isEmpty());
      verify(dal, org.mockito.Mockito.never()).createQuery(eq(BusinessPartner.class), anyString());

      sessionClient("CLIENT1");
      when(dal.createQuery(eq(BusinessPartner.class), anyString()))
          .thenThrow(new IllegalStateException("db"));
      assertTrue(lookups.vendorsByTaxId("44666500V", 10).isEmpty());
    }

    @Test
    @DisplayName("the stored tax id of a vendor is read through the tenant guard and trimmed")
    void vendorTaxIdIsGuardedAndTrimmed() {
      BusinessPartner vendor = mock(BusinessPartner.class);
      when(vendor.getTaxID()).thenReturn(" B81639719 ");
      tenantMock.when(() -> TenantOwnership.loadOwned(BusinessPartner.class, "V1")).thenReturn(vendor);
      tenantMock.when(() -> TenantOwnership.loadOwned(BusinessPartner.class, "GONE")).thenReturn(null);

      assertEquals("B81639719", lookups.vendorTaxId("V1"));
      assertEquals("", lookups.vendorTaxId("GONE"));
    }

    @Test
    @DisplayName("a vendor without a tax id, or a failing read, is no tax id on file")
    void vendorTaxIdMissingOrFailing() {
      BusinessPartner vendor = mock(BusinessPartner.class);
      when(vendor.getTaxID()).thenReturn(null);
      tenantMock.when(() -> TenantOwnership.loadOwned(BusinessPartner.class, "V1")).thenReturn(vendor);
      assertEquals("", lookups.vendorTaxId("V1"));

      tenantMock.when(() -> TenantOwnership.loadOwned(BusinessPartner.class, "V2"))
          .thenThrow(new IllegalStateException("db"));
      assertEquals("", lookups.vendorTaxId("V2"));
    }
  }

  // ── the organization's tax id ──────────────────────────────────────────────

  @Nested
  @DisplayName("organization tax id")
  class OrganizationTaxId {

    private void sessionOrganization() {
      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("ORG1");
      OBContext context = mock(OBContext.class);
      when(context.getCurrentOrganization()).thenReturn(org);
      contextMock.when(OBContext::getOBContext).thenReturn(context);
    }

    @Test
    @DisplayName("is the TaxID of the current organization's information, read through the worker's own DAL")
    void readsTheOrganizationInformation() {
      sessionOrganization();
      OrganizationInformation info = mock(OrganizationInformation.class);
      when(info.getTaxID()).thenReturn("B81639719");
      when(dal.get(OrganizationInformation.class, "ORG1")).thenReturn(info);

      assertEquals("B81639719", lookups.organizationTaxId());
      verify(readOnlyDal, org.mockito.Mockito.never()).get(eq(OrganizationInformation.class),
          anyString());
    }

    @Test
    @DisplayName("no information, or a failure, is no tax id")
    void absentOrFailing() {
      sessionOrganization();
      assertNull(lookups.organizationTaxId());

      when(dal.get(OrganizationInformation.class, "ORG1"))
          .thenThrow(new IllegalStateException("db"));
      assertNull(lookups.organizationTaxId());
    }
  }

  // ── the registry that binds a document type to its lookups ────────────────

  @Test
  @DisplayName("the registry binds the configuration's resolver name to a fresh set of lookups")
  void registryBinding() {
    OcrLookups first = OcrLookupsRegistry.forResolver(PurchaseInvoiceOcrLookups.RESOLVER).orElseThrow();
    OcrLookups second = OcrLookupsRegistry.forResolver("purchase-invoice").orElseThrow();

    assertTrue(first instanceof PurchaseInvoiceOcrLookups);
    assertTrue(first != second, "a new instance per request");
    assertTrue(OcrLookupsRegistry.forResolver("unknown").isEmpty());
    assertTrue(OcrLookupsRegistry.forResolver(null).isEmpty());
    assertNotNull(PurchaseInvoiceOcrLookups.RESOLVER);
  }

  // ── selector failures: the answer and the WARN that names the kind ─────────

  /** Collects the WARN events the lookups log, to check their kind and that they carry no document text. */
  private static final class WarnCapture extends AbstractAppender implements AutoCloseable {
    private final Logger logger;
    private final List<String> warnings = new ArrayList<>();

    WarnCapture() {
      super("capture-selector-warn", null, null, true, Property.EMPTY_ARRAY);
      logger = (Logger) LogManager.getLogger(PurchaseInvoiceOcrLookups.class);
      start();
      logger.addAppender(this);
    }

    @Override
    public void append(LogEvent event) {
      if (event.getLevel() == Level.WARN) {
        warnings.add(event.getMessage().getFormattedMessage());
      }
    }

    @Override
    public void close() {
      logger.removeAppender(this);
      stop();
    }
  }

  @Nested
  @DisplayName("selector failures")
  class SelectorFailures {

    private static final String DOCUMENT_TEXT = "SECRET-INVOICE-TEXT";

    private String onlyWarning(WarnCapture capture) {
      assertEquals(1, capture.warnings.size(), capture.warnings.toString());
      String warning = capture.warnings.get(0);
      assertFalse(warning.contains(DOCUMENT_TEXT), "the log must not carry document text");
      assertFalse(warning.contains("V1"), "the log must not carry ids");
      return warning;
    }

    @Test
    @DisplayName("a spec that is not found: no vendors, address UNKNOWN, WARN names the missing spec")
    void specMissing() {
      supportMock.when(() -> NeoServletSupport.findSpec("purchase-invoice")).thenReturn(null);

      try (WarnCapture capture = new WarnCapture()) {
        assertTrue(lookups.searchVendors(DOCUMENT_TEXT, 20).isEmpty());
        assertTrue(onlyWarning(capture).contains("spec purchase-invoice not found"));
      }
      try (WarnCapture capture = new WarnCapture()) {
        assertEquals(AddressState.UNKNOWN, lookups.vendorAddress("V1").state());
        assertTrue(onlyWarning(capture).contains("spec purchase-invoice not found"));
      }
      selectorMock.verifyNoInteractions();
    }

    @Test
    @DisplayName("an HTTP answer of 400 or more: no vendors, address UNKNOWN, WARN names the status")
    void httpFailure() {
      selectorAnswers(NeoResponse.error(403, "forbidden"));

      try (WarnCapture capture = new WarnCapture()) {
        assertTrue(lookups.searchVendors(DOCUMENT_TEXT, 20).isEmpty());
        assertTrue(onlyWarning(capture).contains("HTTP 403"));
      }
      try (WarnCapture capture = new WarnCapture()) {
        assertEquals(AddressState.UNKNOWN, lookups.vendorAddress("V1").state());
        assertTrue(onlyWarning(capture).contains("HTTP 403"));
      }
    }

    @Test
    @DisplayName("an exception: no vendors, address UNKNOWN, WARN names the exception class only")
    void exceptionFailure() {
      selectorMock.when(() -> NeoSelectorService.querySelector(anyString(), anyString(),
          anyString(), any(), anyInt(), anyInt(), anyMap()))
          .thenThrow(new IllegalStateException(DOCUMENT_TEXT + " V1"));

      try (WarnCapture capture = new WarnCapture()) {
        assertTrue(lookups.searchVendors(DOCUMENT_TEXT, 20).isEmpty());
        assertTrue(onlyWarning(capture).contains("IllegalStateException"));
      }
      try (WarnCapture capture = new WarnCapture()) {
        assertEquals(AddressState.UNKNOWN, lookups.vendorAddress("V1").state());
        assertTrue(onlyWarning(capture).contains("IllegalStateException"));
      }
    }

    @Test
    @DisplayName("a successful selector logs no warning")
    void successLogsNothing() throws Exception {
      selectorAnswers(items(new JSONObject().put("id", "V1").put("label", "Acme")));

      try (WarnCapture capture = new WarnCapture()) {
        assertEquals(1, lookups.searchVendors("Acme", 20).size());
        assertTrue(capture.warnings.isEmpty(), capture.warnings.toString());
      }
    }
  }

  // ── review details (the review action's read-time enrichment) ─────────────

  @Nested
  @DisplayName("review details: product by id, vendor and product details")
  class ReviewDetails {

    private MockedStatic<NeoTrl> trlMock;
    private MockedStatic<NeoLanguage> languageMock;

    @BeforeEach
    void openTranslation() {
      trlMock = mockStatic(NeoTrl.class);
      trlMock.when(() -> NeoTrl.translatedNames(anyString(), any(), anyString()))
          .thenReturn(Map.of());
      languageMock = mockStatic(NeoLanguage.class);
      languageMock.when(NeoLanguage::currentCode).thenReturn("es_ES");
    }

    @AfterEach
    void closeTranslation() {
      languageMock.close();
      trlMock.close();
    }

    @SuppressWarnings("unchecked")
    private <T extends org.openbravo.base.structure.BaseOBObject> OBQuery<T> query(Class<T> type,
        List<T> found) {
      OBQuery<T> query = mock(OBQuery.class);
      when(query.list()).thenReturn(found);
      when(dal.createQuery(eq(type), anyString())).thenReturn(query);
      return query;
    }

    private Product product(String id, String name, boolean active, boolean visible) {
      Product product = mock(Product.class);
      when(product.getId()).thenReturn(id);
      when(product.getName()).thenReturn(name);
      when(product.isActive()).thenReturn(active);
      when(product.getSearchKey()).thenReturn("KEY-" + id);
      tenantMock.when(() -> TenantOwnership.isVisibleToCurrentTenant(product)).thenReturn(visible);
      return product;
    }

    private void withCategoryAndUom(Product product, String categoryName, String uomName) {
      String productId = product.getId();
      ProductCategory category = mock(ProductCategory.class);
      when(category.getId()).thenReturn("CAT-" + productId);
      when(category.getName()).thenReturn(categoryName);
      UOM uom = mock(UOM.class);
      when(uom.getId()).thenReturn("UOM-" + productId);
      when(uom.getName()).thenReturn(uomName);
      when(product.getProductCategory()).thenReturn(category);
      when(product.getUOM()).thenReturn(uom);
    }

    private BusinessPartner partner(String id, String name, String taxId, boolean visible) {
      BusinessPartner partner = mock(BusinessPartner.class);
      when(partner.getId()).thenReturn(id);
      when(partner.getName()).thenReturn(name);
      when(partner.getTaxID()).thenReturn(taxId);
      tenantMock.when(() -> TenantOwnership.isVisibleToCurrentTenant(partner)).thenReturn(visible);
      return partner;
    }

    private org.openbravo.model.common.businesspartner.Location location(String partnerId,
        String city) {
      org.openbravo.model.common.businesspartner.Location location = mock(
          org.openbravo.model.common.businesspartner.Location.class);
      BusinessPartner owner = mock(BusinessPartner.class);
      when(owner.getId()).thenReturn(partnerId);
      when(location.getBusinessPartner()).thenReturn(owner);
      if (city != null) {
        org.openbravo.model.common.geography.Location address = mock(
            org.openbravo.model.common.geography.Location.class);
        when(address.getCityName()).thenReturn(city);
        when(location.getLocationAddress()).thenReturn(address);
      }
      return location;
    }

    // ---- productById

    @Test
    @DisplayName("a product by id is returned only when active and visible to the tenant")
    void productById() {
      Product ok = product("P1", "Pen", true, true);
      when(dal.get(Product.class, "P1")).thenReturn(ok);
      assertEquals("Pen", lookups.productById("P1").orElseThrow().name());
      assertEquals("P1", lookups.productById("P1").orElseThrow().id());

      Product foreign = product("P2", "Other tenant", true, false);
      when(dal.get(Product.class, "P2")).thenReturn(foreign);
      assertTrue(lookups.productById("P2").isEmpty());

      Product inactive = product("P3", "Gone", false, true);
      when(dal.get(Product.class, "P3")).thenReturn(inactive);
      assertTrue(lookups.productById("P3").isEmpty());

      assertTrue(lookups.productById("nope").isEmpty());
      assertTrue(lookups.productById(" ").isEmpty());
      assertTrue(lookups.productById(null).isEmpty());
    }

    @Test
    @DisplayName("a DAL failure on the product by id is an empty answer")
    void productByIdFailure() {
      when(dal.get(Product.class, "P1")).thenThrow(new IllegalStateException("db"));

      assertTrue(lookups.productById("P1").isEmpty());
    }

    // ---- vendorDetails

    @Test
    @DisplayName("no usable ids: empty answer and no query")
    void vendorDetailsWithoutIds() {
      assertTrue(lookups.vendorDetails(null).isEmpty());
      assertTrue(lookups.vendorDetails(List.of()).isEmpty());
      assertTrue(lookups.vendorDetails(java.util.Arrays.asList("", " ", null)).isEmpty());

      verify(dal, never()).createQuery(any(Class.class), anyString());
    }

    @Test
    @DisplayName("one query for the partners and one for the addresses, whatever the ids")
    void vendorDetailsBatches() {
      OBQuery<BusinessPartner> partners = query(BusinessPartner.class,
          List.of(partner("V1", "Acme", " B123 ", true), partner("V2", "Beta", null, true),
              partner("V3", "Foreign", "X", false)));
      query(org.openbravo.model.common.businesspartner.Location.class,
          List.of(location("V1", " Madrid "), location("V1", "Second city"),
              location("V3", "Hidden")));

      Map<String, OcrLookups.VendorDetails> out = lookups.vendorDetails(
          java.util.Arrays.asList("V1", "V2", "V1", "V3", "", null));

      verify(dal, times(2)).createQuery(any(Class.class), anyString());
      @SuppressWarnings("unchecked")
      ArgumentCaptor<java.util.Collection<String>> ids = ArgumentCaptor.forClass(
          java.util.Collection.class);
      verify(partners).setNamedParameter(eq("ids"), ids.capture());
      assertEquals(List.of("V1", "V2", "V3"), List.copyOf(ids.getValue()));
      assertEquals("Acme", out.get("V1").name());
      assertEquals("B123", out.get("V1").taxId());
      assertEquals("Madrid", out.get("V1").city(), "the first address wins, trimmed");
      assertEquals("", out.get("V2").taxId());
      assertEquals("", out.get("V2").city(), "no address: empty city");
      assertFalse(out.containsKey("V3"), "not visible to the tenant");
    }

    @Test
    @DisplayName("an address without a location record has an empty city")
    void vendorDetailsAddressWithoutLocation() {
      query(BusinessPartner.class, List.of(partner("V1", "Acme", "B1", true)));
      query(org.openbravo.model.common.businesspartner.Location.class,
          List.of(location("V1", null)));

      assertEquals("", lookups.vendorDetails(List.of("V1")).get("V1").city());
    }

    @Test
    @DisplayName("a failing city query keeps name and tax id with an empty city")
    void vendorDetailsCityFailureKeepsTheRest() {
      query(BusinessPartner.class,
          List.of(partner("V1", "Acme", "B1", true), partner("V2", "Beta", " B2 ", true)));
      OBQuery<org.openbravo.model.common.businesspartner.Location> cityQuery = query(
          org.openbravo.model.common.businesspartner.Location.class, List.of());
      when(cityQuery.list()).thenThrow(new IllegalStateException("SECRET V1"));

      Map<String, OcrLookups.VendorDetails> out = lookups.vendorDetails(List.of("V1", "V2"));

      assertEquals(2, out.size());
      assertEquals("Acme", out.get("V1").name());
      assertEquals("B1", out.get("V1").taxId());
      assertEquals("", out.get("V1").city());
      assertEquals("B2", out.get("V2").taxId());
      assertEquals("", out.get("V2").city());
    }

    @Test
    @DisplayName("the city query receives only the visible partners' ids")
    void vendorDetailsCityQueryOnlyForVisiblePartners() {
      query(BusinessPartner.class, List.of(partner("V1", "Acme", "B1", true),
          partner("V3", "Foreign", "X", false)));
      OBQuery<org.openbravo.model.common.businesspartner.Location> cityQuery = query(
          org.openbravo.model.common.businesspartner.Location.class,
          List.of(location("V1", "Madrid")));

      Map<String, OcrLookups.VendorDetails> out = lookups.vendorDetails(List.of("V1", "V3"));

      @SuppressWarnings("unchecked")
      ArgumentCaptor<java.util.Collection<String>> ids = ArgumentCaptor.forClass(
          java.util.Collection.class);
      verify(cityQuery).setNamedParameter(eq("ids"), ids.capture());
      assertEquals(List.of("V1"), List.copyOf(ids.getValue()));
      assertEquals("Madrid", out.get("V1").city());
      assertFalse(out.containsKey("V3"));
    }

    @Test
    @DisplayName("when no partner is visible the city query is not run at all")
    void vendorDetailsNoVisiblePartnerNoCityQuery() {
      query(BusinessPartner.class, List.of(partner("V3", "Foreign", "X", false)));

      assertTrue(lookups.vendorDetails(List.of("V3")).isEmpty());

      verify(dal, times(1)).createQuery(any(Class.class), anyString());
    }

    @Test
    @DisplayName("a failing vendor details lookup is an empty answer, logged without ids")
    void vendorDetailsFailure() {
      when(dal.createQuery(any(Class.class), anyString()))
          .thenThrow(new IllegalStateException("SECRET V1"));

      assertTrue(lookups.vendorDetails(List.of("V1")).isEmpty());
    }

    // ---- productDetails

    @Test
    @DisplayName("no usable ids: empty answer and no query")
    void productDetailsWithoutIds() {
      assertTrue(lookups.productDetails(null).isEmpty());
      assertTrue(lookups.productDetails(List.of()).isEmpty());
      assertTrue(lookups.productDetails(java.util.Arrays.asList(" ", null)).isEmpty());

      verify(dal, never()).createQuery(any(Class.class), anyString());
    }

    @Test
    @DisplayName("one product query; names follow the session language and fall back to the base")
    void productDetailsTranslateAndFallBack() {
      Product p1 = product("P1", "Pen", true, true);
      withCategoryAndUom(p1, "Office", "Unit");
      Product p2 = product("P2", "Pencil", true, true);
      withCategoryAndUom(p2, "Office", "Box");
      Product foreign = product("P3", "Foreign", true, false);
      query(Product.class, List.of(p1, p2, foreign));
      trlMock.when(() -> NeoTrl.translatedNames(eq("Product"), any(), eq("es_ES")))
          .thenReturn(Map.of("P1", "Boligrafo"));
      trlMock.when(() -> NeoTrl.translatedNames(eq("ProductCategory"), any(), eq("es_ES")))
          .thenReturn(Map.of("CAT-P1", "Oficina"));
      trlMock.when(() -> NeoTrl.translatedNames(eq("UOM"), any(), eq("es_ES")))
          .thenReturn(Map.of("UOM-P1", "Unidad"));

      Map<String, OcrLookups.ProductDetails> out = lookups.productDetails(
          List.of("P1", "P2", "P3", "P1"));

      verify(dal, times(1)).createQuery(any(Class.class), anyString());
      OcrLookups.ProductDetails first = out.get("P1");
      assertEquals("Boligrafo", first.name());
      assertEquals("KEY-P1", first.searchKey());
      assertEquals("Oficina", first.category());
      assertEquals("Unidad", first.uom());
      OcrLookups.ProductDetails second = out.get("P2");
      assertEquals("Pencil", second.name());
      assertEquals("Office", second.category());
      assertEquals("Box", second.uom());
      assertFalse(out.containsKey("P3"), "not visible to the tenant");
    }

    @Test
    @DisplayName("a product without category or unit answers empty texts")
    void productDetailsWithoutCategoryOrUom() {
      Product p1 = product("P1", "Pen", true, true);
      query(Product.class, List.of(p1));

      OcrLookups.ProductDetails details = lookups.productDetails(List.of("P1")).get("P1");

      assertEquals("", details.category());
      assertEquals("", details.uom());
    }

    @Test
    @DisplayName("a failing product details lookup is an empty answer")
    void productDetailsFailure() {
      when(dal.createQuery(any(Class.class), anyString()))
          .thenThrow(new IllegalStateException("db"));

      assertTrue(lookups.productDetails(List.of("P1")).isEmpty());
    }

    // ---- the interface defaults

    @Test
    @DisplayName("an implementation that does not support the details answers nothing, never throws")
    void interfaceDefaultsAreTotal() {
      OcrLookups bare = mock(OcrLookups.class, org.mockito.Mockito.CALLS_REAL_METHODS);

      assertTrue(bare.productById("P1").isEmpty());
      assertTrue(bare.vendorDetails(List.of("V1")).isEmpty());
      assertTrue(bare.productDetails(List.of("P1")).isEmpty());
    }

    @Test
    @DisplayName("the data classes hand back what they were given")
    void dataClassesCarryTheirValues() {
      OcrLookups.VendorDetails vendor = new OcrLookups.VendorDetails("N", "T", "C");
      OcrLookups.ProductDetails product = new OcrLookups.ProductDetails("N", "K", "G", "U");

      assertEquals("N|T|C", vendor.name() + "|" + vendor.taxId() + "|" + vendor.city());
      assertEquals("N|K|G|U", product.name() + "|" + product.searchKey() + "|"
          + product.category() + "|" + product.uom());
    }
  }
}
