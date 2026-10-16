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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

import com.smf.ticketbai.data.TbaiSyncinvoice;
import com.smf.ticketbai.data.TbaiValcode;

/**
 * Unit tests for {@link TbaiXmlgeneratorHandler}.
 *
 * <p>Covers action-name matching for the three valid variants, the error
 * message prefix produced when execution fails, and the failed-attempt record
 * written when a send fails before the tax authority answered (ETP-5700): without
 * it {@code tbai_syncinvoice} stays empty and the invoice keeps showing "Pendiente".
 *
 * @covers com.etendoerp.go.schemaforge.TbaiXmlgeneratorHandler
 */
public class TbaiXmlgeneratorHandlerTest {

  private final TbaiXmlgeneratorHandler handler = new TbaiXmlgeneratorHandler();

  @Test
  public void testMatchesCanonicalActionName() {
    assertTrue(handler.matchesActionName(TbaiXmlgeneratorHandler.ACTION_NAME));
  }

  @Test
  public void testMatchesLegacyActionName() {
    assertTrue(handler.matchesActionName(TbaiXmlgeneratorHandler.ACTION_NAME_LEGACY));
  }

  @Test
  public void testMatchesQualifierActionName() {
    assertTrue(handler.matchesActionName(TbaiXmlgeneratorHandler.ACTION_NAME_QUALIFIER));
  }

  @Test
  public void testDoesNotMatchUnrelatedActionName() {
    assertFalse(handler.matchesActionName("EM_Aeatsii_Send"));
    assertFalse(handler.matchesActionName("registerPayment"));
    assertFalse(handler.matchesActionName(""));
  }

  @Test
  public void testDoesNotMatchNull() {
    assertFalse(handler.matchesActionName(null));
  }

  @Test
  public void testBuildExecutionErrorMessageIncludesPrefix() {
    RuntimeException e = new RuntimeException("process not found");
    String msg = handler.buildExecutionErrorMessage(e);
    assertTrue(msg.startsWith("TicketBAI XML generation failed: "));
    assertTrue(msg.contains("process not found"));
  }

  @Test
  public void testHandleReturnsNullForGetRequest() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName(TbaiXmlgeneratorHandler.ACTION_NAME)
        .recordId("invoice-1")
        .build();

