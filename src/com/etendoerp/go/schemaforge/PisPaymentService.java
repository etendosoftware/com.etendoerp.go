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

import java.math.BigDecimal;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.advpaymentmngt.dao.AdvPaymentMngtDao;
import org.openbravo.advpaymentmngt.process.FIN_AddPayment;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BankAccount;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.payment.removal.util.PaymentRemovalUtil;
import com.etendoerp.psd2.bank.integration.data.PisPayment;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationConstants;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationPISUtils;
import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUtils;
import com.etendoerp.psd2.bank.integration.utils.PISPaymentDao;
import com.etendoerp.psd2.bank.integration.utils.PISTransactionUtils;

/**
 * PIS (Payment Initiation Service — bank transfer via Salt Edge) actions and helpers.
 *
 * <p>Split out of {@link PaymentRegistrationService} to keep that class under the authorized
 * method-count limit — this is purely an organizational split, not a behavioral change: status
 * polling, cancellation, template listing, supplier IBAN listing, and the PIS branch of the
 * advanced two-step payment registration flow. Reuses {@link PaymentRegistrationService}'s shared
 * response-envelope helpers and error messages (package-visible on that class for this reason).
 */
final class PisPaymentService {

  private static final Logger log = LogManager.getLogger(PisPaymentService.class);

  private static final String FIELD_PIS_PAYMENT_ID = "pisPaymentId";
  private static final String KEY_PIS_PAYMENT_URL = "pisPaymentUrl";
  private static final String KEY_PIS_STATUS = "pisStatus";
  private static final String PIS_STATUS_REQUESTED = "requested";
  // The PSD2_PIS_PAYMENT.status ref-list has no "cancelled" value; "failed" marks an undone attempt.
  private static final String PIS_STATUS_UNDONE = "failed";

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

  private PisPaymentService() {
  }

  // ─── PIS: bank-transfer status polling + supplier IBAN selection ───────────

