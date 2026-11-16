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

package com.etendoerp.go.portal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;

/**
 * The only way the portal reads sales documents (ETP-6209, plan §B.1, §B.3).
 *
 * <p><b>Every query built here carries the same scope, and takes it only from a
 * {@link PortalSession}:</b> the tenant, the Business Partner, active rows, the document kind's own
 * discriminator (sales side; quotation vs order by document sub-type; outbound shipments only)
 * and the kind's <b>allow-list</b> of visible statuses. That is security invariant 3 of the plan,
 * centralised so there is one place to read to confirm it and no endpoint composes its own
 * document query. An allow-list rather than a deny-list: a status nobody listed is hidden.
 *
 * <p>Queries are plain HQL on the session. DAL's own client/organization filters are not involved:
 * a portal request has no session to filter against (see {@link PortalLinkDal}), and the scope is
 * stated explicitly instead.
 */
final class PortalDocumentQuery {

  /** The main entity's alias and the start of its condition, in every list query. */
  private static final String ALIAS_WHERE = " e where ";

  /** Visible {@code DocStatus} per kind (plan §B.1). Drafts and in-process states never show. */
  private static final Map<PortalDocumentKind, List<String>> VISIBLE_STATUSES =
      new EnumMap<>(PortalDocumentKind.class);

  static {
    VISIBLE_STATUSES.put(PortalDocumentKind.QUOTATION,
        List.of("UE", "CO", "ETGO_AC", "CA", "ETGO_CI", "CL", "CJ", "VO"));
    VISIBLE_STATUSES.put(PortalDocumentKind.ORDER, List.of("CO", "CL", "VO"));
    VISIBLE_STATUSES.put(PortalDocumentKind.SHIPMENT, List.of("CO", "CL", "VO"));
    VISIBLE_STATUSES.put(PortalDocumentKind.INVOICE, List.of("CO", "CL", "VO"));
  }

  /**
   * The customer-facing status groups of each kind's list tabs (ETP-6209 design rework, plan
   * §B.4): a small set of answers to "what do I still have to do", each one a subset of the kind's
   * allow-list plus, where the status alone cannot say it, a condition on the document's own data.
   * "all" is implicit (no group). The badges inside a group keep the app's own status labels.
   */
  private static final Map<PortalDocumentKind, Map<String, Group>> GROUPS =
      new EnumMap<>(PortalDocumentKind.class);
  /** An instalment still owing money is past due. */
  private static final String HAS_OVERDUE_INSTALMENT = "exists (select 1 from FIN_Payment_Schedule"
      + " ps where ps.invoice.id = e.id and ps.active = true and ps.outstandingAmount > 0"
      + " and ps.dueDate < :today)";
  /** A line not yet fully delivered or not yet fully invoiced. */
  private static final String HAS_PENDING_LINE = "exists (select 1 from OrderLine l"
      + " where l.salesOrder.id = e.id and l.active = true"
      + " and (l.orderedQuantity <> l.deliveredQuantity or l.orderedQuantity <> l.invoicedQuantity))";

