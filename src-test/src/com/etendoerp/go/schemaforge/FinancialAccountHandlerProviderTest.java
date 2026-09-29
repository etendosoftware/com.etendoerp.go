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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.ProviderCatalogUtils;

/**
 * Mockito-driven unit tests for {@link FinancialAccountHandler#validateAndEnrichCreate} /
 * {@link FinancialAccountHandler#validateAndEnrichUpdate} focused on the {@code enrichProvider}
 * step of the offline "with bank selected" flow, for <b>bank and card</b> accounts.
 *
 * <p>Split out of {@link FinancialAccountHandlerTest} so that file (already at the Sonar
 * 35-method-per-class ceiling) is not pushed over it; the logo URL sanitizer cases live in
 * {@link FinancialAccountHandlerProviderLogoTest} for the same reason, and the shared fixtures in
 * {@link FinancialAccountProviderTestSupport}.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>Bank or card create + providerCode → upsertProvider(code, name, null, logoUrl), the FK
 *       id injected under {@code psd2Provider}, transient keys stripped.</li>
 *   <li>Bank create + providerCode without providerName → name defaults to the code.</li>
 *   <li>Fill-only logo (ETP-5521): the sanitized logo reaches the upsert only when the provider
 *       is new or has no stored logo; the lookup is skipped when there is no usable logo.</li>
 *   <li>Cash create + providerCode/logo → no upsert / no FK; keys still stripped.</li>
 *   <li>Bank or card create without providerCode → no upsert / no FK; keys still stripped.</li>
 *   <li>Update → the three provider keys are always stripped (create-only).</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountHandlerProviderTest extends FinancialAccountProviderTestSupport {

  /**
   * A bank account created with a {@code providerCode} (and {@code providerName}) upserts the Salt
   * Edge provider, flushes, and injects the resolved {@code psd2Provider} FK into the body. The
   * transient {@code providerCode}/{@code providerName} keys are stripped so they are not treated
   * as entity properties.
   */
  @Test
  public void testCreateBankWithProviderCodeUpsertsAndInjectsFk() throws Exception {
    JSONObject body = validCreateBody().put(PROVIDER_CODE, SANTANDER_CODE).put(PROVIDER_NAME,
        SANTANDER_NAME);
    stubValidCreate();
    Provider provider = mock(Provider.class);
    when(provider.getId()).thenReturn(PROVIDER_FK_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ProviderCatalogUtils> utils = mockStatic(ProviderCatalogUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      utils.when(() -> ProviderCatalogUtils.upsertProvider(SANTANDER_CODE, SANTANDER_NAME, null,
          null)).thenReturn(provider);

      assertNull(handler.validateAndEnrichCreate(body));

      assertEquals(PROVIDER_FK_ID, body.getString(PSD2_PROVIDER));
      assertFalse("transient providerCode stripped", body.has(PROVIDER_CODE));
      assertFalse("transient providerName stripped", body.has(PROVIDER_NAME));
      utils.verify(
          () -> ProviderCatalogUtils.upsertProvider(SANTANDER_CODE, SANTANDER_NAME, null, null));
      verify(dal).flush();
    }
  }

  /**
   * When {@code providerName} is absent the provider code is reused as the upsert name, and the FK
   * is still injected.
   */
  @Test
  public void testCreateBankWithProviderCodeDefaultsNameToCode() throws Exception {
    JSONObject body = validCreateBody().put(PROVIDER_CODE, SANTANDER_CODE);
    stubValidCreate();
    Provider provider = mock(Provider.class);
    when(provider.getId()).thenReturn(PROVIDER_FK_ID);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<ProviderCatalogUtils> utils = mockStatic(ProviderCatalogUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      utils.when(() -> ProviderCatalogUtils.upsertProvider(SANTANDER_CODE, SANTANDER_CODE, null,
          null)).thenReturn(provider);

      assertNull(handler.validateAndEnrichCreate(body));

      assertEquals(PROVIDER_FK_ID, body.getString(PSD2_PROVIDER));
      utils.verify(
          () -> ProviderCatalogUtils.upsertProvider(SANTANDER_CODE, SANTANDER_CODE, null, null));
    }
  }

  /**
   * A cash account carrying a {@code providerCode} injects no provider FK and performs
   * no upsert; the transient keys are still stripped.
   */
  @Test
  public void testCreateCashWithProviderCodeInjectsNothing() throws Exception {
    JSONObject body = validCreateBody().put(FIELD_TYPE, "C").put(PROVIDER_CODE, SANTANDER_CODE);
    stubValidCreate();

    try (MockedStatic<ProviderCatalogUtils> utils = mockStatic(ProviderCatalogUtils.class)) {
      assertNull(handler.validateAndEnrichCreate(body));

      assertFalse("no provider FK injected for a cash account", body.has(PSD2_PROVIDER));
      assertFalse("transient providerCode stripped", body.has(PROVIDER_CODE));
      utils.verifyNoInteractions();
    }
  }

  /** A bank account with no {@code providerCode} injects no provider FK and never upserts. */
  @Test
  public void testCreateBankWithoutProviderCodeInjectsNothing() throws Exception {
    JSONObject body = validCreateBody();
    stubValidCreate();

    try (MockedStatic<ProviderCatalogUtils> utils = mockStatic(ProviderCatalogUtils.class)) {
      assertNull(handler.validateAndEnrichCreate(body));

      assertFalse("no provider FK injected without a provider code", body.has(PSD2_PROVIDER));
      utils.verifyNoInteractions();
    }
  }

  /**
   * ETP-5521: a bank create with no {@code providerLogoUrl} never looks the provider up — the
   * fill-only lookup is skipped when there is no usable logo.
   */
  @Test
  public void testCreateBankWithoutLogoSkipsProviderLookup() throws Exception {
    JSONObject body = validCreateBody().put(PROVIDER_CODE, SANTANDER_CODE).put(PROVIDER_NAME,
        SANTANDER_NAME);
    assertUpsertedWithLogo(body, null);
    verify(enricher, never()).findExistingProvider(anyString());
  }

  /**
   * ETP-5521 fill-only: an already-registered provider that has a logo keeps it — the upsert
   * receives a null logo, so the client value never overwrites the shared catalog row.
   */
  @Test
  public void testCreateBankWithExistingProviderLogoKeepsStoredLogo() throws Exception {
    Provider existing = mock(Provider.class);
    when(existing.getLogoURL()).thenReturn("https://" + TRUSTED_LOGO_HOST + "/stored.svg");

    assertUpsertedWithLogo(bankBodyWithLogo(SANTANDER_LOGO), existing, null);
    verify(enricher).findExistingProvider(SANTANDER_CODE);
  }

  /** ETP-5521 fill-only: an existing provider with a blank logo gets the client logo filled in. */
  @Test
  public void testCreateBankWithExistingProviderBlankLogoFillsLogo() throws Exception {
    Provider existing = mock(Provider.class);
    when(existing.getLogoURL()).thenReturn("  ");

    assertUpsertedWithLogo(bankBodyWithLogo(SANTANDER_LOGO), existing, SANTANDER_LOGO);
  }

  /** ETP-5521 fill-only: an existing provider with a null logo gets the client logo filled in. */
  @Test
  public void testCreateBankWithExistingProviderNullLogoFillsLogo() throws Exception {
    Provider existing = mock(Provider.class);
    when(existing.getLogoURL()).thenReturn(null);

    assertUpsertedWithLogo(bankBodyWithLogo(SANTANDER_LOGO), existing, SANTANDER_LOGO);
  }

  /**
   * ETP-5521: a cash account ignores {@code providerLogoUrl} entirely — no upsert — but
   * the transient key is still stripped so it is not treated as an entity property.
   */
  @Test
  public void testCreateCashWithLogoIgnoresLogoAndStripsKey() throws Exception {
    JSONObject body = validCreateBody().put(FIELD_TYPE, "C")
        .put(PROVIDER_CODE, SANTANDER_CODE)
        .put(PROVIDER_LOGO_URL, SANTANDER_LOGO);
    stubValidCreate();

    try (MockedStatic<ProviderCatalogUtils> utils = mockStatic(ProviderCatalogUtils.class)) {
      assertNull(handler.validateAndEnrichCreate(body));

      assertFalse("no provider FK injected for a cash account", body.has(PSD2_PROVIDER));
      assertFalse("transient providerLogoUrl stripped", body.has(PROVIDER_LOGO_URL));
      utils.verifyNoInteractions();
    }
  }

  /**
   * ETP-5521: a bank account without a {@code providerCode} but with a stray
   * {@code providerLogoUrl} does not upsert and still strips the logo key.
   */
  @Test
  public void testCreateBankWithoutProviderCodeStripsLogoKey() throws Exception {
    JSONObject body = validCreateBody().put(PROVIDER_LOGO_URL, SANTANDER_LOGO);
    stubValidCreate();

    try (MockedStatic<ProviderCatalogUtils> utils = mockStatic(ProviderCatalogUtils.class)) {
      assertNull(handler.validateAndEnrichCreate(body));

      assertFalse("transient providerLogoUrl stripped", body.has(PROVIDER_LOGO_URL));
      utils.verifyNoInteractions();
    }
  }

  /**
   * ETP-5521: the provider keys are create-only — an update strips {@code providerCode},
   * {@code providerName} and {@code providerLogoUrl} so the generic CRUD never sees them, and
   * never touches the provider catalog.
   */
  @Test
  public void testUpdateStripsTransientProviderKeys() throws Exception {
    JSONObject body = new JSONObject().put(PROVIDER_CODE, SANTANDER_CODE)
        .put(PROVIDER_NAME, SANTANDER_NAME)
        .put(PROVIDER_LOGO_URL, SANTANDER_LOGO);

    try (MockedStatic<ProviderCatalogUtils> utils = mockStatic(ProviderCatalogUtils.class)) {
      assertNull(handler.validateAndEnrichUpdate("acc-1", body));

      assertFalse("providerCode stripped on update", body.has(PROVIDER_CODE));
      assertFalse("providerName stripped on update", body.has(PROVIDER_NAME));
      assertFalse("providerLogoUrl stripped on update", body.has(PROVIDER_LOGO_URL));
      utils.verifyNoInteractions();
    }
  }

  /**
   * ETP-5521: a card account also goes through the wizard's bank picker, so a card create with a
   * {@code providerCode} upserts the provider, injects the {@code psd2Provider} FK and strips the
   * transient keys — exactly like a bank account. No logo → the fill-only lookup is skipped.
   */
  @Test
  public void testCreateCardWithProviderCodeUpsertsAndInjectsFk() throws Exception {
    JSONObject body = validCreateBody().put(FIELD_TYPE, TYPE_CARD)
        .put(PROVIDER_CODE, SANTANDER_CODE)
        .put(PROVIDER_NAME, SANTANDER_NAME);
    assertUpsertedWithLogo(body, null);
    assertEquals(TYPE_CARD, body.getString(FIELD_TYPE));
    verify(enricher, never()).findExistingProvider(anyString());
  }

  /**
   * ETP-5521: a card create with a valid Salt Edge CDN logo for a provider not in the catalog yet
   * takes the fill-only path and passes the logo to the upsert.
   */
  @Test
  public void testCreateCardWithCdnLogoFillsLogo() throws Exception {
    JSONObject body = bankBodyWithLogo(SANTANDER_LOGO).put(FIELD_TYPE, TYPE_CARD);
    assertUpsertedWithLogo(body, null, SANTANDER_LOGO);
    verify(enricher).findExistingProvider(SANTANDER_CODE);
  }

  /**
   * ETP-5521: a card create without a {@code providerCode} links no provider and never upserts,
   * but a stray {@code providerName}/{@code providerLogoUrl} is still stripped.
   */
  @Test
  public void testCreateCardWithoutProviderCodeInjectsNothing() throws Exception {
    JSONObject body = validCreateBody().put(FIELD_TYPE, TYPE_CARD)
        .put(PROVIDER_NAME, SANTANDER_NAME)
        .put(PROVIDER_LOGO_URL, SANTANDER_LOGO);
    stubValidCreate();

    try (MockedStatic<ProviderCatalogUtils> utils = mockStatic(ProviderCatalogUtils.class)) {
      assertNull(handler.validateAndEnrichCreate(body));

      assertFalse("no provider FK injected without a provider code", body.has(PSD2_PROVIDER));
      assertFalse("transient providerName stripped", body.has(PROVIDER_NAME));
      assertFalse("transient providerLogoUrl stripped", body.has(PROVIDER_LOGO_URL));
      utils.verifyNoInteractions();
    }
  }
}
