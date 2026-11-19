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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Real-database guard of the ETP-5473 rule that a financial account's country is NEVER derived
 * from its IBAN: a REST PATCH {@code {iBAN, updated}} on a Bank account stored without a country —
 * what the remittance window's «Datos para el fichero SEPA» block sends for a legacy or sample-data
 * account — is refused 400 {@code ETGO_FinAccIbanRequiresCountry} and nothing is persisted, whether
 * the body leaves the country out or sends {@code country: null}. The same IBAN with its country is
 * accepted and the row trigger ({@code FIN_FINANCIAL_ACCOUNT_TRG2}, the IBAN/country pair check)
 * takes the pair. (ETP-6148 briefly derived the country from the prefix; the user reverted it.)
 *
 * <p>Through the real NEO REST dispatch ({@link NeoRestTestClient}) of
 * {@code financial-account/account}. The account is created through the DAL
 * ({@link FinancialAccountSupport#createAccount}, as the bank-connection flow does), so it has no
 * country. Writes run under a caller-owned transaction, so the generic CRUD never commits; every
 * test rolls back.
 *
 * <p>A class of its own: {@code FinancialAccountHandlerCountryTest} specifies the rule with
 * Mockito; what only the database shows is that a refused PATCH leaves the row untouched and that
 * the accepted pair is the one the trigger takes.
 *
 * @covers com.etendoerp.go.schemaforge.FinancialAccountHandler
 */
public class FinancialAccountIbanCountryIntegrationTest extends WeldBaseTest {

  private static final String SPEC = "financial-account";
  private static final String ENTITY = "account";
  private static final String EURO_ID = "102";
  private static final String VALID_ES_IBAN = "ES9121000418450200051332";
  /** {@code C_Country} Spain. */
  private static final String SPAIN_ID = "106";

  /**
   * The generic CRUD's JSON service singleton reads the DAL servlet context, which a test JVM does
   * not have; OBBaseTest installs its mock one around each case.
   */
  @Override
  protected boolean shouldMockServletContext() {
    return true;
  }

  @Before
  public void openTheRequest() {
    setTestAdminContext();
    // These direct handler calls bypass NeoServlet, which opens this mode after authentication.
    OBContext.setAdminMode();
    BatchService.beginCallerOwnedTransaction();
  }

  @After
  public void rollBack() {
    try {
      BatchService.endCallerOwnedTransaction();
      OBDal.getInstance().rollbackAndClose();
    } finally {
      while (OBContext.getOBContext() != null
          && OBContext.getOBContext().isInAdministratorMode()) {
        OBContext.restorePreviousMode();
      }
    }
  }

  /** No country in the body: refused, and the country is not derived from the IBAN prefix. */
  @Test
  public void anIbanGivenToACountrylessBankAccountIsRefusedAndNothingIsSaved() throws Exception {
    FIN_FinancialAccount account = countrylessBankAccount();

    NeoResponse response = NeoRestTestClient.patch(SPEC, ENTITY, account.getId(),
        new JSONObject().put("iBAN", VALID_ES_IBAN));

    assertIbanRequiresCountry(response, account);
  }

  /** An explicit {@code country: null} is refused the same way. */
  @Test
  public void anExplicitNullCountryIsStillRefused400() throws Exception {
    FIN_FinancialAccount account = countrylessBankAccount();

    NeoResponse response = NeoRestTestClient.patch(SPEC, ENTITY, account.getId(),
        new JSONObject().put("iBAN", VALID_ES_IBAN).put("country", JSONObject.NULL));

    assertIbanRequiresCountry(response, account);
  }

  /** With its country the IBAN is accepted: the way forward is setting the country first. */
  @Test
  public void theSameIbanWithItsCountryIsSaved() throws Exception {
    FIN_FinancialAccount account = countrylessBankAccount();

    NeoResponse response = NeoRestTestClient.patch(SPEC, ENTITY, account.getId(),
        new JSONObject().put("iBAN", VALID_ES_IBAN).put("country", SPAIN_ID));

    assertTrue(String.valueOf(response.getBody()), response.getHttpStatus() < 300);
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(account);
    assertNotNull(account.getCountry());
    assertEquals("ES", account.getCountry().getISOCountryCode());
    assertEquals(VALID_ES_IBAN,
        FinancialAccountCountrySupport.normalizeIban(account.getIBAN()));
  }

  private static void assertIbanRequiresCountry(NeoResponse response,
      FIN_FinancialAccount account) throws Exception {
    assertEquals(String.valueOf(response.getBody()), 400, response.getHttpStatus());
    assertEquals(FinancialAccountCountrySupport.MSG_IBAN_REQUIRES_COUNTRY,
        response.getBody().getJSONObject("error").getJSONArray("messageKeys").getString(0));
    OBDal.getInstance().flush();
    OBDal.getInstance().refresh(account);
    assertNull("no country derived", account.getCountry());
    assertNull("no IBAN saved", account.getIBAN());
  }

  /** A Bank account stored without a country or IBAN, as the bank-connection flow creates one. */
  private FIN_FinancialAccount countrylessBankAccount() {
    FIN_FinancialAccount account = FinancialAccountSupport.createAccount(
        OBDal.getInstance().get(Client.class, TEST_CLIENT_ID),
        OBDal.getInstance().get(Organization.class, TEST_ORG_ID),
        OBDal.getInstance().get(Currency.class, EURO_ID),
        "SF country IT " + System.nanoTime(), FinancialAccountHandler.TYPE_BANK);
    assertNull("the fixture has no country", account.getCountry());
    return account;
  }
}
