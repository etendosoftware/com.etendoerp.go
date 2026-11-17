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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.AddressState;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicate;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicates;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Tax;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.TaxAnswer;

/**
 * The {@code apply} action of the invoice inbox (ETP-6050): the eight operations, every refusal of
 * the error table, atomicity, the caps, the stable line index, the tax rebuild, the totals, the
 * status transitions and the {@code ADJUSTED} matrix. The DAL, the guards and the lookups are
 * mocked: what is asserted is the stored {@code CURRENT_DATA} and the row the action leaves.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxApply
 */
class InvoiceInboxApplyTest {

  /** What the row of the mocked inbox document holds, readable after the action ran. */
  private static final class RowState {
    String status = "PENDING_REVIEW";
    String errorCode;
    boolean reviewed;
    boolean adjusted;
    String currentData;
    String targetType = "PURCHASE_INVOICE";
  }

  private static final String DOC = "{version:1,header:{vendor:{state:'linked',matchedBy:'taxId',"
      + "id:'V1',name:'ACME',candidates:[{id:'V9',name:'Other'}],"
      + "extracted:{name:'ACME SL',taxId:'B1'},addressState:'PRESENT',locationId:'LOC1'},"
      + "documentNo:'FC-1',invoiceDate:'2026-11-02',totalAmount:169.4,totalSource:'lines',"
      + "duplicate:{state:'none',invoices:[],inbox:[]},receiver:{check:'absent'}},"
      + "lines:[" + line(0, "Tornillo", 10, 10) + "," + line(1, "Arandela", 2, 20) + "],"
      + "notices:[]}";

  private static String line(int index, String description, double quantity, double price) {
    return "{index:" + index + ",description:'" + description + "',quantity:" + quantity
        + ",unitPrice:" + price + ",taxRate:21,taxLabel:'',product:{state:'linked',id:'P1',"
        + "name:'Prod1',matchedBy:'name',candidates:[{id:'P2',name:'Prod2'}]},"
        + "tax:{state:'resolved',id:'T21',name:'21%',rate:21,candidates:[]}}";
  }

  private MockedStatic<OBDal> dalStatic;
  private static final String READABLE_ORG = "ORG-READABLE";
  private MockedStatic<OBContext> context;
  private MockedStatic<OBMessageUtils> messages;
  private MockedStatic<InvoiceInboxEditGate> gate;
  private MockedStatic<InvoiceInboxReview> review;
  private MockedStatic<InvoiceInboxDuplicates> duplicates;
  private OBDal dal;
  private OcrLookups lookups;
  private IncomingDocument row;
  private RowState state;
  private NeoResponse gateRefusal;
  private boolean lookupsAvailable;
  private JSONArray inboxFound;
  private String openedWith;

  @BeforeEach
  void setUp() throws Exception {
    state = new RowState();
    state.currentData = DOC;
    inboxFound = new JSONArray();
    lookupsAvailable = true;
    gateRefusal = null;
    openedWith = null;
    dal = mock(OBDal.class);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    context = mockStatic(OBContext.class);
    OBContext obContext = mock(OBContext.class);
    when(obContext.getReadableOrganizations()).thenReturn(new String[] { READABLE_ORG });
    context.when(OBContext::getOBContext).thenReturn(obContext);
    messages = mockStatic(OBMessageUtils.class);
    row = mockRow();
    lookups = mockLookups();
    gate = mockStatic(InvoiceInboxEditGate.class, (Answer<Object>) inv -> {
      if (!"open".equals(inv.getMethod().getName())) {
        return inv.callRealMethod();
      }
      openedWith = inv.getArgument(1);
      InvoiceInboxEditGate.Opened opened = mock(InvoiceInboxEditGate.Opened.class);
      when(opened.refusal()).thenReturn(gateRefusal);
      when(opened.row()).thenReturn(gateRefusal == null ? row : null);
      return opened;
    });
    review = mockStatic(InvoiceInboxReview.class, (Answer<Object>) inv -> {
      switch (inv.getMethod().getName()) {
        case "lookupsFor":
          return lookupsAvailable ? Optional.of(lookups) : Optional.empty();
        case "respond":
          return NeoResponse.ok(new JSONObject().put("applied", true));
        default:
          return inv.callRealMethod();
      }
    });
    duplicates = mockStatic(InvoiceInboxDuplicates.class);
    duplicates.when(() -> InvoiceInboxDuplicates.find(any(), any(), any())).thenAnswer(inv -> inboxFound);
  }

  @AfterEach
  void tearDown() {
    duplicates.close();
    review.close();
    gate.close();
    messages.close();
    context.close();
    dalStatic.close();
  }

  private IncomingDocument mockRow() {
    IncomingDocument mocked = mock(IncomingDocument.class);
    when(mocked.getStatus()).thenAnswer(i -> state.status);
    when(mocked.getErrorCode()).thenAnswer(i -> state.errorCode);
    when(mocked.getCurrentData()).thenAnswer(i -> state.currentData);
    when(mocked.getTargetType()).thenAnswer(i -> state.targetType);
    when(mocked.isReviewed()).thenAnswer(i -> state.reviewed);
    when(mocked.isAdjusted()).thenAnswer(i -> state.adjusted);
    when(mocked.getId()).thenReturn("R1");
    org.mockito.Mockito.doAnswer(i -> {
      state.status = i.getArgument(0);
      return null;
    }).when(mocked).setStatus(anyString());
    org.mockito.Mockito.doAnswer(i -> {
      state.errorCode = i.getArgument(0);
      return null;
    }).when(mocked).setErrorCode(any());
    org.mockito.Mockito.doAnswer(i -> {
      state.reviewed = i.getArgument(0);
      return null;
    }).when(mocked).setReviewed(any());
    org.mockito.Mockito.doAnswer(i -> {
      state.adjusted = i.getArgument(0);
      return null;
    }).when(mocked).setAdjusted(any());
    org.mockito.Mockito.doAnswer(i -> {
      state.currentData = i.getArgument(0);
      return null;
    }).when(mocked).setCurrentData(any());
    return mocked;
  }

  private static OcrLookups mockLookups() {
    OcrLookups mocked = mock(OcrLookups.class);
    when(mocked.vendorById("V2")).thenReturn(Optional.of(new Party("V2", "Beta")));
    when(mocked.vendorById("V3")).thenReturn(Optional.of(new Party("V3", "Gamma")));
    when(mocked.vendorAddress("V2")).thenReturn(new Address(AddressState.PRESENT, "LOC2"));
    when(mocked.vendorAddress("V3")).thenReturn(new Address(AddressState.MISSING, null));
    when(mocked.productById("P2")).thenReturn(Optional.of(new Party("P2", "Prod2")));
    when(mocked.taxById(eq("T10"), any())).thenReturn(
        Optional.of(new Tax("T10", "10%", new BigDecimal("10.00"))));
    when(mocked.productCategoryById("C1")).thenReturn(Optional.of(new Party("C1", "Cat")));
    when(mocked.uomById("U1")).thenReturn(Optional.of(new Party("U1", "Unit")));
    when(mocked.duplicates(anyString(), anyString())).thenReturn(new Duplicates(true, List.of()));
    when(mocked.defaultTax(any(), any(), any())).thenAnswer(inv -> {
      BigDecimal rate = inv.getArgument(2);
      Tax tax = rate == null ? new Tax("TDEF", "Default 4%", new BigDecimal("4"))
          : new Tax("T" + rate.stripTrailingZeros().toPlainString(),
              rate.stripTrailingZeros().toPlainString() + "%", rate);
      return new TaxAnswer(true, tax, List.of(tax));
    });
    return mocked;
  }

  // ------------------------------------------------------------------ helpers

  /** The document a test is arranging: serialized when the action runs, so it can be edited after. */
  private JSONObject pending;

  private JSONObject use(JSONObject document) {
    pending = document;
    return document;
  }

  /** Stores the arranged document in the row and returns what the row holds. */
  private String commit() {
    if (pending != null) {
      state.currentData = pending.toString();
      pending = null;
    }
    return state.currentData;
  }

  private static JSONObject doc() throws Exception {
    return new JSONObject(DOC);
  }

  private static JSONObject op(String json) throws Exception {
    return new JSONObject(json);
  }

  private NeoResponse apply(String... operations) throws Exception {
    commit();
    JSONArray array = new JSONArray();
    for (String operation : operations) {
      array.put(op(operation));
    }
    return InvoiceInboxApply.apply("R1", new JSONObject().put("updated", "T").put("operations",
        array));
  }

  private JSONObject saved() throws Exception {
    return new JSONObject(state.currentData);
  }

  private JSONObject header() throws Exception {
    return saved().getJSONObject("header");
  }

  private JSONObject lineAt(int position) throws Exception {
    return saved().getJSONArray("lines").getJSONObject(position);
  }

  private static void assertRefusal(NeoResponse response, int status, String code)
      throws Exception {
    assertEquals(status, response.getHttpStatus());
    assertEquals(code, response.getBody().getJSONObject("error").getString("code"));
  }

  private static void assertSaved(NeoResponse response) throws Exception {
    assertEquals(200, response.getHttpStatus());
    assertTrue(response.getBody().optBoolean("applied"));
  }

  private void assertNothingWritten(String before) {
    verify(dal, never()).save(any());
    verify(dal, never()).flush();
    assertEquals(before, state.currentData);
    assertFalse(state.adjusted);
  }

  private void assertWritten() {
    verify(dal).save(row);
    verify(dal).flush();
  }

  // ------------------------------------------------------------------ the request shape

  @Test
  void aNullBodyIsInvalid() throws Exception {
    assertRefusal(InvoiceInboxApply.apply("R1", null), 400, "invalid_request");
  }

  @Test
  void anUnknownTopLevelKeyIsInvalid() throws Exception {
    JSONObject body = new JSONObject().put("updated", "T").put("operations",
        new JSONArray().put(op("{op:'addLine'}"))).put("extra", 1);

    assertRefusal(InvoiceInboxApply.apply("R1", body), 400, "invalid_request");
    verify(dal, never()).save(any());
  }

