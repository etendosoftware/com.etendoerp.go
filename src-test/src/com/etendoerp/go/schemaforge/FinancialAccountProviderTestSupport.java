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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.geography.Country;

import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.ProviderCatalogUtils;

/**
 * Shared fixtures for the {@link FinancialAccountHandler#validateAndEnrichCreate} provider tests
 * ({@link FinancialAccountHandlerProviderTest}, {@link FinancialAccountHandlerProviderLogoTest}).
 *
 * <p>Strategy: spy the handler and stub its DAL-bound seams ({@code loadCurrency},
 * {@code nameExists}, {@code loadCountry}, {@code listMatchingAlgorithms}); wire a spied
 * {@link FinancialAccountProviderEnricher} into {@code handler.providerEnricher} and stub the
 * fill-only {@code findExistingProvider} seam on that enricher spy; statically mock
 * {@link ProviderCatalogUtils} / {@link OBDal} so no database or live OBContext is needed.
 * The two concrete classes are split only to keep each under the Sonar 35-method-per-class limit.
 * Subclasses supply {@code @RunWith(MockitoJUnitRunner.Silent.class)}; it also initializes the
 * {@code @Mock} fields declared here.
 */
abstract class FinancialAccountProviderTestSupport {

  static final String EUR_ID = "102";
  static final String ES_COUNTRY_ID = "106";
  static final String PROVIDER_CODE = "providerCode";
  static final String PROVIDER_NAME = "providerName";
  static final String PSD2_PROVIDER = "psd2Provider";
  static final String FIELD_TYPE = "type";
  static final String TYPE_CARD = "CA";
  static final String SANTANDER_CODE = "santander";
  static final String SANTANDER_NAME = "Banco Santander";
  static final String PROVIDER_FK_ID = "prov-1";
  static final String PROVIDER_LOGO_URL = "providerLogoUrl";
  static final String TRUSTED_LOGO_HOST = "d1uuj3mi6rzwpm.cloudfront.net";
  static final String SANTANDER_LOGO =
      "https://" + TRUSTED_LOGO_HOST + "/logos/providers/es/santander.svg";
  static final int MAX_LOGO_LENGTH = 255;

  @Mock
  Provider logoProvider;

  @Mock
  OBDal logoDal;

  FinancialAccountHandler handler;

  FinancialAccountProviderEnricher enricher;

  /** Spies the handler and neutralizes the OBContext/rollback seams (no live session in CI). */
  @Before
  public void setUp() {
    handler = spy(new FinancialAccountHandler());
    enricher = spy(new FinancialAccountProviderEnricher());
    handler.providerEnricher = enricher;
    doNothing().when(handler).enterAdminMode();
    doNothing().when(handler).exitAdminMode();
    doNothing().when(handler).doRollbackAndClose();
  }

  /** Clears the inline mock cache after each test to keep the single-JVM suite heap flat. */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  JSONObject bankBodyWithLogo(String logo) throws Exception {
    return validCreateBody().put(PROVIDER_CODE, SANTANDER_CODE)
        .put(PROVIDER_NAME, SANTANDER_NAME)
        .put(PROVIDER_LOGO_URL, logo);
  }

  static String logoOfLength(int length) {
    String prefix = "https://" + TRUSTED_LOGO_HOST + "/";
    StringBuilder sb = new StringBuilder(prefix);
    while (sb.length() < length) {
      sb.append('a');
    }
    return sb.toString();
  }

  /**
   * Runs a bank/card create and asserts the 4-arg upsert received {@code expectedLogo} (possibly
   * null), the FK was injected and every transient provider key was stripped.
   */
  void assertUpsertedWithLogo(JSONObject body, String expectedLogo) throws Exception {
    assertUpsertedWithLogo(body, null, expectedLogo);
  }

  /**
   * Same as {@link #assertUpsertedWithLogo(JSONObject, String)} with {@code existing} returned by
   * the fill-only seam {@code findExistingProvider} ({@code null} = not in the catalog yet). The
   * seam is stubbed on the enricher spy — never MockedStatic
   * FinancialAccountBankConnectionSupport, which would also mock its private statics.
   */
  void assertUpsertedWithLogo(JSONObject body, Provider existing, String expectedLogo)
      throws Exception {
    stubValidCreate();
    doReturn(existing).when(enricher).findExistingProvider(SANTANDER_CODE);
    when(logoProvider.getId()).thenReturn(PROVIDER_FK_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ProviderCatalogUtils> utils = mockStatic(ProviderCatalogUtils.class)) {
      obDal.when(OBDal::getInstance).thenReturn(logoDal);
      utils.when(() -> ProviderCatalogUtils.upsertProvider(SANTANDER_CODE, SANTANDER_NAME, null,
          expectedLogo)).thenReturn(logoProvider);

      assertNull(handler.validateAndEnrichCreate(body));

      assertTrue("provider FK injected", body.has(PSD2_PROVIDER));
      assertEquals(PROVIDER_FK_ID, body.getString(PSD2_PROVIDER));
      assertFalse("transient providerCode stripped", body.has(PROVIDER_CODE));
      assertFalse("transient providerName stripped", body.has(PROVIDER_NAME));
      assertFalse("transient providerLogoUrl stripped", body.has(PROVIDER_LOGO_URL));
      utils.verify(() -> ProviderCatalogUtils.upsertProvider(SANTANDER_CODE, SANTANDER_NAME, null,
          expectedLogo));
    }
  }

  /**
   * Runs a bank create with an unacceptable {@code rawLogo}: the upsert gets a null logo and the
   * fill-only provider lookup is never performed.
   */
  void assertLogoRejected(String rawLogo) throws Exception {
    assertUpsertedWithLogo(bankBodyWithLogo(rawLogo), null);
    verify(enricher, never()).findExistingProvider(anyString());
  }

  /** Country is mandatory on create for every account type (ETP-5473), so the fixture carries one. */
  JSONObject validCreateBody() throws Exception {
    return new JSONObject().put("name", "BBVA").put("currency", EUR_ID).put("country", ES_COUNTRY_ID);
  }

  void stubValidCreate() {
    doReturn(mock(Currency.class)).when(handler).loadCurrency(EUR_ID);
    doReturn(false).when(handler).nameExists("BBVA", null);
    doReturn(Collections.emptyList()).when(handler).listMatchingAlgorithms();
    doReturn(mock(Country.class)).when(handler).loadCountry(ES_COUNTRY_ID);
  }
}
