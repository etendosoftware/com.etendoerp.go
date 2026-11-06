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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.Query;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.RemittanceLine;

import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoActionContract.Param;

/**
 * ETP-6148 — «Seleccionar desde factura»: the invoice due amounts a remittance can take, and adding
 * them as lines through Classic's own {@code REMSelectInvoicesAndOrders}. Declared actions of the
 * remittance header ({@link RemittanceHeaderHandler}); the record id is the remittance.
 *
 * <p><b>Candidates</b> ({@link #candidateWhere}) — Classic's «Select Invoices and Orders» rules
 * ({@code PendingPaymentsHqlTransformer} and the process' save checks) <em>without</em> its default
 * payment-method filter, plus two checks Classic lacks:
 * <ul>
 *   <li>not paid ({@code paymentDetails is null}), not cancelled, not invoice-paid;</li>
 *   <li>invoice payment schedules only (no orders), with a business partner;</li>
 *   <li>organization in the child tree of the remittance's organization;</li>
 *   <li>expected date on or before the remittance due date;</li>
 *   <li>direction from the account ↔ type-method link: pay-in only → sales invoices, pay-out only
 *       → purchase invoices, both or no link → no filter (Classic's {@code getPaymentType});</li>
 *   <li><b>added:</b> invoice currency = the financial account currency;</li>
 *   <li><b>added:</b> not already a line of another unprocessed remittance.</li>
 * </ul>
 * Rows are grouped per invoice payment schedule, as Classic's grid does; the amount is the sum of
 * the remaining schedule details.
 *
 * <p><b>Selecting</b> re-checks every id against the same rules, then calls the Classic handler with
 * the full outstanding amount of each schedule and no {@code _allRows}, so it never deletes an
 * existing line. The status refresh is REM's.
 */
final class RemittanceDueItems {

  private static final Logger log = LogManager.getLogger(RemittanceDueItems.class);

  static final String ACTION_AVAILABLE = "availableDueItems";
  static final String ACTION_SELECT = "selectDueItems";

  static final String P_SEARCH = "search";
  static final String P_PAGE = "page";
  static final String P_PAGE_SIZE = "pageSize";
  static final String P_IDS = "ids";

  static final int DEFAULT_PAGE_SIZE = 50;
  static final int MAX_PAGE_SIZE = 200;

  static final String MSG_TYPE_OR_ACCOUNT_MISSING = "ETGO_RemittanceTypeOrAccountMissing";
  static final String MSG_IDS_REQUIRED = "ETGO_RemittanceDueItemsRequired";
  static final String MSG_NOT_AVAILABLE = "ETGO_RemittanceDueItemNotAvailable";
  static final String MSG_SELECTION_FAILED = "ETGO_RemittanceDueItemsSelectionFailed";

  /** Classic's «Select Invoices and Orders» process and its handler. */
  static final String SELECT_PROCESS_ID = "99E532BA0306450A839F5DE238375238";
  static final String SELECT_HANDLER_CLASS =
      "org.openbravo.module.remittance.ad_actionbutton.REMSelectInvoicesAndOrders";
  /** The remittance header tab: the handler reads {@code REM_Remittance_ID} off its key column. */
  static final String HEADER_TAB_ID = "FF8080812E71F7C7012E71FE49BB0021";
  static final String GRID_PARAMETER = "pending payments";

  private static final String SELECT_ROWS = "select ips.id, inv.documentNo, bp.name, "
      + "ips.expectedDate, sum(psd.amount), cur.iSOCode";
  private static final String GROUP_ORDER = " group by ips.id, inv.documentNo, bp.name, "
      + "ips.expectedDate, cur.iSOCode order by ips.expectedDate, inv.documentNo, ips.id";

  private RemittanceDueItems() {
  }

  // ---------------------------------------------------------------------------
  // Contracts
  // ---------------------------------------------------------------------------

