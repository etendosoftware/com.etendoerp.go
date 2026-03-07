package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.List;

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
      BaseOBObject entity = findEntity(specId, entityName);
      if (entity == null) {
        return NeoResponse.error(404, "Entity not found: " + entityName);
      }

      // Find all included fields for this entity
      OBCriteria<BaseOBObject> fieldCrit = OBDal.getInstance().createCriteria("ETGO_SF_Field");
      fieldCrit.add(Restrictions.eq("etgoSfEntity.id", entity.getId()));
      fieldCrit.add(Restrictions.eq("active", true));
      fieldCrit.add(Restrictions.eq("included", true));
      fieldCrit.addOrderBy("sequenceNumber", true);
      List<BaseOBObject> fields = fieldCrit.list();

      JSONArray selectors = new JSONArray();
      for (BaseOBObject field : fields) {
        Column column = resolveColumn(field);
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
      String columnName, String search, int limit, int offset) {
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
      BaseOBObject entity = findEntity(specId, entityName);
      if (entity == null) {
        return NeoResponse.error(404, "Entity not found: " + entityName);
      }

      // Find the specific field by column name
      BaseOBObject sfField = findFieldByColumnName(
          (String) entity.getId(), columnName);
      if (sfField == null) {
        return NeoResponse.error(404,
            "Field not found or not included: " + columnName);
      }

      Column column = resolveColumn(sfField);
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

      // Build and execute query
      if (meta.isRich) {
        return executeRichQuery(meta, search, limit, offset);
      }
      return executeQuery(meta, search, limit, offset);

    } catch (Exception e) {
      log.error("Error querying selector {}/{}", entityName, columnName, e);
      return NeoResponse.error(500, e.getMessage());
    }
  }

  /**
   * Execute the paginated query against the target entity (simple selectors).
   */
  private static NeoResponse executeQuery(SelectorMeta meta,
      String search, int limit, int offset) throws Exception {

    StringBuilder hql = new StringBuilder();

    // Apply where clause from AD_Ref_Table if present
    if (StringUtils.isNotBlank(meta.whereClause)) {
      hql.append(meta.whereClause);
    }

    // Search filter on display property
    if (StringUtils.isNotBlank(search)) {
      if (hql.length() > 0) {
        hql.append(" AND ");
      }
      hql.append("lower(e.").append(meta.displayProperty)
          .append(") LIKE :search");
    }

    String whereStr = hql.length() > 0 ? hql.toString() : null;

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
    String dataWhere = whereStr != null ? whereStr + orderBy : orderBy;

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
      String search, int limit, int offset) throws Exception {

    if (meta.isCustomQuery) {
      return NeoResponse.error(400,
          "Custom HQL selectors not supported yet");
    }

    StringBuilder hql = new StringBuilder();

    // Apply where clause from OBUISEL_Selector with @param@ substitution
    if (StringUtils.isNotBlank(meta.whereClause)) {
      String resolved = resolveObuiselParams(meta.whereClause);
      hql.append(resolved);
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
        hql.append("lower(COALESCE(cast(e.").append(prop)
            .append(" as string), '')) LIKE :search");
      }
      hql.append(")");
    }

    String whereStr = hql.length() > 0 ? hql.toString() : null;

    // Count query
    OBQuery<BaseOBObject> countQuery = OBDal.getInstance()
        .createQuery(meta.entityName, whereStr);
    if (StringUtils.isNotBlank(search) && !meta.searchableProperties.isEmpty()) {
      countQuery.setNamedParameter("search",
          "%" + search.toLowerCase() + "%");
    }
    int totalCount = countQuery.count();

    // Data query with ordering and pagination
    String orderBy = " ORDER BY e." + meta.displayProperty;
    String dataWhere = whereStr != null ? whereStr + orderBy : orderBy;

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

  /**
   * Replace @param@ placeholders in OBUISEL where clauses with
   * values from OBContext.
   */
  private static String resolveObuiselParams(String whereClause) {
    String result = whereClause;
    OBContext ctx = OBContext.getOBContext();
    result = result.replace("@AD_Org_ID@",
        "'" + ctx.getCurrentOrganization().getId() + "'");
    result = result.replace("@AD_Client_ID@",
        "'" + ctx.getCurrentClient().getId() + "'");
    result = result.replace("@AD_User_ID@",
        "'" + ctx.getUser().getId() + "'");
    result = result.replace("@AD_Role_ID@",
        "'" + ctx.getRole().getId() + "'");
    return result;
  }

  // ---- Resolution helpers ----

  @SuppressWarnings("unchecked")
  private static BaseOBObject findEntity(String specId, String entityName) {
    OBCriteria<BaseOBObject> criteria = OBDal.getInstance()
        .createCriteria("ETGO_SF_Entity");
    criteria.add(Restrictions.eq("etgoSfSpec.id", specId));
    criteria.add(Restrictions.eq("name", entityName));
    criteria.add(Restrictions.eq("active", true));
    criteria.add(Restrictions.eq("included", true));
    criteria.setMaxResults(1);
    List<BaseOBObject> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  @SuppressWarnings("unchecked")
  private static BaseOBObject findFieldByColumnName(String entityId,
      String columnName) {
    OBCriteria<BaseOBObject> criteria = OBDal.getInstance()
        .createCriteria("ETGO_SF_Field");
    criteria.add(Restrictions.eq("etgoSfEntity.id", entityId));
    criteria.add(Restrictions.eq("active", true));
    criteria.add(Restrictions.eq("included", true));
    criteria.createAlias("column", "col");
    criteria.add(Restrictions.eq("col.dBColumnName", columnName));
    criteria.setMaxResults(1);
    List<BaseOBObject> results = criteria.list();
    return results.isEmpty() ? null : results.get(0);
  }

  private static Column resolveColumn(BaseOBObject sfField) {
    try {
      Object colRef = sfField.get("column");
      if (colRef instanceof Column) {
        return (Column) colRef;
      }
      if (colRef instanceof BaseOBObject) {
        String colId = (String) ((BaseOBObject) colRef).getId();
        return OBDal.getInstance().get(Column.class, colId);
      }
    } catch (Exception e) {
      log.warn("Could not resolve column from field: {}", e.getMessage());
    }
    return null;
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
      // Check for custom query
      boolean isCustom = Boolean.TRUE.equals(selector.isCustomQuery());

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
          searchableProps
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

    /** Constructor for simple selectors (TableDir, Table, Search). */
    SelectorMeta(String entityName, String displayProperty, String whereClause) {
      this(entityName, displayProperty, whereClause,
          false, false, "id",
          new ArrayList<>(), new ArrayList<>());
    }

    /** Constructor for rich (OBUISEL) selectors. */
    SelectorMeta(String entityName, String displayProperty, String whereClause,
        boolean isRich, boolean isCustomQuery, String valueProperty,
        List<RichFieldMeta> gridFields, List<String> searchableProperties) {
      this.entityName = entityName;
      this.displayProperty = displayProperty;
      this.whereClause = whereClause;
      this.isRich = isRich;
      this.isCustomQuery = isCustomQuery;
      this.valueProperty = valueProperty;
      this.gridFields = gridFields;
      this.searchableProperties = searchableProperties;
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
