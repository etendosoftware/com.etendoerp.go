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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Re-previews {@code DocumentNo} when a draft record's doc-type target changes, extracted from
 * {@link NeoCrudHandler} to keep that class within its method-count budget (same reasoning as
 * {@link NeoSequencePreviewHelper}, extracted from {@link NeoDefaultsService} for the same rule).
 *
 * <p>All methods are stateless and behavior-identical to their previous private counterparts in
 * {@code NeoCrudHandler}; only their location changed.
 */
final class DocumentNoRepreviewHelper {

  private static final Logger log = LogManager.getLogger(DocumentNoRepreviewHelper.class);

  private static final String COL_DOC_TYPE_TARGET_ID = "C_DocTypeTarget_ID";
  private static final String COL_DOCUMENT_NO = "DocumentNo";
  private static final String PROP_PROCESSED = "processed";
  private static final String PROP_DOCUMENT_STATUS = "documentStatus";
  private static final String DOC_STATUS_DRAFT = "DR";
  private static final String FIELD_DOCUMENT_NO = "documentNo";

  private DocumentNoRepreviewHelper() {
  }

  /**
   * Re-previews {@code DocumentNo} when the caller changes the doc-type target of a record that is
   * still a DRAFT, mirroring the classic {@code SL_Invoice_Legacy} callout: a different target gets
   * the new sequence's {@code <currentnext>} placeholder, an unchanged target keeps the current
   * number. The value is deliberately a placeholder in angle brackets — the sequence is previewed
   * with {@code updateNext=false} and only materialized when the document is completed.
   *
   * <p>Entirely best-effort: every guard and the whole computation are defensive, because failing
   * to refresh a draft's number must never turn into a failed update.
   *
   * @param filteredBody        the post-filter request body (modified in place, DAL property names)
   * @param context             the current NEO context (tab, record id, OBContext, SF entity)
   * @param dalEntityName       the DAL entity name used to read the persisted record
   * @param clientSentDocumentNo whether the caller explicitly authored a DocumentNo (then: no-op)
   */
  static void regenerateDocumentNoOnDocTypeChange(JSONObject filteredBody, NeoContext context,
      String dalEntityName, boolean clientSentDocumentNo) {
    if (filteredBody == null || context == null || clientSentDocumentNo) {
      return;
    }
    try {
      Tab adTab = context.getAdTab();
      String recordId = context.getRecordId();
      if (adTab == null || adTab.getTable() == null || StringUtils.isBlank(recordId)) {
        return;
      }
      Entity dalEntity = ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
      if (dalEntity == null) {
        return;
      }
      Property targetProp = dalEntity.getPropertyByColumnName(COL_DOC_TYPE_TARGET_ID, false);
      Property docNoProp = dalEntity.getPropertyByColumnName(COL_DOCUMENT_NO, false);
      if (targetProp == null || docNoProp == null || filteredBody.has(docNoProp.getName())) {
        return;
      }
      String submittedTarget = filteredBody.optString(targetProp.getName(), null);
      if (StringUtils.isBlank(submittedTarget)) {
        return;
      }
      BaseOBObject stored = OBDal.getInstance().get(dalEntityName, recordId);
      if (stored == null || !isDraftRecord(stored, dalEntity)) {
        return;
      }
      // Unchanged target: keep the persisted number, exactly as SL_Invoice_Legacy does.
      if (StringUtils.equals(resolveStoredId(stored, targetProp), submittedTarget)) {
        return;
      }
      applyDocumentNoPreview(filteredBody, context, adTab, docNoProp, submittedTarget);
    } catch (Exception e) {
      log.debug("Could not refresh DocumentNo after doc-type change: {}", e.getMessage());
    }
  }