  static void addContracts(Map<String, NeoActionContract> contracts) {
    String idDescription = "the id of the remittance";
    contracts.put(ACTION_AVAILABLE, NeoActionContract.read(ACTION_AVAILABLE,
        "Lists the invoice due amounts this remittance can take, as the «Seleccionar desde "
            + "factura» dialog shows them: unpaid, not cancelled, of an invoice in the account's "
            + "currency, of the remittance's direction (sales invoices for a collection remittance, "
            + "purchase invoices for a payment one), expected on or before the remittance due date "
            + "and not in another unprocessed remittance. Returns items [{id (the invoice payment "
            + "schedule), documentNo, businessPartner, dueDate (yyyy-MM-dd), outstandingAmount, "
            + "currency, alreadyInRemittance}] and total, ordered by dueDate then documentNo.",
        Param.optional(P_SEARCH, NeoActionContract.TYPE_STRING,
            "Part of the invoice number or of the business partner name; case-insensitive."),
        Param.optional(P_PAGE, NeoActionContract.TYPE_NUMBER, "Page to return, from 1. Default 1."),
        Param.optional(P_PAGE_SIZE, NeoActionContract.TYPE_NUMBER,
            "Items per page, 1 to " + MAX_PAGE_SIZE + ". Default " + DEFAULT_PAGE_SIZE + "."))
        .withIdDescription(idDescription));
    contracts.put(ACTION_SELECT, NeoActionContract.write(ACTION_SELECT,
        "Adds invoice due amounts to the remittance as lines, each for its full outstanding "
            + "amount, through the remittance module's own process. Every id must be one that "
            + "availableDueItems offers; lines already in the remittance are kept. Not available "
            + "on a processed remittance. Returns created (the number of new lines) and message.",
        Param.array(P_IDS, NeoActionContract.TYPE_STRING, true,
            "Ids of invoice payment schedules, as availableDueItems returns them (items[].id)."))
        .withIdDescription(idDescription));
  }

  static boolean serves(String action) {
    return ACTION_AVAILABLE.equals(action) || ACTION_SELECT.equals(action);
  }

  // ---------------------------------------------------------------------------
  // Actions
  // ---------------------------------------------------------------------------

  /**
   * Lists the candidates of {@code remittance}.
   *
   * @param remittance the (unprocessed) remittance
   * @param params     {@value #P_SEARCH}, {@value #P_PAGE}, {@value #P_PAGE_SIZE}
   * @return {@code {items, total}}
   */
  static NeoResponse available(Remittance remittance, JSONObject params) throws JSONException {
    Criteria criteria = Criteria.of(remittance);
    if (criteria == null) {
      return NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_TYPE_OR_ACCOUNT_MISSING);
    }
    String search = StringUtils.trimToNull(params.optString(P_SEARCH, null));
    int pageSize = clamp(params.optInt(P_PAGE_SIZE, DEFAULT_PAGE_SIZE), 1, MAX_PAGE_SIZE);
    int page = Math.max(1, params.optInt(P_PAGE, 1));

    StringBuilder where = candidateWhere(criteria);
    if (search != null) {
      where.append(" and (lower(inv.documentNo) like :search or lower(bp.name) like :search)");
    }
    Query<Long> count = session().createQuery(
        "select count(distinct ips.id)" + from() + where, Long.class);
    criteria.bind(count);
    Query<Object[]> rows = session().createQuery(SELECT_ROWS + from() + where + GROUP_ORDER,
        Object[].class);
    criteria.bind(rows);
    if (search != null) {
      String pattern = "%" + search.toLowerCase(Locale.ROOT) + "%";
      count.setParameter(P_SEARCH, pattern);
      rows.setParameter(P_SEARCH, pattern);
    }
    List<Object[]> page0 = rows.setFirstResult((page - 1) * pageSize).setMaxResults(pageSize)
        .list();
    Set<String> inThisRemittance = schedulesInRemittance(remittance);

