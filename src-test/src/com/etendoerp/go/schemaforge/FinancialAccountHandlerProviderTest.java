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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.psd2.bank.integration.data.Provider;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;

/**
 * Mockito-driven unit tests for {@link FinancialAccountHandler#validateAndEnrichCreate} focused on
 * the {@code enrichProvider} step added by the bank connection bridge (offline "with bank selected" flow).
 *
 * <p>Split out of {@link FinancialAccountHandlerTest} so that file (already at the Sonar
 * 35-method-per-class ceiling) is not pushed over it. Strategy mirrors the sibling file: spy the
 * handler, stub the DAL-bound seams ({@code loadCurrency}, {@code nameExists},
 * {@code listMatchingAlgorithms}) and statically mock {@link BankIntegrationUtils} /
 * {@link OBDal} so no database or live OBContext is needed.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>Bank create + providerCode → upsertProvider(code, name, null), the FK id injected under
 *       {@code psd2Provider}, transient keys stripped.</li>
 *   <li>Bank create + providerCode without providerName → name defaults to the code.</li>
 *   <li>Non-bank (cash) create + providerCode → no upsert / no FK; keys still stripped.</li>
 *   <li>Bank create without providerCode → no upsert / no FK.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountHandlerProviderTest {

  private static final String EUR_ID = "102";
  private static final String PROVIDER_CODE = "providerCode";
  private static final String PROVIDER_NAME = "providerName";
  private static final String PSD2_PROVIDER = "psd2Provider";
  private static final String FIELD_TYPE = "type";
  private static final String SANTANDER_CODE = "santander";
  private static final String SANTANDER_NAME = "Banco Santander";
  private static final String PROVIDER_FK_ID = "prov-1";

  private FinancialAccountHandler handler;

  /** Spies the handler and neutralizes the OBContext/rollback seams (no live session in CI). */
  @Before
  public void setUp() {
    handler = spy(new FinancialAccountHandler());
    doNothing().when(handler).enterAdminMode();
    doNothing().when(handler).exitAdminMode();
    doNothing().when(handler).doRollbackAndClose();
  }

  /** Clears the inline mock cache after each test to keep the single-JVM suite heap flat. */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

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
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      utils.when(() -> BankIntegrationUtils.upsertProvider(SANTANDER_CODE, SANTANDER_NAME, null))
          .thenReturn(provider);

      assertNull(handler.validateAndEnrichCreate(body));

      assertEquals(PROVIDER_FK_ID, body.getString(PSD2_PROVIDER));
      assertFalse("transient providerCode stripped", body.has(PROVIDER_CODE));
      assertFalse("transient providerName stripped", body.has(PROVIDER_NAME));
      utils.verify(() -> BankIntegrationUtils.upsertProvider(SANTANDER_CODE, SANTANDER_NAME, null));
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
        MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      OBDal dal = mock(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      utils.when(() -> BankIntegrationUtils.upsertProvider(SANTANDER_CODE, SANTANDER_CODE, null))
          .thenReturn(provider);

      assertNull(handler.validateAndEnrichCreate(body));

      assertEquals(PROVIDER_FK_ID, body.getString(PSD2_PROVIDER));
      utils.verify(() -> BankIntegrationUtils.upsertProvider(SANTANDER_CODE, SANTANDER_CODE, null));
    }
  }

  /**
   * A non-bank account (Cash) carrying a {@code providerCode} injects no provider FK and performs
   * no upsert; the transient keys are still stripped.
   */
  @Test
  public void testCreateNonBankWithProviderCodeInjectsNothing() throws Exception {
    JSONObject body = validCreateBody().put(FIELD_TYPE, "C").put(PROVIDER_CODE, SANTANDER_CODE);
    stubValidCreate();

    try (MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      assertNull(handler.validateAndEnrichCreate(body));

      assertFalse("no provider FK injected for a non-bank account", body.has(PSD2_PROVIDER));
      assertFalse("transient providerCode stripped", body.has(PROVIDER_CODE));
      utils.verifyNoInteractions();
    }
  }

  /** A bank account with no {@code providerCode} injects no provider FK and never upserts. */
  @Test
  public void testCreateBankWithoutProviderCodeInjectsNothing() throws Exception {
    JSONObject body = validCreateBody();
    stubValidCreate();

    try (MockedStatic<BankIntegrationUtils> utils = mockStatic(BankIntegrationUtils.class)) {
      assertNull(handler.validateAndEnrichCreate(body));

      assertFalse("no provider FK injected without a provider code", body.has(PSD2_PROVIDER));
      utils.verifyNoInteractions();
    }
  }

  // ── fixtures ──────────────────────────────────────────────────────────────

  private JSONObject validCreateBody() throws Exception {
    return new JSONObject().put("name", "BBVA").put("currency", EUR_ID);
  }

  private void stubValidCreate() {
    doReturn(mock(Currency.class)).when(handler).loadCurrency(EUR_ID);
    doReturn(false).when(handler).nameExists("BBVA", null);
    doReturn(Collections.emptyList()).when(handler).listMatchingAlgorithms();
  }
}
