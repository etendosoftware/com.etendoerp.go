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

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;

/** Executes AD List reference selectors. */
final class ListReferenceSelectorExecutor {

  private static final String FIELD_LABEL = "label";

  private ListReferenceSelectorExecutor() {
  }

  static NeoResponse resolveListSelector(Column column, String search,
      int limit, int offset, Map<String, String> contextParams) throws Exception {

    org.openbravo.model.ad.domain.Reference listRef = column.getReferenceSearchKey();
    if (listRef == null) {
      listRef = column.getReference();
    }

    String valRuleSql = SelectorQueryBuilder.resolveValidationSql(column, contextParams);
    OBCriteria<org.openbravo.model.ad.domain.List> countCrit = buildCriteria(listRef.getId(),
        valRuleSql, search);
    int totalCount = countCrit.count();

    OBCriteria<org.openbravo.model.ad.domain.List> dataCrit = buildCriteria(listRef.getId(),
        valRuleSql, search);
    dataCrit.addOrderBy(org.openbravo.model.ad.domain.List.PROPERTY_SEQUENCENUMBER, true);
    dataCrit.setFirstResult(offset);
    dataCrit.setMaxResults(limit);

    JSONArray items = new JSONArray();
    for (org.openbravo.model.ad.domain.List listItem : dataCrit.list()) {
      JSONObject item = new JSONObject();
      item.put("id", listItem.getSearchKey());
      item.put(FIELD_LABEL, listItem.getName());
      items.put(item);
    }
    return SelectorResponseSupport.buildSelectorResponse(items, new JSONArray(), totalCount, limit, offset);
  }

  static Map<String, String> getListLabels(String referenceId) {
    Map<String, String> labels = new HashMap<>();
    try {
      OBCriteria<org.openbravo.model.ad.domain.List> crit = OBDal.getInstance()
          .createCriteria(org.openbravo.model.ad.domain.List.class);
      crit.add(Restrictions.eq(
          org.openbravo.model.ad.domain.List.PROPERTY_REFERENCE + ".id", referenceId));
      crit.add(Restrictions.eq(
          org.openbravo.model.ad.domain.List.PROPERTY_ACTIVE, true));
      for (org.openbravo.model.ad.domain.List item : crit.list()) {
        labels.put(item.getSearchKey(), item.getName());
      }
    } catch (Exception e) {
      // Preserve historical behavior: list labels are optional enrichment.
    }
    return labels;
  }

  private static OBCriteria<org.openbravo.model.ad.domain.List> buildCriteria(String referenceId,
      String valRuleSql, String search) {
    OBCriteria<org.openbravo.model.ad.domain.List> criteria = OBDal.getInstance()
        .createCriteria(org.openbravo.model.ad.domain.List.class);
    criteria.add(Restrictions.eq(
        org.openbravo.model.ad.domain.List.PROPERTY_REFERENCE + ".id", referenceId));
    criteria.add(Restrictions.eq(org.openbravo.model.ad.domain.List.PROPERTY_ACTIVE, true));
    if (valRuleSql != null) {
      criteria.add(Restrictions.sqlRestriction(valRuleSql));
    }
    if (StringUtils.isNotBlank(search)) {
      criteria.add(Restrictions.ilike(
          org.openbravo.model.ad.domain.List.PROPERTY_NAME, "%" + search + "%"));
    }
    return criteria;
  }
}
