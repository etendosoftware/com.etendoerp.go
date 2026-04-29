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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.ProcessAccess;
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
   * Checks whether the current role has access to the given AD window.
   *
   * @param windowId the ID of the AD window to check
   * @return {@code true} if the current role has an active window-access record, or if the role is the system administrator role
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
   * Checks whether the current role has access to the given AD process.
   *
   * @param processId the ID of the AD process to check
   * @return {@code true} if the current role has an active process-access record, or if the role is the system administrator role
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
   * Returns the AD process linked to the given spec.
   *
   * @param spec the Schema Forge spec whose associated process is needed
   * @return the {@link org.openbravo.model.ad.ui.Process} configured on the spec, or {@code null} if none
   */
  public static Process resolveProcess(SFSpec spec) {
    return spec.getProcess();
  }
}
