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

package com.etendoerp.go.schemaforge.util;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Static helpers for access control and process resolution.
 */
public final class NeoAccessHelper {

  private static final Logger log = LogManager.getLogger(NeoAccessHelper.class);

  private static final String DEFAULT_POST_PROCESS_ID = "57496FB9CF9E4E8F847224017941570E";

  private NeoAccessHelper() {
  }

  /**
   * Checks whether the current role has (read) access to the given AD window.
   *
   * <p>Equivalent to {@link #hasWindowAccess(String, String)} with a {@code GET} method —
   * i.e. answers "is this window visible/reachable at all for the current role", regardless
   * of whether that role's access is read-only or full. Used by discovery/listing call sites
   * (MCP tool discovery, window sub-endpoint discovery) that don't have a concrete HTTP verb.</p>
   *
   * @param windowId the ID of the AD window to check
   * @return {@code true} if the current role has an active window-access record, is the
   *         System Administrator role, or is a client-admin role for the current client
   */
  public static boolean hasWindowAccess(String windowId) {
    return hasWindowAccess(windowId, "GET");
  }

  /**
   * Checks whether the current role has access to the given AD window for the given HTTP
   * method, enforcing the read-only vs. full-access tiering declared on {@code AD_Window_Access}.
   *
   * <p>Resolution order:</p>
   * <ol>
   *   <li>No role assigned (role is {@code null}) → deny.</li>
   *   <li>System Administrator role ({@code "0"}) or a client-admin role
   *       ({@link Role#isClientAdmin()}) → always allow, any method.</li>
   *   <li>No active {@code WindowAccess} row for role+window → deny.</li>
   *   <li>Read methods ({@code GET}) → allow whenever an active row exists.</li>
   *   <li>Write methods ({@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE}) → allow only
   *       when the row's {@code IsReadWrite} flag ({@link WindowAccess#isEditableField()})
   *       is {@code true}; a read-only row denies them.</li>
   * </ol>
   *
   * @param windowId the ID of the AD window to check
   * @param httpMethod the HTTP method of the current request (e.g. {@code GET}, {@code POST})
   * @return {@code true} if the current role is allowed to perform {@code httpMethod} on
   *         {@code windowId}
   */
  public static boolean hasWindowAccess(String windowId, String httpMethod) {
    Role role = currentRole();
    if (role == null) {
      return false;
    }
    if (isAdminOrClientAdmin(role)) {
      return true;
    }
    WindowAccess access = findActiveWindowAccess(windowId, role.getId());
    if (access == null) {
      return false;
    }
    if (isWriteMethod(httpMethod)) {
      return Boolean.TRUE.equals(access.isEditableField());
    }
    return true;
  }

  /**
   * Checks whether the current role has access to the given AD process.
   *
   * <p>Process access remains binary (no read/write tiering) — any active
   * {@code ProcessAccess} row grants full access to execute the process.</p>
   *
   * @param processId the ID of the AD process to check
   * @return {@code true} if the current role has an active process-access record, or if the role is the system administrator role
   */
  public static boolean hasProcessAccess(String processId) {
    Role role = currentRole();
    if (role == null) {
      return false;
    }
    String roleId = role.getId();
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

  /**
   * Resolves the current role from the {@link OBContext}, tolerating a missing context or
   * a request with no role assigned.
   *
   * @return the current {@link Role}, or {@code null} if there is no context or no role
   */
  private static Role currentRole() {
    OBContext context = OBContext.getOBContext();
    return context == null ? null : context.getRole();
  }

  /**
   * Whether {@code role} should bypass window/process access checks entirely: the true
   * System Administrator role ({@code "0"}), or a per-client "GO Admin" role
   * ({@code AD_Role.is_client_admin = 'Y'}).
   *
   * @param role the role to evaluate (never {@code null})
   * @return {@code true} if this role always has full access
   */
  private static boolean isAdminOrClientAdmin(Role role) {
    return "0".equals(role.getId()) || Boolean.TRUE.equals(role.isClientAdmin());
  }

  /**
   * Looks up the single active {@code WindowAccess} row for the given role+window, if any.
   *
   * @param windowId the ID of the AD window to check
   * @param roleId the ID of the role to check
   * @return the active {@link WindowAccess} row, or {@code null} if none exists
   */
  private static WindowAccess findActiveWindowAccess(String windowId, String roleId) {
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_WINDOW + ".id", windowId));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE + ".id", roleId));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    List<WindowAccess> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  /**
   * Whether {@code httpMethod} is a write/mutating HTTP verb that requires full
   * (not read-only) window access.
   *
   * @param httpMethod the HTTP method of the current request
   * @return {@code true} for {@code POST}, {@code PUT}, {@code PATCH}, or {@code DELETE}
   *         (case-insensitive)
   */
  private static boolean isWriteMethod(String httpMethod) {
    return "POST".equalsIgnoreCase(httpMethod)
        || "PUT".equalsIgnoreCase(httpMethod)
        || "PATCH".equalsIgnoreCase(httpMethod)
        || "DELETE".equalsIgnoreCase(httpMethod);
  }

  /**
   * Checks whether the current role has access to the given OBUIAPP process definition.
   *
   * @param processId the ID of the OBUIAPP process definition to check
   * @return {@code true} if the current role has an active OBUIAPP process-access record,
   *         or if the role is the system administrator role
   */
  public static boolean hasObuiappProcessAccess(String processId) {
    String roleId = OBContext.getOBContext().getRole().getId();
    if ("0".equals(roleId)) {
      return true;
    }
    OBCriteria<org.openbravo.client.application.ProcessAccess> criteria = OBDal.getInstance()
        .createCriteria(org.openbravo.client.application.ProcessAccess.class);
    criteria.add(Restrictions.eq(
        org.openbravo.client.application.ProcessAccess.PROPERTY_OBUIAPPPROCESS + ".id",
        processId));
    criteria.add(Restrictions.eq(
        org.openbravo.client.application.ProcessAccess.PROPERTY_ROLE + ".id", roleId));
    criteria.add(Restrictions.eq(
        org.openbravo.client.application.ProcessAccess.PROPERTY_ACTIVE, true));
    criteria.setMaxResults(1);
    return !criteria.list().isEmpty();
  }

  /**
   * Resolves the default post (accounting) process used for the Posted button.
   *
   * @return the default {@code Process} instance, or {@code null} if it cannot be found
   */
  public static org.openbravo.client.application.Process resolveDefaultPostProcess() {
    try {
      return OBDal.getInstance().get(
          org.openbravo.client.application.Process.class, DEFAULT_POST_PROCESS_ID);
    } catch (Exception e) {
      log.debug("Default Post process not found: {}", DEFAULT_POST_PROCESS_ID);
      return null;
    }
  }

  /**
   * Resolve the shared fallback OBUIAPP process for button columns that do not declare one explicitly.
   *
   * @param column the AD_Column being evaluated
   * @return the fallback OBUIAPP process when the column matches the shared Posted convention,
   *         or {@code null} when no fallback applies
   */
  public static org.openbravo.client.application.Process resolveFallbackObuiappProcess(
      org.openbravo.model.ad.datamodel.Column column) {
    if (column == null || !"Posted".equals(column.getDBColumnName())) {
      return null;
    }
    return resolveDefaultPostProcess();
  }

  /**
   * Returns the AD process linked to the given spec.
   *
   * @param spec the Schema Forge spec whose associated process is needed
   * @return the {@link org.openbravo.model.ad.ui.Process} configured on the spec, or {@code null} if none
   */
  public static Process resolveProcess(SFSpec spec) {
    return spec.getProcess();
  }
}
