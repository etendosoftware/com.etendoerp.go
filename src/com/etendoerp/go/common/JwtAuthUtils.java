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

package com.etendoerp.go.common;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Shared JWT authentication utility for Etendo GO servlets.
 *
 * Reads the {@code Authorization: Bearer <token>} header, decodes the JWT,
 * and sets up {@link OBContext} for the request. Throws {@link OBException}
 * on any authentication failure so callers can return 401.
 */
public class JwtAuthUtils {

  private JwtAuthUtils() {
  }

  /**
   * Authenticates the request via Bearer JWT and sets up OBContext.
   *
   * @param request the incoming HTTP request carrying the Authorization header
   * @throws OBException if the token is missing, invalid, or has missing claims
   * @throws Exception   for any other decode/context failure
   */
  public static void authenticate(HttpServletRequest request) throws Exception {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new OBException("Missing or invalid Authorization header");
    }
    String token = authHeader.substring(7);
    DecodedJWT decoded = SecureWebServicesUtils.decodeToken(token);

    String userId      = decoded.getClaim("user").asString();
    String roleId      = decoded.getClaim("role").asString();
    String orgId       = decoded.getClaim("organization").asString();
    String warehouseId = decoded.getClaim("warehouse").asString();
    String clientId    = decoded.getClaim("client").asString();

    if (StringUtils.isAnyBlank(userId, roleId, orgId, clientId)) {
      throw new OBException("Invalid token: missing required claims");
    }

    OBContext context = SecureWebServicesUtils.createContext(userId, roleId, orgId, warehouseId, clientId);
    OBContext.setOBContext(context);
    OBContext.setOBContextInSession(request, context);
  }

  /**
   * Authenticates the request and, on failure, writes a 401 response and logs the reason.
   * Returns {@code true} on success, {@code false} when the caller should abort processing.
   *
   * @param request  the incoming HTTP request
   * @param response the HTTP response (used to write the 401 body on failure)
   * @param log      logger used to record the failure cause
   * @param context  short label for the endpoint, included in the log message
   */
  public static boolean authenticateOrFail(HttpServletRequest request, HttpServletResponse response,
      Logger log, String context) throws IOException {
    try {
      authenticate(request);
      return true;
    } catch (OBException e) {
      log.warn("Unauthorized {}: {}", context, e.getMessage());
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
      return false;
    } catch (Exception e) {
      log.warn("Unauthorized {}: {}", context, e.getMessage());
      ServletResponseUtils.sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
      return false;
    }
  }
}
