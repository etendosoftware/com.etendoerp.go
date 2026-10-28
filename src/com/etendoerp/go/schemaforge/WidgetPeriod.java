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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.sql.Date;
import java.util.List;

import org.openbravo.dal.service.OBDal;

/**
 * The period a dashboard widget's {@code range} covers, as dates (ETP-5852, M-38 / M-42): what the
 * MCP tells an agent next to the figures. The start is the very expression the widget queries use
 * ({@link WidgetQueryHelper#rangeToSqlDateFrom}), evaluated by the database, so the reported
 * period and the computed one cannot drift apart.
 */
public final class WidgetPeriod {

  /** The {@code range} values the widget queries recognize. */
  public static final List<String> RANGES = List.of("ytd", "mtd", "last30d", "last90d", "lastYear");

  private WidgetPeriod() {
  }

  /**
   * @param range one of {@link #RANGES}
   * @return the first day of the period, as the database computes it now
   */
  public static Date start(String range) {
    if (!RANGES.contains(range)) {
      throw new IllegalArgumentException("Unknown widget range: " + range);
    }
    Object value = OBDal.getInstance().getSession()
        .createNativeQuery("SELECT CAST(" + WidgetQueryHelper.rangeToSqlDateFrom(range)
            + " AS date)")
        .uniqueResult();
    return value instanceof Date ? (Date) value : Date.valueOf(String.valueOf(value));
  }
}
