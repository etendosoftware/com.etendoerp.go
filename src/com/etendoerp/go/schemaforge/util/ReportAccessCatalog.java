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

package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

/**
 * ETP-5402 — the "Informes" (Reports) subsection catalog: the exact 9 reports the real
 * `report-viewer` gallery shows (`ReportViewerPage.jsx`'s own `REPORT_PREVIEW_IMAGES` keys /
 * each report's `artifacts/&lt;id&gt;/report-contract.json` — 8 under Finance, 1 under
 * Inventory), and the shared tier-resolution logic for them, factored out so BOTH {@code
 * SFRolesOverview} ("Configuración &gt; Roles") and {@code SFSystemRoleTemplates} (the User
 * window's "Roles del usuario" tab matrix columns) resolve a role's Informes access identically
 * — the two webhooks must never drift on which anchor id/category/kind backs a given row.
 *
 * <p><b>Corrected inventory (2026-09-21, live QA against a running environment) — this is NOT
 * the same 10-row list an earlier pass of this class shipped with.</b> The original inventory
 * mistakenly included 6 rows tied to {@code ETGO_SF_ENTITY.ad_tab_id} pointing at the
 * "Financial Account" window ({@code bank-statements}, {@code bank-reconciliation}, {@code
 * cash-close}, {@code financial-account-transactions}, {@code financial-account-bank-connection},
 * {@code financial-accounts-page}) — none of which is an actual report-viewer gallery card
 * (confirmed live: the "Informes" sidebar link only ever renders 9 cards, and only one of those
 * 6 ids — {@code financial-accounts-page} — even exists as an {@code ETGO_SF_SPEC} row at all).
 * It also completely missed 5 real gallery cards that have NO {@code ETGO_SF_SPEC} row of their
 * own — {@code balance-sheet}, {@code profit-loss}, {@code report-general-ledger}, {@code
 * report-journal-entries}, {@code report-trial-balance} — served entirely outside the
 * Schema-Forge spec pipeline. Confirmed via direct DB query (2026-09-21): only {@code
 * tax-report}, {@code aging-receivable}, {@code inventory-stock-report} and {@code
 * financial-accounts-page} exist as {@code ETGO_SF_SPEC} rows at all; the 5 new reports have
 * none. Their only real access gate is the SAME coarse, category-level pseudo-window
 * {@code ReportViewerPage.jsx}'s own {@code REPORT_CATEGORY_WINDOW_IDS.finance} already uses to
 * gate the whole "Informes" sidebar link for Finance — {@link #FINANCIAL_REPORTS_WINDOW_ID}
 * ("Informes financieros" / Financial Reports, {@code D647D118F5014D00AF47A636B2CD0DD3}, 0 tabs,
 * never opened directly, already granted Financiero-only per {@code
 * TemplateRoleWindowAccess#financeGrants()}) — so these 5 rows resolve through it too, at
 * per-row granularity (there being nothing finer-grained to resolve against).
 *
 * <p>{@code aging-receivable} is represented as TWO rows ({@code aging-receivable} / {@code
 * aging-payable}) — matching how {@code TemplateRoleWindowAccess} and the frontend's {@code
 * REPORT_PREVIEW_IMAGES} already treat Cobros/Pagos as two distinct identities gated by two
 * different OBUIAPP process grants (receivable vs. payable). <b>ETP-5483</b> gave {@code
 * aging-payable} its own {@code ETGO_SF_SPEC} row and its own handler ({@code
 * AgingPayableReportHandler}, {@code @Named("agingPayableReportHandler")}), mirroring Classic's
 * two separate "Aging Balance Process Definition" reports — before that change both rows were
 * served by the single {@code aging-receivable} spec and {@code AgingReportHandler}'s {@code
 * recOrPay} switch. This catalog's rows and anchor ids did not need to change either way: it
 * resolves access by OBUIAPP process id, not by which spec/handler ultimately serves the report.
 */
public final class ReportAccessCatalog {

  private ReportAccessCatalog() {
    // Static utility, never instantiated.
  }

  /** Access-tier value for a report a role can fully use. */
  public static final String FULL = "full";

  /** Access-tier value for a report a role can only read. */
  public static final String READ_ONLY = "read-only";

  /** Access-tier value for a report a role cannot reach at all. */
  public static final String NONE = "none";

