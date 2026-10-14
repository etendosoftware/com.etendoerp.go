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
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;

/**
 * Reads which value a request runs the {@code DocAction} button with, for a document customization
 * that has to react to one specific value before the button's process runs.
 *
 * <p><b>Two questions, two answers (ETP-5704).</b></p>
 * <ul>
 *   <li>{@link #effectiveDocAction} — the value the process will <em>execute</em>. A guard that
 *       refuses an action must decide on this and nothing else, or a body that says one thing in
 *       one key and another in the key the process reads gets past it. It delegates to
 *       {@link DocActionResolution#docActionToRun}: root {@code docAction} when the DAL would
 *       accept writing it on the record (non-blank, a member of the list, exact case), otherwise
 *       the record's stored {@code DocAction}, which the DB procedure then runs.
 *       {@link NeoProcessService} resolves the value and the record through that same class when
 *       it writes it, so the guard and the process cannot drift apart.</li>
 *   <li>{@link #requests} — the value the caller <em>asked for</em>, used to re-route a request to
 *       a different flow (Reject on a quotation). Root {@code docAction} wins, because it is what
 *       the process would run; the older spellings {@code fieldValues.documentAction} and root
 *       {@code documentAction} are honoured only when it is absent. The stored value never counts:
 *       a body that names no action does not express an intent to re-route.</li>
 * </ul>
 *
 * <p>The button is named by its column ({@code DocAction}) or by its field
 * ({@code documentAction}) — the two names {@code NeoButtonActionHelper.findButtonColumn} matches
 * for it. Only a {@code POST} on the ACTION endpoint counts. Both the REST action sub-endpoint and
 * the MCP {@code etendo_action} reach the entity's customization with this same context shape.</p>
 *
 * <p>Pure structure: it names no entity, so any customization may call it.</p>
 */
final class DocActionRequestSupport {

  static final String BUTTON_COLUMN = "DocAction";
  static final String BUTTON_FIELD = "documentAction";

  private DocActionRequestSupport() {
  }

  /**
   * Whether the request is a POST on the ACTION endpoint naming the {@code DocAction} button.
   *
   * @param context the request
   * @return {@code true} when the request runs the DocAction button
   */
  static boolean isDocActionButtonPost(NeoContext context) {
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !"POST".equals(context.getHttpMethod())) {
      return false;
    }
    String action = context.getFieldName();
    return BUTTON_COLUMN.equals(action) || BUTTON_FIELD.equals(action);
  }

  /**
   * The DocAction value the button's process will execute for this request: the request's own
   * root {@code docAction} when the process can write it on the record, otherwise the record's
   * stored value — see {@link DocActionResolution#docActionToRun}.
   *
   * @param context the request; its record and tab identify the stored value
   * @return the value, or {@code null} when nothing is stored
   * @throws DocActionResolution.DocActionRecordNotFoundException when no record has this id
   * @throws RuntimeException when the record cannot be loaded, or when the tab or the record's
   *                          DocAction cannot be resolved — the action that will run is then
   *                          unknown, and a caller that guards on it must fail closed
   */
  static String effectiveDocAction(NeoContext context) {
    OBContext.setAdminMode(true);
    try {
      return DocActionResolution.docActionToRun(tabIdOf(context), context.getRecordId(),
          context.getRequestBody());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Whether the caller asks the {@code DocAction} button for exactly {@code docAction} — see the
   * class documentation for which keys count and in which order.
   *
   * @param context   the request
   * @param docAction the button value to look for, e.g. {@code RJ}
   * @return {@code true} for a POST ACTION on the DocAction button asking for that value
   */
  static boolean requests(NeoContext context, String docAction) {
    return isDocActionButtonPost(context)
        && docAction.equals(requestedDocAction(context.getRequestBody()));
  }

  /**
   * The button value the caller asked for, or {@code null} when the body names none.
   *
   * @param body the action request body; may be {@code null}
   * @return the trimmed value: root {@code docAction}, else {@code fieldValues.documentAction},
   *         else root {@code documentAction}
   */
  static String requestedDocAction(JSONObject body) {
    if (body == null) {
      return null;
    }
    String executed = StringUtils.trimToNull(DocActionResolution.requestedDocAction(body));
    if (executed != null) {
      return executed;
    }
    JSONObject fieldValues = body.optJSONObject("fieldValues");
    String nested = fieldValues != null
        ? StringUtils.trimToNull(fieldValues.optString(BUTTON_FIELD, null)) : null;
    return nested != null ? nested
        : StringUtils.trimToNull(body.optString(BUTTON_FIELD, null));
  }

  /** The tab the button process resolves the record through, as the action helper does. */
  private static String tabIdOf(NeoContext context) {
    Tab tab = context.getSfEntity() != null ? context.getSfEntity().getADTab() : null;
    if (tab == null) {
      tab = context.getAdTab();
    }
    return tab != null ? tab.getId() : null;
  }
}