    assertNull(handler.handle(ctx));
  }

  @Test
  public void testHandleReturnsNullForCrudEndpoint() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.CRUD)
        .fieldName(TbaiXmlgeneratorHandler.ACTION_NAME)
        .recordId("invoice-1")
        .build();

    assertNull(handler.handle(ctx));
  }

  // ── ETP-5700: a send that fails before the tax authority answers leaves a trace ──

  private static final String INVOICE_ID = "invoice-5700";
  private static final String OBEXCEPTION_PREFIX = "org.openbravo.base.exception.OBException: ";
  private static final String POSTAL_CODE_ERROR = "El código postal NO puede ser nulo";

  /** Builds the flat 400 body {@code translateObuiappResult} produces for a process-view error. */
  private static NeoResponse obuiappError(String message) throws Exception {
    return new NeoResponse(400, new JSONObject().put("status", "error").put("message", message));
  }

  /** Spy whose DAL-touching seams are stubbed; {@code attemptsAfter} models ticketbai's writes. */
  private TbaiXmlgeneratorHandler stubbedHandler(NeoResponse processResult, int attemptsBefore,
      int attemptsAfter) throws Exception {
    TbaiXmlgeneratorHandler spyHandler = spy(new TbaiXmlgeneratorHandler());
    doReturn(attemptsBefore, attemptsAfter).when(spyHandler).countSyncAttempts(INVOICE_ID);
    doReturn(processResult).when(spyHandler).runTbaiProcess(INVOICE_ID);
    doNothing().when(spyHandler).recordFailedAttempt(anyString(), anyString());
    return spyHandler;
  }

  @Test
  public void failureWithoutAuthorityResponseRecordsErrorAttemptWithCleanMessage()
      throws Exception {
    TbaiXmlgeneratorHandler spyHandler = stubbedHandler(
        obuiappError(OBEXCEPTION_PREFIX + POSTAL_CODE_ERROR), 0, 0);

    NeoResponse result = spyHandler.executeAction(INVOICE_ID);

    verify(spyHandler).recordFailedAttempt(INVOICE_ID, POSTAL_CODE_ERROR);
    assertEquals(400, result.getHttpStatus());
    assertEquals(POSTAL_CODE_ERROR, result.getBody().getString("message"));
  }

  @Test
  public void successRecordsNoAttempt() throws Exception {
    NeoResponse ok = NeoResponse.ok(new JSONObject().put("status", "success"));
    TbaiXmlgeneratorHandler spyHandler = stubbedHandler(ok, 0, 1);

    NeoResponse result = spyHandler.executeAction(INVOICE_ID);

    assertSame(ok, result);
    verify(spyHandler, never()).recordFailedAttempt(anyString(), anyString());
  }

  @Test
  public void failureAfterTicketbaiRecordedAResponseRecordsNoExtraAttempt() throws Exception {
    // e.g. the authority answered "Rechazado": ticketbai already wrote its own row.
    TbaiXmlgeneratorHandler spyHandler = stubbedHandler(
        obuiappError("Error en la generación del XML"), 2, 3);

    NeoResponse result = spyHandler.executeAction(INVOICE_ID);

    assertEquals(400, result.getHttpStatus());
    verify(spyHandler, never()).recordFailedAttempt(anyString(), anyString());
  }

  @Test
  public void exceptionWithoutAuthorityResponseRecordsAttemptAndRethrows() throws Exception {
    TbaiXmlgeneratorHandler spyHandler = spy(new TbaiXmlgeneratorHandler());
    doReturn(0, 0).when(spyHandler).countSyncAttempts(INVOICE_ID);
    RuntimeException boom = new RuntimeException(OBEXCEPTION_PREFIX + POSTAL_CODE_ERROR);
    doThrow(boom).when(spyHandler).runTbaiProcess(INVOICE_ID);
    doNothing().when(spyHandler).recordFailedAttempt(anyString(), anyString());

    try {
      spyHandler.executeAction(INVOICE_ID);
      fail("the exception must reach the base handler, which turns it into a 500");
    } catch (RuntimeException e) {
      assertSame(boom, e);
    }
    verify(spyHandler).recordFailedAttempt(INVOICE_ID, POSTAL_CODE_ERROR);
    assertEquals("TicketBAI XML generation failed: " + POSTAL_CODE_ERROR,
        spyHandler.buildExecutionErrorMessage(boom));
  }

  @Test
  public void failureToRecordTheAttemptDoesNotMaskTheOriginalError() throws Exception {
    TbaiXmlgeneratorHandler spyHandler = stubbedHandler(obuiappError(POSTAL_CODE_ERROR), 0, 0);
    doThrow(new IllegalStateException("db down")).when(spyHandler)
        .recordFailedAttempt(anyString(), anyString());

    NeoResponse result = spyHandler.executeAction(INVOICE_ID);

    assertEquals(400, result.getHttpStatus());
    assertEquals(POSTAL_CODE_ERROR, result.getBody().getString("message"));
  }

  @Test
  public void cleanMessageStripsQualifiedExceptionPrefixesOnly() {
    assertEquals(POSTAL_CODE_ERROR,
        TbaiXmlgeneratorHandler.cleanMessage(OBEXCEPTION_PREFIX + POSTAL_CODE_ERROR));
    // nested inside another prefix (ticketbai's TBAI_syncError wrapper, NEO's process prefix)
    assertEquals("Process execution failed: " + POSTAL_CODE_ERROR,
        TbaiXmlgeneratorHandler.cleanMessage("Process execution failed: java.lang."
            + "IllegalStateException: " + OBEXCEPTION_PREFIX + POSTAL_CODE_ERROR));
    // ordinary text with colons is untouched
    assertEquals("Error en la sincronización Gipuzkoa: dato inválido",
        TbaiXmlgeneratorHandler.cleanMessage("Error en la sincronización Gipuzkoa: dato inválido"));
    assertEquals("", TbaiXmlgeneratorHandler.cleanMessage(null));
  }

  @Test
  public void recordFailedAttemptPersistsErrorRowAndTruncatedValidationCode() {
    String longMessage = StringUtils.repeat('x', 1500);
    OBDal dal = mock(OBDal.class);
    OBProvider provider = mock(OBProvider.class);
    Invoice invoice = mock(Invoice.class);
    Organization org = mock(Organization.class);
    Client client = mock(Client.class);
    TbaiSyncinvoice syncInvoice = mock(TbaiSyncinvoice.class);
    TbaiValcode valCode = mock(TbaiValcode.class);
    when(invoice.getOrganization()).thenReturn(org);
    when(invoice.getClient()).thenReturn(client);
    when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
    when(provider.get(TbaiSyncinvoice.class)).thenReturn(syncInvoice);
    when(provider.get(TbaiValcode.class)).thenReturn(valCode);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);

      handler.recordFailedAttempt(INVOICE_ID, longMessage);
    }

    verify(syncInvoice).setOrganization(org);
    verify(syncInvoice).setClient(client);
    verify(syncInvoice).setInvoice(invoice);
    verify(syncInvoice).setEstado("Error");
    verify(syncInvoice).setDescripcion("01");
    verify(valCode).setOrganization(org);
    verify(valCode).setClient(client);
    verify(valCode).setTbaiSyncinvoiceID(syncInvoice);
    ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
    verify(valCode).setDescripcion(description.capture());
    assertEquals(1000, description.getValue().length());
    verify(dal).save(syncInvoice);
    verify(dal).save(valCode);
    verify(dal).flush();
  }

  @Test
  public void recordFailedAttemptIsANoOpWhenTheInvoiceIsGone() {
    OBDal dal = mock(OBDal.class);
    when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(null);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      handler.recordFailedAttempt(INVOICE_ID, POSTAL_CODE_ERROR);
    }

    verify(dal, never()).save(any());
    verify(dal, never()).flush();
  }

  @Test
  public void blankFailureMessageStillRecordsAnAttempt() throws Exception {
    TbaiXmlgeneratorHandler spyHandler = stubbedHandler(
        new NeoResponse(400, new JSONObject().put("status", "error")), 0, 0);

    String fallback = "El envío a TicketBAI ha fallado.";

    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      messages.when(() -> OBMessageUtils.messageBD(
          TbaiXmlgeneratorHandler.FALLBACK_FAILURE_MESSAGE_KEY)).thenReturn(fallback);

      spyHandler.executeAction(INVOICE_ID);
    }

    verify(spyHandler).recordFailedAttempt(INVOICE_ID, fallback);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void countSyncAttemptsCountsTheInvoiceSyncRows() {
    OBDal dal = mock(OBDal.class);
    OBCriteria<TbaiSyncinvoice> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(TbaiSyncinvoice.class)).thenReturn(criteria);
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.count()).thenReturn(3);

    int count;
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      count = handler.countSyncAttempts(INVOICE_ID);
    }

    assertEquals(3, count);
    verify(dal).createCriteria(TbaiSyncinvoice.class);
    ArgumentCaptor<Criterion> restriction = ArgumentCaptor.forClass(Criterion.class);
    verify(criteria).add(restriction.capture());
    assertEquals(TbaiSyncinvoice.PROPERTY_INVOICE + ".id=" + INVOICE_ID,
        restriction.getValue().toString());
    verify(criteria).count();
  }

  @Test
  public void nestedErrorMessageIsCleanedInTheResponseAndTheRecordedAttempt() throws Exception {
    JSONObject body = new JSONObject().put("error",
        new JSONObject().put("message", OBEXCEPTION_PREFIX + "msg"));
    TbaiXmlgeneratorHandler spyHandler = stubbedHandler(new NeoResponse(400, body), 0, 0);

    NeoResponse result = spyHandler.executeAction(INVOICE_ID);

    verify(spyHandler).recordFailedAttempt(INVOICE_ID, "msg");
    assertEquals(400, result.getHttpStatus());
    assertEquals("msg", result.getBody().getJSONObject("error").getString("message"));
    assertFalse(result.getBody().has("message"));
  }

  @Test
  public void cleanMessageKeepsDottedFieldPathsAndStripsJavaLangPrefixes() {
    // a single dotted segment is a field path, not a qualified class name
    assertEquals("Error: nif.Emisor: vacío",
        TbaiXmlgeneratorHandler.cleanMessage("Error: nif.Emisor: vacío"));
    assertEquals("x", TbaiXmlgeneratorHandler.cleanMessage("java.lang.NullPointerException: x"));
  }
}
