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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchemaDefault;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchemaElement;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchemaGL;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.openbravo.model.financialmgmt.calendar.Calendar;

/**
 * Aggregate handler for the custom General Ledger Configuration window.
 *
 * <p>The frontend is a hand-written 4-tab custom page but the runtime data is anchored to one
 * organization's active accounting schema. Generic CRUD over the extracted tabs is not enough for
 * that UX because the page needs one org-scoped payload combining:</p>
 *
 * <ul>
 *   <li>the active ledger header ({@link AcctSchema})</li>
 *   <li>its single defaults row ({@link AcctSchemaDefault})</li>
 *   <li>its dimensions rows ({@link AcctSchemaElement})</li>
 *   <li>its single general accounts row ({@link AcctSchemaGL})</li>
 *   <li>org-owned read-only metadata (calendar + organization label)</li>
 * </ul>
 *
 * <p>This handler is attached to the `General` entity via
 * `entities.generalLedgerConfiguration.javaQualifier = "generalLedgerConfigurationHandler"`.
 * It intercepts:</p>
 *
 * <ul>
 *   <li><b>GET list</b> (`/general-ledger-configuration/General?...`) and returns exactly one
 *       aggregate row in `response.data[0]`.</li>
 *   <li><b>POST list</b> (`/general-ledger-configuration/General?...`) and persists the backed
 *       sections (`general`, `defaults`, `dimensions`) in one transaction, then returns the
 *       refreshed aggregate row.</li>
 * </ul>
 *
 * <p>`Documentos` intentionally remains read-only and currently unbacked in this dataset. The
 * response carries a note so the frontend can surface that limitation explicitly for PM review.</p>
 */
