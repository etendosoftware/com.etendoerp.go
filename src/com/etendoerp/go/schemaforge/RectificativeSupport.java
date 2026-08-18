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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;

/**
 * Shared guard for the optional {@code C_DocType.EM_ETSG_ISRECTIFICATIVE} column, owned by the
 * (optional) SIF General module ({@code com.etendoerp.sif.general}, ETP-4737 "Factura
 * Rectificativa").
 *
 * <p>Both the GET-side enrichment ({@link AbstractInvoiceHeaderHandler#enrichIsRectificative})
 * and the write-side rectificative doc-type lookup ({@link ReturnShipmentUtils#findReturnDocTypeForOrg})
 * and subtype classification ({@code classifyDocType} in {@link SalesInvoiceHeaderHandler} /
 * {@link PurchaseInvoiceHeaderHandler}) must go through {@link #isColumnPresent()} (or
 * {@link #isRectificative(DocumentType)}) before touching the column — a SELECT against a
 * missing column would abort the whole shared PostgreSQL transaction for the rest of the request.
 */
final class RectificativeSupport {

  private static final Logger log = LogManager.getLogger(RectificativeSupport.class);

  private static volatile Boolean columnPresent;

  private RectificativeSupport() {
  }

  /** Test hook: force or reset (null) the cached column-presence check. */
  static void setColumnPresentForTests(Boolean value) {
    columnPresent = value;
  }

  /**
   * Whether {@code c_doctype.em_etsg_isrectificative} exists in this database (the column
   * belongs to the SIF General module, which may not be installed). Resolved lazily once:
   * querying a missing column would abort the whole PostgreSQL transaction, poisoning the
   * shared read-only connection for every statement that follows in the same request.
   */
  @SuppressWarnings("java:S2077")
  static boolean isColumnPresent() {
    Boolean present = columnPresent;
    if (present == null) {
      synchronized (RectificativeSupport.class) {
        present = columnPresent;
        if (present == null) {
          present = false;
          try {
            String sql = "SELECT 1 FROM information_schema.columns"
                + " WHERE table_name = 'c_doctype' AND column_name = 'em_etsg_isrectificative'";
            Connection conn = OBDal.getReadOnlyInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
              present = rs.next();
            }
          } catch (Exception e) {
            log.warn("Could not check for em_etsg_isrectificative column: {}", e.getMessage());
          }
          columnPresent = present;
        }
      }
    }
    return present;
  }

  /**
   * Whether the given document type is flagged as rectificative
   * ({@code EM_Etsg_Isrectificative = 'Y'}). Always {@code false} when {@code dt} is
   * {@code null} or the column is not present in this database (SIF General not installed).
   *
   * @param dt the document type to inspect, may be {@code null}
   * @return {@code true} only when the column exists and the flag is set
   */
  static boolean isRectificative(DocumentType dt) {
    if (dt == null || !isColumnPresent()) {
      return false;
    }
    return Boolean.TRUE.equals(dt.isEtsgIsRectificative());
  }

}
