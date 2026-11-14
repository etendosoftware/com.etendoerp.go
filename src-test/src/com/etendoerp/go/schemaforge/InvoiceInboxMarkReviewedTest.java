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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;

import com.etendoerp.go.schemaforge.data.IncomingDocument;

/**
 * The {@code mark-reviewed} action of the invoice inbox (ETP-6050): {@code PENDING_REVIEW} becomes
 * {@code READY} with {@code REVIEWED = Y} only for a document that validates, a {@code READY} one
 * answers unchanged, an {@code ERROR} one is not reviewable, and nothing else is touched.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxMarkReviewed
 */
class InvoiceInboxMarkReviewedTest {

  private static final String VALID = "{version:1,header:{vendor:{state:'linked',id:'V1',"
      + "name:'ACME',candidates:[],extracted:{}},documentNo:'FC-1',invoiceDate:'2026-11-02',"
      + "totalAmount:121,totalSource:'lines',receiver:{check:'absent'}},lines:[{index:0,"
      + "description:'Tornillo',quantity:10,unitPrice:10,taxRate:21,product:{state:'linked',"
      + "id:'P1'},tax:{state:'resolved',id:'T21'}}],notices:[]}";

  private String status;
  private String errorCode;
  private boolean reviewed;
  private boolean adjusted;
  private String currentData;
  private NeoResponse gateRefusal;
  private String openedWith;
  private IncomingDocument row;
  private OBDal dal;
  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBMessageUtils> messages;
  private MockedStatic<InvoiceInboxEditGate> gate;
  private MockedStatic<InvoiceInboxReview> review;

