package com.etendoerp.go.mcp;

import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.WindowAccess;

import org.hibernate.criterion.Restrictions;

/**
 * RBAC access check utilities, extracted from NeoServlet for reuse across
 * the NEO Headless servlet and the MCP tool registry.
 * <p>
 * Checks are based on AD_Window_Access / AD_Process_Access records for the
 * current role. The System Administrator role (id "0") bypasses all checks.
 */
public final class NeoAccessUtils {

  private NeoAccessUtils() {
    // utility class
  }

  /**
   * Check if the current role has access to the given AD_Window.
   *
   * @param windowId AD_Window_ID to check
   * @return true if the role has an active WindowAccess record, or is System Admin
   */
  public static boolean hasWindowAccess(String windowId) {
    String roleId = OBContext.getOBContext().getRole().getId();
    if ("0".equals(roleId)) {
      return true;
    }
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_WINDOW + ".id", windowId));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE + ".id", roleId));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  /**
   * Check if the current role has access to the given AD_Process.
   *
   * @param processId AD_Process_ID to check
   * @return true if the role has an active ProcessAccess record, or is System Admin
   */
  public static boolean hasProcessAccess(String processId) {
    String roleId = OBContext.getOBContext().getRole().getId();
    if ("0".equals(roleId)) {
      return true;
    }
    OBCriteria<ProcessAccess> criteria = OBDal.getInstance().createCriteria(ProcessAccess.class);
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_PROCESS + ".id", processId));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE + ".id", roleId));
    criteria.add(Restrictions.eq(ProcessAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }
}
