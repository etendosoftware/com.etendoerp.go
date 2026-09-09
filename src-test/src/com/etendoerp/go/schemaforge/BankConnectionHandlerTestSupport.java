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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.psd2.bank.integration.data.Provider;

/**
 * Shared test fixtures for the {@link FinancialAccountBankConnectionHandler} test classes. The handler is one
 * large action router, so its tests are split into focused classes (routing, query, connect, link)
 * to stay under the Sonar 35-method-per-class limit; this support type factors out the duplicated
 * mock-building so no near-identical setup is repeated across them (Sonar duplication gate).
 *
 * <p>It is intentionally NOT a {@code *Test} class, so the runner does not pick it up as a suite.
 */
final class BankConnectionHandlerTestSupport {

  static final String CLIENT_ID = "23C59575B9CF467C9620760EB255B389";
  static final String ACCOUNT_ID = "FA-001";
  static final String CONNECTION_ID = "SE-CONN-001";
  static final String SALT_EDGE_ACCOUNT_ID = "SE-ACC-001";
  static final String API_KEY = "bank-connection-api-key";
  static final String ORIGIN = "https://app.etendo.cloud";

  static final String PARAM_ACTION = "action";
  static final String PARAM_ACCOUNT_ID = "financialAccountId";
  static final String PARAM_CONNECTION_ID = "connectionId";
  static final String PARAM_TYPE = "type";
  /** Disconnect mode flag: {@code true} deletes the connection, {@code false} only deactivates it. */
  static final String PARAM_PERMANENT_DELETION = "permanentDeletion";

  private BankConnectionHandlerTestSupport() {
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
  // ctx and client ARE used (as receivers of the when(...) stubbings below); S1854 mis-reports
  // them as dead stores on this mock-setup pattern (false positive), so it is suppressed here.
  @SuppressWarnings("java:S1854")
  static void stubObContext(org.mockito.MockedStatic<OBContext> obContext) {
    OBContext ctx = mock(OBContext.class);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    when(ctx.getCurrentClient()).thenReturn(client);
    when(ctx.getCurrentOrganization()).thenReturn(mock(Organization.class));
    obContext.when(OBContext::getOBContext).thenReturn(ctx);
  }

  /**
   * Stubs the {@code OBCriteria<Provider>} chain the provider-by-code lookup walks, resolving to
   * {@code found} (pass null for "no such provider").
   *
   * <p>Shared by every suite that reaches a provider lookup — linking an account (which resolves
   * or registers the provider) and the ETP-5181 max-fetch-interval read — so the criteria seam is
   * described in exactly one place and all of them break together if it changes.
   *
   * @param obDal the open MockedStatic over {@link OBDal}
   * @param found the Provider the query resolves to, or null
   * @return the {@link OBDal} instance mock, for tests that also need to stub {@code save}/
   *     {@code flush} or add further criteria on it
   */
  // java:S1854 — both locals below ARE read (dal by thenReturn and createCriteria and the return,
  // criteria by createCriteria's stub), but Sonar's dataflow does not follow a value handed to a
  // MockedStatic's thenReturn and reports the mock() assignments as dead stores. Same false
  // positive, same suppression as stubObContext above; do not "clean up" by inlining the mocks,
  // the chain needs the references.
  @SuppressWarnings("java:S1854")
  static OBDal stubProviderLookup(org.mockito.MockedStatic<OBDal> obDal, Provider found) {
    OBDal dal = mock(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    @SuppressWarnings("unchecked")
    OBCriteria<Provider> criteria = mock(OBCriteria.class);
    when(dal.createCriteria(Provider.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.setMaxResults(1)).thenReturn(criteria);
    when(criteria.uniqueResult()).thenReturn(found);
    return dal;
  }
}
