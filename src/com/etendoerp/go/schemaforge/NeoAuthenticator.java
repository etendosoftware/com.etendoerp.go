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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Authentication and access-check collaborator for {@link NeoServlet}.
 * Validates JWTs, sets up the {@link OBContext}, and answers per-window
 * and per-process access questions for the current role.
 */
class NeoAuthenticator {

  private static final Logger log = LogManager.getLogger(NeoAuthenticator.class);

  private final NeoServlet servlet;

  NeoAuthenticator(NeoServlet servlet) {
    this.servlet = servlet;
  }

  /**
   * Verify the request carries a valid JWT and set up the corresponding
   * {@link OBContext}. On failure writes an HTTP error to {@code response}
   * and returns {@code false}.
   */
  boolean authenticateRequest(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try {
      authenticateJwt(request);
      return true;
    } catch (OBException e) {
      // OBException messages are safe to expose (we control them)
      log.warn("Unauthorized NEO request: {}", e.getMessage());
      servlet.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
      return false;
    } catch (Exception e) {
      // Other exceptions (JWT decode failures, NPEs) don't leak internals.
      log.warn("Unauthorized NEO request: {}", e.getMessage());
      servlet.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
      return false;
    }
  }

  void authenticateJwt(HttpServletRequest request) throws Exception {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new OBException("Missing or invalid Authorization header");
    }
    String token = authHeader.substring(7);
    DecodedJWT decodedToken = SecureWebServicesUtils.decodeToken(token);

    String userId = decodedToken.getClaim("user").asString();
    String roleId = decodedToken.getClaim("role").asString();
    String orgId = decodedToken.getClaim("organization").asString();
    String warehouseId = decodedToken.getClaim("warehouse").asString();
    String clientId = decodedToken.getClaim("client").asString();

    if (StringUtils.isAnyBlank(userId, roleId, orgId, clientId)) {
      throw new OBException("Invalid token: missing required claims");
    }

    OBContext context = SecureWebServicesUtils.createContext(userId, roleId, orgId, warehouseId, clientId);
    // The JWT warehouse may have been set to an inaccessible warehouse by the token generator
    // (SecureWebServicesUtils.getWarehouse() falls back to warehouseList.get(0) when the user
    // has no default, without filtering by client). Validate it against the user's readable orgs;
    // if inaccessible, find the first warehouse the user can actually access.
    if (context.getWarehouse() != null) {
      String whOrgId = context.getWarehouse().getOrganization().getId();
      boolean accessible = false;
      for (String readableOrg : context.getReadableOrganizations()) {
        if (readableOrg.equals(whOrgId)) {
          accessible = true;
          break;
        }
      }
      if (!accessible) {
        log.warn("JWT warehouse '{}' (org='{}') is not in user '{}' readable orgs — resolving accessible warehouse",
            warehouseId, whOrgId, userId);
        String correctedWarehouseId = NeoServletSupport.findAccessibleWarehouse(context);
        context = SecureWebServicesUtils.createContext(userId, roleId, orgId, correctedWarehouseId, clientId);
      }
    }
    OBContext.setOBContext(context);
    OBContext.setOBContextInSession(request, context);
  }

  boolean hasWindowAccess(String windowId) {
    return NeoServletSupport.hasWindowAccess(windowId);
  }

  boolean hasProcessAccess(String processId) {
    return NeoServletSupport.hasProcessAccess(processId);
  }
}