  @Test
  void aMissingOrBlankTokenIsMissingUpdated() throws Exception {
    JSONArray ops = new JSONArray().put(op("{op:'addLine'}"));

    assertRefusal(InvoiceInboxApply.apply("R1", new JSONObject().put("operations", ops)), 400,
        "missing_updated");
    assertRefusal(InvoiceInboxApply.apply("R1",
        new JSONObject().put("updated", "  ").put("operations", ops)), 400, "missing_updated");
    assertRefusal(InvoiceInboxApply.apply("R1",
        new JSONObject().put("updated", JSONObject.NULL).put("operations", ops)), 400,
        "missing_updated");
  }

  @Test
  void aTokenThatIsNotAStringIsInvalid() throws Exception {
    JSONArray ops = new JSONArray().put(op("{op:'addLine'}"));

    assertRefusal(InvoiceInboxApply.apply("R1",
        new JSONObject().put("updated", 123).put("operations", ops)), 400, "invalid_request");
  }

  @Test
  void zeroOrMoreThanFiftyOperationsAreInvalid() throws Exception {
    assertRefusal(InvoiceInboxApply.apply("R1",
        new JSONObject().put("updated", "T").put("operations", new JSONArray())), 400,
        "invalid_request");
    assertRefusal(InvoiceInboxApply.apply("R1", new JSONObject().put("updated", "T")), 400,
        "invalid_request");
    JSONArray many = new JSONArray();
    for (int i = 0; i < 51; i++) {
      many.put(op("{op:'setHeader',documentNo:'FC-1'}"));
    }
    assertRefusal(InvoiceInboxApply.apply("R1",
        new JSONObject().put("updated", "T").put("operations", many)), 400, "invalid_request");
    verify(dal, never()).save(any());
  }

  @Test
  void exactlyFiftyOperationsAreAccepted() throws Exception {
    String[] ops = new String[50];
    java.util.Arrays.fill(ops, "{op:'setHeader',documentNo:'FC-1'}");

    assertSaved(apply(ops));
  }

  @Test
  void anUnknownOperationKeyOrShapeIsInvalid() throws Exception {
    String before = commit();
    String[] bad = {
        "{op:'explode'}",
        "{op:'setHeader',totalAmount:1}",
        "{op:'setHeader',vendor:'x'}",
        "{op:'setHeader',currency:'EUR'}",
        "{op:'setLine',quantity:1}",
        "{op:'setLine',index:-1,quantity:1}",
        "{op:'setLine',index:1.5,quantity:1}",
        "{op:'setLine',index:'0',quantity:1}",
        "{op:'setLine',index:0,color:'red'}",
        "{op:'addLine',index:3}",
        "{op:'removeLine'}",
        "{op:'selectVendor',id:'  '}",
        "{op:'selectVendor'}",
        "{op:'selectVendor',id:12}",
        "{op:'selectProduct',index:0}",
        "{op:'newVendor',fields:'x'}",
        "{op:'newVendor',fields:{color:'red'}}",
        "{op:'newProduct',index:0,categoryId:5}",
        "{op:'newProduct',index:0,color:'red'}" };
    for (String operation : bad) {
      assertRefusal(apply(operation), 400, "invalid_request");
    }
    assertNothingWritten(before);
  }

  @Test
  void aNonObjectOperationIsInvalid() throws Exception {
    JSONArray ops = new JSONArray().put("addLine");

    assertRefusal(InvoiceInboxApply.apply("R1",
        new JSONObject().put("updated", "T").put("operations", ops)), 400, "invalid_request");
  }

  @Test
  void aBodyOverSixtyFourKilobytesIsRefusedAsTooLarge() throws Exception {
    String big = "x".repeat(70_000);
    JSONObject operation = op("{op:'setHeader'}").put("documentNo", big);
    JSONObject body = new JSONObject().put("updated", "T").put("operations",
        new JSONArray().put(operation));

    assertRefusal(InvoiceInboxApply.apply("R1", body), 413, "request_too_large");
    verify(dal, never()).save(any());
  }

  // ------------------------------------------------------------------ the guards

  @Test
  void theGuardsRefusalIsAnsweredAsIsAndNothingIsWritten() throws Exception {
    String before = commit();
    gateRefusal = InvoiceInboxEditGate.refuse(409, "stale_document",
        InvoiceInboxMessages.STALE_DOCUMENT, null);

    NeoResponse response = apply("{op:'setHeader',documentNo:'FC-2'}");

    assertRefusal(response, 409, "stale_document");
    assertNothingWritten(before);
  }

  @Test
  void theTokenIsPassedTrimmedToTheGuards() throws Exception {
    JSONObject body = new JSONObject().put("updated", "  2026-11-13T10:00:00.123+01:00 ")
        .put("operations", new JSONArray().put(op("{op:'setHeader',documentNo:'FC-1'}")));

    InvoiceInboxApply.apply("R1", body);

    assertEquals("2026-11-13T10:00:00.123+01:00", openedWith);
  }

  @Test
  void aNotEditableRefusalCarriesTheDocumentStatus() throws Exception {
    gateRefusal = InvoiceInboxEditGate.refuse(409, "not_editable", InvoiceInboxMessages.NOT_EDITABLE,
        InvoiceInboxEditGate.details("documentStatus", "CONFIRMED"), "status", "CONFIRMED");

    NeoResponse response = apply("{op:'addLine'}");

    assertRefusal(response, 409, "not_editable");
    assertEquals("CONFIRMED",
        response.getBody().getJSONObject("error").getString("documentStatus"));
  }

  // ------------------------------------------------------------------ setHeader

  @Test
  void setHeaderChangesTheDocumentNumberAndSyncsTheSummary() throws Exception {
    assertSaved(apply("{op:'setHeader',documentNo:'FC-2'}"));

    assertEquals("FC-2", header().getString("documentNo"));
    assertWritten();
    verify(row).setOrderReference("FC-2");
    assertEquals("PENDING_REVIEW", state.status);
    assertNull(state.errorCode);
    assertTrue(state.adjusted);
  }

  @Test
  void setHeaderWithTheSameValueWritesNothingAndDoesNotMark() throws Exception {
    String before = commit();

    assertSaved(apply("{op:'setHeader',documentNo:'FC-1',invoiceDate:'2026-11-02'}"));

    assertNothingWritten(before);
  }

  @Test
  void aNullDocumentNumberEqualsAnEmptyOne() throws Exception {
    use(doc()).getJSONObject("header").put("documentNo", "");
    String before = commit();

    apply("{op:'setHeader',documentNo:null}");

    assertNothingWritten(before);
  }

  @Test
  void theDocumentNumberIsCleanedAsAnExtractedText() throws Exception {
    apply("{op:'setHeader',documentNo:'  FC\\n  9\\u0000\\u200b '}");

    assertEquals("FC 9", header().getString("documentNo"));
  }

  @Test
  void aDocumentNumberOverTwoHundredCharactersIsCutAtTwoHundred() throws Exception {
    apply("{op:'setHeader',documentNo:'" + "9".repeat(500) + "'}");

    assertEquals(200, header().getString("documentNo").length());
  }

  @Test
  void aDocumentNumberOverTwentyIsAValidationNotARefusal() throws Exception {
    NeoResponse response = apply("{op:'setHeader',documentNo:'" + "A".repeat(21) + "'}");

    assertSaved(response);
    assertEquals("ERROR", state.status);
    assertEquals("REFERENCE_TOO_LONG", state.errorCode);
    verify(row).setOrderReference(null);
  }

  @Test
  void aDocumentNumberThatIsNotAStringIsInvalidValue() throws Exception {
    String before = commit();

    NeoResponse response = apply("{op:'setHeader',documentNo:5}");

    assertRefusal(response, 422, "invalid_value");
    assertEquals("documentNo", response.getBody().getJSONObject("error").getString("field"));
    assertNothingWritten(before);
  }

  @Test
  void setHeaderChangesTheDateAndRebuildsEveryTaxWithIt() throws Exception {
    apply("{op:'setHeader',invoiceDate:'2026-11-05'}");

    assertEquals("2026-11-05", header().getString("invoiceDate"));
    verify(lookups).defaultTax(eq("LOC1"), eq("2026-11-05"),
        org.mockito.ArgumentMatchers.argThat(r -> r.compareTo(new BigDecimal("21")) == 0));
    assertEquals("resolved", lineAt(0).getJSONObject("tax").getString("state"));
    assertTrue(state.adjusted);
  }

  @Test
  void anInvalidDateIsInvalidValue() throws Exception {
    String before = commit();
    for (String date : new String[] { "'02/11/2026'", "'1899-12-31'", "'2101-01-01'",
        "'2026-02-30'", "'2026-1-2'", "20261102", "true" }) {
      NeoResponse response = apply("{op:'setHeader',invoiceDate:" + date + "}");
      assertRefusal(response, 422, "invalid_value");
      assertEquals("invoiceDate",
          response.getBody().getJSONObject("error").getString("field"));
    }
    assertNothingWritten(before);
  }

  @Test
  void clearingTheDateMakesTheDocumentInvalid() throws Exception {
    assertSaved(apply("{op:'setHeader',invoiceDate:null}"));

    assertTrue(header().isNull("invoiceDate"));
    assertEquals("ERROR", state.status);
    assertEquals("MISSING_FIELDS", state.errorCode);
    assertTrue(saved().getJSONObject("validation").getJSONArray("missing").toString()
        .contains("invoiceDate"));
    verify(row).setInvoiceDate(null);
  }

  @Test
  void clearingAnAlreadyEmptyDateIsANoOpForNullAndBlank() throws Exception {
    use(doc()).getJSONObject("header").put("invoiceDate", JSONObject.NULL);
    String before = commit();

    apply("{op:'setHeader',invoiceDate:null}");
    apply("{op:'setHeader',invoiceDate:''}");

    assertNothingWritten(before);
  }

  // ------------------------------------------------------------------ setLine

  @Test
  void setLineQuantityRecomputesTheTotalFromTheLines() throws Exception {
    assertSaved(apply("{op:'setLine',index:0,quantity:12}"));

    assertEquals(12, lineAt(0).getDouble("quantity"), 0);
    // 12 x 10 + 2 x 20 = 160, tax 21 % = 33.60
    assertEquals(193.6, header().getDouble("totalAmount"), 0.0001);
    verify(row).setGrandTotalAmount(BigDecimal.valueOf(193.6));
    assertTrue(state.adjusted);
  }

  @Test
  void aNumberEqualAtTheStoredScaleIsNoChange() throws Exception {
    String before = commit();

    apply("{op:'setLine',index:0,quantity:10.00004,unitPrice:10.0,taxRate:21.001}");

    assertNothingWritten(before);
  }

