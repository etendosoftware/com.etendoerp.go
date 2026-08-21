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

package com.etendoerp.go.common;

import java.io.IOException;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.HttpBaseServlet;

/**
 * Serves the instance-wide currency number-formatting configuration.
 *
 * Mapped to /sws/neo/currency-format via AD_MODEL_OBJECT registration.
 *
 * GET /sws/neo/currency-format — returns
 * {"thousandsSeparator": ".", "decimalSeparator": ",", "symbolRightSide": {"EUR": true, "USD": false, ...}}.
 * `symbolRightSide` is read from `C_CURRENCY.ISSYMBOLRIGHTSIDE` (ETP-4314 follow-up) — no
 * currency is hardcoded as an exception on the frontend, it reads whatever Etendo Classic's
 * own reference data says. Not sensitive data (pure UI formatting), so no authentication is
 * required — same reasoning already applied to ReportSelectorsServlet.
 */
public class NeoCurrencyFormatServlet extends HttpBaseServlet {

  private static final Logger LOG = LogManager.getLogger(NeoCurrencyFormatServlet.class);
  private static final String ALLOWED_METHODS = "GET, OPTIONS";
  private static final String ALLOWED_HEADERS = "Content-Type";

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, ALLOWED_METHODS, ALLOWED_HEADERS, null, false);

    try {
      NeoCurrencyFormatConfig config = NeoCurrencyFormatConfig.fromRuntime();
      JSONObject body = new JSONObject();
      body.put("thousandsSeparator", config.getThousandsSeparator());
      body.put("decimalSeparator", config.getDecimalSeparator());
      body.put("symbolRightSide", buildSymbolRightSideJson());
      ServletResponseUtils.writeJson(response, HttpServletResponse.SC_OK, body);
    } catch (JSONException e) {
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to build currency-format response");
    }
  }

  /**
   * Builds the {@code symbolRightSide} map, failing soft to an empty object on any DB
   * error — this field is additive, so a problem reading it must never take down the
   * thousands/decimal separators the rest of the app depends on.
   */
  private JSONObject buildSymbolRightSideJson() throws JSONException {
    JSONObject json = new JSONObject();
    try {
      for (Map.Entry<String, Boolean> entry : NeoCurrencySymbolPositions.fetchAll().entrySet()) {
        json.put(entry.getKey(), entry.getValue());
      }
    } catch (RuntimeException e) {
      LOG.warn("Failed to read C_CURRENCY.ISSYMBOLRIGHTSIDE — symbolRightSide will be empty", e);
    }
    return json;
  }
}
