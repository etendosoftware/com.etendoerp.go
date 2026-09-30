/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.erpCommon.ad_forms.AcctServer;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.financial.ResetAccounting;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Category;
import org.openbravo.model.common.businesspartner.CategoryAccounts;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.plm.ProductAccounts;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.materialmgmt.transaction.InternalConsumption;
import org.openbravo.model.materialmgmt.transaction.InternalConsumptionLine;
import org.openbravo.model.materialmgmt.transaction.InventoryCount;
import org.openbravo.model.materialmgmt.transaction.InventoryCountLine;
import org.openbravo.model.materialmgmt.transaction.MaterialTransaction;
import org.openbravo.model.procurement.ReceiptInvoiceMatch;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoProcessService;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoMessageTranslator;

/**
 * Shared post/unpost core for Etendo GO. NOT a {@code NeoHandler} — it is a plain injectable bean
 * reused by every document-window handler (and the shared {@code DocumentActionHandler}). Posting
 * goes through the Etendo accounting engine ({@link AcctServer}); unposting (descontabilizar) goes
 * through {@link ResetAccounting}.
 *
 * <p>Posting always runs with {@code force = false} so an already-posted document is never
 * force-reposted.</p>
 *
 */
public class DocumentPostingService {

  private static final Logger log = LogManager.getLogger(DocumentPostingService.class);

  /** AD_MESSAGE searchkey for the BP + BP Group enrichment (both resolvable). */
  private static final String MSG_INVALID_ACCOUNT_BP_AND_GROUP = "ETGO_InvalidAccountBpAndGroup";
  /** AD_MESSAGE searchkey for the BP-only enrichment fallback (no BP Group). */
  private static final String MSG_INVALID_ACCOUNT_BP_ONLY = "ETGO_InvalidAccountBpOnly";
  /** AD_MESSAGE searchkey for naming the specific missing {@code C_BP_Group_Acct} column(s). */
  private static final String MSG_MISSING_BP_GROUP_ACCOUNTS = "ETGO_InvalidAccountMissingBpGroupAccounts";
  /**
   * AD_MESSAGE searchkey for naming the specific missing {@code M_Product_Acct} column(s), on a
   * Matched Purchase Invoice only (ETP-5175).
   */
  private static final String MSG_MISSING_PRODUCT_ACCOUNTS = "ETGO_InvalidAccountMissingProductAccounts";

  /** {@code AD_LANGUAGE} code that selects the Spanish label pair below; anything else falls back to English. */
  private static final String LANGUAGE_ES_ES = "es_ES";

  /**
   * {@code AcctServer#tableName} value for Goods Movements (ETP-5436) — matches the literal
   * {@code acct.tableName = "M_Movement"} assignment in {@code AcctServer.get()}'s {@code case
   * 323} branch (core {@code AcctServer.java}), which is the only place this string is defined.
   */
  private static final String TABLE_M_MOVEMENT = "M_Movement";

  /**
   * DB table name (not {@code AD_Table_ID} — resolved from the table's own record, see
   * {@link #post(String, String, ConnectionProvider)}) for Physical Inventory, one of the two
   * document types the cost-calculated pre-check applies to (ETP-5360).
   */
  private static final String TABLE_M_INVENTORY = "M_Inventory";

  /**
   * DB table name for Internal Consumption, the second document type the cost-calculated
   * pre-check applies to (ETP-5445). Same resolution rule as {@link #TABLE_M_INVENTORY}.
   */
  private static final String TABLE_M_INTERNAL_CONSUMPTION = "M_Internal_Consumption";

  /**
   * {@code AD_MESSAGE.VALUE} for the plain, no-params "cost not yet calculated" text (ETP-5360) —
   * confirmed against {@code AD_MESSAGE_ID = B6CDB7D04FD249579A48D26C0ED48F45} in core Etendo's
   * {@code AD_MESSAGE.xml} ("Cost has not yet been calculated for all products in the document.").
   * Resolved via {@link OBMessageUtils#messageBD}, which already follows {@code OBContext}'s
   * language like the rest of this file (see {@link #MSG_INVALID_ACCOUNT_BASE} and
   * {@link #errorMessageOf}).
   *
   * <p>Reused as-is for {@code STATUS_DocumentDisabled} on a Goods Movement ({@link
   * #TABLE_M_MOVEMENT}, ETP-5436) instead of a second, hand-written message: the wording is
   * table-agnostic ("...in the document", not Inventory-specific), and {@code
   * DocMovement#getDocumentConfirmation} sets 'D' for the same underlying condition this message
   * already describes — no {@code MaterialTransaction} on the document's lines has a calculated
   * cost yet. Reusing a real, already-translated core {@code AD_MESSAGE} beats a hardcoded EN/ES
   * pair maintained only in Java.</p>
   */
  private static final String MSG_NOT_CALCULATED_COST = "NotCalculatedCost";

