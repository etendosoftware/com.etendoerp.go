package com.etendoerp.go.schemaforge;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Warehouse;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * Shared lookups used by {@link NeoServlet}.
 */
class NeoServletSupport {

  private static final Logger log = LogManager.getLogger(NeoServletSupport.class);

  private NeoServletSupport() {
  }

  static OBContext authenticateJwt(HttpServletRequest request) throws Exception {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new OBException("Missing or invalid Authorization header");
    }
    String token = authHeader.substring(7);
    DecodedJWT decoded = SecureWebServicesUtils.decodeToken(token);

    String userId = decoded.getClaim("user").asString();
    String roleId = decoded.getClaim("role").asString();
    String orgId = decoded.getClaim("organization").asString();
    String warehouseId = decoded.getClaim("warehouse").asString();
    String clientId = decoded.getClaim("client").asString();

    if (StringUtils.isAnyBlank(userId, roleId, orgId, clientId)) {
      throw new OBException("Invalid token: missing required claims");
    }

    OBContext context = SecureWebServicesUtils.createContext(userId, roleId, orgId, warehouseId, clientId);
    OBContext.setOBContext(context);
    OBContext.setOBContextInSession(request, context);
    return context;
  }

  static String findAccessibleWarehouse(OBContext ctx) {
    try {
      OBContext.setAdminMode(true);
      Set<String> readableOrgs = new HashSet<>(Arrays.asList(ctx.getReadableOrganizations()));
      OBCriteria<Warehouse> criteria = OBDal.getInstance().createCriteria(Warehouse.class);
      criteria.add(Restrictions.eq(Warehouse.PROPERTY_CLIENT, ctx.getCurrentClient()));
      criteria.add(Restrictions.eq(Warehouse.PROPERTY_ACTIVE, true));
      criteria.setMaxResults(50);
      for (Warehouse warehouse : criteria.list()) {
        String warehouseOrgId = warehouse.getOrganization().getId();
        if (readableOrgs.contains(warehouseOrgId)) {
          log.debug("Resolved accessible warehouse '{}' (org='{}') for user '{}'",
              warehouse.getId(), warehouseOrgId, ctx.getUser().getId());
          return warehouse.getId();
        }
      }
      log.warn("No accessible warehouse found for user '{}' client '{}'",
          ctx.getUser().getId(), ctx.getCurrentClient().getId());
      return null;
    } catch (Exception e) {
      log.error("Error finding accessible warehouse for user '{}': {}",
          ctx.getUser().getId(), e.getMessage(), e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  static SFSpec findSpec(String specName) {
    OBCriteria<SFSpec> criteria = OBDal.getInstance().createCriteria(SFSpec.class);
    criteria.add(Restrictions.ilike(SFSpec.PROPERTY_NAME, specName, MatchMode.EXACT));
    criteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    criteria.setMaxResults(1);
    List<SFSpec> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  static boolean hasWindowAccess(String windowId) {
    return com.etendoerp.go.schemaforge.util.NeoAccessHelper.hasWindowAccess(windowId);
  }

  static boolean hasProcessAccess(String processId) {
    return com.etendoerp.go.schemaforge.util.NeoAccessHelper.hasProcessAccess(processId);
  }

  static NeoServlet.NeoPathInfo parsePath(String pathInfo) {
    if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
      return new NeoServlet.NeoPathInfo(null, null, null);
    }

    String[] parts = normalizePathParts(pathInfo);
    if (parts.length < 1 || parts[0].isEmpty()) {
      return new NeoServlet.NeoPathInfo(null, null, null);
    }

    String specName = parts[0];
    if (parts.length == 1) {
      return new NeoServlet.NeoPathInfo(specName, null, null);
    }

    return parseEntityPath(specName, parts);
  }

  private static String[] normalizePathParts(String pathInfo) {
    String normalizedPath = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    return normalizedPath.split("/");
  }

  private static NeoServlet.NeoPathInfo parseEntityPath(String specName, String[] parts) {
    String entityName = parts[1];
    if (parts.length < 3) {
      return new NeoServlet.NeoPathInfo(specName, entityName, null);
    }
    NeoServlet.NeoPathInfo subEndpointPath = parseSubEndpointPath(specName, entityName, parts);
    if (subEndpointPath != null) {
      return subEndpointPath;
    }
    String recordId = parts[2];
    return parseActionOrRecordPath(specName, entityName, recordId, parts);
  }

  private static NeoServlet.NeoPathInfo parseSubEndpointPath(String specName, String entityName,
      String[] parts) {
    String thirdSegment = parts[2];
    if ("selectors".equals(thirdSegment)) {
      String selectorField = parts.length >= 4 ? parts[3] : null;
      return new NeoServlet.NeoPathInfo(specName, entityName, null, true, selectorField);
    }
    if ("callout".equals(thirdSegment)) {
      return new NeoServlet.NeoPathInfo(
          specName, entityName, null, false, null, false, null, false, true, false);
    }
    if ("defaults".equals(thirdSegment)) {
      return new NeoServlet.NeoPathInfo(
          specName, entityName, null, false, null, false, null, false, false, true);
    }
    if ("evaluate-display".equals(thirdSegment)) {
      return new NeoServlet.NeoPathInfo(specName, entityName, null, false, null, false, null, true);
    }
    return null;
  }

  private static NeoServlet.NeoPathInfo parseActionOrRecordPath(String specName, String entityName,
      String recordId, String[] parts) {
    if (parts.length >= 4 && "action".equals(parts[3])) {
      String actionName = parts.length >= 5 ? parts[4] : null;
      return new NeoServlet.NeoPathInfo(specName, entityName, recordId, false, null, true,
          actionName);
    }
    return new NeoServlet.NeoPathInfo(specName, entityName, recordId);
  }
}