@Named("generalLedgerConfigurationHandler")
public class GeneralLedgerConfigurationHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(GeneralLedgerConfigurationHandler.class);

  private static final String SPEC = "general-ledger-configuration";
  private static final String METHOD_GET = "GET";
  private static final String METHOD_POST = "POST";

  private static final String PARAM_SELECTED_ORG_ID = "selectedOrgId";

  private static final String FIELD_ACCRUAL = "accrual";
  private static final String FIELD_DESCRIPTION = "description";
  private static final String FIELD_CURRENCY = "currency";
  private static final String FIELD_AUTOMATIC_PERIOD_CONTROL = "automaticPeriodControl";
  private static final String FIELD_ACTIVE = "active";

  private static final String FIELD_SUSPENSE_BALANCING_USE = "suspenseBalancingUse";
  private static final String FIELD_SUSPENSE_BALANCING = "suspenseBalancing";
  private static final String FIELD_SUSPENSE_ERROR_USE = "suspenseErrorUse";
  private static final String FIELD_CURRENCY_BALANCING_USE = "currencyBalancingUse";
  // These four mirror the raw AD property name (see AcctSchemaGL / schema-raw.json) rather than a
  // cosmetic apiKey, matching the convention already used by DEFAULT_FIELD_MAPPINGS.
  private static final String FIELD_CURRENCY_BALANCING_ACCOUNT = "currencyBalancingAcct";
  private static final String FIELD_RETAINED_EARNING = "retainedEarning";
  private static final String FIELD_INCOME_SUMMARY = "incomeSummary";
  private static final String FIELD_CFS_ORDER_ACCOUNT = "cFSOrderAccount";
  private static final String FIELD_REVERSE_PERMANENT_BALANCES = "createClosing";

  private static final class DefaultFieldMapping {
    final String apiKey;
    final String property;

    DefaultFieldMapping(String apiKey, String property) {
      this.apiKey = apiKey;
      this.property = property;
    }
  }

  private static final List<DefaultFieldMapping> DEFAULT_FIELD_MAPPINGS = Arrays.asList(
      new DefaultFieldMapping("bankAsset", AcctSchemaDefault.PROPERTY_BANKASSET),
      new DefaultFieldMapping("bankInTransit", AcctSchemaDefault.PROPERTY_BANKINTRANSIT),
      new DefaultFieldMapping("bankExpense", AcctSchemaDefault.PROPERTY_BANKEXPENSE),
      new DefaultFieldMapping("bankRevaluationGain", AcctSchemaDefault.PROPERTY_BANKREVALUATIONGAIN),
      new DefaultFieldMapping("bankRevaluationLoss", AcctSchemaDefault.PROPERTY_BANKREVALUATIONLOSS),
      new DefaultFieldMapping("cashBookAsset", AcctSchemaDefault.PROPERTY_CASHBOOKASSET),
      new DefaultFieldMapping("cashBookDifferences", AcctSchemaDefault.PROPERTY_CASHBOOKDIFFERENCES),
      new DefaultFieldMapping("cashTransfer", AcctSchemaDefault.PROPERTY_CASHTRANSFER),
      new DefaultFieldMapping("customerReceivablesNo", AcctSchemaDefault.PROPERTY_CUSTOMERRECEIVABLESNO),
      new DefaultFieldMapping("customerPrepayment", AcctSchemaDefault.PROPERTY_CUSTOMERPREPAYMENT),
      new DefaultFieldMapping("vendorLiability", AcctSchemaDefault.PROPERTY_VENDORLIABILITY),
      new DefaultFieldMapping("vendorPrepayment", AcctSchemaDefault.PROPERTY_VENDORPREPAYMENT),
      new DefaultFieldMapping("writeoff", AcctSchemaDefault.PROPERTY_WRITEOFF),
      new DefaultFieldMapping("writeoffRevenue", AcctSchemaDefault.PROPERTY_WRITEOFFREVENUE),
      new DefaultFieldMapping("nonInvoicedReceipts", AcctSchemaDefault.PROPERTY_NONINVOICEDRECEIPTS),
      new DefaultFieldMapping("doubtfulDebtAccount", AcctSchemaDefault.PROPERTY_DOUBTFULDEBTACCOUNT),
      new DefaultFieldMapping("badDebtExpenseAccount", AcctSchemaDefault.PROPERTY_BADDEBTEXPENSEACCOUNT),
      new DefaultFieldMapping("badDebtRevenueAccount", AcctSchemaDefault.PROPERTY_BADDEBTREVENUEACCOUNT),
      new DefaultFieldMapping("allowanceForDoubtfulDebtAccount", AcctSchemaDefault.PROPERTY_ALLOWANCEFORDOUBTFULDEBTACCOUNT),
      new DefaultFieldMapping("taxDue", AcctSchemaDefault.PROPERTY_TAXDUE),
      new DefaultFieldMapping("taxCredit", AcctSchemaDefault.PROPERTY_TAXCREDIT),
      new DefaultFieldMapping("taxExpense", AcctSchemaDefault.PROPERTY_TAXEXPENSE),
      new DefaultFieldMapping("tDueTransAcct", AcctSchemaDefault.PROPERTY_DUETRANSACCT),
      new DefaultFieldMapping("tCreditTransAcct", AcctSchemaDefault.PROPERTY_CREDITTRANSACCT),
      new DefaultFieldMapping("fixedAsset", AcctSchemaDefault.PROPERTY_FIXEDASSET),
      new DefaultFieldMapping("productExpense", AcctSchemaDefault.PROPERTY_PRODUCTEXPENSE),
      new DefaultFieldMapping("productDeferredExpense", AcctSchemaDefault.PROPERTY_PRODUCTDEFERREDEXPENSE),
      new DefaultFieldMapping("productRevenue", AcctSchemaDefault.PROPERTY_PRODUCTREVENUE),
      new DefaultFieldMapping("productDeferredRevenue", AcctSchemaDefault.PROPERTY_PRODUCTDEFERREDREVENUE),
      new DefaultFieldMapping("productCOGS", AcctSchemaDefault.PROPERTY_PRODUCTCOGS),
      new DefaultFieldMapping("invoicePriceVariance", AcctSchemaDefault.PROPERTY_INVOICEPRICEVARIANCE),
      new DefaultFieldMapping("productRevenueReturn", AcctSchemaDefault.PROPERTY_PRODUCTREVENUERETURN),
      new DefaultFieldMapping("productCOGSReturn", AcctSchemaDefault.PROPERTY_PRODUCTCOGSRETURN),
      new DefaultFieldMapping("warehouseDifferences", AcctSchemaDefault.PROPERTY_WAREHOUSEDIFFERENCES),
      new DefaultFieldMapping("inventoryRevaluation", AcctSchemaDefault.PROPERTY_INVENTORYREVALUATION),
      new DefaultFieldMapping("workInProgress", AcctSchemaDefault.PROPERTY_WORKINPROGRESS),
      new DefaultFieldMapping("depreciation", AcctSchemaDefault.PROPERTY_DEPRECIATION),
      new DefaultFieldMapping("accumulatedDepreciation", AcctSchemaDefault.PROPERTY_ACCUMULATEDDEPRECIATION),
      new DefaultFieldMapping("disposalGain", AcctSchemaDefault.PROPERTY_DISPOSALGAIN),
      new DefaultFieldMapping("disposalLoss", AcctSchemaDefault.PROPERTY_DISPOSALLOSS),
      new DefaultFieldMapping("projectAsset", AcctSchemaDefault.PROPERTY_PROJECTASSET),
      new DefaultFieldMapping("bankInterestRevenue", AcctSchemaDefault.PROPERTY_BANKINTERESTREVENUE),
      new DefaultFieldMapping("bankInterestExpense", AcctSchemaDefault.PROPERTY_BANKINTERESTEXPENSE),
      new DefaultFieldMapping("bankUnidentifiedReceipts", AcctSchemaDefault.PROPERTY_BANKUNIDENTIFIEDRECEIPTS),
      new DefaultFieldMapping("unallocatedCash", AcctSchemaDefault.PROPERTY_UNALLOCATEDCASH),
      new DefaultFieldMapping("bankSettlementGain", AcctSchemaDefault.PROPERTY_BANKSETTLEMENTGAIN),
      new DefaultFieldMapping("bankSettlementLoss", AcctSchemaDefault.PROPERTY_BANKSETTLEMENTLOSS),
      new DefaultFieldMapping("cashBookExpense", AcctSchemaDefault.PROPERTY_CASHBOOKEXPENSE),
      new DefaultFieldMapping("cashBookReceipt", AcctSchemaDefault.PROPERTY_CASHBOOKRECEIPT),
      new DefaultFieldMapping("paymentSelection", AcctSchemaDefault.PROPERTY_PAYMENTSELECTION));

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!SPEC.equals(context.getSpecName()) || context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    if (!"General".equals(context.getEntityName())) {
      return null;
    }

    try {
      OBContext.setAdminMode(true);
      if (METHOD_GET.equals(context.getHttpMethod())) {
        return handleAggregateGet(context);
      }
      if (METHOD_POST.equals(context.getHttpMethod())) {
        return handleAggregateSave(context);
      }
      return null;
    } catch (OBException e) {
      log.warn("general-ledger-configuration business error: {}", e.getMessage());
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      log.error("general-ledger-configuration aggregate error", e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error");
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private NeoResponse handleAggregateGet(NeoContext context) throws Exception {
    Organization org = resolveTargetOrganization(context.getQueryParams());
    AggregateState state = loadState(org);
    return NeoResponse.ok(wrapSingle(buildAggregateRow(state)));
  }

  private NeoResponse handleAggregateSave(NeoContext context) throws Exception {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST, "Missing request body");
    }

    Organization org = resolveTargetOrganization(mergeOrgQuery(context.getQueryParams(), body));
    AggregateState state = loadState(org);

    JSONObject general = body.optJSONObject("general");
    JSONObject defaults = body.optJSONObject("defaults");
    JSONArray dimensions = body.optJSONArray("dimensions");
    JSONObject generalAccounts = body.optJSONObject("generalAccounts");

    if (general != null) {
      applyGeneralChanges(state, general);
    }
    if (defaults != null) {
      applyDefaultChanges(state, defaults);
    }
    if (dimensions != null) {
      applyDimensionChanges(state, dimensions);
    }
    if (generalAccounts != null) {
      applyGeneralAccountsChanges(state, generalAccounts);
    }

    OBDal.getInstance().save(state.organization);
    OBDal.getInstance().save(state.schema);
    OBDal.getInstance().save(state.defaults);
    OBDal.getInstance().save(state.generalAccounts);
    OBDal.getInstance().flush();

    AggregateState refreshed = loadState(state.organization);
    return NeoResponse.ok(wrapSingle(buildAggregateRow(refreshed)));
  }

  private Organization resolveTargetOrganization(Map<String, String> queryParams) {
    String orgId = queryParams != null ? StringUtils.trimToNull(queryParams.get(PARAM_SELECTED_ORG_ID)) : null;
    if (orgId == null) {
      Organization current = OBContext.getOBContext().getCurrentOrganization();
      if (current == null || StringUtils.isBlank(current.getId()) || "0".equals(current.getId())) {
        throw new OBException("A concrete organization is required for General Ledger Configuration");
      }
      orgId = current.getId();
    }

    Organization org = OBDal.getInstance().get(Organization.class, orgId);
    if (org == null) {
      throw new OBException("Organization not found: " + orgId);
    }
    if (org.getGeneralLedger() == null) {
      throw new OBException("The selected organization has no general ledger configured");
    }
    return org;
  }

  private AggregateState loadState(Organization org) {
    AcctSchema schema = org.getGeneralLedger();
    if (schema == null) {
      throw new OBException("The selected organization has no general ledger configured");
    }

    AcctSchemaDefault defaults = uniqueByProperty(
        AcctSchemaDefault.class,
        AcctSchemaDefault.PROPERTY_ACCOUNTINGSCHEMA,
        schema,
        Order.desc(AcctSchemaDefault.PROPERTY_UPDATED));
    if (defaults == null) {
      throw new OBException("No accounting schema defaults row exists for ledger " + schema.getId());
    }

    @SuppressWarnings("unchecked")
    List<AcctSchemaElement> dimensions = OBDal.getInstance()
        .createCriteria(AcctSchemaElement.class)
        .setFilterOnActive(false)
        .add(Restrictions.eq(AcctSchemaElement.PROPERTY_ACCOUNTINGSCHEMA, schema))
        .addOrder(Order.asc(AcctSchemaElement.PROPERTY_SEQUENCENUMBER))
        .list();

    AcctSchemaGL generalAccounts = uniqueByProperty(
        AcctSchemaGL.class,
        AcctSchemaGL.PROPERTY_ACCOUNTINGSCHEMA,
        schema,
        Order.desc(AcctSchemaGL.PROPERTY_UPDATED));
    if (generalAccounts == null) {
      throw new OBException("No general accounts row exists for ledger " + schema.getId());
    }

    return new AggregateState(org, schema, defaults, dimensions, generalAccounts);
  }

  private <T extends BaseOBObject> T uniqueByProperty(Class<T> entityClass, String property,
      Object value, Order order) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(entityClass);
    criteria.add(Restrictions.eq(property, value));
    if (order != null) {
      criteria.addOrder(order);
    }
    criteria.setMaxResults(1);
    return entityClass.cast(criteria.uniqueResult());
  }

  private JSONObject wrapSingle(JSONObject row) throws JSONException {
    JSONArray data = new JSONArray();
    data.put(row);
    JSONObject response = new JSONObject();
    response.put("data", data);
    response.put("count", 1);
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", response);
    return wrapper;
  }

  private JSONObject buildAggregateRow(AggregateState state) throws JSONException {
    JSONObject row = new JSONObject();
    row.put("general", buildGeneral(state));
    row.put("defaults", buildDefaults(state.defaults));
    row.put("dimensions", buildDimensions(state.dimensions));
    row.put("documents", buildDocumentSeeds());
    row.put("orgInfo", buildOrgInfo(state.organization));
    row.put("catalogs", buildCatalogs(state.schema));
    row.put("generalAccounts", buildGeneralAccounts(state.generalAccounts));
    row.put("meta", buildMeta());
    return row;
  }

  private JSONObject buildGeneral(AggregateState state) throws JSONException {
    JSONObject out = new JSONObject();
    out.put("id", state.schema.getId());
    out.put("name", state.schema.getName());
    out.put("gAAP", state.schema.getGAAP());
    out.put(FIELD_ACCRUAL, bool(state.schema.isAccrual()));
    out.put(FIELD_DESCRIPTION, nullable(state.schema.getDescription()));
    out.put(FIELD_CURRENCY,
        state.schema.getCurrency() != null ? state.schema.getCurrency().getId() : JSONObject.NULL);
    out.put(FIELD_AUTOMATIC_PERIOD_CONTROL, bool(state.schema.isAutomaticPeriodControl()));
    return out;
  }

  private JSONObject buildDefaults(AcctSchemaDefault defaults) throws JSONException {
    JSONObject out = new JSONObject();
    for (DefaultFieldMapping mapping : DEFAULT_FIELD_MAPPINGS) {
      out.put(mapping.apiKey, toCombinationId(defaults.get(mapping.property)));
    }
    return out;
  }

  private JSONObject buildGeneralAccounts(AcctSchemaGL generalAccounts) throws JSONException {
    JSONObject out = new JSONObject();
    out.put("id", generalAccounts.getId());
    out.put(FIELD_SUSPENSE_BALANCING_USE, bool(generalAccounts.isSuspenseBalancingUse()));
    out.put(FIELD_SUSPENSE_BALANCING, toCombinationId(generalAccounts.getSuspenseBalancing()));
    out.put(FIELD_SUSPENSE_ERROR_USE, bool(generalAccounts.isSuspenseErrorUse()));
    out.put(FIELD_CURRENCY_BALANCING_USE, bool(generalAccounts.isCurrencyBalancingUse()));
    out.put(FIELD_CURRENCY_BALANCING_ACCOUNT, toCombinationId(generalAccounts.getCurrencyBalancingAcct()));
    out.put(FIELD_RETAINED_EARNING, toCombinationId(generalAccounts.getRetainedEarning()));
    out.put(FIELD_INCOME_SUMMARY, toCombinationId(generalAccounts.getIncomeSummary()));
    out.put(FIELD_CFS_ORDER_ACCOUNT, toCombinationId(generalAccounts.getCFSOrderAccount()));
    out.put(FIELD_ACTIVE, bool(generalAccounts.isActive()));
    out.put(FIELD_REVERSE_PERMANENT_BALANCES, bool(generalAccounts.isCreateClosing()));
    return out;
  }

  private JSONArray buildDimensions(List<AcctSchemaElement> dimensions) throws JSONException {
    JSONArray out = new JSONArray();
    for (AcctSchemaElement row : dimensions) {
      JSONObject item = new JSONObject();
      item.put("id", row.getId());
      item.put("label", nullable(row.getName()));
      item.put(FIELD_ACTIVE, bool(row.isActive()));
      item.put("mandatory", bool(row.isMandatory()));
      item.put("caption", buildDimensionCaption(row));
      item.put("type", nullable(row.getType()));
      out.put(item);
    }
    return out;
  }

  private JSONArray buildDocumentSeeds() throws JSONException {
    JSONArray out = new JSONArray();
    out.put(documentRow("doc-arc", "glc.doc.salesInvoice", "700", "Ventas de mercaderias"));
    out.put(documentRow("doc-api", "glc.doc.purchaseInvoice", "600", "Compras de mercaderias"));
    out.put(documentRow("doc-arn", "glc.doc.salesCreditMemo", "708", "Devoluciones de ventas"));
    out.put(documentRow("doc-apn", "glc.doc.purchaseCreditMemo", "608", "Devoluciones de compras"));
    out.put(documentRow("doc-arr", "glc.doc.receipt", "572", "Bancos c/c"));
    out.put(documentRow("doc-app", "glc.doc.payment", "410", "Acreedores por prestaciones"));
    JSONObject journal = new JSONObject();
    journal.put("id", "doc-glj");
    journal.put("typeKey", "glc.doc.manualJournal");
    journal.put("journalKey", "glc.doc.generalJournal");
    out.put(journal);
    out.put(documentRow("doc-amz", "glc.doc.depreciation", "681", "Amortizacion del inmovilizado"));
    return out;
  }

  private JSONObject documentRow(String id, String typeKey, String code, String name) throws JSONException {
    JSONObject row = new JSONObject();
    row.put("id", id);
    row.put("typeKey", typeKey);
    row.put("accountId", "seed-" + code);
    row.put("accountCode", code);
    row.put("accountName", name);
    return row;
  }

  private JSONObject buildOrgInfo(Organization organization) throws JSONException {
    JSONObject out = new JSONObject();
    out.put("organization", organization.getName() != null ? organization.getName() : JSONObject.NULL);
    Calendar calendar = organization.getCalendar();
    out.put("fiscalCalendar", calendar != null && calendar.getName() != null ? calendar.getName() : JSONObject.NULL);
    return out;
  }

  private JSONObject buildCatalogs(AcctSchema schema) throws JSONException {
    JSONObject out = new JSONObject();
    out.put("accounts", buildAccountOptions(schema));
    out.put("currencies", buildCurrencyOptions());
    return out;
  }

  private JSONArray buildAccountOptions(AcctSchema schema) throws JSONException {
    OBCriteria<AccountingCombination> criteria = OBDal.getInstance().createCriteria(AccountingCombination.class);
    criteria.add(Restrictions.eq(AccountingCombination.PROPERTY_ACCOUNTINGSCHEMA, schema));
    criteria.add(Restrictions.eq(AccountingCombination.PROPERTY_ACTIVE, true));
    criteria.addOrder(Order.asc(AccountingCombination.PROPERTY_COMBINATION));
    @SuppressWarnings("unchecked")
    List<AccountingCombination> rows = criteria.list();

    JSONArray out = new JSONArray();
    for (AccountingCombination combo : rows) {
      JSONObject item = new JSONObject();
      item.put("id", combo.getId());
      item.put("code", resolveCombinationCode(combo));
      item.put("name", resolveCombinationName(combo));
      out.put(item);
    }
    return out;
  }

  private JSONArray buildCurrencyOptions() throws JSONException {
    OBCriteria<Currency> criteria = OBDal.getInstance().createCriteria(Currency.class);
    criteria.add(Restrictions.eq(Currency.PROPERTY_ACTIVE, true));
    criteria.addOrder(Order.asc(Currency.PROPERTY_ISOCODE));
    criteria.setMaxResults(50);
    @SuppressWarnings("unchecked")
    List<Currency> rows = criteria.list();

    JSONArray out = new JSONArray();
    for (Currency currency : rows) {
      JSONObject item = new JSONObject();
      item.put("value", currency.getId());
      String iso = currency.getISOCode();
      String name = iso != null ? iso : currency.getId();
      item.put("name", iso != null ? iso + " — " + name : name);
      out.put(item);
    }
    return out;
  }

  private JSONObject buildMeta() throws JSONException {
    JSONObject meta = new JSONObject();
    meta.put("source", "neo");
    meta.put("documentsBacked", false);
    meta.put("documentsNote",
        "Document mappings remain a read-only visual reference until Product confirms the source model.");
    return meta;
  }

  private void applyGeneralChanges(AggregateState state, JSONObject general) {
    if (general.has("name")) {
      state.schema.setName(trimmedOrNull(general.optString("name", null)));
    }
    if (general.has("gAAP")) {
      state.schema.setGAAP(trimmedOrNull(general.optString("gAAP", null)));
    }
    if (general.has(FIELD_DESCRIPTION)) {
      state.schema.setDescription(trimmedOrNull(general.optString(FIELD_DESCRIPTION, null)));
    }
    if (general.has(FIELD_ACCRUAL)) {
      state.schema.setAccrual(general.optBoolean(FIELD_ACCRUAL));
    }
    if (general.has(FIELD_AUTOMATIC_PERIOD_CONTROL)) {
      state.schema.setAutomaticPeriodControl(general.optBoolean(FIELD_AUTOMATIC_PERIOD_CONTROL));
    }
    if (general.has(FIELD_CURRENCY) && !general.isNull(FIELD_CURRENCY)) {
      String currencyId = trimmedOrNull(general.optString(FIELD_CURRENCY, null));
      if (currencyId != null) {
        Currency currency = OBDal.getInstance().get(Currency.class, currencyId);
        if (currency == null) {
          throw new OBException("Currency not found: " + currencyId);
        }
        state.schema.setCurrency(currency);
      }
    }
  }

  private void applyDefaultChanges(AggregateState state, JSONObject defaults) {
    for (DefaultFieldMapping mapping : DEFAULT_FIELD_MAPPINGS) {
      if (!defaults.has(mapping.apiKey)) {
        continue;
      }
      Object raw = defaults.opt(mapping.apiKey);
      AccountingCombination combo = null;
      if (!(raw == null || raw == JSONObject.NULL)) {
        String id = StringUtils.trimToNull(String.valueOf(raw));
        if (id != null) {
          combo = OBDal.getInstance().get(AccountingCombination.class, id);
          if (combo == null) {
            throw new OBException("Accounting combination not found: " + id);
          }
        }
      }
      state.defaults.set(mapping.property, combo);
    }
  }

  private void applyGeneralAccountsChanges(AggregateState state, JSONObject generalAccounts) {
    AcctSchemaGL ga = state.generalAccounts;
    if (generalAccounts.has(FIELD_SUSPENSE_BALANCING_USE)) {
      ga.setSuspenseBalancingUse(generalAccounts.optBoolean(FIELD_SUSPENSE_BALANCING_USE));
    }
    if (generalAccounts.has(FIELD_SUSPENSE_BALANCING)) {
      ga.setSuspenseBalancing(resolveAccountingCombination(generalAccounts, FIELD_SUSPENSE_BALANCING));
    }
    if (generalAccounts.has(FIELD_SUSPENSE_ERROR_USE)) {
      ga.setSuspenseErrorUse(generalAccounts.optBoolean(FIELD_SUSPENSE_ERROR_USE));
    }
    if (generalAccounts.has(FIELD_CURRENCY_BALANCING_USE)) {
      ga.setCurrencyBalancingUse(generalAccounts.optBoolean(FIELD_CURRENCY_BALANCING_USE));
    }
    if (generalAccounts.has(FIELD_CURRENCY_BALANCING_ACCOUNT)) {
      ga.setCurrencyBalancingAcct(resolveAccountingCombination(generalAccounts, FIELD_CURRENCY_BALANCING_ACCOUNT));
    }
    if (generalAccounts.has(FIELD_RETAINED_EARNING)) {
      ga.setRetainedEarning(resolveAccountingCombination(generalAccounts, FIELD_RETAINED_EARNING));
    }
    if (generalAccounts.has(FIELD_INCOME_SUMMARY)) {
      ga.setIncomeSummary(resolveAccountingCombination(generalAccounts, FIELD_INCOME_SUMMARY));
    }
    if (generalAccounts.has(FIELD_CFS_ORDER_ACCOUNT)) {
      ga.setCFSOrderAccount(resolveAccountingCombination(generalAccounts, FIELD_CFS_ORDER_ACCOUNT));
    }
    if (generalAccounts.has(FIELD_ACTIVE)) {
      ga.setActive(generalAccounts.optBoolean(FIELD_ACTIVE));
    }
    if (generalAccounts.has(FIELD_REVERSE_PERMANENT_BALANCES)) {
      ga.setCreateClosing(generalAccounts.optBoolean(FIELD_REVERSE_PERMANENT_BALANCES));
    }
  }

  private AccountingCombination resolveAccountingCombination(JSONObject payload, String key) {
    Object raw = payload.opt(key);
    if (raw == null || raw == JSONObject.NULL) {
      return null;
    }
    String id = StringUtils.trimToNull(String.valueOf(raw));
    if (id == null) {
      return null;
    }
    AccountingCombination combo = OBDal.getInstance().get(AccountingCombination.class, id);
    if (combo == null) {
      throw new OBException("Accounting combination not found: " + id);
    }
    return combo;
  }

  private void applyDimensionChanges(AggregateState state, JSONArray dimensions) throws JSONException {
    Map<String, AcctSchemaElement> byId = new LinkedHashMap<>();
    for (AcctSchemaElement row : state.dimensions) {
      byId.put(row.getId(), row);
    }

    for (int i = 0; i < dimensions.length(); i++) {
      JSONObject item = dimensions.optJSONObject(i);
      String id = item != null ? trimmedOrNull(item.optString("id", null)) : null;
      AcctSchemaElement row = id != null ? byId.get(id) : null;
      if (row != null && item.has(FIELD_ACTIVE)) {
        if (bool(row.isMandatory()) && !item.optBoolean(FIELD_ACTIVE)) {
          throw new OBException("Mandatory accounting dimensions cannot be deactivated");
        }
        row.setActive(item.optBoolean(FIELD_ACTIVE));
        OBDal.getInstance().save(row);
      }
    }
  }

  private String buildDimensionCaption(AcctSchemaElement row) {
    String obligation = bool(row.isMandatory()) ? "Obligatorio" : "Opcional";
    String scope = inferDimensionScope(row.getType());
    return scope != null ? obligation + " · " + scope : obligation;
  }

  private String inferDimensionScope(String type) {
    if (type == null) {
      return null;
    }
    switch (type) {
      case "AC":
      case "OO":
        return "Facturas y asientos";
      case "PR":
      case "BP":
      case "MC":
        return "Ventas y compras";
      case "PJ":
      case "AY":
      case "SR":
      case "AS":
        return "Todos los documentos";
      default:
        return "Todos los documentos";
    }
  }

  private Map<String, String> mergeOrgQuery(Map<String, String> queryParams, JSONObject body) {
    Map<String, String> out = new LinkedHashMap<>();
    if (queryParams != null) {
      out.putAll(queryParams);
    }
    if (body != null && body.has(PARAM_SELECTED_ORG_ID) && !body.isNull(PARAM_SELECTED_ORG_ID)) {
      out.put(PARAM_SELECTED_ORG_ID, body.optString(PARAM_SELECTED_ORG_ID, null));
    }
    return out;
  }

  private Object nullable(String value) {
    return value != null ? value : JSONObject.NULL;
  }

  private boolean bool(Boolean value) {
    return Boolean.TRUE.equals(value);
  }

  private String trimmedOrNull(String value) {
    return StringUtils.trimToNull(value);
  }

  private Object toCombinationId(Object combo) {
    if (combo instanceof AccountingCombination) {
      return ((AccountingCombination) combo).getId();
    }
    return JSONObject.NULL;
  }

  private String resolveCombinationCode(AccountingCombination combo) {
    ElementValue account = combo.getAccount();
    if (account != null && StringUtils.isNotBlank(account.getSearchKey())) {
      return account.getSearchKey();
    }
    return combo.getCombination();
  }

  private String resolveCombinationName(AccountingCombination combo) {
    ElementValue account = combo.getAccount();
    if (account != null && StringUtils.isNotBlank(account.getName())) {
      return account.getName();
    }
    if (StringUtils.isNotBlank(combo.getDescription())) {
      return combo.getDescription();
    }
    return combo.getCombination();
  }

  private static final class AggregateState {
    final Organization organization;
    final AcctSchema schema;
    final AcctSchemaDefault defaults;
    final List<AcctSchemaElement> dimensions;
    final AcctSchemaGL generalAccounts;

    AggregateState(Organization organization, AcctSchema schema, AcctSchemaDefault defaults,
        List<AcctSchemaElement> dimensions, AcctSchemaGL generalAccounts) {
      this.organization = organization;
      this.schema = schema;
      this.defaults = defaults;
      this.dimensions = dimensions != null ? new ArrayList<>(dimensions) : new ArrayList<>();
      this.dimensions.sort(Comparator.comparing(AcctSchemaElement::getSequenceNumber, Comparator.nullsLast(Long::compareTo)));
      this.generalAccounts = generalAccounts;
    }
  }
}
