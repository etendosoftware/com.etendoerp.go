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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceType;

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
 *   <li><b>Header writes (ETP-6247).</b> The database no longer copies the account's SEPA values
 *       onto the remittance, the write customization does: a create with no IBAN, creditor
 *       identifier or scheme gets the account's IBAN and creditor identifier (its real
 *       {@code EM_REM_CreditorIdentifier} column) and {@code CORE}; an update that moves it to
 *       another account gets that account's values. The hook is run directly: the generic write
 *       behind it (the JSON data service) commits, which a rolled-back test must not do.</li>
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
 * @covers com.etendoerp.go.schemaforge.RemittanceHeaderWrites
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaFileData
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaProvisioning
 */
public class RemittanceHeaderHandlerAccountsIntegrationTest extends WeldBaseTest {

  private static final String EURO_ID = "102";
  private static final String SPAIN_ID = "106";
  private static final String VALID_ES_IBAN = "ES9121000418450200051332";
  private static final String OTHER_ES_IBAN = "ES7921000813610123456789";
  private static final String CREDITOR_ID = "ES97000B12345678";
  private static final String OTHER_CREDITOR_ID = "ES82000B87654323";
  private static final String SPEC = "remittance";

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

  // ---------------------------------------------------------------- header writes

  @Test
  public void aCreateWithoutSepaValuesTakesTheAccountsIbanCreditorIdAndCore() throws Exception {
    FIN_FinancialAccount account = sepaAccount("alta", VALID_ES_IBAN, CREDITOR_ID);
    JSONObject body = new JSONObject()
        .put(RemittanceHeaderHandler.FIELD_ACCOUNT, account.getId())
        .put(RemittanceHeaderHandler.FIELD_TYPE, collectionType().getId())
        .put("dueDate", LocalDate.now().plusDays(10).toString());

    NeoResponse refusal = handler().handle(crud("POST", null, body));

    assertEquals("the write goes on to the generic CRUD", null, refusal);
    assertEquals(VALID_ES_IBAN, body.getString(RemittanceHeaderHandler.FIELD_IBAN));
    assertEquals(CREDITOR_ID, body.getString(RemittanceHeaderHandler.FIELD_CREDITOR_ID));
    assertEquals(RemittanceSepaProvisioning.SCHEME_CORE,
        body.getString(RemittanceHeaderHandler.FIELD_SCHEME));
  }

  @Test
  public void anUpdateThatChangesTheAccountTakesTheNewAccountsIbanAndCreditorId()
      throws Exception {
    FIN_FinancialAccount first = sepaAccount("primera", VALID_ES_IBAN, CREDITOR_ID);
    FIN_FinancialAccount second = sepaAccount("segunda", OTHER_ES_IBAN, OTHER_CREDITOR_ID);
    Remittance stored = storedCollection(first);
    JSONObject body = new JSONObject().put(RemittanceHeaderHandler.FIELD_ACCOUNT, second.getId());

    NeoResponse refusal = handler().handle(crud("PUT", stored.getId(), body));

    assertEquals("the write goes on to the generic CRUD", null, refusal);
    assertEquals(OTHER_ES_IBAN, body.getString(RemittanceHeaderHandler.FIELD_IBAN));
    assertEquals(OTHER_CREDITOR_ID, body.getString(RemittanceHeaderHandler.FIELD_CREDITOR_ID));
    assertFalse("the scheme is not the account's: an update leaves it alone",
        body.has(RemittanceHeaderHandler.FIELD_SCHEME));
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

  /** A linked Bank account with a valid IBAN and creditor identifier. */
  private FIN_FinancialAccount sepaAccount(String name, String iban, String creditorId) {
    FIN_FinancialAccount account = linkedBankAccount(name);
    account.set(RemittanceCreditorId.PROPERTY, creditorId);
    giveIban(account, iban);
    return account;
  }

  private RemittanceType collectionType() {
    RemittanceType type = RemittanceSepaProvisioning.findType(client,
        RemittanceSepaProvisioning.SCHEME_CORE);
    assertNotNull("the provisioned CORE type", type);
    return type;
  }

  /**
   * A not processed collection remittance of {@code account} with the account's SEPA values,
   * saved through the DAL in the test transaction (the trigger copies nothing: they are set here).
   */
  private Remittance storedCollection(FIN_FinancialAccount account) {
    Remittance remittance = OBProvider.getInstance().get(Remittance.class);
    remittance.setClient(client);
    remittance.setOrganization(account.getOrganization());
    remittance.setFinancialAccount(account);
    remittance.setRemittanceType(collectionType());
    remittance.setDocumentType(remittanceDocumentType());
    remittance.setDocumentNo(marker);
    remittance.setName(marker);
    remittance.setTransactionDate(day(LocalDate.now()));
    remittance.setDueDate(day(LocalDate.now().plusDays(10)));
    remittance.setProcessed(false);
    remittance.set(RemittanceIban.PROPERTY, account.getIBAN());
    remittance.set(RemittanceCreditorId.REMITTANCE_PROPERTY,
        account.get(RemittanceCreditorId.PROPERTY));
    remittance.set(RemittanceSepaOptions.PROPERTY_SCHEME, RemittanceSepaProvisioning.SCHEME_CORE);
    OBDal.getInstance().save(remittance);
    OBDal.getInstance().flush();
    return remittance;
  }

  private DocumentType remittanceDocumentType() {
    DocumentType docType = (DocumentType) OBDal.getInstance().getSession().createQuery(
        "from DocumentType as d where d.client.id = :client and d.documentCategory = 'REM_REM'"
            + " and d.active = true")
        .setParameter("client", client.getId())
        .setMaxResults(1)
        .uniqueResult();
    assertNotNull("the remittance document type", docType);
    return docType;
  }

  private static Date day(LocalDate date) {
    return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  /**
   * A REST write of the remittance header as the customization sees it. The hook is run directly:
   * the generic write behind it (the JSON data service) commits, so it is not run here.
   */
  private static NeoContext crud(String method, String id, JSONObject body) {
    return NeoContext.builder().specName(SPEC).entityName(SPEC).httpMethod(method)
        .endpointType(NeoEndpointType.CRUD).recordId(id).requestBody(body).build();
  }
}