  /** Which access-control mechanism a {@link Row} resolves its tier through. */
  public enum Kind { WINDOW, OBUIAPP_PROCESS, CLASSIC_PROCESS }

  /**
   * A single Informes-subsection report row: its stable id (matches the report's {@code
   * artifacts/} directory name / {@code REPORT_PREVIEW_IMAGES} key, except for the {@code
   * aging-receivable}/{@code aging-payable} split above), a fallback display name (the
   * frontend's {@code menu.json} is expected to override it, same convention as {@code
   * SFRolesOverview}'s {@code PROXY_MATRIX_ROWS}), which access mechanism to resolve it
   * through, the anchor id in that mechanism's own id-space, and the hardcoded category it
   * belongs to (a report row's category cannot be derived from the classic {@code AD_Menu}
   * tree the way a real window's can, so it is a human-assigned constant here).
   */
  public static final class Row {
    /** Stable id, matching the report's {@code artifacts/} directory name. */
    public final String id;
    /** Fallback display name, expected to be overridden by the frontend's {@code menu.json}. */
    public final String name;
    /** Which access-control mechanism this row resolves its tier through. */
    public final Kind kind;
    /** The anchor id in {@link #kind}'s own id-space (window/OBUIAPP-process/classic-process id). */
    public final String anchorId;
    /** Hardcoded Informes category this row belongs to (see {@link #FINANCE_CATEGORY}/{@link #INVENTORY_CATEGORY}). */
    public final String category;

    Row(String id, String name, Kind kind, String anchorId, String category) {
      this.id = id;
      this.name = name;
      this.kind = kind;
      this.anchorId = anchorId;
      this.category = category;
    }
  }

  /** Classic {@code AD_Process_ID} gating {@code tax-report} — mirrors {@code TaxReportHandler}. */
  public static final String TAX_REPORT_PROCESS_ID = "8C1331B9EC14CED7E040007F010119A0";

  /** OBUIAPP process id gating the receivable ("Cobros") tier of {@code aging-receivable}. */
  public static final String AGING_RECEIVABLE_PROCESS_ID = "0D37A9F6109549DEB058373EF2DAEB6A";

  /** OBUIAPP process id gating the payable ("Pagos") tier of {@code aging-receivable}. */
  public static final String AGING_PAYABLE_PROCESS_ID = "EB4C4053F3B94A17A08D1DD7E89CEB7E";

  /** {@code AD_Window_ID} of the tab-less pseudo-window gating {@code inventory-stock-report}. */
  public static final String INVENTORY_STOCK_REPORT_WINDOW_ID = "6346B88619F948F9A42224BDB0B239FA";

  /**
   * {@code AD_Window_ID} of "Informes financieros" / Financial Reports — a real, active,
   * tab-less pseudo-window (confirmed live) with NO backing {@code ETGO_SF_SPEC}, granted
   * Financiero-only (see {@code TemplateRoleWindowAccess#financeGrants()}). This is the SAME
   * anchor {@code ReportViewerPage.jsx}'s own {@code REPORT_CATEGORY_WINDOW_IDS.finance} uses
   * to gate the whole Finance "Informes" sidebar link — the 5 reports below have no
   * finer-grained access control of their own to resolve against (see the class javadoc's
   * "Corrected inventory" note), so this coarse, category-level gate is genuinely their only
   * real access boundary.
   */
  public static final String FINANCIAL_REPORTS_WINDOW_ID = "D647D118F5014D00AF47A636B2CD0DD3";

  /** Hardcoded Informes category for the Finance-family report rows. */
  public static final String FINANCE_CATEGORY = "Finance";

  /** Hardcoded Informes category for the Inventory-family report rows. */
  public static final String INVENTORY_CATEGORY = "Inventory";