  /**
   * The {@code C_BP_Group_Acct} columns relevant to this app's document types (ETP-5175) — a
   * curated subset, not every nullable column on that table. {@code NotInvoicedReceivables_Acct}
   * (getter {@code getNonInvoicedReceivables()}) was deliberately dropped from this list
   * (ETP-5175 follow-up): an exhaustive search of {@code AcctServer.java}, every {@code Doc*.java}
   * posting handler and every {@code ad_forms} {@code .xsql} found zero references to that
   * column in any posting engine — only onboarding-provisioning code touches it. It can never
   * legitimately be the cause of an Invalid-Account posting failure, so including it here only
   * produced a false-positive "missing account" report (the column the BP Group's Non-Invoiced
   * Receipts DocMatchInv actually reads was fine; the unrelated, unused Non-Invoiced Receivables
   * column happened to also be null and was wrongly surfaced to the user). Each entry pairs the
   * account's stable wire code (sent in {@link #MESSAGE_PARAMS_MISSING_BP_GROUP_ACCOUNTS}, so the
   * SPA can label it in its own locale) and its EN/ES label (backend prose only) with the
   * {@link CategoryAccounts} getter that reads it.
   * {@code getVendorLiability()} is a DB {@code NOT NULL} column — its null-check structurally
   * never fires, kept for completeness.
   */
  private static final List<BpGroupAccountColumn> BP_GROUP_ACCOUNT_COLUMNS = List.of(
      new BpGroupAccountColumn("nonInvoicedReceipts", "Non-Invoiced Receipts", "Recibos no facturados",
          CategoryAccounts::getNonInvoicedReceipts),
      new BpGroupAccountColumn("customerReceivablesNo", "Customer Receivables No.", "Recibos de clientes",
          CategoryAccounts::getCustomerReceivablesNo),
      new BpGroupAccountColumn("vendorLiability", "Vendor Liability", "Pasivo del proveedor",
          CategoryAccounts::getVendorLiability),
      new BpGroupAccountColumn("customerPrepayment", "Customer Prepayment", "Prepago del cliente",
          CategoryAccounts::getCustomerPrepayment),
      new BpGroupAccountColumn("vendorPrepayment", "Vendor Prepayment", "Pagos por adelantado del proveedor",
          CategoryAccounts::getVendorPrepayment));

  /**
   * One curated {@code C_BP_Group_Acct} column: its stable wire code, its EN/ES labels plus its
   * {@link CategoryAccounts} getter. {@link #label(String)} picks the Spanish label when {@code lang} is
   * {@value #LANGUAGE_ES_ES} (same exact-match precedent as
   * {@code NotPostedDocumentsHandler#getTranslatedName}), English otherwise.
   */
  private record BpGroupAccountColumn(String code, String labelEn, String labelEs,
      Function<CategoryAccounts, AccountingCombination> getter) {
    String label(String lang) {
      return LANGUAGE_ES_ES.equals(lang) ? labelEs : labelEn;
    }
  }

  /**
   * The {@code M_Product_Acct} columns relevant to a Matched Purchase Invoice failure (ETP-5175):
   * {@code DocMatchInv#createFact} resolves these two accounts independently from the BP-Group
   * ones above, keyed by the invoice line's PRODUCT rather than the Business Partner's group.
   * {@code getProductExpense()} is a DB {@code NOT NULL} column — like {@code getVendorLiability()}
   * above, its null-check structurally never fires on an existing row; kept for completeness.
   */
  private static final List<ProductAccountColumn> PRODUCT_ACCOUNT_COLUMNS = List.of(
      new ProductAccountColumn("productExpense", "Product Expense", "Gastos del producto",
          ProductAccounts::getProductExpense),
      new ProductAccountColumn("invoicePriceVariance", "Invoice Price Variance", "Desviación Pr. Factura",
          ProductAccounts::getInvoicePriceVariance));

  /**
   * One curated {@code M_Product_Acct} column: its stable wire code, its EN/ES labels plus its
   * {@link ProductAccounts} getter. Same language-selection rule as {@link BpGroupAccountColumn#label(String)}.
   */
  private record ProductAccountColumn(String code, String labelEn, String labelEs,
      Function<ProductAccounts, AccountingCombination> getter) {
    String label(String lang) {
      return LANGUAGE_ES_ES.equals(lang) ? labelEs : labelEn;
    }
  }

  /** {@code messageParams} entry: the failing Business Partner's name (ETP-5175). */
  static final String MESSAGE_PARAMS_BP_NAME = "bpName";
  /** {@code messageParams} entry: the failing Business Partner's Contact Category (BP Group) name. */
  static final String MESSAGE_PARAMS_BP_GROUP = "bpGroup";
  /** {@code messageParams} entry: stable codes of the unconfigured Contact Category accounts. */
  static final String MESSAGE_PARAMS_MISSING_BP_GROUP_ACCOUNTS = "missingBpGroupAccounts";
  /** {@code messageParams} entry: stable codes of the unconfigured Product accounts. */
  static final String MESSAGE_PARAMS_MISSING_PRODUCT_ACCOUNTS = "missingProductAccounts";

