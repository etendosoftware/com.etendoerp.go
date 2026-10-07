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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.webhooks;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.Role;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.ReportAccessCatalog;
import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * ETP-5402 QA follow-up — hands the CURRENT authenticated caller's own Informes-subsection report
 * access up front, mirroring {@link SFWindowAccessMap}'s exact "current role, proactive, open to
 * any authenticated role" shape (as opposed to {@link SFRolesOverview}, which is a cross-role
 * aggregate gated to admin/client-admin callers only — see that class's own javadoc for why it
 * cannot serve this need: a regular Sales/Finance/... role calling it gets an empty result).
 *
 * <p><b>Why this exists.</b> {@code ReportViewerPage.jsx}'s real gate ({@code
 * REPORT_CATEGORY_WINDOW_IDS}) only checks ONE coarse window per category and, once past it,
 * shows every card in that category with zero per-report filtering — so the per-report tiers
 * {@link ReportAccessCatalog} already resolves for the Roles/Users matrix were, until this
 * webhook, purely decorative: nothing in the real report-viewer consulted them. This endpoint is
 * the data source the frontend needs to (a) show the "Informes" sidebar link for a category
 * whenever the caller's role has access to AT LEAST ONE report in it, even without the coarse
 * window grant (e.g. Sales holds {@code aging-receivable} but not the Financial Reports window),
 * and (b) filter the report-viewer's own gallery cards down to only the reports the caller
 * actually has a tier for.</p>
 *
 * <p>Resolution order (mirrors {@link SFWindowAccessMap}):</p>
 * <ol>
 *   <li>No role assigned → {@code {"reportAccess": {}}}.</li>
 *   <li>Admin/client-admin bypass ({@link NeoAccessHelper#isAdminOrClientAdmin(Role)}) → every
 *       one of {@link ReportAccessCatalog#ROWS}' 9 rows resolves to {@link
 *       ReportAccessCatalog#FULL} — the same "full access to everything, no grant rows needed"
 *       contract {@code SFWindowAccessMap} already gives admin for real windows.</li>
 *   <li>Otherwise, {@link ReportAccessCatalog#resolveTierMap(Role)} for the resolved role — a
 *       report with tier {@link ReportAccessCatalog#NONE} is simply absent from the map, matching
 *       {@code windowAccess}'s own "missing key means none" convention.</li>
 * </ol>
 *
 * GET /webhooks/SFMyReportAccess (reached via the NEO pseudo-spec bridge, {@code
 * NeoPseudoSpecDispatcher}'s {@code "myreportaccess"} case — see {@code docs/neo-headless.md}
 * §4.10–4.11 for why new Etendo-GO-authored webhooks default to that bridge).
 */
public class SFMyReportAccess extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFMyReportAccess.class);

  /** JSON key used for the per-report access-tier map. */
  private static final String REPORT_ACCESS = "reportAccess";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    // Capture the real current role BEFORE entering admin mode — same convention as
    // SFWindowAccessMap/SFListMenu: access decisions must always be made against the role
    // actually resolved for this request, never against whatever the ambient OBContext exposes
    // once admin mode is active.
    Role currentRole = NeoAccessHelper.resolveCurrentRole();

    if (currentRole == null) {
      responseVars.put("result", emptyResult().toString());
      return;
    }

    OBContext.setAdminMode();
    try {
      JSONObject result = buildResult(currentRole);
      responseVars.put("result", result.toString());
    } catch (Exception e) {
      log.error("Error in SFMyReportAccess", e);
      responseVars.put("error", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Builds the empty result used when the current request has no role assigned.
   */
  private static JSONObject emptyResult() {
    try {
      JSONObject result = new JSONObject();
      result.put(REPORT_ACCESS, new JSONObject());
      return result;
    } catch (JSONException e) {
      // JSONObject#put never throws for a non-null key; unreachable in practice.
      throw new IllegalStateException("Unable to build empty report-access result", e);
    }
  }

  /**
   * Builds the {@code reportAccess} result for {@code role}, branching on the admin/client-admin
   * bypass first (see class javadoc for the full resolution order).
   */
  private JSONObject buildResult(Role role) throws JSONException {
    Map<String, String> reportTiers;
    if (NeoAccessHelper.isAdminOrClientAdmin(role)) {
      reportTiers = new LinkedHashMap<>();
      for (ReportAccessCatalog.Row row : ReportAccessCatalog.ROWS) {
        reportTiers.put(row.id, ReportAccessCatalog.FULL);
      }
    } else {
      reportTiers = ReportAccessCatalog.resolveTierMap(role);
    }

    JSONObject reportAccess = new JSONObject();
    for (Map.Entry<String, String> entry : reportTiers.entrySet()) {
      if (!ReportAccessCatalog.NONE.equals(entry.getValue())) {
        reportAccess.put(entry.getKey(), entry.getValue());
      }
    }

    JSONObject result = new JSONObject();
    result.put(REPORT_ACCESS, reportAccess);
    return result;
  }
}
