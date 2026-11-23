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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceTax;

/**
 * Unit tests for {@link PurchaseInvoiceTaxExtension}, the {@code purchase-invoice/tax}
 * customization: automatic tax rows ({@code Recalculate = 'Y'}) cannot be edited or deleted,
 * manual rows can, a create is always a manual row, and on a Múltiple DUA import (F5/LC) every
 * tax row must carry DUA and Fecha DUA.
 *
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceTaxExtension
 */
public class PurchaseInvoiceTaxExtensionTest {

  private static final String INVOICE_ID = "pinv-tax";
  private static final String TAX_ID = "itax-1";

  private MockedStatic<OBDal> obDal;
  private MockedStatic<OBContext> obContext;

  private Invoice invoice;
  private InvoiceTax taxRow;

  private final PurchaseInvoiceTaxExtension extension = new PurchaseInvoiceTaxExtension();

  @Before
  public void setUp() {
    obDal = mockStatic(OBDal.class);
    obContext = mockStatic(OBContext.class);
    obContext.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
    obContext.when(OBContext::restorePreviousMode).thenAnswer(i -> null);
    OBDal dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);

    // A draft, unsent purchase invoice without Múltiple DUA, with one manual tax row.
    invoice = mock(Invoice.class);
    when(invoice.getDocumentStatus()).thenReturn("DR");
    when(invoice.isProcessed()).thenReturn(false);
    when(invoice.isAeatsiiIssent()).thenReturn(false);
    when(invoice.isAeatsiiMultiDua()).thenReturn(false);
    when(invoice.getAeatsiiClaveTipoFc()).thenReturn("F5");
    taxRow = mock(InvoiceTax.class);
    when(taxRow.getInvoice()).thenReturn(invoice);
    when(taxRow.isRecalculate()).thenReturn(false);
    when(dal.get(Invoice.class, INVOICE_ID)).thenReturn(invoice);
    when(dal.get(InvoiceTax.class, TAX_ID)).thenReturn(taxRow);
  }

  @After
  public void tearDown() {
    obContext.close();
    obDal.close();
  }

  private static NeoContext crud(String method, String recordId, JSONObject body) {
    return NeoContext.builder()
        .specName("purchase-invoice").entityName("tax")
        .httpMethod(method).endpointType(NeoEndpointType.CRUD)
        .recordId(recordId).requestBody(body).build();
  }

  private static JSONObject newRow() throws Exception {
    return new JSONObject().put(PurchaseInvoiceTaxExtension.FIELD_PARENT_ID, INVOICE_ID)
        .put(PurchaseInvoiceTaxExtension.FIELD_RECALCULATE, true);
  }

  private static void assertRefused(NeoResponse response, int status, String key)
      throws Exception {
    assertEquals(status, response.getHttpStatus());
    assertEquals(key, response.getBody().getJSONObject("error")
        .getJSONArray(NeoProcessService.MESSAGE_KEYS).getString(0));
  }

  // ── automatic rows are protected ─────────────────────────────────────────────────────────

  @Test
  public void updatingAnAutomaticTaxRowIsRefused() throws Exception {
    when(taxRow.isRecalculate()).thenReturn(true);

    assertRefused(extension.handle(crud("PATCH", TAX_ID, new JSONObject().put("taxAmount", 5))),
        409, PurchaseInvoiceTaxExtension.MSG_AUTOMATIC);
  }

  @Test
  public void deletingAnAutomaticTaxRowIsRefused() throws Exception {
    when(taxRow.isRecalculate()).thenReturn(true);

    assertRefused(extension.handle(crud("DELETE", TAX_ID, null)), 409,
        PurchaseInvoiceTaxExtension.MSG_AUTOMATIC);
  }

  // ── rows the caller cannot see ───────────────────────────────────────────────────────────

  @Test
  public void aRowTheCallerCannotSeeIsLeftToCrudWithoutAdminMode() throws Exception {
    when(OBDal.getInstance().get(InvoiceTax.class, TAX_ID)).thenReturn(null);

    assertNull(extension.handle(crud("PATCH", TAX_ID, new JSONObject().put("taxAmount", 5))));
    obContext.verify(() -> OBContext.setAdminMode(anyBoolean()), Mockito.never());
  }

  // ── manual rows are allowed ──────────────────────────────────────────────────────────────

  @Test
  public void updatingAManualTaxRowContinuesToCrud() throws Exception {
    assertNull(extension.handle(crud("PATCH", TAX_ID, new JSONObject().put("taxAmount", 5))));
  }

  @Test
  public void deletingAManualTaxRowContinuesToCrud() {
    assertNull(extension.handle(crud("DELETE", TAX_ID, null)));
  }

  @Test
  public void aCreateIsAlwaysAManualRow() throws Exception {
    JSONObject body = newRow();

    assertNull(extension.handle(crud("POST", null, body)));
    assertFalse(body.getBoolean(PurchaseInvoiceTaxExtension.FIELD_RECALCULATE));
  }

  @Test
  public void anyWriteOnACompletedInvoiceIsRefused() throws Exception {
    when(invoice.getDocumentStatus()).thenReturn("CO");

    assertRefused(extension.handle(crud("POST", null, newRow())), 409,
        PurchaseInvoiceTaxExtension.MSG_LOCKED);
  }

  // ── Múltiple DUA: each row needs DUA and Fecha DUA ───────────────────────────────────────

  @Test
  public void aMultiDuaImportRowWithoutDuaIsRefused() throws Exception {
    when(invoice.isAeatsiiMultiDua()).thenReturn(true);
    JSONObject body = newRow().put(PurchaseInvoiceTaxExtension.FIELD_DUA_DATE, "2026-11-01");

    assertRefused(extension.handle(crud("POST", null, body)), 400,
        PurchaseInvoiceTaxExtension.MSG_DUA_REQUIRED);
  }

  @Test
  public void aMultiDuaLcRowClearingItsStoredDuaDateIsRefused() throws Exception {
    when(invoice.isAeatsiiMultiDua()).thenReturn(true);
    when(invoice.getAeatsiiClaveTipoFc()).thenReturn("LC");
    when(taxRow.getAeatsiiDua()).thenReturn("26ES00281234567890");
    when(taxRow.getAeatsiiFechaDua()).thenReturn(new Date());
    JSONObject body = new JSONObject()
        .put(PurchaseInvoiceTaxExtension.FIELD_DUA_DATE, JSONObject.NULL);

    assertRefused(extension.handle(crud("PATCH", TAX_ID, body)), 400,
        PurchaseInvoiceTaxExtension.MSG_DUA_REQUIRED);
  }

  @Test
  public void aMultiDuaImportRowWithDuaAndDateContinuesToCrud() throws Exception {
    when(invoice.isAeatsiiMultiDua()).thenReturn(true);
    JSONObject body = newRow().put(PurchaseInvoiceTaxExtension.FIELD_DUA, "26ES00281234567890")
        .put(PurchaseInvoiceTaxExtension.FIELD_DUA_DATE, "2026-11-01");

    assertNull(extension.handle(crud("POST", null, body)));
  }

  @Test
  public void aMultiDuaRowKeepingItsStoredDuaContinuesToCrud() throws Exception {
    when(invoice.isAeatsiiMultiDua()).thenReturn(true);
    when(taxRow.getAeatsiiDua()).thenReturn("26ES00281234567890");
    when(taxRow.getAeatsiiFechaDua()).thenReturn(new Date());

    assertNull(extension.handle(crud("PATCH", TAX_ID, new JSONObject().put("taxAmount", 5))));
  }

  @Test
  public void multiDuaOnANonImportInvoiceDoesNotRequireLineDua() throws Exception {
    when(invoice.isAeatsiiMultiDua()).thenReturn(true);
    when(invoice.getAeatsiiClaveTipoFc()).thenReturn("F1");

    assertNull(extension.handle(crud("POST", null, newRow())));
  }
}
