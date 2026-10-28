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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link InvoiceReactivationGuard} (ETP-5745): a Sales/Purchase Invoice that has
 * already been accepted by a fiscal system (SII, TicketBAI or Verifactu) AND is already rectified
 * by at least one COMPLETED rectificativa refuses the {@code documentAction=RE} reactivation with
 * a 422 naming the fiscal system and the rectificativa document number(s). Every other case keeps
 * reactivating exactly as before.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceReactivationGuard
 */
public class InvoiceReactivationGuardTest {

  private static final String INVOICE_ID = "INV-5745";
  private static final String FIELD_DOCUMENT_ACTION = "documentAction";

  // ---------------------------------------------------------------------------
  // fixtures
  // ---------------------------------------------------------------------------

  private static NeoContext reactivateAction(JSONObject body) {
    return NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("POST")
        .fieldName(FIELD_DOCUMENT_ACTION)
        .recordId(INVOICE_ID)
        .requestBody(body)
        .build();
  }

  private static JSONObject rootBody(String docAction) throws Exception {
    return new JSONObject().put(FIELD_DOCUMENT_ACTION, docAction);
  }

  private static JSONObject nestedBody(String docAction) throws Exception {
    JSONObject fieldValues = new JSONObject().put(FIELD_DOCUMENT_ACTION, docAction);
    return new JSONObject().put("fieldValues", fieldValues);
  }

  private static Invoice invoice(boolean processed) {
    Invoice invoice = mock(Invoice.class);
    when(invoice.isProcessed()).thenReturn(processed);
    return invoice;
  }

  /** A {@link ResultSet} mock that walks {@code docNos}, answering {@code getString(1)} positionally. */
  private static ResultSet rectificativaRows(String... docNos) throws Exception {
    List<String> rows = List.of(docNos);
    int[] cursor = { -1 };
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenAnswer(inv -> ++cursor[0] < rows.size());
    when(rs.getString(1)).thenAnswer(inv -> rows.get(cursor[0]));
    return rs;
  }

  /**
   * Runs {@code checkReactivation(context)} with {@link OBDal} answering {@code invoice} and the
   * raw JDBC rectificativa query answering {@code rectificativaRs}.
   */
  private static NeoResponse check(NeoContext context, Invoice invoice, ResultSet rectificativaRs)
      throws Exception {
    try (MockedStatic<OBContext> obc = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      Connection conn = mock(Connection.class);
      when(obDal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rectificativaRs);
      return InvoiceReactivationGuard.checkReactivation(context);
    }
  }

  private static NeoResponse checkWithNoRectificativaQuery(NeoContext context, Invoice invoice)
      throws Exception {
    return check(context, invoice, rectificativaRows());
  }

  private static JSONObject body(NeoResponse response) {
    assertNotNull("expected a refusal", response);
    assertEquals(422, response.getHttpStatus());
    return response.getBody();
  }

  /**
   * {@code messageParams.fiscalSystem} is a real {@code JSONArray} (ETP-5745), not a pre-joined
   * string — see {@code reject()}'s javadoc for why. Comma-joined here purely for assertion
   * convenience; the frontend's own join behavior (conjunction, not comma) is covered separately.
   */
  private static String fiscalSystemOf(JSONObject messageParams) throws Exception {
    JSONArray systems = messageParams.getJSONArray("fiscalSystem");
    List<String> values = new ArrayList<>();
    for (int i = 0; i < systems.length(); i++) {
      values.add(systems.getString(i));
    }
    return String.join(", ", values);
  }

  // ---------------------------------------------------------------------------
  // gating — only an ACTION documentAction=RE request is evaluated
  // ---------------------------------------------------------------------------

  @Test
  public void aCrudPatchIsNeverEvaluated() throws Exception {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(INVOICE_ID)
        .requestBody(rootBody("RE"))
        .build();

    assertNull(InvoiceReactivationGuard.checkReactivation(context));
  }

  @Test
  public void aDifferentDocumentActionIsNeverEvaluated() throws Exception {
    assertNull(InvoiceReactivationGuard.checkReactivation(reactivateAction(rootBody("CO"))));
  }

  @Test
  public void aDifferentFieldNameIsNeverEvaluated() throws Exception {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("POST")
        .fieldName("someOtherAction")
        .recordId(INVOICE_ID)
        .requestBody(rootBody("RE"))
        .build();

    assertNull(InvoiceReactivationGuard.checkReactivation(context));
  }

  @Test
  public void aNullBodyIsNeverEvaluated() throws Exception {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("POST")
        .fieldName(FIELD_DOCUMENT_ACTION)
        .recordId(INVOICE_ID)
        .requestBody(null)
        .build();

    assertNull(InvoiceReactivationGuard.checkReactivation(context));
  }

