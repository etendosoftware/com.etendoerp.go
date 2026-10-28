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

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The one refusal both "do not create a second one" rules share (ETP-5712 for a return's
 * rectificativa, ETP-5714 for an order's draft invoice): a 409 with a dedicated error code, a
 * message, a hint and the list of what already exists, unless the request carries the JSON boolean
 * {@code allowDuplicate:true}. The check is strict: a string, a number or {@code null} is never read
 * as true and never fails open, it is a 422.
 */
final class DuplicateCreationRefusal {

  private DuplicateCreationRefusal() {
  }

  /**
   * Refuses the creation while something already exists.
   *
   * @param body     the request body; {@code null} reads as empty
   * @param existing what already exists; {@code null} or empty lets the creation go on
   * @param code     the {@code error.code} of the 409
   * @param message  the 409 message
   * @param hint     the 409 hint: the one way past it
   * @param listKey  the key under which {@code existing} travels in the 409 body
   * @return the 409 or 422, or {@code null} when the creation may go on
   */
  static NeoResponse refuseUnlessAllowed(JSONObject body, JSONArray existing, String code,
      String message, String hint, String listKey) {
    Object raw = body != null ? body.opt(ReturnShipmentUtils.PARAM_ALLOW_DUPLICATE) : null;
    if (raw != null && !(raw instanceof Boolean)) {
      return NeoResponse.error(ReturnableQuantity.SC_UNPROCESSABLE,
          ReturnShipmentUtils.MSG_ALLOW_DUPLICATE_NOT_BOOLEAN);
    }
    if (Boolean.TRUE.equals(raw) || existing == null || existing.length() == 0) {
      return null;
    }
    try {
      JSONObject error = new JSONObject();
      error.put("status", HttpServletResponse.SC_CONFLICT);
      error.put("code", code);
      error.put("message", message);
      error.put("hint", hint);
      error.put(listKey, existing);
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          new JSONObject().put("error", error));
    } catch (JSONException e) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT, message);
    }
  }
}
