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


import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BankAccount;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;

import com.etendoerp.payment.removal.util.PaymentRemovalUtil;
import com.etendoerp.psd2.bank.integration.data.PisPayment;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationPISUtils;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.PISPaymentDao;

/**
 * PIS (Payment Initiation Service — bank transfer via Salt Edge) actions and helpers.
 *
 * <p>Split out of {@link PaymentRegistrationService} to keep that class under the authorized
 * method-count limit: status polling, cancellation, template listing and supplier IBAN listing.
 * Reuses {@link PaymentRegistrationService}'s shared response-envelope helpers and error messages
 * (package-visible on that class for this reason). Starting a transfer lives in
 * {@link PisDeferredPaymentService}.
 *
 * <p>Public only for {@code handlePisPaymentStatus}, which the payment window's handler routes from
 * another package so a retry started there can be polled like one started from the invoice modal.
 */
public final class PisPaymentService {

  private static final Logger log = LogManager.getLogger(PisPaymentService.class);

  private static final String FIELD_PIS_PAYMENT_ID = "pisPaymentId";
  /**
   * The complete {@code PSD2_PIS_PAYMENT.status} vocabulary — the AD ref-list "PIS Payment Status"
   * ({@code AD_REFERENCE_ID = D5483E7D91134499B42BBD963BC2F9CC}, Bank Integration module) has
   * exactly these 8 values and no others.
   * <p>
   * They are declared here as one block because the gates below used to hardcode a partial,
   * hand-copied subset each: {@code initiated_info_required} was missing from the cancellable set,
   * so a transfer sitting in that perfectly normal pre-authorization state could not be undone
   * ("already in progress and can no longer be undone from here") and left an orphan payment
   * behind — see ETP-4895. {@code BankIntegrationConstants} only defines the three final ones.
   */
  private static final String PIS_STATUS_REQUESTED = "requested";
  private static final String PIS_STATUS_INITIATED = "initiated";
  private static final String PIS_STATUS_INITIATED_INFO_REQUIRED = "initiated_info_required";
  private static final String PIS_STATUS_AUTHORIZING = "authorizing";
  // The ref-list has no "cancelled" value; "failed" marks an undone attempt.
  private static final String PIS_STATUS_UNDONE = BankIntegrationConstants.PIS_STATUS_FAILED;

  /**
   * Statuses reached before the user has authorized anything at the bank, so no money has moved
   * and the attempt can still be safely undone. Deliberately excludes {@code authorized} and
   * everything after it.
   */
  private static final String[] PIS_STATUSES_PRE_AUTHORIZATION = {
      PIS_STATUS_REQUESTED, PIS_STATUS_INITIATED, PIS_STATUS_INITIATED_INFO_REQUIRED,
      PIS_STATUS_AUTHORIZING, PIS_STATUS_UNDONE
  };

  // PIS request fields sent by the SPA (mirror the classic "Generate Bank Payment" dialog).
  private static final String FIELD_PIS_TEMPLATE = "pisTemplate";
  private static final String FIELD_PIS_CREDITOR_IBAN = "pisCreditorIban";
  private static final String FIELD_PIS_CREDITOR_BBAN = "pisCreditorBban";
  private static final String FIELD_PIS_CREDITOR_ACCOUNT_NUMBER = "pisCreditorAccountNumber";
  private static final String FIELD_PIS_CREDITOR_SORT_CODE = "pisCreditorSortCode";

  /**
   * AD reference "Template List for Bank Payments" (defined by the PSD2 module). Its list values
   * (SEPA / DOMESTIC / FPS) drive which creditor fields the SPA shows and validates.
   */
  private static final String PIS_TEMPLATE_REFERENCE_ID = "C2ED369FE83548AD9AAA47186502F1BF";

  /**
   * Currencies a PIS transfer can be instructed in, keyed by the currency of the DEBTOR bank
   * account — not the invoice's (ETP-5084). A transfer always leaves the bank in the account's own
   * currency, so that is what decides both eligibility and the payment template
   * (EUR → SEPA, USD → DOMESTIC, GBP → FPS; see {@code PisPaymentBridge.templateForCurrency}).
   * An invoice in any other currency is payable as long as a conversion rate is available — the
   * amount is converted to the account currency before it is sent to the bank.
   */
  private static final Set<String> PIS_ELIGIBLE_ACCOUNT_CURRENCIES = Set.of("EUR", "USD", "GBP");

  private PisPaymentService() {
  }

  // ─── PIS: bank-transfer status polling + supplier IBAN selection ───────────

