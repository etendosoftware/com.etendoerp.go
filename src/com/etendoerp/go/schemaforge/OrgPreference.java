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


package com.etendoerp.go.schemaforge;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.selector.meta.SelectorContextResolver;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;

/**
 * ETP-5086: organization preference of the combo first-option fallback. Gives the fallback an
 * organization context and ranks the selector candidates by closeness to it (exact org, nearest
 * ancestor, {@code *}) before the display name.
 */
final class OrgPreference {

  /** Context param asking the selector to rank candidates by closeness to the context org. */
  static final String PARAM_PREFER_CONTEXT_ORG = "preferContextOrg";
  /**
   * Extra filter param carrying the organization the first-option fallback ranks rows by (see
   * {@link SelectorOrgFilter#buildOrgProximityOrder}); not a bindable HQL parameter. Honoured by
   * the simple and plain rich queries; custom-HQL and value-paged selectors keep display order.
   */
  static final String PARAM_PREFER_ORG = "selectorPreferOrgId";
  private static final String PARAM_AD_ORG_ID = "AD_Org_ID";
  private static final String PARAM_PARENT_ID = "parentId";
  private static final String PROP_ORGANIZATION = "organization";
  private static final String ORG_STAR = "0";
  private static final String ORDER_BY = " ORDER BY ";

  private OrgPreference() {
  }

  /**
   * Gives the combo first-option fallback the organization of the record being defaulted, so the
   * selector restricts candidates to that organization's natural tree and ranks them by closeness
   * to it. Mirrors Classic, where the record's {@code AD_Org_ID} is resolved before any combo and
   * {@code ComboTableData} filters every combo by its natural tree:
   * <ol>
   * <li>the organization sent for the record (request body);</li>
   * <li>else the parent record's organization ({@code parentId});</li>
   * <li>else the session organization ({@code #AD_Org_ID}).</li>
   * </ol>
   * An organization {@code 0} ({@code *}) has every organization in its natural tree, so it adds
   * no restriction, as in Classic. A caller-supplied {@code AD_Org_ID} is kept.
   */
  static void putFallbackOrganization(Map<String, String> params, NeoContext ctx,
      String parentId) {
    if (ctx == null || params.containsKey(PARAM_AD_ORG_ID)) {
      return;
    }
    String orgId = resolveRecordOrganization(ctx, parentId);
    if (orgId != null && !ORG_STAR.equals(orgId)) {
      params.put(PARAM_AD_ORG_ID, orgId);
      params.put(PARAM_PREFER_CONTEXT_ORG, "Y");
    }
  }

  private static String resolveRecordOrganization(NeoContext ctx, String parentId) {
    JSONObject requestBody = ctx.getRequestBody();
    String orgId = requestBody != null
        ? StringUtils.trimToNull(requestBody.optString(PROP_ORGANIZATION, null)) : null;
    if (orgId != null) {
      return orgId;
    }
    String effectiveParentId = StringUtils.trimToNull(parentId);
    if (effectiveParentId == null && ctx.getQueryParams() != null) {
      effectiveParentId = StringUtils.trimToNull(ctx.getQueryParams().get(PARAM_PARENT_ID));
    }
    if (effectiveParentId != null) {
      orgId = SelectorContextResolver.resolveOrgFromParentRecord(ctx.getSfEntity(),
          effectiveParentId);
      if (orgId != null) {
        return orgId;
      }
    }
    OBContext obContext = ctx.getObContext() != null ? ctx.getObContext() : OBContext.getOBContext();
    Organization sessionOrg = obContext != null ? obContext.getCurrentOrganization() : null;
    return sessionOrg != null ? sessionOrg.getId() : null;
  }

  /**
   * The first-option fallback (flag {@code preferContextOrg=Y}) ranks candidates by closeness to the
   * resolved context organization; any other request keeps display order.
   */
  static void putOrgPreference(Map<String, Object> filterParams, Map<String, String> contextParams,
      String contextOrganizationId) {
    if ("Y".equals(contextParams.get(PARAM_PREFER_CONTEXT_ORG))
        && StringUtils.isNotBlank(contextOrganizationId)) {
      filterParams.put(PARAM_PREFER_ORG, contextOrganizationId);
    }
  }

  /** The org-proximity ORDER BY prefix ({@code "CASE ... END"}) and its params, or {@code null}. */
  static SelectorQueryBuilder.HqlWithParams orgPreferenceOrder(SelectorMeta meta, String alias,
      Map<String, Object> extraFilterParams) {
    Object orgId = extraFilterParams != null ? extraFilterParams.get(PARAM_PREFER_ORG) : null;
    if (orgId == null) {
      return null;
    }
    return SelectorOrgFilter.buildOrgProximityOrder(meta.entityName, alias, orgId.toString());
  }

  /** {@code " ORDER BY [<org proximity>, ]alias.display, alias.id"}; the id breaks display ties (ETP-5903). */
  static String orderBy(SelectorQueryBuilder.HqlWithParams orgOrder, String alias,
      String displayProperty) {
    return ORDER_BY + (orgOrder != null ? orgOrder.getHql() + ", " : "") + alias + "."
        + displayProperty + ", " + alias + ".id";
  }

  /** Binds the params of the org-proximity ORDER BY, when there is one. */
  static void bindOrgOrder(OBQuery<BaseOBObject> query, SelectorQueryBuilder.HqlWithParams orgOrder) {
    if (orgOrder != null) {
      NeoSelectorExecutionHelper.bindNamedParameters(query, orgOrder.getParams());
    }
  }
}
