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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.handlers.ContactTypeRules;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.AddressState;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Tax;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.TaxAnswer;

/**
 * The unit of work of the confirmation: what is created and in which order (a new vendor and its
 * address, the new products, then the invoice and its lines, through the NEO batch in the caller's
 * transaction), what the bodies carry, how each failure is named and sanitized, and that the row is
 * linked and the invoice completed only after the original file is attached. The batch, the DAL and
 * the completion are mocked: what is asserted is what is sent to them and what comes out.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmWork
 */
class InvoiceInboxConfirmWorkTest {

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<OBMessageUtils> messages;
  private MockedStatic<NeoBatchScope> scope;
  private MockedStatic<BatchService> batchStatic;
  private MockedStatic<TenantOwnership> tenant;
  private MockedStatic<InvoiceInboxLiveChecks> live;
  private MockedStatic<ContactTypeRules> contactType;
  private MockedStatic<InvoiceInboxConfirmLookups> confirmLookups;
  private MockedStatic<InvoiceInboxConfirmVerify> totalsVerify;
  private MockedStatic<InvoiceInboxConfirmAttachment> attachment;
  private MockedStatic<NeoQuotaCreateCheck> quota;
  private MockedStatic<DocumentCurrencyRate> rates;
  private MockedStatic<InvoiceSifPreset> sif;
  private MockedStatic<InvoiceCompletionService> completion;
  private MockedStatic<InvoiceInboxSummary> summary;
  private MockedStatic<Utility> utility;
  private MockedConstruction<DalConnectionProvider> connectionProvider;

  private OBDal dal;
  private Session session;
  private BatchService batch;
  private Invoice invoice;
  private IncomingDocument row;
  private OcrLookups lookups;
  private final List<JSONArray> batches = new ArrayList<>();
  /** The failure the n-th batch answers, {@code null} for success. */
  private final List<JSONObject> batchFailures = new ArrayList<>();
  private boolean flagOn;
  private String category;
  private String nextKey;

