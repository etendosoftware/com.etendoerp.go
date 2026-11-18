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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;

/**
 * The orchestration of the {@code confirm} action: the request shape, the answer of each outcome
 * (fresh, already confirmed, refused by the claim or the preflight, failed, failed after the
 * commit), what is recorded where, and the telemetry that goes out once and only on the first
 * transition. The collaborators (claim, preflight, unit of work) are tested on their own.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirm
 */
class InvoiceInboxConfirmTest {

  private static final String ROW_ID = "ROW-1";

  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<OBMessageUtils> messages;
  private MockedStatic<InvoiceInboxConfirmClaim> claim;
  private MockedStatic<InvoiceInboxReview> review;
  private MockedStatic<InvoiceInboxConfirmChecks> checks;
  private MockedStatic<InvoiceInboxConfirmWork> work;
  private MockedStatic<InvoiceInboxConfirmFailure> failure;
  private MockedStatic<InvoiceInboxTelemetry> telemetry;
  private MockedStatic<InvoiceInboxSummary> summary;
  private MockedStatic<InvoiceInboxLiveChecks> liveChecks;
  private OBDal dal;
  private IncomingDocument row;
  private Invoice invoice;
  private OcrLookups lookups;
  private boolean mismatch;

  @BeforeEach
  void setUp() throws Exception {
    mismatch = false;
    dal = mock(OBDal.class);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    messages = mockStatic(OBMessageUtils.class);
    claim = mockStatic(InvoiceInboxConfirmClaim.class);
    review = mockStatic(InvoiceInboxReview.class, CALLS_REAL_METHODS);
    checks = mockStatic(InvoiceInboxConfirmChecks.class);
    work = mockStatic(InvoiceInboxConfirmWork.class);
    failure = mockStatic(InvoiceInboxConfirmFailure.class);
    telemetry = mockStatic(InvoiceInboxTelemetry.class);
    summary = mockStatic(InvoiceInboxSummary.class);
    liveChecks = mockStatic(InvoiceInboxLiveChecks.class);
    summary.when(() -> InvoiceInboxSummary.totalMismatch(any(), any())).thenAnswer(i -> mismatch);

    lookups = mock(OcrLookups.class);
    review.when(() -> InvoiceInboxReview.lookupsFor(any())).thenReturn(Optional.of(lookups));
    invoice = mock(Invoice.class);
    when(invoice.getId()).thenReturn("INV-1");
    when(invoice.getDocumentNo()).thenReturn("1000012");
    when(invoice.getIdentifier()).thenReturn("1000012 - 2026-11-02 - 121.00");
    when(dal.get(Invoice.class, "INV-1")).thenReturn(invoice);
    row = mock(IncomingDocument.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("C1");
    Organization org = mock(Organization.class);
    when(row.getId()).thenReturn(ROW_ID);
    when(row.getClient()).thenReturn(client);
    when(row.getOrganization()).thenReturn(org);
    when(row.getTargetType()).thenReturn("PURCHASE_INVOICE");
    when(row.getCurrentData()).thenReturn("{\"version\":1,\"header\":{\"vendor\":{}},\"lines\":[]}");
  }

  @AfterEach
  void tearDown() {
    liveChecks.close();
    summary.close();
    telemetry.close();
    failure.close();
    work.close();
    checks.close();
    review.close();
    claim.close();
    messages.close();
    dalStatic.close();
  }

  private static InvoiceInboxConfirmClaim.Opened opened(NeoResponse response, IncomingDocument row,
      boolean already) {
    InvoiceInboxConfirmClaim.Opened opened = mock(InvoiceInboxConfirmClaim.Opened.class);
    when(opened.response()).thenReturn(response);
    when(opened.row()).thenReturn(row);
    when(opened.alreadyConfirmed()).thenReturn(already);
    return opened;
  }

  private void claimOpens(boolean already) {
    InvoiceInboxConfirmClaim.Opened opened = opened(null, row, already);
    claim.when(() -> InvoiceInboxConfirmClaim.open(anyString(), anyString())).thenReturn(opened);
  }

  private static void assertRefusal(NeoResponse response, int status, String code)
      throws Exception {
    assertEquals(status, response.getHttpStatus());
    assertEquals(code, response.getBody().getJSONObject("error").getString("code"));
  }

  private static InvoiceInboxConfirmWork.Done done(boolean adjusted, boolean vendor, int products,
      boolean mismatch) {
    return new InvoiceInboxConfirmWork.Done("INV-1", adjusted, vendor, products, mismatch);
  }

  // ------------------------------------------------------------------ the request

  @Test
  @DisplayName("a body with an unknown key, or an updated that is not a string, is 400 and touches nothing")
  void anInvalidBodyIsRefusedBeforeAnything() throws Exception {
    assertRefusal(InvoiceInboxConfirm.confirm(ROW_ID, new JSONObject().put("updated", "T")
        .put("extra", 1)), 400, "invalid_request");
    assertRefusal(InvoiceInboxConfirm.confirm(ROW_ID, new JSONObject().put("updated", 7)), 400,
        "invalid_request");
    assertRefusal(InvoiceInboxConfirm.confirm(ROW_ID, new JSONObject().put("other", "T")), 400,
        "invalid_request");

    claim.verifyNoInteractions();
    work.verifyNoInteractions();
  }

  @Test
  @DisplayName("the token is trimmed; no body, no token and a null token are the empty token")
  void theTokenIsPassedTrimmed() throws Exception {
    claimOpens(true);
    work.when(() -> InvoiceInboxConfirmWork.run(any(), any(), any())).thenReturn(null);

    InvoiceInboxConfirm.confirm(ROW_ID, new JSONObject().put("updated", "  T-1  "));
    InvoiceInboxConfirm.confirm(ROW_ID, null);
    InvoiceInboxConfirm.confirm(ROW_ID, new JSONObject());
    InvoiceInboxConfirm.confirm(ROW_ID, new JSONObject().put("updated", JSONObject.NULL));

    claim.verify(() -> InvoiceInboxConfirmClaim.open(ROW_ID, "T-1"));
    claim.verify(() -> InvoiceInboxConfirmClaim.open(ROW_ID, ""), times(3));
  }

  @Test
  @DisplayName("a refusal of the claim is answered as it is and nothing else runs")
  void aClaimRefusalIsAnsweredAsIs() throws Exception {
    NeoResponse refusal = NeoResponse.error(409, "not confirmable");
    InvoiceInboxConfirmClaim.Opened refused = opened(refusal, null, false);
    claim.when(() -> InvoiceInboxConfirmClaim.open(anyString(), anyString())).thenReturn(refused);

    assertSame(refusal, InvoiceInboxConfirm.confirm(ROW_ID, null));

    checks.verifyNoInteractions();
    work.verifyNoInteractions();
    telemetry.verifyNoInteractions();
  }

  // ------------------------------------------------------------------ the fresh confirmation

  @Test
  @DisplayName("a first confirmation answers the invoice, the flags and the counts, and emits the event once")
  void aFirstConfirmationAnswersAndEmitsOnce() throws Exception {
    claimOpens(false);
    checks.when(() -> InvoiceInboxConfirmChecks.preflight(any(), any(), any())).thenReturn(null);
    work.when(() -> InvoiceInboxConfirmWork.run(any(), any(), any()))
        .thenReturn(done(true, true, 2, true));

    NeoResponse response = InvoiceInboxConfirm.confirm(ROW_ID, new JSONObject().put("updated", "T"));

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals(ROW_ID, body.getString("id"));
    assertEquals("CONFIRMED", body.getString("status"));
    assertEquals("INV-1", body.getJSONObject("invoice").getString("id"));
    assertEquals("1000012", body.getJSONObject("invoice").getString("documentNo"));
    assertEquals("1000012 - 2026-11-02 - 121.00", body.getJSONObject("invoice").getString("identifier"));
    assertTrue(body.getBoolean("adjusted"));
    assertTrue(body.getBoolean("createdVendor"));
    assertEquals(2, body.getInt("createdProducts"));
    assertFalse(body.getBoolean("alreadyConfirmed"));
    assertTrue(body.getBoolean("totalMismatch"));
    telemetry.verify(() -> InvoiceInboxTelemetry.documentConfirmed(true, "C1"), times(1));
    failure.verifyNoInteractions();
  }

  @Test
  @DisplayName("the unit of work receives the stored document and the lookups of the document type")
  void theWorkGetsTheDocumentAndTheLookups() throws Exception {
    claimOpens(false);
    work.when(() -> InvoiceInboxConfirmWork.run(any(), any(), any()))
        .thenReturn(done(false, false, 0, false));

    InvoiceInboxConfirm.confirm(ROW_ID, null);

    ArgumentCaptor<JSONObject> document = ArgumentCaptor.forClass(JSONObject.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Optional<OcrLookups>> found = ArgumentCaptor.forClass(Optional.class);
    work.verify(() -> InvoiceInboxConfirmWork.run(eq(row), document.capture(), found.capture()));
    assertEquals(1, document.getValue().getInt("version"));
    assertSame(lookups, found.getValue().get());
    telemetry.verify(() -> InvoiceInboxTelemetry.documentConfirmed(false, "C1"));
  }

  @Test
  @DisplayName("an unreadable or blank stored document is judged as the empty skeleton, never an exception")
  void anUnreadableDocumentIsTheSkeleton() throws Exception {
    claimOpens(false);
    NeoResponse refusal = NeoResponse.error(422, "not valid");
    checks.when(() -> InvoiceInboxConfirmChecks.preflight(any(), any(), any())).thenReturn(refusal);
    for (String stored : new String[] { "not json {", "", "   ", null }) {
      when(row.getCurrentData()).thenReturn(stored);

      assertSame(refusal, InvoiceInboxConfirm.confirm(ROW_ID, null), String.valueOf(stored));
    }

    ArgumentCaptor<JSONObject> document = ArgumentCaptor.forClass(JSONObject.class);
    checks.verify(() -> InvoiceInboxConfirmChecks.preflight(eq(row), document.capture(), any()),
        times(4));
    for (JSONObject judged : document.getAllValues()) {
      assertEquals("none", judged.getJSONObject("header").getJSONObject("vendor").getString("state"));
      assertEquals(0, judged.getJSONArray("lines").length());
    }
    work.verifyNoInteractions();
  }

  @Test
  @DisplayName("a refusal of the preflight is answered as it is: nothing written, nothing emitted")
  void aPreflightRefusalStopsTheConfirmation() throws Exception {
    claimOpens(false);
    NeoResponse refusal = NeoResponse.error(422, "duplicate");
    checks.when(() -> InvoiceInboxConfirmChecks.preflight(any(), any(), any())).thenReturn(refusal);

    assertSame(refusal, InvoiceInboxConfirm.confirm(ROW_ID, null));

    work.verifyNoInteractions();
    failure.verifyNoInteractions();
    telemetry.verifyNoInteractions();
  }

  // ------------------------------------------------------------------ idempotency

  @Test
  @DisplayName("a second confirm answers the same invoice with alreadyConfirmed, creates nothing, emits nothing")
  void anAlreadyConfirmedDocumentIsAnsweredAgain() throws Exception {
    claimOpens(true);
    when(row.getInvoice()).thenReturn(invoice);
    when(row.isAdjusted()).thenReturn(true);
    mismatch = true;

    NeoResponse response = InvoiceInboxConfirm.confirm(ROW_ID, null);

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals("INV-1", body.getJSONObject("invoice").getString("id"));
    assertTrue(body.getBoolean("alreadyConfirmed"));
    assertTrue(body.getBoolean("adjusted"));
    assertFalse(body.getBoolean("createdVendor"));
    assertEquals(0, body.getInt("createdProducts"));
    assertTrue(body.getBoolean("totalMismatch"), "the warning is recomputed for every shape");
    work.verifyNoInteractions();
    checks.verifyNoInteractions();
    telemetry.verifyNoInteractions();
    failure.verifyNoInteractions();
  }

  @Test
  @DisplayName("an already confirmed row without a readable invoice answers invoice null and adjusted false")
  void anAlreadyConfirmedRowWithoutAnInvoice() throws Exception {
    claimOpens(true);
    when(row.getInvoice()).thenReturn(null);
    when(row.isAdjusted()).thenReturn(null);

    JSONObject body = InvoiceInboxConfirm.confirm(ROW_ID, null).getBody();

    assertTrue(body.isNull("invoice"));
    assertFalse(body.getBoolean("adjusted"));
    assertFalse(body.getBoolean("totalMismatch"));
  }

  // ------------------------------------------------------------------ failures of the unit

  @Test
  @DisplayName("a failure of the unit is 422 confirm_failed with the reason, recorded on the row, nothing emitted")
  void aFailedUnitIsRecordedAndAnswered() throws Exception {
    claimOpens(false);
    work.when(() -> InvoiceInboxConfirmWork.run(any(), any(), any()))
        .thenThrow(new InvoiceInboxConfirmWork.Stop("line 2: tax is required"));

    NeoResponse response = InvoiceInboxConfirm.confirm(ROW_ID, null);

    assertRefusal(response, 422, "confirm_failed");
    assertEquals("line 2: tax is required",
        response.getBody().getJSONObject("error").getString("detail"));
    verify(dal).rollbackAndClose();
    failure.verify(() -> InvoiceInboxConfirmFailure.record(ROW_ID, "line 2: tax is required"));
    telemetry.verifyNoInteractions();
  }

  @Test
  @DisplayName("a Stop that carries a refusal answers it as it is and records nothing")
  void aStopWithARefusalIsAnsweredAsIs() throws Exception {
    claimOpens(false);
    NeoResponse refusal = NeoResponse.error(422, "no address");
    work.when(() -> InvoiceInboxConfirmWork.run(any(), any(), any()))
        .thenThrow(new InvoiceInboxConfirmWork.Stop(refusal));

    assertSame(refusal, InvoiceInboxConfirm.confirm(ROW_ID, null));

    verify(dal).rollbackAndClose();
    failure.verifyNoInteractions();
    telemetry.verifyNoInteractions();
  }

  @Test
  @DisplayName("a quota refusal is the Refused response itself (403 QUOTA_EXCEEDED), rolled back once, nothing recorded")
  void aQuotaRefusalIsAnsweredAsIs() throws Exception {
    claimOpens(false);
    NeoResponse quota = NeoErrorResponses.toResponse(403, NeoQuotaWriteGate.CODE, "quota exceeded", null);
    work.when(() -> InvoiceInboxConfirmWork.run(any(), any(), any()))
        .thenThrow(new NeoQuotaCreateCheck.Refused(quota, null));

    NeoResponse response = InvoiceInboxConfirm.confirm(ROW_ID, null);

    assertSame(quota, response);
    assertEquals(403, response.getHttpStatus());
    assertEquals("QUOTA_EXCEEDED", response.getBody().getJSONObject("error").getString("code"));
    verify(dal, times(1)).rollbackAndClose();
    failure.verifyNoInteractions();
    telemetry.verifyNoInteractions();
  }

  @Test
  @DisplayName("inside a batch the quota refusal is the same answer and the batch, not confirm, owns the rollback")
  void aQuotaRefusalInsideABatchDoesNotRollBack() throws Exception {
    claimOpens(false);
    NeoResponse quota = NeoErrorResponses.toResponse(403, NeoQuotaWriteGate.CODE, "quota exceeded", null);
    work.when(() -> InvoiceInboxConfirmWork.run(any(), any(), any()))
        .thenThrow(new NeoQuotaCreateCheck.Refused(quota, null));
    BatchService.beginCallerOwnedTransaction();
    try {
      NeoResponse response = InvoiceInboxConfirm.confirm(ROW_ID, null);

      assertSame(quota, response);
    } finally {
      BatchService.endCallerOwnedTransaction();
    }
    verify(dal, never()).rollbackAndClose();
    failure.verifyNoInteractions();
    telemetry.verifyNoInteractions();
  }

  @Test
  @DisplayName("an SIF refusal becomes confirm_failed with its message")
  void aSifRefusalBecomesConfirmFailed() throws Exception {
    claimOpens(false);
    InvoiceSifPreset.Refused sif = new InvoiceSifPreset.Refused(
        NeoErrorResponses.toResponse(422, "sif", "SIF fields are missing", null));
    work.when(() -> InvoiceInboxConfirmWork.run(any(), any(), any())).thenThrow(sif);

    NeoResponse response = InvoiceInboxConfirm.confirm(ROW_ID, null);

    assertRefusal(response, 422, "confirm_failed");
    failure.verify(() -> InvoiceInboxConfirmFailure.record(ROW_ID, "SIF fields are missing"));
    telemetry.verifyNoInteractions();
  }

  @Test
  @DisplayName("any other exception is classified and sanitized: its message, never its stack, reaches the row")
  void anUnexpectedExceptionIsSanitized() throws Exception {
    claimOpens(false);
    work.when(() -> InvoiceInboxConfirmWork.run(any(), any(), any()))
        .thenThrow(new IllegalStateException("could not execute statement SELECT * FROM x"));
    try (MockedStatic<NeoErrorResponses> errors = mockStatic(NeoErrorResponses.class,
        CALLS_REAL_METHODS)) {
      errors.when(() -> NeoErrorResponses.fromException(any(), anyString(), anyString(), any()))
          .thenReturn(NeoResponse.error(500, "The operation could not be completed"));

      NeoResponse response = InvoiceInboxConfirm.confirm(ROW_ID, null);

      assertRefusal(response, 422, "confirm_failed");
      assertEquals("The operation could not be completed",
          response.getBody().getJSONObject("error").getString("detail"));
      failure.verify(() -> InvoiceInboxConfirmFailure.record(ROW_ID,
          "The operation could not be completed"));
      assertFalse(response.getBody().toString().contains("SELECT"));
    }
  }

  // ------------------------------------------------------------------ after the commit

  @Test
  @DisplayName("a failure building the answer after the commit never answers 422: the idempotent shape, event sent")
  void aFailureAfterTheCommitAnswersTheIdempotentShape() throws Exception {
    claimOpens(false);
    work.when(() -> InvoiceInboxConfirmWork.run(any(), any(), any()))
        .thenReturn(done(true, true, 1, true));
    when(dal.get(Invoice.class, "INV-1")).thenThrow(new IllegalStateException("session closed"));

    NeoResponse response = InvoiceInboxConfirm.confirm(ROW_ID, null);

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals(ROW_ID, body.getString("id"));
    assertEquals("CONFIRMED", body.getString("status"));
    assertEquals("INV-1", body.getJSONObject("invoice").getString("id"));
    assertTrue(body.getBoolean("alreadyConfirmed"));
    assertTrue(body.getBoolean("adjusted"));
    assertTrue(body.getBoolean("createdVendor"));
    assertEquals(1, body.getInt("createdProducts"));
    assertTrue(body.getBoolean("totalMismatch"), "the fallback shape carries the warning too");
    failure.verifyNoInteractions();
    verify(dal, never()).rollbackAndClose();
    telemetry.verify(() -> InvoiceInboxTelemetry.documentConfirmed(true, "C1"), times(1));
  }

  @Test
  @DisplayName("the event is sent before the answer is built, so a failing answer cannot lose it")
  void theEventDoesNotDependOnTheAnswer() throws Exception {
    claimOpens(false);
    work.when(() -> InvoiceInboxConfirmWork.run(any(), any(), any()))
        .thenReturn(done(false, false, 0, false));
    when(dal.get(Invoice.class, "INV-1")).thenThrow(new IllegalStateException("x"));

    assertNotNull(InvoiceInboxConfirm.confirm(ROW_ID, null));

    telemetry.verify(() -> InvoiceInboxTelemetry.documentConfirmed(eq(false), eq("C1")));
    work.verify(() -> InvoiceInboxConfirmWork.run(any(), any(), any()), times(1));
    verify(dal, never()).rollbackAndClose();
  }
}