  @Test
  void theNumbersAreRoundedHalfUpToTheStoredScale() throws Exception {
    apply("{op:'setLine',index:0,quantity:1.23455,unitPrice:0.3555555,taxRate:7.005}");

    assertEquals(1.2346, lineAt(0).getDouble("quantity"), 0);
    assertEquals(0.355556, lineAt(0).getDouble("unitPrice"), 0, "the price keeps 6 decimals");
    assertEquals(7.01, lineAt(0).getDouble("taxRate"), 0);
  }

  @Test
  void aLineBasedTaxIsRoundedPerLineAndStampedOnTheTaxBlock() throws Exception {
    when(lookups.taxDocAmountModes(any())).thenReturn(java.util.Map.of("T21", "L"));
    use(doc()).getJSONArray("lines").getJSONObject(0).put("quantity", 1).put("unitPrice", 0.25)
        .put("taxRate", 10);
    use(pending);
    JSONObject second = pending.getJSONArray("lines").getJSONObject(1);
    second.put("quantity", 1).put("unitPrice", 0.25).put("taxRate", 10);

    apply("{op:'setLine',index:0,description:'x'}");

    assertEquals("L", lineAt(0).getJSONObject("tax").getString("docTaxAmount"));
    // two lines of 0.25 at 10 %, per line round2(0.025) = 0.03 twice: 0.50 + 0.06
    assertEquals(0.56, header().getDouble("totalAmount"), 0.0001);
  }

  @Test
  void aPriceOfSixDecimalsIsKeptAsIs() throws Exception {
    apply("{op:'setLine',index:0,unitPrice:0.123457}");

    assertEquals(0.123457, lineAt(0).getDouble("unitPrice"), 0);
  }

  @Test
  void theCurrencyIsDerivedFromTheVendorWhenMissingAndOnAVendorChange() throws Exception {
    when(lookups.documentCurrency(any(), any())).thenReturn(Optional.of(
        new OcrLookups.DocCurrency("JPY", "JPY", "Y", 0)));
    when(lookups.documentCurrency(eq("V2"), any())).thenReturn(Optional.of(
        new OcrLookups.DocCurrency("USD", "USD", "$", 2)));

    apply("{op:'setLine',index:0,quantity:3}");
    assertEquals("JPY", header().getString("currencyId"));
    // 3 x 10 + 2 x 20 = 70 at 21 % -> scale 0: tax round0(14.7) = 15, total 85
    assertEquals(85, header().getDouble("totalAmount"), 0);

    apply("{op:'selectVendor',id:'V2'}");
    assertEquals("USD", header().getString("currencyId"));
    assertEquals(84.7, header().getDouble("totalAmount"), 0.0001);
  }

  @Test
  void aStoredCurrencyGivesTheScaleWithoutDerivingItAgain() throws Exception {
    when(lookups.currencyById("JPY")).thenReturn(Optional.of(
        new OcrLookups.DocCurrency("JPY", "JPY", "Y", 0)));
    use(doc()).getJSONObject("header").put("currencyId", "JPY");

    apply("{op:'setLine',index:0,quantity:3}");

    assertEquals("JPY", header().getString("currencyId"));
    // 3 x 10 + 2 x 20 = 70 at 21 % -> scale 0: tax round0(14.7) = 15, total 85
    assertEquals(85, header().getDouble("totalAmount"), 0);
    verify(lookups, never()).documentCurrency(any(), any());
  }

  @Test
  void aStoredCurrencyThatCannotBeReadIsKeptAndTheTotalIsInCents() throws Exception {
    use(doc()).getJSONObject("header").put("currencyId", "GONE");

    apply("{op:'setLine',index:0,quantity:3}");

    assertEquals("GONE", header().getString("currencyId"));
    assertEquals(84.7, header().getDouble("totalAmount"), 0.0001);
  }

  @Test
  void aVendorChangeThatCannotDeriveACurrencyDropsTheStoredOneAndRoundsToCents()
      throws Exception {
    use(doc()).getJSONObject("header").put("currencyId", "JPY");

    apply("{op:'selectVendor',id:'V2'}");

    assertFalse(header().has("currencyId"));
    assertEquals(169.4, header().getDouble("totalAmount"), 0.0001);
  }

  @Test
  void aVendorChangeAsksTheCurrencyOfTheNewVendorAndANonLinkedVendorAsksWithNone()
      throws Exception {
    when(lookups.documentCurrency(any(), any())).thenReturn(Optional.of(
        new OcrLookups.DocCurrency("EUR", "EUR", "E", 2)));

    apply("{op:'selectVendor',id:'V2'}");
    verify(lookups).documentCurrency(eq("V2"), any());

    state = new RowState();
    apply("{op:'newVendor'}");
    verify(lookups).documentCurrency(isNull(), any());
  }

  @Test
  void aPrintedTotalIsNeverTouchedByTheCurrencyNorTheEditsToTheLines() throws Exception {
    when(lookups.documentCurrency(any(), any())).thenReturn(Optional.of(
        new OcrLookups.DocCurrency("JPY", "JPY", "Y", 0)));
    use(doc()).getJSONObject("header").put("totalSource", "printed").put("totalAmount", 169.4);

    apply("{op:'setLine',index:0,quantity:3}", "{op:'selectVendor',id:'V2'}");

    assertEquals(169.4, header().getDouble("totalAmount"), 0);
    assertEquals("printed", header().getString("totalSource"));
  }

  @Test
  void aCurrencyOfThreeDecimalsKeepsThreeInTheTotal() throws Exception {
    when(lookups.documentCurrency(any(), any())).thenReturn(Optional.of(
        new OcrLookups.DocCurrency("KWD", "KWD", "K", 3)));
    use(doc()).getJSONArray("lines").getJSONObject(0).put("quantity", 1).put("unitPrice", 0.12345);
    pending.getJSONArray("lines").getJSONObject(1).put("quantity", 1).put("unitPrice", 0);

    apply("{op:'setLine',index:1,description:'x'}");

    // net 0.123 (0.12345 to 3 decimals), tax 21 % = 0.02583 -> 0.026
    assertEquals(0.149, header().getDouble("totalAmount"), 0.00001);
  }

  @Test
  void theInputScalesAreFourSixFourAndTwoAndNothingBeyondIsStored() throws Exception {
    apply("{op:'setLine',index:0,quantity:1.00005,unitPrice:0.0000005,discount:12.34565,"
        + "taxRate:4.995}");

    assertEquals(1.0001, lineAt(0).getDouble("quantity"), 0);
    assertEquals(0.000001, lineAt(0).getDouble("unitPrice"), 0);
    assertEquals(12.3457, lineAt(0).getDouble("discount"), 0);
    assertEquals(5.0, lineAt(0).getDouble("taxRate"), 0);
    assertEquals(4, InvoiceInboxApply.QUANTITY_SCALE);
    assertEquals(6, InvoiceInboxApply.PRICE_SCALE);
    assertEquals(4, InvoiceInboxApply.DISCOUNT_SCALE);
  }

  @Test
  void aValueThatRoundsToTheStoredOneAtItsScaleIsNoChangeAndNeverMarks() throws Exception {
    apply("{op:'setLine',index:0,unitPrice:0.1234567}");
    assertEquals(0.123457, lineAt(0).getDouble("unitPrice"), 0);
    assertTrue(state.adjusted);
    state.adjusted = false;

    assertSaved(apply("{op:'setLine',index:0,unitPrice:0.1234571}"));

    assertFalse(state.adjusted, "0.1234571 is 0.123457 at six decimals: nothing changed");
    verify(dal, times(1)).save(any());
  }

  @Test
  void aSevenDecimalPriceThatDiffersAtTheSixthDecimalDoesMark() throws Exception {
    apply("{op:'setLine',index:0,unitPrice:0.123457}");
    state.adjusted = false;

    apply("{op:'setLine',index:0,unitPrice:0.1234575}");

    assertEquals(0.123458, lineAt(0).getDouble("unitPrice"), 0);
    assertTrue(state.adjusted);
  }

  @Test
  void aNegativeQuantityIsAcceptedAndTheTotalIsACredit() throws Exception {
    assertSaved(apply("{op:'setLine',index:0,quantity:-3}"));

    assertEquals(-3, lineAt(0).getDouble("quantity"), 0);
    // -3 x 10 + 2 x 20 = 10 at 21 %
    assertEquals(12.1, header().getDouble("totalAmount"), 0.0001);
    assertEquals("PENDING_REVIEW", state.status, "a credit line is a valid document");
  }

  @Test
  void aNegativePriceIsAcceptedAndTheTotalCanGoBelowZeroWithoutBecomingAnError()
      throws Exception {
    assertSaved(apply("{op:'setLine',index:0,unitPrice:-10}"));

    assertEquals(-10, lineAt(0).getDouble("unitPrice"), 0);
    // 10 x -10 + 2 x 20 = -60 at 21 % = -72.60
    assertEquals(-72.6, header().getDouble("totalAmount"), 0.0001);
    assertEquals("PENDING_REVIEW", state.status);
    assertTrue(saved().getJSONObject("validation").getBoolean("valid"));
  }

  @Test
  void aZeroQuantityIsAcceptedAndTheDocumentStaysValid() throws Exception {
    assertSaved(apply("{op:'setLine',index:0,quantity:0,unitPrice:0}"));

    assertEquals(0, lineAt(0).getDouble("quantity"), 0);
    assertEquals(0, lineAt(0).getDouble("unitPrice"), 0);
    assertEquals(48.4, header().getDouble("totalAmount"), 0.0001);
    assertEquals("PENDING_REVIEW", state.status);
  }

  @Test
  void aCreditDocumentWithLineBasedTaxRoundsEachNegativeLineAway() throws Exception {
    when(lookups.taxDocAmountModes(any())).thenReturn(java.util.Map.of("T21", "L"));
    use(doc()).getJSONArray("lines").getJSONObject(0).put("quantity", -1).put("unitPrice", 0.125);
    pending.getJSONArray("lines").getJSONObject(1).put("quantity", -1).put("unitPrice", 0.125);

    apply("{op:'setLine',index:0,description:'x'}");

    // each net -0.13, tax per line -0.0273 -> -0.03: -0.26 + -0.06
    assertEquals(-0.32, header().getDouble("totalAmount"), 0.0001);
  }

