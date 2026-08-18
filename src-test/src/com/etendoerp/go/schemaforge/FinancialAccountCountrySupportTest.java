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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;

/**
 * Unit tests for {@link FinancialAccountCountrySupport} (ETP-4896).
 *
 * <p>Pure-logic methods ({@code normalizeIban}, {@code isChecksumValid}, {@code
 * validateIbanCountryPair}, {@code bodyString}, {@code isExplicitClear}) run with plain
 * Mockito-stubbed {@link Country} objects, no DAL. DAL-backed lookups follow the same
 * {@code mockStatic(OBDal.class)} + {@code mock(OBCriteria.class)} idiom already used in
 * {@link FinancialAccountHandlerTest#testResolveCountryFromIbanUppercasesPrefixAndReturnsMatch}.
 */
public class FinancialAccountCountrySupportTest {

  private static final String VALID_ES_IBAN = "ES9121000418450200051332";

  private static Country countryWithIbanMeta(String isoCode, String ibanCode, long ibanLength, String name) {
    Country country = mock(Country.class);
    when(country.getISOCountryCode()).thenReturn(isoCode);
    when(country.getIBANCode()).thenReturn(ibanCode);
    when(country.getIBANLength()).thenReturn(ibanLength);
    when(country.getName()).thenReturn(name);
    return country;
  }

  // ---------------------------------------------------------------------------
  // normalizeIban
  // ---------------------------------------------------------------------------

  @Test
  public void normalizeIbanStripsSpacesAndUppercases() {
    assertEquals("ES9121000418450200051332",
        FinancialAccountCountrySupport.normalizeIban("es91 2100 0418 4502 0005 1332"));
  }

  @Test
  public void normalizeIbanStripsDashesAndOtherPunctuation() {
    assertEquals("ES9121000418450200051332",
        FinancialAccountCountrySupport.normalizeIban("ES91-2100-0418-4502-0005-1332"));
  }

  @Test
  public void normalizeIbanOfNullIsEmptyString() {
    assertEquals("", FinancialAccountCountrySupport.normalizeIban(null));
  }

  // ---------------------------------------------------------------------------
  // isChecksumValid
  // ---------------------------------------------------------------------------

  @Test
  public void checksumValidForRealIban() {
    assertTrue(FinancialAccountCountrySupport.isChecksumValid(VALID_ES_IBAN));
  }

  @Test
  public void checksumInvalidWhenOneDigitIsFlipped() {
    String tampered = "ES9121000418450200051333";
    assertFalse(FinancialAccountCountrySupport.isChecksumValid(tampered));
  }

  @Test
  public void checksumInvalidWhenTooShortToMatchShape() {
    assertFalse(FinancialAccountCountrySupport.isChecksumValid("ES91"));
  }

  @Test
  public void checksumInvalidForNull() {
    assertFalse(FinancialAccountCountrySupport.isChecksumValid(null));
  }

  // ---------------------------------------------------------------------------
  // validateIbanCountryPair
  // ---------------------------------------------------------------------------

  @Test
  public void pairRejectedWhenIbanTooShort() {
    Country spain = countryWithIbanMeta("ES", "ES", 24, "Spain");
    String message = FinancialAccountCountrySupport.validateIbanCountryPair("ES912100", spain);
    assertNotNull(message);
    assertTrue(message.contains("too short"));
  }

  @Test
  public void pairRejectedWhenCountryIsNull() {
    String message = FinancialAccountCountrySupport.validateIbanCountryPair(VALID_ES_IBAN, null);
    assertNotNull(message);
    assertTrue(message.contains("must have a country"));
  }

  @Test
  public void pairRejectedWhenCountryHasBlankIbanCode() {
    Country noIbanConfig = countryWithIbanMeta("GB", "", 22, "United Kingdom");
    String message = FinancialAccountCountrySupport.validateIbanCountryPair(VALID_ES_IBAN, noIbanConfig);
    assertNotNull(message);
    assertTrue(message.contains("United Kingdom"));
    assertTrue(message.contains("no IBAN configuration"));
  }

  @Test
  public void pairRejectedWhenCountryHasNullIbanLength() {
    Country noIbanConfig = mock(Country.class);
    when(noIbanConfig.getIBANCode()).thenReturn("US");
    when(noIbanConfig.getIBANLength()).thenReturn(null);
    when(noIbanConfig.getName()).thenReturn("United States");
    String message = FinancialAccountCountrySupport.validateIbanCountryPair(VALID_ES_IBAN, noIbanConfig);
    assertNotNull(message);
    assertTrue(message.contains("no IBAN configuration"));
  }