  /** The 9 Informes-subsection report rows, in declaration order. */
  public static final List<Row> ROWS = List.of(
      new Row("tax-report", "Tax Report",
          Kind.CLASSIC_PROCESS, TAX_REPORT_PROCESS_ID, FINANCE_CATEGORY),
      new Row("aging-receivable", "Aging Report (Receivables)",
          Kind.OBUIAPP_PROCESS, AGING_RECEIVABLE_PROCESS_ID, FINANCE_CATEGORY),
      new Row("aging-payable", "Aging Report (Payables)",
          Kind.OBUIAPP_PROCESS, AGING_PAYABLE_PROCESS_ID, FINANCE_CATEGORY),
      new Row("balance-sheet", "Balance Sheet",
          Kind.WINDOW, FINANCIAL_REPORTS_WINDOW_ID, FINANCE_CATEGORY),
      new Row("profit-loss", "Profit & Loss",
          Kind.WINDOW, FINANCIAL_REPORTS_WINDOW_ID, FINANCE_CATEGORY),
      new Row("report-general-ledger", "General Ledger",
          Kind.WINDOW, FINANCIAL_REPORTS_WINDOW_ID, FINANCE_CATEGORY),
      new Row("report-journal-entries", "Journal Entries",
          Kind.WINDOW, FINANCIAL_REPORTS_WINDOW_ID, FINANCE_CATEGORY),
      new Row("report-trial-balance", "Trial Balance",
          Kind.WINDOW, FINANCIAL_REPORTS_WINDOW_ID, FINANCE_CATEGORY),
      new Row("inventory-stock-report", "Stock Report",
          Kind.WINDOW, INVENTORY_STOCK_REPORT_WINDOW_ID, INVENTORY_CATEGORY));

  /**
   * Resolves the 9-row Informes tier map for {@code role}, one entry per {@link #ROWS} id,
   * dispatching per row on {@link Row#kind}. Every {@code WINDOW}-kind row's anchor is a
   * tab-less pseudo-window with no backing {@code ETGO_SF_SPEC} of its own (never a real,
   * already-exposed window a caller might have separately resolved), so — unlike an earlier
   * revision of this method — there is no "reuse an already-resolved tier" shortcut to take:
   * every row always does its own single-anchor query.
   *
   * @param role the role to resolve every {@link #ROWS} row's tier against
   * @return report id → tier, one entry per {@link #ROWS} row (never omitted, {@link #NONE}
   *     included)
   */
  public static Map<String, String> resolveTierMap(Role role) {
    Map<String, String> reportTiers = new LinkedHashMap<>();
    for (Row row : ROWS) {
      String tier;
      switch (row.kind) {
        case OBUIAPP_PROCESS:
          tier = resolveObuiappProcessTierMap(role, row.anchorId).getOrDefault(row.anchorId, NONE);
          break;
        case CLASSIC_PROCESS:
          tier = resolveClassicProcessTierMap(role, row.anchorId).getOrDefault(row.anchorId, NONE);
          break;
        case WINDOW:
        default:
          tier = resolveWindowTierMap(role, Set.of(row.anchorId)).getOrDefault(row.anchorId, NONE);
          break;
      }
      reportTiers.put(row.id, tier);
    }
    return reportTiers;
  }

  /**
   * Single-anchor {@code AD_Window_Access} tier lookup — same tier logic as every sibling webhook
   * in this package ({@link #FULL} for {@code IsReadWrite = true}, {@link #READ_ONLY} otherwise).
   * Client/organization filtering is explicitly disabled to match every other query in this
   * package, so this works unchanged for a system-client ({@code AD_Client_ID = '0'}) role too.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, String> resolveWindowTierMap(Role role, Set<String> windowIds) {
    OBCriteria<WindowAccess> criteria = OBDal.getInstance().createCriteria(WindowAccess.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ROLE + ".id", role.getId()));
    criteria.add(Restrictions.eq(WindowAccess.PROPERTY_ACTIVE, true));

    Map<String, String> tiers = new LinkedHashMap<>();
    for (WindowAccess access : (List<WindowAccess>) criteria.list()) {
      Window window = access.getWindow();
      if (window == null || !windowIds.contains(window.getId())) {
        continue;
      }
      tiers.put(window.getId(), Boolean.TRUE.equals(access.isEditableField()) ? FULL : READ_ONLY);
    }
    return tiers;
  }

  /**
   * OBUIAPP {@code ProcessAccess} tier lookup for a single target process id — same tier logic as
   * {@code SFRolesOverview}'s own {@code resolveProcessTierMap} (ETP-5071).
   */
  @SuppressWarnings("unchecked")
  private static Map<String, String> resolveObuiappProcessTierMap(Role role, String obuiappProcessId) {
    OBCriteria<org.openbravo.client.application.ProcessAccess> criteria = OBDal.getInstance()
        .createCriteria(org.openbravo.client.application.ProcessAccess.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(
        org.openbravo.client.application.ProcessAccess.PROPERTY_ROLE + ".id", role.getId()));
    criteria.add(Restrictions.eq(
        org.openbravo.client.application.ProcessAccess.PROPERTY_ACTIVE, true));

    Map<String, String> tiers = new LinkedHashMap<>();
    for (org.openbravo.client.application.ProcessAccess access
        : (List<org.openbravo.client.application.ProcessAccess>) criteria.list()) {
      org.openbravo.client.application.Process process = access.getObuiappProcess();
      if (process == null || !obuiappProcessId.equals(process.getId())) {
        continue;
      }
      tiers.put(obuiappProcessId, Boolean.TRUE.equals(access.isEditableField()) ? FULL : READ_ONLY);
    }
    return tiers;
  }

