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

package com.etendoerp.go.startup;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.client.kernel.ComponentProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.SessionInfo;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.ReportAccessCatalog;

/**
 * Startup self-healer for missing window/process access on Etendo GO automatic roles.
 *
 * <p>Module-shipped windows (e.g. "Match Rule", Verifactu/SII/TBAI windows) are installed via
 * {@code update.database}, which runs with DB triggers disabled. As a result {@code AD_WINDOW_TRG}
 * never fires for them and automatic roles ({@code ad_role.ismanual='N'}) are left WITHOUT the
 * matching {@code ad_window_access}. The base GOClient sampledata ships no
 * {@code AD_WINDOW_ACCESS.xml} and its roles do not go through onboarding, so on a clean install
 * nothing creates that access.</p>
 *
 * <p>This {@link ApplicationInitializer} closes the gap PREVENTIVELY for new tenants and CORRECTIVELY
 * for existing ones: on every application startup it grants — idempotently — the missing
 * {@link WindowAccess} / {@link ProcessAccess} for every active {@link SFSpec} to all automatic
 * roles of real clients. It only ever INSERTs into the access tables; it never touches
 * {@code AD_WINDOW} nor any {@code AD_ROLE} row, so it cannot trigger {@code AD_ROLE_TRG}'s
 * destructive access rebuild.</p>
 *
 * <p>The grant logic mirrors the onboarding role-access provisioning exactly, so freshly
 * onboarded tenants and self-healed existing tenants converge on the same access set.</p>
 *
 * <p><b>ETP-5402 QA follow-up (2026-09-21) — also self-heals the 9-row Informes-subsection
 * report catalog ({@link ReportAccessCatalog#ROWS}).</b> An automatic (non-manual) role's "full
 * access to everything" is exactly this class's job to realize as real grant rows — it is NOT a
 * runtime bypass (unlike {@code isClientAdmin}, which {@code NeoAccessHelper} already
 * special-cases at request time regardless of grant rows). Every one of this catalog's 5 anchors
 * was confirmed live to have ZERO grant rows for any automatic role, because the SPEC-driven loop
 * above can never reach them: the two pseudo-windows ({@code FINANCIAL_REPORTS_WINDOW_ID}, {@code
 * INVENTORY_STOCK_REPORT_WINDOW_ID}) have NO backing {@code ETGO_SF_SPEC} row at all (0 tabs,
 * permission anchors only), {@code tax-report}'s spec is type {@code "R"} (report) not {@code
 * "P"} so {@link #activeSpecs(String)}'s {@code "P"} filter never selects it, and neither aging
 * process is reachable at all — {@code grantProcessAccess} only ever writes classic {@link
 * ProcessAccess}, with no OBUIAPP {@code obuiapp_process_access} counterpart until this pass added
 * {@link #grantReportAccess}. This mechanism deliberately targets the SAME {@link #targetRoles()}
 * as the spec-driven grants above (every automatic role of a real client, Admin included) — the
 * fixed 5-anchor list is looked up directly by id rather than through an {@code SFSpec}, since 4
 * of the 5 have none.</p>
 */
@ApplicationScoped
@ComponentProvider.Qualifier(NeoAccessStartup.QUALIFIER)
public class NeoAccessStartup extends SessionAwareStartup {

  static final String QUALIFIER = "com.etendoerp.go.startup.NeoAccessStartup";

  private static final Logger log = LogManager.getLogger(NeoAccessStartup.class);

  /** Organization assigned to granted access rows, matching onboarding role-access grants (org '0'). */
  private static final String ORG_ZERO = "0";
  private static final String SYSTEM_CLIENT = "0";

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected String name() {
    return "NeoAccessStartup";
  }

  @Override
  protected void runPass() {
    grantMissingAccess();
  }

