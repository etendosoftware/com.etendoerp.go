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

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.mcp.McpToolRouterTestAccess;

/**
 * Real-database run of the remittance header's account rules (ETP-6148): which accounts the
 * {@code financialAccount} selector offers, and the {@code sepaFileData} action.
 *
 * <ul>
 *   <li><b>Selector.</b> Offered: active Bank accounts with an active link to a remittance payment
 *       method, with or without a valid IBAN, each marked {@code ibanValid}. Not offered: a Bank
 *       account without the links, one whose links are inactive, and an account that is no longer
 *       Bank. Search and paging cut the same list, so {@code totalCount} and {@code hasMore}
 *       describe it. The link condition is the real HQL fragment
 *       ({@link RemittanceSepaProvisioning#activeRemittanceLinkExists}) on real rows.</li>
 *   <li><b>sepaFileData.</b> An organization the tenant does not own (another client's, or an
 *       unknown id) is a 404; without one, a remittance not saved yet ({@code new}) uses the
 *       account's organization; the MCP {@code etendo_action} call with id {@code new} reaches the
 *       action (its record guards let the sentinel id through) and answers the account.</li>
 * </ul>
 *
 * <p>Fixtures are built through the DAL in the F&B sample client — the client setup
 * ({@link RemittanceSepaProvisioning#ensureClient}), Bank accounts
 * ({@link FinancialAccountSupport#createAccount}) and their links — and rolled back after each
 * test. The flag is the only stub ({@link SepaRemittancesFlag#isEnabled()} through a static mock);
 * the selector's request is a mock carrying its {@code q} / {@code limit} / {@code offset}.
 * Runs on {@link WeldBaseTest}: the MCP router resolves the header handler through Weld.
 *
 * <p>A class of its own: {@code RemittanceHeaderHandlerTest} specifies the rules with the DAL
 * mocked; {@code RemittanceDueItemsQueryIntegrationTest} is about the due items query.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceHeaderHandler
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaFileData
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaProvisioning
 */
public class RemittanceHeaderHandlerAccountsIntegrationTest extends WeldBaseTest {

  private static final String EURO_ID = "102";
  private static final String SPAIN_ID = "106";
  private static final String VALID_ES_IBAN = "ES9121000418450200051332";

  private MockedStatic<SepaRemittancesFlag> flag;
  private Client client;
  private Organization org;
  private String marker;

  @Before
  public void openTheRequest() {
    setTestAdminContext();
    OBContext.setAdminMode();
    flag = Mockito.mockStatic(SepaRemittancesFlag.class, Mockito.CALLS_REAL_METHODS);
    flag.when(SepaRemittancesFlag::isEnabled).thenReturn(true);
    client = OBDal.getInstance().get(Client.class, TEST_CLIENT_ID);
    org = OBDal.getInstance().get(Organization.class, TEST_ORG_ID);
    marker = "SFSEL" + System.nanoTime();
    RemittanceSepaProvisioning.ensureClient(client);
  }

  @After
  public void rollBack() {
    try {
      flag.close();
      OBDal.getInstance().rollbackAndClose();
    } finally {
      while (OBContext.getOBContext() != null
          && OBContext.getOBContext().isInAdministratorMode()) {
        OBContext.restorePreviousMode();
      }
    }
  }

  // ---------------------------------------------------------------- selector

  @Test
  public void theSelectorOffersLinkedBankAccountsWithOrWithoutAValidIbanAndNothingElse()
      throws Exception {
    FIN_FinancialAccount withoutIban = linkedBankAccount("1 sin IBAN");
    FIN_FinancialAccount withIban = linkedBankAccount("2 con IBAN");
    giveIban(withIban, VALID_ES_IBAN);
    bankAccount("3 sin enlaces");
    FIN_FinancialAccount inactive = linkedBankAccount("4 enlaces inactivos");
    assertEquals(2, RemittanceSepaProvisioning.deactivateAccountLinks(inactive));
    FIN_FinancialAccount noLongerBank = linkedBankAccount("5 ahora caja");
    noLongerBank.setType("C");
    OBDal.getInstance().save(noLongerBank);
    OBDal.getInstance().flush();

    JSONObject page = selector(marker, null, null);

    JSONArray items = page.getJSONArray("items");
    assertEquals(items.toString(), 2, items.length());
    assertEquals(2L, page.getLong("totalCount"));
    assertFalse(page.getBoolean("hasMore"));
    JSONObject first = items.getJSONObject(0);
    assertEquals(withoutIban.getId(), first.getString("id"));
    assertFalse("no IBAN: offered, marked invalid", first.getBoolean("ibanValid"));
    assertTrue(first.isNull("iban"));
    JSONObject second = items.getJSONObject(1);
    assertEquals(withIban.getId(), second.getString("id"));
    assertTrue(second.getBoolean("ibanValid"));
    assertEquals(VALID_ES_IBAN, second.getString("iban"));
  }