  static {
    Map<String, Group> quotation = new LinkedHashMap<>();
    // CO is a quotation completed but not yet answered, the same "awaiting" the app's convert and
    // reject actions accept (QuotationTopbarActions: CO or UE). CL and VO show under "all" only.
    quotation.put("awaiting", new Group(List.of("UE", "CO"), null));
    // ETGO_AC: accepted by the customer from the portal, not yet converted (Part C, PO Q1).
    quotation.put("accepted", new Group(List.of("ETGO_AC", "CA", "ETGO_CI"), null));
    quotation.put("rejected", new Group(List.of("CJ"), null));
    GROUPS.put(PortalDocumentKind.QUOTATION, quotation);

    Map<String, Group> order = new LinkedHashMap<>();
    order.put("inProgress", new Group(List.of("CO"), HAS_PENDING_LINE));
    order.put("completed", new Group(List.of("CO", "CL"),
        "(e.documentStatus = 'CL' or not " + HAS_PENDING_LINE + ")"));
    order.put("voided", new Group(List.of("VO"), null));
    GROUPS.put(PortalDocumentKind.ORDER, order);

    Map<String, Group> shipment = new LinkedHashMap<>();
    shipment.put("delivered", new Group(List.of("CO", "CL"), null));
    shipment.put("voided", new Group(List.of("VO"), null));
    GROUPS.put(PortalDocumentKind.SHIPMENT, shipment);

    Map<String, Group> invoice = new LinkedHashMap<>();
    // "Pendientes" is everything still owed, overdue included ("Ver facturas pendientes" of the
    // home opens it); "Vencidas" is the overdue part of it.
    invoice.put("pending", new Group(List.of("CO", "CL"), "e.outstandingAmount <> 0"));
    invoice.put("overdue", new Group(List.of("CO", "CL"), HAS_OVERDUE_INSTALMENT));
    invoice.put("paid", new Group(List.of("CO", "CL"), "e.outstandingAmount = 0"));
    GROUPS.put(PortalDocumentKind.INVOICE, invoice);
  }

  /** One status group: the statuses it covers and an optional extra HQL condition on {@code e}. */
  static final class Group {
    final List<String> statuses;
    final String condition;

    Group(List<String> statuses, String condition) {
      this.statuses = statuses;
      this.condition = condition;
    }

    boolean usesToday() {
      return condition != null && condition.contains(":" + P_TODAY);
    }
  }

  /** Quotation and proposal sub-types of {@code C_DocType.DocSubTypeSO}. */
  private static final String QUOTATION_SUBTYPES = "('OB', 'ON')";
  /**
   * Sub-types the order list never shows: the quotations (their own kind) and the return-material
   * orders ({@code RM}), which are out of the portal's scope (PO decision, ETP-6209).
   */
  private static final String NON_ORDER_SUBTYPES = "('OB', 'ON', 'RM')";
  /** {@code M_InOut.MovementType} of a customer shipment (outbound). */
  private static final String CUSTOMER_SHIPMENT = "C-";

  private static final String P_CLIENT = "clientId";
  private static final String P_BPARTNER = "bpartnerId";
  private static final String P_STATUSES = "statuses";
  private static final String P_IDS = "ids";
  private static final String P_FROM = "dateFrom";
  private static final String P_TO = "dateTo";
  private static final String P_SEARCH = "search";
  private static final String P_TODAY = "today";

  private PortalDocumentQuery() {
  }

  /** The list filters a caller may set; anything else about the query is fixed by the scope. */
  static final class Filter {
    final List<String> statuses;
    final Date dateFrom;
    final Date dateToExclusive;
    final String search;
    final Group group;
    final Date today;

    Filter(List<String> statuses, Date dateFrom, Date dateToExclusive, String search) {
      this(statuses, dateFrom, dateToExclusive, search, null, null);
    }

    Filter(List<String> statuses, Date dateFrom, Date dateToExclusive, String search, Group group,
        Date today) {
      this.statuses = statuses;
      this.dateFrom = dateFrom;
      this.dateToExclusive = dateToExclusive;
      this.search = StringUtils.trimToNull(search);
      this.group = group;
      this.today = today;
    }

    /** Only the given group, for the home's tiles. */
    static Filter ofGroup(Group group, Date today) {
      return new Filter(null, null, null, null, group, today);
    }

    static Filter none() {
      return new Filter(null, null, null, null);
    }
  }

  /**
   * @param kind a document kind
   * @return the statuses a customer may see for it, in the app's display order
   */
  static List<String> visibleStatuses(PortalDocumentKind kind) {
    return VISIBLE_STATUSES.get(kind);
  }

  /**
   * @param kind a document kind
   * @return its status groups' codes, in tab order
   */
  static List<String> groupCodes(PortalDocumentKind kind) {
    return new ArrayList<>(GROUPS.get(kind).keySet());
  }

