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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-4967: resolves the ids of every {@code M_Product_Category} of a given client flagged
 * {@code EM_Etgo_IsSystemCategory = 'Y'} — categories that exist to support internal logic (e.g.
 * "Discounts", which holds the internal global-discount product {@code ETGO_DTO}) rather than
 * for end-user classification, and must therefore be hidden from windows and selectors.
 *
 * <p>Plain SQL rather than DAL/Criteria: the column is not yet exposed as an
 * {@code ETGO_SF_FIELD} on any window's contract, so it is not mapped as a queryable entity
 * property this code can safely depend on — same reasoning as
 * {@code AbstractInvoiceHeaderHandler#enrichIsRectificative} for another {@code EM_} column.
 *
 * <p>Shared by every place that needs this set, so the query lives in exactly one place:
 * {@link ProductCategoryDefaultHandler} (hides the categories themselves from the "Categoría del
 * producto" window), {@code ComboRowSelectorPolicy} (hides them from the category selector on the
 * Product window), and {@link ProductDefaultsHandler} (hides products classified under them from
 * the Product window).
 */
public final class SystemCategoryIds {

  private static final Logger log = LogManager.getLogger(SystemCategoryIds.class);

  private SystemCategoryIds() {
  }

  /**
   * @param clientId the tenant to scope the lookup to
   * @return ids of every system-flagged category for that client, or an empty set on any
   *         resolution failure (fail open — callers must treat an empty set as "hide nothing",
   *         never as an error condition to propagate)
   */
  public static Set<String> resolve(String clientId) {
    Set<String> ids = new HashSet<>();
    if (clientId == null || clientId.isEmpty()) {
      return ids;
    }
    String sql = "SELECT m_product_category_id FROM m_product_category "
        + "WHERE ad_client_id = ? AND em_etgo_issystemcategory = 'Y'";
    try {
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, clientId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            ids.add(rs.getString(1));
          }
        }
      }
    } catch (Exception e) {
      log.warn("Could not resolve system product categories for client {}: {}", clientId,
          e.getMessage());
    }
    return ids;
  }
}
