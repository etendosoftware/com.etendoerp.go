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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess.classic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openbravo.client.kernel.KernelConstants;

import com.etendoerp.go.supportaccess.IssuedSupportTicket;
import com.etendoerp.go.supportaccess.SupportAccessException;
import com.etendoerp.go.supportaccess.SupportAccessGuard;
import com.etendoerp.go.supportaccess.SupportAccessRecord;
import com.etendoerp.go.supportaccess.SupportAccessService;
import com.etendoerp.go.supportaccess.SupportTenantBusyException;

/**
 * "Access as Support" reads its parameters and the selected company, refuses any role but the
 * support one, maps every refusal to its message and answers with the client action that opens
 * the GO URL in a new tab. No database: the service and the OBContext/message seams are stubbed.
 *
 * @covers com.etendoerp.go.supportaccess.classic.SupportAccessIssueActionHandler
 * @covers com.etendoerp.go.supportaccess.classic.SupportAccessActionHandler
 */
class SupportAccessIssueActionHandlerTest {

  static final String OPERATOR_ID = "A1B2C3D4E5F60718293A4B5C6D7E8F90";
  static final String CLIENT_ID = "4028E6C72959682B01295A070852010D";
  static final String ACCESS_ID = "0123456789ABCDEF0123456789ABCDEF";
  static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
  private static final String KEY_PROPERTY = "inpetgoSupportClientVId";
  private static final String REASON = "Customer reports totals do not add up";
  private static final String GO_URL = "https://go.example.com/support-access#t=TICKET_abc-123";
  private static final String RESPONSE_ACTIONS = "responseActions";
  private static final String SHOW_MSG = "showMsgInProcessView";
  private static final String MSG_TEXT = "msgText";
  private static final String MSG_TYPE = "msgType";
  private static final String ERROR = "error";
  private static final String RETRY = "retryExecution";
  private static final String RETRY_MESSAGE = "message";
  private static final String RETRY_TEXT = "text";

  private SupportAccessService service;
  private TestableHandler handler;

  @BeforeEach
  void setUp() {
    service = mock(SupportAccessService.class);
    handler = new TestableHandler(service);
  }

  /** Stubs the OBContext, message, transaction and clock seams. */
  static final class TestableHandler extends SupportAccessIssueActionHandler {
    String roleId = SupportAccessGuard.SUPPORT_OPERATOR_ROLE_ID;
    boolean appUrlConfigured = true;
    int commits;
    int rollbacks;

    TestableHandler(SupportAccessService service) {
      super(() -> service);
    }

    @Override
    protected String currentUserId() {
      return OPERATOR_ID;
    }

    @Override
    protected String currentRoleId() {
      return roleId;
    }

    @Override
    protected String translate(String key, String... params) {
      return params.length == 0 ? key : key + Arrays.toString(params);
    }

    @Override
    protected void commit() {
      commits++;
    }

    @Override
    protected void rollback() {
      rollbacks++;
    }

    @Override
    protected Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Override
    protected ZoneId zone() {
      return ZoneOffset.UTC;
    }

    @Override
    protected boolean isAppUrlConfigured() {
      return appUrlConfigured;
    }
  }

  static String content(String clientId, String reason, String duration) throws JSONException {
    JSONObject params = new JSONObject();
    if (reason != null) {
      params.put(SupportAccessClassicMetadata.PARAM_REASON, reason);
    }
    if (duration != null) {
      params.put(SupportAccessClassicMetadata.PARAM_DURATION, duration);
    }
    JSONObject request = new JSONObject();
    request.put("inpKeyName", KEY_PROPERTY);
    if (clientId != null) {
      request.put(KEY_PROPERTY, clientId);
    }
    request.put("_params", params);
    request.put("_buttonValue", "DONE");
    return request.toString();
  }

  static JSONObject firstAction(JSONObject response, String name) throws JSONException {
    JSONArray actions = response.getJSONArray(RESPONSE_ACTIONS);
    for (int i = 0; i < actions.length(); i++) {
      if (actions.getJSONObject(i).has(name)) {
        return actions.getJSONObject(i).getJSONObject(name);
      }
    }
    throw new AssertionError("No " + name + " action in " + response);
  }

  private IssuedSupportTicket ticket(String url) {
    return new IssuedSupportTicket(ACCESS_ID, "TICKET_abc-123", url, NOW.plusSeconds(60), 120);
  }

  private JSONObject run(String content) {
    return handler.doExecute(new HashMap<>(), content);
  }

