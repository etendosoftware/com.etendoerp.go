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

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DOC_TAX_AMOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Stamps the rounding mode of its tax on every line of an inbox document (ETP-6050):
 * {@code tax.docTaxAmount} is {@code D} (tax on the sum of the nets per rate) or {@code L} (tax of
 * each line, summed), read from {@code C_Tax.DocTaxAmount} of the tax id, {@code D} when unknown.
 * {@link InvoiceInboxTotals} follows it so the inbox ends with the amounts the purchase invoice
 * will have. The worker, {@code apply} and the review's answer all stamp through here.
 */
public final class InboxTaxModes {

  private static final String DEFAULT_MODE = "D";

  private InboxTaxModes() {
  }

  /**
   * Stamps every line's {@code tax} block that carries an id.
   *
   * @param lines   the {@code lines} array of the document, may be {@code null}
   * @param lookups the lookups of the document type
   * @throws JSONException when a block cannot be written
   */
  public static void stamp(JSONArray lines, OcrLookups lookups) throws JSONException {
    Set<String> ids = new LinkedHashSet<>();
    for (int i = 0; lines != null && i < lines.length(); i++) {
      String id = taxId(lines.optJSONObject(i));
      if (id != null) {
        ids.add(id);
      }
    }
    if (ids.isEmpty()) {
      return;
    }
    Map<String, String> modes = lookups.taxDocAmountModes(ids);
    for (int i = 0; i < lines.length(); i++) {
      String id = taxId(lines.optJSONObject(i));
      if (id != null) {
        String mode = modes.get(id);
        lines.optJSONObject(i).getJSONObject(K_TAX).put(K_DOC_TAX_AMOUNT,
            "L".equals(mode) ? "L" : DEFAULT_MODE);
      }
    }
  }

  private static String taxId(JSONObject line) {
    JSONObject tax = line == null ? null : line.optJSONObject(K_TAX);
    String id = tax == null ? "" : OcrJson.str(tax, K_ID);
    return id.isEmpty() ? null : id;
  }
}
