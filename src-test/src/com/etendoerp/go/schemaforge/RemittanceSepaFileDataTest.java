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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for {@link RemittanceSepaFileData}, the «Datos para el fichero SEPA» answer: the
 * creditor business code normalized as Classic's file does, and the {@code missing} list — what
 * keeps the file from being generated — for each combination of IBAN validity, organization
 * country and tax ID, including a tax ID too long to form an identifier and a stored business code
 * that cannot form one. Pure logic: the account is a mock, nothing touches the DAL.
 *
 * <p>A class of its own: {@code RemittanceSepaFileData} is a new production class.
 * {@code RemittanceHeaderHandlerTest} pins how the header reads the account, the organization and
 * the business code and routes the action to it.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaFileData
 */
class RemittanceSepaFileDataTest {

  private static final String TAX_ID = "B12345678";
  /** 29 characters: one more than a creditor identifier can carry. */
  private static final String TAX_ID_TOO_LONG = "B123456789012345678901234567X";

  @Test
  void theActionIsAReadWithARequiredAccountAndAnOptionalOrganization() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    RemittanceSepaFileData.addContract(contracts);

    NeoActionContract contract = contracts.get("sepaFileData");
    assertFalse(contract.isMutating());
    List<String> required = new ArrayList<>();
    List<String> optional = new ArrayList<>();
    contract.getParams().forEach(p -> (p.isRequired() ? required : optional).add(p.getName()));
    assertEquals(List.of("accountId"), required);
    assertEquals(List.of("organizationId", "remittanceId"), optional);
    assertTrue(RemittanceSepaFileData.serves("sepaFileData"));
    assertFalse(RemittanceSepaFileData.serves("process"));
    assertFalse(RemittanceSepaFileData.serves(null));
  }

  @ParameterizedTest(name = "[{0}] -> {1}")
  @CsvSource(value = { "ABC|ABC", "ABCD|ABC", " A B C |ABC", "a1|a1", "NULL|000", "''|000",
      "'   '|000" }, delimiter = '|', nullValues = "NULL")
  void theBusinessCodeIsNormalizedAsClassicDoesAndDefaultsTo000(String stored, String suffix) {
    assertEquals(suffix, RemittanceSepaFileData.suffixOf(stored));
  }

  @Test
  void everythingPresentGivesTheIdentifierAndNothingMissing() throws Exception {
    JSONObject body = build(true, "ES", TAX_ID, null);

    assertEquals(SepaCreditorId.build("ES", "000", TAX_ID), body.getString("creditorId"));
    assertTrue(body.getBoolean("creditorIdAvailable"));
    assertEquals(0, body.getJSONArray("missing").length());
    assertEquals("ACC-1", body.getString("accountId"));
    assertEquals("BBVA", body.getString("accountName"));
    assertEquals("ES", body.getString("country"));
    assertEquals("ORG-1", body.getString("organizationId"));
  }

  @Test
  void eachMissingPieceIsNamedInOrder() throws Exception {
    assertEquals(List.of("iban"), missing(build(false, "ES", TAX_ID, null)));
    assertEquals(List.of("orgTaxId"), missing(build(true, "ES", "  ", null)));
    assertEquals(List.of("country"), missing(build(true, null, TAX_ID, null)));
    assertEquals(List.of("iban", "orgTaxId", "country"), missing(build(false, " ", null, null)));
    assertTrue(build(true, null, TAX_ID, null).isNull("creditorId"),
        "no identifier without the organization's country");
  }

  /** A tax ID over 28 characters cannot form an identifier: reported as the tax ID. */
  @Test
  void aTaxIdTooLongForAnIdentifierIsReportedAsTheTaxId() throws Exception {
    JSONObject body = build(true, "ES", TAX_ID_TOO_LONG, null);

    assertTrue(body.isNull("creditorId"));
    assertFalse(body.getBoolean("creditorIdAvailable"));
    assertEquals(List.of("orgTaxId"), missing(body));
  }

  /** A stored code that cannot form one, with good organization data, is named as the code. */
  @Test
  void aMalformedBusinessCodeIsNamedAndNotBlamedOnTheTaxId() throws Exception {
    JSONObject body = build(true, "ES", TAX_ID, "A-");

    assertTrue(body.isNull("creditorId"));
    assertEquals(List.of("creditorBusinessCode"), missing(body));
  }

  // ---------------------------------------------------------------- creditor id on the account

  private static final String STORED = "ES82000B87654323";

  @Test
  void theActionsAreTheReadAndTheCreditorIdWrite() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    RemittanceSepaFileData.addContract(contracts);

    NeoActionContract save = contracts.get("saveCreditorId");
    assertTrue(save.isMutating());
    List<String> required = new ArrayList<>();
    save.getParams().forEach(p -> {
      if (p.isRequired()) {
        required.add(p.getName());
      }
    });
    assertEquals(List.of("accountId", "creditorId"), required);
    assertTrue(RemittanceSepaFileData.serves("saveCreditorId"));
    List<String> optional = new ArrayList<>();
    save.getParams().forEach(p -> {
      if (!p.isRequired()) {
        optional.add(p.getName());
      }
    });
    assertEquals(List.of("remittanceId", "saveToAccount", "organizationId"), optional);
    List<String> readOptional = new ArrayList<>();
    contracts.get("sepaFileData").getParams().forEach(p -> {
      if (!p.isRequired()) {
        readOptional.add(p.getName());
      }
    });
    assertEquals(List.of("organizationId", "remittanceId"), readOptional);
  }

  @Test
  void aSavedValidIdIsTheEffectiveOneAndTheSuggestedStaysAvailable() throws Exception {
    JSONObject body = RemittanceSepaFileData.build(account("ES9121000418450200051332"), true,
        "ORG-1", "ES", TAX_ID, null, STORED);

    assertEquals(STORED, body.getString("storedCreditorId"));
    assertTrue(body.getBoolean("storedCreditorIdValid"));
    assertEquals(SepaCreditorId.build("ES", "000", TAX_ID), body.getString("suggestedCreditorId"));
    assertEquals(STORED, body.getString("creditorId"));
    assertEquals("account", body.getString("creditorIdSource"));
    assertEquals(STORED, body.getString("accountCreditorId"));
    assertTrue(body.isNull("remittanceCreditorId"));
    assertTrue(body.getBoolean("creditorIdAvailable"));
  }

  @Test
  void withNothingSavedTheComputedOneIsSuggestedOrTheIdIsMissing() throws Exception {
    JSONObject suggested = build(true, "ES", TAX_ID, null);
    assertTrue(suggested.isNull("storedCreditorId"));
    assertTrue(suggested.isNull("storedCreditorIdValid"));
    assertEquals("suggested", suggested.getString("creditorIdSource"));

    JSONObject missing = build(true, null, TAX_ID, null);
    assertEquals("missing", missing.getString("creditorIdSource"));
    assertTrue(missing.isNull("creditorId"));
    assertFalse(missing.getBoolean("creditorIdAvailable"));
  }

  /** An invalid saved value is reported as such and never used. */
  @Test
  void anInvalidSavedValueIsNotUsed() throws Exception {
    JSONObject body = RemittanceSepaFileData.build(account("ES9121000418450200051332"), true,
        "ORG-1", "ES", TAX_ID, null, "ES00000B87654323");

    assertTrue(body.isNull("storedCreditorId"));
    assertFalse(body.getBoolean("storedCreditorIdValid"));
    assertEquals("suggested", body.getString("creditorIdSource"));
  }

  private static final String COPY = "ES97000B12345678";

  /** The remittance's own copy is the effective one, whatever the account holds. */
  @Test
  void theRemittanceCopyWinsOverTheAccountDefault() throws Exception {
    JSONObject body = RemittanceSepaFileData.build(inputs(account("ES9121000418450200051332"), true)
        .accountCreditorId(STORED).remittanceCreditorId(COPY));

    assertEquals(COPY, body.getString("remittanceCreditorId"));
    assertEquals(STORED, body.getString("accountCreditorId"));
    assertEquals(COPY, body.getString("creditorId"));
    assertEquals("remittance", body.getString("creditorIdSource"));
    assertTrue(body.getBoolean("remittanceCreditorIdValid"));
  }

  /** A present but invalid copy is NOT replaced by the account's: the file would be refused. */
  @Test
  void anInvalidRemittanceCopyIsNotReplacedByTheAccountDefault() throws Exception {
    JSONObject body = RemittanceSepaFileData.build(inputs(account("ES9121000418450200051332"), true)
        .accountCreditorId(STORED).remittanceCreditorId("ES00000B87654323"));

    assertTrue(body.isNull("remittanceCreditorId"));
    assertFalse(body.getBoolean("remittanceCreditorIdValid"));
    assertEquals("suggested", body.getString("creditorIdSource"));
    assertEquals(SepaCreditorId.build("ES", "000", TAX_ID), body.getString("creditorId"));
  }

  private static final String ACC_IBAN = "ES9121000418450200051332";
  private static final String REM_IBAN = "DE89370400440532013000";

  @Test
  void theRemittanceIbanWinsAndTheAccountsIsReportedApart() throws Exception {
    JSONObject body = RemittanceSepaFileData.build(inputs(account(ACC_IBAN), true)
        .remittanceIban(REM_IBAN));

    assertEquals(REM_IBAN, body.getString("iban"));
    assertEquals(REM_IBAN, body.getString("remittanceIban"));
    assertEquals(ACC_IBAN, body.getString("accountIban"));
    assertEquals("remittance", body.getString("ibanSource"));
    assertEquals(0, body.getJSONArray("missing").length());
  }

  /** The account's invalid IBAN does not block a remittance that has its own valid one. */
  @Test
  void aValidRemittanceIbanCoversAnAccountWithoutOne() throws Exception {
    JSONObject body = RemittanceSepaFileData.build(inputs(account(null), false)
        .remittanceIban(REM_IBAN));

    assertTrue(body.getBoolean("ibanValid"));
    assertFalse(body.getBoolean("accountIbanValid"));
    assertEquals(0, body.getJSONArray("missing").length());
  }

  /** A present but invalid copy is not replaced by the account's: the file would be refused. */
  @Test
  void anInvalidRemittanceIbanIsMissingNotReplaced() throws Exception {
    JSONObject body = RemittanceSepaFileData.build(inputs(account(ACC_IBAN), true)
        .remittanceIban("ES0021000418450200051332"));

    assertTrue(body.isNull("remittanceIban"));
    assertFalse(body.getBoolean("remittanceIbanValid"));
    assertEquals("missing", body.getString("ibanSource"));
    assertFalse(body.getBoolean("ibanValid"));
    assertEquals(List.of("iban"), missing(body));
  }

  @Test
  void withNoCopyTheAccountsIbanIsTheSource() throws Exception {
    JSONObject body = build(true, "ES", TAX_ID, null);

    assertEquals("account", body.getString("ibanSource"));
    assertTrue(body.isNull("remittanceIban"));
    assertEquals("missing", build(false, "ES", TAX_ID, null).getString("ibanSource"));
  }

  /** A saved id covers an organization whose own data cannot form one, but the file still needs its data. */
  @Test
  void aSavedIdIsStoredEvenWhenTheOrganizationCannotComputeOne() throws Exception {
    JSONObject body = RemittanceSepaFileData.build(account("ES9121000418450200051332"), true,
        "ORG-1", null, null, null, STORED);

    assertEquals("account", body.getString("creditorIdSource"));
    assertTrue(body.isNull("suggestedCreditorId"));
    assertEquals(STORED, body.getString("creditorId"));
  }

  /** The stored IBAN is shown trimmed; a blank one is null, and the organization may be unknown. */
  @Test
  void theIbanIsShownTrimmedAndBlankIsNull() throws Exception {
    FIN_FinancialAccount account = account(" ES9121000418450200051332 ");
    assertEquals("ES9121000418450200051332", RemittanceSepaFileData.build(account, true, null,
        "ES", TAX_ID, null).getString("iban"));

    JSONObject blank = RemittanceSepaFileData.build(account("  "), false, null, "ES", TAX_ID,
        null);
    assertTrue(blank.isNull("iban"));
    assertTrue(blank.isNull("organizationId"));
  }

  private static JSONObject build(boolean ibanValid, String country, String taxId,
      String businessCode) throws Exception {
    return RemittanceSepaFileData.build(account("ES9121000418450200051332"), ibanValid, "ORG-1",
        country, taxId, businessCode);
  }

  /** The inputs of a Spanish organization with {@code TAX_ID} and no business code. */
  private static RemittanceSepaFileData.Inputs inputs(FIN_FinancialAccount account,
      boolean ibanValid) {
    return new RemittanceSepaFileData.Inputs(account, ibanValid, "ORG-1", "ES", TAX_ID, null);
  }

  private static FIN_FinancialAccount account(String iban) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.getId()).thenReturn("ACC-1");
    when(account.getName()).thenReturn("BBVA");
    when(account.getIBAN()).thenReturn(iban);
    return account;
  }

  private static List<String> missing(JSONObject body) throws Exception {
    JSONArray array = body.getJSONArray("missing");
    List<String> list = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      list.add(array.getString(i));
    }
    return list;
  }
}
