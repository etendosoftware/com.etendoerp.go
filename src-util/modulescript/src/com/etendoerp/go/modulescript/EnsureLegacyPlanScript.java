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
package com.etendoerp.go.modulescript;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.openbravo.database.ConnectionProvider;
import org.openbravo.modulescript.ModuleScript;

/**
 * ETP-5046 — makes sure the grandfathered {@code legacy-productive} plan row exists in
 * {@code ETGO_PLAN}, creating it when it is missing and leaving it strictly alone when it is not.
 *
 * <p><b>Why a module script and not sourcedata.</b> {@code ETGO_PLAN} also holds the priced plans
 * that operators create at runtime (Classic "Plans" window, Stripe catalog). DBSM treats a table in
 * the module's {@code AD} dataset as module-owned as a whole, whatever the dataset's where clause
 * says: a plain {@code update.database} then refuses with "Database has local changes" as soon as
 * one runtime plan exists, and {@code update.database -Dforce=yes} deletes every runtime plan and
 * fails recreating the foreign keys of the subscriptions, checkout requests and quotas that pointed
 * at them. {@code ETGO_PLAN} must therefore never be an {@code AD_DATASET_TABLE}; this script is
 * the only thing that seeds the legacy row.</p>
 *
 * <p><b>When it runs.</b> On every {@code update.database} (no execution limits — the statement is
 * idempotent), after DBSM has applied the model, so the table always exists by then; and on
 * {@code install.source} through {@code import.sample.data}. A failed statement is loud:
 * {@link #handleError} fails the build.</p>
 *
 * <p><b>Self-contained on purpose.</b> Module scripts are loaded before this module's main
 * {@code src/} tree is compiled (see {@code EnsureSystemRoleTemplatesScript}), so this class uses
 * plain SQL and literals and never references {@code PlanCatalogService}. The canonical search key
 * is {@code com.etendoerp.go.payment.PlanCatalogService.LEGACY_PLAN_KEY}; keep
 * {@link #LEGACY_PLAN_KEY} equal to it.</p>
 *
 * <p><b>The compiled class is committed</b>, like {@code EnsureSystemRoleTemplatesScript}'s:
 * {@code update.database} only runs module scripts that are already compiled, and a deploy does not
 * run {@code compile.modulescript}. After ANY edit to this source, re-run
 * {@code ./gradlew compile.modulescript -Dmodule=com.etendoerp.go} and
 * {@code git add -f build/classes/com/etendoerp/go/modulescript/EnsureLegacyPlanScript.class}; a
 * stale class keeps running the old logic with no warning.</p>
 *
 * <p><b>Retirement.</b> When the legacy plan is retired, remove or change this script in the same
 * change (source AND committed class) — otherwise the next {@code update.database} recreates the
 * row.</p>
 */
public class EnsureLegacyPlanScript extends ModuleScript {

  /** Fixed id the row has carried since it first shipped; subscriptions reference it. */
  static final String LEGACY_PLAN_ID = "219D5C8E15C64E97B2F553B228D30DD0";

  /** Literal copy of {@code PlanCatalogService.LEGACY_PLAN_KEY}. */
  static final String LEGACY_PLAN_KEY = "legacy-productive";

  static final String LEGACY_PLAN_NAME = "Legacy Productive (grandfathered)";

  static final String LEGACY_PLAN_DESCRIPTION = "Grandfathered plan for pre-catalog productive "
      + "tenants. No price of its own, no quotas (unlimited). Sold only via the legacy price "
      + "fallback: at etendo.go.checkout.price.id while no plan has a provider price; the first "
      + "priced plan retires it.";

  /**
   * One idempotent statement. The price columns stay NULL (the row is unpriced by design, which
   * {@code ETGO_PLAN_PRICED_CHK} allows). The guard matches on the key AND on the id, so neither
   * {@code ETGO_PLAN_VALUE_UQ} nor the primary key can ever reject it.
   */
  static final String INSERT_IF_MISSING_SQL = "INSERT INTO ETGO_Plan (ETGO_Plan_ID, AD_Client_ID, "
      + "AD_Org_ID, IsActive, Created, CreatedBy, Updated, UpdatedBy, Value, Name, Description) "
      + "SELECT ?, '0', '0', 'Y', now(), '0', now(), '0', ?, ?, ? "
      + "WHERE NOT EXISTS (SELECT 1 FROM ETGO_Plan WHERE Value = ? OR ETGO_Plan_ID = ?)";

  static final String KEY_EXISTS_SQL = "SELECT 1 FROM ETGO_Plan WHERE Value = ?";

  @Override
  public void execute() {
    try {
      ensureLegacyPlan(getConnectionProvider());
    } catch (Exception e) {
      handleError(e);
    }
  }

  /**
   * Inserts the legacy plan when no row carries its key or its id.
   *
   * @return the number of rows inserted: 1 on a database that lacked the row, 0 otherwise
   */
  int ensureLegacyPlan(ConnectionProvider cp) throws Exception {
    int inserted;
    try (PreparedStatement ps = cp.getPreparedStatement(INSERT_IF_MISSING_SQL)) {
      ps.setString(1, LEGACY_PLAN_ID);
      ps.setString(2, LEGACY_PLAN_KEY);
      ps.setString(3, LEGACY_PLAN_NAME);
      ps.setString(4, LEGACY_PLAN_DESCRIPTION);
      ps.setString(5, LEGACY_PLAN_KEY);
      ps.setString(6, LEGACY_PLAN_ID);
      inserted = ps.executeUpdate();
    }
    if (inserted > 0) {
      log4j.info("Created the grandfathered " + LEGACY_PLAN_KEY + " plan (" + LEGACY_PLAN_ID + ")");
    } else if (!keyExists(cp)) {
      // Only reachable when the fixed id was re-keyed by hand: the fallback will not find the plan.
      log4j.error("ETGO_Plan " + LEGACY_PLAN_ID + " exists under a search key other than "
          + LEGACY_PLAN_KEY + "; the legacy price fallback cannot find the grandfathered plan. "
          + "Restore its search key to " + LEGACY_PLAN_KEY + ".");
    }
    return inserted;
  }

  private boolean keyExists(ConnectionProvider cp) throws Exception {
    try (PreparedStatement ps = cp.getPreparedStatement(KEY_EXISTS_SQL)) {
      ps.setString(1, LEGACY_PLAN_KEY);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }
}