  /**
   * Returns the current Salt Edge status of a PIS payment by its LOCAL {@code PSD2_PIS_PAYMENT}
   * id (the one returned in {@code pisPaymentId} by
   * {@link PisDeferredPaymentService#initiateDeferredPis}), so the SPA can poll it while the SCA
   * widget / bank confirmation is pending.
   * Body: {@code {pisPaymentId}}.
   *
   * @param context the NEO request; the transfer is read from its body, so the record it is posted
   *     to is irrelevant — which is what lets the payment window route it without an invoice
   * @return the stored status, refreshed from Salt Edge first unless it is already terminal; 400
   *     without a {@code pisPaymentId}, 404 when it names no transfer
   */
  public static NeoResponse handlePisPaymentStatus(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String pisPaymentId = body != null ? body.optString(FIELD_PIS_PAYMENT_ID, null) : null;
    if (StringUtils.isBlank(pisPaymentId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "pisPaymentId is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        PisPayment pisPayment = OBDal.getInstance().get(PisPayment.class, pisPaymentId);
        if (pisPayment == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "PIS payment not found");
        }
        // Actively refresh from Salt Edge instead of trusting a possibly-stale local value.
        // The async POST webhook that would otherwise keep this in sync can't reach a
        // non-publicly-reachable (e.g. local dev) server, and Etendo Go's own return_to routes
        // to its own SPA callback (see PisPaymentBridge), bypassing PisPaymentCallback's
        // synchronous refresh-on-browser-return. Best-effort: a Salt Edge/network failure here
        // just leaves the stored status as-is for the next poll tick to retry.
        if (!isTerminalPisStatus(pisPayment.getStatus())) {
          refreshPisStatusBestEffort(pisPayment, pisPaymentId);
        }
        JSONObject data = new JSONObject();
        data.put(PaymentRegistrationService.KEY_STATUS, pisPayment.getStatus());
        return new NeoResponse(200, data);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error fetching PIS payment status for {}: {}", pisPaymentId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to fetch PIS payment status");
    }
  }

  /** True once a PIS payment can no longer change — no point re-querying Salt Edge for it. */
  private static boolean isTerminalPisStatus(String status) {
    return StringUtils.equalsAnyIgnoreCase(status, BankIntegrationConstants.PIS_STATUS_EXECUTED,
        BankIntegrationConstants.PIS_STATUS_FAILED, BankIntegrationConstants.PIS_STATUS_SETTLED);
  }

  /**
   * Refreshes {@code pisPayment} from Salt Edge, swallowing and logging any failure — a network
   * hiccup here must never fail the whole status-polling request. Extracted from
   * {@link #handlePisPaymentStatus} to keep that method's try-block nesting shallow.
   */
  private static void refreshPisStatusBestEffort(PisPayment pisPayment, String pisPaymentId) {
    try {
      refreshPisStatusFromSaltEdge(pisPayment);
    } catch (Exception e) {
      log.warn("Could not refresh PIS payment {} status from Salt Edge: {}", pisPaymentId,
          e.getMessage());
    }
  }

  /**
   * Actively fetches {@code pisPayment}'s current status from Salt Edge, persists it, and brings
   * the Etendo Go side in line with it. Mirrors the reconcile sequence
   * {@code PisPaymentCallback#doGet} runs on browser-return, composing only public PSD2 APIs (no
   * PSD2 logic is duplicated or altered).
   *
   * <p>The Etendo Go side is delegated to {@link PisDeferredPaymentService#reconcile}, which is
   * what actually creates the {@code FIN_Payment} — the step Classic's own callback has no way to
   * perform, since PSD2 is the shared module and must not know about Etendo Go.
   *
   * <p>Package-private rather than private so {@link PisReturnCallbackServlet} can reuse it
   * verbatim for the browser-return path: ONE consult-and-reconcile sequence, called from either
   * the SPA's poll (user still watching) or the browser's own redirect back from the bank (which
   * works even with the app-shell tab already closed).
   */
  static void refreshPisStatusFromSaltEdge(PisPayment pisPayment) {
    String apiKey = BankIntegrationUtils.getPsd2ApiKey(OBContext.getOBContext().getCurrentClient());
    BankIntegrationPISUtils.PISPaymentStatus status = BankIntegrationPISUtils.showPayment(apiKey,
        pisPayment.getSaltedgePayment());
    PISPaymentDao.updateStatusWithAttributes(pisPayment, status);
    OBDal.getInstance().save(pisPayment);
    OBDal.getInstance().flush();
    PisDeferredPaymentService.reconcile(pisPayment);
  }

  /**
   * Undoes a PIS payment that was created (status {@code PPM}) but never authorized/executed at
   * the bank: reactivates and DELETES the {@link FIN_Payment} through the official
   * {@code com.etendoerp.payment.removal} flow (which also refreshes the invoice's paid status),
   * and marks the {@code PSD2_PIS_PAYMENT} row as {@code cancelled} for audit. Body:
   * {@code {pisPaymentId}}.
   *
   * <p>Refuses only when the transfer is already past the point of no return — the Salt Edge status
   * is beyond {@code authorizing} (authorized / processing / executed / settled) — so we never undo a
   * payment whose money has actually moved. A locally auto-created {@code FIN_Finacc_Transaction}
   * (account still had Automatic Deposit/Withdrawn on) does NOT block the undo: the reactivation
   * mode {@code "R"} removes it too.
   */
  static NeoResponse handleCancelPisPayment(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String pisPaymentId = body != null ? body.optString(FIELD_PIS_PAYMENT_ID, null) : null;
    if (StringUtils.isBlank(pisPaymentId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "pisPaymentId is required");
    }
    try {
      OBContext.setAdminMode(true);
      try {
        PisPayment pisPayment = OBDal.getInstance().get(PisPayment.class, pisPaymentId);
        if (pisPayment == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "PIS payment not found");
        }
        if (!isCancellablePisStatus(pisPayment.getStatus())) {
          throw new OBException("The bank transfer is already in progress and can no longer be "
              + "undone from here.");
        }
        FIN_Payment payment = pisPayment.getPayment();
        // Detach + mark the PIS record as undone (status "failed") first, so removing the payment
        // does not hit the PSD2_PIS_PAYMENT → FIN_Payment foreign key, while keeping the row as an
        // audit trail. ("cancelled" is not a valid value in the status ref-list.)
        pisPayment.setStatus(PIS_STATUS_UNDONE);
        pisPayment.setPayment(null);
        OBDal.getInstance().save(pisPayment);
        OBDal.getInstance().flush();

        if (payment != null) {
          // "R" = "Reactivate and Delete Lines" (com.etendoerp.payment.removal reactivation mode):
          // reverts the payment AND its auto-created FIN_Finacc_Transaction, un-applying it from the
          // invoice; then delete the payment record entirely. Works whether the payment stayed in
          // PPM or a transaction was created (account still had Automatic Deposit/Withdrawn on).
          String paymentId = payment.getId();
          PaymentRemovalUtil.reactivate(paymentId, "R");
          FIN_Payment reactivated = OBDal.getInstance().get(FIN_Payment.class, paymentId);
          if (reactivated != null) {
            PaymentRemovalUtil.remove(reactivated);
          }
        }
        JSONObject data = new JSONObject();
        data.put("cancelled", true);
        return new NeoResponse(200, data);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (OBException e) {
      OBDal.getInstance().rollbackAndClose();
      log.warn("Cancel PIS payment {} failed: {}", pisPaymentId, e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      OBDal.getInstance().rollbackAndClose();
      log.error("Error cancelling PIS payment {}: {}", pisPaymentId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to cancel PIS payment");
    }
  }

  /**
   * True when a PIS payment can still be safely undone: it has not advanced past {@code authorizing}
   * (so no SCA-authorized / processing / executed / settled transfer is reversed). Blank/unknown is
   * treated as cancellable (freshly created).
   */
  private static boolean isCancellablePisStatus(String status) {
    return StringUtils.isBlank(status)
        || StringUtils.equalsAnyIgnoreCase(status, PIS_STATUSES_PRE_AUTHORIZATION);
  }

  /**
   * Lists the PIS payment templates (SEPA / DOMESTIC / FPS) from the AD reference
   * "Template List for Bank Payments", so the SPA offers the same choice as the classic
   * "Generate Bank Payment" dialog. Each item is {@code {value, label}} (label is the AD list
   * name, translated to the user's language when a translation exists), ordered by sequence.
   */
  static NeoResponse handlePisTemplates() {
    try {
      OBContext.setAdminMode(true);
      try {
        OBCriteria<org.openbravo.model.ad.domain.List> crit = OBDal.getInstance()
            .createCriteria(org.openbravo.model.ad.domain.List.class);
        crit.add(Restrictions.eq(org.openbravo.model.ad.domain.List.PROPERTY_REFERENCE + ".id",
            PIS_TEMPLATE_REFERENCE_ID));
        crit.add(Restrictions.eq(org.openbravo.model.ad.domain.List.PROPERTY_ACTIVE, Boolean.TRUE));
        crit.addOrderBy(org.openbravo.model.ad.domain.List.PROPERTY_SEQUENCENUMBER, true);
        String language = OBContext.getOBContext().getLanguage().getLanguage();

        JSONArray arr = new JSONArray();
        for (org.openbravo.model.ad.domain.List value : crit.list()) {
          JSONObject item = new JSONObject();
          item.put("value", value.getSearchKey());
          item.put("label", org.openbravo.erpCommon.utility.Utility.getListValueName(
              value.getReference().getName(), value.getSearchKey(), language));
          arr.put(item);
        }
        return PaymentRegistrationService.itemsResponse(arr);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error listing PIS payment templates: {}", e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to list PIS payment templates");
    }
  }

  /**
   * Lists the invoice's business partner's bank accounts that carry an IBAN, for the
   * "Destination IBAN" selector in the PIS payment flow. {@code C_BP_BankAccount} is not split
   * by AR/AP direction, so — unlike the sibling {@code invoiceXxx} listing actions routed the
   * same way — this action takes no direction flag.
   *
   * <p>Each item is {@code {id: <iban>, name, iban}} — the {@code id} is the IBAN itself (not the
   * bank-account record id) so the SPA can treat a picked account and a hand-typed IBAN uniformly.
   * {@code name} is the account name (falling back to bank name / account number) for the
   * "Name · IBAN" display. Judgment call: {@code C_BP_BankAccount} has no explicit "default" flag,
   * so the oldest account (by creation date) is marked {@code default: true} to preselect.
   */
  static NeoResponse handleListSupplierBankAccounts(NeoContext context) {
    String invoiceId = context.getRecordId();
    if (StringUtils.isBlank(invoiceId)) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          PaymentRegistrationService.MSG_INVOICE_ID_REQUIRED);
    }
    try {
      OBContext.setAdminMode(true);
      try {
        Invoice invoice = OBDal.getInstance().get(Invoice.class, invoiceId);
        if (invoice == null) {
          return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
              PaymentRegistrationService.MSG_INVOICE_NOT_FOUND);
        }
        if (invoice.getBusinessPartner() == null) {
          return PaymentRegistrationService.itemsResponse(new JSONArray());
        }
        OBCriteria<BankAccount> crit = OBDal.getInstance().createCriteria(BankAccount.class);
        crit.add(Restrictions.eq(BankAccount.PROPERTY_BUSINESSPARTNER,
            invoice.getBusinessPartner()));
        crit.addOrderBy(BankAccount.PROPERTY_CREATIONDATE, true);

        JSONArray arr = new JSONArray();
        boolean defaultAssigned = false;
        for (BankAccount ba : crit.list()) {
          if (StringUtils.isBlank(ba.getIBAN())) {
            continue;
          }
          JSONObject item = new JSONObject();
          item.put("id", ba.getIBAN());
          item.put("iban", ba.getIBAN());
          item.put("name", supplierAccountName(ba));
          if (!defaultAssigned) {
            item.put("default", true);
            defaultAssigned = true;
          }
          arr.put(item);
        }
        return PaymentRegistrationService.itemsResponse(arr);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error listing supplier bank accounts for invoice {}: {}", invoiceId,
          e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to list supplier bank accounts");
    }
  }

  /** Display name for a supplier bank account: its name, else bank name, else account number. */
  private static String supplierAccountName(BankAccount ba) {
    if (StringUtils.isNotBlank(ba.getName())) {
      return ba.getName();
    }
    if (StringUtils.isNotBlank(ba.getBankName())) {
      return ba.getBankName();
    }
    return StringUtils.defaultString(ba.getAccountNo());
  }

  // ─── ADVANCED: PIS branch of the two-step draft/confirm payment flow ───────
  // Initiation itself lives in PisDeferredPaymentService: it creates no payment.

  /**
   * Collects the template + creditor fields the SPA sends for a PIS payment, keyed by the
   * orchestrator's parameter names ({@code template}, {@code creditor_iban}, {@code creditor_bban},
   * {@code creditor_account_number}, {@code creditor_sort_code}). The template selection and which
   * of these are required are enforced downstream by the PSD2 orchestrator (SEPA→IBAN, FPS→sort
   * code + account number, DOMESTIC→any one identifier).
   */
  static JSONObject extractPisInput(JSONObject body) throws Exception {
    JSONObject input = new JSONObject();
    putIfPresent(input, "template", body.optString(FIELD_PIS_TEMPLATE, null));
    putIfPresent(input, BankIntegrationConstants.CREDITOR_IBAN,
        body.optString(FIELD_PIS_CREDITOR_IBAN, null));
    putIfPresent(input, BankIntegrationConstants.CREDITOR_BBAN,
        body.optString(FIELD_PIS_CREDITOR_BBAN, null));
    putIfPresent(input, BankIntegrationConstants.CREDITOR_ACCOUNT_NUMBER,
        body.optString(FIELD_PIS_CREDITOR_ACCOUNT_NUMBER, null));
    putIfPresent(input, BankIntegrationConstants.CREDITOR_SORT_CODE,
        body.optString(FIELD_PIS_CREDITOR_SORT_CODE, null));
    return input;
  }

  private static void putIfPresent(JSONObject target, String key, String value) throws Exception {
    if (StringUtils.isNotBlank(value)) {
      target.put(key, value);
    }
  }

  /**
   * Validates that {@code account}/{@code paymentMethod}/{@code invoice} are eligible for a
   * real PIS bank transfer: the account must have a bank connection, the payment method must be a
   * bank transfer, and the ACCOUNT currency must be one PIS supports.
   *
   * <p>The currency check deliberately keys off the debtor account, not the invoice (ETP-5084).
   * The transfer leaves the bank in the account's own currency, so that is the currency the bank
   * is instructed in and the one that selects the payment template. An invoice in a different
   * currency is perfectly payable: {@link PaymentRegistrationService} converts the amount with the
   * request's conversion rate before it reaches the bank — the very same rate the resulting
   * {@code FIN_Payment} is later booked at, so the instructed and booked amounts cannot diverge.
   */
  static void validatePisEligibility(FIN_FinancialAccount account,
      FIN_PaymentMethod paymentMethod, Invoice invoice) {
    if (!BankIntegrationConstants.FA_CONNECTION_STATUS_CONNECTED
        .equals(account.getPSD2ConnectionStatus())) {
      throw new OBException("The selected financial account has no bank connection. "
          + "Connect it to your bank via Salt Edge before paying by bank transfer.");
    }
    if (!isTransferMethod(paymentMethod)) {
      throw new OBException("Bank transfer (PIS) payment requires a transfer payment method.");
    }
    // Without it the amount could not be converted to the account currency, and instructing the
    // bank with an unconverted figure would move the wrong amount of money.
    if (invoice.getCurrency() == null) {
      throw new OBException("The invoice has no currency, so the transfer amount cannot be "
          + "converted to the bank account currency.");
    }
    String accountIso = account.getCurrency() != null ? account.getCurrency().getISOCode() : null;
    if (accountIso == null || PIS_ELIGIBLE_ACCOUNT_CURRENCIES.stream()
        .noneMatch(eligible -> eligible.equalsIgnoreCase(accountIso))) {
      throw new OBException("Bank transfer (PIS) payments require a bank account in EUR, USD or "
          + "GBP. The selected account is in " + (accountIso != null ? accountIso : "no currency")
          + ".");
    }
  }

  /**
   * Judgment call: {@code FIN_PaymentMethod} has no explicit "is transfer" flag in this model,
   * so eligibility is inferred from the method's display name containing "transfer" (EN) or
   * "transferencia" (ES), case-insensitive. Fragile against renamed/localized payment methods —
   * revisit if Etendo core ever exposes a proper type flag. Same heuristic is used in
   * {@link FinancialAccountBankConnectionHandler}.
   */
  private static boolean isTransferMethod(FIN_PaymentMethod method) {
    if (method == null || method.getName() == null) {
      return false;
    }
    return StringUtils.containsIgnoreCase(method.getName(), "transfer")
        || StringUtils.containsIgnoreCase(method.getName(), "transferencia");
  }

  /**
   * The {@code PSD2_PIS_PAYMENT} row behind {@code payment}, or null when the payment was not
   * initiated through the Salt Edge PIS flow (a manually-recorded bank transfer, say).
   * <p>
   * The SPA needs the row itself, not just its existence: retrying a rejected transfer acts on the
   * PIS attempt rather than on the payment. {@code PisPayment} is a plain DAL entity, so no
   * PSD2-module method is needed for this.
   */
  static PisPayment linkedPisPayment(FIN_Payment p) {
    OBCriteria<PisPayment> criteria = OBDal.getInstance().createCriteria(PisPayment.class);
    criteria.add(Restrictions.eq(PisPayment.PROPERTY_PAYMENT, p));
    criteria.setMaxResults(1);
    return (PisPayment) criteria.uniqueResult();
  }
}
