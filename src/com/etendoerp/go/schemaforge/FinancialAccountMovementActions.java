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
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.accounting.Costcenter;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.project.Project;

import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoActionContract.Param;

/**
 * The manual movements of a financial account as declared actions of {@code financial-account/
 * account} (ETP-5558): what the account's Movements tab lets a person do — record a deposit or a
 * withdrawal against a G/L item, edit it, process it, reactivate it, delete it.
 *
 * <p><b>Why it exists.</b> The SPA records movements through the {@code financial-account-
 * transactions} endpoint, a report-type spec the MCP does not serve, and the {@code transaction}
 * entity refuses every write through MCP because the UI never writes a movement there. So an agent
 * could not record a deposit at all; in a blind run it fell back to a bank-statement line, which is
 * a different record (what the bank says happened, waiting to be matched), not a movement of the
 * account.</p>
 *
 * <p><b>One set of business rules.</b> Every write is handed to
 * {@link FinancialAccountTransactionsHandler} — the handler behind the SPA's endpoint, resolved
 * and run as REST runs it ({@link FinancialAccountTransactionsEndpoint}) — with the very body the SPA's {@code NewTransactionModal} and {@code MovementRowKebab} send, so validation,
 * processing ({@code FIN_TransactionProcess}) and removal ({@code TransactionRemovalUtil}) are the
 * same code. What this class adds is what the SPA decides before it calls, and an agent cannot:</p>
 * <ul>
 *   <li>the movement must be a movement of THIS account, readable by the tenant (404 otherwise);</li>
 *   <li>the gates the Movements tab applies per row: Edit and Process only on a movement that
 *       belongs to no payment, Process only on a draft, Reactivate only on a processed one, and on a
 *       processed movement only its description, G/L item and dimensions are editable;</li>
 *   <li>what the modal requires: a deposit ({@code BPD}) or a withdrawal ({@code BPW}), an amount
 *       above zero, a G/L item, a description of at most 255 characters;</li>
 *   <li>a referenced id the tenant cannot read is refused, where the endpoint would silently drop
 *       it (a movement saved without the G/L item the agent asked for);</li>
 *   <li>{@code updateMovement} merges: the endpoint has no partial update, so the movement's own
 *       values are resent with the agent's changes on top, as the SPA's inline edit does
 *       ({@code buildDimensionUpdatePayload});</li>
 *   <li>every answer describes the movement as it now is.</li>
 * </ul>
 *
 * <p>The route is new, so its gates apply to whoever calls it; the SPA's own endpoint is not
 * touched. Transfers between accounts and "Add payment" from the account are not declared here.</p>
 */
class FinancialAccountMovementActions {

  private static final Logger log = LogManager.getLogger(FinancialAccountMovementActions.class);

  static final String LIST_ACTION = "listMovements";
  static final String GL_ITEMS_ACTION = "movementGlItems";
  static final String CREATE_ACTION = "createMovement";
  static final String UPDATE_ACTION = "updateMovement";
  static final String PROCESS_ACTION = "processMovement";
  static final String REACTIVATE_ACTION = "reactivateMovement";
  static final String DELETE_ACTION = "deleteMovement";

  static final String P_MOVEMENT_ID = "movementId";
  static final String P_TRX_TYPE = "trxType";
  static final String P_AMOUNT = "amount";
  static final String P_DATE = "date";
  static final String P_DESCRIPTION = "description";
  static final String P_GL_ITEM_ID = "glItemId";
  static final String P_BPARTNER_ID = "bpartnerId";
  static final String P_PROJECT_ID = "projectId";
  static final String P_COSTCENTER_ID = "costcenterId";
  static final String P_PRODUCT_ID = "productId";
  static final String P_PROCESS = "process";
  static final String P_SEARCH = "search";

