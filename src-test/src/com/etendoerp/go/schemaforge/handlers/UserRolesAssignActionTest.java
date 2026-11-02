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

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Consumer;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.StaleStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;

import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * ETP-5848: {@code assignRoles} on {@code user/user} sets a user's template roles the way the
 * Users window does, through the same service and with the same guards.
 */
class UserRolesAssignActionTest {

  private static final String USER_ID = "U1";
  private static final String SALES = "SALES";
  private static final String FINANCE = "FINANCE";

  private static NeoContext call(String method, JSONObject body) {
    NeoContext context = mock(NeoContext.class);
    when(context.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(context.getFieldName()).thenReturn(UserRolesAssignAction.ACTION);
    when(context.getHttpMethod()).thenReturn(method);
    when(context.getRequestBody()).thenReturn(body);
    when(context.getRecordId()).thenReturn(USER_ID);
    return context;
  }

  private static JSONObject roles(String... ids) throws Exception {
    return new JSONObject().put(UserRolesAssignAction.PARAM_ROLE_IDS, new JSONArray(List.of(ids)));
  }

  /** Runs {@code serve} as an administrator, with the service answering as {@code service} says. */
  private static NeoResponse serveAsAdmin(NeoContext context, boolean admin,
      Consumer<UserRoleCompositionService> service) {
    Role caller = mock(Role.class);
    User callerUser = mock(User.class);
    when(callerUser.getId()).thenReturn("CALLER");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getUser()).thenReturn(callerUser);
    OBDal dal = mock(OBDal.class);
    Role sales = mock(Role.class);
    when(sales.getName()).thenReturn("Ventas");
    when(dal.get(Role.class, SALES)).thenReturn(sales);
    try (MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class);
        MockedStatic<OBContext> obContextStatic = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
        MockedConstruction<UserRoleCompositionService> services = mockConstruction(
            UserRoleCompositionService.class, (mock, ctx) -> service.accept(mock))) {
      access.when(NeoAccessHelper::resolveCurrentRole).thenReturn(caller);
      access.when(() -> NeoAccessHelper.isAdminOrClientAdmin(caller)).thenReturn(admin);
      obContextStatic.when(OBContext::getOBContext).thenReturn(obContext);
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      NeoResponse response = UserRolesAssignAction.serve(context);
      if (!admin || !"POST".equals(context.getHttpMethod())) {
        assertTrue(services.constructed().isEmpty(), "nothing is written for a refused call");
      }
      return response;
    }
  }


  @Test
  @DisplayName("passes the whole deduplicated set, the caller's role and user, and answers the"
      + " roles by name with what changed")
  void passesTheWholeSet() throws Exception {
    Role caller = mock(Role.class);
    User callerUser = mock(User.class);
    when(callerUser.getId()).thenReturn("CALLER");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getUser()).thenReturn(callerUser);
    OBDal dal = mock(OBDal.class);
    Role sales = mock(Role.class);
    when(sales.getName()).thenReturn("Ventas");
    when(dal.get(Role.class, SALES)).thenReturn(sales);
    try (MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class);
        MockedStatic<OBContext> obContextStatic = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
        MockedConstruction<UserRoleCompositionService> services = mockConstruction(
            UserRoleCompositionService.class, (mock, ctx) ->
                when(mock.assignTemplateRoles(anyString(), anyList(), any(Role.class), anyString()))
                    .thenReturn(new UserRoleCompositionService.AssignmentResult(USER_ID, "P1",
                        List.of(SALES), 1, 0)))) {
      access.when(NeoAccessHelper::resolveCurrentRole).thenReturn(caller);
      access.when(() -> NeoAccessHelper.isAdminOrClientAdmin(caller)).thenReturn(true);
      obContextStatic.when(OBContext::getOBContext).thenReturn(obContext);
      dalStatic.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse response = UserRolesAssignAction.serve(call("POST", roles(SALES, " ", SALES)));

      assertEquals(200, response.getHttpStatus());
      verify(services.constructed().get(0)).assignTemplateRoles(USER_ID, List.of(SALES), caller,
          "CALLER");
      JSONObject body = response.getBody();
      assertEquals("Ventas", body.getJSONArray("roles").getJSONObject(0).getString("name"));
      assertEquals(1, body.getInt("added"));
      assertEquals(0, body.getInt("removed"));
    }
  }

  @Test
  @DisplayName("a non-administrator is refused with 403 and nothing is written")
  void nonAdminIsRefused() throws Exception {
    assertEquals(403, serveAsAdmin(call("POST", roles(SALES)), false, s -> { }).getHttpStatus());
  }

  @Test
  @DisplayName("only POST writes: a GET is 405 and writes nothing")
  void onlyPostWrites() throws Exception {
    assertEquals(405, serveAsAdmin(call("GET", roles(SALES)), true, s -> { }).getHttpStatus());
  }

  @Test
  @DisplayName("without templateRoleIds nothing is guessed: 400")
  void missingSetIsRefused() throws Exception {
    assertEquals(400, serveAsAdmin(call("POST", new JSONObject()), true, s -> { })
        .getHttpStatus());
  }

  @Test
  @DisplayName("a domain refusal (not a template, the owner, an administrator) is a 422 with its"
      + " own message")
  void domainRefusalIs422() throws Exception {
    NeoResponse response = serveAsAdmin(call("POST", roles(FINANCE)), true, s ->
        when(s.assignTemplateRoles(anyString(), anyList(), any(Role.class), anyString()))
            .thenThrow(new OBException("Role is not a template, cannot be composed: X")));
    assertEquals(422, response.getHttpStatus());
    assertTrue(response.getBody().toString().contains("not a template"));
  }

  @Test
  @DisplayName("a concurrent write of the same user's roles is a 409 to retry, not a 500")
  void concurrentWriteIs409() throws Exception {
    NeoResponse response = serveAsAdmin(call("POST", roles(SALES)), true, s ->
        when(s.assignTemplateRoles(anyString(), anyList(), any(Role.class), anyString()))
            .thenThrow(new OBException("lost", new StaleStateException("stale"))));
    assertEquals(409, response.getHttpStatus());
  }
}
