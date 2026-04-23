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

  private static final String AUTH_HEADER = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String CLAIM_USER = "user";
  private static final String CLAIM_ROLE = "role";
  private static final String CLAIM_ORG = "organization";
  private static final String CLAIM_WAREHOUSE = "warehouse";
  private static final String CLAIM_CLIENT = "client";

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
    String token = extractBearerToken(request);
    Claims claims = decodeClaims(token);
    applyContext(request, claims);
  }

  /**
   * Authenticates the request and, on failure, writes a 401 response and logs the reason.
   *
   * @param request  the incoming HTTP request
   * @param response the HTTP response (used to write the 401 body on failure)
   * @param log      logger used to record the failure cause
   * @param context  short label for the endpoint, included in the log message
   * @return {@code true} when authentication succeeded, {@code false} when the caller must abort
   * @throws IOException if writing the 401 response body fails
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

  private static String extractBearerToken(HttpServletRequest request) {
    String authHeader = request.getHeader(AUTH_HEADER);
    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      throw new OBException("Missing or invalid Authorization header");
    }
    return authHeader.substring(BEARER_PREFIX.length());
  }

  private static Claims decodeClaims(String token) throws Exception {
    DecodedJWT decoded = SecureWebServicesUtils.decodeToken(token);
    if (decoded == null) {
      throw new OBException("Invalid token: unable to decode JWT");
    }
    Claims claims = new Claims(
        decoded.getClaim(CLAIM_USER).asString(),
        decoded.getClaim(CLAIM_ROLE).asString(),
        decoded.getClaim(CLAIM_ORG).asString(),
        decoded.getClaim(CLAIM_WAREHOUSE).asString(),
        decoded.getClaim(CLAIM_CLIENT).asString());
    if (StringUtils.isAnyBlank(claims.userId, claims.roleId, claims.orgId, claims.clientId)) {
      throw new OBException("Invalid token: missing required claims");
    }
    return claims;
  }

  private static void applyContext(HttpServletRequest request, Claims c) {
    OBContext ctx = SecureWebServicesUtils.createContext(c.userId, c.roleId, c.orgId, c.warehouseId, c.clientId);
    OBContext.setOBContext(ctx);
    OBContext.setOBContextInSession(request, ctx);
  }

  private static final class Claims {
    final String userId;
    final String roleId;
    final String orgId;
    final String warehouseId;
    final String clientId;

    Claims(String userId, String roleId, String orgId, String warehouseId, String clientId) {
      this.userId = userId;
      this.roleId = roleId;
      this.orgId = orgId;
      this.warehouseId = warehouseId;
      this.clientId = clientId;
    }
  }
}
