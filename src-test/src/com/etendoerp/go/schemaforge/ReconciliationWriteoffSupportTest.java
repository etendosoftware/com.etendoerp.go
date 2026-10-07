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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;

/**
 * Tenant-isolation regression for {@link ReconciliationWriteoffSupport} (ETP-4950 / H5).
 *
 * <p>The write-off limit is asserted BEFORE the payment is created, which is what made this one
 * subtle: the {@code scheduleId} coming from the request body is read here, several calls earlier
 * than the {@code TenantOwnership.loadOwned} that {@code ReconciliationFlowSupport} applies to the
 * very same id. The guard existed; it just ran too late. Unguarded, a foreign instalment's
 * outstanding amount was added to the total, and the rejection message then reported the resulting
 * difference — handing the caller the exact pending amount of another tenant's invoice.
 *
 * <p>Both directions are covered on purpose: without the "owned" counterpart the regression would
 * also pass if the limit check stopped working altogether.
 *
 * <p>{@link ConversionRouting} covers the other decision of
 * {@link ReconciliationWriteoffSupport#payInvoicesFromBody} (ETP-5657): a body carrying any
 * explicit-conversion field goes to {@link ReconciliationConversionSupport}, any other body keeps
 * the default path untouched.
 *
 * @covers com.etendoerp.go.schemaforge.ReconciliationWriteoffSupport
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Write-off support — tenant isolation and conversion routing")
class ReconciliationWriteoffSupportTest {

  private static final String TENANT_CLIENT = "client-1";
  private static final String FOREIGN_CLIENT = "client-other";
  private static final String SCHEDULE_ID = "SCH-1";
  /** Outstanding of the instalment under test; the number that must never leak. */
  private static final String SECRET_OUTSTANDING = "1000.00";

  @Mock
  private FIN_FinancialAccount account;
  @Mock
  private FIN_BankStatementLine line;
  @Mock
  private FIN_PaymentSchedule schedule;
  @Mock
  private Client scheduleClient;
  @Mock
  private OBDal dal;
  @Mock
  private OBContext obContext;

  /** One invoice spec pointing at {@link #SCHEDULE_ID}. */
  private static JSONArray specsFor(String scheduleId) throws Exception {
    return new JSONArray().put(new JSONObject().put("scheduleId", scheduleId));
  }

  /**
   * Runs {@code payInvoices} with the schedule owned by {@code scheduleOwner}, the session able to
   * read only {@link #TENANT_CLIENT}, and the downstream payment creation stubbed out.
   */
  private NeoResponse runWithScheduleOwnedBy(String scheduleOwner) throws Exception {
    when(scheduleClient.getId()).thenReturn(scheduleOwner);
    when(schedule.getClient()).thenReturn(scheduleClient);
    when(schedule.getOutstandingAmount()).thenReturn(new BigDecimal(SECRET_OUTSTANDING));
    when(obContext.getReadableClients()).thenReturn(new String[] {TENANT_CLIENT});
    when(obContext.getReadableOrganizations()).thenReturn(new String[] {"org-1"});
    // A 10.00 inflow against a 1.00 write-off limit: with the foreign outstanding counted the
    // difference is 990.00 and the request is rejected; without it, nothing to reject.
    when(line.getCramount()).thenReturn(new BigDecimal("10.00"));
    when(line.getDramount()).thenReturn(BigDecimal.ZERO);
    when(account.getWriteofflimit()).thenReturn(BigDecimal.ONE);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBContext> context = mockStatic(OBContext.class);
        MockedStatic<ReconciliationFlowSupport> flow =
            mockStatic(ReconciliationFlowSupport.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(FIN_PaymentSchedule.class), anyString())).thenReturn(schedule);
      context.when(OBContext::getOBContext).thenReturn(obContext);
      flow.when(() -> ReconciliationFlowSupport.createInvoicePayments(any(), any(), any(), any(),
          any(), anyString(), anyBoolean())).thenReturn(null);

      return ReconciliationWriteoffSupport.payInvoices(account, line, specsFor(SCHEDULE_ID),
          new ArrayList<>(), BigDecimal.ZERO, "PM-1", true);
    }
  }

  /**
   * An instalment of another tenant contributes nothing to the write-off total, so no rejection is
   * produced and its outstanding amount never reaches the caller.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  @DisplayName("A foreign instalment neither counts nor leaks its amount")
  void testForeignInstalmentDoesNotCountTowardsTheWriteoffLimit() throws Exception {
    NeoResponse response = runWithScheduleOwnedBy(FOREIGN_CLIENT);

    assertNull(response, "a hidden instalment leaves nothing to write off, so nothing to reject");
  }

  /**
   * The same request with an instalment of the caller's OWN tenant is still rejected, and the
   * message still carries the difference — proving the previous test is not passing because the
   * limit check stopped working.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  @DisplayName("An owned instalment over the limit is still rejected")
  void testOwnedInstalmentOverTheLimitIsStillRejected() throws Exception {
    NeoResponse response = runWithScheduleOwnedBy(TENANT_CLIENT);

    assertNotNull(response, "990.00 over a 1.00 limit must be refused");
    assertEquals(400, response.getHttpStatus());
    String message = response.getBody().getJSONObject("error").getString("message");
    assertTrue(message.contains("990.00"), "the difference belongs in the message: " + message);
  }

  /**
   * The amount of a foreign instalment is absent from whatever the caller receives. Asserted
   * separately from the null check above because the leak was through the message text, not through
   * the status code.
   *
   * @throws Exception if the mocked interaction fails
   */
  @Test
  @DisplayName("The foreign outstanding amount appears nowhere in the response")
  void testForeignOutstandingIsNeverEchoedBack() throws Exception {
    NeoResponse response = runWithScheduleOwnedBy(FOREIGN_CLIENT);

    String body = response == null ? "" : String.valueOf(response.getBody());
    assertFalse(body.contains(SECRET_OUTSTANDING),
        "another tenant's pending amount must not be echoed back: " + body);
    assertFalse(body.contains("990"),
        "nor the difference derived from it: " + body);
  }

  // ── payInvoicesFromBody: explicit-conversion routing (ETP-5657) ──────────

  @Nested
  @DisplayName("payInvoicesFromBody — explicit-conversion routing")
  class ConversionRouting {

    private final NeoResponse conversionPath = NeoResponse.error(299, "conversion path");
    private final NeoResponse defaultPath = NeoResponse.error(298, "default path");
    private final FIN_FinancialAccount routedAccount = mock(FIN_FinancialAccount.class);
    private final FIN_BankStatementLine routedLine = mock(FIN_BankStatementLine.class);
    private final AtomicReference<ReconciliationConversionSupport.ConversionPaymentContext>
        seenContext = new AtomicReference<>();
    private final AtomicReference<ReconciliationConversionSupport.ExplicitConversion>
        seenConversion = new AtomicReference<>();

    private JSONArray oneInvoice() throws Exception {
      return new JSONArray().put(new JSONObject().put("invoiceId", "INV-1")
          .put("scheduleId", "SCH-1"));
    }

    /**
     * Runs {@code payInvoicesFromBody} with both destinations stubbed to a sentinel, and asserts
     * which one ran. {@code parse} is the real one, evaluated before the static mock opens: inside
     * it, the class's private helpers are intercepted too, so {@code thenCallRealMethod} would not
     * run the real parsing.
     */
    private NeoResponse route(JSONObject body, JSONArray invoiceSpecs, List<String> operationIds,
        boolean expectConversion) throws Exception {
      ReconciliationConversionSupport.ExplicitConversion parsed =
          ReconciliationConversionSupport.parse(body);
      try (MockedStatic<ReconciliationConversionSupport> conversion =
          mockStatic(ReconciliationConversionSupport.class);
          MockedStatic<ReconciliationFlowSupport> flow =
              mockStatic(ReconciliationFlowSupport.class)) {
        conversion.when(() -> ReconciliationConversionSupport.parse(body)).thenReturn(parsed);
        conversion.when(() -> ReconciliationConversionSupport.payInvoices(any(), any()))
            .thenAnswer(inv -> {
              seenContext.set(inv.getArgument(0));
              seenConversion.set(inv.getArgument(1));
              return conversionPath;
            });
        flow.when(() -> ReconciliationFlowSupport.createInvoicePayments(any(), any(), any(),
            any(), any(), any(), anyBoolean())).thenReturn(defaultPath);

        NeoResponse response = ReconciliationWriteoffSupport.payInvoicesFromBody(routedAccount,
            routedLine, invoiceSpecs, body, operationIds, BigDecimal.ZERO);

        if (expectConversion) {
          flow.verify(() -> ReconciliationFlowSupport.createInvoicePayments(any(), any(), any(),
              any(), any(), any(), anyBoolean()), never());
        } else {
          conversion.verify(() -> ReconciliationConversionSupport.payInvoices(any(), any()),
              never());
        }
        return response;
      }
    }

    @Test
    @DisplayName("a body with conversion fields is paid by ReconciliationConversionSupport")
    void conversionFieldsRouteToTheConversionSupport() throws Exception {
      JSONArray invoiceSpecs = oneInvoice();
      List<String> operationIds = new ArrayList<>();
      JSONObject body = new JSONObject().put("actualPayment", 40.91)
          .put("convertedAmount", "27.87").put("paymentMethodId", "PM-1");

      NeoResponse response = route(body, invoiceSpecs, operationIds, true);

      assertSame(conversionPath, response);
      ReconciliationConversionSupport.ConversionPaymentContext ctx = seenContext.get();
      assertSame(routedAccount, ctx.account());
      assertSame(routedLine, ctx.line());
      assertSame(invoiceSpecs, ctx.invoiceSpecs());
      assertSame(operationIds, ctx.operationIds(), "new transactions join the caller's list");
      assertEquals("PM-1", ctx.paymentMethodId());
      assertFalse(ctx.writeoffDifference());
      assertEquals("40.91", seenConversion.get().actualPayment());
      assertNull(seenConversion.get().conversionRate());
      assertEquals("27.87", seenConversion.get().convertedAmount());
    }

    @Test
    @DisplayName("one conversion field is enough; writeoffDifference is forwarded to be refused")
    void oneFieldIsEnoughAndTheWriteoffFlagIsForwarded() throws Exception {
      JSONObject body = new JSONObject().put("conversionRate", "0.68")
          .put("writeoffDifference", true);

      NeoResponse response = route(body, oneInvoice(), new ArrayList<>(), true);

      assertSame(conversionPath, response);
      assertTrue(seenContext.get().writeoffDifference());
      assertNull(seenContext.get().paymentMethodId());
      assertEquals("0.68", seenConversion.get().conversionRate());
    }

    @Test
    @DisplayName("a body without conversion fields keeps the default path, arguments unchanged")
    void noConversionFieldKeepsTheDefaultPath() throws Exception {
      JSONArray invoiceSpecs = oneInvoice();
      List<String> operationIds = new ArrayList<>(List.of("T1"));
      JSONObject body = new JSONObject().put("paymentMethodId", "PM-1")
          .put("writeoffDifference", false);
      BigDecimal tolerance = new BigDecimal("0.01");
      assertNull(ReconciliationConversionSupport.parse(body), "precondition: no conversion field");

      try (MockedStatic<ReconciliationConversionSupport> conversion =
          mockStatic(ReconciliationConversionSupport.class);
          MockedStatic<ReconciliationFlowSupport> flow =
              mockStatic(ReconciliationFlowSupport.class)) {
        flow.when(() -> ReconciliationFlowSupport.createInvoicePayments(any(), any(), any(),
            any(), any(), any(), anyBoolean())).thenReturn(defaultPath);

        NeoResponse response = ReconciliationWriteoffSupport.payInvoicesFromBody(routedAccount,
            routedLine, invoiceSpecs, body, operationIds, tolerance);

        assertSame(defaultPath, response);
        flow.verify(() -> ReconciliationFlowSupport.createInvoicePayments(routedAccount,
            routedLine, invoiceSpecs, operationIds, tolerance, "PM-1", false));
        conversion.verify(() -> ReconciliationConversionSupport.payInvoices(any(), any()),
            never());
      }
    }

    /**
     * The same routing with the conversion class NOT mocked: its own first rule (no conversion
     * together with existing transactions) answers, which only that path can produce.
     */
    @Test
    @DisplayName("unmocked: the conversion path's own refusal is what comes back")
    void unmockedConversionPathAnswersItsOwnRefusal() throws Exception {
      JSONObject body = new JSONObject().put("actualPayment", "40.91");
      List<String> operationIds = new ArrayList<>(List.of("T1"));

      try (MockedStatic<ReconciliationFlowSupport> flow =
          mockStatic(ReconciliationFlowSupport.class)) {
        NeoResponse response = ReconciliationWriteoffSupport.payInvoicesFromBody(routedAccount,
            routedLine, oneInvoice(), body, operationIds, BigDecimal.ZERO);

        assertNotNull(response);
        assertEquals(400, response.getHttpStatus());
        assertEquals(ReconciliationConversionSupport.MSG_NOT_COMBINABLE,
            response.getBody().getJSONObject("error").getString("message"));
        flow.verifyNoInteractions();
        assertEquals(List.of("T1"), operationIds);
      }
    }

    @Test
    @DisplayName("blank or JSON-null conversion fields count as absent → default path")
    void blankOrNullFieldsKeepTheDefaultPath() throws Exception {
      JSONObject body = new JSONObject().put("actualPayment", "  ")
          .put("conversionRate", JSONObject.NULL).put("convertedAmount", "");

      NeoResponse response = route(body, oneInvoice(), new ArrayList<>(), false);

      assertSame(defaultPath, response);
    }

    /**
     * Runs {@code payInvoicesFromBody} with NO selected invoice ({@code invoices} empty, or absent
     * when {@code emptyArray} is false). The conversion class is not mocked, so the real
     * {@code parse} decides; the default path and the payment seam are, to prove neither runs.
     */
    private NeoResponse withoutInvoices(JSONObject body, boolean emptyArray,
        List<String> operationIds) throws Exception {
      try (MockedStatic<ReconciliationFlowSupport> flow =
          mockStatic(ReconciliationFlowSupport.class);
          MockedStatic<ReconciliationPaymentService> payments =
              mockStatic(ReconciliationPaymentService.class)) {
        NeoResponse response = ReconciliationWriteoffSupport.payInvoicesFromBody(routedAccount,
            routedLine, emptyArray ? new JSONArray() : null, body, operationIds, BigDecimal.ZERO);
        flow.verifyNoInteractions();
        payments.verifyNoInteractions();
        return response;
      }
    }

    /**
     * The conversion fields only describe the invoice leg: sent with no invoice (an agent's
     * {@code operationIds + convertedAmount}) they are refused instead of silently ignored, which
     * would answer 201 having booked nothing of what was asked.
     */
    @ParameterizedTest(name = "{0} with invoices {1}")
    @CsvSource({
        "actualPayment,   empty",
        "actualPayment,   absent",
        "conversionRate,  empty",
        "conversionRate,  absent",
        "convertedAmount, empty",
        "convertedAmount, absent" })
    @DisplayName("conversion fields with no selected invoice → 400 not combinable, nothing runs")
    void conversionFieldsWithoutInvoicesAreRefused(String field, String invoices)
        throws Exception {
      JSONObject body = new JSONObject().put(field, "27.87");
      List<String> operationIds = new ArrayList<>(List.of("T1"));

      NeoResponse response = withoutInvoices(body, "empty".equals(invoices), operationIds);

      assertNotNull(response);
      assertEquals(400, response.getHttpStatus());
      assertEquals("Conversion fields cannot be combined with existing transactions or a "
          + "write-off", response.getBody().getJSONObject("error").getString("message"));
      assertEquals(List.of("T1"), operationIds, "the caller's operations are left untouched");
    }

    @ParameterizedTest(name = "invoices {0}")
    @ValueSource(strings = { "empty", "absent" })
    @DisplayName("no selected invoice and no conversion field is still a no-op")
    void noInvoiceAndNoConversionFieldIsANoOp(String invoices) throws Exception {
      JSONObject body = new JSONObject().put("paymentMethodId", "PM-1")
          .put("writeoffDifference", false)
          // Blank and JSON-null conversion fields count as absent, so they do not refuse either.
          .put("actualPayment", "  ").put("convertedAmount", JSONObject.NULL);
      List<String> operationIds = new ArrayList<>(List.of("T1"));

      NeoResponse response = withoutInvoices(body, "empty".equals(invoices), operationIds);

      assertNull(response);
      assertEquals(List.of("T1"), operationIds);
    }
  }
}