  /**
   * Returns the current Salt Edge status of a PIS payment by its LOCAL {@code PSD2_PIS_PAYMENT}
   * id (the one returned in {@code pisPaymentId} by {@link #applyOverpaymentAndInitiatePis}), so
   * the SPA can poll it while the SCA widget / bank confirmation is pending.
   * Body: {@code {pisPaymentId}}.
   */
  static NeoResponse handlePisPaymentStatus(NeoContext context) {
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
        PIS_STATUS_UNDONE, "settled");
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
   * Actively fetches {@code pisPayment}'s current status from Salt Edge, persists it, and creates
   * the {@code FIN_Finacc_Transaction} when the status is {@code executed}. Mirrors the reconcile
   * sequence {@code PisPaymentCallback#doGet} runs on browser-return, but done here because Etendo
   * Go's own SPA callback bypasses that servlet and the async webhook can't reach a local server.
   * Composes only public PSD2 APIs (no PSD2 logic is duplicated or altered).
   */
  private static void refreshPisStatusFromSaltEdge(PisPayment pisPayment) {
    String apiKey = BankIntegrationUtils.getPsd2ApiKey(OBContext.getOBContext().getCurrentClient());
    BankIntegrationPISUtils.PISPaymentStatus status = BankIntegrationPISUtils.showPayment(apiKey,
        pisPayment.getSaltedgePayment());
    PISPaymentDao.updateStatusWithAttributes(pisPayment, status);
    OBDal.getInstance().save(pisPayment);
    OBDal.getInstance().flush();
    if (BankIntegrationConstants.PIS_STATUS_EXECUTED.equals(status.getStatus())) {
      PISTransactionUtils.createFinancialTransactionIfEligible(pisPayment);
    }
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
        || StringUtils.equalsAnyIgnoreCase(status, PIS_STATUS_REQUESTED, "initiated", "authorizing",
            PIS_STATUS_UNDONE);
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

  /**
   * Registers any over-payment as generated credit, PROCESSES the payment so it lands in status
   * {@code PPM} ("Payment Made") — applied to the invoice but with NO {@code FIN_Finacc_Transaction}
   * — and then initiates the real bank transfer through the PSD2 PIS integration.
   *
   * <p>Processing does not create a financial transaction here because the account's transfer
   * payment method had its {@code Automatic Deposit/Withdrawn} flags cleared when the account was
   * connected to PSD2 from Etendo Go (see {@code FinancialAccountPsd2Handler} §2b). The bank
   * transaction is created only once Salt Edge confirms execution, by the PSD2 module's own
   * {@code PisPaymentCallback} → {@code PISTransactionUtils} (idempotent). This mirrors Classic,
   * whose "Generate Bank Payment" process requires {@code Status='PPM'} and
   * {@code PSD2_HasFinTransaction=0}.
   *
   * <p>The refund sub-flow is skipped entirely for PIS: creating a refund payment now would
   * refund money that has not moved yet. Any leftover is kept as generated credit instead,
   * logging a warning when the caller had actually requested a refund.
   */
  static NeoResponse applyOverpaymentAndInitiatePis(FIN_Payment payment,
      AdvPaymentMngtDao dao, Organization org, BigDecimal funds, BigDecimal invoiceApplied,
      JSONObject pisInput, String overpaymentAction) throws Exception {
    VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(OBContext.getOBContext());
    RequestContext.get().setVariableSecureApp(vars);
    DalConnectionProvider conn = new DalConnectionProvider(false);

    BigDecimal leftover = funds.subtract(invoiceApplied);
    boolean overpaid = leftover.compareTo(BigDecimal.ZERO) > 0;
    if (overpaid) {
      FIN_PaymentScheduleDetail creditPsd = dao.getNewPaymentScheduleDetail(org, leftover);
      dao.getNewPaymentDetail(payment, creditPsd, leftover, BigDecimal.ZERO, false, null);
      OBDal.getInstance().flush();
      if ("refund".equalsIgnoreCase(overpaymentAction)) {
        log.warn("PIS payment {}: refund requested but skipped (funds have not moved yet — the "
            + "payment awaits Salt Edge execution); leftover {} kept as generated credit instead.",
            payment.getDocumentNo(), leftover);
      }
    }

    // Process → status PPM. Transaction creation is deferred to the PIS callback (§2b clears the
    // method's Automatic flags, so processing does not create a FIN_Finacc_Transaction here).
    PaymentRegistrationService.failOnError(
        FIN_AddPayment.processPayment(vars, conn, "P", payment, ""));
    OBDal.getInstance().flush();

    if (hasFinTransaction(payment)) {
      log.warn("PIS payment {}: a financial transaction was created at processing time — the "
          + "account's transfer method still has Automatic Deposit/Withdrawn enabled. The PIS "
          + "callback will skip creating another one (idempotent), but reconnect the account from "
          + "Etendo Go so §2b clears those flags and the transaction is deferred until execution.",
          payment.getDocumentNo());
    }

    HttpServletRequest request = RequestContext.get() != null
        ? RequestContext.get().getRequest() : null;
    BankIntegrationPISUtils.PISCreatePaymentResult result =
        PisPaymentBridge.initiatePisPayment(payment, pisInput, request);

    PisPayment pisPayment = PISPaymentDao.findBySaltedgePaymentId(result.getPaymentId());
    String localPisPaymentId = pisPayment != null ? pisPayment.getId() : null;
    return builtPisPaymentResponse(payment, result.getPaymentUrl(), localPisPaymentId);
  }

  /** True when a {@code FIN_Finacc_Transaction} already exists for the payment. */
  private static boolean hasFinTransaction(FIN_Payment payment) {
    Long count = OBDal.getInstance().getSession()
        .createQuery("select count(t) from FIN_Finacc_Transaction t where t.finPayment.id = :id",
            Long.class)
        .setParameter("id", payment.getId())
        .uniqueResult();
    return count != null && count > 0;
  }

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
   * real PIS bank transfer: the account must be PSD2-connected, the payment method must be a
   * bank transfer, and the invoice currency must be one PIS supports (EUR → SEPA, GBP → FPS).
   */
  static void validatePisEligibility(FIN_FinancialAccount account,
      FIN_PaymentMethod paymentMethod, Invoice invoice) {
    if (!BankIntegrationConstants.FA_CONNECTION_STATUS_CONNECTED
        .equals(account.getPSD2ConnectionStatus())) {
      throw new OBException("The selected financial account is not connected to PSD2. "
          + "Connect it to your bank via Salt Edge before paying by bank transfer.");
    }
    if (!isTransferMethod(paymentMethod)) {
      throw new OBException("Bank transfer (PIS) payment requires a transfer payment method.");
    }
    String isoCode = invoice.getCurrency() != null ? invoice.getCurrency().getISOCode() : null;
    if (!"EUR".equalsIgnoreCase(isoCode) && !"GBP".equalsIgnoreCase(isoCode)) {
      throw new OBException("Bank transfer (PIS) payments are only supported for EUR and "
          + "GBP invoices.");
    }
  }

  /**
   * Judgment call: {@code FIN_PaymentMethod} has no explicit "is transfer" flag in this model,
   * so eligibility is inferred from the method's display name containing "transfer" (EN) or
   * "transferencia" (ES), case-insensitive. Fragile against renamed/localized payment methods —
   * revisit if Etendo core ever exposes a proper type flag. Same heuristic is used in
   * {@link FinancialAccountPsd2Handler}.
   */
  private static boolean isTransferMethod(FIN_PaymentMethod method) {
    if (method == null || method.getName() == null) {
      return false;
    }
    return StringUtils.containsIgnoreCase(method.getName(), "transfer")
        || StringUtils.containsIgnoreCase(method.getName(), "transferencia");
  }

  /**
   * Sibling of {@code PaymentRegistrationService#builtPaymentResponse}: same base payload plus
   * the PIS fields the SPA needs to open the Salt Edge SCA widget and poll for the transfer's
   * execution status.
   */
  private static NeoResponse builtPisPaymentResponse(FIN_Payment payment, String pisPaymentUrl,
      String localPisPaymentId) throws Exception {
    JSONObject data = PaymentRegistrationService.basePaymentData(payment);
    data.put(KEY_PIS_PAYMENT_URL, pisPaymentUrl);
    data.put(FIELD_PIS_PAYMENT_ID, localPisPaymentId);
    data.put(KEY_PIS_STATUS, PIS_STATUS_REQUESTED);
    return PaymentRegistrationService.wrapCreatedData(data);
  }

  /**
   * True when {@code payment} has an associated {@code PSD2_PIS_PAYMENT} row, meaning it was
   * initiated through the Salt Edge PIS flow rather than just recorded as a manual bank
   * transfer — surfaced in the SPA's payment history as a "Realizado vía PSD2" badge.
   * {@code PisPayment} is a plain DAL entity, so no PSD2-module method is needed for this.
   */
  static boolean hasLinkedPisPayment(FIN_Payment p) {
    OBCriteria<PisPayment> criteria = OBDal.getInstance().createCriteria(PisPayment.class);
    criteria.add(Restrictions.eq(PisPayment.PROPERTY_PAYMENT, p));
    criteria.setMaxResults(1);
    return criteria.uniqueResult() != null;
  }
}
