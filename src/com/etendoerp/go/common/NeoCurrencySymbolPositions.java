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

package com.etendoerp.go.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Reads the per-currency symbol-side flag from {@code C_CURRENCY.ISSYMBOLRIGHTSIDE}.
 *
 * Backs the {@code symbolRightSide} map in {@code GET /sws/neo/currency-format}
 * (ETP-4314 follow-up — QA found the symbol side hardcoded the same way for every
 * currency). No currency allow-list on the frontend: the app reads whatever Etendo
 * Classic's own reference data already says. {@code ISO_CODE} is globally unique on
 * {@code C_CURRENCY} (shared reference data, not per-client), so no client/org
 * filtering is needed beyond {@code isactive}.
 */
public final class NeoCurrencySymbolPositions {

  private static final String QUERY = "SELECT iso_code, issymbolrightside FROM c_currency WHERE isactive = 'Y'";

  private NeoCurrencySymbolPositions() {
  }

  /**
   * @return a map of ISO 4217 code to whether that currency's symbol renders on the
   *     right of the amount, for every active currency in {@code C_CURRENCY}.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Boolean> fetchAll() {
    Map<String, Boolean> result = new LinkedHashMap<>();
    try {
      OBContext.setAdminMode();
      NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(QUERY);
      List<Object[]> rows = (List<Object[]>) query.list();
      for (Object[] row : rows) {
        // ISSYMBOLRIGHTSIDE is CHAR(1) — depending on the JDBC/Hibernate type
        // resolution for an unaliased native-query column, row[1] can come back
        // as a String OR a Character. "Y".equals(row[1]) is silently always
        // false for a Character (String.equals rejects non-String operands
        // outright), so normalize via String.valueOf() before comparing.
        result.put((String) row[0], "Y".equalsIgnoreCase(String.valueOf(row[1])));
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    return result;
  }
}
