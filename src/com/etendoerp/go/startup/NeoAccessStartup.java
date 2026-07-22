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
import org.openbravo.client.kernel.ApplicationInitializer;
import org.openbravo.client.kernel.ComponentProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.SessionInfo;
import org.openbravo.model.ad.access.ProcessAccess;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.SFSpec;

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
 */
@ApplicationScoped
@ComponentProvider.Qualifier(NeoAccessStartup.QUALIFIER)
public class NeoAccessStartup implements ApplicationInitializer {

  static final String QUALIFIER = "com.etendoerp.go.startup.NeoAccessStartup";

  private static final Logger log = LogManager.getLogger(NeoAccessStartup.class);

  /** Organization assigned to granted access rows, matching onboarding role-access grants (org '0'). */
  private static final String ORG_ZERO = "0";
  private static final String SYSTEM_CLIENT = "0";

  /** Poll interval while waiting for the DB session info to be initialized. */
  private static final long SESSION_POLL_MS = 100L;
  /** Hard cap before proceeding regardless of session-info state. */
  private static final long SESSION_WAIT_TIMEOUT_MS = 60_000L;

  @Override
  public void initialize() {
    // Run asynchronously so we never block (nor fail) the application startup sequence, and so we
    // can wait for SessionInfo to be initialized before borrowing a DAL connection (borrowing one
    // too early hits the ad_context_info temp-table problem).
    Thread worker = new Thread(this::runSafely, "NeoAccessStartup-grant");
    worker.setDaemon(true);
    worker.start();
  }

  private void runSafely() {
    try {
      waitForSessionInfoInitialized();
      grantMissingAccess();
    } catch (Exception e) {
      // Startup self-healing must never break the application: log and continue.
      log.error("NeoAccessStartup: failed to grant missing NEO access; skipping.", e);
      try {
        OBDal.getInstance().rollbackAndClose();
      } catch (Exception rollbackError) {
        log.debug("NeoAccessStartup: rollback after failure also failed.", rollbackError);
      }
    }
  }

  private void waitForSessionInfoInitialized() {
    long deadline = System.currentTimeMillis() + SESSION_WAIT_TIMEOUT_MS;
    while (!SessionInfo.isInitialized() && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(SESSION_POLL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    if (!SessionInfo.isInitialized()) {
      log.warn("NeoAccessStartup: SessionInfo not initialized after {} ms; proceeding anyway.",
          SESSION_WAIT_TIMEOUT_MS);
    }
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
        if (windowGranted + processGranted > 0) {
          log.info("NeoAccessStartup: granted {} window + {} process access row(s) to role \"{}\""
              + " (client {}).", windowGranted, processGranted, role.getName(),
              role.getClient().getId());
        }
        accessGranted += windowGranted + processGranted;
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
