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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Imports lines of a completed invoice into a DRAFT rectificative invoice (ETP-5876), and lists what
 * can be imported: {@code availableOriginInvoices}, {@code availableOriginInvoiceLines},
 * {@code importOriginInvoiceLines}. It stands for the window's "Import from source invoice" modal
 * ({@code ImportFromSourceInvoiceModal}), and does what the modal does, not what a rule of its own
 * would:
 * <ul>
 *   <li><b>Which invoices.</b> Completed, of the same business partner, the same side
 *       of the trade, in the same currency on sales only (the sales modal filters by it, the
 *       purchase one does not), never the target itself, and not a rectificative (the modal lists the invoices
 *       of subtype FAC only: a rectificative is never rectified by another).</li>
 *   <li><b>Which lines.</b> The lines of the invoice as the window's grid shows them: active, with a
 *       product and a quantity, without the total-discount line. A line already imported into THIS
 *       rectificative (the {@code EM_ETGO_Source_Invoiceline_ID} of one of its lines) is not offered
 *       again, even when only part of its quantity came in: the modal tracks presence, not quantity.</li>
 *   <li><b>How much.</b> At most the quantity of the source line, partial quantities allowed. The
 *       modal does NOT subtract what other draft rectificatives, or credits made through a return,
 *       already hold of that line; that is kept, on purpose, so the import credits what the screen
 *       would.</li>
 *   <li><b>The lines.</b> Created NEGATIVE, with the product, unit of measure, tax, unit price, list
 *       price, gross prices and discount of the source line, the source line in
 *       {@code EM_ETGO_Source_Invoiceline_ID}, and, when the header says so (the sales modal does,
 *       the purchase one does not; see {@link RectificativeImportPolicy#keepsOrderLink}), its order
 *       line, so the order's invoiced quantity moves when the rectificative completes.</li>
 *   <li><b>The link.</b> The origin invoice is linked in {@code C_Invoice_Reverse} (the
 *       "Rectificaciones" tab), one row per distinct origin, never duplicated: what the modal's
 *       {@code originInvoices} PATCH is meant to do. It is written here, after the target is locked,
 *       on both sides and whether or not the source has an order. The return import writes none.</li>
 * </ul>
 * The header's total discount is not carried, as in the window. The invoice is never completed.
 *
 * <p>The lines are written through the DAL, not through the lines handler: the quantities, prices
 * and amounts are the ones the modal would post, and nothing re-derives a price from a discount.
 * Everything that can refuse runs before the first write, so a refusal leaves no trace.
 *
 * <p>It is a plain CDI bean (the module's {@code beans.xml} discovers every class) injected by the
 * invoice header customizations that serve the action; it is bound to no entity, so it carries
 * neither {@code @Named} nor {@code @NeoExtension}.
 */
public class ImportOriginInvoiceLinesHandler extends AbstractInvoiceLineImportHandler {

  static final String ACTION_AVAILABLE_INVOICES = "availableOriginInvoices";
  static final String ACTION_AVAILABLE_LINES = "availableOriginInvoiceLines";
  static final String ACTION_IMPORT = "importOriginInvoiceLines";

  /** The three origin invoice actions of a rectificative invoice. */
  static final Set<String> ACTIONS =
      Set.of(ACTION_AVAILABLE_INVOICES, ACTION_AVAILABLE_LINES, ACTION_IMPORT);

  private static final String PARAM_INVOICE_ID = "invoiceId";
  private static final String KEY_ORIGIN_INVOICES = "originInvoices";
  private static final int MAX_CANDIDATES = 200;
  private static final int MAX_INVOICES = 50;
  private static final int DEFAULT_PRECISION = 2;
  private static final long LINE_STEP = 10L;

  /**
   * The three contracts of the invoice this delegate is injected in, in flow order. Declared for a
   * rectificative; {@link RectificativeImportGate} refuses them on any other invoice.
   *
   * @param sales {@code true} for a sales invoice, {@code false} for a purchase invoice
   */
  static Map<String, NeoActionContract> contracts(boolean sales) {
    String doc = sales ? "sales" : "purchase";
    String flow = " Flow, on a DRAFT rectificative " + doc + " invoice (the invoice's document type "
        + "is a rectificative one; any other invoice answers 422 importRequiresRectificative): "
        + ACTION_AVAILABLE_INVOICES + ", then " + ACTION_AVAILABLE_LINES + ", then "
        + ACTION_IMPORT + ", and complete it with documentAction (CO).";
    String on = DocumentLineImport.ID_OF_THE_DRAFT + "rectificative " + doc
        + " invoice the lines would be imported into.";
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(ACTION_AVAILABLE_INVOICES, NeoActionContract.read(ACTION_AVAILABLE_INVOICES,
        "Lists the completed " + doc + " invoices of this invoice's business partner"
            + (sales ? " and currency " : " ")
            + "that it can rectify: {data:[{id, documentNo, date, businessPartner, "
            + "businessPartner$_identifier, currency, pendingLines}], hasMore, limit, scanLimit}. "
            + "Rectificative invoices are never listed (a rectificative is not rectified), nor is "
            + "an invoice whose lines are all already imported here. pendingLines counts the lines "
            + "still offered. hasMore true means the list may be incomplete: it looks at the "
            + "scanLimit most recent candidates and returns at most limit documents, so an "
            + "invoice it does not list may still be importable when you know its id. Step 1."
            + flow)
        .withIdDescription(on).availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(ACTION_AVAILABLE_LINES, NeoActionContract.read(ACTION_AVAILABLE_LINES,
        "Lists the lines of an invoice that can be imported here: {data:[{id, lineNo, product, "
            + "product$_identifier, uOM, pendingQuantity}], hasMore:false}, where pendingQuantity "
            + "is the quantity of the source line (a positive number) and the id is the "
            + "sourceLineId " + ACTION_IMPORT + " takes. A line already imported into this "
            + "invoice is not listed again, even when only part of it came in. Step 2." + flow,
        NeoActionContract.Param.required(PARAM_INVOICE_ID, NeoActionContract.TYPE_STRING,
            "Id of an invoice returned by " + ACTION_AVAILABLE_INVOICES + "."))
        .withIdDescription(on).availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
    contracts.put(ACTION_IMPORT, NeoActionContract.write(ACTION_IMPORT,
        "Adds lines of a completed " + doc + " invoice (the one this rectificative corrects) to this "
            + "DRAFT rectificative, in ONE transaction, WITHOUT completing it. The lines come in "
            + "NEGATIVE (a credit), with the product, unit of measure, tax, price and discount of "
            + "the source line"
            + (sales ? " and its order line (so the order's invoiced quantity moves when the "
                + "rectificative is completed)" : "")
            + ", and the source invoice is linked in the Rectificaciones tab (once per invoice). "
            + "Partial quantities are accepted: without lines it imports every offered line in "
            + "full; with lines, exactly those, quantity a positive number (the sign is applied) "
            + "not above the quantity of the source line, and the same line listed twice counts "
            + "both. What other draft rectificatives or returns already credited of that line is "
            + "NOT deducted: the window does not either. More than the source line answers 422 "
            + "importQuantityExceedsPending and creates NOTHING; so does a line already imported "
            + "here. Refused (422) when the invoice is not a draft rectificative, the source is "
            + "not a completed standard invoice, or business partner"
            + (sales ? ", currency" : "") + " or direction differ"
            + (sales ? "" : " (the purchase window does not compare currency)") + ". The total discount of the header is not carried. Returns {data:"
            + "{importedCount, lines:[{id, sourceLineId, product, productName, quantity}], "
            + "headerLinked, salesOrder?, originInvoices:[{id, documentNo}]}} (quantity is "
            + "negative; headerLinked and salesOrder refer to the order link of a sales import). "
            + "Step 3."
            + flow,
        NeoActionContract.Param.required(PARAM_INVOICE_ID, NeoActionContract.TYPE_STRING,
            "Id of the invoice to import from, returned by " + ACTION_AVAILABLE_INVOICES + "."),
        NeoActionContract.Param.array(DocumentLineImport.KEY_LINES,
            NeoActionContract.TYPE_OBJECT, false,
            "Each {sourceLineId, quantity}: sourceLineId is the id of a line from "
                + ACTION_AVAILABLE_LINES + ", quantity a positive number not above its "
                + "pendingQuantity. Omit to import every offered line in full."))
        .withIdDescription(DocumentLineImport.ID_OF_THE_DRAFT + "rectificative " + doc
            + " invoice the lines are added to.")
        .availableWhen(DocumentLineImport.TARGET_IS_DRAFT));
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

  @Override
  protected NeoResponse execute(NeoContext context, String action) throws Exception {
    RectificativeImportPolicy policy = RectificativeImportPolicy.from(context);
    JSONObject body = context.getRequestBody();
    if (ACTION_AVAILABLE_INVOICES.equals(action)) {
      return availableInvoices(requireExistingInvoice(context.getRecordId()), policy);
    }
    if (ACTION_AVAILABLE_LINES.equals(action)) {
      Invoice target = requireExistingInvoice(context.getRecordId());
      Invoice source = requireSource(target, requireParam(body, PARAM_INVOICE_ID), policy);
      return availableLines(target, source);
    }
    return importLines(requireDraftInvoice(context.getRecordId()), body, policy);
  }

  // ---------------------------------------------------------------------------
  // import
  // ---------------------------------------------------------------------------

  private NeoResponse importLines(Invoice invoice, JSONObject body,
      RectificativeImportPolicy policy) throws JSONException, SQLException {
    Map<String, BigDecimal> requested = DocumentLineImport.parseRequestedLines(body);
    // The quantity is a positive number for the caller; the line that is written is negative.
    requested.replaceAll((id, quantity) -> quantity.abs());
    Invoice source = requireSource(invoice, requireParam(body, PARAM_INVOICE_ID), policy);
    // Serialise per target BEFORE the lines it holds are read: two calls (or a retry) importing
    // into the same draft would otherwise both see a line as not imported yet, and both link it.
    lockTarget(invoice.getId());

    Map<String, InvoiceLine> offered = new LinkedHashMap<>();
    Map<String, BigDecimal> pending = new LinkedHashMap<>();
    for (InvoiceLine line : offeredLines(source, importedSourceLineIds(invoice))) {
      offered.put(line.getId(), line);
      pending.put(line.getId(), line.getInvoicedQuantity().abs());
    }
    Map<String, String> documentLines = new LinkedHashMap<>();
    for (InvoiceLine line : activeLines(source)) {
      documentLines.put(line.getId(), label(line));
    }
    Map<String, BigDecimal> planned = DocumentLineImport.plan(requested, documentLines, pending);

    List<DocumentLineImport.ImportedLine> created = copyLines(invoice, offered, planned,
        policy.keepsOrderLink());
    linkOrigin(invoice, source);
    String headerOrderId = policy.keepsOrderLink() ? syncHeaderOrder(invoice)
        : orderIdOf(invoice);
    requireQuotaAllowed(InvoiceLine.ENTITY_NAME, created);
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put(KEY_ORIGIN_INVOICES, new JSONArray().put(new JSONObject().put("id", source.getId())
        .put("documentNo", source.getDocumentNo())));
    return DocumentLineImport.ok(created, headerOrderId, extra);
  }

  /**
   * One negative line per planned source line, in the order of the source's lines. The prices are
   * the source line's, exactly: a price is never re-derived from a discount here.
   */
  private List<DocumentLineImport.ImportedLine> copyLines(Invoice invoice,
      Map<String, InvoiceLine> offered, Map<String, BigDecimal> planned, boolean keepsOrderLink) {
    int precision = invoice.getCurrency() == null ? DEFAULT_PRECISION
        : invoice.getCurrency().getStandardPrecision().intValue();
    long lineNo = lastLineNo(invoice);
    List<DocumentLineImport.ImportedLine> created = new ArrayList<>();
    Set<String> newIds = new LinkedHashSet<>();
    for (Map.Entry<String, BigDecimal> entry : planned.entrySet()) {
      InvoiceLine source = offered.get(entry.getKey());
      lineNo += LINE_STEP;
      InvoiceLine line = newLine();
      copyInto(line, source, invoice, lineNo, entry.getValue().negate(), precision,
          keepsOrderLink);
      save(line);
      invoice.getInvoiceLineList().add(line);
      newIds.add(line.getId());
      created.add(new DocumentLineImport.ImportedLine(line.getId(), source.getId(),
          source.getProduct().getId(), source.getProduct().getName(), line.getInvoicedQuantity(),
          line.getSalesOrderLine() == null || line.getSalesOrderLine().getSalesOrder() == null
              ? null : line.getSalesOrderLine().getSalesOrder().getId()));
    }
    flush();
    getSupport().alignLineListPrices(invoice, newIds);
    getSupport().ensureLineGrossAmounts(invoice, newIds);
    return created;
  }

  private static void copyInto(InvoiceLine line, InvoiceLine source, Invoice invoice, long lineNo,
      BigDecimal quantity, int precision, boolean keepsOrderLink) {
    BigDecimal unitPrice = orZero(source.getUnitPrice());
    BigDecimal netAmount = quantity.multiply(unitPrice).setScale(precision, RoundingMode.HALF_UP);
    line.setClient(invoice.getClient());
    line.setOrganization(invoice.getOrganization());
    line.setInvoice(invoice);
    line.setLineNo(lineNo);
    line.setProduct(source.getProduct());
    line.setUOM(source.getUOM());
    line.setTax(source.getTax());
    line.setInvoicedQuantity(quantity);
    line.setUnitPrice(unitPrice);
    line.setListPrice(isNonZero(source.getListPrice()) ? source.getListPrice() : unitPrice);
    // The modals' body carries neither the standard price nor the price limit, so the screen stores
    // 0 in both whatever the source line holds: copying them would differ from the screen.
    line.setStandardPrice(BigDecimal.ZERO);
    line.setPriceLimit(BigDecimal.ZERO);
    line.setLineNetAmount(netAmount);
    line.setTaxableAmount(netAmount);
    if (isNonZero(source.getGrossUnitPrice())) {
      line.setGrossUnitPrice(source.getGrossUnitPrice());
      line.setGrossListPrice(source.getGrossListPrice());
      line.setBaseGrossUnitPrice(source.getBaseGrossUnitPrice());
    }
    if (isNonZero(source.getEtgoDiscount())) {
      line.setEtgoDiscount(source.getEtgoDiscount());
    }
    if (keepsOrderLink) {
      line.setSalesOrderLine(source.getSalesOrderLine());
    }
    line.setETGOSourceInvoiceLine(source);
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static boolean isNonZero(BigDecimal value) {
    return value != null && value.signum() != 0;
  }

  private long lastLineNo(Invoice invoice) {
    long last = 0L;
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      if (line.getLineNo() != null && line.getLineNo() > last) {
        last = line.getLineNo();
      }
    }
    return last;
  }

  /** The origin is linked once per invoice, whatever lines came from it. */
  private void linkOrigin(Invoice invoice, Invoice origin) {
    if (linkIfMissing(invoice, origin)) {
      flush();
    }
  }

  /**
   * The header points at the order of the imported lines under the rule the window's line save
   * applies: only while every line of the invoice comes from that one order.
   *
   * @return the order the header points at now, or {@code null}
   */
  private String syncHeaderOrder(Invoice invoice) {
    Map<String, Order> orders = new LinkedHashMap<>();
    for (InvoiceLine line : activeLines(invoice)) {
      if (line.getSalesOrderLine() != null && line.getSalesOrderLine().getSalesOrder() != null) {
        Order order = line.getSalesOrderLine().getSalesOrder();
        orders.put(order.getId(), order);
      }
    }
    DocumentLineImport.HeaderLink link = DocumentLineImport.resolveHeaderLink(orderIdOf(invoice),
        orders.keySet());
    switch (link.action()) {
      case LINK:
        invoice.setSalesOrder(orders.get(link.orderId()));
        save(invoice);
        return link.orderId();
      case CLEAR:
        invoice.setSalesOrder(null);
        save(invoice);
        return null;
      default:
        return orderIdOf(invoice);
    }
  }

  private static String orderIdOf(Invoice invoice) {
    return invoice.getSalesOrder() == null ? null : invoice.getSalesOrder().getId();
  }

  // ---------------------------------------------------------------------------
  // available invoices / lines
  // ---------------------------------------------------------------------------

  private NeoResponse availableInvoices(Invoice target, RectificativeImportPolicy policy)
      throws JSONException {
    String partnerId = target.getBusinessPartner() == null ? null
        : target.getBusinessPartner().getId();
    String currencyId = target.getCurrency() == null ? null : target.getCurrency().getId();
    Set<String> imported = importedSourceLineIds(target);
    // Only the sales modal filters the candidates by currency; the purchase one does not.
    String compared = policy.comparesCurrency() ? currencyId : null;
    return discover(findCandidateInvoices(partnerId, isSales(target), compared, target.getId()),
        invoice -> {
          int pendingLines = policy.isRectificative(invoice) ? 0
              : offeredLines(invoice, imported).size();
          if (pendingLines == 0) {
            return null;
          }
          return documentRow(invoice.getId(), invoice.getDocumentNo(), day(invoice.getInvoiceDate()),
              partnerId, invoice.getBusinessPartner().getName(), currencyId(invoice), pendingLines);
        });
  }

  private NeoResponse availableLines(Invoice target, Invoice source) throws JSONException {
    JSONArray rows = new JSONArray();
    for (InvoiceLine line : offeredLines(source, importedSourceLineIds(target))) {
      rows.put(DocumentLineImport.lineRow(line.getId(), line.getLineNo(),
          line.getProduct().getId(), line.getProduct().getName(),
          line.getUOM() == null ? null : line.getUOM().getId(),
          line.getInvoicedQuantity().abs()));
    }
    return completePage(rows);
  }

  private static String currencyId(Invoice invoice) {
    return invoice.getCurrency() == null ? null : invoice.getCurrency().getId();
  }

  /**
   * The lines of the source the window offers: active, with a product and a quantity, never the
   * total-discount line (the lines grid hides it), and not already imported into the target.
   */
  private List<InvoiceLine> offeredLines(Invoice source, Set<String> alreadyImported) {
    List<InvoiceLine> offered = new ArrayList<>();
    for (InvoiceLine line : activeLines(source)) {
      boolean hasQuantity = line.getInvoicedQuantity() != null
          && line.getInvoicedQuantity().signum() != 0;
      boolean discountLine = line.getProduct() != null
          && TotalDiscountService.DISCOUNT_PRODUCT_ID.equals(line.getProduct().getId());
      if (line.getProduct() != null && hasQuantity && !discountLine
          && !alreadyImported.contains(line.getId())) {
        offered.add(line);
      }
    }
    return offered;
  }

  private static List<InvoiceLine> activeLines(Invoice invoice) {
    List<InvoiceLine> lines = new ArrayList<>();
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      if (line.isActive()) {
        lines.add(line);
      }
    }
    lines.sort(Comparator.comparing(InvoiceLine::getLineNo,
        Comparator.nullsLast(Comparator.naturalOrder())));
    return lines;
  }

  /** The source lines the target already holds a copy of (any quantity of them). */
  private Set<String> importedSourceLineIds(Invoice target) {
    Set<String> ids = new LinkedHashSet<>();
    for (InvoiceLine line : activeLines(target)) {
      if (line.getETGOSourceInvoiceLine() != null) {
        ids.add(line.getETGOSourceInvoiceLine().getId());
      }
    }
    return ids;
  }

  private static String label(InvoiceLine line) {
    return line.getProduct() != null && line.getProduct().getName() != null
        ? line.getProduct().getName() : line.getId();
  }

  // ---------------------------------------------------------------------------
  // Loading and validating
  // ---------------------------------------------------------------------------

  private static boolean isSales(Invoice invoice) {
    return Boolean.TRUE.equals(invoice.isSalesTransaction());
  }

  /**
   * Runs in admin mode ({@link AbstractLineImportHandler#handle} wraps every action in it), so
   * classifying the source reads its document type even when the role cannot read that org.
   */
  private Invoice requireSource(Invoice target, String sourceId, RectificativeImportPolicy policy) {
    Invoice source = loadInvoice(sourceId);
    if (source == null) {
      throw DocumentLineImport.notFound("Invoice", sourceId);
    }
    requireOwnClient("Invoice", sourceId, source.getClient());
    if (source.getId().equals(target.getId())) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_SOURCE_IS_TARGET,
          "An invoice cannot be imported into itself");
    }
    DocumentLineImport.requireCompletedSource(source.getDocumentNo(), source.getDocumentStatus());
    DocumentLineImport.requireSameDirection(isSales(target), isSales(source),
        source.getDocumentNo());
    DocumentLineImport.requireSameBusinessPartner(
        target.getBusinessPartner() == null ? null : target.getBusinessPartner().getId(),
        source.getBusinessPartner() == null ? null : source.getBusinessPartner().getId(),
        source.getDocumentNo());
    if (policy.comparesCurrency()) {
      DocumentLineImport.requireSameCurrency(currencyId(target), currencyId(source),
          source.getDocumentNo());
    }
    if (policy.isRectificative(source)) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_SOURCE_NOT_STANDARD,
          "Invoice " + source.getDocumentNo() + " is itself a rectificative: only a standard "
              + "invoice can be rectified");
    }
    return source;
  }

  // ---------------------------------------------------------------------------
  // Seams (DAL) — overridden by the unit tests
  // ---------------------------------------------------------------------------

  /**
   * The HQL behind {@link #findCandidateInvoices}: the completed invoices of one side and business
   * partner, in the session's client and in an organization the role reads. Exposed so a test
   * parses it against the real model.
   *
   * @param withCurrency whether the currency of the target narrows the list
   */
  static String candidateInvoicesHql(boolean withCurrency) {
    return "from Invoice i where i.documentStatus = 'CO' and i.salesTransaction = :sot "
        + "and i.businessPartner.id = :bp and i.active = true and i.client.id = :client "
        + "and i.organization.id in (:orgs) and i.id <> :target"
        + (withCurrency ? " and i.currency.id = :cur" : "")
        + " order by i.invoiceDate desc, i.documentNo desc";
  }

  /** Completed invoices of the partner, newest first; a bounded scan (see {@link #scanLimit()}). */
  protected List<Invoice> findCandidateInvoices(String partnerId, boolean sales,
      String currencyId, String targetId) {
    org.hibernate.query.Query<Invoice> query = OBDal.getInstance().getSession()
        .createQuery(candidateInvoicesHql(currencyId != null), Invoice.class)
        .setParameter("sot", sales).setParameter("bp", partnerId)
        .setParameter("client", sessionClientId())
        .setParameterList("orgs", Arrays.asList(OBContext.getOBContext().getReadableOrganizations()))
        .setParameter("target", targetId)
        .setMaxResults(scanLimit());
    if (currencyId != null) {
      query.setParameter("cur", currencyId);
    }
    return new ArrayList<>(query.list());
  }

  /** Seam: the row lock of the target invoice. */
  protected void lockTarget(String invoiceId) throws SQLException {
    DocumentLineImport.lockInvoice(invoiceId);
  }

  /** The {@code C_Invoice_Reverse} row, created unless the pair is already linked. */
  protected boolean linkIfMissing(Invoice invoice, Invoice origin) {
    return OriginInvoiceLinks.linkIfMissing(invoice, origin);
  }

  protected InvoiceLine newLine() {
    return OBProvider.getInstance().get(InvoiceLine.class);
  }

  protected void save(Object entity) {
    OBDal.getInstance().save(entity);
  }

  protected void flush() {
    OBDal.getInstance().flush();
  }
}
