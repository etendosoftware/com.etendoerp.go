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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import javax.inject.Inject;

import org.codehaus.jettison.json.JSONArray;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.common.actionhandler.createlinesfromprocess.CreateInvoiceLinesFromProcess;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;

/**
 * What the two "import lines into a draft invoice" delegates share (ETP-5713): loading and checking
 * the target invoice, running the native line creation on it, and the post-processing of the lines
 * it added.
 *
 * <p><b>The lines are created by the native {@code CreateInvoiceLinesFromProcess}</b>, the process
 * the one-step {@code createPurchaseInvoice} / {@code createDraftInvoice} actions already use, on
 * the existing draft: it copies product, UOM, tax, unit/list/standard price (and gross prices) from
 * the order line exactly, links the invoice line to its order line and movement line, and keeps the
 * invoice header's order reference (set when every line comes from one order, cleared when a second
 * order joins). It is deliberately NOT the CRUD path: a line written through the lines handler gets
 * its amounts derived again by {@code InvoiceLineAmountSupport}, and a line whose unit price equals
 * its list price and carries a discount would be discounted twice (ETP-5710). The native process
 * never recomputes a price from a discount, so the unit price is the order's, once.
 *
 * <p>What the native process does not copy, the post-processing adds, scoped to the NEW lines so
 * the lines already on the invoice are not touched (a deliberate zero line or a negative one keeps
 * the gross it has): the per-line discount percentage ({@code EM_Etgo_Discount}, display only) and
 * the gross amount of a new line that has none. The invoice is never completed, and the movement
 * link is whatever the native process (or the order import's own binding) set: there is no
 * back-fill pass.
 */
abstract class AbstractInvoiceLineImportHandler extends AbstractLineImportHandler {

  @Inject
  InvoiceFromOrderSupport invoiceFromOrderSupport;

  InvoiceFromOrderSupport getSupport() {
    return invoiceFromOrderSupport != null ? invoiceFromOrderSupport
        : new InvoiceFromOrderSupport();
  }

  /** The invoice of the session's client; a missing one and a foreign one answer alike. */
  protected Invoice requireExistingInvoice(String id) {
    Invoice invoice = loadInvoice(id);
    if (invoice == null) {
      throw DocumentLineImport.notFound("Invoice", id);
    }
    requireOwnClient("Invoice", id, invoice.getClient());
    return invoice;
  }

