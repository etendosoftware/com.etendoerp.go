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

import java.util.Map;
import java.util.TreeSet;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.datamodel.Column;

import com.etendoerp.go.schemaforge.NeoSelectorService;

/**
 * What {@code neo_schema} says about a button column: whether it is one, whether the current
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
      fieldObj.put(McpSchemaFieldBuilder.KEY_INVOKE_VIA, "neo_action");
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
    org.openbravo.model.ad.domain.Reference listRef = col.getReferenceSearchKey();
    if (listRef == null) {
      return;
    }
    Map<String, String> labels = NeoSelectorService.getListLabels((String) listRef.getId());
    if (labels == null || labels.isEmpty()) {
      return;
    }
    JSONArray values = new JSONArray();
    for (String value : new TreeSet<>(labels.keySet())) {
      JSONObject entry = new JSONObject();
      entry.put("value", value);
      entry.put("label", labels.get(value));
      values.put(entry);
    }
    fieldObj.put(McpConstants.KEY_ACTION_VALUES, values);
    fieldObj.put(McpConstants.KEY_ACTION_PARAMETER, McpConstants.PARAM_DOC_ACTION);
  }
}
