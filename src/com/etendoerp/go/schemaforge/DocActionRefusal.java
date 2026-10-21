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

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * ETP-5783 — the answer to a document process refused by the document's own rules:
 * {@code 422 business_rule}, not 400. The request was well formed (a malformed one keeps 400/404);
 * what failed is the document's state, which the caller has to fix. The body keeps the flat
 * {@code status}/{@code message}/{@code messageKeys} the SPA has always read and adds the nested
 * {@code error} envelope every other NEO refusal uses, so a client reads either.
 */
final class DocActionRefusal {

  static final int STATUS = 422;
  static final String CODE_BUSINESS_RULE = "business_rule";

  private DocActionRefusal() {
  }

  /**
   * @param flatBody the flat error body ({@code status}, {@code message}, optional
   *                 {@code messageKeys}); gets the nested envelope added
   * @return the 422 response
   * @throws JSONException if the body cannot be written
   */
  static NeoResponse businessRule(JSONObject flatBody) throws JSONException {
    JSONObject error = new JSONObject();
    error.put(NeoProcessService.MESSAGE, flatBody.optString(NeoProcessService.MESSAGE));
    error.put("status", STATUS);
    error.put("code", CODE_BUSINESS_RULE);
    if (flatBody.has(NeoProcessService.MESSAGE_KEYS)) {
      error.put(NeoProcessService.MESSAGE_KEYS, flatBody.get(NeoProcessService.MESSAGE_KEYS));
    }
    flatBody.put(NeoProcessService.ERROR, error);
    return new NeoResponse(STATUS, flatBody);
  }
}
