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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge.webhooks;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.onboarding.OnboardingCostingScheduleService;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService.ClientOutcome;
import com.etendoerp.go.onboarding.OnboardingCostingScheduleService.RealignReport;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * ETP-5370 — one-shot remediation endpoint that brings existing tenants to the costing-schedule
 * invariant new tenants are born with: <b>exactly ONE active scheduled {@code CostingBackground}
 * request per client, firing every 30 seconds</b>.
 *
 * <h2>Why this exists as a webhook and not as a data-fix {@code .sql}</h2>
 *
 * <p>The cadence lives in two places that a SQL {@code UPDATE} cannot keep in sync: the
 * {@code AD_PROCESS_REQUEST} row, and the Quartz trigger that was armed from it. Once armed, the
 * trigger is the only thing that decides when the process runs —
 * {@code OBScheduler.initialize()} reads the table exactly ONCE at startup, and
 * {@code DefaultJob.execute} rebuilds its bundle from Quartz's own {@code JobDataMap}, never
 * re-reading the row. The same conclusion was reached independently on ETP-5269 and is written up
 * in {@link SFAcctProcessMonitor}'s class javadoc ("Refuted from source"), and the PSD2
 * schedule-removal data-fix records that even DELETING the row leaves the job firing. Production
 * does not restart Tomcat, so a {@code .sql} would leave every tenant's row claiming 30 seconds
 * while the trigger kept firing every 5 minutes — worse than doing nothing, because the row would
 * then be lying. The correction has to run inside the live JVM; this endpoint is how it is reached.
 *
 * <p>The work itself lives in {@link OnboardingCostingScheduleService#realignCadence(String)}, next
 * to the provisioning code whose shape it has to match — one implementation, no SQL/Java drift.
 *
 * <h2>Access and scope</h2>
 *
 * <p>Gated on {@link NeoAccessHelper#isAdminOrClientAdmin(Role)}, enforced server-side, like the
 * rest of this webhook family. Two scopes:</p>
 * <ul>
 *   <li>{@code scope=client} (the DEFAULT) — realigns the CALLER'S OWN client only. A tenant admin
 *       can fix their own instance and can never reach anyone else's.</li>
 *   <li>{@code scope=all} — sweeps every tenant in one call, and is refused unless the caller is in
 *       the System client ({@code '0'}). This is a fleet-wide operator action, not a tenant one;
 *       letting a tenant admin re-arm 74 other tenants' Quartz jobs would be a privilege
 *       escalation, so the check is on the CLIENT, not only on the role.</li>
 * </ul>
 *
 * <p>Denial answers with a payload rather than a 403, matching the "answer, don't 403" convention
 * the sibling webhooks use ({@code NeoGoWebhookBridge} maps {@code responseVars["error"]} to HTTP
 * 500, so a refusal must not travel as an error).</p>
 *
 * <h2>Idempotency, and why it still re-arms every time</h2>
 *
 * <h2>What it does NOT do</h2>
 *
 * <p><b>It never CREATES a missing schedule.</b> A client with zero active {@code SCH} requests is
 * simply absent from the response — it is not an error and not a silent success, there is just
 * nothing to realign. Provisioning a missing one is a different problem with a different answer
 * (resolving an org plus a security context for a tenant nobody onboarded through this code), and it
 * already has an owner: onboarding step 8 for new tenants, and the ETP-5245 data-fix
 * {@code R36-costing-background-schedule} for existing ones. Read an empty {@code clients} array as
 * "nothing here was misconfigured", never as "every tenant is covered".
 *
 * <p>Calling it twice is safe. The per-client {@code status} distinguishes a row that actually
 * needed changing ({@code realigned}) from one that was already correct ({@code alreadyCorrect}) —
 * but the surviving request's trigger is re-armed in BOTH cases, deliberately. The row is not
 * evidence about the live trigger; that is the entire premise above. Re-arming is the only thing
 * that can guarantee the invariant, and at a 30-second cadence resetting the trigger phase costs
 * nothing.</p>
 */
public class SFCostingCadence extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFCostingCadence.class);

  private static final String PARAM_SCOPE = "scope";
  private static final String SCOPE_ALL = "all";
  private static final String SYSTEM_CLIENT = "0";

  private static final String RESPONSE_VAR_RESULT = "result";
  private static final String FIELD_ERROR = "error";
  private static final String FIELD_SUCCESS = "success";
  private static final String FIELD_REASON = "reason";

  private static final String REASON_NOT_AUTHORIZED = "notAuthorized";
  private static final String REASON_SYSTEM_SCOPE_REQUIRED = "systemScopeRequired";
  private static final String REASON_SCHEDULER_UNAVAILABLE = "schedulerUnavailable";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    try {
      Role role = NeoAccessHelper.resolveCurrentRole();
      if (role == null || !NeoAccessHelper.isAdminOrClientAdmin(role)) {
        responseVars.put(RESPONSE_VAR_RESULT, refusal(REASON_NOT_AUTHORIZED).toString());
        return;
      }

      boolean sweepAll = SCOPE_ALL
          .equalsIgnoreCase(StringUtils.trimToEmpty(parameter.get(PARAM_SCOPE)));
      String callerClientId = OBContext.getOBContext().getCurrentClient().getId();
      if (sweepAll && !SYSTEM_CLIENT.equals(callerClientId)) {
        responseVars.put(RESPONSE_VAR_RESULT, refusal(REASON_SYSTEM_SCOPE_REQUIRED).toString());
        return;
      }

      // null = every tenant; otherwise the caller's own client.
      String clientFilter = sweepAll ? null : callerClientId;
      RealignReport report = scheduleService().realignCadence(clientFilter);
      if (!report.isSchedulerAvailable()) {
        // A node under the no-execute background policy leaves Quartz in standby, where
        // schedule/reschedule silently no-op. Reporting success there would be a lie.
        responseVars.put(RESPONSE_VAR_RESULT, refusal(REASON_SCHEDULER_UNAVAILABLE).toString());
        return;
      }
      responseVars.put(RESPONSE_VAR_RESULT, buildResult(report, sweepAll).toString());
    } catch (JSONException e) {
      log.error("Error building SFCostingCadence response", e);
      responseVars.put(FIELD_ERROR, e.getMessage());
    }
  }

  /**
   * The remediation routine, behind a package-visible seam so tests can substitute it without
   * reflection — the same override-a-seam shape the service itself uses for {@code rearm} and
   * {@code schedulingAllowed}. The service holds no state, so a fresh instance per call is fine.
   */
  OnboardingCostingScheduleService scheduleService() {
    return new OnboardingCostingScheduleService();
  }

  /** A refused call is a successful HTTP answer carrying {@code success:false} plus a reason. */
  private JSONObject refusal(String reason) throws JSONException {
    JSONObject payload = new JSONObject();
    payload.put(FIELD_SUCCESS, false);
    payload.put(FIELD_REASON, reason);
    return payload;
  }

  /** Per-client detail plus the four counters an operator needs to read the run at a glance. */
  private JSONObject buildResult(RealignReport report, boolean sweepAll) throws JSONException {
    JSONArray clients = new JSONArray();
    int realigned = 0;
    int alreadyCorrect = 0;
    int failed = 0;
    int deactivated = 0;

    for (ClientOutcome outcome : report.getOutcomes()) {
      JSONObject entry = new JSONObject();
      entry.put("clientId", outcome.getClientId());
      entry.put("clientName", outcome.getClientName());
      entry.put("status", outcome.getStatus());
      entry.put("requestId", outcome.getRequestId());
      entry.put("deactivated", outcome.getDeactivated());
      if (outcome.getDetail() != null) {
        entry.put("detail", outcome.getDetail());
      }
      clients.put(entry);

      deactivated += outcome.getDeactivated();
      if (ClientOutcome.REALIGNED.equals(outcome.getStatus())) {
        realigned++;
      } else if (ClientOutcome.ALREADY_CORRECT.equals(outcome.getStatus())) {
        alreadyCorrect++;
      } else {
        failed++;
      }
    }

    JSONObject result = new JSONObject();
    result.put(FIELD_SUCCESS, failed == 0);
    result.put(PARAM_SCOPE, sweepAll ? SCOPE_ALL : "client");
    result.put("clients", clients);
    result.put("realigned", realigned);
    result.put("alreadyCorrect", alreadyCorrect);
    result.put("failed", failed);
    result.put("deactivated", deactivated);
    return result;
  }
}
