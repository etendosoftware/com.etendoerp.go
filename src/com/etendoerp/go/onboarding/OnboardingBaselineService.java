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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

/**
 * Stamps a freshly-provisioned tenant with its BASELINE row in the System-owned data-fix ledger
 * ({@code ETGO_DATA_FIX_HISTORY}). This is the LIVE preventive-front counterpart to the corrective
 * runner's Phase-0 DETECTED sweep (see {@code schema_forge/cli/src/data-fixes/run.js}).
 *
 * <p>It is invoked as the final action of {@code EtendoGoJwtServlet.ensureOnboardingDataset}, before
 * {@code commitDalChanges("onboarding")}, so the baseline row is part of the same atomic onboarding
 * commit. This service is the single source of truth for baseline registration (the live wiring goes
 * through the service chain, not the inert {@code OnboardingStep} classes).</p>
 *
 * <h3>Row shape (non-negotiable, System-owned)</h3>
 * {@code ad_client_id='0'}, {@code ad_org_id='0'} (the ledger is System-owned so only the System
 * Administrator sees every tenant's history in one grid), the new tenant in the dedicated
 * {@code remediated_client_id} FK, {@code fix_id='__baseline__'}, {@code status='BASELINE'},
 * {@code applied_utc=ONBOARDING_PROVISIONED_THROUGH} (a hardcoded CUT — NOT {@code now()} — that
 * represents the last data-fix already incorporated into this version of onboarding; bump it when
 * a new gap is closed on the preventive front). PK generated DB-side via {@code get_uuid()} — never hand-typed.
 *
 * <h3>Idempotency &amp; DETECTED conservation</h3>
 * The insert uses {@code ON CONFLICT ON CONSTRAINT etgo_dfh_tenant_fix_un DO NOTHING}. If a baseline
 * row already exists for this tenant (e.g. a DETECTED row left by the legacy sweep) it is conserved,
 * not overwritten — DETECTED remains the baseline. The 0-row outcome is expected and benign.
 *
 * <h3>Transaction / failure semantics (shared connection)</h3>
 * This runs on the shared DAL connection ({@code OBDal.getInstance().getConnection()}) that the rest
 * of onboarding commits via {@code OBDal.commitAndClose()}. In PostgreSQL a statement error aborts
 * the whole transaction, so swallowing a SQL error here would poison the final onboarding commit.
 * Therefore the contract is:
 * <ul>
 *   <li><b>Expected case</b> ({@code ON CONFLICT} → 0 rows): non-fatal, logged at INFO, no throw.</li>
 *   <li><b>Genuine error</b> (near-impossible — no SELECT dependency, all literals + the just-created
 *       client FK): rethrown as {@link OBException}, never swallowed. The caller lets it propagate to
 *       {@code handleOnboarding}'s catch, which performs a clean {@code rollbackDalChanges}.</li>
 * </ul>
 * This honors "registering the baseline must not abort a healthy onboarding" for every realistic
 * outcome, while avoiding the unsafe swallow-on-poisoned-connection anti-pattern.
 */
public class OnboardingBaselineService {

  private static final Logger log = LogManager.getLogger(OnboardingBaselineService.class);

  /** Sentinel fix id that marks a tenant's baseline/cutoff row (one per tenant via the UNIQUE constraint). */
  private static final String BASELINE_FIX_ID = "__baseline__";

  /** Ledger status for a tenant that finished provisioning clean. */
  private static final String BASELINE_STATUS = "BASELINE";

  /** System owner shape — the ledger is System-owned regardless of the tenant being created. */
  private static final String SYSTEM_ID = "0";

