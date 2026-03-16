package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.ReferencedTable;
import org.openbravo.userinterface.selector.Selector;
import org.openbravo.userinterface.selector.SelectorField;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFField;

/**
 * Generic dynamic selector service for FK fields.
 *
 * Resolves AD_Reference metadata to serve dropdown/lookup values
 * for Table (18), TableDir (19), Search (30), and OBUISEL_Selector
 * reference types.
 *
 * Only serves selectors for fields that are included (IsIncluded = Y)
 * in the ETGO_SF_Field configuration.
 */
public class NeoSelectorService {

  private static final Logger log = LogManager.getLogger(NeoSelectorService.class);

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  // AD_Reference base IDs for FK types
  private static final String REF_TABLE = "18";
  private static final String REF_TABLEDIR = "19";
  private static final String REF_SEARCH = "30";

  private NeoSelectorService() {
  }

  /**
   * List all available selectors for an entity.
   * Only returns fields that are included and have a FK reference type.
   */
  @SuppressWarnings("unchecked")
  public static NeoResponse listSelectors(String specId, String entityName) {
    try {
      // Find the entity record
      SFEntity entity = findEntity(specId, entityName);
      if (entity == null) {
        return NeoResponse.error(404, "Entity not found: " + entityName);
      }

      // Find all included fields for this entity
      OBCriteria<SFField> fieldCrit = OBDal.getInstance().createCriteria(SFField.class);
      fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entity.getId()));
      fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
      fieldCrit.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true));
      fieldCrit.addOrderBy(SFField.PROPERTY_SEQNO, true);
      List<SFField> fields = fieldCrit.list();

      JSONArray selectors = new JSONArray();
      for (SFField field : fields) {
        Column column = field.getADColumn();
        if (column == null) {
          continue;
        }

        String refId = getBaseReferenceId(column);
        // Check for OBUISEL or classic FK reference
        boolean isObuisel = hasObuiselSelector(column);
        if (!isObuisel && !isFkReference(refId)) {
          continue;
        }

        SelectorMeta meta = resolveTarget(column, refId);
        if (meta == null) {
          continue;
        }

        JSONObject item = new JSONObject();
        item.put("columnName", column.getDBColumnName());
        if (meta.isRich) {
          item.put("referenceType", "OBUISEL");
          item.put("type", "rich");
        } else {
          item.put("referenceType", refId.equals(REF_TABLE) ? "Table"
              : refId.equals(REF_TABLEDIR) ? "TableDir" : "Search");
          item.put("type", "simple");
        }
        item.put("targetEntity", meta.entityName);
        item.put("displayProperty", meta.displayProperty);

        // Include selectorParams from validation rule
        org.openbravo.model.ad.domain.Validation valRule = column.getValidation();
        if (valRule != null && StringUtils.isNotBlank(valRule.getValidationCode())) {
          JSONArray params = new JSONArray();
          Matcher m = VALIDATION_PARAM.matcher(valRule.getValidationCode());
          java.util.Set<String> seen = new java.util.HashSet<>();
          while (m.find()) {
            String param = m.group(1);
            if (seen.add(param)) {
              params.put(param);
            }
          }
          if (params.length() > 0) {
            item.put("selectorParams", params);
          }
        }

        selectors.put(item);
      }

      JSONObject result = new JSONObject();
      result.put("selectors", selectors);
      result.put("count", selectors.length());
      return NeoResponse.ok(result);

    } catch (Exception e) {
      log.error("Error listing selectors for {}", entityName, e);
      return NeoResponse.error(500, e.getMessage());
    }
  }

  /**
   * Query selector values for a specific FK field.
   *
   * @param specId     the ETGO_SF_Spec ID
   * @param entityName the entity name within the spec
   * @param columnName the DB column name (e.g., C_BPartner_ID)
   * @param search     optional search text (filters on display property)
   * @param limit      page size (default 20, max 100)
   * @param offset     page offset (default 0)
   */
  @SuppressWarnings("unchecked")
  public static NeoResponse querySelector(String specId, String entityName,
      String columnName, String search, int limit, int offset,
      Map<String, String> contextParams) {
    try {
      if (limit <= 0) {
        limit = DEFAULT_LIMIT;
      }
      if (limit > MAX_LIMIT) {
        limit = MAX_LIMIT;
      }
      if (offset < 0) {
        offset = 0;
      }

      // Find the entity
      SFEntity entity = findEntity(specId, entityName);
      if (entity == null) {
        return NeoResponse.error(404, "Entity not found: " + entityName);
      }

      // Find the specific field by column name
      SFField sfField = findFieldByColumnName(entity.getId(), columnName);
      if (sfField == null) {
        return NeoResponse.error(404,
            "Field not found or not included: " + columnName);
      }

      Column column = sfField.getADColumn();
      if (column == null) {
        return NeoResponse.error(500,
            "Could not resolve AD_Column for field: " + columnName);
      }

      String refId = getBaseReferenceId(column);
      boolean isObuisel = hasObuiselSelector(column);
      if (!isObuisel && !isFkReference(refId)) {
        return NeoResponse.error(400,
            "Field is not a FK reference: " + columnName);
      }

      SelectorMeta meta = resolveTarget(column, refId);
      if (meta == null) {
        return NeoResponse.error(500,
            "Could not resolve target for: " + columnName);
      }

      // Resolve validation rule filter from context params
      String validationFilter = resolveValidationFilter(column, meta.entityName, contextParams);

      // Build and execute query
      if (meta.isRich) {
        return executeRichQuery(meta, search, limit, offset, validationFilter);
      }
      return executeQuery(meta, search, limit, offset, validationFilter);

    } catch (Exception e) {
      log.error("Error querying selector {}/{}", entityName, columnName, e);
      return NeoResponse.error(500, e.getMessage());
    }
  }

  /**
   * Execute the paginated query against the target entity (simple selectors).
   */
  private static NeoResponse executeQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter) throws Exception {

    StringBuilder hql = new StringBuilder();

    // Apply where clause from AD_Ref_Table if present
    if (StringUtils.isNotBlank(meta.whereClause)) {
      hql.append(meta.whereClause);
    }

    // Apply validation rule filter (resolved from context params)
    if (StringUtils.isNotBlank(validationFilter)) {
      if (hql.length() > 0) {
        hql.append(" AND ");
      }
      hql.append(validationFilter);
    }

    // Search filter on display property
    if (StringUtils.isNotBlank(search)) {
      if (hql.length() > 0) {
        hql.append(" AND ");
      }
      hql.append("lower(e.").append(meta.displayProperty)
          .append(") LIKE :search");
    }

    // Prefix with alias "as e" so OBQuery registers the entity alias
    String whereStr = hql.length() > 0 ? "as e where " + hql.toString() : "as e";

    // Count query
    OBQuery<BaseOBObject> countQuery = OBDal.getInstance()
        .createQuery(meta.entityName, whereStr);
    if (StringUtils.isNotBlank(search)) {
      countQuery.setNamedParameter("search",
          "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.count();

    // Data query with ordering and pagination
    String orderBy = " ORDER BY e." + meta.displayProperty;
    String dataWhere = whereStr + orderBy;

    OBQuery<BaseOBObject> dataQuery = OBDal.getInstance()
        .createQuery(meta.entityName, dataWhere);
    if (StringUtils.isNotBlank(search)) {
      dataQuery.setNamedParameter("search",
          "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResult(limit);
    dataQuery.setFirstResult(offset);

    // Build results
    JSONArray items = new JSONArray();
    for (BaseOBObject bob : dataQuery.list()) {
      JSONObject item = new JSONObject();
      item.put("id", bob.getId());
      item.put("label", bob.getIdentifier());
      items.put(item);
    }

    JSONObject result = new JSONObject();
    result.put("items", items);
    result.put("columns", new JSONArray());
    result.put("totalCount", totalCount);
    result.put("limit", limit);
    result.put("offset", offset);
    result.put("hasMore", (offset + limit) < totalCount);
    return NeoResponse.ok(result);
  }

  /**
   * Execute a rich (OBUISEL) selector query with multi-column response.
   */
  private static NeoResponse executeRichQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter) throws Exception {

    if (meta.isCustomQuery && StringUtils.isNotBlank(meta.customHql)) {
      return executeCustomHqlQuery(meta, search, limit, offset, validationFilter);
    }
    // Custom query flag set but no HQL defined: fall through to standard query

    String alias = "e";
    StringBuilder hql = new StringBuilder();

    // Apply where clause from OBUISEL_Selector with @param@ substitution
    if (StringUtils.isNotBlank(meta.whereClause)) {
      String resolved = resolveObuiselParams(meta.whereClause);
      hql.append(resolved);
    }

    // Apply validation rule filter (resolved from context params)
    if (StringUtils.isNotBlank(validationFilter)) {
      if (hql.length() > 0) {
        hql.append(" AND ");
      }
      hql.append(validationFilter);
    }

    // Search filter: OR across all searchable properties
    if (StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty()) {
      if (hql.length() > 0) {
        hql.append(" AND ");
      }
      hql.append("(");
      for (int i = 0; i < meta.searchableProperties.size(); i++) {
        if (i > 0) {
          hql.append(" OR ");
        }
        String prop = meta.searchableProperties.get(i);
        hql.append("lower(COALESCE(cast(").append(alias).append(".")
            .append(prop).append(" as string), '')) LIKE :search");
      }
      hql.append(")");
    }

    // Prefix with alias "as e" so OBQuery registers the entity alias
    String whereStr = hql.length() > 0
        ? "as " + alias + " where " + hql.toString()
        : "as " + alias;

    // Count query
    OBQuery<BaseOBObject> countQuery = OBDal.getInstance()
        .createQuery(meta.entityName, whereStr);
    if (StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty()) {
      countQuery.setNamedParameter("search",
          "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.count();

    // Data query with ordering and pagination
    String orderBy = " ORDER BY " + alias + "." + meta.displayProperty;
    String dataWhere = whereStr + orderBy;

    OBQuery<BaseOBObject> dataQuery = OBDal.getInstance()
        .createQuery(meta.entityName, dataWhere);
    if (StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty()) {
      dataQuery.setNamedParameter("search",
          "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResult(limit);
    dataQuery.setFirstResult(offset);

    // Build column metadata
    JSONArray columns = new JSONArray();
    for (RichFieldMeta fieldMeta : meta.gridFields) {
      JSONObject col = new JSONObject();
      col.put("name", fieldMeta.propertyKey);
      col.put("label", fieldMeta.label);
      col.put("sortNo", fieldMeta.sortNo);
      columns.put(col);
    }

    // Build results with all grid fields
    Entity entityDef = ModelProvider.getInstance()
        .getEntity(meta.entityName);
    JSONArray items = new JSONArray();
    for (BaseOBObject bob : dataQuery.list()) {
      JSONObject item = new JSONObject();
      item.put("id", bob.getId());
      item.put("label", bob.getIdentifier());

      // Add all grid fields
      for (RichFieldMeta fieldMeta : meta.gridFields) {
        Object value = resolvePropertyValue(bob, fieldMeta.property, entityDef);
        item.put(fieldMeta.propertyKey, value != null ? value : JSONObject.NULL);
      }
      items.put(item);
    }

    JSONObject result = new JSONObject();
    result.put("items", items);
    result.put("columns", columns);
    result.put("totalCount", totalCount);
    result.put("limit", limit);
    result.put("offset", offset);
    result.put("hasMore", (offset + limit) < totalCount);
    return NeoResponse.ok(result);
  }

  /**
   * Execute a custom HQL selector query using the full HQL from the Selector definition.
   * Custom HQL selectors define their own FROM clause (e.g., "FROM Product AS p WHERE ...").
   * We append additional filters and use Session.createQuery for the full HQL.
   */
  @SuppressWarnings("unchecked")
  private static NeoResponse executeCustomHqlQuery(SelectorMeta meta,
      String search, int limit, int offset, String validationFilter) throws Exception {

    String alias = meta.entityAlias;

    // Resolve Etendo classic UI placeholder before any processing
    String rawHql = meta.customHql.replace("@additional_filters@", "1=1");

    // Extract the FROM clause onward from the custom HQL (which includes its own SELECT).
    // FROM may be preceded by space, newline, or tab — use regex to find it.
    java.util.regex.Matcher fromMatcher = Pattern.compile("\\sFROM\\s",
        Pattern.CASE_INSENSITIVE).matcher(rawHql);
    if (!fromMatcher.find()) {
      throw new IllegalArgumentException(
          "Custom HQL does not contain a FROM clause: " + rawHql);
    }
    int fromIdx = fromMatcher.start();
    // We only need the FROM-onwards portion; the data query uses "SELECT alias"
    // to return BaseOBObject entities, not the custom SELECT columns.
    String fromOnwards = rawHql.substring(fromIdx);

    StringBuilder baseHql = new StringBuilder(fromOnwards);

    // Check if the FROM-onwards portion already has WHERE (may be preceded by newline)
    boolean hasWhere = Pattern.compile("\\sWHERE\\s", Pattern.CASE_INSENSITIVE)
        .matcher(fromOnwards).find();

    // Apply where clause from OBUISEL_Selector config with @param@ substitution
    if (StringUtils.isNotBlank(meta.whereClause)) {
      String resolved = resolveObuiselParams(meta.whereClause);
      baseHql.append(hasWhere ? " AND " : " WHERE ");
      baseHql.append(resolved);
      hasWhere = true;
    }

    // Apply validation rule filter (resolved from context params)
    if (StringUtils.isNotBlank(validationFilter)) {
      baseHql.append(hasWhere ? " AND " : " WHERE ");
      baseHql.append(validationFilter);
      hasWhere = true;
    }

    // Apply readable organizations filter
    OBContext ctx = OBContext.getOBContext();
    String[] readableOrgs = ctx.getReadableOrganizations();
    if (readableOrgs != null && readableOrgs.length > 0) {
      baseHql.append(hasWhere ? " AND " : " WHERE ");
      baseHql.append(alias).append(".organization.id IN (");
      for (int i = 0; i < readableOrgs.length; i++) {
        if (i > 0) {
          baseHql.append(", ");
        }
        baseHql.append("'").append(readableOrgs[i]).append("'");
      }
      baseHql.append(")");
      hasWhere = true;
    }

    // Search filter across searchable properties
    boolean hasSearch = StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty();
    if (hasSearch) {
      baseHql.append(hasWhere ? " AND " : " WHERE ");
      baseHql.append("(");
      for (int i = 0; i < meta.searchableProperties.size(); i++) {
        if (i > 0) {
          baseHql.append(" OR ");
        }
        String prop = meta.searchableProperties.get(i);
        baseHql.append("lower(COALESCE(cast(").append(alias).append(".")
            .append(prop).append(" as string), '')) LIKE :search");
      }
      baseHql.append(")");
    }

    String fromClause = baseHql.toString();

    // Count query — use the FROM-onwards portion (no duplicate SELECT)
    String countHql = "SELECT COUNT(" + alias + ")" + fromClause;
    org.hibernate.query.Query<Long> countQuery = OBDal.getInstance()
        .getSession().createQuery(countHql, Long.class);
    if (hasSearch) {
      countQuery.setParameter("search", "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.uniqueResult().intValue();

    // Data query with ORDER BY and pagination — use entity alias, not custom columns
    String dataHql = "SELECT " + alias + fromClause
        + " ORDER BY " + alias + "." + meta.displayProperty;
    org.hibernate.query.Query<BaseOBObject> dataQuery = OBDal.getInstance()
        .getSession().createQuery(dataHql, BaseOBObject.class);
    if (hasSearch) {
      dataQuery.setParameter("search", "%" + search.toLowerCase() + "%");
    }
    dataQuery.setMaxResults(limit);
    dataQuery.setFirstResult(offset);

    // Build column metadata
    JSONArray columns = new JSONArray();
    for (RichFieldMeta fieldMeta : meta.gridFields) {
      JSONObject col = new JSONObject();
      col.put("name", fieldMeta.propertyKey);
      col.put("label", fieldMeta.label);
      col.put("sortNo", fieldMeta.sortNo);
      columns.put(col);
    }

    // Build results with all grid fields
    Entity entityDef = ModelProvider.getInstance()
        .getEntity(meta.entityName);
    JSONArray items = new JSONArray();
    for (BaseOBObject bob : dataQuery.list()) {
      JSONObject item = new JSONObject();
      item.put("id", bob.getId());
      item.put("label", bob.getIdentifier());

      for (RichFieldMeta fieldMeta : meta.gridFields) {
        Object value = resolvePropertyValue(bob, fieldMeta.property, entityDef);
        item.put(fieldMeta.propertyKey, value != null ? value : JSONObject.NULL);
      }
      items.put(item);
    }

    JSONObject result = new JSONObject();
    result.put("items", items);
    result.put("columns", columns);
    result.put("totalCount", totalCount);
    result.put("limit", limit);
    result.put("offset", offset);
    result.put("hasMore", (offset + limit) < totalCount);
    return NeoResponse.ok(result);
  }

  /**
   * Resolve a potentially dotted property path on a BaseOBObject.
   * E.g., "product.name" navigates bob.get("product").get("name").
   */
  private static Object resolvePropertyValue(BaseOBObject bob,
      String propertyPath, Entity entityDef) {
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
      // If the final value is a BaseOBObject, return its identifier
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

  /** Pattern matching @param@ placeholders in OBUISEL clauses. */
  private static final Pattern PARAM_PATTERN = Pattern.compile("@([A-Za-z_]+)@");

  /**
   * Replace @param@ placeholders in OBUISEL where/HQL clauses with values from OBContext.
   * Known context params (AD_Org_ID, AD_Client_ID, AD_User_ID, AD_Role_ID) are resolved
   * case-insensitively. Unknown params (e.g. @inpmWarehouseId@) that depend on form context
   * are replaced with NULL since NEO selectors don't have that context yet.
   */
  private static String resolveObuiselParams(String whereClause) {
    OBContext ctx = OBContext.getOBContext();
    java.util.Map<String, String> knownParams = new java.util.HashMap<>();
    knownParams.put("ad_org_id", "'" + ctx.getCurrentOrganization().getId() + "'");
    knownParams.put("ad_client_id", "'" + ctx.getCurrentClient().getId() + "'");
    knownParams.put("ad_user_id", "'" + ctx.getUser().getId() + "'");
    knownParams.put("ad_role_id", "'" + ctx.getRole().getId() + "'");
    // Common aliases
    knownParams.put("client", "'" + ctx.getCurrentClient().getId() + "'");

    java.util.regex.Matcher m = PARAM_PATTERN.matcher(whereClause);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String paramName = m.group(1);
      String resolved = knownParams.get(paramName.toLowerCase());
      if (resolved != null) {
        m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(resolved));
      } else {
        // Unknown context param — replace with NULL so the condition
        // evaluates safely rather than crashing with '@' parse error
        log.debug("Unresolved OBUISEL param @{}@ replaced with NULL", paramName);
        m.appendReplacement(sb, "NULL");
      }
    }
    m.appendTail(sb);
    return sb.toString();
  }

  // ---- Resolution helpers ----

  private static SFEntity findEntity(String specId, String entityName) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_NAME, entityName));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    criteria.setMaxResults(1);
    List<SFEntity> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  private static SFField findFieldByColumnName(String entityId,
      String columnName) {
    OBCriteria<SFField> criteria = OBDal.getInstance().createCriteria(SFField.class);
    criteria.add(Restrictions.eq(SFField.PROPERTY_ETGOSFENTITY + ".id", entityId));
    criteria.add(Restrictions.eq(SFField.PROPERTY_ISACTIVE, true));
    criteria.add(Restrictions.eq(SFField.PROPERTY_ISINCLUDED, true));
    criteria.createAlias(SFField.PROPERTY_ADCOLUMN, "col");
    criteria.add(Restrictions.eq("col." + Column.PROPERTY_DBCOLUMNNAME, columnName));
    criteria.setMaxResults(1);
    List<SFField> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }


  /**
   * Get the base reference ID (18, 19, or 30) checking parent references.
   */
  private static String getBaseReferenceId(Column column) {
    String refId = column.getReference().getId();

    // Check if this is 18, 19, or 30 directly
    if (REF_TABLE.equals(refId) || REF_TABLEDIR.equals(refId)
        || REF_SEARCH.equals(refId)) {
      return refId;
    }

    // Check parent reference
    org.openbravo.model.ad.domain.Reference ref = column.getReference();
    if (ref.getParentReference() != null) {
      String parentId = ref.getParentReference().getId();
      if (REF_TABLE.equals(parentId) || REF_TABLEDIR.equals(parentId)
          || REF_SEARCH.equals(parentId)) {
        return parentId;
      }
    }

    return refId;
  }

  private static boolean isFkReference(String refId) {
    return REF_TABLE.equals(refId) || REF_TABLEDIR.equals(refId)
        || REF_SEARCH.equals(refId);
  }

  /**
   * Check if a column has an associated OBUISEL_Selector.
   * Checks both referenceSearchKey and the column's own reference.
   */
  private static boolean hasObuiselSelector(Column column) {
    return findObuiselSelector(column) != null;
  }

  /**
   * Find the OBUISEL_Selector for a column, if any.
   * First checks referenceSearchKey, then falls back to the column's reference.
   */
  private static Selector findObuiselSelector(Column column) {
    // First: check via referenceSearchKey (AD_Reference_Value_ID)
    org.openbravo.model.ad.domain.Reference refSearchKey =
        column.getReferenceSearchKey();
    if (refSearchKey != null) {
      Selector sel = findSelectorByReference(refSearchKey.getId());
      if (sel != null) {
        return sel;
      }
    }

    // Fallback: check the column's own reference
    String refId = column.getReference().getId();
    if (!REF_TABLE.equals(refId) && !REF_TABLEDIR.equals(refId)
        && !REF_SEARCH.equals(refId)) {
      // Only check if it's not a base type (base types don't have OBUISEL)
      Selector sel = findSelectorByReference(refId);
      if (sel != null) {
        return sel;
      }
    }

    return null;
  }

  /**
   * Query OBUISEL_Selector by AD_Reference_ID.
   */
  private static Selector findSelectorByReference(String referenceId) {
    try {
      OBCriteria<Selector> crit = OBDal.getInstance()
          .createCriteria(Selector.class);
      crit.add(Restrictions.eq(Selector.PROPERTY_REFERENCE + ".id",
          referenceId));
      crit.add(Restrictions.eq(Selector.PROPERTY_ACTIVE, true));
      crit.setMaxResults(1);
      return (Selector) crit.uniqueResult();
    } catch (Exception e) {
      log.debug("Error looking up OBUISEL_Selector for ref {}: {}",
          referenceId, e.getMessage());
      return null;
    }
  }

  /**
   * Resolve the target entity, display property, and optional where clause.
   * Priority: OBUISEL_Selector first, then TableDir / AD_Ref_Table.
   */
  private static SelectorMeta resolveTarget(Column column, String baseRefId) {
    // 1. Check OBUISEL_Selector first
    Selector obuisel = findObuiselSelector(column);
    if (obuisel != null) {
      return resolveObuiselSelector(obuisel);
    }

    // 2. Fall back to classic resolution
    if (REF_TABLEDIR.equals(baseRefId)) {
      return resolveTableDir(column);
    } else {
      // Table (18) or Search (30): use AD_Ref_Table
      return resolveRefTable(column);
    }
  }

  /**
   * Resolve an OBUISEL_Selector into a SelectorMeta with rich field info.
   */
  private static SelectorMeta resolveObuiselSelector(Selector selector) {
    try {
      // Check for custom query and retrieve custom HQL if present
      boolean isCustom = Boolean.TRUE.equals(selector.isCustomQuery());
      String customHql = isCustom ? selector.getHQL() : null;
      String entityAlias = selector.getEntityAlias();
      if (StringUtils.isBlank(entityAlias)) {
        entityAlias = "e";
      }

      Table targetTable = selector.getTable();
      if (targetTable == null) {
        log.warn("OBUISEL_Selector {} has no target table",
            selector.getName());
        return null;
      }

      Entity targetEntity = ModelProvider.getInstance()
          .getEntityByTableName(targetTable.getDBTableName());
      if (targetEntity == null) {
        log.warn("No entity for OBUISEL table: {}",
            targetTable.getDBTableName());
        return null;
      }

      // Resolve display property from displayfield
      String displayProp;
      SelectorField displayField = selector.getDisplayfield();
      if (displayField != null && StringUtils.isNotBlank(displayField.getProperty())) {
        displayProp = displayField.getProperty();
      } else {
        displayProp = findIdentifierProperty(targetEntity);
      }

      // Resolve value property from valuefield
      String valueProp = "id";
      SelectorField valueField = selector.getValuefield();
      if (valueField != null && StringUtils.isNotBlank(valueField.getProperty())) {
        valueProp = valueField.getProperty();
      }

      // Get where clause
      String whereClause = selector.getHQLWhereClause();
      if (StringUtils.isBlank(whereClause)) {
        whereClause = null;
      }

      // Load selector fields
      List<SelectorField> selectorFields = selector.getOBUISELSelectorFieldList();

      List<RichFieldMeta> gridFields = new ArrayList<>();
      List<String> searchableProps = new ArrayList<>();

      for (SelectorField sf : selectorFields) {
        if (!Boolean.TRUE.equals(sf.isActive())) {
          continue;
        }
        String prop = sf.getProperty();
        if (StringUtils.isBlank(prop)) {
          continue;
        }

        // Grid columns
        if (Boolean.TRUE.equals(sf.isShowingrid())) {
          String propKey = getLastSegment(prop);
          Long sortNo = sf.getSortno();
          gridFields.add(new RichFieldMeta(
              propKey, sf.getName(), prop,
              sortNo != null ? sortNo : 0L));
        }

        // Searchable properties
        if (Boolean.TRUE.equals(sf.isSearchinsuggestionbox())) {
          searchableProps.add(prop);
        }
      }

      // Sort grid fields by sortNo
      gridFields.sort((a, b) -> Long.compare(a.sortNo, b.sortNo));

      return new SelectorMeta(
          targetEntity.getName(),
          displayProp,
          whereClause,
          true, // isRich
          isCustom,
          valueProp,
          gridFields,
          searchableProps,
          customHql,
          entityAlias
      );

    } catch (Exception e) {
      log.warn("Could not resolve OBUISEL_Selector {}: {}",
          selector.getName(), e.getMessage());
      return null;
    }
  }

  /**
   * Get the last segment of a dotted property path.
   * E.g., "product.name" -> "name", "id" -> "id".
   */
  private static String getLastSegment(String propertyPath) {
    int lastDot = propertyPath.lastIndexOf('.');
    if (lastDot >= 0 && lastDot < propertyPath.length() - 1) {
      return propertyPath.substring(lastDot + 1);
    }
    return propertyPath;
  }

  /**
   * TableDir convention: column name = {TableName}_ID.
   * Target table is derived from column name.
   */
  private static SelectorMeta resolveTableDir(Column column) {
    String colName = column.getDBColumnName();
    if (!colName.endsWith("_ID")) {
      log.warn("TableDir column doesn't end with _ID: {}", colName);
      return null;
    }

    String tableName = colName.substring(0, colName.length() - 3);

    try {
      Entity targetEntity = ModelProvider.getInstance()
          .getEntityByTableName(tableName);
      if (targetEntity == null) {
        log.warn("No entity found for table: {}", tableName);
        return null;
      }

      // Find the identifier property for display
      String displayProp = findIdentifierProperty(targetEntity);

      return new SelectorMeta(
          targetEntity.getName(),
          displayProp,
          null // no where clause for TableDir
      );
    } catch (Exception e) {
      log.warn("Could not resolve TableDir for {}: {}", colName, e.getMessage());
      return null;
    }
  }

  /**
   * Table/Search: use AD_Ref_Table to find target table and display column.
   */
  private static SelectorMeta resolveRefTable(Column column) {
    org.openbravo.model.ad.domain.Reference refValue = column.getReferenceSearchKey();
    if (refValue == null) {
      log.warn("Column {} has no AD_Reference_Value", column.getDBColumnName());
      return null;
    }

    try {
      // Query AD_Ref_Table for this reference
      OBCriteria<ReferencedTable> refTableCrit =
          OBDal.getInstance().createCriteria(ReferencedTable.class);
      refTableCrit.add(Restrictions.eq(
          ReferencedTable.PROPERTY_REFERENCE + ".id", refValue.getId()));
      refTableCrit.setMaxResults(1);

      ReferencedTable refTable =
          (ReferencedTable) refTableCrit.uniqueResult();

      if (refTable == null) {
        log.warn("No AD_Ref_Table found for reference: {}", refValue.getId());
        return null;
      }

      Table targetTable = refTable.getTable();
      Column displayCol = refTable.getDisplayedColumn();

      Entity targetEntity = ModelProvider.getInstance()
          .getEntityByTableName(targetTable.getDBTableName());
      if (targetEntity == null) {
        log.warn("No entity for table: {}", targetTable.getDBTableName());
        return null;
      }

      // Resolve display property from column
      String displayProp;
      if (displayCol != null) {
        Property prop = targetEntity.getPropertyByColumnName(
            displayCol.getDBColumnName());
        displayProp = prop != null ? prop.getName() : "name";
      } else {
        displayProp = findIdentifierProperty(targetEntity);
      }

      // Get optional where clause
      String whereClause = refTable.getHqlwhereclause();
      if (StringUtils.isBlank(whereClause)) {
        whereClause = null;
      }

      return new SelectorMeta(targetEntity.getName(), displayProp, whereClause);

    } catch (Exception e) {
      log.warn("Could not resolve ref table for {}: {}",
          column.getDBColumnName(), e.getMessage());
      return null;
    }
  }

  /**
   * Find the first identifier property of an entity.
   * Falls back to "name" or "id" if no identifier is found.
   */
  private static String findIdentifierProperty(Entity entity) {
    for (Property prop : entity.getIdentifierProperties()) {
      if (!prop.isPrimitive()) {
        continue;
      }
      return prop.getName();
    }
    // Fallback: try common property names
    if (entity.hasProperty("name")) {
      return "name";
    }
    if (entity.hasProperty("searchKey")) {
      return "searchKey";
    }
    return "id";
  }

  private static final Pattern VALIDATION_PARAM = Pattern.compile("@(\\w+)@");

  /**
   * Resolve the column's validation rule into an HQL filter using context params.
   *
   * Validation rules are SQL-style clauses like:
   *   C_BPartner_Location.C_BPartner_ID=@C_BPartner_ID@ AND C_BPartner_Location.IsShipTo='Y'
   *
   * This method:
   * 1. Replaces @Param@ placeholders with actual values from contextParams
   * 2. Converts TABLE.COLUMN references to DAL property paths (e.property)
   * 3. Returns null if no validation rule or required params are missing
   */
  private static String resolveValidationFilter(Column column, String targetEntityName,
      Map<String, String> contextParams) {
    org.openbravo.model.ad.domain.Validation valRule = column.getValidation();
    if (valRule == null || StringUtils.isBlank(valRule.getValidationCode())) {
      return null;
    }
    if (contextParams == null || contextParams.isEmpty()) {
      return null;
    }

    String code = valRule.getValidationCode();

    // Check that all required @Param@ have values in contextParams
    Matcher paramMatcher = VALIDATION_PARAM.matcher(code);
    boolean hasAllParams = true;
    while (paramMatcher.find()) {
      String paramName = paramMatcher.group(1);
      if (!contextParams.containsKey(paramName)) {
        hasAllParams = false;
        break;
      }
    }
    if (!hasAllParams) {
      return null;
    }

    // Replace @Param@ with sanitized values
    StringBuffer resolved = new StringBuffer();
    paramMatcher = VALIDATION_PARAM.matcher(code);
    while (paramMatcher.find()) {
      String paramName = paramMatcher.group(1);
      String value = contextParams.get(paramName).replace("'", "''");
      paramMatcher.appendReplacement(resolved, "'" + value + "'");
    }
    paramMatcher.appendTail(resolved);

    // Convert SQL TABLE.COLUMN references to HQL e.property paths
    String hqlFilter = convertSqlToHql(resolved.toString(), targetEntityName);
    return hqlFilter;
  }

  /**
   * Convert a SQL-style validation clause to HQL.
   * Replaces TABLE.COLUMN with e.dalProperty, handling FK columns (_ID → .id).
   *
   * Example: "C_BPartner_Location.C_BPartner_ID='abc'" → "e.businessPartner.id='abc'"
   */
  private static String convertSqlToHql(String sqlClause, String targetEntityName) {
    try {
      Entity targetEntity = ModelProvider.getInstance().getEntity(targetEntityName);
      if (targetEntity == null) {
        return sqlClause;
      }

      String tableName = targetEntity.getTableName();

      // Replace TABLE.COLUMN patterns with e.property
      Pattern tableColPattern = Pattern.compile(
          Pattern.quote(tableName) + "\\.(\\w+)", Pattern.CASE_INSENSITIVE);
      Matcher m = tableColPattern.matcher(sqlClause);

      StringBuffer result = new StringBuffer();
      while (m.find()) {
        String dbColName = m.group(1);
        Property prop = targetEntity.getPropertyByColumnName(dbColName);
        if (prop != null) {
          String replacement;
          if (!prop.isPrimitive() && prop.getTargetEntity() != null) {
            // FK property: TABLE.FK_ID → e.property.id
            replacement = "e." + prop.getName() + ".id";
          } else {
            replacement = "e." + prop.getName();
          }
          m.appendReplacement(result, replacement);
        } else {
          m.appendReplacement(result, "e." + dbColName);
        }
      }
      m.appendTail(result);
      return result.toString();
    } catch (Exception e) {
      log.warn("Could not convert SQL to HQL for entity {}: {}", targetEntityName, e.getMessage());
      return sqlClause;
    }
  }

  /**
   * Holds resolved target metadata for a selector.
   */
  private static class SelectorMeta {
    final String entityName;
    final String displayProperty;
    final String whereClause;
    final boolean isRich;
    final boolean isCustomQuery;
    final String valueProperty;
    final List<RichFieldMeta> gridFields;
    final List<String> searchableProperties;
    final String customHql;
    final String entityAlias;

    /** Constructor for simple selectors (TableDir, Table, Search). */
    SelectorMeta(String entityName, String displayProperty, String whereClause) {
      this(entityName, displayProperty, whereClause,
          false, false, "id",
          new ArrayList<>(), new ArrayList<>(),
          null, "e");
    }

    /** Constructor for rich (OBUISEL) selectors. */
    SelectorMeta(String entityName, String displayProperty, String whereClause,
        boolean isRich, boolean isCustomQuery, String valueProperty,
        List<RichFieldMeta> gridFields, List<String> searchableProperties,
        String customHql, String entityAlias) {
      this.entityName = entityName;
      this.displayProperty = displayProperty;
      this.whereClause = whereClause;
      this.isRich = isRich;
      this.isCustomQuery = isCustomQuery;
      this.valueProperty = valueProperty;
      this.gridFields = gridFields;
      this.searchableProperties = searchableProperties;
      this.customHql = customHql;
      this.entityAlias = entityAlias;
    }
  }

  /**
   * Metadata for a single field in a rich selector grid.
   */
  private static class RichFieldMeta {
    final String propertyKey;  // last segment of property path
    final String label;        // display name
    final String property;     // full DAL property path
    final long sortNo;

    RichFieldMeta(String propertyKey, String label, String property, long sortNo) {
      this.propertyKey = propertyKey;
      this.label = label;
      this.property = property;
      this.sortNo = sortNo;
    }
  }
}
