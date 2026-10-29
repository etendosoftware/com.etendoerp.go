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

package com.etendoerp.go.schemaforge.webhooks;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * ETP-5952 — the external MCP feedback bug router ({@code etendo-bots}, {@code bin/bug-router})
 * records what it did with one {@code etendo_feedback} report: the cell it classified it into, the
 * person it assigned (if it was sure), the public GitHub pre-ticket and, when it opened one, the
 * private Jira task.
 *
 * <pre>POST /webhooks/McpFeedbackTriage  {"id", "cell", "issue", "task"?, "assignee"?}</pre>
 *
 * <p><b>Validated before anything is written.</b> A value that fails its format, or does not fit
 * its column, answers {@code error} and writes nothing.
 *
 * <p><b>Write-once.</b> The {@code UPDATE} is conditioned on {@code row_type = 'feedback' AND
 * triaged IS NULL}, so a row already triaged is never overwritten, not even by a retry of the same
 * call. The answer is {@code updated = "true"}, or {@code "false"} with a {@code reason}: {@value
 * #REASON_ALREADY_TRIAGED}, {@value #REASON_NOT_FEEDBACK} or {@value #REASON_NOT_FOUND}.
 *
 * <p><b>Any client.</b> Like {@link McpFeedbackPending}, plain SQL on the request's connection,
 * so the bot user's own client does not limit which rows it can mark. {@code STATUS}, {@code
 * REVIEWED_BY} and {@code ISACTIVE} belong to the human review and are never touched.
 */
public class McpFeedbackTriage extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(McpFeedbackTriage.class);

  static final String PARAM_ID = "id";
  static final String PARAM_CELL = "cell";
  static final String PARAM_ISSUE = "issue";
  static final String PARAM_TASK = "task";
  static final String PARAM_ASSIGNEE = "assignee";

  static final String KEY_ERROR = "error";
  static final String KEY_UPDATED = "updated";
  static final String KEY_REASON = "reason";

  static final String REASON_ALREADY_TRIAGED = "already_triaged";
  static final String REASON_NOT_FEEDBACK = "not_feedback";
  static final String REASON_NOT_FOUND = "not_found";

  private static final String ROW_TYPE_FEEDBACK = "feedback";
  /** Audit user for a write without a resolved caller; never the case behind the webhook servlet. */
  private static final String SYSTEM_USER = "100";

  private static final Pattern CELL = Pattern.compile("Celula[A-Za-z]+");
  /** A GitHub login: letters, digits and hyphens, at most 39 characters. */
  private static final Pattern ASSIGNEE = Pattern.compile("[A-Za-z0-9-]{1,39}");
  private static final Pattern ISSUE =
      Pattern.compile("https://github\\.com/etendosoftware/[A-Za-z0-9._-]+/issues/\\d+");
  private static final Pattern TASK = Pattern.compile("[A-Z][A-Z0-9]+-\\d+");

  /** Widths of the target columns, so a value that matches its format still cannot overflow. */
  private static final int CELL_WIDTH = 60;
  private static final int ISSUE_WIDTH = 200;
  private static final int TASK_WIDTH = 60;

  private static final String UPDATE_SQL = "UPDATE etgo_mcp_usage SET triage_cell = ?,"
      + " triage_assignee = ?, triage_issue = ?, triage_task = ?, triaged = now(),"
      + " updated = now(), updatedby = ?"
      + " WHERE etgo_mcp_usage_id = ? AND row_type = 'feedback' AND triaged IS NULL";
  private static final String ROW_STATE_SQL =
      "SELECT row_type FROM etgo_mcp_usage WHERE etgo_mcp_usage_id = ?";

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    String id = StringUtils.trimToNull(parameter.get(PARAM_ID));
    String cell = StringUtils.trimToNull(parameter.get(PARAM_CELL));
    String issue = StringUtils.trimToNull(parameter.get(PARAM_ISSUE));
    String task = StringUtils.trimToNull(parameter.get(PARAM_TASK));
    String assignee = StringUtils.trimToNull(parameter.get(PARAM_ASSIGNEE));

    String invalid = validate(id, cell, issue, task, assignee);
    if (invalid != null) {
      responseVars.put(KEY_ERROR, invalid);
      return;
    }
    try {
      Connection connection = OBDal.getInstance().getConnection();
      if (markTriaged(connection, id, new String[] { cell, assignee, issue, task })) {
        responseVars.put(KEY_UPDATED, "true");
        log.info("MCP feedback triaged: usageId={} cell={} task={} assigned={}", id, cell, task,
            assignee != null);
        return;
      }
      responseVars.put(KEY_UPDATED, "false");
      responseVars.put(KEY_REASON, reasonNotUpdated(connection, id));
    } catch (Exception e) {
      log.error("Error in McpFeedbackTriage for usageId {}", id, e);
      responseVars.put(KEY_ERROR, "Could not record the triage of " + id);
    }
  }

  /**
   * @return the first validation failure, phrased for the caller, or {@code null} when every value
   *         is acceptable; {@code task} and {@code assignee} may be {@code null}, the other three
   *         may not
   */
  static String validate(String id, String cell, String issue, String task, String assignee) {
    if (id == null) {
      return "id is required";
    }
    if (!fits(cell, CELL, CELL_WIDTH)) {
      return "cell must match Celula[A-Za-z]+ (at most " + CELL_WIDTH + " characters)";
    }
    if (!fits(issue, ISSUE, ISSUE_WIDTH)) {
      return "issue must be a https://github.com/etendosoftware/<repo>/issues/<number> URL";
    }
    if (task != null && !fits(task, TASK, TASK_WIDTH)) {
      return "task must be a Jira key such as ETP-1234";
    }
    if (assignee != null && !ASSIGNEE.matcher(assignee).matches()) {
      return "assignee must be a GitHub login (letters, digits and hyphens, at most 39)";
    }
    return null;
  }

  private static boolean fits(String value, Pattern format, int width) {
    return value != null && value.length() <= width && format.matcher(value).matches();
  }

  private static boolean markTriaged(Connection connection, String id, String[] values)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
      int index = 1;
      for (String value : values) {
        statement.setString(index++, value);
      }
      statement.setString(index++, callerId());
      statement.setString(index, id);
      return statement.executeUpdate() == 1;
    }
  }

  /** Why the conditioned {@code UPDATE} touched nothing; read after it, on the same connection. */
  private static String reasonNotUpdated(Connection connection, String id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ROW_STATE_SQL)) {
      statement.setString(1, id);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          return REASON_NOT_FOUND;
        }
        return ROW_TYPE_FEEDBACK.equals(rs.getString(1)) ? REASON_ALREADY_TRIAGED
            : REASON_NOT_FEEDBACK;
      }
    }
  }

  private static String callerId() {
    OBContext context = OBContext.getOBContext();
    return context != null && context.getUser() != null ? context.getUser().getId() : SYSTEM_USER;
  }
}
