/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;

/**
 * The Modelo 390 incident rules (ETP-5716), one positive and one negative case per rule. Pure:
 * every input is a {@link Fiscal390IncidentRules.Facts} field.
 *
 * @covers com.etendoerp.go.schemaforge.Fiscal390IncidentRules
 */
public class Fiscal390IncidentRulesTest {

  private Fiscal390IncidentRules.Facts f;
  private Map<String, String> inputs;

  /** Facts that raise no incident at all. */
  @Before
  public void setUp() {
    f = new Fiscal390IncidentRules.Facts();
    f.orgNif = "B12345678";
    f.taxpayerName = "ACME SL";
    f.mainActivities = List.of(new Fiscal390IncidentRules.Activity("Comercio", true));
    f.otherActivities = new ArrayList<>();
    f.representatives = List.of(new Fiscal390IncidentRules.Representative("12345678Z", "Ana Ruiz"));
    inputs = new HashMap<>();
    f.inputs = inputs;
    f.missing303Periods = new ArrayList<>();
    f.unpostedSales = 0;
    f.unpostedPurchase = 0;
  }

  private List<String> codes() {
    return Fiscal390IncidentRules.evaluate(f).stream().map(i -> i.code)
        .collect(Collectors.toList());
  }

  private Fiscal390IncidentRules.Incident only(String code) {
    List<Fiscal390IncidentRules.Incident> hits = Fiscal390IncidentRules.evaluate(f).stream()
        .filter(i -> code.equals(i.code)).collect(Collectors.toList());
    assertEquals("incidents with code " + code, 1, hits.size());
    return hits.get(0);
  }

  @Test
  public void testCleanFactsRaiseNothing() {
    assertTrue(codes().toString(), codes().isEmpty());
  }

  // ── Blocking ───────────────────────────────────────────────────────────

  @Test
  public void testNoMainActivityBlocksAndPointsToTheOrganization() {
    f.mainActivities = List.of();
    Fiscal390IncidentRules.Incident inc = only(Fiscal390IncidentRules.NO_MAIN_IAE);
    assertEquals(Fiscal390IncidentRules.SEVERITY_BLOCK, inc.severity);
    assertEquals("organization", inc.action.get("type"));
    assertEquals("iae", inc.action.get("section"));
  }

  @Test
  public void testActivityWithoutCodeBlocks() {
    f.otherActivities.add(new Fiscal390IncidentRules.Activity("Hosteleria", false));
    Fiscal390IncidentRules.Incident inc = only(Fiscal390IncidentRules.IAE_WITHOUT_CODE);
    assertTrue(inc.isBlocking());
    assertEquals("Hosteleria", inc.params.get("activity"));
  }

  @Test
  public void testFiveOtherActivitiesAreFineSixBlock() {
    for (int i = 0; i < 5; i++) {
      f.otherActivities.add(new Fiscal390IncidentRules.Activity("A" + i, true));
    }
    assertTrue(codes().isEmpty());
    f.otherActivities.add(new Fiscal390IncidentRules.Activity("A5", true));
    Fiscal390IncidentRules.Incident inc = only(Fiscal390IncidentRules.TOO_MANY_IAE);
    assertTrue(inc.isBlocking());
    assertEquals(6, inc.params.get("count"));
  }

  @Test
  public void testUnsupportedRateBlocksAndNoBoxWarns() {
    f.calculatorWarnings = List.of(
        Map.of("code", "UNSUPPORTED_RATE", "parameter", "VAT_SALES_NORMAL", "rate", "3.00"),
        Map.of("code", "NO_BOX", "parameter", "VAT_PURCHASE", "rate", ""));
    assertTrue(only(Fiscal390IncidentRules.UNSUPPORTED_RATE).isBlocking());
    assertEquals("3.00", only(Fiscal390IncidentRules.UNSUPPORTED_RATE).params.get("rate"));
    assertEquals(Fiscal390IncidentRules.SEVERITY_WARN,
        only(Fiscal390IncidentRules.NO_BOX).severity);
  }

  @Test
  public void testComputeFailureBlocks() {
    f.computeError = "Missing period 12";
    assertTrue(only(Fiscal390IncidentRules.COMPUTE_FAILED).isBlocking());
  }

  @Test
  public void testSubstituteNeedsThirteenDigits() {
    f.substituteFlag = true;
    f.formerStatement = "390123456789"; // 12 digits
    assertTrue(only(Fiscal390IncidentRules.SUBSTITUTE_ID_INVALID).isBlocking());
    f.formerStatement = "3901234567890";
    assertTrue(codes().isEmpty());
  }

  @Test
  public void testSubstituteFlagFromTheInputsUsesSupplementaryNo() {
    inputs.put("ISSUPPLEMENTARY", "Y");
    inputs.put("SUPPLEMENTARYNO", "39012345678AB");
    only(Fiscal390IncidentRules.SUBSTITUTE_ID_INVALID);
    inputs.put("SUPPLEMENTARYNO", "3901234567890");
    assertTrue(codes().isEmpty());
  }