  @Test
  void aNullQuantityOrPriceMakesTheDocumentAnErrorWithTheLineAndFieldInTheVerdict()
      throws Exception {
    assertSaved(apply("{op:'setLine',index:1,quantity:null}"));

    assertEquals("ERROR", state.status);
    assertTrue(saved().getJSONObject("validation").getJSONArray("missing").toString()
        .contains("line[1].quantity"));
  }

  @Test
  void aDiscountOutsideZeroToHundredIsRefusedAndNothingIsWritten() throws Exception {
    String before = commit();

    assertRefusal(apply("{op:'setLine',index:0,discount:100.01}"), 422, "invalid_value");
    assertRefusal(apply("{op:'setLine',index:0,discount:-1}"), 422, "invalid_value");
    assertNothingWritten(before);
  }

  @Test
  void aNewRateRebuildsTheTaxOfThatLineOnlyAndAsksOncePerRate() throws Exception {
    apply("{op:'setLine',index:0,taxRate:10}", "{op:'setLine',index:1,taxRate:10}");

    assertEquals("T10", lineAt(0).getJSONObject("tax").getString("id"));
    assertEquals("T10", lineAt(1).getJSONObject("tax").getString("id"));
    verify(lookups, times(1)).defaultTax(eq("LOC1"), any(),
        org.mockito.ArgumentMatchers.argThat(r -> r != null && r.compareTo(BigDecimal.TEN) == 0));
  }

  @Test
  void onlyTheLineWhoseRateChangedHasItsTaxRebuilt() throws Exception {
    apply("{op:'setLine',index:1,taxRate:10}");

    assertEquals("T21", lineAt(0).getJSONObject("tax").getString("id"));
    assertEquals("T10", lineAt(1).getJSONObject("tax").getString("id"));
  }

  @Test
  void aNullRateIsStoredAndItsTaxAskedWithoutRate() throws Exception {
    apply("{op:'setLine',index:0,taxRate:null}");

    assertTrue(lineAt(0).isNull("taxRate"));
    verify(lookups).defaultTax("LOC1", "2026-11-02", null);
    assertTrue(state.adjusted);
  }

  @Test
  void aTaxWithNoChosenCandidateIsNoTaxAtRate() throws Exception {
    when(lookups.defaultTax(any(), any(), any())).thenReturn(
        new TaxAnswer(true, null, List.of(new Tax("T4", "4%", new BigDecimal("4")))));

    apply("{op:'setLine',index:0,taxRate:33}");

    assertEquals("no_tax_at_rate", lineAt(0).getJSONObject("tax").getString("state"));
    assertEquals("T4", lineAt(0).getJSONObject("tax").getJSONArray("candidates")
        .getJSONObject(0).getString("id"));
  }

  @Test
  void anUnknownTaxAnswerIsUnknown() throws Exception {
    when(lookups.defaultTax(any(), any(), any())).thenReturn(new TaxAnswer(false, null, List.of()));

    apply("{op:'setLine',index:0,taxRate:33}");

    assertEquals("unknown", lineAt(0).getJSONObject("tax").getString("state"));
  }

  @Test
  void outOfRangeOrNonNumericValuesAreInvalidValueWithFieldAndIndex() throws Exception {
    String before = commit();
    String[][] bad = {
        { "taxRate", "101" }, { "taxRate", "-1" }, { "discount", "100.01" }, { "discount", "-1" },
        { "quantity", "'12'" }, { "quantity", "1e10" }, { "unitPrice", "-1e10" },
        { "quantity", "true" }, { "description", "5" } };
    for (String[] pair : bad) {
      NeoResponse response = apply("{op:'setLine',index:1," + pair[0] + ":" + pair[1] + "}");
      assertRefusal(response, 422, "invalid_value");
      JSONObject error = response.getBody().getJSONObject("error");
      assertEquals(pair[0], error.getString("field"));
      assertEquals(1, error.getInt("index"));
    }
    assertNothingWritten(before);
  }

  @Test
  void theBoundsThemselvesAreAccepted() throws Exception {
    assertSaved(apply("{op:'setLine',index:0,taxRate:100,discount:100}"));
    assertSaved(apply("{op:'setLine',index:0,taxRate:0,discount:0}"));

    assertEquals(0, lineAt(0).getDouble("taxRate"), 0);
    assertFalse(lineAt(0).has("discount"));
  }

  @Test
  void aDiscountPercentLowersTheLineAndTheTotal() throws Exception {
    apply("{op:'setLine',index:0,discount:10}");

    assertEquals(10, lineAt(0).getDouble("discount"), 0);
    // unit 9.000000, net 90.00 + 40.00 = 130.00, tax 27.30
    assertEquals(157.3, header().getDouble("totalAmount"), 0.0001);
    assertTrue(state.adjusted);
  }

  @Test
  void aDiscountIsStoredWithFourDecimals() throws Exception {
    apply("{op:'setLine',index:0,discount:33.33333}");

    assertEquals(33.3333, lineAt(0).getDouble("discount"), 0);
  }

  @Test
  void anAbsentDiscountEqualsZeroAndNullOrZeroIsNoChange() throws Exception {
    String before = commit();

    apply("{op:'setLine',index:0,discount:null}");
    apply("{op:'setLine',index:0,discount:0}");
    apply("{op:'setLine',index:0,discount:0.00001}");

    assertNothingWritten(before);
    assertFalse(lineAt(0).has("discount"));
  }

  @Test
  void removingAStoredDiscountDropsTheKeyAndMarks() throws Exception {
    use(doc()).getJSONArray("lines").getJSONObject(0).put("discount", 10);

    apply("{op:'setLine',index:0,discount:null}");

    assertFalse(lineAt(0).has("discount"));
    assertTrue(state.adjusted);
    assertEquals(169.4, header().getDouble("totalAmount"), 0.0001);
  }

  @Test
  void aTenAndATenPointZeroDiscountAreTheSameValue() throws Exception {
    use(doc()).getJSONArray("lines").getJSONObject(0).put("discount", 10);
    String before = commit();

    apply("{op:'setLine',index:0,discount:10.0000}");

    assertNothingWritten(before);
  }

  @Test
  void theDescriptionIsCleanedCappedAndNeverRematchesTheProduct() throws Exception {
    apply("{op:'setLine',index:0,description:'  <b>Tornillo</b>\\t M8\\n" + "z".repeat(300) + "'}");

    String description = lineAt(0).getString("description");
    assertTrue(description.startsWith("<b>Tornillo</b> M8 z"));
    assertEquals(200, description.length());
    assertEquals("linked", lineAt(0).getJSONObject("product").getString("state"));
    assertEquals("P1", lineAt(0).getJSONObject("product").getString("id"));
    verify(lookups, never()).productCandidates(any());
    assertTrue(state.adjusted);
  }

  @Test
  void aSameDescriptionIsNoChange() throws Exception {
    String before = commit();

    apply("{op:'setLine',index:0,description:' Tornillo '}");

    assertNothingWritten(before);
  }

  @Test
  void aLineThatDoesNotExistIsLineNotFound() throws Exception {
    String before = commit();

    NeoResponse response = apply("{op:'setLine',index:9,quantity:1}");

    assertRefusal(response, 422, "line_not_found");
    assertEquals(9, response.getBody().getJSONObject("error").getInt("index"));
    assertNothingWritten(before);
  }

  // ------------------------------------------------------------------ atomicity

  @Test
  void aFailingOperationDiscardsTheEarlierOnesOfTheRequest() throws Exception {
    String before = commit();

    NeoResponse response = apply("{op:'setHeader',documentNo:'FC-2'}",
        "{op:'setLine',index:0,quantity:99}", "{op:'setLine',index:9,quantity:1}");

    assertRefusal(response, 422, "line_not_found");
    assertNothingWritten(before);
    verify(row, never()).setAdjusted(true);
    verify(row, never()).setStatus(anyString());
  }

  @Test
  void anInvalidValueAfterAValidOperationAlsoWritesNothing() throws Exception {
    String before = commit();

    NeoResponse response = apply("{op:'addLine'}", "{op:'setLine',index:0,taxRate:500}");

    assertRefusal(response, 422, "invalid_value");
    assertNothingWritten(before);
  }

  @Test
  void theOperationsApplyInOrderOnTheWorkingCopy() throws Exception {
    assertSaved(apply("{op:'addLine'}", "{op:'setLine',index:2,description:'Nueva',quantity:3,"
        + "unitPrice:5}", "{op:'removeLine',index:0}"));

    JSONArray lines = saved().getJSONArray("lines");
    assertEquals(2, lines.length());
    assertEquals(1, lines.getJSONObject(0).getInt("index"));
    assertEquals("Nueva", lines.getJSONObject(1).getString("description"));
  }

  // ------------------------------------------------------------------ addLine / removeLine

  @Test
  void addLineTakesTheNextIndexTheRateOfTheLastLineAndAPendingProduct() throws Exception {
    use(doc()).getJSONArray("lines").getJSONObject(1).put("taxRate", 10);

    assertSaved(apply("{op:'addLine'}"));

    JSONObject added = lineAt(2);
    assertEquals(2, added.getInt("index"));
    assertEquals("", added.getString("description"));
    assertEquals(1, added.getDouble("quantity"), 0);
    assertEquals(0, added.getDouble("unitPrice"), 0);
    assertEquals(10, added.getDouble("taxRate"), 0);
    assertFalse(added.has("discount"));
    assertEquals("none", added.getJSONObject("product").getString("state"));
    assertEquals("T10", added.getJSONObject("tax").getString("id"));
    assertTrue(state.adjusted);
    // it needs a product before the document can be reviewed
    assertEquals("ERROR", state.status);
    assertTrue(saved().getJSONObject("validation").getJSONArray("missing").toString()
        .contains("line[2].product"));
  }

  @Test
  void addLineAfterALineWithoutRateTakesTheVendorDefaultTaxAndItsRate() throws Exception {
    use(doc()).getJSONArray("lines").getJSONObject(1).put("taxRate", JSONObject.NULL);

    apply("{op:'addLine'}");

    assertEquals(4, lineAt(2).getDouble("taxRate"), 0);
    assertEquals("TDEF", lineAt(2).getJSONObject("tax").getString("id"));
    verify(lookups).defaultTax("LOC1", "2026-11-02", null);
  }

