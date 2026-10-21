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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Imports the pending lines of a completed order into a DRAFT purchase or sales invoice
 * (ETP-5713), and lists what can be imported: {@code availableOrders}, {@code availableOrderLines},
 * {@code importOrderLines}. The direction comes from the invoice, never from the entity.
 *
 * <p><b>The cap</b> is {@code ordered - invoiced - draft}: {@code C_OrderLine.QtyInvoiced} (moved
 * only when an invoice is COMPLETED) minus what every line of every DRAFT invoice of the same
 * direction already holds against that order line, the target's own lines included. The one-step
 * {@code createPurchaseInvoice} / {@code createDraftInvoice} ignore drafts because they complete
 * the invoice in the same call; an import into a draft cannot, or two drafts could each take the
 * whole order. The order lines are locked ({@code FOR UPDATE}) before the total is read.
 *
 * <p>Lines are created by the native process (see {@link AbstractInvoiceLineImportHandler}).
 * The requested quantity of an order line is distributed first over the order line's COMPLETED,
 * not yet invoiced receipt / shipment lines (oldest first, each up to its own pending, drafts
 * included, exactly what {@code importReceiptLines} / {@code importShipmentLines} would offer),
 * as entries bound to the movement line: the invoice line then points at the order line AND the
 * movement line, so the movement counts as invoiced for the receipt cap and the order cap counts
 * the same line through its order line. What is left (ordered and not delivered, or delivered but
 * already taken by another draft) is invoiced against the order line alone. See
 * {@link #buildBatches} for why the two kinds go in separate native calls and how the remainder is
 * protected from the native process swapping it for receipt lines; nothing is refused for having
 * deliveries.
 *
 * <p>The window's modal also patches the header's total discount; here the header takes the order's
 * {@code etgoTotalDiscount} when it is positive and the invoice has none yet (or the same one).
 * The discount line itself is materialised when the invoice is completed, as always.
 */
@Named("importOrderLinesIntoInvoiceHandler")
public class ImportOrderLinesIntoInvoiceHandler extends AbstractInvoiceLineImportHandler {

  static final String ACTION_AVAILABLE_ORDERS = "availableOrders";
  static final String ACTION_AVAILABLE_LINES = "availableOrderLines";
  static final String ACTION_IMPORT = "importOrderLines";

  private static final String PARAM_ORDER_ID = "orderId";
  private static final Set<String> ACTIONS =
      Set.of(ACTION_AVAILABLE_ORDERS, ACTION_AVAILABLE_LINES, ACTION_IMPORT);
  private static final int MAX_CANDIDATES = 200;
  private static final int MAX_ORDERS = 50;

  /**
   * The three contracts of the invoice this delegate is injected in, in flow order.
   *
   * @param sales {@code true} for a sales invoice, {@code false} for a purchase invoice
   */
  static Map<String, NeoActionContract> contracts(boolean sales) {
    String order = sales ? "sales order" : "purchase order";
    String doc = sales ? "sales invoice" : "purchase invoice";
    String inout = sales ? "shipment" : "receipt";
    String oneStep = sales ? "createDraftInvoice on the sales order" : "createPurchaseInvoice on "
        + "the purchase order";
    String flow = " Flow: create the draft " + doc + " header with etendo_create (business partner "
        + "and the rest defaulted; etendo_schema view:\"create\" is the authority), call "
        + "availableOrders, then availableOrderLines, then importOrderLines on that header, and "
        + "complete it with documentAction (CO). To invoice a whole order in ONE step (created AND "
        + "completed, no draft to fill) call " + oneStep + " instead.";
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(ACTION_AVAILABLE_ORDERS, NeoActionContract.read(ACTION_AVAILABLE_ORDERS,
        "Lists the completed " + order + "s of this " + doc + "'s business partner and currency "
            + "that still have quantity to invoice: {data:[{id, documentNo, date, businessPartner, "
            + "businessPartner$_identifier, currency, pendingLines}], hasMore, limit, scanLimit}. hasMore true means the list may be incomplete: it looks at the scanLimit most recent candidates and returns at most limit documents, so a document it does not list may still be importable when you know its id. Pending counts what draft "
            + "invoices already hold. Step 1." + flow)
        .withIdDescription(DocumentLineImport.ID_OF_THE_DRAFT + doc + " the lines would be imported into.")
        .availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(ACTION_AVAILABLE_LINES, NeoActionContract.read(ACTION_AVAILABLE_LINES,
        "Lists the lines of a " + order + " that can still be invoiced here: {data:[{id, lineNo, "
            + "product, product$_identifier, uOM, pendingQuantity}], hasMore:false}, where pendingQuantity is "
            + "ordered minus invoiced minus what any draft invoice (this one included) already "
            + "holds, and the id is the sourceLineId importOrderLines takes. Step 2." + flow,
        NeoActionContract.Param.required(PARAM_ORDER_ID, NeoActionContract.TYPE_STRING,
            "Id of an order returned by availableOrders."))
        .withIdDescription(DocumentLineImport.ID_OF_THE_DRAFT + doc + " the lines would be imported into.")
        .availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(ACTION_IMPORT, NeoActionContract.write(ACTION_IMPORT,
        "Adds lines of a completed " + order + " to this DRAFT " + doc + " (product, unit of "
            + "measure, tax, price and discount copied from the order line, linked to it), in ONE "
            + "transaction, WITHOUT completing the invoice. Without lines it imports everything "
            + "still pending; with lines, exactly those. More than what is pending on a line "
            + "(drafts included, this invoice's own lines too; the same line listed twice counts "
            + "both) answers 422 importQuantityExceedsPending naming the product, the requested "
            + "and the pending quantity, and creates NOTHING. The same order line may be imported "
            + "again while it stays under the cap. The quantity of an order line goes first to its "
            + "completed " + inout + " lines not yet invoiced (oldest first, each up to its own "
            + "pending), linked to both documents, and the rest, ordered but not delivered, is "
            + "invoiced against the order line alone (also when a " + inout + " line is already "
            + "covered by another draft invoice: the order's pending counts drafts, so what is "
            + "left is what can still be invoiced). The invoice's order reference is set when every line comes from one order and "
            + "left empty when several orders are mixed; its total discount takes the order's "
            + "when it has none. The order's invoiced quantity only moves when the invoice is "
            + "completed. Refused (422) when the invoice is not a draft, the order is not "
            + "completed, or business partner, currency or direction differ. Returns "
            + "{data:{importedCount, lines:[{id, sourceLineId, product, productName, quantity}], "
            + "headerLinked, salesOrder}} (salesOrder is the order the header points at now; headerLinked is true only when an imported line belongs to it). Step 3." + flow,
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
    JSONObject body = context.getRequestBody();
    if (ACTION_AVAILABLE_ORDERS.equals(action)) {
      return availableOrders(requireExistingInvoice(context.getRecordId()));
    }
    if (ACTION_AVAILABLE_LINES.equals(action)) {
      Invoice invoice = requireExistingInvoice(context.getRecordId());
      return availableOrderLines(requireSourceOrder(invoice, body));
    }
    return importLines(requireDraftInvoice(context.getRecordId()), body);
  }

  // ---------------------------------------------------------------------------
  // importOrderLines
  // ---------------------------------------------------------------------------

  private NeoResponse importLines(Invoice invoice, JSONObject body)
      throws SQLException, JSONException {
    Map<String, BigDecimal> requested = DocumentLineImport.parseRequestedLines(body);
    Order order = requireSourceOrder(invoice, body);
    boolean sales = Boolean.TRUE.equals(order.isSalesTransaction());

    Map<String, OrderLine> linesById = new LinkedHashMap<>();
    Map<String, String> documentLines = new LinkedHashMap<>();
    for (OrderLine ol : order.getOrderLineList()) {
      linesById.put(ol.getId(), ol);
      documentLines.put(ol.getId(), label(ol));
    }
    lockOrderLines(linesById.keySet());
    // What the cap reads is read AFTER the lock: a line loaded before it may carry the invoiced
    // quantity from before another import completed.
    refreshOrderLines(linesById.values());
    Map<String, BigDecimal> pending = pendingToInvoice(order, sales);
    Map<String, BigDecimal> planned = DocumentLineImport.plan(requested, documentLines, pending);

    Batches batches = buildBatches(linesById, planned, sales);
    List<DocumentLineImport.ImportedLine> created = createLines(invoice, batches.calls,
        OrderLine.class, planned, line -> line.getSalesOrderLine(),
        (call, lines) -> unmarkRemainder(batches, call, lines));
    carryOrderDiscount(invoice, order);
    return DocumentLineImport.ok(created, invoice.getSalesOrder() == null ? null
        : invoice.getSalesOrder().getId());
  }

  // ---------------------------------------------------------------------------
  // Distribution over the delivered lines
  // ---------------------------------------------------------------------------

  /** One movement line that can take part of an order line's quantity, and how much of it. */
  static final class Candidate {
    final ShipmentInOutLine line;
    /** Still invoiceable on this line, signed like it; drafts and invoices already deducted. */
    final BigDecimal pending;

    Candidate(ShipmentInOutLine line, BigDecimal pending) {
      this.line = line;
      this.pending = pending;
    }
  }

  /** A slice of the requested quantity assigned to one movement line. */
  static final class Allocation {
    final ShipmentInOutLine line;
    final BigDecimal quantity;

    Allocation(ShipmentInOutLine line, BigDecimal quantity) {
      this.line = line;
      this.quantity = quantity;
    }
  }

  /**
   * Distributes {@code quantity} over the candidates in the order given (oldest first), each up to
   * its own pending, keeping the sign of the request. What no candidate can take is the remainder
   * ({@code quantity} minus the sum of the allocations).
   */
  static List<Allocation> allocate(BigDecimal quantity, List<Candidate> candidates) {
    List<Allocation> allocations = new ArrayList<>();
    BigDecimal left = quantity.abs();
    for (Candidate candidate : candidates) {
      if (left.signum() == 0) {
        break;
      }
      if (candidate.pending.signum() == quantity.signum()) {
        BigDecimal taken = left.min(candidate.pending.abs());
        allocations.add(new Allocation(candidate.line,
            quantity.signum() < 0 ? taken.negate() : taken));
        left = left.subtract(taken);
      }
    }
    return allocations;
  }

  /** The native calls to make, and what has to be undone on the last one. */
  static final class Batches {
    final List<JSONArray> calls = new ArrayList<>();
    /** Movement lines used only as a marker on a remainder entry; the link is removed again. */
    final Set<String> markerIds = new java.util.HashSet<>();
    /** Index in {@link #calls} of the remainder call, or -1 when there is none. */
    int remainderCall = -1;
  }

  /**
   * One native call per phase. First the entries bound to a movement line (the order line AND the
   * receipt / shipment line, so the invoice line points at both and the movement counts as invoiced
   * for the receipt cap); then, in a second call, the remainder: what was ordered and not delivered
   * (or already taken by a draft), plus any line without movements.
   *
   * <p>The native process replaces a PLAIN order-line entry by every completed, not yet invoiced
   * movement line of that order line, at THEIR quantity, and it ignores drafts: a receipt line fully
   * covered by another draft invoice is still "not invoiced" for it (live case: order 10, receipt 6
   * in a draft, request 4 gets swapped for the receipt's 6). An entry that already names a movement
   * line is never swapped, so a remainder entry of an order line that has such lines carries one of
   * them as a MARKER ({@code shipmentInOutLine}) and the link is removed once the line exists
   * ({@link #unmarkRemainder}): the quantity is the entry's, the prices, tax and UOM are the
   * order line's, and the invoice line ends up pointing at the order line only, so the receipt
   * is not counted twice by its cap.
   */
  private Batches buildBatches(Map<String, OrderLine> linesById, Map<String, BigDecimal> planned,
      boolean sales) throws SQLException, JSONException {
    Map<String, List<ShipmentInOutLine>> movementsByLine = new LinkedHashMap<>();
    Set<String> movementIds = new java.util.TreeSet<>();
    Set<String> documentIds = new java.util.LinkedHashSet<>();
    for (String id : planned.keySet()) {
      List<ShipmentInOutLine> movements = movementLinesOf(linesById.get(id));
      movementsByLine.put(id, movements);
      for (ShipmentInOutLine movement : movements) {
        movementIds.add(movement.getId());
        documentIds.add(movement.getShipmentReceipt().getId());
      }
    }
    lockInOutLines(movementIds);
    Map<String, BigDecimal> pendingByMovement = new HashMap<>();
    for (String documentId : documentIds) {
      pendingByMovement.putAll(movementPending(documentId));
    }
    Batches batches = new Batches();
    JSONArray bound = new JSONArray();
    JSONArray remainders = new JSONArray();
    for (Map.Entry<String, BigDecimal> entry : planned.entrySet()) {
      OrderLine orderLine = linesById.get(entry.getKey());
      List<Allocation> allocations = allocate(entry.getValue(),
          candidatesOf(orderLine, entry.getValue(), movementsByLine.get(entry.getKey()),
              pendingByMovement));
      BigDecimal remainder = entry.getValue();
      for (Allocation allocation : allocations) {
        bound.put(MultiDocumentInvoiceSupport.buildOrderLineEntryForInOutLine(orderLine.getId(),
            allocation.line, allocation.quantity));
        remainder = remainder.subtract(allocation.quantity);
      }
      if (remainder.signum() != 0) {
        JSONObject plain = new JSONObject().put("id", orderLine.getId())
            .put("orderedQuantity", remainder.toPlainString());
        Set<String> related = nativeRelatedRemaining(orderLine, sales).keySet();
        if (!related.isEmpty()) {
          String marker = related.iterator().next();
          plain.put("shipmentInOutLine", marker);
          batches.markerIds.add(marker);
        }
        remainders.put(plain);
      }
    }
    if (bound.length() > 0) {
      batches.calls.add(bound);
    }
    if (remainders.length() > 0) {
      batches.calls.add(remainders);
      batches.remainderCall = batches.calls.size() - 1;
    }
    return batches;
  }

  /**
   * Removes the marker link from the lines the remainder call created, then asserts that NO line of
   * that call still points at a movement line: a skipped unmark would leave the receipt counted
   * twice by its cap, so it is rolled back and refused, never left silent.
   */
  private void unmarkRemainder(Batches batches, int call, List<InvoiceLine> created) {
    if (call != batches.remainderCall || batches.markerIds.isEmpty()) {
      return;
    }
    for (InvoiceLine line : created) {
      ShipmentInOutLine linked = line.getGoodsShipmentLine();
      if (linked != null && batches.markerIds.contains(linked.getId())) {
        line.setGoodsShipmentLine(null);
        OBDal.getInstance().save(line);
      }
    }
    OBDal.getInstance().flush();
    for (InvoiceLine line : created) {
      if (line.getGoodsShipmentLine() != null) {
        rollback();
        throw DocumentLineImport.refusal(DocumentLineImport.ERROR_QUANTITY_NOT_HONOURED,
            "A remainder line is still linked to a receipt / shipment line; nothing was imported");
      }
    }
  }

  /** The movement lines that can take quantity of this order line: same product, unit and sign. */
  private static List<Candidate> candidatesOf(OrderLine orderLine, BigDecimal quantity,
      List<ShipmentInOutLine> movements, Map<String, BigDecimal> pendingByMovement) {
    List<Candidate> candidates = new ArrayList<>();
    for (ShipmentInOutLine movement : movements) {
      BigDecimal moved = movement.getMovementQuantity();
      BigDecimal magnitude = pendingByMovement.get(movement.getId());
      if (moved == null || moved.signum() != quantity.signum() || magnitude == null
          || !sameProductAndUnit(orderLine, movement)) {
        continue;
      }
      BigDecimal capped = magnitude.min(moved.abs());
      if (capped.signum() > 0) {
        candidates.add(new Candidate(movement, moved.signum() < 0 ? capped.negate() : capped));
      }
    }
    return candidates;
  }

  private static boolean sameProductAndUnit(OrderLine orderLine, ShipmentInOutLine movement) {
    return orderLine.getProduct() != null && movement.getProduct() != null
        && orderLine.getProduct().getId().equals(movement.getProduct().getId())
        && orderLine.getUOM() != null && movement.getUOM() != null
        && orderLine.getUOM().getId().equals(movement.getUOM().getId());
  }

  /** The order's total discount, when it is positive and the invoice has none yet or the same. */
  private void carryOrderDiscount(Invoice invoice, Order order) {
    BigDecimal discount = order.getEtgoTotalDiscount();
    if (discount == null || discount.signum() <= 0) {
      return;
    }
    BigDecimal current = invoice.getEtgoTotalDiscount();
    if (current == null || current.signum() == 0 || current.compareTo(discount) == 0) {
      invoice.setEtgoTotalDiscount(discount);
      OBDal.getInstance().save(invoice);
      OBDal.getInstance().flush();
    }
  }

  // ---------------------------------------------------------------------------
  // The pending quantity
  // ---------------------------------------------------------------------------

  /**
   * ordered - invoiced - draft, per order line, signed like the order line; lines with nothing left
   * (or over-invoiced, or the synthetic total-discount line) are absent.
   */
  protected Map<String, BigDecimal> pendingToInvoice(Order order, boolean sales) {
    Map<String, BigDecimal> base = new LinkedHashMap<>();
    for (OrderLine ol : order.getOrderLineList()) {
      if (!ol.isActive() || ol.getProduct() == null
          || TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(ol.getProduct().getId())) {
        continue;
      }
      BigDecimal ordered = ol.getOrderedQuantity() == null ? BigDecimal.ZERO
          : ol.getOrderedQuantity();
      BigDecimal invoiced = ol.getInvoicedQuantity() == null ? BigDecimal.ZERO
          : ol.getInvoicedQuantity();
      BigDecimal pending = ordered.subtract(invoiced);
      if (pending.signum() != 0 && pending.signum() == ordered.signum()) {
        base.put(ol.getId(), pending);
      }
    }
    if (base.isEmpty()) {
      return base;
    }
    return afterDrafts(base, draftInvoicedQuantities(base.keySet(), sales));
  }

  /** {@code base} minus {@code drafts}; a line a draft already covers (or over-covers) drops out. */
  static Map<String, BigDecimal> afterDrafts(Map<String, BigDecimal> base,
      Map<String, BigDecimal> drafts) {
    Map<String, BigDecimal> remaining = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> entry : base.entrySet()) {
      BigDecimal left = entry.getValue().subtract(
          drafts.getOrDefault(entry.getKey(), BigDecimal.ZERO));
      if (left.signum() == entry.getValue().signum()) {
        remaining.put(entry.getKey(), left);
      }
    }
    return remaining;
  }

  // ---------------------------------------------------------------------------
  // availableOrders / availableOrderLines
  // ---------------------------------------------------------------------------

  private NeoResponse availableOrders(Invoice invoice) throws JSONException {
    boolean sales = Boolean.TRUE.equals(invoice.isSalesTransaction());
    String partnerId = invoice.getBusinessPartner() == null ? null
        : invoice.getBusinessPartner().getId();
    String currencyId = invoice.getCurrency() == null ? null : invoice.getCurrency().getId();
    return discover(findCandidateOrders(partnerId, sales, currencyId), order -> {
      int pendingLines = isReturn(order) ? 0 : pendingToInvoice(order, sales).size();
      if (pendingLines == 0) {
        return null;
      }
      String orderCurrency = order.getCurrency() == null ? null : order.getCurrency().getId();
      return documentRow(order.getId(), order.getDocumentNo(), day(order.getOrderDate()),
          partnerId, order.getBusinessPartner().getName(), orderCurrency, pendingLines);
    });
  }

  private NeoResponse availableOrderLines(Order order) throws JSONException {
    boolean sales = Boolean.TRUE.equals(order.isSalesTransaction());
    Map<String, BigDecimal> pending = pendingToInvoice(order, sales);
    JSONArray rows = new JSONArray();
    for (OrderLine ol : order.getOrderLineList()) {
      BigDecimal left = pending.get(ol.getId());
      if (left != null) {
        rows.put(DocumentLineImport.lineRow(ol.getId(), ol.getLineNo(), ol.getProduct().getId(),
            ol.getProduct().getName(), ol.getUOM() == null ? null : ol.getUOM().getId(), left));
      }
    }
    return completePage(rows);
  }

  // ---------------------------------------------------------------------------
  // Loading and validating
  // ---------------------------------------------------------------------------

  /** A return order is credited through its own flow, never imported into an invoice. */
  private static boolean isReturn(Order order) {
    return order.getTransactionDocument() != null
        && Boolean.TRUE.equals(order.getTransactionDocument().isReturn());
  }

  private Order requireSourceOrder(Invoice invoice, JSONObject body) {
    String orderId = requireParam(body, PARAM_ORDER_ID);
    Order order = loadOrder(orderId);
    if (order == null) {
      throw DocumentLineImport.notFound("Order", orderId);
    }
    requireOwnClient("Order", orderId, order.getClient());
    DocumentLineImport.requireCompletedSource(order.getDocumentNo(), order.getDocumentStatus());
    if (isReturn(order)) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_DIRECTION_MISMATCH,
          "Document " + order.getDocumentNo() + " is a return order: it is credited through its "
              + "own return flow, not imported here");
    }
    DocumentLineImport.requireSameDirection(Boolean.TRUE.equals(invoice.isSalesTransaction()),
        Boolean.TRUE.equals(order.isSalesTransaction()), order.getDocumentNo());
    DocumentLineImport.requireSameBusinessPartner(
        invoice.getBusinessPartner() == null ? null : invoice.getBusinessPartner().getId(),
        order.getBusinessPartner() == null ? null : order.getBusinessPartner().getId(),
        order.getDocumentNo());
    DocumentLineImport.requireSameCurrency(
        invoice.getCurrency() == null ? null : invoice.getCurrency().getId(),
        order.getCurrency() == null ? null : order.getCurrency().getId(), order.getDocumentNo());
    return order;
  }

  private static String label(OrderLine ol) {
    return ol.getProduct() != null && ol.getProduct().getName() != null
        ? ol.getProduct().getName() : ol.getId();
  }

  // ---------------------------------------------------------------------------
  // Seams (DAL) — overridden by the unit tests
  // ---------------------------------------------------------------------------

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

  protected void lockInOutLines(Collection<String> ids) throws SQLException {
    DocumentLineImport.lockInOutLines(ids);
  }

  /** Per order line, the quantity held by the lines of DRAFT invoices of the direction. */
  protected Map<String, BigDecimal> draftInvoicedQuantities(Collection<String> orderLineIds,
      boolean sales) {
    List<Object[]> rows = OBDal.getInstance().getSession().createQuery(
        "select il.salesOrderLine.id, il.invoicedQuantity from InvoiceLine il "
            + "join il.invoice i where i.documentStatus = 'DR' and i.salesTransaction = :sot "
            + "and il.active = true and il.salesOrderLine.id in :ids", Object[].class)
        .setParameter("sot", sales).setParameterList("ids", orderLineIds).list();
    Map<String, BigDecimal> held = new LinkedHashMap<>();
    for (Object[] row : rows) {
      held.merge((String) row[0], row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1],
          BigDecimal::add);
    }
    return held;
  }

  /**
   * The COMPLETED receipt / shipment lines of an order line, oldest first (movement date, then
   * id): the lines an invoice quantity is distributed over before any remainder.
   */
  protected List<ShipmentInOutLine> movementLinesOf(OrderLine orderLine) {
    return OBDal.getInstance().getSession().createQuery(
        "select il from MaterialMgmtShipmentInOutLine il join il.shipmentReceipt sh "
            + "where il.salesOrderLine.id = :id and sh.processed = 'Y' "
            + "and sh.documentStatus = 'CO' and il.active = true "
            + "order by sh.movementDate, il.id", ShipmentInOutLine.class)
        .setParameter("id", orderLine.getId()).list();
  }

  /** Pending MAGNITUDE per movement line of one document, drafts included (the receipt cap). */
  protected Map<String, BigDecimal> movementPending(String inOutId) {
    return NeoInvoiceSupport.computePendingQtyPerLineOrThrow(inOutId, true);
  }

  /**
   * What the native process still sees to invoice on each movement line of the order line: the
   * population of {@code CreateLinesFromUtil#getRelatedNotInvoicedInOutLines} with its quantity
   * (purchase: movement minus matched invoices; sales: the whole line of a shipment not completely
   * invoiced). An order-line entry WITHOUT a movement line is replaced by exactly these lines, so
   * the plain remainder entry is only safe once every one of them is fully taken by an invoice
   * line of the target.
   */
  protected Map<String, BigDecimal> nativeRelatedRemaining(OrderLine orderLine, boolean sales) {
    String hql = sales
        ? "select il.id, il.movementQuantity from MaterialMgmtShipmentInOutLine il "
            + "join il.shipmentReceipt sh where il.salesOrderLine.id = :id "
            + "and sh.processed = 'Y' and sh.documentStatus in ('CO', 'CL') "
            + "and sh.completelyInvoiced = 'N' and il.reinvoice = 'N' order by il.id"
        : "select il.id, il.movementQuantity - sum(coalesce(mi.quantity, 0)) "
            + "from MaterialMgmtShipmentInOutLine il join il.shipmentReceipt sh "
            + "left join il.procurementReceiptInvoiceMatchList mi "
            + "where il.salesOrderLine.id = :id and sh.processed = 'Y' "
            + "and sh.documentStatus in ('CO', 'CL') "
            + "group by il.id, il.movementQuantity "
            + "having (il.movementQuantity - sum(coalesce(mi.quantity, 0)) <> 0) "
            + "order by il.id";
    List<Object[]> rows = OBDal.getInstance().getSession().createQuery(hql, Object[].class)
        .setParameter("id", orderLine.getId()).list();
    Map<String, BigDecimal> remaining = new LinkedHashMap<>();
    for (Object[] row : rows) {
      remaining.put((String) row[0], (BigDecimal) row[1]);
    }
    return remaining;
  }

  /** Completed orders of the partner, newest first; a bounded scan, not the whole history. */
  protected List<Order> findCandidateOrders(String partnerId, boolean sales, String currencyId) {
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
        .setMaxResults(scanLimit());
    if (currencyId != null) {
      query.setParameter("cur", currencyId);
    }
    return new ArrayList<>(query.list());
  }
}