  /**
   * @return the kind's group of that code, or {@code null} for "all", blank or an unknown code
   */
  static Group group(PortalDocumentKind kind, String code) {
    String normalized = StringUtils.trimToNull(code);
    return normalized == null ? null : GROUPS.get(kind).get(normalized);
  }

  /**
   * One page of a kind's documents, newest first, with {@code documentNo} as tie-breaker so paging
   * is deterministic.
   */
  static List<BaseOBObject> list(PortalSession session, PortalDocumentKind kind, Filter filter,
      int offset, int limit) {
    Query<BaseOBObject> query = session().createQuery("select e from " + kind.getEntityName()
        + ALIAS_WHERE + where(kind, filter) + " order by e." + kind.getDateProperty()
        + " desc, e.documentNo desc", BaseOBObject.class);
    bind(query, session, kind, filter);
    query.setFirstResult(Math.max(offset, 0));
    query.setMaxResults(limit);
    return query.list();
  }

  /**
   * Count and sums of the whole filtered set, per currency — never of a page.
   *
   * @return rows of {@code [currencyIso, count, taxBaseSum, grandTotalSum]}; the sums are
   *     {@code null} for a shipment, which carries no amount
   */
  static List<Object[]> totals(PortalSession session, PortalDocumentKind kind, Filter filter) {
    if (!kind.hasAmounts()) {
      return Collections.singletonList(
          new Object[] { null, count(session, kind, filter), null, null });
    }
    Query<Object[]> query = session().createQuery("select e.currency.iSOCode, count(e),"
        + " sum(e.summedLineAmount), sum(e.grandTotalAmount) from " + kind.getEntityName()
        + ALIAS_WHERE + where(kind, filter) + " group by e.currency.iSOCode", Object[].class);
    bind(query, session, kind, filter);
    return query.list();
  }

  /** Count of a kind's visible documents, optionally narrowed by the filter. */
  static long count(PortalSession session, PortalDocumentKind kind, Filter filter) {
    Query<Long> query = session().createQuery("select count(e) from " + kind.getEntityName()
        + ALIAS_WHERE + where(kind, filter), Long.class);
    bind(query, session, kind, filter);
    Long result = query.uniqueResult();
    return result == null ? 0L : result;
  }

  /**
   * One document within the session's scope, or {@code null} — which the caller answers as 404,
   * never 403 (a 403 would confirm the record exists).
   */
  static BaseOBObject findInScope(PortalSession session, PortalDocumentKind kind, String id) {
    if (StringUtils.isBlank(id)) {
      return null;
    }
    List<BaseOBObject> found = findAllInScope(session, kind, Collections.singleton(id));
    return found.isEmpty() ? null : found.get(0);
  }

