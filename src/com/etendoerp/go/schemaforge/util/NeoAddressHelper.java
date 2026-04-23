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
import org.openbravo.model.common.geography.Location;

/**
 * Static helpers for address formatting used in printable documents.
 */
public final class NeoAddressHelper {

  private NeoAddressHelper() {
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
   * @param loc the location record; must not be {@code null}
   * @return formatted city line, or {@code null} if no data is present
   */
  public static String formatCityLine(Location loc) {
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