  @Test
  public void aBlankRecordIdIsNeverEvaluated() throws Exception {
    NeoContext context = NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("POST")
        .fieldName(FIELD_DOCUMENT_ACTION)
        .recordId(null)
        .requestBody(rootBody("RE"))
        .build();

    assertNull(InvoiceReactivationGuard.checkReactivation(context));
  }

  @Test
  public void theNestedFieldValuesShapeIsAlsoRecognized() throws Exception {
    // documentAction=RE nested under fieldValues (the draft-mode confirm button shape), on a
    // non-processed invoice: passes through (null), proving the shape was read, not ignored.
    assertNull(checkWithNoRectificativaQuery(reactivateAction(nestedBody("RE")), invoice(false)));
  }

  // ---------------------------------------------------------------------------
  // the refusal condition — ACCEPTED fiscal status AND a completed rectificativa
  // ---------------------------------------------------------------------------

  @Test
  public void aNonProcessedInvoiceNeverBlocksRegardlessOfFiscalStatus() throws Exception {
    Invoice invoice = invoice(false);
    when(invoice.getAeatsiiEstado()).thenReturn("CO");

    assertNull(checkWithNoRectificativaQuery(reactivateAction(rootBody("RE")), invoice));
  }

  @Test
  public void aProcessedInvoiceWithNoFiscalAcceptanceIsNotBlocked() throws Exception {
    Invoice invoice = invoice(true);
    // none of the three fiscal columns indicate acceptance
    when(invoice.getAeatsiiEstado()).thenReturn("PE");
    when(invoice.getEtvfacInvoiceStatus()).thenReturn("Pendiente");
    when(invoice.getETGOTbaiStatus()).thenReturn("Pendiente");

    assertNull(checkWithNoRectificativaQuery(reactivateAction(rootBody("RE")), invoice));
  }

  @Test
  public void aFiscallyAcceptedInvoiceWithNoRectificativaAtAllIsNotBlocked() throws Exception {
    Invoice invoice = invoice(true);
    when(invoice.getAeatsiiEstado()).thenReturn("CO");

    assertNull(check(reactivateAction(rootBody("RE")), invoice, rectificativaRows()));
  }

  /** Explicit "no regression" case: a DRAFT rectificativa must never block reactivation. */
  @Test
  public void aDraftRectificativaDoesNotBlock() throws Exception {
    Invoice invoice = invoice(true);
    when(invoice.getAeatsiiEstado()).thenReturn("CO");

    // the SQL filters docstatus = 'CO' itself; a draft rectificativa never appears in the
    // result set, which is exactly what this test simulates.
    assertNull(check(reactivateAction(rootBody("RE")), invoice, rectificativaRows()));
  }

  @Test
  public void siiAcceptedWithCompletedRectificativaIsBlocked() throws Exception {
    Invoice invoice = invoice(true);
    when(invoice.getAeatsiiEstado()).thenReturn("CO");

    JSONObject body = body(check(reactivateAction(rootBody("RE")), invoice,
        rectificativaRows("RECT-001")));

    assertFalse(body.getBoolean("success"));
    assertEquals(InvoiceReactivationGuard.MSG_REACTIVATE_FISCAL_RECTIFIED,
        body.getJSONArray("messageKeys").getString(0));
    JSONObject messageParams = body.getJSONObject("messageParams");
    assertEquals("SII", fiscalSystemOf(messageParams));
    assertEquals(1, messageParams.getJSONArray("rectificativas").length());
    assertEquals("RECT-001", messageParams.getJSONArray("rectificativas").getString(0));
    assertTrue(body.getString("message").contains("SII"));
    assertTrue(body.getString("message").contains("RECT-001"));
  }

  @Test
  public void siiAcceptedWithErrorsIsAlsoBlocked() throws Exception {
    Invoice invoice = invoice(true);
    when(invoice.getAeatsiiEstado()).thenReturn("AE");

    assertEquals("SII", fiscalSystemOf(body(check(reactivateAction(rootBody("RE")), invoice,
        rectificativaRows("RECT-001"))).getJSONObject("messageParams")));
  }

  /**
   * ETP-5745 CP-9: an invoice accepted by MORE THAN ONE fiscal system at once must name all of
   * them, not just the first match — the previous if/else-if chain silently dropped every system
   * after the first (SII) it found, which under-reported the real fiscal exposure to the user.
   */
  @Test
  public void everyAcceptedFiscalSystemIsNamedWhenMoreThanOneAccepts() throws Exception {
    Invoice invoice = invoice(true);
    when(invoice.getAeatsiiEstado()).thenReturn("CO");
    when(invoice.getEtvfacInvoiceStatus()).thenReturn("AC");

    JSONObject body = body(check(reactivateAction(rootBody("RE")), invoice,
        rectificativaRows("RECT-004")));

    assertEquals("SII, Verifactu", fiscalSystemOf(body.getJSONObject("messageParams")));
    assertTrue(body.getString("message").contains("SII, Verifactu"));
  }