  @Test
  void addLineOnADocumentWithoutLinesStartsAtZeroWithTheVendorDefault() throws Exception {
    use(doc()).put("lines", new JSONArray());

    apply("{op:'addLine'}");

    assertEquals(0, lineAt(0).getInt("index"));
    assertEquals(4, lineAt(0).getDouble("taxRate"), 0);
  }

  @Test
  void addLineWithoutAVendorAddressLeavesTheTaxPendingAndNoRate() throws Exception {
    JSONObject document = use(doc());
    document.put("lines", new JSONArray());
    document.getJSONObject("header").getJSONObject("vendor").put("addressState", "MISSING")
        .put("locationId", JSONObject.NULL);

    apply("{op:'addLine'}");

    assertEquals("pending_vendor", lineAt(0).getJSONObject("tax").getString("state"));
    assertTrue(lineAt(0).isNull("taxRate"));
    verify(lookups, never()).defaultTax(any(), any(), any());
  }

  @Test
  void aHundredLinesIsTheCapAndTheNextOneIsRefused() throws Exception {
    JSONObject document = use(doc());
    JSONArray lines = new JSONArray();
    for (int i = 0; i < 99; i++) {
      lines.put(op(line(i, "L" + i, 1, 1)));
    }
    document.put("lines", lines);

    assertSaved(apply("{op:'addLine'}"));
    assertEquals(100, saved().getJSONArray("lines").length());
    String before = commit();

    NeoResponse response = apply("{op:'addLine'}");

    assertRefusal(response, 422, "too_many_lines");
    assertEquals(100, response.getBody().getJSONObject("error").getInt("max"));
    assertEquals(before, state.currentData);
  }

  @Test
  void theIndexIsStableAfterRemovingAndAddingALine() throws Exception {
    assertSaved(apply("{op:'removeLine',index:0}"));
    assertEquals(1, lineAt(0).getInt("index"));

    assertSaved(apply("{op:'addLine'}"));

    JSONArray lines = saved().getJSONArray("lines");
    assertEquals(1, lines.getJSONObject(0).getInt("index"));
    assertEquals(2, lines.getJSONObject(1).getInt("index"));
  }

  @Test
  void linesWithoutAnIndexGetTheirPositionBeforeTheOperationsRun() throws Exception {
    JSONObject document = use(doc());
    document.getJSONArray("lines").getJSONObject(0).remove("index");
    document.getJSONArray("lines").getJSONObject(1).remove("index");

    assertSaved(apply("{op:'setLine',index:1,quantity:5}"));

    assertEquals(0, lineAt(0).getInt("index"));
    assertEquals(5, lineAt(1).getDouble("quantity"), 0);
  }

  @Test
  void removeLineRecomputesTheTotalAndAlwaysMarks() throws Exception {
    assertSaved(apply("{op:'removeLine',index:1}"));

    assertEquals(1, saved().getJSONArray("lines").length());
    // 10 x 10 + 21 % = 121
    assertEquals(121, header().getDouble("totalAmount"), 0.0001);
    assertTrue(state.adjusted);
  }

  @Test
  void removingEveryLineLeavesAnInvalidDocumentWithATotalOfZero() throws Exception {
    apply("{op:'removeLine',index:0}", "{op:'removeLine',index:1}");

    assertEquals(0, header().getDouble("totalAmount"), 0);
    assertEquals("ERROR", state.status);
    assertTrue(saved().getJSONObject("validation").getJSONArray("missing").toString()
        .contains("lines"));
  }

  @Test
  void removingALineThatDoesNotExistIsLineNotFound() throws Exception {
    assertRefusal(apply("{op:'removeLine',index:7}"), 422, "line_not_found");
  }

  // ------------------------------------------------------------------ totals

  @Test
  void aPrintedTotalNeverChanges() throws Exception {
    use(doc()).getJSONObject("header").put("totalSource", "printed").put("totalAmount", 200);

    apply("{op:'setLine',index:0,quantity:12}");

    assertEquals(200, header().getDouble("totalAmount"), 0);
    assertEquals("printed", header().getString("totalSource"));
  }

  @Test
  void theTotalUsesTheGoRoundingOrderAndAHalfCentLine() throws Exception {
    use(doc()).put("lines", new JSONArray());

    apply("{op:'addLine'}", "{op:'setLine',index:0,quantity:48324,unitPrice:0.2771,taxRate:0,"
        + "discount:77.1721}");

    // the real GO rule gives 3056.78 (the single-rounding formula would give 3056.79)
    assertEquals(3056.78, header().getDouble("totalAmount"), 0.00001);
  }

  // ------------------------------------------------------------------ selectVendor

  @Test
  void selectVendorLinksItAndRebuildsTheTaxesAndDuplicatesForItsAddress() throws Exception {
    assertSaved(apply("{op:'selectVendor',id:'V2'}"));

    JSONObject vendor = header().getJSONObject("vendor");
    assertEquals("linked", vendor.getString("state"));
    assertEquals("manual", vendor.getString("matchedBy"));
    assertEquals("V2", vendor.getString("id"));
    assertEquals("Beta", vendor.getString("name"));
    assertEquals("PRESENT", vendor.getString("addressState"));
    assertEquals("LOC2", vendor.getString("locationId"));
    assertEquals("V9", vendor.getJSONArray("candidates").getJSONObject(0).getString("id"));
    assertEquals("ACME SL", vendor.getJSONObject("extracted").getString("name"));
    verify(lookups, times(1)).defaultTax(eq("LOC2"), eq("2026-11-02"), any());
    verify(lookups).duplicates("V2", "FC-1");
    assertTrue(state.adjusted);
  }

  @Test
  void reselectingTheLinkedVendorIsANoOp() throws Exception {
    String before = commit();

    assertSaved(apply("{op:'selectVendor',id:'V1'}"));

    assertNothingWritten(before);
    verify(lookups, never()).vendorById(anyString());
  }

  @Test
  void anUnknownVendorIsRefusedAndNothingIsWritten() throws Exception {
    String before = commit();

    assertRefusal(apply("{op:'selectVendor',id:'NOPE'}"), 422, "unknown_vendor");
    assertNothingWritten(before);
  }

  @Test
  void withoutLookupsEveryResolutionIsUnknown() throws Exception {
    lookupsAvailable = false;

    assertRefusal(apply("{op:'selectVendor',id:'V2'}"), 422, "unknown_vendor");
    assertRefusal(apply("{op:'selectProduct',index:0,id:'P2'}"), 422, "unknown_product");
    assertRefusal(apply("{op:'newProduct',index:0,categoryId:'C1'}"), 422, "unknown_category");
    assertRefusal(apply("{op:'newProduct',index:0,uomId:'U1'}"), 422, "unknown_uom");
  }

  @Test
  void aVendorWithoutAddressLeavesTheTaxesPendingAndRaisesTheNotice() throws Exception {
    apply("{op:'selectVendor',id:'V3'}");

    JSONObject vendor = header().getJSONObject("vendor");
    assertEquals("MISSING", vendor.getString("addressState"));
    assertTrue(vendor.isNull("locationId"));
    assertEquals("pending_vendor", lineAt(0).getJSONObject("tax").getString("state"));
    assertTrue(saved().getJSONArray("notices").toString().contains("vendor_address_missing"));

    apply("{op:'selectVendor',id:'V2'}");

    assertFalse(saved().getJSONArray("notices").toString().contains("vendor_address_missing"));
    assertEquals("T21", lineAt(0).getJSONObject("tax").getString("id"));
  }

  @Test
  void selectingAVendorOverAnAmbiguousOneMarksAndCompletesTheDocument() throws Exception {
    JSONObject vendor = use(doc()).getJSONObject("header").getJSONObject("vendor");
    vendor.put("state", "ambiguous").remove("id");
    state.status = "ERROR";
    state.errorCode = "MISSING_FIELDS";

    apply("{op:'selectVendor',id:'V2'}");

    assertEquals("PENDING_REVIEW", state.status);
    assertNull(state.errorCode);
    assertTrue(state.adjusted);
    verify(row).setBusinessPartner(null);
  }

  // ------------------------------------------------------------------ duplicates

  @Test
  void aChangedNumberAsksTheDuplicatesOfTheLinkedVendor() throws Exception {
    when(lookups.duplicates("V1", "FC-2")).thenReturn(new Duplicates(true,
        List.of(new Duplicate("INV9", "FC-2"))));

    apply("{op:'setHeader',documentNo:'FC-2'}");

    JSONObject duplicate = header().getJSONObject("duplicate");
    assertEquals("found", duplicate.getString("state"));
    assertEquals("INV9", duplicate.getJSONArray("invoices").getJSONObject(0).getString("id"));
    assertEquals("FC-2", duplicate.getJSONArray("invoices").getJSONObject(0)
        .getString("documentNo"));
  }

  @Test
  void anUnknownDuplicateCheckRaisesItsNotice() throws Exception {
    when(lookups.duplicates(anyString(), anyString())).thenReturn(new Duplicates(false,
        List.of()));

    apply("{op:'setHeader',documentNo:'FC-2'}");

    assertEquals("unknown", header().getJSONObject("duplicate").getString("state"));
    assertTrue(saved().getJSONArray("notices").toString().contains("duplicate_check_unknown"));
  }

  @Test
  void aClearedNumberAsksNothingAndAnswersNone() throws Exception {
    apply("{op:'setHeader',documentNo:''}");

    verify(lookups, never()).duplicates(anyString(), anyString());
    assertEquals("none", header().getJSONObject("duplicate").getString("state"));
  }

  @Test
  void aVendorThatIsNotLinkedIsNotAskedForDuplicates() throws Exception {
    JSONObject vendor = use(doc()).getJSONObject("header").getJSONObject("vendor");
    vendor.put("state", "none");

    apply("{op:'setHeader',documentNo:'FC-2'}");

    verify(lookups, never()).duplicates(anyString(), anyString());
  }

  @Test
  void theInboxDuplicatesAreRecheckedWithTheirNotice() throws Exception {
    inboxFound = new JSONArray().put(op("{id:'R7',fileName:'x.pdf'}"));

    apply("{op:'setHeader',documentNo:'FC-2'}");

    assertEquals("R7", header().getJSONObject("duplicate").getJSONArray("inbox")
        .getJSONObject(0).getString("id"));
    assertTrue(saved().getJSONArray("notices").toString().contains("duplicate_in_inbox"));

    inboxFound = new JSONArray();
    apply("{op:'setHeader',documentNo:'FC-3'}");

    assertFalse(saved().getJSONArray("notices").toString().contains("duplicate_in_inbox"));
  }

