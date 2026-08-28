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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * Stateless helpers extracted from {@link FinancialAccountTransactionsHandler} to keep that class
 * below Sonar's per-class method threshold: Classic-parity presentation labels, request-body
 * parsing utilities, and the funds-transfer conversion-rate rule. All methods are pure (or only
 * touch the DAL) and carry no per-request state, so they live here as static utilities.
 */
final class FinancialAccountTransactionsSupport {

  private static final Logger log = LogManager.getLogger(FinancialAccountTransactionsSupport.class);

  /** Reused error message (appears across every mutating action). */
  static final String MSG_BODY_REQUIRED = "Request body is required";

  /**
   * Shared wrapper for the mutating POST actions (create / update / process / reactivate / delete /
   * transfer / create-payment). Centralizes the admin-mode + try/catch/finally boilerplate that was
   * previously duplicated in each handler: validates the body is present, runs the action under
   * admin mode, and on failure rolls back and maps the exception to a 400 (business) / 500
   * (unexpected) response. {@code logContext} labels the log lines; {@code userError} is the safe
   * message returned to the client on an unexpected error (never leaks internal details). The action
   * is a {@link Callable} so it may propagate the checked {@code JSONException} the DAL/JSON layer
   * throws; it should close over the already-validated body.
   */
  static NeoResponse runMutation(JSONObject body, String logContext, String userError,
      Callable<NeoResponse> action) {
    if (body == null) return NeoResponse.error(400, MSG_BODY_REQUIRED);
    try {
      OBContext.setAdminMode(true);
      return action.call();
    } catch (OBException e) {
      log.warn("{} business error: {}", logContext, e.getMessage());
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(400, e.getMessage());
    } catch (Exception e) {
      log.error("Error during {}", logContext, e);
      OBDal.getInstance().rollbackAndClose();
      return NeoResponse.error(500, userError);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Resolves the currency for a mutation: the explicit {@code currencyId} from the body when given,
   * otherwise the supplied fallback. Returns {@code null} when a given id does not resolve, so the
   * caller can surface a 400.
   */
  static Currency resolveCurrency(JSONObject body, Currency fallback) {
    String currencyId = body.optString("currencyId", null);
    return StringUtils.isBlank(currencyId)
        ? fallback
        : OBDal.getInstance().get(Currency.class, currencyId);
  }

  /** trxType code → Classic "Transaction Type" label (unknown codes pass through). */
  private static final Map<String, String> TRX_TYPE_CLASSIC = Map.of(
      "BPD", "BP Deposit",
      "BPW", "BP Withdrawal");

  /** payment status code → long Classic label (dual of movementStatusConfig). */
  private static final Map<String, String> STATUS_CLASSIC = Map.of(
      "RPAP", "Awaiting Payment",
      "RPAE", "Awaiting Execution",
      "RPVOID", "Voided",
      "RPR", "Payment Received",
      "PPM", "Payment Made",
      "PWNC", "Withdrawn not Cleared",
      "RDNC", "Deposited not Cleared",
      "RPPC", "Payment Cleared");

  private static final DateTimeFormatter DMY_DASH =
      DateTimeFormatter.ofPattern("dd-MM-yyyy").withZone(ZoneOffset.UTC);

  private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private FinancialAccountTransactionsSupport() {
  }

  /** trxType code → Classic "Transaction Type" label (unknown codes pass through). */
  static String trxTypeClassicLabel(String code) {
    return StringUtils.isBlank(code) ? "" : TRX_TYPE_CLASSIC.getOrDefault(code, code);
  }

  /** payment status code → long Classic label (dual of movementStatusConfig). */
  static String statusClassicLabel(String code) {
    return StringUtils.isBlank(code) ? "" : STATUS_CLASSIC.getOrDefault(code, code);
  }

  /** Synthetic "Payment" column: {@code docNo - dd-MM-yyyy - contact - |amount|}. */
  static String buildPaymentLabel(String docNo, Timestamp date, String contact, BigDecimal amount) {
    String dateStr = date == null ? "" : DMY_DASH.format(Instant.ofEpochMilli(date.getTime()));
    String amt = (amount == null ? BigDecimal.ZERO : amount.abs())
        .stripTrailingZeros().toPlainString();
    StringBuilder sb = new StringBuilder();
    for (String part : new String[] { docNo, dateStr, contact, amt }) {
      if (StringUtils.isNotBlank(part)) {
        if (sb.length() > 0) sb.append(" - ");
        sb.append(part);
      }
    }
    return sb.toString();
  }

  static String formatDmy(java.sql.Date d) {
    return d == null ? "" : DMY.format(d.toLocalDate());
  }

  /** Signed days from today to the due date: negative = overdue, 0 = due today. */
  static int daysUntil(java.sql.Date dueDate, LocalDate today) {
    return dueDate == null ? 0 : (int) ChronoUnit.DAYS.between(today, dueDate.toLocalDate());
  }

  /** SQL fragment restricting bpartners by role (customer / vendor / any). */
  static String bpartnerRoleFilter(String role) {
    if ("customer".equals(role)) {
      return " AND iscustomer='Y'";
    }
    if ("vendor".equals(role)) {
      return " AND isvendor='Y'";
    }
    return "";
  }

  /**
   * Conversion rate to pass to {@code FundsTransferActionHandler.createTransfer}: {@code 1} for a
   * same-currency transfer; the user-provided rate when currencies differ; or {@code null} to let
   * Classic resolve the system rate.
   */
  static BigDecimal resolveConversionRate(FIN_FinancialAccount source,
      FIN_FinancialAccount dest, BigDecimal provided) {
    if (source.getCurrency().getId().equalsIgnoreCase(dest.getCurrency().getId())) {
      return BigDecimal.ONE;
    }
    return provided;
  }

  static BigDecimal optBigDecimal(JSONObject body, String key) {
    if (!body.has(key) || body.isNull(key)) return null;
    try {
      return new BigDecimal(body.getString(key));
    } catch (Exception e) {
      try {
        return BigDecimal.valueOf(body.getDouble(key));
      } catch (Exception ex) {
        return null;
      }
    }
  }

  /**
   * Parses a civil (date-only) value like {@code "2026-07-16"} or {@code "2026-07-16T00:00:00Z"}
   * into a {@link Date} at the SERVER's local start-of-day. Using local midnight (instead of UTC
   * midnight) keeps the stored calendar day correct: UTC midnight would roll back a day when the
   * JDBC driver writes a {@code date} column in a negative-offset timezone (e.g. UTC-3).
   */
  static Date parseLocalDate(String iso, Date fallback) {
    if (StringUtils.isBlank(iso)) return fallback;
    try {
      String datePart = iso.length() >= 10 ? iso.substring(0, 10) : iso;
      return Date.from(LocalDate.parse(datePart).atStartOfDay(ZoneId.systemDefault()).toInstant());
    } catch (Exception e) {
      return fallback;
    }
  }

  static <T extends BaseOBObject> void attachOptional(String id, Class<T> entityClass,
      Consumer<T> setter) {
    if (StringUtils.isBlank(id)) return;
    T ref = OBDal.getInstance().get(entityClass, id);
    if (ref != null) setter.accept(ref);
  }

  /**
   * Sets an optional FK reference from a request-body key, supporting edits: when the key is
   * ABSENT the reference is left unchanged; when it is present-but-blank the reference is CLEARED
   * (set to {@code null}); otherwise the referenced entity is loaded and set.
   */
  static <T extends BaseOBObject> void setOptionalRef(JSONObject body, String key,
      Class<T> entityClass, Consumer<T> setter) {
    if (!body.has(key)) return;
    String id = body.optString(key, null);
    setter.accept(StringUtils.isBlank(id) ? null : OBDal.getInstance().get(entityClass, id));
  }

  /** The bank-statement line currently matched to {@code trx}, or {@code null} when it is unmatched. */
  static FIN_BankStatementLine linkedBankStatementLine(FIN_FinaccTransaction trx) {
    List<FIN_BankStatementLine> lines = trx.getFINBankStatementLineList();
    return lines.isEmpty() ? null : lines.get(0);
  }
}
