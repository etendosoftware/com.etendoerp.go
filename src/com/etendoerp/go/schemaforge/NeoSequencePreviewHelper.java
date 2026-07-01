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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.sequences.SequenceUtils;

/**
 * Sequence/DocumentNo preview helpers extracted from {@link NeoDefaultsService} to keep that
 * class within its method-count budget.
 *
 * <p>Groups the preview-generation concerns used during NEO default resolution:
 * <ul>
 *   <li>choosing between transactional and DocumentNo previews for a column
 *   <li>generating a DocumentNo preview using doctypes resolved in pass 1
 *   <li>the legacy/deprecated DocumentNo preview used outside the two-pass flow
 * </ul>
 *
 * <p>All methods are stateless and behavior-identical to their previous private counterparts in
 * {@link NeoDefaultsService}; only their location changed. Transactional sequence previews are
 * still produced by {@link NeoDefaultsService#resolveTransactionalSequencePreview(Column)}, which
 * remains the single source of truth for that path.
 */
final class NeoSequencePreviewHelper {

  private static final Logger log = LogManager.getLogger(NeoSequencePreviewHelper.class);
  private static final String LOG_SEQUENCE_PREVIEW_FAILURE =
      "Could not generate sequence preview for {}: {}";

  private NeoSequencePreviewHelper() {
  }

  static @Nullable String resolveSequencePreviewForColumn(Column adColumn, VariablesSecureApp vars,
      DalConnectionProvider conn, String windowId, String docTypeTargetId, String docTypeId) {
    String preview;
    if (Boolean.TRUE.equals(SequenceUtils.isSequence(adColumn))) {
      preview = NeoDefaultsService.resolveTransactionalSequencePreview(adColumn);
    } else {
      preview = resolveSequencePreviewWithDocType(
          adColumn, vars, conn, windowId, docTypeTargetId, docTypeId);
    }
    return preview;
  }

  /**
   * Generate a sequence preview using the doctype IDs already resolved in pass 1.
   *
   * <p>Mirrors UIDefinition.getFieldProperties line 210 exactly:
   * {@code Utility.getDocumentNo(conn, vars, windowId, tableName, docTypeTarget, docType, false, false)}
   * Classic reads docTypeTarget from RequestContext (set when C_DocTypeTarget_ID was processed
   * before DocumentNo). We pass those values explicitly after resolving them in pass 1.
   */
  static String resolveSequencePreviewWithDocType(Column adColumn,
      VariablesSecureApp vars, DalConnectionProvider conn, String windowId,
      String docTypeTargetId, String docTypeId) {
    try {
      String tableName = adColumn.getTable().getDBTableName();
      String docNo = Utility.getDocumentNo(conn, vars, windowId, tableName,
          docTypeTargetId, docTypeId, false, false);
      if (docNo != null && !docNo.isEmpty()) {
        return "<" + docNo + ">";
      }
      return null;
    } catch (Exception e) {
      log.debug(LOG_SEQUENCE_PREVIEW_FAILURE,
          adColumn.getDBColumnName(), e.getMessage());
      return null;
    }
  }

  /**
   * Generate a preview of the next sequence value without consuming it.
   * Uses Utility.getDocumentNo with updateNext=false for a real preview.
   * Returns the value wrapped in angle brackets (e.g., "<1000234>").
   *
   * @deprecated Use {@link #resolveSequencePreviewWithDocType} from resolveDefaults pass 2.
   *   This method passes empty doctype strings and is only kept for callers outside the
   *   two-pass defaults flow (e.g., injectMandatoryDefaults).
   */
  @Deprecated
  static String resolveSequencePreview(Column adColumn, VariablesSecureApp vars,
      DalConnectionProvider conn, String windowId, NeoContext ctx) {
    try {
      String tableName = adColumn.getTable().getDBTableName();
      String docNo = Utility.getDocumentNo(conn, vars, windowId, tableName, "", "", false, false);
      if (docNo != null && !docNo.isEmpty()) {
        return "<" + docNo + ">";
      }
      return "<auto>";
    } catch (Exception e) {
      log.debug(LOG_SEQUENCE_PREVIEW_FAILURE,
          adColumn.getDBColumnName(), e.getMessage());
      return "<auto>";
    }
  }
}
