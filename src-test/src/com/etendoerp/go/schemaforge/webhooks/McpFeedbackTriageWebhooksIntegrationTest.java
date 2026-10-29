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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.webhookevents.services.WebhookServiceHandler;

/**
 * Real-database specs for the two ETP-5952 webhooks the external triage bot polls:
 * {@link McpFeedbackPending} and {@link McpFeedbackTriage}.
 *
 * <p>The point a mock cannot make is the tenant one. {@code WebhookServiceHandler} binds the
 * {@code OBContext} of the bot's token, which belongs to ONE client, while the bot has to see and
 * mark the feedback of EVERY client. So each spec runs the webhook under a user of one tenant
 * against rows of another, on the real table.
 *
 * <p>The access specs drive the real {@link WebhookServiceHandler} with this module's webhook
 * definitions, so they prove the shipped definitions grant nothing by themselves.
 *
 * <p>Nothing is committed: fixtures, webhook writes and reads share the request connection, and
 * {@link #rollback()} throws them all away.
 *
 * @covers com.etendoerp.go.schemaforge.webhooks.McpFeedbackPending
 * @covers com.etendoerp.go.schemaforge.webhooks.McpFeedbackTriage
 */
public class McpFeedbackTriageWebhooksIntegrationTest extends OBBaseTest {

  private static final String PENDING_WEBHOOK = "McpFeedbackPending";
  private static final String SF_LIST_WINDOWS_ID = "3D3FC3280BB44A46843E65422620671A";
  private static final String PENDING_ID = "43B3F00FB047480380992AA7AE689183";
  private static final String FEEDBACK = "feedback";
  private static final String TOOL_CALL = "tool_call";
  private static final String PAYLOAD = "{\"summary\":\"etp5952 fixture\"}";
  private static final String CELL = "CelulaPlataforma";
  private static final String ISSUE = "https://github.com/etendosoftware/etendo-ai/issues/42";
  private static final String TASK = "ETP-5952";
  private static final String ASSIGNEE = "octo-cat";
  private static final String TRUE = "true";
  private static final String FALSE = "false";
  /** Every fixture is older than any real row, so it heads the oldest-first queue. */
  private static final LocalDateTime BASE = LocalDateTime.of(2000, 1, 1, 0, 0);

  /** The caller: a user of {@link #callerClient}, as the bot's token would be. */
  private String callerUserRoleId;
  private String callerUser;
  private String callerRole;
  private String callerClient;
  /** A tenant the caller does not belong to. */
  private String otherClient;

  @Before
  public void pickTenants() throws SQLException {
    OBContext.setOBContext("100", "0", "0", "0");
    try (PreparedStatement st = connection().prepareStatement(
        "SELECT ur.ad_user_roles_id, ur.ad_user_id, ur.ad_role_id, ur.ad_client_id"
            + " FROM ad_user_roles ur JOIN ad_role r ON r.ad_role_id = ur.ad_role_id"
            + " JOIN ad_user u ON u.ad_user_id = ur.ad_user_id"
            + " WHERE ur.ad_client_id <> '0' AND ur.isactive = 'Y' AND r.isactive = 'Y'"
            + " AND u.isactive = 'Y' ORDER BY ur.ad_client_id, ur.ad_user_roles_id LIMIT 1");
        ResultSet rs = st.executeQuery()) {
      assertTrue("The test needs a tenant user with an active role", rs.next());
      callerUserRoleId = rs.getString(1);
      callerUser = rs.getString(2);
      callerRole = rs.getString(3);
      callerClient = rs.getString(4);
    }
    try (PreparedStatement st = connection().prepareStatement(
        "SELECT ad_client_id FROM ad_client WHERE ad_client_id NOT IN ('0', ?)"
            + " ORDER BY ad_client_id LIMIT 1")) {
      st.setString(1, callerClient);
      try (ResultSet rs = st.executeQuery()) {
        assertTrue("The test needs a second tenant", rs.next());
        otherClient = rs.getString(1);
      }
    }
  }