  /**
   * Computes the new sequence placeholder and writes it into the body. Both doctype arguments are
   * the NEW target: {@code syncDocumentTypeToSubmittedTarget} has already made the effective
   * doctype equal to it.
   */
  private static void applyDocumentNoPreview(JSONObject filteredBody, NeoContext context, Tab adTab,
      Property docNoProp, String newDocTypeId) throws JSONException {
    Column docNoColumn = findTableColumn(adTab, COL_DOCUMENT_NO);
    if (docNoColumn == null) {
      return;
    }
    VariablesSecureApp vars =
        NeoDefaultsService.buildVariablesSecureApp(context.getObContext(), adTab);
    DalConnectionProvider conn = new DalConnectionProvider(false);
    String windowId = context.getSfEntity() != null
        ? NeoDefaultsService.resolveWindowId(context.getSfEntity())
        : "";
    String preview = NeoSequencePreviewHelper.resolveSequencePreviewWithDocType(
        docNoColumn, vars, conn, windowId, newDocTypeId, newDocTypeId);
    if (StringUtils.isNotBlank(preview)) {
      filteredBody.put(docNoProp.getName(), preview);
      log.debug("Re-previewed DocumentNo as {} after doc-type change to {}", preview, newDocTypeId);
    }
  }

  /**
   * Whether the caller authored a real {@code DocumentNo} of its own. A sequence preview
   * PLACEHOLDER ({@code <10000000>}, {@code <REC-1000008>}) does NOT count: the client echoes it
   * back from its own form state after a doc-type change, so reading it as a deliberate choice
   * would suppress the very re-numbering it is previewing (ETP-5274).
   */
  static boolean hasClientAuthoredDocumentNo(JSONObject rawBody) {
    if (rawBody == null || !rawBody.has(FIELD_DOCUMENT_NO)
        || rawBody.isNull(FIELD_DOCUMENT_NO)) {
      return false;
    }
    Object raw = rawBody.opt(FIELD_DOCUMENT_NO);
    String value = raw == null ? null : raw.toString().trim();
    return StringUtils.isNotBlank(value) && !isSequencePlaceholder(value);
  }

  /**
   * True for a sequence preview placeholder: angle-bracket delimited, with no nested bracket.
   * Mirrors {@code isSequencePlaceholder} in the frontend's {@code useEntity.js}.
   */
  private static boolean isSequencePlaceholder(String value) {
    if (value == null || value.length() < 3 || value.charAt(0) != '<'
        || value.charAt(value.length() - 1) != '>') {
      return false;
    }
    String inner = value.substring(1, value.length() - 1);
    return inner.indexOf('<') < 0 && inner.indexOf('>') < 0;
  }

  /**
   * Whether the persisted record is still a draft. {@code documentStatus} is authoritative when the
   * entity has it; {@code processed} is the fallback. An entity carrying neither is not a document
   * with a completion flow, so the caller must treat it as "does not apply" ({@code false}).
   */
  private static boolean isDraftRecord(BaseOBObject stored, Entity dalEntity) {
    if (dalEntity.hasProperty(PROP_DOCUMENT_STATUS)) {
      Object status = stored.get(PROP_DOCUMENT_STATUS);
      if (status != null) {
        return DOC_STATUS_DRAFT.equals(status.toString());
      }
    }
    if (dalEntity.hasProperty(PROP_PROCESSED)) {
      return Boolean.FALSE.equals(stored.get(PROP_PROCESSED));
    }
    return false;
  }

  /** The id of a persisted reference property, or {@code null} when unset. */
  private static String resolveStoredId(BaseOBObject stored, Property prop) {
    Object value = stored.get(prop.getName());
    if (value instanceof BaseOBObject) {
      Object id = ((BaseOBObject) value).getId();
      return id == null ? null : id.toString();
    }
    return value == null ? null : value.toString();
  }

  /** The active AD column of the tab's table with the given DB column name, or {@code null}. */
  private static Column findTableColumn(Tab adTab, String dbColumnName) {
    for (Column col : adTab.getTable().getADColumnList()) {
      if (dbColumnName.equalsIgnoreCase(col.getDBColumnName())) {
        return col;
      }
    }
    return null;
  }
}