  @BeforeEach
  void setUp() throws Exception {
    batches.clear();
    batchFailures.clear();
    flagOn = false;
    category = "BPG-PROVEEDOR";
    nextKey = "P-0001";
    dal = mock(OBDal.class);
    session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    invoice = mock(Invoice.class);
    when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("12.10"));
    when(dal.get(Invoice.class, "REC-inv")).thenReturn(invoice);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    contextStatic = mockStatic(OBContext.class);
    contextStatic.when(OBContext::getOBContext).thenReturn(mock(OBContext.class));
    messages = mockStatic(OBMessageUtils.class);
    scope = mockStatic(NeoBatchScope.class);
    batch = mock(BatchService.class);
    batchStatic = mockStatic(BatchService.class);
    batchStatic.when(BatchService::forBatchOnly).thenReturn(batch);
    when(batch.executeInCallerTransaction(any())).thenAnswer(inv -> answerBatch(inv.getArgument(0)));
    tenant = mockStatic(TenantOwnership.class);
    tenant.when(() -> TenantOwnership.loadOwnedByClient(eq(BusinessPartner.class), anyString()))
        .thenReturn(mock(BusinessPartner.class));
    live = mockStatic(InvoiceInboxLiveChecks.class);
    live.when(InvoiceInboxLiveChecks::countries).thenReturn(List.of(
        new InvoiceInboxConfirmSeeds.CountryRow("C_ES", "ES", "Spain")));
    live.when(() -> InvoiceInboxLiveChecks.documentCurrencyId(any(), any(), anyString()))
        .thenReturn("EUR-ID");
    contactType = mockStatic(ContactTypeRules.class);
    contactType.when(ContactTypeRules::isEnabled).thenAnswer(i -> flagOn);
    confirmLookups = mockStatic(InvoiceInboxConfirmLookups.class);
    confirmLookups.when(() -> InvoiceInboxConfirmLookups.purchaseContactCategory(anyString()))
        .thenAnswer(i -> category);
    confirmLookups.when(() -> InvoiceInboxConfirmLookups.taxCategoryOf(any())).thenReturn("TC-1");
    totalsVerify = mockStatic(InvoiceInboxConfirmVerify.class);
    attachment = mockStatic(InvoiceInboxConfirmAttachment.class);
    quota = mockStatic(NeoQuotaCreateCheck.class);
    rates = mockStatic(DocumentCurrencyRate.class);
    sif = mockStatic(InvoiceSifPreset.class);
    completion = mockStatic(InvoiceCompletionService.class);
    summary = mockStatic(InvoiceInboxSummary.class);
    summary.when(() -> InvoiceInboxSummary.totalMismatch(any(), any())).thenReturn(false);
    live.when(() -> InvoiceInboxLiveChecks.documentCurrency(any(), any())).thenReturn(null);
    utility = mockStatic(Utility.class);
    utility.when(() -> Utility.getDocumentNoConnection(any(), any(), anyString(), anyString(),
        org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(i -> nextKey);
    connectionProvider = mockConstruction(DalConnectionProvider.class);

    row = mock(IncomingDocument.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("C1");
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG-1");
    when(row.getId()).thenReturn("ROW-1");
    when(row.getClient()).thenReturn(client);
    when(row.getOrganization()).thenReturn(org);
    lookups = mock(OcrLookups.class);
    when(lookups.vendorAddress("V1")).thenReturn(new Address(AddressState.PRESENT, "LOC1"));
    Tax tax = new Tax("T21", "21%", new BigDecimal("21"));
    when(lookups.defaultTax(any(), any(), any())).thenReturn(new TaxAnswer(true, tax, List.of(tax)));
  }

  @AfterEach
  void tearDown() {
    connectionProvider.close();
    utility.close();
    summary.close();
    completion.close();
    sif.close();
    rates.close();
    quota.close();
    attachment.close();
    totalsVerify.close();
    confirmLookups.close();
    contactType.close();
    live.close();
    tenant.close();
    batchStatic.close();
    scope.close();
    messages.close();
    contextStatic.close();
    dalStatic.close();
  }

  /** Every operation succeeds with the record id {@code REC-<op id>}, unless the batch is set to fail. */
  private JSONObject answerBatch(JSONArray ops) throws Exception {
    int position = batches.size();
    batches.add(ops);
    JSONObject failure = position < batchFailures.size() ? batchFailures.get(position) : null;
    if (failure != null) {
      return failure;
    }
    JSONArray done = new JSONArray();
    for (int i = 0; i < ops.length(); i++) {
      String id = ops.getJSONObject(i).getString("id");
      done.put(new JSONObject().put("id", id).put("ok", true).put("recordId", "REC-" + id));
    }
    return new JSONObject().put("committed", false).put("operations", done);
  }

  private static final String LINKED = "{state:'linked',id:'V1'}";
  private static final String NEW_VENDOR = "{state:'new',extracted:{name:'Acme',taxId:'B1',"
      + "address:'Calle 1',city:'Madrid',country:'Spain'}}";
  private static final String EXISTING_LINE = "{index:0,description:'Tornillo',quantity:2,"
      + "unitPrice:10,discount:10,taxRate:21,product:{state:'linked',id:'P1'},tax:{id:'T21'}}";
  private static final String NEW_PRODUCT_LINE = "{index:1,description:'Arandela',quantity:1,"
      + "unitPrice:5,taxRate:21,product:{state:'new',new:{name:'Arandela M8',categoryId:'C1',"
      + "uomId:'U1'}},tax:{id:'T21'}}";

  private static JSONObject document(String vendor, String... lines) throws Exception {
    return new JSONObject("{header:{vendor:" + vendor + ",documentNo:' F-1 ',invoiceDate:'2026-11-02'},"
        + "lines:[" + String.join(",", lines) + "]}");
  }

  private InvoiceInboxConfirmWork.Done run(JSONObject document) {
    return InvoiceInboxConfirmWork.run(row, document, Optional.of(lookups));
  }

  private JSONObject opAt(int batchIndex, int position) throws Exception {
    return batches.get(batchIndex).getJSONObject(position);
  }

  private static JSONObject failureOf(String opId, String message, JSONArray fields)
      throws Exception {
    JSONObject nested = new JSONObject().put("message", message);
    if (fields != null) {
      nested.put("fields", fields);
    }
    return new JSONObject().put("committed", false)
        .put("failedAt", new JSONObject().put("id", opId))
        .put("error", new JSONObject().put("message", "Batch failed")
            .put("detail", new JSONObject().put("error", nested)));
  }

  // ------------------------------------------------------------------ the happy path

  @Test
  @DisplayName("a linked vendor and an existing product: the invoice and its line are created, attached, linked, completed")
  void aLinkedVendorAndAnExistingProduct() throws Exception {
    when(row.isAdjusted()).thenReturn(true);

    InvoiceInboxConfirmWork.Done done = run(document(LINKED, EXISTING_LINE));

    assertEquals("REC-inv", done.invoiceId);
    assertTrue(done.adjusted);
    assertFalse(done.createdVendor);
    assertEquals(0, done.createdProducts);
    assertFalse(done.totalMismatch);
    assertEquals(1, batches.size(), "no master data to create: one batch, the invoice");

    JSONObject header = opAt(0, 0);
    assertEquals("purchase-invoice", header.getString("spec"));
    assertEquals("Header", header.getString("entity"));
    JSONObject body = header.getJSONObject("body");
    assertEquals("V1", body.getString("businessPartner"));
    assertEquals("LOC1", body.getString("partnerAddress"));
    assertEquals("F-1", body.getString("orderReference"));
    assertEquals("2026-11-02", body.getString("invoiceDate"));
    assertEquals("EUR-ID", body.getString("currency"));
    assertEquals("ORG-1", body.getString("organization"));

    JSONObject line = opAt(0, 1);
    assertEquals("Lines", line.getString("entity"));
    assertEquals("inv", line.getString("parentRef"));
    assertEquals("P1", line.getJSONObject("body").getString("product"));
    assertEquals("T21", line.getJSONObject("body").getString("tax"));
    assertEquals(0, new BigDecimal("9").compareTo(
        new BigDecimal(line.getJSONObject("body").get("unitPrice").toString())));
  }

  @Test
  @DisplayName("an invoice without a grand total leaves the list total of the row as it was")
  void aMissingInvoiceGrandTotalKeepsTheListTotal() throws Exception {
    when(invoice.getGrandTotalAmount()).thenReturn(null);

    run(document(LINKED, EXISTING_LINE));

    verify(row, never()).setGrandTotalAmount(any());
  }

  @Test
  @DisplayName("everything is written before the completion: file attached, row linked CONFIRMED, then completed once")
  void theOrderOfTheUnit() throws Exception {
    run(document(LINKED, EXISTING_LINE));

    InOrder order = inOrder(dal, row);
    order.verify(row).setInvoice(invoice);
    order.verify(row).setGrandTotalAmount(new BigDecimal("12.10"));
    order.verify(row).setStatus("CONFIRMED");
    order.verify(row).setErrorCode(null);
    order.verify(row).setErrorDetail(null);
    order.verify(dal).save(row);
    verify(row, never()).setAdjusted(any());
    attachment.verify(() -> InvoiceInboxConfirmAttachment.attach("ROW-1", "REC-inv"));
    totalsVerify.verify(() -> InvoiceInboxConfirmVerify.verify(eq(invoice), any(), any()));
    quota.verify(() -> NeoQuotaCreateCheck.requireAllowedDocument(invoice));
    rates.verify(() -> DocumentCurrencyRate.requireRateForCompletion(eq(invoice), anyString()));
    sif.verify(() -> InvoiceSifPreset.applyBeforeCompletion(invoice, null));
    completion.verify(() -> InvoiceCompletionService.completeInvoiceOrThrow(eq("REC-inv"), any()),
        times(1));
  }

  @Test
  @DisplayName("the unit runs in admin mode with its own batch scope, both restored after it, and the after-commit actions run")
  void adminModeAndScopeAreRestored() throws Exception {
    run(document(LINKED, EXISTING_LINE));

    contextStatic.verify(() -> OBContext.setAdminMode(true));
    contextStatic.verify(OBContext::restorePreviousMode);
    scope.verify(() -> NeoBatchScope.open(false, false));
    scope.verify(() -> NeoBatchScope.close(any()));
    scope.verify(NeoBatchScope::runAfterCommitActions);
  }

  @Test
  @DisplayName("a document of a type without lookups is a failure to record, with nothing written")
  void noLookupsStops() throws Exception {
    InvoiceInboxConfirmWork.Stop stop = assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> InvoiceInboxConfirmWork.run(row, document(LINKED, EXISTING_LINE), Optional.empty()));

    assertTrue(stop.getMessage().contains("no lookups for the document type"));
    assertEquals("no_lookups", stop.detail().code());
    assertTrue(batches.isEmpty());
  }

  // ------------------------------------------------------------------ master data

  @Test
  @DisplayName("a new vendor and a new product: the vendor and its address, then the product, in one batch; the invoice uses the created ids")
  void newMasterData() throws Exception {
    InvoiceInboxConfirmWork.Done done = run(document(NEW_VENDOR, EXISTING_LINE, NEW_PRODUCT_LINE));

    assertTrue(done.createdVendor);
    assertEquals(1, done.createdProducts);
    assertEquals(2, batches.size());

    JSONObject vendor = opAt(0, 0);
    assertEquals("contacts", vendor.getString("spec"));
    assertEquals("businessPartner", vendor.getString("entity"));
    assertEquals("bp", vendor.getString("id"));
    assertEquals("Acme", vendor.getJSONObject("body").getString("name"));
    assertEquals("ORG-1", vendor.getJSONObject("body").getString("organization"));
    assertEquals("BPG-PROVEEDOR", vendor.getJSONObject("body").getString("businessPartnerCategory"));
    assertTrue(vendor.getJSONObject("body").getBoolean("vendor"), "flag off sends vendor = true");

    JSONObject address = opAt(0, 1);
    assertEquals("locationAddress", address.getString("entity"));
    assertEquals("bp", address.getString("parentRef"));
    assertEquals("C_ES", address.getJSONObject("body").getString("country"));
    assertEquals("$ref:bp", address.getJSONObject("body").getString("businessPartner"));

    JSONObject product = opAt(0, 2);
    assertEquals("p1", product.getString("id"));
    assertEquals("product", product.getString("spec"));
    assertEquals("Arandela M8", product.getJSONObject("body").getString("name"));
    assertEquals("P-0001", product.getJSONObject("body").getString("searchKey"));
    assertEquals("TC-1", product.getJSONObject("body").getString("taxCategory"));
    assertEquals("ORG-1", product.getJSONObject("body").getString("organization"));

    JSONObject header = opAt(1, 0).getJSONObject("body");
    assertEquals("REC-bp", header.getString("businessPartner"));
    assertEquals("REC-loc", header.getString("partnerAddress"));
    assertEquals("P1", opAt(1, 1).getJSONObject("body").getString("product"));
    assertEquals("REC-p1", opAt(1, 2).getJSONObject("body").getString("product"));
  }

  @Test
  @DisplayName("two new products get one search key each, asked in order")
  void twoNewProductsTwoKeys() throws Exception {
    utility.when(() -> Utility.getDocumentNoConnection(any(), any(), anyString(), anyString(),
        org.mockito.ArgumentMatchers.anyBoolean())).thenReturn("P-0001", "P-0002");
    String second = NEW_PRODUCT_LINE.replace("index:1", "index:2").replace("Arandela", "Tuerca");

    run(document(LINKED, NEW_PRODUCT_LINE, second));

    assertEquals("P-0001", opAt(0, 0).getJSONObject("body").getString("searchKey"));
    assertEquals("P-0002", opAt(0, 1).getJSONObject("body").getString("searchKey"));
    utility.verify(() -> Utility.getDocumentNoConnection(any(), any(), eq("C1"), eq("M_Product"),
        eq(true)), times(2));
  }

  @Test
  @DisplayName("no document sequence for the product key is a failure naming it")
  void noSequence() throws Exception {
    nextKey = " ";

    InvoiceInboxConfirmWork.Stop stop = assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> run(document(LINKED, NEW_PRODUCT_LINE)));

    assertTrue(stop.getMessage().contains("no document sequence"));
    assertEquals("no_product_sequence", stop.detail().code());
  }

