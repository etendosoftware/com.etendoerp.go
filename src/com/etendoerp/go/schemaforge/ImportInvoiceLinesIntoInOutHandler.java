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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Lists what a DRAFT goods shipment (sales) or goods receipt (purchase) can take from a completed
 * invoice (ETP-6099): {@code availableInvoices} and {@code availableInvoiceLines}, the two reads
 * behind the window's "Import from invoice" popup ({@code ImportFromSalesInvoiceModal},
 * {@code ImportFromPurchaseInvoiceModal}), which used to read up to 500 company-wide invoices plus
 * the lines of every one of them. Read-only: the popup keeps creating the movement lines itself
 * (with {@code invoiceLineId}); no import action is added here.
 *
 * <p>What it lists is what the popup shows:
 * <ul>
 *   <li><b>Which invoices.</b> Completed, of the target's business partner and direction, in the
 *       target's currency when it has one, newest created first, and accepted by the header's
 *       {@link #ATTRIBUTE policy} (the goods shipment leaves out rectificatives; the goods receipt
 *       an invoice with a negative grand total). Listed when a line has something pending;
 *       {@code includeCovered} also lists the others, at {@code pendingLines} 0, so the popup can
 *       say "all already imported".</li>
 *   <li><b>Which lines.</b> Active, other than the total-discount line, in line order.</li>
 *   <li><b>How much</b>, by the header's {@link SourcePolicy}. Goods shipment
 *       ({@link SourcePolicy#byDirectLinks}, ETP-6002 CP-22 and review M2): exactly the pending of
 *       the invoice's own «Create shipment» follow-up ({@link #followUpPending},
 *       {@link InvoicePendingResolver}) — {@code invoiced - moved} by the invoice line's OWN links
 *       ({@code C_InvoiceLine.M_InOutLine_ID} or the match table, drafts included), capped by its
 *       order line's {@code QtyOrdered - QtyDelivered - draft lines}, shared out in line order.
 *       The popup, the MCP listing and {@code createShipment} therefore agree. Goods receipt
 *       (the popup's rule, delivered NOT deducted): a linked line has 0, any other
 *       {@code invoiced - drafts}, drafts being the OTHER draft receipts holding its order
 *       line.</li>
 * </ul>
 * The direction comes from the target document, never from the entity; the per-direction rule is
 * the header customization's own ({@link #ATTRIBUTE}). A plain CDI bean injected by the goods
 * shipment and goods receipt header NeoExtensions: no {@code @Named}, no {@code @NeoExtension}.
 */
public class ImportInvoiceLinesIntoInOutHandler extends AbstractLineImportHandler {

  static final String ACTION_AVAILABLE_INVOICES = "availableInvoices";
  static final String ACTION_AVAILABLE_LINES = "availableInvoiceLines";
  static final Set<String> ACTIONS = Set.of(ACTION_AVAILABLE_INVOICES, ACTION_AVAILABLE_LINES);

  /**
   * The {@link NeoContext} attribute the header customization stores its {@link SourcePolicy}
   * under. Absent, {@link SourcePolicy#DEFAULT}.
   */
  static final String ATTRIBUTE = "invoiceIntoInOutImportPolicy";

  /**
   * The per-direction rules of the header that injects this delegate (ETP-6099): which completed
   * invoices of the partner its popup offers, and what the pending of a line deducts. Decided by
   * the header customization, never by this delegate.
   */
  static final class SourcePolicy {
    /** Every invoice offered; delivered and every draft, the target's included, deducted. */
    static final SourcePolicy DEFAULT = new SourcePolicy(invoice -> true, true, true);

    final Predicate<Invoice> offered;
    /** Whether the pending of a line with an order line deducts what that order line delivered. */
    final boolean deductsDelivered;
    /** Whether the drafts deducted include the target's own lines. */
    final boolean deductsTargetDraft;
    /**
     * Whether the two listings accept {@code pendingBasis: "classic"} (ETP-6099 follow-up, product
     * decision): the goods receipt's popup rule from before it listed through the actions, a line
     * at {@code linked ? 0 : invoiced} with NO draft deduction ({@link #classicLineStates}).
     */
    final boolean offersClassicPending;
    /**
     * Whether a line pends by its own movement links ({@link #byDirectLinks}, ETP-6002 CP-22)
     * instead of by what its order line delivered. Such a policy also gives
     * {@code availableInvoices} rows (with {@code detail}) their pending {@code amount}.
     */
    final boolean pendsByDirectLinks;

    SourcePolicy(Predicate<Invoice> offered, boolean deductsDelivered,
        boolean deductsTargetDraft) {
      this(offered, deductsDelivered, deductsTargetDraft, false);
    }

    SourcePolicy(Predicate<Invoice> offered, boolean deductsDelivered,
        boolean deductsTargetDraft, boolean offersClassicPending) {
      this(offered, deductsDelivered, deductsTargetDraft, offersClassicPending, false);
    }

    private SourcePolicy(Predicate<Invoice> offered, boolean deductsDelivered,
        boolean deductsTargetDraft, boolean offersClassicPending, boolean pendsByDirectLinks) {
      this.offered = offered;
      this.deductsDelivered = deductsDelivered;
      this.deductsTargetDraft = deductsTargetDraft;
      this.offersClassicPending = offersClassicPending;
      this.pendsByDirectLinks = pendsByDirectLinks;
    }

    /**
     * A line pends as the invoice's follow-up computes it ({@link #followUpPending}): by its own
     * links (draft and completed movement lines, the target's included), capped by its order
     * line's {@code QtyOrdered - QtyDelivered - drafts}. A movement line linked to the invoice
     * line counts whatever order line it carries, or none, so a line created by the window's popup
     * and one created through the MCP with only {@code invoiceLineId} give the same pending.
     */
    static SourcePolicy byDirectLinks(Predicate<Invoice> offered) {
      return new SourcePolicy(offered, true, true, false, true);
    }
  }

  static final String PARAM_INVOICE_ID = "invoiceId";
  private static final int MAX_CANDIDATES = 200;
  private static final int MAX_INVOICES = 50;
  private static final String INVOICE = "Invoice";

  /**
   * The two contracts of the document this delegate is injected in.
   *
   * @param sales {@code true} for a goods shipment (sales invoices), {@code false} for a goods
   *              receipt (purchase invoices)
   */
  static Map<String, NeoActionContract> contracts(boolean sales) {
    String doc = sales ? "goods shipment" : "goods receipt";
    String invoice = sales ? "sales invoice" : "purchase invoice";
    String which = sales ? " Rectificative invoices are never listed."
        : " Invoices with a negative grand total are never listed.";
    String flow = " Flow: on a DRAFT " + doc + ", " + ACTION_AVAILABLE_INVOICES + ", then "
        + ACTION_AVAILABLE_LINES + "; the lines are then created on the " + doc + " lines (each "
        + "with the invoice line in invoiceLineId), as the window's import popup does. Read-only: "
        + "nothing is written by these two actions.";
    String on = DocumentLineImport.ID_OF_THE_DRAFT + doc + " the lines would be imported into.";
    String pendingRule = sales
        ? "pendingQuantity is the invoiced quantity minus what the " + doc + " lines linked to "
            + "THIS invoice line already ship or hold (completed or DRAFT, this one included; a "
            + doc + " line is linked when it was created with invoiceLineId), never below 0. A "
            + "line with its own order line is also capped by that order line's ordered minus "
            + "delivered minus what any DRAFT " + doc + " holds of it. Only the invoice line's "
            + "own links count, never another invoice's shipments of the same order line. It is "
            + "the same pending the invoice's createShipment action uses"
        : "pendingQuantity is 0 for a line already linked to a " + doc + " line, and otherwise the "
            + "invoiced quantity minus what OTHER draft " + doc + "s hold of its order line (its "
            + "own, or the invoice's order line of the same product), never below 0; what the "
            + "order line already delivered is not deducted";
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(ACTION_AVAILABLE_INVOICES, NeoActionContract.read(ACTION_AVAILABLE_INVOICES,
        "Lists the completed " + invoice + "s of this " + doc + "'s business partner and "
            + "currency with a line still to " + (sales ? "ship" : "receive") + ": {data:[{id, "
            + "documentNo, date, businessPartner, businessPartner$_identifier, currency, "
            + "pendingLines, order}], hasMore, limit, scanLimit}, newest created first; order is "
            + "the invoice's order id or null." + which + " hasMore true means the list may be "
            + "incomplete. limit (1 to " + ImportListing.MAX_LIMIT + "), offset and "
            + "includeCovered are optional; with limit or offset the answer adds offset and "
            + "nextOffset (page on while hasMore); detail adds creationDate"
            + (sales ? " and amount (unit price x pendingQuantity summed over the lines with a "
                + "positive pending, at the invoice currency's precision: the import popup's row "
                + "total)" : "")
            + ". includeCovered also lists the invoices with nothing left, at pendingLines 0. "
            + "Step 1." + flow,
        ImportListing.listParams(MAX_INVOICES, "creationDate (ISO instant, UTC)"))
        .withIdDescription(on).availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(ACTION_AVAILABLE_LINES, NeoActionContract.read(ACTION_AVAILABLE_LINES,
        "Lists the lines of a " + invoice + " to " + (sales ? "ship" : "receive") + " here: "
            + "{data:[{id, lineNo, product, product$_identifier, uOM, pendingQuantity, "
            + "invoicedQuantity, orderLine, linked, inDraftDocuments"
            + (sales ? ", unitPrice" : "") + "}], hasMore:false}, where "
            + pendingRule + "; orderLine is that order line id or null, linked whether the invoice "
            + "line already points at a " + doc + " line, and inDraftDocuments the document "
            + "numbers of the OTHER draft " + doc + "s holding its order line. Only the lines with "
            + "something pending are listed unless includeCovered is true. Step 2." + flow,
        ImportListing.linesParams(null,
            NeoActionContract.Param.required(PARAM_INVOICE_ID, NeoActionContract.TYPE_STRING,
                "Id of an invoice returned by " + ACTION_AVAILABLE_INVOICES + ".")))
        .withIdDescription(on).availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    return contracts;
  }

  @Override
  protected int scanLimit() {
    return MAX_CANDIDATES;
  }

  @Override
  protected int rowLimit() {
    return MAX_INVOICES;
  }

  @Override
  protected Set<String> actions() {
    return ACTIONS;
  }

  /** Both listings take {@code pendingBasis: "classic"} where the header's policy offers it. */
  @Override
  protected boolean classicPendingOffered(NeoContext context, String action) {
    return ACTIONS.contains(action) && policyOf(context).offersClassicPending;
  }

  @Override
  protected NeoResponse execute(NeoContext context, String action) throws Exception {
    ShipmentInOut target = requireTarget(context.getRecordId());
    SourcePolicy policy = policyOf(context);
    JSONObject body = context.getRequestBody();
    boolean classic = classicPending(context);
    if (ACTION_AVAILABLE_INVOICES.equals(action)) {
      return availableInvoices(target, policy, listRequest(body), classic);
    }
    Invoice source = requireSource(target, requireParam(body, PARAM_INVOICE_ID), policy.offered);
    return availableLines(target, source, policy, ImportListing.includeCovered(body), classic);
  }

  private static SourcePolicy policyOf(NeoContext context) {
    Object stored = context.getAttribute(ATTRIBUTE);
    return stored instanceof SourcePolicy ? (SourcePolicy) stored : SourcePolicy.DEFAULT;
  }

  // ---------------------------------------------------------------------------
  // availableInvoices / availableInvoiceLines
  // ---------------------------------------------------------------------------

  private NeoResponse availableInvoices(ShipmentInOut target, SourcePolicy policy,
      ImportListing.ListRequest request, boolean classic) throws JSONException {
    boolean sales = isSales(target);
    String partnerId = target.getBusinessPartner() == null ? null
        : target.getBusinessPartner().getId();
    String currencyId = currencyOf(target);
    List<Invoice> candidates = findCandidateInvoices(partnerId, sales, currencyId, request.offset,
        request.scan);
    return discover(candidates, invoice -> {
      if (!policy.offered.test(invoice)) {
        return null;
      }
      List<LineState> lines = statesOfSourceInvoice(target, invoice, policy, classic);
      int pendingLines = countPendingLines(lines);
      if (pendingLines == 0 && !(request.includeCovered && !lines.isEmpty())) {
        return null;
      }
      JSONObject row = availableInvoiceRow(invoice, partnerId, pendingLines, request.detail);
      if (request.detail && policy.pendsByDirectLinks && !classic) {
        row.put("amount", pendingAmount(invoice, lines));
      }
      return row;
    }, request);
  }

  /**
   * The popup's row total of an invoice (ETP-6002 CP-27): unit price x pending over the lines
   * with a positive pending, at the invoice currency's precision — the same figure
   * {@code availableOrders} gives an order ({@code ImportOrderLinesIntoInOutHandler}).
   */
  static BigDecimal pendingAmount(Invoice invoice, List<LineState> lines) {
    BigDecimal sum = BigDecimal.ZERO;
    for (LineState state : lines) {
      if (state.pending.signum() > 0) {
        sum = sum.add(orZero(state.line.getUnitPrice()).multiply(state.pending));
      }
    }
    Currency currency = invoice.getCurrency();
    int precision = currency == null || currency.getStandardPrecision() == null ? 2
        : currency.getStandardPrecision().intValue();
    return sum.setScale(precision, RoundingMode.HALF_UP);
  }

  /** The listed lines of {@code invoice} under the requested pending basis. */
  private List<LineState> statesOfSourceInvoice(ShipmentInOut target, Invoice invoice,
      SourcePolicy policy, boolean classic) {
    return classic ? classicLineStates(invoice) : lineStates(target, invoice, policy);
  }

  /** How many of {@code lines} keep a pending above 0. */
  private static int countPendingLines(List<LineState> lines) {
    int pendingLines = 0;
    for (LineState line : lines) {
      pendingLines += line.pending.signum() > 0 ? 1 : 0;
    }
    return pendingLines;
  }

  /** The {@code availableInvoices} row of one offered invoice. */
  private static JSONObject availableInvoiceRow(Invoice invoice, String partnerId,
      int pendingLines, boolean detail) throws JSONException {
    JSONObject row = documentRow(invoice.getId(), invoice.getDocumentNo(),
        day(invoice.getInvoiceDate()), partnerId, invoice.getBusinessPartner().getName(),
        invoice.getCurrency() == null ? null : invoice.getCurrency().getId(), pendingLines);
    ImportListing.putNullable(row, "order",
        invoice.getSalesOrder() == null ? null : invoice.getSalesOrder().getId());
    if (detail) {
      ImportListing.putCreationDate(row, invoice.getCreationDate());
    }
    return row;
  }

  private NeoResponse availableLines(ShipmentInOut target, Invoice source, SourcePolicy policy,
      boolean includeCovered, boolean classic) throws JSONException {
    JSONArray rows = new JSONArray();
    for (LineState state : statesOfSourceInvoice(target, source, policy, classic)) {
      if (state.pending.signum() <= 0 && !includeCovered) {
        continue;
      }
      InvoiceLine line = state.line;
      JSONObject row = DocumentLineImport.lineRow(line.getId(), line.getLineNo(),
          line.getProduct() == null ? null : line.getProduct().getId(),
          line.getProduct() == null ? null : line.getProduct().getName(),
          line.getUOM() == null ? null : line.getUOM().getId(), state.pending);
      ImportListing.putNullable(row, "invoicedQuantity", line.getInvoicedQuantity());
      ImportListing.putNullable(row, "orderLine", state.orderLineId);
      row.put("linked", line.getGoodsShipmentLine() != null);
      row.put("inDraftDocuments", new JSONArray(state.draftDocuments));
      if (policy.pendsByDirectLinks && !classic) {
        // ETP-6002 CP-27: the popup's row total follows the loaded lines, priced like an order's.
        ImportListing.putNullable(row, "unitPrice", line.getUnitPrice());
      }
      rows.put(row);
    }
    return completePage(rows);
  }

  /** One listed invoice line and what the popup shows of it. */
  static final class LineState {
    final InvoiceLine line;
    final String orderLineId;
    final BigDecimal pending;
    final Collection<String> draftDocuments;

    LineState(InvoiceLine line, String orderLineId, BigDecimal pending,
        Collection<String> draftDocuments) {
      this.line = line;
      this.orderLineId = orderLineId;
      this.pending = pending;
      this.draftDocuments = draftDocuments;
    }
  }

  /** A quantity a DRAFT movement line holds of an order line. */
  static final class DraftHolding {
    final String orderLineId;
    final String inOutId;
    final String documentNo;
    final BigDecimal quantity;

    DraftHolding(String orderLineId, String inOutId, String documentNo, BigDecimal quantity) {
      this.orderLineId = orderLineId;
      this.inOutId = inOutId;
      this.documentNo = documentNo;
      this.quantity = quantity;
    }
  }

  /** The listed lines of {@code invoice} with their pending, as the class comment states. */
  List<LineState> lineStates(ShipmentInOut target, Invoice invoice, SourcePolicy policy) {
    List<InvoiceLine> lines = listedLines(invoice);
    Map<String, OrderLine> orderLineOf = orderLinesOfInvoiceLines(lines,
        headerOrderLinesByProduct(invoice));
    Set<String> orderLineIds = new LinkedHashSet<>();
    orderLineOf.values().forEach(ol -> orderLineIds.add(ol.getId()));
    DraftTotals drafts = draftTotals(target, orderLineIds, policy);
    if (policy.pendsByDirectLinks) {
      return directLinkLineStates(target, invoice, lines, orderLineOf, drafts);
    }
    List<LineState> states = new ArrayList<>();
    for (InvoiceLine line : lines) {
      OrderLine orderLine = orderLineOf.get(line.getId());
      BigDecimal pending = pendingOfInvoiceLine(line, orderLine, policy, drafts.held);
      states.add(new LineState(line, orderLine == null ? null : orderLine.getId(),
          pending.max(BigDecimal.ZERO), orderLine == null ? Set.of()
              : drafts.others.getOrDefault(orderLine.getId(), Set.of())));
    }
    return states;
  }

  /** The order line each of {@code lines} resolves to, keyed by invoice line id (none: absent). */
  private static Map<String, OrderLine> orderLinesOfInvoiceLines(List<InvoiceLine> lines,
      Map<String, OrderLine> byProduct) {
    Map<String, OrderLine> orderLineOf = new HashMap<>();
    for (InvoiceLine line : lines) {
      OrderLine orderLine = resolveOrderLine(line, byProduct);
      if (orderLine != null) {
        orderLineOf.put(line.getId(), orderLine);
      }
    }
    return orderLineOf;
  }

  /**
   * The order line of an invoice line: its own, else the header order's line of the same product
   * (the popup's map).
   */
  private static OrderLine resolveOrderLine(InvoiceLine line, Map<String, OrderLine> byProduct) {
    if (line.getSalesOrderLine() != null) {
      return line.getSalesOrderLine();
    }
    String productId = line.getProduct() == null ? null : line.getProduct().getId();
    return byProduct.get(productId);
  }

  /** What the draft movement lines hold of the order lines, split as lineStates uses it. */
  private static final class DraftTotals {
    /** Quantity deducted per order line. */
    final Map<String, BigDecimal> held = new HashMap<>();
    /** Document numbers of the OTHER drafts holding each order line. */
    final Map<String, Set<String>> others = new HashMap<>();
  }

  private DraftTotals draftTotals(ShipmentInOut target, Set<String> orderLineIds,
      SourcePolicy policy) {
    DraftTotals totals = new DraftTotals();
    for (DraftHolding holding : draftHoldings(orderLineIds, isSales(target))) {
      boolean ofTarget = holding.inOutId.equals(target.getId());
      if (!ofTarget || policy.deductsTargetDraft) {
        totals.held.merge(holding.orderLineId, orZero(holding.quantity), BigDecimal::add);
      }
      if (!ofTarget) {
        totals.others.computeIfAbsent(holding.orderLineId, k -> new LinkedHashSet<>())
            .add(holding.documentNo);
      }
    }
    return totals;
  }

  /** The pending of one invoice line before the floor at 0, as the class comment states. */
  private static BigDecimal pendingOfInvoiceLine(InvoiceLine line, OrderLine orderLine,
      SourcePolicy policy, Map<String, BigDecimal> held) {
    BigDecimal quantity = orZero(line.getInvoicedQuantity());
    if (orderLine != null && (policy.deductsDelivered || line.getGoodsShipmentLine() == null)) {
      BigDecimal delivered = policy.deductsDelivered ? orZero(orderLine.getDeliveredQuantity())
          : BigDecimal.ZERO;
      return quantity.subtract(delivered)
          .subtract(held.getOrDefault(orderLine.getId(), BigDecimal.ZERO));
    }
    return line.getGoodsShipmentLine() != null ? BigDecimal.ZERO : quantity;
  }

  // ---------------------------------------------------------------------------
  // Pending by direct links (SourcePolicy.byDirectLinks, ETP-6002 CP-22)
  // ---------------------------------------------------------------------------

  /**
   * The lines of {@code invoice} under {@link SourcePolicy#byDirectLinks}: each at the pending the
   * invoice's own «Create shipment» follow-up computes ({@link #followUpPending}, one rule for the
   * popup, the MCP listing and {@code createShipment} — ETP-6002 review M2). A listed line the
   * follow-up does not carry (no product, nothing pending) is at 0. The draft note is the same as
   * under the order-line rule.
   */
  private List<LineState> directLinkLineStates(ShipmentInOut target, Invoice invoice,
      List<InvoiceLine> lines, Map<String, OrderLine> orderLineOf, DraftTotals drafts) {
    Map<String, BigDecimal> pendingByLine = followUpPending(invoice.getId(), isSales(target));
    List<LineState> states = new ArrayList<>();
    for (InvoiceLine line : lines) {
      OrderLine orderLine = orderLineOf.get(line.getId());
      BigDecimal pending = pendingByLine.getOrDefault(line.getId(), BigDecimal.ZERO)
          .max(BigDecimal.ZERO);
      states.add(new LineState(line, orderLine == null ? null : orderLine.getId(), pending,
          orderLine == null ? Set.of()
              : drafts.others.getOrDefault(orderLine.getId(), Set.of())));
    }
    return states;
  }

  /**
   * The lines of {@code invoice} under {@code pendingBasis: "classic"} (ETP-6099 follow-up), the
   * goods receipt popup's rule before it listed through the actions
   * ({@code ImportFromPurchaseInvoiceModal.jsx}, {@code enrichLines}): only the lines with an
   * invoiced quantity above 0, each at {@code linked ? 0 : invoiced}. Nothing deducts drafts or
   * deliveries; {@code orderLine} is resolved as in {@link #lineStates} (display only) and
   * {@code inDraftDocuments} is empty, the old popup showed none.
   */
  List<LineState> classicLineStates(Invoice invoice) {
    Map<String, OrderLine> byProduct = headerOrderLinesByProduct(invoice);
    List<LineState> states = new ArrayList<>();
    for (InvoiceLine line : listedLines(invoice)) {
      BigDecimal quantity = orZero(line.getInvoicedQuantity());
      if (quantity.signum() <= 0) {
        continue;
      }
      OrderLine orderLine = resolveOrderLine(line, byProduct);
      states.add(new LineState(line, orderLine == null ? null : orderLine.getId(),
          line.getGoodsShipmentLine() != null ? BigDecimal.ZERO : quantity, Set.of()));
    }
    return states;
  }

  /** Active lines other than the total-discount line, in line order. */
  private static List<InvoiceLine> listedLines(Invoice invoice) {
    List<InvoiceLine> lines = new ArrayList<>();
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      boolean discountLine = line.getProduct() != null
          && TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(line.getProduct().getId());
      if (line.isActive() && !discountLine) {
        lines.add(line);
      }
    }
    lines.sort(Comparator.comparing(InvoiceLine::getLineNo,
        Comparator.nullsLast(Comparator.naturalOrder())));
    return lines;
  }

  /** The header order's lines by product, the last one winning (the popup's map). */
  private static Map<String, OrderLine> headerOrderLinesByProduct(Invoice invoice) {
    Map<String, OrderLine> byProduct = new HashMap<>();
    Order order = invoice.getSalesOrder();
    if (order == null) {
      return byProduct;
    }
    for (OrderLine ol : order.getOrderLineList()) {
      if (ol.getProduct() != null) {
        byProduct.put(ol.getProduct().getId(), ol);
      }
    }
    return byProduct;
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  // ---------------------------------------------------------------------------
  // Loading and validating
  // ---------------------------------------------------------------------------

  private static boolean isSales(ShipmentInOut target) {
    return Boolean.TRUE.equals(target.isSalesTransaction());
  }

  private static String currencyOf(ShipmentInOut target) {
    return target.getEtgoCurrency() == null ? null : target.getEtgoCurrency().getId();
  }

  private ShipmentInOut requireTarget(String id) {
    ShipmentInOut target = loadTarget(id);
    if (target == null) {
      throw DocumentLineImport.notFound("Document", id);
    }
    requireOwnClient("Document", id, target.getClient());
    return target;
  }

  /** The invoice named by {@code invoiceId}, once it is known to be one the list would offer. */
  private Invoice requireSource(ShipmentInOut target, String invoiceId,
      Predicate<Invoice> offered) {
    Invoice source = loadInvoice(invoiceId);
    if (source == null) {
      throw DocumentLineImport.notFound(INVOICE, invoiceId);
    }
    requireOwnClient(INVOICE, invoiceId, source.getClient());
    DocumentLineImport.requireCompletedSource(source.getDocumentNo(), source.getDocumentStatus());
    DocumentLineImport.requireSameDirection(isSales(target),
        Boolean.TRUE.equals(source.isSalesTransaction()), source.getDocumentNo());
    DocumentLineImport.requireSameBusinessPartner(
        target.getBusinessPartner() == null ? null : target.getBusinessPartner().getId(),
        source.getBusinessPartner() == null ? null : source.getBusinessPartner().getId(),
        source.getDocumentNo());
    DocumentLineImport.requireSameCurrency(currencyOf(target),
        source.getCurrency() == null ? null : source.getCurrency().getId(),
        source.getDocumentNo());
    if (!offered.test(source)) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_SOURCE_NOT_STANDARD,
          "Invoice " + source.getDocumentNo() + " is not one this document imports from");
    }
    return source;
  }

  // ---------------------------------------------------------------------------
  // Seams (DAL) — overridden by the unit tests
  // ---------------------------------------------------------------------------

  protected ShipmentInOut loadTarget(String id) {
    return OBDal.getInstance().get(ShipmentInOut.class, id);
  }

  protected Invoice loadInvoice(String id) {
    return OBDal.getInstance().get(Invoice.class, id);
  }

  /**
   * The HQL behind {@link #findCandidateInvoices}: the completed invoices of one direction and
   * business partner, in the session's client and in an organization the role reads, newest
   * created first. Exposed so a test parses it against the real model.
   *
   * @param withCurrency whether the currency of the target narrows the list
   */
  static String candidateInvoicesHql(boolean withCurrency) {
    return "from Invoice i where i.documentStatus = 'CO' and i.salesTransaction = :sot "
        + "and i.businessPartner.id = :bp and i.active = true and i.client.id = :client "
        + "and i.organization.id in (:orgs)"
        + (withCurrency ? " and i.currency.id = :cur" : "")
        + " order by i.creationDate desc, i.documentNo desc, i.id desc";
  }

  /** Completed invoices of the partner, newest created first, from {@code offset}; bounded. */
  protected List<Invoice> findCandidateInvoices(String partnerId, boolean sales,
      String currencyId, int offset, int scan) {
    org.hibernate.query.Query<Invoice> query = OBDal.getInstance().getSession()
        .createQuery(candidateInvoicesHql(currencyId != null), Invoice.class)
        .setParameter("sot", sales).setParameter("bp", partnerId)
        .setParameter("client", sessionClientId())
        .setParameterList("orgs", Arrays.asList(OBContext.getOBContext().getReadableOrganizations()))
        .setFirstResult(offset).setMaxResults(scan);
    if (currencyId != null) {
      query.setParameter("cur", currencyId);
    }
    return new ArrayList<>(query.list());
  }

  /**
   * Every line of a DRAFT shipment / receipt of the direction pointing at one of the order lines,
   * oldest document first (the population of {@link InOutLineFromOrderFactory}'s draft deduction).
   */
  protected List<DraftHolding> draftHoldings(Collection<String> orderLineIds, boolean sales) {
    List<DraftHolding> holdings = new ArrayList<>();
    if (orderLineIds.isEmpty()) {
      return holdings;
    }
    List<Object[]> rows = OBDal.getInstance().getSession().createQuery(
        "select iol.salesOrderLine.id, io.id, io.documentNo, iol.movementQuantity "
            + "from MaterialMgmtShipmentInOutLine iol join iol.shipmentReceipt io "
            + "where io.documentStatus = 'DR' and io.salesTransaction = :sot "
            + "and iol.salesOrderLine.id in (:ids) order by io.creationDate",
        Object[].class).setParameter("sot", sales).setParameterList("ids", orderLineIds).list();
    for (Object[] row : rows) {
      holdings.add(new DraftHolding((String) row[0], (String) row[1], (String) row[2],
          (BigDecimal) row[3]));
    }
    return holdings;
  }

  /**
   * What each line of {@code invoiceId} still has to move, as the invoice's «Create shipment» /
   * «Create receipt» follow-up computes it ({@link InvoicePendingResolver#loadSources}): per invoice
   * line, {@code QtyInvoiced} minus what the movement lines linked to it (column or match table,
   * drafts included, voided and returns excluded) moved, capped by its order line's
   * {@code QtyOrdered - QtyDelivered - draft movement lines on it}, shared out in line order. Only
   * the lines with something pending are returned. The eligibility verdict of the follow-up is not
   * used here: the listing decides which invoices it offers by its own policy.
   *
   * @return invoice line id to its pending; absent when nothing is pending
   */
  protected Map<String, BigDecimal> followUpPending(String invoiceId, boolean sales) {
    InvoicePendingResolver resolver = new InvoicePendingResolver(
        sales ? InOutTargetBuilder.Direction.SALES : InOutTargetBuilder.Direction.PURCHASE,
        docTypeId -> true);
    Map<String, BigDecimal> pending = new HashMap<>();
    PendingResolver.Source source = resolver.loadSources(List.of(invoiceId)).get(invoiceId);
    if (source != null) {
      for (PendingResolver.SourceLine line : source.getLines()) {
        pending.put(line.getSourceLineId(), line.getPendingQty());
      }
    }
    return pending;
  }
    return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString());
  }
}
