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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Representative test for the shared posting delegation added to every document header handler,
 * plus the invoice-only posting rules layered on top of it (ETP-5692).
 *
 * <p>The same one-line "delegate to {@link DocumentPostingService#handleAction} first, short-circuit
 * on a non-null result" edit was applied to all six header handlers (Sales/Purchase Invoice, GL
 * Journal, Amortization, Goods Receipt, Goods Shipment). This test exercises
 * {@link SalesInvoiceHeaderHandler} as the representative case: a mocked service returning a sentinel
 * {@link NeoResponse} for an ACTION context must be returned unchanged by {@code handle()}, proving
 * the posting branch runs before the handler's own logic.</p>
 *
 * <p>ETP-5692: an invoice may be unposted only when it is Completed AND posted
 * ({@link InvoicePostingGate}), and both invoice headers declare {@code post} / {@code unpost} as
 * action contracts so agents can discover them.</p>
 *
 * @covers com.etendoerp.go.schemaforge.SalesInvoiceHeaderHandler
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceHeaderHandler
 * @covers com.etendoerp.go.schemaforge.InvoicePostingGate
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class SalesInvoiceHeaderHandlerPostingTest {

  private static final String INVOICE_ID = "INV-1";
  private static final String ACTION_UNPOST = "unpost";
  private static final String MESSAGE_KEYS = "messageKeys";

  @Test
  public void handleReturnsPostingResponseWhenServiceHandlesAction() {
    DocumentPostingService service = mock(DocumentPostingService.class);
    NeoContext context = mock(NeoContext.class);
    NeoResponse sentinel = NeoResponse.ok(new JSONObject());
    when(service.handleAction(context)).thenReturn(sentinel);

    SalesInvoiceHeaderHandler handler = new SalesInvoiceHeaderHandler();
    handler.setPostingService(service);

    assertSame("Posting response must short-circuit the handler's own logic",
        sentinel, handler.handle(context));
  }

  // ---------------------------------------------------------------------------
  // ETP-5692 — unpost status gate (BUG-4 repro)
  // ---------------------------------------------------------------------------

  private static NeoContext actionContext(String action) {
    return NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION)
        .httpMethod("POST")
        .fieldName(action)
        .recordId(INVOICE_ID)
        .requestBody(new JSONObject())
        .build();
  }

  private static Invoice invoice(String docStatus, String posted) {
    Invoice invoice = mock(Invoice.class);
    when(invoice.getDocumentStatus()).thenReturn(docStatus);
    when(invoice.getPosted()).thenReturn(posted);
    return invoice;
  }

  /** Runs {@code handler.handle(context)} with {@link OBDal} answering {@code invoice}. */
  private static NeoResponse handleWith(NeoHandler handler, Invoice invoice, NeoContext context) {
    try (MockedStatic<OBContext> obc = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = Mockito.mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
      return handler.handle(context);
    }
  }

  private static SalesInvoiceHeaderHandler salesHandler(DocumentPostingService service) {
    SalesInvoiceHeaderHandler handler = new SalesInvoiceHeaderHandler();
    handler.setPostingService(service);
    return handler;
  }

  /** Live QA: unpost of a draft answered {"success":true}. Now a 422 and the service never runs. */
  @Test
  public void unpostOfDraftInvoiceIsRefusedBeforeTheService() throws Exception {
    DocumentPostingService service = mock(DocumentPostingService.class);

    NeoResponse response = handleWith(salesHandler(service), invoice("DR", "N"),
        actionContext(ACTION_UNPOST));

    assertEquals(422, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertFalse(body.getBoolean("success"));
    assertEquals(InvoicePostingGate.MSG_UNPOST_NOT_COMPLETED,
        body.getJSONArray(MESSAGE_KEYS).getString(0));
    assertEquals("DR", body.getJSONObject("messageParams").getString("docStatus"));
    assertTrue(body.getString("message").contains("DR"));
    verify(service, never()).handleAction(any());
  }

  /**
   * Live QA: unpost of a voided invoice flipped Posted from 'D' to 'N' (re-enabling its
   * background posting). Now refused, nothing runs.
   */
  @Test
  public void unpostOfVoidedInvoiceIsRefusedBeforeTheService() throws Exception {
    DocumentPostingService service = mock(DocumentPostingService.class);

    NeoResponse response = handleWith(salesHandler(service), invoice("VO", "D"),
        actionContext(ACTION_UNPOST));

    assertEquals(422, response.getHttpStatus());
    assertEquals(InvoicePostingGate.MSG_UNPOST_NOT_COMPLETED,
        response.getBody().getJSONArray(MESSAGE_KEYS).getString(0));
    verify(service, never()).handleAction(any());
  }

  /** A completed invoice that is not posted has nothing to unpost: a clear 422, not a no-op 200. */
  @Test
  public void unpostOfCompletedUnpostedInvoiceIsRefused() throws Exception {
    DocumentPostingService service = mock(DocumentPostingService.class);

    NeoResponse response = handleWith(salesHandler(service), invoice("CO", "N"),
        actionContext(ACTION_UNPOST));

    assertEquals(422, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals(InvoicePostingGate.MSG_UNPOST_NOT_POSTED,
        body.getJSONArray(MESSAGE_KEYS).getString(0));
    assertFalse(body.has("messageParams"));
    verify(service, never()).handleAction(any());
  }

  /** The one eligible case — Completed and posted — reaches the shared service unchanged. */
  @Test
  public void unpostOfCompletedPostedInvoiceDelegatesToTheService() {
    DocumentPostingService service = mock(DocumentPostingService.class);
    NeoResponse sentinel = NeoResponse.ok(new JSONObject());
    NeoContext context = actionContext(ACTION_UNPOST);
    when(service.handleAction(context)).thenReturn(sentinel);

    assertSame(sentinel, handleWith(salesHandler(service), invoice("CO", "Y"), context));
  }

  /** The gate is the invoice customization, so the purchase header applies it too. */
  @Test
  public void purchaseInvoiceUnpostOfDraftIsRefused() {
    DocumentPostingService service = mock(DocumentPostingService.class);
    PurchaseInvoiceHeaderHandler handler = new PurchaseInvoiceHeaderHandler();
    handler.setPostingService(service);

    NeoResponse response = handleWith(handler, invoice("DR", "N"), actionContext(ACTION_UNPOST));

    assertEquals(422, response.getHttpStatus());
    verify(service, never()).handleAction(any());
  }

  /** {@code post} is not gated by the invoice: the service names a draft / posted refusal itself. */
  @Test
  public void postIsNotGatedByTheInvoiceCustomization() {
    DocumentPostingService service = mock(DocumentPostingService.class);
    NeoResponse sentinel = NeoResponse.ok(new JSONObject());
    NeoContext context = actionContext("post");
    when(service.handleAction(context)).thenReturn(sentinel);

    assertSame(sentinel, handleWith(salesHandler(service), invoice("DR", "N"), context));
  }

  // ---------------------------------------------------------------------------
  // ETP-5692 — post / unpost discoverable (GAP-1)
  // ---------------------------------------------------------------------------

  /** Both invoice headers declare post and unpost: mutating, no parameters, the id described. */
  @Test
  public void bothInvoiceHeadersDeclarePostAndUnpost() {
    for (Map<String, NeoActionContract> contracts : List.of(
        new SalesInvoiceHeaderHandler().actionContracts(),
        new PurchaseInvoiceHeaderHandler().actionContracts())) {
      for (String action : List.of("post", ACTION_UNPOST)) {
        NeoActionContract contract = contracts.get(action);
        assertNotNull(action + " must be declared", contract);
        assertTrue(contract.isMutating());
        assertTrue(contract.getParams().isEmpty());
        assertEquals("POST", contract.getHttpMethod());
        assertEquals("the invoice id", contract.getIdDescription());
      }
      assertTrue(contracts.get(ACTION_UNPOST).getDescription().contains("'CO'"));
      assertTrue(contracts.containsKey("registerPayment"));
      assertTrue(contracts.containsKey("currencyOptions"));
    }
  }
}
