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
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Category;
import org.openbravo.model.common.businesspartner.CategoryAccounts;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.plm.ProductAccounts;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.procurement.ReceiptInvoiceMatch;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

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
   * account's EN/ES label with the {@link CategoryAccounts} getter that reads it.
   * {@code getVendorLiability()} is a DB {@code NOT NULL} column — its null-check structurally
   * never fires, kept for completeness.
   */
  private static final List<BpGroupAccountColumn> BP_GROUP_ACCOUNT_COLUMNS = List.of(
      new BpGroupAccountColumn("Non-Invoiced Receipts", "Recibos no facturados", CategoryAccounts::getNonInvoicedReceipts),
      new BpGroupAccountColumn("Customer Receivables No.", "Recibos de clientes", CategoryAccounts::getCustomerReceivablesNo),
      new BpGroupAccountColumn("Vendor Liability", "Pasivo del proveedor", CategoryAccounts::getVendorLiability),
      new BpGroupAccountColumn("Customer Prepayment", "Prepago del cliente", CategoryAccounts::getCustomerPrepayment),
      new BpGroupAccountColumn("Vendor Prepayment", "Pagos por adelantado del proveedor", CategoryAccounts::getVendorPrepayment));

  /**
   * One curated {@code C_BP_Group_Acct} column: its EN/ES labels plus its {@link CategoryAccounts}
   * getter. {@link #label(String)} picks the Spanish label when {@code lang} is
   * {@value #LANGUAGE_ES_ES} (same exact-match precedent as
   * {@code NotPostedDocumentsHandler#getTranslatedName}), English otherwise.
   */
  private record BpGroupAccountColumn(String labelEn, String labelEs,
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
      new ProductAccountColumn("Product Expense", "Gastos del producto", ProductAccounts::getProductExpense),
      new ProductAccountColumn("Invoice Price Variance", "Desviación Pr. Factura",
          ProductAccounts::getInvoicePriceVariance));

  /**
   * One curated {@code M_Product_Acct} column: its EN/ES labels plus its {@link ProductAccounts}
   * getter. Same language-selection rule as {@link BpGroupAccountColumn#label(String)}.
   */
  private record ProductAccountColumn(String labelEn, String labelEs,
      Function<ProductAccounts, AccountingCombination> getter) {
    String label(String lang) {
      return LANGUAGE_ES_ES.equals(lang) ? labelEs : labelEn;
    }
  }

  /** Result of a post/unpost attempt. */
  public record PostResult(boolean ok, String message) {
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
        return new PostResult(false, errorMessageOf(acct));
      }
      conn.releaseCommitConnection(con);
      return new PostResult(true, "Document posted");
    } catch (Exception e) {
      rollbackQuietly(conn, con);
      log.error("Post failed for table {} record {}", adTableId, recordId, e);
      return new PostResult(false, e.getMessage());
    }
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
      return new PostResult(false, e.getMessage());
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
    return enrichWithFailingEntity(acct, message);
  }

  /**
   * Core Etendo's accounting engine ({@link AcctServer}) does not always populate which entity
   * caused an "account could not be found" failure: {@code DocInOut#createFact} (and other
   * {@code Doc*} subclasses) can leave an account null and fall through to
   * {@code AcctServer#post}'s generic {@code setMessageResult(conn, vars, getStatus(), "")}
   * fallback with no parameters, which resolves to the bare {@code @InvalidAccount@} message
   * ("Account could not be found.") — no account type, no owning entity, nothing to grep server
   * logs for. This enriches that specific, generic status with the transaction's Business
   * Partner and BP Group, resolved generically from the public {@code C_BPartner_ID} that
   * {@link AcctServer} sets for every document type it posts (not specific to Goods Receipts or
   * to the Not-Invoiced-Receipts account type — any document/account-type combination that hits
   * this same fallback benefits). Other statuses already carry their own detailed message from
   * core Etendo and are left untouched.
   *
   * @param acct
   *     the {@link AcctServer} instance after a failed {@code post()} call.
   * @param baseMessage
   *     the message already resolved from {@code acct.getMessageResult()}.
   * @return {@code baseMessage}, optionally suffixed with resolvable Business Partner / BP Group
   *     detail.
   */
  private static String enrichWithFailingEntity(AcctServer acct, String baseMessage) {
    if (acct == null || !AcctServer.STATUS_InvalidAccount.equals(acct.getStatus())) {
      return baseMessage;
    }
    String detail = resolveBusinessPartnerDetail(acct);
    return detail != null ? baseMessage + " " + detail : baseMessage;
  }

  private static String resolveBusinessPartnerDetail(AcctServer acct) {
    String bpartnerId = acct.C_BPartner_ID;
    if (StringUtils.isBlank(bpartnerId)) {
      return null;
    }
    try {
      BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, bpartnerId);
      if (bp == null) {
        return null;
      }
      // Fails closed on its own (see javadoc) — safe to call unconditionally, before the
      // BP-Group branching below, without risking the outer try/catch here.
      String productAccountsDetail = resolveMissingProductAccountsDetail(acct);
      Category bpGroup = bp.getBusinessPartnerCategory();
      if (bpGroup == null) {
        String bpOnly = OBMessageUtils.messageBD(MSG_INVALID_ACCOUNT_BP_ONLY).replace("@bpName@", bp.getName());
        return appendDetail(bpOnly, productAccountsDetail);
      }
      String detail = OBMessageUtils.messageBD(MSG_INVALID_ACCOUNT_BP_AND_GROUP)
          .replace("@bpName@", bp.getName())
          .replace("@bpGroup@", bpGroup.getName());
      detail = appendDetail(detail, resolveMissingAccountsDetail(bpGroup.getId(), acct));
      return appendDetail(detail, productAccountsDetail);
    } catch (Exception e) {
      log.debug("Could not resolve Business Partner detail for account error, bpartnerId={}", bpartnerId, e);
      return null;
    }
  }

  /** Appends {@code addendum} to {@code base} (space-separated) when present, otherwise returns {@code base} unchanged. */
  private static String appendDetail(String base, String addendum) {
    return addendum != null ? base + " " + addendum : base;
  }

  /**
   * Names which of the curated {@link #BP_GROUP_ACCOUNT_COLUMNS} are unconfigured for the failing
   * BP Group + accounting schema (ETP-5175). Returns {@code null} when the accounting schema
   * cannot be resolved (defensive — leaves the message unchanged rather than guessing), when
   * every curated column is configured, so a fully-configured group produces no behavior change,
   * or when the lookup itself fails (e.g. transient DB error, OBDal/Hibernate mapping issue) —
   * this addendum is optional, so it fails closed on its own instead of propagating to
   * {@link #resolveBusinessPartnerDetail}, which would otherwise discard the already-built
   * BP + BP Group detail along with it (QA regression, ETP-5175).
   *
   * @param bpGroupId
   *     id of the resolved BP Group ({@code C_BP_Group_ID}).
   * @param acct
   *     the failed {@link AcctServer} instance, used to resolve the accounting schema.
   * @return the "missing account setup" message detail, or {@code null} if nothing is missing or
   *     the lookup failed.
   */
  private static String resolveMissingAccountsDetail(String bpGroupId, AcctServer acct) {
    String acctSchemaId = resolveAcctSchemaId(acct);
    if (StringUtils.isBlank(acctSchemaId)) {
      return null;
    }
    List<String> missing;
    try {
      missing = resolveMissingBpGroupAccounts(bpGroupId, acctSchemaId);
    } catch (Exception e) {
      log.debug("Could not resolve missing BP Group accounts detail, bpGroupId={}", bpGroupId, e);
      return null;
    }
    if (missing.isEmpty()) {
      return null;
    }
    return OBMessageUtils.messageBD(MSG_MISSING_BP_GROUP_ACCOUNTS)
        .replace("@missingAccounts@", String.join(", ", missing));
  }

  /** First accounting schema's id, resolved the same way the rest of {@code AcctServer} does (its public {@code m_as} array). */
  private static String resolveAcctSchemaId(AcctServer acct) {
    return (acct.m_as != null && acct.m_as.length > 0) ? acct.m_as[0].getC_AcctSchema_ID() : null;
  }

  /**
   * Looks up the {@link CategoryAccounts} row (the {@code C_BP_Group_Acct} table) for the given
   * BP Group + accounting schema — the unique constraint {@code c_bp_group_acct_schem_group_un}
   * guarantees at most one row — and returns the EN labels of every curated column that is null.
   * When no row exists at all for that group + schema, every curated column is reported as
   * missing (there is no configuration whatsoever), which is itself the useful signal.
   *
   * @param bpGroupId
   *     id of the BP Group ({@code C_BP_Group_ID}).
   * @param acctSchemaId
   *     id of the accounting schema ({@code C_AcctSchema_ID}).
   * @return labels of the unconfigured curated columns; empty when everything is configured.
   */
  private static List<String> resolveMissingBpGroupAccounts(String bpGroupId, String acctSchemaId) {
    OBCriteria<CategoryAccounts> criteria = OBDal.getInstance().createCriteria(CategoryAccounts.class);
    criteria.add(Restrictions.eq(CategoryAccounts.PROPERTY_BUSINESSPARTNERCATEGORY + ".id", bpGroupId));
    criteria.add(Restrictions.eq(CategoryAccounts.PROPERTY_ACCOUNTINGSCHEMA + ".id", acctSchemaId));
    criteria.setMaxResults(1);
    CategoryAccounts categoryAccounts = (CategoryAccounts) criteria.uniqueResult();

    String lang = OBContext.getOBContext().getLanguage().getLanguage();
    List<String> missing = new ArrayList<>();
    for (BpGroupAccountColumn column : BP_GROUP_ACCOUNT_COLUMNS) {
      AccountingCombination value = categoryAccounts == null ? null : column.getter().apply(categoryAccounts);
      if (value == null) {
        missing.add(column.label(lang));
      }
    }
    return missing;
  }

  /**
   * Names which of the curated {@link #PRODUCT_ACCOUNT_COLUMNS} are unconfigured for the product
   * on a failing Matched Purchase Invoice (ETP-5175). {@code DocMatchInv#createFact} resolves
   * these from {@code M_Product_Acct} — a completely different table from the BP-Group one above,
   * keyed by the invoice line's PRODUCT, not the Business Partner's group — so this check is
   * gated to {@code AcctServer.DOCTYPE_MatMatchInv} only: a Sales Invoice's (or any other document
   * type's) Invalid-Account failure has nothing to do with product accounts.
   *
   * <p>Fails closed on its own, same as {@link #resolveMissingAccountsDetail}: any exception here
   * (product lookup, criteria query) is caught locally and yields {@code null}, so it never
   * unwinds {@link #resolveBusinessPartnerDetail}'s outer try and discards the already-built
   * BP (+ BP Group) detail — the same regression class QA caught once for the sibling BP-Group
   * lookup (ETP-5175 reject cycle).</p>
   *
   * @param acct
   *     the failed {@link AcctServer} instance; {@code acct.Record_ID} is the
   *     {@code M_MatchInv_ID} on a Matched Purchase Invoice failure.
   * @return the "missing account setup" message detail, or {@code null} if the document is not a
   *     Matched Purchase Invoice, nothing is missing, or the lookup failed.
   */
  private static String resolveMissingProductAccountsDetail(AcctServer acct) {
    if (!AcctServer.DOCTYPE_MatMatchInv.equals(acct.DocumentType)) {
      return null;
    }
    String acctSchemaId = resolveAcctSchemaId(acct);
    if (StringUtils.isBlank(acctSchemaId)) {
      return null;
    }
    List<String> missing;
    try {
      missing = resolveMissingProductAccounts(acct.Record_ID, acctSchemaId);
    } catch (Exception e) {
      log.debug("Could not resolve missing product accounts detail, matchInvId={}", acct.Record_ID, e);
      return null;
    }
    if (missing.isEmpty()) {
      return null;
    }
    return OBMessageUtils.messageBD(MSG_MISSING_PRODUCT_ACCOUNTS)
        .replace("@missingAccounts@", String.join(", ", missing));
  }

  /**
   * Looks up the {@link ProductAccounts} row (the {@code M_Product_Acct} table, keyed by
   * {@code (M_Product_ID, C_AcctSchema_ID)}) for the product on the given Matched Purchase Invoice
   * ({@code M_MatchInv_ID}) + accounting schema, and returns the EN labels of every curated column
   * that is null. When no {@code ProductAccounts} row exists at all (the product's accounting was
   * never provisioned) every curated column is reported as missing, mirroring
   * {@link #resolveMissingBpGroupAccounts}'s "no row" handling. When the {@code M_MatchInv} record
   * or its product cannot be resolved at all (a data-integrity edge case, not an accounting-setup
   * gap), nothing is reported — there is no product to name a missing account against.
   *
   * @param matchInvId
   *     id of the {@code M_MatchInv} record ({@code acct.Record_ID} on a Matched Purchase Invoice
   *     failure).
   * @param acctSchemaId
   *     id of the accounting schema ({@code C_AcctSchema_ID}).
   * @return labels of the unconfigured curated columns; empty when everything is configured.
   */
  private static List<String> resolveMissingProductAccounts(String matchInvId, String acctSchemaId) {
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
    List<String> missing = new ArrayList<>();
    for (ProductAccountColumn column : PRODUCT_ACCOUNT_COLUMNS) {
      AccountingCombination value = productAccounts == null ? null : column.getter().apply(productAccounts);
      if (value == null) {
        missing.add(column.label(lang));
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
