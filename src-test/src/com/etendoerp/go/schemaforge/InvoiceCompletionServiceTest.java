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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletResponse;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.advpaymentmngt.ProcessInvoiceUtil;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.ui.Process;

/**
 * Unit tests for {@link InvoiceCompletionService} (ETP-5381).
 *
 * <p>This class holds the completion logic that used to live inside
 * {@code AbstractInvoiceHeaderHandler.completeInvoiceIfNeeded}; the tests below are the
 * behavioral contract of that logic, now asserted directly on the extracted service.
 * {@code AbstractInvoiceHeaderHandlerTest} keeps its own copies of the same scenarios, but
 * there they assert the <em>delegation</em> from the handler — they are not duplicates.
 *
 * <p>Everything is exercised with {@link MockedStatic}: no CDI container, no database.
 */
public class InvoiceCompletionServiceTest {

  private static final String PROCESS_ID_COMPLETE_INVOICE = "111";

  /** Builds a success {@link OBError}, the shape {@code ProcessInvoiceUtil} returns when the
   * document was completed. */
  private static OBError successResult(String message) {
    OBError result = new OBError();
    result.setType("Success");
    result.setTitle("Success");
    result.setMessage(message);
    return result;
  }

  /** Builds a business-error {@link OBError}, the shape returned when a rule rejects completion. */
  private static OBError errorResult(String message) {
    OBError result = new OBError();
    result.setType("Error");
    result.setTitle("Error");
    result.setMessage(message);
    return result;
  }

  /**
   * A blank invoice id is rejected before any CDI lookup or DB access is attempted: the caller
   * lost the id (the entity is detached after completion, so this is a real failure mode), and
   * running the process without one would be meaningless.
   */
  @Test
  public void completeInvoice_blankInvoiceId_returns400NoWeldLookup() {
    try (MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      NeoResponse result = InvoiceCompletionService.completeInvoice("", null);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      assertTrue(result.getBody().toString().contains("Missing invoice record id for completion"));
      weldMock.verifyNoInteractions();
    }
  }

