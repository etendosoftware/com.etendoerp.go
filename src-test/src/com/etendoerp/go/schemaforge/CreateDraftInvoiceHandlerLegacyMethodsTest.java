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

import static org.junit.Assert.assertFalse;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

/**
 * Regression guard for ETP-4015.
 *
 * <p>The legacy discount-materialisation methods on {@link CreateDraftInvoiceHandler}
 * inserted a duplicate {@code c_invoicetax} row (with {@code recalculate='N'} and a
 * non-zero millisecond timestamp) alongside the one the DB trigger chain
 * ({@code c_invoiceline_trg2 → c_invoicelinetax_trg}) already maintains. The duplicate
 * row corrupted the invoice totals breakdown on the frontend.
 *
 * <p>The fix moved the discount materialisation into
 * {@link InvoiceFromOrderSupport#applyOrderDiscountToInvoice(org.openbravo.model.common.invoice.Invoice, String, TotalDiscountService)},
 * which copies the percentage via JDBC and delegates the line materialisation to
 * {@link TotalDiscountService#recalculate(String, boolean)}. The legacy methods on
 * {@code CreateDraftInvoiceHandler} were deleted and must NOT be re-introduced.
 *
 * <p>This cheap reflection test fails fast if anyone re-adds them.
 */
public class CreateDraftInvoiceHandlerLegacyMethodsTest {

  private static final String[] FORBIDDEN_METHODS = {
      "applyTotalDiscountIfPresent",
      "updateInvoiceTaxAggregates",
      "resolveMissingInvoiceTax",
      "readNetByTaxFromInvoiceLines",
  };

  @Test
  public void testLegacyDiscountMethodsAreNotReintroduced() {
    Method[] declared = CreateDraftInvoiceHandler.class.getDeclaredMethods();
    for (String forbidden : FORBIDDEN_METHODS) {
      boolean present = Arrays.stream(declared)
          .anyMatch(m -> m.getName().equals(forbidden));
      assertFalse(
          "Legacy method " + forbidden + " must not be re-introduced on "
              + "CreateDraftInvoiceHandler; the discount materialisation lives in "
              + "InvoiceFromOrderSupport.applyOrderDiscountToInvoice. "
              + "See ETP-4015 for context.",
          present);
    }
  }
}
