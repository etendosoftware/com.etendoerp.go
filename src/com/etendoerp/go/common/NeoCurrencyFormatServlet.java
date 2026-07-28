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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.HttpBaseServlet;

/**
 * Serves the instance-wide currency number-formatting configuration.
 *
 * Mapped to /sws/neo/currency-format via AD_MODEL_OBJECT registration.
 *
 * GET /sws/neo/currency-format — returns {"thousandsSeparator": ".", "decimalSeparator": ","}.
 * Not sensitive data (pure UI formatting), so no authentication is required — same
 * reasoning already applied to ReportSelectorsServlet.
 */
public class NeoCurrencyFormatServlet extends HttpBaseServlet {

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
      ServletResponseUtils.writeJson(response, HttpServletResponse.SC_OK, body);
    } catch (JSONException e) {
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to build currency-format response");
    }
  }
}