  /**
   * Result of a post/unpost attempt. {@code messageKeys} (ETP-5360) are the AD_MESSAGE search keys
   * behind a failure {@code message}, captured before translation so a client can map the failure
   * by identity; never {@code null}, empty when there are none. {@code messageParams} (ETP-5175)
   * are the values those keys interpolate, so a client can render the whole sentence in its own
   * locale instead of trusting the backend prose; never {@code null}, empty when there are none.
   * Values are {@code String} or {@code List<String>}.
   */
  public record PostResult(boolean ok, String message, List<String> messageKeys,
      Map<String, Object> messageParams) {
    /**
     * Canonical constructor. Normalizes {@code messageKeys} and {@code messageParams} to
     * immutable copies, and {@code null} to empty, so callers never have to null-check them.
     *
     * @param ok
     *     {@code true} when the post/unpost succeeded.
     * @param message
     *     the user-facing outcome message, already translated to the session language.
     * @param messageKeys
     *     the AD_MESSAGE search keys behind {@code message}; may be {@code null}.
     * @param messageParams
     *     the values interpolated by {@code messageKeys}; may be {@code null}.
     */
    public PostResult {
      messageKeys = messageKeys == null ? List.of() : List.copyOf(messageKeys);
      messageParams = messageParams == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(messageParams));
    }

    /**
     * Result whose message was built from AD_MESSAGE tokens but carries no interpolated values.
     *
     * @param ok
     *     {@code true} when the post/unpost succeeded.
     * @param message
     *     the user-facing outcome message.
     * @param messageKeys
     *     the AD_MESSAGE search keys behind {@code message}; may be {@code null}.
     */
    public PostResult(boolean ok, String message, List<String> messageKeys) {
      this(ok, message, messageKeys, Map.of());
    }

