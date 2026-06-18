/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;

/**
 * Static helpers for the {@code autoMatch} and {@code applySuggestions} actions of
 * {@link ReconciliationHandler}. Extracted to keep the handler class under the Sonar
 * method-count threshold.
 */
final class AutoMatchSupport {

  private static final Logger log = LogManager.getLogger(AutoMatchSupport.class);

  private static final String KEY_ID = "id";
  private static final String KEY_DATE = "date";
  private static final String KEY_AMOUNT = "amount";
  private static final String KEY_IS_NEW = "isNew";

  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  private AutoMatchSupport() {
  }

  // ---------------------------------------------------------------------------
  // Group builders
  // ---------------------------------------------------------------------------

  static JSONObject buildStandardGroup(FIN_BankStatementLine line,
      FIN_FinaccTransaction txn, String matchLevel) throws JSONException {
    JSONObject group = new JSONObject();
    group.put("groupKey", line.getId() + "-" + txn.getId());
    group.put("statementLine", lineToJson(line));
    JSONArray ops = new JSONArray();
    ops.put(txnToJson(txn));
    group.put("operations", ops);
    group.put("origin", "standard");
    group.put("matchLevel", StringUtils.defaultIfBlank(matchLevel, ""));
    BigDecimal lineAmt = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    BigDecimal opAmt = nullSafe(txn.getDepositAmount()).subtract(nullSafe(txn.getPaymentAmount()));
    group.put("difference", lineAmt.subtract(opAmt));
    group.put(KEY_IS_NEW, false);
    return group;
  }

  static JSONObject buildRuleGroup(FIN_BankStatementLine line,
      MatchRuleEngine.Rule rule, List<MatchRuleEngine.Rule> alternatives) throws JSONException {
    JSONObject group = new JSONObject();
    group.put("groupKey", line.getId() + "-rule-" + rule.id);
    group.put("statementLine", lineToJson(line));
    boolean isNew = StringUtils.isNotBlank(rule.glItemId);
    group.put(KEY_IS_NEW, isNew);
    group.put("origin", "rule");
    group.put("ruleName", rule.name);
    BigDecimal lineAmt = nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount()));
    group.put("difference", BigDecimal.ZERO);

    JSONArray ops = new JSONArray();
    if (isNew) {
      JSONObject proposedOp = new JSONObject();
      proposedOp.put(KEY_ID, "new");
      proposedOp.put("glItemId", StringUtils.defaultIfBlank(rule.glItemId, ""));
      proposedOp.put("bpartnerId", StringUtils.defaultIfBlank(rule.bpartnerId, ""));
      proposedOp.put(KEY_AMOUNT, lineAmt);
      proposedOp.put(KEY_IS_NEW, true);
      ops.put(proposedOp);
    }
    group.put("operations", ops);

    JSONArray altArray = new JSONArray();
    for (MatchRuleEngine.Rule alt : alternatives) {
      JSONObject altJson = new JSONObject();
      altJson.put(KEY_ID, alt.id);
      altJson.put("name", alt.name);
      altJson.put("priority", alt.priority);
      altArray.put(altJson);
    }
    group.put("alternatives", altArray);

    if (isNew) {
      JSONObject cp = new JSONObject();
      cp.put("ruleId", rule.id);
      cp.put("glItemId", StringUtils.defaultIfBlank(rule.glItemId, ""));
      cp.put("bpartnerId", StringUtils.defaultIfBlank(rule.bpartnerId, ""));
      cp.put("transactionTypeId", StringUtils.defaultIfBlank(rule.transactionTypeId, ""));
      cp.put(KEY_AMOUNT, lineAmt);
      group.put("createPayment", cp);
    }
    return group;
  }

  static JSONObject lineToJson(FIN_BankStatementLine line) throws JSONException {
    JSONObject j = new JSONObject();
    j.put(KEY_ID, line.getId());
    j.put(KEY_DATE, formatDate(line.getTransactionDate() != null
        ? new Timestamp(line.getTransactionDate().getTime()) : null));
    j.put("description", StringUtils.trimToEmpty(line.getDescription()));
    j.put("referenceNo", StringUtils.trimToEmpty(line.getReferenceNo()));
    j.put(KEY_AMOUNT, nullSafe(line.getCramount()).subtract(nullSafe(line.getDramount())));
    return j;
  }

  static JSONObject txnToJson(FIN_FinaccTransaction txn) throws JSONException {
    JSONObject j = new JSONObject();
    j.put(KEY_ID, txn.getId());
    j.put(KEY_DATE, formatDate(txn.getTransactionDate() != null
        ? new Timestamp(txn.getTransactionDate().getTime()) : null));
    j.put("documentNo",
        txn.getFinPayment() != null ? StringUtils.trimToEmpty(txn.getFinPayment().getDocumentNo()) : "");
    j.put(KEY_AMOUNT, nullSafe(txn.getDepositAmount()).subtract(nullSafe(txn.getPaymentAmount())));
    j.put(KEY_IS_NEW, false);
    return j;
  }

  // ---------------------------------------------------------------------------
  // Payment helpers
  // ---------------------------------------------------------------------------

  static void failOnError(OBError result) {
    if (result != null && "Error".equalsIgnoreCase(result.getType())) {
      throw new OBException(result.getMessage());
    }
  }

  /**
   * Resolves the default payment method for the account and direction (in/out).
   * Returns {@code null} if none is configured.
   */
  static FIN_PaymentMethod resolveDefaultPaymentMethod(FIN_FinancialAccount account,
      boolean isReceipt) {
    List<FinAccPaymentMethod> methods = account.getFinancialMgmtFinAccPaymentMethodList();
    if (methods == null || methods.isEmpty()) {
      return null;
    }
    for (FinAccPaymentMethod fapm : methods) {
      if (isReceipt && Boolean.TRUE.equals(fapm.isPayinAllow())
          || !isReceipt && Boolean.TRUE.equals(fapm.isPayoutAllow())) {
        return fapm.getPaymentMethod();
      }
    }
    return methods.get(0).getPaymentMethod();
  }

  /** Increments {@code ETGO_MATCH_RULE.matchcount} for the given rule id. Best-effort (non-fatal). */
  static void incrementMatchCount(String ruleId) {
    try {
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE etgo_match_rule SET matchcount = matchcount + 1 WHERE etgo_match_rule_id = ?")) { // NOSONAR java:S2077
        ps.setString(1, ruleId);
        ps.executeUpdate();
      }
    } catch (Exception e) {
      log.warn("Could not increment matchCount for rule {}", ruleId, e);
    }
  }

  // ---------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------

  private static String formatDate(Timestamp ts) {
    return ts == null ? "" : ISO_UTC.format(Instant.ofEpochMilli(ts.getTime()));
  }

  static BigDecimal nullSafe(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