  /**
   * Visible for testing: the synchronous grant pass, decoupled from the startup thread and the
   * {@link SessionInfo} wait.
   */
  void grantMissingAccess() {
    long startMs = System.currentTimeMillis();
    log.info("NeoAccessStartup: checking automatic roles for missing NEO window/process access.");
    OBContext.setAdminMode(true);
    try {
      Organization orgZero = OBDal.getInstance().get(Organization.class, ORG_ZERO);
      List<SFSpec> windowSpecs = activeSpecs("W");
      List<SFSpec> processSpecs = activeSpecs("P");

      int rolesProcessed = 0;
      int accessGranted = 0;
      for (Role role : targetRoles()) {
        // Skip system-client roles: they must keep their trigger-managed access untouched.
        if (role.getClient() == null || SYSTEM_CLIENT.equals(role.getClient().getId())) {
          continue;
        }
        int windowGranted = grantWindowAccess(role, orgZero, windowSpecs);
        int processGranted = grantProcessAccess(role, orgZero, processSpecs);
        int reportGranted = grantReportAccess(role, orgZero);
        if (windowGranted + processGranted + reportGranted > 0) {
          log.info("NeoAccessStartup: granted {} window + {} process + {} Informes-report access"
              + " row(s) to role \"{}\" (client {}).", windowGranted, processGranted,
              reportGranted, role.getName(), role.getClient().getId());
        }
        accessGranted += windowGranted + processGranted + reportGranted;
        rolesProcessed++;
      }

      OBDal.getInstance().flush();
      OBDal.getInstance().commitAndClose();
      long elapsedMs = System.currentTimeMillis() - startMs;
      log.info("NeoAccessStartup: processed {} automatic role(s), granted {} missing access row(s)"
          + " in {} ms.", rolesProcessed, accessGranted, elapsedMs);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Automatic roles: active and non-manual ({@code ismanual='N'}). The system-client ({@code '0'})
   * roles are excluded in the caller (filtering an association id directly in the criteria is
   * driver-fragile, so it is done in Java).
   */
  private List<Role> targetRoles() {
    OBCriteria<Role> roles = OBDal.getInstance().createCriteria(Role.class);
    roles.add(Restrictions.eq(Role.PROPERTY_ACTIVE, true));
    roles.add(Restrictions.eq(Role.PROPERTY_MANUAL, false));
    roles.setFilterOnReadableClients(false);
    return roles.list();
  }

  private List<SFSpec> activeSpecs(String specType) {
    OBCriteria<SFSpec> specs = OBDal.getInstance().createCriteria(SFSpec.class);
    specs.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    specs.add(Restrictions.eq(SFSpec.PROPERTY_SPECTYPE, specType));
    specs.setFilterOnReadableClients(false);
    return specs.list();
  }

  private int grantWindowAccess(Role role, Organization orgZero, List<SFSpec> windowSpecs) {
    Set<String> existing = existingWindowIds(role);
    int granted = 0;
    for (SFSpec spec : windowSpecs) {
      if (spec.getADWindow() != null && !existing.contains(spec.getADWindow().getId())) {
        WindowAccess wa = OBProvider.getInstance().get(WindowAccess.class);
        wa.setNewOBObject(true);
        wa.setClient(role.getClient());
        wa.setOrganization(orgZero);
        wa.setRole(role);
        wa.setWindow(spec.getADWindow());
        wa.setEditableField(true);
        OBDal.getInstance().save(wa);
        existing.add(spec.getADWindow().getId());
        granted++;
      }
    }
    return granted;
  }

  private int grantProcessAccess(Role role, Organization orgZero, List<SFSpec> processSpecs) {
    Set<String> existing = existingProcessIds(role);
    int granted = 0;
    for (SFSpec spec : processSpecs) {
      if (spec.getProcess() != null && !existing.contains(spec.getProcess().getId())) {
        ProcessAccess pa = OBProvider.getInstance().get(ProcessAccess.class);
        pa.setNewOBObject(true);
        pa.setClient(role.getClient());
        pa.setOrganization(orgZero);
        pa.setRole(role);
        pa.setProcess(spec.getProcess());
        OBDal.getInstance().save(pa);
        existing.add(spec.getProcess().getId());
        granted++;
      }
    }
    return granted;
  }

  /**
   * ETP-5402 QA follow-up — grants {@code role} whichever of {@link ReportAccessCatalog#ROWS}'
   * 5 anchors it is still missing, dispatching per row on {@link ReportAccessCatalog.Row#kind}
   * since these anchors span two different access tables ({@code AD_Window_Access}/{@code
   * AD_Process_Access} for {@code WINDOW}/{@code CLASSIC_PROCESS} rows, {@code
   * obuiapp_process_access} for {@code OBUIAPP_PROCESS} rows) plus one table {@link
   * #grantProcessAccess} never writes at all. Looked up directly by anchor id (never via {@code
   * SFSpec}, unlike {@link #grantWindowAccess}/{@link #grantProcessAccess} above) — see the class
   * javadoc's ETP-5402 note for why none of the 5 is spec-reachable. A missing/inactive anchor
   * entity (e.g. an id typo, or the row deleted from the DB) is skipped rather than failing the
   * whole pass — {@code OBDal#get} returning {@code null} is treated as "nothing to grant" for
   * that one row, same permissive-skip convention {@link #grantWindowAccess}/{@link
   * #grantProcessAccess} already use for a spec with no linked window/process.
   */
  private int grantReportAccess(Role role, Organization orgZero) {
    Set<String> existingWindowIds = existingWindowIds(role);
    Set<String> existingProcessIds = existingProcessIds(role);
    Set<String> existingObuiappProcessIds = existingObuiappProcessIds(role);
    int granted = 0;
    for (ReportAccessCatalog.Row row : ReportAccessCatalog.ROWS) {
      switch (row.kind) {
        case WINDOW:
          granted += grantWindowAnchor(role, orgZero, row.anchorId, existingWindowIds);
          break;
        case CLASSIC_PROCESS:
          granted += grantClassicProcessAnchor(role, orgZero, row.anchorId, existingProcessIds);
          break;
        case OBUIAPP_PROCESS:
        default:
          granted += grantObuiappProcessAnchor(role, orgZero, row.anchorId, existingObuiappProcessIds);
          break;
      }
    }
    return granted;
  }

  /**
   * Grants {@code role} an {@code AD_Window_Access} row for {@code windowId} unless it already
   * has one or the window itself does not resolve (see {@link #grantReportAccess} javadoc for
   * the permissive-skip convention). One guard-clause branch of that method's per-{@link
   * ReportAccessCatalog.Kind} dispatch, split out to keep each branch's own complexity low.
   *
   * @return {@code 1} if a new grant was created, {@code 0} otherwise
   */
  private int grantWindowAnchor(Role role, Organization orgZero, String windowId,
      Set<String> existingWindowIds) {
    if (existingWindowIds.contains(windowId)) {
      return 0;
    }
    Window window = OBDal.getInstance().get(Window.class, windowId);
    if (window == null) {
      return 0;
    }
    WindowAccess wa = OBProvider.getInstance().get(WindowAccess.class);
    wa.setNewOBObject(true);
    wa.setClient(role.getClient());
    wa.setOrganization(orgZero);
    wa.setRole(role);
    wa.setWindow(window);
    wa.setEditableField(true);
    OBDal.getInstance().save(wa);
    existingWindowIds.add(windowId);
    return 1;
  }

  /**
   * Grants {@code role} an {@code AD_Process_Access} row for {@code processId} unless it already
   * has one or the classic process itself does not resolve. See {@link #grantWindowAnchor}'s
   * javadoc for the shared rationale — this is the {@code CLASSIC_PROCESS} branch counterpart.
   *
   * @return {@code 1} if a new grant was created, {@code 0} otherwise
   */
  private int grantClassicProcessAnchor(Role role, Organization orgZero, String processId,
      Set<String> existingProcessIds) {
    if (existingProcessIds.contains(processId)) {
      return 0;
    }
    Process process = OBDal.getInstance().get(Process.class, processId);
    if (process == null) {
      return 0;
    }
    ProcessAccess pa = OBProvider.getInstance().get(ProcessAccess.class);
    pa.setNewOBObject(true);
    pa.setClient(role.getClient());
    pa.setOrganization(orgZero);
    pa.setRole(role);
    pa.setProcess(process);
    OBDal.getInstance().save(pa);
    existingProcessIds.add(processId);
    return 1;
  }

  /**
   * Grants {@code role} an {@code obuiapp_process_access} row for {@code obuiappProcessId}
   * unless it already has one or the OBUIAPP process itself does not resolve. See {@link
   * #grantWindowAnchor}'s javadoc for the shared rationale — this is the {@code OBUIAPP_PROCESS}
   * branch counterpart.
   *
   * @return {@code 1} if a new grant was created, {@code 0} otherwise
   */
  private int grantObuiappProcessAnchor(Role role, Organization orgZero, String obuiappProcessId,
      Set<String> existingObuiappProcessIds) {
    if (existingObuiappProcessIds.contains(obuiappProcessId)) {
      return 0;
    }
    org.openbravo.client.application.Process obuiappProcess = OBDal.getInstance()
        .get(org.openbravo.client.application.Process.class, obuiappProcessId);
    if (obuiappProcess == null) {
      return 0;
    }
    org.openbravo.client.application.ProcessAccess opa = OBProvider.getInstance()
        .get(org.openbravo.client.application.ProcessAccess.class);
    opa.setNewOBObject(true);
    opa.setClient(role.getClient());
    opa.setOrganization(orgZero);
    opa.setRole(role);
    opa.setObuiappProcess(obuiappProcess);
    OBDal.getInstance().save(opa);
    existingObuiappProcessIds.add(obuiappProcessId);
    return 1;
  }

  /** Every {@code obuiapp_process_id} {@code role} already has active access to. */
  private Set<String> existingObuiappProcessIds(Role role) {
    OBCriteria<org.openbravo.client.application.ProcessAccess> existing = OBDal.getInstance()
        .createCriteria(org.openbravo.client.application.ProcessAccess.class);
    existing.add(Restrictions.eq(
        org.openbravo.client.application.ProcessAccess.PROPERTY_ROLE, role));
    existing.setFilterOnReadableClients(false);
    Set<String> ids = new HashSet<>();
    for (org.openbravo.client.application.ProcessAccess opa : existing.list()) {
      if (opa.getObuiappProcess() != null) {
        ids.add(opa.getObuiappProcess().getId());
      }
    }
    return ids;
  }

  private Set<String> existingWindowIds(Role role) {
    OBCriteria<WindowAccess> existing = OBDal.getInstance().createCriteria(WindowAccess.class);
    existing.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE, role));
    existing.setFilterOnReadableClients(false);
    Set<String> ids = new HashSet<>();
    for (WindowAccess wa : existing.list()) {
      if (wa.getWindow() != null) {
        ids.add(wa.getWindow().getId());
      }
    }
    return ids;
  }

  private Set<String> existingProcessIds(Role role) {
    OBCriteria<ProcessAccess> existing = OBDal.getInstance().createCriteria(ProcessAccess.class);
    existing.add(Restrictions.eq(ProcessAccess.PROPERTY_ROLE, role));
    existing.setFilterOnReadableClients(false);
    Set<String> ids = new HashSet<>();
    for (ProcessAccess pa : existing.list()) {
      if (pa.getProcess() != null) {
        ids.add(pa.getProcess().getId());
      }
    }
    return ids;
  }
}
