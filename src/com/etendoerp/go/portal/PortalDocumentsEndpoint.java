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
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.Query;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.PaymentTerm;
import org.openbravo.model.financialmgmt.tax.TaxRate;

import com.etendoerp.go.schemaforge.AnonymousAttachmentStreamer;

/**
 * The customer portal's document reads (ETP-6209, plan §B.3–§B.7): the home aggregates, the list
 * of one document kind, one document's read-only detail, and its printable.
 *
 * <pre>
 * GET /sws/portal/home                                  balances, per-kind tiles, recent documents
 * GET /sws/portal/documents?type=&amp;status=&amp;dateFrom=&amp;dateTo=&amp;q=&amp;limit=&amp;offset=
 * GET /sws/portal/documents/{kind}/{id}                 header, lines, taxes, payments, links
 * GET /sws/portal/documents/{kind}/{id}/pdf             the main attachment, streamed
 * </pre>
 *
 * <p>Every read goes through {@link PortalDocumentQuery}, so the scope (tenant, Business Partner,
 * visible statuses, kind) comes only from the validated link. Out-of-scope, hidden or mistyped
 * documents answer 404, never 403. Amounts are JSON numbers from {@link BigDecimal}s and dates are
 * calendar days ({@code yyyy-MM-dd}); formatting is the browser's job ({@code formatCurrency},
 * {@code formatCalendarDate}).
 *
 * <p><b>No N+1 on lists:</b> a page is one query, its invoice payment state one grouped query,
 * the totals one grouped query; the home is a fixed number of aggregate queries whatever the
 * customer's history size.
 */
final class PortalDocumentsEndpoint {

  /** Calendar days travel as {@code yyyy-MM-dd}, both ways. */
  private static final String DATE_FORMAT = "yyyy-MM-dd";
  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;
  private static final int RECENT_LIMIT = 5;
  private static final String KIND = "kind";
  private static final String ID = "id";
  private static final String CURRENCY = "currency";
  private static final String COUNT = "count";
  private static final String DOCUMENT_STATUS_UNDER_EVALUATION = "UE";

  private PortalDocumentsEndpoint() {
  }

  // ─── Home ────────────────────────────────────────────────────────────────

  /**
   * The home's real data: the pending balance per currency (outstanding over the visible invoices,
   * and the part of it that is overdue), one tile per kind with its count and highlight, and the
   * newest documents.
   */
  static JSONObject home(PortalSession session) throws JSONException {
    Date today = today();
    JSONObject body = new JSONObject();

    JSONArray balances = new JSONArray();
    Map<String, Object[]> overdue = new java.util.HashMap<>();
    for (Object[] row : PortalDocumentQuery.overdueByCurrency(session, today)) {
      overdue.put((String) row[0], row);
    }
    for (Object[] row : PortalDocumentQuery.outstandingByCurrency(session)) {
      JSONObject balance = new JSONObject();
      String currency = (String) row[0];
      balance.put(CURRENCY, StringUtils.defaultString(currency));
      balance.put("outstanding", PortalDocumentQuery.amount(row[1]));
      balance.put("pendingCount", ((Number) row[2]).longValue());
      Object[] late = overdue.get(currency);
      balance.put("overdue", late == null ? BigDecimal.ZERO : PortalDocumentQuery.amount(late[1]));
      balance.put("overdueSince", late == null ? JSONObject.NULL : formatDate((Date) late[2]));
      balance.put("overdueCount", late == null ? 0L : ((Number) late[3]).longValue());
      balances.put(balance);
    }
    body.put("balances", balances);

    long overdueInvoices = 0;
    for (Object[] row : overdue.values()) {
      overdueInvoices += ((Number) row[3]).longValue();
    }
    JSONObject tiles = new JSONObject();
    for (PortalDocumentKind kind : PortalDocumentKind.values()) {
      JSONObject tile = new JSONObject();
      tile.put(COUNT, PortalDocumentQuery.count(session, kind, PortalDocumentQuery.Filter.none()));
      tile.put("highlight", highlight(session, kind, overdueInvoices, today));
      tiles.put(kind.name(), tile);
    }
    body.put("tiles", tiles);

    JSONArray recent = new JSONArray();
    List<BaseOBObject> documents = PortalDocumentQuery.recent(session, RECENT_LIMIT);
    Map<String, Object[]> schedules = PortalDocumentQuery.invoiceSchedules(invoiceIds(documents),
        today);
    for (BaseOBObject document : documents) {
      recent.put(row(document, schedules));
    }
    body.put("recent", recent);
    return body;
  }