  /** All three fiscal systems accepted at once — every one must be named, in the stable order. */
  @Test
  public void allThreeFiscalSystemsAreNamedWhenAllAccept() throws Exception {
    Invoice invoice = invoice(true);
    when(invoice.getAeatsiiEstado()).thenReturn("CO");
    when(invoice.getEtvfacInvoiceStatus()).thenReturn("AC");
    when(invoice.getETGOTbaiStatus()).thenReturn("Recibido");

    JSONObject body = body(check(reactivateAction(rootBody("RE")), invoice,
        rectificativaRows("RECT-005")));

    assertEquals("SII, Verifactu, TicketBAI",
        fiscalSystemOf(body.getJSONObject("messageParams")));
  }

  @Test
  public void everySiiNonAcceptedStatusPassesThrough() throws Exception {
    for (String status : List.of("PE", "EE", "IN", "NR", "AN", "BA")) {
      Invoice invoice = invoice(true);
      when(invoice.getAeatsiiEstado()).thenReturn(status);

      assertNull("SII status " + status + " must not block",
          check(reactivateAction(rootBody("RE")), invoice, rectificativaRows("RECT-001")));
    }
  }

  @Test
  public void verifactuAcceptedWithCompletedRectificativaIsBlocked() throws Exception {
    Invoice invoice = invoice(true);
    when(invoice.getEtvfacInvoiceStatus()).thenReturn("AC");

    assertEquals("Verifactu", fiscalSystemOf(body(check(reactivateAction(rootBody("RE")), invoice,
        rectificativaRows("RECT-002"))).getJSONObject("messageParams")));
  }

  @Test
  public void verifactuAcceptedWithErrorsIsAlsoBlocked() throws Exception {
    Invoice invoice = invoice(true);
    when(invoice.getEtvfacInvoiceStatus()).thenReturn("AE");

    assertEquals("Verifactu", fiscalSystemOf(body(check(reactivateAction(rootBody("RE")), invoice,
        rectificativaRows("RECT-002"))).getJSONObject("messageParams")));
  }

  @Test
  public void everyVerifactuNonAcceptedStatusPassesThrough() throws Exception {
    for (String status : List.of("Rechazado", "Error", "Pendiente", "NoAplica")) {
      Invoice invoice = invoice(true);
      when(invoice.getEtvfacInvoiceStatus()).thenReturn(status);

      assertNull("Verifactu status " + status + " must not block",
          check(reactivateAction(rootBody("RE")), invoice, rectificativaRows("RECT-002")));
    }
  }

  @Test
  public void tbaiReceivedWithCompletedRectificativaIsBlocked() throws Exception {
    Invoice invoice = invoice(true);
    when(invoice.getETGOTbaiStatus()).thenReturn("Recibido");

    assertEquals("TicketBAI", fiscalSystemOf(body(check(reactivateAction(rootBody("RE")), invoice,
        rectificativaRows("RECT-003"))).getJSONObject("messageParams")));
  }

  @Test
  public void everyTbaiNonAcceptedStatusPassesThrough() throws Exception {
    for (String status : List.of("Rechazado", "Error", "Pendiente", "NoAplica")) {
      Invoice invoice = invoice(true);
      when(invoice.getETGOTbaiStatus()).thenReturn(status);

      assertNull("TBAI status " + status + " must not block",
          check(reactivateAction(rootBody("RE")), invoice, rectificativaRows("RECT-003")));
    }
  }

  /** The message lists every completed rectificativa, in the plural form. */
  @Test
  public void multipleCompletedRectificativasAreAllListed() throws Exception {
    Invoice invoice = invoice(true);
    when(invoice.getAeatsiiEstado()).thenReturn("CO");

    JSONObject body = body(check(reactivateAction(rootBody("RE")), invoice,
        rectificativaRows("RECT-001", "RECT-002")));

    JSONArray rectificativas = body.getJSONObject("messageParams").getJSONArray("rectificativas");
    assertEquals(2, rectificativas.length());
    assertEquals("RECT-001", rectificativas.getString(0));
    assertEquals("RECT-002", rectificativas.getString(1));
    assertTrue("plural wording", body.getString("message").contains("invoices"));
    assertTrue(body.getString("message").contains("RECT-001, RECT-002"));
  }

  // ---------------------------------------------------------------------------
  // computeBlock — shared by checkReactivation and the GET enrichment
  // ---------------------------------------------------------------------------