  @Test
  @DisplayName("with the contact-category flag on, a new vendor takes the category alone (no vendor=true)")
  void flagOnSeedsTheCategoryOnly() throws Exception {
    flagOn = true;

    run(document(NEW_VENDOR, EXISTING_LINE));

    JSONObject body = opAt(0, 0).getJSONObject("body");
    assertFalse(body.has("vendor"));
    assertEquals("BPG-PROVEEDOR", body.getString("businessPartnerCategory"));
  }

  @Test
  @DisplayName("flag on and no unique Proveedor category: CONFIRM_NO_CATEGORY; flag off still creates the vendor without it")
  void noCategory() throws Exception {
    category = null;
    flagOn = true;

    InvoiceInboxConfirmWork.Stop stop = assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> run(document(NEW_VENDOR, EXISTING_LINE)));
    assertTrue(stop.getMessage().contains("Proveedor"), stop.getMessage());
    assertEquals("no_vendor_category", stop.detail().code());
    assertTrue(batches.isEmpty());

    flagOn = false;
    run(document(NEW_VENDOR, EXISTING_LINE));
    JSONObject body = opAt(0, 0).getJSONObject("body");
    assertTrue(body.getBoolean("vendor"));
    assertFalse(body.has("businessPartnerCategory"));
  }

  @Test
  @DisplayName("a new vendor whose printed country matches nothing, or with no address, is vendor_address_missing, nothing created")
  void aNewVendorWithoutAUsableAddress() throws Exception {
    String oslo = "{state:'new',extracted:{name:'Acme',city:'Oslo',country:'Norway'}}";
    String noAddress = "{state:'new',extracted:{name:'Acme'}}";
    String notNew = "{state:'none',extracted:{name:'Acme',city:'Madrid'}}";

    for (String vendor : new String[] { oslo, noAddress, notNew }) {
      InvoiceInboxConfirmWork.Stop stop = assertThrows(InvoiceInboxConfirmWork.Stop.class,
          () -> run(document(vendor, EXISTING_LINE)), vendor);
      assertNotNull(stop.refusal(), vendor);
      assertEquals("vendor_address_missing",
          stop.refusal().getBody().getJSONObject("error").getString("code"));
    }
    assertTrue(batches.isEmpty());
  }

  @Test
  @DisplayName("a linked vendor of another client, or without an address now, is vendor_address_missing")
  void aLinkedVendorThatCannotBeUsed() throws Exception {
    tenant.when(() -> TenantOwnership.loadOwnedByClient(eq(BusinessPartner.class), anyString()))
        .thenReturn(null);
    InvoiceInboxConfirmWork.Stop foreign = assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> run(document(LINKED, EXISTING_LINE)));
    assertEquals("vendor_address_missing",
        foreign.refusal().getBody().getJSONObject("error").getString("code"));

    tenant.when(() -> TenantOwnership.loadOwnedByClient(eq(BusinessPartner.class), anyString()))
        .thenReturn(mock(BusinessPartner.class));
    when(lookups.vendorAddress("V1")).thenReturn(new Address(AddressState.MISSING, null));
    InvoiceInboxConfirmWork.Stop noAddress = assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> run(document(LINKED, EXISTING_LINE)));
    assertNotNull(noAddress.refusal());
    assertTrue(batches.isEmpty());
  }

  // ------------------------------------------------------------------ tax and body details

  @Test
  @DisplayName("a line with a rate and no tax id takes the tax the vendor's address determines; none found sends none")
  void theTaxIsDeterminedWhenAbsent() throws Exception {
    String untaxed = "{index:0,description:'x',quantity:1,unitPrice:10,taxRate:21,"
        + "product:{state:'linked',id:'P1'}}";
    String noRate = "{index:1,description:'y',quantity:1,unitPrice:10,taxRate:null,"
        + "product:{state:'linked',id:'P1'}}";

    run(document(LINKED, untaxed, noRate));

    assertEquals("T21", opAt(0, 1).getJSONObject("body").getString("tax"));
    assertFalse(opAt(0, 2).getJSONObject("body").has("tax"));
    verify(lookups).defaultTax(eq("LOC1"), eq("2026-11-02"), eq(new BigDecimal("21")));
  }

  @Test
  @DisplayName("the unit does not send a currency when none can be derived, nor a number when the document has none")
  void optionalHeaderFields() throws Exception {
    live.when(() -> InvoiceInboxLiveChecks.documentCurrencyId(any(), any(), anyString()))
        .thenReturn(null);
    JSONObject doc = document(LINKED, EXISTING_LINE);
    doc.getJSONObject("header").put("documentNo", "  ");

    run(doc);

    JSONObject body = opAt(0, 0).getJSONObject("body");
    assertFalse(body.has("currency"));
    assertFalse(body.has("orderReference"));
  }

  // ------------------------------------------------------------------ failures

  @Test
  @DisplayName("a refused creation names the step: the new vendor, its address, a product by name, the header, a line")
  void aRefusedCreationNamesTheStep() throws Exception {
    String[][] cases = { { "bp", "new vendor", "vendor" },
        { "loc", "new vendor address", "vendorAddress" },
        { "p0", "new product 'Arandela M8'", "product" } };
    for (String[] c : cases) {
      batches.clear();
      batchFailures.clear();
      batchFailures.add(failureOf(c[0], "Name is required", null));

      InvoiceInboxConfirmWork.Stop stop = assertThrows(InvoiceInboxConfirmWork.Stop.class,
          () -> run(document(NEW_VENDOR, NEW_PRODUCT_LINE)));

      assertEquals(c[1] + ": Name is required", stop.getMessage());
      assertEquals("step_failed", stop.detail().code());
      assertEquals(c[2], stop.detail().params().get("step"));
      assertEquals("Name is required", stop.detail().params().get("reason"));
      assertNull(stop.refusal());
    }
  }

  @Test
  @DisplayName("a refused header or line of the invoice names it, with the missing fields appended")
  void aRefusedInvoiceStepNamesIt() throws Exception {
    batchFailures.add(failureOf("ln1", "Required fields are missing",
        new JSONArray("[\"unitPrice\",\"tax\"]")));

    InvoiceInboxConfirmWork.Stop line = assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> run(document(LINKED, EXISTING_LINE, EXISTING_LINE.replace("index:0", "index:1"))));
    assertEquals("line 2: Required fields are missing (unitPrice, tax)", line.getMessage());
    assertEquals(2, line.detail().params().get("n"));
    assertEquals(java.util.List.of("unitPrice", "tax"), line.detail().params().get("missing"));
    assertEquals("Required fields are missing", line.detail().params().get("reason"));

    batches.clear();
    batchFailures.clear();
    batchFailures.add(failureOf("inv", "Currency is not valid", null));
    InvoiceInboxConfirmWork.Stop header = assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> run(document(LINKED, EXISTING_LINE)));
    assertEquals("invoice header: Currency is not valid", header.getMessage());
    assertEquals("header", header.detail().params().get("step"));
    completion.verifyNoInteractions();
  }

  @Test
  @DisplayName("a duplicated search key gets its own message, nothing persisted")
  void aDuplicatedSearchKey() throws Exception {
    batchFailures.add(failureOf("p0", "Search Key is not unique", null));

    InvoiceInboxConfirmWork.Stop stop = assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> run(document(LINKED, NEW_PRODUCT_LINE)));

    assertTrue(stop.getMessage().endsWith("the search key already exists"), stop.getMessage());
    assertEquals("search_key_exists", stop.detail().code());
    assertEquals("product", stop.detail().params().get("step"));
    completion.verifyNoInteractions();
    verify(row, never()).setStatus(anyString());
  }

  @Test
  @DisplayName("the step label and the reason of a batch failure, on their own")
  void stepLabelAndReason() throws Exception {
    JSONArray ops = new JSONArray().put(new JSONObject().put("id", "p3")
        .put("body", new JSONObject().put("name", "Cable")));
    assertEquals(java.util.Map.of("step", "vendor"),
        InvoiceInboxConfirmWork.stepParams(failureOf("bp", "x", null), ops));
    assertEquals(java.util.Map.of("step", "vendorAddress"),
        InvoiceInboxConfirmWork.stepParams(failureOf("loc", "x", null), ops));
    assertEquals(java.util.Map.of("step", "header"),
        InvoiceInboxConfirmWork.stepParams(failureOf("inv", "x", null), ops));
    assertEquals(java.util.Map.of("step", "line", "n", 8),
        InvoiceInboxConfirmWork.stepParams(failureOf("ln7", "x", null), ops));
    assertEquals(java.util.Map.of("step", "product", "name", "Cable"),
        InvoiceInboxConfirmWork.stepParams(failureOf("p3", "x", null), ops));
    assertEquals(java.util.Map.of("step", "product", "name", "p9"),
        InvoiceInboxConfirmWork.stepParams(failureOf("p9", "x", null), ops));
    assertEquals(java.util.Map.of("step", "creation"),
        InvoiceInboxConfirmWork.stepParams(failureOf("zzz", "x", null), ops));
    assertEquals(java.util.Map.of("step", "creation"),
        InvoiceInboxConfirmWork.stepParams(new JSONObject(), ops));

    assertEquals("nested", InvoiceInboxConfirmWork.reasonOf(failureOf("bp", "nested", null)));
    assertEquals("The creation was refused", InvoiceInboxConfirmWork.reasonOf(new JSONObject()));
    assertEquals("detail only", InvoiceInboxConfirmWork.reasonOf(new JSONObject().put("error",
        new JSONObject().put("detail", new JSONObject().put("message", "detail only")))));
    assertEquals("batch level", InvoiceInboxConfirmWork.reasonOf(new JSONObject().put("error",
        new JSONObject().put("message", "batch level"))));
  }

  // ------------------------------------------------------------------ the completion

  @Test
  @DisplayName("a business refusal of the completion shows its translated message and nothing is committed here")
  void aBusinessRefusalOfTheCompletion() throws Exception {
    completion.when(() -> InvoiceCompletionService.completeInvoiceOrThrow(anyString(), any()))
        .thenThrow(new OBException("Period is closed"));

    InvoiceInboxConfirmWork.Stop stop = assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> run(document(LINKED, EXISTING_LINE)));

    assertEquals("Period is closed", stop.getMessage());
    assertEquals("refused", stop.detail().code());
    assertEquals("Period is closed", stop.detail().params().get("message"));
    scope.verify(NeoBatchScope::runAfterCommitActions, never());
  }

  @Test
  @DisplayName("an infrastructure failure of the completion shows a generic message, never its own text")
  void anInfrastructureFailureOfTheCompletion() throws Exception {
    completion.when(() -> InvoiceCompletionService.completeInvoiceOrThrow(anyString(), any()))
        .thenThrow(new IllegalStateException("ERROR: relation c_invoice_x violates constraint"));

    InvoiceInboxConfirmWork.Stop stop = assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> run(document(LINKED, EXISTING_LINE)));

    assertFalse(stop.getMessage().contains("violates"), stop.getMessage());
    assertTrue(stop.getMessage().contains("could not be completed"), stop.getMessage());
    assertEquals("completion_error", stop.detail().code());
  }

  @Test
  @DisplayName("a file that cannot be copied is the generic completion error, and the invoice is not completed")
  void anIoFailureIsSanitized() throws Exception {
    attachment.when(() -> InvoiceInboxConfirmAttachment.attach(anyString(), anyString()))
        .thenThrow(new IOException("/tmp/secret/path.pdf: disk full"));

    InvoiceInboxConfirmWork.Stop stop = assertThrows(InvoiceInboxConfirmWork.Stop.class,
        () -> run(document(LINKED, EXISTING_LINE)));

    assertFalse(stop.getMessage().contains("/tmp"), stop.getMessage());
    completion.verifyNoInteractions();
    verify(row, never()).setStatus(anyString());
  }

  @Test
  @DisplayName("a divergence the verification finds stops before the file is attached or the row linked")
  void aVerificationStopComesFirst() throws Exception {
    totalsVerify.when(() -> InvoiceInboxConfirmVerify.verify(any(), any(), any()))
        .thenThrow(new InvoiceInboxConfirmWork.Stop(InvoiceInboxFailureDetail.of(
            InvoiceInboxFailureDetail.TOTAL_MISMATCH, "invoice", "1", "document", "2")));

    assertThrows(InvoiceInboxConfirmWork.Stop.class, () -> run(document(LINKED, EXISTING_LINE)));

    attachment.verifyNoInteractions();
    verify(row, never()).setInvoice(any());
    completion.verifyNoInteractions();
  }

  @Test
  @DisplayName("a quota refusal at the end of the unit propagates as itself")
  void aQuotaRefusalPropagates() throws Exception {
    quota.when(() -> NeoQuotaCreateCheck.requireAllowedDocument(any()))
        .thenThrow(new NeoQuotaCreateCheck.Refused(NeoResponse.error(403, "quota"), null));

    assertThrows(NeoQuotaCreateCheck.Refused.class, () -> run(document(LINKED, EXISTING_LINE)));

    completion.verifyNoInteractions();
  }

  // ------------------------------------------------------------------ after the commit

  @Test
  @DisplayName("an after-commit action that fails never fails the confirmation: the invoice is already committed")
  void anAfterCommitFailureIsSwallowed() throws Exception {
    scope.when(NeoBatchScope::runAfterCommitActions).thenThrow(new IllegalStateException("mail down"));

    InvoiceInboxConfirmWork.Done done = run(document(LINKED, EXISTING_LINE));

    assertEquals("REC-inv", done.invoiceId);
    completion.verify(() -> InvoiceCompletionService.completeInvoiceOrThrow(eq("REC-inv"), any()));
  }

  @Test
  @DisplayName("the warning is computed from the stored document and carried in the result")
  void theWarningIsReadFromTheStoredDocument() throws Exception {
    summary.when(() -> InvoiceInboxSummary.totalMismatch(any(), any())).thenReturn(true);

    assertTrue(run(document(LINKED, EXISTING_LINE)).totalMismatch);

    ArgumentCaptor<JSONObject> document = ArgumentCaptor.forClass(JSONObject.class);
    summary.verify(() -> InvoiceInboxSummary.totalMismatch(document.capture(), any()));
    assertEquals("F-1", document.getValue().getJSONObject("header").getString("documentNo").trim());
  }
}
