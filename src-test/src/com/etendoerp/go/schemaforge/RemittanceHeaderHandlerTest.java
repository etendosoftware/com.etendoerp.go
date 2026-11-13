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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceType;

/**
 * Unit tests for {@link RemittanceHeaderHandler}, the {@code remittance} spec's header
 * customization: the flag gate, the write rules (a remittance account, a provisioned type
 * linked to that account, processed is read-only, the name), the two selectors, the name
 * suggestion ({@link RemittanceNameSuggestion}) the callout answers and the create hooks fill
 * (REST pre-hook, MCP {@code resolveServerCreateFields}), and the routing of the two
 * «Seleccionar desde factura» actions to {@link RemittanceDueItems} and of «Procesar» /
 * «Reactivar» to {@link RemittanceProcessAction} (whose rules are specified in {@code RemittanceDueItemsTest} and
 * {@code RemittanceProcessActionTest}).
 *
 * <p>The handler is spied and its package-private seams ({@code isFlagEnabled},
 * {@code loadRemittance}, {@code loadAccount}, {@code loadType}, {@code currentClientId},
 * {@code currentRequest}, {@code readCalloutBody}, {@code currentLanguage}) stubbed. The suggestion
 * wording itself is specified in {@code RemittanceNameSuggestionTest}. {@link OBContext} is mocked statically (admin mode and the
 * readable organizations); the selectors' HQL runs against a mocked Hibernate session that records
 * every query and its parameters.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceHeaderHandler
 * @covers com.etendoerp.go.schemaforge.RemittanceHeaderWrites
 * @covers com.etendoerp.go.schemaforge.RemittanceHeaderSelectors
 * @covers com.etendoerp.go.schemaforge.RemittanceNameCallout
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaFileDataAction
 * @covers com.etendoerp.go.schemaforge.RemittanceRequestParams
 */
class RemittanceHeaderHandlerTest {

  /** A valid Spanish IBAN (mod-97 ok, 24 characters). */
  private static final String ES_IBAN = "ES9121000418450200051332";
  /** {@link #ES_IBAN} with its check digits broken. */
  private static final String ES_IBAN_BAD_CHECKSUM = "ES0021000418450200051332";
  /** A valid French IBAN: valid on its own, but not for a Spanish account. */
  private static final String FR_IBAN = "FR1420041010050500013M02606";

  private static final String CLIENT_ID = "CLIENT-1";
  private static final String ACC_ID = "ACC-1";
  private static final String TYPE_ID = "TYPE-1";
  private static final String REM_ID = "REM-1";
  /** The pinned {@code today()} of the due-date rule. */
  private static final java.time.LocalDate TODAY = java.time.LocalDate.of(2026, 11, 6);

  /** One {@code createQuery} call: the HQL and the parameters it was bound with. */
  private static final class Recorded {
    final String hql;
    final Map<String, Object> params = new HashMap<>();

    Recorded(String hql) {
      this.hql = hql;
    }
  }

  private RemittanceHeaderHandler handler;
  private MockedStatic<OBContext> contextStatic;
  private MockedStatic<OBDal> dalStatic;
  private Session session;
  private final List<Recorded> queries = new ArrayList<>();
  private List<?> selectorRows = List.of();
  private long selectorTotal;

  @BeforeEach
  void setUp() {
    handler = spy(new RemittanceHeaderHandler());
    doReturn(true).when(handler).isFlagEnabled();
    doReturn(CLIENT_ID).when(handler).currentClientId();
    // Spanish by default; a test that needs another language re-stubs it AFTER its own setup.
    doReturn("es_ES").when(handler).currentLanguage();
    // The due-date rule compares with today: pinned, so the fixed dates below never go stale.
    doReturn(TODAY).when(handler).today();

    OBContext obContext = mock(OBContext.class);
    when(obContext.getReadableOrganizations()).thenReturn(new String[] { "0", "ORG-1" });
    OBDal dal = mock(OBDal.class);
    session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createQuery(anyString(), any(Class.class))).thenAnswer(
        inv -> recordingQuery(inv.getArgument(0), inv.getArgument(1)));

