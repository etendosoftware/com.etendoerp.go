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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import com.etendoerp.go.schemaforge.InOutLineFromOrderFactory.PendingOrderLine;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Imports the pending lines of a completed order into a DRAFT goods receipt or goods shipment
 * (ETP-5713), and lists what can be imported. Served by the header handlers of both documents
 * through {@link NeoHeaderActionRouter}: {@code availableOrders}, {@code availableOrderLines},
 * {@code importOrderLines}. The direction (purchase or sales) is read from the document, never from
 * the entity.
 *
 * <p>The window's "Add from order" modal posts one line at a time and links the header afterwards,
 * so a half-failed import leaves a half-filled receipt. This is one request, one transaction, and
 * the whole request is validated before the first line is written.
 *
 * <p><b>The cap</b> is the one {@link InOutLineFromOrderFactory#collectPendingLines} already applies
 * to "create from the order": ordered, minus delivered, minus every line of every DRAFT document of
 * the same direction that points at the order line — the target's own lines included, so importing
 * the same line twice is allowed only while it stays under the cap. The order lines are locked
 * ({@code FOR UPDATE}) before that total is read.
 *
 * <p>The order's {@code C_OrderLine.QtyDelivered} only moves when the receipt / shipment is
 * COMPLETED, so what an import lowers is the "pending including drafts", not the delivered
 * quantity.
 */
@Named("importOrderLinesIntoInOutHandler")
public class ImportOrderLinesIntoInOutHandler extends AbstractLineImportHandler {

  static final String ACTION_AVAILABLE_ORDERS = "availableOrders";
  static final String ACTION_AVAILABLE_LINES = "availableOrderLines";
  static final String ACTION_IMPORT = "importOrderLines";

  private static final String PARAM_ORDER_ID = "orderId";
  private static final Set<String> ACTIONS =
      Set.of(ACTION_AVAILABLE_ORDERS, ACTION_AVAILABLE_LINES, ACTION_IMPORT);
  private static final int MAX_CANDIDATES = 200;
  private static final int MAX_ORDERS = 50;
  private static final long LINE_STEP = 10L;
  /** What {@code detail} adds to an {@code availableOrders} row (ETP-6099). */
  static final String DOC_DETAIL_KEYS = "creationDate (ISO instant, UTC) and amount (unit price x pending quantity summed over the "
      + "lines with a positive pending, at the order currency's precision: the import popup's "
      + "row total)";
  /** What {@code detail} adds to an {@code availableOrderLines} row (ETP-6099). */
  static final String LINE_DETAIL_KEYS = "orderedQuantity, deliveredQuantity, unitPrice, "
      + "description of the order line, and inDraftDocuments (the document numbers of the OTHER "
      + "draft documents holding quantity of the line, [] when none)";

  /**
   * The three contracts of the document this delegate is injected in, in flow order.
   *
   * @param sales {@code true} for a goods shipment (sales orders), {@code false} for a goods receipt
   */
  static Map<String, NeoActionContract> contracts(boolean sales) {
    String order = sales ? "sales order" : "purchase order";
    String doc = sales ? "goods shipment" : "goods receipt";
    String oneStep = sales ? "createShipment on the sales order" : "createGoodsReceipt on the "
        + "purchase order";
    String flow = " Flow: create the draft " + doc + " header with etendo_create (business partner "
        + "and the rest defaulted; etendo_schema view:\"create\" is the authority), call "
        + "availableOrders, then availableOrderLines, then importOrderLines on that header, and "
        + "confirm it with documentAction (CO). To " + (sales ? "ship" : "receive") + " a whole "
        + "order in ONE step, without a draft header to fill, call " + oneStep + " instead.";
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(ACTION_AVAILABLE_ORDERS, NeoActionContract.read(ACTION_AVAILABLE_ORDERS,
        "Lists the completed " + order + "s of this " + doc + "'s business partner and currency "
            + "that still have quantity to " + (sales ? "ship" : "receive") + ": {data:[{id, "
            + "documentNo, date, businessPartner, businessPartner$_identifier, currency, "
            + "pendingLines}], hasMore, limit, scanLimit}. hasMore true means the list may be incomplete: it looks at the scanLimit most recent candidates and returns at most limit documents, so a document it does not list may still be importable when you know its id. Pending counts what draft documents already hold. "
            + ImportInOutLinesIntoInvoiceHandler.LISTING_NOTE + " includeCovered also lists the "
            + "orders whose pending lines are all held by draft documents, with pendingLines 0; "
            + "detail adds " + DOC_DETAIL_KEYS + ". "
            + "Step 1." + flow,
        ImportListing.listParams(MAX_ORDERS, DOC_DETAIL_KEYS))
        .withIdDescription(DocumentLineImport.ID_OF_THE_DRAFT + doc + " the lines would be imported into.")
        .availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(ACTION_AVAILABLE_LINES, NeoActionContract.read(ACTION_AVAILABLE_LINES,
        "Lists the lines of a " + order + " that can still be imported: {data:[{id, lineNo, "
            + "product, product$_identifier, uOM, pendingQuantity}], hasMore:false}, where pendingQuantity is "
            + "ordered minus delivered minus what any draft document (this one included) already "
            + "holds, and the id is the sourceLineId importOrderLines takes. includeCovered also "
            + "lists the order's other product lines (nothing left to " + (sales ? "ship" : "receive")
            + ") at pendingQuantity 0; detail adds " + LINE_DETAIL_KEYS + ". Step 2." + flow,
        ImportListing.linesParams(LINE_DETAIL_KEYS,
            NeoActionContract.Param.required(PARAM_ORDER_ID, NeoActionContract.TYPE_STRING,
                "Id of an order returned by availableOrders.")))
        .withIdDescription(DocumentLineImport.ID_OF_THE_DRAFT + doc + " the lines would be imported into.")
        .availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(ACTION_IMPORT, NeoActionContract.write(ACTION_IMPORT,
        "Adds lines of a completed " + order + " to this DRAFT " + doc + " (product, unit of "
            + "measure, storage bin and the link to the order line), in ONE transaction. Without "
            + "lines it imports everything still pending; with lines, exactly those. More than "
            + "what is pending on a line (drafts included, this document's own lines too; the same "
            + "line listed twice counts both) answers 422 importQuantityExceedsPending naming the "
            + "product, the requested and the pending quantity, and creates NOTHING. The same "
            + "order line may be imported again while it stays under the cap. The order's "
            + "delivered quantity only moves when this document is completed. The document is "
            + "linked to the order when every line it holds comes from that one order. Refused "
            + "(422) when this document is not a draft, the order is not completed, or business "
            + "partner, currency or direction differ. Returns {data:{importedCount, lines:[{id, "
            + "sourceLineId, product, productName, quantity}], headerLinked, salesOrder}} (salesOrder is the order the header points at now; headerLinked is true only when an imported line belongs to it). "
            + "Step 3." + flow,
        NeoActionContract.Param.required(PARAM_ORDER_ID, NeoActionContract.TYPE_STRING,
            "Id of the order to import from, returned by availableOrders."),
        NeoActionContract.Param.array(DocumentLineImport.KEY_LINES,
            NeoActionContract.TYPE_OBJECT, false,
            "Each {sourceLineId, quantity}: sourceLineId is the id of a line from "
                + "availableOrderLines, quantity a number not above its pendingQuantity. Omit to "
                + "import every pending line in full."))
        .withIdDescription(DocumentLineImport.ID_OF_THE_DRAFT + doc + " the lines are added to.")
        .availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    return contracts;
  }

  @Override
  protected int scanLimit() {
    return MAX_CANDIDATES;
  }

  @Override
  protected int rowLimit() {
    return MAX_ORDERS;
  }

  @Override
  protected Set<String> actions() {
    return ACTIONS;
  }

  @Override
  protected NeoResponse execute(NeoContext context, String action) throws Exception {
    ShipmentInOut target = requireTarget(context.getRecordId());
    JSONObject body = context.getRequestBody();
    switch (action) {
      case ACTION_AVAILABLE_ORDERS:
        return availableOrders(target, listRequest(body));
      case ACTION_AVAILABLE_LINES:
        return availableOrderLines(target, requireSourceOrder(target, body),
            ImportListing.includeCovered(body), ImportListing.detail(body));
      default:
        return importLines(target, body);
    }
  }

  // ---------------------------------------------------------------------------
  // importOrderLines
  // ---------------------------------------------------------------------------

  private NeoResponse importLines(ShipmentInOut target, JSONObject body)
      throws SQLException, JSONException {
    DocumentLineImport.requireDraftTarget(target.getDocumentNo(), target.getDocumentStatus());
    Map<String, BigDecimal> requested = DocumentLineImport.parseRequestedLines(body);
    Order order = requireSourceOrder(target, body);
    boolean sales = Boolean.TRUE.equals(order.isSalesTransaction());

    List<String> orderLineIds = new ArrayList<>();
    Map<String, String> documentLines = new LinkedHashMap<>();
    for (OrderLine ol : order.getOrderLineList()) {
      orderLineIds.add(ol.getId());
      documentLines.put(ol.getId(), productLabel(ol));
    }
    // Lock first, read the drafts after: a concurrent import of the same order line waits here and
    // then sees the first one's lines in the total.
    lockOrderLines(orderLineIds);
    // What the pending reads is read AFTER the lock, from freshly loaded order lines.
    refreshOrderLines(order.getOrderLineList());

    Set<String> coveringDrafts = new LinkedHashSet<>();
    Map<String, PendingOrderLine> pendingById = new LinkedHashMap<>();
    Map<String, BigDecimal> pending = new LinkedHashMap<>();
    for (PendingOrderLine line : pendingLines(order, sales, coveringDrafts)) {
      pendingById.put(line.getOrderLine().getId(), line);
      pending.put(line.getOrderLine().getId(), line.getPendingQty());
    }
    if (requested.isEmpty() && pending.isEmpty() && !coveringDrafts.isEmpty()) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_NOTHING_PENDING,
          "Nothing is left to import from this order: its pending lines are already covered by "
              + "draft documents (" + String.join(", ", coveringDrafts) + ")");
    }
    Map<String, BigDecimal> planned = DocumentLineImport.plan(requested, documentLines, pending);

    Locator bin = resolveBinIfNeeded(target, planned, pendingById);
    long lineNo = nextLineNo(target.getId());
    List<DocumentLineImport.ImportedLine> created = new ArrayList<>();
    for (Map.Entry<String, BigDecimal> entry : planned.entrySet()) {
      OrderLine orderLine = pendingById.get(entry.getKey()).getOrderLine();
      ShipmentInOutLine line = createLine(target, orderLine, bin, lineNo, entry.getValue());
      lineNo += LINE_STEP;
      created.add(new DocumentLineImport.ImportedLine(line.getId(), orderLine.getId(),
          orderLine.getProduct().getId(), orderLine.getProduct().getName(), entry.getValue(),
          order.getId()));
    }
    String linkedOrderId = linkHeader(target);
    requireQuotaAllowed(ShipmentInOutLine.ENTITY_NAME, created);
    return DocumentLineImport.ok(created, linkedOrderId);
  }

  /** The bin to give the stockable lines: the target's warehouse anchor, or a refusal. */
  private Locator resolveBinIfNeeded(ShipmentInOut target, Map<String, BigDecimal> planned,
      Map<String, PendingOrderLine> pendingById) {
    boolean stockable = false;
    for (String id : planned.keySet()) {
      stockable |= InOutLineFromOrderFactory.isStockable(
          pendingById.get(id).getOrderLine().getProduct());
    }
    if (!stockable) {
      return null;
    }
    Locator bin = resolveBin(target);
    if (bin == null) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_NO_STORAGE_BIN,
          "The warehouse of this document has no active storage bin: the stockable lines cannot "
              + "be imported until one exists");
    }
    return bin;
  }

  /**
   * Points the header at the order when every line it holds comes from that one order, clears a
   * link of ours once a second order joins, leaves anything else alone.
   *
   * @return the order id the header ends up linked to, or {@code null}
   */
  private String linkHeader(ShipmentInOut target) {
    Set<String> lineOrderIds = orderIdsOfActiveLines(target.getId());
    String current = target.getSalesOrder() == null ? null : target.getSalesOrder().getId();
    DocumentLineImport.HeaderLink link = DocumentLineImport.resolveHeaderLink(current,
        lineOrderIds);
    switch (link.action()) {
      case LINK:
        if (!link.orderId().equals(current)) {
          target.setSalesOrder(OBDal.getInstance().get(Order.class, link.orderId()));
          OBDal.getInstance().save(target);
          OBDal.getInstance().flush();
        }
        return link.orderId();
      case CLEAR:
        target.setSalesOrder(null);
        OBDal.getInstance().save(target);
        OBDal.getInstance().flush();
        return null;
      default:
        return current;
    }
  }

  // ---------------------------------------------------------------------------
  // availableOrders / availableOrderLines
  // ---------------------------------------------------------------------------

  private NeoResponse availableOrders(ShipmentInOut target, ImportListing.ListRequest request)
      throws JSONException {
    boolean sales = Boolean.TRUE.equals(target.isSalesTransaction());
    String partnerId = target.getBusinessPartner() == null ? null
        : target.getBusinessPartner().getId();
    String currencyId = target.getEtgoCurrency() == null ? null : target.getEtgoCurrency().getId();
    List<Order> candidates = request.isDefault(scanLimit())
        ? findCandidateOrders(partnerId, sales, currencyId)
        : findCandidateOrders(partnerId, sales, currencyId, request.offset, request.scan);
    return discover(candidates, order -> {
      List<PendingOrderLine> pending = pendingLines(order, sales, new LinkedHashSet<>());
      int pendingLines = pending.size();
      if (pendingLines == 0 && !(request.includeCovered && hasPendingBeforeDrafts(order))) {
        return null;
      }
      String orderCurrency = order.getCurrency() == null ? null : order.getCurrency().getId();
      JSONObject row = documentRow(order.getId(), order.getDocumentNo(), day(order.getOrderDate()),
          partnerId, order.getBusinessPartner().getName(), orderCurrency, pendingLines);
      if (request.detail) {
        ImportListing.putCreationDate(row, order.getCreationDate());
        row.put("amount", pendingAmount(order, pending));
      }
      return row;
    }, request);
  }

  /**
   * The popup's row total (ETP-6099): unit price x pending over the lines with a positive pending
   * (the popup clamps a pending at 0 and drops a receipt's negative lines), at the order
   * currency's precision.
   */
  private static BigDecimal pendingAmount(Order order, List<PendingOrderLine> pending) {
    BigDecimal sum = BigDecimal.ZERO;
    for (PendingOrderLine line : pending) {
      if (line.getPendingQty().signum() > 0) {
        sum = sum.add(OrderImportListing.unitPriceOf(line.getOrderLine())
            .multiply(line.getPendingQty()));
      }
    }
    return sum.setScale(OrderImportListing.amountPrecision(order), java.math.RoundingMode.HALF_UP);
  }

  /** ordered - delivered left on a line, same sign as ordered: what drafts can have taken. */
  private static boolean hasPendingBeforeDrafts(Order order) {
    for (OrderLine ol : order.getOrderLineList()) {
      BigDecimal base = InOutLineFromOrderFactory.pendingQuantityFor(ol);
      if (base != null && ol.getOrderedQuantity() != null
          && base.signum() == ol.getOrderedQuantity().signum()) {
        return true;
      }
    }
    return false;
  }

  /**
   * The lines that can still be imported. With {@code includeCovered} (ETP-6099) also every other
   * product line of the order (active, not the total-discount line) at {@code pendingQuantity} 0,
   * in the order's line order: the popup lists them greyed out.
   */
  private NeoResponse availableOrderLines(ShipmentInOut target, Order order,
      boolean includeCovered, boolean detail) throws JSONException {
    boolean sales = Boolean.TRUE.equals(order.isSalesTransaction());
    List<PendingOrderLine> pendingLines = pendingLines(order, sales, new LinkedHashSet<>());
    List<OrderLine> listed = new ArrayList<>();
    Map<String, BigDecimal> pending = new LinkedHashMap<>();
    for (PendingOrderLine line : pendingLines) {
      pending.put(line.getOrderLine().getId(), line.getPendingQty());
      listed.add(line.getOrderLine());
    }
    if (includeCovered) {
      listed = new ArrayList<>();
      for (OrderLine ol : order.getOrderLineList()) {
        if (pending.containsKey(ol.getId()) || isProductLine(ol)) {
          listed.add(ol);
        }
      }
    }
    Map<String, Set<String>> drafts = detail ? draftDocumentNos(idsOf(listed), sales,
        target.getId()) : Map.of();
    JSONArray rows = new JSONArray();
    for (OrderLine ol : listed) {
      JSONObject row = DocumentLineImport.lineRow(ol.getId(), ol.getLineNo(),
          ol.getProduct().getId(), ol.getProduct().getName(),
          ol.getUOM() == null ? null : ol.getUOM().getId(),
          pending.getOrDefault(ol.getId(), BigDecimal.ZERO));
      if (detail) {
        ImportListing.putNullable(row, "orderedQuantity", ol.getOrderedQuantity());
        ImportListing.putNullable(row, "deliveredQuantity", ol.getDeliveredQuantity());
        ImportListing.putNullable(row, "unitPrice", ol.getUnitPrice());
        ImportListing.putNullable(row, "description", ol.getDescription());
        row.put("inDraftDocuments", new JSONArray(drafts.getOrDefault(ol.getId(), Set.of())));
      }
      rows.put(row);
    }
    return completePage(rows);
  }

  /** An active line with a product other than the total-discount line. */
  private static boolean isProductLine(OrderLine ol) {
    return ol.isActive() && ol.getProduct() != null
        && !TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(ol.getProduct().getId());
  }

  private static List<String> idsOf(List<OrderLine> lines) {
    List<String> ids = new ArrayList<>();
    for (OrderLine ol : lines) {
      ids.add(ol.getId());
    }
    return ids;
  }

  /**
   * The pending lines of the order, minus the over-delivered ones. The shared factory computes
   * {@code ordered - delivered} and keeps any non-zero result, so a line ordered 5 and delivered 7
   * would be offered as a receipt line of -2; a pending only counts when it has the SAME sign as
   * the ordered quantity (a negative order line keeps its negative pending). The one-step
   * {@code createGoodsReceipt} / {@code createShipment} share that factory and its flip (see
   * neo-headless.md §4.12.34); this import does not inherit it.
   */
  private List<PendingOrderLine> pendingLines(Order order, boolean sales,
      Set<String> coveringDrafts) {
    List<PendingOrderLine> pending = new ArrayList<>();
    for (PendingOrderLine line : collectPending(order, sales, coveringDrafts)) {
      BigDecimal ordered = line.getOrderLine().getOrderedQuantity();
      if (ordered != null && line.getPendingQty().signum() == ordered.signum()) {
        pending.add(line);
      }
    }
    return pending;
  }

  // ---------------------------------------------------------------------------
  // Loading and validating
  // ---------------------------------------------------------------------------

  private ShipmentInOut requireTarget(String id) {
    ShipmentInOut target = loadTarget(id);
    if (target == null) {
      throw DocumentLineImport.notFound("Document", id);
    }
    requireOwnClient("Document", id, target.getClient());
    return target;
  }

  /** The order named by {@code orderId}, once it is known to fit the target. */
  private Order requireSourceOrder(ShipmentInOut target, JSONObject body) {
    String orderId = requireParam(body, PARAM_ORDER_ID);
    Order order = loadOrder(orderId);
    if (order == null) {
      throw DocumentLineImport.notFound("Order", orderId);
    }
    requireOwnClient("Order", orderId, order.getClient());
    DocumentLineImport.requireCompletedSource(order.getDocumentNo(), order.getDocumentStatus());
    DocumentLineImport.requireSameDirection(Boolean.TRUE.equals(target.isSalesTransaction()),
        Boolean.TRUE.equals(order.isSalesTransaction()), order.getDocumentNo());
    DocumentLineImport.requireSameBusinessPartner(
        target.getBusinessPartner() == null ? null : target.getBusinessPartner().getId(),
        order.getBusinessPartner() == null ? null : order.getBusinessPartner().getId(),
        order.getDocumentNo());
    DocumentLineImport.requireSameCurrency(
        target.getEtgoCurrency() == null ? null : target.getEtgoCurrency().getId(),
        order.getCurrency() == null ? null : order.getCurrency().getId(), order.getDocumentNo());
    return order;
  }

  private static String productLabel(OrderLine ol) {
    return ol.getProduct() != null && ol.getProduct().getName() != null
        ? ol.getProduct().getName() : ol.getId();
  }

  // ---------------------------------------------------------------------------
  // Seams (DAL) — overridden by the unit tests
  // ---------------------------------------------------------------------------

  protected ShipmentInOut loadTarget(String id) {
    return OBDal.getInstance().get(ShipmentInOut.class, id);
  }

  protected Order loadOrder(String id) {
    return OBDal.getInstance().get(Order.class, id);
  }

  /** Seam: re-reads the order lines, so what the cap reads was not loaded before the lock. */
  protected void refreshOrderLines(Collection<OrderLine> lines) {
    for (OrderLine line : lines) {
      OBDal.getInstance().getSession().refresh(line);
    }
  }

  protected void lockOrderLines(Collection<String> ids) throws SQLException {
    DocumentLineImport.lockOrderLines(ids);
  }

  /** The lines still pending, drafts of the same direction (the target's included) deducted. */
  protected List<PendingOrderLine> collectPending(Order order, boolean sales,
      Set<String> coveringDrafts) {
    return InOutLineFromOrderFactory.collectPendingLines(order, sales, coveringDrafts);
  }

  /** Seam: the OTHER drafts holding each order line (ETP-6099, the popup's draft note). */
  protected Map<String, Set<String>> draftDocumentNos(Collection<String> orderLineIds,
      boolean sales, String targetId) {
    return InOutLineFromOrderFactory.draftDocumentNos(orderLineIds, sales, targetId);
  }

  protected Locator resolveBin(ShipmentInOut target) {
    return NeoHandlerUtils.resolveWarehouseAnchorBin(target.getWarehouse(), log);
  }

  protected long nextLineNo(String targetId) {
    return ReturnShipmentUtils.fetchMaxLineNo(targetId) + LINE_STEP;
  }

  protected ShipmentInOutLine createLine(ShipmentInOut target, OrderLine orderLine, Locator bin,
      long lineNo, BigDecimal quantity) {
    return InOutLineFromOrderFactory.createAndLinkLine(target, orderLine, bin, lineNo, quantity);
  }

  /** The distinct orders of the active lines of the target, after the import was flushed. */
  protected Set<String> orderIdsOfActiveLines(String targetId) {
    List<String> ids = OBDal.getInstance().getSession().createQuery(
        "select distinct ol.salesOrder.id from MaterialMgmtShipmentInOutLine iol "
            + "join iol.salesOrderLine ol where iol.shipmentReceipt.id = :id and iol.active = true",
        String.class).setParameter("id", targetId).list();
    return new LinkedHashSet<>(ids);
  }

  /** Completed orders of the partner, newest first; a bounded scan, not the whole history. */
  protected List<Order> findCandidateOrders(String partnerId, boolean sales, String currencyId) {
    return findCandidateOrders(partnerId, sales, currencyId, 0, scanLimit());
  }

  /** {@link #findCandidateOrders(String, boolean, String)} paged (ETP-6099). */
  protected List<Order> findCandidateOrders(String partnerId, boolean sales, String currencyId,
      int offset, int scan) {
    StringBuilder hql = new StringBuilder("from Order o where o.documentStatus = 'CO' "
        + "and o.salesTransaction = :sot and o.businessPartner.id = :bp and o.active = true");
    hql.append(" and o.client.id = :client");
    if (currencyId != null) {
      hql.append(" and o.currency.id = :cur");
    }
    hql.append(" order by o.orderDate desc, o.documentNo desc");
    org.hibernate.query.Query<Order> query = OBDal.getInstance().getSession()
        .createQuery(hql.toString(), Order.class).setParameter("sot", sales)
        .setParameter("bp", partnerId).setParameter("client", sessionClientId())
        .setFirstResult(offset).setMaxResults(scan);
    if (currencyId != null) {
      query.setParameter("cur", currencyId);
    }
    return query.list();
  }
}