  /**
   * The one figure a tile calls out: quotations awaiting the customer's answer, overdue invoices,
   * shipments of the current month. Orders have no single honest "needs you" figure, so none.
   */
  private static Object highlight(PortalSession session, PortalDocumentKind kind,
      long overdueInvoices, Date today) throws JSONException {
    JSONObject highlight = new JSONObject();
    switch (kind) {
      case QUOTATION:
        highlight.put("code", "awaitingAnswer");
        highlight.put(COUNT, PortalDocumentQuery.count(session, kind,
            new PortalDocumentQuery.Filter(Collections.singletonList(
                DOCUMENT_STATUS_UNDER_EVALUATION), null, null, null)));
        return highlight;
      case INVOICE:
        highlight.put("code", "overdue");
        highlight.put(COUNT, overdueInvoices);
        return highlight;
      case SHIPMENT:
        LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
        highlight.put("code", "thisMonth");
        highlight.put(COUNT, PortalDocumentQuery.count(session, kind,
            new PortalDocumentQuery.Filter(null, toDate(firstOfMonth), null, null)));
        return highlight;
      default:
        return JSONObject.NULL;
    }
  }

  // ─── List ────────────────────────────────────────────────────────────────

  /**
   * One page of one kind. Unknown or missing {@code type} answers {@code null} (the caller's 404);
   * every other malformed parameter is ignored rather than failing the page.
   */
  static JSONObject list(PortalSession session, String type, String status, String dateFrom,
      String dateTo, String search, String limitParam, String offsetParam) throws JSONException {
    PortalDocumentKind kind = kindOf(type);
    if (kind == null) {
      return null;
    }
    Date from = parseDate(dateFrom);
    Date toInclusive = parseDate(dateTo);
    Date toExclusive = toInclusive == null ? null
        : toDate(toLocalDate(toInclusive).plusDays(1));
    // Each value trimmed (`?status=CO, CL`); blanks dropped, and nothing left means no filter.
    List<String> statuses = StringUtils.isBlank(status) ? null
        : Arrays.stream(StringUtils.split(status, ','))
            .map(StringUtils::trimToNull)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    if (statuses != null && statuses.isEmpty()) {
      statuses = null;
    }
    PortalDocumentQuery.Filter filter = new PortalDocumentQuery.Filter(statuses, from, toExclusive,
        search);
    int limit = Math.min(Math.max(parseInt(limitParam, DEFAULT_LIMIT), 1), MAX_LIMIT);
    int offset = Math.max(parseInt(offsetParam, 0), 0);

    List<BaseOBObject> documents = PortalDocumentQuery.list(session, kind, filter, offset, limit);
    Map<String, Object[]> schedules = kind == PortalDocumentKind.INVOICE
        ? PortalDocumentQuery.invoiceSchedules(invoiceIds(documents), today())
        : Collections.emptyMap();
    JSONArray rows = new JSONArray();
    for (BaseOBObject document : documents) {
      rows.put(row(document, schedules));
    }
    long totalCount = 0;
    JSONArray totals = new JSONArray();
    for (Object[] total : PortalDocumentQuery.totals(session, kind, filter)) {
      long count = ((Number) total[1]).longValue();
      totalCount += count;
      JSONObject entry = new JSONObject();
      entry.put(CURRENCY, StringUtils.defaultString((String) total[0]));
      entry.put(COUNT, count);
      entry.put("taxBase", total[2] == null ? JSONObject.NULL : total[2]);
      entry.put("grandTotal", total[3] == null ? JSONObject.NULL : total[3]);
      totals.put(entry);
    }
    JSONObject body = new JSONObject();
    body.put("type", kind.name());
    body.put("documents", rows);
    body.put("totalCount", totalCount);
    body.put("offset", offset);
    body.put("limit", limit);
    body.put("statuses", new JSONArray(PortalDocumentQuery.visibleStatuses(kind)));
    body.put("totals", totals);
    return body;
  }

