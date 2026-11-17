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

import static com.etendoerp.go.schemaforge.InvoiceInboxApplyValues.storedNumber;
import static com.etendoerp.go.schemaforge.InvoiceInboxApplyValues.text;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.A_PRESENT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.D_FOUND;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.D_NONE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.D_UNKNOWN;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ADDRESS_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CURRENCY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DUPLICATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INBOX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INDEX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_LOCATION_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NOTICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TOTAL_AMOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TOTAL_SOURCE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.N_DUPLICATE_CHECK_UNKNOWN;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.N_DUPLICATE_IN_INBOX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.N_VENDOR_ADDRESS_MISSING;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.TOTAL_PRINTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.T_RESOLVED;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.ocr.InboxLineMatcher;
import com.etendoerp.go.schemaforge.ocr.InboxTaxModes;
import com.etendoerp.go.schemaforge.ocr.InvoiceInboxTotals;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicates;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.TaxAnswer;

/**
 * What {@link InvoiceInboxApply} recomputes after the operations (ETP-6050), in one transaction and
 * in this order: the taxes that depend on what changed, their rounding mode, the duplicates, the
 * notices, the currency and the total. Stateless.
 */
final class InvoiceInboxApplyRecompute {

  private InvoiceInboxApplyRecompute() {
  }

  static void recompute(InvoiceInboxApply.Edit edit) throws JSONException {
    rebuildTaxes(edit);
    if (edit.lookups != null) {
      InboxTaxModes.stamp(edit.lines(), edit.lookups);
    }
    if (edit.docNoChanged || edit.vendorChanged) {
      recheckDuplicates(edit);
    }
    if (edit.vendorChanged) {
      JSONObject vendor = edit.vendor();
      setNotice(edit, N_VENDOR_ADDRESS_MISSING, S_LINKED.equals(vendor.optString(K_STATE))
          && OcrLookups.AddressState.MISSING.name().equals(vendor.optString(K_ADDRESS_STATE)));
    }
    refreshTotal(edit, ensureCurrency(edit));
  }

  /**
   * The document currency: derived from the vendor (its purchase price list, else the
   * organization's) and stored in the header when the document has none yet or the vendor changed,
   * kept otherwise. Returns the decimals of its amounts, two when it cannot be known. The exchange
   * rate and the currency picker of the invoice are not touched: this is the scale of the inbox's
   * own amounts.
   */
  private static int ensureCurrency(InvoiceInboxApply.Edit edit) throws JSONException {
    if (edit.lookups == null) {
      return InvoiceInboxTotals.AMOUNT_SCALE;
    }
    JSONObject header = edit.header();
    String stored = text(header, K_CURRENCY_ID);
    if (stored.isEmpty() || edit.vendorChanged) {
      JSONObject vendor = edit.vendor();
      String vendorId = S_LINKED.equals(vendor.optString(K_STATE)) ? vendor.optString(K_ID) : null;
      Optional<OcrLookups.DocCurrency> derived = edit.lookups.documentCurrency(vendorId,
          edit.row.getOrganization() == null ? null : edit.row.getOrganization().getId());
      if (derived.isPresent()) {
        header.put(K_CURRENCY_ID, derived.get().id());
        return derived.get().scale();
      }
      header.remove(K_CURRENCY_ID);
      return InvoiceInboxTotals.AMOUNT_SCALE;
    }
    return edit.lookups.currencyById(stored).map(OcrLookups.DocCurrency::scale)
        .orElse(InvoiceInboxTotals.AMOUNT_SCALE);
  }

  /** The tax of the lines that depend on what changed, once per distinct rate. */
  private static void rebuildTaxes(InvoiceInboxApply.Edit edit) throws JSONException {
    if (edit.lookups == null) {
      return;
    }
    boolean all = edit.vendorChanged || edit.dateChanged;
    String location = locationOf(edit.vendor());
    String date = text(edit.header(), K_INVOICE_DATE);
    Map<String, TaxAnswer> cache = new HashMap<>();
    JSONArray lines = edit.lines();
    for (int i = 0; i < lines.length(); i++) {
      JSONObject line = lines.optJSONObject(i);
      if (line != null && needsRebuild(edit, line, all, date)) {
        rebuildTax(edit, line, cache, location, date);
      }
    }
  }

  /** Whether the line's tax depends on what changed (and is not a still-valid chosen one). */
  private static boolean needsRebuild(InvoiceInboxApply.Edit edit, JSONObject line, boolean all,
      String date) {
    if (!(all || edit.taxLines.contains(line.optInt(K_INDEX, -1)))) {
      return false;
    }
    return !(InvoiceInboxApply.isChosenTax(line) && stillValid(edit, line, date));
  }

