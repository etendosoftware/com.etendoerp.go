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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;

/**
 * Single source of truth for {@code c_doctype.em_etsg_isrectificative} (the "Factura
 * Rectificativa" flag, owned by the optional {@code com.etendoerp.sif.general} module —
 * DB prefix ETSG). Shared by {@link AbstractInvoiceHeaderHandler} (per-record {@code
 * isRectificative} enrichment) and {@link PaymentCreditSourcesService} / {@link
 * PaymentCreditConsumer} (the ETP-4738 "saldo a favor" filter, which lists/consumes only
 * rectificative invoices with a negative total).
 */
final class RectificativeDocTypeSupport {

  private static final Logger log = LogManager.getLogger(RectificativeDocTypeSupport.class);

  /**
   * Cached result of probing whether {@code c_doctype.em_etsg_isrectificative} exists in this
   * database. Resolved lazily once: querying a missing column would abort the whole PostgreSQL
   * transaction, poisoning the shared read-only connection for every statement that follows in
   * the same request.
   */
  private static volatile Boolean rectificativeColumnPresent;

  private RectificativeDocTypeSupport() {
  }

  /** Test hook: force or reset (null) the cached column-presence check. */
  static void setRectificativeColumnPresentForTests(Boolean value) {
    rectificativeColumnPresent = value;
  }

  static boolean isRectificativeColumnPresent() {
    Boolean present = rectificativeColumnPresent;
    if (present == null) {
      synchronized (RectificativeDocTypeSupport.class) {
        present = rectificativeColumnPresent;
        if (present == null) {
          present = probeRectificativeColumnPresent();
          rectificativeColumnPresent = present;
        }
      }
    }
    return present;
  }

  @SuppressWarnings("java:S2077")
  private static boolean probeRectificativeColumnPresent() {
    try {
      String sql = "SELECT 1 FROM information_schema.columns"
          + " WHERE table_name = 'c_doctype' AND column_name = 'em_etsg_isrectificative'";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql);
           ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (Exception e) {
      log.warn("Could not check for em_etsg_isrectificative column: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Whether {@code docTypeId}'s {@code em_etsg_isrectificative} flag is set. False when the id
   * is blank or the column is absent (SIF General not installed).
   */
  @SuppressWarnings("java:S2077")
  static boolean isRectificativeDocType(String docTypeId) {
    if (StringUtils.isBlank(docTypeId) || !isRectificativeColumnPresent()) {
      return false;
    }
    try {
      String sql = "SELECT em_etsg_isrectificative FROM c_doctype WHERE c_doctype_id = ?";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, docTypeId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() && "Y".equals(rs.getString(1));
        }
      }
    } catch (Exception e) {
      log.warn("Could not resolve isRectificative for doctype {}: {}", docTypeId, e.getMessage());
      return false;
    }
  }

  /**
   * Resolves the active "Factura Rectificativa" document type ids for {@code clientId} on the
   * given transaction side (sales vs. purchase), scoped to that client and {@code "0"} (System).
   * Returns an empty, unmodifiable list when the column is absent, {@code clientId} is blank,
   * no doc type is flagged, or the lookup fails — callers must treat an empty result as "no
   * rectificative doc type is configured" (i.e. nothing can qualify), not as "unrestricted".
   */
  @SuppressWarnings("java:S2077")
  static List<String> resolveRectificativeDocTypes(String clientId, boolean isSalesTransaction) {
    if (StringUtils.isBlank(clientId) || !isRectificativeColumnPresent()) {
      return Collections.emptyList();
    }
    List<String> ids = new ArrayList<>();
    try {
      String sql = "SELECT dt.c_doctype_id FROM c_doctype dt"
          + " WHERE dt.em_etsg_isrectificative = 'Y' AND dt.isactive = 'Y'"
          + " AND dt.ad_client_id IN ('0', ?) AND dt.issotrx = ?";
      Connection conn = OBDal.getReadOnlyInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, clientId);
        ps.setString(2, isSalesTransaction ? "Y" : "N");
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            ids.add(rs.getString(1));
          }
        }
      }
    } catch (Exception e) {
      log.warn("Could not resolve rectificative doc types for client {}: {}", clientId,
          e.getMessage());
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(ids);
  }
}
