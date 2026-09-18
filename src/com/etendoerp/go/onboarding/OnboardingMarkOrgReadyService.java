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
   * ETP-5352 reconciliation. AD_Org_Ready derives {@code AD_LEGALENTITY_ORG_ID} /
   * {@code AD_BUSINESSUNIT_ORG_ID} through {@code ad_get_org_le_bu_treenode()} and writes whatever
   * it computed — including NULL — in a single UPDATE. That function only short-circuits to the
   * organization itself when the org's type already carries {@code ISLEGALENTITY = 'Y'}; otherwise
   * it walks AD_TREENODE, and a walk that finds no parent returns NULL silently (AD_Org_Ready wraps
   * the call in {@code EXCEPTION WHEN DATA_EXCEPTION THEN ... := NULL}, so nothing is logged
   * either). An empty legal entity pointer then breaks {@code ad_get_org_le_bu()}, which breaks
   * {@code C_GETTAX}'s cash-VAT criterion, which makes every non-zero-rate tax unresolvable — the
   * user-visible symptom being {@code @TaxNotFound@} when invoicing a shipment with no sales order.
   *
   * <p>We recompute both pointers here, on the DAL session connection and after
   * {@link #provisionOrgTree} has put AD_ORG_TREE in place, using the same core function so the
   * semantics cannot drift. The COALESCE pair makes it strictly non-destructive (an already
   * populated pointer is never overwritten) and the WHERE clause makes it a no-op once healthy.</p>
   *
   * <p>A NULL business unit is normal and expected for a legal-entity organization — it is not
   * part of the defect. The predicate therefore only treats it as repairable when
   * {@code ad_get_org_le_bu_treenode(org, 'BU')} actually has a value to write; matching on
   * {@code ad_businessunit_org_id IS NULL} alone would make this UPDATE touch every healthy
   * onboarding org, bump its audit stamp and fire the WARN below on every single alta, which
   * would destroy the one signal this method exists to raise.</p>
   */
  private static final String ORG_HIERARCHY_POINTERS_SQL =
      "UPDATE ad_org"
          + " SET ad_legalentity_org_id = COALESCE(ad_legalentity_org_id,"
          + " ad_get_org_le_bu_treenode(ad_org_id, 'LE')),"
          + " ad_businessunit_org_id = COALESCE(ad_businessunit_org_id,"
          + " ad_get_org_le_bu_treenode(ad_org_id, 'BU')),"
          + " updated = now(), updatedby = '0'"
          + " WHERE ad_org_id = :orgId AND ad_client_id = :clientId"
          + " AND (ad_legalentity_org_id IS NULL"
          + " OR (ad_businessunit_org_id IS NULL"
          + " AND ad_get_org_le_bu_treenode(ad_org_id, 'BU') IS NOT NULL))";

  /**
   * Reads the two facts {@link #verifyOrgHierarchy} asserts on, straight from the database rather
   * than from the DAL entity — the reconciliation above is a native UPDATE, so a cached
   * {@link Organization} would still show the pre-update values.
   */
  private static final String ORG_HIERARCHY_CHECK_SQL =
      "SELECT ot.islegalentity, o.ad_legalentity_org_id"
          + " FROM ad_org o JOIN ad_orgtype ot ON ot.ad_orgtype_id = o.ad_orgtype_id"
          + " WHERE o.ad_org_id = :orgId";

  /**
   * Marks the organization as ready and reconciles the derived hierarchy state that depends on it.
   *
   * <p>Two responsibilities live here and they are gated differently:</p>
   * <ul>
   *   <li><b>Running AD_Org_Ready</b> stays conditional on {@code isReady}. The process is not
   *       idempotent: when one of its internal checks fails it rolls back and raises
   *       {@code @20545@}, which would take the whole onboarding down.</li>
   *   <li><b>Reconciling derived state</b> ({@code AD_ORG_TREE}, then the legal entity / business
   *       unit pointers) runs <b>always</b>, because it is idempotent and because {@code isReady}
   *       is not the state it repairs. {@code InitialOrgSetup.createOrganization()} already runs
   *       AD_Org_Ready before this method is called, so the organization arrives here with
   *       {@code isReady = 'Y'} — the early return that used to key on that flag skipped every
   *       repair below, which is how ETP-5352's tenants kept an empty legal entity pointer.</li>
   * </ul>
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
      log.debug("Organization {} is already ready, skipping AD_Org_Ready", orgId);
    } else {
      flushChanges();
      executeOrgReadyProcess(orgId, clientId, adminUserId, adminRoleId);

      // AD_Org_Ready wrote AD_ORG through PL/SQL, so the DAL still holds the pre-process entity.
      // Evict it BEFORE the defensive setReady below: saving a stale copy would flush the columns
      // the process just derived (legal entity, business unit, calendar owner) back to their old
      // NULLs. This is hypothesis (b) of gap D1 in docs/etendo-ad/onboarding-gaps.md.
      refreshOrganization(orgId);

      // Defensive: ensure the OBDal entity reflects ready state after process execution
      org = resolveOrganization(orgId);
      if (org != null && !Boolean.TRUE.equals(org.isReady())) {
        org.setReady(true);
        saveOrganization(org);
      }
    }

    // B1: (re)provision the AD_ORG_TREE rows on the DAL session connection. See ORG_TREE_*_SQL.
    provisionOrgTree(clientId, orgId);

    // Flush before the native reconciliation so it sees every pending entity change, and once the
    // tree above is in place so ad_get_org_le_bu_treenode() has something to walk.
    flushChanges();
    reconcileOrgHierarchyPointers(clientId, orgId);
    verifyOrgHierarchy(orgId);
  }

  /**
   * ETP-5352. Recomputes the legal entity / business unit pointers that AD_Org_Ready may have left
   * empty. Idempotent and non-destructive: see {@link #ORG_HIERARCHY_POINTERS_SQL}. Logs at WARN
   * when it actually repairs something, because a repair here means AD_Org_Ready failed silently
   * and we want that visible in the onboarding log rather than discovered months later through a
   * {@code @TaxNotFound@} report from production.
   */
  protected void reconcileOrgHierarchyPointers(String clientId, String orgId) {
    int rows = OBDal.getInstance().getSession()
        .createNativeQuery(ORG_HIERARCHY_POINTERS_SQL)
        .setParameter("clientId", clientId)
        .setParameter("orgId", orgId)
        .executeUpdate();
    if (rows > 0) {
      log.warn("Reconciled legal entity / business unit pointers for org {}: AD_Org_Ready"
          + " left them unset", orgId);
      // The UPDATE above bypassed the DAL, so drop the now-stale entity from the session. Leaving
      // it cached risks a later flush writing the pre-update NULLs straight back over the repair.
      refreshOrganization(orgId);
    }
  }

  /**
   * ETP-5352. Fails the onboarding loudly when the organization's hierarchy is still inconsistent
   * after reconciliation. A half-provisioned tenant is worse than an alta that stops with a clear
   * message: what hid this defect for so long is that everything looked correct from the UI while
   * invoicing was broken.
   *
   * <p>Both assertions are precise enough not to fire on a healthy tenant. Onboarding always
   * creates the organization as "Legal with accounting", so a non-legal-entity type here is itself
   * the anomaly that produces the empty pointer — checking it catches the defect at its source
   * rather than at its symptom.</p>
   */
  protected void verifyOrgHierarchy(String orgId) {
    Object[] row = (Object[]) OBDal.getInstance().getSession()
        .createNativeQuery(ORG_HIERARCHY_CHECK_SQL)
        .setParameter("orgId", orgId)
        .uniqueResult();
    if (row == null) {
      throw new OBException("Organization not found while verifying hierarchy: " + orgId);
    }
    if (!isYesFlag(row[0])) {
      throw new OBException("Onboarding organization " + orgId + " is not typed as a legal entity;"
          + " AD_Org_Ready cannot derive its legal entity pointer and tax resolution would fail");
    }
    if (row[1] == null) {
      throw new OBException("Organization " + orgId + " has no legal entity pointer after"
          + " reconciliation; tax resolution (C_GETTAX) would fail for any non-zero-rate tax");
    }
  }

  /**
   * Reads an Etendo {@code char(1)} yes/no flag out of a native-query result row.
   *
   * <p>This exists because of a trap that is invisible in a unit test. Etendo's boolean columns are
   * {@code character(1)}, and Hibernate's implicit scalar resolution for a native query derives the
   * Java type from the JDBC metadata: {@code JdbcResultMetadata#getHibernateType} sees
   * {@link java.sql.Types#CHAR} with a display size of 1 and {@code Dialect}'s
   * {@code registerHibernateType(Types.CHAR, 1, CHARACTER)} resolves it to {@code CharacterType}.
   * The value therefore arrives as a {@link Character}, not a {@link String}, and
   * {@code "Y".equals(row[0])} is <b>always false</b> — including on a perfectly healthy
   * organization. A mock that stubs {@code uniqueResult()} with a {@code String} passes happily
   * while the real onboarding fails 100% of the time, which is exactly how this shipped.</p>
   *
   * <p>Normalizing through {@link String#valueOf} covers both mappings and does not depend on the
   * driver, the dialect or the column's declared width.</p>
   *
   * @param flag the raw value read from the result row, possibly {@code null}
   * @return {@code true} when the flag is Etendo's {@code 'Y'}
   */
  protected static boolean isYesFlag(Object flag) {
    return flag != null && "Y".equals(String.valueOf(flag));
  }

  /**
   * Re-reads the organization into the DAL session after a native UPDATE changed it underneath.
   */
  protected void refreshOrganization(String orgId) {
    Organization org = resolveOrganization(orgId);
    if (org != null) {
      OBDal.getInstance().getSession().refresh(org);
    }
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