  /** Same guard for a null id — {@code StringUtils.isBlank} covers both. */
  @Test
  public void completeInvoice_nullInvoiceId_returns400() {
    NeoResponse result = InvoiceCompletionService.completeInvoice(null, null);

    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  /**
   * Happy path: obtains {@link ProcessInvoiceUtil} through
   * {@link WeldUtils#getInstanceFromStaticBeanManager} (never {@code new}, or the
   * {@code ProcessInvoiceHook} chain would be empty), calls {@code process(...)} with the exact
   * argument tuple the classic path uses — id, {@code "CO"}, and three empty strings for the
   * void-date/supplier-reference params that {@code ProcessInvoiceUtil} dereferences
   * unconditionally — and translates a success {@link OBError} into a 200.
   */
  @Test
  public void completeInvoice_success_invokesProcessInvoiceUtilAndReturnsOk() {
    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    ProcessInvoiceUtil processInvoiceUtil = mock(ProcessInvoiceUtil.class);
    when(processInvoiceUtil.process(
        eq("inv-ok"), eq("CO"), eq(""), eq(""), eq(""), any(), any()))
        .thenReturn(successResult("Document completed"));

    try (MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class);
        MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any())).thenReturn(vars);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Process.class, PROCESS_ID_COMPLETE_INVOICE)).thenReturn(mock(Process.class));

      NeoResponse result = InvoiceCompletionService.completeInvoice("inv-ok", null);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_OK, result.getHttpStatus());
      verify(processInvoiceUtil).process(
          eq("inv-ok"), eq("CO"), eq(""), eq(""), eq(""), eq(vars), any());
    }
  }

  /**
   * A business-rule rejection inside {@code ProcessInvoiceUtil} (or one of its hooks) comes back
   * as an {@code Error}-typed {@link OBError} and must become a 400 carrying the translated
   * message — the text the user is shown, so it must not be swallowed.
   */
  @Test
  public void completeInvoice_errorOBError_returns400WithMessage() {
    ProcessInvoiceUtil processInvoiceUtil = mock(ProcessInvoiceUtil.class);
    when(processInvoiceUtil.process(
        eq("inv-err"), eq("CO"), eq(""), eq(""), eq(""), any(), any()))
        .thenReturn(errorResult("BP currency is not set"));

    try (MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class);
        MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenReturn(new VariablesSecureApp("u", "c", "o", "r", "en_US"));
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Process.class, PROCESS_ID_COMPLETE_INVOICE)).thenReturn(mock(Process.class));

      NeoResponse result = InvoiceCompletionService.completeInvoice("inv-err", null);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      assertTrue(result.getBody().toString().contains("BP currency is not set"));
    }
  }

  /**
   * AD_Process 111 missing is a broken installation, not a user error: the completion may well
   * have run, so the response must be a 500 rather than a 400 that invites a retry.
   */
  @Test
  public void completeInvoice_processRecordMissing_returns500() {
    ProcessInvoiceUtil processInvoiceUtil = mock(ProcessInvoiceUtil.class);
    when(processInvoiceUtil.process(
        eq("inv-noproc"), eq("CO"), eq(""), eq(""), eq(""), any(), any()))
        .thenReturn(successResult("Document completed"));

    try (MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class);
        MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenReturn(new VariablesSecureApp("u", "c", "o", "r", "en_US"));
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Process.class, PROCESS_ID_COMPLETE_INVOICE)).thenReturn(null);

      NeoResponse result = InvoiceCompletionService.completeInvoice("inv-noproc", null);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
      assertTrue(result.getBody().toString().contains("Completion process configuration missing"));
    }
  }

  /**
   * Any unexpected exception (Weld unavailable, session gone) is caught and reported as a 500 —
   * never propagated, so the calling handler's own error mapping stays authoritative.
   */
  @Test
  public void completeInvoice_unexpectedException_returns500() {
    try (MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class)) {
      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenThrow(new RuntimeException("session unavailable"));

      NeoResponse result = InvoiceCompletionService.completeInvoice("inv-boom", null);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
      assertTrue(result.getBody().toString().contains("session unavailable"));
    }
  }

  // ── completeInvoiceOrThrow ───────────────────────────────────────────────────

  /**
   * The throwing variant used by the creation handlers: a 400 (business rejection) becomes an
   * {@link OBException} carrying the same message, which those handlers already map to a 400.
   * Throwing is what guarantees the caller cannot build a success payload for a rolled-back
   * invoice.
   */
  @Test
  public void completeInvoiceOrThrow_businessError_throwsOBExceptionWithMessage() {
    ProcessInvoiceUtil processInvoiceUtil = mock(ProcessInvoiceUtil.class);
    when(processInvoiceUtil.process(
        eq("inv-throw-err"), eq("CO"), eq(""), eq(""), eq(""), any(), any()))
        .thenReturn(errorResult("Period is closed"));

    try (MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class);
        MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenReturn(new VariablesSecureApp("u", "c", "o", "r", "en_US"));
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Process.class, PROCESS_ID_COMPLETE_INVOICE)).thenReturn(mock(Process.class));

      try {
        InvoiceCompletionService.completeInvoiceOrThrow("inv-throw-err", null);
        fail("A business rejection must abort the caller, not return normally");
      } catch (OBException e) {
        assertTrue("The business message must survive the translation to an exception",
            String.valueOf(e.getMessage()).contains("Period is closed"));
      }
    }
  }

  /** A 500 (infrastructure) must NOT surface as an {@code OBException}: the creation handlers map
   * {@code OBException} to a 400, which would mislabel an infrastructure failure as a user error. */
  @Test
  public void completeInvoiceOrThrow_infrastructureFailure_throwsIllegalStateException() {
    try (MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class)) {
      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenThrow(new RuntimeException("weld down"));

      try {
        InvoiceCompletionService.completeInvoiceOrThrow("inv-infra", null);
        fail("An infrastructure failure must abort the caller");
      } catch (OBException e) {
        fail("An infrastructure failure must not be reported as a business error: " + e.getMessage());
      } catch (IllegalStateException e) {
        // The cause must survive. extractMessage() reads both body shapes on purpose: the 400
        // business path comes from NeoProcessService.translateClassicResult, which puts "message"
        // at the top level, while NeoResponse.error(int, String) nests it under
        // {"error":{"message":...}}. Reading only the top level flattened every infrastructure
        // failure to the generic fallback, losing the reason on exactly the errors worth
        // diagnosing (ETP-5381).
        assertTrue("The underlying cause must survive the translation to an exception: " + e.getMessage(),
            String.valueOf(e.getMessage()).contains("weld down"));
      }
    }
  }

  /** A successful completion returns silently so the caller can go on building its 201 payload. */
  @Test
  public void completeInvoiceOrThrow_success_doesNotThrow() {
    ProcessInvoiceUtil processInvoiceUtil = mock(ProcessInvoiceUtil.class);
    when(processInvoiceUtil.process(
        eq("inv-throw-ok"), eq("CO"), eq(""), eq(""), eq(""), any(), any()))
        .thenReturn(successResult("Document completed"));

    try (MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
        MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class);
        MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenReturn(new VariablesSecureApp("u", "c", "o", "r", "en_US"));
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Process.class, PROCESS_ID_COMPLETE_INVOICE)).thenReturn(mock(Process.class));

      InvoiceCompletionService.completeInvoiceOrThrow("inv-throw-ok", null);

      verify(processInvoiceUtil).process(
          eq("inv-throw-ok"), eq("CO"), eq(""), eq(""), eq(""), any(), any());
    }
  }
}
