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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.RemittanceTypeParameter;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-6148 — customization of the {@code remittance} spec's header ({@code REM_Remittance}, the
 * Classic «Remittance» window).
 *
 * <ul>
 *   <li><b>Flag.</b> With {@code sepa-remittances} off every request (CRUD, defaults, selectors,
 *       callouts) answers 403 {@code feature_disabled}.</li>
 *   <li><b>Defaults</b> never prefill an account without «Admite remesas»
 *       ({@link #afterHandle}).</li>
 *   <li><b>Writes.</b> The account must allow remittances ({@code ETGO_RemittanceAccountNotAllowed})
 *       and the type must be a provisioned remittance type whose payment method is actively linked
 *       to that account ({@code ETGO_RemittanceTypeNotAvailable}); a processed remittance cannot be
 *       modified or deleted ({@code ETGO_RemittanceProcessedNotEditable}); the name is required and
 *       at most {@value #NAME_MAX_LENGTH} characters. All 422 except the name checks (400). The
 *       document type ({@code REM_REM}) and the date come from their AD defaults, injected by the
 *       generic create.</li>
 *   <li><b>Selectors</b>, served here rather than through a {@code SelectorContextPolicy}, which
 *       would leak into every financial-account selector of the instance: {@code financialAccount}
 *       offers only accounts with «Admite remesas»; {@code remittanceType} only the provisioned
 *       types linked to the account the form already holds (request parameter
 *       {@value #PARAM_ACCOUNT}, the field's {@code dependsOn.filterKey}). No account, no types.</li>
 *   <li><b>Callouts</b> are answered here and never reach Classic's. The header's only callout,
 *       {@code REM_RemittanceType_FinAccount} on the type, rebuilds the account combo from the
 *       type (the reverse of this window's order, where the type depends on the account) and sets
 *       {@code isdiscount}, which the spec does not expose; and it dereferences the type without a
 *       null check, so clearing the type — which the dependent field does whenever the account
 *       changes — fails inside the callout. {@link #protectedCreateCalloutFields} keeps the create
 *       cascade from touching the account for the same reason. The one answer given is the
 *       <b>name suggestion</b> ({@link RemittanceNameSuggestion}): a change of {@code dueDate} or
 *       {@code remittanceType} on a NEW remittance answers
 *       {@code updates.name = {value, suggested: true}} — «Cobros noviembre 2026» — unless the
 *       form's name is a name the user typed. {@code suggested} tells the SPA to apply it only to a
 *       name field the user never edited; an existing remittance (the form carries an {@code id})
 *       never gets one. Every other callout is answered empty.</li>
 *   <li><b>«Seleccionar desde factura»</b>: declared actions {@code availableDueItems} and
 *       {@code selectDueItems}, {@code syncDueItems} ({@link RemittanceDueItems}); 422 on a
 *       processed remittance.</li>
 *   <li><b>«Procesar»</b>: declared action {@code process} ({@link RemittanceProcessAction}),
 *       Classic's {@code REMRemittanceProcess}; 422 when already processed, without lines, or
 *       refused by REM. <b>«Reactivar»</b>: {@code reactivate}, the same process with action
 *       {@code R}; 422 when not processed or refused by REM.</li>
 *   <li><b>Due date</b>: a new or changed {@code dueDate} earlier than today (server calendar day,
 *       JVM default time zone) is refused with 422 {@value #MSG_DUE_DATE_PAST}; an unchanged past
 *       date of an existing remittance is accepted ({@link #validateDueDate}).</li>
 *   <li><b>Create without a name</b> (REST, and MCP through {@link #resolveServerCreateFields}):
 *       when the body has a {@code dueDate} and no name, the suggestion is used. A name the caller
 *       sends always wins; updates are never touched.</li>
 * </ul>
 */
@NeoExtension(spec = "remittance", entity = "remittance")
public class RemittanceHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(RemittanceHeaderHandler.class);

  static final String FIELD_ACCOUNT = "financialAccount";
  static final String FIELD_TYPE = "remittanceType";
  static final String FIELD_NAME = "name";
  static final String FIELD_DUE_DATE = "dueDate";
  static final String COLUMN_DUE_DATE = "Duedate";
  static final String COLUMN_TYPE_FOR_CALLOUT = "REM_Remittance_Type_ID";
  static final String COLUMN_ACCOUNT = "FIN_Financial_Account_ID";
  static final String COLUMN_TYPE = "REM_Remittance_Type_ID";
  /** Request parameter the type selector reads the chosen account from. */
  static final String PARAM_ACCOUNT = "FIN_Financial_Account_ID";
  static final int NAME_MAX_LENGTH = 60;

  static final String MSG_ACCOUNT_NOT_ALLOWED = "ETGO_RemittanceAccountNotAllowed";
  static final String MSG_TYPE_NOT_AVAILABLE = "ETGO_RemittanceTypeNotAvailable";
  static final String MSG_PROCESSED = "ETGO_RemittanceProcessedNotEditable";
  /** The body key the SPA process dialog posts its collected parameters under. */
  static final String FIELD_VALUES = "fieldValues";
  static final String MSG_NAME_REQUIRED = "ETGO_RemittanceNameRequired";
  static final String MSG_NAME_TOO_LONG = "ETGO_RemittanceNameTooLong";
  /** Catalog-only ({@code neo_hints}), no {@code AD_MESSAGE} row. */
  static final String MSG_DUE_DATE_PAST = "ETGO_RemittanceDueDatePast";

  private static final int SC_UNPROCESSABLE = 422;
  private static final int SELECTOR_DEFAULT_LIMIT = 20;
  private static final int SELECTOR_MAX_LIMIT = 100;

  @Override
  public NeoResponse handle(NeoContext context) {
    // Evaluated at request entry, while the request runs as the account owner.
    if (!isFlagEnabled()) {
      return SepaRemittancesFlag.featureDisabled();
    }
    NeoEndpointType endpoint = context.getEndpointType();
    if (endpoint == NeoEndpointType.CALLOUT) {
      return nameSuggestionCallout();
    }
    if (endpoint == NeoEndpointType.ACTION
        && (RemittanceDueItems.serves(context.getFieldName())
            || RemittanceProcessAction.serves(context.getFieldName()))) {
      return handleDueItemsAction(context);
    }
    OBContext.setAdminMode(true);
    try {
      if (endpoint == NeoEndpointType.SELECTOR) {
        return handleSelector(context);
      }
      if (NeoEndpointTypes.isCrud(context)) {
        return validateWrite(context);
      }
      return null;
    } catch (JSONException e) {
      log.warn("remittance hook: unreadable request body: {}", e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Invalid request body");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * DEFAULTS: never prefill an account that does not allow remittances. The account column has no
   * AD default, so the generic resolver falls back to the first account it finds, which the
   * account selector would not even offer; the default is dropped then, and the type with it,
   * since the type depends on the account.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.DEFAULTS) {
      return null;
    }
    NeoResponse previous = context.getPreviousResult();
    JSONObject defaults = previous != null && previous.getBody() != null
        ? previous.getBody().optJSONObject("defaults")
        : null;
    if (defaults == null || !defaults.has(FIELD_ACCOUNT)) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      String accountId = idOf(defaults.opt(FIELD_ACCOUNT));
      FIN_FinancialAccount account = accountId != null ? loadAccount(accountId) : null;
      if (accountId != null && account != null
          && Boolean.TRUE.equals(account.isETGOAllowRemittance())) {
        return null;
      }
      for (String key : List.of(FIELD_ACCOUNT, FIELD_TYPE)) {
        defaults.remove(key);
        defaults.remove(key + "$_identifier");
      }
      return NeoResponse.ok(previous.getBody());
    } catch (RuntimeException e) {
      log.warn("remittance defaults: could not check the default account: {}", e.getMessage());
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * MCP create: fills the suggested name before MCP's mandatory pre-check, which runs before
   * {@link #handle}. Idempotent; never overwrites a sent name; never throws.
   */
  @Override
  public void resolveServerCreateFields(NeoContext context) {
    JSONObject body = context != null ? context.getRequestBody() : null;
    if (body == null || !isFlagEnabled()) {
      return;
    }
    OBContext.setAdminMode(true);
    try {
      fillSuggestedName(body);
    } catch (RuntimeException | JSONException e) {
      log.debug("remittance create: no name suggested: {}", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The «Seleccionar desde factura» actions ({@link RemittanceDueItems}): {@code availableDueItems}
   * (read), {@code selectDueItems} and {@code syncDueItems} (write, POST only). The record id is
   * the remittance.
   */
  @Override
  public java.util.Map<String, NeoActionContract> actionContracts() {
    java.util.Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    RemittanceDueItems.addContracts(contracts);
    RemittanceProcessAction.addContract(contracts);
    return contracts;
  }

  @Override
  public boolean servesActions() {
    return true;
  }

  /**
   * Runs a due-items action: 404 for an unknown remittance, 422 on a processed one, 405 for a
   * {@code selectDueItems} that is not a POST, the contract's 422 for a malformed call. Read
   * parameters come from the body (MCP, POST) or the query string (REST GET).
   */
  NeoResponse handleDueItemsAction(NeoContext context) {
    String action = context.getFieldName();
    boolean post = "POST".equals(context.getHttpMethod());
    boolean write = RemittanceDueItems.isWrite(action)
        || RemittanceProcessAction.serves(action);
    if (write && !post) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          action + " only supports POST");
    }
    OBContext.setAdminMode(true);
    try {
      JSONObject params = actionParams(action, context.getRequestBody() != null
          ? context.getRequestBody() : RemittanceDueItems.paramsFromQuery(queryParams(context)));
      NeoResponse invalid = NeoActionContract.validate(actionContracts(), action, params);
      if (invalid != null) {
        return invalid;
      }
      Remittance remittance = loadRemittance(context.getRecordId());
      if (remittance == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, "Remittance not found");
      }
      if (RemittanceProcessAction.ACTION.equals(action)) {
        return processRemittance(remittance, params);
      }
      if (RemittanceProcessAction.ACTION_REACTIVATE.equals(action)) {
        return reactivateRemittance(remittance);
      }
      if (Boolean.TRUE.equals(remittance.isProcessed())) {
        return NeoMessageText.error(SC_UNPROCESSABLE, MSG_PROCESSED);
      }
      if (RemittanceDueItems.ACTION_SYNC.equals(action)) {
        return syncDueItems(remittance, params);
      }
      return RemittanceDueItems.ACTION_AVAILABLE.equals(action)
          ? availableDueItems(remittance, params)
          : selectDueItems(remittance, params);
    } catch (Exception e) {
      log.error("remittance action {} failed on {}", action, context.getRecordId(), e);
      // syncDueItems deletes lines before the Classic run: an unexpected failure after that must
      // not leave the deletes pending in the session (unless a batch owns the transaction).
      NeoErrorResponses.rollbackUnlessBatchOwned();
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not run " + action + ". Please check logs for details.");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The parameters of a call, read where its contract says they travel: {@code process} and
   * {@code reactivate} are pressed from the SPA's process dialog, which posts
   * {@code {"fieldValues": {...}}}, and {@code etendo_action} wraps the agent's flat parameters
   * the same way for those contracts. A flat body is still accepted for them.
   */
  JSONObject actionParams(String action, JSONObject body) {
    NeoActionContract contract = action != null ? actionContracts().get(action) : null;
    if (contract == null || !contract.isFieldValuesBody()) {
      return body;
    }
    JSONObject fieldValues = body.optJSONObject(FIELD_VALUES);
    return fieldValues != null ? fieldValues : body;
  }

  /** Seam over {@link RemittanceProcessAction#process}. */
  NeoResponse processRemittance(Remittance remittance, JSONObject params) throws JSONException {
    return RemittanceProcessAction.process(remittance, params);
  }

  /** Seam over {@link RemittanceProcessAction#reactivate}. */
  NeoResponse reactivateRemittance(Remittance remittance) throws JSONException {
    return RemittanceProcessAction.reactivate(remittance);
  }

  /** Seam over {@link RemittanceDueItems#available}. */
  NeoResponse availableDueItems(Remittance remittance, JSONObject params) throws JSONException {
    return RemittanceDueItems.available(remittance, params);
  }

  /** Seam over {@link RemittanceDueItems#select}. */
  NeoResponse selectDueItems(Remittance remittance, JSONObject params) throws JSONException {
    return RemittanceDueItems.select(remittance, params);
  }

  /** Seam over {@link RemittanceDueItems#sync}. */
  NeoResponse syncDueItems(Remittance remittance, JSONObject params) throws JSONException {
    return RemittanceDueItems.sync(remittance, params);
  }

  private java.util.Map<String, String> queryParams(NeoContext context) {
    // An action context may carry an empty map rather than null; only a non-empty one is the
    // query string, otherwise read the servlet request.
    if (context.getQueryParams() != null && !context.getQueryParams().isEmpty()) {
      return context.getQueryParams();
    }
    java.util.Map<String, String> params = new java.util.HashMap<>();
    HttpServletRequest request = currentRequest();
    if (request != null) {
      for (String key : List.of(RemittanceDueItems.P_SEARCH, RemittanceDueItems.P_PAGE,
          RemittanceDueItems.P_PAGE_SIZE)) {
        String value = request.getParameter(key);
        if (value != null) {
          params.put(key, value);
        }
      }
    }
    return params;
  }

  @Override
  public Set<String> protectedCreateCalloutFields(NeoContext context) {
    return Set.of(FIELD_ACCOUNT);
  }

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  NeoResponse validateWrite(NeoContext context) throws JSONException {
    String method = context.getHttpMethod();
    boolean create = "POST".equals(method);
    boolean update = "PUT".equals(method) || "PATCH".equals(method);
    boolean delete = "DELETE".equals(method);
    if (!create && !update && !delete) {
      return null;
    }
    Remittance stored = create ? null : loadRemittance(context.getRecordId());
    if (!create && stored == null) {
      return null; // the generic CRUD answers the missing record
    }
    if (stored != null && Boolean.TRUE.equals(stored.isProcessed())) {
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_PROCESSED);
    }
    if (delete) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    if (create) {
      fillSuggestedName(body);
    }
    NeoResponse nameError = validateName(body, create);
    if (nameError != null) {
      return nameError;
    }
    NeoResponse dueDateError = validateDueDate(body, stored);
    if (dueDateError != null) {
      return dueDateError;
    }
    if (!create && !body.has(FIELD_ACCOUNT) && !body.has(FIELD_TYPE)) {
      return null;
    }
    String accountId = body.has(FIELD_ACCOUNT) ? idOf(body.opt(FIELD_ACCOUNT))
        : idOf(stored.getFinancialAccount());
    String typeId = body.has(FIELD_TYPE) ? idOf(body.opt(FIELD_TYPE))
        : idOf(stored.getRemittanceType());
    if (accountId == null || typeId == null) {
      return null; // the generic mandatory check names the missing field
    }
    FIN_FinancialAccount account = loadAccount(accountId);
    if (account == null) {
      return null; // unknown reference: the generic write preflight answers it
    }
    if (!Boolean.TRUE.equals(account.isETGOAllowRemittance())) {
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_ACCOUNT_NOT_ALLOWED);
    }
    RemittanceType type = loadType(typeId);
    if (type == null) {
      return null;
    }
    if (!isTypeAvailable(account, type)) {
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_TYPE_NOT_AVAILABLE);
    }
    return null;
  }

  /**
   * A new or changed due date (the charge / credit date) cannot be earlier than today, the
   * server's calendar day in the JVM default time zone ({@link #today()}). Checked only when the
   * body carries {@code dueDate} and, on update, only when it differs from the stored day, so a
   * remittance whose stored date is already in the past stays editable in its other fields. A
   * create without {@code dueDate} gets the AD default (today). Processed remittances never get
   * here. An unparseable value is left to the generic write, which refuses it.
   *
   * <p>"Today" is the server's {@code LocalDate} (JVM zone) while the SPA's date picker uses the
   * browser's, so a client west of the server can pick its own today and still get the 422 in the
   * hours around midnight. Accepted: SEPA remittances are EU-only, where the offset is small.</p>
   */
  NeoResponse validateDueDate(JSONObject body, Remittance stored) {
    if (!body.has(FIELD_DUE_DATE)) {
      return null;
    }
    LocalDate requested = RemittanceNameSuggestion.parseDay(stringOf(body.opt(FIELD_DUE_DATE)));
    if (requested == null) {
      return null;
    }
    if (stored != null && requested.equals(toLocalDate(stored.getDueDate()))) {
      return null;
    }
    if (requested.isBefore(today())) {
      return NeoMessageText.error(SC_UNPROCESSABLE, MSG_DUE_DATE_PAST);
    }
    return null;
  }

  private static LocalDate toLocalDate(java.util.Date date) {
    if (date == null) {
      return null;
    }
    if (date instanceof java.sql.Date) {
      return ((java.sql.Date) date).toLocalDate();
    }
    return date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
  }

  private static NeoResponse validateName(JSONObject body, boolean create) {
    if (!create && !body.has(FIELD_NAME)) {
      return null;
    }
    Object raw = body.opt(FIELD_NAME);
    String name = raw == null || JSONObject.NULL.equals(raw) ? "" : raw.toString().trim();
    if (name.isEmpty()) {
      return NeoMessageText.error(HttpServletResponse.SC_BAD_REQUEST, MSG_NAME_REQUIRED);
    }
    if (name.length() > NAME_MAX_LENGTH) {
      return NeoMessageText.error(HttpServletResponse.SC_BAD_REQUEST, MSG_NAME_TOO_LONG,
          NAME_MAX_LENGTH);
    }
    return null;
  }

  boolean isTypeAvailable(FIN_FinancialAccount account, RemittanceType type) {
    return RemittanceSepaProvisioning.isProvisionedType(type)
        && RemittanceSepaProvisioning.hasActiveLink(account, type.getPaymentMethod());
  }

  // ---------------------------------------------------------------------------
  // Selectors
  // ---------------------------------------------------------------------------

  private NeoResponse handleSelector(NeoContext context) throws JSONException {
    String field = context.getFieldName();
    if (matches(field, FIELD_ACCOUNT, COLUMN_ACCOUNT)) {
      return accountSelector();
    }
    if (matches(field, FIELD_TYPE, COLUMN_TYPE)) {
      return typeSelector();
    }
    return null;
  }

  private NeoResponse accountSelector() throws JSONException {
    SelectorPage page = SelectorPage.fromRequest(currentRequest());
    String where = " from " + FIN_FinancialAccount.ENTITY_NAME + " as a"
        + " where a.client.id = :client and a.active = true and a.eTGOAllowRemittance = true"
        + " and a.organization.id in (:orgs)"
        + (page.search != null ? " and lower(a.name) like :search" : "");
    org.hibernate.query.Query<Long> count = OBDal.getInstance().getSession()
        .createQuery("select count(a.id)" + where, Long.class);
    org.hibernate.query.Query<FIN_FinancialAccount> rows = OBDal.getInstance().getSession()
        .createQuery("select a" + where + " order by a.name, a.id", FIN_FinancialAccount.class);
    bindCommon(count, page);
    bindCommon(rows, page);
    List<FIN_FinancialAccount> accounts =
        rows.setFirstResult(page.offset).setMaxResults(page.limit).list();
    JSONArray items = new JSONArray();
    for (FIN_FinancialAccount account : accounts) {
      items.put(item(account.getId(), account.getIdentifier()));
    }
    return page.response(items, count.uniqueResult());
  }

  private NeoResponse typeSelector() throws JSONException {
    HttpServletRequest request = currentRequest();
    SelectorPage page = SelectorPage.fromRequest(request);
    String accountId = findParamCaseInsensitive(request, PARAM_ACCOUNT);
    if (accountId == null) {
      return page.response(new JSONArray(), 0L);
    }
    String where = " from " + RemittanceType.ENTITY_NAME + " as t"
        + " where t.client.id = :client and t.active = true"
        + " and exists (select 1 from " + RemittanceTypeParameter.ENTITY_NAME + " as p"
        + "   where p.remittanceType.id = t.id and p.name = :marker)"
        + " and exists (select 1 from " + FinAccPaymentMethod.ENTITY_NAME + " as l"
        + "   where l.account.id = :account and l.paymentMethod.id = t.paymentMethod.id"
        + "   and l.active = true)"
        + (page.search != null ? " and lower(t.name) like :search" : "");
    org.hibernate.query.Query<Long> count = OBDal.getInstance().getSession()
        .createQuery("select count(t.id)" + where, Long.class);
    org.hibernate.query.Query<RemittanceType> rows = OBDal.getInstance().getSession()
        .createQuery("select t" + where + " order by t.name, t.id", RemittanceType.class);
    for (org.hibernate.query.Query<?> query : List.of(count, rows)) {
      query.setParameter("client", currentClientId());
      query.setParameter("marker", RemittanceSepaProvisioning.PARAM_SCHEME);
      query.setParameter("account", accountId);
      if (page.search != null) {
        query.setParameter("search", page.likePattern());
      }
    }
    List<RemittanceType> types = rows.setFirstResult(page.offset).setMaxResults(page.limit).list();
    JSONArray items = new JSONArray();
    for (RemittanceType type : types) {
      items.put(item(type.getId(), type.getIdentifier()));
    }
    return page.response(items, count.uniqueResult());
  }

  private void bindCommon(org.hibernate.query.Query<?> query, SelectorPage page) {
    query.setParameter("client", currentClientId());
    query.setParameterList("orgs",
        List.of(OBContext.getOBContext().getReadableOrganizations()));
    if (page.search != null) {
      query.setParameter("search", page.likePattern());
    }
  }

  private static JSONObject item(String id, String label) throws JSONException {
    JSONObject item = new JSONObject();
    item.put("id", id);
    item.put("label", label);
    return item;
  }

  private static boolean matches(String field, String key, String column) {
    return key.equalsIgnoreCase(field) || column.equalsIgnoreCase(field);
  }

  private static String findParamCaseInsensitive(HttpServletRequest request, String name) {
    if (request == null || request.getParameterMap() == null) {
      return null;
    }
    for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
      String[] values = entry.getValue();
      if (name.equalsIgnoreCase(entry.getKey()) && values != null && values.length > 0) {
        String value = StringUtils.trimToNull(values[0]);
        if (value != null) {
          return value;
        }
      }
    }
    return null;
  }

  /** Paging and search of a selector request ({@code q}, {@code limit}, {@code offset}). */
  private static final class SelectorPage {
    private final String search;
    private final int limit;
    private final int offset;

    private SelectorPage(String search, int limit, int offset) {
      this.search = search;
      this.limit = limit;
      this.offset = offset;
    }

    static SelectorPage fromRequest(HttpServletRequest request) {
      String search = request != null ? StringUtils.trimToNull(request.getParameter("q")) : null;
      return new SelectorPage(search,
          intParam(request, "limit", SELECTOR_DEFAULT_LIMIT, 1, SELECTOR_MAX_LIMIT),
          intParam(request, "offset", 0, 0, Integer.MAX_VALUE));
    }

    String likePattern() {
      return "%" + search.toLowerCase(java.util.Locale.ROOT) + "%";
    }

    NeoResponse response(JSONArray items, Long total) throws JSONException {
      long totalCount = total != null ? total : 0L;
      JSONObject result = new JSONObject();
      result.put("items", items);
      result.put("columns", new JSONArray());
      result.put("totalCount", totalCount);
      result.put("hasMore", offset + (long) limit < totalCount);
      return NeoResponse.ok(result);
    }

    private static int intParam(HttpServletRequest request, String name, int fallback, int min,
        int max) {
      String raw = request != null ? StringUtils.trimToNull(request.getParameter(name)) : null;
      if (raw == null) {
        return fallback;
      }
      try {
        return Math.min(Math.max(Integer.parseInt(raw), min), max);
      } catch (NumberFormatException e) {
        return fallback;
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Callouts
  // ---------------------------------------------------------------------------

  /** Create: when the body has a due date and no name, use the suggestion. */
  void fillSuggestedName(JSONObject body) throws JSONException {
    Object raw = body.opt(FIELD_NAME);
    if (raw != null && !JSONObject.NULL.equals(raw) && StringUtils.isNotBlank(raw.toString())) {
      return;
    }
    LocalDate dueDate = RemittanceNameSuggestion.parseDay(stringOf(body.opt(FIELD_DUE_DATE)));
    if (dueDate == null) {
      return;
    }
    body.put(FIELD_NAME, suggestion(dueDate, idOf(body.opt(FIELD_TYPE))));
  }

  /**
   * The answer to an interactive callout: the name suggestion when {@code dueDate} or
   * {@code remittanceType} changed on a new remittance whose name is blank or still a suggestion;
   * the empty answer otherwise. Total: any failure answers empty.
   */
  NeoResponse nameSuggestionCallout() {
    try {
      JSONObject request = readCalloutBody();
      if (request == null) {
        return emptyCalloutResult();
      }
      String field = request.optString("field", "");
      boolean dueDateChanged = matches(field, FIELD_DUE_DATE, COLUMN_DUE_DATE);
      boolean typeChanged = matches(field, FIELD_TYPE, COLUMN_TYPE_FOR_CALLOUT);
      if (!dueDateChanged && !typeChanged) {
        return emptyCalloutResult();
      }
      JSONObject form = request.optJSONObject("formState");
      if (form == null) {
        form = new JSONObject();
      }
      if (StringUtils.isNotBlank(idOf(form.opt("id")))) {
        return emptyCalloutResult(); // an existing remittance keeps its name
      }
      String currentName = stringOf(form.opt(FIELD_NAME));
      if (StringUtils.isNotBlank(currentName)
          && !RemittanceNameSuggestion.isSuggestion(currentName)) {
        return emptyCalloutResult(); // a name the user typed
      }
      Object value = request.opt("value");
      LocalDate dueDate = RemittanceNameSuggestion.parseDay(
          stringOf(dueDateChanged ? value : form.opt(FIELD_DUE_DATE)));
      if (dueDate == null) {
        return emptyCalloutResult();
      }
      String typeId = idOf(typeChanged ? value : form.opt(FIELD_TYPE));
      String name;
      OBContext.setAdminMode(true);
      try {
        name = suggestion(dueDate, typeId);
      } finally {
        OBContext.restorePreviousMode();
      }
      if (name.equals(StringUtils.trimToEmpty(currentName))) {
        return emptyCalloutResult();
      }
      JSONObject update = new JSONObject();
      update.put("value", name);
      update.put("suggested", true);
      JSONObject response = emptyCalloutResult().getBody();
      response.getJSONObject("updates").put(FIELD_NAME, update);
      return NeoResponse.ok(response);
    } catch (Exception e) {
      log.debug("remittance callout: no name suggested: {}", e.getMessage());
      return emptyCalloutResult();
    }
  }

  private String suggestion(LocalDate dueDate, String typeId) {
    RemittanceType type = typeId != null ? loadType(typeId) : null;
    return RemittanceNameSuggestion.suggest(dueDate, RemittanceNameSuggestion.kindOf(type),
        currentLanguage());
  }

  /**
   * The callout request body. The CALLOUT pre-hook context carries none: the endpoint reads the
   * stream itself, later, in the default action. This handler answers every callout itself, so the
   * default action never runs and reading the stream here is safe.
   */
  JSONObject readCalloutBody() throws java.io.IOException {
    HttpServletRequest request = currentRequest();
    if (request == null) {
      return null;
    }
    String raw = NeoRequestBodyParser.readRequestBody(request);
    if (StringUtils.isBlank(raw)) {
      return null;
    }
    try {
      return NeoRequestBodyParser.parseJsonObjectSafely(raw);
    } catch (Exception e) {
      return null;
    }
  }

  private static String stringOf(Object value) {
    return value == null || JSONObject.NULL.equals(value) ? null : value.toString();
  }

  private static NeoResponse emptyCalloutResult() {
    try {
      JSONObject body = new JSONObject();
      body.put("updates", new JSONObject());
      body.put("combos", new JSONObject());
      body.put("messages", new JSONArray());
      return NeoResponse.ok(body);
    } catch (JSONException e) {
      return NeoResponse.ok(new JSONObject());
    }
  }

  // ---------------------------------------------------------------------------
  // Seams
  // ---------------------------------------------------------------------------

  boolean isFlagEnabled() {
    return SepaRemittancesFlag.isEnabled();
  }

  Remittance loadRemittance(String id) {
    return StringUtils.isBlank(id) ? null : TenantOwnership.loadOwned(Remittance.class, id);
  }

  FIN_FinancialAccount loadAccount(String id) {
    return TenantOwnership.loadOwned(FIN_FinancialAccount.class, id);
  }

  RemittanceType loadType(String id) {
    return TenantOwnership.loadOwned(RemittanceType.class, id);
  }

  /** Today in the server's default time zone. A seam, so tests can pin the day. */
  LocalDate today() {
    return LocalDate.now();
  }

  String currentLanguage() {
    return com.etendoerp.go.schemaforge.util.NeoLanguage.currentCode();
  }

  String currentClientId() {
    return OBContext.getOBContext().getCurrentClient().getId();
  }

  HttpServletRequest currentRequest() {
    return RequestContext.get() != null ? RequestContext.get().getRequest() : null;
  }

  /** The id of a body or entity reference: a plain id, a {@code {id}} object, or an entity. */
  static String idOf(Object value) {
    if (value == null || JSONObject.NULL.equals(value)) {
      return null;
    }
    if (value instanceof org.openbravo.base.structure.BaseOBObject) {
      return (String) ((org.openbravo.base.structure.BaseOBObject) value).getId();
    }
    if (value instanceof JSONObject) {
      return StringUtils.trimToNull(((JSONObject) value).optString("id", null));
    }
    return StringUtils.trimToNull(value.toString());
  }
}