  @Test
  public void pairRejectedOnPrefixMismatch_reportsCountryNotChecksum() {
    Country italy = countryWithIbanMeta("IT", "IT", 27, "Italy");
    // Prefix mismatch AND, incidentally, would also fail mod-97 as an "IT" IBAN — the country
    // message must win, per the deliberate ordering documented on validateIbanCountryPair.
    String message = FinancialAccountCountrySupport.validateIbanCountryPair(VALID_ES_IBAN, italy);
    assertNotNull(message);
    assertTrue(message.contains("Italy"));
    assertTrue(message.contains("starts with 'ES'"));
    assertFalse(message.contains("check digits"));
  }

  @Test
  public void pairRejectedOnLengthMismatch() {
    // Spain's real IBANNODIGITS is 24; assert a mismatched configured length is caught before
    // the checksum runs.
    Country spainWrongLength = countryWithIbanMeta("ES", "ES", 20, "Spain");
    String message = FinancialAccountCountrySupport.validateIbanCountryPair(VALID_ES_IBAN, spainWrongLength);
    assertNotNull(message);
    assertTrue(message.contains("Spain"));
    assertTrue(message.contains("20"));
    assertTrue(message.contains("24"));
  }

  @Test
  public void pairRejectedOnChecksumFailureWhenPrefixAndLengthMatch() {
    Country spain = countryWithIbanMeta("ES", "ES", 24, "Spain");
    String tampered = "ES9121000418450200051333";
    String message = FinancialAccountCountrySupport.validateIbanCountryPair(tampered, spain);
    assertNotNull(message);
    assertTrue(message.contains("check digits"));
  }

  @Test
  public void pairAcceptedForConsistentIbanAndCountry() {
    Country spain = countryWithIbanMeta("ES", "ES", 24, "Spain");
    assertNull(FinancialAccountCountrySupport.validateIbanCountryPair(VALID_ES_IBAN, spain));
  }

  // ---------------------------------------------------------------------------
  // resolveCountryForIbanPrefix
  // ---------------------------------------------------------------------------

