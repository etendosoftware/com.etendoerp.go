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
 * <p><b>SII is intentionally NOT enriched here</b> — confirmed by investigation that SII has
 * nothing to configure at tax level; its equivalent ({@code aeatsiiCauseExemption}) already
 * lives on the invoice HEADER and is handled by {@code SifTab.jsx}. The frontend's
 * {@code selectSifFields()} already returns no fields for an SII-only tax, so even though this
 * policy projects the same columns unconditionally, the "missing" check on the frontend simply
 * never fires for SII.
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
  // the TBAI/Verifactu columns are the EXACT raw AD column names `selectSifFields()`'s
  // `buildField()` calls use as `column` — the frontend looks up a resolved field's current
  // value via `row[field.column]`, so casing here must match theirs exactly (Postgres itself
  // is case-insensitive on unquoted identifiers, but the JSON keys are not).
  private static final Map<String, String> COLUMN_TO_JSON_KEY;

  static {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("istaxexempt", "taxExempt");
    m.put("isnotaxable", "notTaxable");
    m.put("em_tbai_claveregimeniva", "EM_Tbai_Claveregimeniva");
    m.put("em_tbai_exemptioncause", "EM_Tbai_Exemptioncause");
    m.put("em_tbai_nonsubjectcause", "EM_Tbai_Nonsubjectcause");
    m.put("em_etvfac_vat_regime", "EM_Etvfac_Vat_Regime");
    m.put("em_etvfac_igic_regime", "em_etvfac_igic_regime");
    m.put("em_etvfac_ipsi_regime", "EM_Etvfac_Ipsi_Regime");
    m.put("em_etvfac_exemption_cause", "EM_Etvfac_Exemption_Cause");
    m.put("em_etvfac_cause_not_taxable", "em_etvfac_cause_not_taxable");
    COLUMN_TO_JSON_KEY = Collections.unmodifiableMap(m);
  }

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
      Map<String, Map<String, Object>> sifByTaxId = querySifColumns(taxIds);
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

  @SuppressWarnings("java:S2077")
  private static Map<String, Map<String, Object>> querySifColumns(List<String> taxIds)
      throws SQLException {
    StringBuilder placeholders = new StringBuilder();
    for (int i = 0; i < taxIds.size(); i++) {
      if (i > 0) {
        placeholders.append(", ");
      }
      placeholders.append('?');
    }
    String columnList = String.join(", ", COLUMN_TO_JSON_KEY.keySet());
    String sql = "SELECT c_tax_id, " + columnList
        + " FROM c_tax WHERE c_tax_id IN (" + placeholders + ")";

    Map<String, Map<String, Object>> result = new HashMap<>();
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < taxIds.size(); i++) {
        ps.setString(i + 1, taxIds.get(i));
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.put(rs.getString("c_tax_id"), extractRow(rs));
        }
      }
    }
    return result;
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
