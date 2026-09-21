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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.WindowAccess;
import org.openbravo.model.ad.ui.Window;

/**
 * ETP-5402 — the "Informes" (Reports) subsection catalog: 9 {@code SPEC_TYPE = 'R'} report
 * specs, expressed as 10 {@link Row}s ({@code aging-receivable} split into 2 rows, see below),
 * and the shared tier-resolution logic for them, factored out so BOTH {@code SFRolesOverview}
 * ("Configuración &gt; Roles") and {@code SFSystemRoleTemplates} (the User window's "Roles del
 * usuario" tab matrix columns) resolve a role's Informes access identically — the two webhooks
 * must never drift on which anchor id/category/kind backs a given report row.
 *
 * <p>None of the 10 rows is a candidate for either webhook's own {@code SPEC_TYPE = 'W'}
 * window-resolution query (windowless by construction), so each row's real access is resolved via
 * whichever ad-hoc mechanism its own NEO handler actually gates on today — see
 * {@code santo_ETP-5402-analysis-and-plan.md}'s Part A DB-verified inventory in
 * {@code etendo_schema_forge}.
 *
 * <p>{@code aging-receivable} is represented as TWO rows ({@code aging-receivable} / {@code
 * aging-payable}) even though both are served by the single {@code aging-receivable} spec and
 * {@code AgingReportHandler} — matching how {@code TemplateRoleWindowAccess} and the frontend's
 * {@code REPORT_PREVIEW_IMAGES} already treat Cobros/Pagos as two distinct identities gated by two
 * different OBUIAPP process grants (receivable vs. payable).
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

  public enum Kind { WINDOW, OBUIAPP_PROCESS, CLASSIC_PROCESS }

  /**
   * A single Informes-subsection report row: its stable id (matches the {@code ETGO_SF_SPEC.name}
   * / artifact spec name, except for the {@code aging-receivable}/{@code aging-payable} split
   * above), a fallback display name (the frontend's {@code menu.json} is expected to override it,
   * same convention as {@code SFRolesOverview}'s {@code PROXY_MATRIX_ROWS}), which access
   * mechanism to resolve it through, the anchor id in that mechanism's own id-space, and the
   * hardcoded category it belongs to (a report row's category cannot be derived from the classic
   * {@code AD_Menu} tree the way a real window's can, so it is a human-assigned constant here).
   */
  public static final class Row {
    public final String id;
    public final String name;
    public final Kind kind;
    public final String anchorId;
    public final String category;

    Row(String id, String name, Kind kind, String anchorId, String category) {
      this.id = id;
      this.name = name;
      this.kind = kind;
      this.anchorId = anchorId;
      this.category = category;
    }
  }

  /**
   * {@code AD_Window_ID} of the "Financial Account" window — the constituent window every one of
   * the 6 financial-family report specs below resolves through (each spec's {@code ad_tab_id}
   * lands on one of this window's own tabs). Already a real, separately-exposed Etendo GO window,
   * so a caller that has ALREADY resolved this window's tier for the role (passed as {@code
   * knownWindowTiers} to {@link #resolveTierMap(Role, Map)}) gets it read straight from that map
   * for these 6 rows instead of a fresh query.
   */
  public static final String FINANCIAL_ACCOUNT_WINDOW_ID = "94EAA455D2644E04AB25D93BE5157B6D";

  /** Classic {@code AD_Process_ID} gating {@code tax-report} — mirrors {@code TaxReportHandler}. */
  public static final String TAX_REPORT_PROCESS_ID = "8C1331B9EC14CED7E040007F010119A0";

  /** OBUIAPP process id gating the receivable ("Cobros") tier of {@code aging-receivable}. */
  public static final String AGING_RECEIVABLE_PROCESS_ID = "0D37A9F6109549DEB058373EF2DAEB6A";

  /** OBUIAPP process id gating the payable ("Pagos") tier of {@code aging-receivable}. */
  public static final String AGING_PAYABLE_PROCESS_ID = "EB4C4053F3B94A17A08D1DD7E89CEB7E";

  /** {@code AD_Window_ID} of the tab-less pseudo-window gating {@code inventory-stock-report}. */
  public static final String INVENTORY_STOCK_REPORT_WINDOW_ID = "6346B88619F948F9A42224BDB0B239FA";

  /** Hardcoded Informes category for the Finance-family report rows. */
  public static final String FINANCE_CATEGORY = "Finance";

  /** Hardcoded Informes category for the Inventory-family report rows. */
  public static final String INVENTORY_CATEGORY = "Inventory";

  /** The 10 Informes-subsection report rows, in declaration order. */
  public static final List<Row> ROWS = List.of(
      new Row("tax-report", "Tax Report",
          Kind.CLASSIC_PROCESS, TAX_REPORT_PROCESS_ID, FINANCE_CATEGORY),
      new Row("aging-receivable", "Informe Antiguedad de Cobros",
          Kind.OBUIAPP_PROCESS, AGING_RECEIVABLE_PROCESS_ID, FINANCE_CATEGORY),
      new Row("aging-payable", "Informe Antiguedad de Pagos",
          Kind.OBUIAPP_PROCESS, AGING_PAYABLE_PROCESS_ID, FINANCE_CATEGORY),
      new Row("inventory-stock-report", "Informes de inventario",
          Kind.WINDOW, INVENTORY_STOCK_REPORT_WINDOW_ID, INVENTORY_CATEGORY),
      new Row("bank-statements", "Bank Statements",
          Kind.WINDOW, FINANCIAL_ACCOUNT_WINDOW_ID, FINANCE_CATEGORY),
      new Row("bank-reconciliation", "Bank Reconciliation",
          Kind.WINDOW, FINANCIAL_ACCOUNT_WINDOW_ID, FINANCE_CATEGORY),
      new Row("cash-close", "Cash Close",
          Kind.WINDOW, FINANCIAL_ACCOUNT_WINDOW_ID, FINANCE_CATEGORY),
      new Row("financial-account-transactions", "Account Transactions",
          Kind.WINDOW, FINANCIAL_ACCOUNT_WINDOW_ID, FINANCE_CATEGORY),
      new Row("financial-account-bank-connection", "Bank Connections",
          Kind.WINDOW, FINANCIAL_ACCOUNT_WINDOW_ID, FINANCE_CATEGORY),
      new Row("financial-accounts-page", "Financial Accounts",
          Kind.WINDOW, FINANCIAL_ACCOUNT_WINDOW_ID, FINANCE_CATEGORY));

  /**
   * Resolves the 10-row Informes tier map for {@code role}, one entry per {@link #ROWS} id,
   * dispatching per row on {@link Row#kind}. {@code knownWindowTiers} is an OPTIONAL
   * already-resolved real-window tier map (id → tier, e.g. the caller's own {@code
   * resolveWindowTierMap(role, goWindowsById.keySet())} result); when non-null, the 6 rows
   * anchored on {@link #FINANCIAL_ACCOUNT_WINDOW_ID} read that window's tier straight out of it
   * instead of re-querying. Pass {@code null} when no such map exists yet (e.g. {@code
   * SFSystemRoleTemplates}, which never builds one as a standalone map) — those 6 rows then each
   * resolve via their own fresh query, same as any other {@code WINDOW}-kind row.
   *
   * @return report id → tier, one entry per {@link #ROWS} row (never omitted, {@link #NONE}
   *     included)
   */
  public static Map<String, String> resolveTierMap(Role role, Map<String, String> knownWindowTiers) {
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
          tier = resolveWindowKindTier(role, row.anchorId, knownWindowTiers);
          break;
      }
      reportTiers.put(row.id, tier);
    }
    return reportTiers;
  }

  private static String resolveWindowKindTier(Role role, String anchorId,
      Map<String, String> knownWindowTiers) {
    if (knownWindowTiers != null && FINANCIAL_ACCOUNT_WINDOW_ID.equals(anchorId)) {
      return knownWindowTiers.getOrDefault(anchorId, NONE);
    }
    return resolveWindowTierMap(role, Set.of(anchorId)).getOrDefault(anchorId, NONE);
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
}
