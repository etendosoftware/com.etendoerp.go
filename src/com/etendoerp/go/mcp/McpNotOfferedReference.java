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

package com.etendoerp.go.mcp;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoReferenceSelectorValidation;
import com.etendoerp.go.schemaforge.NeoUpdateCalloutRefire;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * The reference checks an MCP write runs after ownership (ETP-6176): the reference must be active
 * and must be one the app's selector offers for the record. Same check as the REST path
 * ({@code NeoWritePreflight}), against the merged record (stored values plus the incoming ones).
 */
final class McpNotOfferedReference {

  private McpNotOfferedReference() {
  }

  /**
   * Refuse a write whose request SETS an inactive reference, or one the app's selector would not
   * offer for the record. On an update, the callouts of the references the request changes are
   * re-fired first (a new product's tax, unit, price), so the selector check judges the record the
   * update leaves behind; caller-sent values always win.
   *
   * @param sfEntity      the entity being written
   * @param adTab         the tab being written
   * @param recordId      the id of the record being updated, {@code null} on create
   * @param parentId      the parent id of a child create, or {@code null}
   * @param fields        the body keyed by DAL property name, holding only what the caller sent
   * @param refireContext the update context whose callouts are re-fired, {@code null} on create
   * @param dalEntityName the DAL entity name written, used by the callout re-fire
   * @return the 422 envelope, or {@code null} when every reference is acceptable
   */
  static JSONObject refuse(SFEntity sfEntity, Tab adTab, String recordId, String parentId,
      JSONObject fields, NeoContext refireContext, String dalEntityName) throws JSONException {
    JSONObject inactive = McpWriteRequestSupport.refuseInactiveReference(adTab, fields);
    if (inactive != null) {
      return inactive;
    }
    if (refireContext != null) {
      NeoUpdateCalloutRefire.apply(refireContext, fields, dalEntityName);
    }
    NeoReferenceSelectorValidation.RefusedReference refused =
        NeoReferenceSelectorValidation.findRefusedReference(sfEntity, adTab, recordId, parentId,
            fields);
    if (refused == null) {
      return null;
    }
    JSONObject error = new JSONObject();
    error.put(McpConstants.KEY_STATUS, McpConstants.STATUS_UNPROCESSABLE);
    error.put(McpConstants.KEY_ERROR, "REFERENCE_NOT_ALLOWED");
    error.put(McpConstants.KEY_DETAIL, refused.detail());
    error.put("field", refused.fieldName());
    error.put("value", refused.value() == null ? JSONObject.NULL : refused.value());
    error.put(McpConstants.KEY_HINT, "Choose a value etendo_selectors returns for '"
        + refused.fieldName() + "' with this record's context.");
    error.put(McpConstants.KEY_SEE_ALSO, McpConstants.SEE_ALSO_WRITING);
    return error;
  }
}
