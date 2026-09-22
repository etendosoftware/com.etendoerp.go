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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.advpaymentmngt.ProcessInvoiceUtil;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Shared stubs for the atomic create-and-confirm step introduced by ETP-5381.
 *
 * <p>Every handler that auto-generates an invoice (orders, shipments, receipts, returns,
 * quotations) now completes it in the same request, so exercising any of those creation paths
 * without a CDI container or a database requires the same three stubs. Extracted here
 * (from {@code CreateDraftInvoiceHandlerTest}) once the return handlers needed them too — a
 * second copy would drift the moment {@link InvoiceCompletionService} gains another dependency.
 */
final class InvoiceCompletionTestSupport {

  private InvoiceCompletionTestSupport() {
  }

  /**
   * Stubs the atomic create-and-confirm step, so a creation handler can be exercised without a
   * CDI container or a database.
   *
   * <p>{@code InvoiceCompletionService.completeInvoiceOrThrow} resolves {@link ProcessInvoiceUtil}
   * through {@link WeldUtils} and then reads AD_Process 111 from the DAL; both are stubbed here,
   * together with the {@link VariablesSecureApp} the classic call needs. Callers decide the
   * outcome by passing a success or an error {@link OBError} — that one argument is the
   * difference between the success and the 400 paths.
   *
   * <p>Must be opened INSIDE the caller's own {@code MockedStatic<OBDal>} scope (it stubs the
   * already-created {@code dal} mock) and closed before it — hence the {@link AutoCloseable}
   * shape rather than a plain factory method.
   */
  static final class CompletionMocks implements AutoCloseable {
    private final MockedStatic<NeoDefaultsService> defaultsMock;
    private final MockedStatic<WeldUtils> weldMock;
    final ProcessInvoiceUtil processInvoiceUtil;

    CompletionMocks(OBDal dal, OBError processResult) {
      defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
      weldMock = Mockito.mockStatic(WeldUtils.class);
      processInvoiceUtil = mock(ProcessInvoiceUtil.class);
      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenReturn(new VariablesSecureApp("u", "c", "o", "r", "en_US"));
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);
      when(processInvoiceUtil.process(
          anyString(), eq("CO"), anyString(), anyString(), anyString(), any(), any()))
          .thenReturn(processResult);
      when(dal.get(eq(Process.class), eq("111"))).thenReturn(mock(Process.class));
    }

    @Override
    public void close() {
      weldMock.close();
      defaultsMock.close();
    }
  }

  /** A completion result meaning "the invoice was confirmed". */
  static OBError completionSuccess() {
    OBError result = new OBError();
    result.setType("Success");
    result.setTitle("Success");
    result.setMessage("Document completed");
    return result;
  }

  /** A completion result meaning "a business rule rejected the confirmation". */
  static OBError completionError(String message) {
    OBError result = new OBError();
    result.setType("Error");
    result.setTitle("Error");
    result.setMessage(message);
    return result;
  }

  /**
   * Stubs the invoice re-read that follows completion. The handler cannot reuse the invoice it
   * created — {@code ProcessInvoiceUtil} commits and closes the Hibernate session, detaching it —
   * so it captures the id first and reloads the entity from the reopened session; the response is
   * built from THAT instance.
   */
  static Invoice mockCompletedInvoice(OBDal dal, String invoiceId, String documentNo,
      String documentStatus) {
    Invoice completed = mock(Invoice.class);
    when(completed.getId()).thenReturn(invoiceId);
    when(completed.getDocumentNo()).thenReturn(documentNo);
    when(completed.getDocumentStatus()).thenReturn(documentStatus);
    when(dal.get(eq(Invoice.class), eq(invoiceId))).thenReturn(completed);
    return completed;
  }
}
