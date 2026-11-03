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

package com.etendoerp.go.schemaforge.ocr;

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.A_UNKNOWN;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.D_FOUND;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.D_NONE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.D_UNKNOWN;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ADDRESS_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CHECK;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DUPLICATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_EXTRACTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_HEADER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE_RAW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_LINES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_LOCATION_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NAME;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NOTICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ORG_TAX_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_QUANTITY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_RECEIVER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TOTAL_AMOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TOTAL_SOURCE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UNIT_PRICE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VERSION;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NONE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.TOTAL_LINES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.TOTAL_PRINTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.VERSION;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.AddressState;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicate;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicates;

/**
 * Builds the {@code CURRENT_DATA} document of the invoice inbox from a sanitized extraction
 * (ETP-6048): vendor, lines, duplicate, receiver, date and total, all matched without asking anyone.
 * It reads through {@link OcrLookups} only, so it names no document type; the caller binds the
 * lookups of the type. The validation block is not written here ({@code InvoiceInboxValidator}).
 *
 * <p>A document addressed to another company ({@link ReceiverTaxIdCheck.Status#MISMATCH}) is not
 * matched at all: only what was read is kept, with the receiver block that explains why.</p>
 */
public final class InboxMatcher {

  private static final int TWO_DECIMALS = 2;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final OcrLookups lookups;

  /**
   * Creates the matcher.
   *
   * @param lookups the lookups of the document type
   */
  public InboxMatcher(OcrLookups lookups) {
    this.lookups = lookups;
  }

  /**
   * Builds the document.
   *
   * @param extraction the sanitized payload of the extraction
   * @return the inbox document, version {@value InboxDocument#VERSION}
   * @throws JSONException when an object cannot be built
   */
  public JSONObject build(JSONObject extraction) throws JSONException {
    List<String> notices = new ArrayList<>();
    JSONObject header = new JSONObject();
    JSONObject extracted = extractedVendor(extraction);
    ReceiverTaxIdCheck.Result receiver = checkReceiver(extraction);
    header.put(K_RECEIVER, receiverJson(receiver, notices));
    JSONArray lines = new JSONArray();
    if (receiver.getStatus() == ReceiverTaxIdCheck.Status.MISMATCH) {
      header.put(K_VENDOR, new JSONObject().put(K_STATE, S_NONE).put(K_EXTRACTED, extracted));
    } else {
      lines = matchBody(extraction, extracted, header, notices);
    }
    putHeaderScalars(header, extraction, lines);
    return new JSONObject().put(K_VERSION, VERSION).put(K_HEADER, header).put(K_LINES, lines)
        .put(K_NOTICES, new JSONArray(notices));
  }

  private JSONArray matchBody(JSONObject extraction, JSONObject extracted, JSONObject header,
      List<String> notices) throws JSONException {
    JSONObject vendor = new InboxVendorMatcher(lookups).match(OcrJson.str(extracted, K_NAME),
        OcrJson.str(extracted, K_TAX_ID));
    vendor.put(K_EXTRACTED, extracted);
    String locationId = addressOf(vendor, notices);
    header.put(K_VENDOR, vendor);
    String documentNo = OcrJson.str(extraction, "document_no");
    header.put(K_DUPLICATE, duplicate(vendor, documentNo, notices));
    String date = isoDate(extraction);
    return new InboxLineMatcher(lookups).review(extraction.optJSONArray(OcrDocType.LINES_KEY),
        locationId, date);
  }

  private JSONObject extractedVendor(JSONObject extraction) throws JSONException {
    JSONObject issuer = extraction.optJSONObject("issuer");
    String name = OcrJson.str(extraction, "vendor_name");
    String taxId = OcrJson.str(issuer, "tax_id_raw");
    return new JSONObject().put(K_NAME, OcrJson.filled(name) ? name : OcrJson.str(issuer, K_NAME))
        .put(K_TAX_ID, OcrJson.filled(taxId) ? taxId : OcrJson.str(extraction, "tax_id"))
        .put("address", OcrJson.str(extraction, "vendor_address"))
        .put("postalCode", OcrJson.str(extraction, "vendor_postal_code"))
        .put("city", OcrJson.str(extraction, "vendor_city"))
        .put("country", OcrJson.str(extraction, "vendor_country"))
        .put("email", OcrJson.str(extraction, "vendor_email"))
        .put("phone", OcrJson.str(extraction, "vendor_phone"));
  }

  /** Address of a linked vendor; writes its state into the vendor block and returns the location. */
  private String addressOf(JSONObject vendor, List<String> notices) throws JSONException {
    if (!S_LINKED.equals(vendor.optString(K_STATE))) {
      vendor.put(K_ADDRESS_STATE, A_UNKNOWN).put(K_LOCATION_ID, JSONObject.NULL);
      return null;
    }
    Address address = lookups.vendorAddress(vendor.getString(K_ID));
    vendor.put(K_ADDRESS_STATE, address.state().name());
    boolean present = address.state() == AddressState.PRESENT;
    vendor.put(K_LOCATION_ID, present ? address.locationId() : JSONObject.NULL);
    if (address.state() == AddressState.MISSING) {
      notices.add("vendor_address_missing");
    }
    return present ? address.locationId() : null;
  }

