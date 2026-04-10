package com.etendoerp.go.schemaforge;

import java.util.Collection;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.dal.service.OBQuery;

/**
 * Small execution helpers extracted from {@link NeoSelectorService}
 * to keep the service focused on selector orchestration.
 */
class NeoSelectorExecutionHelper {

  private NeoSelectorExecutionHelper() {
  }

  static void appendSelectorDescriptor(JSONArray selectors, Column column) throws Exception {
    String refId = NeoSelectorService.getBaseReferenceId(column);
    if (NeoSelectorService.isListReference(refId)) {
      selectors.put(SelectorDescriptorBuilder.buildListSelectorItem(column));
      return;
    }
    boolean isObuisel = NeoSelectorService.hasObuiselSelector(column);
    if (isObuisel || NeoSelectorService.isFkReference(refId)) {
      SelectorMeta meta = NeoSelectorService.resolveTarget(column, refId);
      if (meta != null) {
        selectors.put(SelectorDescriptorBuilder.buildSelectorItem(
            column, refId, meta, NeoSelectorService.getSessionParams()));
      }
    }
  }

  static void appendResolvedWhereClause(StringBuilder hql, Map<String, Object> queryParams,
      String whereClause) {
    if (StringUtils.isBlank(whereClause)) {
      return;
    }
    SelectorQueryBuilder.HqlWithParams resolvedWhereClause =
        SelectorQueryBuilder.resolveObuiselParams(whereClause);
    hql.append(resolvedWhereClause.getHql());
    queryParams.putAll(resolvedWhereClause.getParams());
  }

  static void appendLiteralFilter(StringBuilder hql, String filter) {
    if (StringUtils.isBlank(filter)) {
      return;
    }
    appendAndIfNeeded(hql);
    hql.append(filter);
  }

  static void appendSelectorOrganizationFilter(StringBuilder hql,
      Map<String, Object> queryParams, SelectorMeta meta, String contextOrganizationId) {
    SelectorQueryBuilder.HqlWithParams orgFilter = SelectorQueryBuilder.buildOrganizationPredicate(
        meta.entityName, "e", contextOrganizationId, true);
    if (orgFilter == null || orgFilter.isBlank()) {
      orgFilter = SelectorQueryBuilder.buildReadableOrgsPredicate(meta.entityName, "e", true);
    }
    if (orgFilter == null || orgFilter.isBlank()) {
      return;
    }
    appendAndIfNeeded(hql);
    hql.append(orgFilter.getHql());
    queryParams.putAll(orgFilter.getParams());
  }

  static void appendSimpleSearchFilter(StringBuilder hql, String displayProperty,
      String search) {
    if (StringUtils.isBlank(search)) {
      return;
    }
    appendAndIfNeeded(hql);
    hql.append("lower(e.").append(displayProperty).append(") LIKE :search");
  }

  static String buildSimpleWhereClause(StringBuilder hql) {
    return hql.length() > 0 ? "as e where " + hql : "as e";
  }

  static void bindNamedParameters(OBQuery<?> query, Map<String, Object> params) {
    if (params == null || params.isEmpty()) {
      return;
    }
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      query.setNamedParameter(entry.getKey(), entry.getValue());
    }
  }

  static void bindNamedParameters(org.hibernate.query.Query<?> query,
      Map<String, Object> params) {
    if (params == null || params.isEmpty()) {
      return;
    }
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      if (entry.getValue() instanceof Collection<?>) {
        query.setParameterList(entry.getKey(), (Collection<?>) entry.getValue());
      } else {
        query.setParameter(entry.getKey(), entry.getValue());
      }
    }
  }

  private static void appendAndIfNeeded(StringBuilder hql) {
    if (hql.length() > 0) {
      hql.append(SelectorQueryBuilder.SQL_AND);
    }
  }
}
