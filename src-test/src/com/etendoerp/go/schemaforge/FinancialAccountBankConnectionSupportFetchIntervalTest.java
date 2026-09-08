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

import static com.etendoerp.go.schemaforge.BankConnectionHandlerTestSupport.stubProviderLookup;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.psd2.bank.integration.data.FinaccConnection;
import com.etendoerp.psd2.bank.integration.data.Provider;

/**
 * Unit tests for {@link FinancialAccountBankConnectionSupport#maxFetchIntervalOf}, the ETP-5181
 * lookup behind the "Importar desde" advisory: the number of days of history the account's PSD2
 * provider actually publishes, or null when there is no usable limit to advise against.
 *
 * <p>The limit is resolved from the CONNECTION's {@code providerCode}, deliberately not from the
 * {@code FIN_FinancialAccount.psd2Provider} FK, so the field-level hint can never quote a
 * different number from the warning the synchronization itself prints (the PSD2 module resolves
 * the provider the same way).
 *
 * <p>Every "no limit" answer is a distinct branch and each is pinned separately, because the whole
 * point of the method is that it NEVER invents a value: {@code fetchAndRegisterProvider} already
 * writes a made-up 90 when the provider details call fails, and a second default here would make
 * an unpublished limit indistinguishable from a real one.
 *
 * <p>The three cheap guards (null connection, transactions not handled, blank provider code) are
 * asserted to short-circuit BEFORE any DAL work, via {@code verifyNoInteractions} on the mocked
 * {@link OBDal} — that ordering is what keeps {@code GET status} from paying for a criteria query
 * on every account that cannot sync anyway.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class FinancialAccountBankConnectionSupportFetchIntervalTest {

  private static final String PROVIDER_CODE = "bbva";

  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  /** A missing connection means the account cannot sync at all — nothing to advise about. */
  @Test
  public void testNullConnectionHasNoLimit() {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(null));
      obDal.verifyNoInteractions();
    }
  }

  /**
   * A connection that does not handle transactions never imports movements, so the import range
   * is irrelevant to it. Guarded before the provider lookup so the query is not paid for.
   */
  @Test
  public void testConnectionNotHandlingTransactionsHasNoLimit() {
    FinaccConnection connection = connection(Boolean.FALSE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
      obDal.verifyNoInteractions();
    }
  }

  /**
   * {@code isHandlesTransactions()} is a boxed {@link Boolean} and is nullable in the DB, so the
   * check has to be {@code !Boolean.TRUE.equals(...)}: a plain {@code !flag} would unbox null and
   * throw an NPE straight out of {@code GET status}.
   */
  @Test
  public void testNullHandlesTransactionsFlagHasNoLimit() {
    FinaccConnection connection = connection(null, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
      obDal.verifyNoInteractions();
    }
  }

  /** A blank provider code cannot resolve a catalog row; short-circuited before the query. */
  @Test
  public void testBlankProviderCodeHasNoLimit() {
    FinaccConnection connection = connection(Boolean.TRUE, "   ");

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
      obDal.verifyNoInteractions();
    }
  }

  /** Same guard, null rather than whitespace. */
  @Test
  public void testNullProviderCodeHasNoLimit() {
    FinaccConnection connection = connection(Boolean.TRUE, null);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
      obDal.verifyNoInteractions();
    }
  }

  /** The provider code is set but no catalog row matches it (never synced) — advise nothing. */
  @Test
  public void testUnknownProviderHasNoLimit() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, null);

      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  /**
   * The provider exists but published no interval. This must stay null rather than fall back to
   * 90: the SPA reads a missing value as "advise nothing", and a guessed default would advise
   * against dates the bank may well serve.
   */
  @Test
  public void testUnpublishedLimitHasNoLimit() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(null));

      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  /** Zero days is not a real limit; advising against it would flag every past date. */
  @Test
  public void testZeroLimitHasNoLimit() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(BigDecimal.ZERO));

      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  /** Same for a negative value, which the DECIMAL(10,0) column does not prevent. */
  @Test
  public void testNegativeLimitHasNoLimit() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(BigDecimal.valueOf(-30)));

      assertNull(FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  /**
   * The happy path: a published positive limit comes back as a plain {@link Integer}.
   *
   * <p>The narrowing lives here rather than in the caller on purpose. The DAL hands back a
   * {@code BigDecimal}, and jettison's {@code JSONObject.getInt()} unwraps one happily — so a
   * bridge that put the raw BigDecimal on the payload would emit {@code 90.0} while every
   * int-shaped assertion still passed, and the SPA would render "90.0 días". Returning
   * {@code Integer} makes that unrepresentable.
   */
  @Test
  public void testPublishedLimitIsReturned() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(BigDecimal.valueOf(90)));

      assertEquals(Integer.valueOf(90),
          FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  /**
   * A {@code DECIMAL(10,0)} column can still arrive carrying a scale ({@code 90.00}), which is a
   * different {@code BigDecimal} from {@code 90} under {@code equals}. The narrowing must flatten
   * it to the same int rather than let the stored scale reach the wire.
   */
  @Test
  public void testScaledColumnValueIsNarrowedToAnInt() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(new BigDecimal("90.00")));

      assertEquals(Integer.valueOf(90),
          FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  /**
   * A provider that serves MORE than the regulation's 90-day baseline must be reported at its own
   * value. This is the case a hardcoded 90 anywhere in the chain would silently break.
   */
  @Test
  public void testLimitAboveTheRegulatoryBaselineIsNotClamped() {
    FinaccConnection connection = connection(Boolean.TRUE, PROVIDER_CODE);

    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      stubProviderLookup(obDal, provider(BigDecimal.valueOf(730)));

      assertEquals(Integer.valueOf(730),
          FinancialAccountBankConnectionSupport.maxFetchIntervalOf(connection));
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static FinaccConnection connection(Boolean handlesTransactions, String providerCode) {
    FinaccConnection connection = mock(FinaccConnection.class);
    when(connection.isHandlesTransactions()).thenReturn(handlesTransactions);
    when(connection.getProviderCode()).thenReturn(providerCode);
    return connection;
  }

  private static Provider provider(BigDecimal maxFetchInterval) {
    Provider provider = mock(Provider.class);
    when(provider.getMaxFetchInterval()).thenReturn(maxFetchInterval);
    return provider;
  }
}
