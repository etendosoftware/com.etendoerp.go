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
import org.openbravo.dal.service.OBQuery;

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
  private static final String ORDER_BY = " ORDER BY ";

  private OrgPreference() {
  }

  /**
   * Gives the combo first-option fallback an organization context, so the selector restricts
   * candidates to the target record's organization tree (plus {@code *}) and ranks them by closeness
   * to that organization. The organization comes from the record being created (request body), else
   * from the parent record (via {@code parentId}). Organization {@code 0} carries no preference.
   * With neither source, nothing is added and the fallback behaves as before.
   */
  static void putFallbackOrganization(Map<String, String> params, NeoContext ctx) {
    if (ctx == null || params.containsKey(PARAM_AD_ORG_ID) || params.containsKey(PARAM_PARENT_ID)) {
      return;
    }
    String orgId = null;
    JSONObject requestBody = ctx.getRequestBody();
    if (requestBody != null) {
      orgId = StringUtils.trimToNull(requestBody.optString("organization", null));
    }
    if ("0".equals(orgId)) {
      orgId = null;
    }
    if (orgId != null) {
      params.put(PARAM_AD_ORG_ID, orgId);
      params.put(PARAM_PREFER_CONTEXT_ORG, "Y");
      return;
    }
    Map<String, String> queryParams = ctx.getQueryParams();
    String parentId = queryParams != null ? StringUtils.trimToNull(queryParams.get(PARAM_PARENT_ID)) : null;
    if (parentId != null) {
      params.put(PARAM_PARENT_ID, parentId);
      params.put(PARAM_PREFER_CONTEXT_ORG, "Y");
    }
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
