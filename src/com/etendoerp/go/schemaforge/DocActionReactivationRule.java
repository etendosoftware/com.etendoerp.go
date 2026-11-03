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

package com.etendoerp.go.schemaforge;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.email.DocumentStatusLabel;
import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * A draft cannot be reactivated (ETP-6013): {@code docAction RE} on a document in draft status is
 * answered {@code 422} with an explanation, before the DocAction procedure runs.
 *
 * <p><b>Why.</b> The core procedure ({@code C_Order_Post}, {@code C_Invoice_Post},
 * {@code M_InOut_Post}...) refuses it with {@code ActionNotSupported} — "Esta acción no se puede
 * sostener" — which says nothing about why. The GO app never offers Reactivate on a draft; an agent
 * (MCP {@code etendo_action}, {@code etendo_action_batch}) or a REST caller could still ask.</p>
 *
 * <p><b>Where.</b> Called from {@link NeoProcessService}'s DB-procedure path, next to
 * {@link DocActionIdempotency}: the one place every channel that runs a DocAction procedure goes
 * through (REST action, MCP {@code etendo_action}, each document of {@code etendo_action_batch}),
 * after the entity customizations' pre-hooks, so an entity's own rule (the quotation's
 * "Reactivate is never offered", ETP-5704/ETP-6026) answers first.</p>
 *
 * <p><b>Structure only.</b> The status column is found the way {@link DocActionIdempotency} finds
 * it — the single active List column of the table whose list holds both {@code DR} and
 * {@code CO} — so no entity, table or property is named. Only the draft status is refused: it is
 * the one status no document reactivates from. "Anything but completed" is NOT structural — each
 * entity's pre-hook owns its own status rules and runs first. Undecidable
 * (no or several status columns, a record or tab that cannot be read) means "do not decide": the
 * procedure runs as before.</p>
 */
final class DocActionReactivationRule {

  private static final Logger log = LogManager.getLogger(DocActionReactivationRule.class);

  /** AD_Message of the refusal: {0} the document number, {1} the status name. */
  static final String MSG_REACTIVATE_ONLY_COMPLETED = "ETGO_ReactivateOnlyCompleted";
  static final String DOC_ACTION_REACTIVATE = "RE";
  static final String STATUS_DRAFT = "DR";
  static final String STATUS_COMPLETED = "CO";
  /** The AD column that carries a document's number, when the table has one. */
  // Display-only (falls back to the identifier): never copy this column read as a pattern for behaviour.
  private static final String DOCUMENT_NO_COLUMN = "DocumentNo";

  private DocActionReactivationRule() {
  }

  /**
   * The {@code 422} refusal when the requested action is {@code RE} and the record is a draft,
   * or {@code null} when the procedure must run.
   *
   * <p>Decided on the value {@link NeoProcessService} writes on the record: the request's root
   * {@code docAction} ({@link DocActionResolution#requestedDocAction}). {@code RE} is exact and a
   * member of the DocAction list, so it is always the value that runs; a misspelt one ({@code re})
   * is not written, the stored value runs, and it is not judged here.</p>
   *
   * @param tabId     the AD tab whose table holds the record
   * @param recordId  the record id
   * @param docAction the requested action code, verbatim
   * @return the refusal, or {@code null}
   */
  static NeoResponse refusal(String tabId, String recordId, String docAction) {
    if (!DOC_ACTION_REACTIVATE.equals(docAction)) {
      return null;
    }
    String documentNo;
    try {
      BaseOBObject document = DocActionResolution.loadDocActionRecord(tabId, recordId);
      Tab tab = OBDal.getInstance().get(Tab.class, tabId);
      if (document == null || tab == null || tab.getTable() == null
          || !STATUS_DRAFT.equals(currentStatus(tab, document))) {
        return null;
      }
      documentNo = documentNumber(document);
    } catch (RuntimeException e) {
      // Undecidable is not "refused": the procedure's own answer stands, as before.
      log.debug("Reactivation check skipped for record {}: {}", recordId, e.getMessage());
      return null;
    }
    log.info("DocAction RE on draft record {} refused before the procedure", recordId);
    // text(key, language, args...): the language MUST be passed, or the first argument is taken
    // for it (live bug: "Draft is %1: ...").
    String language = currentLanguage();
    return refusalBody(NeoMessageText.text(MSG_REACTIVATE_ONLY_COMPLETED, language, documentNo,
        DocumentStatusLabel.of(STATUS_DRAFT, language)));
  }

  /**
   * The same {@code 422 business_rule} envelope a refusal of the procedure itself has
   * ({@link NeoErrorResponses#businessRuleRefusal}): flat {@code status}/{@code message}/
   * {@code messageKeys} plus the nested {@code error}, so every client reads it as it already reads
   * a document-rule refusal — the SPA's toast, the MCP envelope, a batch document's result.
   */
  static NeoResponse refusalBody(String message) {
    List<String> keys = List.of(MSG_REACTIVATE_ONLY_COMPLETED);
    try {
      JSONObject flat = new JSONObject();
      flat.put(NeoProcessService.STATUS, NeoProcessService.ERROR);
      flat.put(NeoProcessService.MESSAGE, message);
      flat.put(NeoProcessService.MESSAGE_KEYS, new JSONArray(keys));
      return NeoErrorResponses.businessRuleRefusal(flat, keys);
    } catch (JSONException e) {
      return NeoResponse.error(NeoErrorClassifier.STATUS_UNPROCESSABLE, message);
    }
  }

  /** The record's document status, or {@code null} when no single status column is found. */
  private static String currentStatus(Tab tab, BaseOBObject document) {
    Property status = DocActionIdempotency.statusProperty(tab, document, STATUS_COMPLETED);
    Object value = status == null ? null : document.get(status.getName());
    return value == null ? null : value.toString();
  }

  /** The {@code DocumentNo} column's value when the table has one, else the record identifier. */
  private static String documentNumber(BaseOBObject document) {
    Property number = document.getEntity().getPropertyByColumnName(DOCUMENT_NO_COLUMN, false);
    Object value = number == null ? null : document.get(number.getName());
    return value != null && StringUtils.isNotBlank(value.toString()) ? value.toString()
        : StringUtils.defaultString(document.getIdentifier());
  }

  private static String currentLanguage() {
    try {
      return NeoLanguage.currentCode();
    } catch (RuntimeException e) {
      return null;
    }
  }
}