  @Test
  public void theSelectorSearchesByNameAndPagesTheSameList() throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      ids.add(linkedBankAccount("banco " + i).getId());
    }
    linkedBankAccount("otra");

    JSONObject searched = selector(marker + " otra", null, null);
    assertEquals(1, searched.getJSONArray("items").length());

    JSONObject second = selector(marker + " banco", "1", "1");
    assertEquals(3L, second.getLong("totalCount"));
    assertEquals(1, second.getJSONArray("items").length());
    assertEquals(ids.get(1), second.getJSONArray("items").getJSONObject(0).getString("id"));
    assertTrue(second.getBoolean("hasMore"));

    JSONObject last = selector(marker + " banco", "2", "2");
    assertEquals(1, last.getJSONArray("items").length());
    assertEquals(ids.get(2), last.getJSONArray("items").getJSONObject(0).getString("id"));
    assertFalse(last.getBoolean("hasMore"));

    JSONObject past = selector(marker + " banco", "10", "5");
    assertEquals(0, past.getJSONArray("items").length());
    assertFalse(past.getBoolean("hasMore"));
  }

  // ---------------------------------------------------------------- sepaFileData

  @Test
  public void sepaFileDataRefusesAnOrganizationTheTenantDoesNotOwnWith404() throws Exception {
    FIN_FinancialAccount account = linkedBankAccount("org ajena");

    for (String orgId : new String[] { QA_TEST_ORG_ID, "NO-SUCH-ORGANIZATION-0000000000" }) {
      NeoResponse response = handler().handle(sepaFileData(
          new JSONObject().put("accountId", account.getId()).put("organizationId", orgId)));

      assertEquals(orgId, 404, response.getHttpStatus());
    }
  }

  @Test
  public void sepaFileDataOfANewRemittanceUsesTheAccountsOrganization() throws Exception {
    FIN_FinancialAccount account = linkedBankAccount("nueva");

    NeoResponse response = handler().handle(sepaFileData(
        new JSONObject().put("accountId", account.getId())));

    assertEquals(String.valueOf(response.getBody()), 200, response.getHttpStatus());
    assertEquals(account.getOrganization().getId(),
        response.getBody().getString("organizationId"));
    assertFalse(response.getBody().getBoolean("ibanValid"));
    assertTrue(response.getBody().getJSONArray("missing").toString().contains("iban"));
  }

  /** The MCP call the agent makes for a remittance not saved yet: {@code etendo_action} id new. */
  @Test
  public void anMcpActionCallWithIdNewAnswersTheAccount() throws Exception {
    FIN_FinancialAccount account = linkedBankAccount("mcp");

    JSONObject result = McpToolRouterTestAccess.handleAction("remittance", new JSONObject()
        .put("entity", "remittance").put("id", RemittanceHeaderHandler.NEW_RECORD_ID)
        .put("action", RemittanceSepaFileData.ACTION)
        .put("parameters", new JSONObject().put("accountId", account.getId())));

    assertFalse(result.toString(), result.optBoolean("isError", false));
    assertTrue(result.toString(), result.toString().contains(account.getId()));
    assertTrue(result.toString(), result.toString().contains("creditorIdAvailable"));
  }

  // ---------------------------------------------------------------- helpers

  private RemittanceHeaderHandler handler() {
    RemittanceHeaderHandler handler = spy(new RemittanceHeaderHandler());
    doReturn(true).when(handler).isFlagEnabled();
    return handler;
  }

  private static NeoContext sepaFileData(JSONObject body) {
    return NeoContext.builder().specName("remittance").entityName("remittance")
        .httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName(RemittanceSepaFileData.ACTION).recordId(RemittanceHeaderHandler.NEW_RECORD_ID)
        .requestBody(body).build();
  }

  /** One page of the {@code financialAccount} selector. */
  private JSONObject selector(String search, String offset, String limit) throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("q")).thenReturn(search);
    when(request.getParameter("offset")).thenReturn(offset);
    when(request.getParameter("limit")).thenReturn(limit);
    RemittanceHeaderHandler handler = handler();
    doReturn(request).when(handler).currentRequest();
    NeoContext ctx = NeoContext.builder().specName("remittance").entityName("remittance")
        .httpMethod("GET").endpointType(NeoEndpointType.SELECTOR)
        .fieldName(RemittanceHeaderHandler.FIELD_ACCOUNT).build();

    NeoResponse response = handler.handle(ctx);

    assertEquals(String.valueOf(response.getBody()), 200, response.getHttpStatus());
    return response.getBody();
  }

  private FIN_FinancialAccount bankAccount(String name) {
    return FinancialAccountSupport.createAccount(client, org,
        OBDal.getInstance().get(Currency.class, EURO_ID), marker + " " + name,
        FinancialAccountHandler.TYPE_BANK);
  }

  private FIN_FinancialAccount linkedBankAccount(String name) {
    FIN_FinancialAccount account = bankAccount(name);
    RemittanceSepaProvisioning.ensureAccountLinks(account);
    assertTrue(RemittanceSepaProvisioning.hasRemittanceMethodLink(account));
    return account;
  }

  private static void giveIban(FIN_FinancialAccount account, String iban) {
    account.setCountry(OBDal.getInstance().get(Country.class, SPAIN_ID));
    account.setIBAN(iban);
    OBDal.getInstance().save(account);
    OBDal.getInstance().flush();
  }
}