  private JSONObject duplicate(JSONObject vendor, String documentNo, List<String> notices)
      throws JSONException {
    JSONObject out = new JSONObject().put(K_INVOICES, new JSONArray());
    if (!S_LINKED.equals(vendor.optString(K_STATE)) || !OcrJson.filled(documentNo)) {
      return out.put(K_STATE, D_NONE);
    }
    Duplicates found = lookups.duplicates(vendor.getString(K_ID), documentNo);
    if (!found.known()) {
      notices.add("duplicate_check_unknown");
      return out.put(K_STATE, D_UNKNOWN);
    }
    JSONArray invoices = new JSONArray();
    for (Duplicate document : found.documents()) {
      invoices.put(new JSONObject().put(K_ID, document.id())
          .put(K_DOCUMENT_NO, document.documentNo() == null ? "" : document.documentNo()));
    }
    return out.put(K_STATE, invoices.length() > 0 ? D_FOUND : D_NONE).put(K_INVOICES, invoices);
  }

  private ReceiverTaxIdCheck.Result checkReceiver(JSONObject extraction) {
    String receiverId = OcrJson.str(extraction.optJSONObject("receiver"), "tax_id_raw");
    String org = ReceiverTaxIdCheck.clean(receiverId).isEmpty() ? null
        : lookups.organizationTaxId();
    return ReceiverTaxIdCheck.check(extraction, org);
  }

  private static JSONObject receiverJson(ReceiverTaxIdCheck.Result receiver, List<String> notices)
      throws JSONException {
    if (receiver.getStatus() == ReceiverTaxIdCheck.Status.ABSENT) {
      notices.add("receiver_tax_id_absent");
    } else if (receiver.getStatus() == ReceiverTaxIdCheck.Status.NO_ORG_TAX_ID) {
      notices.add("org_tax_id_missing");
    }
    return new JSONObject().put(K_CHECK, receiver.getStatus().code())
        .put(K_TAX_ID, receiver.getReceiverTaxId()).put(K_ORG_TAX_ID, receiver.getOrgTaxId());
  }

  private static String isoDate(JSONObject extraction) {
    String raw = OcrJson.str(extraction, "invoice_date");
    String iso = OcrJson.filled(raw) ? OcrJson.toIsoDate(raw) : "";
    try {
      return iso.isEmpty() ? null : java.time.LocalDate.parse(iso).toString();
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }

  private static void putHeaderScalars(JSONObject header, JSONObject extraction, JSONArray lines)
      throws JSONException {
    BigDecimal printed = OcrJson.decimal(extraction, "total_amount");
    boolean usePrinted = printed != null && printed.signum() > 0;
    BigDecimal total = usePrinted ? printed : totalOfLines(lines);
    String date = isoDate(extraction);
    header.put(K_DOCUMENT_NO, OcrJson.str(extraction, "document_no"))
        .put(K_INVOICE_DATE, date == null ? JSONObject.NULL : date)
        .put(K_INVOICE_DATE_RAW, OcrJson.str(extraction, "invoice_date"))
        .put(K_TOTAL_AMOUNT, InboxLineMatcher.number(total == null ? null
            : total.setScale(TWO_DECIMALS, RoundingMode.HALF_UP)))
        .put(K_TOTAL_SOURCE, usePrinted ? TOTAL_PRINTED : TOTAL_LINES);
    if (!header.has(K_DUPLICATE)) {
      header.put(K_DUPLICATE, new JSONObject().put(K_STATE, D_NONE)
          .put(K_INVOICES, new JSONArray()));
    }
  }

  /** Sum of quantity x price x (1 + rate/100) over the lines that carry quantity and price; null if none. */
  private static BigDecimal totalOfLines(JSONArray lines) {
    BigDecimal sum = null;
    for (int i = 0; i < lines.length(); i++) {
      JSONObject line = lines.optJSONObject(i);
      BigDecimal quantity = decimalOf(line, K_QUANTITY);
      BigDecimal price = decimalOf(line, K_UNIT_PRICE);
      if (quantity == null || price == null) {
        continue;
      }
      BigDecimal rate = decimalOf(line, K_TAX_RATE);
      BigDecimal factor = rate == null ? BigDecimal.ONE
          : BigDecimal.ONE.add(rate.divide(HUNDRED));
      BigDecimal amount = quantity.multiply(price).multiply(factor);
      sum = sum == null ? amount : sum.add(amount);
    }
    return sum;
  }

  private static BigDecimal decimalOf(JSONObject line, String key) {
    return OcrJson.decimal(line, key);
  }
}
