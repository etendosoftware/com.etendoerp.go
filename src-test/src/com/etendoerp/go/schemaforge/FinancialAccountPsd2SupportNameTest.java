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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;

/**
 * Unit tests for {@link FinancialAccountPsd2Support#connectedAccountName}, guarding against a
 * regression where "{providerName} - {accountName}" could exceed the 60-char limit of
 * {@code FIN_Financial_Account.name} (observed with "Societe Generale Luxembourg Corporate
 * (Sandbox) - LU900610000012600EUR", 70 chars, thrown by the OBDal length validator on save).
 */
public class FinancialAccountPsd2SupportNameTest {

  private static JSONObject nodeWithName(String accountName) throws JSONException {
    JSONObject node = new JSONObject();
    node.put(BankIntegrationConstants.NAME, accountName);
    return node;
  }

  @Test
  public void combinedNameWithinLimitIsReturnedAsIs() throws JSONException {
    String result = FinancialAccountPsd2Support.connectedAccountName("BBVA", nodeWithName("ES1234567890"),
        "EUR");
    assertEquals("BBVA - ES1234567890", result);
  }

  @Test
  public void oversizedCombinationIsTruncatedToSixtyCharsPreservingAccountName() throws JSONException {
    String providerName = "Societe Generale Luxembourg Corporate (Sandbox)";
    String accountName = "LU900610000012600EUR";
    assertTrue("precondition: reproduces the reported 70-char overflow",
        (providerName + " - " + accountName).length() > 60);

    String result = FinancialAccountPsd2Support.connectedAccountName(providerName, nodeWithName(accountName), "EUR");

    assertEquals(60, result.length());
    assertEquals("Societe Generale Luxembourg Corporate - LU900610000012600EUR", result);
  }

  @Test
  public void oversizedAccountNameAloneIsTruncatedWithoutProvider() throws JSONException {
    String hugeAccountName = "A".repeat(80);

    String result = FinancialAccountPsd2Support.connectedAccountName("Bank", nodeWithName(hugeAccountName), "EUR");

    assertEquals(60, result.length());
    assertEquals(hugeAccountName.substring(0, 60), result);
  }

  @Test
  public void missingAccountNameFallsBackToProviderName() throws JSONException {
    String result = FinancialAccountPsd2Support.connectedAccountName("BBVA", new JSONObject(), "EUR");
    assertEquals("BBVA", result);
  }

  @Test
  public void missingProviderAndAccountNameFallsBackToCurrencyLabel() throws JSONException {
    String result = FinancialAccountPsd2Support.connectedAccountName(null, new JSONObject(), "EUR");
    assertEquals("EUR account", result);
  }
}
