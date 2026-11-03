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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.data.IncomingDocument;

/**
 * The summary columns of an inbox row follow its document: the extracted name and tax id (capped to
 * their columns), the vendor link (only when linked), the number (empty when it does not fit), the
 * date, the total and the first duplicate. It never touches the status, the error code or the flags.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxSummary
 */
class InvoiceInboxSummaryTest {

  private static final String VENDOR = "vendor";
  private static final String HEADER = "header";
  private static final String STATE = "state";

  private MockedStatic<OBDal> dalStatic;
  private OBDal dal;
  private BusinessPartner partner;
  private Invoice invoice;
  private IncomingDocument row;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    partner = mock(BusinessPartner.class);
    invoice = mock(Invoice.class);
    when(dal.get(BusinessPartner.class, "V1")).thenReturn(partner);
    when(dal.get(Invoice.class, "INV1")).thenReturn(invoice);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    row = mock(IncomingDocument.class);
  }

  @AfterEach
  void tearDown() {
    dalStatic.close();
  }

  /** A document with a linked vendor, a date, a total and a number, the usual output of the worker. */
  private static JSONObject document() throws JSONException {
    return new JSONObject().put(HEADER, new JSONObject()
        .put(VENDOR, new JSONObject().put(STATE, "linked").put("id", "V1").put("extracted",
            new JSONObject().put("name", "  Acme S.L. ").put("taxId", "ESB81639719")))
        .put("documentNo", "F-2026/001").put("invoiceDate", "2026-10-30").put("totalAmount", 121.5)
        .put("duplicate", new JSONObject().put(STATE, "none").put("invoices", new JSONArray())));
  }

  private static JSONObject header(JSONObject document) throws JSONException {
    return document.getJSONObject(HEADER);
  }

  @Test
  @DisplayName("copies the header into the columns: name, tax id, vendor, number, date and total")
  void copiesTheHeader() throws Exception {
    InvoiceInboxSummary.syncFrom(document(), row);

    verify(row).setExtractedName("Acme S.L.");
    verify(row).setExtractedTaxID("ESB81639719");
    verify(row).setBusinessPartner(partner);
    verify(row).setOrderReference("F-2026/001");
    verify(row).setGrandTotalAmount(BigDecimal.valueOf(121.5));
    ArgumentCaptor<Date> date = ArgumentCaptor.forClass(Date.class);
    verify(row).setInvoiceDate(date.capture());
    assertEquals(LocalDate.of(2026, 10, 30), date.getValue().toInstant()
        .atZone(ZoneId.systemDefault()).toLocalDate());
    verify(row).setDuplicateOfInvoice(null);
  }

  @Test
  @DisplayName("the extracted tax id is cut to its 30-character column, the name to 255")
  void longTextsAreTruncated() throws Exception {
    JSONObject document = document();
    header(document).getJSONObject(VENDOR).put("extracted", new JSONObject()
        .put("name", "N".repeat(300)).put("taxId", "T".repeat(45)));

    InvoiceInboxSummary.syncFrom(document, row);

    verify(row).setExtractedTaxID("T".repeat(30));
    verify(row).setExtractedName("N".repeat(255));
  }

  @Test
  @DisplayName("an empty name or tax id leaves the column empty, not blank")
  void emptyTextsAreNull() throws Exception {
    JSONObject document = document();
    header(document).getJSONObject(VENDOR).put("extracted", new JSONObject()
        .put("name", "   ").put("taxId", JSONObject.NULL));

    InvoiceInboxSummary.syncFrom(document, row);

    verify(row).setExtractedName(null);
    verify(row).setExtractedTaxID(null);
  }

  @Test
  @DisplayName("the vendor column is set only for a linked vendor: ambiguous, new and none leave it empty")
  void onlyALinkedVendorIsStored() throws Exception {
    for (String state : new String[] { "ambiguous", "new", "none" }) {
      IncomingDocument other = mock(IncomingDocument.class);
      JSONObject document = document();
      header(document).getJSONObject(VENDOR).put(STATE, state);

      InvoiceInboxSummary.syncFrom(document, other);

      verify(other).setBusinessPartner(null);
    }
    IncomingDocument noVendor = mock(IncomingDocument.class);
    JSONObject document = document();
    header(document).remove(VENDOR);

    InvoiceInboxSummary.syncFrom(document, noVendor);

    verify(noVendor).setBusinessPartner(null);
    verify(noVendor).setExtractedName(null);
  }

  @Test
  @DisplayName("a number of up to 20 characters is stored; a longer one leaves the column empty")
  void theReferenceMustFitItsColumn() throws Exception {
    JSONObject fits = document();
    header(fits).put("documentNo", "A".repeat(20));
    InvoiceInboxSummary.syncFrom(fits, row);
    verify(row).setOrderReference("A".repeat(20));

    IncomingDocument tooLong = mock(IncomingDocument.class);
    JSONObject document = document();
    header(document).put("documentNo", "A".repeat(21));
    InvoiceInboxSummary.syncFrom(document, tooLong);
    verify(tooLong).setOrderReference(null);

    IncomingDocument blank = mock(IncomingDocument.class);
    JSONObject empty = document();
    header(empty).put("documentNo", "  ");
    InvoiceInboxSummary.syncFrom(empty, blank);
    verify(blank).setOrderReference(null);
  }

  @Test
  @DisplayName("a missing or malformed date, or a missing total, leaves the column empty")
  void missingDateAndTotalAreNull() throws Exception {
    for (Object date : new Object[] { JSONObject.NULL, "31/02/2026", "" }) {
      IncomingDocument other = mock(IncomingDocument.class);
      JSONObject document = document();
      header(document).put("invoiceDate", date).put("totalAmount", JSONObject.NULL);

      InvoiceInboxSummary.syncFrom(document, other);

      verify(other).setInvoiceDate(null);
      verify(other).setGrandTotalAmount(null);
    }
    IncomingDocument absent = mock(IncomingDocument.class);
    JSONObject document = document();
    header(document).remove("invoiceDate");
    header(document).remove("totalAmount");

    InvoiceInboxSummary.syncFrom(document, absent);

    verify(absent).setInvoiceDate(null);
    verify(absent).setGrandTotalAmount(null);
  }

  @Test
  @DisplayName("the duplicate column holds the first duplicate invoice")
  void theFirstDuplicateIsStored() throws Exception {
    JSONObject document = document();
    header(document).put("duplicate", new JSONObject().put(STATE, "found").put("invoices",
        new JSONArray().put(new JSONObject().put("id", "INV1")).put(new JSONObject().put("id", "INV2"))));

    InvoiceInboxSummary.syncFrom(document, row);

    verify(row).setDuplicateOfInvoice(invoice);
    verify(dal, never()).get(eq(Invoice.class), eq("INV2"));
  }

  @Test
  @DisplayName("a document without a header leaves the row exactly as it was")
  void noHeaderTouchesNothing() throws Exception {
    InvoiceInboxSummary.syncFrom(new JSONObject(), row);

    verifyNoInteractions(row);
  }

  @Test
  @DisplayName("syncing never writes the status, the error code or the adjusted and reviewed flags")
  void onlyTheSummaryColumnsAreWritten() throws Exception {
    InvoiceInboxSummary.syncFrom(document(), row);

    verify(row, never()).setStatus(any());
    verify(row, never()).setErrorCode(any());
    verify(row, never()).setAdjusted(any());
    verify(row, never()).setReviewed(any());
    verify(row, never()).setCurrentData(any());
  }
}
