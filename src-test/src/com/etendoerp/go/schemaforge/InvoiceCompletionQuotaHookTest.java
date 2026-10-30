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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.invoice.Invoice;

/**
 * ETP-6060 — the plan-quota check of an invoice completion: it runs before the completion, only for
 * the {@code CO} action, answers a refusal as the process's {@link OBError} and hands the 403 body
 * to {@link InvoiceCompletionService} through {@link InvoiceCompletionQuotaHook#takeRefusal}.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceCompletionQuotaHook
 */
class InvoiceCompletionQuotaHookTest {

  private final InvoiceCompletionQuotaHook hook = new InvoiceCompletionQuotaHook();
  private final Invoice invoice = mock(Invoice.class);
  private MockedStatic<NeoQuotaCreateCheck> check;

  @BeforeEach
  void setUp() {
    check = mockStatic(NeoQuotaCreateCheck.class);
  }

  @AfterEach
  void tearDown() {
    check.close();
    InvoiceCompletionQuotaHook.takeRefusal();
  }

  private static NeoQuotaCreateCheck.Refused refusal() throws Exception {
    JSONObject error = new JSONObject().put("message", "Quota exceeded: Facturas (2/2 facturas)")
        .put("code", "QUOTA_EXCEEDED");
    return new NeoQuotaCreateCheck.Refused(
        NeoResponse.error(403, new JSONObject().put("error", error)), null);
  }

  @Test
  void checksACompletionAsTheInvoiceWillStandCompleted() {
    assertNull(hook.preProcess(invoice, "CO"));

    check.verify(() -> NeoQuotaCreateCheck.requireAllowedStatusChange(invoice,
        Invoice.PROPERTY_DOCUMENTSTATUS, "CO"));
    assertNull(InvoiceCompletionQuotaHook.takeRefusal());
  }

  @Test
  void checksNothingForAnotherAction() {
    assertNull(hook.preProcess(invoice, "RE"));
    assertNull(hook.preProcess(invoice, "RC"));

    check.verify(() -> NeoQuotaCreateCheck.requireAllowedStatusChange(any(), anyString(),
        anyString()), never());
  }

  @Test
  void aRefusalStopsTheProcessAndIsHandedOverOnce() throws Exception {
    NeoQuotaCreateCheck.Refused refused = refusal();
    check.when(() -> NeoQuotaCreateCheck.requireAllowedStatusChange(invoice,
        Invoice.PROPERTY_DOCUMENTSTATUS, "CO")).thenThrow(refused);

    OBError error;
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      messages.when(() -> OBMessageUtils.messageBD("Error")).thenReturn("Error");
      error = hook.preProcess(invoice, "CO");
    }

    assertNotNull(error);
    assertEquals("Error", error.getType());
    assertEquals("Quota exceeded: Facturas (2/2 facturas)", error.getMessage());
    assertSame(refused, InvoiceCompletionQuotaHook.takeRefusal());
    assertNull(InvoiceCompletionQuotaHook.takeRefusal(), "taken once");
  }

  /** A backoffice completion nobody answers must not leave its refusal for the next one. */
  @Test
  void theNextProcessForgetsALeftOverRefusal() throws Exception {
    check.when(() -> NeoQuotaCreateCheck.requireAllowedStatusChange(any(), anyString(),
        anyString())).thenThrow(refusal());
    try (MockedStatic<OBMessageUtils> messages = mockStatic(OBMessageUtils.class)) {
      hook.preProcess(invoice, "CO");
    }

    hook.preProcess(invoice, "RE");

    assertNull(InvoiceCompletionQuotaHook.takeRefusal());
  }

  @Test
  void postProcessIsInert() {
    assertNull(hook.postProcess(invoice, "CO"));
  }
}