  @Test
  void opensTheGoUrlInANewTabAndShowsTheFallbackLink() throws JSONException {
    when(service.issue(eq(OPERATOR_ID), eq(CLIENT_ID), eq(REASON), eq(120), isNull(), isNull()))
        .thenReturn(ticket(GO_URL));

    JSONObject response = run(content(CLIENT_ID, REASON, "120"));

    JSONArray actions = response.getJSONArray(RESPONSE_ACTIONS);
    assertEquals(2, actions.length());
    String script = actions.getJSONObject(0)
        .getJSONObject(SupportAccessMessages.CLIENT_ACTION_CUSTOM)
        .getString(SupportAccessMessages.CLIENT_ACTION_FUNCTION);
    assertEquals(SupportAccessMessages.openInNewTabScript(GO_URL), script);
    assertTrue(script.startsWith("window.open("));
    JSONObject message = actions.getJSONObject(1).getJSONObject(SHOW_MSG);
    assertEquals("success", message.getString(MSG_TYPE));
    String text = message.getString(MSG_TEXT);
    assertTrue(text.startsWith(SupportAccessMessages.MSG_ACCESS_ISSUED + "[120, <a href=\""
        + GO_URL + "\""), text);
    assertTrue(text.contains(SupportAccessMessages.MSG_ACCESS_LINK + "["
        + SupportAccessService.TICKET_TTL_SECONDS + "]"), text);
    assertEquals(1, handler.commits);
    assertEquals(0, handler.rollbacks);
  }