  /**
   * The timestamp of the last data-fix that this version of onboarding already provisions natively.
   * New tenants get this as their BASELINE applied_utc, so the corrective runner skips every fix
   * at-or-before this cutoff (they were never needed — onboarding was already correct when the
   * tenant was created).
   *
   * <p><b>BUMP THIS</b> each time a new gap is closed on the preventive front (i.e. a new
   * Onboarding*Service is added that provisions the same data as the corresponding corrective fix).
   * Use the exact UTC timestamp prefix of the last incorporated .sql file, e.g.:
   * {@code "20260617T120000Z"} matches {@code 20260617T120000Z__R7-tax-accounts.sql}.</p>
   *
   * Current watermark: R13 amortization-table-active (2026-07-08).
   *
   * <p><b>Note (2026-07-06):</b> the sibling in-flight branch {@code feat/bp-category-preventive}
   * (ETP-4402) independently bumps this same constant to {@code 2026-07-01T12:00:00Z} for its
   * {@code R9-bp-category-seed} fix, and this same branch previously bumped it to
   * {@code 2026-07-06T12:00:00Z} for {@code R10-accounting-schema-dimensions}. Multiple in-flight
   * branches touch this single line — expect merge conflicts when they converge; always resolve to
   * the LATEST timestamp so no fix's cutoff is lost.</p>
   */
  private static final Instant ONBOARDING_PROVISIONED_THROUGH = Instant.parse("2026-07-08T10:00:00Z");

  private static final String SQL_INSERT_BASELINE = ""
      + "INSERT INTO etgo_data_fix_history ("
      + "  etgo_data_fix_history_id, ad_client_id, ad_org_id, isactive,"
      + "  created, createdby, updated, updatedby,"
      + "  remediated_client_id, fix_id, status, applied_utc, rows_affected, detail"
      + ") VALUES ("
      + "  get_uuid(), ?, ?, 'Y',"
      + "  now(), ?, now(), ?,"
      + "  ?, ?, ?, ?, 0, NULL"
      + ") ON CONFLICT ON CONSTRAINT etgo_dfh_tenant_fix_un DO NOTHING";

  /**
   * Registers the BASELINE ledger row for the given tenant on the shared DAL connection.
   *
   * @param clientId the freshly-provisioned tenant (becomes {@code remediated_client_id})
   * @throws OBException if {@code clientId} is missing or a genuine SQL error occurs (so the caller
   *     can roll back the shared onboarding transaction cleanly)
   */
  public void registerBaseline(String clientId) {
    if (clientId == null || clientId.isEmpty()) {
      throw new OBException(
          "Cannot register data-fix baseline: onboarding context has no client id");
    }

    try {
      // Flush pending DAL state so the ledger insert runs against a consistent shared connection.
      OBDal.getInstance().flush();

      Connection conn = OBDal.getInstance().getConnection();
      int inserted;
      try (PreparedStatement ps = conn.prepareStatement(SQL_INSERT_BASELINE)) {
        ps.setString(   1, SYSTEM_ID);   // ad_client_id — System-owned
        ps.setString(   2, SYSTEM_ID);   // ad_org_id    — System
        ps.setString(   3, SYSTEM_ID);   // createdby    — System
        ps.setString(   4, SYSTEM_ID);   // updatedby    — System
        ps.setString(   5, clientId);    // remediated_client_id — the new tenant
        ps.setString(   6, BASELINE_FIX_ID);
        ps.setString(   7, BASELINE_STATUS);
        ps.setTimestamp(8, Timestamp.from(ONBOARDING_PROVISIONED_THROUGH)); // applied_utc — fixed CUT
        inserted = ps.executeUpdate();
      }

      if (inserted > 0) {
        log.info("Registered BASELINE data-fix row for new tenant '{}'", clientId);
      } else {
        // ON CONFLICT DO NOTHING fired: a baseline row (BASELINE or DETECTED) already exists.
        // Expected and benign — the existing row is conserved.
        log.info("Baseline data-fix row already present for tenant '{}' — conserved, not overwritten",
            clientId);
      }
    } catch (Exception e) {
      // Never swallow on the shared connection: a real SQL error has already aborted the tx, so we
      // rethrow and let handleOnboarding roll back cleanly instead of poisoning the final commit.
      throw new OBException(
          "Failed to register data-fix baseline for tenant '" + clientId + "': " + e.getMessage(),
          e);
    }
  }
}