  @After
  public void rollback() {
    OBDal.getInstance().rollbackAndClose();
    OBContext.setOBContext((OBContext) null);
  }

  // ── McpFeedbackPending ──────────────────────────────────────────────────────────────────────

  @Test
  public void pendingListsUntriagedFeedbackOfEveryClientOldestFirst() throws Exception {
    String older = insertRow(otherClient, FEEDBACK, PAYLOAD, 2, false);
    String newer = insertRow(callerClient, FEEDBACK, PAYLOAD, 3, false);
    String triaged = insertRow(otherClient, FEEDBACK, PAYLOAD, 0, true);
    String toolCall = insertRow(otherClient, TOOL_CALL, null, 0, false);
    String rejected = insertRow(otherClient, FEEDBACK, null, 1, false);

    JSONArray rows = pending();

    List<String> ids = ids(rows);
    assertEquals("The oldest pending report heads the queue, whatever its client", older,
        ids.get(0));
    assertEquals(newer, ids.get(1));
    assertFalse("A triaged row is not pending", ids.contains(triaged));
    assertFalse("A tool call is not feedback", ids.contains(toolCall));
    assertFalse("A rejected report has no payload to triage", ids.contains(rejected));

    JSONObject first = rows.getJSONObject(0);
    assertEquals(otherClient, first.getString("ad_client_id"));
    assertNotEquals("The client name is resolved", JSONObject.NULL, first.get("client"));
    assertEquals(PAYLOAD, first.getString("payload"));
    assertEquals("2000-01-01T00:00:02.000", first.getString("created"));
    for (String column : new String[] { "triage_cell", "triage_assignee", "triage_issue",
        "triage_task", "triaged" }) {
      assertEquals(column + " is listed, empty while pending", JSONObject.NULL, first.get(column));
    }
  }

  @Test
  public void pendingDefaultsToTwentyAndCapsAtOneHundred() {
    assertEquals(Integer.valueOf(20), McpFeedbackPending.parseLimit(null));
    assertEquals(Integer.valueOf(20), McpFeedbackPending.parseLimit(" "));
    assertEquals(Integer.valueOf(100), McpFeedbackPending.parseLimit("100"));
    assertEquals(Integer.valueOf(100), McpFeedbackPending.parseLimit("5000"));
    assertEquals(Integer.valueOf(1), McpFeedbackPending.parseLimit(" 1 "));
  }

  @Test
  public void pendingReturnsAtMostLimitRows() throws Exception {
    String oldest = insertRow(otherClient, FEEDBACK, PAYLOAD, 0, false);
    insertRow(callerClient, FEEDBACK, PAYLOAD, 1, false);

    Map<String, String> response = call(new McpFeedbackPending(), Map.of("limit", "1"));

    assertEquals("1", response.get("count"));
    assertEquals(List.of(oldest), ids(new JSONArray(response.get("result"))));
  }

  @Test
  public void pendingRejectsALimitThatIsNotAPositiveWholeNumber() {
    for (String limit : new String[] { "0", "-3", "ten", "2.5" }) {
      Map<String, String> response = call(new McpFeedbackPending(), Map.of("limit", limit));
      assertNotNull("limit=" + limit + " is refused", response.get(McpFeedbackTriage.KEY_ERROR));
      assertNull(response.get("result"));
    }
  }

  // ── McpFeedbackTriage ───────────────────────────────────────────────────────────────────────

  @Test
  public void triageMarksAFeedbackRowOfAnotherClient() throws Exception {
    String id = insertRow(otherClient, FEEDBACK, PAYLOAD, 0, false);

    Map<String, String> response = triage(id, ASSIGNEE);

    assertEquals(TRUE, response.get(McpFeedbackTriage.KEY_UPDATED));
    assertNull(response.get(McpFeedbackTriage.KEY_REASON));
    Map<String, Object> row = readRow(id);
    assertEquals(CELL, row.get("triage_cell"));
    assertEquals(ASSIGNEE, row.get("triage_assignee"));
    assertEquals(ISSUE, row.get("triage_issue"));
    assertEquals(TASK, row.get("triage_task"));
    assertNotNull("The row is marked processed", row.get("triaged"));
    assertEquals("The write is audited as the bot", callerUser, row.get("updatedby"));
    assertEquals("The human review is not touched", "Y", row.get("isactive"));
    assertNull(row.get("status"));
    assertFalse("A triaged row leaves the queue", ids(pending()).contains(id));
  }