  private static void rebuildTax(InvoiceInboxApply.Edit edit, JSONObject line,
      Map<String, TaxAnswer> cache, String location, String date) throws JSONException {
    BigDecimal rate = storedNumber(line, K_TAX_RATE, InvoiceInboxApply.RATE_SCALE);
    JSONObject tax = InboxLineMatcher.taxState(edit.lookups, cache, rate, location,
        date.isEmpty() ? null : date);
    line.put(K_TAX, tax);
    boolean inherits = edit.inheritRate.contains(line.optInt(K_INDEX, -1));
    if (inherits && rate == null && T_RESOLVED.equals(tax.optString(K_STATE))
        && !tax.isNull(K_RATE)) {
      line.put(K_TAX_RATE, tax.get(K_RATE));
    }
  }

  /** A tax the user chose survives a vendor or date change while it still passes the tax rule. */
  private static boolean stillValid(InvoiceInboxApply.Edit edit, JSONObject line, String date) {
    return edit.lookups.taxById(line.optJSONObject(K_TAX).optString(K_ID),
        date.isEmpty() ? null : date).isPresent();
  }

  /** The vendor's address when it is linked and has one, else {@code null} (taxes stay pending). */
  private static String locationOf(JSONObject vendor) {
    boolean usable = S_LINKED.equals(vendor.optString(K_STATE))
        && A_PRESENT.equals(vendor.optString(K_ADDRESS_STATE)) && !vendor.isNull(K_LOCATION_ID);
    return usable ? StringUtils.defaultIfBlank(vendor.optString(K_LOCATION_ID), null) : null;
  }

  /** The duplicate block and its notices, asked again because the number or the vendor changed. */
  private static void recheckDuplicates(InvoiceInboxApply.Edit edit) throws JSONException {
    JSONObject header = edit.header();
    JSONObject vendor = edit.vendor();
    String documentNo = text(header, K_DOCUMENT_NO);
    JSONObject duplicate = new JSONObject().put(K_STATE, D_NONE).put(K_INVOICES, new JSONArray());
    boolean unknown = false;
    if (edit.lookups != null && S_LINKED.equals(vendor.optString(K_STATE))
        && !documentNo.isEmpty()) {
      Duplicates found = edit.lookups.duplicates(vendor.optString(K_ID), documentNo);
      if (!found.known()) {
        unknown = true;
        duplicate.put(K_STATE, D_UNKNOWN);
      } else {
        JSONArray invoices = new JSONArray();
        for (OcrLookups.Duplicate document : found.documents()) {
          invoices.put(new JSONObject().put(K_ID, document.id()).put(K_DOCUMENT_NO,
              document.documentNo() == null ? "" : document.documentNo()));
        }
        duplicate.put(K_STATE, invoices.length() > 0 ? D_FOUND : D_NONE).put(K_INVOICES, invoices);
      }
    }
    header.put(K_DUPLICATE, duplicate);
    JSONArray inbox = InvoiceInboxDuplicates.find(edit.row, header,
        java.util.Arrays.asList(OBContext.getOBContext().getReadableOrganizations()));
    duplicate.put(K_INBOX, inbox);
    setNotice(edit, N_DUPLICATE_CHECK_UNKNOWN, unknown);
    setNotice(edit, N_DUPLICATE_IN_INBOX, inbox.length() > 0);
  }

  /** The total follows the lines unless the document printed one (which never changes). */
  private static void refreshTotal(InvoiceInboxApply.Edit edit, int scale) throws JSONException {
    JSONObject header = edit.header();
    if (TOTAL_PRINTED.equals(header.optString(K_TOTAL_SOURCE))) {
      return;
    }
    InvoiceInboxTotals.Totals totals = InvoiceInboxTotals.totals(edit.lines(), scale);
    header.put(K_TOTAL_AMOUNT, totals == null ? 0 : totals.total().doubleValue());
  }

  private static void setNotice(InvoiceInboxApply.Edit edit, String code, boolean present)
      throws JSONException {
    JSONArray notices = edit.document.optJSONArray(K_NOTICES);
    JSONArray out = new JSONArray();
    boolean had = false;
    for (int i = 0; notices != null && i < notices.length(); i++) {
      if (code.equals(notices.optString(i))) {
        had = true;
        if (!present) {
          continue;
        }
      }
      out.put(notices.get(i));
    }
    if (present && !had) {
      out.put(code);
    }
    edit.document.put(K_NOTICES, out);
  }
}