  static final String TRX_DEPOSIT = "BPD";
  static final String TRX_WITHDRAWAL = "BPW";
  /** {@code FIN_Finacc_Transaction.Description}, as the modal's own limit. */
  static final int DESCRIPTION_MAX_LENGTH = 255;

  /** The keys of the SPA endpoint's bodies (FinancialAccountTransactionsHandler). */
  private static final String B_ACCOUNT_ID = "FIN_Financial_Account_ID";
  private static final String B_DEPOSIT = "depositAmount";
  private static final String B_PAYMENT = "paymentAmount";
  private static final String B_TRX_DATE = "transactionDate";
  private static final String B_ACCT_DATE = "accountingDate";
  private static final String B_CURRENCY_ID = "currencyId";

  /** On a processed movement the endpoint persists only these (applyEditableDimensions). */
  private static final Set<String> LOCKED_ONCE_PROCESSED = Set.of(P_TRX_TYPE, P_AMOUNT, P_DATE);

  private static final Set<String> WRITE_ACTIONS =
      Set.of(CREATE_ACTION, UPDATE_ACTION, PROCESS_ACTION, REACTIVATE_ACTION, DELETE_ACTION);

  private static final String MSG_MOVEMENT_NOT_FOUND = "Movement not found in this financial account";

  /** The SPA's endpoint. A seam so unit tests see the exact body it receives. */
  private final Function<NeoContext, NeoResponse> transactionsEndpoint;

  FinancialAccountMovementActions() {
    this(FinancialAccountTransactionsEndpoint::call);
  }

  FinancialAccountMovementActions(Function<NeoContext, NeoResponse> transactionsEndpoint) {
    this.transactionsEndpoint = transactionsEndpoint;
  }

  /** @return whether {@code action} is one of the movement actions */
  static boolean serves(String action) {
    return action != null && actionContracts().containsKey(action);
  }