  @Test
  void theInboxDuplicateCheckIsLimitedToTheOrganizationsTheRoleCanRead() throws Exception {
    apply("{op:'setHeader',documentNo:'FC-9'}");

    duplicates.verify(() -> InvoiceInboxDuplicates.find(any(), any(),
        eq(java.util.List.of(READABLE_ORG))));
  }

  // ------------------------------------------------------------------ tax by id

  private static final String CHOSEN_T10 = "{state:'resolved',matchedBy:'manual',id:'T10',rate:10}";

  private void chooseT10OnLine0() throws Exception {
    JSONObject line = use(doc()).getJSONArray("lines").getJSONObject(0);
    line.put("tax", op(CHOSEN_T10)).put("taxRate", 10);
  }

  @Test
  void aTaxChosenByIdWinsOverThePrintedRateAndMarks() throws Exception {
    assertSaved(apply("{op:'setLine',index:0,taxId:'T10'}"));

    JSONObject tax = lineAt(0).getJSONObject("tax");
    assertEquals("T10", tax.getString("id"));
    assertEquals("resolved", tax.getString("state"));
    assertEquals("manual", tax.getString("matchedBy"));
    assertEquals(10, tax.getDouble("rate"), 0);
    assertFalse(tax.has("name"), "the name is never stored");
    assertEquals(10, lineAt(0).getDouble("taxRate"), 0, "the line follows the chosen tax");
    assertTrue(state.adjusted);
    // 100 at 10 % and 40 at 21 %
    assertEquals(158.4, header().getDouble("totalAmount"), 0.0001);
  }

  @Test
  void anUnknownTaxIsRefusedWithTheLineIndex() throws Exception {
    String before = commit();

    NeoResponse response = apply("{op:'setLine',index:1,taxId:'NOPE'}");

    assertRefusal(response, 422, "unknown_tax");
    assertEquals(1, response.getBody().getJSONObject("error").getInt("index"));
    assertEquals(before, state.currentData);
  }

  @Test
  void choosingTheTaxAlreadyChosenWritesNothing() throws Exception {
    chooseT10OnLine0();

    assertSaved(apply("{op:'setLine',index:0,taxId:'T10'}"));

    verify(dal, never()).save(any());
    assertFalse(state.adjusted);
  }

  @Test
  void choosingTheTaxTheServerResolvedOnlyPinsItWithoutMarking() throws Exception {
    apply("{op:'setLine',index:0,taxId:'T21'}");

    assertEquals("manual", lineAt(0).getJSONObject("tax").getString("matchedBy"));
    assertFalse(state.adjusted);
  }

  @Test
  void nullDropsTheChosenTaxAndTheServerResolvesItAgain() throws Exception {
    chooseT10OnLine0();

    apply("{op:'setLine',index:0,taxId:null}");

    JSONObject tax = lineAt(0).getJSONObject("tax");
    assertEquals("T10", tax.getString("id"), "resolved again from the rate that was left");
    assertFalse(tax.has("matchedBy"));
    assertTrue(state.adjusted);
  }

  @Test
  void aChosenTaxSurvivesAVendorChangeWhileItIsStillValid() throws Exception {
    chooseT10OnLine0();

    apply("{op:'selectVendor',id:'V2'}");

    assertEquals("manual", lineAt(0).getJSONObject("tax").getString("matchedBy"));
    assertEquals("T10", lineAt(0).getJSONObject("tax").getString("id"));
  }

  @Test
  void aChosenTaxThatNoLongerPassesTheRuleIsResolvedAgainWhenTheDateChanges() throws Exception {
    chooseT10OnLine0();
    when(lookups.taxById(eq("T10"), eq("2020-01-01"))).thenReturn(Optional.empty());

    apply("{op:'setHeader',invoiceDate:'2020-01-01'}");

    assertFalse(lineAt(0).getJSONObject("tax").has("matchedBy"));
  }

  @Test
  void aTaxIdOfTheWrongTypeIsAnInvalidRequest() throws Exception {
    assertRefusal(apply("{op:'setLine',index:0,taxId:5}"), 400, "invalid_request");
  }

  @Test
  void aTypedRateThatChangesDropsAChosenTaxAndTheTaxIsResolvedByRateAgain() throws Exception {
    chooseT10OnLine0();

    apply("{op:'setLine',index:0,taxRate:5}");

    JSONObject tax = lineAt(0).getJSONObject("tax");
    assertFalse(tax.has("matchedBy"), "no longer chosen");
    assertEquals("T5", tax.getString("id"), "resolved from the typed rate");
    assertEquals(5, lineAt(0).getDouble("taxRate"), 0);
    assertTrue(state.adjusted);
  }

  @Test
  void aTypedRateEqualToTheStoredOneKeepsTheChosenTaxAndWritesNothing() throws Exception {
    chooseT10OnLine0();

    assertSaved(apply("{op:'setLine',index:0,taxRate:10}"));

    assertEquals("manual", lineAt(0).getJSONObject("tax").getString("matchedBy"));
    verify(dal, never()).save(any());
    assertFalse(state.adjusted);
  }

  @Test
  void aTaxIdAndAChangedRateInOneOperationLetTheTaxIdWin() throws Exception {
    apply("{op:'setLine',index:0,taxRate:5,taxId:'T10'}");

    JSONObject tax = lineAt(0).getJSONObject("tax");
    assertEquals("T10", tax.getString("id"));
    assertEquals("manual", tax.getString("matchedBy"));
    assertEquals(10, lineAt(0).getDouble("taxRate"), 0, "the line follows the chosen tax");
    assertEquals(10, tax.getDouble("rate"), 0);
    // 100 at 10 % and 40 at 21 %
    assertEquals(158.4, header().getDouble("totalAmount"), 0.0001);
  }

  @Test
  void aTaxIdAlreadyChosenAndAChangedRateInOneOperationStillLetTheTaxIdWin() throws Exception {
    chooseT10OnLine0();

    // The description change makes the request effective: a request that changes nothing writes
    // nothing, and the arranged document carries a stale seeded total (169.4) that only a recompute
    // corrects. The typed rate must not reach the stored line nor the totals.
    apply("{op:'setLine',index:0,description:'x',taxRate:5,taxId:'T10'}");

    JSONObject tax = lineAt(0).getJSONObject("tax");
    assertEquals("T10", tax.getString("id"));
    assertEquals("manual", tax.getString("matchedBy"));
    assertEquals(10, lineAt(0).getDouble("taxRate"), 0,
        "the line's rate must be the chosen tax's, as the totals read it first");
    // 100 at 10 % and 40 at 21 %
    assertEquals(158.4, header().getDouble("totalAmount"), 0.0001);
  }

  @Test
  void aTypedRateAndTheSameChosenTaxIdWriteNothingAndDoNotMark() throws Exception {
    chooseT10OnLine0();

    assertSaved(apply("{op:'setLine',index:0,taxRate:5,taxId:'T10'}"));

    verify(dal, never()).save(any());
    assertFalse(state.adjusted);
    assertEquals(10, lineAt(0).getDouble("taxRate"), 0);
  }

  @Test
  void pinningTheServerResolvedTaxMakesTheLineRateFollowIt() throws Exception {
    JSONObject line = use(doc()).getJSONArray("lines").getJSONObject(0);
    line.put("taxRate", 21.5);

    apply("{op:'setLine',index:0,taxId:'T21'}");

    assertEquals(21, lineAt(0).getDouble("taxRate"), 0);
    assertEquals("manual", lineAt(0).getJSONObject("tax").getString("matchedBy"));
    assertTrue(state.adjusted, "the stored rate changed");
  }

  @Test
  void aNullTaxIdOnATaxTheUserNeverChoseIsANoOp() throws Exception {
    String before = commit();

    assertSaved(apply("{op:'setLine',index:0,taxId:null}"));

    assertEquals("T21", lineAt(0).getJSONObject("tax").getString("id"));
    assertFalse(state.adjusted);
    verify(dal, never()).save(any());
    assertEquals(before, state.currentData);
  }

  @Test
  void theTaxRuleIsAskedWithTheDocumentDateOrNullWhenThereIsNone() throws Exception {
    apply("{op:'setLine',index:0,taxId:'T10'}");
    verify(lookups).taxById("T10", "2026-11-02");

    use(doc()).getJSONObject("header").put("invoiceDate", JSONObject.NULL);
    apply("{op:'setLine',index:1,taxId:'T10'}");
    verify(lookups).taxById("T10", null);
  }

  @Test
  void anUnknownTaxInALaterOperationDiscardsTheEarlierOnesToo() throws Exception {
    String before = commit();

    NeoResponse response = apply("{op:'setLine',index:0,quantity:3}",
        "{op:'setLine',index:1,taxId:'NOPE'}");

    assertRefusal(response, 422, "unknown_tax");
    assertEquals(1, response.getBody().getJSONObject("error").getInt("index"));
    assertNothingWritten(before);
  }

  @Test
  void aTaxIdWithoutLookupsIsUnknownTax() throws Exception {
    lookupsAvailable = false;

    assertRefusal(apply("{op:'setLine',index:0,taxId:'T10'}"), 422, "unknown_tax");
  }

  @Test
  void aBlankOrTooLongTaxIdIsAnInvalidRequest() throws Exception {
    assertRefusal(apply("{op:'setLine',index:0,taxId:''}"), 400, "invalid_request");
    assertRefusal(apply("{op:'setLine',index:0,taxId:'   '}"), 400, "invalid_request");
    assertRefusal(apply("{op:'setLine',index:0,taxId:'" + "T".repeat(65) + "'}"), 400,
        "invalid_request");
    assertRefusal(apply("{op:'setLine',index:0,taxId:true}"), 400, "invalid_request");
    assertRefusal(apply("{op:'setLine',index:0,taxId:{}}"), 400, "invalid_request");
  }

  @Test
  void aTaxWithoutARateIsStoredWithoutOneAndTheLineAddsToTheBaseOnly() throws Exception {
    when(lookups.taxById(eq("TEX"), any())).thenReturn(
        Optional.of(new Tax("TEX", "Exempt", null)));

    apply("{op:'setLine',index:0,taxId:'TEX'}");

    assertEquals("TEX", lineAt(0).getJSONObject("tax").getString("id"));
    assertTrue(lineAt(0).getJSONObject("tax").isNull("rate"));
    assertTrue(lineAt(0).isNull("taxRate"));
    // 100 untaxed and 40 at 21 %
    assertEquals(148.4, header().getDouble("totalAmount"), 0.0001);
  }

