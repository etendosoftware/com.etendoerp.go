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
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Pure unit tests for {@link FinancialAccountTransactionsSupport}: Classic-parity label mappings,
 * payment-label assembly, date formatting, day arithmetic, role filters, conversion-rate rule, and
 * request-body parsing helpers. No DB or OBBaseTest — everything is either pure or mocked.
 */
public class FinancialAccountTransactionsSupportTest {

  /**
   * Releases the inline-mock references retained by Mockito for the mocks created in the
   * {@code setOptionalRef} tests below (which static-mock {@link OBDal}). The module runs a single
   * test JVM, so leaking these across the whole suite pushes the fork past its heap limit — clearing
   * them after each test keeps the heap flat.
   */
  @After
  public void clearMocks() {
    Mockito.framework().clearInlineMocks();
  }

  // ---------------------------------------------------------------
  // trxTypeClassicLabel
  // ---------------------------------------------------------------

  @Test
  public void testTrxTypeClassicLabelKnownCodesMapToLabels() {
    assertEquals("BP Deposit", FinancialAccountTransactionsSupport.trxTypeClassicLabel("BPD"));
    assertEquals("BP Withdrawal", FinancialAccountTransactionsSupport.trxTypeClassicLabel("BPW"));
  }

  @Test
  public void testTrxTypeClassicLabelUnknownCodePassesThrough() {
    assertEquals("XYZ", FinancialAccountTransactionsSupport.trxTypeClassicLabel("XYZ"));
  }

  @Test
  public void testTrxTypeClassicLabelBlankReturnsEmpty() {
    assertEquals("", FinancialAccountTransactionsSupport.trxTypeClassicLabel(null));
    assertEquals("", FinancialAccountTransactionsSupport.trxTypeClassicLabel(""));
    assertEquals("", FinancialAccountTransactionsSupport.trxTypeClassicLabel("   "));
  }

  // ---------------------------------------------------------------
  // statusClassicLabel
  // ---------------------------------------------------------------

  @Test
  public void testStatusClassicLabelKnownCodesMapToLabels() {
    assertEquals("Awaiting Payment", FinancialAccountTransactionsSupport.statusClassicLabel("RPAP"));
    assertEquals("Awaiting Execution", FinancialAccountTransactionsSupport.statusClassicLabel("RPAE"));
    assertEquals("Voided", FinancialAccountTransactionsSupport.statusClassicLabel("RPVOID"));
    assertEquals("Payment Received", FinancialAccountTransactionsSupport.statusClassicLabel("RPR"));
    assertEquals("Payment Made", FinancialAccountTransactionsSupport.statusClassicLabel("PPM"));
    assertEquals("Withdrawn not Cleared", FinancialAccountTransactionsSupport.statusClassicLabel("PWNC"));
    assertEquals("Deposited not Cleared", FinancialAccountTransactionsSupport.statusClassicLabel("RDNC"));
    assertEquals("Payment Cleared", FinancialAccountTransactionsSupport.statusClassicLabel("RPPC"));
  }

  @Test
  public void testStatusClassicLabelUnknownCodePassesThrough() {
    assertEquals("FOO", FinancialAccountTransactionsSupport.statusClassicLabel("FOO"));
  }

  @Test
  public void testStatusClassicLabelBlankReturnsEmpty() {
    assertEquals("", FinancialAccountTransactionsSupport.statusClassicLabel(null));
    assertEquals("", FinancialAccountTransactionsSupport.statusClassicLabel(""));
  }

  // ---------------------------------------------------------------
  // buildPaymentLabel
  // ---------------------------------------------------------------

  @Test
  public void testBuildPaymentLabelAllPartsJoinedWithSeparator() {
    // 2026-01-15T00:00:00Z (UTC) → 15-01-2026
    long epoch = LocalDate.of(2026, 1, 15).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    Timestamp date = new Timestamp(epoch);

    String label = FinancialAccountTransactionsSupport.buildPaymentLabel(
        "DOC-001", date, "Acme Corp", new BigDecimal("-1234.50"));

    assertEquals("DOC-001 - 15-01-2026 - Acme Corp - 1234.5", label);
  }

  @Test
  public void testBuildPaymentLabelNullDateAndAmountSkipped() {
    String label = FinancialAccountTransactionsSupport.buildPaymentLabel(
        "DOC-002", null, "Vendor", null);
    // null date → empty, skipped; null amount → ZERO → "0"
    assertEquals("DOC-002 - Vendor - 0", label);
  }

  @Test
  public void testBuildPaymentLabelBlankPartsAreOmitted() {
    String label = FinancialAccountTransactionsSupport.buildPaymentLabel(
        "  ", null, null, new BigDecimal("100"));
    assertEquals("100", label);
  }

  @Test
  public void testBuildPaymentLabelAmountAbsoluteValueAndTrailingZerosStripped() {
    String label = FinancialAccountTransactionsSupport.buildPaymentLabel(
        null, null, null, new BigDecimal("-50.000"));
    assertEquals("50", label);
  }