  /**
   * The movement actions, declared for agents. {@code id} is always the financial account.
   *
   * @return the contracts, in presentation order
   */
  static Map<String, NeoActionContract> actionContracts() {
    String idDescription = "the id of the financial account the movement belongs to";
    String movementId = "Id of the movement (listMovements → transactions[].id).";
    String glItem = "Id of the G/L item (concept): the reason for the movement — what the money "
        + "is (a partner's capital contribution, a bank charge, a cash withdrawal…), which decides "
        + "the account it is booked to. If the user did not say what the money is, ask them "
        + "instead of guessing. movementGlItems finds it: its search matches part of the name, "
        + "which starts with the account code.";
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(LIST_ACTION, NeoActionContract.read(LIST_ACTION,
        "Lists the account's movements as its Movements tab shows them, newest first: id, date, "
            + "trxType (BPD deposit, BPW withdrawal, BF bank fee), amount, depositAmount, "
            + "withdrawalAmount, description, processed (false = draft), posted ('Y' = posted), "
            + "paymentStatus, paymentId (set when the movement belongs to a payment or a "
            + "collection), transferTxnId (set on a leg of a funds transfer), glItemId, "
            + "bpartnerId, the dimensions, and the account totals.")
        .withIdDescription(idDescription));
    contracts.put(GL_ITEMS_ACTION, NeoActionContract.read(GL_ITEMS_ACTION,
        "Lists the G/L items (concepts) a movement or a transfer can be booked against: id, "
            + "name. The G/L item is the reason for the money; when the user has not said what "
            + "the money is, ask them rather than search for a likely one.",
        Param.optional(P_SEARCH, NeoActionContract.TYPE_STRING,
            "Part of the name or of the account code to look for (the name starts with the code, "
                + "e.g. '118' or 'aport'); case-insensitive. Default: every G/L item."))
        .withIdDescription(idDescription));
    contracts.put(CREATE_ACTION, NeoActionContract.write(CREATE_ACTION,
        "Records a manual movement in the account — money in (deposit) or out (withdrawal) "
            + "booked against a G/L item — exactly as the account's 'New movement' form does. "
            + "This is a movement of the account itself; it is not a bank-statement line (a "
            + "statement line is what the bank reports, imported and then matched to movements "
            + "in a reconciliation). With process true the movement is processed at once "
            + "(the form's Confirmar); otherwise it stays a draft (Guardar), editable and "
            + "deletable, and processMovement confirms it later. Returns the movement.",
        Param.requiredOptions(P_TRX_TYPE,
            "'BPD' for a deposit (money in), 'BPW' for a withdrawal (money out).",
            List.of(TRX_DEPOSIT, TRX_WITHDRAWAL)),
        Param.required(P_AMOUNT, NeoActionContract.TYPE_NUMBER,
            "Amount of the movement, above zero, in the account currency."),
        Param.required(P_DATE, NeoActionContract.TYPE_DATE,
            "Date of the movement (yyyy-MM-dd); it is also its accounting date."),
        Param.required(P_GL_ITEM_ID, NeoActionContract.TYPE_STRING, glItem),
        Param.optional(P_DESCRIPTION, NeoActionContract.TYPE_STRING,
            "Free text, at most 255 characters. Default: empty."),
        Param.optional(P_BPARTNER_ID, NeoActionContract.TYPE_STRING,
            "Id of the business partner (contact) of the movement. Default: none."),
        Param.optional(P_PROJECT_ID, NeoActionContract.TYPE_STRING, "Id of the project."),
        Param.optional(P_COSTCENTER_ID, NeoActionContract.TYPE_STRING, "Id of the cost center."),
        Param.optional(P_PRODUCT_ID, NeoActionContract.TYPE_STRING, "Id of the product."),
        Param.optional(P_PROCESS, NeoActionContract.TYPE_BOOLEAN,
            "true processes the movement right away; false (default) saves it as a draft."))
        .withIdDescription(idDescription));
    contracts.put(UPDATE_ACTION, NeoActionContract.write(UPDATE_ACTION,
        "Edits a manual movement of the account, as its Edit form does. Send only what changes; "
            + "the rest keeps its value. A draft accepts every field; a processed movement "
            + "only its description, G/L item, business partner and dimensions (reactivate it "
            + "first to change its type, amount or date). A posted movement, or one that belongs "
            + "to a payment or collection, cannot be edited here. Returns the movement.",
        Param.required(P_MOVEMENT_ID, NeoActionContract.TYPE_STRING, movementId),
        Param.options(P_TRX_TYPE, "'BPD' deposit or 'BPW' withdrawal (drafts only).",
            List.of(TRX_DEPOSIT, TRX_WITHDRAWAL)),
        Param.optional(P_AMOUNT, NeoActionContract.TYPE_NUMBER,
            "New amount, above zero (drafts only)."),
        Param.optional(P_DATE, NeoActionContract.TYPE_DATE,
            "New date, yyyy-MM-dd, also the accounting date (drafts only)."),
        Param.optional(P_GL_ITEM_ID, NeoActionContract.TYPE_STRING, glItem),
        Param.optional(P_DESCRIPTION, NeoActionContract.TYPE_STRING,
            "New description, at most 255 characters."),
        Param.optional(P_BPARTNER_ID, NeoActionContract.TYPE_STRING,
            "Id of the business partner; an empty string clears it."),
        Param.optional(P_PROJECT_ID, NeoActionContract.TYPE_STRING,
            "Id of the project; an empty string clears it."),
        Param.optional(P_COSTCENTER_ID, NeoActionContract.TYPE_STRING,
            "Id of the cost center; an empty string clears it."),
        Param.optional(P_PRODUCT_ID, NeoActionContract.TYPE_STRING,
            "Id of the product; an empty string clears it."),
        Param.optional(P_PROCESS, NeoActionContract.TYPE_BOOLEAN,
            "true also processes the draft after saving it (Confirmar). Drafts only."))
        .withIdDescription(idDescription));
    contracts.put(PROCESS_ACTION, NeoActionContract.write(PROCESS_ACTION,
        "Processes a draft manual movement of the account (Borrador → Procesado). A movement "
            + "that belongs to a payment or collection is processed with it, not here. Returns "
            + "the movement.",
        Param.required(P_MOVEMENT_ID, NeoActionContract.TYPE_STRING, movementId))
        .withIdDescription(idDescription));
    contracts.put(REACTIVATE_ACTION, NeoActionContract.write(REACTIVATE_ACTION,
        "Reactivates a processed movement of the account back to draft, undoing its posting and "
            + "its reconciliation first. A movement that belongs to a payment or collection is "
            + "reactivated from that payment. Returns the movement.",
        Param.required(P_MOVEMENT_ID, NeoActionContract.TYPE_STRING, movementId))
        .withIdDescription(idDescription));
    contracts.put(DELETE_ACTION, NeoActionContract.write(DELETE_ACTION,
        "Deletes a movement of the account. A draft is removed; a processed one is reactivated "
            + "(posting and reconciliation undone) and removed, as the row's Eliminar does. A "
            + "movement that belongs to a payment or collection, or a leg of a funds transfer, "
            + "cannot be deleted here. Returns what was deleted.",
        Param.required(P_MOVEMENT_ID, NeoActionContract.TYPE_STRING, movementId))
        .withIdDescription(idDescription));
    return contracts;
  }

