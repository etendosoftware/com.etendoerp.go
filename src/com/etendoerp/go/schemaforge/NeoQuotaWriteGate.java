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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.JDBCException;
import org.hibernate.Transaction;
import org.hibernate.resource.transaction.spi.TransactionStatus;
import org.openbravo.dal.service.OBDal;
import org.openbravo.service.db.DbUtility;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;
import org.openbravo.service.json.JsonUtils;

import com.etendoerp.go.usage.quota.QuotaEvaluation;
import com.etendoerp.go.usage.quota.QuotaItemJson;
import com.etendoerp.go.usage.quota.QuotaWriteGuard;

/**
 * ETP-5051 — wraps one NEO create with the plan-quota check: after the insert, inside the same
 * transaction, the created row is tested by {@link QuotaWriteGuard}; a DENY rolls the create back
 * and answers 403 {@code QUOTA_EXCEEDED} (contract §3).
 *
 * <p><b>Every create channel goes through it:</b> REST single and REST batch reach it in
 * {@code NeoCrudHandler}'s POST branch, and MCP {@code etendo_create}, which writes through its own
 * pipeline in {@code McpToolRouter#handleCreate}, calls it there. MCP {@code etendo_batch} and the
 * MCP batch jobs go through {@code BatchService} and so through {@code NeoCrudHandler}.
 *
 * <p><b>Who ends the transaction.</b> Core's {@link DefaultJsonDataService#add} commits on its own,
 * which would leave nothing to roll back. So when this create must be checked and no batch owns the
 * transaction, the create runs through the deferred-commit service (the one a batch uses, IMP-23)
 * and this gate ends the transaction itself: commit when the row may stand, roll back when it is
 * refused or the write failed. Inside a batch it never commits: a refusal fails the operation and
 * the batch rolls everything back. A create that is not checked — every entity no active
 * {@code block} quota counts — is untouched: same service, same commit, no query.
 *
 * <p><b>When the check itself fails.</b> It depends on whether the request's transaction survived:
 * <ul>
 *   <li><b>An error before any SQL ran</b> — an invalid restriction fragment, a missing strategy
 *       counter — leaves the transaction intact: the error is logged and the create stands, so a
 *       broken quota configuration does not stop every create of an entity (fails open).</li>
 *   <li><b>An error from the database</b> (a JDBC / persistence exception anywhere in the cause
 *       chain, or a transaction Hibernate has marked rollback-only) has poisoned the transaction:
 *       PostgreSQL aborts it and the commit would silently roll the row back while the caller is
 *       told it exists. So it fails <b>closed</b>: the gate rolls back the create it owns, or fails
 *       the batch operation, and answers {@value #CHECK_FAILED_STATUS} {@value #CHECK_FAILED_CODE}
 *       — nothing was saved.</li>
 * </ul>
 * A commit the gate makes that fails (a deferred constraint, say) does not escape as an exception:
 * the add result is replaced by core's own error JSON for that exception ({@link Create#result()}),
 * so the caller classifies it exactly as it classified a failing self-committing add before.
 *
 * <p>Structural only: the decision is driven by catalog data, never by an entity or spec name.
 */
public final class NeoQuotaWriteGate {

  private static final Logger log = LogManager.getLogger(NeoQuotaWriteGate.class);

  /** HTTP status of a quota refusal. */
  public static final int STATUS = HttpServletResponse.SC_FORBIDDEN;
  /** Machine-readable code of a quota refusal; the SPA keys its own message off it. */
  public static final String CODE = "QUOTA_EXCEEDED";

  /** HTTP status when the quota check failed on the database: nothing was saved. */
  public static final int CHECK_FAILED_STATUS = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
  /** Machine-readable code of a failed quota check; the SPA translates it. */
  public static final String CHECK_FAILED_CODE = "QUOTA_CHECK_FAILED";
  /** The message of {@link #CHECK_FAILED_CODE}; clients show their own translation. */
  public static final String CHECK_FAILED_MESSAGE =
      "The quota check failed; nothing was saved. Try again.";

  private static final String KEY_ERROR = "error";
  private static final String KEY_MESSAGE = "message";
  private static final String KEY_STATUS = "status";
  private static final String KEY_CODE = "code";
  private static final String KEY_QUOTA = "quota";

  private NeoQuotaWriteGate() {
  }

  /**
   * Starts one create of {@code dalEntityName}, deciding whether it is checked and who ends its
   * transaction.
   *
   * @param dalEntityName the DAL entity name of the created row
   * @return the create's gate; call {@link Create#finish} with the add result, and
   *     {@link Create#close} in a finally
   */
  public static Create begin(String dalEntityName) {
    return begin(dalEntityName, QuotaWriteGuard.getDefault(),
        BatchService.isCallerOwnedTransaction());
  }