  // ---------------------------------------------------------------
  // formatDmy
  // ---------------------------------------------------------------

  @Test
  public void testFormatDmyFormatsDate() {
    assertEquals("15/01/2026",
        FinancialAccountTransactionsSupport.formatDmy(java.sql.Date.valueOf("2026-01-15")));
  }

  @Test
  public void testFormatDmyNullReturnsEmpty() {
    assertEquals("", FinancialAccountTransactionsSupport.formatDmy(null));
  }

  // ---------------------------------------------------------------
  // daysUntil
  // ---------------------------------------------------------------

  @Test
  public void testDaysUntilFutureIsPositive() {
    LocalDate today = LocalDate.of(2026, 1, 1);
    java.sql.Date due = java.sql.Date.valueOf("2026-01-11");
    assertEquals(10, FinancialAccountTransactionsSupport.daysUntil(due, today));
  }

  @Test
  public void testDaysUntilPastIsNegative() {
    LocalDate today = LocalDate.of(2026, 1, 11);
    java.sql.Date due = java.sql.Date.valueOf("2026-01-01");
    assertEquals(-10, FinancialAccountTransactionsSupport.daysUntil(due, today));
  }

  @Test
  public void testDaysUntilSameDayIsZero() {
    LocalDate today = LocalDate.of(2026, 1, 11);
    java.sql.Date due = java.sql.Date.valueOf("2026-01-11");
    assertEquals(0, FinancialAccountTransactionsSupport.daysUntil(due, today));
  }

  @Test
  public void testDaysUntilNullDueDateIsZero() {
    assertEquals(0, FinancialAccountTransactionsSupport.daysUntil(null, LocalDate.of(2026, 1, 1)));
  }

  // ---------------------------------------------------------------
  // bpartnerRoleFilter
  // ---------------------------------------------------------------

  @Test
  public void testBpartnerRoleFilterCustomer() {
    assertEquals(" AND iscustomer='Y'", FinancialAccountTransactionsSupport.bpartnerRoleFilter("customer"));
  }

  @Test
  public void testBpartnerRoleFilterVendor() {
    assertEquals(" AND isvendor='Y'", FinancialAccountTransactionsSupport.bpartnerRoleFilter("vendor"));
  }

  @Test
  public void testBpartnerRoleFilterOtherReturnsEmpty() {
    assertEquals("", FinancialAccountTransactionsSupport.bpartnerRoleFilter("any"));
    assertEquals("", FinancialAccountTransactionsSupport.bpartnerRoleFilter(null));
  }

  // ---------------------------------------------------------------
  // resolveConversionRate
  // ---------------------------------------------------------------

  @Test
  public void testResolveConversionRateSameCurrencyReturnsOne() {
    FIN_FinancialAccount source = mockAccountWithCurrency("USD");
    FIN_FinancialAccount dest = mockAccountWithCurrency("USD");

    assertEquals(BigDecimal.ONE,
        FinancialAccountTransactionsSupport.resolveConversionRate(source, dest, new BigDecimal("1.5")));
  }

  @Test
  public void testResolveConversionRateSameCurrencyCaseInsensitive() {
    FIN_FinancialAccount source = mockAccountWithCurrency("usd");
    FIN_FinancialAccount dest = mockAccountWithCurrency("USD");

    assertEquals(BigDecimal.ONE,
        FinancialAccountTransactionsSupport.resolveConversionRate(source, dest, new BigDecimal("1.5")));
  }

  @Test
  public void testResolveConversionRateDifferentCurrencyReturnsProvided() {
    FIN_FinancialAccount source = mockAccountWithCurrency("USD");
    FIN_FinancialAccount dest = mockAccountWithCurrency("EUR");
    BigDecimal provided = new BigDecimal("1.09");

    assertEquals(provided,
        FinancialAccountTransactionsSupport.resolveConversionRate(source, dest, provided));
  }

  @Test
  public void testResolveConversionRateDifferentCurrencyNullProvidedReturnsNull() {
    FIN_FinancialAccount source = mockAccountWithCurrency("USD");
    FIN_FinancialAccount dest = mockAccountWithCurrency("EUR");

    assertNull(FinancialAccountTransactionsSupport.resolveConversionRate(source, dest, null));
  }