  @Test
  public void testOrdinaryDeclarationNeedsNoIdentifier() {
    inputs.put("ISSUPPLEMENTARY", "N");
    assertTrue(codes().isEmpty());
  }

  @Test
  public void testTerritoryPercentagesMustAddUpToHundred() {
    inputs.put("COMMON_TERRITORY", "50");
    inputs.put("NAVARRA", "40");
    Fiscal390IncidentRules.Incident inc = only(Fiscal390IncidentRules.TERRITORY_PERCENT_SUM);
    assertTrue(inc.isBlocking());
    assertEquals("90", inc.params.get("sum"));
    assertEquals(87, inc.action.get("box"));
    inputs.put("NAVARRA", "50,00");
    assertTrue(codes().isEmpty());
  }

  @Test
  public void testTerritoryPercentageAbove99Blocks() {
    inputs.put("COMMON_TERRITORY", "100");
    Fiscal390IncidentRules.Incident inc = only(Fiscal390IncidentRules.TERRITORY_PERCENT_MAX);
    assertEquals(87, inc.params.get("box"));
  }

  @Test
  public void testOrgWithoutNifBlocks() {
    f.orgNif = " ";
    Fiscal390IncidentRules.Incident inc = only(Fiscal390IncidentRules.NO_ORG_NIF);
    assertEquals("identity", inc.action.get("section"));
  }

  @Test
  public void testNaturalPersonNeedsNameAndFirstSurname() {
    f.person = true;
    f.firstName = "Ana";
    assertTrue(only(Fiscal390IncidentRules.PERSON_NAME_MISSING).isBlocking());
    f.surname1 = "Ruiz";
    assertTrue(codes().isEmpty());
  }

  @Test
  public void testNaturalPersonRepresentativeNeedsNifAndName() {
    f.person = true;
    f.firstName = "Ana";
    f.surname1 = "Ruiz";
    f.personRepNif = "12345678Z";
    assertTrue(only(Fiscal390IncidentRules.PERSON_REPRESENTATIVE_INCOMPLETE).isBlocking());
    f.personRepName = "Luis";
    assertTrue(codes().isEmpty());
  }

  @Test
  public void testCompensationWithTerritoryBlocks() {
    inputs.put("COMMON_TERRITORY", "60");
    inputs.put("NAVARRA", "40");
    inputs.put("LIQANUAL_COMPENSATION", "10");
    Fiscal390IncidentRules.Incident inc =
        only(Fiscal390IncidentRules.COMPENSATION_WITH_TERRITORY);
    assertEquals(85, inc.action.get("box"));
  }

  @Test
  public void testCompensationTogetherWithBox93Blocks() {
    inputs.put("LIQANUAL_COMPENSATION", "10");
    inputs.put("COMMON_TERRITORY_COMPENSATION", "5");
    assertEquals(93, only(Fiscal390IncidentRules.COMPENSATION_BOTH).action.get("box"));
    inputs.remove("COMMON_TERRITORY_COMPENSATION");
    assertTrue(codes().isEmpty());
  }

  @Test
  public void testNegativeCompensationBlocks() {
    inputs.put("LIQANUAL_COMPENSATION", "-1");
    assertEquals(85, only(Fiscal390IncidentRules.COMPENSATION_NEGATIVE).params.get("box"));
  }

  @Test
  public void testMonthlyRefundNeedsTheMonthlyRegister() {
    inputs.put("DEVOLVER_03", "100");
    assertTrue(only(Fiscal390IncidentRules.REFUND_WITHOUT_MONTHLY_REGISTER).isBlocking());
    inputs.put("ISMONTHLYRETURN", "Y");
    assertTrue(codes().isEmpty());
  }

  @Test
  public void testDecemberRefundWithBox97Blocks() {
    inputs.put("ISMONTHLYRETURN", "Y");
    inputs.put("DEVOLVER_12", "100");
    inputs.put("TOTAL_LASTPERIOD", "50");
    assertEquals(97, only(Fiscal390IncidentRules.LAST_PERIOD_REFUND_CONFLICT).action.get("box"));
    inputs.remove("TOTAL_LASTPERIOD");
    assertTrue(codes().isEmpty());
  }

  @Test
  public void testBlockingOnlyReturnsNoWarning() {
    f.mainActivities = List.of();
    f.unpostedSales = 3;
    List<Fiscal390IncidentRules.Incident> blocking = Fiscal390IncidentRules.blocking(f);
    assertEquals(1, blocking.size());
    assertEquals(Fiscal390IncidentRules.NO_MAIN_IAE, blocking.get(0).code);
  }

  // ── Warnings ───────────────────────────────────────────────────────────