  @Test
  public void computeBlockReturnsNullForABlankInvoiceId() {
    assertNull(InvoiceReactivationGuard.computeBlock(null));
    assertNull(InvoiceReactivationGuard.computeBlock(""));
  }

  @Test
  public void computeBlockMirrorsCheckReactivationForABlockedInvoice() throws Exception {
    Invoice invoice = invoice(true);
    when(invoice.getAeatsiiEstado()).thenReturn("CO");

    InvoiceReactivationGuard.Block block;
    try (MockedStatic<OBContext> obc = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      Connection conn = mock(Connection.class);
      when(obDal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = rectificativaRows("RECT-001");
      when(ps.executeQuery()).thenReturn(rs);
      block = InvoiceReactivationGuard.computeBlock(INVOICE_ID);
    }

    assertNotNull(block);
    assertEquals("SII", block.fiscalSystem);
    assertEquals(List.of("RECT-001"), block.rectificativaDocNos);
  }

  // ---------------------------------------------------------------------------
  // enrichReactivationBlock — the GET read-only signal
  // ---------------------------------------------------------------------------

  @Test
  public void enrichInjectsFalseAndNothingElseWhenNotBlocked() throws Exception {
    Invoice invoice = invoice(false);
    JSONObject rec = new JSONObject();

    try (MockedStatic<OBContext> obc = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      InvoiceReactivationGuard.enrichReactivationBlock(rec, INVOICE_ID);
    }

    assertFalse(rec.getBoolean("reactivationBlocked"));
    assertFalse("no fiscal system key when not blocked",
        rec.has("reactivationBlockedFiscalSystem"));
    assertFalse("no rectificativas key when not blocked",
        rec.has("reactivationBlockedRectificativas"));
  }

  @Test
  public void enrichInjectsTrueAndTheDetailsWhenBlocked() throws Exception {
    Invoice invoice = invoice(true);
    when(invoice.getETGOTbaiStatus()).thenReturn("Recibido");
    JSONObject rec = new JSONObject();

    try (MockedStatic<OBContext> obc = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      Connection conn = mock(Connection.class);
      when(obDal.getConnection()).thenReturn(conn);
      PreparedStatement ps = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      ResultSet rs = rectificativaRows("RECT-009");
      when(ps.executeQuery()).thenReturn(rs);
      InvoiceReactivationGuard.enrichReactivationBlock(rec, INVOICE_ID);
    }

    assertTrue(rec.getBoolean("reactivationBlocked"));
    assertEquals(1, rec.getJSONArray("reactivationBlockedFiscalSystem").length());
    assertEquals("TicketBAI", rec.getJSONArray("reactivationBlockedFiscalSystem").getString(0));
    assertEquals(1, rec.getJSONArray("reactivationBlockedRectificativas").length());
    assertEquals("RECT-009", rec.getJSONArray("reactivationBlockedRectificativas").getString(0));
  }

  // ---------------------------------------------------------------------------
  // fail-open — a read error must never block, never throw
  // ---------------------------------------------------------------------------

  @Test
  public void checkReactivationFailsOpenWhenTheInvoiceCannotBeRead() throws Exception {
    try (MockedStatic<OBContext> obc = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      dal.when(OBDal::getInstance).thenThrow(new IllegalStateException("no DAL"));

      assertNull(InvoiceReactivationGuard.checkReactivation(reactivateAction(rootBody("RE"))));
    }
  }

  @Test
  public void computeBlockFailsOpenWhenTheInvoiceCannotBeRead() {
    try (MockedStatic<OBContext> obc = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      dal.when(OBDal::getInstance).thenThrow(new IllegalStateException("no DAL"));

      assertNull(InvoiceReactivationGuard.computeBlock(INVOICE_ID));
    }
  }

  @Test
  public void checkReactivationFailsOpenWhenTheRectificativaQueryThrows() throws Exception {
    Invoice invoice = invoice(true);
    when(invoice.getAeatsiiEstado()).thenReturn("CO");

    try (MockedStatic<OBContext> obc = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      Connection conn = mock(Connection.class);
      when(obDal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenThrow(new SQLException("connection closed"));

      assertNull(InvoiceReactivationGuard.checkReactivation(reactivateAction(rootBody("RE"))));
    }
  }

  @Test
  public void enrichReactivationBlockFailsOpenWhenTheInvoiceCannotBeRead() throws Exception {
    JSONObject rec = new JSONObject();
    try (MockedStatic<OBContext> obc = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      dal.when(OBDal::getInstance).thenThrow(new IllegalStateException("no DAL"));

      InvoiceReactivationGuard.enrichReactivationBlock(rec, INVOICE_ID);
    }

    assertFalse(rec.getBoolean("reactivationBlocked"));
  }
}