  static Create begin(String dalEntityName, QuotaWriteGuard guard, boolean batchOwnsTransaction) {
    boolean checked;
    try {
      checked = guard.appliesTo(dalEntityName);
    } catch (RuntimeException e) {
      log.error("[QUOTA] Could not decide whether creates of {} are quota-checked; not checking",
          dalEntityName, e);
      checked = false;
    }
    return new Create(dalEntityName, guard, checked, checked && !batchOwnsTransaction);
  }

  /**
   * The contract §3 refusal: 403 with {@code error.message}, {@code error.status},
   * {@code error.code = QUOTA_EXCEEDED} and {@code error.quota} (a {@code QuotaItem}).
   *
   * @param refusal the DENY evaluation
   * @return the response
   */
  public static NeoResponse refusalResponse(QuotaEvaluation refusal) {
    String message = QuotaItemJson.refusalMessage(refusal);
    try {
      JSONObject error = new JSONObject();
      error.put(KEY_MESSAGE, message);
      error.put(KEY_STATUS, STATUS);
      error.put(KEY_CODE, CODE);
      error.put(KEY_QUOTA, QuotaItemJson.of(refusal));
      return NeoResponse.error(STATUS, new JSONObject().put(KEY_ERROR, error));
    } catch (JSONException e) {
      return NeoResponse.error(STATUS, message);
    }
  }

  /**
   * The response of a quota check that failed on the database: {@value #CHECK_FAILED_STATUS} with
   * {@code error.message}, {@code error.status} and {@code error.code = QUOTA_CHECK_FAILED}.
   *
   * @return the response
   */
  public static NeoResponse checkFailedResponse() {
    try {
      JSONObject error = new JSONObject();
      error.put(KEY_MESSAGE, CHECK_FAILED_MESSAGE);
      error.put(KEY_STATUS, CHECK_FAILED_STATUS);
      error.put(KEY_CODE, CHECK_FAILED_CODE);
      return NeoResponse.error(CHECK_FAILED_STATUS, new JSONObject().put(KEY_ERROR, error));
    } catch (JSONException e) {
      return NeoResponse.error(CHECK_FAILED_STATUS, CHECK_FAILED_MESSAGE);
    }
  }

  /** One create under the gate. Not thread-safe; it lives for one request. */
  public static final class Create implements AutoCloseable {

    private final String dalEntityName;
    private final QuotaWriteGuard guard;
    private final boolean checked;
    private final boolean ownsTransaction;
    private boolean ended;
    private String result;

    private Create(String dalEntityName, QuotaWriteGuard guard, boolean checked,
        boolean ownsTransaction) {
      this.dalEntityName = dalEntityName;
      this.guard = guard;
      this.checked = checked;
      this.ownsTransaction = ownsTransaction;
    }

    /**
     * Whether this create is quota-checked.
     *
     * @return {@code true} when some active {@code block} quota counts the entity
     */
    public boolean isChecked() {
      return checked;
    }

    /**
     * Whether this gate ends the transaction: the create is checked and no batch owns it.
     *
     * @return {@code true} when the gate commits or rolls back itself
     */
    public boolean ownsTransaction() {
      return ownsTransaction;
    }

    /**
     * The service the create must run through.
     *
     * @param current the service the caller would use otherwise
     * @return the deferred-commit service when this gate owns the transaction, else
     *     {@code current}
     */
    public DefaultJsonDataService jsonService(DefaultJsonDataService current) {
      return ownsTransaction ? NeoBatchJsonDataService.deferredCommitInstance() : current;
    }

    /**
     * Checks the created rows and ends the transaction when this gate owns it. Afterwards the
     * caller reads {@link #result()} instead of the add result it passed in.
     *
     * @param addResult the JSON string {@code add} returned
     * @return the refusal (403 {@code QUOTA_EXCEEDED}, or {@value #CHECK_FAILED_STATUS}
     *     {@code QUOTA_CHECK_FAILED}), or null when the caller carries on with {@link #result()}
     */
    public NeoResponse finish(String addResult) {
      result = addResult;
      if (!checked) {
        return null;
      }
      Optional<List<String>> createdIds = createdIds(addResult);
      if (!createdIds.isPresent()) {
        rollbackIfOwned();
        return null;
      }
      Outcome outcome = check(createdIds.get());
      if (outcome.response != null) {
        rollbackIfOwned();
        return outcome.response;
      }
      commitIfOwned();
      return null;
    }

    /**
     * The add result the caller must go on with: the one passed to {@link #finish}, or core's error
     * JSON when the gate's own commit failed.
     *
     * @return the add result
     */
    public String result() {
      return result;
    }

    /** Rolls back a transaction this gate owns and never ended (the create threw). */
    @Override
    public void close() {
      rollbackIfOwned();
    }

