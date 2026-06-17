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
package com.etendoerp.go.onboarding;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.reference.PInstanceProcessData;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.scheduling.ProcessRunner;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Marks an organization as ready by executing the AD_Org_Ready process and setting isready=Y.
 * Must run after the onboarding dataset import so that all reference data (payment terms,
 * doc types, etc.) is present before Etendo's org-accessibility filter is enabled.
 */
public class OnboardingMarkOrgReadyService {

  private static final Logger log = LogManager.getLogger(OnboardingMarkOrgReadyService.class);

  private static final String ORG_READY_PROCESS_KEY = "AD_Org_Ready";

  /**
   * B1 defensive provisioning. AD_Org_Ready already populates AD_ORG_TREE, but its tree INSERT
   * filters on AD_ISORGINCLUDED_TREENODE(...) &gt; 0 and runs on its own DalConnectionProvider
   * connection. During onboarding the new org/treenode rows are only flushed (not committed) on
   * the DAL session, so that separate connection cannot see them and the filter yields no rows —
   * AD_ORG_TREE stays empty for the new org. We therefore (re)insert the 2 rows a flat onboarding
   * org needs, on the DAL session connection (same transaction, sees the flushed org), idempotently
   * guarded so it is a no-op whenever AD_Org_Ready did populate the tree. Kept in lockstep with R1
   * step 12 (the corrective twin for already-onboarded tenants).
   */
  private static final String ORG_TREE_SELF_SQL =
      "INSERT INTO ad_org_tree (ad_org_tree_id, ad_client_id, isactive, created, createdby,"
          + " updated, updatedby, ad_org_id, ad_parent_org_id, levelno)"
          + " SELECT get_uuid(), :clientId, 'Y', now(), '0', now(), '0', :orgId, :orgId, 1"
          + " WHERE NOT EXISTS (SELECT 1 FROM ad_org_tree t"
          + " WHERE t.ad_org_id = :orgId AND t.ad_parent_org_id = :orgId)";

  private static final String ORG_TREE_PARENT_SQL =
      "INSERT INTO ad_org_tree (ad_org_tree_id, ad_client_id, isactive, created, createdby,"
          + " updated, updatedby, ad_org_id, ad_parent_org_id, levelno)"
          + " SELECT get_uuid(), :clientId, 'Y', now(), '0', now(), '0', :orgId, '0', 2"
          + " WHERE NOT EXISTS (SELECT 1 FROM ad_org_tree t"
          + " WHERE t.ad_org_id = :orgId AND t.ad_parent_org_id = '0')";

  /**
   * Marks the organization as ready if not already done.
   *
   * @param clientId    target client identifier
   * @param orgId       target organization identifier
   * @param adminUserId administrator user for the process execution context
   * @param adminRoleId administrator role for the process execution context
   */
  public void markOrgReady(String clientId, String orgId, String adminUserId, String adminRoleId) {
    Organization org = resolveOrganization(orgId);
    if (org == null) {
      throw new OBException("Organization not found for markOrgReady: " + orgId);
    }
    if (Boolean.TRUE.equals(org.isReady())) {
      log.debug("Organization {} is already ready, skipping", orgId);
      return;
    }

    flushChanges();
    executeOrgReadyProcess(orgId, clientId, adminUserId, adminRoleId);

    // Defensive: ensure the OBDal entity reflects ready state after process execution
    org = resolveOrganization(orgId);
    if (org != null && !Boolean.TRUE.equals(org.isReady())) {
      org.setReady(true);
      saveOrganization(org);
    }

    // B1: (re)provision the AD_ORG_TREE rows on the DAL session connection. See ORG_TREE_*_SQL.
    provisionOrgTree(clientId, orgId);

    flushChanges();
  }

  /**
   * Inserts the two AD_ORG_TREE rows a flat onboarding organization requires (self-reference at
   * levelno 1, child of the root org '0' at levelno 2). Both inserts are idempotent (NOT EXISTS
   * guards) so re-running onboarding — or a successful AD_Org_Ready that already populated the
   * tree — never double-inserts. Without these rows AD_ISORGINCLUDED returns -1 and same-org
   * documents fail with "lines org does not depend on header org".
   */
  protected void provisionOrgTree(String clientId, String orgId) {
    runOrgTreeInsert(ORG_TREE_SELF_SQL, clientId, orgId);
    runOrgTreeInsert(ORG_TREE_PARENT_SQL, clientId, orgId);
  }

  protected void runOrgTreeInsert(String sql, String clientId, String orgId) {
    int rows = OBDal.getInstance().getSession()
        .createNativeQuery(sql)
        .setParameter("clientId", clientId)
        .setParameter("orgId", orgId)
        .executeUpdate();
    if (rows > 0) {
      log.debug("Provisioned {} AD_ORG_TREE row(s) for org {}", rows, orgId);
    }
  }

  protected void executeOrgReadyProcess(String orgId, String clientId,
      String adminUserId, String adminRoleId) {
    Process process = resolveProcess(ORG_READY_PROCESS_KEY);
    if (process == null) {
      throw new OBException(
          "AD_Org_Ready process not found — ensure Etendo core reference data is loaded");
    }
    try {
      String roleId = adminRoleId;
      OBContext ctx = OBContext.getOBContext();
      String language = (ctx != null && ctx.getLanguage() != null)
          ? ctx.getLanguage().getLanguage() : "en_US";
      DalConnectionProvider conn = new DalConnectionProvider(false);
      VariablesSecureApp vars = new VariablesSecureApp(adminUserId, clientId, orgId, roleId,
          language);
      String pinstanceId = SequenceIdData.getUUID();
      PInstanceProcessData.insertPInstance(conn, pinstanceId, process.getId(), orgId, "Y",
          adminUserId, clientId, orgId);
      ProcessBundle bundle = ProcessBundle.pinstance(pinstanceId, vars, conn);
      new ProcessRunner(bundle).execute(conn);
      log.info("AD_Org_Ready process executed for org '{}'", orgId);
    } catch (Exception e) {
      throw new OBException("AD_Org_Ready process failed for org " + orgId, e);
    }
  }

  protected Process resolveProcess(String searchKey) {
    OBCriteria<Process> criteria = OBDal.getInstance().createCriteria(Process.class);
    criteria.add(Restrictions.eq(Process.PROPERTY_SEARCHKEY, searchKey));
    criteria.setMaxResults(1);
    return (Process) criteria.uniqueResult();
  }

  protected Organization resolveOrganization(String orgId) {
    return OBDal.getInstance().get(Organization.class, orgId);
  }

  protected void saveOrganization(Organization org) {
    OBDal.getInstance().save(org);
  }

  protected void flushChanges() {
    OBDal.getInstance().flush();
  }
}
