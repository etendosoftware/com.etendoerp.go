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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.etendoerp.go.supportaccess.SupportAccessGuard;
import com.etendoerp.go.supportaccess.SupportAccessRecord;
import com.etendoerp.go.supportaccess.SupportAccessService;

/**
 * "Close Support Session" revokes the selected company's open access as the logged operator,
 * and says so when there is none. No database: the service and the seams are stubbed.
 *
 * @covers com.etendoerp.go.supportaccess.classic.SupportAccessRevokeActionHandler
 */
class SupportAccessRevokeActionHandlerTest {

  private static final String OPERATOR_ID = SupportAccessIssueActionHandlerTest.OPERATOR_ID;
  private static final String CLIENT_ID = SupportAccessIssueActionHandlerTest.CLIENT_ID;
  private static final String ACCESS_ID = SupportAccessIssueActionHandlerTest.ACCESS_ID;
  private static final String SHOW_MSG = "showMsgInProcessView";
  private static final String MSG_TEXT = "msgText";

  private SupportAccessService service;
  private TestableHandler handler;

  @BeforeEach
  void setUp() {
    service = mock(SupportAccessService.class);
    handler = new TestableHandler(service);
  }

  /** Stubs the OBContext, message and transaction seams. */
  static final class TestableHandler extends SupportAccessRevokeActionHandler {
    String roleId = SupportAccessGuard.SUPPORT_OPERATOR_ROLE_ID;
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
  }

  private JSONObject run(String clientId) throws JSONException {
    return handler.doExecute(new HashMap<>(),
        SupportAccessIssueActionHandlerTest.content(clientId, null, null));
  }

  private static SupportAccessRecord openAccess() {
    SupportAccessRecord open = new SupportAccessRecord();
    open.setId(ACCESS_ID);
    open.setTargetClientId(CLIENT_ID);
    return open;
  }

  @Test
  void revokesTheOpenAccessAsTheLoggedOperator() throws JSONException {
    when(service.findOpenAccess(CLIENT_ID)).thenReturn(openAccess());
    when(service.revoke(ACCESS_ID, OPERATOR_ID)).thenReturn(true);

    JSONObject response = run(CLIENT_ID);

    verify(service).revoke(ACCESS_ID, OPERATOR_ID);
    JSONObject message = SupportAccessIssueActionHandlerTest.firstAction(response, SHOW_MSG);
    assertEquals("success", message.getString("msgType"));
    assertEquals(SupportAccessMessages.MSG_ACCESS_REVOKED, message.getString(MSG_TEXT));
    assertEquals(1, handler.commits);
  }

  @Test
  void companyWithoutOpenAccessSaysSo() throws JSONException {
    when(service.findOpenAccess(CLIENT_ID)).thenReturn(null);

    JSONObject response = run(CLIENT_ID);

    assertEquals(SupportAccessMessages.MSG_ACCESS_NOT_OPEN,
        SupportAccessIssueActionHandlerTest.firstAction(response, SHOW_MSG).getString(MSG_TEXT));
    verify(service, never()).revoke(any(), any());
    assertEquals(0, handler.commits);
    assertEquals(1, handler.rollbacks);
  }

  @Test
  void accessClosedInTheMeantimeSaysItIsNotOpen() throws JSONException {
    when(service.findOpenAccess(CLIENT_ID)).thenReturn(openAccess());
    when(service.revoke(ACCESS_ID, OPERATOR_ID)).thenReturn(false);

    JSONObject response = run(CLIENT_ID);

    assertEquals(SupportAccessMessages.MSG_ACCESS_NOT_OPEN,
        SupportAccessIssueActionHandlerTest.firstAction(response, SHOW_MSG).getString(MSG_TEXT));
  }

  @Test
  void noSelectedCompanyHasNothingToClose() throws JSONException {
    JSONObject response = run(null);

    assertEquals(SupportAccessMessages.MSG_ACCESS_NOT_OPEN,
        SupportAccessIssueActionHandlerTest.firstAction(response, SHOW_MSG).getString(MSG_TEXT));
    verify(service, never()).findOpenAccess(any());
  }

  @Test
  void anyOtherRoleIsRefused() throws JSONException {
    handler.roleId = "0";

    JSONObject response = run(CLIENT_ID);

    assertEquals(SupportAccessMessages.MSG_OPERATOR_NOT_ALLOWED,
        SupportAccessIssueActionHandlerTest.firstAction(response, SHOW_MSG).getString(MSG_TEXT));
    verify(service, never()).findOpenAccess(any());
    verify(service, never()).revoke(any(), any());
  }
}
