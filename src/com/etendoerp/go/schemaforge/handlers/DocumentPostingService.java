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
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
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
   * The {@code C_BP_Group_Acct} columns relevant to this app's document types (ETP-5175) — a
   * curated subset, not every nullable column on that table. Each entry pairs the account's
   * English label (matching the English-only precedent of {@link #MSG_INVALID_ACCOUNT_BP_AND_GROUP}
   * / {@link #MSG_INVALID_ACCOUNT_BP_ONLY} — no {@code AD_MESSAGE_TRL} exists for this catalog)
   * with the {@link CategoryAccounts} getter that reads it. {@code getVendorLiability()} is a DB
   * {@code NOT NULL} column — its null-check structurally never fires, kept for completeness.
   */
  private static final List<BpGroupAccountColumn> BP_GROUP_ACCOUNT_COLUMNS = List.of(
      new BpGroupAccountColumn("Non-Invoiced Receipts", CategoryAccounts::getNonInvoicedReceipts),
      new BpGroupAccountColumn("Non-Invoiced Receivables", CategoryAccounts::getNonInvoicedReceivables),
      new BpGroupAccountColumn("Customer Receivables No.", CategoryAccounts::getCustomerReceivablesNo),
      new BpGroupAccountColumn("Vendor Liability", CategoryAccounts::getVendorLiability),
      new BpGroupAccountColumn("Customer Prepayment", CategoryAccounts::getCustomerPrepayment),
      new BpGroupAccountColumn("Vendor Prepayment", CategoryAccounts::getVendorPrepayment));

  /** One curated {@code C_BP_Group_Acct} column: its EN label plus its {@link CategoryAccounts} getter. */
  private record BpGroupAccountColumn(String label, Function<CategoryAccounts, AccountingCombination> getter) {
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

  private static String errorMessageOf(AcctServer acct) {
    OBError result = acct.getMessageResult();
    String message = (result != null && result.getMessage() != null && !result.getMessage().isEmpty())
        ? result.getMessage()
        : "Posting failed";
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
      Category bpGroup = bp.getBusinessPartnerCategory();
      if (bpGroup == null) {
        return OBMessageUtils.messageBD(MSG_INVALID_ACCOUNT_BP_ONLY).replace("@bpName@", bp.getName());
      }
      String detail = OBMessageUtils.messageBD(MSG_INVALID_ACCOUNT_BP_AND_GROUP)
          .replace("@bpName@", bp.getName())
          .replace("@bpGroup@", bpGroup.getName());
      String missingAccountsDetail = resolveMissingAccountsDetail(bpGroup.getId(), acct);
      return missingAccountsDetail != null ? detail + " " + missingAccountsDetail : detail;
    } catch (Exception e) {
      log.debug("Could not resolve Business Partner detail for account error, bpartnerId={}", bpartnerId, e);
      return null;
    }
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

    List<String> missing = new ArrayList<>();
    for (BpGroupAccountColumn column : BP_GROUP_ACCOUNT_COLUMNS) {
      AccountingCombination value = categoryAccounts == null ? null : column.getter().apply(categoryAccounts);
      if (value == null) {
        missing.add(column.label());
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
