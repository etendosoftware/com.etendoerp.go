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
package com.etendoerp.go.schemaforge.selector.meta;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import com.etendoerp.go.schemaforge.NeoSelectorService;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.ReferencedTable;
import org.openbravo.userinterface.selector.Selector;
import org.openbravo.userinterface.selector.SelectorField;

/**
 * Resolves AD selector metadata into executable selector descriptors.
 */
public final class SelectorDescriptorResolver {

  private static final Logger log = LogManager.getLogger(SelectorDescriptorResolver.class);
  private static final java.util.regex.Pattern SAFE_HQL_PATH =
      java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.]*$");

  private SelectorDescriptorResolver() {
  }

  public static boolean hasObuiselSelector(Column column) {
    return findObuiselSelector(column) != null;
  }

  public static SelectorMeta resolveTarget(Column column, String baseRefId) {
    Selector obuisel = findObuiselSelector(column);
    if (obuisel != null) {
      return resolveObuiselSelector(obuisel);
    }

    if (NeoSelectorService.REF_TABLEDIR.equals(baseRefId)) {
      return resolveTableDir(column);
    }

    SelectorMeta meta = resolveRefTable(column);
    if (meta == null && column.getDBColumnName().endsWith("_ID")) {
      log.debug("No AD_Ref_Table for {}, trying TableDir fallback", column.getDBColumnName());
      return resolveTableDir(column);
    }
    return meta;
  }

  public static String resolveSearchableFragment(String property, String clauseLeftPart) {
    if (StringUtils.isNotBlank(property)) {
      return property;
    }
    if (StringUtils.isBlank(clauseLeftPart)) {
      return null;
    }
    String trimmed = clauseLeftPart.trim();
    if (SAFE_HQL_PATH.matcher(trimmed).matches()) {
      return trimmed;
    }
    log.debug("Skipping search on selector field with unsafe clause_left_part: {}", trimmed);
    return null;
  }

  public static String findIdentifierProperty(Entity entity) {
    for (Property prop : entity.getIdentifierProperties()) {
      if (!prop.isPrimitive()) {
        continue;
      }
      return prop.getName();
    }
    if (entity.hasProperty("name")) {
      return "name";
    }
    if (entity.hasProperty("searchKey")) {
      return "searchKey";
    }
    return "id";
  }

  private static Selector findObuiselSelector(Column column) {
    org.openbravo.model.ad.domain.Reference refSearchKey = column.getReferenceSearchKey();
    if (refSearchKey != null) {
      Selector sel = findSelectorByReference(refSearchKey.getId());
      if (sel != null) {
        return sel;
      }
    }

    String refId = column.getReference().getId();
    if (!NeoSelectorService.REF_TABLE.equals(refId)
        && !NeoSelectorService.REF_TABLEDIR.equals(refId)
        && !NeoSelectorService.REF_SEARCH.equals(refId)) {
      Selector sel = findSelectorByReference(refId);
      if (sel != null) {
        return sel;
      }
    }

    return null;
  }

  private static Selector findSelectorByReference(String referenceId) {
    try {
      OBCriteria<Selector> crit = OBDal.getInstance().createCriteria(Selector.class);
      crit.add(Restrictions.eq(Selector.PROPERTY_REFERENCE + ".id", referenceId));
      crit.add(Restrictions.eq(Selector.PROPERTY_ACTIVE, true));
      crit.setMaxResults(1);
      return (Selector) crit.uniqueResult();
    } catch (Exception e) {
      log.debug("Error looking up OBUISEL_Selector for ref {}: {}",
          referenceId, e.getMessage());
      return null;
    }
  }

  private static SelectorMeta resolveObuiselSelector(Selector selector) {
    try {
      boolean isCustom = Boolean.TRUE.equals(selector.isCustomQuery());
      String customHql = isCustom ? selector.getHQL() : null;
      String entityAlias = StringUtils.defaultIfBlank(selector.getEntityAlias(), "e");

      Table targetTable = selector.getTable();
      if (targetTable == null) {
        log.warn("OBUISEL_Selector {} has no target table", selector.getName());
        return null;
      }

      Entity targetEntity = ModelProvider.getInstance()
          .getEntityByTableName(targetTable.getDBTableName());
      if (targetEntity == null) {
        log.warn("No entity for OBUISEL table: {}", targetTable.getDBTableName());
        return null;
      }

      String displayProp = resolveDisplayProperty(selector, targetEntity);
      String valueProp = resolveValueProperty(selector);
      String whereClause = StringUtils.trimToNull(selector.getHQLWhereClause());
      ObuiselFieldLists fieldLists = classifySelectorFields(selector.getOBUISELSelectorFieldList());
      fieldLists.gridFields.sort((a, b) -> Long.compare(a.sortNo, b.sortNo));

      return new SelectorMeta.Builder(targetEntity.getName(), displayProp)
          .whereClause(whereClause)
          .isRich(true)
          .isCustomQuery(isCustom)
          .valueProperty(valueProp)
          .gridFields(fieldLists.gridFields)
          .searchableProperties(fieldLists.searchableProps)
          .customHql(customHql)
          .entityAlias(entityAlias)
          .auxFields(fieldLists.auxFields)
          .build();
    } catch (Exception e) {
      log.warn("Could not resolve OBUISEL_Selector {}: {}", selector.getName(), e.getMessage());
      return null;
    }
  }

  private static String resolveDisplayProperty(Selector selector, Entity targetEntity) {
    SelectorField displayField = selector.getDisplayfield();
    if (displayField != null && StringUtils.isNotBlank(displayField.getProperty())) {
      return displayField.getProperty();
    }
    return findIdentifierProperty(targetEntity);
  }

  private static String resolveValueProperty(Selector selector) {
    SelectorField valueField = selector.getValuefield();
    if (valueField != null && StringUtils.isNotBlank(valueField.getProperty())) {
      return valueField.getProperty();
    }
    return "id";
  }

  private static ObuiselFieldLists classifySelectorFields(List<SelectorField> selectorFields) {
    List<RichFieldMeta> gridFields = new ArrayList<>();
    List<String> searchableProps = new ArrayList<>();
    List<AuxFieldMeta> auxFields = new ArrayList<>();

    for (SelectorField sf : selectorFields) {
      if (!Boolean.TRUE.equals(sf.isActive())) {
        continue;
      }
      collectAuxField(sf, auxFields);
      collectGridAndSearchFields(sf, gridFields, searchableProps);
    }
    return new ObuiselFieldLists(gridFields, searchableProps, auxFields);
  }

  private static void collectAuxField(SelectorField sf, List<AuxFieldMeta> auxFields) {
    if (Boolean.TRUE.equals(sf.isOutfield()) && StringUtils.isNotBlank(sf.getSuffix())) {
      String alias = sf.getDisplayColumnAlias();
      auxFields.add(new AuxFieldMeta(
          sf.getSuffix(),
          alias != null ? alias.toLowerCase() : null,
          sf.getName(),
          sf.getProperty()));
    }
  }

  private static void collectGridAndSearchFields(SelectorField sf,
      List<RichFieldMeta> gridFields, List<String> searchableProps) {
    String prop = sf.getProperty();
    String searchFragment = resolveSearchableFragment(prop, sf.getClauseLeftPart());

    if (StringUtils.isNotBlank(prop) && Boolean.TRUE.equals(sf.isShowingrid())) {
      String propKey = getLastSegment(prop);
      Long sortNo = sf.getSortno();
      gridFields.add(new RichFieldMeta(propKey, sf.getName(), prop,
          sortNo != null ? sortNo : 0L));
    }
    if (Boolean.TRUE.equals(sf.isSearchinsuggestionbox())
        && StringUtils.isNotBlank(searchFragment)
        && !searchFragment.endsWith("_identifier")) {
      searchableProps.add(searchFragment);
    }
  }

  private static String getLastSegment(String propertyPath) {
    int lastDot = propertyPath.lastIndexOf('.');
    if (lastDot >= 0 && lastDot < propertyPath.length() - 1) {
      return propertyPath.substring(lastDot + 1);
    }
    return propertyPath;
  }

  private static SelectorMeta resolveTableDir(Column column) {
    String colName = column.getDBColumnName();
    if (!colName.endsWith("_ID")) {
      log.warn("TableDir column doesn't end with _ID: {}", colName);
      return null;
    }

    String tableName = colName.substring(0, colName.length() - 3);
    try {
      Entity targetEntity = ModelProvider.getInstance().getEntityByTableName(tableName);
      if (targetEntity == null) {
        log.warn("No entity found for table: {}", tableName);
        return null;
      }
      return new SelectorMeta(targetEntity.getName(), findIdentifierProperty(targetEntity), null);
    } catch (Exception e) {
      log.warn("Could not resolve TableDir for {}: {}", colName, e.getMessage());
      return null;
    }
  }

  private static SelectorMeta resolveRefTable(Column column) {
    org.openbravo.model.ad.domain.Reference refValue = column.getReferenceSearchKey();
    if (refValue == null) {
      log.warn("Column {} has no AD_Reference_Value", column.getDBColumnName());
      return null;
    }

    try {
      OBCriteria<ReferencedTable> refTableCrit =
          OBDal.getInstance().createCriteria(ReferencedTable.class);
      refTableCrit.add(Restrictions.eq(ReferencedTable.PROPERTY_REFERENCE + ".id",
          refValue.getId()));
      refTableCrit.setMaxResults(1);

      ReferencedTable refTable = (ReferencedTable) refTableCrit.uniqueResult();
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

      String displayProp = displayCol != null
          ? resolveDisplayColumnProperty(targetEntity, displayCol)
          : findIdentifierProperty(targetEntity);
      return new SelectorMeta(targetEntity.getName(), displayProp,
          StringUtils.trimToNull(refTable.getHqlwhereclause()));
    } catch (Exception e) {
      log.warn("Could not resolve ref table for {}: {}",
          column.getDBColumnName(), e.getMessage());
      return null;
    }
  }

  private static String resolveDisplayColumnProperty(Entity targetEntity, Column displayCol) {
    Property prop = targetEntity.getPropertyByColumnName(displayCol.getDBColumnName());
    return prop != null ? prop.getName() : "name";
  }
}
