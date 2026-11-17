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
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceType;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-6148 — customization of the {@code remittance} spec's header ({@code REM_Remittance}, the
 * Classic «Remittance» window).
 *
 * <ul>
 *   <li><b>Flag.</b> With {@code sepa-remittances} off every request (CRUD, defaults, selectors,
 *       callouts) answers 403 {@code feature_disabled}.</li>
 *   <li><b>Remittance account</b> ({@link #accountRefusal}): a Bank account
 *       ({@value #MSG_ACCOUNT_NOT_BANK}) with a valid IBAN for its country
 *       ({@value #MSG_ACCOUNT_IBAN_REQUIRED}, the same pair check as the account form,
 *       {@link FinancialAccountCountrySupport#validateIbanCountryPair}) that has the remittance
 *       payment methods linked ({@value #MSG_ACCOUNT_NOT_ALLOWED}). A Bank account gets those links
 *       when it is created with the {@code sepa-remittances} flag on
 *       ({@link FinancialAccountRemittanceSupport}); an older one has none (no backfill), and would
 *       only fail later, at «Procesar», in Classic's {@code checkPMSetup}, so it is neither offered
 *       nor accepted.</li>
 *   <li><b>Defaults</b> never prefill an account that is not a remittance account, an account
 *       without a valid IBAN included ({@link #afterHandle}).</li>
 *   <li><b>Writes.</b> The account must be a remittance account (above, 422) and the type must be a
 *       provisioned remittance type whose payment method is actively linked to that account
 *       ({@code ETGO_RemittanceTypeNotAvailable}); a processed remittance cannot be
 *       modified or deleted ({@code ETGO_RemittanceProcessedNotEditable}); the name is required and
 *       at most {@value #NAME_MAX_LENGTH} characters. All 422 except the name checks (400). The
 *       document type ({@code REM_REM}) and the date come from their AD defaults, injected by the
 *       generic create.</li>
 *   <li><b>Selectors</b>, served here rather than through a {@code SelectorContextPolicy}, which
 *       would leak into every financial-account selector of the instance: {@code financialAccount}
 *       offers the Bank accounts with the remittance payment methods, with or without a valid
 *       IBAN, each item marked {@code ibanValid} / {@code iban} ({@link #eligibilityRefusal});
 *       {@code remittanceType} only the provisioned types linked to the account the form already holds (request parameter
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
 *   <li><b>«Datos para el fichero SEPA»</b>: declared read action {@code sepaFileData}
 *       ({@link RemittanceSepaFileData}, {@link RemittanceSepaFileDataAction}) — the account's IBAN and the
 *       computed creditor identifier, for a saved or a new remittance. The IBAN the user types
 *       there is saved on the account by the SPA through the financial-account spec, never
 *       here.</li>
 *   <li><b>«Seleccionar desde factura»</b>: declared actions {@code availableDueItems} and
 *       {@code selectDueItems}, {@code syncDueItems} ({@link RemittanceDueItems}); 422 on a
 *       processed remittance.</li>
 *   <li><b>«Procesar»</b>: declared action {@code process} ({@link RemittanceProcessAction}),
 *       Classic's {@code REMRemittanceProcess}; 422 when already processed, without lines, or
 *       refused by REM. <b>«Reactivar»</b>: {@code reactivate}, the same process with action
 *       {@code R}; 422 when not processed or refused by REM.</li>
 *   <li><b>Due date</b>: a new or changed {@code dueDate} earlier than today (server calendar day,
 *       JVM default time zone) is refused with 422 {@value #MSG_DUE_DATE_PAST}; an unchanged past
 *       date of an existing remittance is accepted ({@link RemittanceHeaderWrites}).</li>
 *   <li><b>Create without a name</b> (REST, and MCP through {@link #resolveServerCreateFields}):
 *       when the body has a {@code dueDate} and no name, the suggestion is used. A name the caller
 *       sends always wins; updates are never touched.</li>
 * </ul>
 *
 * <p>The handler keeps the hooks and the seams; the work is split by responsibility into
 * {@link RemittanceHeaderWrites} (writes), {@link RemittanceHeaderSelectors} (selectors),
 * {@link RemittanceNameCallout} (callouts and the suggested name),
 * {@link RemittanceSepaFileDataAction} ({@code sepaFileData}) and {@link RemittanceRequestParams}
 * (the query string of an action). Each reads the DAL, the request and the day through the
 * handler's package-private seams, so a test stubs them on the handler as before.
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

  /** A Bank account with a valid IBAN but without the remittance payment methods. */
  static final String MSG_ACCOUNT_NOT_ALLOWED = "ETGO_RemittanceAccountNotAllowed";
  static final String MSG_ACCOUNT_NOT_BANK = "ETGO_RemittanceAccountNotBank";
  static final String MSG_ACCOUNT_IBAN_REQUIRED = "ETGO_RemittanceAccountIbanRequired";
  /** {@code FIN_FINANCIAL_ACCOUNT.TYPE} of a bank account. */
  static final String TYPE_BANK = "B";
  static final String MSG_TYPE_NOT_AVAILABLE = "ETGO_RemittanceTypeNotAvailable";
  static final String MSG_PROCESSED = "ETGO_RemittanceProcessedNotEditable";
  /** The body key the SPA process dialog posts its collected parameters under. */
  static final String FIELD_VALUES = "fieldValues";
  static final String MSG_NAME_REQUIRED = "ETGO_RemittanceNameRequired";
  static final String MSG_NAME_TOO_LONG = "ETGO_RemittanceNameTooLong";
  /** Catalog-only ({@code neo_hints}), no {@code AD_MESSAGE} row. */
  static final String MSG_DUE_DATE_PAST = "ETGO_RemittanceDueDatePast";

  /** The record id the SPA sends for a remittance that is not saved yet. */
  static final String NEW_RECORD_ID = "new";

  private static final int SC_UNPROCESSABLE = 422;

  @Override
  public NeoResponse handle(NeoContext context) {
    // Evaluated at request entry, while the request runs as the account owner.
    if (!isFlagEnabled()) {
      return SepaRemittancesFlag.featureDisabled();
    }
    NeoEndpointType endpoint = context.getEndpointType();
    if (endpoint == NeoEndpointType.CALLOUT) {
      return RemittanceNameCallout.answer(this);
    }
    if (endpoint == NeoEndpointType.ACTION
        && RemittanceSepaFileData.serves(context.getFieldName())) {
      return RemittanceSepaFileDataAction.handle(this, context);
    }
    if (endpoint == NeoEndpointType.ACTION
        && (RemittanceDueItems.serves(context.getFieldName())
            || RemittanceProcessAction.serves(context.getFieldName()))) {
      return handleDueItemsAction(context);
    }
    OBContext.setAdminMode(true);
    try {
      if (endpoint == NeoEndpointType.SELECTOR) {
        return RemittanceHeaderSelectors.handle(this, context.getFieldName());
      }
      if (NeoEndpointTypes.isCrud(context)) {
        return RemittanceHeaderWrites.validate(this, context);
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
   * DEFAULTS: never prefill an account that is not a remittance account. The account column has no
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
      if (accountId != null && account != null && accountRefusal(account) == null) {
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
      RemittanceNameCallout.fillSuggestedName(this, body);
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
  public Map<String, NeoActionContract> actionContracts() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    RemittanceDueItems.addContracts(contracts);
    RemittanceProcessAction.addContract(contracts);
    RemittanceSepaFileData.addContract(contracts);
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
          ? context.getRequestBody()
          : RemittanceDueItems.paramsFromQuery(RemittanceRequestParams.of(context,
              this::currentRequest, RemittanceDueItems.P_SEARCH, RemittanceDueItems.P_PAGE,
              RemittanceDueItems.P_PAGE_SIZE)));
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

  @Override
  public Set<String> protectedCreateCalloutFields(NeoContext context) {
    return Set.of(FIELD_ACCOUNT);
  }

  /**
   * Why {@code account} cannot hold a remittance, or {@code null} when it can: a Bank account
   * ({@value #MSG_ACCOUNT_NOT_BANK}) with a valid IBAN for its country
   * ({@value #MSG_ACCOUNT_IBAN_REQUIRED}) and the remittance payment methods linked
   * ({@value #MSG_ACCOUNT_NOT_ALLOWED}). Checked in that order, cheapest first; the link check is
   * the only query.
   *
   * @param account the financial account
   * @return the {@code AD_MESSAGE} key of the refusal, or {@code null}
   */
  String accountRefusal(FIN_FinancialAccount account) {
    if (!TYPE_BANK.equals(account.getType())) {
      return MSG_ACCOUNT_NOT_BANK;
    }
    if (!hasValidIban(account)) {
      return MSG_ACCOUNT_IBAN_REQUIRED;
    }
    if (!hasRemittanceMethods(account)) {
      return MSG_ACCOUNT_NOT_ALLOWED;
    }
    return null;
  }

  /**
   * Why {@code account} cannot be chosen for a remittance at all, or {@code null} when it can: the
   * account selector's rule, and the {@code sepaFileData} action's. A Bank account
   * ({@value #MSG_ACCOUNT_NOT_BANK}) with the remittance payment methods linked
   * ({@value #MSG_ACCOUNT_NOT_ALLOWED}); the IBAN is NOT required here, since the user can give the
   * account one from the remittance header. Saving the remittance still needs it
   * ({@link #accountRefusal}).
   *
   * @param account the financial account
   * @return the {@code AD_MESSAGE} key of the refusal, or {@code null}
   */
  String eligibilityRefusal(FIN_FinancialAccount account) {
    if (!TYPE_BANK.equals(account.getType())) {
      return MSG_ACCOUNT_NOT_BANK;
    }
    return hasRemittanceMethods(account) ? null : MSG_ACCOUNT_NOT_ALLOWED;
  }

  /**
   * Whether the stored IBAN is valid for the stored country: the account form's own pair check
   * ({@link FinancialAccountCountrySupport#validateIbanCountryPair}), so an account the form would
   * refuse to save is never used for a SEPA file. No country is invalid here.
   */
  static boolean hasValidIban(FIN_FinancialAccount account) {
    String iban = FinancialAccountCountrySupport.normalizeIban(account.getIBAN());
    return !iban.isEmpty() && account.getCountry() != null
        && FinancialAccountCountrySupport.validateIbanCountryPair(iban, account.getCountry()) == null;
  }

  boolean isTypeAvailable(FIN_FinancialAccount account, RemittanceType type) {
    return RemittanceSepaProvisioning.isProvisionedType(type)
        && RemittanceSepaProvisioning.hasActiveLink(account, type.getPaymentMethod());
  }

  // ---------------------------------------------------------------------------
  // Callouts
  // ---------------------------------------------------------------------------

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

  boolean hasRemittanceMethods(FIN_FinancialAccount account) {
    return RemittanceSepaProvisioning.hasRemittanceMethodLink(account);
  }

  RemittanceType loadType(String id) {
    return TenantOwnership.loadOwned(RemittanceType.class, id);
  }

  /** The client's stored creditor business code ({@link RemittanceSepaProvisioning}), raw. */
  String creditorBusinessCode(FIN_FinancialAccount account) {
    return RemittanceSepaProvisioning.creditorBusinessCode(account.getClient());
  }

  Organization loadOrganization(String id) {
    return TenantOwnership.loadOwned(Organization.class, id);
  }

  /** The organization's own information (no parent fallback), as Classic's SEPA file reads it. */
  OrganizationInformation loadOrganizationInfo(Organization organization) {
    List<OrganizationInformation> infos = organization.getOrganizationInformationList();
    return infos == null || infos.isEmpty() ? null : infos.get(0);
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