  @Test
  public void triageWithoutAssigneeStillMarksTheRowProcessed() throws Exception {
    String id = insertRow(otherClient, FEEDBACK, PAYLOAD, 0, false);

    assertEquals(TRUE, triage(id, null).get(McpFeedbackTriage.KEY_UPDATED));

    Map<String, Object> row = readRow(id);
    assertNull(row.get("triage_assignee"));
    assertNotNull(row.get("triaged"));
  }

  @Test
  public void aSecondTriageOfTheSameRowChangesNothing() throws Exception {
    String id = insertRow(otherClient, FEEDBACK, PAYLOAD, 0, false);
    triage(id, ASSIGNEE);
    Map<String, Object> first = readRow(id);

    Map<String, String> again = call(new McpFeedbackTriage(), params(id,
        "CelulaVentas", "https://github.com/etendosoftware/etendo-ai/issues/43", "ETP-1", "other"));

    assertEquals(FALSE, again.get(McpFeedbackTriage.KEY_UPDATED));
    assertEquals(McpFeedbackTriage.REASON_ALREADY_TRIAGED, again.get(McpFeedbackTriage.KEY_REASON));
    assertEquals("The first triage stands", first, readRow(id));
  }

  @Test
  public void triageOfAToolCallRowChangesNothing() throws Exception {
    String id = insertRow(otherClient, TOOL_CALL, null, 0, false);

    Map<String, String> response = triage(id, ASSIGNEE);

    assertEquals(FALSE, response.get(McpFeedbackTriage.KEY_UPDATED));
    assertEquals(McpFeedbackTriage.REASON_NOT_FEEDBACK, response.get(McpFeedbackTriage.KEY_REASON));
    assertNull(readRow(id).get("triaged"));
    assertNull(readRow(id).get("triage_cell"));
  }

  @Test
  public void triageOfAnUnknownIdReportsNotFound() {
    Map<String, String> response = triage("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", ASSIGNEE);

    assertEquals(FALSE, response.get(McpFeedbackTriage.KEY_UPDATED));
    assertEquals(McpFeedbackTriage.REASON_NOT_FOUND, response.get(McpFeedbackTriage.KEY_REASON));
  }

  @Test
  public void anInvalidValueIsRefusedWithoutWriting() throws Exception {
    String id = insertRow(otherClient, FEEDBACK, PAYLOAD, 0, false);
    String[][] invalid = {
        // cell, issue, task, assignee
        { "Plataforma", ISSUE, TASK, ASSIGNEE },
        { "CelulaPlataforma2", ISSUE, TASK, ASSIGNEE },
        { "Celula" + "x".repeat(60), ISSUE, TASK, ASSIGNEE },
        { CELL, "https://github.com/someone-else/etendo-ai/issues/42", TASK, ASSIGNEE },
        { CELL, "https://github.com/etendosoftware/etendo-ai/pull/42", TASK, ASSIGNEE },
        { CELL, "http://github.com/etendosoftware/etendo-ai/issues/42", TASK, ASSIGNEE },
        { CELL, ISSUE + "#comment", TASK, ASSIGNEE },
        { CELL, ISSUE, "etp-5952", ASSIGNEE },
        { CELL, ISSUE, "ETP5952", ASSIGNEE },
        { CELL, ISSUE, TASK, "octo_cat" },
        { CELL, ISSUE, TASK, "a".repeat(40) },
        { null, ISSUE, TASK, ASSIGNEE },
        { CELL, null, TASK, ASSIGNEE },
        { CELL, ISSUE, null, ASSIGNEE },
    };
    for (String[] values : invalid) {
      Map<String, String> response = call(new McpFeedbackTriage(),
          params(id, values[0], values[1], values[2], values[3]));
      String label = String.join(" | ", String.valueOf(values[0]), String.valueOf(values[1]),
          String.valueOf(values[2]), String.valueOf(values[3]));
      assertNotNull(label + " is refused", response.get(McpFeedbackTriage.KEY_ERROR));
      assertNull(label + " gets no updated flag", response.get(McpFeedbackTriage.KEY_UPDATED));
    }
    assertNull("Nothing was written", readRow(id).get("triaged"));
    assertNull(readRow(id).get("triage_cell"));
  }