  // ─── Detail ──────────────────────────────────────────────────────────────

  /**
   * One document's read-only detail, or {@code null} when it is not in the session's scope (the
   * caller's 404).
   */
  static JSONObject detail(PortalSession session, String type, String id) throws JSONException {
    PortalDocumentKind kind = kindOf(type);
    BaseOBObject document = kind == null ? null
        : PortalDocumentQuery.findInScope(session, kind, id);
    if (document == null) {
      return null;
    }
    Map<String, Object[]> schedules = kind == PortalDocumentKind.INVOICE
        ? PortalDocumentQuery.invoiceSchedules(Collections.singletonList(id), today())
        : Collections.emptyMap();
    JSONObject body = row(document, schedules);
    body.put("description", text(document.get("description")));
    body.put("paymentMethod", nameOf(document.get("paymentMethod")));
    body.put("paymentTerms", nameOf(document.get("paymentTerms")));
    if (document instanceof Order) {
      body.put("scheduledDeliveryDate", formatDate(((Order) document).getScheduledDeliveryDate()));
    }
    JSONObject customer = new JSONObject();
    BusinessPartner partner = (BusinessPartner) document.get("businessPartner");
    customer.put("name", partner == null ? "" : StringUtils.defaultString(partner.getName()));
    Location address = (Location) document.get("partnerAddress");
    customer.put("address", address == null ? "" : StringUtils.defaultString(address.getName()));
    body.put("customer", customer);
    body.put("issuer", StringUtils.defaultString(session.getTenantName()));
    body.put("lines", lines(kind, id));
    body.put("taxes", taxes(kind, id));
    if (kind == PortalDocumentKind.INVOICE) {
      body.put("installments", installments(id));
    }
    JSONArray related = new JSONArray();
    List<BaseOBObject> links = PortalDocumentLinks.related(session, kind, id);
    Map<String, Object[]> linkSchedules = PortalDocumentQuery.invoiceSchedules(invoiceIds(links),
        today());
    for (BaseOBObject link : links) {
      related.put(row(link, linkSchedules));
    }
    body.put("related", related);
    body.put("hasPdf", mainAttachment(session, kind, id) != null);
    return body;
  }

  /**
   * A document's main attachment — the very file the email's download link serves — when it
   * belongs to the session's tenant; otherwise {@code null}.
   *
   * <p>Resolved through {@link AnonymousAttachmentStreamer}, the email link's session-less path
   * (ETP-6209 QA BUG-1): native SQL plus an explicit check against the link's client. The
   * session-based {@code NeoAttachmentsHelper#handleGetMain} cannot be used here: it hides an
   * attachment that is not visible to the current tenant, and a portal request has no tenant.
   * The caller must already have checked that the document itself is in the session's scope.
   */
  static Attachment mainAttachment(PortalSession session, PortalDocumentKind kind, String id) {
    return AnonymousAttachmentStreamer.findMainAttachmentOfClient(kind.getTableName(), id,
        session.getClientId());
  }

  /**
   * @param type a kind code, as in {@code ?type=} ({@code invoice}) or the enum name
   * @return the kind, or {@code null}
   */
  static PortalDocumentKind kindOf(String type) {
    String normalized = StringUtils.trimToNull(type);
    return normalized == null ? null
        : PortalDocumentKind.fromCode(normalized.toUpperCase(Locale.ROOT)).orElse(null);
  }

  // ─── Shapes ──────────────────────────────────────────────────────────────

