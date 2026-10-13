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

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

/**
 * Resolves which {@code DocAction} a DocAction-style DB procedure ({@code C_Order_Post},
 * {@code M_InOut_Post0}, ...) runs for a call, and the record it is written on.
 *
 * <p>The one place both sides read it from: {@link NeoProcessService} when it executes the
 * procedure ({@code executeDbProcedure} / {@code setDocAction}), and a customization that must
 * decide on the action about to run before it does (ETP-5704, {@code OrderReactivationGuard}
 * through {@link DocActionRequestSupport}). Kept together so the two cannot drift apart;
 * extracted from {@link NeoProcessService} to keep that class within its method budget.</p>
 *
 * <p>Pure structure: it names no entity and no action value.</p>
 */
final class DocActionResolution {

  /** Parameter carrying a list-backed button's chosen value (DocAction style). */
  static final String PARAM_DOC_ACTION = "docAction";
  /** DAL property a DocAction-style DB procedure reads the action to run from. */
  static final String PROPERTY_DOCUMENT_ACTION = "documentAction";

  private DocActionResolution() {
  }

  /**
   * The DocAction value {@code NeoProcessService.executeDbProcedure} writes on the record before
   * it calls a DocAction-style procedure: the {@code docAction} parameter, verbatim, when it is
   * non-blank. Nothing else in the parameters is read for it — in particular not
   * {@code fieldValues}.
   *
   * <p>A customization that must decide on the action about to run (ETP-5704,
   * {@code OrderReactivationGuard}) reads it from this one place instead of re-deriving it.</p>
   *
   * @param params the process parameters (the action request body); may be {@code null}
   * @return the value {@code NeoProcessService.executeDbProcedure} tries to write, or
   *         {@code null} when the call carries none. Trying is not writing: see
   *         {@link #docActionToRun} for the value the procedure actually runs
   */
  static String requestedDocAction(JSONObject params) {
    if (params == null) {
      return null;
    }
    String docAction = params.optString(PARAM_DOC_ACTION, null);
    return docAction != null && !docAction.isBlank() ? docAction : null;
  }

  /**
   * The DocAction value a DocAction-style DB procedure will actually run for this call — what
   * {@code NeoProcessService.executeDbProcedure} leaves on the record before calling it.
   *
   * <p>That is the {@link #requestedDocAction requested} value only when
   * {@code NeoProcessService.setDocAction} manages to write it. {@code BaseOBObject.set}
   * validates the value first
   * ({@code Property.checkIsValidValue}: for a list property, membership in the list, exact and
   * case-sensitive — {@code BaseEnumerateDomainType} throws a {@code ValidationException}
   * otherwise) and then checks the property is writable; {@code setDocAction} swallows any failure,
   * and the procedure runs the record's <em>stored</em> value instead. So {@code "re"},
   * {@code " RE"} or {@code "XX"} run the stored action, not nothing (ETP-5704 review: a guard that
   * took the requested value verbatim let those run a stored {@code RE} unchecked).</p>
   *
   * <p>{@code set} runs three checks before writing: {@code checkIsValidValue},
   * {@code checkDerivedReadable} and {@code checkIsWritable}. This mirrors the first and the last;
   * {@code checkDerivedReadable} is deliberately left out because it is a no-op in admin mode,
   * where both the process and its callers run. It never writes. A customization
   * guarding on the action about to run (ETP-5704, {@code OrderReactivationGuard}) reads it from
   * the same place the process does.</p>
   *
   * @param tabId    the AD tab whose table holds the record (the process's {@code inpTabId})
   * @param recordId the record id
   * @param params   the process parameters; may be {@code null}
   * @return the value that will run, or {@code null} when nothing is stored
   * @throws DocActionRecordNotFoundException when the tab resolves but no record has this id — the
   *                               request names a record that does not exist, so nothing will run
   * @throws IllegalStateException when the tab, its table or the record's {@code documentAction}
   *                               property cannot be resolved — the action that will run is then
   *                               unknown
   * @throws RuntimeException      when the record cannot be loaded — unlike {@code setDocAction},
   *                               a caller deciding on this value has to know it could not be read
   */
  static String docActionToRun(String tabId, String recordId, JSONObject params) {
    Tab tab = tabId != null ? OBDal.getInstance().get(Tab.class, tabId) : null;
    if (tab == null || tab.getTable() == null || recordId == null) {
      throw new IllegalStateException("Cannot resolve the DocAction record " + recordId
          + " through tab " + tabId);
    }
    BaseOBObject rec = OBDal.getInstance().get(tab.getTable().getName(), recordId);
    if (rec == null) {
      throw new DocActionRecordNotFoundException(recordId);
    }
    if (!rec.getEntity().hasProperty(PROPERTY_DOCUMENT_ACTION)) {
      throw new IllegalStateException("Record " + recordId + " of " + rec.getEntity().getName()
          + " has no DocAction");
    }
    String requested = requestedDocAction(params);
    if (requested != null && isWritableDocAction(rec, requested)) {
      return requested;
    }
    Object stored = rec.get(PROPERTY_DOCUMENT_ACTION);
    return stored != null ? stored.toString() : null;
  }

  /**
   * Whether {@code rec.set(documentAction, value)} would accept the value: the validation and the
   * writability check {@code BaseOBObject.set} runs before it stores anything. Any failure counts
   * as "not written", exactly as {@code NeoProcessService.setDocAction} treats it.
   */
  private static boolean isWritableDocAction(BaseOBObject rec, String value) {
    try {
      Property property = rec.getEntity().getProperty(PROPERTY_DOCUMENT_ACTION);
      property.checkIsValidValue(value);
      property.checkIsWritable();
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * Raised by {@link #docActionToRun} when the tab resolves but the record does not exist — a
   * caller's "not found", distinct from a lookup it cannot trust.
   */
  static final class DocActionRecordNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DocActionRecordNotFoundException(String recordId) {
      super("No record " + recordId + " to run a DocAction on");
    }
  }

  /**
   * The record a DocAction is written on, as {@code NeoProcessService.setDocAction} resolves it:
   * through the tab's table, and only when its entity has a {@code documentAction} property.
   *
   * @param tabId    the AD tab whose table holds the record
   * @param recordId the record id
   * @return the record, or {@code null} when the tab, its table, the record or the property is
   *         absent
   */
  static BaseOBObject loadDocActionRecord(String tabId, String recordId) {
    Tab tab = OBDal.getInstance().get(Tab.class, tabId);
    if (tab == null || tab.getTable() == null) {
      return null;
    }
    String dalEntityName = tab.getTable().getName();
    BaseOBObject rec = OBDal.getInstance().get(dalEntityName, recordId);
    return rec != null && rec.getEntity().hasProperty(PROPERTY_DOCUMENT_ACTION) ? rec : null;
  }
}