  // ── access, through the real webhook servlet ────────────────────────────────────────────────

  @Test
  public void aRequestWithoutApiKeyIsRejected() throws Exception {
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, servletStatus(null));
  }

  @Test
  public void anApiKeyGrantedOnlyToOtherWebhooksIsRejected() throws Exception {
    String apiKey = insertToken();
    grant(SF_LIST_WINDOWS_ID, apiKey);

    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, servletStatus(apiKey));

    // Control: the same key passes authorization once it is granted this webhook, so the
    // rejection above is the missing grant and not a broken fixture.
    grant(PENDING_ID, apiKey);
    assertNotEquals(HttpServletResponse.SC_UNAUTHORIZED, servletStatus(apiKey));
  }

  // ── fixtures ────────────────────────────────────────────────────────────────────────────────

  private static Connection connection() {
    return OBDal.getInstance().getConnection();
  }

  private String insertRow(String clientId, String rowType, String payload, int second,
      boolean triaged) throws SQLException {
    String id = UUID.randomUUID().toString().replace("-", "").toUpperCase();
    try (PreparedStatement st = connection().prepareStatement("INSERT INTO etgo_mcp_usage"
        + " (etgo_mcp_usage_id, ad_client_id, ad_org_id, isactive, created, createdby, updated,"
        + " updatedby, tool_name, outcome, row_type, payload, triage_cell, triaged)"
        + " VALUES (?, ?, '0', 'Y', ?, '100', ?, '100', 'etendo_feedback', 'ok', ?, ?, ?, ?)")) {
      Timestamp created = Timestamp.valueOf(BASE.plusSeconds(second));
      st.setString(1, id);
      st.setString(2, clientId);
      st.setTimestamp(3, created);
      st.setTimestamp(4, created);
      st.setString(5, rowType);
      st.setString(6, payload);
      st.setString(7, triaged ? CELL : null);
      st.setTimestamp(8, triaged ? created : null);
      st.executeUpdate();
    }
    return id;
  }

  private Map<String, Object> readRow(String id) throws SQLException {
    try (PreparedStatement st = connection().prepareStatement("SELECT triage_cell,"
        + " triage_assignee, triage_issue, triage_task, triaged, updatedby, isactive, status"
        + " FROM etgo_mcp_usage WHERE etgo_mcp_usage_id = ?")) {
      st.setString(1, id);
      try (ResultSet rs = st.executeQuery()) {
        assertTrue(rs.next());
        Map<String, Object> row = new HashMap<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
          row.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
        }
        return row;
      }
    }
  }

  /** Runs a webhook as the tenant user the bot's token would bind. */
  private Map<String, String> call(com.etendoerp.webhookevents.services.BaseWebhookService hook,
      Map<String, String> parameters) {
    OBContext.setOBContext(callerUser, callerRole, callerClient, "0");
    Map<String, String> response = new HashMap<>();
    hook.get(new HashMap<>(parameters), response);
    return response;
  }

  /** The default queue, as the bot reads it every poll. */
  private JSONArray pending() throws Exception {
    Map<String, String> response = call(new McpFeedbackPending(), new HashMap<>());
    assertNull(response.get(McpFeedbackTriage.KEY_ERROR));
    return new JSONArray(response.get("result"));
  }

  private Map<String, String> triage(String id, String assignee) {
    return call(new McpFeedbackTriage(), params(id, CELL, ISSUE, TASK, assignee));
  }

  private static Map<String, String> params(String id, String cell, String issue, String task,
      String assignee) {
    Map<String, String> parameters = new HashMap<>();
    parameters.put(McpFeedbackTriage.PARAM_ID, id);
    putIfPresent(parameters, McpFeedbackTriage.PARAM_CELL, cell);
    putIfPresent(parameters, McpFeedbackTriage.PARAM_ISSUE, issue);
    putIfPresent(parameters, McpFeedbackTriage.PARAM_TASK, task);
    putIfPresent(parameters, McpFeedbackTriage.PARAM_ASSIGNEE, assignee);
    return parameters;
  }

  private static void putIfPresent(Map<String, String> parameters, String key, String value) {
    if (value != null) {
      parameters.put(key, value);
    }
  }

  private static List<String> ids(JSONArray rows) throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < rows.length(); i++) {
      ids.add(rows.getJSONObject(i).getString("id"));
    }
    return ids;
  }

  /**
   * A webhook token bound to the caller's user and role, granted to nothing yet. The table's
   * trigger generates the API key on insert, whatever the insert says, so it is read back.
   */
  private String insertToken() throws SQLException {
    String tokenId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
    try (PreparedStatement st = connection().prepareStatement("INSERT INTO"
        + " smfwhe_definedwebhook_token (smfwhe_definedwebhook_token_id, ad_client_id, ad_org_id,"
        + " isactive, created, createdby, updated, updatedby, ad_user_roles_id, name, getapikey,"
        + " role_access) VALUES (?, ?, '0', 'Y', now(), '100', now(), '100', ?,"
        + " 'ETP-5952 fixture', 'N', 'N')")) {
      st.setString(1, tokenId);
      st.setString(2, callerClient);
      st.setString(3, callerUserRoleId);
      st.executeUpdate();
    }
    try (PreparedStatement st = connection().prepareStatement(
        "SELECT apikey FROM smfwhe_definedwebhook_token WHERE smfwhe_definedwebhook_token_id = ?")) {
      st.setString(1, tokenId);
      try (ResultSet rs = st.executeQuery()) {
        assertTrue(rs.next());
        assertNotNull("The trigger generated a key", rs.getString(1));
        return rs.getString(1);
      }
    }
  }

  private void grant(String webhookId, String apiKey) throws SQLException {
    try (PreparedStatement st = connection().prepareStatement("INSERT INTO"
        + " smfwhe_definedwebhook_acc (smfwhe_definedwebhook_acc_id, ad_client_id, ad_org_id,"
        + " isactive, created, createdby, updated, updatedby, smfwhe_definedwebhook_id,"
        + " smfwhe_definedwebhook_token_id) SELECT get_uuid(), ?, '0', 'Y', now(), '100', now(),"
        + " '100', ?, smfwhe_definedwebhook_token_id FROM smfwhe_definedwebhook_token"
        + " WHERE apikey = ?")) {
      st.setString(1, callerClient);
      st.setString(2, webhookId);
      st.setString(3, apiKey);
      assertEquals(1, st.executeUpdate());
    }
    // The servlet reads the grants through the webhook's DAL collection: drop the cached one.
    OBDal.getInstance().getSession().clear();
  }

  /** The HTTP status the real webhook servlet answers to {@code GET /webhooks/McpFeedbackPending}. */
  private int servletStatus(String apiKey) throws Exception {
    OBContext.setOBContext(callerUser, callerRole, callerClient, "0");
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getPathInfo()).thenReturn("/" + PENDING_WEBHOOK);
    when(request.getParameter("apikey")).thenReturn(apiKey);
    when(request.getParameterMap()).thenReturn(new HashMap<>());
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    new WebhookServiceHandler().doGet(request, response);

    ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
    verify(response).setStatus(status.capture());
    return status.getValue();
  }
}