    /**
     * Convenience constructor for a result that carries no AD_MESSAGE keys (every success, and
     * failures whose message was not built from AD_MESSAGE tokens).
     *
     * @param ok
     *     {@code true} when the post/unpost succeeded.
     * @param message
     *     the user-facing outcome message.
     */
    public PostResult(boolean ok, String message) {
      this(ok, message, List.of(), Map.of());
    }
  }

  /**
   * Writes {@code result}'s message identity — {@link NeoProcessService#MESSAGE_KEYS} and
   * {@link NeoProcessService#MESSAGE_PARAMS} — onto a response body, each only when non-empty, so
   * a success body (and a failure with no identity) stays exactly as before. The one place that
   * serializes the identity, shared by {@link #handleAction} and {@code NotPostedDocumentsHandler}.
   *
   * @param body
   *     the response body (or per-row result) to write onto.
   * @param result
   *     the post/unpost outcome.
   * @throws JSONException
   *     never in practice (keys are non-null constants).
   */
  static void putMessageIdentity(JSONObject body, PostResult result) throws JSONException {
    if (!result.messageKeys().isEmpty()) {
      body.put(NeoProcessService.MESSAGE_KEYS, new JSONArray(result.messageKeys()));
    }
    if (!result.messageParams().isEmpty()) {
      JSONObject params = new JSONObject();
      for (Map.Entry<String, Object> entry : result.messageParams().entrySet()) {
        Object value = entry.getValue();
        params.put(entry.getKey(), value instanceof List<?> list ? new JSONArray(list) : value);
      }
      body.put(NeoProcessService.MESSAGE_PARAMS, params);
    }
  }

  /**
   * Failure result for a caught exception (ETP-5360). Core accounting code raises raw
   * {@code @AD_Message_Key@} tokens (e.g. {@code ResetAccounting}'s
   * {@code @PeriodClosedForUnPosting@}); forwarding {@code e.getMessage()} verbatim showed that
   * token in the browser. The keys are extracted first, then the text is translated in the
   * session language.
   */
  private static PostResult translatedFailure(String rawMessage) {
    return new PostResult(false, NeoMessageTranslator.safeParseTranslation(rawMessage),
        NeoMessageTranslator.extractMessageKeys(rawMessage));
  }

  /**
   * Post a single document with {@code force = false} (only posts a not-yet-posted document).
   * Manages its own transaction connection: commit on success, rollback on failure or accounting
   * errors.
   *
   * @param adTableId
   *     AD_Table_ID of the document table.
   * @param recordId
   *     primary key of the record to post.
   * @return a {@link PostResult} describing the outcome.
   */
  public PostResult post(String adTableId, String recordId) {
    return post(adTableId, recordId, getConnectionProvider());
  }

  /**
   * Package-private seam used by {@link #post(String, String)} and by unit tests, so the posting /
   * commit / rollback logic can be exercised with a mocked {@link ConnectionProvider} (no live DB).
   */
  PostResult post(String adTableId, String recordId, ConnectionProvider conn) {
    if (isUncalculatedCost(adTableId, recordId)) {
      return new PostResult(false, OBMessageUtils.messageBD(MSG_NOT_CALCULATED_COST),
          List.of(MSG_NOT_CALCULATED_COST));
    }
    OBContext ctx = OBContext.getOBContext();
    String clientId = ctx.getCurrentClient().getId();
    String orgId = ctx.getCurrentOrganization().getId();
    String userId = ctx.getUser().getId();
    Connection con = null;
    try {
      con = conn.getTransactionConnection();
      AcctServer acct = AcctServer.get(adTableId, clientId, orgId, conn);
      if (acct == null) {
        conn.releaseRollbackConnection(con);
        return new PostResult(false, "No accounting engine for table " + adTableId);
      }
      VariablesSecureApp vars = new VariablesSecureApp(userId, clientId, orgId);
      boolean posted = acct.post(recordId, false, vars, conn, con);
      if (!posted || acct.errors != 0) {
        conn.releaseRollbackConnection(con);
        return failureOf(acct);
      }
      conn.releaseCommitConnection(con);
      return new PostResult(true, "Document posted");
    } catch (Exception e) {
      rollbackQuietly(conn, con);
      log.error("Post failed for table {} record {}", adTableId, recordId, e);
      return translatedFailure(e.getMessage());
    }
  }

  /**
   * Cost-calculated pre-check scoped to Physical Inventory ({@code M_Inventory}, ETP-5360) and
   * Internal Consumption ({@code M_Internal_Consumption}, ETP-5445) ONLY — do not generalize to
   * other document types without confirming they share this exact per-transaction cost-calculation
   * shape (one {@code M_Transaction} per document line, validated line by line in
   * {@code createFact}).
   *
   * <p>{@code DocInventory#createFact} (classic core, {@code DocInventory.java}) throws a bare
   * {@code IllegalStateException()} when a line's {@code MaterialTransaction.isCostCalculated()}
   * is false — no message, no status. That exception falls into {@code AcctServer.createFacts}'s
   * generic {@code catch (Exception e)} branch (only {@code OBException} is special-cased there),
   * so the specific {@code STATUS_NotCalculatedCost} core would otherwise set is discarded before
   * we ever see it, and {@code postLogic} returns the generic {@code STATUS_Error} instead. Even if
   * that status did survive, core's resolved text is the per-product
   * {@code NotCalculatedCostWithTransaction} variant, not the clean generic message this ticket
   * wants, and it would carry the same classic-session locale bug fixed for {@code InvalidAccount}
   * in ETP-5175 (see {@link #errorMessageOf}). Rather than patch {@code AcctServer}'s status/message
   * propagation (a core change, out of scope here), this pre-check runs BEFORE {@code acct.post()}
   * is ever called, so the swallowed exception never happens and we return our own clean, correctly
   * localized message directly.
   *
   * <p>ETP-5445 extends the same gate to Internal Consumption: core's
   * {@code DocInternalConsumption#validateCostCalculation} ({@code DocInternalConsumption.java},
   * lines 223-228) has the identical shape — it sets {@code STATUS_NotCalculatedCost} and then
   * throws a bare {@code IllegalStateException()}, which {@code AcctServer.createFacts} swallows
   * the same way. The table-specific part (header → lines) is resolved in
   * {@link #hasUncalculatedInventoryCost(String)} and
   * {@link #hasUncalculatedInternalConsumptionCost(String)}; the per-line transaction scan is
   * shared in {@link #hasUncalculatedTransaction(List)}. Both helpers are called inside this
   * method's {@code try}, so any exception they throw still fails open here.</p>
   *
   * @param adTableId
   *     AD_Table_ID of the document table being posted.
   * @param recordId
   *     primary key of the record being posted ({@code M_Inventory_ID} or
   *     {@code M_Internal_Consumption_ID} when applicable).
   * @return {@code true} when this is an {@code M_Inventory} or {@code M_Internal_Consumption}
   *     document with at least one line transaction whose cost is not yet calculated (including a
   *     null/unset flag, treated as not calculated); {@code false} otherwise, including when the
   *     table is neither of those two, the record cannot be resolved, or the lookup itself fails.
   *     Unlike {@link #resolveMissingBpGroupAccounts} and {@link #resolveMissingProductAccounts}
   *     — enrichment helpers called AFTER {@code acct.post()} has already failed, where a failure
   *     here just omits extra detail text and the post stays blocked either way — this method is
   *     a GATE called BEFORE {@code acct.post()}. On a lookup error it returns {@code false},
   *     which lets the post PROCEED normally: this pre-check fails OPEN (permissive), not closed.
   *     That is deliberate: the alternative (blocking on lookup failure) would risk breaking the
   *     ~15 existing non-Inventory unit tests that exercise this path with an unmocked
   *     {@code OBDal}, for a pre-check that is a purely additive improvement over the generic
   *     error path in the first place.
   */
  private boolean isUncalculatedCost(String adTableId, String recordId) {
    try {
      Table table = OBDal.getInstance().get(Table.class, adTableId);
      if (table == null) {
        return false;
      }
      String dbTableName = table.getDBTableName();
      if (TABLE_M_INVENTORY.equals(dbTableName)) {
        return hasUncalculatedInventoryCost(recordId);
      }
      if (TABLE_M_INTERNAL_CONSUMPTION.equals(dbTableName)) {
        return hasUncalculatedInternalConsumptionCost(recordId);
      }
      return false;
    } catch (Exception e) {
      log.warn("Could not evaluate cost-calculated pre-check for table {} record {}", adTableId, recordId, e);
      return false;
    }
  }

  /**
   * {@code M_Inventory} branch of {@link #isUncalculatedCost}: resolves the Physical Inventory
   * header and scans each line's transactions. {@code false} when the record does not exist.
   * Exceptions propagate to the caller's fail-open {@code catch}.
   */
  private static boolean hasUncalculatedInventoryCost(String recordId) {
    InventoryCount inventoryCount = OBDal.getInstance().get(InventoryCount.class, recordId);
    if (inventoryCount == null) {
      return false;
    }
    for (InventoryCountLine line : inventoryCount.getMaterialMgmtInventoryCountLineList()) {
      if (hasUncalculatedTransaction(line.getMaterialMgmtMaterialTransactionList())) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code M_Internal_Consumption} branch of {@link #isUncalculatedCost} (ETP-5445): resolves the
   * Internal Consumption header and scans each line's transactions. {@code false} when the record
   * does not exist. Exceptions propagate to the caller's fail-open {@code catch}.
   */
  private static boolean hasUncalculatedInternalConsumptionCost(String recordId) {
    InternalConsumption consumption = OBDal.getInstance().get(InternalConsumption.class, recordId);
    if (consumption == null) {
      return false;
    }
    for (InternalConsumptionLine line : consumption.getMaterialMgmtInternalConsumptionLineList()) {
      if (hasUncalculatedTransaction(line.getMaterialMgmtMaterialTransactionList())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Shared per-line scan for {@link #isUncalculatedCost}: {@code true} when any of the line's
   * material transactions has {@code IsCostCalculated} not set to {@code 'Y'} (a null flag counts
   * as not calculated).
   */
  private static boolean hasUncalculatedTransaction(List<MaterialTransaction> transactions) {
    for (MaterialTransaction transaction : transactions) {
      if (!Boolean.TRUE.equals(transaction.isCostCalculated())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Unpost (descontabilizar): delete the {@code Fact_Acct} rows and set {@code Posted = 'N'} via
   * {@link ResetAccounting#delete}.
   *
   * @param adTableId
   *     AD_Table_ID of the document table.
   * @param recordId
   *     primary key of the record to unpost.
   * @return a {@link PostResult} mentioning how many entries were removed.
   */
  public PostResult unpost(String adTableId, String recordId) {
    OBContext ctx = OBContext.getOBContext();
    String clientId = ctx.getCurrentClient().getId();
    String orgId = ctx.getCurrentOrganization().getId();
    try {
      HashMap<String, Integer> counts =
          ResetAccounting.delete(clientId, orgId, adTableId, recordId, "", "");
      int deleted = counts.getOrDefault("deleted", 0);
      return new PostResult(true, "Unposted (" + deleted + " entries removed)");
    } catch (Exception e) {
      log.error("Unpost failed for table {} record {}", adTableId, recordId, e);
      return translatedFailure(e.getMessage());
    }
  }

  /**
   * Action-endpoint dispatch. Returns a {@link NeoResponse} for {@code post}/{@code unpost}
   * actions, or {@code null} to let the caller fall through to default CRUD. Reused by every
   * document-window handler.
   *
   * @param context
   *     the current NEO request context.
   * @return a {@link NeoResponse} for handled actions, otherwise {@code null}.
   */
  public NeoResponse handleAction(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.ACTION) {
      return null;
    }
    String action = context.getFieldName();
    if (!"post".equals(action) && !"unpost".equals(action)) {
      return null;
    }
    String adTableId = context.getAdTab().getTable().getId();
    String recordId = context.getRecordId();
    PostResult result =
        "post".equals(action) ? post(adTableId, recordId) : unpost(adTableId, recordId);
    try {
      JSONObject body = new JSONObject();
      body.put("success", result.ok());
      body.put("message", result.message());
      putMessageIdentity(body, result);
      // NOTE: pass the JSONObject itself, NOT body.toString() — that String would bind to the
      // NeoResponse.error(int, String) overload, which wraps it as a nested error.message
      // string instead of sending this flat body, silently discarding the real message from
      // every NEO client that only reads a top-level `message` field (ETP-4706).
      return result.ok() ? NeoResponse.ok(body) : NeoResponse.error(422, body);
    } catch (Exception e) {
      log.error("Posting action error for record {}", context.getRecordId(), e);
      return NeoResponse.error(500, "Posting action error");
    }
  }

  /** Seam for the transaction connection provider; overridable in tests. */
  ConnectionProvider getConnectionProvider() {
    return new DalConnectionProvider(false);
  }

  /**
   * {@code AD_MESSAGE.VALUE} for the base "account could not be found" text (ETP-5175) — confirmed
   * against {@code AD_MESSAGE_ID = FF8080812EA11CED012EA1CCB28700F0} in core Etendo's
   * {@code AD_MESSAGE.xml}. Re-resolved directly (see {@link #errorMessageOf}) rather than trusted
   * from {@code AcctServer.getMessageResult()}, because core always bakes that base text in the
   * WRONG language for a NEO Headless request.
   */
  private static final String MSG_INVALID_ACCOUNT_BASE = "InvalidAccount";

  private static String errorMessageOf(AcctServer acct) {
    OBError result = acct.getMessageResult();
    String message = (result != null && result.getMessage() != null && !result.getMessage().isEmpty())
        ? result.getMessage()
        : "Posting failed";
    // ETP-5175: core's AcctServer.setMessageResult always re-derives the message language from
    // the classic HttpServletRequest/session (see AcctServer.java — it never reads the GO locale
    // NeoAuthenticator/NeoLanguage apply to OBContext for a NEO request, because a NEO request
    // always has an active HttpServletRequest and so always takes that branch), so the base
    // "InvalidAccount" text is permanently baked in the wrong language before we ever see it here.
    // Re-resolve it ourselves in the GO locale for this one known status — no core change needed,
    // OBMessageUtils.messageBD already follows OBContext's language like the rest of this file's
    // own enrichment messages (MSG_INVALID_ACCOUNT_BP_AND_GROUP, etc.). Every other status already
    // carries its own correctly-derived message from core and is left untouched.
    if (AcctServer.STATUS_InvalidAccount.equals(acct.getStatus())) {
      String localizedBase = OBMessageUtils.messageBD(MSG_INVALID_ACCOUNT_BASE);
      if (StringUtils.isNotBlank(localizedBase)) {
        message = localizedBase;
      }
    }
    // ETP-5436: 'D' on a Goods Movement means the same "cost not yet calculated" condition
    // MSG_NOT_CALCULATED_COST already describes (see its javadoc) — reuse it rather than a
    // second hardcoded message.
    if (AcctServer.STATUS_DocumentDisabled.equals(acct.getStatus())
        && TABLE_M_MOVEMENT.equals(acct.tableName)) {
      String localizedNotCalculated = OBMessageUtils.messageBD(MSG_NOT_CALCULATED_COST);
      if (StringUtils.isNotBlank(localizedNotCalculated)) {
        message = localizedNotCalculated;
      }
    }
    return message;
  }

  /**
   * Failure result for a failed {@code acct.post()}. Every status keeps core's (re-localized, see
   * {@link #errorMessageOf}) message and no identity, except {@code STATUS_InvalidAccount}: core's
   * accounting engine does not always say which entity caused that failure — {@code
   * DocInOut#createFact} (and other {@code Doc*} subclasses) can leave an account null and fall
   * through to {@code AcctServer#post}'s generic {@code setMessageResult(conn, vars, getStatus(),
   * "")} fallback, which resolves to the bare {@code @InvalidAccount@} message ("Account could not
   * be found.") — so the message is enriched with the transaction's Business Partner, its Contact
   * Category (BP Group) and the unconfigured accounts, resolved generically from the public
   * {@code C_BPartner_ID} {@link AcctServer} sets for every document type it posts.
   *
   * <p>ETP-5175 (pasada 1): the enrichment also travels as structured identity — {@code
   * messageKeys} plus {@code messageParams} (names and stable account codes) — because the
   * Spanish wording of the {@code ETGO_InvalidAccount*} fragments has no versioned home: this
   * module is not a translation module, so {@code AD_MESSAGE_TRL} is never exported for it and
   * drifts per environment. The SPA renders the sentence from the identity in its own locale; the
   * prose stays for clients that do not (MCP, older SPA builds).</p>
   *
   * <p>When the Business Partner cannot be resolved the keys are just {@code ["InvalidAccount"]}
   * with no params, and the message is the bare base text, as before.</p>
   */
  private static PostResult failureOf(AcctServer acct) {
    String message = errorMessageOf(acct);
    if (!AcctServer.STATUS_InvalidAccount.equals(acct.getStatus())) {
      return new PostResult(false, message);
    }
    InvalidAccountDetail detail = resolveInvalidAccountDetail(acct);
    if (detail == null) {
      return new PostResult(false, message, List.of(MSG_INVALID_ACCOUNT_BASE));
    }
    return new PostResult(false, message + " " + detail.prose(), detail.messageKeys(),
        detail.messageParams());
  }

  /**
   * The Invalid-Account enrichment, both as prose (backend-localized, appended to the base
   * message) and as identity ({@code messageKeys} starting with {@code InvalidAccount}, plus the
   * {@code messageParams} they interpolate).
   */
  private record InvalidAccountDetail(String prose, List<String> messageKeys,
      Map<String, Object> messageParams) {
  }

  /** One unconfigured curated account: its stable wire code and its backend-localized label. */
  private record MissingAccount(String code, String label) {
  }

  /**
   * Builds the {@link InvalidAccountDetail} for a failed post, or {@code null} when the Business
   * Partner is not set or cannot be resolved, or the lookup fails (the caller then keeps the bare
   * base message). The two missing-accounts lookups fail closed on their own (empty list), so a
   * failure there never discards the already-resolved BP / Contact Category detail (QA
   * regression, ETP-5175).
   */
  private static InvalidAccountDetail resolveInvalidAccountDetail(AcctServer acct) {
    String bpartnerId = acct.C_BPartner_ID;
    if (StringUtils.isBlank(bpartnerId)) {
      return null;
    }
    try {
      BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, bpartnerId);
      if (bp == null) {
        return null;
      }
      List<MissingAccount> missingProductAccounts = resolveMissingProductAccounts(acct);
      List<String> keys = new ArrayList<>(List.of(MSG_INVALID_ACCOUNT_BASE));
      Map<String, Object> params = new LinkedHashMap<>();
      params.put(MESSAGE_PARAMS_BP_NAME, bp.getName());
      Category bpGroup = bp.getBusinessPartnerCategory();
      String prose;
      if (bpGroup == null) {
        keys.add(MSG_INVALID_ACCOUNT_BP_ONLY);
        prose = OBMessageUtils.messageBD(MSG_INVALID_ACCOUNT_BP_ONLY).replace("@bpName@", bp.getName());
      } else {
        keys.add(MSG_INVALID_ACCOUNT_BP_AND_GROUP);
        params.put(MESSAGE_PARAMS_BP_GROUP, bpGroup.getName());
        prose = OBMessageUtils.messageBD(MSG_INVALID_ACCOUNT_BP_AND_GROUP)
            .replace("@bpName@", bp.getName())
            .replace("@bpGroup@", bpGroup.getName());
        prose = appendMissingAccounts(prose, keys, params, MSG_MISSING_BP_GROUP_ACCOUNTS,
            MESSAGE_PARAMS_MISSING_BP_GROUP_ACCOUNTS, resolveMissingBpGroupAccounts(bpGroup.getId(), acct));
      }
      prose = appendMissingAccounts(prose, keys, params, MSG_MISSING_PRODUCT_ACCOUNTS,
          MESSAGE_PARAMS_MISSING_PRODUCT_ACCOUNTS, missingProductAccounts);
      return new InvalidAccountDetail(prose, keys, params);
    } catch (Exception e) {
      log.debug("Could not resolve Business Partner detail for account error, bpartnerId={}", bpartnerId, e);
      return null;
    }
  }

  /**
   * Appends one "missing accounts" addendum when {@code missing} is non-empty: the prose sentence
   * ({@code messageKey} with its labels, space-separated), the key, and the account codes under
   * {@code paramName}. Returns {@code prose} unchanged, touching nothing, when nothing is missing.
   */
  private static String appendMissingAccounts(String prose, List<String> keys, Map<String, Object> params,
      String messageKey, String paramName, List<MissingAccount> missing) {
    if (missing.isEmpty()) {
      return prose;
    }
    keys.add(messageKey);
    params.put(paramName, missing.stream().map(MissingAccount::code).toList());
    String labels = String.join(", ", missing.stream().map(MissingAccount::label).toList());
    return prose + " " + OBMessageUtils.messageBD(messageKey).replace("@missingAccounts@", labels);
  }

  /**
   * Which of the curated {@link #BP_GROUP_ACCOUNT_COLUMNS} are unconfigured for the failing BP
   * Group + accounting schema (ETP-5175). Empty when the accounting schema cannot be resolved
   * (defensive — no guessing), when every curated column is configured, or when the lookup itself
   * fails (e.g. transient DB error, OBDal/Hibernate mapping issue): the addendum is optional, so
   * it fails closed on its own instead of propagating to {@link #resolveInvalidAccountDetail},
   * which would otherwise discard the already-built BP + BP Group detail along with it.
   *
   * @param bpGroupId
   *     id of the resolved BP Group ({@code C_BP_Group_ID}).
   * @param acct
   *     the failed {@link AcctServer} instance, used to resolve the accounting schema.
   * @return the unconfigured accounts; empty if nothing is missing or the lookup failed.
   */
  private static List<MissingAccount> resolveMissingBpGroupAccounts(String bpGroupId, AcctServer acct) {
    String acctSchemaId = resolveAcctSchemaId(acct);
    if (StringUtils.isBlank(acctSchemaId)) {
      return List.of();
    }
    try {
      return queryMissingBpGroupAccounts(bpGroupId, acctSchemaId);
    } catch (Exception e) {
      log.debug("Could not resolve missing BP Group accounts detail, bpGroupId={}", bpGroupId, e);
      return List.of();
    }
  }

  /** First accounting schema's id, resolved the same way the rest of {@code AcctServer} does (its public {@code m_as} array). */
  private static String resolveAcctSchemaId(AcctServer acct) {
    return (acct.m_as != null && acct.m_as.length > 0) ? acct.m_as[0].getC_AcctSchema_ID() : null;
  }

  /**
   * Looks up the {@link CategoryAccounts} row (the {@code C_BP_Group_Acct} table) for the given
   * BP Group + accounting schema — the unique constraint {@code c_bp_group_acct_schem_group_un}
   * guarantees at most one row — and returns every curated column that is null. When no row
   * exists at all for that group + schema, every curated column is reported as missing (there is
   * no configuration whatsoever), which is itself the useful signal.
   *
   * @param bpGroupId
   *     id of the BP Group ({@code C_BP_Group_ID}).
   * @param acctSchemaId
   *     id of the accounting schema ({@code C_AcctSchema_ID}).
   * @return the unconfigured curated columns; empty when everything is configured.
   */
  private static List<MissingAccount> queryMissingBpGroupAccounts(String bpGroupId, String acctSchemaId) {
    OBCriteria<CategoryAccounts> criteria = OBDal.getInstance().createCriteria(CategoryAccounts.class);
    criteria.add(Restrictions.eq(CategoryAccounts.PROPERTY_BUSINESSPARTNERCATEGORY + ".id", bpGroupId));
    criteria.add(Restrictions.eq(CategoryAccounts.PROPERTY_ACCOUNTINGSCHEMA + ".id", acctSchemaId));
    criteria.setMaxResults(1);
    CategoryAccounts categoryAccounts = (CategoryAccounts) criteria.uniqueResult();

    String lang = OBContext.getOBContext().getLanguage().getLanguage();
    List<MissingAccount> missing = new ArrayList<>();
    for (BpGroupAccountColumn column : BP_GROUP_ACCOUNT_COLUMNS) {
      AccountingCombination value = categoryAccounts == null ? null : column.getter().apply(categoryAccounts);
      if (value == null) {
        missing.add(new MissingAccount(column.code(), column.label(lang)));
      }
    }
    return missing;
  }

  /**
   * Which of the curated {@link #PRODUCT_ACCOUNT_COLUMNS} are unconfigured for the product on a
   * failing Matched Purchase Invoice (ETP-5175). {@code DocMatchInv#createFact} resolves these
   * from {@code M_Product_Acct} — a completely different table from the BP-Group one above, keyed
   * by the invoice line's PRODUCT, not the Business Partner's group — so this check is gated to
   * {@code AcctServer.DOCTYPE_MatMatchInv} only: a Sales Invoice's (or any other document type's)
   * Invalid-Account failure has nothing to do with product accounts.
   *
   * <p>Fails closed on its own, same as {@link #resolveMissingBpGroupAccounts}: any exception here
   * (product lookup, criteria query) is caught locally and yields an empty list, so it never
   * unwinds {@link #resolveInvalidAccountDetail}'s outer try and discards the already-built BP
   * (+ BP Group) detail — the same regression class QA caught once for the sibling BP-Group
   * lookup (ETP-5175 reject cycle).</p>
   *
   * @param acct
   *     the failed {@link AcctServer} instance; {@code acct.Record_ID} is the
   *     {@code M_MatchInv_ID} on a Matched Purchase Invoice failure.
   * @return the unconfigured accounts; empty if the document is not a Matched Purchase Invoice,
   *     nothing is missing, or the lookup failed.
   */
  private static List<MissingAccount> resolveMissingProductAccounts(AcctServer acct) {
    if (!AcctServer.DOCTYPE_MatMatchInv.equals(acct.DocumentType)) {
      return List.of();
    }
    String acctSchemaId = resolveAcctSchemaId(acct);
    if (StringUtils.isBlank(acctSchemaId)) {
      return List.of();
    }
    try {
      return queryMissingProductAccounts(acct.Record_ID, acctSchemaId);
    } catch (Exception e) {
      log.debug("Could not resolve missing product accounts detail, matchInvId={}", acct.Record_ID, e);
      return List.of();
    }
  }

  /**
   * Looks up the {@link ProductAccounts} row (the {@code M_Product_Acct} table, keyed by
   * {@code (M_Product_ID, C_AcctSchema_ID)}) for the product on the given Matched Purchase Invoice
   * ({@code M_MatchInv_ID}) + accounting schema, and returns every curated column that is null.
   * When no {@code ProductAccounts} row exists at all (the product's accounting was never
   * provisioned) every curated column is reported as missing, mirroring
   * {@link #queryMissingBpGroupAccounts}'s "no row" handling. When the {@code M_MatchInv} record
   * or its product cannot be resolved at all (a data-integrity edge case, not an accounting-setup
   * gap), nothing is reported — there is no product to name a missing account against.
   *
   * @param matchInvId
   *     id of the {@code M_MatchInv} record ({@code acct.Record_ID} on a Matched Purchase Invoice
   *     failure).
   * @param acctSchemaId
   *     id of the accounting schema ({@code C_AcctSchema_ID}).
   * @return the unconfigured curated columns; empty when everything is configured.
   */
  private static List<MissingAccount> queryMissingProductAccounts(String matchInvId, String acctSchemaId) {
    ReceiptInvoiceMatch match = OBDal.getInstance().get(ReceiptInvoiceMatch.class, matchInvId);
    Product product = match == null ? null : match.getProduct();
    if (product == null) {
      return List.of();
    }

    OBCriteria<ProductAccounts> criteria = OBDal.getInstance().createCriteria(ProductAccounts.class);
    criteria.add(Restrictions.eq(ProductAccounts.PROPERTY_PRODUCT + ".id", product.getId()));
    criteria.add(Restrictions.eq(ProductAccounts.PROPERTY_ACCOUNTINGSCHEMA + ".id", acctSchemaId));
    criteria.setMaxResults(1);
    ProductAccounts productAccounts = (ProductAccounts) criteria.uniqueResult();

    String lang = OBContext.getOBContext().getLanguage().getLanguage();
    List<MissingAccount> missing = new ArrayList<>();
    for (ProductAccountColumn column : PRODUCT_ACCOUNT_COLUMNS) {
      AccountingCombination value = productAccounts == null ? null : column.getter().apply(productAccounts);
      if (value == null) {
        missing.add(new MissingAccount(column.code(), column.label(lang)));
      }
    }
    return missing;
  }

  private static void rollbackQuietly(ConnectionProvider conn, Connection con) {
    if (con == null) {
      return;
    }
    try {
      conn.releaseRollbackConnection(con);
    } catch (Exception ignore) {
      log.debug("Rollback after posting error failed (ignored)", ignore);
    }
  }
}
