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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.entity.ContentType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.HttpBaseServlet;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.common.CorsUtils;
import com.etendoerp.go.schemaforge.util.NeoJwtAuth;

/**
 * Persists and retrieves navigator favorites per user via AD_PREFERENCE.
 *
 * Mapped to /sws/neo/favorites via AD_MODEL_OBJECT registration.
 *
 * GET  /sws/neo/favorites — returns the current user's favorites as a JSON array
 * PUT  /sws/neo/favorites — replaces the current user's favorites with the given JSON array
 */
public class NeoFavoritesServlet extends HttpBaseServlet {

  private static final Logger log = LogManager.getLogger(NeoFavoritesServlet.class);
  private static final String ALLOWED_METHODS = "GET, PUT, OPTIONS";
  private static final String ALLOWED_HEADERS = "Authorization, Content-Type";

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, ALLOWED_METHODS, ALLOWED_HEADERS, null, false);
    try {
      NeoJwtAuth.authenticate(request);
    } catch (OBException e) {
      log.warn("Unauthorized favorites GET: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
      return;
    } catch (Exception e) {
      log.warn("Unauthorized favorites GET: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
      return;
    }

    try {
      OBContext.setAdminMode();
      String json = NeoFavoritesService.getFavoritesJson();
      response.setContentType(ContentType.APPLICATION_JSON.getMimeType());
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setStatus(HttpServletResponse.SC_OK);
      response.getWriter().write(json);
    } catch (Exception e) {
      log.error("Error reading favorites: {}", e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while reading favorites.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Override
  public void doPut(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, ALLOWED_METHODS, ALLOWED_HEADERS, null, false);
    try {
      NeoJwtAuth.authenticate(request);
    } catch (OBException e) {
      log.warn("Unauthorized favorites PUT: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
      return;
    } catch (Exception e) {
      log.warn("Unauthorized favorites PUT: {}", e.getMessage());
      sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
      return;
    }

    try {
      OBContext.setAdminMode();
      String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      NeoFavoritesService.saveFavoritesJson(body);
      response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    } catch (Exception e) {
      log.error("Error saving favorites: {}", e.getMessage(), e);
      sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "An internal error occurred while saving favorites.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Override
  public void doOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
    CorsUtils.apply(request, response, ALLOWED_METHODS, ALLOWED_HEADERS, null, false);
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
  }
}
