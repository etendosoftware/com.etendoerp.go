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

import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.util.NeoMessages;

/**
 * Idempotency of a document action (ETP-5783): asking a document to reach the status it is
 * already in is answered {@code 200 alreadyApplied} instead of running the procedure again.
 *
 * <p>Without it a client that retries after a timeout re-runs {@code C_Order_Post} on a completed
 * order and gets a refusal for a request that, in effect, succeeded. Only the "already there" case
 * is treated as success: any other status goes to the procedure exactly as before, so a real
 * problem is never hidden.</p>
 *
 * <p><b>Structure only.</b> The status column is found through the AD, not through a property
 * name: it is the single active List column of the record's table whose reference list holds both
 * the draft status ({@code DR}) and the status the action leads to. The document action's own
 * list never holds {@code DR}, so it cannot be mistaken for it, and each document type may use its
 * own status list (orders do). More or fewer than one such column, or a record the lookup cannot
 * read, means "do not decide": the procedure runs as today.</p>
 *
 * <p>The action-to-status mapping is limited to the actions whose target status is unambiguous:
 * {@code CO} leads to {@code CO}, {@code VO} to {@code VO}, {@code CL} to {@code CL}. Others
 * ({@code RE} is the trap: its letters are a status of their own, "Reversed", yet the action leads
 * to draft) are left to the procedure.</p>
 */
final class DocActionIdempotency {

  private static final Logger log = LogManager.getLogger(DocActionIdempotency.class);

  /** AD_Message search key of the "already in that status" sentence. */
  static final String MSG_ALREADY_APPLIED = "ETGO_DocActionAlreadyApplied";
  private static final String DEFAULT_MESSAGE = "The document is already in the requested status.";
  /** Doc actions whose target status carries the same code. */
  private static final Set<String> SELF_TARGETING_ACTIONS = Set.of("CO", "VO", "CL");
  private static final String DRAFT_STATUS = "DR";
  /** AD_Reference of the List reference type. */
  private static final String LIST_REFERENCE_ID = "17";

  private DocActionIdempotency() {
  }

  /**
   * The {@code 200 alreadyApplied} answer when the record is already in the status
   * {@code docAction} leads to, or {@code null} when the procedure must run.
   *
   * @param tabId     the AD tab whose table holds the record
   * @param recordId  the record id
   * @param docAction the requested action code, verbatim
   * @return the answer, or {@code null}
   */
  static NeoResponse alreadyApplied(String tabId, String recordId, String docAction) {
    if (!SELF_TARGETING_ACTIONS.contains(docAction)) {
      return null;
    }
    try {
      if (!isAlreadyInStatus(tabId, recordId, docAction)) {
        return null;
      }
      JSONObject body = new JSONObject();
      body.put(NeoProcessService.STATUS, NeoProcessService.SUCCESS);
      body.put(NeoProcessService.MESSAGE, message());
      body.put("alreadyApplied", true);
      return NeoResponse.ok(body);
    } catch (JSONException | RuntimeException e) {
      // Undecidable is not "applied": let the procedure speak.
      log.debug("Idempotency check skipped for record {}: {}", recordId, e.getMessage());
      return null;
    }
  }

  private static boolean isAlreadyInStatus(String tabId, String recordId, String status) {
    BaseOBObject record = DocActionResolution.loadDocActionRecord(tabId, recordId);
    Tab tab = OBDal.getInstance().get(Tab.class, tabId);
    if (record == null || tab == null || tab.getTable() == null) {
      return false;
    }
    Property statusProperty = null;
    for (Column column : tab.getTable().getADColumnList()) {
      Property property = isDocumentStatusColumn(column, status)
          ? record.getEntity().getPropertyByColumnName(column.getDBColumnName(), false)
          : null;
      if (property == null) {
        continue;
      }
      if (statusProperty != null) {
        return false; // two candidates: ambiguous
      }
      statusProperty = property;
    }
    if (statusProperty == null) {
      return false;
    }
    Object current = record.get(statusProperty.getName());
    return current != null && status.equals(current.toString());
  }

  /** Active List column whose reference list holds the draft status and {@code status}. */
  private static boolean isDocumentStatusColumn(Column column, String status) {
    Reference list = column.getReferenceSearchKey();
    if (!Boolean.TRUE.equals(column.isActive()) || column.getReference() == null
        || !LIST_REFERENCE_ID.equals(column.getReference().getId()) || list == null) {
      return false;
    }
    OBCriteria<org.openbravo.model.ad.domain.List> values = OBDal.getInstance()
        .createCriteria(org.openbravo.model.ad.domain.List.class);
    values.add(Restrictions.eq(org.openbravo.model.ad.domain.List.PROPERTY_REFERENCE + ".id",
        list.getId()));
    values.add(Restrictions.eq(org.openbravo.model.ad.domain.List.PROPERTY_ACTIVE, Boolean.TRUE));
    values.add(Restrictions.in(org.openbravo.model.ad.domain.List.PROPERTY_SEARCHKEY,
        DRAFT_STATUS, status));
    return values.count() == 2;
  }

  private static String message() {
    String text = NeoMessages.tr(MSG_ALREADY_APPLIED);
    return StringUtils.equals(text, MSG_ALREADY_APPLIED) ? DEFAULT_MESSAGE : text;
  }
}
