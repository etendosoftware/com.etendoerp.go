/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.payment.reconcile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

/** ETP-5048 — the small helpers every billing reconciliation phase shares. */
final class ReconciliationSupport {

  private static final Logger log = LogManager.getLogger(ReconciliationSupport.class);

  private ReconciliationSupport() {
  }

  /**
   * Builds the allow-listed {@code PAYLOAD_SUMMARY} of a reconciliation row: provider ids and
   * statuses only, never customer data. A null value is left out.
   *
   * @param keysAndValues alternating keys and values
   * @return compact JSON text, or null when it cannot be built
   */
  static String summary(String... keysAndValues) {
    JSONObject summary = new JSONObject();
    try {
      for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
        if (keysAndValues[i + 1] != null) {
          summary.put(keysAndValues[i], keysAndValues[i + 1]);
        }
      }
    } catch (JSONException e) {
      return null;
    }
    return summary.toString();
  }

  /**
   * Records that one item failed, rolls its pending work back and lets the phase go on with the
   * next item: one bad row must not stop the run.
   *
   * @param result the run's result
   * @param item what failed, e.g. {@code checkout <requestId>}
   * @param e the failure
   */
  static void itemFailed(ReconciliationResult result, String item, RuntimeException e) {
    log.error("{} Reconciling {} failed", ReconciliationAudit.LOG_PREFIX, item, e);
    rollbackQuietly();
    result.error(item + ": " + e.getClass().getSimpleName());
  }

  /** Rolls the current DAL session back without letting a second failure escape. */
  static void rollbackQuietly() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (RuntimeException e) {
      log.debug("Rollback after a failed reconciliation step also failed", e);
    }
  }
}