  private static FIN_FinancialAccount mockAccountWithCurrency(String currencyId) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn(currencyId);
    when(account.getCurrency()).thenReturn(currency);
    return account;
  }

  // ---------------------------------------------------------------
  // optBigDecimal
  // ---------------------------------------------------------------

  @Test
  public void testOptBigDecimalMissingKeyReturnsNull() throws Exception {
    JSONObject body = new JSONObject();
    assertNull(FinancialAccountTransactionsSupport.optBigDecimal(body, "amount"));
  }

  @Test
  public void testOptBigDecimalNullValueReturnsNull() throws Exception {
    JSONObject body = new JSONObject();
    body.put("amount", JSONObject.NULL);
    assertNull(FinancialAccountTransactionsSupport.optBigDecimal(body, "amount"));
  }

  @Test
  public void testOptBigDecimalStringValueParsed() throws Exception {
    JSONObject body = new JSONObject();
    body.put("amount", "123.45");
    assertEquals(new BigDecimal("123.45"), FinancialAccountTransactionsSupport.optBigDecimal(body, "amount"));
  }

  @Test
  public void testOptBigDecimalNumericValueFallsBackToDouble() throws Exception {
    JSONObject body = new JSONObject();
    body.put("amount", 42.5);
    // getString on a numeric coerces in jettison, but the double fallback also yields 42.5
    assertEquals(0, new BigDecimal("42.5").compareTo(
        FinancialAccountTransactionsSupport.optBigDecimal(body, "amount")));
  }

  // ---------------------------------------------------------------
  // parseLocalDate
  // ---------------------------------------------------------------

  /**
   * A blank / null input returns the supplied fallback untouched, matching {@code parseDate}.
   */
  @Test
  public void testParseLocalDateBlankReturnsFallback() {
    Date fallback = new Date(0L);
    assertEquals(fallback, FinancialAccountTransactionsSupport.parseLocalDate(null, fallback));
    assertEquals(fallback, FinancialAccountTransactionsSupport.parseLocalDate("   ", fallback));
  }

  /**
   * A full ISO instant ("2026-07-16T00:00:00Z") is truncated to its date part and parsed to the
   * SERVER's local start-of-day (asserted against {@code ZoneId.systemDefault()} so the test is
   * timezone-independent).
   */
  @Test
  public void testParseLocalDateFullIsoParsedToLocalStartOfDay() {
    Date result = FinancialAccountTransactionsSupport.parseLocalDate("2026-07-16T00:00:00Z", null);
    long expected = LocalDate.of(2026, 7, 16)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    assertEquals(expected, result.getTime());
  }

  /**
   * A date-only value ("2026-07-16") is parsed to the server's local start-of-day.
   */
  @Test
  public void testParseLocalDateDateOnlyParsedToLocalStartOfDay() {
    Date result = FinancialAccountTransactionsSupport.parseLocalDate("2026-07-16", null);
    long expected = LocalDate.of(2026, 7, 16)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    assertEquals(expected, result.getTime());
  }

  /**
   * An unparseable value returns the fallback (the {@code catch} branch).
   */
  @Test
  public void testParseLocalDateInvalidReturnsFallback() {
    Date fallback = new Date(456L);
    assertEquals(fallback, FinancialAccountTransactionsSupport.parseLocalDate("not-a-date", fallback));
  }

  // ---------------------------------------------------------------
  // setOptionalRef
  // ---------------------------------------------------------------

  /**
   * When the key is ABSENT the reference is left unchanged — the setter is never called (and the
   * DAL is never touched).
   */
  @Test
  public void testSetOptionalRefAbsentKeyDoesNotCallSetter() {
    JSONObject body = new JSONObject();
    List<GLItem> captured = new ArrayList<>();
    Consumer<GLItem> setter = captured::add;

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      FinancialAccountTransactionsSupport.setOptionalRef(body, "glItemId", GLItem.class, setter);
      assertEquals(0, captured.size());
      obDalMock.verifyNoInteractions();
    }
  }

  /**
   * When the key is present-but-BLANK the reference is CLEARED — the setter is invoked once with
   * {@code null} and no DAL lookup happens.
   */
  @Test
  public void testSetOptionalRefBlankValueClearsReference() throws Exception {
    JSONObject body = new JSONObject();
    body.put("glItemId", "");
    List<GLItem> captured = new ArrayList<>();
    Consumer<GLItem> setter = captured::add;

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      FinancialAccountTransactionsSupport.setOptionalRef(body, "glItemId", GLItem.class, setter);
      assertEquals(1, captured.size());
      assertNull(captured.get(0));
      obDalMock.verifyNoInteractions();
    }
  }

  /**
   * When the key carries an id the referenced entity is loaded via {@code OBDal.get} and passed to
   * the setter.
   */
  @Test
  public void testSetOptionalRefWithIdLoadsAndSetsEntity() throws Exception {
    JSONObject body = new JSONObject();
    body.put("glItemId", "gl-1");
    GLItem glItem = mock(GLItem.class);
    List<GLItem> captured = new ArrayList<>();
    Consumer<GLItem> setter = captured::add;

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(GLItem.class), eq("gl-1"))).thenReturn(glItem);

      FinancialAccountTransactionsSupport.setOptionalRef(body, "glItemId", GLItem.class, setter);

      assertEquals(1, captured.size());
      assertSame(glItem, captured.get(0));
    }
  }

  // ---------------------------------------------------------------
  // private constructor (coverage of the no-arg ctor)
  // ---------------------------------------------------------------

  @Test
  public void testPrivateConstructorIsAccessibleViaReflection() throws Exception {
    java.lang.reflect.Constructor<FinancialAccountTransactionsSupport> ctor =
        FinancialAccountTransactionsSupport.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    assertTrue(ctor.newInstance() instanceof FinancialAccountTransactionsSupport);
  }
}
