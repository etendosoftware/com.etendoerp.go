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

import java.util.Comparator;
import java.util.Map;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.ProcessParameter;

import com.etendoerp.go.schemaforge.NeoSelectorService;

/**
 * What {@code etendo_schema} says about a button column: whether it is one, whether the current
 * surface can invoke it, which values it accepts, and whether invoking it is business-critical.
 *
 * <p>Split out of {@link McpSchemaFieldBuilder} under Sonar's class-size rule (S1448). The four
 * belong together because they answer one question between them - can an agent press this, and
 * with what - and because getting any of them wrong fails the same way: a button advertised as
 * invokable that answers 400, or one hidden that a role legitimately holds.</p>
 */
final class McpSchemaActionFields {

  /** The one column name that makes an action business-critical on its name alone. */
  private static final String COLUMN_POSTED = "Posted";

  private McpSchemaActionFields() {
    throw new IllegalStateException("Utility class");
  }

  /**
   * Whether this AD column is a button.
   *
   * @param col the AD column
   * @return {@code true} when its reference maps to the button type
   */
  static boolean isButtonColumn(Column col) {
    String refId = col.getReference() != null ? (String) col.getReference().getId() : null;
    return McpSchemaFieldBuilder.TYPE_BUTTON.equals(McpSchemaFieldBuilder.mapColumnType(refId));
  }

  /**
   * State whether the button can actually be invoked, and if not, why.
   *
   * <p>The reason is carried rather than the field being dropped: an agent that can see the
   * button and is told nothing would retry, whereas one told "no process" stops.</p>
   *
   * @param fieldObj    the field being described, modified in place
   * @param visibility  the curated visibility
   * @param hasProcess  whether a process is wired behind the column
   * @param hiddenInTab whether AD hides the button in the tab
   * @throws JSONException if the field cannot be written
   */
  static void addInvokability(JSONObject fieldObj, String visibility, boolean hasProcess,
      boolean hiddenInTab) throws JSONException {
    String blocker = null;
    if (McpSchemaFieldBuilder.VISIBILITY_DISCARDED.equals(visibility)) {
      blocker = "discarded: this action is not part of the curated agent surface for this window";
    } else if (hiddenInTab) {
      blocker = "hidden: AD does not display this button in the tab, so it is an internal flag "
          + "rather than a user-facing action";
    } else if (!hasProcess) {
      blocker = "no process: the AD button column has no process wired behind it";
    }
    if (blocker == null) {
      fieldObj.put(McpSchemaFieldBuilder.KEY_INVOKE_VIA, "etendo_action");
      return;
    }
    fieldObj.put(McpSchemaFieldBuilder.KEY_INVOKABLE, false);
    fieldObj.put(McpSchemaFieldBuilder.KEY_NOT_INVOKABLE_REASON, blocker);
  }

  /**
   * Whether invoking this action changes something a caller should not trigger casually.
   *
   * @param fieldObj  the field being described
   * @param dbColName the AD column name
   * @return {@code true} when the action is business-critical
   */
  static boolean isCriticalAction(JSONObject fieldObj, String dbColName) {
    return fieldObj.has(McpConstants.KEY_ACTION_PARAMETER)
        || COLUMN_POSTED.equalsIgnoreCase(dbColName);
  }

  /**
   * Attach the values the action accepts, read from the column's AD reference list.
   *
   * @param fieldObj the field being described, modified in place
   * @param col      the AD column
   * @throws JSONException if the values cannot be written
   */
  static void addActionValues(JSONObject fieldObj, Column col) throws JSONException {
    JSONArray values = listOptions(col.getReferenceSearchKey());
    if (values == null) {
      return;
    }
    fieldObj.put(McpConstants.KEY_ACTION_VALUES, values);
    fieldObj.put(McpConstants.KEY_ACTION_PARAMETER, McpConstants.PARAM_DOC_ACTION);
  }