  /**
   * Serve one movement action.
   *
   * @param context the ACTION context; {@code recordId} is the financial account
   * @return the answer, or {@code null} when {@code context} is not a movement action
   */
  NeoResponse handle(NeoContext context) {
    String action = context.getFieldName();
    if (!NeoEndpointType.ACTION.equals(context.getEndpointType()) || !serves(action)) {
      return null;
    }
    if (WRITE_ACTIONS.contains(action) && !"POST".equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed.");
    }
    JSONObject params = context.getRequestBody() != null ? context.getRequestBody()
        : new JSONObject();
    try {
      OBContext.setAdminMode(true);
      FIN_FinancialAccount account =
          TenantOwnership.loadOwned(FIN_FinancialAccount.class, context.getRecordId());
      if (account == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Financial account not found");
      }
      return dispatch(action, account, params, context.isMcpOrigin());
    } catch (Exception e) {
      log.error("Movement action {} failed on account {}", action, context.getRecordId(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not run " + action + ". Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private NeoResponse dispatch(String action, FIN_FinancialAccount account, JSONObject params,
      boolean mcp) throws JSONException {
    if (LIST_ACTION.equals(action)) {
      return callEndpoint("GET", null, Map.of(B_ACCOUNT_ID, account.getId()), null, mcp);
    }
    if (GL_ITEMS_ACTION.equals(action)) {
      return callEndpoint("GET", "glitem-lookup",
          Map.of("q", StringUtils.defaultString(params.optString(P_SEARCH, null))), null, mcp);
    }
    if (CREATE_ACTION.equals(action)) {
      return create(account, params, mcp);
    }
    FIN_FinaccTransaction movement =
        movementOf(account, params.optString(P_MOVEMENT_ID, null));
    if (movement == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, MSG_MOVEMENT_NOT_FOUND);
    }
    if (UPDATE_ACTION.equals(action)) {
      return update(movement, params, mcp);
    }
    if (PROCESS_ACTION.equals(action)) {
      return process(movement, mcp);
    }
    if (REACTIVATE_ACTION.equals(action)) {
      return reactivate(movement, mcp);
    }
    return delete(movement, mcp);
  }

  // ── writes ─────────────────────────────────────────────────────────────

  private NeoResponse create(FIN_FinancialAccount account, JSONObject params, boolean mcp)
      throws JSONException {
    NeoResponse invalid = firstNonNull(requireMovementShape(params.optString(P_TRX_TYPE, null),
        optAmount(params), params.optString(P_GL_ITEM_ID, null)),
        checkDescription(params), checkReferences(params));
    if (invalid != null) {
      return invalid;
    }
    BigDecimal amount = optAmount(params);
    String trxType = params.getString(P_TRX_TYPE);
    String date = params.getString(P_DATE);
    // The body NewTransactionModal.handleSave sends.
    JSONObject body = new JSONObject();
    body.put(B_ACCOUNT_ID, account.getId());
    body.put(P_TRX_TYPE, trxType);
    body.put(B_TRX_DATE, date);
    body.put(B_ACCT_DATE, date);
    body.put(B_DEPOSIT, TRX_DEPOSIT.equals(trxType) ? amount : BigDecimal.ZERO);
    body.put(B_PAYMENT, TRX_WITHDRAWAL.equals(trxType) ? amount : BigDecimal.ZERO);
    body.put(B_CURRENCY_ID, account.getCurrency() != null ? account.getCurrency().getId() : null);
    body.put(P_DESCRIPTION, params.optString(P_DESCRIPTION, ""));
    body.put(P_GL_ITEM_ID, params.getString(P_GL_ITEM_ID));
    for (String ref : List.of(P_BPARTNER_ID, P_PROJECT_ID, P_COSTCENTER_ID, P_PRODUCT_ID)) {
      body.put(ref, StringUtils.trimToNull(params.optString(ref, null)));
    }
    body.put(P_PROCESS, params.optBoolean(P_PROCESS, false));
    NeoResponse result = callEndpoint("POST", "create", null, body, mcp);
    return isSuccess(result) ? answer(result, idIn(result)) : result;
  }

  private NeoResponse update(FIN_FinaccTransaction movement, JSONObject params, boolean mcp)
      throws JSONException {
    NeoResponse refused = refusePaymentLinked(movement, "edited");
    if (refused != null) {
      return refused;
    }
    if ("Y".equals(movement.getPosted())) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "A posted movement cannot be edited; reactivate it first (reactivateMovement).");
    }
    boolean processed = Boolean.TRUE.equals(movement.isProcessed());
    if (processed) {
      for (String locked : LOCKED_ONCE_PROCESSED) {
        if (params.has(locked)) {
          return unprocessable("'" + locked + "' cannot change on a processed movement; "
              + "reactivate it first (reactivateMovement), or send only description, glItemId, "
              + "bpartnerId and the dimensions.", locked);
        }
      }
      if (params.optBoolean(P_PROCESS, false)) {
        return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
            "The movement is already processed.");
      }
    }
    JSONObject body = mergedBody(movement, params);
    String trxType = body.getString(P_TRX_TYPE);
    BigDecimal amount = TRX_DEPOSIT.equals(trxType) ? (BigDecimal) body.get(B_DEPOSIT)
        : (BigDecimal) body.get(B_PAYMENT);
    NeoResponse invalid = firstNonNull(
        processed ? requireGlItem(body.optString(P_GL_ITEM_ID, null))
            : requireMovementShape(trxType, amount, body.optString(P_GL_ITEM_ID, null)),
        checkDescription(params), checkReferences(params));
    if (invalid != null) {
      return invalid;
    }
    NeoResponse result = callEndpoint("POST", "update", null, body, mcp);
    return isSuccess(result) ? answer(result, movement.getId()) : result;
  }

