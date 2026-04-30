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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.application.window.ApplicationDictionaryCachedStructures;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.data.FieldProvider;
import org.openbravo.erpCommon.utility.ComboTableData;
import org.openbravo.erpCommon.utility.FieldProviderFactory;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.domain.Validation;
import org.openbravo.model.ad.ui.Field;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.data.SFEntity;

/** Executes classic ComboTableData selectors for SQL validation-rule references. */
final class ComboReferenceSelectorExecutor {

  private static final Logger log = LogManager.getLogger(ComboReferenceSelectorExecutor.class);
  private static final String FIELD_LABEL = "label";

  private ComboReferenceSelectorExecutor() {
  }

  static boolean shouldUseCoreComboSelector(SFEntity sourceEntity, Column column, String refId) {
    return sourceEntity != null
        && NeoSelectorService.isFkReference(refId)
        && hasSqlValidationRule(column)
        && resolveComboField(sourceEntity, column) != null;
  }

  static NeoResponse resolveClassicSelectorWithCoreCombo(SFEntity sourceEntity,
      Column column, String search, int limit, int offset, Map<String, String> contextParams)
      throws Exception {
    Field field = resolveComboField(sourceEntity, column);
    if (field == null) {
      return NeoResponse.error(500,
          "Could not resolve AD_Field for SQL validation selector: " + column.getDBColumnName());
    }

    OBContext obCtx = OBContext.getOBContext();
    VariablesSecureApp vars = CalloutRequestBuilder.buildCalloutVars(obCtx, sourceEntity.getADTab());
    RequestContext.get().setVariableSecureApp(vars);

    ApplicationDictionaryCachedStructures cachedStructures = WeldUtils
        .getInstanceFromStaticBeanManager(ApplicationDictionaryCachedStructures.class);
    ComboTableData comboTableData = cachedStructures.getComboTableData(field);

    Map<String, String> selectorParams = SelectorContextResolver.buildComboSelectorParams(
        sourceEntity, contextParams);
    selectorParams.put("CLIENT_LIST", OBContext.getOBContext().getCurrentClient().getId());
    selectorParams.put("ORG_LIST", Arrays.stream(OBContext.getOBContext().getReadableOrganizations())
        .collect(Collectors.joining(",")));

    log.info("[ComboSelector] column={} selectorParams={}", column.getDBColumnName(), selectorParams);

    String windowId = field.getTab() != null && field.getTab().getWindow() != null
        ? field.getTab().getWindow().getId()
        : null;
    Map<String, String> resolvedParams = comboTableData.fillSQLParametersIntoMap(
        new DalConnectionProvider(false), vars, new FieldProviderFactory(selectorParams), windowId,
        null);
    log.info("[ComboSelector] resolvedParams={}", resolvedParams);

    if (StringUtils.isNotBlank(search)) {
      resolvedParams.put("FILTER_VALUE", search);
    }

    FieldProvider[] rawRows = comboTableData.select(new DalConnectionProvider(false), resolvedParams,
        false, offset, offset + limit);
    log.info("[ComboSelector] column={} rawRows={} offset={} limit={} resolvedParams={}",
        column.getDBColumnName(), rawRows.length, offset, limit, resolvedParams);

    return buildResponse(rawRows, limit, offset);
  }

  private static boolean hasSqlValidationRule(Column column) {
    Validation validation = column != null ? column.getValidation() : null;
    return validation != null && "S".equalsIgnoreCase(validation.getType());
  }

  private static Field resolveComboField(SFEntity sourceEntity, Column column) {
    if (sourceEntity == null || column == null || sourceEntity.getADTab() == null) {
      return null;
    }

    OBCriteria<Field> criteria = OBDal.getInstance().createCriteria(Field.class);
    criteria.add(Restrictions.eq(Field.PROPERTY_TAB, sourceEntity.getADTab()));
    criteria.add(Restrictions.eq("column", column));
    criteria.add(Restrictions.eq("active", true));
    criteria.setMaxResults(1);

    List<Field> fields = criteria.list();
    return fields.isEmpty() ? null : fields.get(0);
  }

  private static NeoResponse buildResponse(FieldProvider[] rawRows, int limit, int offset)
      throws Exception {
    boolean hasMore = rawRows.length > limit;
    int visibleRows = hasMore ? limit : rawRows.length;
    int totalCount = hasMore ? offset + limit + 1 : offset + visibleRows;

    JSONArray items = new JSONArray();
    for (int index = 0; index < visibleRows; index++) {
      FieldProvider row = rawRows[index];
      JSONObject item = new JSONObject();
      item.put("id", row.getField("ID"));
      item.put(FIELD_LABEL, row.getField("NAME"));
      items.put(item);
    }

    return SelectorQueryBuilder.buildSelectorResponse(items, new JSONArray(), totalCount, limit,
        offset);
  }
}
