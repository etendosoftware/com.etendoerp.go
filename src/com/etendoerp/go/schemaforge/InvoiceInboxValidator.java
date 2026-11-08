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

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CHECK;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DESCRIPTION;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_EXTRACTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_HEADER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INVOICE_DATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_LINES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_MISSING;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NAME;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_PRODUCT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_QUANTITY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_RECEIVER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TOTAL_AMOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UNIT_PRICE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VALID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.MAX_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NEW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.T_PENDING_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.T_RESOLVED;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.ocr.ReceiverTaxIdCheck;

/**
 * The mandatory-data rules of an inbox document (ETP-6048). Pure: it reads the {@code CURRENT_DATA}
 * object and answers, with no database and no side effect, so the worker and the review panel's
 * every edit (ETP-6050) apply the very same rules.
 *
 * <p>It answers with codes only: {@code vendor}, {@code invoiceDate}, {@code amount}, {@code lines},
 * {@code line[i].tax}, {@code line[i].product} (and {@code receiver} for a foreign receiver). The
 * error code follows the priority {@code FOREIGN_RECEIVER > REFERENCE_TOO_LONG > MISSING_FIELDS}.</p>
 */
public final class InvoiceInboxValidator {

  /** Error code: the document is addressed to another company. */
  public static final String FOREIGN_RECEIVER = "FOREIGN_RECEIVER";
  /** Error code: the document number does not fit the target column. */
  public static final String REFERENCE_TOO_LONG = "REFERENCE_TOO_LONG";
  /** Error code: some mandatory data is missing. */
  public static final String MISSING_FIELDS = "MISSING_FIELDS";

  private InvoiceInboxValidator() {
  }

  /** The verdict: whether the document is complete, what is missing and the error code. */
  public static final class Result {
    private final List<String> missing;
    private final String errorCode;

    Result(List<String> missing, String errorCode) {
      this.missing = missing;
      this.errorCode = errorCode;
    }

    /** Returns whether nothing is missing and no error applies. */
    public boolean isValid() {
      return errorCode == null;
    }

    /** Returns the codes of what is missing. */
    public List<String> getMissing() {
      return missing;
    }

    /** Returns the error code, {@code null} when valid. */
    public String getErrorCode() {
      return errorCode;
    }

    /**
     * The {@code validation} block of the document.
     *
     * @return {@code {valid, missing[]}}
     * @throws JSONException when the object cannot be built
     */
    public JSONObject toJson() throws JSONException {
      return new JSONObject().put(K_VALID, isValid()).put(K_MISSING, new JSONArray(missing));
    }
  }

  /**
   * Validates a document.
   *
   * @param document the {@code CURRENT_DATA} object
   * @return the verdict
   */
  public static Result validate(JSONObject document) {
    JSONObject header = document == null ? null : document.optJSONObject(K_HEADER);
    if (header == null) {
      return new Result(List.of(K_HEADER), MISSING_FIELDS);
    }
    if (isForeignReceiver(header)) {
      return new Result(List.of(K_RECEIVER), FOREIGN_RECEIVER);
    }
    List<String> missing = new ArrayList<>();
    checkHeader(header, missing);
    checkLines(document.optJSONArray(K_LINES), missing);
    if (header.optString(K_DOCUMENT_NO, "").trim().length() > MAX_DOCUMENT_NO) {
      return new Result(missing, REFERENCE_TOO_LONG);
    }
    return new Result(missing, missing.isEmpty() ? null : MISSING_FIELDS);
  }

  private static boolean isForeignReceiver(JSONObject header) {
    JSONObject receiver = header.optJSONObject(K_RECEIVER);
    return receiver != null && ReceiverTaxIdCheck.Status.MISMATCH.code()
        .equals(receiver.optString(K_CHECK, ""));
  }

  private static void checkHeader(JSONObject header, List<String> missing) {
    if (!vendorComplete(header.optJSONObject(K_VENDOR))) {
      missing.add("vendor");
    }
    if (StringUtils.isBlank(stringOf(header, K_INVOICE_DATE))) {
      missing.add("invoiceDate");
    }
    if (header.optDouble(K_TOTAL_AMOUNT, 0d) <= 0d) {
      missing.add("amount");
    }
  }

  /** Linked, or new with a name and a tax id (what the vendor needs to be created). */
  private static boolean vendorComplete(JSONObject vendor) {
    if (vendor == null) {
      return false;
    }
    String state = vendor.optString(K_STATE, "");
    if (S_LINKED.equals(state)) {
      return true;
    }
    JSONObject extracted = vendor.optJSONObject(K_EXTRACTED);
    return S_NEW.equals(state) && StringUtils.isNotBlank(stringOf(extracted, K_NAME))
        && StringUtils.isNotBlank(stringOf(extracted, K_TAX_ID));
  }

  private static void checkLines(JSONArray lines, List<String> missing) {
    if (lines == null || lines.length() == 0) {
      missing.add("lines");
      return;
    }
    for (int i = 0; i < lines.length(); i++) {
      JSONObject line = lines.optJSONObject(i);
      if (line == null || !taxComplete(line)) {
        missing.add("line[" + i + "].tax");
      }
      if (line == null || !productComplete(line)) {
        missing.add("line[" + i + "].product");
      }
    }
  }

  /** Resolved, or pending the vendor's address while the line prints a rate. */
  private static boolean taxComplete(JSONObject line) {
    JSONObject tax = line.optJSONObject(K_TAX);
    String state = tax == null ? "" : tax.optString(K_STATE, "");
    return T_RESOLVED.equals(state)
        || (T_PENDING_VENDOR.equals(state) && !line.isNull(K_TAX_RATE) && line.has(K_TAX_RATE));
  }

  /** Linked, or new with a description, a quantity above zero and a price of zero or more. */
  private static boolean productComplete(JSONObject line) {
    JSONObject product = line.optJSONObject(K_PRODUCT);
    String state = product == null ? "" : product.optString(K_STATE, "");
    if (S_LINKED.equals(state)) {
      return true;
    }
    return S_NEW.equals(state) && StringUtils.isNotBlank(stringOf(line, K_DESCRIPTION))
        && !line.isNull(K_QUANTITY) && line.optDouble(K_QUANTITY, 0d) > 0d
        && !line.isNull(K_UNIT_PRICE) && line.optDouble(K_UNIT_PRICE, -1d) >= 0d;
  }

  private static String stringOf(JSONObject object, String key) {
    Object value = object == null ? null : object.opt(key);
    return value instanceof String ? (String) value : "";
  }
}
