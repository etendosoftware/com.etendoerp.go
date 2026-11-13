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
    assertEquals(List.of("organizationId"), optional);
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
