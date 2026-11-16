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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.time.LocalDate;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.module.remittance.RemittanceType;

/**
 * ETP-6148 — the remittance name suggestion ({@link RemittanceNameSuggestion}) as the remittance
 * header gives it: the answer to its interactive callouts, and the name a create without one gets.
 * Split out of {@link RemittanceHeaderHandler}; the callout body, the type and the language are
 * still read through the handler's seams.
 */
final class RemittanceNameCallout {

  private static final Logger log = LogManager.getLogger(RemittanceNameCallout.class);

  private RemittanceNameCallout() {
  }

  /**
   * Create: when the body has a due date and no name, use the suggestion.
   *
   * @param handler the remittance header customization (its seams)
   * @param body    the create body, mutated in place
   * @throws JSONException if the name cannot be written
   */
  static void fillSuggestedName(RemittanceHeaderHandler handler, JSONObject body)
      throws JSONException {
    Object raw = body.opt(RemittanceHeaderHandler.FIELD_NAME);
    if (raw != null && !JSONObject.NULL.equals(raw) && StringUtils.isNotBlank(raw.toString())) {
      return;
    }
    LocalDate dueDate = RemittanceNameSuggestion.parseDay(
        stringOf(body.opt(RemittanceHeaderHandler.FIELD_DUE_DATE)));
    if (dueDate == null) {
      return;
    }
    body.put(RemittanceHeaderHandler.FIELD_NAME, suggestion(handler, dueDate,
        RemittanceHeaderHandler.idOf(body.opt(RemittanceHeaderHandler.FIELD_TYPE))));
  }

  /**
   * The answer to an interactive callout: the name suggestion when {@code dueDate} or
   * {@code remittanceType} changed on a new remittance whose name is blank or still a suggestion;
   * the empty answer otherwise. Total: any failure answers empty.
   *
   * @param handler the remittance header customization (its seams)
   * @return the callout answer
   */
  static NeoResponse answer(RemittanceHeaderHandler handler) {
    try {
      JSONObject request = handler.readCalloutBody();
      String name = request != null ? suggestedName(handler, request) : null;
      if (name == null) {
        return emptyCalloutResult();
      }
      JSONObject update = new JSONObject();
      update.put("value", name);
      update.put("suggested", true);
      JSONObject response = emptyCalloutResult().getBody();
      response.getJSONObject("updates").put(RemittanceHeaderHandler.FIELD_NAME, update);
      return NeoResponse.ok(response);
    } catch (Exception e) {
      log.debug("remittance callout: no name suggested: {}", e.getMessage());
      return emptyCalloutResult();
    }
  }

  /**
   * The name to suggest for a callout {@code request}, or {@code null} when none applies: another
   * field changed, an existing remittance, a name the user typed, no due date, or the suggestion
   * the form already holds.
   */
  private static String suggestedName(RemittanceHeaderHandler handler, JSONObject request) {
    String field = request.optString("field", "");
    boolean dueDateChanged = RemittanceHeaderSelectors.matches(field,
        RemittanceHeaderHandler.FIELD_DUE_DATE, RemittanceHeaderHandler.COLUMN_DUE_DATE);
    boolean typeChanged = RemittanceHeaderSelectors.matches(field,
        RemittanceHeaderHandler.FIELD_TYPE, RemittanceHeaderHandler.COLUMN_TYPE_FOR_CALLOUT);
    if (!dueDateChanged && !typeChanged) {
      return null;
    }
    JSONObject form = request.optJSONObject("formState");
    if (form == null) {
      form = new JSONObject();
    }
    if (StringUtils.isNotBlank(RemittanceHeaderHandler.idOf(form.opt("id")))) {
      return null; // an existing remittance keeps its name
    }
    String currentName = stringOf(form.opt(RemittanceHeaderHandler.FIELD_NAME));
    if (StringUtils.isNotBlank(currentName)
        && !RemittanceNameSuggestion.isSuggestion(currentName)) {
      return null; // a name the user typed
    }
    Object value = request.opt("value");
    LocalDate dueDate = RemittanceNameSuggestion.parseDay(
        stringOf(dueDateChanged ? value : form.opt(RemittanceHeaderHandler.FIELD_DUE_DATE)));
    if (dueDate == null) {
      return null;
    }
    String typeId = RemittanceHeaderHandler.idOf(
        typeChanged ? value : form.opt(RemittanceHeaderHandler.FIELD_TYPE));
    String name;
    OBContext.setAdminMode(true);
    try {
      name = suggestion(handler, dueDate, typeId);
    } finally {
      OBContext.restorePreviousMode();
    }
    return name.equals(StringUtils.trimToEmpty(currentName)) ? null : name;
  }

  private static String suggestion(RemittanceHeaderHandler handler, LocalDate dueDate,
      String typeId) {
    RemittanceType type = typeId != null ? handler.loadType(typeId) : null;
    return RemittanceNameSuggestion.suggest(dueDate, RemittanceNameSuggestion.kindOf(type),
        handler.currentLanguage());
  }

  /**
   * The text of a body value, {@code null} for an absent or JSON null one.
   *
   * @param value the value
   * @return its text, or {@code null}
   */
  static String stringOf(Object value) {
    return value == null || JSONObject.NULL.equals(value) ? null : value.toString();
  }

  private static NeoResponse emptyCalloutResult() {
    try {
      JSONObject body = new JSONObject();
      body.put("updates", new JSONObject());
      body.put("combos", new JSONObject());
      body.put("messages", new JSONArray());
      return NeoResponse.ok(body);
    } catch (JSONException e) {
      return NeoResponse.ok(new JSONObject());
    }
  }
}