  private NeoResponse process(FIN_FinaccTransaction movement, boolean mcp) throws JSONException {
    NeoResponse refused = refusePaymentLinked(movement, "processed");
    if (refused != null) {
      return refused;
    }
    if (Boolean.TRUE.equals(movement.isProcessed())) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "The movement is already processed.");
    }
    NeoResponse result = callEndpoint("POST", "process", null, idBody(movement), mcp);
    return isSuccess(result) ? answer(result, movement.getId()) : result;
  }

  private NeoResponse reactivate(FIN_FinaccTransaction movement, boolean mcp)
      throws JSONException {
    // A payment-linked movement is refused by the endpoint itself (409, ETP-5111).
    if (!Boolean.TRUE.equals(movement.isProcessed())) {
      return NeoResponse.error(HttpServletResponse.SC_CONFLICT,
          "The movement is a draft; there is nothing to reactivate.");
    }
    NeoResponse result = callEndpoint("POST", "reactivate", null, idBody(movement), mcp);
    return isSuccess(result) ? answer(result, movement.getId()) : result;
  }

  private NeoResponse delete(FIN_FinaccTransaction movement, boolean mcp) throws JSONException {
    // Described before it goes. The endpoint refuses a payment-linked movement and a transfer leg
    // (409), and reactivates a processed one before removing it, as the row kebab's Eliminar.
    JSONObject deleted = describe(movement);
    NeoResponse result = callEndpoint("POST", "delete", null, idBody(movement), mcp);
    if (!isSuccess(result)) {
      return result;
    }
    JSONObject data = new JSONObject();
    data.put("deleted", deleted);
    return NeoResponse.ok(envelope(data));
  }

  // ── gates ──────────────────────────────────────────────────────────────

  /** The movement, if it is one of {@code account}'s and the tenant may read it. */
  private static FIN_FinaccTransaction movementOf(FIN_FinancialAccount account, String id) {
    FIN_FinaccTransaction movement = TenantOwnership.loadOwned(FIN_FinaccTransaction.class, id);
    if (movement == null || movement.getAccount() == null
        || !StringUtils.equals(movement.getAccount().getId(), account.getId())) {
      return null;
    }
    return movement;
  }

  /** The Movements tab hides Edit and Process on a movement that belongs to a payment. */
  private static NeoResponse refusePaymentLinked(FIN_FinaccTransaction movement, String verb) {
    if (movement.getFinPayment() == null) {
      return null;
    }
    String owner = Boolean.TRUE.equals(movement.getFinPayment().isReceipt()) ? "receipt"
        : "payment";
    return NeoResponse.error(HttpServletResponse.SC_CONFLICT, "This movement belongs to a "
        + owner + "; it is " + verb + " with the " + owner + ", not here.");
  }

  /** What the New movement form requires before Guardar/Confirmar are enabled. */
  private static NeoResponse requireMovementShape(String trxType, BigDecimal amount,
      String glItemId) throws JSONException {
    if (!TRX_DEPOSIT.equals(trxType) && !TRX_WITHDRAWAL.equals(trxType)) {
      return unprocessable("trxType must be 'BPD' (deposit) or 'BPW' (withdrawal).", P_TRX_TYPE);
    }
    if (amount == null || amount.signum() <= 0) {
      return unprocessable("amount must be greater than zero.", P_AMOUNT);
    }
    return requireGlItem(glItemId);
  }

  private static NeoResponse requireGlItem(String glItemId) throws JSONException {
    if (StringUtils.isBlank(glItemId)) {
      return unprocessable("glItemId is required: a manual movement is booked against a G/L item "
          + "(movementGlItems lists them).", P_GL_ITEM_ID);
    }
    return null;
  }

  private static NeoResponse checkDescription(JSONObject params) throws JSONException {
    String description = params.optString(P_DESCRIPTION, "");
    if (description.length() > DESCRIPTION_MAX_LENGTH) {
      return unprocessable("description has " + description.length() + " characters; at most "
          + DESCRIPTION_MAX_LENGTH + " are allowed.", P_DESCRIPTION);
    }
    return null;
  }

  /**
   * Every id the caller sent must be one the tenant can read. The endpoint treats an unreadable
   * id as "none" (ETP-4950), which keeps another tenant's row out but saves the movement without
   * what was asked for; refused here so the caller learns it.
   */
  private static NeoResponse checkReferences(JSONObject params) throws JSONException {
    Map<String, Class<? extends BaseOBObject>> refs = new LinkedHashMap<>();
    refs.put(P_GL_ITEM_ID, GLItem.class);
    refs.put(P_BPARTNER_ID, BusinessPartner.class);
    refs.put(P_PROJECT_ID, Project.class);
    refs.put(P_COSTCENTER_ID, Costcenter.class);
    refs.put(P_PRODUCT_ID, Product.class);
    for (Map.Entry<String, Class<? extends BaseOBObject>> ref : refs.entrySet()) {
      String id = StringUtils.trimToNull(params.optString(ref.getKey(), null));
      if (id != null && TenantOwnership.loadOwned(ref.getValue(), id) == null) {
        return unprocessable(ref.getKey() + " '" + id + "' was not found.", ref.getKey());
      }
    }
    return null;
  }

  // ── bodies and answers ─────────────────────────────────────────────────

  /**
   * The full update body (the endpoint has no partial update): the movement's own values, with
   * the caller's changes on top — the same shape {@code buildDimensionUpdatePayload} sends.
   */
  static JSONObject mergedBody(FIN_FinaccTransaction movement, JSONObject params)
      throws JSONException {
    String trxType = params.optString(P_TRX_TYPE, movement.getTransactionType());
    BigDecimal current = TRX_DEPOSIT.equals(movement.getTransactionType())
        ? movement.getDepositAmount() : movement.getPaymentAmount();
    BigDecimal amount = params.has(P_AMOUNT) ? optAmount(params) : current;
    String date = params.has(P_DATE) ? params.getString(P_DATE)
        : isoDay(movement.getTransactionDate());
    JSONObject body = new JSONObject();
    body.put("id", movement.getId());
    body.put(P_TRX_TYPE, trxType);
    body.put(B_TRX_DATE, date);
    body.put(B_ACCT_DATE, params.has(P_DATE) ? date : isoDay(movement.getDateAcct()));
    body.put(B_DEPOSIT, TRX_DEPOSIT.equals(trxType) ? orZero(amount) : BigDecimal.ZERO);
    body.put(B_PAYMENT, TRX_DEPOSIT.equals(trxType) ? BigDecimal.ZERO : orZero(amount));
    body.put(B_CURRENCY_ID, idOf(movement.getCurrency()));
    body.put(P_DESCRIPTION, params.has(P_DESCRIPTION) ? params.optString(P_DESCRIPTION, "")
        : StringUtils.defaultString(movement.getDescription()));
    putRef(body, params, P_GL_ITEM_ID, movement.getGLItem());
    putRef(body, params, P_BPARTNER_ID, movement.getBusinessPartner());
    putRef(body, params, P_PROJECT_ID, movement.getProject());
    putRef(body, params, P_COSTCENTER_ID, movement.getCostCenter());
    putRef(body, params, P_PRODUCT_ID, movement.getProduct());
    body.put(P_PROCESS, params.optBoolean(P_PROCESS, false));
    return body;
  }

  /** The movement as it now is, under the action's own {@code response.data}. */
  private static NeoResponse answer(NeoResponse result, String movementId) throws JSONException {
    // tenant-ok: the id came from a movement this class already validated, or from the endpoint's
    // own create answer.
    FIN_FinaccTransaction movement = movementId == null ? null
        : OBDal.getInstance().get(FIN_FinaccTransaction.class, movementId);
    if (movement == null) {
      return result;
    }
    JSONObject data = describe(movement);
    return result.getHttpStatus() == HttpServletResponse.SC_CREATED
        ? NeoResponse.createdWithData(data) : NeoResponse.ok(envelope(data));
  }

  /** What an agent needs to know about a movement after acting on it. */
  static JSONObject describe(FIN_FinaccTransaction movement) throws JSONException {
    boolean deposit = TRX_DEPOSIT.equals(movement.getTransactionType());
    JSONObject out = new JSONObject();
    out.put("id", movement.getId());
    out.put("accountId", idOf(movement.getAccount()));
    out.put(P_TRX_TYPE, movement.getTransactionType());
    out.put(P_AMOUNT, orZero(deposit ? movement.getDepositAmount() : movement.getPaymentAmount()));
    out.put(B_DEPOSIT, orZero(movement.getDepositAmount()));
    out.put(B_PAYMENT, orZero(movement.getPaymentAmount()));
    out.put(P_DATE, isoDay(movement.getTransactionDate()));
    out.put(P_DESCRIPTION, StringUtils.defaultString(movement.getDescription()));
    out.put(P_GL_ITEM_ID, idOf(movement.getGLItem()));
    out.put(P_BPARTNER_ID, idOf(movement.getBusinessPartner()));
    out.put("status", movement.getStatus());
    out.put("processed", Boolean.TRUE.equals(movement.isProcessed()));
    out.put("posted", "Y".equals(movement.getPosted()));
    return out;
  }

  private NeoResponse callEndpoint(String method, String action, Map<String, String> query,
      JSONObject body, boolean mcp) {
    Map<String, String> queryParams = new HashMap<>();
    if (query != null) {
      queryParams.putAll(query);
    }
    if (action != null) {
      queryParams.put("action", action);
    }
    return transactionsEndpoint.apply(NeoContext.builder()
        .specName(FinancialAccountTransactionsEndpoint.SPEC)
        .entityName(FinancialAccountTransactionsEndpoint.SPEC)
        .httpMethod(method)
        .queryParams(queryParams)
        .requestBody(body)
        .endpointType(NeoEndpointType.CRUD)
        .mcpOrigin(mcp)
        .obContext(OBContext.getOBContext())
        .build());
  }

  private static JSONObject idBody(FIN_FinaccTransaction movement) throws JSONException {
    return new JSONObject().put("id", movement.getId());
  }

  private static String idIn(NeoResponse result) {
    JSONObject body = result.getBody();
    JSONObject response = body == null ? null : body.optJSONObject("response");
    JSONObject data = response == null ? null : response.optJSONObject("data");
    return data == null ? null : data.optString("id", null);
  }

  private static boolean isSuccess(NeoResponse result) {
    return result != null && result.getHttpStatus() >= 200 && result.getHttpStatus() < 300;
  }

  private static JSONObject envelope(JSONObject data) throws JSONException {
    return new JSONObject().put("response", new JSONObject().put("data", data));
  }

  private static NeoResponse unprocessable(String message, String field) throws JSONException {
    JSONObject error = new JSONObject();
    error.put("message", message);
    error.put("status", NeoActionContract.SC_UNPROCESSABLE);
    error.put("field", field);
    return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE,
        new JSONObject().put("error", error));
  }

  private static void putRef(JSONObject body, JSONObject params, String key, BaseOBObject current)
      throws JSONException {
    String id = params.has(key) ? StringUtils.trimToNull(params.optString(key, null))
        : idOf(current);
    // Blank clears the reference, as the SPA's null does (setOptionalRef).
    body.put(key, id != null ? id : "");
  }

  private static BigDecimal optAmount(JSONObject params) {
    Object raw = params.opt(P_AMOUNT);
    if (raw == null || raw == JSONObject.NULL || StringUtils.isBlank(String.valueOf(raw))) {
      return null;
    }
    try {
      return new BigDecimal(String.valueOf(raw).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String idOf(BaseOBObject object) {
    return object == null ? null : (String) object.getId();
  }

  /** The calendar day of a date-only column, read in the server's zone (it is stored there). */
  static String isoDay(Date date) {
    return date == null ? null
        : Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate()
            .toString();
  }

  @SafeVarargs
  private static <T> T firstNonNull(T... candidates) {
    for (T candidate : candidates) {
      if (candidate != null) {
        return candidate;
      }
    }
    return null;
  }
}
