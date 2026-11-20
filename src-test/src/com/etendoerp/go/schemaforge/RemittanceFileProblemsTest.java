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
import static org.mockito.Mockito.mockStatic;

import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.RemittanceFileProblems.LineRef;
import com.etendoerp.go.schemaforge.util.NeoLanguage;

/**
 * Unit tests for {@link RemittanceFileProblems}, the problem list of a remittance's SEPA file:
 * organization and account problems keep one entry per code; a problem of a business partner is
 * folded per partner and code, the first line naming it and {@code lineIds} listing every line;
 * an amount problem (a line's own) is never folded; a due's problem (the sum of its lines) is
 * folded per due and lists every line of the due; {@code message} is the code's text in the
 * request language and {@code params} its placeholders, with {@code -} for a missing one.
 *
 * <p>{@link NeoLanguage} is mocked to choose the request language; the texts come from the
 * {@code neo_hints} catalogs (no database in a unit JVM, so the AD_MESSAGE lookups answer
 * nothing).
 *
 * <p>A class of its own: {@code RemittanceFileProblems} is a new production class.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceFileProblems
 */
class RemittanceFileProblemsTest {

  private MockedStatic<NeoLanguage> language;

  @BeforeEach
  void setUp() {
    language = mockStatic(NeoLanguage.class);
    language.when(NeoLanguage::requestCode).thenReturn("en_US");
  }

  @AfterEach
  void tearDown() {
    language.close();
  }

  @Test
  void aPartnerProblemIsFoldedPerPartnerAndCodeCollectingEveryLine() throws Exception {
    RemittanceFileProblems problems = new RemittanceFileProblems();
    problems.line(ref("L1", "BP-1", "Acme", "INV-1", "F-1"),
        RemittanceFileLineCheck.MSG_NO_MANDATE, "Acme", "F-1");
    problems.line(ref("L2", "BP-1", "Acme", "INV-2", "F-2"),
        RemittanceFileLineCheck.MSG_NO_MANDATE, "Acme", "F-2");
    problems.line(ref("L3", "BP-1", "Acme", "INV-3", "F-3"),
        RemittanceFileLineCheck.MSG_NO_TAX_ID, "Acme", "F-3");
    problems.line(ref("L4", "BP-2", "Beta", "INV-4", "F-4"),
        RemittanceFileLineCheck.MSG_NO_MANDATE, "Beta", "F-4");

    JSONArray json = problems.toJson();

    assertEquals(3, problems.size(), json.toString());
    JSONObject folded = json.getJSONObject(0);
    assertEquals(RemittanceFileProblems.SCOPE_LINE, folded.getString("scope"));
    assertEquals(RemittanceFileLineCheck.MSG_NO_MANDATE, folded.getString("code"));
    assertEquals("L1", folded.getString("lineId"), "the first line names it");
    assertEquals("INV-1", folded.getString("invoiceId"));
    assertEquals("F-1", folded.getString("documentNo"));
    assertEquals("BP-1", folded.getString("businessPartnerId"));
    assertEquals("Acme", folded.getString("businessPartnerName"));
    assertEquals("[\"L1\",\"L2\"]", folded.getJSONArray("lineIds").toString());
    assertEquals(RemittanceFileLineCheck.MSG_NO_TAX_ID, json.getJSONObject(1).getString("code"),
        "another code of the same partner is its own problem");
    assertEquals("[\"L4\"]", json.getJSONObject(2).getJSONArray("lineIds").toString(),
        "the same code of another partner is its own problem");
  }

  @Test
  void anAmountProblemIsNeverFolded() throws Exception {
    RemittanceFileProblems problems = new RemittanceFileProblems();
    for (String line : new String[] { "L1", "L2" }) {
      problems.lineItself(ref(line, "BP-1", "Acme", "INV-" + line, "F-" + line),
          RemittanceFileLineCheck.MSG_AMOUNT_INVALID, "Acme", "F-" + line, "10.00");
    }

    assertEquals(2, problems.size());
    assertEquals("[\"L1\"]", problems.toJson().getJSONObject(0).getJSONArray("lineIds")
        .toString());
    assertEquals("[\"L2\"]", problems.toJson().getJSONObject(1).getJSONArray("lineIds")
        .toString());
  }

