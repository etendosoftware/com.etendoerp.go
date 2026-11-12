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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.email;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoDocumentPdf;

/**
 * The name of a {@code DocStatus} code in a language, for the sentence that refuses a document email
 * by status, and for the refusal of Reactivate on a draft (ETP-6013): "1000123 está en Borrador:
 * ...".
 *
 * <p>Read from the document status lists ({@link NeoDocumentPdf#DOCUMENT_STATUS_REFERENCES}: the
 * {@code 131} All_Document Status list of invoices and goods movements, and the Order_Document Status
 * list of the order windows), translated through {@code AD_Ref_List_Trl} when the environment
 * carries the translation. Structural, not per document. A value present in both lists is named from
 * {@code 131}; the two lists name the values a send can be refused in ({@code DR}, {@code CL},
 * {@code IP}, {@code VO}...) the same way, and an order-only value ({@code UE}...) is found in the
 * order list.</p>
 *
 * <p>Total: never throws. Without a database, a translation or a list value, the answer degrades to
 * the base name and then to the code itself, so the refusal is always readable.</p>
 */
public final class DocumentStatusLabel {

  private static final Logger log = LogManager.getLogger(DocumentStatusLabel.class);

  /** The All_Document Status list, preferred when both lists hold the value. */
  private static final String ALL_DOCUMENT_STATUS = "131";

  /** The document status lists, in a fixed order, each bound to its own {@code IN} placeholder. */
  static final List<String> REFERENCES =
      List.copyOf(new TreeSet<>(NeoDocumentPdf.DOCUMENT_STATUS_REFERENCES));

  /**
   * How many {@code IN} placeholders {@link #SQL} has: one per document status list. The SQL is a
   * compile-time constant (no list built into it); {@code DocumentStatusLabelTest} pins that it
   * equals the size of {@link NeoDocumentPdf#DOCUMENT_STATUS_REFERENCES}, so adding a list there
   * fails the build until a placeholder is added here.
   */
  static final int REFERENCE_PLACEHOLDERS = 2;

  static final String SQL = "SELECT COALESCE(t.name, l.name) FROM ad_ref_list l"
      + " LEFT JOIN ad_ref_list_trl t ON t.ad_ref_list_id = l.ad_ref_list_id"
      + " AND t.ad_language = ? AND t.isactive = 'Y'"
      + " WHERE l.ad_reference_id IN (?, ?)"
      + " AND l.value = ?"
      + " ORDER BY CASE WHEN l.ad_reference_id = ? THEN 0 ELSE 1 END LIMIT 1";

  private DocumentStatusLabel() {
  }

  /**
   * The name of {@code status} in {@code language}.
   *
   * @param status   the {@code DocStatus} code, such as {@code DR}
   * @param language the AD language code, such as {@code es_ES}; blank reads the base name
   * @return the translated name, the base name, or the code when neither can be read
   */
  public static String of(String status, String language) {
    if (StringUtils.isBlank(status)) {
      return StringUtils.defaultString(status);
    }
    boolean adminMode = false;
    try {
      // Set first: setAdminMode pushes the mode before it can throw, and the finally must
      // pop it even then.
      adminMode = true;
      OBContext.setAdminMode(true);
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(SQL)) {
        int index = 1;
        ps.setString(index++, StringUtils.defaultString(language));
        for (int i = 0; i < REFERENCE_PLACEHOLDERS; i++) {
          ps.setString(index++, i < REFERENCES.size() ? REFERENCES.get(i) : null);
        }
        ps.setString(index++, status);
        ps.setString(index, ALL_DOCUMENT_STATUS);
        try (ResultSet rs = ps.executeQuery()) {
          String name = rs.next() ? StringUtils.trimToNull(rs.getString(1)) : null;
          return name != null ? name : status;
        }
      }
    } catch (Exception e) {
      log.debug("Could not read the name of document status {}: {}", status, e.getMessage());
      return status;
    } finally {
      if (adminMode) {
        OBContext.restorePreviousMode();
      }
    }
  }
}
