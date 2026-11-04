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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The territory x system table of the Fiscal Configuration onboarding and the record bodies it
 * derives from the territory. The expected table is written out here on purpose, independently of
 * the production map, so a change to either side shows up.
 *
 * @covers com.etendoerp.go.schemaforge.fiscal.FiscalSifRules
 */
class FiscalSifRulesTest {

  private static final String TODAY = "2026-11-03";

  @ParameterizedTest(name = "{0} allows {1}")
  @CsvSource({
      "navarra,   SII",
      "alava,     TBAI|SII+TBAI",
      "bizkaia,   TBAI|SII+TBAI",
      "gipuzkoa,  TBAI|SII+TBAI",
      "baleares,  SII|VERIFACTU",
      "canarias,  SII|VERIFACTU",
      "ceuta,     VERIFACTU" })
  void everyTerritoryAllowsExactlyItsSystems(String territory, String expected) {
    List<String> allowed = List.of(expected.split("\\|"));

    assertEquals(allowed, FiscalSifRules.allowedSystems(territory));
    for (String system : FiscalSifRules.SYSTEMS) {
      assertEquals(allowed.contains(system), FiscalSifRules.isAllowed(system, territory),
          system + " in " + territory);
    }
  }

  @Test
  void theTableNamesEveryTerritoryAndEverySystem() {
    assertEquals(List.of("navarra", "alava", "bizkaia", "gipuzkoa", "baleares", "canarias",
        "ceuta"), FiscalSifRules.TERRITORIES);
    assertEquals(List.of("SII", "TBAI", "SII+TBAI", "VERIFACTU"), FiscalSifRules.SYSTEMS);
  }

  @Test
  void anUnknownOrMissingTerritoryAllowsNothing() {
    assertTrue(FiscalSifRules.allowedSystems("madrid").isEmpty());
    assertTrue(FiscalSifRules.allowedSystems(null).isEmpty());
    assertFalse(FiscalSifRules.isAllowed(FiscalSifRules.SII, null));
  }

  @Test
  void siiPlusTbaiIsTwoRecordsAndEveryOtherSystemIsOne() {
    assertEquals(List.of("SII", "TBAI"), FiscalSifRules.recordSystems("SII+TBAI"));
    assertEquals(List.of("VERIFACTU"), FiscalSifRules.recordSystems("VERIFACTU"));
    assertTrue(FiscalSifRules.recordSystems(null).isEmpty());
  }

