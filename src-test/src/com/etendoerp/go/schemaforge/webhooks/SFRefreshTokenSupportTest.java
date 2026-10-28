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
package com.etendoerp.go.schemaforge.webhooks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.rest.EtendoGoJwtSupport;
import com.etendoerp.go.supportaccess.SupportAccessGuard;
import com.smf.securewebservices.utils.SecureWebServicesUtils;

/**
 * ETP-5351 (T5) — {@link SFRefreshToken} never mints a bearer JWT for a tenant's "Soporte Etendo"
 * user: such a token would outlive the support session. It answers the no-op shape instead.
 */
class SFRefreshTokenSupportTest {

  private static final String CLIENT = "4028E6C72959682B01295A070852010D";
  private static final String SUPPORT_USER = SupportAccessGuard.supportUserIdFor(CLIENT);

  @Test
  void theSupportUserGetsNoToken() throws Exception {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT);
    User user = mock(User.class);
    when(user.getId()).thenReturn(SUPPORT_USER);
    when(user.isActive()).thenReturn(true);
    when(user.getClient()).thenReturn(client);
    when(user.getDefaultRole()).thenReturn(mock(Role.class));
    OBContext context = mock(OBContext.class);
    when(context.getUser()).thenReturn(user);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(User.class, SUPPORT_USER)).thenReturn(user);
    Map<String, String> responseVars = new HashMap<>();

    try (MockedStatic<OBContext> ctx = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
        MockedStatic<EtendoGoJwtSupport> support = mockStatic(EtendoGoJwtSupport.class);
        MockedStatic<SecureWebServicesUtils> sws = mockStatic(SecureWebServicesUtils.class)) {
      ctx.when(OBContext::getOBContext).thenReturn(context);
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      support.when(() -> EtendoGoJwtSupport.loadRoleListData(SUPPORT_USER)).thenReturn(
          new EtendoGoJwtSupport.RoleListData("R1", new JSONArray()));

      new SFRefreshToken().get(new HashMap<>(), responseVars);

      sws.verify(() -> SecureWebServicesUtils.generateToken(any(User.class), any(Role.class)),
          never());
    }

    JSONObject result = new JSONObject(responseVars.get("result"));
    assertTrue(result.optBoolean("unchanged"));
    assertFalse(result.has("token"));
  }
}