  /** The summary every list, the home and the related documents share. */
  private static JSONObject row(BaseOBObject document, Map<String, Object[]> schedules)
      throws JSONException {
    PortalDocumentKind kind = PortalDocumentKind.of(document);
    JSONObject row = new JSONObject();
    row.put(KIND, kind == null ? "" : kind.name());
    row.put(ID, document.getId());
    row.put("documentNo", text(document.get("documentNo")));
    row.put("isCreditNote", PortalDocumentKind.isCreditNote(document));
    String reference = StringUtils.trimToNull((String) document.get("orderReference"));
    row.put("reference", reference != null ? reference : text(document.get("description")));
    row.put("date", formatDate(PortalDocumentQuery.dateOf(document)));
    row.put("documentStatus", text(document.get("documentStatus")));
    if (kind != null && kind.hasAmounts()) {
      Currency currency = (Currency) document.get("currency");
      row.put(CURRENCY, currency == null ? "" : StringUtils.defaultString(currency.getISOCode()));
      row.put("taxBase", PortalDocumentQuery.amount(document.get("summedLineAmount")));
      row.put("grandTotal", PortalDocumentQuery.amount(document.get("grandTotalAmount")));
    } else {
      row.put(CURRENCY, JSONObject.NULL);
      row.put("taxBase", JSONObject.NULL);
      row.put("grandTotal", JSONObject.NULL);
    }
    if (kind == PortalDocumentKind.QUOTATION) {
      row.put("validUntil", formatDate(((Order) document).getValidUntil()));
    }
    if (document instanceof Invoice) {
      Object[] schedule = schedules.get(document.getId());
      row.put("outstandingAmount",
          PortalDocumentQuery.amount(((Invoice) document).getOutstandingAmount()));
      row.put("dueDate", schedule == null ? JSONObject.NULL : formatDate((Date) schedule[0]));
      row.put("overdue", schedule != null && ((Number) schedule[1]).longValue() > 0);
    }
    return row;
  }

  private static JSONArray lines(PortalDocumentKind kind, String id) throws JSONException {
    JSONArray lines = new JSONArray();
    String hql;
    switch (kind) {
      case SHIPMENT:
        hql = "select l from MaterialMgmtShipmentInOutLine l left join fetch l.product"
            + " left join fetch l.uOM where l.shipmentReceipt.id = :id and l.active = true"
            + " order by l.lineNo";
        break;
      case INVOICE:
        hql = "select l from InvoiceLine l left join fetch l.product left join fetch l.tax"
            + " left join fetch l.uOM where l.invoice.id = :id and l.active = true"
            + " order by l.lineNo";
        break;
      default:
        hql = "select l from OrderLine l left join fetch l.product left join fetch l.tax"
            + " left join fetch l.uOM where l.salesOrder.id = :id and l.active = true"
            + " order by l.lineNo";
        break;
    }
    Query<BaseOBObject> query = OBDal.getInstance().getSession()
        .createQuery(hql, BaseOBObject.class);
    query.setParameter(ID, id);
    for (BaseOBObject line : query.list()) {
      JSONObject json = new JSONObject();
      Product product = (Product) line.get("product");
      json.put("product", product == null ? "" : StringUtils.defaultString(product.getName()));
      json.put("productCode",
          product == null ? "" : StringUtils.defaultString(product.getSearchKey()));
      json.put("description", text(line.get("description")));
      UOM uom = (UOM) line.get("uOM");
      json.put("uom", uom == null ? "" : StringUtils.defaultIfBlank(uom.getSymbol(),
          StringUtils.defaultString(uom.getName())));
      if (kind == PortalDocumentKind.SHIPMENT) {
        json.put("quantity", PortalDocumentQuery.amount(line.get("movementQuantity")));
      } else {
        json.put("quantity", PortalDocumentQuery.amount(line.get(
            kind == PortalDocumentKind.INVOICE ? "invoicedQuantity" : "orderedQuantity")));
        json.put("unitPrice", PortalDocumentQuery.amount(line.get("unitPrice")));
        json.put("discount", kind == PortalDocumentKind.INVOICE ? JSONObject.NULL
            : PortalDocumentQuery.amount(line.get("discount")));
        json.put("tax", nameOf(line.get("tax")));
        json.put("amount", PortalDocumentQuery.amount(line.get("lineNetAmount")));
      }
      lines.put(json);
    }
    return lines;
  }

