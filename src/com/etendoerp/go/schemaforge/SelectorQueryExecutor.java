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
import java.util.regex.Matcher;
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
  private static final String ORDER_BY = " ORDER BY ";
  private static final String PARAM_SEARCH_LANG = "searchLang";
  private static final String FIELD_LABEL = "label";
  private static final String PARAM_LANGUAGE = "language";
  /** Aux out-field suffix carrying the per-locator on-hand quantity of a stock-breakdown selector. */
  private static final String AUX_SUFFIX_QTY = "_QTY";
  /** Aux out-field suffix carrying the per-locator storage bin of a stock-breakdown selector. */
  private static final String AUX_SUFFIX_LOC = "_LOC";

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

    // For view-backed selectors whose Value Field is not the entity PK (e.g. Product Complete →
    // M_Product_Stock_V, one row per product×warehouse×locator), the same value appears in many
    // rows. Restrict both the count and the data query to one representative row per distinct
    // valueProperty so the response holds one item per value (no duplicates) and paging math is
    // correct. Guard mirrors resolveRichItemId exactly: only when valueProperty is a non-PK path.
    // Stock-breakdown selectors are the deliberate exception (see shouldDistinctByValue): they
    // MUST keep every per-locator row. When the guard is false the where clause is byte-identical
    // to before (zero regression).
    boolean distinctByValue = shouldDistinctByValue(meta);
    String effectiveWhere = distinctByValue
        ? buildRepresentativeRowWhere(whereClause.getHql(), meta, alias)
        : whereClause.getHql();

    // count(*) over representative rows == count(distinct valueProperty), because the subquery
    // yields exactly one row per distinct value. Both count and data use the same OBQuery path,
    // so Hibernate's automatic readable-client/org filters apply identically to each.
    OBQuery<BaseOBObject> countQuery = OBDal.getInstance()
        .createQuery(meta.entityName, effectiveWhere);
    NeoSelectorExecutionHelper.bindNamedParameters(countQuery, whereClause.getParams());
    NeoSelectorExecutionHelper.bindNamedParameters(countQuery, extraFilterParams);
    if (hasSearch) {
      countQuery.setNamedParameter(PARAM_SEARCH, "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.count();

    String dataWhere = effectiveWhere + ORDER_BY + alias + "." + meta.displayProperty;
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

  /**
   * Decides whether the rich-selector query must be collapsed to one representative row per
   * distinct {@code valueProperty} (the ETP-4429 de-duplication of view-backed selectors).
   *
   * <p>De-duplication is enabled only when the Value Field is a non-PK path (mirrors
   * {@link #resolveRichItemId}), AND the selector is NOT a stock-breakdown selector. Stock-breakdown
   * selectors (see {@link #isStockBreakdownSelector}) are the deliberate exception: their multiple
   * rows per value are the payload the frontend needs, not duplicates to collapse.</p>
   */
  private static boolean shouldDistinctByValue(SelectorMeta meta) {
    return meta.valueProperty != null
        && !"id".equals(meta.valueProperty)
        && !isStockBreakdownSelector(meta);
  }

  /**
   * Detects a "stock-breakdown" selector — one that exposes a per-row on-hand-quantity and/or
   * storage-bin aux out-field ({@code _QTY} / {@code _LOC}). The canonical case is the "Product
   * Complete" selector over {@code M_Product_Stock_V}, used by the goods-movements and
   * internal-consumption line product-search drawers, which returns ONE row per storage bin holding
   * stock (plus a synthetic generic zero-stock row).
   *
   * <p>Such selectors must NOT be de-duplicated by {@code valueProperty}: the per-locator rows are
   * exactly what the drawer renders (it reads {@code _aux._QTY} / {@code _aux._LOC} per row). The
   * ETP-4429 de-dup picks the representative row via {@code MIN(id)}, but in
   * {@code M_Product_Stock_V} the generic zero-stock row's id equals {@code <m_product_id>} — a
   * strict string prefix of every real per-locator row id ({@code <m_product_id><m_storage_detail_id>})
   * — so string {@code MIN()} always selects the zero-stock row, collapsing stock to 0 and destroying
   * the breakdown. Skipping de-dup here returns all rows and fixes that.</p>
   *
   * <p>The signal keys off selector metadata (the OBUISEL aux out-field suffixes), not the window, so
   * it covers every window sharing this selector. It cannot misfire on classic FK view-backed
   * selectors: those expose no {@code _QTY}/{@code _LOC} aux out-fields, so they keep de-dup ON
   * exactly as ETP-4429 established.</p>
   */
  private static boolean isStockBreakdownSelector(SelectorMeta meta) {
    if (meta.auxFields == null) {
      return false;
    }
    return meta.auxFields.stream().anyMatch(af -> af != null
        && (AUX_SUFFIX_QTY.equalsIgnoreCase(af.suffix) || AUX_SUFFIX_LOC.equalsIgnoreCase(af.suffix)));
  }

  /**
   * Rewrites a rich-selector OBQuery where-string so it returns a single representative row per
   * distinct {@code valueProperty}. Used only when the selector's Value Field is not the entity PK.
   *
   * <p>The representative row is deterministic: the one with the minimum {@code id} within each
   * value group. The very same filter (the original where conditions) is re-applied inside the
   * subquery — with the entity alias rewritten to a private sub-alias — so filtering stays
   * consistent and the chosen representative always satisfies the outer predicate. Because there is
   * exactly one representative per value, {@code count(*)} over the result equals
   * {@code count(distinct valueProperty)}, which keeps limit/offset paging math correct.</p>
   *
   * <p>Input format is what {@link SelectorQueryBuilder#buildRichQueryWhereClause} produces:
   * {@code "as e"} or {@code "as e where <conditions>"}. Named parameters that appear in the
   * original conditions are duplicated into the subquery text, but Hibernate binds each named
   * parameter to every occurrence, so the existing single binding still applies.</p>
   */
  private static String buildRepresentativeRowWhere(String baseWhere, SelectorMeta meta,
      String alias) {
    final String whereKeyword = " where ";
    int whereIdx = StringUtils.indexOfIgnoreCase(baseWhere, whereKeyword);
    String conditions = (whereIdx >= 0)
        ? baseWhere.substring(whereIdx + whereKeyword.length())
        : null;

    String subAlias = alias + "_dv";
    StringBuilder subQuery = new StringBuilder();
    subQuery.append("select min(").append(subAlias).append(".id) from ")
        .append(meta.entityName).append(" ").append(subAlias);
    if (conditions != null) {
      subQuery.append(whereKeyword).append(rewriteAlias(conditions, alias, subAlias));
    }
    subQuery.append(" group by ").append(subAlias).append(".").append(meta.valueProperty);

    StringBuilder result = new StringBuilder("as ").append(alias).append(whereKeyword);
    if (conditions != null) {
      result.append("(").append(conditions).append(")").append(SelectorQueryBuilder.SQL_AND);
    }
    result.append(alias).append(".id in (").append(subQuery).append(")");
    return result.toString();
  }

  /**
   * Rewrites {@code <alias>.} property references in an HQL fragment to use {@code <newAlias>.}.
   * The negative look-behind avoids touching identifiers that merely end in the alias letters
   * (e.g. {@code table.name}) and leaves named parameters ({@code :search}) untouched.
   *
   * <p>KNOWN LIMITATION: this is a purely lexical rewrite — it does not skip string literals. A
   * WHERE condition embedding {@code <alias>.} inside a single-quoted literal (e.g.
   * {@code 'see e.g. below'}) would be corrupted. This is safe for every value-field selector in
   * use today (their resolved HQL where clauses are simple: {@code e.active='Y'} plus org filters,
   * no literals containing the alias). A future value-field selector with such a literal would
   * need this rewrite hardened to skip quoted literals before it could be used safely.</p>
   */
  private static String rewriteAlias(String hql, String alias, String newAlias) {
    Pattern aliasRef = Pattern.compile("(?<![\\w.])" + Pattern.quote(alias) + "\\.");
    return aliasRef.matcher(hql).replaceAll(Matcher.quoteReplacement(newAlias + "."));
  }

  @SuppressWarnings("unchecked")
  private static NeoResponse executeCustomHqlQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter,
      String contextOrganizationId, Map<String, Object> extraFilterParams) throws Exception {

    // ETP-5703: a custom-HQL selector can return several rows for ONE record — a join to a child
    // table (ProductSimple joins PricingProductPrice, one row per price-list version) multiplies
    // them. No blanket "SELECT DISTINCT": the projection has many columns, so it would dedupe by the
    // whole tuple. When the projection's record-id column is a plain path ending in `.id` AND the
    // display property is a plain (non-association) property of the selector entity — both decided
    // up front, structurally, with no query — the query is paged by RECORD instead of by row (see
    // pageRecordIds): count, offset, limit and hasMore all use the same unit, so no record is
    // repeated across pages or made unreachable. Otherwise it keeps the row-based flow it always had.
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
    Integer idColIdx = SelectorRowMapper.resolveIdColumnIndex(meta, alias, colIndexMap, selectExprs);
    String idExpr = plainIdExpression(selectExprs, idColIdx);

    Map<String, Object> fromParams = new HashMap<>(fromClause.getParams());
    if (extraFilterParams != null) {
      fromParams.putAll(extraFilterParams);
    }
    String searchParam = hasSearch ? "%" + search.toLowerCase() + "%" : null;

    String displayOrder = alias + "." + meta.displayProperty;
    // Decided up front, with no query: no fallback on failure. A failing Hibernate query marks the
    // transaction rollback-only before any catch could run, and custom-HQL selectors also run
    // inside writes (FK-by-name resolution), so a swallowed failure would let a write report
    // success and lose its commit. If record paging is chosen and fails, the error propagates.
    RecordPage page = idExpr != null && displayIsPrimitive(meta)
        ? fetchRecordPage(new RecordQuery(selectPart, fromClause.getHql(), fromOnwards, idExpr,
            displayOrder, fromParams, searchParam), limit, offset)
        : fetchRowPage(alias, new RecordQuery(selectPart, fromClause.getHql(), fromOnwards, null,
            displayOrder, fromParams, searchParam), limit, offset);

    JSONArray columns = SelectorResponseSupport.buildGridColumnMetadata(meta.gridFields);
    Entity entityDef = ModelProvider.getInstance().getEntity(meta.entityName);
    List<String> entityIds = new ArrayList<>();
    JSONArray items = page.ids != null
        ? itemsByRecord(page, idColIdx, colIndexMap, meta, entityDef, entityIds)
        : itemsByRow(page.rows, idColIdx, colIndexMap, meta, entityDef, entityIds);
    int totalCount = page.totalCount;

    boolean hasHqlOnlyAux = meta.auxFields.stream()
        .anyMatch(af -> StringUtils.isBlank(af.property) && StringUtils.isNotBlank(af.hqlAlias));
    if (hasHqlOnlyAux && !entityIds.isEmpty()) {
      SelectorAuxResolver.resolveAuxFieldsViaHql(items, entityIds, rawHql, fromIdx, alias, meta);
    }

    return SelectorResponseSupport.buildSelectorResponse(items, columns, totalCount, limit, offset);
  }

  /**
   * The row-paged flow every custom-HQL selector had before ETP-5703: {@code COUNT(<alias>)} and the
   * data query paged by row. The returned page carries no record ids ({@code ids == null}).
   */
  private static RecordPage fetchRowPage(String alias, RecordQuery query, int limit, int offset) {
    RecordPage page = new RecordPage();
    String countHql = "SELECT COUNT(" + alias + ")" + query.fromHql;
    org.hibernate.query.Query<Long> countQuery = OBDal.getInstance()
        .getSession().createQuery(countHql, Long.class);
    bindCustomHqlParams(countQuery, query.params, query.searchParam);
    Long countResult = countQuery.uniqueResult();
    page.totalCount = (countResult != null) ? countResult.intValue() : 0;
    String dataHql = query.selectPart + query.fromHql + ORDER_BY + query.displayOrder;
    org.hibernate.query.Query<?> dataQuery = OBDal.getInstance().getSession().createQuery(dataHql);
    bindCustomHqlParams(dataQuery, query.params, query.searchParam);
    dataQuery.setMaxResults(limit);
    dataQuery.setFirstResult(offset);
    page.ids = null;
    page.rows = new ArrayList<>(dataQuery.list());
    return page;
  }

  /** A query row as an {@code Object[]} (a single-column projection comes back unwrapped). */
  private static Object[] asRow(Object rawRow) {
    return (rawRow instanceof Object[]) ? (Object[]) rawRow : new Object[]{ rawRow };
  }

  /** The selector item of one row: id, label and grid fields. */
  private static JSONObject toItem(Object[] row, String recordId, Map<String, Integer> colIndexMap,
      SelectorMeta meta, Entity entityDef) throws Exception {
    JSONObject item = new JSONObject();
    item.put("id", recordId);
    item.put(FIELD_LABEL,
        SelectorRowMapper.extractDisplayLabel(row, colIndexMap, meta.displayProperty,
            entityDef, recordId));
    SelectorRowMapper.mapGridFieldsToItem(item, row, colIndexMap, meta.gridFields);
    return item;
  }

  /**
   * Record-paged items (ETP-5703): one item per record — the first of its rows, in query order —
   * emitted in page order, and only the page's records: a row of any other record (e.g. let in by
   * operator precedence in the custom where clause) is dropped. Fills {@code entityIds}.
   */
  private static JSONArray itemsByRecord(RecordPage page, Integer idColIdx,
      Map<String, Integer> colIndexMap, SelectorMeta meta, Entity entityDef,
      List<String> entityIds) throws Exception {
    Map<String, JSONObject> itemsById = new java.util.LinkedHashMap<>();
    for (Object rawRow : page.rows) {
      Object[] row = asRow(rawRow);
      String recordId = SelectorResponseSupport.extractRecordId(row, idColIdx);
      if (recordId == null || itemsById.containsKey(recordId)) {
        continue; // another row of a record already taken (first row wins, ordered)
      }
      itemsById.put(recordId, toItem(row, recordId, colIndexMap, meta, entityDef));
    }
    JSONArray items = new JSONArray();
    for (String pageId : page.ids) {
      JSONObject item = itemForPageId(itemsById, pageId);
      if (item != null && !entityIds.contains(item.getString("id"))) {
        items.put(item);
        entityIds.add(item.getString("id"));
      }
    }
    return items;
  }

  /** Row-paged items: one per row, in query order, as before ETP-5703. Fills {@code entityIds}. */
  private static JSONArray itemsByRow(List<Object> rows, Integer idColIdx,
      Map<String, Integer> colIndexMap, SelectorMeta meta, Entity entityDef,
      List<String> entityIds) throws Exception {
    JSONArray items = new JSONArray();
    for (Object rawRow : rows) {
      Object[] row = asRow(rawRow);
      String recordId = SelectorResponseSupport.extractRecordId(row, idColIdx);
      JSONObject item = toItem(row, recordId, colIndexMap, meta, entityDef);
      items.put(item);
      entityIds.add(item.optString("id", null));
    }
    return items;
  }

  private static final String PARAM_PAGE_IDS = "sfPageRecordIds";

  /**
   * The parts of a record-paged custom-HQL query (ETP-5703), grouped so the helpers below take one
   * argument instead of seven.
   */
  private static final class RecordQuery {
    final String selectPart;
    final String fromHql;
    final String fromOnwards;
    final String idExpr;
    final String displayOrder;
    final Map<String, Object> params;
    final String searchParam;

    RecordQuery(String selectPart, String fromHql, String fromOnwards, String idExpr,
        String displayOrder, Map<String, Object> params, String searchParam) {
      this.selectPart = selectPart;
      this.fromHql = fromHql;
      this.fromOnwards = fromOnwards;
      this.idExpr = idExpr;
      this.displayOrder = displayOrder;
      this.params = params;
      this.searchParam = searchParam;
    }
  }

  /** One record-paged result: the distinct count, the page's record ids and their rows. */
  private static final class RecordPage {
    int totalCount;
    List<String> ids = new ArrayList<>();
    List<Object> rows = new ArrayList<>();
  }

  /**
   * The record-paged queries (ETP-5703): {@code COUNT(DISTINCT <id>)}, the page of ids, the rows of
   * those ids. Any failure propagates to the caller.
   */
  private static RecordPage fetchRecordPage(RecordQuery query, int limit, int offset) {
    RecordPage page = new RecordPage();
    org.hibernate.query.Query<Long> countQuery = OBDal.getInstance().getSession()
        .createQuery("SELECT COUNT(DISTINCT " + query.idExpr + ")" + query.fromHql, Long.class);
    bindCustomHqlParams(countQuery, query.params, query.searchParam);
    Long count = countQuery.uniqueResult();
    page.totalCount = count != null ? count.intValue() : 0;
    List<Object> pageIdValues = pageRecordIds(query, limit, offset);
    for (Object value : pageIdValues) {
      page.ids.add(idValueAsString(value));
    }
    if (!pageIdValues.isEmpty()) {
      page.rows = rowsOfRecords(query, pageIdValues);
    }
    return page;
  }

  /**
   * Whether the selector's display property is a plain (non-association) property of its entity,
   * resolved structurally through the runtime model (ETP-5703): only then is
   * {@code MIN(<alias>.<display>)} — the page-of-ids ordering — a valid aggregate. No query runs.
   */
  static boolean displayIsPrimitive(SelectorMeta meta) {
    if (meta == null || StringUtils.isBlank(meta.displayProperty)
        || meta.displayProperty.contains(".")) {
      return false;
    }
    try {
      Entity entity = ModelProvider.getInstance().getEntity(meta.entityName);
      org.openbravo.base.model.Property property =
          entity != null ? entity.getProperty(meta.displayProperty, false) : null;
      return property != null && property.isPrimitive();
    } catch (RuntimeException e) {
      log.debug("[ETP-5703] Display property {} of {} not resolvable; using row paging: {}",
          meta.displayProperty, meta.entityName, e.getMessage());
      return false;
    }
  }

  /** The FROM root alias of a custom HQL ({@code from PricingProductPrice pp} → {@code pp}). */
  private static final Pattern FROM_ROOT_ALIAS = Pattern.compile(
      "^\\s*FROM\\s+[a-z_][\\w.]*\\s+(?:AS\\s+)?([a-z_]\\w*)", Pattern.CASE_INSENSITIVE);

  private static final java.util.Set<String> HQL_KEYWORDS = new java.util.HashSet<>(
      java.util.Arrays.asList("where", "join", "left", "inner", "right", "full", "cross", "order",
          "group", "having", "fetch", "outer"));

  private static void bindCustomHqlParams(org.hibernate.query.Query<?> query,
      Map<String, Object> params, String searchParam) {
    NeoSelectorExecutionHelper.bindNamedParameters(query, params);
    if (searchParam != null) {
      query.setParameter(PARAM_SEARCH, searchParam);
    }
  }

  /**
   * One page of distinct record ids (ETP-5703): {@code GROUP BY <idExpr>}, ordered by the first
   * display value of each record and then the id, so the order is total and stable across pages.
   */
  @SuppressWarnings("unchecked")
  private static List<Object> pageRecordIds(RecordQuery query, int limit, int offset) {
    String pageHql = "SELECT " + query.idExpr + query.fromHql + " GROUP BY " + query.idExpr
        + " ORDER BY MIN(" + query.displayOrder + "), " + query.idExpr;
    org.hibernate.query.Query<?> pageQuery = OBDal.getInstance().getSession().createQuery(pageHql);
    bindCustomHqlParams(pageQuery, query.params, query.searchParam);
    pageQuery.setMaxResults(limit);
    pageQuery.setFirstResult(offset);
    // The same id twice cannot come out of a GROUP BY; kept unique anyway, defensively.
    return new ArrayList<>(new java.util.LinkedHashSet<>(pageQuery.list()));
  }

  /**
   * Every row of the given records, ordered so the row kept per record is deterministic: display
   * value, record id, then the FROM root's id when it can be read off the HQL.
   */
  @SuppressWarnings("unchecked")
  private static List<Object> rowsOfRecords(RecordQuery query, List<Object> pageIdValues) {
    boolean hasWhere = Pattern.compile("\\sWHERE\\s", Pattern.CASE_INSENSITIVE)
        .matcher(query.fromHql).find();
    StringBuilder dataHql = new StringBuilder(query.selectPart).append(query.fromHql)
        .append(hasWhere ? " AND " : " WHERE ").append(query.idExpr).append(" IN (:")
        .append(PARAM_PAGE_IDS).append(")")
        .append(ORDER_BY).append(query.displayOrder).append(", ").append(query.idExpr);
    String rootAlias = fromRootAlias(query.fromOnwards);
    if (rootAlias != null) {
      dataHql.append(", ").append(rootAlias).append(".id");
    }
    org.hibernate.query.Query<?> dataQuery = OBDal.getInstance().getSession()
        .createQuery(dataHql.toString());
    bindCustomHqlParams(dataQuery, query.params, query.searchParam);
    dataQuery.setParameterList(PARAM_PAGE_IDS, pageIdValues);
    return new ArrayList<>(dataQuery.list());
  }

  /** The alias of the FROM root entity, or {@code null} when it cannot be read reliably. */
  static String fromRootAlias(String fromOnwards) {
    Matcher m = FROM_ROOT_ALIAS.matcher(fromOnwards);
    if (!m.find()) {
      return null;
    }
    String candidate = m.group(1);
    return HQL_KEYWORDS.contains(candidate.toLowerCase()) ? null : candidate;
  }

  private static String idValueAsString(Object value) {
    return value instanceof BaseOBObject ? ((BaseOBObject) value).getId().toString()
        : String.valueOf(value);
  }

  /**
   * The item of the page id: by equal id, or — for a 64-char composite view id, which
   * {@code extractRecordId} normalizes to its second half — by that half.
   */
  private static JSONObject itemForPageId(Map<String, JSONObject> itemsById, String pageId) {
    JSONObject item = itemsById.get(pageId);
    if (item == null && pageId != null && pageId.length() == 64) {
      item = itemsById.get(pageId.substring(32));
    }
    return item;
  }

  /**
   * Whether {@code expr} is a plain HQL path to a record id ({@code e.id}, {@code e.product.id}),
   * safe inside COUNT(DISTINCT): dot-separated ASCII identifiers ({@code [A-Za-z_][A-Za-z0-9_]*}),
   * at least two of them, the last being exactly {@code id} — only then is it the identity of a
   * record. Hand-written (no regex) so it cannot backtrack.
   */
  static boolean isPlainIdPath(String expr) {
    if (expr == null) {
      return false;
    }
    String[] segments = expr.split("\\.", -1);
    if (segments.length < 2 || !"id".equals(segments[segments.length - 1])) {
      return false;
    }
    for (String segment : segments) {
      if (!isAsciiIdentifier(segment)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAsciiIdentifier(String segment) {
    if (segment.isEmpty()) {
      return false;
    }
    for (int i = 0; i < segment.length(); i++) {
      char c = segment.charAt(i);
      boolean letter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
      if (!letter && (i == 0 || c < '0' || c > '9')) {
        return false;
      }
    }
    return true;
  }

  /**
   * The record-id expression of the projection when it is a plain property path ending in
   * {@code .id}, else {@code null} (ETP-5703): only then is the selector counted and paged by
   * record.
   */
  static String plainIdExpression(String[] selectExprs, Integer idColIdx) {
    if (idColIdx != null && idColIdx >= 0 && idColIdx < selectExprs.length) {
      String expr = stripColumnAlias(selectExprs[idColIdx]);
      if (isPlainIdPath(expr)) {
        return expr;
      }
    }
    return null;
  }

  /** {@code "e.id as id"} → {@code "e.id"}; an expression without an alias is returned trimmed. */
  private static String stripColumnAlias(String selectExpr) {
    String expr = selectExpr.trim();
    int asIdx = expr.toLowerCase().lastIndexOf(" as ");
    return asIdx >= 0 ? expr.substring(0, asIdx).trim() : expr;
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
