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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

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

import com.etendoerp.go.schemaforge.DefaultTaxResolver.Determination;
import com.etendoerp.go.schemaforge.DefaultTaxResolver.Result;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.AddressState;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Candidate;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicates;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.TaxAnswer;

/**
 * The purchase-invoice lookups of the OCR review: each one must reach the SAME server code the
 * application's review reaches (the invoice header's own selectors, the similarity search, the
 * {@code defaultTax} rule), with the context the invoice form gives, and must answer "nothing" or
 * "unknown" — never throw — when that code fails. The server edges are mocked.
 *
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceOcrLookups
 * @covers com.etendoerp.go.schemaforge.OcrLookupsRegistry
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
    @DisplayName("is the TaxID of the current organization's information")
    void readsTheOrganizationInformation() {
      sessionOrganization();
      OrganizationInformation info = mock(OrganizationInformation.class);
      when(info.getTaxID()).thenReturn("B81639719");
      when(readOnlyDal.get(OrganizationInformation.class, "ORG1")).thenReturn(info);

      assertEquals("B81639719", lookups.organizationTaxId());
    }

    @Test
    @DisplayName("no information, or a failure, is no tax id")
    void absentOrFailing() {
      sessionOrganization();
      assertNull(lookups.organizationTaxId());

      when(readOnlyDal.get(OrganizationInformation.class, "ORG1"))
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
}