  @Test
  void aChosenTaxGetsTheRoundingModeOfItsTaxStampedAndTheTotalFollowsIt() throws Exception {
    when(lookups.taxDocAmountModes(any())).thenReturn(java.util.Map.of("T10", "L"));
    use(doc()).getJSONArray("lines").getJSONObject(0).put("quantity", 1).put("unitPrice", 0.25);
    use(pending).getJSONArray("lines").getJSONObject(1).put("quantity", 1).put("unitPrice", 0.25);
    pending.getJSONArray("lines").getJSONObject(1).put("tax", op(CHOSEN_T10)).put("taxRate", 10);

    apply("{op:'setLine',index:0,taxId:'T10'}");

    assertEquals("L", lineAt(0).getJSONObject("tax").getString("docTaxAmount"));
    assertEquals("L", lineAt(1).getJSONObject("tax").getString("docTaxAmount"));
    // two lines of 0.25 at 10 %, per line round2(0.025) = 0.03 twice: 0.50 + 0.06
    assertEquals(0.56, header().getDouble("totalAmount"), 0.0001);
  }

  @Test
  void aChosenTaxThatNoLongerPassesTheRuleAfterAVendorChangeIsResolvedAgain() throws Exception {
    chooseT10OnLine0();
    when(lookups.taxById(eq("T10"), any())).thenReturn(Optional.empty());

    apply("{op:'selectVendor',id:'V2'}");

    JSONObject tax = lineAt(0).getJSONObject("tax");
    assertFalse(tax.has("matchedBy"));
    assertEquals("T10", tax.getString("id"), "resolved again from the line's rate (10)");
  }

  @Test
  void aChosenTaxSurvivesAChangeOfTheDateWhileItStillPassesTheRule() throws Exception {
    chooseT10OnLine0();

    apply("{op:'setHeader',invoiceDate:'2026-12-01'}");

    assertEquals("manual", lineAt(0).getJSONObject("tax").getString("matchedBy"));
    verify(lookups).taxById("T10", "2026-12-01");
  }

  @Test
  void aChangeOfAnythingElseDoesNotRecheckDuplicates() throws Exception {
    apply("{op:'setLine',index:0,quantity:3}");

    verify(lookups, never()).duplicates(anyString(), anyString());
    duplicates.verify(() -> InvoiceInboxDuplicates.find(any(), any(), any()), never());
  }

  // ------------------------------------------------------------------ newVendor

  private void useVendor(String vendorJson) throws Exception {
    use(doc()).getJSONObject("header").put("vendor", op(vendorJson));
  }

  @Test
  void anUnmodifiedAltaFromAnAmbiguousVendorDoesNotMark() throws Exception {
    useVendor("{state:'ambiguous',candidates:[{id:'V9',name:'Other'}],"
        + "extracted:{name:'ACME SL',taxId:'B1'}}");

    assertSaved(apply("{op:'newVendor'}"));

    JSONObject vendor = header().getJSONObject("vendor");
    assertEquals("new", vendor.getString("state"));
    assertEquals("UNKNOWN", vendor.getString("addressState"));
    assertTrue(vendor.isNull("locationId"));
    assertFalse(vendor.has("id"));
    assertFalse(vendor.has("matchedBy"));
    assertEquals("V9", vendor.getJSONArray("candidates").getJSONObject(0).getString("id"));
    assertEquals("ACME SL", vendor.getJSONObject("extracted").getString("name"));
    assertEquals("pending_vendor", lineAt(0).getJSONObject("tax").getString("state"));
    assertWritten();
    assertFalse(state.adjusted);
  }

  @Test
  void anAltaWithFieldsEqualToTheExtractedOnesFromNoneDoesNotMark() throws Exception {
    useVendor("{state:'none',candidates:[],extracted:{name:'ACME SL',taxId:'B1',city:'Madrid'}}");

    apply("{op:'newVendor',fields:{name:'ACME SL',city:' Madrid '}}");

    assertEquals("new", header().getJSONObject("vendor").getString("state"));
    assertFalse(state.adjusted);
  }

  @Test
  void anAltaWithAChangedFieldMarksAndMergesTheRest() throws Exception {
    useVendor("{state:'none',candidates:[],extracted:{name:'ACME SL',taxId:'B1'}}");

    apply("{op:'newVendor',fields:{name:'ACME SA',email:'a@acme.es'}}");

    JSONObject extracted = header().getJSONObject("vendor").getJSONObject("extracted");
    assertEquals("ACME SA", extracted.getString("name"));
    assertEquals("B1", extracted.getString("taxId"));
    assertEquals("a@acme.es", extracted.getString("email"));
    assertTrue(state.adjusted);
    verify(row).setExtractedName("ACME SA");
  }

  @Test
  void anAltaOverAnAutoLinkedVendorMarksEvenWithoutFields() throws Exception {
    assertSaved(apply("{op:'newVendor'}"));

    assertEquals("new", header().getJSONObject("vendor").getString("state"));
    assertTrue(state.adjusted);
    verify(row).setBusinessPartner(null);
  }

  @Test
  void theAltaOnANewVendorUnmodifiedIsANoOp() throws Exception {
    useVendor("{state:'new',candidates:[],extracted:{name:'ACME SL',taxId:'B1'},"
        + "addressState:'UNKNOWN',locationId:null}");
    String before = commit();

    apply("{op:'newVendor'}");
    apply("{op:'newVendor',fields:{name:'ACME SL'}}");

    assertNothingWritten(before);
  }

  @Test
  void editingTheDataOfANewVendorWritesAndMarks() throws Exception {
    useVendor("{state:'new',candidates:[],extracted:{name:'ACME SL',taxId:'B1'},"
        + "addressState:'UNKNOWN',locationId:null}");

    apply("{op:'newVendor',fields:{postalCode:'28001'}}");

    assertEquals("28001", header().getJSONObject("vendor").getJSONObject("extracted")
        .getString("postalCode"));
    assertTrue(state.adjusted);
  }

  @Test
  void aVendorFieldIsCleanedAndMustBeAString() throws Exception {
    useVendor("{state:'none',candidates:[],extracted:{}}");

    apply("{op:'newVendor',fields:{name:'  ACME\\n  SL\\u200b '}}");
    assertEquals("ACME SL", header().getJSONObject("vendor").getJSONObject("extracted")
        .getString("name"));

    String before = commit();
    assertRefusal(apply("{op:'newVendor',fields:{taxId:123}}"), 422, "invalid_value");
    assertEquals(before, state.currentData);
  }

  @Test
  void theNewVendorHasTheCandidatesKeyEvenWhenItHadNone() throws Exception {
    useVendor("{state:'none',extracted:{name:'ACME SL',taxId:'B1'}}");

    apply("{op:'newVendor'}");

    assertEquals(0, header().getJSONObject("vendor").getJSONArray("candidates").length());
  }

  // ------------------------------------------------------------------ selectProduct

  private void useProduct(int position, String productJson) throws Exception {
    use(doc()).getJSONArray("lines").getJSONObject(position).put("product", op(productJson));
  }

  @Test
  void selectProductLinksItKeepsTheCandidatesAndDropsTheNewBlock() throws Exception {
    useProduct(0, "{state:'new',id:null,candidates:[{id:'P3',name:'Prod3'}],new:{name:'x'}}");

    assertSaved(apply("{op:'selectProduct',index:0,id:'P2'}"));

    JSONObject product = lineAt(0).getJSONObject("product");
    assertEquals("linked", product.getString("state"));
    assertEquals("manual", product.getString("matchedBy"));
    assertEquals("P2", product.getString("id"));
    assertEquals("Prod2", product.getString("name"));
    assertFalse(product.has("new"));
    assertEquals("P3", product.getJSONArray("candidates").getJSONObject(0).getString("id"));
    assertTrue(state.adjusted);
  }

  @Test
  void aChoiceAmongCandidatesOfAnAmbiguousProductMarks() throws Exception {
    useProduct(1, "{state:'ambiguous',id:null,candidates:[{id:'P2',name:'Prod2'}]}");

    apply("{op:'selectProduct',index:1,id:'P2'}");

    assertEquals("P2", lineAt(1).getJSONObject("product").getString("id"));
    assertTrue(state.adjusted);
  }

  @Test
  void reselectingTheLinkedProductIsANoOp() throws Exception {
    String before = commit();

    assertSaved(apply("{op:'selectProduct',index:0,id:'P1'}"));

    assertNothingWritten(before);
    verify(lookups, never()).productById(anyString());
  }

  @Test
  void anUnknownProductIsRefusedWithTheLineIndex() throws Exception {
    String before = commit();

    NeoResponse response = apply("{op:'selectProduct',index:1,id:'NOPE'}");

    assertRefusal(response, 422, "unknown_product");
    assertEquals(1, response.getBody().getJSONObject("error").getInt("index"));
    assertNothingWritten(before);
  }

  @Test
  void selectProductOnAMissingLineIsLineNotFound() throws Exception {
    assertRefusal(apply("{op:'selectProduct',index:9,id:'P2'}"), 422, "line_not_found");
    assertRefusal(apply("{op:'newProduct',index:9}"), 422, "line_not_found");
  }

  // ------------------------------------------------------------------ newProduct

  @Test
  void newProductDefaultsTheNameToTheDescriptionAndAlwaysMarks() throws Exception {
    useProduct(0, "{state:'none',candidates:[]}");

    assertSaved(apply("{op:'newProduct',index:0}"));

    JSONObject product = lineAt(0).getJSONObject("product");
    assertEquals("new", product.getString("state"));
    assertTrue(product.isNull("id"));
    assertEquals("Tornillo", product.getJSONObject("new").getString("name"));
    // N1: the alta of a product always marks, even without any change of the extracted data
    assertTrue(state.adjusted);
  }

