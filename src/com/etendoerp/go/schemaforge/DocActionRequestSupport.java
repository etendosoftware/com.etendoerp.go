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

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;

/**
 * Reads which value a request runs the {@code DocAction} button with — the parsing a document
 * customization needs to react to one specific value (Reject on a quotation, Reactivate on a sales
 * order) before the button's process runs.
 *
 * <p>Extracted from {@code SalesQuotationHeaderHandler} (ETP-5704) unchanged, so both callers read
 * the value from the same places in the same order:</p>
 * <ol>
 *   <li>{@code fieldValues.documentAction} — the SPA's save-and-process path;</li>
 *   <li>{@code docAction} at the root — the {@code useDocumentAction}/bulk-bar body, and the
 *       {@code actionParameter} {@code etendo_schema} advertises to agents;</li>
 *   <li>{@code documentAction} at the root.</li>
 * </ol>
 *
 * <p>The button is named by its column ({@code DocAction}) or by its field
 * ({@code documentAction}), as {@code NeoButtonActionHelper.findButtonColumn} accepts both. Only a
 * {@code POST} on the ACTION endpoint counts. Both the REST action sub-endpoint and the MCP
 * {@code etendo_action} reach the entity's customization with this same context shape.</p>
 *
 * <p>Pure structure: it names no entity and no value, so any customization may call it.</p>
 */
final class DocActionRequestSupport {

  static final String BUTTON_COLUMN = "DocAction";
  static final String BUTTON_FIELD = "documentAction";
  static final String PARAM_DOC_ACTION = "docAction";

  private DocActionRequestSupport() {
  }

  /**
   * Whether the request runs the {@code DocAction} button with exactly {@code docAction}.
   *
   * @param context   the request
   * @param docAction the button value to look for, e.g. {@code RJ} or {@code RE}
   * @return {@code true} for a POST ACTION on the DocAction button asking for that value
   */
  static boolean requests(NeoContext context, String docAction) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !"POST".equals(context.getHttpMethod())) {
      return false;
    }
    String action = context.getFieldName();
    if (!BUTTON_COLUMN.equals(action) && !BUTTON_FIELD.equals(action)) {
      return false;
    }
    return docAction.equals(requestedDocAction(context.getRequestBody()));
  }

  /**
   * The button value carried by an action body, or {@code null} when it carries none.
   *
   * @param body the action request body; may be {@code null}
   * @return the trimmed value, read in the documented order, or {@code null}
   */
  static String requestedDocAction(JSONObject body) {
    if (body == null) {
      return null;
    }
    JSONObject fieldValues = body.optJSONObject("fieldValues");
    String nested = fieldValues != null
        ? StringUtils.trimToNull(fieldValues.optString(BUTTON_FIELD, null)) : null;
    if (nested != null) {
      return nested;
    }
    String root = StringUtils.trimToNull(body.optString(PARAM_DOC_ACTION, null));
    return root != null ? root
        : StringUtils.trimToNull(body.optString(BUTTON_FIELD, null));
  }
}
