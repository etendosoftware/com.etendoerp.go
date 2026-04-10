package com.etendoerp.go.schemaforge;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Warehouse;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Shared lookups used by {@link NeoServlet}.
 */
class NeoServletSupport {

  private static final Logger log = LogManager.getLogger(NeoServletSupport.class);

  private NeoServletSupport() {
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

    String path = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    String[] parts = path.split("/");
    if (parts.length < 1 || parts[0].isEmpty()) {
      return new NeoServlet.NeoPathInfo(null, null, null);
    }

    String specName = parts[0];
    if (parts.length == 1) {
      return new NeoServlet.NeoPathInfo(specName, null, null);
    }

    String entityName = parts[1];
    if (parts.length >= 3 && "selectors".equals(parts[2])) {
      String selectorField = parts.length >= 4 ? parts[3] : null;
      return new NeoServlet.NeoPathInfo(specName, entityName, null, true, selectorField);
    }
    if (parts.length >= 3 && "callout".equals(parts[2])) {
      return new NeoServlet.NeoPathInfo(
          specName, entityName, null, false, null, false, null, false, true, false);
    }
    if (parts.length >= 3 && "defaults".equals(parts[2])) {
      return new NeoServlet.NeoPathInfo(
          specName, entityName, null, false, null, false, null, false, false, true);
    }
    if (parts.length >= 3 && "evaluate-display".equals(parts[2])) {
      return new NeoServlet.NeoPathInfo(specName, entityName, null, false, null, false, null, true);
    }
    if (parts.length >= 4 && "action".equals(parts[3])) {
      String actionName = parts.length >= 5 ? parts[4] : null;
      return new NeoServlet.NeoPathInfo(specName, entityName, parts[2], false, null, true, actionName);
    }
    String recordId = parts.length >= 3 ? parts[2] : null;
    return new NeoServlet.NeoPathInfo(specName, entityName, recordId);
  }
}