  /**
   * Classic {@code AD_Process_Access} tier lookup for a single target process id — needed only for
   * the one {@link Kind#CLASSIC_PROCESS} Informes row ({@code tax-report}). Mirrors {@link
   * NeoAccessHelper#hasProcessAccess(Role, String)}'s own binary semantics deliberately: any
   * active classic {@code ProcessAccess} row grants {@link #FULL}, never {@link #READ_ONLY} —
   * classic process access carries no meaningful read/write tiering despite the entity exposing an
   * {@code IsReadWrite} column (see that method's own javadoc). Deriving {@link #READ_ONLY} from
   * {@code isEditableField()} here, the way {@link #resolveObuiappProcessTierMap} does for OBUIAPP
   * grants, would make the admin listing show a tier the real request-time gate ({@code
   * hasProcessAccess}) does not actually enforce.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, String> resolveClassicProcessTierMap(Role role, String classicProcessId) {
    OBCriteria<org.openbravo.model.ad.access.ProcessAccess> criteria = OBDal.getInstance()
        .createCriteria(org.openbravo.model.ad.access.ProcessAccess.class);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq(
        org.openbravo.model.ad.access.ProcessAccess.PROPERTY_ROLE + ".id", role.getId()));
    criteria.add(Restrictions.eq(
        org.openbravo.model.ad.access.ProcessAccess.PROPERTY_ACTIVE, true));

    Map<String, String> tiers = new LinkedHashMap<>();
    for (org.openbravo.model.ad.access.ProcessAccess access
        : (List<org.openbravo.model.ad.access.ProcessAccess>) criteria.list()) {
      org.openbravo.model.ad.ui.Process process = access.getProcess();
      if (process == null || !classicProcessId.equals(process.getId())) {
        continue;
      }
      tiers.put(classicProcessId, FULL);
    }
    return tiers;
  }

  /**
   * Turns a report-id → tier map (from {@link #resolveTierMap(Role)}) into the sorted-by-name
   * {@code {id, name, tier}} JSON array both {@code SFRolesOverview} (a role card's own {@code
   * reports}) and {@code SFSystemRoleTemplates} (same shape, its own {@code reports}) need
   * identically — extracted here, alongside {@link #resolveTierMap}, so the two webhooks can
   * never drift on this shape either. Only accessible rows appear: a row whose tier is {@link
   * #NONE} is skipped, matching {@code windowsJsonFromTierMap}'s own "only accessible rows
   * appear" convention in both webhooks for real windows.
   *
   * @param reportTiers report id → tier, as returned by {@link #resolveTierMap(Role)}
   * @return the sorted-by-name {@code {id, name, tier}} JSON array, one entry per accessible row
   * @throws JSONException if building one of the row objects fails (never expected in practice —
   *     every key/value written here is a plain string)
   */
  public static JSONArray reportsJson(Map<String, String> reportTiers) throws JSONException {
    List<JSONObject> reportJsons = new ArrayList<>();
    for (Row row : ROWS) {
      String tier = reportTiers.getOrDefault(row.id, NONE);
      if (NONE.equals(tier)) {
        continue;
      }
      JSONObject reportJson = new JSONObject();
      reportJson.put("id", row.id);
      reportJson.put("name", row.name);
      reportJson.put("tier", tier);
      reportJsons.add(reportJson);
    }

    reportJsons.sort((a, b) -> {
      try {
        return a.getString("name").compareToIgnoreCase(b.getString("name"));
      } catch (JSONException e) {
        return 0;
      }
    });

    JSONArray reports = new JSONArray();
    for (JSONObject reportJson : reportJsons) {
      reports.put(reportJson);
    }
    return reports;
  }
}