    contextStatic = mockStatic(OBContext.class);
    contextStatic.when(OBContext::getOBContext).thenReturn(obContext);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
  }

  @AfterEach
  void tearDown() {
    dalStatic.close();
    contextStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------- flag

  @ParameterizedTest
  @ValueSource(strings = { "CRUD", "DEFAULTS", "SELECTOR", "CALLOUT", "ACTION" })
  void everyEndpointAnswers403WhileTheFlagIsOff(String endpoint) throws Exception {
    doReturn(false).when(handler).isFlagEnabled();
    NeoContext ctx = context("GET", NeoEndpointType.valueOf(endpoint), null, null);

    NeoResponse response = handler.handle(ctx);

    assertEquals(403, response.getHttpStatus());
    assertEquals("feature_disabled", response.getBody().getJSONObject("error").getString("code"));
    verify(handler, never()).loadAccount(any());
    verify(handler, never()).loadRemittance(any());
    verify(handler, never()).currentRequest();
  }

  // ---------------------------------------------------------------- writes

  @Test
  void aCreateOnAnAccountThatIsNotARemittanceAccountIsRefusedWithItsReason() throws Exception {
    stubAccount(false);

    NeoResponse response = handler.handle(create(validBody()));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceHeaderHandler.MSG_ACCOUNT_NOT_ALLOWED, messageKey(response));
    verify(handler, never()).loadType(any());
  }

  @Test
  void aCreateWithATypeNotLinkedToTheAccountIsRefusedTypeNotAvailable() throws Exception {
    FIN_FinancialAccount account = stubAccount(true);
    RemittanceType type = stubType();
    doReturn(false).when(handler).isTypeAvailable(account, type);

    NeoResponse response = handler.handle(create(validBody()));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceHeaderHandler.MSG_TYPE_NOT_AVAILABLE, messageKey(response));
  }

  @Test
  void aValidCreateContinuesToTheGenericCrud() throws Exception {
    FIN_FinancialAccount account = stubAccount(true);
    RemittanceType type = stubType();
    doReturn(true).when(handler).isTypeAvailable(account, type);

    assertNull(handler.handle(create(validBody())));
    verify(handler).isTypeAvailable(account, type);
  }

  @Test
  void referencesAreReadAsPlainIdsOrAsIdObjects() throws Exception {
    FIN_FinancialAccount account = stubAccount(true);
    RemittanceType type = stubType();
    doReturn(true).when(handler).isTypeAvailable(account, type);
    JSONObject body = new JSONObject().put("name", "Remesa enero")
        .put("financialAccount", new JSONObject().put("id", ACC_ID))
        .put("remittanceType", " " + TYPE_ID + " ");

    assertNull(handler.handle(create(body)));
    verify(handler).loadAccount(ACC_ID);
    verify(handler).loadType(TYPE_ID);
  }

  @Test
  void isTypeAvailableNeedsAProvisionedTypeWhoseMethodIsLinkedToTheAccount() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    RemittanceType type = mock(RemittanceType.class);
    when(type.getPaymentMethod()).thenReturn(method);
    try (MockedStatic<RemittanceSepaProvisioning> provisioning =
        mockStatic(RemittanceSepaProvisioning.class)) {
      provisioning.when(() -> RemittanceSepaProvisioning.isProvisionedType(type)).thenReturn(true);
      provisioning.when(() -> RemittanceSepaProvisioning.hasActiveLink(account, method))
          .thenReturn(true);
      assertTrue(handler.isTypeAvailable(account, type));

      provisioning.when(() -> RemittanceSepaProvisioning.hasActiveLink(account, method))
          .thenReturn(false);
      assertFalse(handler.isTypeAvailable(account, type), "not linked to the account");

      provisioning.when(() -> RemittanceSepaProvisioning.isProvisionedType(type))
          .thenReturn(false);
      provisioning.when(() -> RemittanceSepaProvisioning.hasActiveLink(account, method))
          .thenReturn(true);
      assertFalse(handler.isTypeAvailable(account, type), "a type this module did not provision");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "PUT", "PATCH", "DELETE" })
  void aProcessedRemittanceCannotBeModifiedOrDeleted(String method) throws Exception {
    stubStored(true);
    NeoContext ctx = context(method, NeoEndpointType.CRUD, new JSONObject().put("name", "x"),
        REM_ID);

    NeoResponse response = handler.handle(ctx);

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceHeaderHandler.MSG_PROCESSED, messageKey(response));
  }

  @Test
  void deletingAnUnprocessedRemittanceContinues() throws Exception {
    stubStored(false);

    assertNull(handler.handle(context("DELETE", NeoEndpointType.CRUD, null, REM_ID)));
  }

  @Test
  void anUpdateOfAMissingRemittanceIsLeftToTheGenericCrud() throws Exception {
    doReturn(null).when(handler).loadRemittance(REM_ID);

    assertNull(handler.handle(context("PATCH", NeoEndpointType.CRUD,
        new JSONObject().put("name", ""), REM_ID)));
  }

  @Test
  void anUpdateThatTouchesNeitherAccountNorTypeIsNotRevalidated() throws Exception {
    stubStored(false);

    assertNull(handler.handle(context("PATCH", NeoEndpointType.CRUD,
        new JSONObject().put("description", "x"), REM_ID)));
    verify(handler, never()).loadAccount(any());
  }

  @Test
  void anUpdateOfTheTypeIsCheckedAgainstTheStoredAccount() throws Exception {
    Remittance stored = stubStored(false);
    FIN_FinancialAccount storedAccount = mock(FIN_FinancialAccount.class);
    when(storedAccount.getId()).thenReturn(ACC_ID);
    when(stored.getFinancialAccount()).thenReturn(storedAccount);
    FIN_FinancialAccount account = stubAccount(true);
    RemittanceType type = stubType();
    doReturn(false).when(handler).isTypeAvailable(account, type);

    NeoResponse response = handler.handle(context("PATCH", NeoEndpointType.CRUD,
        new JSONObject().put("remittanceType", TYPE_ID), REM_ID));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceHeaderHandler.MSG_TYPE_NOT_AVAILABLE, messageKey(response));
    verify(handler).loadAccount(ACC_ID);
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "   " })
  void aCreateWithABlankNameIsRefused400(String name) throws Exception {
    NeoResponse response = handler.handle(create(validBody().put("name", name)));

    assertEquals(400, response.getHttpStatus());
    assertEquals(RemittanceHeaderHandler.MSG_NAME_REQUIRED, messageKey(response));
    verify(handler, never()).loadAccount(any());
  }

  @Test
  void aCreateWithoutANameIsRefused400() throws Exception {
    JSONObject body = validBody();
    body.remove("name");

    assertEquals(RemittanceHeaderHandler.MSG_NAME_REQUIRED,
        messageKey(handler.handle(create(body))));
    assertEquals(RemittanceHeaderHandler.MSG_NAME_REQUIRED,
        messageKey(handler.handle(create(validBody().put("name", JSONObject.NULL)))));
  }

  @Test
  void aNameOfSixtyCharactersIsAcceptedAndSixtyOneIsRefused() throws Exception {
    FIN_FinancialAccount account = stubAccount(true);
    RemittanceType type = stubType();
    doReturn(true).when(handler).isTypeAvailable(account, type);
    String sixty = "R".repeat(RemittanceHeaderHandler.NAME_MAX_LENGTH);
    assertEquals(60, sixty.length());

    assertNull(handler.handle(create(validBody().put("name", sixty))));

    NeoResponse response = handler.handle(create(validBody().put("name", sixty + "R")));
    assertEquals(400, response.getHttpStatus());
    assertEquals(RemittanceHeaderHandler.MSG_NAME_TOO_LONG, messageKey(response));
    assertTrue(errorMessage(response).contains("60"), errorMessage(response));
  }

  @Test
  void anUpdateThatClearsTheNameIsRefused400() throws Exception {
    stubStored(false);

    NeoResponse response = handler.handle(context("PUT", NeoEndpointType.CRUD,
        new JSONObject().put("name", " "), REM_ID));

    assertEquals(400, response.getHttpStatus());
    assertEquals(RemittanceHeaderHandler.MSG_NAME_REQUIRED, messageKey(response));
  }

  // ---------------------------------------------------------------- selectors

  /**
   * The account selector offers every active Bank account with a remittance-method link, WITH OR
   * WITHOUT a valid IBAN (the header lets the user give it one); each item says which, and carries
   * the stored IBAN. The link condition is the shared one of RemittanceSepaProvisioning.
   */
  @Test
  void theAccountSelectorOffersBankAccountsWithLinksAndMarksTheirIban() throws Exception {
    FIN_FinancialAccount bbva = bankAccount(ACC_ID, "BBVA", ES_IBAN);
    FIN_FinancialAccount broken = bankAccount("ACC-BROKEN", "Broken", ES_IBAN_BAD_CHECKSUM);
    FIN_FinancialAccount none = bankAccount("ACC-NONE", "Sin IBAN", null);
    selectorRows = List.of(bbva, broken, none);
    doReturn(request(Map.of())).when(handler).currentRequest();

    NeoResponse response = handler.handle(selector("financialAccount"));

    JSONArray items = response.getBody().getJSONArray("items");
    assertEquals(3, items.length(), "an account without a valid IBAN is still offered");
    assertEquals(ACC_ID, items.getJSONObject(0).getString("id"));
    assertEquals("BBVA", items.getJSONObject(0).getString("label"));
    assertTrue(items.getJSONObject(0).getBoolean("ibanValid"));
    assertEquals(ES_IBAN, items.getJSONObject(0).getString("iban"));
    assertFalse(items.getJSONObject(1).getBoolean("ibanValid"));
    assertEquals(ES_IBAN_BAD_CHECKSUM, items.getJSONObject(1).getString("iban"));
    assertFalse(items.getJSONObject(2).getBoolean("ibanValid"));
    assertTrue(items.getJSONObject(2).isNull("iban"));
    assertEquals(3, response.getBody().getLong("totalCount"));
    assertFalse(response.getBody().getBoolean("hasMore"));
    assertEquals(1, queries.size(), "one query; the page is cut from the same list");
    Recorded query = queries.get(0);
    assertTrue(query.hql.contains("a.type = :bank"), query.hql);
    assertFalse(query.hql.contains("iBAN"), "no IBAN filter: " + query.hql);
    assertTrue(query.hql.contains(RemittanceSepaProvisioning.activeRemittanceLinkExists("a")),
        query.hql);
    assertTrue(query.hql.contains("a.active = true"), query.hql);
    assertEquals("B", query.params.get("bank"));
    assertEquals(RemittanceSepaProvisioning.PARAM_SCHEME, query.params.get("marker"));
    assertEquals(CLIENT_ID, query.params.get("client"));
    assertEquals(List.of("0", "ORG-1"), query.params.get("orgs"));
  }

  @Test
  void theAccountSelectorIsAlsoReachedByItsColumnNameAndFiltersBySearch() throws Exception {
    HttpServletRequest request = request(Map.of("q", new String[] { "Bb" }));
    when(request.getParameter("q")).thenReturn("Bb");
    doReturn(request).when(handler).currentRequest();

    handler.handle(selector("FIN_Financial_Account_ID"));

    assertEquals(1, queries.size());
    assertTrue(queries.get(0).hql.contains("lower(a.name) like :search"));
    assertEquals("%bb%", queries.get(0).params.get("search"));
  }

  @Test
  void theTypeSelectorOffersOnlyProvisionedTypesLinkedToTheChosenAccount() throws Exception {
    RemittanceType core = mock(RemittanceType.class);
    when(core.getId()).thenReturn(TYPE_ID);
    when(core.getIdentifier()).thenReturn("Remesa de cobro (adeudo SEPA)");
    selectorRows = List.of(core);
    selectorTotal = 1;
    doReturn(request(Map.of("fin_financial_account_id", new String[] { ACC_ID })))
        .when(handler).currentRequest();

    NeoResponse response = handler.handle(selector("remittanceType"));

    JSONArray items = response.getBody().getJSONArray("items");
    assertEquals(1, items.length());
    assertEquals(TYPE_ID, items.getJSONObject(0).getString("id"));
    assertEquals(2, queries.size());
    for (Recorded query : queries) {
      assertTrue(query.hql.contains("p.name = :marker"), query.hql);
      assertTrue(query.hql.contains("l.account.id = :account"), query.hql);
      assertTrue(query.hql.contains("l.active = true"), query.hql);
      assertEquals(RemittanceSepaProvisioning.PARAM_SCHEME, query.params.get("marker"));
      assertEquals(ACC_ID, query.params.get("account"), "the account parameter is case-insensitive");
      assertEquals(CLIENT_ID, query.params.get("client"));
    }
  }

  @Test
  void theTypeSelectorIsEmptyWithoutAnAccount() throws Exception {
    doReturn(request(Map.of("FIN_Financial_Account_ID", new String[] { "  " })))
        .when(handler).currentRequest();

    NeoResponse response = handler.handle(selector("REM_Remittance_Type_ID"));

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, response.getBody().getJSONArray("items").length());
    assertEquals(0, response.getBody().getLong("totalCount"));
    assertTrue(queries.isEmpty(), "no query runs without an account");
  }

  @Test
  void anotherSelectorFieldFallsThroughToTheGenericSelector() throws Exception {
    doReturn(request(Map.of())).when(handler).currentRequest();

    assertNull(handler.handle(selector("documentType")));
    assertTrue(queries.isEmpty());
  }

  @Test
  void theSelectorPagesWithLimitAndOffsetAndReportsHasMore() throws Exception {
    List<Object> accounts = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      accounts.add(bankAccount("ACC-" + i, "Bank " + i, ES_IBAN));
    }
    selectorRows = accounts;
    HttpServletRequest request = request(Map.of());
    when(request.getParameter("limit")).thenReturn("10");
    when(request.getParameter("offset")).thenReturn("5");
    doReturn(request).when(handler).currentRequest();

    NeoResponse response = handler.handle(selector("financialAccount"));

    JSONArray items = response.getBody().getJSONArray("items");
    assertEquals(10, items.length());
    assertEquals("ACC-5", items.getJSONObject(0).getString("id"));
    assertTrue(response.getBody().getBoolean("hasMore"));
    assertEquals(30, response.getBody().getLong("totalCount"));
  }

  /** An offset past the last account answers an empty page that has no more. */
  @Test
  void anOffsetPastTheEndAnswersAnEmptyPageWithoutMore() throws Exception {
    selectorRows = List.of(bankAccount("ACC-0", "Bank 0", ES_IBAN),
        bankAccount("ACC-1", "Bank 1", ES_IBAN));
    HttpServletRequest request = request(Map.of());
    when(request.getParameter("limit")).thenReturn("10");
    when(request.getParameter("offset")).thenReturn("50");
    doReturn(request).when(handler).currentRequest();

    NeoResponse response = handler.handle(selector("financialAccount"));

    assertEquals(0, response.getBody().getJSONArray("items").length());
    assertFalse(response.getBody().getBoolean("hasMore"));
    assertEquals(2, response.getBody().getLong("totalCount"));
  }

  // ---------------------------------------------------------------- remittance account

  /**
   * accountRefusal, cheapest first: a non-Bank account is NotBank without any link query; a Bank
   * account without a valid IBAN for its country (none, no country, a broken checksum, an IBAN of
   * another country) is IbanRequired; a valid Bank account without the remittance methods is
   * NotAllowed; with them it is a remittance account.
   */
  @Test
  void accountRefusalChecksTypeThenIbanThenTheRemittanceLinks() {
    for (String type : new String[] { "C", "CA", null }) {
      FIN_FinancialAccount account = bankAccount(ACC_ID, "Caja", ES_IBAN);
      when(account.getType()).thenReturn(type);
      assertEquals(RemittanceHeaderHandler.MSG_ACCOUNT_NOT_BANK, handler.accountRefusal(account),
          String.valueOf(type));
    }
    verify(handler, never()).hasRemittanceMethods(any());

    FIN_FinancialAccount noIban = bankAccount(ACC_ID, "BBVA", null);
    FIN_FinancialAccount blankIban = bankAccount(ACC_ID, "BBVA", "  ");
    FIN_FinancialAccount noCountry = bankAccount(ACC_ID, "BBVA", ES_IBAN);
    when(noCountry.getCountry()).thenReturn(null);
    FIN_FinancialAccount badChecksum = bankAccount(ACC_ID, "BBVA", ES_IBAN_BAD_CHECKSUM);
    FIN_FinancialAccount otherCountry = bankAccount(ACC_ID, "BNP", FR_IBAN);
    for (FIN_FinancialAccount account : List.of(noIban, blankIban, noCountry, badChecksum,
        otherCountry)) {
      assertEquals(RemittanceHeaderHandler.MSG_ACCOUNT_IBAN_REQUIRED,
          handler.accountRefusal(account), String.valueOf(account.getIBAN()));
    }
    verify(handler, never()).hasRemittanceMethods(any());

    FIN_FinancialAccount unlinked = bankAccount(ACC_ID, "BBVA", ES_IBAN);
    doReturn(false).when(handler).hasRemittanceMethods(unlinked);
    assertEquals(RemittanceHeaderHandler.MSG_ACCOUNT_NOT_ALLOWED,
        handler.accountRefusal(unlinked));

    FIN_FinancialAccount remittanceAccount = bankAccount(ACC_ID, "BBVA", ES_IBAN);
    doReturn(true).when(handler).hasRemittanceMethods(remittanceAccount);
    assertNull(handler.accountRefusal(remittanceAccount));
  }

  /** The IBAN is read as the account form reads it: spaces and case do not matter. */
  @Test
  void aValidIbanIsAcceptedWhateverItsSpacingOrCase() {
    assertTrue(RemittanceHeaderHandler.hasValidIban(
        bankAccount(ACC_ID, "BBVA", "es91 2100 0418 4502 0005 1332")));
    assertFalse(RemittanceHeaderHandler.hasValidIban(
        bankAccount(ACC_ID, "BBVA", ES_IBAN_BAD_CHECKSUM)));
  }

  /** A create answers each refusal of the account with its own key, before the type is read. */
  @Test
  void aCreateAnswersEachAccountRefusalWithItsOwnKey() throws Exception {
    FIN_FinancialAccount cash = bankAccount(ACC_ID, "Caja", ES_IBAN);
    when(cash.getType()).thenReturn("C");
    FIN_FinancialAccount withoutIban = bankAccount(ACC_ID, "BBVA", null);
    FIN_FinancialAccount unlinked = bankAccount(ACC_ID, "BBVA", ES_IBAN);
    doReturn(false).when(handler).hasRemittanceMethods(unlinked);
    Object[][] cases = {
        { cash, RemittanceHeaderHandler.MSG_ACCOUNT_NOT_BANK },
        { withoutIban, RemittanceHeaderHandler.MSG_ACCOUNT_IBAN_REQUIRED },
        { unlinked, RemittanceHeaderHandler.MSG_ACCOUNT_NOT_ALLOWED }, };

    for (Object[] c : cases) {
      doReturn(c[0]).when(handler).loadAccount(ACC_ID);

      NeoResponse response = handler.handle(create(validBody()));

      assertEquals(422, response.getHttpStatus(), (String) c[1]);
      assertEquals(c[1], messageKey(response));
    }
    assertEquals("ETGO_RemittanceAccountNotBank", RemittanceHeaderHandler.MSG_ACCOUNT_NOT_BANK);
    assertEquals("ETGO_RemittanceAccountIbanRequired",
        RemittanceHeaderHandler.MSG_ACCOUNT_IBAN_REQUIRED);
    assertEquals("ETGO_RemittanceAccountNotAllowed",
        RemittanceHeaderHandler.MSG_ACCOUNT_NOT_ALLOWED);
    verify(handler, never()).loadType(any());
  }

  // ---------------------------------------------------------------- DEFAULTS

  /**
   * The generic defaults may prefill any account; one that is not a remittance account — a Cash
   * one, or a Bank one without the remittance methods — is dropped, and the type with it, while
   * the other defaults stay.
   */
  @Test
  void theDefaultsDropAnAccountThatIsNotARemittanceAccountAndItsType() throws Exception {
    FIN_FinancialAccount cash = bankAccount(ACC_ID, "Caja", ES_IBAN);
    when(cash.getType()).thenReturn("C");
    FIN_FinancialAccount unlinked = bankAccount(ACC_ID, "BBVA", ES_IBAN);
    doReturn(false).when(handler).hasRemittanceMethods(unlinked);

    for (FIN_FinancialAccount account : List.of(cash, unlinked)) {
      doReturn(account).when(handler).loadAccount(ACC_ID);

      NeoResponse response = handler.afterHandle(defaultsContext());

      JSONObject defaults = response.getBody().getJSONObject("defaults");
      assertFalse(defaults.has("financialAccount"), defaults.toString());
      assertFalse(defaults.has("financialAccount$_identifier"), defaults.toString());
      assertFalse(defaults.has("remittanceType"), defaults.toString());
      assertFalse(defaults.has("remittanceType$_identifier"), defaults.toString());
      assertEquals("Remesa noviembre", defaults.getString("name"), "other defaults stay");
    }
    verify(handler, never()).hasRemittanceMethods(cash);
  }

  /**
   * A Bank account with the remittance methods but no valid IBAN may be chosen in the selector,
   * but it is never prefilled: the defaults drop it, and the type with it.
   */
  @Test
  void theDefaultsDropALinkedAccountWithoutAValidIban() throws Exception {
    FIN_FinancialAccount noIban = bankAccount(ACC_ID, "BBVA", null);
    FIN_FinancialAccount brokenIban = bankAccount(ACC_ID, "BBVA", ES_IBAN_BAD_CHECKSUM);

    for (FIN_FinancialAccount account : List.of(noIban, brokenIban)) {
      doReturn(true).when(handler).hasRemittanceMethods(account);
      doReturn(account).when(handler).loadAccount(ACC_ID);

      NeoResponse response = handler.afterHandle(defaultsContext());

      JSONObject defaults = response.getBody().getJSONObject("defaults");
      assertFalse(defaults.has("financialAccount"), defaults.toString());
      assertFalse(defaults.has("remittanceType"), defaults.toString());
    }
  }

  /** A remittance account is kept as the default: the generic result stands. */
  @Test
  void theDefaultsKeepARemittanceAccount() throws Exception {
    FIN_FinancialAccount account = bankAccount(ACC_ID, "BBVA", ES_IBAN);
    doReturn(true).when(handler).hasRemittanceMethods(account);
    doReturn(account).when(handler).loadAccount(ACC_ID);

    assertNull(handler.afterHandle(defaultsContext()));
  }

  private static NeoContext defaultsContext() throws Exception {
    JSONObject defaults = new JSONObject().put("financialAccount", ACC_ID)
        .put("financialAccount$_identifier", "BBVA").put("remittanceType", TYPE_ID)
        .put("remittanceType$_identifier", "SEPA CORE").put("name", "Remesa noviembre");
    NeoContext ctx = context("GET", NeoEndpointType.DEFAULTS, null, null);
    when(ctx.getPreviousResult())
        .thenReturn(NeoResponse.ok(new JSONObject().put("defaults", defaults)));
    return ctx;
  }

  // ---------------------------------------------------------------- callouts

  /** No request (or no body): the callout answers the empty result, never an error. */
  @Test
  void aCalloutWithoutARequestBodyIsAnsweredEmpty() throws Exception {
    doReturn(null).when(handler).currentRequest();

    NeoResponse response = handler.handle(context("POST", NeoEndpointType.CALLOUT,
        new JSONObject().put("remittanceType", TYPE_ID), null));

    assertEmptyCallout(response);
    verify(handler, never()).loadType(any());
  }

  @Test
  void aDueDateOnANewRemittanceWithoutTypeSuggestsTheGenericName() throws Exception {
    givenCallout("dueDate", "2026-11-15", new JSONObject().put("name", ""));

    NeoResponse response = handler.handle(callout());

    assertEquals(200, response.getHttpStatus());
    JSONObject name = response.getBody().getJSONObject("updates").getJSONObject("name");
    assertEquals("Remesa noviembre 2026", name.getString("value"));
    assertTrue(name.getBoolean("suggested"));
    assertEquals(1, response.getBody().getJSONObject("updates").length());
    assertEquals(0, response.getBody().getJSONObject("combos").length());
    assertEquals(0, response.getBody().getJSONArray("messages").length());
    verify(handler, never()).loadType(any());
  }

  @Test
  void aCollectionTypeSuggestsCobrosAndAPaymentTypeSuggestsPagos() throws Exception {
    stubTypeWithDirections(true, false);
    givenCallout("remittanceType", TYPE_ID, new JSONObject().put("dueDate", "2026-11-15"));
    assertEquals("Cobros noviembre 2026", suggestedName(handler.handle(callout())));

    stubTypeWithDirections(false, true);
    givenCallout("REM_Remittance_Type_ID", TYPE_ID, new JSONObject().put("dueDate", "2026-11-15"));
    assertEquals("Pagos noviembre 2026", suggestedName(handler.handle(callout())));
  }

  @Test
  void changingTheTypeOrTheDateReplacesAPreviousSuggestion() throws Exception {
    stubTypeWithDirections(true, false);
    givenCallout("remittanceType", TYPE_ID, new JSONObject().put("dueDate", "2026-11-15")
        .put("name", "Remesa noviembre 2026"));
    assertEquals("Cobros noviembre 2026", suggestedName(handler.handle(callout())));

    givenCallout("Duedate", "2026-12-01T00:00:00", new JSONObject()
        .put("remittanceType", TYPE_ID).put("name", "Cobros noviembre 2026"));
    assertEquals("Cobros diciembre 2026", suggestedName(handler.handle(callout())));
  }

  @Test
  void theSuggestionFollowsTheRequestLanguage() throws Exception {
    givenCallout("dueDate", "2026-11-15", new JSONObject());
    doReturn("en_US").when(handler).currentLanguage();
    assertEquals("Remittance November 2026", suggestedName(handler.handle(callout())));

    givenCallout("dueDate", "2026-11-15", new JSONObject());
    doReturn("es_AR").when(handler).currentLanguage();
    assertEquals("Remesa noviembre 2026", suggestedName(handler.handle(callout())));
  }

  @Test
  void aNameTheUserTypedIsNeverReplaced() throws Exception {
    givenCallout("dueDate", "2026-11-15", new JSONObject().put("name", "Mi remesa"));

    assertEmptyCallout(handler.handle(callout()));
  }

  @Test
  void anExistingRemittanceKeepsItsName() throws Exception {
    givenCallout("dueDate", "2026-11-15", new JSONObject().put("id", REM_ID)
        .put("name", "Remesa octubre 2026"));

    assertEmptyCallout(handler.handle(callout()));
  }

  @Test
  void noDueDateMeansNoSuggestion() throws Exception {
    givenCallout("remittanceType", TYPE_ID, new JSONObject().put("name", ""));
    assertEmptyCallout(handler.handle(callout()));

    givenCallout("dueDate", "  ", new JSONObject());
    assertEmptyCallout(handler.handle(callout()));
  }

  @Test
  void aCalloutOnAnyOtherFieldIsAnsweredEmpty() throws Exception {
    givenCallout("financialAccount", ACC_ID, new JSONObject().put("dueDate", "2026-11-15"));

    assertEmptyCallout(handler.handle(callout()));
    verify(handler, never()).loadType(any());
  }

  @Test
  void aSuggestionEqualToTheCurrentNameIsNotResent() throws Exception {
    givenCallout("dueDate", "2026-11-15", new JSONObject().put("name", "Remesa noviembre 2026"));

    assertEmptyCallout(handler.handle(callout()));
  }

  @Test
  void anUnreadableOrMissingCalloutBodyIsAnsweredEmpty() throws Exception {
    doReturn("es_ES").when(handler).currentLanguage();
    for (String raw : new String[] { "not json", "", "[1, 2]" }) {
      HttpServletRequest request = mock(HttpServletRequest.class);
      when(request.getInputStream()).thenReturn(servletStream(raw));
      doReturn(request).when(handler).currentRequest();

      assertEmptyCallout(handler.handle(callout()));
    }
    doReturn(null).when(handler).readCalloutBody();
    assertEmptyCallout(handler.handle(callout()));
  }

  @Test
  void theCalloutBodyIsReadFromTheRequestStream() throws Exception {
    doReturn("es_ES").when(handler).currentLanguage();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getInputStream()).thenReturn(servletStream(
        "{\"field\": \"dueDate\", \"value\": \"2026-11-15\", \"formState\": {}}"));
    doReturn(request).when(handler).currentRequest();

    assertEquals("Remesa noviembre 2026", suggestedName(handler.handle(callout())));
  }

  @Test
  void theCalloutStillAnswers403WhileTheFlagIsOff() throws Exception {
    doReturn(false).when(handler).isFlagEnabled();
    givenCallout("dueDate", "2026-11-15", new JSONObject());

    NeoResponse response = handler.handle(callout());

    assertEquals(403, response.getHttpStatus());
    verify(handler, never()).readCalloutBody();
  }

  // ---------------------------------------------------------------- create: name suggestion

  @Test
  void aRestCreateWithoutANameButWithADueDateIsSavedWithTheSuggestion() throws Exception {
    doReturn("es_ES").when(handler).currentLanguage();
    FIN_FinancialAccount account = stubAccount(true);
    RemittanceType type = stubType();
    doReturn(true).when(handler).isTypeAvailable(account, type);
    JSONObject body = new JSONObject().put("financialAccount", ACC_ID)
        .put("remittanceType", TYPE_ID).put("dueDate", "2026-11-15");

    assertNull(handler.handle(create(body)), "no 400 NameRequired");
    assertEquals("Remesa noviembre 2026", body.getString("name"));
  }

  @Test
  void aRestCreateKeepsTheNameItSent() throws Exception {
    doReturn("es_ES").when(handler).currentLanguage();
    FIN_FinancialAccount account = stubAccount(true);
    RemittanceType type = stubType();
    doReturn(true).when(handler).isTypeAvailable(account, type);
    JSONObject body = validBody().put("dueDate", "2026-11-15");

    assertNull(handler.handle(create(body)));
    assertEquals("Remesa enero", body.getString("name"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "PUT", "PATCH" })
  void anUpdateNeverChangesTheName(String method) throws Exception {
    doReturn("es_ES").when(handler).currentLanguage();
    stubStored(false);
    JSONObject withoutName = new JSONObject().put("dueDate", "2026-11-15");

    assertNull(handler.handle(context(method, NeoEndpointType.CRUD, withoutName, REM_ID)));
    assertFalse(withoutName.has("name"), "an update is not given a name");

    JSONObject cleared = new JSONObject().put("dueDate", "2026-11-15").put("name", "");
    NeoResponse response = handler.handle(context(method, NeoEndpointType.CRUD, cleared, REM_ID));
    assertEquals(400, response.getHttpStatus(), "clearing the name is still refused");
    assertEquals("", cleared.getString("name"));
  }

  @Test
  void theMcpCreateHookFillsAMissingName() throws Exception {
    doReturn("es_ES").when(handler).currentLanguage();
    stubTypeWithDirections(false, true);
    JSONObject body = new JSONObject().put("remittanceType", TYPE_ID).put("dueDate", "2026-11-15");

    handler.resolveServerCreateFields(create(body));

    assertEquals("Pagos noviembre 2026", body.getString("name"));
  }

  @Test
  void theMcpCreateHookKeepsASentNameAndIsANoOpWithoutADueDate() throws Exception {
    doReturn("es_ES").when(handler).currentLanguage();
    JSONObject named = new JSONObject().put("name", "Mi remesa").put("dueDate", "2026-11-15");
    JSONObject undated = new JSONObject().put("remittanceType", TYPE_ID);

    handler.resolveServerCreateFields(create(named));
    handler.resolveServerCreateFields(create(undated));

    assertEquals("Mi remesa", named.getString("name"));
    assertFalse(undated.has("name"));
    verify(handler, never()).loadType(any());
  }

  @Test
  void theMcpCreateHookIsANoOpWithTheFlagOff() throws Exception {
    doReturn(false).when(handler).isFlagEnabled();
    JSONObject body = new JSONObject().put("dueDate", "2026-11-15");

    handler.resolveServerCreateFields(create(body));

    assertFalse(body.has("name"));
    verify(handler, never()).currentLanguage();
  }

  @Test
  void theMcpCreateHookNeverThrows() throws Exception {
    doReturn("es_ES").when(handler).currentLanguage();
    Mockito.doThrow(new IllegalStateException("no session")).when(handler).loadType(TYPE_ID);
    JSONObject body = new JSONObject().put("remittanceType", TYPE_ID).put("dueDate", "2026-11-15");

    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> handler.resolveServerCreateFields(create(body)));
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> handler.resolveServerCreateFields(null));

    assertFalse(body.has("name"));
    contextStatic.verify(OBContext::restorePreviousMode);
  }

  @Test
  void theCreateCascadeNeverTouchesTheAccount() {
    assertEquals(Set.of("financialAccount"),
        handler.protectedCreateCalloutFields(mock(NeoContext.class)));
  }

  // ---------------------------------------------------------------- idOf

  @Test
  void idOfReadsEveryReferenceShape() throws Exception {
    FIN_FinancialAccount entity = mock(FIN_FinancialAccount.class);
    when(entity.getId()).thenReturn(ACC_ID);

    assertEquals(ACC_ID, RemittanceHeaderHandler.idOf(entity));
    assertEquals(ACC_ID, RemittanceHeaderHandler.idOf(new JSONObject().put("id", ACC_ID)));
    assertEquals(ACC_ID, RemittanceHeaderHandler.idOf(" " + ACC_ID));
    assertNull(RemittanceHeaderHandler.idOf(null));
    assertNull(RemittanceHeaderHandler.idOf(JSONObject.NULL));
    assertNull(RemittanceHeaderHandler.idOf(new JSONObject()));
    assertNull(RemittanceHeaderHandler.idOf("  "));
  }

  // ---------------------------------------------------------------- helpers

  @SuppressWarnings("unchecked")
  private Query<Object> recordingQuery(String hql, Class<?> resultType) {
    Recorded recorded = new Recorded(hql);
    queries.add(recorded);
    Query<Object> query = mock(Query.class, RETURNS_SELF);
    when(query.setParameter(anyString(), any())).thenAnswer(inv -> {
      recorded.params.put(inv.getArgument(0), inv.getArgument(1));
      return query;
    });
    when(query.setParameterList(anyString(), any(java.util.Collection.class))).thenAnswer(inv -> {
      recorded.params.put(inv.getArgument(0), inv.getArgument(1));
      return query;
    });
    if (resultType == Long.class) {
      when(query.uniqueResult()).thenAnswer(inv -> selectorTotal);
    } else {
      when(query.list()).thenAnswer(inv -> new ArrayList<Object>(selectorRows));
    }
    return query;
  }

  private static HttpServletRequest request(Map<String, String[]> params) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameterMap()).thenReturn(params);
    return request;
  }

  /**
   * The account the body names: a remittance account when {@code eligible}, otherwise one
   * {@link RemittanceHeaderHandler#accountRefusal} refuses as without remittance methods.
   */
  private FIN_FinancialAccount stubAccount(boolean eligible) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    doReturn(eligible ? null : RemittanceHeaderHandler.MSG_ACCOUNT_NOT_ALLOWED).when(handler)
        .accountRefusal(account);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    return account;
  }

  /** A Bank account in Spain (IBAN-configured country) with {@code iban}. */
  private static FIN_FinancialAccount bankAccount(String id, String name, String iban) {
    Country spain = mock(Country.class);
    when(spain.getName()).thenReturn("Spain");
    when(spain.getIBANCode()).thenReturn("ES");
    when(spain.getIBANLength()).thenReturn(24L);
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn(id);
    when(account.getIdentifier()).thenReturn(name);
    when(account.getType()).thenReturn("B");
    when(account.getIBAN()).thenReturn(iban);
    when(account.getCountry()).thenReturn(spain);
    return account;
  }

  private RemittanceType stubType() {
    RemittanceType type = mock(RemittanceType.class);
    doReturn(type).when(handler).loadType(TYPE_ID);
    return type;
  }

  private Remittance stubStored(boolean processed) {
    Remittance stored = mock(Remittance.class);
    when(stored.isProcessed()).thenReturn(processed);
    doReturn(stored).when(handler).loadRemittance(REM_ID);
    return stored;
  }

  private static JSONObject validBody() throws Exception {
    return new JSONObject().put("name", "Remesa enero").put("financialAccount", ACC_ID)
        .put("remittanceType", TYPE_ID);
  }

  private static NeoContext create(JSONObject body) {
    return context("POST", NeoEndpointType.CRUD, body, null);
  }

  private static NeoContext selector(String field) {
    NeoContext ctx = context("GET", NeoEndpointType.SELECTOR, null, null);
    when(ctx.getFieldName()).thenReturn(field);
    return ctx;
  }

  private static NeoContext context(String method, NeoEndpointType endpoint, JSONObject body,
      String recordId) {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getSpecName()).thenReturn("remittance");
    when(ctx.getEntityName()).thenReturn("remittance");
    when(ctx.getHttpMethod()).thenReturn(method);
    when(ctx.getEndpointType()).thenReturn(endpoint);
    when(ctx.getRequestBody()).thenReturn(body);
    when(ctx.getRecordId()).thenReturn(recordId);
    return ctx;
  }

  private static String messageKey(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0);
  }

  private static String errorMessage(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("message");
  }

  // ---------------------------------------------------------------- due date not in the past

  @Test
  void aCreateWithAPastDueDateIsRefused422() throws Exception {
    NeoResponse response = handler.handle(create(validBody().put("dueDate", "2026-11-05")));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceHeaderHandler.MSG_DUE_DATE_PAST, messageKey(response));
    assertEquals("ETGO_RemittanceDueDatePast", messageKey(response));
    verify(handler, never()).loadAccount(any());
  }

  @ParameterizedTest
  @ValueSource(strings = { "2026-11-06", "2026-11-07", "2027-01-01" })
  void aCreateDueTodayOrLaterPasses(String dueDate) throws Exception {
    givenAvailableAccountAndType();

    assertNull(handler.handle(create(validBody().put("dueDate", dueDate))));
  }

  @Test
  void aCreateWithoutADueDateIsNotJudged() throws Exception {
    givenAvailableAccountAndType();

    assertNull(handler.handle(create(validBody())));
    verify(handler, never()).today();
  }

  @ParameterizedTest
  @ValueSource(strings = { "PUT", "PATCH" })
  void anUpdateThatMovesTheDueDateIntoThePastIsRefused422(String method) throws Exception {
    Remittance stored = stubStored(false);
    when(stored.getDueDate()).thenReturn(date(2026, 11, 20));

    NeoResponse response = handler.handle(context(method, NeoEndpointType.CRUD,
        new JSONObject().put("dueDate", "2026-11-01"), REM_ID));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceHeaderHandler.MSG_DUE_DATE_PAST, messageKey(response));
  }

  @ParameterizedTest
  @ValueSource(strings = { "2026-11-06", "2026-12-01" })
  void anUpdateThatMovesTheDueDateToTodayOrLaterPasses(String dueDate) throws Exception {
    Remittance stored = stubStored(false);
    when(stored.getDueDate()).thenReturn(date(2026, 11, 1));

    assertNull(handler.handle(context("PATCH", NeoEndpointType.CRUD,
        new JSONObject().put("dueDate", dueDate), REM_ID)));
  }

  @Test
  void anUpdateThatKeepsAStoredPastDueDatePasses() throws Exception {
    Remittance stored = stubStored(false);
    when(stored.getDueDate()).thenReturn(date(2026, 10, 1));

    assertNull(handler.handle(context("PUT", NeoEndpointType.CRUD,
        new JSONObject().put("dueDate", "2026-10-01").put("name", "Remesa octubre"), REM_ID)),
        "echoing the stored day is not a change");
    assertNull(handler.handle(context("PATCH", NeoEndpointType.CRUD,
        new JSONObject().put("dueDate", "2026-10-01T00:00:00"), REM_ID)));
  }

  @Test
  void renamingARemittanceWhoseStoredDueDateIsPastPasses() throws Exception {
    Remittance stored = stubStored(false);
    when(stored.getDueDate()).thenReturn(date(2026, 10, 1));

    assertNull(handler.handle(context("PATCH", NeoEndpointType.CRUD,
        new JSONObject().put("name", "Remesa renombrada"), REM_ID)));
    verify(handler, never()).today();
  }

  @Test
  void aProcessedRemittanceIsRefusedAsProcessedBeforeTheDateIsJudged() throws Exception {
    Remittance stored = stubStored(true);
    when(stored.getDueDate()).thenReturn(date(2026, 11, 20));

    NeoResponse response = handler.handle(context("PATCH", NeoEndpointType.CRUD,
        new JSONObject().put("dueDate", "2026-11-01"), REM_ID));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceHeaderHandler.MSG_PROCESSED, messageKey(response));
    verify(handler, never()).today();
  }

  @Test
  void aTimestampIsJudgedByItsDatePart() throws Exception {
    NeoResponse past = handler.handle(create(validBody().put("dueDate", "2026-11-01T00:00:00")));
    assertEquals(422, past.getHttpStatus());

    givenAvailableAccountAndType();
    assertNull(handler.handle(create(validBody().put("dueDate", "2026-11-06T00:00:00"))),
        "midnight of today is today");
  }

  @ParameterizedTest
  @ValueSource(strings = { "not a date", "01/11/2026", "" })
  void anUnparseableDueDateIsLeftToTheGenericWrite(String dueDate) throws Exception {
    givenAvailableAccountAndType();

    assertNull(handler.handle(create(validBody().put("dueDate", dueDate))));
    verify(handler, never()).today();
  }

  @Test
  void thePastDueDateRefusalIsWordedInTheRequestLanguage() throws Exception {
    String[][] expected = {
        { "es_ES", "La fecha de cargo/abono no puede ser anterior a hoy." },
        { "es_AR", "La fecha de cargo/abono no puede ser anterior a hoy." },
        { "en_US", "The charge / credit date cannot be earlier than today." }, };
    for (String[] row : expected) {
      try (MockedStatic<com.etendoerp.go.schemaforge.util.NeoLanguage> language =
          mockStatic(com.etendoerp.go.schemaforge.util.NeoLanguage.class)) {
        language.when(com.etendoerp.go.schemaforge.util.NeoLanguage::currentCode)
            .thenReturn(row[0]);

        NeoResponse response = handler.handle(create(validBody().put("dueDate", "2026-11-05")));

        assertEquals(row[1], errorMessage(response), row[0]);
      }
    }
  }

  private void givenAvailableAccountAndType() {
    FIN_FinancialAccount account = stubAccount(true);
    RemittanceType type = stubType();
    doReturn(true).when(handler).isTypeAvailable(account, type);
  }

  private static java.util.Date date(int year, int month, int day) {
    return java.util.Date.from(java.time.LocalDate.of(year, month, day)
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
  }

  // ---------------------------------------------------------------- due-items actions

  @Test
  void theHeaderDeclaresTheDueItemsAndProcessActions() {
    assertTrue(handler.servesActions());
    assertEquals(List.of("availableDueItems", "selectDueItems", "syncDueItems", "process",
        "reactivate", "sepaFileData"),
        new ArrayList<>(handler.actionContracts().keySet()));
  }

  // ---------------------------------------------------------------- sepaFileData

  /**
   * «Datos para el fichero SEPA» on a remittance that is not saved yet (record id {@code new}, the
   * SPA's POST body {@code {accountId}}): the remittance is never required, the account's
   * organization is used, and an account without an IBAN is answered with iban in missing — the
   * creditor identifier is the Classic AT-02 value for the organization's country and tax ID.
   */
  @Test
  void sepaFileDataAnswersForANewRemittanceWithTheAccountsOrganization() throws Exception {
    FIN_FinancialAccount account = bankAccount(ACC_ID, "BBVA", null);
    when(account.getName()).thenReturn("BBVA");
    org.openbravo.model.common.enterprise.Organization org = organization("ORG-1", "ES",
        "B-12345678");
    when(account.getOrganization()).thenReturn(org);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(true).when(handler).hasRemittanceMethods(account);
    doReturn(null).when(handler).creditorBusinessCode(account);
    NeoContext ctx = context("POST", NeoEndpointType.ACTION,
        new JSONObject().put("accountId", ACC_ID), "new");
    when(ctx.getFieldName()).thenReturn("sepaFileData");

    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals(ACC_ID, body.getString("accountId"));
    assertEquals("BBVA", body.getString("accountName"));
    assertTrue(body.isNull("iban"));
    assertFalse(body.getBoolean("ibanValid"));
    assertEquals("ORG-1", body.getString("organizationId"));
    assertEquals("ES", body.getString("country"));
    assertEquals(SepaCreditorId.build("ES", "000", "B12345678"), body.getString("creditorId"));
    assertTrue(body.getBoolean("creditorIdAvailable"));
    assertEquals(List.of("iban"), List.of(body.getJSONArray("missing").getString(0)));
    assertEquals(1, body.getJSONArray("missing").length());
    verify(handler, never()).loadRemittance(any());
  }

  /** Without the organization's tax ID and country, no creditor identifier and both are missing. */
  @Test
  void sepaFileDataNamesTheMissingOrganizationData() throws Exception {
    FIN_FinancialAccount account = bankAccount(ACC_ID, "BBVA", ES_IBAN);
    org.openbravo.model.common.enterprise.Organization org = organization("ORG-1", null, "  ");
    when(account.getOrganization()).thenReturn(org);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(null).when(handler).loadRemittance(REM_ID);
    doReturn(true).when(handler).hasRemittanceMethods(account);
    doReturn("000").when(handler).creditorBusinessCode(account);

    NeoResponse response = handler.handle(action("POST", "sepaFileData",
        new JSONObject().put("accountId", ACC_ID)));

    JSONObject body = response.getBody();
    assertTrue(body.getBoolean("ibanValid"));
    assertTrue(body.isNull("creditorId"));
    assertFalse(body.getBoolean("creditorIdAvailable"));
    assertEquals("[\"orgTaxId\",\"country\"]", body.getJSONArray("missing").toString());
  }

  /**
   * The creditor business code is the one the file carries: the CORE type's
   * Creditor_Business_Code, normalized as Classic does (whitespace removed, first three
   * characters); missing or blank falls back to 000.
   */
  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource(value = { "ABC|ABC", " A B C D |ABC", "NULL|000",
      "'   '|000" }, delimiter = '|', nullValues = "NULL")
  void sepaFileDataBuildsTheCreditorIdWithTheClientsBusinessCode(String stored, String suffix)
      throws Exception {
    FIN_FinancialAccount account = bankAccount(ACC_ID, "BBVA", ES_IBAN);
    org.openbravo.model.common.enterprise.Organization org = organization("ORG-1", "ES",
        "B-12345678");
    when(account.getOrganization()).thenReturn(org);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(null).when(handler).loadRemittance(REM_ID);
    doReturn(true).when(handler).hasRemittanceMethods(account);
    doReturn(stored).when(handler).creditorBusinessCode(account);

    JSONObject body = handler.handle(action("POST", "sepaFileData",
        new JSONObject().put("accountId", ACC_ID))).getBody();

    assertEquals(SepaCreditorId.build("ES", suffix, "B12345678"), body.getString("creditorId"));
    assertEquals(suffix, body.getString("creditorId").substring(4, 7));
    assertEquals(0, body.getJSONArray("missing").length());
  }

  /** A stored code that cannot form an identifier is named, not blamed on the tax ID. */
  @Test
  void sepaFileDataNamesAMalformedBusinessCode() throws Exception {
    FIN_FinancialAccount account = bankAccount(ACC_ID, "BBVA", ES_IBAN);
    org.openbravo.model.common.enterprise.Organization org = organization("ORG-1", "ES",
        "B-12345678");
    when(account.getOrganization()).thenReturn(org);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(null).when(handler).loadRemittance(REM_ID);
    doReturn(true).when(handler).hasRemittanceMethods(account);
    doReturn("A").when(handler).creditorBusinessCode(account);

    JSONObject body = handler.handle(action("POST", "sepaFileData",
        new JSONObject().put("accountId", ACC_ID))).getBody();

    assertTrue(body.isNull("creditorId"));
    assertEquals("[\"creditorBusinessCode\"]", body.getJSONArray("missing").toString());
  }

  /** An account that cannot be used for a remittance at all is refused with its own key. */
  @Test
  void sepaFileDataRefusesAnAccountWithoutTheRemittanceMethods() throws Exception {
    FIN_FinancialAccount account = bankAccount(ACC_ID, "BBVA", ES_IBAN);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(false).when(handler).hasRemittanceMethods(account);

    NeoResponse response = handler.handle(action("POST", "sepaFileData",
        new JSONObject().put("accountId", ACC_ID)));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceHeaderHandler.MSG_ACCOUNT_NOT_ALLOWED, messageKey(response));
  }

  /** The organizationId a caller names must be one the tenant owns: otherwise 404. */
  @Test
  void sepaFileDataAnswers404ForAnOrganizationTheTenantDoesNotOwn() throws Exception {
    sepaAccount();
    doReturn(null).when(handler).loadOrganization("ORG-OTHER");

    NeoResponse response = handler.handle(action("POST", "sepaFileData",
        new JSONObject().put("accountId", ACC_ID).put("organizationId", "ORG-OTHER")));

    assertEquals(404, response.getHttpStatus());
    verify(handler, never()).loadOrganizationInfo(any());
    verify(handler, never()).loadRemittance(any());
  }

  /** A saved remittance is read for its organization, which wins over the account's. */
  @Test
  void sepaFileDataOfASavedRemittanceUsesTheRemittancesOrganization() throws Exception {
    sepaAccount();
    org.openbravo.model.common.enterprise.Organization remittanceOrg = organization("ORG-REM",
        "FR", "FR12345678901");
    Remittance stored = mock(Remittance.class);
    when(stored.getOrganization()).thenReturn(remittanceOrg);
    doReturn(stored).when(handler).loadRemittance(REM_ID);

    JSONObject body = handler.handle(action("POST", "sepaFileData",
        new JSONObject().put("accountId", ACC_ID))).getBody();

    assertEquals("ORG-REM", body.getString("organizationId"));
    assertEquals("FR", body.getString("country"));
    assertEquals(SepaCreditorId.build("FR", "000", "FR12345678901"), body.getString("creditorId"));
  }

  /** An explicit organizationId is used as given, without reading the remittance. */
  @Test
  void sepaFileDataUsesAnExplicitOrganization() throws Exception {
    sepaAccount();
    org.openbravo.model.common.enterprise.Organization chosen = organization("ORG-CHOSEN", "ES",
        "B-87654321");
    doReturn(chosen).when(handler).loadOrganization("ORG-CHOSEN");

    JSONObject body = handler.handle(action("POST", "sepaFileData",
        new JSONObject().put("accountId", ACC_ID).put("organizationId", "ORG-CHOSEN")))
        .getBody();

    assertEquals("ORG-CHOSEN", body.getString("organizationId"));
    verify(handler, never()).loadRemittance(any());
  }

  /** A GET carries its parameters in the query string. */
  @Test
  void sepaFileDataReadsAGetsQueryParameters() throws Exception {
    sepaAccount();
    org.openbravo.model.common.enterprise.Organization chosen = organization("ORG-CHOSEN", "ES",
        "B-87654321");
    doReturn(chosen).when(handler).loadOrganization("ORG-CHOSEN");
    NeoContext ctx = action("GET", "sepaFileData", null);
    when(ctx.getQueryParams()).thenReturn(
        Map.of("accountId", " " + ACC_ID + " ", "organizationId", "ORG-CHOSEN"));

    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    assertEquals(ACC_ID, response.getBody().getString("accountId"));
    assertEquals("ORG-CHOSEN", response.getBody().getString("organizationId"));
  }

  @Test
  void sepaFileDataAnswers403WhileTheFlagIsOff() throws Exception {
    doReturn(false).when(handler).isFlagEnabled();

    NeoResponse response = handler.handle(action("POST", "sepaFileData",
        new JSONObject().put("accountId", ACC_ID)));

    assertEquals(403, response.getHttpStatus());
    verify(handler, never()).loadAccount(any());
  }

  @Test
  void sepaFileDataWithoutAnAccountIsRefusedByItsContract() throws Exception {
    for (JSONObject body : new JSONObject[] { new JSONObject(),
        new JSONObject().put("organizationId", "ORG-1") }) {
      NeoResponse response = handler.handle(action("POST", "sepaFileData", body));

      assertEquals(422, response.getHttpStatus());
      assertEquals("accountId",
          response.getBody().getJSONObject("error").getJSONArray("missingParameters").getString(0));
    }
    verify(handler, never()).loadAccount(any());
  }

  /** The MCP etendo_action call for a remittance not saved yet: id new, never a remittance read. */
  @Test
  void anMcpSepaFileDataCallWithIdNewAnswersTheAccount() throws Exception {
    sepaAccount();
    NeoContext ctx = context("POST", NeoEndpointType.ACTION,
        new JSONObject().put("accountId", ACC_ID), RemittanceHeaderHandler.NEW_RECORD_ID);
    when(ctx.getFieldName()).thenReturn("sepaFileData");
    when(ctx.isMcpOrigin()).thenReturn(true);

    NeoResponse response = handler.handle(ctx);

    assertEquals(200, response.getHttpStatus());
    assertEquals("ORG-1", response.getBody().getString("organizationId"));
    verify(handler, never()).loadRemittance(any());
  }

  /** A tax ID too long to form an identifier (over 28 characters) is reported as the tax ID. */
  @Test
  void sepaFileDataReportsATaxIdTooLongForAnIdentifier() throws Exception {
    FIN_FinancialAccount account = bankAccount(ACC_ID, "BBVA", ES_IBAN);
    org.openbravo.model.common.enterprise.Organization org = organization("ORG-1", "ES",
        "B12345678901234567890123456789");
    when(account.getOrganization()).thenReturn(org);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(true).when(handler).hasRemittanceMethods(account);
    doReturn(null).when(handler).creditorBusinessCode(account);
    doReturn(null).when(handler).loadRemittance(REM_ID);

    JSONObject body = handler.handle(action("POST", "sepaFileData",
        new JSONObject().put("accountId", ACC_ID))).getBody();

    assertTrue(body.isNull("creditorId"));
    assertFalse(body.getBoolean("creditorIdAvailable"));
    assertEquals("[\"orgTaxId\"]", body.getJSONArray("missing").toString());
  }

  /** A remittance account with a valid IBAN in Spain, organization ORG-1 (ES, B-12345678). */
  private FIN_FinancialAccount sepaAccount() {
    FIN_FinancialAccount account = bankAccount(ACC_ID, "BBVA", ES_IBAN);
    org.openbravo.model.common.enterprise.Organization org = organization("ORG-1", "ES",
        "B-12345678");
    when(account.getOrganization()).thenReturn(org);
    doReturn(account).when(handler).loadAccount(ACC_ID);
    doReturn(true).when(handler).hasRemittanceMethods(account);
    doReturn(null).when(handler).creditorBusinessCode(account);
    doReturn(null).when(handler).loadRemittance(REM_ID);
    return account;
  }

  private static org.openbravo.model.common.enterprise.Organization organization(String id,
      String countryIso, String taxId) {
    org.openbravo.model.common.enterprise.Organization org =
        mock(org.openbravo.model.common.enterprise.Organization.class);
    when(org.getId()).thenReturn(id);
    org.openbravo.model.common.enterprise.OrganizationInformation info =
        mock(org.openbravo.model.common.enterprise.OrganizationInformation.class);
    when(info.getTaxID()).thenReturn(taxId);
    if (countryIso != null) {
      Country country = mock(Country.class);
      when(country.getISOCountryCode()).thenReturn(countryIso);
      org.openbravo.model.common.geography.Location location =
          mock(org.openbravo.model.common.geography.Location.class);
      when(location.getCountry()).thenReturn(country);
      when(info.getLocationAddress()).thenReturn(location);
    }
    when(org.getOrganizationInformationList()).thenReturn(List.of(info));
    return org;
  }

  @Test
  void availableDueItemsReadsItsParametersFromTheQueryString() throws Exception {
    Remittance stored = stubStored(false);
    NeoResponse listed = NeoResponse.ok(new JSONObject().put("items", new JSONArray()));
    org.mockito.ArgumentCaptor<JSONObject> params = org.mockito.ArgumentCaptor.forClass(JSONObject.class);
    doReturn(listed).when(handler).availableDueItems(org.mockito.ArgumentMatchers.eq(stored),
        params.capture());
    NeoContext ctx = action("GET", "availableDueItems", null);
    Map<String, String> query = new HashMap<>();
    query.put("search", "bbva");
    query.put("page", "2");
    query.put("pageSize", "25");
    when(ctx.getQueryParams()).thenReturn(query);

    assertSameResponse(listed, handler.handle(ctx));
    assertEquals("bbva", params.getValue().getString("search"));
    assertEquals(2, params.getValue().getInt("page"));
    assertEquals(25, params.getValue().getInt("pageSize"));
  }

  @Test
  void availableDueItemsFallsBackToTheRequestParameters() throws Exception {
    Remittance stored = stubStored(false);
    org.mockito.ArgumentCaptor<JSONObject> params = org.mockito.ArgumentCaptor.forClass(JSONObject.class);
    doReturn(NeoResponse.ok(new JSONObject())).when(handler)
        .availableDueItems(org.mockito.ArgumentMatchers.eq(stored), params.capture());
    HttpServletRequest request = request(Map.of());
    when(request.getParameter("page")).thenReturn("4");
    doReturn(request).when(handler).currentRequest();

    handler.handle(action("GET", "availableDueItems", null));

    assertEquals(4, params.getValue().getInt("page"));
  }

  @Test
  void availableDueItemsReadsItsParametersFromAPostBody() throws Exception {
    Remittance stored = stubStored(false);
    JSONObject body = new JSONObject().put("search", "FV-001");
    doReturn(NeoResponse.ok(new JSONObject())).when(handler).availableDueItems(stored, body);

    assertEquals(200, handler.handle(action("POST", "availableDueItems", body)).getHttpStatus());
    verify(handler).availableDueItems(stored, body);
  }

  @Test
  void selectDueItemsOnlyAcceptsPost() throws Exception {
    stubStored(false);

    for (String method : new String[] { "GET", "PUT", "DELETE" }) {
      NeoResponse response = handler.handle(action(method, "selectDueItems",
          new JSONObject().put("ids", new JSONArray().put("IPS-1"))));

      assertEquals(405, response.getHttpStatus(), method);
    }
    verify(handler, never()).selectDueItems(any(), any());
  }

  @Test
  void selectDueItemsWithoutIdsIsRefusedByItsContract() throws Exception {
    stubStored(false);

    for (JSONObject body : new JSONObject[] { new JSONObject(),
        new JSONObject().put("ids", new JSONArray()) }) {
      NeoResponse response = handler.handle(action("POST", "selectDueItems", body));

      assertEquals(422, response.getHttpStatus());
      assertEquals("ids",
          response.getBody().getJSONObject("error").getJSONArray("missingParameters").getString(0));
    }
    verify(handler, never()).selectDueItems(any(), any());
  }

  @Test
  void selectDueItemsRunsOnAnUnprocessedRemittance() throws Exception {
    Remittance stored = stubStored(false);
    JSONObject body = new JSONObject().put("ids", new JSONArray().put("IPS-1"));
    NeoResponse selected = NeoResponse.ok(new JSONObject().put("created", 1));
    doReturn(selected).when(handler).selectDueItems(stored, body);

    assertSameResponse(selected, handler.handle(action("POST", "selectDueItems", body)));
  }

  @Test
  void syncDueItemsOnlyAcceptsPost() throws Exception {
    stubStored(false);
    JSONObject body = new JSONObject().put("remove", new JSONArray().put("IPS-1"));

    for (String method : new String[] { "GET", "PUT", "DELETE" }) {
      assertEquals(405, handler.handle(action(method, "syncDueItems", body)).getHttpStatus(),
          method);
    }
    verify(handler, never()).syncDueItems(any(), any());
  }

  @Test
  void syncDueItemsIsRoutedWithItsBodyAndItsAnswerReturnedAsIs() throws Exception {
    Remittance stored = stubStored(false);
    JSONObject body = new JSONObject()
        .put("items", new JSONArray().put(new JSONObject().put("id", "IPS-1").put("amount", 50)))
        .put("remove", new JSONArray().put("IPS-2"));
    NeoResponse synced = NeoResponse.ok(new JSONObject().put("created", 1).put("removed", 1));
    doReturn(synced).when(handler).syncDueItems(stored, body);

    assertSameResponse(synced, handler.handle(action("POST", "syncDueItems", body)));
    verify(handler, never()).selectDueItems(any(), any());
    verify(handler, never()).availableDueItems(any(), any());
    contextStatic.verify(OBContext::restorePreviousMode);
  }

  /**
   * Alex W2 (ETP-6148 round 3): {@code syncDueItems} deletes lines before the Classic run, so an
   * unexpected exception after the deletes must not leave them pending in the session: the 500
   * path rolls back (unless a batch owns the transaction).
   */
  @Test
  void anUnexpectedSyncFailureRollsBackBeforeAnswering500() throws Exception {
    Remittance stored = stubStored(false);
    JSONObject body = new JSONObject().put("remove", new JSONArray().put("IPS-2"));
    org.mockito.Mockito.doThrow(new IllegalStateException("boom after the deletes"))
        .when(handler).syncDueItems(stored, body);
    try (org.mockito.MockedStatic<NeoErrorResponses> errors =
        org.mockito.Mockito.mockStatic(NeoErrorResponses.class)) {

      NeoResponse response = handler.handle(action("POST", "syncDueItems", body));

      assertEquals(500, response.getHttpStatus());
      errors.verify(NeoErrorResponses::rollbackUnlessBatchOwned);
    }
    contextStatic.verify(OBContext::restorePreviousMode);
  }

  @Test
  void syncDueItemsOnAProcessedRemittanceIsRefused422AndOnAnUnknownOne404() throws Exception {
    JSONObject body = new JSONObject().put("remove", new JSONArray().put("IPS-1"));
    doReturn(null).when(handler).loadRemittance(REM_ID);
    assertEquals(404, handler.handle(action("POST", "syncDueItems", body)).getHttpStatus());

    stubStored(true);
    NeoResponse response = handler.handle(action("POST", "syncDueItems", body));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceHeaderHandler.MSG_PROCESSED, messageKey(response));
    verify(handler, never()).syncDueItems(any(), any());
  }

  @Test
  void syncDueItemsAnswers403WhileTheFlagIsOff() throws Exception {
    doReturn(false).when(handler).isFlagEnabled();

    assertEquals(403, handler.handle(action("POST", "syncDueItems",
        new JSONObject().put("remove", new JSONArray().put("IPS-1")))).getHttpStatus());
    verify(handler, never()).loadRemittance(any());
    verify(handler, never()).syncDueItems(any(), any());
  }

  @Test
  void anUnknownParameterIsRefusedByTheContract() throws Exception {
    stubStored(false);

    NeoResponse response = handler.handle(action("POST", "availableDueItems",
        new JSONObject().put("color", "blue")));

    assertEquals(422, response.getHttpStatus());
    verify(handler, never()).availableDueItems(any(), any());
  }

  @Test
  void bothActionsAnswer404ForAnUnknownRemittanceAnd422ForAProcessedOne() throws Exception {
    JSONObject select = new JSONObject().put("ids", new JSONArray().put("IPS-1"));
    doReturn(null).when(handler).loadRemittance(REM_ID);
    assertEquals(404, handler.handle(action("GET", "availableDueItems", null)).getHttpStatus());
    assertEquals(404, handler.handle(action("POST", "selectDueItems", select)).getHttpStatus());

    stubStored(true);
    for (NeoResponse response : new NeoResponse[] {
        handler.handle(action("GET", "availableDueItems", null)),
        handler.handle(action("POST", "selectDueItems", select)) }) {
      assertEquals(422, response.getHttpStatus());
      assertEquals(RemittanceHeaderHandler.MSG_PROCESSED, messageKey(response));
    }
    verify(handler, never()).availableDueItems(any(), any());
    verify(handler, never()).selectDueItems(any(), any());
  }

  @Test
  void bothActionsAnswer403WhileTheFlagIsOff() throws Exception {
    doReturn(false).when(handler).isFlagEnabled();

    for (String name : new String[] { "availableDueItems", "selectDueItems" }) {
      NeoResponse response = handler.handle(action("POST", name,
          new JSONObject().put("ids", new JSONArray().put("IPS-1"))));

      assertEquals(403, response.getHttpStatus(), name);
    }
    verify(handler, never()).loadRemittance(any());
  }

  @Test
  void aFailureInsideAnActionIsAnswered500() throws Exception {
    Remittance stored = stubStored(false);
    Mockito.doThrow(new IllegalStateException("boom")).when(handler)
        .availableDueItems(org.mockito.ArgumentMatchers.eq(stored), any());

    assertEquals(500, handler.handle(action("GET", "availableDueItems", null)).getHttpStatus());
    contextStatic.verify(OBContext::restorePreviousMode);
  }

  @Test
  void anUnknownActionIsNotRoutedToTheDeclaredActions() throws Exception {
    assertNull(handler.handle(action("POST", "archiveEverything", new JSONObject())));
    verify(handler, never()).availableDueItems(any(), any());
    verify(handler, never()).selectDueItems(any(), any());
    verify(handler, never()).syncDueItems(any(), any());
    verify(handler, never()).processRemittance(any(), any());
    verify(handler, never()).reactivateRemittance(any());
    verify(handler, never()).loadRemittance(any());
  }

  /** An action context may carry an EMPTY query map: the REST GET params come from the request. */
  @Test
  void anEmptyQueryMapReadsTheRestGetParametersFromTheServletRequest() throws Exception {
    Remittance stored = stubStored(false);
    org.mockito.ArgumentCaptor<JSONObject> params = org.mockito.ArgumentCaptor.forClass(JSONObject.class);
    doReturn(NeoResponse.ok(new JSONObject())).when(handler)
        .availableDueItems(org.mockito.ArgumentMatchers.eq(stored), params.capture());
    HttpServletRequest request = request(Map.of());
    when(request.getParameter("search")).thenReturn("FV-2026");
    when(request.getParameter("pageSize")).thenReturn("10");
    doReturn(request).when(handler).currentRequest();
    NeoContext ctx = action("GET", "availableDueItems", null);
    when(ctx.getQueryParams()).thenReturn(new HashMap<>());

    handler.handle(ctx);

    assertEquals("FV-2026", params.getValue().getString("search"));
    assertEquals(10, params.getValue().getInt("pageSize"));
  }

  // ---------------------------------------------------------------- process action

  @Test
  void processOnlyAcceptsPost() throws Exception {
    stubStored(false);

    for (String method : new String[] { "GET", "PUT", "PATCH", "DELETE" }) {
      assertEquals(405, handler.handle(action(method, "process", new JSONObject()))
          .getHttpStatus(), method);
    }
    verify(handler, never()).processRemittance(any(), any());
  }

  @Test
  void processIsRoutedWithItsBodyAndItsAnswerReturnedAsIs() throws Exception {
    Remittance stored = stubStored(false);
    NeoResponse processed = NeoResponse.ok(new JSONObject().put("status", "PR").put("message", "ok"));
    for (JSONObject body : new JSONObject[] { new JSONObject(),
        new JSONObject().put("grouping", "P"), new JSONObject().put("grouping", "GGPD") }) {
      doReturn(processed).when(handler).processRemittance(stored, body);

      org.junit.jupiter.api.Assertions.assertSame(processed,
          handler.handle(action("POST", "process", body)));
      verify(handler).processRemittance(stored, body);
    }
  }

  @Test
  void anInvalidGroupingIsRefusedByTheContract() throws Exception {
    stubStored(false);

    NeoResponse response = handler.handle(action("POST", "process",
        new JSONObject().put("grouping", "BY_INVOICE")));

    assertEquals(422, response.getHttpStatus());
    verify(handler, never()).processRemittance(any(), any());
  }

  @Test
  void processingAProcessedRemittanceIsRefusedAlreadyProcessed() throws Exception {
    stubStored(true);

    NeoResponse response = handler.handle(action("POST", "process", new JSONObject()));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceProcessAction.MSG_ALREADY_PROCESSED, messageKey(response));
  }

  @Test
  void processAnswers404ForAnUnknownRemittanceAnd403WhileTheFlagIsOff() throws Exception {
    doReturn(null).when(handler).loadRemittance(REM_ID);
    assertEquals(404, handler.handle(action("POST", "process", new JSONObject())).getHttpStatus());

    doReturn(false).when(handler).isFlagEnabled();
    assertEquals(403, handler.handle(action("POST", "process", new JSONObject())).getHttpStatus());
    verify(handler, never()).processRemittance(any(), any());
  }

  @Test
  void processReadsItsGroupingFromAFieldValuesBody() throws Exception {
    Remittance stored = stubStored(false);
    org.mockito.ArgumentCaptor<JSONObject> params = org.mockito.ArgumentCaptor.forClass(JSONObject.class);
    doReturn(NeoResponse.ok(new JSONObject())).when(handler)
        .processRemittance(org.mockito.ArgumentMatchers.eq(stored), params.capture());

    NeoResponse response = handler.handle(action("POST", "process",
        new JSONObject().put("fieldValues", new JSONObject().put("grouping", "GGPD"))));

    assertEquals(200, response.getHttpStatus());
    assertEquals("GGPD", params.getValue().getString("grouping"));
    assertFalse(params.getValue().has("fieldValues"), "the process step gets the inner values");
  }

  @Test
  void anUnknownParameterInsideFieldValuesIsRefused422() throws Exception {
    stubStored(false);

    NeoResponse response = handler.handle(action("POST", "process",
        new JSONObject().put("fieldValues", new JSONObject().put("bogus", 1))));

    assertEquals(422, response.getHttpStatus());
    assertEquals("bogus", response.getBody().getJSONObject("error")
        .getJSONArray("unknownParameters").getString(0));
    verify(handler, never()).processRemittance(any(), any());
  }

  @Test
  void actionParamsUnwrapsFieldValuesOnlyForTheActionsThatDeclareIt() throws Exception {
    JSONObject inner = new JSONObject().put("grouping", "P");
    JSONObject wrapped = new JSONObject().put("fieldValues", inner);
    JSONObject flat = new JSONObject().put("grouping", "P");

    org.junit.jupiter.api.Assertions.assertSame(inner, handler.actionParams("process", wrapped));
    org.junit.jupiter.api.Assertions.assertSame(flat, handler.actionParams("process", flat),
        "a flat body is still accepted");
    org.junit.jupiter.api.Assertions.assertSame(wrapped,
        handler.actionParams("availableDueItems", wrapped), "not a fieldValues contract");
    org.junit.jupiter.api.Assertions.assertSame(wrapped, handler.actionParams(null, wrapped));
  }

  // ---------------------------------------------------------------- reactivate action

  @Test
  void reactivateOnlyAcceptsPost() throws Exception {
    stubStored(true);

    for (String method : new String[] { "GET", "PUT", "PATCH", "DELETE" }) {
      assertEquals(405, handler.handle(action(method, "reactivate", new JSONObject()))
          .getHttpStatus(), method);
    }
    verify(handler, never()).reactivateRemittance(any());
  }

  @Test
  void reactivateIsRoutedAndItsAnswerReturnedAsIs() throws Exception {
    Remittance stored = stubStored(true);
    NeoResponse reactivated = NeoResponse.ok(new JSONObject().put("status", "PE"));
    doReturn(reactivated).when(handler).reactivateRemittance(stored);

    org.junit.jupiter.api.Assertions.assertSame(reactivated,
        handler.handle(action("POST", "reactivate", new JSONObject())));
    verify(handler, never()).processRemittance(any(), any());
  }

  @Test
  void reactivatingAnUnprocessedRemittanceIsRefusedNotProcessed() throws Exception {
    stubStored(false);

    NeoResponse response = handler.handle(action("POST", "reactivate", new JSONObject()));

    assertEquals(422, response.getHttpStatus());
    assertEquals(RemittanceProcessAction.MSG_NOT_PROCESSED, messageKey(response));
  }

  @Test
  void reactivateWithAnEmptyFieldValuesBodyPassesValidation() throws Exception {
    Remittance stored = stubStored(true);
    NeoResponse reactivated = NeoResponse.ok(new JSONObject().put("status", "PE"));
    doReturn(reactivated).when(handler).reactivateRemittance(stored);

    org.junit.jupiter.api.Assertions.assertSame(reactivated, handler.handle(
        action("POST", "reactivate", new JSONObject().put("fieldValues", new JSONObject()))));
  }

  @Test
  void reactivateTakesNoParameters() throws Exception {
    stubStored(true);

    NeoResponse response = handler.handle(action("POST", "reactivate",
        new JSONObject().put("grouping", "P")));

    assertEquals(422, response.getHttpStatus());
    verify(handler, never()).reactivateRemittance(any());
  }

  @Test
  void reactivateAnswers404ForAnUnknownRemittanceAnd403WhileTheFlagIsOff() throws Exception {
    doReturn(null).when(handler).loadRemittance(REM_ID);
    assertEquals(404, handler.handle(action("POST", "reactivate", new JSONObject()))
        .getHttpStatus());

    doReturn(false).when(handler).isFlagEnabled();
    assertEquals(403, handler.handle(action("POST", "reactivate", new JSONObject()))
        .getHttpStatus());
    verify(handler, never()).reactivateRemittance(any());
  }

  private static NeoContext action(String method, String name, JSONObject body) {
    NeoContext ctx = context(method, NeoEndpointType.ACTION, body, REM_ID);
    when(ctx.getFieldName()).thenReturn(name);
    return ctx;
  }

  private static void assertSameResponse(NeoResponse expected, NeoResponse actual) {
    org.junit.jupiter.api.Assertions.assertSame(expected, actual);
  }

  // ---------------------------------------------------------------- callout helpers

  /** The callout body the endpoint reads: {@code {field, value, formState}}. */
  private void givenCallout(String field, Object value, JSONObject formState) throws Exception {
    doReturn(new JSONObject().put("field", field).put("value", value).put("formState", formState))
        .when(handler).readCalloutBody();
  }

  private static NeoContext callout() {
    return context("POST", NeoEndpointType.CALLOUT, null, null);
  }

  private static String suggestedName(NeoResponse response) throws Exception {
    JSONObject name = response.getBody().getJSONObject("updates").getJSONObject("name");
    assertTrue(name.getBoolean("suggested"));
    return name.getString("value");
  }

  private static void assertEmptyCallout(NeoResponse response) throws Exception {
    assertEquals(200, response.getHttpStatus());
    assertEquals(0, response.getBody().getJSONObject("updates").length());
    assertEquals(0, response.getBody().getJSONObject("combos").length());
    assertEquals(0, response.getBody().getJSONArray("messages").length());
  }

  private RemittanceType stubTypeWithDirections(boolean payIn, boolean payOut) {
    FIN_PaymentMethod method = mock(FIN_PaymentMethod.class);
    when(method.isPayinAllow()).thenReturn(payIn);
    when(method.isPayoutAllow()).thenReturn(payOut);
    RemittanceType type = mock(RemittanceType.class);
    when(type.getPaymentMethod()).thenReturn(method);
    doReturn(type).when(handler).loadType(TYPE_ID);
    return type;
  }

  private static javax.servlet.ServletInputStream servletStream(String raw) {
    java.io.ByteArrayInputStream bytes = new java.io.ByteArrayInputStream(
        raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new javax.servlet.ServletInputStream() {
      @Override
      public int read() {
        return bytes.read();
      }

      @Override
      public boolean isFinished() {
        return bytes.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(javax.servlet.ReadListener listener) {
        // not used
      }
    };
  }
}
