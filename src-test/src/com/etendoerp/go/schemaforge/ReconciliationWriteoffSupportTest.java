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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Write-off limit — tenant isolation")
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
}