    private Outcome check(List<String> createdIds) {
      try {
        Optional<QuotaEvaluation> refusal = guard.refusalFor(dalEntityName, createdIds);
        if (refusal.isPresent()) {
          log.info("[QUOTA] Refused a create of {}: {}", dalEntityName, refusal.get());
          return new Outcome(refusalResponse(refusal.get()));
        }
        return Outcome.STANDS;
      } catch (RuntimeException e) {
        if (isTransactionPoisoned(e)) {
          log.error("[QUOTA] The quota check of a create of {} failed on the database; the "
              + "transaction is lost, so nothing is saved ({})", dalEntityName, CHECK_FAILED_CODE,
              e);
          return new Outcome(checkFailedResponse());
        }
        log.error("[QUOTA] The quota check of a create of {} failed before reaching the "
            + "database; the create stands", dalEntityName, e);
        return Outcome.STANDS;
      }
    }

    private void commitIfOwned() {
      if (!ownsTransaction || ended) {
        return;
      }
      ended = true;
      try {
        OBDal.getInstance().commitAndClose();
      } catch (RuntimeException e) {
        // Core's commitAndClose closes the session on failure. Hand the caller the same error JSON
        // core's self-committing add returned for a failing commit, so it is classified as before.
        log.warn("[QUOTA] The commit of a checked create of {} failed", dalEntityName, e);
        result = JsonUtils.convertExceptionToJson(DbUtility.getUnderlyingSQLException(e));
      }
    }

    private void rollbackIfOwned() {
      if (ownsTransaction && !ended) {
        ended = true;
        OBDal.getInstance().rollbackAndClose();
      }
    }
  }

  /** What the check decided: a response to return, or none (the create stands). */
  private static final class Outcome {
    private static final Outcome STANDS = new Outcome(null);
    private final NeoResponse response;

    private Outcome(NeoResponse response) {
      this.response = response;
    }
  }

  /**
   * Whether a failed check has poisoned the request's transaction: an exception from the database
   * anywhere in the cause chain, or a transaction Hibernate already marked rollback-only. Such a
   * transaction cannot be committed, so the create must fail closed.
   *
   * <p>A bare {@code PersistenceException} is not enough: every {@code HibernateException} is one,
   * including a {@code QueryException} raised while parsing HQL, before any SQL reached the
   * database. Hibernate wraps a real database failure with its {@link JDBCException} as the cause,
   * so the chain walk still finds it.
   *
   * @param error the check's failure
   * @return {@code true} when the transaction is lost
   */
  static boolean isTransactionPoisoned(Throwable error) {
    for (Throwable cause = error; cause != null; cause = nextCause(cause)) {
      if (cause instanceof SQLException || cause instanceof JDBCException) {
        return true;
      }
    }
    return isMarkedRollbackOnly();
  }

  private static Throwable nextCause(Throwable cause) {
    return cause.getCause() == cause ? null : cause.getCause();
  }

  private static boolean isMarkedRollbackOnly() {
    try {
      Transaction transaction = OBDal.getInstance().getSession().getTransaction();
      return transaction != null && transaction.getStatus() == TransactionStatus.MARKED_ROLLBACK;
    } catch (RuntimeException e) {
      // No readable transaction state: the session itself is unusable.
      log.debug("Could not read the transaction state after a failed quota check", e);
      return true;
    }
  }

  /**
   * The ids of the rows an {@code add} created.
   *
   * @param addResult the JSON string {@code add} returned
   * @return the ids, possibly empty; empty {@link Optional} on a failed or unreadable result
   */
  static Optional<List<String>> createdIds(String addResult) {
    if (StringUtils.isBlank(addResult)) {
      return Optional.empty();
    }
    try {
      JSONObject response = new JSONObject(addResult).optJSONObject(JsonConstants.RESPONSE_RESPONSE);
      if (response == null || response.optInt(JsonConstants.RESPONSE_STATUS,
          JsonConstants.RPCREQUEST_STATUS_FAILURE) != JsonConstants.RPCREQUEST_STATUS_SUCCESS) {
        return Optional.empty();
      }
      return Optional.of(idsOf(response.optJSONArray(JsonConstants.RESPONSE_DATA)));
    } catch (JSONException e) {
      return Optional.empty();
    }
  }

  private static List<String> idsOf(JSONArray data) {
    List<String> ids = new ArrayList<>();
    if (data == null) {
      return ids;
    }
    for (int i = 0; i < data.length(); i++) {
      JSONObject row = data.optJSONObject(i);
      String id = row == null ? null : row.optString(JsonConstants.ID, null);
      if (StringUtils.isNotBlank(id)) {
        ids.add(id);
      }
    }
    return ids;
  }
}
