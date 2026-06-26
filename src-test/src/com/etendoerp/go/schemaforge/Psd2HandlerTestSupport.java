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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Shared test fixtures for the {@link FinancialAccountPsd2Handler} test classes. The handler is one
 * large action router, so its tests are split into focused classes (routing, query, connect, link)
 * to stay under the Sonar 35-method-per-class limit; this support type factors out the duplicated
 * mock-building so no near-identical setup is repeated across them (Sonar duplication gate).
 *
 * <p>It is intentionally NOT a {@code *Test} class, so the runner does not pick it up as a suite.
 */
final class Psd2HandlerTestSupport {

  static final String CLIENT_ID = "23C59575B9CF467C9620760EB255B389";
  static final String ACCOUNT_ID = "FA-001";
  static final String CONNECTION_ID = "SE-CONN-001";
  static final String SALT_EDGE_ACCOUNT_ID = "SE-ACC-001";
  static final String API_KEY = "psd2-api-key";
  static final String ORIGIN = "https://app.etendo.cloud";

  static final String PARAM_ACTION = "action";
  static final String PARAM_ACCOUNT_ID = "financialAccountId";
  static final String PARAM_CONNECTION_ID = "connectionId";
  static final String PARAM_TYPE = "type";

  private Psd2HandlerTestSupport() {
  }

  /** Builds a NeoContext mock for a GET request carrying the given query params. */
  static NeoContext getContext(Map<String, String> queryParams) {
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("GET");
    when(context.getQueryParams()).thenReturn(queryParams);
    return context;
  }

  /** Builds a NeoContext mock for a POST request with the given action and JSON body. */
  static NeoContext postContext(String action, JSONObject body) {
    NeoContext context = mock(NeoContext.class);
    when(context.getHttpMethod()).thenReturn("POST");
    when(context.getQueryParams()).thenReturn(singleParam(PARAM_ACTION, action));
    when(context.getRequestBody()).thenReturn(body);
    return context;
  }

  /** Builds a one-entry query-param map. */
  static Map<String, String> singleParam(String key, String value) {
    Map<String, String> params = new HashMap<>();
    params.put(key, value);
    return params;
  }

  /**
   * Stubs the static {@link OBContext} accessors on the given mock so {@code currentClient()}
   * resolves to a client whose id is {@link #CLIENT_ID} and the current organization is a mock.
   *
   * @param obContext the open MockedStatic over {@link OBContext}
   */
  static void stubObContext(org.mockito.MockedStatic<OBContext> obContext) {
    OBContext ctx = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    when(ctx.getCurrentClient()).thenReturn(client);
    when(ctx.getCurrentOrganization()).thenReturn(mock(Organization.class));
    obContext.when(OBContext::getOBContext).thenReturn(ctx);
  }
}
