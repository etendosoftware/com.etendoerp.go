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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *
 * <p><b>Syncing</b> ({@link #sync}) is the dialog as a manager of the remittance's documents: one
 * transaction that adds dues with an amount (a part of the due is allowed, see
 * {@link RemittanceLineAmount}), changes the amount of dues already in it (their lines are replaced
 * through the same Classic handler, which spreads the amount over the due's open details), and
 * removes the dues listed explicitly. Removal is explicit, never "whatever is not in the list": the
 * dialog is paginated and searchable, so a final-set body would drop every due off the page. Dues
 * already in the remittance are always listed by {@link #available}, even when the remittance due
 * date has moved before their expected date.
 */
final class RemittanceDueItems {

  private static final Logger log = LogManager.getLogger(RemittanceDueItems.class);

  static final String ACTION_AVAILABLE = "availableDueItems";
  static final String ACTION_SELECT = "selectDueItems";
  static final String ACTION_SYNC = "syncDueItems";

  static final String P_SEARCH = "search";
  static final String P_PAGE = "page";
  static final String P_PAGE_SIZE = "pageSize";
  static final String P_IDS = "ids";
  static final String P_ITEMS = "items";
  static final String P_REMOVE = "remove";
  static final String P_ID = "id";
  static final String P_AMOUNT = "amount";

  static final String DUE_OVERDUE = "OVERDUE";
  static final String DUE_TODAY = "DUE_TODAY";
  static final String DUE_PENDING = "PENDING";

  static final int DEFAULT_PAGE_SIZE = 50;
  static final int MAX_PAGE_SIZE = 200;

  static final String MSG_TYPE_OR_ACCOUNT_MISSING = "ETGO_RemittanceTypeOrAccountMissing";
  static final String MSG_IDS_REQUIRED = "ETGO_RemittanceDueItemsRequired";
  static final String MSG_NOT_AVAILABLE = "ETGO_RemittanceDueItemNotAvailable";
  static final String MSG_SELECTION_FAILED = "ETGO_RemittanceDueItemsSelectionFailed";
  static final String MSG_NOT_INCLUDED = "ETGO_RemittanceDueItemNotIncluded";
  private static final String KEY_INVALID_IDS = "invalidIds";
  private static final String KEY_ERROR = "error";
  private static final String KEY_MESSAGE = "message";
  /** HQL parameter of the remittance id. */
  private static final String Q_REMITTANCE = "remittance";

  /** Classic's «Select Invoices and Orders» process and its handler. */
  static final String SELECT_PROCESS_ID = "99E532BA0306450A839F5DE238375238";
  static final String SELECT_HANDLER_CLASS =
      "org.openbravo.module.remittance.ad_actionbutton.REMSelectInvoicesAndOrders";
  /** The remittance header tab: the handler reads {@code REM_Remittance_ID} off its key column. */
  static final String HEADER_TAB_ID = "FF8080812E71F7C7012E71FE49BB0021";
  static final String GRID_PARAMETER = "pending payments";

  private static final String SELECT_ROWS = "select ips.id, inv.documentNo, bp.name, "
      + "ips.expectedDate, sum(psd.amount), cur.iSOCode, inv.id, inv.salesTransaction, "
      + "inv.invoiceDate, inv.grandTotalAmount, bp.id";
  private static final String GROUP_ORDER = " group by ips.id, inv.documentNo, bp.name, "
      + "ips.expectedDate, cur.iSOCode, inv.id, inv.salesTransaction, inv.invoiceDate, "
      + "inv.grandTotalAmount, bp.id order by ips.expectedDate, inv.documentNo, ips.id";

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
            + "and not in another unprocessed remittance; the dues already in this remittance are "
            + "always listed. Returns items [{id (the invoice payment schedule), documentNo, "
            + "invoiceId, salesTransaction, invoiceDate, dueDate (yyyy-MM-dd), "
            + "dueStatus (OVERDUE, DUE_TODAY or PENDING), businessPartner, businessPartnerId, "
            + "grandTotalAmount, outstandingAmount (the most that can be remitted), currency, "
            + "alreadyInRemittance, lineAmount and lineIds (only when already in the "
            + "remittance)}] and total, ordered by dueDate then documentNo.",
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
    contracts.put(ACTION_SYNC, NeoActionContract.write(ACTION_SYNC,
        "Applies the «Seleccionar desde factura» dialog to the remittance in one transaction: adds "
            + "the dues in items that are not in it yet, changes the amount of those already in "
            + "it, and removes the dues listed in remove. Each amount must have the sign of the "
            + "due and be above 0 and at most its outstandingAmount (a part of a due may be "
            + "remitted). Every id must be one availableDueItems offers; a remove id must be in "
            + "the remittance. Nothing is kept when anything is refused. Not available on a "
            + "processed remittance. Returns created, updated, removed (numbers of dues) and "
            + "message.",
        Param.array(P_ITEMS, NeoActionContract.TYPE_OBJECT, false,
            "Dues to add or keep: [{id (items[].id of availableDueItems), amount}]. A due "
                + "already in the remittance with the same amount is left as it is."),
        Param.array(P_REMOVE, NeoActionContract.TYPE_STRING, false,
            "Ids of dues already in the remittance to take out of it."))
        .withIdDescription(idDescription));
  }

  static boolean serves(String action) {
    return ACTION_AVAILABLE.equals(action) || ACTION_SELECT.equals(action)
        || ACTION_SYNC.equals(action);
  }

  /** @return whether {@code action} writes (POST only) */
  static boolean isWrite(String action) {
    return ACTION_SELECT.equals(action) || ACTION_SYNC.equals(action);
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
    Map<String, IncludedDue> inThisRemittance = includedDues(remittance);
    LocalDate today = today();

    JSONArray items = new JSONArray();
    for (Object[] row : page0) {
      items.put(item(row, inThisRemittance, today));
    }
    Long total = count.uniqueResult();
    JSONObject body = new JSONObject();
    body.put(P_ITEMS, items);
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
      BigDecimal amount = toBigDecimal(row[4]);
      selection.put(RemittanceDueSelection.row(id, amount, amount));
    }
    List<String> invalid = new ArrayList<>(ids);
    invalid.removeAll(found);
    if (!invalid.isEmpty()) {
      return withInvalidIds(
          NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_NOT_AVAILABLE), invalid);
    }

    long before = countLines(remittance);
    NeoResponse result = RemittanceDueSelection.run(RemittanceDueSelection.params(remittance, selection));
    String failure = RemittanceDueSelection.failureText(result);
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
    body.put(KEY_MESSAGE, RemittanceDueSelection.successText(result, created));
    return NeoResponse.ok(body);
  }

  /**
   * Applies the picker to {@code remittance} in one transaction (see the {@value #ACTION_SYNC}
   * contract). Everything is validated before anything is written; then the removed dues lose
   * their lines, a due whose amount changed loses its lines too, and the new and changed dues are
   * added back through Classic's handler with their amount, which spreads it over the due's open
   * details exactly as Classic does. A refusal by REM rolls everything back.
   *
   * @param remittance the (unprocessed) remittance
   * @param params     {@value #P_ITEMS}, {@value #P_REMOVE}
   * @return {@code {created, updated, removed, message}}, or 422
   */
  static NeoResponse sync(Remittance remittance, JSONObject params) throws JSONException {
    Criteria criteria = Criteria.of(remittance);
    if (criteria == null) {
      return NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_TYPE_OR_ACCOUNT_MISSING);
    }
    SyncPlan plan = new SyncPlan();
    NeoResponse refusal = validateSync(remittance, criteria, params, plan);
    return refusal != null ? refusal : applySync(remittance, plan);
  }

  /** What {@link #validateSync} accepted and {@link #applySync} writes. */
  static final class SyncPlan {
    /** Lines to delete: those of the removed dues and of the dues whose amount changed. */
    final List<String> lineIdsToDelete = new ArrayList<>();
    /** One Classic selection row per new or changed due. */
    final JSONArray selection = new JSONArray();
    int created;
    int updated;
    int removed;
  }

  /**
   * Every refusal of a sync, before anything is written: no ids, a {@code remove} id not in the
   * remittance (or also in {@code items}), an {@code items} id that is not a candidate, an invalid
   * amount. On success fills {@code plan}.
   *
   * @return the 422, or {@code null} when {@code plan} is ready to apply
   */
  private static NeoResponse validateSync(Remittance remittance, Criteria criteria,
      JSONObject params, SyncPlan plan) throws JSONException {
    Map<String, Object> requested = requestedAmounts(params.optJSONArray(P_ITEMS));
    Set<String> remove = idsOf(params.optJSONArray(P_REMOVE));
    if (requested.isEmpty() && remove.isEmpty()) {
      return NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_IDS_REQUIRED);
    }
    Map<String, IncludedDue> included = includedDues(remittance);
    List<String> notIncluded = notIncluded(remove, requested.keySet(), included.keySet());
    if (!notIncluded.isEmpty()) {
      return withInvalidIds(NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE,
          MSG_NOT_INCLUDED), notIncluded);
    }
    Map<String, BigDecimal> outstanding = outstandingOf(criteria, requested.keySet());
    List<String> notAvailable = new ArrayList<>(requested.keySet());
    notAvailable.removeAll(outstanding.keySet());
    if (!notAvailable.isEmpty()) {
      return withInvalidIds(NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE,
          MSG_NOT_AVAILABLE), notAvailable);
    }
    List<String> invalidAmount = new ArrayList<>();
    Map<String, BigDecimal> amounts = validAmounts(requested, outstanding, invalidAmount);
    if (!invalidAmount.isEmpty()) {
      return withInvalidIds(RemittanceLineAmount.invalid(singleMax(invalidAmount, outstanding)),
          invalidAmount);
    }
    fillPlan(plan, remove, included, amounts, outstanding);
    return null;
  }

  /** @return the {@code remove} ids not in the remittance or also requested in {@code items} */
  private static List<String> notIncluded(Set<String> remove, Set<String> requested,
      Set<String> included) {
    List<String> refused = new ArrayList<>();
    for (String id : remove) {
      if (!included.contains(id) || requested.contains(id)) {
        refused.add(id);
      }
    }
    return refused;
  }

  /**
   * @param invalid collects the ids whose amount fails {@link RemittanceLineAmount#isValid}
   * @return the valid amounts, by id, in request order
   */
  private static Map<String, BigDecimal> validAmounts(Map<String, Object> requested,
      Map<String, BigDecimal> outstanding, List<String> invalid) {
    Map<String, BigDecimal> amounts = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : requested.entrySet()) {
      BigDecimal amount = RemittanceLineAmount.parse(entry.getValue());
      if (RemittanceLineAmount.isValid(amount, outstanding.get(entry.getKey()))) {
        amounts.put(entry.getKey(), amount);
      } else {
        invalid.add(entry.getKey());
      }
    }
    return amounts;
  }

  /** @return the max of the single invalid id, or {@code null} when several are invalid */
  private static BigDecimal singleMax(List<String> invalid, Map<String, BigDecimal> outstanding) {
    return invalid.size() == 1 ? outstanding.get(invalid.get(0)) : null;
  }

  /**
   * The writes of an accepted sync: the removed dues' lines go; a due already in with the same
   * amount is left alone; a changed due's lines go and it is selected again; a new due is selected.
   */
  private static void fillPlan(SyncPlan plan, Set<String> remove,
      Map<String, IncludedDue> included, Map<String, BigDecimal> amounts,
      Map<String, BigDecimal> outstanding) throws JSONException {
    for (String id : remove) {
      plan.lineIdsToDelete.addAll(included.get(id).lineIds);
      plan.removed++;
    }
    for (Map.Entry<String, BigDecimal> entry : amounts.entrySet()) {
      IncludedDue due = included.get(entry.getKey());
      if (due == null) {
        plan.created++;
      } else if (due.amount.compareTo(entry.getValue()) == 0) {
        continue;
      } else {
        plan.lineIdsToDelete.addAll(due.lineIds);
        plan.updated++;
      }
      plan.selection.put(RemittanceDueSelection.row(entry.getKey(), entry.getValue(),
          outstanding.get(entry.getKey())));
    }
  }

  /**
   * Writes an accepted {@code plan}: the deletes, a flush, then one Classic run for the selection.
   * A REM refusal rolls everything back.
   *
   * @return {@code {created, updated, removed, message}}, or the 422 of a REM refusal
   */
  private static NeoResponse applySync(Remittance remittance, SyncPlan plan)
      throws JSONException {
    if (!plan.lineIdsToDelete.isEmpty()) {
      deleteLines(remittance, plan.lineIdsToDelete);
    }
    OBDal.getInstance().flush();
    String message = "";
    if (plan.selection.length() > 0) {
      NeoResponse result = RemittanceDueSelection.run(RemittanceDueSelection.params(remittance, plan.selection));
      String failure = RemittanceDueSelection.failureText(result);
      if (failure != null) {
        log.warn("syncDueItems on remittance {} refused by REM: {}", remittance.getId(), failure);
        rollback();
        return NeoMessageText.error(NeoActionContract.SC_UNPROCESSABLE, MSG_SELECTION_FAILED,
            failure);
      }
      message = RemittanceDueSelection.successText(result, plan.created + (long) plan.updated);
    }
    JSONObject body = new JSONObject();
    body.put("created", plan.created);
    body.put("updated", plan.updated);
    body.put("removed", plan.removed);
    body.put(KEY_MESSAGE, message);
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
        // A due already in this remittance stays listed even if the due date moved earlier.
        .append(" and (ips.expectedDate < :dueDateLimit or exists (select 1 from ")
        .append(RemittanceLine.ENTITY_NAME).append(" as mine where mine.paymentScheduleDetail.id")
        .append(" = psd.id and mine.remittance.id = :remittance))")
        .append(" and cur.id = :currency")
        .append(" and not exists (select 1 from ").append(RemittanceLine.ENTITY_NAME)
        .append(" as other where other.paymentScheduleDetail.id = psd.id")
        .append(" and other.remittance.id <> :remittance and other.remittance.processed = false)");
    if (criteria.direction != Direction.ANY) {
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
    private final Direction direction;

    private Criteria(String clientId, String remittanceId, Collection<String> organizations,
        Date dueDateLimit, String currencyId, Direction direction) {
      this.clientId = clientId;
      this.remittanceId = remittanceId;
      this.organizations = organizations;
      this.dueDateLimit = dueDateLimit;
      this.currencyId = currencyId;
      this.direction = direction;
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

    /** The start of the day after {@code date}: candidates expected before it are in. */
    private static Date dayAfter(Date date) {
      LocalDate next = toLocalDate(date).plusDays(1);
      return Date.from(next.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    void bind(Query<?> query) {
      query.setParameter("client", clientId);
      query.setParameter(Q_REMITTANCE, remittanceId);
      query.setParameterList("orgs", organizations);
      query.setParameter("dueDateLimit", dueDateLimit);
      query.setParameter("currency", currencyId);
      if (direction != Direction.ANY) {
        query.setParameter("sales", direction == Direction.SALES);
      }
    }
  }

  /** Which invoices a remittance takes: sales, purchase, or both (no filter). */
  enum Direction {
    SALES, PURCHASE, ANY
  }

  /**
   * The direction filter, as Classic's {@code RemittanceUtils.getPaymentType} decides it from the
   * account's link to the type's method: pay-in only → sales invoices, pay-out only → purchase
   * invoices, otherwise (both, neither, no link) no filter.
   */
  static Direction direction(FIN_FinancialAccount account, FIN_PaymentMethod method) {
    List<FinAccPaymentMethod> links = session().createQuery(
        "from " + FinAccPaymentMethod.ENTITY_NAME + " as l where l.account.id = :account"
            + " and l.paymentMethod.id = :method order by l.creationDate desc",
        FinAccPaymentMethod.class)
        .setParameter("account", account.getId())
        .setParameter("method", method.getId())
        .setMaxResults(1)
        .list();
    if (links.isEmpty()) {
      return Direction.ANY;
    }
    boolean in = Boolean.TRUE.equals(links.get(0).isPayinAllow());
    boolean out = Boolean.TRUE.equals(links.get(0).isPayoutAllow());
    if (in && !out) {
      return Direction.SALES;
    }
    if (out && !in) {
      return Direction.PURCHASE;
    }
    return Direction.ANY;
  }

  /** A due already in the remittance: its lines and their total amount. */
  static final class IncludedDue {
    final List<String> lineIds = new ArrayList<>();
    BigDecimal amount = BigDecimal.ZERO;
  }

  /** @return the dues (invoice payment schedules) of the remittance's lines, by schedule id */
  static Map<String, IncludedDue> includedDues(Remittance remittance) {
    List<Object[]> rows = session().createQuery(
        "select rl.paymentScheduleDetail.invoicePaymentSchedule.id, rl.id, rl.amount from "
            + RemittanceLine.ENTITY_NAME + " as rl where rl.remittance.id = :remittance"
            + " and rl.paymentScheduleDetail.invoicePaymentSchedule is not null"
            + " order by rl.lineNo",
        Object[].class)
        .setParameter(Q_REMITTANCE, remittance.getId())
        .list();
    Map<String, IncludedDue> dues = new LinkedHashMap<>();
    for (Object[] row : rows) {
      IncludedDue due = dues.computeIfAbsent((String) row[0], k -> new IncludedDue());
      due.lineIds.add((String) row[1]);
      due.amount = due.amount.add(toBigDecimal(row[2]));
    }
    return dues;
  }

  /** @return the outstanding amount of each of {@code ids} that is a candidate, by id */
  private static Map<String, BigDecimal> outstandingOf(Criteria criteria, Set<String> ids) {
    Map<String, BigDecimal> outstanding = new HashMap<>();
    if (ids.isEmpty()) {
      return outstanding;
    }
    StringBuilder where = candidateWhere(criteria).append(" and ips.id in (:ids)");
    Query<Object[]> rows = session().createQuery(SELECT_ROWS + from() + where + GROUP_ORDER,
        Object[].class);
    criteria.bind(rows);
    rows.setParameterList(P_IDS, ids);
    for (Object[] row : rows.list()) {
      outstanding.put((String) row[0], toBigDecimal(row[4]));
    }
    return outstanding;
  }

  /** @return the requested amount of each due id, in request order (values not parsed yet) */
  private static Map<String, Object> requestedAmounts(JSONArray items) {
    Map<String, Object> requested = new LinkedHashMap<>();
    if (items == null) {
      return requested;
    }
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      String id = item != null ? StringUtils.trimToNull(item.optString(P_ID, null)) : null;
      if (id != null) {
        requested.put(id, item.opt(P_AMOUNT));
      }
    }
    return requested;
  }

  /** Removes the given lines of {@code remittance}, as REM's own line removal does. */
  private static void deleteLines(Remittance remittance, List<String> lineIds) {
    Set<String> ids = new HashSet<>(lineIds);
    remittance.getREMRemittanceLineList().removeIf(line -> ids.contains(line.getId()));
    for (String id : lineIds) {
      RemittanceLine line = OBDal.getInstance().get(RemittanceLine.class, id);
      if (line != null) {
        OBDal.getInstance().remove(line);
      }
    }
  }

  private static NeoResponse withInvalidIds(NeoResponse refusal, List<String> ids)
      throws JSONException {
    JSONObject error = refusal.getBody() != null ? refusal.getBody().optJSONObject(KEY_ERROR) : null;
    if (error != null) {
      error.put(KEY_INVALID_IDS, new JSONArray(ids));
    }
    return refusal;
  }

  private static long countLines(Remittance remittance) {
    OBDal.getInstance().flush();
    Long count = session().createQuery("select count(rl.id) from " + RemittanceLine.ENTITY_NAME
        + " as rl where rl.remittance.id = :remittance", Long.class)
        .setParameter(Q_REMITTANCE, remittance.getId())
        .uniqueResult();
    return count != null ? count : 0L;
  }

  private static JSONObject item(Object[] row, Map<String, IncludedDue> inThisRemittance,
      LocalDate today) throws JSONException {
    JSONObject item = new JSONObject();
    String id = (String) row[0];
    LocalDate dueDate = row[3] instanceof Date ? toLocalDate((Date) row[3]) : null;
    item.put("id", id);
    item.put("documentNo", row[1]);
    item.put("invoiceId", row[6]);
    item.put("salesTransaction", Boolean.TRUE.equals(row[7]));
    item.put("invoiceDate", row[8] instanceof Date ? toLocalDate((Date) row[8]).toString()
        : JSONObject.NULL);
    item.put("dueDate", dueDate != null ? dueDate.toString() : JSONObject.NULL);
    item.put("dueStatus", dueStatus(dueDate, today));
    item.put("businessPartner", row[2]);
    item.put("businessPartnerId", row[10]);
    item.put("grandTotalAmount", toBigDecimal(row[9]));
    item.put("outstandingAmount", toBigDecimal(row[4]));
    item.put("currency", row[5]);
    IncludedDue due = inThisRemittance.get(id);
    item.put("alreadyInRemittance", due != null);
    if (due != null) {
      item.put("lineAmount", due.amount);
      item.put("lineIds", new JSONArray(due.lineIds));
    }
    return item;
  }

  /** @return {@value #DUE_OVERDUE}, {@value #DUE_TODAY} or {@value #DUE_PENDING} */
  static String dueStatus(LocalDate dueDate, LocalDate today) {
    if (dueDate == null) {
      return DUE_PENDING;
    }
    int cmp = dueDate.compareTo(today);
    if (cmp < 0) {
      return DUE_OVERDUE;
    }
    return cmp == 0 ? DUE_TODAY : DUE_PENDING;
  }

  /** Seam: the server's calendar day (JVM zone), as the remittance due-date rule uses. */
  static LocalDate today() {
    return LocalDate.now();
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

  static LocalDate toLocalDate(Date date) {
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