  @BeforeEach
  void setUp() {
    status = "PENDING_REVIEW";
    errorCode = null;
    reviewed = false;
    adjusted = false;
    currentData = VALID;
    gateRefusal = null;
    openedWith = null;
    dal = mock(OBDal.class);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    messages = mockStatic(OBMessageUtils.class);
    row = mock(IncomingDocument.class);
    when(row.getStatus()).thenAnswer(i -> status);
    when(row.getCurrentData()).thenAnswer(i -> currentData);
    org.mockito.Mockito.doAnswer(i -> {
      status = i.getArgument(0);
      return null;
    }).when(row).setStatus(anyString());
    org.mockito.Mockito.doAnswer(i -> {
      reviewed = i.getArgument(0);
      return null;
    }).when(row).setReviewed(any());
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
      if ("respond".equals(inv.getMethod().getName())) {
        return NeoResponse.ok(new JSONObject().put("status", status).put("reviewed", reviewed));
      }
      return inv.callRealMethod();
    });
  }

  @AfterEach
  void tearDown() {
    review.close();
    gate.close();
    messages.close();
    dalStatic.close();
  }

  private NeoResponse mark() throws Exception {
    return InvoiceInboxMarkReviewed.markReviewed("R1", new JSONObject().put("updated", "T"));
  }

  private static void assertRefusal(NeoResponse response, int status, String code)
      throws Exception {
    assertEquals(status, response.getHttpStatus());
    assertEquals(code, response.getBody().getJSONObject("error").getString("code"));
  }

  @Test
  void aValidPendingReviewDocumentBecomesReadyAndReviewed() throws Exception {
    NeoResponse response = mark();

    assertEquals(200, response.getHttpStatus());
    assertEquals("READY", status);
    assertTrue(reviewed);
    assertEquals("READY", response.getBody().getString("status"));
    verify(dal).save(row);
    verify(dal).flush();
    verify(dal).refresh(row);
  }

  @Test
  void itTouchesNeitherAdjustedNorTheDocumentNorTheSummary() throws Exception {
    mark();

    verify(row, never()).setAdjusted(any());
    verify(row, never()).setCurrentData(any());
    verify(row, never()).setOrderReference(any());
    verify(row, never()).setGrandTotalAmount(any());
    verify(row, never()).setErrorCode(any());
    assertFalse(adjusted);
    assertEquals(VALID, currentData);
  }

  @Test
  void aReadyDocumentAnswersTheSameAndWritesNothing() throws Exception {
    status = "READY";
    reviewed = true;

    NeoResponse response = mark();

    assertEquals(200, response.getHttpStatus());
    assertEquals("READY", response.getBody().getString("status"));
    verify(dal, never()).save(any());
    verify(row, never()).setStatus(anyString());
  }

  @Test
  void anErrorDocumentIsNotReviewableAndCarriesItsStatus() throws Exception {
    status = "ERROR";
    errorCode = "MISSING_FIELDS";

    NeoResponse response = mark();

    assertRefusal(response, 409, "not_reviewable");
    assertEquals("ERROR", response.getBody().getJSONObject("error").getString("documentStatus"));
    verify(dal, never()).save(any());
    assertEquals("ERROR", status);
  }

  @Test
  void anInvalidDocumentIsNotValidAndListsWhatIsMissing() throws Exception {
    currentData = "{version:1,header:{vendor:{state:'none',candidates:[],extracted:{}},"
        + "documentNo:'',invoiceDate:null,totalAmount:0,totalSource:'lines'},lines:[],notices:[]}";

    NeoResponse response = mark();

    assertRefusal(response, 422, "not_valid");
    JSONArray missing = response.getBody().getJSONObject("error").getJSONArray("missing");
    assertTrue(missing.toString().contains("vendor"));
    assertTrue(missing.toString().contains("invoiceDate"));
    assertTrue(missing.toString().contains("amount"));
    assertTrue(missing.toString().contains("lines"));
    verify(dal, never()).save(any());
    assertEquals("PENDING_REVIEW", status);
    assertFalse(reviewed);
  }

  @Test
  void theStoredVerdictIsNotTrusted() throws Exception {
    // a stored "valid" block over a document that is not
    currentData = "{version:1,header:{vendor:{state:'none'},documentNo:'A',invoiceDate:null,"
        + "totalAmount:5},lines:[],validation:{valid:true,missing:[]}}";

    assertRefusal(mark(), 422, "not_valid");
  }

  @Test
  void aNewProductWithoutCategoryAndUnitIsNotValid() throws Exception {
    currentData = VALID.replace("product:{state:'linked',id:'P1'}",
        "product:{state:'new',id:null,new:{name:'Tornillo'}}");

    NeoResponse response = mark();

    assertRefusal(response, 422, "not_valid");
    assertTrue(response.getBody().getJSONObject("error").getJSONArray("missing").toString()
        .contains("line[0].product"));
  }

  @Test
  void aDocumentWithoutStoredDataIsJudgedAsAnEmptyOne() throws Exception {
    currentData = null;

    assertRefusal(mark(), 422, "not_valid");

    currentData = "{broken";
    assertRefusal(mark(), 422, "not_valid");
  }

  @Test
  void aStaleTokenRefusalOfTheGuardsIsAnsweredAndNothingIsWritten() throws Exception {
    gateRefusal = InvoiceInboxEditGate.refuse(409, "stale_document",
        InvoiceInboxMessages.STALE_DOCUMENT, null);

    assertRefusal(mark(), 409, "stale_document");
    verify(dal, never()).save(any());
    assertEquals("PENDING_REVIEW", status);
  }

  @Test
  void theTokenIsPassedTrimmedToTheGuards() throws Exception {
    InvoiceInboxMarkReviewed.markReviewed("R1", new JSONObject().put("updated", " TOKEN "));

    assertEquals("TOKEN", openedWith);
  }

  @Test
  void aMissingBlankOrNonStringTokenIsMissingUpdated() throws Exception {
    assertRefusal(InvoiceInboxMarkReviewed.markReviewed("R1", new JSONObject()), 400,
        "missing_updated");
    assertRefusal(InvoiceInboxMarkReviewed.markReviewed("R1",
        new JSONObject().put("updated", "  ")), 400, "missing_updated");
    assertRefusal(InvoiceInboxMarkReviewed.markReviewed("R1",
        new JSONObject().put("updated", 5)), 400, "missing_updated");
  }

  @Test
  void aNullBodyOrAnUnknownKeyIsInvalidRequest() throws Exception {
    assertRefusal(InvoiceInboxMarkReviewed.markReviewed("R1", null), 400, "invalid_request");
    assertRefusal(InvoiceInboxMarkReviewed.markReviewed("R1",
        new JSONObject().put("updated", "T").put("status", "READY")), 400, "invalid_request");
    verify(dal, never()).save(any());
  }
}
