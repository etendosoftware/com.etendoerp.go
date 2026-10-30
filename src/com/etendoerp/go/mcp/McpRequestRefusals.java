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

package com.etendoerp.go.mcp;

import java.util.List;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * The 422 refusals of a read request's own arguments: a filter operator or value of the wrong
 * shape, {@code allParents} where it does not apply, a widget range nobody knows. Each is a
 * {@link McpRoutingException} built through {@link McpRoutingException#unprocessable}; they live
 * here so the exception class keeps to the envelope and the routing failures.
 */
final class McpRequestRefusals {

  private McpRequestRefusals() {
  }

  /**
   * A filter used a recognized operator but gave it a value of the wrong shape (ETP-5184).
   *
   * @param key      the filter key
   * @param operator the operator whose value was malformed
   * @param expected one clause describing the shape that was expected
   * @return the exception to throw
   */
  static McpRoutingException malformedFilterOperator(String key, String operator, String expected) {
    return McpRoutingException.unprocessable(
        "Filter '" + key + "' operator '" + operator + "' has a malformed value: " + expected,
        key, List.of(), "Correct the operator's value and retry.");
  }

  /**
   * A filter value that is not of the field's type (ETP-5914): a word on a number, a date that is
   * not {@code YYYY-MM-DD}, a boolean other than {@code true}/{@code false}. It used to reach the
   * query and fail it with a 500, or be cleaned into a filter that matched everything.
   *
   * @param key          the filter key
   * @param expectedType {@code number}, {@code date} or {@code boolean}
   * @param received     the value as sent
   * @return the exception to throw
   */
  static McpRoutingException invalidFilterValue(String key, String expectedType, Object received) {
    JSONObject extras = new JSONObject();
    try {
      extras.put("expectedType", expectedType);
      extras.put("received", received == null ? JSONObject.NULL : received);
    } catch (JSONException e) {
      // a String key and a JSON value cannot fail; the refusal still carries field and detail
    }
    return McpRoutingException.unprocessable(
        "Filter '" + key + "' expects a " + expectedType + " value"
            + ("date".equals(expectedType) ? " (YYYY-MM-DD)" : "") + ", got '" + received + "'.",
        key, List.of(), "Correct the value and retry; nothing was read.")
        .withExtras(extras);
  }

  /**
   * {@code allParents:true} sent together with {@code parentId} (ETP-5852).
   *
   * @return the exception to throw
   */
  static McpRoutingException allParentsWithParent() {
    return McpRoutingException.unprocessable(
        "allParents and parentId contradict each other: parentId reads one parent's records, "
            + "allParents reads them across every parent. Nothing was read.",
        McpConstants.PARAM_ALL_PARENTS, List.of(), "Send only one of the two.");
  }

  /**
   * {@code allParents:true} on an entity that is read only through its parent (ETP-5852).
   *
   * @param specName   the spec
   * @param entityName the entity
   * @return the exception to throw
   */
  static McpRoutingException allParentsNotAllowed(String specName, String entityName) {
    return McpRoutingException.unprocessable(
        "'" + entityName + "' of '" + specName + "' cannot be listed across every parent: it is "
            + "read only through one parent record. Nothing was read.",
        McpConstants.PARAM_ALL_PARENTS, List.of(),
        "Drop allParents and pass parentId. etendo_schema says per entity whether "
            + "allParentsAllowed is true.");
  }

  /**
   * An {@code etendo_widget} {@code range} the widgets do not know (ETP-5852, M-38). It used to
   * fall back to the rolling 12 months in silence.
   *
   * @param widget the widget
   * @param range  the value sent
   * @param valid  the accepted values
   * @return the exception to throw
   */
  static McpRoutingException invalidWidgetRange(String widget, String range, List<String> valid) {
    return McpRoutingException.unprocessable(
        "Widget '" + widget + "' does not know range '" + range + "'. Nothing was computed.",
        "params.range", valid,
        "Retry with one of the values in 'available', or omit range for the widget's default "
            + "period.");
  }
}
