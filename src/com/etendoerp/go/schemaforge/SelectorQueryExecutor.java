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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import com.etendoerp.go.schemaforge.selector.meta.RichFieldMeta;
import com.etendoerp.go.schemaforge.selector.meta.SelectorMeta;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.schemaforge.util.NeoTrl;

/**
 * Executes resolved selector query plans against DAL/HQL and maps rows into selector responses.
 */
final class SelectorQueryExecutor {

  private static final Logger log = LogManager.getLogger(SelectorQueryExecutor.class);
  private static final String PARAM_SEARCH = "search";
  private static final String PARAM_SEARCH_LANG = "searchLang";
  private static final String FIELD_LABEL = "label";
  private static final String PARAM_LANGUAGE = "language";

  private SelectorQueryExecutor() {
  }

  static NeoResponse execute(SelectorMeta meta, String search, int limit, int offset,
      String validationFilter, String contextOrganizationId) throws Exception {
    return execute(meta, search, limit, offset, validationFilter, contextOrganizationId, null);
  }

  static NeoResponse execute(SelectorMeta meta, String search, int limit, int offset,
      String validationFilter, String contextOrganizationId,
      Map<String, Object> extraFilterParams) throws Exception {
    if (meta.isRich) {
      return executeRichQuery(meta, search, limit, offset, validationFilter, contextOrganizationId,
          extraFilterParams);
    }
    return executeQuery(meta, search, limit, offset, validationFilter, contextOrganizationId,
        extraFilterParams);
  }