  /** The subset of {@code ids} that is in the session's scope for {@code kind}. */
  static List<BaseOBObject> findAllInScope(PortalSession session, PortalDocumentKind kind,
      Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return Collections.emptyList();
    }
    Query<BaseOBObject> query = session().createQuery("select e from " + kind.getEntityName()
        + " e where e.id in (:" + P_IDS + ") and " + where(kind, Filter.none()),
        BaseOBObject.class);
    bind(query, session, kind, Filter.none());
    query.setParameterList(P_IDS, new LinkedHashSet<>(ids));
    return query.list();
  }

  /**
   * Per-invoice payment schedule aggregates for one page: the latest instalment due date (the
   * app's due date, {@code getLatestInstallmentDueDate}) and whether any instalment with money
   * still owed is past due. One grouped query for the whole page, never one per row.
   *
   * @return {@code invoiceId -> [latestDueDate, overdueInstalmentCount]}
   */
  static Map<String, Object[]> invoiceSchedules(Collection<String> invoiceIds, Date today) {
    if (invoiceIds == null || invoiceIds.isEmpty()) {
      return Collections.emptyMap();
    }
    Query<Object[]> query = session().createQuery("select ps.invoice.id, max(ps.dueDate),"
        + " sum(case when ps.dueDate < :" + P_TODAY + " and ps.outstandingAmount > 0"
        + " then 1 else 0 end) from FIN_Payment_Schedule ps where ps.invoice.id in (:" + P_IDS
        + ") and ps.active = true group by ps.invoice.id", Object[].class);
    query.setParameterList(P_IDS, new LinkedHashSet<>(invoiceIds));
    query.setParameter(P_TODAY, today);
    Map<String, Object[]> result = new HashMap<>();
    for (Object[] row : query.list()) {
      result.put((String) row[0], new Object[] { row[1], row[2] });
    }
    return result;
  }

  /**
   * Outstanding over the visible invoices still owed, per currency.
   *
   * @return rows of {@code [currencyIso, outstandingSum, invoiceCount]}
   */
  static List<Object[]> outstandingByCurrency(PortalSession session) {
    PortalDocumentKind kind = PortalDocumentKind.INVOICE;
    Query<Object[]> query = session().createQuery("select e.currency.iSOCode,"
        + " sum(e.outstandingAmount), count(e) from Invoice e where " + where(kind, Filter.none())
        + " and e.outstandingAmount > 0 group by e.currency.iSOCode", Object[].class);
    bind(query, session, kind, Filter.none());
    return query.list();
  }

  /**
   * Overdue money over the visible invoices, per currency: instalments past due with money still
   * owed.
   *
   * @return rows of {@code [currencyIso, overdueSum, earliestDueDate, invoiceCount]}
   */
  static List<Object[]> overdueByCurrency(PortalSession session, Date today) {
    PortalDocumentKind kind = PortalDocumentKind.INVOICE;
    Query<Object[]> query = session().createQuery("select e.currency.iSOCode,"
        + " sum(ps.outstandingAmount), min(ps.dueDate), count(distinct e.id)"
        + " from FIN_Payment_Schedule ps join ps.invoice e where " + where(kind, Filter.none())
        + " and ps.active = true and ps.outstandingAmount > 0 and ps.dueDate < :" + P_TODAY
        + " group by e.currency.iSOCode", Object[].class);
    bind(query, session, kind, Filter.none());
    query.setParameter(P_TODAY, today);
    return query.list();
  }

  /**
   * What the customer paid on the invoices issued since {@code from} (the home's "Pagado este
   * año"), per currency: {@code Invoice.totalPaid}, over completed and closed invoices that have a
   * payment at all.
   *
   * @return rows of {@code [currencyIso, paidSum, invoiceCount]}
   */
  static List<Object[]> paidSince(PortalSession session, Date from) {
    PortalDocumentKind kind = PortalDocumentKind.INVOICE;
    Filter filter = new Filter(List.of("CO", "CL"), from, null, null);
    Query<Object[]> query = session().createQuery("select e.currency.iSOCode, sum(e.totalPaid),"
        + " count(e) from Invoice e where " + where(kind, filter) + " and e.totalPaid > 0"
        + " group by e.currency.iSOCode", Object[].class);
    bind(query, session, kind, filter);
    return query.list();
  }

  /**
   * Invoiced amount per calendar month since {@code from}, per currency (the home's "Facturado por
   * mes"), over completed and closed invoices.
   *
   * @return rows of {@code [year, month, currencyIso, grandTotalSum]}
   */
  static List<Object[]> invoicedByMonth(PortalSession session, Date from) {
    PortalDocumentKind kind = PortalDocumentKind.INVOICE;
    Filter filter = new Filter(List.of("CO", "CL"), from, null, null);
    Query<Object[]> query = session().createQuery("select year(e.invoiceDate),"
        + " month(e.invoiceDate), e.currency.iSOCode, sum(e.grandTotalAmount) from Invoice e"
        + " where " + where(kind, filter) + " group by year(e.invoiceDate), month(e.invoiceDate),"
        + " e.currency.iSOCode", Object[].class);
    bind(query, session, kind, filter);
    return query.list();
  }

  /**
   * Completed and closed invoices issued since {@code from}: how many there are, and how many of
   * them have an instalment past due with money owed (the home's "Al día con X de Y facturas").
   *
   * @return {@code [total, overdue]}
   */
  static long[] invoicesSince(PortalSession session, Date from, Date today) {
    PortalDocumentKind kind = PortalDocumentKind.INVOICE;
    Filter all = new Filter(List.of("CO", "CL"), from, null, null);
    Filter overdue = new Filter(null, from, null, null, GROUPS.get(kind).get("overdue"), today);
    return new long[] { count(session, kind, all), count(session, kind, overdue) };
  }

  /**
   * The order generated from each of the given quotations, when it is in the session's scope:
   * the "Pedido PV…" line an accepted quotation's card shows. One query for a whole page.
   *
   * @return {@code quotationId -> [orderId, orderDocumentNo]}
   */
  static Map<String, String[]> ordersOfQuotations(PortalSession session,
      Collection<String> quotationIds) {
    if (quotationIds == null || quotationIds.isEmpty()) {
      return Collections.emptyMap();
    }
    PortalDocumentKind kind = PortalDocumentKind.ORDER;
    Query<Object[]> query = session().createQuery("select e.quotation.id, e.id, e.documentNo"
        + " from Order e where e.quotation.id in (:" + P_IDS + ") and "
        + where(kind, Filter.none()) + " order by e.orderDate", Object[].class);
    bind(query, session, kind, Filter.none());
    query.setParameterList(P_IDS, new LinkedHashSet<>(quotationIds));
    Map<String, String[]> result = new HashMap<>();
    for (Object[] row : query.list()) {
      result.putIfAbsent((String) row[0], new String[] { (String) row[1], (String) row[2] });
    }
    return result;
  }

  /**
   * Documents of every kind matching a search, newest first (the header search of the home).
   * Each kind contributes at most {@code limit}; the merged list is cut to {@code limit}.
   */
  static List<BaseOBObject> search(PortalSession session, String search, int limit) {
    if (StringUtils.isBlank(search)) {
      return Collections.emptyList();
    }
    Filter filter = new Filter(null, null, null, search);
    List<BaseOBObject> all = new ArrayList<>();
    for (PortalDocumentKind kind : PortalDocumentKind.values()) {
      all.addAll(list(session, kind, filter, 0, limit));
    }
    sortNewestFirst(all);
    return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
  }

  /** The {@code limit} newest visible documents of every kind, newest first. */
  static List<BaseOBObject> recent(PortalSession session, int limit) {
    List<BaseOBObject> all = new ArrayList<>();
    for (PortalDocumentKind kind : PortalDocumentKind.values()) {
      all.addAll(list(session, kind, Filter.none(), 0, limit));
    }
    sortNewestFirst(all);
    return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
  }

  private static void sortNewestFirst(List<BaseOBObject> documents) {
    documents.sort((a, b) -> compareNewestFirst(dateOf(a), dateOf(b)));
  }

  /** Newest first; a document without a date sorts after every dated one. */
  private static int compareNewestFirst(Date da, Date db) {
    if (da != null && db != null) {
      return db.compareTo(da);
    }
    if (da == null && db == null) {
      return 0;
    }
    return da == null ? 1 : -1;
  }

  /** The document date of any portal document (order, shipment or invoice date). */
  static Date dateOf(BaseOBObject document) {
    PortalDocumentKind kind = PortalDocumentKind.ofEntity(document.getEntityName());
    return kind == null ? null : (Date) document.get(kind.getDateProperty());
  }

  /** {@code 0} for {@code null}, so sums of an empty set never surface as {@code null}. */
  static BigDecimal amount(Object value) {
    return value instanceof BigDecimal ? (BigDecimal) value : BigDecimal.ZERO;
  }

  private static String where(PortalDocumentKind kind, Filter filter) {
    StringBuilder where = new StringBuilder()
        .append("e.client.id = :").append(P_CLIENT)
        .append(" and e.businessPartner.id = :").append(P_BPARTNER)
        .append(" and e.active = true")
        .append(" and e.salesTransaction = true")
        .append(" and e.documentStatus in (:").append(P_STATUSES).append(')')
        .append(" and ").append(discriminator(kind));
    if (filter.dateFrom != null) {
      where.append(" and e.").append(kind.getDateProperty()).append(" >= :").append(P_FROM);
    }
    if (filter.dateToExclusive != null) {
      where.append(" and e.").append(kind.getDateProperty()).append(" < :").append(P_TO);
    }
    if (filter.group != null && filter.group.condition != null) {
      where.append(" and ").append(filter.group.condition);
    }
    if (filter.search != null) {
      where.append(" and (lower(e.documentNo) like :").append(P_SEARCH)
          .append(" or lower(e.orderReference) like :").append(P_SEARCH)
          .append(" or lower(e.description) like :").append(P_SEARCH).append(')');
    }
    return where.toString();
  }

  private static String discriminator(PortalDocumentKind kind) {
    switch (kind) {
      case QUOTATION:
        return "e.transactionDocument.sOSubType in " + QUOTATION_SUBTYPES;
      case ORDER:
        return "(e.transactionDocument.sOSubType is null or e.transactionDocument.sOSubType not in "
            + NON_ORDER_SUBTYPES + ")";
      case SHIPMENT:
        return "e.movementType = '" + CUSTOMER_SHIPMENT + "'";
      default:
        return "1 = 1";
    }
  }

  private static void bind(Query<?> query, PortalSession session, PortalDocumentKind kind,
      Filter filter) {
    query.setParameter(P_CLIENT, session.getClientId());
    query.setParameter(P_BPARTNER, session.getBusinessPartnerId());
    query.setParameterList(P_STATUSES, statusesFor(kind, filter));
    if (filter.dateFrom != null) {
      query.setParameter(P_FROM, filter.dateFrom);
    }
    if (filter.dateToExclusive != null) {
      query.setParameter(P_TO, filter.dateToExclusive);
    }
    if (filter.search != null) {
      query.setParameter(P_SEARCH, "%" + escapeLike(filter.search.toLowerCase(Locale.ROOT)) + "%");
    }
    if (filter.group != null && filter.group.usesToday()) {
      query.setParameter(P_TODAY, filter.today);
    }
  }

  /**
   * The requested statuses intersected with the kind's allow-list: a status outside it is ignored,
   * never an error, and an empty intersection falls back to the whole allow-list.
   */
  private static Set<String> statusesFor(PortalDocumentKind kind, Filter filter) {
    List<String> allowed = VISIBLE_STATUSES.get(kind);
    if (filter.group != null) {
      // A group's statuses are a subset of the allow-list by construction; intersected anyway so
      // a mistake in GROUPS can never widen what a customer sees.
      Set<String> grouped = new LinkedHashSet<>(filter.group.statuses);
      grouped.retainAll(allowed);
      return grouped;
    }
    if (filter.statuses == null || filter.statuses.isEmpty()) {
      return new LinkedHashSet<>(allowed);
    }
    Set<String> requested = new LinkedHashSet<>();
    for (String status : filter.statuses) {
      if (allowed.contains(status)) {
        requested.add(status);
      }
    }
    return requested.isEmpty() ? new LinkedHashSet<>(allowed) : requested;
  }

  /**
   * The search is a substring, not a pattern: {@code %} and {@code _} typed by the user match
   * themselves ({@code \\} is PostgreSQL's default {@code LIKE} escape).
   */
  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static Session session() {
    return OBDal.getInstance().getSession();
  }
}
