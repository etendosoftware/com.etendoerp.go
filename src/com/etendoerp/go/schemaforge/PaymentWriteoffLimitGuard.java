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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;

/**
 * Server-side write-off limit for the invoice payment registration
 * ({@code doRegisterPaymentAdvanced}, ETP-5558).
 *
 * <p>Until ETP-5558 the {@code FIN_Financial_Account.Writeofflimit} was enforced only by the SPA
 * ({@code writeoffMath.js → writeoffState}), which disables the toggle over the limit. Every other
 * caller — the MCP {@code neo_action registerPayment}, a direct REST call — could write off any
 * amount: {@code writeoffDifference:true} went straight to Core. The toggle is a convenience, the
 * boundary is here.
 *
 * <p>Same rule as the SPA and as the reconciliation path
 * ({@link ReconciliationWriteoffSupport}): an unset or zero limit means "no limit", and the
 * difference is rounded to cents like the SPA's balance and then compared against the limit — no
 * currency conversion, exactly like {@code writeoffState}, which compares the invoice-currency
 * shortfall with the raw limit.
 */
final class PaymentWriteoffLimitGuard {

  static final String MSG_WRITEOFF_LIMIT_EXCEEDED = "ETGO_WriteoffLimitExceeded";

  private PaymentWriteoffLimitGuard() {
  }

  /**
   * Rejects a {@code writeoffDifference} request whose shortfall exceeds the account's write-off
   * limit. Returns {@code null} when the request may proceed.
   *
   * <p>Runs BEFORE anything is written (the draft payment, consumed credit, a PIS transfer), so a
   * refusal leaves the invoice untouched. The shortfall is computed exactly as Core will write it
   * off in {@code FIN_AddPayment.updatePaymentDetail}: the pending installment details minus what
   * the payment funds (cash plus the selected credit sources).
   *
   * @param body         the register request; reads {@code writeoffDifference}, {@code paymentId},
   *                     {@code creditSources}
   * @param account      the financial account the payment goes to
   * @param cash         {@code actual_payment}, in the invoice currency
   * @param scheduleId   the invoice installment being paid; its pending details are only read when
   *                     a positive limit actually has to be checked
   */
  static NeoResponse check(JSONObject body, FIN_FinancialAccount account, BigDecimal cash,
      String scheduleId) {
    // The edit path ignores the flag (see applyInvoiceInstallment), so there is nothing to limit.
    boolean editing = StringUtils.isNotBlank(body.optString("paymentId", null));
    if (!body.optBoolean("writeoffDifference", false) || editing) {
      return null;
    }
    BigDecimal limit = account.getWriteofflimit();
    if (limit == null || limit.signum() <= 0) {
      return null;
    }
    // Both sides rounded to cents BEFORE subtracting, exactly as the SPA does (usePaymentBalance:
    // applied = round2(total), funds = round2(amount + usedCredit)); otherwise a sub-cent residue
    // (115.996 paid → 5.004 short) passes the SPA's check at a 5.00 limit and fails this one.
    // HALF_UP matches Math.round on these positive amounts.
    BigDecimal funds = cents(cash.add(
        PaymentCreditConsumer.requestedFunding(body.optJSONArray("creditSources"))));
    BigDecimal difference =
        cents(pendingTotal(PaymentRegistrationService.findPendingPSDs(scheduleId)))
            .subtract(funds);
    if (difference.compareTo(limit) <= 0) {
      return null;
    }
    String message = OBMessageUtils.messageBD(MSG_WRITEOFF_LIMIT_EXCEEDED)
        .replace("@difference@", difference.toPlainString())
        .replace("@limit@", limit.toPlainString());
    return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, message);
  }

  // Scale hardcoded to 2 to mirror the SPA, which rounds these amounts to cents. Wrong for
  // currencies with 0 or 3 decimals (JPY, KWD). The fix is the currency's standard precision on
  // both sides, SPA and server, changed together - never on one side alone.
  private static BigDecimal cents(BigDecimal amount) {
    return amount.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal pendingTotal(List<FIN_PaymentScheduleDetail> psds) {
    BigDecimal total = BigDecimal.ZERO;
    for (FIN_PaymentScheduleDetail psd : psds) {
      if (psd.getAmount() != null) {
        total = total.add(psd.getAmount());
      }
    }
    return total;
  }
}
