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
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * {@link OrderInvoiceBatching#buildBatches} for why the two kinds go in separate native calls
 * and how the remainder is protected from the native process swapping it for receipt lines;
 * nothing is refused for having deliveries.
 *
 * <p>{@code importOrderLines} takes one order ({@code orderId} + {@code lines}) or several
 * ({@code orders:[{orderId, lines?}]}), all validated, locked and planned before the first write
 * and imported in ONE transaction, the way the window's "Add from order" popup does.
 *
 * <p><b>The global discount</b> (a percentage, never an amount) follows the popup (ETP-5828). With
 * {@code carryGlobalDiscount} true (the default) the header's {@code etgoTotalDiscount} takes the
 * orders' {@code etgoTotalDiscount} when every selected order has the same positive percentage and,
 * judged on the invoice BEFORE the call, the invoice already had that percentage or had no total
 * discount AND no lines (the total-discount line does not count). Orders and invoice all without a
 * discount means nothing to carry. Any other case is a conflict
 * ({@link InvoiceGlobalDiscountRule#discountConflict}), including an invoice with a total discount
 * and orders without one, and answers 422 {@value #ERROR_GLOBAL_DISCOUNT_CONFLICT} before anything
 * is written, so the caller can ask the user and resend with {@code carryGlobalDiscount:false}: the
 * lines are imported and the header's total discount is not touched. The lines always keep their own line discount. The
 * discount line itself is materialised when the invoice is completed, as always. Like the popup,
 * each {@code availableOrders} row carries the order's {@code globalDiscount} % and a successful
 * import says what happened to it ({@link InvoiceGlobalDiscountRule#outcome}), so an agent can
 * tell the user what the popup's tag and toast would have shown.
 *
 * <p>Not a binding of its own: an {@code @Inject}ed delegate of the sales and purchase invoice
 * header NeoExtensions, resolved by type (no {@code @Named}, no {@code Java_Qualifier}).
 */
public class ImportOrderLinesIntoInvoiceHandler extends AbstractInvoiceLineImportHandler {

  static final String ACTION_AVAILABLE_ORDERS = "availableOrders";
  static final String ACTION_AVAILABLE_LINES = "availableOrderLines";
  static final String ACTION_IMPORT = "importOrderLines";

  /** See {@link InvoiceGlobalDiscountRule#ERROR_GLOBAL_DISCOUNT_CONFLICT}. */
  static final String ERROR_GLOBAL_DISCOUNT_CONFLICT =
      InvoiceGlobalDiscountRule.ERROR_GLOBAL_DISCOUNT_CONFLICT;
  /** See {@link InvoiceGlobalDiscountRule#CONFLICT_ORDERS_DIFFER}. */
  static final String CONFLICT_ORDERS_DIFFER = InvoiceGlobalDiscountRule.CONFLICT_ORDERS_DIFFER;
  /** See {@link InvoiceGlobalDiscountRule#CONFLICT_INVOICE_OTHER_DISCOUNT}. */
  static final String CONFLICT_INVOICE_OTHER_DISCOUNT =
      InvoiceGlobalDiscountRule.CONFLICT_INVOICE_OTHER_DISCOUNT;
  /** See {@link InvoiceGlobalDiscountRule#CONFLICT_INVOICE_LINES_WITHOUT_DISCOUNT}. */
  static final String CONFLICT_INVOICE_LINES_WITHOUT_DISCOUNT =
      InvoiceGlobalDiscountRule.CONFLICT_INVOICE_LINES_WITHOUT_DISCOUNT;

  private static final String PARAM_ORDER_ID = OrderImportRequest.PARAM_ORDER_ID;
  private static final String PARAM_ORDERS = OrderImportRequest.PARAM_ORDERS;
  private static final String PARAM_CARRY_DISCOUNT = OrderImportRequest.PARAM_CARRY_DISCOUNT;
  /**
   * The order's global discount on an {@code availableOrders} row, and what happened to it on an
   * {@code importOrderLines} answer (see {@link InvoiceGlobalDiscountRule#outcome}).
   */
  static final String KEY_GLOBAL_DISCOUNT = "globalDiscount";
  static final String KEY_AMOUNT = "amount";
  static final String KEY_UNIT_PRICE = "unitPrice";
  static final String KEY_DISCOUNT = "discount";
  /**
   * {@code availableOrderLines}: the OTHER draft invoices holding part of the line,
   * {@code [{id, documentNo}]}, present only when there is one (ETP-5828 QA UI-4).
   */
  static final String KEY_IN_DRAFT_INVOICES = "inDraftInvoices";
  /** {@code availableOrderLines} with {@code detail}: THIS invoice has a line of the order line. */
  static final String KEY_IMPORTED_HERE = "importedHere";
  /** The three order actions of a normal invoice (a rectificative takes none, ETP-5876). */
  static final Set<String> ACTIONS =
      Set.of(ACTION_AVAILABLE_ORDERS, ACTION_AVAILABLE_LINES, ACTION_IMPORT);
  private static final int MAX_CANDIDATES = 200;
  private static final int MAX_ORDERS = OrderImportRequest.MAX_ORDERS;
  /** {@code availableOrders}: how many orders to list (ETP-5828; both directions since ETP-6099). */
  static final String PARAM_LIMIT = ImportListing.PARAM_LIMIT;
  /** {@code availableOrders} / {@code availableOrderLines}: also what has nothing left. */
  static final String PARAM_INCLUDE_COVERED = ImportListing.PARAM_INCLUDE_COVERED;
  /** The longest {@code availableOrders} page a caller may ask for. */
  static final int MAX_LISTED_ORDERS = ImportListing.MAX_LIMIT;

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
        + "completed, no draft to fill) call " + oneStep + " instead. A rectificative invoice "
        + "answers 422 importNotAvailableOnRectificative: its lines come from a return or from the "
        + "invoice it rectifies.";
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(ACTION_AVAILABLE_ORDERS, NeoActionContract.read(ACTION_AVAILABLE_ORDERS,
        "Lists the completed " + order + "s of this " + doc + "'s business partner and currency "
            + "that still have quantity to invoice: {data:[{id, documentNo, date, businessPartner, "
            + "businessPartner$_identifier, currency, pendingLines, globalDiscount, amount}], "
            + "hasMore, limit, scanLimit}; globalDiscount is the order's global discount as a % (0 "
            + "when it has none), the one importOrderLines carries to the invoice; amount is the "
            + "sum of its pending lines' amounts (unit price x pending quantity), WITHOUT the "
            + "global discount, as the application's import popup shows it. hasMore true means the list may be incomplete: it looks at the scanLimit most recent candidates and returns at most limit documents, so a document it does not list may still be importable when you know its id. Pending counts what draft "
            + "invoices already hold." + (sales ? " Only orders with a line not fully invoiced are "
            + "looked at, newest created first." : "") + " limit (1 to " + MAX_LISTED_ORDERS
            + "), offset and includeCovered are optional; with limit or offset the answer adds "
            + "offset and nextOffset (page on while hasMore). includeCovered also lists the orders "
            + "whose pending lines are all held by draft invoices, with pendingLines 0 and amount 0; "
            + "detail adds creationDate (ISO instant, UTC) and leaves out of pendingLines and "
            + "amount the lines this invoice already holds (an order left at 0 is then listed only "
            + "with includeCovered)."
            + " Step 1." + flow,
        OrderImportListing.availableOrdersParams())
        .withIdDescription(DocumentLineImport.ID_OF_THE_DRAFT + doc + " the lines would be imported into.")
        .availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(ACTION_AVAILABLE_LINES, NeoActionContract.read(ACTION_AVAILABLE_LINES,
        "Lists the lines of a " + order + " that can still be invoiced here: {data:[{id, lineNo, "
            + "product, product$_identifier, uOM, pendingQuantity, unitPrice, discount, amount}], "
            + "hasMore:false}, where unitPrice is the order line's net price (its line discount "
            + "already applied), discount that line discount as a %, amount unitPrice x "
            + "pendingQuantity (what the import popup shows), and pendingQuantity is "
            + "ordered minus invoiced minus what any draft invoice (this one included) already "
            + "holds, and the id is the sourceLineId importOrderLines takes. When other draft "
            + "invoices hold part of the line, inDraftInvoices lists them ([{id, documentNo, "
            + "quantity}], this invoice left out; quantity is what that draft holds of the "
            + "line): tell the user which draft holds what is missing. "
            + "includeCovered also lists the lines with nothing left at pendingQuantity 0; "
            + "detail adds "
            + OrderImportListing.LINE_DETAIL_KEYS + " and importedHere (this invoice has an "
            + "active line linked to the order line). Step 2." + flow,
        OrderImportListing.availableLinesParams())
        .withIdDescription(DocumentLineImport.ID_OF_THE_DRAFT + doc + " the lines would be imported into.")
        .availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(ACTION_IMPORT, NeoActionContract.write(ACTION_IMPORT,
        "Adds lines of one or several completed " + order + "s to this DRAFT " + doc
            + " (product, unit of measure, tax, price and discount copied from the order line, "
            + "linked to it), in ONE transaction, WITHOUT completing the invoice. Pass either "
            + "orderId (+ optional lines) for one order, or orders:[{orderId, lines?}] to import "
            + "several orders together (never both). Without lines an order imports everything "
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
            + "left empty when several orders are mixed. Global discount (always a %): with "
            + "carryGlobalDiscount true (the default) the invoice's total discount takes the "
            + "orders' global discount when ALL selected orders have the same positive % and, "
            + "before this call, the invoice had that same % or had neither a total discount nor "
            + "lines (a total-discount line does not count). When neither the orders nor the "
            + "invoice have a discount there is nothing to carry. Otherwise it answers 422 "
            + ERROR_GLOBAL_DISCOUNT_CONFLICT + " and creates NOTHING: the orders have different % "
            + "(0 counts as different), the invoice already has another total discount (also when "
            + "the orders have none), or it already has lines and no total discount; ask the "
            + "user, and to import anyway resend the same call with carryGlobalDiscount:false "
            + "(lines keep their own line discount, the invoice's total discount is not touched). "
            + "The order's invoiced quantity only moves when the invoice is "
            + "completed. Refused (422) when the invoice is not a draft, an order is not "
            + "completed, or business partner, currency or direction differ. Returns "
            + "{data:{importedCount, lines:[{id, sourceLineId, product, productName, quantity}], "
            + "headerLinked, salesOrder, order, globalDiscount:{applied, percent, reason}}} "
            + "(order is the order the header points at now, also on a purchase invoice; "
            + "salesOrder repeats it under its older name; headerLinked is true only when an imported "
            + "line belongs to it; globalDiscount says whether the invoice's total discount now "
            + "holds the orders' %: applied true with that percent, or applied false, percent "
            + "null and reason " + InvoiceGlobalDiscountRule.REASON_NOT_REQUESTED + " (sent "
            + "carryGlobalDiscount:false) or " + InvoiceGlobalDiscountRule.REASON_NO_ORDER_DISCOUNT
            + " (the orders have no global discount); tell the user which). Step 3." + flow,
        NeoActionContract.Param.optional(PARAM_ORDER_ID, NeoActionContract.TYPE_STRING,
            "Id of the order to import from, returned by availableOrders. Required unless "
                + "orders is given."),
        NeoActionContract.Param.array(DocumentLineImport.KEY_LINES,
            NeoActionContract.TYPE_OBJECT, false,
            "With orderId only. Each {sourceLineId, quantity}: sourceLineId is the id of a line "
                + "from availableOrderLines, quantity a number not above its pendingQuantity. "
                + "Omit to import every pending line in full."),
        NeoActionContract.Param.array(PARAM_ORDERS, NeoActionContract.TYPE_OBJECT, false,
            "Several orders imported together, instead of orderId + lines: each {orderId, "
                + "lines?} with orderId from availableOrders (each order at most once, at most "
                + MAX_ORDERS + ") and lines shaped as above, omitted to import that order's "
                + "pending lines in full."),
        NeoActionContract.Param.optional(PARAM_CARRY_DISCOUNT, NeoActionContract.TYPE_BOOLEAN,
            "Default true: carry the orders' global discount % to the invoice's total discount, "
                + "or answer 422 " + ERROR_GLOBAL_DISCOUNT_CONFLICT + " when it cannot be "
                + "carried. false: import the lines and leave the invoice's total discount "
                + "unchanged; send it only after the user chose so."))
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
    // ETP-5828 (QA MCP-2): the order actions no longer offer the classic pending basis. The
    // purchase popup lists and imports with the server rule, drafts deducted, exactly as the
    // sales popup and the MCP do, and asking for the classic basis here is refused with the
    // generic 400 of an action that does not offer it.
    if (ACTION_AVAILABLE_ORDERS.equals(action)) {
      Invoice invoice = requireExistingInvoice(context.getRecordId());
      return new OrderInvoiceListing(this).availableOrders(invoice, body);
    }
    if (ACTION_AVAILABLE_LINES.equals(action)) {
      Invoice invoice = requireExistingInvoice(context.getRecordId());
      Order order = requireSourceOrder(invoice, requireParam(body, PARAM_ORDER_ID));
      // ETP-6099: includeCovered and detail on both directions (the purchase popup lists here too).
      boolean includeCovered = ImportListing.includeCovered(body);
      boolean detail = ImportListing.detail(body);
      return new OrderInvoiceListing(this).availableOrderLines(invoice, order, includeCovered,
          detail);
    }
    return importLines(requireDraftInvoice(context.getRecordId()), body);
  }

  // ---------------------------------------------------------------------------
  // importOrderLines
  // ---------------------------------------------------------------------------

  /** One order being imported: the order, its lines, what will be created and how. */
  static final class OrderImport {
    final Order order;
    final boolean sales;
    final Map<String, BigDecimal> requested;
    final Map<String, OrderLine> linesById = new LinkedHashMap<>();
    final Map<String, String> documentLines = new LinkedHashMap<>();
    Map<String, BigDecimal> planned;
    Batches batches;

    OrderImport(Order order, Map<String, BigDecimal> requested) {
      this.order = order;
      this.sales = Boolean.TRUE.equals(order.isSalesTransaction());
      this.requested = requested;
      for (OrderLine ol : order.getOrderLineList()) {
        linesById.put(ol.getId(), ol);
        documentLines.put(ol.getId(), label(ol));
      }
    }

    private static String label(OrderLine ol) {
      return ol.getProduct() != null && ol.getProduct().getName() != null
          ? ol.getProduct().getName() : ol.getId();
    }
  }

  /**
   * Validates, locks and plans every order, decides the global discount, and only then creates
   * the lines, order by order: any refusal leaves nothing written.
   */
  private NeoResponse importLines(Invoice invoice, JSONObject body)
      throws SQLException, JSONException {
    List<OrderImportRequest> requests = OrderImportRequest.parseOrderRequests(body);
    boolean carryDiscount = OrderImportRequest.parseCarryDiscount(body);
    List<OrderImport> imports = planImports(invoice, requests);
    // Decided before the movement lines are locked and read, and BEFORE the lines of this call
    // exist: they must not count as "the invoice had lines".
    List<Order> orders = new ArrayList<>();
    for (OrderImport item : imports) {
      orders.add(item.order);
    }
    BigDecimal discount = carryDiscount ? InvoiceGlobalDiscountRule.toCarry(invoice, orders) : null;
    List<DocumentLineImport.ImportedLine> created = createPlannedLines(invoice, imports);
    InvoiceGlobalDiscountRule.apply(invoice, discount);
    requireQuotaAllowed(InvoiceLine.ENTITY_NAME, created);
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put(KEY_GLOBAL_DISCOUNT, InvoiceGlobalDiscountRule.outcome(carryDiscount, discount));
    return DocumentLineImport.ok(created, invoice.getSalesOrder() == null ? null
        : invoice.getSalesOrder().getId(), extra);
  }

  /** Binds every planned order to its movement lines, then creates the lines, order by order. */
  private List<DocumentLineImport.ImportedLine> createPlannedLines(Invoice invoice,
      List<OrderImport> imports) throws SQLException, JSONException {
    OrderInvoiceBatching batching = new OrderInvoiceBatching(this);
    for (OrderImport item : imports) {
      item.batches = batching.buildBatches(item.linesById, item.planned, item.sales);
    }
    List<DocumentLineImport.ImportedLine> created = new ArrayList<>();
    for (OrderImport item : imports) {
      Batches batches = item.batches;
      created.addAll(createLines(invoice, batches.calls, OrderLine.class, item.planned,
          line -> line.getSalesOrderLine(),
          (call, lines) -> batching.unmarkRemainder(batches, call, lines)));
    }
    return created;
  }

  /**
   * The line creation of {@code importOrderLines} for ONE order, into an invoice the caller has
   * just built itself (ETP-5828): "Generar factura" on a sales order whose lines are partly held by
   * draft invoices creates its header from the order and hands the lines here (the quantities
   * chosen by {@link #shipmentScopedPending}, or the caller's explicit {@code lines}), each bound
   * first to the shipment lines it can still take and protected from the native swap. Same
   * validation, lock and plan as {@link #importLines}; no global-discount decision (the caller
   * carries the order's %), no quota check and no completion — those stay with the caller.
   *
   * @param invoice   the new draft invoice, already saved, of the order's partner and currency
   * @param order     the completed order to invoice
   * @param requested {@code orderLineId -> quantity}; empty imports everything still pending
   * @return the created lines
   * @throws DocumentLineImport.ImportRefusal when nothing is pending, a quantity exceeds it or the
   *     created lines do not add up (the latter already rolled back)
   */
  List<DocumentLineImport.ImportedLine> importOrderInto(Invoice invoice, Order order,
      Map<String, BigDecimal> requested) throws SQLException, JSONException {
    List<OrderImport> imports = planImports(invoice,
        List.of(new OrderImportRequest(order.getId(), requested)));
    return createPlannedLines(invoice, imports);
  }

  /**
   * What "Generar factura" on an order partly held by drafts invoices per order line WITHOUT
   * explicit lines (ETP-5828), with the quantity rule of the no-draft creation (ETP-5709): a line
   * with completed, not yet invoiced shipment lines (the population the native process would swap
   * an order-line entry for) is invoiced only for what those shipment lines can still take, net of
   * the drafts (the movement cap counts them) and never above the line's net pending; the
   * unshipped remainder of such a line is NOT invoiced. A line without such shipment lines — also
   * one whose shipped quantity the drafts already hold entirely, which net of drafts has none left
   * — is invoiced for its net pending. A draft therefore only subtracts what it holds; it never
   * changes which rule a quantity falls under.
   *
   * @param order the completed order
   * @return {@code orderLineId -> quantity}, signed like the line; every line with a net pending
   *     is present, so the map is empty only when nothing is pending net of the drafts
   */
  Map<String, BigDecimal> shipmentScopedPending(Order order) {
    boolean sales = Boolean.TRUE.equals(order.isSalesTransaction());
    Map<String, BigDecimal> net = pendingToInvoice(order, sales);
    Map<String, BigDecimal> scoped = new LinkedHashMap<>();
    for (OrderLine ol : order.getOrderLineList()) {
      BigDecimal left = net.get(ol.getId());
      if (left == null) {
        continue;
      }
      BigDecimal shipped = shippedLeftToInvoice(ol, left, sales);
      // No shipment line left to take (none, or the drafts hold all that was shipped): the second
      // case of the rule, the net pending as a plain line (the import protects it from the swap).
      scoped.put(ol.getId(), shipped.signum() != 0 ? shipped : left);
    }
    return scoped;
  }

  /**
   * How much of {@code netPending} the order line's completed, not yet invoiced shipment / receipt
   * lines can still take, drafts counted (the movement cap), signed like the line; zero when it has
   * none (the native population is empty) or the drafts already hold all of them (ETP-5828).
   */
  BigDecimal shippedLeftToInvoice(OrderLine ol, BigDecimal netPending, boolean sales) {
    if (nativeRelatedRemaining(ol, sales).isEmpty()) {
      return BigDecimal.ZERO;
    }
    List<ShipmentInOutLine> movements = movementLinesOf(ol);
    Map<String, BigDecimal> pendingByMovement = new HashMap<>();
    for (String documentId : OrderLineMovementAllocation.documentIdsOf(movements)) {
      pendingByMovement.putAll(movementPending(documentId));
    }
    BigDecimal shipped = BigDecimal.ZERO;
    for (OrderLineMovementAllocation.Allocation allocation : OrderLineMovementAllocation.allocate(
        netPending, OrderLineMovementAllocation.candidatesOf(ol, netPending, movements,
            pendingByMovement))) {
      shipped = shipped.add(allocation.quantity);
    }
    return shipped;
  }

  /**
   * Every order is checked before any is locked, then the lines of all of them are locked in ONE
   * ascending pass, and the cap is read AFTER the lock: a line loaded before it may carry the
   * invoiced quantity from before another import completed.
   */
  private List<OrderImport> planImports(Invoice invoice, List<OrderImportRequest> requests)
      throws SQLException {
    List<OrderImport> imports = new ArrayList<>();
    Set<String> lineIds = new java.util.LinkedHashSet<>();
    for (OrderImportRequest request : requests) {
      OrderImport item = new OrderImport(requireSourceOrder(invoice, request.orderId),
          request.requested);
      lineIds.addAll(item.linesById.keySet());
      imports.add(item);
    }
    lockOrderLines(lineIds);
    for (OrderImport item : imports) {
      refreshOrderLines(item.linesById.values());
      Map<String, Object> source = new LinkedHashMap<>();
      source.put(PARAM_ORDER_ID, item.order.getId());
      source.put("documentNo", item.order.getDocumentNo());
      item.planned = DocumentLineImport.plan(item.requested, item.documentLines,
          pendingToInvoice(item.order, item.sales), "order " + item.order.getDocumentNo(),
          source);
    }
    return imports;
  }

  // ---------------------------------------------------------------------------
  // Distribution over the delivered lines
  // ---------------------------------------------------------------------------

  // The rule itself (candidates, pending, oldest-first allocation with split) lives in
  // OrderLineMovementAllocation, shared with the sales "create invoice from order" path (ETP-6002).

  /** The native calls to make, and what has to be undone on the last one. */
  static final class Batches {
    final List<JSONArray> calls = new ArrayList<>();
    /** Movement lines used only as a marker on a remainder entry; the link is removed again. */
    final Set<String> markerIds = new java.util.HashSet<>();
    /** Index in {@link #calls} of the remainder call, or -1 when there is none. */
    int remainderCall = -1;
  }

  // ---------------------------------------------------------------------------
  // The pending quantity
  // ---------------------------------------------------------------------------

  /**
   * ordered - invoiced - draft, per order line, signed like the order line; lines with nothing left
   * (or over-invoiced, or the synthetic total-discount line) are absent. The rule is
   * {@link OrderInvoicePending}, shared with the sales order's "Generar factura" (ETP-5828); the
   * draft part stays behind the {@link #draftInvoicedQuantities} seam.
   */
  protected Map<String, BigDecimal> pendingToInvoice(Order order, boolean sales) {
    Map<String, BigDecimal> base = OrderInvoicePending.beforeDrafts(order);
    if (base.isEmpty()) {
      return base;
    }
    return afterDrafts(base, draftInvoicedQuantities(base.keySet(), sales));
  }

  /** See {@link OrderInvoicePending#afterDrafts}. */
  static Map<String, BigDecimal> afterDrafts(Map<String, BigDecimal> base,
      Map<String, BigDecimal> drafts) {
    return OrderInvoicePending.afterDrafts(base, drafts);
  }

  /**
   * The popup's order-row amount: unit price × pending quantity summed over the order's pending
   * lines (the line discount is already in the unit price; the order's global discount is NOT
   * applied), at the order currency's precision.
   */
  BigDecimal pendingOrderAmount(Order order, Map<String, BigDecimal> pending) {
    BigDecimal sum = BigDecimal.ZERO;
    for (OrderLine ol : order.getOrderLineList()) {
      BigDecimal left = pending.get(ol.getId());
      if (left != null) {
        sum = sum.add(OrderImportListing.unitPriceOf(ol).multiply(left));
      }
    }
    return sum.setScale(OrderImportListing.amountPrecision(order), RoundingMode.HALF_UP);
  }

  /**
   * The pending lines the popup leaves selectable (ETP-6099, {@code detail}): those not already
   * linked from an active line of THIS invoice. Pending is quantity-based, so a line partly on this
   * invoice still has some left, but the popup greys it out ({@code importedHere}); counting it
   * listed an order that turned out empty once expanded. Without {@code detail} the set is empty
   * and the pending map is returned as is, so the default answer is unchanged.
   */
  static Map<String, BigDecimal> selectableOnly(Map<String, BigDecimal> pending,
      Set<String> importedHere) {
    if (importedHere.isEmpty() || pending.isEmpty()) {
      return pending;
    }
    Map<String, BigDecimal> selectable = new LinkedHashMap<>(pending);
    selectable.keySet().removeAll(importedHere);
    return selectable;
  }

  // ---------------------------------------------------------------------------
  // Loading and validating
  // ---------------------------------------------------------------------------

  private Order requireSourceOrder(Invoice invoice, String orderId) {
    Order order = loadOrder(orderId);
    if (order == null) {
      throw DocumentLineImport.notFound("Order", orderId);
    }
    requireOwnClient("Order", orderId, order.getClient());
    DocumentLineImport.requireCompletedSource(order.getDocumentNo(), order.getDocumentStatus());
    if (OrderInvoiceListing.isReturn(order)) {
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
    return OrderInvoicePending.draftQuantities(orderLineIds, sales);
  }

  /**
   * Seam: per order line, the OTHER draft invoices of the direction that hold it, only those of
   * the session's client and the role's readable organizations (their document number is shown).
   */
  protected Map<String, List<OrderInvoicePending.DraftInvoiceRef>> draftInvoicesHolding(
      Collection<String> orderLineIds, boolean sales, String exceptInvoiceId) {
    return OrderInvoicePending.draftHolders(orderLineIds, sales, exceptInvoiceId,
        sessionClientId(), readableOrganizations());
  }

  /** Seam: the organizations the session's role can read. */
  protected List<String> readableOrganizations() {
    return java.util.Arrays.asList(
        org.openbravo.dal.core.OBContext.getOBContext().getReadableOrganizations());
  }

  /**
   * The COMPLETED receipt / shipment lines of an order line, oldest first (movement date, then
   * id): the lines an invoice quantity is distributed over before any remainder.
   */
  protected List<ShipmentInOutLine> movementLinesOf(OrderLine orderLine) {
    return OrderLineMovementAllocation.completedMovementLines(orderLine);
  }

  /** Pending MAGNITUDE per movement line of one document, drafts included (the receipt cap). */
  protected Map<String, BigDecimal> movementPending(String inOutId) {
    return OrderLineMovementAllocation.movementPending(inOutId);
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

  /** Seam: the order lines the active lines of this invoice are linked to. */
  protected Set<String> orderLinesOnInvoice(String invoiceId) {
    return new java.util.HashSet<>(OBDal.getInstance().getSession().createQuery(
        "select il.salesOrderLine.id from InvoiceLine il where il.invoice.id = :inv "
            + "and il.active = true and il.salesOrderLine is not null", String.class)
        .setParameter("inv", invoiceId).list());
  }

  /**
   * Completed orders of the partner, newest first; a bounded scan of {@code scan} candidates, not
   * the whole history. On the sales side (ETP-5828, see
   * {@link OrderInvoiceListing#availableOrders}) only the orders that still have a line not fully
   * invoiced are candidates — a necessary condition of having something pending, so no order the
   * rows would list is left out — newest created first; the purchase side keeps its order-date
   * scan unchanged.
   */
  protected List<Order> findCandidateOrders(String partnerId, boolean sales, String currencyId,
      int scan) {
    return findCandidateOrders(partnerId, sales, currencyId, 0, scan);
  }

  /**
   * {@link #findCandidateOrders(String, boolean, String, int)} from position {@code offset} of the
   * same ordering (ETP-6099, the page of a caller that pages through the scan).
   */
  protected List<Order> findCandidateOrders(String partnerId, boolean sales, String currencyId,
      int offset, int scan) {
    StringBuilder hql = new StringBuilder("from Order o where o.documentStatus = 'CO' "
        + "and o.salesTransaction = :sot and o.businessPartner.id = :bp and o.active = true");
    hql.append(" and o.client.id = :client");
    if (currencyId != null) {
      hql.append(" and o.currency.id = :cur");
    }
    if (sales) {
      hql.append(" and exists (select 1 from OrderLine ol where ol.salesOrder.id = o.id"
          + " and ol.active = true and ol.product.id <> :dto"
          + " and ol.orderedQuantity <> ol.invoicedQuantity)");
      hql.append(" order by o.creationDate desc, o.documentNo desc, o.id desc");
    } else {
      hql.append(" order by o.orderDate desc, o.documentNo desc, o.id desc");
    }
    org.hibernate.query.Query<Order> query = OBDal.getInstance().getSession()
        .createQuery(hql.toString(), Order.class).setParameter("sot", sales)
        .setParameter("bp", partnerId).setParameter("client", sessionClientId())
        .setFirstResult(offset).setMaxResults(scan);
    if (currencyId != null) {
      query.setParameter("cur", currencyId);
    }
    if (sales) {
      query.setParameter("dto", TotalDiscountService.DISCOUNT_PRODUCT_ID);
    }
    return new ArrayList<>(query.list());
  }
}
