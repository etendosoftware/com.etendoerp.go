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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

/**
 * {@link PaymentWriteoffLimitGuard} (ETP-5558): the server-side write-off limit of the invoice
 * payment registration. The rule mirrors the SPA's {@code writeoffState} (writeoffMath.js) —
 * null or 0 means no limit, a difference EQUAL to the limit is allowed — so these cases are the
 * same ones {@code writeoffState.test.js} pins on the other side.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Payment write-off limit guard")
class PaymentWriteoffLimitGuardTest {

  private static final String SCHEDULE_ID = "sched-1";
  private static final String WRITEOFF = "writeoffDifference";
  private static final String CREDIT_SOURCES = "creditSources";

  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBMessageUtils> messagesMock;
  private OBDal dal;
  private FIN_FinancialAccount account;

  @BeforeEach
  void setUp() {
    dal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(dal);
    messagesMock = mockStatic(OBMessageUtils.class);
    messagesMock.when(
        () -> OBMessageUtils.messageBD(PaymentWriteoffLimitGuard.MSG_WRITEOFF_LIMIT_EXCEEDED))
        .thenReturn("diff=@difference@ limit=@limit@");

    // One pending installment detail of 121.00 — the live repro.
    stubPending("121.00");

    account = mock(FIN_FinancialAccount.class);
    when(account.getWriteofflimit()).thenReturn(new BigDecimal("5.00"));
  }

  @AfterEach
  void tearDown() {
    messagesMock.close();
    obDalMock.close();
  }

  @SuppressWarnings("unchecked")
  private void stubPending(String amount) {
    FIN_PaymentScheduleDetail psd = mock(FIN_PaymentScheduleDetail.class);
    when(psd.getAmount()).thenReturn(new BigDecimal(amount));
    OBCriteria<FIN_PaymentScheduleDetail> crit = mock(OBCriteria.class);
    when(dal.createCriteria(FIN_PaymentScheduleDetail.class)).thenReturn(crit);
    when(crit.add(any(Criterion.class))).thenReturn(crit);
    when(crit.addOrderBy(anyString(), anyBoolean())).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.singletonList(psd));
  }

  private NeoResponse check(JSONObject body, String cash) {
    return PaymentWriteoffLimitGuard.check(body, account, new BigDecimal(cash), SCHEDULE_ID);
  }

  @Test
  @DisplayName("Refuses a shortfall above the limit and names both amounts")
  void refusesAboveLimit() throws Exception {
    NeoResponse response = check(new JSONObject().put(WRITEOFF, true), "100.00");

    assertNotNull(response);
    assertEquals(400, response.getHttpStatus());
    assertEquals("diff=21.00 limit=5.00",
        response.getBody().getJSONObject("error").getString("message"));
  }

  @Test
  @DisplayName("Allows a shortfall exactly equal to the limit")
  void allowsEqualToLimit() throws Exception {
    assertNull(check(new JSONObject().put(WRITEOFF, true), "116.00"));
  }

  /**
   * The SPA rounds to cents before comparing ({@code usePaymentBalance}: {@code round2} of the
   * invoice total and of the funds), so a sub-cent residue never reaches the limit check there. A
   * cash of 115.996 is 116.00 to the SPA, a 5.00 shortfall, allowed at a 5.00 limit; compared
   * exactly it is 5.004 and was refused — the two sides must not disagree on the same numbers.
   */
  @Test
  @DisplayName("Rounds the funds to cents like the SPA: 5.004 at a 5.00 limit is allowed")
  void roundsFundsToCentsLikeTheSpa() throws Exception {
    assertNull(check(new JSONObject().put(WRITEOFF, true), "115.996"));
  }

  @Test
  @DisplayName("Rounds the pending total to cents like the SPA")
  void roundsPendingToCentsLikeTheSpa() throws Exception {
    stubPending("121.004");
    assertNull(check(new JSONObject().put(WRITEOFF, true), "116.00"));
  }

  @Test
  @DisplayName("Reports the rounded difference in the refusal")
  void reportsTheRoundedDifference() throws Exception {
    NeoResponse response = check(new JSONObject().put(WRITEOFF, true), "115.994");

    assertNotNull(response, "115.994 is 115.99 to the SPA: a 5.01 shortfall over a 5.00 limit");
    assertEquals("diff=5.01 limit=5.00",
        response.getBody().getJSONObject("error").getString("message"));
  }

  @Test
  @DisplayName("Does nothing when the write-off is not requested, even far above the limit")
  void ignoresWhenFlagOff() throws Exception {
    assertNull(check(new JSONObject().put(WRITEOFF, false), "1.00"));
    assertNull(check(new JSONObject(), "1.00"));
    verify(dal, never()).createCriteria(FIN_PaymentScheduleDetail.class);
  }

  @Test
  @DisplayName("A null limit means no limit")
  void nullLimitIsUnlimited() throws Exception {
    when(account.getWriteofflimit()).thenReturn(null);
    assertNull(check(new JSONObject().put(WRITEOFF, true), "1.00"));
  }

  @Test
  @DisplayName("A zero limit means no limit, NOT forbid everything (same as the SPA)")
  void zeroLimitIsUnlimited() throws Exception {
    when(account.getWriteofflimit()).thenReturn(BigDecimal.ZERO);
    assertNull(check(new JSONObject().put(WRITEOFF, true), "1.00"));
  }

  @Test
  @DisplayName("Editing a draft is not limited: that path never writes off")
  void editPathIsNotLimited() throws Exception {
    JSONObject body = new JSONObject().put(WRITEOFF, true).put("paymentId", "draft-1");
    assertNull(check(body, "1.00"));
  }

  @Test
  @DisplayName("Selected credit sources count as funds, like the SPA's balance")
  void creditSourcesReduceTheShortfall() throws Exception {
    JSONArray sources = new JSONArray()
        .put(new JSONObject().put("kind", "credit").put("paymentId", "pay-1").put("use", "10.00"))
        .put(new JSONObject().put("kind", "abono").put("psdId", "psd-1").put("use", "8.00"));
    // 121 − (100 + 18) = 3 ≤ 5
    assertNull(check(new JSONObject().put(WRITEOFF, true).put(CREDIT_SOURCES, sources), "100.00"));
  }

  @Test
  @DisplayName("A credit source that consume() would skip does not count as funds")
  void skippedCreditSourcesDoNotCount() throws Exception {
    JSONArray sources = new JSONArray()
        .put(new JSONObject().put("kind", "credit").put("use", "18.00"))
        .put(new JSONObject().put("kind", "abono").put("psdId", "psd-1").put("use", "-5"));
    NeoResponse response = check(
        new JSONObject().put(WRITEOFF, true).put(CREDIT_SOURCES, sources), "100.00");

    assertNotNull(response, "blank-id and non-positive sources fund nothing: 21.00 > 5.00");
  }
}