    JSONArray items = new JSONArray();
    for (Object[] row : page0) {
      items.put(item(row, inThisRemittance));
    }
    Long total = count.uniqueResult();
    JSONObject body = new JSONObject();
    body.put("items", items);
    body.put("total", total != null ? total : 0L);
    return NeoResponse.ok(body);
  }

  /**
   * Adds the given schedules to {@code remittance} through Classic's handler.
   *
   * @param remittance the (unprocessed) remittance
   * @param params     {@value #P_IDS}
   * @return {@code {created, message}}, or 422 when an id is not a candidate
   */
  static NeoResponse select(Remittance remittance, JSONObject params) throws JSONException {
    Criteria criteria = Criteria.of(remittance);
    if (criteria == null) {
      return NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_TYPE_OR_ACCOUNT_MISSING);
    }
    Set<String> ids = idsOf(params.optJSONArray(P_IDS));
    if (ids.isEmpty()) {
      return NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_IDS_REQUIRED);
    }
    StringBuilder where = candidateWhere(criteria).append(" and ips.id in (:ids)");
    Query<Object[]> rows = session().createQuery(SELECT_ROWS + from() + where + GROUP_ORDER,
        Object[].class);
    criteria.bind(rows);
    rows.setParameterList(P_IDS, ids);
    List<Object[]> eligible = rows.list();

    Set<String> found = new HashSet<>();
    JSONArray selection = new JSONArray();
    for (Object[] row : eligible) {
      String id = (String) row[0];
      found.add(id);
      String amount = toBigDecimal(row[4]).toPlainString();
      JSONObject selected = new JSONObject();
      selected.put("paymentScheduleID", id);
      selected.put("realpayment", amount);
      selected.put("outstandingamount", amount);
      selected.put("rest", "0");
      selection.put(selected);
    }
    List<String> invalid = new ArrayList<>(ids);
    invalid.removeAll(found);
    if (!invalid.isEmpty()) {
      NeoResponse refusal =
          NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_NOT_AVAILABLE);
      JSONObject error = refusal.getBody() != null ? refusal.getBody().optJSONObject("error") : null;
      if (error != null) {
        error.put("invalidIds", new JSONArray(invalid));
      }
      return refusal;
    }

    long before = countLines(remittance);
    JSONObject grid = new JSONObject();
    grid.put("_selection", selection); // no _allRows: REM must never delete existing lines
    JSONObject processParams = new JSONObject();
    processParams.put("inpRecordId", remittance.getId());
    processParams.put("inpTabId", HEADER_TAB_ID);
    processParams.put(GRID_PARAMETER, grid);
    NeoResponse result = runClassicSelection(processParams);
    String failure = failureText(result);
    if (failure != null) {
      log.warn("selectDueItems on remittance {} refused by REM: {}", remittance.getId(), failure);
      // REM may have created some lines before failing; they must not commit with our 422.
      rollback();
      return NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_SELECTION_FAILED,
          failure);
    }
    long created = Math.max(0L, countLines(remittance) - before);
    JSONObject body = new JSONObject();
    body.put("created", created);
    body.put("message", successText(result, created));
    return NeoResponse.ok(body);
  }

  /**
   * Seam: rolls back what a refused REM selection wrote, unless a batch ({@code etendo_batch},
   * {@code /sws/neo/batch}) owns the transaction and rolls the whole set back itself.
   */
  static void rollback() {
    NeoErrorResponses.rollbackUnlessBatchOwned();
  }

  // ---------------------------------------------------------------------------
  // Query
  // ---------------------------------------------------------------------------

  private static String from() {
    return " from " + FIN_PaymentScheduleDetail.ENTITY_NAME + " as psd"
        + " join psd.invoicePaymentSchedule as ips"
        + " join ips.invoice as inv"
        + " join inv.currency as cur"
        + " join psd.businessPartner as bp";
  }

  /** The candidate rules, see the class comment. Parameters bound by {@link Criteria#bind}. */
  static StringBuilder candidateWhere(Criteria criteria) {
    StringBuilder where = new StringBuilder(" where psd.client.id = :client")
        .append(" and psd.paymentDetails is null")
        .append(" and psd.canceled = false and psd.invoicePaid = false")
        .append(" and psd.organization.id in (:orgs)")
        .append(" and ips.expectedDate < :dueDateLimit")
        .append(" and cur.id = :currency")
        .append(" and not exists (select 1 from ").append(RemittanceLine.ENTITY_NAME)
        .append(" as other where other.paymentScheduleDetail.id = psd.id")
        .append(" and other.remittance.id <> :remittance and other.remittance.processed = false)");
    if (criteria.salesTransaction != null) {
      where.append(" and inv.salesTransaction = :sales");
    }
    return where;
  }

  /** The values the candidate rules compare against, from the remittance. */
  static final class Criteria {
    private final String clientId;
    private final String remittanceId;
    private final Collection<String> organizations;
    private final Date dueDateLimit;
    private final String currencyId;
    private final Boolean salesTransaction;

    private Criteria(String clientId, String remittanceId, Collection<String> organizations,
        Date dueDateLimit, String currencyId, Boolean salesTransaction) {
      this.clientId = clientId;
      this.remittanceId = remittanceId;
      this.organizations = organizations;
      this.dueDateLimit = dueDateLimit;
      this.currencyId = currencyId;
      this.salesTransaction = salesTransaction;
    }

    /** @return the criteria, or {@code null} when the remittance has no type, account or date */
    static Criteria of(Remittance remittance) {
      FIN_FinancialAccount account = remittance.getFinancialAccount();
      FIN_PaymentMethod method = remittance.getRemittanceType() != null
          ? remittance.getRemittanceType().getPaymentMethod()
          : null;
      if (account == null || method == null || account.getCurrency() == null
          || remittance.getDueDate() == null) {
        return null;
      }
      Set<String> orgs = OBContext.getOBContext().getOrganizationStructureProvider()
          .getChildTree(remittance.getOrganization().getId(), true);
      return new Criteria(remittance.getClient().getId(), remittance.getId(), orgs,
          dayAfter(remittance.getDueDate()), account.getCurrency().getId(),
          direction(account, method));
    }

    void bind(Query<?> query) {
      query.setParameter("client", clientId);
      query.setParameter("remittance", remittanceId);
      query.setParameterList("orgs", organizations);
      query.setParameter("dueDateLimit", dueDateLimit);
      query.setParameter("currency", currencyId);
      if (salesTransaction != null) {
        query.setParameter("sales", salesTransaction);
      }
    }
  }

  /**
   * The direction filter, as Classic's {@code RemittanceUtils.getPaymentType} decides it from the
   * account's link to the type's method: pay-in only → sales invoices, pay-out only → purchase
   * invoices, otherwise (both, neither, no link) no filter.
   */
  static Boolean direction(FIN_FinancialAccount account, FIN_PaymentMethod method) {
    List<FinAccPaymentMethod> links = session().createQuery(
        "from " + FinAccPaymentMethod.ENTITY_NAME + " as l where l.account.id = :account"
            + " and l.paymentMethod.id = :method order by l.creationDate desc",
        FinAccPaymentMethod.class)
        .setParameter("account", account.getId())
        .setParameter("method", method.getId())
        .setMaxResults(1)
        .list();
    if (links.isEmpty()) {
      return null;
    }
    boolean in = Boolean.TRUE.equals(links.get(0).isPayinAllow());
    boolean out = Boolean.TRUE.equals(links.get(0).isPayoutAllow());
    if (in && !out) {
      return Boolean.TRUE;
    }
    if (out && !in) {
      return Boolean.FALSE;
    }
    return null;
  }

  private static Set<String> schedulesInRemittance(Remittance remittance) {
    List<String> ids = session().createQuery(
        "select distinct rl.paymentScheduleDetail.invoicePaymentSchedule.id from "
            + RemittanceLine.ENTITY_NAME + " as rl where rl.remittance.id = :remittance"
            + " and rl.paymentScheduleDetail.invoicePaymentSchedule is not null",
        String.class)
        .setParameter("remittance", remittance.getId())
        .list();
    return new HashSet<>(ids);
  }

  private static long countLines(Remittance remittance) {
    OBDal.getInstance().flush();
    Long count = session().createQuery("select count(rl.id) from " + RemittanceLine.ENTITY_NAME
        + " as rl where rl.remittance.id = :remittance", Long.class)
        .setParameter("remittance", remittance.getId())
        .uniqueResult();
    return count != null ? count : 0L;
  }

  private static JSONObject item(Object[] row, Set<String> inThisRemittance) throws JSONException {
    JSONObject item = new JSONObject();
    String id = (String) row[0];
    item.put("id", id);
    item.put("documentNo", row[1]);
    item.put("businessPartner", row[2]);
    item.put("dueDate", row[3] instanceof Date ? toDay((Date) row[3]).toString() : JSONObject.NULL);
    item.put("outstandingAmount", toBigDecimal(row[4]));
    item.put("currency", row[5]);
    item.put("alreadyInRemittance", inThisRemittance.contains(id));
    return item;
  }

  // ---------------------------------------------------------------------------
  // Classic handler
  // ---------------------------------------------------------------------------

  /** Seam: the Classic handler run. */
  static NeoResponse runClassicSelection(JSONObject processParams) {
    return NeoProcessService.executeObuiappClass(SELECT_HANDLER_CLASS, SELECT_PROCESS_ID,
        processParams);
  }

  /**
   * The error text of REM's answer, or {@code null} on success. REM reports through
   * {@code showMsgInView} (not {@code showMsgInProcessView}), which the generic translation does not
   * read as a failure, so it is read here.
   */
  static String failureText(NeoResponse result) {
    if (result == null || result.getBody() == null) {
      return "No response";
    }
    if (result.getHttpStatus() >= 400) {
      return StringUtils.defaultIfBlank(result.getBody().optString("message", null),
          "Process failed");
    }
    JSONObject message = viewMessage(result.getBody());
    if (message != null && "error".equalsIgnoreCase(message.optString("msgType"))) {
      return StringUtils.defaultIfBlank(message.optString("msgText", null), "Process failed");
    }
    return null;
  }

  private static String successText(NeoResponse result, long created) {
    JSONObject message = result != null && result.getBody() != null
        ? viewMessage(result.getBody())
        : null;
    String text = message != null ? StringUtils.trimToNull(message.optString("msgText", null))
        : null;
    return text != null ? text : String.valueOf(created);
  }

  private static JSONObject viewMessage(JSONObject body) {
    JSONArray actions = body.optJSONArray("responseActions");
    if (actions == null) {
      return null;
    }
    for (int i = 0; i < actions.length(); i++) {
      JSONObject action = actions.optJSONObject(i);
      JSONObject message = action != null ? action.optJSONObject("showMsgInView") : null;
      if (message == null && action != null) {
        message = action.optJSONObject("showMsgInProcessView");
      }
      if (message != null) {
        return message;
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static org.hibernate.Session session() {
    return OBDal.getInstance().getSession();
  }

  static Set<String> idsOf(JSONArray array) {
    Set<String> ids = new LinkedHashSet<>();
    if (array == null) {
      return ids;
    }
    for (int i = 0; i < array.length(); i++) {
      Object value = array.opt(i);
      String id = value != null && !JSONObject.NULL.equals(value)
          ? StringUtils.trimToNull(value.toString())
          : null;
      if (id != null) {
        ids.add(id);
      }
    }
    return ids;
  }

  private static Date dayAfter(Date date) {
    LocalDate next = toDay(date).plusDays(1);
    return Date.from(next.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  private static LocalDate toDay(Date date) {
    if (date instanceof java.sql.Date) {
      return ((java.sql.Date) date).toLocalDate();
    }
    return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
  }

  private static BigDecimal toBigDecimal(Object value) {
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    return value instanceof Number ? new BigDecimal(value.toString()) : BigDecimal.ZERO;
  }

  private static int clamp(int value, int min, int max) {
    return Math.min(Math.max(value, min), max);
  }

  /** @return the read parameters from a query string, typed as the contract declares them */
  static JSONObject paramsFromQuery(Map<String, String> query) throws JSONException {
    JSONObject params = new JSONObject();
    if (query == null) {
      return params;
    }
    if (StringUtils.isNotBlank(query.get(P_SEARCH))) {
      params.put(P_SEARCH, query.get(P_SEARCH));
    }
    for (String key : List.of(P_PAGE, P_PAGE_SIZE)) {
      String raw = StringUtils.trimToNull(query.get(key));
      if (raw != null && raw.matches("\\d{1,9}")) {
        params.put(key, Integer.parseInt(raw));
      }
    }
    return params;
  }
}
