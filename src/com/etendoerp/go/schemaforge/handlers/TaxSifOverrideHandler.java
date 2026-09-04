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

package com.etendoerp.go.schemaforge.handlers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * NeoHandler for the {@code tax} entity (Tax window) implementing the ETP-5122 storage
 * redirection for the 8 TBAI/Verifactu SIF (Sistemas de Información de Facturación) value
 * fields: {@code etvfacVatRegime}, {@code etvfacIGICRegime}, {@code etvfacIPSIRegime},
 * {@code etvfacExemptionCause}, {@code etvfacCauseNotTaxable}, {@code tbaiClaveregimeniva},
 * {@code tbaiNonsubjectcause} and {@code tBAICausaDeExencion} (camelCase names confirmed against
 * {@code artifacts/tax/contract.json} in schema_forge — NOT guessed).
 *
 * <p><b>No new sub-tab, no new AD_Field.</b> Per ETP-5122 decision D4, these 8 fields keep
 * being exposed exactly where they are today, with their existing display logic — only the
 * WRITE destination changes.
 *
 * <h3>PUT/PATCH — redirect to {@code etsg_tax_sif_config} (D4)</h3>
 * <p>When the request body carries any of the 8 fields, this handler strips them out of the
 * body, upserts them into {@code etsg_tax_sif_config} keyed by
 * {@code (c_tax_id, AD_GET_ORG_LE_BU(currentOrg, 'LE'))} (the legal entity of the ACTING
 * organization, not the raw org itself — matching the read-side precedence join, see below),
 * and lets the (possibly now-empty) remainder of the body flow through to the default CRUD for
 * {@code c_tax} itself. Only the columns actually present in the request are written to
 * {@code etsg_tax_sif_config} — an UPDATE never blanks a sibling column the caller did not
 * send. POST (create) is deliberately NOT intercepted: a brand-new {@code c_tax} row has no id
 * yet to key the override table on, and in practice these fields are only edited once a tax
 * already exists.
 *
 * <h3>GET afterHandle — effective value (D5)</h3>
 * <p>Overwrites the 8 properties on every row of a {@code tax} GET response (single or list)
 * with the EFFECTIVE value: {@code c_tax.<col>} wins when non-blank, otherwise the active
 * {@code etsg_tax_sif_config} override row for the same {@code (c_tax_id, LE org)} pair is
 * used, else {@code null}. This is the same precedence
 * {@code InvoiceLineTaxSifSelectorPolicy#querySifColumns} applies for the lines tax selector, so
 * the Tax window and the invoice/order line grid never disagree about which value is "the"
 * value. Kept independent (small SQL duplication) rather than sharing code across packages,
 * because the two call sites read from different response shapes (selector {@code items} vs
 * CRUD {@code response.data}) and a shared helper would need to abstract that away for a single
 * 8-column SELECT.
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2 (this qualifier silently stops being
 * discovered if a scope annotation such as {@code @ApplicationScoped} is added).
 */
@Named("taxSifOverrideHandler")
public class TaxSifOverrideHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(TaxSifOverrideHandler.class);

  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";
  private static final String METHOD_GET = "GET";
  private static final String TAX_ENTITY_NAME = "tax";
  private static final String FIELD_ID = "id";

  // JSON field name (camelCase, as exposed on the `tax` entity contract) -> DB column, shared by
  // both c_tax and etsg_tax_sif_config (same column name on both tables). Confirmed against
  // artifacts/tax/contract.json in schema_forge, NOT guessed camelCase.
  private static final Map<String, String> SIF_FIELD_TO_COLUMN;

  static {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("etvfacVatRegime", "em_etvfac_vat_regime");
    m.put("etvfacIGICRegime", "em_etvfac_igic_regime");
    m.put("etvfacIPSIRegime", "em_etvfac_ipsi_regime");
    m.put("etvfacExemptionCause", "em_etvfac_exemption_cause");
    m.put("etvfacCauseNotTaxable", "em_etvfac_cause_not_taxable");
    m.put("tbaiClaveregimeniva", "em_tbai_claveregimeniva");
    m.put("tbaiNonsubjectcause", "em_tbai_nonsubjectcause");
    m.put("tBAICausaDeExencion", "em_tbai_exemptioncause");
    SIF_FIELD_TO_COLUMN = Collections.unmodifiableMap(m);
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!TAX_ENTITY_NAME.equals(context.getEntityName())) {
      return null;
    }
    String method = context.getHttpMethod();
    if (!METHOD_PUT.equalsIgnoreCase(method) && !METHOD_PATCH.equalsIgnoreCase(method)) {
      return null;
    }
    String taxId = context.getRecordId();
    JSONObject body = context.getRequestBody();
    if (StringUtils.isBlank(taxId) || body == null) {
      return null;
    }
    OBContext obContext = context.getObContext();
    if (obContext == null || obContext.getCurrentOrganization() == null
        || obContext.getCurrentClient() == null || obContext.getUser() == null) {
      log.warn("TaxSifOverrideHandler.handle: incomplete OBContext, skipping override redirect "
          + "for tax {}", taxId);
      return null;
    }
    try {
      Map<String, String> overrideValues = extractAndStripOverrideFields(body);
      if (overrideValues.isEmpty()) {
        // Nothing SIF-related in this request — fall through unchanged.
        return null;
      }
      OBContext.setAdminMode(true);
      try {
        upsertOverride(taxId, overrideValues, obContext);
      } finally {
        OBContext.restorePreviousMode();
      }
      if (body.length() > 0) {
        // Remaining fields still need the default CRUD to persist them onto c_tax.
        return null;
      }
      return buildOverrideOnlyResponse(taxId, context);
    } catch (Exception e) {
      log.error("TaxSifOverrideHandler.handle error for tax {}", taxId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "SIF override upsert failed: " + e.getMessage());
    }
  }

  /**
   * Overwrites the 8 SIF value properties on every row of a {@code tax} GET/PUT/PATCH response
   * (single record or list) with the effective value (D5 precedence). Mutates {@code
   * context.getPreviousResult()}'s body in place and returns {@code null} to keep using it.
   *
   * <p>PUT/PATCH are covered too, not just GET: a write whose body ALSO carried a plain {@code
   * c_tax} field falls through to the default CRUD (see {@link #handle}), and that default
   * response echoes back {@code c_tax}'s own (always blank, by design) SIF columns. Without the
   * overlay here the frontend's post-save merge would blank the dropdown the user just filled in
   * — the exact ETP-5122 symptom. Applying the overlay on the write response too keeps a single
   * source of truth instead of duplicating the precedence formula per response shape.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    String method = context.getHttpMethod();
    boolean overlayable = METHOD_GET.equalsIgnoreCase(method)
        || METHOD_PUT.equalsIgnoreCase(method) || METHOD_PATCH.equalsIgnoreCase(method);
    if (!TAX_ENTITY_NAME.equals(context.getEntityName()) || !overlayable) {
      return null;
    }
    try {
      NeoResponse previousResult = context.getPreviousResult();
      JSONObject body = previousResult != null ? previousResult.getBody() : null;
      JSONObject inner = body != null ? body.optJSONObject(JsonConstants.RESPONSE_RESPONSE) : null;
      JSONArray data = inner != null ? inner.optJSONArray(JsonConstants.RESPONSE_DATA) : null;
      if (data == null || data.length() == 0) {
        return null;
      }
      List<String> taxIds = extractIds(data);
      if (taxIds.isEmpty()) {
        return null;
      }
      String organizationId = resolveContextOrganizationId(context);
      Map<String, Map<String, String>> effectiveByTaxId = queryEffectiveValues(taxIds, organizationId);
      applyEffectiveValues(data, effectiveByTaxId);
    } catch (Exception e) {
      // ERROR, not WARN: the overlay failing is never benign — every SIF field silently reverts
      // to blank in the UI while the stored override is perfectly fine, which is exactly how
      // the ETP-5122 NonUniqueDiscoveredSqlAliasException stayed invisible. The response is
      // still returned unmodified (a broken overlay must not turn a working GET into a 500).
      log.error("TaxSifOverrideHandler.afterHandle: failed to overlay effective SIF values: {}",
          e.getMessage(), e);
    }
    return null;
  }

  private static String resolveContextOrganizationId(NeoContext context) {
    OBContext obContext = context.getObContext() != null ? context.getObContext() : OBContext.getOBContext();
    if (obContext == null || obContext.getCurrentOrganization() == null) {
      return null;
    }
    return obContext.getCurrentOrganization().getId();
  }

  private static Map<String, String> extractAndStripOverrideFields(JSONObject body) throws JSONException {
    Map<String, String> values = new LinkedHashMap<>();
    for (String jsonKey : SIF_FIELD_TO_COLUMN.keySet()) {
      if (body.has(jsonKey)) {
        String value = body.isNull(jsonKey) ? null : StringUtils.trimToNull(body.optString(jsonKey, null));
        values.put(jsonKey, value);
        body.remove(jsonKey);
      }
    }
    return values;
  }

  /**
   * Upserts the given SIF value fields into {@code etsg_tax_sif_config}, keyed by
   * {@code (c_tax_id, AD_GET_ORG_LE_BU(currentOrg, 'LE'))}. Only the columns present in {@code
   * values} are written on both INSERT and the ON CONFLICT UPDATE — a sibling column not sent
   * in this request is never touched, whether the row is being created or already exists.
   *
   * <p>The legal entity organization MUST resolve. Every read path ({@link
   * #queryEffectiveValues}, {@code InvoiceLineTaxSifSelectorPolicy#buildSifColumnsSql},
   * {@code TaxSifConfigResolver#resolve} in {@code com.etendoerp.sif.general}, and the
   * {@code ETVFAC_ORDER_VFAC_VALIDATION} PL/SQL function) filters strictly by {@code ad_org_id =
   * ad_get_org_le_bu(:orgId, 'LE')}. Silently falling back to the raw {@code currentOrgId} would
   * persist a row none of those readers can ever find — an override that "saved" but never takes
   * effect, with no error anywhere. Failing fast here is deliberate: a visible PATCH failure is
   * preferable to a silently orphaned row.
   *
   * @throws OBException if the legal entity organization cannot be resolved for {@code
   *                      obContext}'s current organization
   */
  private static void upsertOverride(String taxId, Map<String, String> values, OBContext obContext) {
    Session session = OBDal.getInstance().getSession();
    String currentOrgId = obContext.getCurrentOrganization().getId();
    String leOrgId = resolveLegalEntityOrgId(session, currentOrgId);
    if (StringUtils.isBlank(leOrgId)) {
      throw new OBException(
          "Unable to resolve the legal entity organization for organization " + currentOrgId
              + " — cannot save the SIF override for tax " + taxId
              + " (AD_GET_ORG_LE_BU returned no result)");
    }

    List<String> presentColumns = new ArrayList<>();
    for (String jsonKey : values.keySet()) {
      presentColumns.add(SIF_FIELD_TO_COLUMN.get(jsonKey));
    }

    NativeQuery<?> upsert = session.createNativeQuery(buildUpsertSql(presentColumns));
    upsert.setParameter("id", SequenceIdData.getUUID());
    upsert.setParameter("clientId", obContext.getCurrentClient().getId());
    upsert.setParameter("orgId", leOrgId);
    upsert.setParameter("userId", obContext.getUser().getId());
    upsert.setParameter("taxId", taxId);
    for (Map.Entry<String, String> entry : values.entrySet()) {
      upsert.setParameter(SIF_FIELD_TO_COLUMN.get(entry.getKey()), entry.getValue());
    }
    upsert.executeUpdate();
  }

  /**
   * Column names in {@code presentColumns} come exclusively from {@link #SIF_FIELD_TO_COLUMN}'s
   * fixed value set (never from external input), so building the column/parameter lists by
   * string concatenation carries no injection risk — mirrors the same safety argument already
   * documented on {@code InvoiceLineTaxSifSelectorPolicy}'s dynamic COALESCE list.
   */
  private static String buildUpsertSql(List<String> presentColumns) {
    StringBuilder cols = new StringBuilder(
        "etsg_tax_sif_config_id, ad_client_id, ad_org_id, isactive, created, createdby, "
            + "updated, updatedby, c_tax_id");
    StringBuilder vals = new StringBuilder(
        ":id, :clientId, :orgId, 'Y', now(), :userId, now(), :userId, :taxId");
    StringBuilder setClause = new StringBuilder("isactive = 'Y', updated = now(), updatedby = :userId");
    for (String col : presentColumns) {
      cols.append(", ").append(col);
      vals.append(", :").append(col);
      setClause.append(", ").append(col).append(" = EXCLUDED.").append(col);
    }
    return "INSERT INTO etsg_tax_sif_config (" + cols + ") VALUES (" + vals + ") "
        + "ON CONFLICT (c_tax_id, ad_org_id) DO UPDATE SET " + setClause;
  }

  @SuppressWarnings("unchecked")
  private static String resolveLegalEntityOrgId(Session session, String organizationId) {
    try {
      NativeQuery<Object> query = (NativeQuery<Object>) session.createNativeQuery(
          "SELECT ad_get_org_le_bu(:orgId, 'LE')");
      query.setParameter("orgId", organizationId);
      Object result = query.uniqueResult();
      return result != null ? result.toString() : null;
    } catch (Exception e) {
      log.warn("TaxSifOverrideHandler: could not resolve legal entity for org {}: {}",
          organizationId, e.getMessage());
      return null;
    }
  }

  /**
   * Builds the response for a PATCH/PUT whose body was ENTIRELY consumed by the SIF override
   * redirect (nothing left for the default CRUD to write onto {@code c_tax}).
   *
   * <p>Two things this MUST match, or the header form's dropdown silently goes blank right
   * after a successful save even though the write itself persisted correctly (ETP-5122
   * follow-up):
   * <ul>
   *   <li>{@code response.data} as a one-element {@link JSONArray} — the same shape
   *       {@code NeoCrudHandler}'s default PATCH/PUT response uses (SmartClient DataSource
   *       protocol, see {@code DefaultJsonDataService}), and the shape every frontend save
   *       path already unwraps via {@code data?.response?.data?.[0]}
   *       ({@code useEntity.js}'s {@code performSave}, {@code DetailView.jsx}'s inline-row and
   *       secondary-tab PATCH handlers).</li>
   *   <li>The EFFECTIVE value (D5 precedence, the same {@link #queryEffectiveValues} /
   *       {@link #applyEffectiveValues} formula {@link #afterHandle} applies on GET) of ALL 8
   *       SIF columns — not just the one or two fields that happened to be in THIS particular
   *       request. A request that only touches {@code tbaiClaveregimeniva} must still answer
   *       with the effective value of the other 7 columns (each explicitly {@code null} when
   *       neither {@code c_tax} nor the override carries one), exactly like a GET would,
   *       instead of silently omitting them.</li>
   * </ul>
   */
  private static NeoResponse buildOverrideOnlyResponse(String taxId, NeoContext context)
      throws JSONException {
    String organizationId = resolveContextOrganizationId(context);
    Map<String, Map<String, String>> effectiveByTaxId =
        queryEffectiveValues(Collections.singletonList(taxId), organizationId);
    Map<String, String> effective = effectiveByTaxId.getOrDefault(taxId, Collections.emptyMap());

    JSONObject row = new JSONObject();
    row.put(FIELD_ID, taxId);
    for (String jsonKey : SIF_FIELD_TO_COLUMN.keySet()) {
      String value = effective.get(jsonKey);
      row.put(jsonKey, value == null ? JSONObject.NULL : value);
    }

    JSONArray data = new JSONArray();
    data.put(row);
    JSONObject inner = new JSONObject();
    inner.put(JsonConstants.RESPONSE_STATUS, 0);
    inner.put(JsonConstants.RESPONSE_DATA, data);
    JSONObject responseBody = new JSONObject();
    responseBody.put(JsonConstants.RESPONSE_RESPONSE, inner);
    return NeoResponse.ok(responseBody);
  }

  private static List<String> extractIds(JSONArray data) throws JSONException {
    List<String> ids = new ArrayList<>(data.length());
    for (int i = 0; i < data.length(); i++) {
      JSONObject row = data.optJSONObject(i);
      String id = row != null ? row.optString(FIELD_ID, null) : null;
      if (StringUtils.isNotBlank(id)) {
        ids.add(id);
      }
    }
    return ids;
  }

  private static void applyEffectiveValues(JSONArray data, Map<String, Map<String, String>> effectiveByTaxId)
      throws JSONException {
    for (int i = 0; i < data.length(); i++) {
      JSONObject row = data.optJSONObject(i);
      String id = row != null ? row.optString(FIELD_ID, null) : null;
      Map<String, String> effective = id != null ? effectiveByTaxId.get(id) : null;
      if (effective == null) {
        continue;
      }
      for (Map.Entry<String, String> entry : effective.entrySet()) {
        row.put(entry.getKey(), entry.getValue() == null ? JSONObject.NULL : entry.getValue());
      }
    }
  }

  /**
   * Resolves the effective value (D5 precedence: {@code c_tax.<col>} wins when non-blank,
   * otherwise the active {@code etsg_tax_sif_config} override for {@code (c_tax_id,
   * AD_GET_ORG_LE_BU(organizationId, 'LE'))}) for each of the 8 SIF columns, for every id in
   * {@code taxIds}. When {@code organizationId} is blank, the override lookup is skipped and
   * the plain {@code c_tax} value is used (matching pre-ETP-5122 behavior).
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, String>> queryEffectiveValues(List<String> taxIds,
      String organizationId) {
    Session session = OBDal.getInstance().getSession();
    boolean withOverride = StringUtils.isNotBlank(organizationId);
    String sql = buildEffectiveValuesSql(withOverride);
    NativeQuery<Object> query = (NativeQuery<Object>) session.createNativeQuery(sql);
    if (withOverride) {
      query.setParameter("orgId", organizationId);
    }
    query.setParameterList("taxIds", taxIds);

    Map<String, Map<String, String>> result = new HashMap<>();
    for (Object rawRow : query.list()) {
      Object[] row = (Object[]) rawRow;
      Map<String, String> values = new LinkedHashMap<>();
      int i = 1;
      for (String jsonKey : SIF_FIELD_TO_COLUMN.keySet()) {
        Object value = row[i++];
        // A null effective value is put EXPLICITLY (LinkedHashMap allows null values), never
        // skipped: callers render it as a JSON null, which is what makes "user cleared the
        // dropdown" actually reach the client. Omitting the key instead would leave the
        // frontend's post-save/GET merge showing the stale pre-clear value.
        values.put(jsonKey, value != null ? value.toString() : null);
      }
      result.put((String) row[0], values);
    }
    return result;
  }

  /**
   * <b>Every computed column MUST carry an explicit {@code AS <alias>}.</b> Hibernate's
   * native-query auto-discovery derives each result alias from the SQL expression itself, so 8
   * unaliased {@code COALESCE(...)} expressions all resolve to the alias {@code coalesce} and
   * the query dies with {@code NonUniqueDiscoveredSqlAliasException: Encountered a duplicated
   * sql alias [coalesce]} — at RUNTIME only, never at compile time and never in a mocked unit
   * test. That failure is what made the Tax window's SIF dropdowns come back blank in ETP-5122:
   * {@link #afterHandle} swallows it in its catch-all and silently skips the overlay, so every
   * one of the 8 fields fell back to {@code c_tax}'s (by design always empty) own column.
   *
   * <p>Aliasing each expression back to its own column name keeps the aliases unique and the
   * result-set column order identical to {@link #SIF_FIELD_TO_COLUMN}'s iteration order, which
   * {@link #queryEffectiveValues} reads positionally.
   */
  private static String buildEffectiveValuesSql(boolean withOverride) {
    StringBuilder select = new StringBuilder("t.c_tax_id");
    for (String col : SIF_FIELD_TO_COLUMN.values()) {
      if (withOverride) {
        select.append(", COALESCE(NULLIF(TRIM(t.").append(col).append("), ''), NULLIF(TRIM(ovr.")
            .append(col).append("), '')) AS ").append(col);
      } else {
        select.append(", t.").append(col);
      }
    }
    StringBuilder sql = new StringBuilder("SELECT ").append(select).append(" FROM c_tax t");
    if (withOverride) {
      sql.append(" LEFT JOIN etsg_tax_sif_config ovr ON ovr.c_tax_id = t.c_tax_id")
          .append(" AND ovr.ad_org_id = ad_get_org_le_bu(:orgId, 'LE') AND ovr.isactive = 'Y'");
    }
    sql.append(" WHERE t.c_tax_id IN (:taxIds)");
    return sql.toString();
  }
}
