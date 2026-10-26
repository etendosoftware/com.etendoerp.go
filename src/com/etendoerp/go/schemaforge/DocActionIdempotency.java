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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;


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
    return check(tabId, recordId, docAction, false);
  }

  /**
   * A refusal of the procedure, or the {@code alreadyApplied} success when a concurrent request got
   * the record to the target status first: the status is re-read from the database once
   * (the cached instance would still show the old one).
   *
   * @param refusal   what the procedure answered
   * @param tabId     the AD tab whose table holds the record
   * @param recordId  the record id
   * @param docAction the requested action code
   * @return {@code alreadyApplied} when the record is now in the target status, else the refusal
   *         untouched (also for any answer that is not a refusal)
   */
  static NeoResponse orAlreadyApplied(NeoResponse refusal, String tabId, String recordId,
      String docAction) {
    if (refusal == null || refusal.getHttpStatus() != NeoErrorClassifier.STATUS_UNPROCESSABLE) {
      return refusal;
    }
    NeoResponse applied = check(tabId, recordId, docAction, true);
    return applied != null ? applied : refusal;
  }

  private static NeoResponse check(String tabId, String recordId, String docAction,
      boolean refresh) {
    if (!SELF_TARGETING_ACTIONS.contains(docAction)) {
      return null;
    }
    try {
      if (!isAlreadyInStatus(tabId, recordId, docAction, refresh)) {
        return null;
      }
      JSONObject body = new JSONObject();
      body.put(NeoProcessService.STATUS, NeoProcessService.SUCCESS);
      body.put(NeoProcessService.MESSAGE, message());
      body.put("alreadyApplied", true);
      return NeoResponse.ok(body);
    } catch (JSONException | RuntimeException e) {
      // Undecidable is not "applied": let the procedure's own answer stand.
      log.debug("Idempotency check skipped for record {}: {}", recordId, e.getMessage());
      return null;
    }
  }

  private static boolean isAlreadyInStatus(String tabId, String recordId, String status,
      boolean refresh) {
    BaseOBObject document = DocActionResolution.loadDocActionRecord(tabId, recordId);
    Tab tab = OBDal.getInstance().get(Tab.class, tabId);
    if (document == null || tab == null || tab.getTable() == null) {
      return false;
    }
    if (refresh) {
      OBDal.getInstance().refresh(document);
    }
    Property statusProperty = statusProperty(tab, document, status);
    if (statusProperty == null) {
      return false;
    }
    Object current = document.get(statusProperty.getName());
    return current != null && status.equals(current.toString());
  }

  /**
   * The single property of {@code document} that is its document status for {@code status}: an
   * active List column of the table whose reference list holds the draft status and
   * {@code status}. One query over {@code AD_Ref_List} for all the table's List references; none or
   * several candidate columns mean {@code null} (do not decide).
   */
  private static Property statusProperty(Tab tab, BaseOBObject document, String status) {
    Map<String, List<Column>> columnsByReference = listColumnsByReference(tab);
    if (columnsByReference.isEmpty()) {
      return null;
    }
    Map<String, Set<String>> valuesByReference = heldValues(columnsByReference.keySet(), status);
    Property found = null;
    for (Map.Entry<String, List<Column>> entry : columnsByReference.entrySet()) {
      Set<String> held = valuesByReference.get(entry.getKey());
      if (held == null || held.size() != 2) {
        continue;
      }
      for (Column column : entry.getValue()) {
        Property property = document.getEntity().getPropertyByColumnName(column.getDBColumnName(),
            false);
        if (property == null) {
          continue;
        }
        if (found != null) {
          return null; // two candidates: ambiguous
        }
        found = property;
      }
    }
    return found;
  }

  /** The table's active List columns, grouped by their list reference id. */
  private static Map<String, List<Column>> listColumnsByReference(Tab tab) {
    Map<String, List<Column>> columnsByReference = new HashMap<>();
    for (Column column : tab.getTable().getADColumnList()) {
      if (isActiveListColumn(column)) {
        columnsByReference.computeIfAbsent(column.getReferenceSearchKey().getId(),
            id -> new ArrayList<>()).add(column);
      }
    }
    return columnsByReference;
  }

  /** For each list reference, which of the draft status and {@code status} it holds. */
  private static Map<String, Set<String>> heldValues(Set<String> referenceIds, String status) {
    Map<String, Set<String>> valuesByReference = new HashMap<>();
    OBCriteria<org.openbravo.model.ad.domain.List> values = OBDal.getInstance()
        .createCriteria(org.openbravo.model.ad.domain.List.class);
    values.add(Restrictions.in(org.openbravo.model.ad.domain.List.PROPERTY_REFERENCE + ".id",
        referenceIds));
    values.add(Restrictions.eq(org.openbravo.model.ad.domain.List.PROPERTY_ACTIVE, Boolean.TRUE));
    values.add(Restrictions.in(org.openbravo.model.ad.domain.List.PROPERTY_SEARCHKEY,
        DRAFT_STATUS, status));
    for (org.openbravo.model.ad.domain.List value : values.list()) {
      valuesByReference.computeIfAbsent(value.getReference().getId(), id -> new HashSet<>())
          .add(value.getSearchKey());
    }
    return valuesByReference;
  }

  private static boolean isActiveListColumn(Column column) {
    return Boolean.TRUE.equals(column.isActive()) && column.getReference() != null
        && LIST_REFERENCE_ID.equals(column.getReference().getId())
        && column.getReferenceSearchKey() != null;
  }

  private static String message() {
    String text;
    try {
      text = OBMessageUtils.messageBD(MSG_ALREADY_APPLIED);
    } catch (RuntimeException e) {
      // A translation failure must never hide the answer itself.
      text = null;
    }
    return StringUtils.isBlank(text) || StringUtils.equals(text, MSG_ALREADY_APPLIED)
        ? DEFAULT_MESSAGE : text;
  }
}
