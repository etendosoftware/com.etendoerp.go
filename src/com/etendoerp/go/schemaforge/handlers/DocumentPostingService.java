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
import java.util.HashMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.erpCommon.ad_forms.AcctServer;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.financial.ResetAccounting;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Category;
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
    String detail = resolveBusinessPartnerDetail(acct.C_BPartner_ID);
    return detail != null ? baseMessage + " " + detail : baseMessage;
  }

  private static String resolveBusinessPartnerDetail(String bpartnerId) {
    if (StringUtils.isBlank(bpartnerId)) {
      return null;
    }
    try {
      BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, bpartnerId);
      if (bp == null) {
        return null;
      }
      Category bpGroup = bp.getBusinessPartnerCategory();
      return bpGroup != null
          ? String.format("(Business Partner: %s, BP Group: %s)", bp.getName(), bpGroup.getName())
          : String.format("(Business Partner: %s)", bp.getName());
    } catch (Exception e) {
      log.debug("Could not resolve Business Partner detail for account error, bpartnerId={}", bpartnerId, e);
      return null;
    }
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
