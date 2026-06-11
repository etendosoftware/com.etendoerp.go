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
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.common.invoice.Invoice;

/**
 * Unit tests for {@link InvoiceCompletionRateHook}.
 *
 * <p>Verifies the preProcess gating on the {@code CO} action, delegation to
 * {@link InvoiceExchangeRateValidator}, the OBError shape on block, and that postProcess is inert.
 */
public class InvoiceCompletionRateHookTest {

  @Test
  public void testPreProcessNonCompleteActionReturnsNullWithoutValidating() {
    InvoiceCompletionRateHook hook = new InvoiceCompletionRateHook();
    try (MockedStatic<InvoiceExchangeRateValidator> validator =
        Mockito.mockStatic(InvoiceExchangeRateValidator.class)) {
      assertNull(hook.preProcess(mock(Invoice.class), "RE"));
      validator.verify(() -> InvoiceExchangeRateValidator.checkRateForCompletion(any()), never());
    }
  }

  @Test
  public void testPreProcessCompleteActionNoErrorReturnsNull() {
    InvoiceCompletionRateHook hook = new InvoiceCompletionRateHook();
    Invoice invoice = mock(Invoice.class);
    try (MockedStatic<InvoiceExchangeRateValidator> validator =
        Mockito.mockStatic(InvoiceExchangeRateValidator.class)) {
      validator.when(() -> InvoiceExchangeRateValidator.checkRateForCompletion(invoice))
          .thenReturn(null);

      assertNull(hook.preProcess(invoice, "CO"));
    }
  }

  @Test
  public void testPreProcessCompleteActionWithErrorReturnsOBError() {
    InvoiceCompletionRateHook hook = new InvoiceCompletionRateHook();
    Invoice invoice = mock(Invoice.class);
    try (MockedStatic<InvoiceExchangeRateValidator> validator =
        Mockito.mockStatic(InvoiceExchangeRateValidator.class);
        MockedStatic<OBMessageUtils> msgUtils = Mockito.mockStatic(OBMessageUtils.class)) {
      validator.when(() -> InvoiceExchangeRateValidator.checkRateForCompletion(invoice))
          .thenReturn("No rate USD → EUR");
      msgUtils.when(() -> OBMessageUtils.messageBD("Error")).thenReturn("Error");

      OBError error = hook.preProcess(invoice, "CO");

      assertNotNull(error);
      assertEquals("Error", error.getType());
      assertEquals("Error", error.getTitle());
      assertEquals("No rate USD → EUR", error.getMessage());
    }
  }

  @Test
  public void testPostProcessAlwaysReturnsNull() {
    InvoiceCompletionRateHook hook = new InvoiceCompletionRateHook();
    assertNull(hook.postProcess(mock(Invoice.class), "CO"));
    assertNull(hook.postProcess(mock(Invoice.class), "RE"));
  }
}
