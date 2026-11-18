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

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CURRENCY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DUPLICATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_EXTRACTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_HEADER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_LINES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NAME;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_PRODUCT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TOTAL_AMOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TOTAL_SOURCE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.MAX_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NEW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.TOTAL_PRINTED;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.InvoiceInboxTotals;

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
    Currency currency = InvoiceInboxLiveChecks.documentCurrency(document, row);
    row.setGrandTotalAmount(listTotal(document, header, currency));
    row.setDuplicateOfInvoice(firstDuplicate(header.optJSONObject(K_DUPLICATE)));
    row.setCurrency(currency);
    row.setTotalMismatch(totalMismatch(document, currency));
    row.setNewProducts(newProducts(document.optJSONArray(K_LINES)));
  }

  /**
   * The list total of a pending row: THE LINES ALWAYS WIN (owner decision 2026-11-18). It is the
   * total of the lines computed by the shared model ({@link InvoiceInboxTotals}) at the decimals of
   * the document currency, i.e. what confirming would create, whatever the {@code totalSource}.
   * Only a document with no usable line falls back to the header's {@code totalAmount} (the printed
   * one, or {@code null}). {@code header.totalAmount} itself is never changed here.
   */
  private static BigDecimal listTotal(JSONObject document, JSONObject header, Currency currency) {
    int scale = currency == null || currency.getStandardPrecision() == null ? 2
        : currency.getStandardPrecision().intValue();
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(document.optJSONArray(K_LINES),
        scale);
    if (totals != null && totals.total() != null) {
      return totals.total().setScale(scale, RoundingMode.HALF_UP);
    }
    return header.isNull(K_TOTAL_AMOUNT) || !header.has(K_TOTAL_AMOUNT) ? null
        : BigDecimal.valueOf(header.optDouble(K_TOTAL_AMOUNT));
  }

  /**
   * The non-blocking "total does not match" warning (decision 4), the rule of the SPA banner
   * ({@code totalMismatch} of {@code invoiceInboxReviewModel.js}) at the decimals of the currency: only
   * when the total of the document is the PRINTED one and it differs from the total the model computes
   * from the lines ({@link InvoiceInboxTotals}) by MORE than one minimal unit of the currency (a cent
   * at scale 2, a whole unit at scale 0), both compared at that scale. Never blocks anything.
   *
   * @param document the {@code CURRENT_DATA} object
   * @param currency the document currency, {@code null} for two decimals
   * @return {@code true} when the printed total and the lines total diverge
   */
  static boolean totalMismatch(JSONObject document, Currency currency) {
    JSONObject header = document == null ? null : document.optJSONObject(K_HEADER);
    // A missing, null, non-numeric or non-finite printed total never warns (the SPA's
    // Number(null) = 0 can: the intended rule is "no printed total, nothing to compare").
    if (header == null || !TOTAL_PRINTED.equals(header.optString(K_TOTAL_SOURCE, ""))
        || !(header.opt(K_TOTAL_AMOUNT) instanceof Number)
        || !Double.isFinite(((Number) header.opt(K_TOTAL_AMOUNT)).doubleValue())) {
      return false;
    }
    int scale = currency == null || currency.getStandardPrecision() == null ? 2
        : currency.getStandardPrecision().intValue();
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(document.optJSONArray(K_LINES),
        scale);
    BigDecimal lines = totals == null ? BigDecimal.ZERO.setScale(scale) : totals.total();
    BigDecimal printed = new BigDecimal(header.opt(K_TOTAL_AMOUNT).toString()).setScale(scale,
        RoundingMode.HALF_UP);
    BigDecimal oneUnit = BigDecimal.ONE.movePointLeft(scale);
    return printed.subtract(lines.setScale(scale, RoundingMode.HALF_UP)).abs()
        .compareTo(oneUnit) > 0;
  }

  /** The number of lines whose product does not exist yet (ETP-6051): the lines of state {@code new}. */
  static long newProducts(JSONArray lines) {
    long count = 0;
    for (int i = 0; lines != null && i < lines.length(); i++) {
      JSONObject line = lines.optJSONObject(i);
      JSONObject product = line == null ? null : line.optJSONObject(K_PRODUCT);
      if (product != null && S_NEW.equals(product.optString(K_STATE, ""))) {
        count++;
      }
    }
    return count;
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