  /**
   * The values of an AD list reference as {@code [{value, label}]}, sorted by value, with the
   * session-language labels; {@code null} for no reference or an empty list. One builder for the
   * three places that publish a list: button values, list-field options and process parameters.
   */
  private static JSONArray listOptions(org.openbravo.model.ad.domain.Reference listRef)
      throws JSONException {
    if (listRef == null) {
      return null;
    }
    Map<String, String> labels = NeoSelectorService.getListLabels((String) listRef.getId());
    if (labels == null || labels.isEmpty()) {
      return null;
    }
    JSONArray options = new JSONArray();
    for (String value : new TreeSet<>(labels.keySet())) {
      options.put(new JSONObject().put("value", value).put(McpConstants.KEY_LABEL, labels.get(value)));
    }
    return options;
  }

  /** Key of the values a {@code list} field can hold (ETP-5852). */
  static final String KEY_OPTIONS = "options";

  /**
   * ETP-5852: attach {@code options:[{value, label}]} to a {@code list} field, from the column's AD
   * reference list with the session-language labels — the same source {@link #addActionValues}
   * reads for buttons. A list field used to publish only {@code type:"list"}, so an agent read
   * {@code status:"N"} or {@code documentStatus:"DR"} with no way to know what the code meant;
   * production agents took the period control's {@code openClose} button value for the period's
   * state and inverted open and closed. A column without a reference list, or an empty one, gets
   * no key.
   *
   * @param fieldObj the field being described, modified in place
   * @param col      the AD column
   * @throws JSONException if the values cannot be written
   */
  static void addListOptions(JSONObject fieldObj, Column col) throws JSONException {
    JSONArray options = listOptions(col.getReferenceSearchKey());
    if (options != null) {
      fieldObj.put(KEY_OPTIONS, options);
    }
  }

  /** Key of the parameters a Classic process button reads (ETP-5848). */
  static final String KEY_PROCESS_PARAMETERS = "processParameters";

  /**
   * ETP-5848: attach {@code processParameters:[{name, label, type, required, default?, options?}]}
   * to a button whose Classic process reads parameters of its own ({@code AD_Process_Para}). The
   * button listed only its process name, so an agent pressing Internal Consumption's
   * {@code processNow} learnt of its mandatory {@code action} (CO / VO) from a 400 and guessed the
   * value. {@code name} is the key {@code etendo_action} takes in {@code parameters};
   * {@code required} is what {@code NeoProcessService} enforces: mandatory with no default. A
   * process with no active parameter gets no key.
   *
   * @param fieldObj the button being described, modified in place
   * @param process  the Classic process behind it
   * @throws JSONException if the parameters cannot be written
   */
  static void addProcessParameters(JSONObject fieldObj, Process process) throws JSONException {
    JSONArray out = new JSONArray();
    process.getADProcessParameterList().stream()
        .filter(p -> Boolean.TRUE.equals(p.isActive()) && p.getDBColumnName() != null)
        .sorted(Comparator.comparing(ProcessParameter::getSequenceNumber,
            Comparator.nullsLast(Comparator.naturalOrder())))
        .forEach(p -> out.put(describe(p)));
    if (out.length() > 0) {
      fieldObj.put(KEY_PROCESS_PARAMETERS, out);
    }
  }

  private static JSONObject describe(ProcessParameter param) {
    try {
      String refId = param.getReference() != null ? (String) param.getReference().getId() : null;
      boolean hasDefault = StringUtils.isNotBlank(param.getDefaultValue());
      JSONObject entry = new JSONObject()
          .put("name", param.getDBColumnName())
          .put(McpConstants.KEY_LABEL, StringUtils.defaultString(param.getName()))
          .put("type", McpSchemaFieldBuilder.mapColumnType(refId))
          .put("required", Boolean.TRUE.equals(param.isMandatory()) && !hasDefault);
      if (hasDefault) {
        entry.put("default", param.getDefaultValue());
      }
      JSONArray options = listOptions(param.getReferenceSearchKey());
      if (options != null) {
        entry.put(KEY_OPTIONS, options);
      }
      return entry;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }
}