  /** The target: it exists, it is a draft and it has a price list to price its lines with. */
  protected Invoice requireDraftInvoice(String id) {
    Invoice invoice = requireExistingInvoice(id);
    DocumentLineImport.requireDraftTarget(invoice.getDocumentNo(), invoice.getDocumentStatus());
    if (invoice.getPriceList() == null) {
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_PRICE_LIST_REQUIRED,
          "This invoice has no price list: set one before importing lines");
    }
    return invoice;
  }

  /**
   * Creates the lines with the native process, post-processes the new ones and checks they add up
   * to what was planned. A mismatch is rolled back whole: the caller asked for exact quantities.
   *
   * @param selected     the native selection, one entry per source line
   * @param from         {@code OrderLine} or {@code ShipmentInOutLine}
   * @param planned      {@code sourceLineId -> quantity}, signed like the line
   * @param sourceOf     the source line of an invoice line (its order line or its movement line)
   * @return what was created, in creation order
   */
  protected List<DocumentLineImport.ImportedLine> createLines(Invoice invoice, JSONArray selected,
      Class<? extends BaseOBObject> from, Map<String, BigDecimal> planned,
      Function<InvoiceLine, BaseOBObject> sourceOf) {
    return createLines(invoice, List.of(selected), from, planned, sourceOf, null);
  }

  /**
   * Same, in several native calls, run in order: a later call sees the lines of the earlier ones
   * as already on the invoice, which the order import relies on to add the undelivered remainder
   * only after the movement lines were taken (see {@link ImportOrderLinesIntoInvoiceHandler}).
   */
  protected List<DocumentLineImport.ImportedLine> createLines(Invoice invoice,
      List<JSONArray> batches, Class<? extends BaseOBObject> from,
      Map<String, BigDecimal> planned, Function<InvoiceLine, BaseOBObject> sourceOf) {
    return createLines(invoice, batches, from, planned, sourceOf, null);
  }

  /**
   * Same, calling {@code afterCall(index, linesOfThatCall)} after each native call, once its
   * lines are flushed — the hook the order import uses to undo a marker link.
   */
  protected List<DocumentLineImport.ImportedLine> createLines(Invoice invoice,
      List<JSONArray> batches, Class<? extends BaseOBObject> from,
      Map<String, BigDecimal> planned, Function<InvoiceLine, BaseOBObject> sourceOf,
      BiConsumer<Integer, List<InvoiceLine>> afterCall) {
    Set<String> before = lineIdsOf(invoice);
    Set<String> seen = new LinkedHashSet<>(before);
    for (int call = 0; call < batches.size(); call++) {
      createNativeLines(batches.get(call), invoice, from);
      OBDal.getInstance().flush();
      List<InvoiceLine> ofCall = new ArrayList<>();
      for (InvoiceLine line : invoice.getInvoiceLineList()) {
        if (seen.add(line.getId())) {
          ofCall.add(line);
        }
      }
      if (afterCall != null) {
        afterCall.accept(call, ofCall);
      }
    }
    List<InvoiceLine> added = new ArrayList<>();
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      if (!before.contains(line.getId())) {
        added.add(line);
      }
    }
    Set<String> addedIds = new LinkedHashSet<>();
    for (InvoiceLine line : added) {
      addedIds.add(line.getId());
    }
    postProcess(invoice, addedIds);
    return verified(added, planned, sourceOf);
  }

  /** The new lines add up, per source line, to exactly what was planned — or nothing is kept. */
  private List<DocumentLineImport.ImportedLine> verified(List<InvoiceLine> added,
      Map<String, BigDecimal> planned, Function<InvoiceLine, BaseOBObject> sourceOf) {
    Map<String, BigDecimal> created = new HashMap<>();
    List<DocumentLineImport.ImportedLine> lines = new ArrayList<>();
    for (InvoiceLine line : added) {
      BaseOBObject source = sourceOf.apply(line);
      String sourceId = source == null ? null : (String) source.getId();
      BigDecimal quantity = line.getInvoicedQuantity() == null ? BigDecimal.ZERO
          : line.getInvoicedQuantity();
      created.merge(String.valueOf(sourceId), quantity, BigDecimal::add);
      lines.add(new DocumentLineImport.ImportedLine(line.getId(), sourceId,
          line.getProduct() == null ? null : line.getProduct().getId(),
          line.getProduct() == null ? null : line.getProduct().getName(), quantity,
          orderIdOf(line)));
    }
    boolean honoured = created.size() == planned.size();
    for (Map.Entry<String, BigDecimal> entry : planned.entrySet()) {
      BigDecimal got = created.get(entry.getKey());
      honoured &= got != null && got.compareTo(entry.getValue()) == 0;
    }
    if (!honoured) {
      rollback();
      throw DocumentLineImport.refusal(DocumentLineImport.ERROR_QUANTITY_NOT_HONOURED,
          "The invoice lines created do not match the quantities requested; nothing was imported");
    }
    return lines;
  }

  private static String orderIdOf(InvoiceLine line) {
    return line.getSalesOrderLine() == null || line.getSalesOrderLine().getSalesOrder() == null
        ? null : line.getSalesOrderLine().getSalesOrder().getId();
  }

  /** Only the new lines are touched: the draft may hold others, edited or deliberately zero. */
  private void postProcess(Invoice invoice, Set<String> addedIds) {
    getSupport().copyLineDiscountsFromOrder(invoice, addedIds);
    getSupport().alignLineListPrices(invoice, addedIds);
    getSupport().ensureLineGrossAmounts(invoice, addedIds);
  }

  // ---------------------------------------------------------------------------
  // Seams (DAL / native process) — overridden by the unit tests
  // ---------------------------------------------------------------------------

  protected Invoice loadInvoice(String id) {
    return OBDal.getInstance().get(Invoice.class, id);
  }

  protected Set<String> lineIdsOf(Invoice invoice) {
    Set<String> ids = new LinkedHashSet<>();
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      ids.add(line.getId());
    }
    return ids;
  }

  protected void createNativeLines(JSONArray selected, Invoice invoice,
      Class<? extends BaseOBObject> from) {
    CreateInvoiceLinesFromProcess process =
        WeldUtils.getInstanceFromStaticBeanManager(CreateInvoiceLinesFromProcess.class);
    process.createInvoiceLinesFromDocumentLines(selected, invoice, from);
  }
}
