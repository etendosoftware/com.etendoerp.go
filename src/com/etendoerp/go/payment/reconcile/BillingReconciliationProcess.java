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
package com.etendoerp.go.payment.reconcile;

import java.util.Map;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.service.db.DalBaseProcess;

import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.startup.BillingReconciliationScheduleStartup;
import com.etendoerp.go.usage.UsageMessages;

/**
 * ETP-5048 — the Classic entry point of the billing reconciliation: the background process
 * {@code ETGO_BillingReconciliation}, on the System menu entry <b>Billing Reconciliation</b> and
 * planted as a System process request by {@code BillingReconciliationScheduleStartup}.
 *
 * <p>Modelled on {@code UsageAggregationProcess} (ETP-5050). The operator reads the outcome in the
 * popup (the {@link OBError} result) and, for scheduled runs, in Process Monitor, where the summary
 * is also written to the run log. A run that met errors, or whose absent-subscription breaker
 * tripped, sets an error result <em>and</em> throws: the interactive popup reads the result, while
 * Process Monitor records ERROR only when an exception leaves the process.
 *
 * <p>Parameter (optional): <b>Dry run</b> ({@code dryrun}, Yes/No). Yes reads and decides
 * everything and writes nothing.
 *
 * <p>The startup plants the scheduled request (the fixed id
 * {@link BillingReconciliationScheduleStartup#REQUEST_ID}) only while the
 * {@value GoFeatureFlags#FLAG_BILLING_RECONCILIATION_SCHEDULE} flag is on. As a safety net a run of
 * that request also does nothing while the flag is off; every other launch runs.
 */
public class BillingReconciliationProcess extends DalBaseProcess {

  private static final Logger log = LogManager.getLogger(BillingReconciliationProcess.class);

  static final String PARAM_DRY_RUN = "dryrun";
  private static final String TITLE_DONE = "Billing Reconciliation Complete";
  private static final String TITLE_FAILED = "Billing Reconciliation Finished With Errors";
  private static final FeatureFlagContext ENVIRONMENT = FeatureFlagContext.forAccount(null);

  private final Supplier<BillingReconciliationService> serviceFactory;

  /** Production wiring. */
  public BillingReconciliationProcess() {
    this(BillingReconciliationService::new);
  }

  BillingReconciliationProcess(Supplier<BillingReconciliationService> serviceFactory) {
    this.serviceFactory = serviceFactory;
  }

  @Override
  public void doExecute(ProcessBundle bundle) throws Exception {
    if (isScheduledRequest(bundle) && !scheduleEnabled()) {
      log.debug("{} Scheduled billing reconciliation skipped: the {} flag is off",
          ReconciliationAudit.LOG_PREFIX, GoFeatureFlags.FLAG_BILLING_RECONCILIATION_SCHEDULE);
      OBError skipped = new OBError();
      skipped.setType("Success");
      skipped.setTitle(TITLE_DONE);
      skipped.setMessage("Scheduled run skipped: the billing-reconciliation-schedule flag is off.");
      bundle.setResult(skipped);
      return;
    }
    boolean dryRun = readDryRun(bundle);
    ReconciliationResult result;
    try {
      result = serviceFactory.get().run(dryRun);
    } catch (RuntimeException e) {
      log.error("{} Billing reconciliation failed", ReconciliationAudit.LOG_PREFIX, e);
      fail(bundle, "Billing Reconciliation Failed", UsageMessages.atSafe(e.getMessage()));
      return;
    }
    String summary = UsageMessages.atSafe(result.toString());
    bundle.getLogger().logln(summary);
    if (result.hasFailures()) {
      fail(bundle, TITLE_FAILED, summary);
    }
    log.info("{} {}", ReconciliationAudit.LOG_PREFIX, summary);
    OBError success = new OBError();
    success.setType("Success");
    success.setTitle(TITLE_DONE);
    success.setMessage(summary);
    bundle.setResult(success);
  }

  private static boolean isScheduledRequest(ProcessBundle bundle) {
    return BillingReconciliationScheduleStartup.REQUEST_ID.equals(
        bundle.getProcessRequestId());
  }

  boolean scheduleEnabled() {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_BILLING_RECONCILIATION_SCHEDULE,
        ENVIRONMENT);
  }

  /**
   * Reads the optional Dry run flag, case-insensitively on the key (the parameter window, a
   * scheduled request and a direct call do not agree on its case) and leniently on the value.
   */
  static boolean readDryRun(ProcessBundle bundle) {
    Map<String, Object> params = bundle.getParams();
    if (params == null) {
      return false;
    }
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(PARAM_DRY_RUN)) {
        Object value = entry.getValue();
        if (value instanceof Boolean) {
          return (Boolean) value;
        }
        String text = String.valueOf(value).trim();
        return "Y".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text)
            || "yes".equalsIgnoreCase(text);
      }
    }
    return false;
  }

  /**
   * Reports a failed run on both surfaces — see {@code UsageAggregationProcess#fail} for why both.
   *
   * @throws OBException always
   */
  private static void fail(ProcessBundle bundle, String title, String message) {
    log.error("{} {}: {}", ReconciliationAudit.LOG_PREFIX, title, message);
    OBError error = new OBError();
    error.setType("Error");
    error.setTitle(title);
    error.setMessage(message);
    bundle.setResult(error);
    throw new OBException(message);
  }
}
