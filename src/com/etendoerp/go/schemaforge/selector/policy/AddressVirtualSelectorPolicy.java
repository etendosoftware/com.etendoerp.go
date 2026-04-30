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
package com.etendoerp.go.schemaforge.selector.policy;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.data.SFEntity;

/** Resolves virtual selector columns for the contact address wrapper entity. */
public final class AddressVirtualSelectorPolicy {

  private static final Logger log = LogManager.getLogger(AddressVirtualSelectorPolicy.class);

  private AddressVirtualSelectorPolicy() {
  }

  /**
   * Resolve the backing location column for a virtual address wrapper selector.
   *
   * @param entity source Schema Forge entity
   * @param columnName requested DB column name
   * @return the backing location column, or {@code null} when the wrapper policy does not apply
   */
  public static Column resolveVirtualSelectorColumn(SFEntity entity, String columnName) {
    if (entity == null || StringUtils.isBlank(columnName)) {
      return null;
    }

    org.openbravo.model.ad.ui.Tab tab = entity.getADTab();
    String tableName = tab != null && tab.getTable() != null
        ? tab.getTable().getDBTableName()
        : null;

    boolean isBPartnerLocationWrapper = "C_BPartner_Location".equalsIgnoreCase(tableName)
        || "locationAddress".equals(entity.getName());
    boolean isLocationVirtualColumn = "C_Country_ID".equalsIgnoreCase(columnName)
        || "C_Region_ID".equalsIgnoreCase(columnName);
    if (!isBPartnerLocationWrapper || !isLocationVirtualColumn) {
      return null;
    }

    OBCriteria<Column> criteria = OBDal.getInstance().createCriteria(Column.class);
    criteria.createAlias(Column.PROPERTY_TABLE, "tbl");
    criteria.add(Restrictions.eq("tbl.dBTableName", "C_Location"));
    criteria.add(Restrictions.eq(Column.PROPERTY_DBCOLUMNNAME, columnName));
    criteria.setMaxResults(1);

    List<Column> results = criteria.list();
    if (results.isEmpty()) {
      return null;
    }
    log.debug("Resolved virtual selector column {} for entity {}", columnName, entity.getName());
    return results.get(0);
  }
}