  private static JSONArray taxes(PortalDocumentKind kind, String id) throws JSONException {
    JSONArray taxes = new JSONArray();
    if (!kind.hasAmounts()) {
      return taxes;
    }
    String hql = kind == PortalDocumentKind.INVOICE
        ? "select t from InvoiceTax t left join fetch t.tax where t.invoice.id = :id"
            + " and t.active = true order by t.lineNo"
        : "select t from OrderTax t left join fetch t.tax where t.salesOrder.id = :id"
            + " and t.active = true order by t.lineNo";
    Query<BaseOBObject> query = OBDal.getInstance().getSession()
        .createQuery(hql, BaseOBObject.class);
    query.setParameter(ID, id);
    for (BaseOBObject tax : query.list()) {
      JSONObject json = new JSONObject();
      json.put("tax", nameOf(tax.get("tax")));
      json.put("taxableAmount", PortalDocumentQuery.amount(tax.get("taxableAmount")));
      json.put("taxAmount", PortalDocumentQuery.amount(tax.get("taxAmount")));
      taxes.put(json);
    }
    return taxes;
  }

  private static JSONArray installments(String invoiceId) throws JSONException {
    JSONArray installments = new JSONArray();
    Query<BaseOBObject> query = OBDal.getInstance().getSession().createQuery(
        "select ps from FIN_Payment_Schedule ps where ps.invoice.id = :id and ps.active = true"
            + " order by ps.dueDate", BaseOBObject.class);
    query.setParameter(ID, invoiceId);
    for (BaseOBObject schedule : query.list()) {
      JSONObject json = new JSONObject();
      json.put("dueDate", formatDate((Date) schedule.get("dueDate")));
      json.put("amount", PortalDocumentQuery.amount(schedule.get("amount")));
      json.put("paidAmount", PortalDocumentQuery.amount(schedule.get("paidAmount")));
      json.put("outstandingAmount", PortalDocumentQuery.amount(schedule.get("outstandingAmount")));
      installments.put(json);
    }
    return installments;
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────

  private static List<String> invoiceIds(List<BaseOBObject> documents) {
    List<String> ids = new ArrayList<>();
    for (BaseOBObject document : documents) {
      if (document instanceof Invoice) {
        ids.add((String) document.getId());
      }
    }
    return ids;
  }

  private static String nameOf(Object value) {
    if (value instanceof TaxRate) {
      return StringUtils.defaultString(((TaxRate) value).getName());
    }
    if (value instanceof FIN_PaymentMethod) {
      return StringUtils.defaultString(((FIN_PaymentMethod) value).getName());
    }
    if (value instanceof PaymentTerm) {
      return StringUtils.defaultString(((PaymentTerm) value).getName());
    }
    return "";
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static Object formatDate(Date value) {
    return value == null ? JSONObject.NULL : new SimpleDateFormat(DATE_FORMAT).format(value);
  }

  private static Date parseDate(String raw) {
    String normalized = StringUtils.trimToNull(raw);
    if (normalized == null) {
      return null;
    }
    try {
      SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT);
      format.setLenient(false);
      return format.parse(normalized);
    } catch (ParseException e) {
      return null;
    }
  }

  private static int parseInt(String raw, int fallback) {
    try {
      return raw == null ? fallback : Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /**
   * Today's calendar day on the server, the cut-off for the home's overdue aggregates and the row
   * {@code overdue} flag: a due date before it is overdue. Aggregates (the overdue balance and the
   * "overdue" highlight) can only be computed here, so they use the server's day. The per-document
   * badge and due-date dot do not read the row flag: the UI decides overdue in the browser's local
   * calendar day exactly like the app's invoice list ({@code getCalendarDateRelation}). Around
   * midnight the two may therefore differ by one day for a customer in another time zone; accepted.
   */
  private static Date today() {
    return toDate(LocalDate.now());
  }

  private static Date toDate(LocalDate date) {
    return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  private static LocalDate toLocalDate(Date date) {
    return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
  }
}
