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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoExtension;
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
 * {@code (c_tax_id, AD_GET_ORG_LE_BU(writeOrg, 'LE'))} (the legal entity of the ACTING
 * organization, not the raw org itself — matching the read-side precedence join, see below),
 * and lets the remainder of the body flow through to the default CRUD for {@code c_tax} itself
 * — unless nothing but the {@code updated} version token is left, in which case the handler
 * answers the request itself and {@code c_tax} is not written (ETP-6062). Only the columns
 * actually present in the request are written to
 * {@code etsg_tax_sif_config} — an UPDATE never blanks a sibling column the caller did not
 * send. POST (create) is deliberately NOT intercepted: a brand-new {@code c_tax} row has no id
 * yet to key the override table on, and in practice these fields are only edited once a tax
 * already exists.
 *
 * <p><b>ETP-5229 — {@code writeOrg} is NOT always the session org.</b> When the SIF modal is
 * opened from an invoice/order LINE context, the frontend sends the invoice's own
 * {@code AD_Org_ID} as the {@code sifContextOrgId} query param, and {@code writeOrg} becomes
 * THAT organization instead of {@code obContext.getCurrentOrganization()}. This closes a real
 * bug: the user's currently-active session org can legitimately differ from the org of the
 * invoice they are editing (e.g. navigating a multi-org tree), and the read side
 * ({@code InvoiceLineTaxSifSelectorPolicy#querySifColumns}) always resolves the legal entity
 * from the INVOICE's own org, never the session's. Without {@code sifContextOrgId}, a save
 * could persist under one legal entity while the badge-read query looked it up under another —
 * upsert succeeds, badge never clears, no error anywhere. See
 * {@link #resolveWriteOrganizationId} for the full precedence and the standalone Tax-window
 * fallback that keeps working unchanged when the param is absent.
 *
 * <p><b>ETP-6062 — the context organization must belong to the caller.</b> {@code
 * sifContextOrgId} is honoured only when it names an organization of the caller's own client,
 * other than {@code *}: on a write it must also be one of the caller's writable organizations,
 * otherwise the write is refused with {@code 404 Record not found} before anything is stored; on
 * a read it must be readable, otherwise it is ignored and the session organization is used, the
 * same as a request without the param.
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
 * <h3>A System tax is not written, so it is not refused (ETP-6062)</h3>
 * <p>Taxes are System rows shared by every client, which the CRUD target guard refuses to let a
 * tenant write. A SIF-only update never writes the row — it lands in the tenant's own
 * {@code etsg_tax_sif_config} — so {@link #consumesTargetWrite} declares it, and the guard then
 * only requires the tax to be readable. A body that also carries a plain {@code c_tax} field
 * is not declared, and stays refused on a System tax BEFORE {@link #handle} runs, so nothing
 * is upserted for it either.
 *
 * <p>Bound by {@code @NeoExtension} (ETP-6062 migrated it off the {@code taxSifOverrideHandler}
 * {@code Java_Qualifier}). No {@code @Named} and never a normal CDI scope: the class stays a
 * {@code @Dependent} bean under {@code bean-discovery-mode="all"}.
 */
@NeoExtension(spec = "tax", entity = "tax")
public class TaxSifOverrideHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(TaxSifOverrideHandler.class);

  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";
  private static final String METHOD_GET = "GET";
  private static final String TAX_ENTITY_NAME = "tax";
  private static final String FIELD_ID = "id";
  // The optimistic-locking token the SPA's apiFetch adds to every update it sends (ETP-5073). It
  // versions the c_tax row, which a SIF-only update never writes, so it is not a field to persist.
  private static final String FIELD_UPDATED = "updated";
  private static final String PARAM_ORG_ID = "orgId";

  // ETP-5229: optional query param the frontend sends when the SIF modal is opened from an
  // invoice/order LINE context (e.g. TaxSifModal.jsx), carrying that document's OWN
  // AD_Org_ID — the exact organization InvoiceLineTaxSifSelectorPolicy#querySifColumns
  // resolves the legal entity from on the READ side (via SelectorContextResolver, which reads
  // the invoice/order's own `organization` property, NOT the caller's session org). When
  // present, both the write (upsertOverride) and the immediate read-back
  // (buildOverrideOnlyResponse) key off THIS organization instead of the session's currently
  // active one, so an override saved while the user's session org differs from the invoice's
  // own org lands under the SAME legal entity the badge-read path looks it up under.
  //
  // Absent (the standalone Tax window's own PATCH flow has no invoice in scope), the handler
  // falls back to the pre-ETP-5229 behavior: `obContext.getCurrentOrganization()`. Both
  // behaviors are intentional and permanent, not a transition — see resolveWriteOrganizationId.
  private static final String PARAM_SIF_CONTEXT_ORG_ID = "sifContextOrgId";

  // ETP-6062: the answer to a write whose context organization does not belong to the caller —
  // the same text and status the CRUD target guard gives an unknown record.
  private static final String MSG_RECORD_NOT_FOUND = "Record not found";
  private static final String SYSTEM_ORG_ID = "0";

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
    if (!carriesSifField(body)) {
      // Nothing SIF-related in this request — fall through unchanged.
      return null;
    }
    // ETP-6062: the context organization must belong to the caller, checked before anything is
    // written. An unacceptable one is refused, never replaced by the session org in silence.
    String sifContextOrgId = resolveSifContextOrgIdParam(context);
    if (sifContextOrgId != null && !isWritableContextOrganization(sifContextOrgId, obContext)) {
      // The client-supplied value is not echoed into the log.
      log.warn("TaxSifOverrideHandler.handle: the context organization does not belong to the "
          + "caller, tax {} not saved", taxId);
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, MSG_RECORD_NOT_FOUND);
    }
    try {
      Map<String, String> overrideValues = extractAndStripOverrideFields(body);
      // ETP-5229: resolve ONCE and reuse for both the write and the immediate read-back below,
      // so a request carrying sifContextOrgId is fully self-consistent end to end.
      String writeOrganizationId = resolveWriteOrganizationId(sifContextOrgId, obContext);
      OBContext.setAdminMode(true);
      try {
        upsertOverride(taxId, overrideValues, obContext, writeOrganizationId);
      } finally {
        OBContext.restorePreviousMode();
      }
      if (carriesTaxFields(body)) {
        // Remaining fields still need the default CRUD to persist them onto c_tax.
        return null;
      }
      return buildOverrideOnlyResponse(taxId, writeOrganizationId);
    } catch (Exception e) {
      log.error("TaxSifOverrideHandler.handle error for tax {}", taxId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "SIF override upsert failed: " + e.getMessage());
    }
  }

  /**
   * ETP-6062: an update whose body carries SIF fields and nothing of {@code c_tax} itself (the
   * version token aside) is stored entirely in {@code etsg_tax_sif_config} by {@link #handle},
   * which answers it with its own response — the tax row is never written. Declaring it lets a
   * tenant save its override of a System tax, which the CRUD target guard otherwise refuses.
   *
   * <p>Must agree with {@link #handle}: every body this answers {@code true} for is one
   * {@code handle} short-circuits. Pure — the body is only read.
   */
  @Override
  public boolean consumesTargetWrite(NeoContext context) {
    String method = context.getHttpMethod();
    if (!TAX_ENTITY_NAME.equals(context.getEntityName())
        || (!METHOD_PUT.equalsIgnoreCase(method) && !METHOD_PATCH.equalsIgnoreCase(method))) {
      return false;
    }
    JSONObject body = context.getRequestBody();
    if (StringUtils.isBlank(context.getRecordId()) || body == null) {
      return false;
    }
    boolean hasSifField = false;
    Iterator<?> keys = body.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      if (SIF_FIELD_TO_COLUMN.containsKey(key)) {
        hasSifField = true;
      } else if (!FIELD_UPDATED.equals(key)) {
        return false;
      }
    }
    return hasSifField;
  }

  /**
   * Whether the body, with the SIF fields already stripped, still carries a field the default
   * CRUD must persist onto {@code c_tax}. The version token alone is not one.
   */
  private static boolean carriesTaxFields(JSONObject body) {
    Iterator<?> keys = body.keys();
    while (keys.hasNext()) {
      if (!FIELD_UPDATED.equals(String.valueOf(keys.next()))) {
        return true;
      }
    }
    return false;
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

  /**
   * The organization the read-side overlay resolves the legal entity from: {@code
   * sifContextOrgId} when it is an organization of the caller's client that the caller may read
   * (ETP-6062), otherwise the session organization — the same value a request without the param
   * gets. A read never fails on the param; it just does not honour one that does not belong to
   * the caller.
   */
  private static String resolveContextOrganizationId(NeoContext context) {
    OBContext obContext = context.getObContext() != null ? context.getObContext()
        : OBContext.getOBContext();
    String sifContextOrgId = resolveSifContextOrgIdParam(context);
    if (sifContextOrgId != null && isReadableContextOrganization(sifContextOrgId, obContext)) {
      return sifContextOrgId;
    }
    if (sifContextOrgId != null) {
      // The client-supplied value is not echoed into the log.
      log.warn("TaxSifOverrideHandler.afterHandle: the context organization does not belong to "
          + "the caller, using the session organization");
    }
    if (obContext == null || obContext.getCurrentOrganization() == null) {
      return null;
    }
    return obContext.getCurrentOrganization().getId();
  }

  /**
   * ETP-6062: a context organization a write may key the override on — not {@code *} ({@code
   * 0}), one of the caller's writable organizations, and an organization of the caller's own
   * client. The last condition also keeps the legal entity {@code AD_GET_ORG_LE_BU} resolves from
   * it inside the caller's client.
   */
  private static boolean isWritableContextOrganization(String organizationId, OBContext obContext) {
    return obContext != null && obContext.getWritableOrganizations() != null
        && obContext.getWritableOrganizations().contains(organizationId)
        && belongsToCurrentClient(organizationId, obContext);
  }

  /**
   * ETP-6062: a context organization a read may resolve the overlay from — not {@code *}, one of
   * the caller's readable organizations, and an organization of the caller's own client.
   */
  private static boolean isReadableContextOrganization(String organizationId, OBContext obContext) {
    return obContext != null && obContext.getReadableOrganizations() != null
        && java.util.Arrays.asList(obContext.getReadableOrganizations()).contains(organizationId)
        && belongsToCurrentClient(organizationId, obContext);
  }

  /** Whether the organization exists and belongs to the current client; never {@code *}. */
  private static boolean belongsToCurrentClient(String organizationId, OBContext obContext) {
    if (SYSTEM_ORG_ID.equals(organizationId) || obContext.getCurrentClient() == null) {
      return false;
    }
    OBContext.setAdminMode(true);
    try {
      Organization organization = OBDal.getInstance().get(Organization.class, organizationId);
      return organization != null && organization.getClient() != null
          && StringUtils.equals(organization.getClient().getId(),
              obContext.getCurrentClient().getId());
    } catch (RuntimeException e) {
      log.warn("TaxSifOverrideHandler: could not check the context organization: {}",
          e.getClass().getSimpleName());
      return false;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static boolean carriesSifField(JSONObject body) {
    for (String jsonKey : SIF_FIELD_TO_COLUMN.keySet()) {
      if (body.has(jsonKey)) {
        return true;
      }
    }
    return false;
  }

  /**
   * ETP-5229: resolves the organization to key the SIF override write (and its immediate
   * read-back) on. Prefers the {@code sifContextOrgId} query param — sent by the frontend when
   * the SIF modal is opened from an invoice/order LINE context, carrying that document's own
   * {@code AD_Org_ID} — over the caller's session organization.
   *
   * <p>Two coexisting behaviors, both permanent:
   * <ul>
   *   <li><b>Invoice-line-initiated override</b> ({@code sifContextOrgId} present): keys off the
   *       INVOICE's own org, matching {@code InvoiceLineTaxSifSelectorPolicy#querySifColumns}'s
   *       read-side resolution (via {@code SelectorContextResolver}, which reads the invoice's
   *       own {@code organization} property). This is what fixes the badge-never-clears bug:
   *       write and read now agree on which legal entity the override belongs to, even when the
   *       user's currently-active session org differs from the invoice's own org.</li>
   *   <li><b>Standalone Tax-window override</b> (no {@code sifContextOrgId} — the Tax admin
   *       window's own PATCH flow has no invoice in scope): falls back to
   *       {@code obContext.getCurrentOrganization()}, exactly the pre-ETP-5229 behavior. This
   *       keeps working unmodified for the case this handler was originally built for.</li>
   * </ul>
   *
   * <p>ETP-6062: the param must name an organization that belongs to the caller ({@link
   * #isWritableContextOrganization}); {@link #handle} checks it first and refuses the write
   * otherwise, so the value handed in here is already accepted.
   *
   * @param sifContextOrgId the accepted {@code sifContextOrgId}, or {@code null} when absent
   * @return the organization id to resolve the legal entity from; never null (falls back to the
   *     session organization's id, which the {@code obContext == null} early-return in
   *     {@link #handle} already guarantees is present)
   */
  private static String resolveWriteOrganizationId(String sifContextOrgId, OBContext obContext) {
    return sifContextOrgId != null ? sifContextOrgId : obContext.getCurrentOrganization().getId();
  }

  private static String resolveSifContextOrgIdParam(NeoContext context) {
    Map<String, String> queryParams = context.getQueryParams();
    if (queryParams == null) {
      return null;
    }
    return StringUtils.trimToNull(queryParams.get(PARAM_SIF_CONTEXT_ORG_ID));
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
   * {@code (c_tax_id, AD_GET_ORG_LE_BU(writeOrganizationId, 'LE'))}. Only the columns present
   * in {@code
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
   * @param writeOrganizationId the organization to resolve the legal entity from — either the
   *     invoice/order's own org (ETP-5229, when {@code sifContextOrgId} was sent) or the
   *     caller's session org (standalone Tax-window flow); see
   *     {@link #resolveWriteOrganizationId}
   * @throws OBException if the legal entity organization cannot be resolved for {@code
   *                      writeOrganizationId}
   */
  private static void upsertOverride(String taxId, Map<String, String> values, OBContext obContext,
      String writeOrganizationId) {
    Session session = OBDal.getInstance().getSession();
    String leOrgId = resolveLegalEntityOrgId(session, writeOrganizationId);
    if (StringUtils.isBlank(leOrgId)) {
      throw new OBException(
          "Unable to resolve the legal entity organization for organization " + writeOrganizationId
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
    upsert.setParameter(PARAM_ORG_ID, leOrgId);
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
      query.setParameter(PARAM_ORG_ID, organizationId);
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
  private static NeoResponse buildOverrideOnlyResponse(String taxId, String organizationId)
      throws JSONException {
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
      query.setParameter(PARAM_ORG_ID, organizationId);
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