  private static NeoResponse executeQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter,
      String contextOrganizationId, Map<String, Object> extraFilterParams) throws Exception {

    // Extract language before binding — it is not an HQL parameter
    String language = null;
    Map<String, Object> safeExtraParams = extraFilterParams;
    if (extraFilterParams != null && extraFilterParams.containsKey(PARAM_LANGUAGE)) {
      language = (String) extraFilterParams.get(PARAM_LANGUAGE);
      safeExtraParams = new HashMap<>(extraFilterParams);
      safeExtraParams.remove(PARAM_LANGUAGE);
    }

    StringBuilder hql = new StringBuilder();
    Map<String, Object> queryParams = new HashMap<>();
    NeoSelectorExecutionHelper.appendResolvedWhereClause(hql, queryParams, meta.whereClause);
    NeoSelectorExecutionHelper.appendLiteralFilter(hql, validationFilter);
    NeoSelectorExecutionHelper.appendSelectorOrganizationFilter(hql, queryParams, meta,
        contextOrganizationId);
    // Match the search against the translated name too, so users can search selectors in the GO
    // locale (e.g. "pie" → Pie/Pie Cúbico), not only the base language (ETP-4304). Falls back to a
    // base-only filter when the entity has no *_Trl or there is no request language.
    String searchLang = resolveEnrichLanguage(language);
    NeoTrl.TrlSearchMeta trlSearch = (StringUtils.isNotBlank(search) && StringUtils.isNotBlank(searchLang))
        ? NeoTrl.resolveSearchMeta(meta.entityName) : null;
    if (trlSearch != null) {
      NeoSelectorExecutionHelper.appendTranslatedSearchFilter(hql, meta.displayProperty, search, trlSearch);
      queryParams.put(PARAM_SEARCH_LANG, searchLang);
    } else {
      NeoSelectorExecutionHelper.appendSimpleSearchFilter(hql, meta.displayProperty, search);
    }
    if (safeExtraParams != null) {
      queryParams.putAll(safeExtraParams);
    }

    String whereStr = NeoSelectorExecutionHelper.buildSimpleWhereClause(hql);

    // Guard: selectors whose filter contains outer-query table aliases (e.g. td0.c_uom_id) cannot
    // be executed as standalone HQL — doing so throws InvalidPathException which marks the
    // Hibernate session as rollback-only and aborts the enclosing transaction. Return empty
    // results instead so injectMandatoryDefaults can continue safely.
    if (whereStr != null && whereStr.contains("td0.")) {
      log.warn("[SELECTOR] Skipping selector with outer-context reference (td0.) for entity {}: {}",
          meta.entityName, whereStr);
      return SelectorResponseSupport.buildSelectorResponse(
          new JSONArray(), new JSONArray(), 0, limit, offset);
    }

    OBQuery<BaseOBObject> countQuery = OBDal.getInstance().createQuery(meta.entityName, whereStr);
    NeoSelectorExecutionHelper.bindNamedParameters(countQuery, queryParams);
    if (StringUtils.isNotBlank(search)) {
      countQuery.setNamedParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.count();

    String dataWhere = whereStr + " ORDER BY e." + meta.displayProperty;
    OBQuery<BaseOBObject> dataQuery = OBDal.getInstance().createQuery(meta.entityName, dataWhere);
    NeoSelectorExecutionHelper.bindNamedParameters(dataQuery, queryParams);
    if (StringUtils.isNotBlank(search)) {
      dataQuery.setNamedParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResult(limit);
    dataQuery.setFirstResult(offset);

    JSONArray items = buildSimpleSelectorItems(dataQuery.list(), meta);

    enrichTranslations(items, meta.entityName, searchLang);

    return SelectorResponseSupport.buildSelectorResponse(items, new JSONArray(), totalCount, limit, offset);
  }

  /**
   * Builds the {id, label} JSON items for a simple (non-rich) selector result set.
   * The label comes from a resolved dotted display property when present, otherwise from the
   * entity identifier. Extracted from executeQuery to keep its cognitive complexity within range.
   */
  private static JSONArray buildSimpleSelectorItems(List<BaseOBObject> rows, SelectorMeta meta)
      throws Exception {
    JSONArray items = new JSONArray();
    final boolean hasDottedDisplay = meta.displayProperty != null && meta.displayProperty.contains(".");
    for (BaseOBObject bob : rows) {
      JSONObject item = new JSONObject();
      item.put("id", SelectorRowMapper.normalizeEntityId(bob.getId().toString()));
      if (hasDottedDisplay) {
        Object labelValue = resolvePropertyValue(bob, meta.displayProperty);
        item.put(FIELD_LABEL, labelValue != null ? labelValue : bob.getIdentifier());
      } else {
        item.put(FIELD_LABEL, bob.getIdentifier());
      }
      items.put(item);
    }
    return items;
  }

  private static NeoResponse executeRichQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter,
      String contextOrganizationId, Map<String, Object> extraFilterParams) throws Exception {

    if (meta.isCustomQuery && StringUtils.isNotBlank(meta.customHql)) {
      return executeCustomHqlQuery(meta, search, limit, offset, validationFilter,
          contextOrganizationId, extraFilterParams);
    }

    String alias = "e";
    SelectorQueryBuilder.HqlWithParams whereClause = SelectorQueryBuilder.buildRichQueryWhereClause(
        meta, search, validationFilter, alias, contextOrganizationId);
    boolean hasSearch = StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty();

    OBQuery<BaseOBObject> countQuery = OBDal.getInstance()
        .createQuery(meta.entityName, whereClause.getHql());
    NeoSelectorExecutionHelper.bindNamedParameters(countQuery, whereClause.getParams());
    NeoSelectorExecutionHelper.bindNamedParameters(countQuery, extraFilterParams);
    if (hasSearch) {
      countQuery.setNamedParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.count();

    String dataWhere = whereClause.getHql() + " ORDER BY " + alias + "." + meta.displayProperty;
    OBQuery<BaseOBObject> dataQuery = OBDal.getInstance().createQuery(meta.entityName, dataWhere);
    NeoSelectorExecutionHelper.bindNamedParameters(dataQuery, whereClause.getParams());
    NeoSelectorExecutionHelper.bindNamedParameters(dataQuery, extraFilterParams);
    if (hasSearch) {
      dataQuery.setNamedParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResult(limit);
    dataQuery.setFirstResult(offset);

    JSONArray columns = SelectorResponseSupport.buildGridColumnMetadata(meta.gridFields);
    JSONArray items = new JSONArray();
    List<String> entityIds = new ArrayList<>();
    for (BaseOBObject bob : dataQuery.list()) {
      JSONObject item = new JSONObject();
      String itemId = resolveRichItemId(bob, meta);
      item.put("id", itemId);
      item.put(FIELD_LABEL, bob.getIdentifier());
      entityIds.add(itemId);
      entityIds.add(bob.getId().toString());

      for (RichFieldMeta fieldMeta : meta.gridFields) {
        Object value = resolvePropertyValue(bob, fieldMeta.property);
        item.put(fieldMeta.propertyKey, value != null ? value : JSONObject.NULL);
      }
      SelectorAuxResolver.appendAuxFields(item, bob, meta.auxFields);
      items.put(item);
    }

    enrichTranslations(items, meta.entityName, resolveEnrichLanguage(null));

    return SelectorResponseSupport.buildSelectorResponse(items, columns, totalCount, limit, offset);
  }

  @SuppressWarnings("unchecked")
  private static NeoResponse executeCustomHqlQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter,
      String contextOrganizationId, Map<String, Object> extraFilterParams) throws Exception {

    String alias = meta.entityAlias;
    String rawHql = meta.customHql.replace("@additional_filters@", "1=1");
    java.util.regex.Matcher fromMatcher = Pattern.compile("\\sFROM\\s",
        Pattern.CASE_INSENSITIVE).matcher(rawHql);
    if (!fromMatcher.find()) {
      throw new IllegalArgumentException("Custom HQL does not contain a FROM clause: " + rawHql);
    }
    int fromIdx = fromMatcher.start();
    String fromOnwards = rawHql.substring(fromIdx);

    SelectorQueryBuilder.HqlWithParams fromClause = SelectorQueryBuilder.buildCustomHqlFromClause(
        fromOnwards, alias, meta, validationFilter, search, contextOrganizationId);
    boolean hasSearch = StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty();

    String selectPart = rawHql.substring(0, fromIdx).trim();
    String[] selectExprs = selectPart.replaceFirst("(?i)^select\\s+", "").split(",");
    Map<String, Integer> colIndexMap = SelectorRowMapper.buildSelectColumnIndexMap(selectExprs);

    Map<String, Object> fromParams = new HashMap<>(fromClause.getParams());
    if (extraFilterParams != null) {
      fromParams.putAll(extraFilterParams);
    }

    String countHql = "SELECT COUNT(" + alias + ")" + fromClause.getHql();
    org.hibernate.query.Query<Long> countQuery = OBDal.getInstance()
        .getSession().createQuery(countHql, Long.class);
    NeoSelectorExecutionHelper.bindNamedParameters(countQuery, fromParams);
    if (hasSearch) {
      countQuery.setParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    Long countResult = countQuery.uniqueResult();
    int totalCount = (countResult != null) ? countResult.intValue() : 0;

    String dataHql = selectPart + fromClause.getHql() + " ORDER BY " + alias + "."
        + meta.displayProperty;
    org.hibernate.query.Query<?> dataQuery = OBDal.getInstance().getSession().createQuery(dataHql);
    NeoSelectorExecutionHelper.bindNamedParameters(dataQuery, fromParams);
    if (hasSearch) {
      dataQuery.setParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResults(limit);
    dataQuery.setFirstResult(offset);

    Integer idColIdx = SelectorRowMapper.resolveIdColumnIndex(meta, alias, colIndexMap, selectExprs);
    JSONArray columns = SelectorResponseSupport.buildGridColumnMetadata(meta.gridFields);
    Entity entityDef = ModelProvider.getInstance().getEntity(meta.entityName);
    JSONArray items = new JSONArray();
    List<String> entityIds = new ArrayList<>();
    for (Object rawRow : dataQuery.list()) {
      Object[] row = (rawRow instanceof Object[]) ? (Object[]) rawRow : new Object[]{ rawRow };
      JSONObject item = new JSONObject();

      String recordId = SelectorResponseSupport.extractRecordId(row, idColIdx);
      item.put("id", recordId);
      entityIds.add(recordId);
      item.put(FIELD_LABEL,
          SelectorRowMapper.extractDisplayLabel(row, colIndexMap, meta.displayProperty,
              entityDef, recordId));
      SelectorRowMapper.mapGridFieldsToItem(item, row, colIndexMap, meta.gridFields);
      items.put(item);
    }

    boolean hasHqlOnlyAux = meta.auxFields.stream()
        .anyMatch(af -> StringUtils.isBlank(af.property) && StringUtils.isNotBlank(af.hqlAlias));
    if (hasHqlOnlyAux && !entityIds.isEmpty()) {
      SelectorAuxResolver.resolveAuxFieldsViaHql(items, entityIds, rawHql, fromIdx, alias, meta);
    }

    return SelectorResponseSupport.buildSelectorResponse(items, columns, totalCount, limit, offset);
  }

  private static String resolveRichItemId(BaseOBObject bob, SelectorMeta meta) {
    if (meta.valueProperty != null && !"id".equals(meta.valueProperty)) {
      Object val = resolvePropertyValue(bob, meta.valueProperty);
      if (val != null) {
        return val.toString();
      }
    }
    return SelectorRowMapper.normalizeEntityId(bob.getId().toString());
  }

  private static Object resolvePropertyValue(BaseOBObject bob, String propertyPath) {
    try {
      String[] parts = propertyPath.split("\\.");
      Object current = bob;
      for (String part : parts) {
        if (current == null) {
          return null;
        }
        if (current instanceof BaseOBObject) {
          current = ((BaseOBObject) current).get(part);
        } else {
          return current;
        }
      }
      if (current instanceof BaseOBObject) {
        return ((BaseOBObject) current).getIdentifier();
      }
      return current;
    } catch (Exception e) {
      log.debug("Could not resolve property {} on {}: {}",
          propertyPath, bob.getId(), e.getMessage());
      return null;
    }
  }

  /**
   * The language to resolve selector-value translations into: the GO locale already applied to the
   * request by {@code NeoAuthenticator} ({@link NeoLanguage#currentCode()}), falling back to an
   * explicit {@code language} context param when there is no request locale.
   */
  private static String resolveEnrichLanguage(String contextParamLanguage) {
    String current = NeoLanguage.currentCode();
    return current != null ? current : contextParamLanguage;
  }

  /**
   * Replaces base-language selector labels with their {@code *_Trl} translation for {@code language}
   * when one exists, for any translatable entity (UoM, Country, …). Entity identifiers are not
   * translated by Etendo, so this is what makes selector values honor the GO locale (ETP-4304).
   * Falls back silently to the original label when there is no translation.
   */
  private static void enrichTranslations(JSONArray items, String entityName, String language)
      throws Exception {
    if (items.length() == 0 || StringUtils.isBlank(language)) {
      return;
    }
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < items.length(); i++) {
      ids.add(items.getJSONObject(i).getString("id"));
    }
    Map<String, String> translations = NeoTrl.translatedNames(entityName, ids, language);
    if (translations.isEmpty()) {
      return;
    }
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      String translated = translations.get(item.getString("id"));
      if (translated != null) {
        item.put(FIELD_LABEL, translated);
      }
    }
  }
}
