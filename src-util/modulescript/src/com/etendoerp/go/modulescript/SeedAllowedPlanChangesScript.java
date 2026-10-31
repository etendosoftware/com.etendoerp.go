/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.modulescript;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.openbravo.database.ConnectionProvider;
import org.openbravo.modulescript.ModuleScript;

/**
 * ETP-5053 — seeds the <b>Allowed plan changes</b> configuration ({@code ETGO_PLAN_CHANGE}) the
 * first time an environment is updated with the table, from the plan catalog that environment
 * already has.
 *
 * <p><b>Why a module script.</b> Plans are operator data, different in every environment, so the
 * seed cannot ship as sourcedata; and the tenant data-fix runner deliberately never touches the
 * System client these rows belong to. A module script runs once per {@code update.database}, on
 * every environment, with the catalog in front of it.
 *
 * <p><b>Only into an empty table.</b> Operators maintain the rows afterwards (the ticket's rule:
 * the direction is declared by the row, never inferred), so the seed must never overwrite,
 * re-add or remove what an operator decided. One row in the table, active or not, and this
 * script does nothing.
 *
 * <p><b>The seeded rule</b> (ETP-5053): within one billing interval, a higher tier applies
 * immediately (an upgrade) and a lower tier at the end of the period (a downgrade); monthly to
 * annual of the same or a higher tier applies immediately; nothing is seeded from annual to
 * monthly. The catalog has no tier column, so for this one-off seed the tier is the plan's rank by
 * display price within its interval and currency — the proxy that the ticket forbids at
 * <em>run time</em> is acceptable once, at seed time, because the operator reviews the result in
 * the Plans window. Only active plans with a provider price, a display price and a currency take
 * part: the grandfathered legacy plan has none and is skipped, and plans in different currencies
 * are never joined.
 */
public class SeedAllowedPlanChangesScript extends ModuleScript {

  static final String COUNT_SQL = "SELECT count(*) FROM etgo_plan_change";

  /**
   * One statement, guarded twice: the {@code NOT EXISTS} makes it a no-op when a row already
   * exists, so a concurrent or repeated run can never add to an operator's rows.
   */
  static final String SEED_SQL = "INSERT INTO etgo_plan_change (etgo_plan_change_id, ad_client_id,"
      + " ad_org_id, isactive, created, createdby, updated, updatedby, etgo_plan_id,"
      + " target_plan_id, timing)"
      + " SELECT get_uuid(), '0', '0', 'Y', now(), '0', now(), '0', f.etgo_plan_id, t.etgo_plan_id,"
      + " CASE WHEN f.billing_interval = t.billing_interval AND t.tier < f.tier"
      + " THEN 'period_end' ELSE 'immediate' END"
      + " FROM ("
      + "   SELECT p.etgo_plan_id, p.billing_interval, p.currency_code,"
      + "     dense_rank() OVER (PARTITION BY p.billing_interval, p.currency_code"
      + "       ORDER BY p.display_price) AS tier"
      + "   FROM etgo_plan p"
      + "   WHERE p.isactive = 'Y' AND p.provider_price_id IS NOT NULL"
      + "     AND p.display_price IS NOT NULL AND p.currency_code IS NOT NULL"
      + "     AND p.billing_interval IN ('month', 'year')"
      + " ) f"
      + " JOIN ("
      + "   SELECT p.etgo_plan_id, p.billing_interval, p.currency_code,"
      + "     dense_rank() OVER (PARTITION BY p.billing_interval, p.currency_code"
      + "       ORDER BY p.display_price) AS tier"
      + "   FROM etgo_plan p"
      + "   WHERE p.isactive = 'Y' AND p.provider_price_id IS NOT NULL"
      + "     AND p.display_price IS NOT NULL AND p.currency_code IS NOT NULL"
      + "     AND p.billing_interval IN ('month', 'year')"
      + " ) t ON t.currency_code = f.currency_code AND t.etgo_plan_id <> f.etgo_plan_id"
      + " WHERE ((f.billing_interval = t.billing_interval AND f.tier <> t.tier)"
      + "   OR (f.billing_interval = 'month' AND t.billing_interval = 'year'"
      + "     AND t.tier >= f.tier))"
      + "   AND NOT EXISTS (SELECT 1 FROM etgo_plan_change)";

  @Override
  public void execute() {
    try {
      seed(getConnectionProvider());
    } catch (Exception e) {
      handleError(e);
    }
  }

  /**
   * Seeds the table when it is empty.
   *
   * @return the number of rows inserted; 0 when the table already had rows or the catalog offers
   *     no transition
   */
  int seed(ConnectionProvider cp) throws Exception {
    if (countRows(cp) > 0) {
      return 0;
    }
    int inserted;
    try (PreparedStatement ps = cp.getPreparedStatement(SEED_SQL)) {
      inserted = ps.executeUpdate();
    }
    if (inserted > 0) {
      log4j.info("Seeded " + inserted + " allowed plan change row(s) from the plan catalog;"
          + " review them in the Plans window, Allowed Plan Changes tab");
    } else {
      log4j.info("No allowed plan change was seeded: the catalog offers no transition between"
          + " priced plans. Rows can be added in the Plans window, Allowed Plan Changes tab");
    }
    return inserted;
  }

  private long countRows(ConnectionProvider cp) throws Exception {
    try (PreparedStatement ps = cp.getPreparedStatement(COUNT_SQL);
        ResultSet rs = ps.executeQuery()) {
      return rs.next() ? rs.getLong(1) : 0L;
    }
  }
}
