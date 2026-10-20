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

package com.etendoerp.go.schemaforge.util;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.common.geography.Location;

/**
 * Static helpers for address formatting used in printable documents, plus generic
 * column-name-based accessors for the structured-address columns added to {@code C_Location} by
 * {@code org.openbravo.sepa.iso20022} (ETP-5806: {@code EM_OBSEPA_StrtNm}, {@code EM_OBSEPA_BldgNb},
 * {@code EM_OBSEPA_BldgNm}).
 */
public final class NeoAddressHelper {

  private static final Logger log = LogManager.getLogger(NeoAddressHelper.class);

  private NeoAddressHelper() {
  }

  /**
   * Reads a DAL property by its backing DB column name rather than by a hardcoded getter.
   *
   * <p>ETP-5806: the generated DAL property name for an {@code EM_<prefix>_*} custom column is not
   * fixed by a simple rule (Etendo Go has confirmed divergent casing for other modules' custom
   * columns), and the columns this reads belong to {@code org.openbravo.sepa.iso20022}, a module
   * this one depends on but does not generate code against. Resolving by column name through
   * {@link ModelProvider} — the same technique {@code AddressVirtualSelectorPolicy} already uses
   * for the wrapper's virtual columns — reads the real metadata instead of guessing a getter name
   * that could silently fail to compile, or worse, silently compile against the wrong property.
   *
   * @return the string value, or {@code null} when the object, the column or the value is absent
   */
  public static String getColumnValue(BaseOBObject obj, String tableName, String columnName) {
    Property property = resolveProperty(tableName, columnName);
    if (property == null) {
      return null;
    }
    Object value = obj.get(property.getName());
    return value == null ? null : value.toString();
  }

  /**
   * Writes a DAL property by its backing DB column name. See {@link #getColumnValue} for why this
   * indirection exists. A column the running instance does not have yet (module not installed —
   * see the ETP-5806 plan's environment prerequisite) resolves to no property and the write is a
   * silent no-op, logged at DEBUG: the alternative, a hardcoded getter/setter call, would not
   * compile at all until the dependency is installed, which is strictly worse for every OTHER
   * entity in this module.
   */
  public static void setColumnValue(BaseOBObject obj, String tableName, String columnName,
      String value) {
    Property property = resolveProperty(tableName, columnName);
    if (property == null) {
      log.debug("Column {}.{} not found in the model (module not installed?) — skipping write",
          tableName, columnName);
      return;
    }
    obj.set(property.getName(), value);
  }

  private static Property resolveProperty(String tableName, String columnName) {
    Entity entity = ModelProvider.getInstance().getEntityByTableName(tableName);
    return entity == null ? null : entity.getPropertyByColumnName(columnName, false);
  }

  /**
   * Formats a {@link Location} into a single city/postal/region line, replicating
   * the output of Etendo Classic's {@code C_LOCATION_DESCRIPTION} PL/SQL function:
   * <pre>
   *   &lt;POSTAL&gt; - &lt;CITY&gt; (&lt;REGION&gt;)
   * </pre>
   * Each component is omitted when blank. Returns {@code null} when all components
   * are blank.
   *
   * @param loc the location record; if {@code null}, returns {@code null}
   * @return formatted city line, or {@code null} if no data is present
   */
  public static String formatCityLine(Location loc) {
    if (loc == null) {
      return null;
    }
    String postal = loc.getPostalCode();
    String city = loc.getCityName();
    String region = loc.getRegion() != null ? loc.getRegion().getName() : null;

    StringBuilder sb = new StringBuilder();
    if (StringUtils.isNotBlank(postal)) {
      sb.append(postal);
    }
    if (StringUtils.isNotBlank(city) || StringUtils.isNotBlank(region)) {
      if (sb.length() > 0) {
        sb.append(" - ");
      }
      if (StringUtils.isNotBlank(city)) {
        sb.append(city);
      }
      if (StringUtils.isNotBlank(region)) {
        if (StringUtils.isNotBlank(city)) {
          sb.append(' ');
        }
        sb.append('(').append(region).append(')');
      }
    }
    return sb.length() > 0 ? sb.toString() : null;
  }
}