  @Test
  void newProductResolvesTheCategoryAndTheUnitServerSide() throws Exception {
    useProduct(0, "{state:'none',candidates:[]}");

    apply("{op:'newProduct',index:0,name:' Tornillo M8 ',categoryId:'C1',uomId:'U1'}");

    JSONObject draft = lineAt(0).getJSONObject("product").getJSONObject("new");
    assertEquals("Tornillo M8", draft.getString("name"));
    assertEquals("C1", draft.getString("categoryId"));
    assertFalse(draft.has("category"), "only the id is stored: the name is added at read time");
    assertEquals("U1", draft.getString("uomId"));
    assertFalse(draft.has("uom"));
    assertTrue(saved().getJSONObject("validation").getJSONArray("missing").toString()
        .indexOf("line[0].product") < 0);
  }

  @Test
  void anUnknownCategoryOrUnitIsRefusedWithTheLineIndex() throws Exception {
    useProduct(0, "{state:'none',candidates:[]}");
    String before = commit();

    NeoResponse category = apply("{op:'newProduct',index:0,categoryId:'NOPE'}");
    NeoResponse unit = apply("{op:'newProduct',index:0,uomId:'NOPE'}");

    assertRefusal(category, 422, "unknown_category");
    assertRefusal(unit, 422, "unknown_uom");
    assertEquals(0, unit.getBody().getJSONObject("error").getInt("index"));
    assertNothingWritten(before);
  }

  @Test
  void aNullCategoryClearsItAndAnAbsentOneKeepsTheStoredOne() throws Exception {
    useProduct(0, "{state:'new',id:null,candidates:[],new:{name:'Tornillo',categoryId:'C1',"
        + "category:'Cat',uomId:'U1',uom:'Unit'}}");

    apply("{op:'newProduct',index:0,name:'Tornillo B'}");
    JSONObject kept = lineAt(0).getJSONObject("product").getJSONObject("new");
    assertEquals("C1", kept.getString("categoryId"));
    assertEquals("U1", kept.getString("uomId"));

    apply("{op:'newProduct',index:0,categoryId:null}");
    JSONObject cleared = lineAt(0).getJSONObject("product").getJSONObject("new");
    assertFalse(cleared.has("categoryId"));
    assertFalse(cleared.has("category"));
    assertEquals("U1", cleared.getString("uomId"));
    assertEquals("Tornillo B", cleared.getString("name"));
  }

  @Test
  void repeatingTheSameAltaIsANoOp() throws Exception {
    useProduct(0, "{state:'new',id:null,candidates:[],new:{name:'Tornillo',categoryId:'C1',"
        + "category:'Cat',uomId:'U1',uom:'Unit'}}");
    String before = commit();

    apply("{op:'newProduct',index:0}");
    apply("{op:'newProduct',index:0,name:'Tornillo',categoryId:'C1',uomId:'U1'}");

    assertNothingWritten(before);
  }

  @Test
  void anAltaOverALinkedProductBecomesNewAndKeepsItsCandidates() throws Exception {
    assertSaved(apply("{op:'newProduct',index:0}"));

    JSONObject product = lineAt(0).getJSONObject("product");
    assertEquals("new", product.getString("state"));
    assertTrue(product.isNull("id"));
    assertEquals("P2", product.getJSONArray("candidates").getJSONObject(0).getString("id"));
    assertTrue(state.adjusted);
  }

  @Test
  void aProductNameThatIsNotAStringIsInvalidValue() throws Exception {
    assertRefusal(apply("{op:'newProduct',index:0,name:7}"), 422, "invalid_value");
  }

  @Test
  void aBlankNameFallsBackToTheDescriptionForValidation() throws Exception {
    useProduct(0, "{state:'none',candidates:[]}");

    apply("{op:'newProduct',index:0,name:'  ',categoryId:'C1',uomId:'U1'}");

    assertEquals("", lineAt(0).getJSONObject("product").getJSONObject("new").getString("name"));
    // an empty name falls back to the line description: the line is complete
    assertEquals(-1, saved().getJSONObject("validation").getJSONArray("missing").toString()
        .indexOf("line[0].product"));
  }

  // ------------------------------------------------------------------ ADJUSTED

  @Test
  void aRevertedEditStillMarksBecauseEachOperationComparesWithThePreviousOne() throws Exception {
    assertSaved(apply("{op:'setLine',index:0,quantity:12}", "{op:'setLine',index:0,quantity:10}"));

    assertEquals(10, lineAt(0).getDouble("quantity"), 0);
    assertTrue(state.adjusted);
  }

  @Test
  void theFlagIsStickyAcrossRequests() throws Exception {
    apply("{op:'setLine',index:0,quantity:12}");
    apply("{op:'setLine',index:0,quantity:10}");

    assertTrue(state.adjusted);
    verify(row, never()).setAdjusted(false);
  }

  @Test
  void aServerDerivedChangeAloneNeverMarks() throws Exception {
    useVendor("{state:'ambiguous',candidates:[],extracted:{name:'ACME SL',taxId:'B1'}}");

    apply("{op:'newVendor'}");

    // the taxes became pending_vendor and the total/validation were recomputed: no mark
    assertFalse(state.adjusted);
  }

  @Test
  void theEditsOfTheMatrixMarkEachOnItsOwn() throws Exception {
    String[] marking = {
        "{op:'setHeader',documentNo:'X'}",
        "{op:'setHeader',invoiceDate:'2026-01-01'}",
        "{op:'setLine',index:0,description:'Otra'}",
        "{op:'setLine',index:0,quantity:11}",
        "{op:'setLine',index:0,unitPrice:11}",
        "{op:'setLine',index:0,taxRate:10}",
        "{op:'setLine',index:0,discount:5}",
        "{op:'addLine'}",
        "{op:'removeLine',index:0}",
        "{op:'selectVendor',id:'V2'}",
        "{op:'newVendor'}" };
    for (String operation : marking) {
      state = new RowState();
      state.currentData = DOC;
      apply(operation);
      assertTrue(state.adjusted, operation);
    }
  }

  // ------------------------------------------------------------------ status and skeleton

  @Test
  void anEditedReadyDocumentGoesBackToPendingReviewUnreviewed() throws Exception {
    state.status = "READY";
    state.reviewed = true;

    assertSaved(apply("{op:'setLine',index:0,quantity:11}"));

    assertEquals("PENDING_REVIEW", state.status);
    assertFalse(state.reviewed);
    assertNull(state.errorCode);
  }

  @Test
  void aReadyDocumentWhoseEditMakesItInvalidBecomesAnError() throws Exception {
    state.status = "READY";
    state.reviewed = true;

    apply("{op:'setHeader',invoiceDate:null}");

    assertEquals("ERROR", state.status);
    assertEquals("MISSING_FIELDS", state.errorCode);
    assertFalse(state.reviewed);
  }

  @Test
  void aNoOpOnAReadyDocumentLeavesItReadyAndReviewed() throws Exception {
    state.status = "READY";
    state.reviewed = true;

    apply("{op:'setHeader',documentNo:'FC-1'}");

    assertEquals("READY", state.status);
    assertTrue(state.reviewed);
    verify(dal, never()).save(any());
  }

  @Test
  void anErrorDocumentThatTheEditCompletesMovesToPendingReview() throws Exception {
    use(doc()).getJSONObject("header").put("invoiceDate", JSONObject.NULL);
    state.status = "ERROR";
    state.errorCode = "MISSING_FIELDS";

    apply("{op:'setHeader',invoiceDate:'2026-11-02'}");

    assertEquals("PENDING_REVIEW", state.status);
    assertNull(state.errorCode);
  }

  @Test
  void anUnreadableRowIsCompletedByHandFromTheSkeleton() throws Exception {
    state.currentData = null;
    state.status = "ERROR";
    state.errorCode = "UNREADABLE";

    assertSaved(apply("{op:'selectVendor',id:'V2'}", "{op:'setHeader',invoiceDate:'2026-11-02',"
        + "documentNo:'A-1'}", "{op:'addLine'}", "{op:'setLine',index:0,description:'Tornillo',"
        + "quantity:2,unitPrice:5,taxRate:21}", "{op:'selectProduct',index:0,id:'P2'}"));

    assertEquals(1, saved().getInt("version"));
    assertEquals("PENDING_REVIEW", state.status);
    assertNull(state.errorCode);
    // 2 x 5 + 21 % = 12.10
    assertEquals(12.1, header().getDouble("totalAmount"), 0.0001);
    assertEquals("T21", lineAt(0).getJSONObject("tax").getString("id"));
    verify(row).setBusinessPartner(null);
  }

  @Test
  void theFirstEffectiveEditOfAnUnreadableRowPersistsTheSkeletonAsAnError() throws Exception {
    state.currentData = null;
    state.status = "ERROR";
    state.errorCode = "SERVICE_FAILURE";

    assertSaved(apply("{op:'setHeader',documentNo:'A-1'}"));

    assertNotNull(state.currentData);
    assertEquals("A-1", header().getString("documentNo"));
    assertEquals("ERROR", state.status);
    assertEquals("MISSING_FIELDS", state.errorCode);
    assertTrue(state.adjusted);
  }

  @Test
  void aNoOpOnASkeletonRowPersistsNothing() throws Exception {
    state.currentData = null;
    state.status = "ERROR";
    state.errorCode = "UNREADABLE";

    assertSaved(apply("{op:'setHeader',documentNo:''}"));

    assertNull(state.currentData);
    verify(dal, never()).save(any());
    assertEquals("UNREADABLE", state.errorCode);
  }

  @Test
  void aStoredDocumentThatIsNotJsonIsReplacedByTheSkeleton() throws Exception {
    state.currentData = "{not json";

    assertSaved(apply("{op:'setHeader',documentNo:'A-1'}"));

    assertEquals("A-1", header().getString("documentNo"));
  }

  @Test
  void extractedDataIsNeverTouched() throws Exception {
    apply("{op:'setLine',index:0,quantity:11}", "{op:'newVendor'}");

    verify(row, never()).setExtractedData(any());
  }

  @Test
  void aDocumentOverOneMegabyteAfterTheEditIsRefused() throws Exception {
    use(doc()).put("junk", "x".repeat(1_100_000));
    String before = commit();

    assertRefusal(apply("{op:'setLine',index:0,quantity:11}"), 413, "document_too_large");

    assertEquals(before, state.currentData);
    verify(dal, never()).save(any());
    assertFalse(state.adjusted);
  }

  @Test
  void theSavedRowIsFlushedAndRefreshedBeforeAnswering() throws Exception {
    apply("{op:'setLine',index:0,quantity:11}");

    verify(dal).save(row);
    verify(dal).flush();
    verify(dal).refresh(row);
  }
}