  @Test
  void territoriesAllowingListsThemInPresentationOrder() {
    assertEquals(List.of("navarra", "baleares", "canarias"),
        FiscalSifRules.territoriesAllowing("SII"));
    assertEquals(List.of("baleares", "canarias", "ceuta"),
        FiscalSifRules.territoriesAllowing("VERIFACTU"));
    assertEquals(List.of("alava", "bizkaia", "gipuzkoa"),
        FiscalSifRules.territoriesAllowing("SII+TBAI"));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({ "alava, ARABA", "bizkaia, BIZKAIA", "gipuzkoa, GIPUZKOA" })
  void tbaiTerritoryMapsBothWays(String territory, String tbai) {
    assertEquals(tbai, FiscalSifRules.tbaiTerritory(territory));
    assertEquals(territory, FiscalSifRules.territoryOfTbai(tbai));
  }

  @Test
  void tbaiTerritoryIsNullOutsideTheBasqueCountry() {
    assertNull(FiscalSifRules.tbaiTerritory("navarra"));
    assertNull(FiscalSifRules.tbaiTerritory(null));
    assertNull(FiscalSifRules.territoryOfTbai("MADRID"));
  }

  // ── payloads ──────────────────────────────────────────────────────────────

  @ParameterizedTest(name = "VERI*FACTU in {0} has tAXType {1}")
  @CsvSource({ "baleares, 01", "canarias, 03", "ceuta, 02" })
  void verifactuTaxTypeComesFromTheTerritory(String territory, String taxType) throws Exception {
    Map<String, JSONObject> payloads = FiscalSifRules.onboardingPayloads("VERIFACTU", territory,
        TODAY);

    assertEquals(List.of("VERIFACTU"), new ArrayList<>(payloads.keySet()));
    JSONObject body = payloads.get("VERIFACTU");
    assertEquals(taxType, body.getString("tAXType"));
    assertEquals("60", body.getString("nextSendWaitTime"));
    assertEquals("Y", body.getString("defaultQR"));
  }

  @Test
  void siiInNavarraIsTheNavarraAgencyWithIva() throws Exception {
    JSONObject sii = FiscalSifRules.onboardingPayloads("SII", "navarra", TODAY).get("SII");

    assertEquals("Y", sii.getString("navarra"));
    assertFalse(sii.has("guipuzcoa"));
    assertEquals("IVA", sii.getString("taxtype"));
    assertEquals("Y", sii.getString("acogidaAlSII"), "acogido from creation, on every channel");
    assertEquals("Y", sii.getString("entornoDeProduccin"));
    assertEquals("Y", sii.getString("adjuntarArchivosXML"));
    assertEquals(TODAY, sii.getString("fechaAcogidaSII"));
    assertEquals(TODAY, sii.getString("monitordate"));
  }

  @Test
  void siiInCanariasIsIgicWithNoRegionalAgency() throws Exception {
    JSONObject sii = FiscalSifRules.onboardingPayloads("SII", "canarias", TODAY).get("SII");

    assertEquals("IGIC", sii.getString("taxtype"));
    assertFalse(sii.has("navarra"));
    assertFalse(sii.has("guipuzcoa"));
  }

  @Test
  void siiInBalearesIsTheAeatWithIva() throws Exception {
    JSONObject sii = FiscalSifRules.onboardingPayloads("SII", "baleares", TODAY).get("SII");

    assertEquals("IVA", sii.getString("taxtype"));
    assertFalse(sii.has("navarra"));
    assertFalse(sii.has("guipuzcoa"));
  }

  @Test
  void siiPlusTbaiInGipuzkoaCreatesSiiFirstWithTheGipuzkoaAgency() throws Exception {
    Map<String, JSONObject> payloads = FiscalSifRules.onboardingPayloads("SII+TBAI", "gipuzkoa",
        TODAY);

    assertEquals(List.of("SII", "TBAI"), new ArrayList<>(payloads.keySet()));
    JSONObject sii = payloads.get("SII");
    assertEquals("Y", sii.getString("guipuzcoa"));
    assertFalse(sii.has("navarra"));
    assertEquals("IVA", sii.getString("taxtype"));
    assertEquals("GIPUZKOA", payloads.get("TBAI").getString("etsgSifTerritory"));
  }

  @ParameterizedTest(name = "TicketBAI in {0} is {1}")
  @CsvSource({ "alava, ARABA", "bizkaia, BIZKAIA", "gipuzkoa, GIPUZKOA" })
  void tbaiTerritoryAndDefaultsComeFromTheTerritory(String territory, String tbai)
      throws Exception {
    Map<String, JSONObject> payloads = FiscalSifRules.onboardingPayloads("TBAI", territory, TODAY);

    assertEquals(List.of("TBAI"), new ArrayList<>(payloads.keySet()));
    JSONObject body = payloads.get("TBAI");
    assertEquals(tbai, body.getString("etsgSifTerritory"));
    assertEquals(TODAY, body.getString("tbaisystemdate"));
    assertEquals("Y", body.getString("productionEnv"));
    assertEquals("N", body.getString("uSEAsproductDesc"));
    assertEquals("N", body.getString("validatePreviousInvoice"));
  }

  @Test
  void siiPlusTbaiInAlavaOrBizkaiaHasNoRegionalSiiAgency() throws Exception {
    for (String territory : List.of("alava", "bizkaia")) {
      JSONObject sii = FiscalSifRules.onboardingPayloads("SII+TBAI", territory, TODAY).get("SII");
      assertFalse(sii.has("guipuzcoa"), territory);
      assertFalse(sii.has("navarra"), territory);
    }
  }

  @Test
  void aRefusedPairBuildsNoPayload() throws Exception {
    assertTrue(FiscalSifRules.onboardingPayloads("SII", "ceuta", TODAY).isEmpty());
    assertTrue(FiscalSifRules.onboardingPayloads("TBAI", "navarra", TODAY).isEmpty());
    assertTrue(FiscalSifRules.onboardingPayloads("VERIFACTU", "alava", TODAY).isEmpty());
  }

  @Test
  void theAddSiiPayloadOfABasqueTerritoryIsTheSiiPartOfItsOnboarding() throws Exception {
    JSONObject added = FiscalSifRules.recordPayload("SII", "gipuzkoa", TODAY);
    JSONObject onboarding = FiscalSifRules.onboardingPayloads("SII+TBAI", "gipuzkoa", TODAY)
        .get("SII");

    assertEquals(onboarding.toString(), added.toString());
  }
}