  @Test
  void passesTheOperatorIpAndUserAgentToTheAudit() throws JSONException {
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getRemoteAddr()).thenReturn("203.0.113.7");
    when(http.getHeader("User-Agent")).thenReturn("Firefox");
    Map<String, Object> parameters = new HashMap<>();
    parameters.put(KernelConstants.HTTP_REQUEST, http);
    when(service.issue(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
        .thenReturn(ticket(GO_URL));

    handler.doExecute(parameters, content(CLIENT_ID, REASON, "60"));

    verify(service).issue(OPERATOR_ID, CLIENT_ID, REASON, 60, "203.0.113.7", "Firefox");
  }

  @Test
  void blankDurationTakesTheConfiguredDefault() throws JSONException {
    when(service.issue(anyString(), anyString(), anyString(), isNull(), isNull(), isNull()))
        .thenReturn(ticket(GO_URL));

    run(content(CLIENT_ID, REASON, " "));

    verify(service).issue(OPERATOR_ID, CLIENT_ID, REASON, null, null, null);
  }

  @Test
  void nonNumericDurationKeepsThePopupOpenWithTheMaximum() throws JSONException {
    when(service.getMaxDurationMinutes()).thenReturn(240);

    JSONObject response = run(content(CLIENT_ID, REASON, "forever"));

    assertTrue(response.getBoolean(RETRY));
    JSONObject message = response.getJSONObject(RETRY_MESSAGE);
    assertEquals(ERROR, message.getString("severity"));
    assertEquals(SupportAccessMessages.MSG_DURATION_INVALID + "[240]", message.getString(RETRY_TEXT));
    verify(service, never()).issue(any(), any(), any(), any(), any(), any());
  }

  @Test
  void shortReasonKeepsThePopupOpen() throws JSONException {
    when(service.issue(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenThrow(new SupportAccessException(SupportAccessException.CODE_REASON_REQUIRED, "x"));

    JSONObject response = run(content(CLIENT_ID, "short", "60"));

    assertTrue(response.getBoolean(RETRY));
    assertEquals(SupportAccessMessages.MSG_REASON_REQUIRED + "["
        + SupportAccessService.MIN_REASON_LENGTH + "]",
        response.getJSONObject(RETRY_MESSAGE).getString(RETRY_TEXT));
    assertEquals(1, handler.rollbacks);
  }

  @Test
  void missingReasonIsSentAsEmpty() throws JSONException {
    when(service.issue(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenThrow(new SupportAccessException(SupportAccessException.CODE_REASON_REQUIRED, "x"));

    run(content(CLIENT_ID, null, "60"));

    verify(service).issue(OPERATOR_ID, CLIENT_ID, "", 60, null, null);
  }

  @Test
  void busyCompanySaysWhoIsInsideAndUntilWhen() throws JSONException {
    SupportAccessRecord holder = new SupportAccessRecord();
    holder.setOperatorName("Ana Ops");
    holder.setSessionExpiresAt(NOW.plusSeconds(30 * 60L));
    when(service.issue(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenThrow(new SupportTenantBusyException(holder));

    JSONObject response = run(content(CLIENT_ID, REASON, "60"));

    JSONObject message = firstAction(response, SHOW_MSG);
    assertEquals(ERROR, message.getString(MSG_TYPE));
    assertEquals(SupportAccessMessages.MSG_TENANT_BUSY + "[Ana Ops, 10:30, 30]",
        message.getString(MSG_TEXT));
    assertFalse(response.has(RETRY));
    assertEquals(1, handler.rollbacks);
    assertEquals(0, handler.commits);
  }

  @Test
  void otherRefusalsCloseThePopupWithTheirMessage() throws JSONException {
    when(service.issue(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenThrow(new SupportAccessException(SupportAccessException.CODE_NO_ADMIN_ROLE, "x"));

    JSONObject response = run(content(CLIENT_ID, REASON, "60"));

    assertEquals(SupportAccessMessages.MSG_NO_ADMIN_ROLE,
        firstAction(response, SHOW_MSG).getString(MSG_TEXT));
    assertFalse(response.has(RETRY));
  }

  @Test
  void anyOtherRoleIsRefusedBeforeTouchingTheService() throws JSONException {
    handler.roleId = "0";

    JSONObject response = run(content(CLIENT_ID, REASON, "60"));

    assertEquals(SupportAccessMessages.MSG_OPERATOR_NOT_ALLOWED,
        firstAction(response, SHOW_MSG).getString(MSG_TEXT));
    verify(service, never()).issue(any(), any(), any(), any(), any(), any());
  }

  @Test
  void noSelectedCompanyIsNotEligible() throws JSONException {
    JSONObject response = run(content(null, REASON, "60"));

    assertEquals(SupportAccessMessages.MSG_TARGET_NOT_ELIGIBLE,
        firstAction(response, SHOW_MSG).getString(MSG_TEXT));
    verify(service, never()).issue(any(), any(), any(), any(), any(), any());
  }

  @Test
  void missingAppUrlRefusesBeforeWritingAnything() throws JSONException {
    handler.appUrlConfigured = false;

    JSONObject response = run(content(CLIENT_ID, REASON, "60"));

    assertEquals(SupportAccessMessages.MSG_APP_URL_MISSING,
        firstAction(response, SHOW_MSG).getString(MSG_TEXT));
    verify(service, never()).issue(any(), any(), any(), any(), any(), any());
    assertEquals(0, handler.commits);
  }

  @Test
  void ticketWithoutUrlIsRevokedSoTheCompanyIsNotHeld() throws JSONException {
    when(service.issue(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(ticket(null));

    JSONObject response = run(content(CLIENT_ID, REASON, "60"));

    verify(service).revoke(ACCESS_ID, OPERATOR_ID);
    assertEquals(SupportAccessMessages.MSG_APP_URL_MISSING,
        firstAction(response, SHOW_MSG).getString(MSG_TEXT));
    assertEquals(1, handler.commits);
  }

  @Test
  void nonHttpUrlIsNeverHandedToTheBrowser() throws JSONException {
    when(service.issue(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenReturn(ticket("javascript:alert(1)//#t=x"));

    JSONObject response = run(content(CLIENT_ID, REASON, "60"));

    verify(service).revoke(ACCESS_ID, OPERATOR_ID);
    assertFalse(response.toString().contains("window.open"));
  }

  @Test
  void unexpectedFailureRollsBackWithTheGenericMessage() throws JSONException {
    when(service.issue(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenThrow(new IllegalStateException("db down"));

    JSONObject response = run(content(CLIENT_ID, REASON, "60"));

    assertEquals(SupportAccessMessages.MSG_ACCESS_FAILED,
        firstAction(response, SHOW_MSG).getString(MSG_TEXT));
    assertEquals(1, handler.rollbacks);
  }

  @Test
  void malformedRequestIsAGenericFailure() throws JSONException {
    JSONObject response = run("{not json");

    assertEquals(SupportAccessMessages.MSG_ACCESS_FAILED,
        firstAction(response, SHOW_MSG).getString(MSG_TEXT));
  }

  @Test
  void readsTheSelectedRecordThroughItsKeyName() throws JSONException {
    assertEquals(CLIENT_ID, SupportAccessActionHandler.selectedClientId(
        new JSONObject(content(CLIENT_ID, REASON, "60"))));
    assertNull(SupportAccessActionHandler.selectedClientId(new JSONObject()));
    JSONObject nullValue = new JSONObject().put("inpKeyName", KEY_PROPERTY)
        .put(KEY_PROPERTY, JSONObject.NULL);
    assertNull(SupportAccessActionHandler.selectedClientId(nullValue));
  }

  @Test
  void parsesTheDurationParameter() throws JSONException {
    String duration = SupportAccessClassicMetadata.PARAM_DURATION;
    assertEquals(Integer.valueOf(480), SupportAccessIssueActionHandler.durationParam(
        new JSONObject().put(duration, "480")));
    assertEquals(Integer.valueOf(30), SupportAccessIssueActionHandler.durationParam(
        new JSONObject().put(duration, 30)));
    assertNull(SupportAccessIssueActionHandler.durationParam(null));
    assertNull(SupportAccessIssueActionHandler.durationParam(
        new JSONObject().put(duration, JSONObject.NULL)));
    SupportAccessException error = assertThrows(SupportAccessException.class,
        () -> SupportAccessIssueActionHandler.durationParam(
            new JSONObject().put(duration, "1h")));
    assertEquals(SupportAccessException.CODE_DURATION_INVALID, error.getCode());
  }

  @Test
  void maximumDurationFallsBackWhenThePreferenceCannotBeRead() throws JSONException {
    when(service.getMaxDurationMinutes()).thenThrow(new IllegalStateException("no db"));
    when(service.issue(anyString(), anyString(), anyString(), anyInt(), any(), any()))
        .thenThrow(new SupportAccessException(SupportAccessException.CODE_DURATION_INVALID, "x"));

    JSONObject response = run(content(CLIENT_ID, REASON, "9999"));

    assertEquals(SupportAccessMessages.MSG_DURATION_INVALID + "["
        + SupportAccessService.FALLBACK_MAX_MINUTES + "]",
        response.getJSONObject(RETRY_MESSAGE).getString(RETRY_TEXT));
  }
}
