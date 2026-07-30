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

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;

import com.etendoerp.psd2.bank.integration.actionhandler.GenerateBankPayment;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationPISUtils;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;

/**
 * Bridges a NEO-registered {@link FIN_Payment} to the PSD2 module's PIS (Payment Initiation
 * Service) flow, so paying a purchase invoice by real bank transfer from the Etendo Go "Add
 * payment" modal goes through the exact same Salt Edge logic as Classic's "Generate Bank Payment"
 * process ({@code com.etendoerp.psd2.bank.integration.actionhandler.GenerateBankPayment}).
 *
 * <p>Rather than re-implementing the ~300 lines of payload building / SEPA-FPS-DOMESTIC validation
 * / persistence, this reuses {@code GenerateBankPayment.processPayment(...)} directly — that method
 * is public and takes an explicit {@code returnToUrl} so the popup can return to the Etendo Go SPA
 * callback instead of the Classic result page. No PSD2 logic is copied or changed here.
 *
 * <p>The {@code params} JSON built here uses the exact same keys that {@code GenerateBankPayment}
 * normalizes: {@code template}, {@code end_to_end_id}, {@code creditor_name}, {@code amount},
 * {@code currency_id}, {@code description}, {@code creditor_iban}. Template selection is
 * currency-driven — {@code SEPA} for EUR, {@code FPS} for GBP — any other currency must already
 * have been rejected by the caller's eligibility check (see
 * {@code PaymentRegistrationService#validatePisEligibility}) before this class is reached.
 */
final class PisPaymentBridge {

  // Mirrors the private constants of GenerateBankPayment (not public there, so redeclared here).
  private static final String KEY_TEMPLATE = "template";
  private static final String KEY_AMOUNT = "amount";
  private static final String KEY_CURRENCY_ID = "currency_id";
  private static final String KEY_DESCRIPTION = "description";
  private static final String TEMPLATE_SEPA = "SEPA";
  private static final String TEMPLATE_FPS = "FPS";
  private static final String CURRENCY_GBP = "GBP";

  /**
   * App-shell SPA route the Salt Edge popup is returned to after SCA. It is a tiny page that just
   * closes the popup (the "Add payment" modal already polls the payment status), so the user lands
   * back on the invoice instead of the Classic-styled shared bank-auth result page. Mirrors the
   * AIS connect flow's {@code /financial-account/bank-connection-callback}.
   */
  private static final String PIS_CALLBACK_PATH = "/financial-account/pis-callback";

  private PisPaymentBridge() {
  }

  /**
   * Builds the PIS payment request for {@code payment} and delegates to
   * {@link GenerateBankPayment#processPayment}, returning the Salt Edge payment id + SCA widget
   * URL.
   *
   * <p>The payment-derived fields (end-to-end id, creditor name, amount, currency, description)
   * are set here; the template and creditor account identifiers come from {@code pisInput} — the
   * user's choices in the SPA (mirroring the classic "Generate Bank Payment" dialog). When the
   * template is missing it defaults from the currency (EUR→SEPA, GBP→FPS) for backwards
   * compatibility. {@code GenerateBankPayment} validates which creditor fields are required per
   * template.
   *
   * @param payment  the draft {@link FIN_Payment} to submit for a real bank transfer
   * @param pisInput template + creditor fields, keyed by the orchestrator's parameter names
   *                 ({@code template}, {@code creditor_iban}, {@code creditor_bban},
   *                 {@code creditor_account_number}, {@code creditor_sort_code}); may be {@code null}
   * @param request  the current HTTP request (used by GenerateBankPayment to resolve the client's
   *                 real IP for PSD2 fraud-detection headers); may be {@code null}
   * @return the Salt Edge create-payment result (payment id + payment URL)
   * @throws JSONException if building the request payload fails
   */
  static BankIntegrationPISUtils.PISCreatePaymentResult initiatePisPayment(FIN_Payment payment,
      JSONObject pisInput, HttpServletRequest request) throws JSONException {
    String apiKey = BankIntegrationUtils.getPsd2ApiKey(OBContext.getOBContext().getCurrentClient());

    JSONObject params = pisInput != null ? pisInput : new JSONObject();
    if (!params.has(KEY_TEMPLATE) || StringUtils.isBlank(params.optString(KEY_TEMPLATE, null))) {
      params.put(KEY_TEMPLATE, templateForCurrency(payment.getCurrency().getISOCode()));
    }
    params.put(BankIntegrationConstants.END_TO_END_ID, payment.getDocumentNo());
    params.put(BankIntegrationConstants.CREDITOR_NAME, payment.getBusinessPartner().getName());
    params.put(KEY_AMOUNT, payment.getAmount().toString());
    params.put(KEY_CURRENCY_ID, payment.getCurrency().getId());
    params.put(KEY_DESCRIPTION, descriptionFor(payment));

    // Route the post-SCA browser redirect back to the Etendo Go SPA (auto-closing popup) rather
    // than the shared Classic bank-auth page. When the request origin can't be resolved this is
    // null and GenerateBankPayment falls back to its default /pisPaymentCallback (Classic behaviour).
    String returnUrl = resolveGoReturnUrl(request);

    // Reuse Classic's exact payload/validation/persistence via its now-public processPayment.
    return new GenerateBankPayment().processPayment(payment, params, apiKey, request, returnUrl);
  }

  private static String descriptionFor(FIN_Payment payment) {
    return payment.getDescription() != null ? payment.getDescription() : payment.getDocumentNo();
  }

  /**
   * Builds the app-shell return URL from the request {@code Origin} header (falling back to
   * {@code Referer}), mirroring {@code FinancialAccountBankConnectionHandler#resolveAppShellOrigin}. Returns
   * {@code null} when the origin can't be resolved, so the caller keeps the default callback.
   */
  private static String resolveGoReturnUrl(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String origin = StringUtils.trimToNull(request.getHeader("Origin"));
    if (origin == null) {
      origin = FinancialAccountBankConnectionSupport.originFromReferer(request.getHeader("Referer"));
    }
    if (origin == null) {
      return null;
    }
    return StringUtils.removeEnd(origin, "/") + PIS_CALLBACK_PATH;
  }

  /** SEPA for EUR, FPS for GBP. Fallback only — the SPA normally sends the template explicitly. */
  private static String templateForCurrency(String isoCode) {
    return CURRENCY_GBP.equalsIgnoreCase(isoCode) ? TEMPLATE_FPS : TEMPLATE_SEPA;
  }
}
