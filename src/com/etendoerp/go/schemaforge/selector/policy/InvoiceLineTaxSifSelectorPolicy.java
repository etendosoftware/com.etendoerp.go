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
package com.etendoerp.go.schemaforge.selector.policy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.NeoSelectorService;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * Enriches the lines Tax selector response for the sales-invoice (AD_Window_Id
 * {@code 167}), purchase-invoice (AD_Window_Id {@code 183}), sales-order (AD_Window_Id
 * {@code 143}) and purchase-order (AD_Window_Id {@code 181}) windows with the columns
 * the frontend's {@code selectSifFields()} (mirrored here, see
 * {@code tools/app-shell/src/windows/custom/shared/TaxSifField.jsx}) needs to detect a tax
 * missing its TBAI/Verifactu SIF (Sistemas de Información de Facturación) configuration —
 * ETP-4888 point 5 (invoices), extended to sales-order/purchase-order in a follow-up round
 * once a real-world sales-order confirmation failed with an uncommunicated missing
 * "Clave Régimen Especial IVA" — the exact class of error this feature exists to surface
 * earlier. The class/file name stays invoice-flavored for now (kept as a single diff, not a
 * rename) — a follow-up rename to a spec-neutral name is a reasonable Alex-review call.
 *
 * <p><b>Why the selector, not a per-line GET enrichment:</b> the frontend calls this SAME
 * selector endpoint ONCE per document (a large, unfiltered page — the client's tax catalog is
 * small) to build a client-side {@code taxId -> completeness} lookup, instead of one GET-by-id
 * per distinct tax used on the grid. Enriching the selector response, rather than duplicating
 * the same columns onto every line's own GET response, keeps the extra payload to exactly one
 * request regardless of how many lines/distinct taxes the document has.
 *
 * <p><b>Scoping:</b> {@code entityName == "lines"} alone is too broad — several unrelated
 * windows name their detail/lines entity "lines" too. This also requires
 * {@link NeoSelectorService#SOURCE_WINDOW_ID_PARAM} to be one of the four in-scope windows,
 * and the selector's resolved DAL target entity to be {@code FinancialMgmtTaxRate} (so this
 * never misfires against, e.g., the SAME lines entity's Product selector).
 *
 * <p><b>Without this scoping, the frontend badge would false-positive:</b> {@code
 * useTaxSifLineRowActions.jsx}'s {@code isTaxSifMissing()} treats an ABSENT enrichment column
 * the same as a genuinely blank one ({@code value == null || value === ''}), and {@code
 * selectSifFields()} still resolves a régimen field even with no {@code taxExempt}/{@code
 * notTaxable} enrichment (it falls through to the régimen-column default). So an unscoped
 * window would show the "needs SIF configuration" warning on every TBAI/Verifactu tax, even
 * correctly configured ones — not merely "no badge", but a wrong one.
 *
 * <p><b>SII is intentionally NOT enriched here</b> — {@code c_tax} does have an SII column
 * ({@code em_aeatsii_cause_exemption_id}, with an active AD_Field), so this is not a technical
 * gap. It is a deliberate ETP-5122 product decision: in GO the SII exemption cause is edited on
 * the invoice HEADER ({@code SifTab.jsx}), and in Classic it is propagated tariff→header via
 * {@code org.openbravo.module.sii.eventhandlers.InvoiceLineEventHandler.setExemption()} — so
 * SII was deliberately excluded from this line-level tax-completeness mechanism. The frontend's
 * {@code selectSifFields()} already returns no fields for an SII-only tax, so even though this
 * policy projects the same columns unconditionally, the "missing" check on the frontend simply
 * never fires for SII.
 *
 * <p><b>Compound/summary-tax resolution (ETP-4888 follow-up):</b> a Spanish compound tax
 * ("Entregas IVA+RE 21+5.2% ISP" and the like) is a summary tax ({@code c_tax.issummary='Y'})
 * whose rate components are separate {@code C_Tax} rows linked via {@code parent_tax_id}. An
 * order/invoice line always attaches the SUMMARY tax, never its component, but the régimen key
 * Classic's completion validation reads lives on the non-equivalence-charge component
 * ({@code em_obspti_isequivalentcharge='N'}). This policy projects {@code issummary}/
 * {@code parent_tax_id}/{@code em_obspti_isequivalentcharge} (as {@code isSummary}/
 * {@code parentTaxId}/{@code isEquivalentCharge}) unconditionally alongside the SIF value
 * columns above, so the frontend can link a summary tax to its already-fetched child within the
 * SAME catalog response and check the CHILD's completeness, without a second request — see
 * {@code resolveEffectiveTaxRow()} in {@code useTaxSifLineRowActions.jsx}.
 */
public final class InvoiceLineTaxSifSelectorPolicy implements SelectorEnrichmentPolicy {

  private static final Logger log = LogManager.getLogger(InvoiceLineTaxSifSelectorPolicy.class);

  // AD_Window_Id — sales-invoice (167), purchase-invoice (183), sales-order (143),
  // purchase-order (181). Mirrors the useWindowAccess('167')/('183')/('143')/('181')
  // constants already hardcoded in each window's own
  // tools/app-shell/src/windows/custom/{sales-invoice,purchase-invoice,sales-order,purchase-order}/index.jsx.
  private static final Set<String> IN_SCOPE_WINDOW_IDS = Set.of("167", "183", "143", "181");
  private static final String LINES_ENTITY_NAME = "lines";
  // DAL entity name for C_Tax (org.openbravo.model.financialmgmt.tax.TaxRate), resolved by
  // SelectorDescriptorResolver via ModelProvider.getEntityByTableName("C_Tax").getName().
  // Confirmed via TaxRate.ENTITY_NAME and SelectorDescriptorResolver — NOT the Java simple
  // class name "TaxRate" (see ETP-4888 fix commit for the full trace).
  private static final String TAX_TARGET_ENTITY = "FinancialMgmtTaxRate";

  // c_tax DB column (key) -> JSON key emitted on each enriched selector item (value). Keys for
  // the TBAI/Verifactu VALUE columns are the EXACT raw AD column names `selectSifFields()`'s
  // `buildField()` calls use as `column` — the frontend looks up a resolved field's current
  // value via `row[field.column]`, so casing here must match theirs exactly (Postgres itself
  // is case-insensitive on unquoted identifiers, but the JSON keys are not).
  //
  // Split (ETP-5122) into two groups because they are read differently now:
  //
  // `STRUCTURAL_COLUMNS` (`issummary`/`parent_tax_id`/`em_obspti_isequivalentcharge`, plus
  // `istaxexempt`/`isnotaxable`) carry no `row[field.column]` lookup contract, so their JSON
  // keys are plain camelCase. They are always read straight off `c_tax` — there is no per-org
  // override for them. Added so the frontend can resolve a compound/summary tax
  // (`c_tax.issummary='Y'`, e.g. "Entregas IVA+RE 21+5.2% ISP") down to its rate-component child
  // WITHOUT a second request: the whole tax catalog is already fetched in one page-through (see
  // `fetchAllTaxPages` in `useTaxSifLineRowActions.jsx`), so a child's own row is already
  // present in the same client-side map, keyed by its own id, needing only these extra columns
  // to be linked up (`resolveEffectiveTaxRow()` in that file). Mirrors the exact child-selection
  // criterion Etendo Classic's own completion validation uses
  // (`em_obspti_isequivalentcharge = 'N'` — see `ETVFAC_ORDER_VFAC_VALIDATION.xml` /
  // `InitialValidator.java` in com.etendoerp.verifactu).
  //
  // `SIF_VALUE_COLUMNS` are the 8 TBAI/Verifactu VALUE columns. `c_tax.<col>` wins when
  // non-blank; otherwise `etsg_tax_sif_config.<col>` (the per-legal-entity override row, see
  // `querySifColumns()`) is used — the same precedence the write-side handler
  // (`TaxSifOverrideHandler`) and Classic apply (ETP-5122 decision D5).
  private static final Map<String, String> STRUCTURAL_COLUMNS;
  private static final Map<String, String> SIF_VALUE_COLUMNS;
  private static final Map<String, String> COLUMN_TO_JSON_KEY;

  static {
    Map<String, String> structural = new LinkedHashMap<>();
    structural.put("istaxexempt", "taxExempt");
    structural.put("isnotaxable", "notTaxable");
    structural.put("issummary", "isSummary");
    structural.put("parent_tax_id", "parentTaxId");
    structural.put("em_obspti_isequivalentcharge", "isEquivalentCharge");
    STRUCTURAL_COLUMNS = Collections.unmodifiableMap(structural);

    Map<String, String> sifValues = new LinkedHashMap<>();
    sifValues.put("em_tbai_claveregimeniva", "EM_Tbai_Claveregimeniva");
    sifValues.put("em_tbai_exemptioncause", "EM_Tbai_Exemptioncause");
    sifValues.put("em_tbai_nonsubjectcause", "EM_Tbai_Nonsubjectcause");
    sifValues.put("em_etvfac_vat_regime", "EM_Etvfac_Vat_Regime");
    sifValues.put("em_etvfac_igic_regime", "em_etvfac_igic_regime");
    sifValues.put("em_etvfac_ipsi_regime", "EM_Etvfac_Ipsi_Regime");
    sifValues.put("em_etvfac_exemption_cause", "EM_Etvfac_Exemption_Cause");
    sifValues.put("em_etvfac_cause_not_taxable", "em_etvfac_cause_not_taxable");
    SIF_VALUE_COLUMNS = Collections.unmodifiableMap(sifValues);

    Map<String, String> combined = new LinkedHashMap<>(structural);
    combined.putAll(sifValues);
    COLUMN_TO_JSON_KEY = Collections.unmodifiableMap(combined);
  }

  // Selector context param key carrying the requesting document's organization (see
  // SelectorContextResolver.resolveContextOrganizationId), propagated into
  // selectorContextParams by NeoSelectorService so this policy can resolve the
  // etsg_tax_sif_config override row for the correct legal entity.
  private static final String AD_ORG_ID_PARAM = "AD_Org_ID";

  public InvoiceLineTaxSifSelectorPolicy() {
    // Stateless policy; public constructor supports registry composition without CDI.
  }

  @Override
  public boolean supports(SelectorMeta meta, Map<String, String> contextParams) {
    if (meta == null || contextParams == null) {
      return false;
    }
    String sourceEntity = contextParams.get(NeoSelectorService.SOURCE_ENTITY_NAME_PARAM);
    String sourceWindowId = contextParams.get(NeoSelectorService.SOURCE_WINDOW_ID_PARAM);
    // Set.of(...) forbids contains(null) — throws NPE instead of returning false — so the
    // missing-key case (map.get returns null whenever the source spec's window link can't be
    // resolved, e.g. most OTHER windows' "lines" entities too) must be checked before consulting
    // the set. Mirrors the same guard GoodsMovementProductSelectorPolicy already applies to
    // SOURCE_ENTITY_NAME_PARAM for the identical reason.
    return LINES_ENTITY_NAME.equals(sourceEntity)
        && sourceWindowId != null && IN_SCOPE_WINDOW_IDS.contains(sourceWindowId)
        && TAX_TARGET_ENTITY.equals(meta.entityName);
  }

  @Override
  public NeoResponse enrich(NeoResponse response, SelectorMeta meta,
      Map<String, String> contextParams) {
    if (response == null || response.getBody() == null) {
      return response;
    }
    try {
      JSONArray items = response.getBody().optJSONArray("items");
      if (items == null || items.length() == 0) {
        return response;
      }
      List<String> taxIds = extractIds(items);
      if (taxIds.isEmpty()) {
        return response;
      }
      String organizationId = contextParams != null
          ? StringUtils.trimToNull(contextParams.get(AD_ORG_ID_PARAM))
          : null;
      Map<String, Map<String, Object>> sifByTaxId = querySifColumns(taxIds, organizationId);
      applyEnrichment(items, sifByTaxId);
    } catch (Exception e) {
      log.warn("[InvoiceLineTaxSifSelectorPolicy] Failed to enrich tax selector: {}",
          e.getMessage(), e);
    }
    return response;
  }

  private static void applyEnrichment(JSONArray items, Map<String, Map<String, Object>> sifByTaxId)
      throws JSONException {
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      Map<String, Object> sif = sifByTaxId.get(item.optString("id"));
      if (sif == null) {
        continue;
      }
      for (Map.Entry<String, Object> entry : sif.entrySet()) {
        item.put(entry.getKey(), entry.getValue());
      }
    }
  }

  private static List<String> extractIds(JSONArray items) throws JSONException {
    List<String> ids = new ArrayList<>(items.length());
    for (int i = 0; i < items.length(); i++) {
      String id = items.getJSONObject(i).optString("id");
      if (StringUtils.isNotBlank(id)) {
        ids.add(id);
      }
    }
    return ids;
  }

  /**
   * Query the SIF-relevant columns for the given taxes, resolving each of the 8 TBAI/Verifactu
   * VALUE columns with the ETP-5122 override precedence (D5): {@code c_tax.<col>} wins when
   * non-blank, otherwise the active {@code etsg_tax_sif_config} row for
   * {@code (c_tax_id, AD_GET_ORG_LE_BU(organizationId, 'LE'))} is used. Structural columns
   * (compound-tax linkage, exemption/no-tax flags) are always read straight off {@code c_tax} —
   * they have no per-org override. When {@code organizationId} is blank the override lookup is
   * skipped entirely (no legal entity to resolve against), so VALUE columns fall back to the
   * plain {@code c_tax} value, matching the pre-ETP-5122 behavior.
   *
   * @param taxIds tax identifiers present in the selector page being enriched
   * @param organizationId context organization used to resolve the {@code etsg_tax_sif_config}
   *     override row's legal entity, or {@code null}/blank to skip the override lookup
   */
  @SuppressWarnings("java:S2077")
  private static Map<String, Map<String, Object>> querySifColumns(List<String> taxIds,
      String organizationId) throws SQLException {
    StringBuilder placeholders = new StringBuilder();
    for (int i = 0; i < taxIds.size(); i++) {
      if (i > 0) {
        placeholders.append(", ");
      }
      placeholders.append('?');
    }
    boolean withOverride = StringUtils.isNotBlank(organizationId);
    String sql = buildSifColumnsSql(placeholders.toString(), withOverride);

    Map<String, Map<String, Object>> result = new HashMap<>();
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      int paramIndex = 1;
      if (withOverride) {
        ps.setString(paramIndex++, organizationId);
      }
      for (String taxId : taxIds) {
        ps.setString(paramIndex++, taxId);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.put(rs.getString("c_tax_id"), extractRow(rs));
        }
      }
    }
    return result;
  }

  private static String buildSifColumnsSql(String placeholders, boolean withOverride) {
    StringBuilder select = new StringBuilder("t.c_tax_id");
    for (String col : STRUCTURAL_COLUMNS.keySet()) {
      select.append(", t.").append(col).append(" AS ").append(col);
    }
    for (String col : SIF_VALUE_COLUMNS.keySet()) {
      if (withOverride) {
        select.append(", COALESCE(NULLIF(TRIM(t.").append(col).append("), ''), NULLIF(TRIM(ovr.")
            .append(col).append("), '')) AS ").append(col);
      } else {
        select.append(", t.").append(col).append(" AS ").append(col);
      }
    }
    StringBuilder sql = new StringBuilder("SELECT ").append(select)
        .append(" FROM c_tax t");
    if (withOverride) {
      sql.append(" LEFT JOIN etsg_tax_sif_config ovr ON ovr.c_tax_id = t.c_tax_id")
          .append(" AND ovr.ad_org_id = ad_get_org_le_bu(?, 'LE') AND ovr.isactive = 'Y'");
    }
    sql.append(" WHERE t.c_tax_id IN (").append(placeholders).append(")");
    return sql.toString();
  }

  private static Map<String, Object> extractRow(ResultSet rs) throws SQLException {
    Map<String, Object> row = new HashMap<>();
    for (Map.Entry<String, String> entry : COLUMN_TO_JSON_KEY.entrySet()) {
      String value = rs.getString(entry.getKey());
      if (value != null) {
        row.put(entry.getValue(), value);
      }
    }
    return row;
  }
}
