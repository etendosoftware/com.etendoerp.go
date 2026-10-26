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

import java.math.BigDecimal;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The {@code etendo_batch} the review proposes (ETP-5715), shaped like the batch the application
 * builds after its review steps: one header operation and one line operation per line, the lines
 * hanging from the header through {@code parentRef}.
 *
 * <p>It is a template, not something to send as it is. Text read from the document is never copied
 * into it: where the application would send a read value the template names the place to take it from
 * ({@code {documentData.lines[0].description}}), and where a decision is still open it names the
 * decision. Only identifiers the server itself resolved and the numbers are literal. The header's
 * supplier reference and date appear only as optional entries: they are sent only when the user
 * accepted them — the application's toggles, which the MCP respects.</p>
 */
final class OcrBatchTemplate {

  private static final String KEY_BODY = "body";
  private static final String ADDRESS_PLACEHOLDER =
      "{the vendor's address: call again with vendor_id once the user chose the vendor}";
  private static final String HEADER_ID = "inv";

  private OcrBatchTemplate() {
  }

  /**
   * The header operation.
   *
   * @param spec        the spec the document is created in
   * @param vendorId    the vendor's id when decided, {@code null} while the decision is open
   * @param locationId  the vendor's address when known, {@code null} otherwise
   * @return the operation with its optional entries listed under {@code optionalBody}
   * @throws JSONException when the object cannot be built
   */
  static JSONObject header(String spec, String vendorId, String locationId) throws JSONException {
    JSONObject body = new JSONObject()
        .put("businessPartner", vendorId != null ? vendorId : "{decision vendor}")
        .put("partnerAddress", locationId != null ? locationId : ADDRESS_PLACEHOLDER);
    JSONObject optional = new JSONObject()
        .put("orderReference", "{documentData.documentNo} — only if apply_documentNo was accepted")
        .put("invoiceDate", "{documentData.invoiceDateIso} — only if apply_invoiceDate was accepted");
    return new JSONObject().put("id", HEADER_ID).put("spec", spec).put("entity", "header")
        .put(KEY_BODY, body).put("optionalBody", optional);
  }

  /**
   * The operation of one line.
   *
   * @param spec      the spec
   * @param index     the line's position, from 0
   * @param product   what to send as the product: the product id when matched, else the decision
   * @param numbers   quantity and unit price as read, either may be {@code null}
   * @param taxId     the tax the server resolved, {@code null} to omit it
   * @return the operation
   * @throws JSONException when the object cannot be built
   */
  static JSONObject line(String spec, int index, String product, BigDecimal[] numbers,
      String taxId) throws JSONException {
    JSONObject body = new JSONObject().put("product", product)
        .put("description", "{documentData.lines[" + index + "].description}");
    if (numbers[0] != null) {
      body.put("invoicedQuantity", numbers[0]);
    }
    if (numbers[1] != null) {
      body.put("unitPrice", numbers[1]).put("listPrice", numbers[1]);
    }
    if (taxId != null) {
      body.put("tax", taxId);
    }
    return new JSONObject().put("id", "ln" + index).put("spec", spec).put("entity", "lines")
        .put("parentRef", HEADER_ID).put(KEY_BODY, body);
  }

  /**
   * Wraps the operations.
   *
   * @param header the header operation
   * @param lines  the line operations
   * @return {@code {tool, operations, notes}}
   * @throws JSONException when the object cannot be built
   */
  static JSONObject wrap(JSONObject header, List<JSONObject> lines) throws JSONException {
    JSONArray operations = new JSONArray().put(header);
    lines.forEach(operations::put);
    return new JSONObject().put("tool", "etendo_batch").put("operations", operations)
        .put("notes", "A template: replace every {placeholder} with the user's decision or the "
            + "value in documentData, drop lines the user skips, drop optionalBody entries the user "
            + "did not accept, and merge the rest of optionalBody into body. Send it with "
            + "etendo_batch only after the user confirmed.");
  }
}
