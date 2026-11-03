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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DUPLICATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_EXTRACTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_HEADER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NAME;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TOTAL_AMOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.MAX_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.data.IncomingDocument;

/**
 * Keeps the summary columns of an inbox row equal to its {@code CURRENT_DATA} (ETP-6048): the list
 * filters and sorts on columns, the review works on the document, so every save syncs one from the
 * other. The worker uses it once; the review panel's autosave will use it on every edit (ETP-6050).
 *
 * <p>{@code C_BPARTNER_ID} is only set for a {@code linked} vendor; {@code POREFERENCE} stays empty
 * when the number does not fit its column (the full value stays in the document).</p>
 */
public final class InvoiceInboxSummary {

  private static final int MAX_TAX_ID = 30;
  private static final int MAX_NAME = 255;

  private InvoiceInboxSummary() {
  }

  /**
   * Copies the document's header into the row's summary columns.
   *
   * @param document the {@code CURRENT_DATA} object
   * @param row      the inbox row
   */
  public static void syncFrom(JSONObject document, IncomingDocument row) {
    JSONObject header = document.optJSONObject(K_HEADER);
    if (header == null) {
      return;
    }
    JSONObject vendor = header.optJSONObject(K_VENDOR);
    JSONObject extracted = vendor == null ? null : vendor.optJSONObject(K_EXTRACTED);
    row.setExtractedName(limit(text(extracted, K_NAME), MAX_NAME));
    row.setExtractedTaxID(limit(text(extracted, K_TAX_ID), MAX_TAX_ID));
    row.setBusinessPartner(linkedVendor(vendor));
    String documentNo = text(header, K_DOCUMENT_NO);
    row.setOrderReference(documentNo.length() > MAX_DOCUMENT_NO ? null : emptyToNull(documentNo));
    row.setInvoiceDate(dateOf(text(header, K_INVOICE_DATE)));
    row.setGrandTotalAmount(header.isNull(K_TOTAL_AMOUNT) || !header.has(K_TOTAL_AMOUNT) ? null
        : BigDecimal.valueOf(header.optDouble(K_TOTAL_AMOUNT)));
    row.setDuplicateOfInvoice(firstDuplicate(header.optJSONObject(K_DUPLICATE)));
  }

  private static BusinessPartner linkedVendor(JSONObject vendor) {
    boolean linked = vendor != null && S_LINKED.equals(vendor.optString(K_STATE, ""));
    return linked ? OBDal.getInstance().get(BusinessPartner.class, vendor.optString(K_ID)) : null;
  }

  private static Invoice firstDuplicate(JSONObject duplicate) {
    JSONArray invoices = duplicate == null ? null : duplicate.optJSONArray(K_INVOICES);
    JSONObject first = invoices == null ? null : invoices.optJSONObject(0);
    return first == null ? null : OBDal.getInstance().get(Invoice.class, first.optString(K_ID));
  }

  private static Date dateOf(String iso) {
    try {
      return iso.isEmpty() ? null : Date.from(LocalDate.parse(iso).atStartOfDay(
          ZoneId.systemDefault()).toInstant());
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static String text(JSONObject object, String key) {
    Object value = object == null ? null : object.opt(key);
    return value instanceof String ? ((String) value).trim() : "";
  }

  private static String limit(String value, int max) {
    String cut = value.length() > max ? value.substring(0, max) : value;
    return emptyToNull(cut);
  }

  private static String emptyToNull(String value) {
    return StringUtils.isEmpty(value) ? null : value;
  }
}
