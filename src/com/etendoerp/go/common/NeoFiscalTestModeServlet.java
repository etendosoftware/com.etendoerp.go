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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Serves the effective {@code ETSG_ForceTestMode} preference value for the current
 * authenticated Client.
 *
 * Mapped to /sws/neo/fiscal-test-mode via AD_MODEL_OBJECT registration.
 *
 * GET /sws/neo/fiscal-test-mode — returns {@code {"forceTestMode": true|false}}: whether
 * fiscal submissions (SII / TicketBAI / VeriFactu) are effectively forced into test/sandbox
 * mode for the authenticated user's Client — that Client's own {@code ETSG_ForceTestMode}
 * row if it has one, else the System-wide default (see {@link FiscalTestModeResolver}).
 *
 * <p>Unlike {@link NeoCurrencyFormatServlet} (instance-wide, unauthenticated formatting
 * config), this value is Client-scoped, so a valid NEO bearer token is required — the same
 * {@code Authorization: Bearer} + {@link JwtAuthUtils} mechanism used across NEO Headless,
 * which also establishes the {@link OBContext} this servlet reads the current Client from.
 */
public class NeoFiscalTestModeServlet extends HttpBaseServlet {

  private static final Logger LOG = LogManager.getLogger(NeoFiscalTestModeServlet.class);
  private static final String ALLOWED_METHODS = "GET, OPTIONS";
  private static final String ALLOWED_HEADERS = "Authorization, Content-Type";

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, ALLOWED_METHODS, ALLOWED_HEADERS, null, false);
    if (!JwtAuthUtils.authenticateOrFail(request, response, LOG, "fiscal-test-mode GET")) {
      return;
    }

    try {
      boolean forceTestMode = FiscalTestModeResolver.isForceTestModeActive(
          OBContext.getOBContext().getCurrentClient());
      JSONObject body = new JSONObject();
      body.put("forceTestMode", forceTestMode);
      ServletResponseUtils.writeJson(response, HttpServletResponse.SC_OK, body);
    } catch (JSONException e) {
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to build fiscal-test-mode response");
    } catch (RuntimeException e) {
      LOG.error("Error resolving fiscal test mode: {}", e.getMessage(), e);
      OBDal.getInstance().rollbackAndClose();
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while resolving fiscal test mode.");
    }
  }

  @Override
  public void doOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, ALLOWED_METHODS, ALLOWED_HEADERS, null, false);
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }
}