  @Test
  public void resolveCountryForIbanPrefixReturnsNullWithoutDalWhenTooShort() {
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountCountrySupport.resolveCountryForIbanPrefix("E"));
      obDal.verifyNoInteractions();
    }
  }

  @Test
  public void resolveCountryForIbanPrefixMatchesByIbanCodeFirst() {
    Country spain = mock(Country.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Country> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(Country.class)).thenReturn(criteria);
      when(criteria.uniqueResult()).thenReturn(spain);

      Country result = FinancialAccountCountrySupport.resolveCountryForIbanPrefix(VALID_ES_IBAN);

      assertSame(spain, result);
      // Only the IBAN-code lookup should run when it already finds a match.
      verify(dal, times(1)).createCriteria(Country.class);
    }
  }

  @Test
  public void resolveCountryForIbanPrefixFallsBackToIsoCodeWhenIbanCodeLookupMisses() {
    Country matchByIso = mock(Country.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Country> firstMiss = mock(OBCriteria.class);
      @SuppressWarnings("unchecked")
      OBCriteria<Country> secondHit = mock(OBCriteria.class);
      when(dal.createCriteria(Country.class)).thenReturn(firstMiss, secondHit);
      when(firstMiss.uniqueResult()).thenReturn(null);
      when(secondHit.uniqueResult()).thenReturn(matchByIso);

      Country result = FinancialAccountCountrySupport.resolveCountryForIbanPrefix(VALID_ES_IBAN);

      assertSame(matchByIso, result);
      verify(dal, times(2)).createCriteria(Country.class);
    }
  }

  // ---------------------------------------------------------------------------
  // resolveOrganizationCountry
  // ---------------------------------------------------------------------------

  @Test
  public void resolveOrganizationCountryReturnsOwnLocationCountryWithoutAncestorWalk() {
    String orgId = "ORG1";
    Country spain = mock(Country.class);
    OrganizationInformation orgInfo = mock(OrganizationInformation.class);
    Location location = mock(Location.class);
    when(orgInfo.getLocationAddress()).thenReturn(location);
    when(location.getCountry()).thenReturn(spain);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedConstruction<OrganizationStructureProvider> ospConstruction =
             mockConstruction(OrganizationStructureProvider.class)) {
      OBDal readOnlyDal = mock(OBDal.class);
      obDal.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);
      when(readOnlyDal.get(OrganizationInformation.class, orgId)).thenReturn(orgInfo);

      Country result = FinancialAccountCountrySupport.resolveOrganizationCountry(orgId);

      assertSame(spain, result);
      assertTrue("no ancestor walk needed once the org's own location resolves",
          ospConstruction.constructed().isEmpty());
    }
  }

  @Test
  public void resolveOrganizationCountryWalksUpToFirstAncestorWithACountry() {
    String orgId = "ORG_CHILD";
    Country france = mock(Country.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedConstruction<OrganizationStructureProvider> ospConstruction =
             mockConstruction(OrganizationStructureProvider.class, (provider, ctx) ->
                 when(provider.getParentList(orgId, false))
                     .thenReturn(Arrays.asList("ORG_PARENT", "ORG_GRANDPARENT")))) {
      OBDal readOnlyDal = mock(OBDal.class);
      obDal.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);
      // The org itself and its first ancestor have no location; the grandparent does.
      when(readOnlyDal.get(eq(OrganizationInformation.class), eq(orgId))).thenReturn(null);
      OrganizationInformation parentInfo = mock(OrganizationInformation.class);
      when(parentInfo.getLocationAddress()).thenReturn(null);
      when(readOnlyDal.get(OrganizationInformation.class, "ORG_PARENT")).thenReturn(parentInfo);
      OrganizationInformation grandparentInfo = mock(OrganizationInformation.class);
      Location grandparentLocation = mock(Location.class);
      when(grandparentInfo.getLocationAddress()).thenReturn(grandparentLocation);
      when(grandparentLocation.getCountry()).thenReturn(france);
      when(readOnlyDal.get(OrganizationInformation.class, "ORG_GRANDPARENT")).thenReturn(grandparentInfo);

      Country result = FinancialAccountCountrySupport.resolveOrganizationCountry(orgId);

      assertSame(france, result);
    }
  }

  @Test
  public void resolveOrganizationCountryFallsBackToSpainWhenChainIsEmpty() {
    String orgId = "ORG_NO_LOCATION";
    Country spainFallback = mock(Country.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedConstruction<OrganizationStructureProvider> ospConstruction =
             mockConstruction(OrganizationStructureProvider.class, (provider, ctx) ->
                 when(provider.getParentList(orgId, false)).thenReturn(Collections.emptyList()))) {
      OBDal readOnlyDal = mock(OBDal.class);
      obDal.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);
      when(readOnlyDal.get(OrganizationInformation.class, orgId)).thenReturn(null);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Country> fallbackCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Country.class)).thenReturn(fallbackCriteria);
      when(fallbackCriteria.uniqueResult()).thenReturn(spainFallback);

      Country result = FinancialAccountCountrySupport.resolveOrganizationCountry(orgId);

      assertSame(spainFallback, result);
    }
  }

  @Test
  public void resolveOrganizationCountryReturnsNullWhenEvenTheFallbackIsMissing() {
    String orgId = "ORG_NO_LOCATION";

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedConstruction<OrganizationStructureProvider> ospConstruction =
             mockConstruction(OrganizationStructureProvider.class, (provider, ctx) ->
                 when(provider.getParentList(orgId, false)).thenReturn(Collections.emptyList()))) {
      OBDal readOnlyDal = mock(OBDal.class);
      obDal.when(OBDal::getReadOnlyInstance).thenReturn(readOnlyDal);
      when(readOnlyDal.get(OrganizationInformation.class, orgId)).thenReturn(null);

      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Country> fallbackCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Country.class)).thenReturn(fallbackCriteria);
      when(fallbackCriteria.uniqueResult()).thenReturn(null);

      Country result = FinancialAccountCountrySupport.resolveOrganizationCountry(orgId);

      assertNull(result);
    }
  }

  @Test
  public void resolveOrganizationCountryWithBlankOrgIdGoesStraightToFallback() {
    Country spainFallback = mock(Country.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Country> fallbackCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Country.class)).thenReturn(fallbackCriteria);
      when(fallbackCriteria.uniqueResult()).thenReturn(spainFallback);

      Country result = FinancialAccountCountrySupport.resolveOrganizationCountry("");

      assertSame(spainFallback, result);
      obDal.verify(OBDal::getReadOnlyInstance, never());
    }
  }

  // ---------------------------------------------------------------------------
  // buildIbanRules
  // ---------------------------------------------------------------------------

  @Test
  public void buildIbanRulesShapeThenCachesOnlyANonEmptyResult() throws JSONException {
    // IBAN_RULES_CACHE is static/JVM-wide; clear it first so this test's expectations do not
    // depend on whether some other test in the suite already populated it.
    FinancialAccountCountrySupport.clearIbanRulesCacheForTests();
    Country spain = countryWithIbanMeta("ES", "ES", 24, "Spain");
    when(spain.getId()).thenReturn("SPAIN_ID");

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<Country> emptyCriteria = mock(OBCriteria.class);
      @SuppressWarnings("unchecked")
      OBCriteria<Country> populatedCriteria = mock(OBCriteria.class);
      when(dal.createCriteria(Country.class)).thenReturn(emptyCriteria, populatedCriteria);
      when(emptyCriteria.list()).thenReturn(Collections.emptyList());
      when(populatedCriteria.list()).thenReturn(Collections.singletonList(spain));

      // First call: the loader returns nothing (e.g. transient DAL hiccup) — must NOT be cached.
      JSONArray firstResult = FinancialAccountCountrySupport.buildIbanRules();
      assertEquals(0, firstResult.length());

      // Second call: a real, non-empty catalog — gets cached from here on.
      JSONArray secondResult = FinancialAccountCountrySupport.buildIbanRules();
      assertEquals(1, secondResult.length());
      JSONObject rule = secondResult.getJSONObject(0);
      assertEquals("SPAIN_ID", rule.getString("id"));
      assertEquals("ES", rule.getString("iso"));
      assertEquals("Spain", rule.getString("name"));
      assertEquals("ES", rule.getString("ibanPrefix"));
      assertEquals(24L, rule.getLong("ibanLength"));

      // Third call: served from cache — no third createCriteria() invocation.
      JSONArray thirdResult = FinancialAccountCountrySupport.buildIbanRules();
      assertEquals(1, thirdResult.length());
      verify(dal, times(2)).createCriteria(Country.class);
    }
  }

  // ---------------------------------------------------------------------------
  // bodyString / isExplicitClear
  // ---------------------------------------------------------------------------

  @Test
  public void bodyStringReturnsNullForAbsentKey() throws JSONException {
    JSONObject body = new JSONObject();
    assertNull(FinancialAccountCountrySupport.bodyString(body, "iBAN"));
  }

  @Test
  public void bodyStringReturnsNullForExplicitJsonNull_notTheLiteralString() throws JSONException {
    // A real PATCH body carries an explicit JSON null by being PARSED from a request string —
    // JSONObject.put(key, null) instead REMOVES the key (jettison-specific), so parsing is the
    // only way to reproduce what FinancialAccountHandler actually receives over the wire.
    JSONObject body = new JSONObject("{\"iBAN\": null}");
    assertTrue("precondition: the key must still be present after parsing", body.has("iBAN"));
    assertNull(FinancialAccountCountrySupport.bodyString(body, "iBAN"));
  }

  @Test
  public void bodyStringReturnsThePresentValue() throws JSONException {
    JSONObject body = new JSONObject();
    body.put("iBAN", VALID_ES_IBAN);
    assertEquals(VALID_ES_IBAN, FinancialAccountCountrySupport.bodyString(body, "iBAN"));
  }

  @Test
  public void isExplicitClearFalseWhenKeyAbsent() throws JSONException {
    JSONObject body = new JSONObject();
    assertFalse(FinancialAccountCountrySupport.isExplicitClear(body, "iBAN"));
  }

  @Test
  public void isExplicitClearTrueForJsonNull() throws JSONException {
    // See bodyStringReturnsNullForExplicitJsonNull_notTheLiteralString: only a parsed body
    // preserves an explicit JSON null; JSONObject.put(key, null) would remove the key instead.
    JSONObject body = new JSONObject("{\"iBAN\": null}");
    assertTrue(FinancialAccountCountrySupport.isExplicitClear(body, "iBAN"));
  }

  @Test
  public void isExplicitClearTrueForBlankString() throws JSONException {
    JSONObject body = new JSONObject();
    body.put("iBAN", "   ");
    assertTrue(FinancialAccountCountrySupport.isExplicitClear(body, "iBAN"));
  }

  @Test
  public void isExplicitClearFalseWhenValuePresent() throws JSONException {
    JSONObject body = new JSONObject();
    body.put("iBAN", VALID_ES_IBAN);
    assertFalse(FinancialAccountCountrySupport.isExplicitClear(body, "iBAN"));
  }
}