  /**
   * A due's problem names its first line and carries every line of the due; it is folded per due,
   * never with another due of the same partner nor with that partner's other problems.
   */
  @Test
  void aDueProblemCarriesEveryLineOfTheDueAndIsFoldedPerDue() throws Exception {
    RemittanceFileProblems problems = new RemittanceFileProblems();
    LineRef first = ref("L1", "BP-1", "Acme", "INV-1", "F-1");
    problems.due(first, "DUE-1", List.of("L1", "L2", "L3"),
        RemittanceFileLineCheck.MSG_AMOUNT_INVALID, "Acme", "F-1", "100");
    problems.due(ref("L4", "BP-1", "Acme", "INV-2", "F-2"), "DUE-2", List.of("L4", "L5"),
        RemittanceFileLineCheck.MSG_AMOUNT_INVALID, "Acme", "F-2", "50");
    problems.line(ref("L6", "BP-1", "Acme", "INV-3", "F-3"),
        RemittanceFileLineCheck.MSG_AMOUNT_INVALID, "Acme", "F-3");

    JSONArray json = problems.toJson();

    assertEquals(3, problems.size(), json.toString());
    JSONObject due = json.getJSONObject(0);
    assertEquals(RemittanceFileProblems.SCOPE_LINE, due.getString("scope"));
    assertEquals(RemittanceFileLineCheck.MSG_AMOUNT_INVALID, due.getString("code"));
    assertEquals("L1", due.getString("lineId"));
    assertEquals("[\"L1\",\"L2\",\"L3\"]", due.getJSONArray("lineIds").toString());
    assertEquals("BP-1", due.getString("businessPartnerId"));
    assertEquals("INV-1", due.getString("invoiceId"));
    assertEquals("F-1", due.getString("documentNo"));
    assertEquals("[\"Acme\",\"F-1\",\"100\"]", due.getJSONArray("params").toString());
    assertEquals("[\"L4\",\"L5\"]", json.getJSONObject(1).getJSONArray("lineIds").toString());
    assertEquals("[\"L6\"]", json.getJSONObject(2).getJSONArray("lineIds").toString());
  }

  /** A line without a partner is folded on itself, never with another partner-less line. */
  @Test
  void aLineWithoutAPartnerIsNotFoldedWithAnother() throws Exception {
    RemittanceFileProblems problems = new RemittanceFileProblems();
    problems.line(ref("L1", null, null, null, null), RemittanceFileLineCheck.MSG_NO_PARTNER);
    problems.line(ref("L2", null, null, null, null), RemittanceFileLineCheck.MSG_NO_PARTNER);

    assertEquals(2, problems.size());
    JSONObject first = problems.toJson().getJSONObject(0);
    assertTrue(first.isNull("businessPartnerId"));
    assertTrue(first.isNull("invoiceId"));
  }

  @Test
  void orgAndAccountProblemsCarryTheirIdAndComeInInsertionOrder() throws Exception {
    RemittanceFileProblems problems = new RemittanceFileProblems();
    assertTrue(problems.isEmpty());
    problems.org("ORG-1", RemittanceFileReadiness.MSG_ORG_NO_TAX_ID, "Norte");
    problems.account("ACC-1", RemittanceFileReadiness.MSG_ACCOUNT_INVALID_BIC, "BBVA", "XX");
    problems.org("ORG-1", RemittanceFileReadiness.MSG_ORG_NO_TAX_ID, "Norte");

    assertFalse(problems.isEmpty());
    assertEquals(2, problems.size(), "the same org code once");
    JSONObject org = problems.toJson().getJSONObject(0);
    assertEquals(RemittanceFileProblems.SCOPE_ORG, org.getString("scope"));
    assertEquals("ORG-1", org.getString("organizationId"));
    JSONObject account = problems.toJson().getJSONObject(1);
    assertEquals(RemittanceFileProblems.SCOPE_ACCOUNT, account.getString("scope"));
    assertEquals("ACC-1", account.getString("accountId"));
    assertEquals("[\"BBVA\",\"XX\"]", account.getJSONArray("params").toString());
    assertEquals("The BIC XX of the bank account BBVA is not valid.",
        account.getString("message"));
  }

  @Test
  void theMessageComesInTheRequestLanguageAndAMissingParamIsADash() throws Exception {
    language.when(NeoLanguage::requestCode).thenReturn("es_ES");
    RemittanceFileProblems problems = new RemittanceFileProblems();
    problems.org("ORG-1", RemittanceFileReadiness.MSG_ORG_NO_ADDRESS, (Object) null);

    JSONObject problem = problems.toJson().getJSONObject(0);

    assertEquals("La organización - no tiene dirección.", problem.getString("message"));
    assertEquals("[\"-\"]", problem.getJSONArray("params").toString());
  }

  private static LineRef ref(String lineId, String partnerId, String partnerName,
      String invoiceId, String documentNo) {
    return new LineRef(lineId, partnerId, partnerName, invoiceId, documentNo);
  }
}