  @Test
  public void testUnpostedInvoicesWarnWithCounts() {
    f.unpostedSales = 2;
    f.unpostedPurchase = 1;
    f.unpostedDocuments = List.of("F-1", "F-2", "C-9");
    Fiscal390IncidentRules.Incident inc = only(Fiscal390IncidentRules.UNPOSTED_INVOICES);
    assertEquals(Fiscal390IncidentRules.SEVERITY_WARN, inc.severity);
    assertEquals(3, inc.params.get("count"));
    assertEquals("F-1, F-2, C-9", inc.params.get("documents"));
    assertEquals("invoices", inc.action.get("type"));
    assertEquals(2, inc.action.get("sales"));
  }

  @Test
  public void testMissing303Warns() {
    f.missing303Periods = List.of("T3", "T4");
    Fiscal390IncidentRules.Incident inc = only(Fiscal390IncidentRules.MISSING_303);
    assertEquals("T3, T4", inc.params.get("periods"));
    f.missing303Periods = null; // the preload could not be read: unknown, not missing
    assertTrue(codes().isEmpty());
  }

  @Test
  public void testDifferenceWith303BeyondToleranceWarns() {
    f.annualResult = new BigDecimal("102.00");
    f.expected303Result = new BigDecimal("100.00");
    Fiscal390IncidentRules.Incident inc = only(Fiscal390IncidentRules.DIFF_303);
    assertEquals("2.00", inc.params.get("difference"));
    assertEquals(86, inc.action.get("box"));
    f.territoryMode = true;
    assertEquals(94, only(Fiscal390IncidentRules.DIFF_303).action.get("box"));
  }

  @Test
  public void testDifferenceWithinToleranceIsFine() {
    f.annualResult = new BigDecimal("101.00");
    f.expected303Result = new BigDecimal("100.00");
    assertTrue(codes().isEmpty());
    f.expected303Result = null;
    f.annualResult = new BigDecimal("5000");
    assertTrue(codes().isEmpty());
  }

  @Test
  public void testInactiveTaxWithOperationsWarns() {
    f.inactiveTaxes = List.of(new Fiscal390IncidentRules.InactiveTax("IVA 21% 2025", 4L));
    Fiscal390IncidentRules.Incident inc =
        only(Fiscal390IncidentRules.INACTIVE_TAX_WITH_OPERATIONS);
    assertEquals(4L, inc.params.get("invoices"));
  }

  @Test
  public void testLegalEntityWithoutRepresentativeWarns() {
    f.representatives = List.of();
    Fiscal390IncidentRules.Incident inc = only(Fiscal390IncidentRules.NO_LEGAL_REPRESENTATIVE);
    assertEquals("modelo390", inc.action.get("section"));
  }

  @Test
  public void testNaturalPersonWithoutRepresentativeIsFine() {
    f.person = true;
    f.firstName = "Ana";
    f.surname1 = "Ruiz";
    f.representatives = List.of();
    assertTrue(codes().isEmpty());
  }

  @Test
  public void testLongSocialNameWarnsMeasuredAsTheFileKeepsIt() {
    f.taxpayerName = "\u00D1".repeat(40) + "ACME"; // \u00D1 is dropped by the file: 4 characters kept
    assertTrue(codes().isEmpty());
    f.taxpayerName = "\u00C1".repeat(38);
    Fiscal390IncidentRules.Incident inc = only(Fiscal390IncidentRules.NAME_TRUNCATED);
    assertEquals("socialName", inc.params.get("field"));
    assertEquals(37, inc.params.get("limit"));
    assertNull(inc.params.get("label"));
  }

  @Test
  public void testLongRepresentativeNameWarnsWithItsNif() {
    f.representatives = List.of(
        new Fiscal390IncidentRules.Representative("12345678Z", "A".repeat(37)));
    Fiscal390IncidentRules.Incident inc = only(Fiscal390IncidentRules.NAME_TRUNCATED);
    assertEquals("legalRepresentative", inc.params.get("field"));
    assertEquals("12345678Z", inc.params.get("label"));
  }

  @Test
  public void testNaturalPersonLongNamesWarn() {
    f.person = true;
    f.firstName = "B".repeat(16);
    f.surname1 = "Ruiz";
    assertEquals("firstName", only(Fiscal390IncidentRules.NAME_TRUNCATED).params.get("field"));
  }

  @Test
  public void testBox98WithBox97Warns() {
    inputs.put("TOTAL_LASTPERIOD_DEVOLVER", "10");
    assertTrue(codes().isEmpty());
    inputs.put("TOTAL_LASTPERIOD", "5");
    assertEquals(98, only(Fiscal390IncidentRules.BOX98_WITH_96_97).action.get("box"));
  }

  @Test
  public void testBlockingComeBeforeWarnings() {
    f.unpostedSales = 1;
    f.mainActivities = List.of();
    List<String> codes = codes();
    assertEquals(Fiscal390IncidentRules.NO_MAIN_IAE, codes.get(0));
    assertEquals(Fiscal390IncidentRules.UNPOSTED_INVOICES, codes.get(1));
  }
}
