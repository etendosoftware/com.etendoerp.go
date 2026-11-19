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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * The server-side cap of a goods shipment line against the sales invoice line it ships (ETP-6002
 * CP-30). The window's import popup limits the quantity on the client only, so a CRUD write could
 * ship more than was invoiced. Called from the hooks of {@link GoodsShipmentLineHandler}, so it
 * holds on REST single, {@code /batch} and MCP.
 *
 * <p>The pending is never computed here: it is the one of the invoice's own follow-up
 * ({@link InvoicePendingResolver}, drafts included), read after locking the invoice and flushing
 * the session.
 *
 * <p><b>The guarantee has two steps.</b> {@link #refusalFor} refuses what is already over the
 * pending before anything is written, but it cannot serialize two single creates: outside a batch
 * the insert commits inside the write, which releases the lock before the new line is linked, and
 * an unlinked line does not count as moved. So {@link #linkUnderCap} takes the invoice lock again
 * after the insert, writes the link and re-checks under that same lock. Of two racing creates that
 * together exceed what is pending, exactly one keeps its line; the other loses its line and is
 * answered the same 422. Which one stays depends on the binding cap, not on who locks first: under
 * the invoice line's own cap the first to lock stays, while under the order line's cap the first
 * to lock already sees both drafts and removes itself. For a moment the removed line exists,
 * unlinked. Inside a batch the caller owns the transaction: the refusal rolls the whole batch back.
 *
 * <ul>
 *   <li><b>Create with {@code invoiceLineId}:</b> above 0 and up to the pending. Otherwise, or with
 *       nothing pending, a 422 {@link DocumentLineImport#ERROR_EXCEEDS_PENDING}. A create that
 *       sends no quantity takes the pending, as the popup proposes it; on MCP a value the callout
 *       cascade filled in does not count as sent. An invoice that is not completed carries no line,
 *       so it has nothing pending and is refused.</li>
 *   <li><b>Update of {@code movementQuantity}</b> on a line linked to invoice lines (the column or
 *       {@code M_MatchSI}): up to the pending plus what the line already holds, for EVERY linked
 *       invoice line. Lowering it, down to 0, or leaving it unchanged is allowed; a negative
 *       quantity is refused. A linked invoice that is no longer completed commits no quantity and
 *       does not cap a positive one.</li>
 *   <li><b>Left as they were:</b> a line with no invoice link (the supported way of shipping extra
 *       units), a non-numeric quantity, and an {@code invoiceLineId} that is not the caller's
 *       own.</li>
 * </ul>
 */
final class ShipmentLineInvoiceCap {

  private static final Logger log = LogManager.getLogger(ShipmentLineInvoiceCap.class);

  private static final String FIELD_MOVEMENT_QUANTITY =
      ReturnLineQuantityPolicy.FIELD_MOVEMENT_QUANTITY;

  /** The active invoice lines linked to one movement line: {@code line id, invoice id}. */
  private static final String LINKED_INVOICE_LINES_SQL =
      "SELECT il.c_invoiceline_id, il.c_invoice_id FROM c_invoiceline il "
          + "WHERE il.m_inoutline_id = ? AND il.isactive = 'Y' "
          + "UNION "
          + "SELECT il.c_invoiceline_id, il.c_invoice_id FROM "
          + InOutInvoiceLinks.MatchTable.SALES.tableName() + " mt "
          + "JOIN c_invoiceline il ON il.c_invoiceline_id = mt.c_invoiceline_id "
          + "WHERE mt.m_inoutline_id = ? AND il.isactive = 'Y'";

  private ShipmentLineInvoiceCap() {
  }

  /**
   * Applies the cap to a CRUD write of a shipment line, before it is persisted.
   *
   * @param context the pre-hook context; its body may receive the defaulted quantity
   * @return the 422 to answer, or {@code null} to proceed with the write
   */
  static NeoResponse refusalFor(NeoContext context) {
    if (!NeoEndpointType.CRUD.equals(context.getEndpointType())
        || context.getRequestBody() == null) {
      return null;
    }
    try {
      if ("POST".equalsIgnoreCase(context.getHttpMethod())) {
        checkCreate(context);
      } else if (isUpdate(context.getHttpMethod())) {
        checkUpdate(context);
      }
      return null;
    } catch (DocumentLineImport.ImportRefusal refusal) {
      return refusal.response();
    }
  }

  /**
   * Links a just-created shipment line to its invoice line and re-checks the cap under the invoice
   * lock, which is held until the transaction ends: the lock comes first, so link and re-check of
   * two racing creates cannot interleave.
   *
   * @param invoiceLineId the source invoice line, already checked to be the caller's own
   * @param newLineId the shipment line just created
   * @return {@code null} when the line stands; the 422 when it exceeded the invoice line and was
   *     removed; a 500 when the link, the re-check or the removal failed
   */
  static NeoResponse linkUnderCap(String invoiceLineId, String newLineId) {
    InvoiceLine invoiceLine = TenantOwnership.loadOwned(InvoiceLine.class, invoiceLineId);
    if (invoiceLine == null || invoiceLine.getInvoice() == null) {
      InvoiceLineLinker.linkInvoiceLineToInOutLine(invoiceLineId, newLineId,
          InOutInvoiceLinks.MatchTable.SALES);
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      String invoiceId = invoiceLine.getInvoice().getId();
      InvoicePendingResolver resolver = salesResolver();
      resolver.lockSource(invoiceId);
      InvoiceLineLinker.linkInvoiceLineToInOutLine(invoiceLineId, newLineId,
          InOutInvoiceLinks.MatchTable.SALES);
      BigDecimal remaining = resolver.remainingByLine(invoiceId).get(invoiceLineId);
      return remaining == null || remaining.signum() >= 0 ? null
          : refuseExcess(invoiceLine, newLineId, remaining);
    } catch (RuntimeException e) {
      log.error("Shipment line {} could not be verified against invoice line {}: it may exceed "
          + "what was invoiced", newLineId, invoiceLineId, e);
      return NeoErrorResponses.afterCommitFailure(e, "POST", "Shipment line invoice cap", null);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The 422 of a line the re-check found in excess. Outside a batch the line was already committed,
   * so it is removed here and that removal is committed, whatever the channel does with an error
   * answer; inside a batch the caller rolls everything back.
   */
  private static NeoResponse refuseExcess(InvoiceLine invoiceLine, String newLineId,
      BigDecimal remaining) {
    ShipmentInOutLine line = OBDal.getInstance().get(ShipmentInOutLine.class, newLineId);
    BigDecimal moved = line == null || line.getMovementQuantity() == null ? BigDecimal.ZERO
        : line.getMovementQuantity();
    NeoResponse refusal = DocumentLineImport.exceeds(invoiceLine.getId(),
        productName(invoiceLine.getProduct()), moved, remaining.add(moved).max(BigDecimal.ZERO))
        .response();
    if (!BatchService.callerOwnsTransaction()) {
      InvoiceLineLinker.unlinkInvoiceLineFromInOutLine(invoiceLine.getId(), newLineId,
          InOutInvoiceLinks.MatchTable.SALES);
      if (line != null) {
        OBDal.getInstance().remove(line);
      }
      OBDal.getInstance().commitAndClose();
    }
    return refusal;
  }

  private static boolean isUpdate(String method) {
    return "PATCH".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method);
  }

  private static void checkCreate(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String invoiceLineId = DocumentLineImport.text(body,
        GoodsShipmentLineHandler.FIELD_INVOICE_LINE_ID);
    InvoiceLine invoiceLine = invoiceLineId == null ? null
        : TenantOwnership.loadOwned(InvoiceLine.class, invoiceLineId);
    if (invoiceLine == null || invoiceLine.getInvoice() == null) {
      return;
    }
    boolean sent = quantitySent(context, body);
    BigDecimal requested = sent
        ? ReturnableQuantity.parseQuantity(body.opt(FIELD_MOVEMENT_QUANTITY)) : null;
    if (sent && requested == null) {
      return;
    }
    OBContext.setAdminMode(true);
    try {
      String invoiceId = invoiceLine.getInvoice().getId();
      BigDecimal pending = DirectLinkPending
          .pendingByLine(lockAndLoad(Set.of(invoiceId)).get(invoiceId))
          .getOrDefault(invoiceLineId, BigDecimal.ZERO);
      if (sent) {
        requireFits(invoiceLineId, invoiceLine.getProduct(), requested, pending);
      } else {
        defaultToPending(body, invoiceLine, pending);
      }
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Whether the caller chose the quantity. On MCP the pre-hook runs after the defaults pass, so
   * the key set the caller sent is the witness; on REST the body still is.
   */
  private static boolean quantitySent(NeoContext context, JSONObject body) {
    Object provided = context.getAttribute(NeoContext.ATTR_CLIENT_PROVIDED_FIELDS);
    if (provided instanceof Collection && !((Collection<?>) provided).contains(
        FIELD_MOVEMENT_QUANTITY)) {
      return false;
    }
    return DocumentLineImport.text(body, FIELD_MOVEMENT_QUANTITY) != null;
  }

  private static void defaultToPending(JSONObject body, InvoiceLine invoiceLine,
      BigDecimal pending) {
    if (pending.signum() <= 0) {
      BigDecimal invoiced = invoiceLine.getInvoicedQuantity() == null ? BigDecimal.ZERO
          : invoiceLine.getInvoicedQuantity();
      throw DocumentLineImport.exceeds(invoiceLine.getId(), productName(invoiceLine.getProduct()),
          invoiced, BigDecimal.ZERO);
    }
    try {
      body.put(FIELD_MOVEMENT_QUANTITY, pending);
    } catch (JSONException e) {
      throw new OBException("Could not default the quantity of the shipment line", e);
    }
  }

  private static void checkUpdate(NeoContext context) {
    JSONObject body = context.getRequestBody();
    String lineId = context.getRecordId();
    if (StringUtils.isBlank(lineId) || !body.has(FIELD_MOVEMENT_QUANTITY)) {
      return;
    }
    BigDecimal requested = ReturnableQuantity.parseQuantity(body.opt(FIELD_MOVEMENT_QUANTITY));
    if (requested == null || requested.signum() == 0) {
      return;
    }
    OBContext.setAdminMode(true);
    try {
      ShipmentInOutLine line = TenantOwnership.loadOwned(ShipmentInOutLine.class, lineId);
      Map<String, String> invoiceOfLine = line == null ? Map.of() : linkedInvoiceLines(lineId);
      if (!invoiceOfLine.isEmpty()) {
        requireFitsEveryLink(line, requested, invoiceOfLine);
      }
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** The line's quantity is read once the invoices are locked, so no other write can move it. */
  private static void requireFitsEveryLink(ShipmentInOutLine line, BigDecimal requested,
      Map<String, String> invoiceOfLine) {
    Map<String, PendingResolver.Source> sources = lockAndLoad(invoiceOfLine.values());
    OBDal.getInstance().refresh(line);
    BigDecimal held = line.getMovementQuantity() == null ? BigDecimal.ZERO
        : line.getMovementQuantity();
    if (requested.signum() > 0 && requested.compareTo(held) <= 0) {
      return;
    }
    for (Map.Entry<String, String> link : invoiceOfLine.entrySet()) {
      PendingResolver.Source source = sources.get(link.getValue());
      if (requested.signum() < 0 || commitsQuantity(source)) {
        BigDecimal maximum = DirectLinkPending.pendingByLine(source)
            .getOrDefault(link.getKey(), BigDecimal.ZERO).add(held);
        if (requested.signum() < 0) {
          throw DocumentLineImport.quantityNotPositiveOnEdit(link.getKey(),
              productName(line.getProduct()), requested, maximum);
        }
        if (!DocumentLineImport.fits(requested, maximum)) {
          throw DocumentLineImport.exceedsLineMaximum(link.getKey(),
              productName(line.getProduct()), requested, maximum);
        }
      }
    }
  }

  /** A completed invoice, with or without something pending; any other verdict commits nothing. */
  private static boolean commitsQuantity(PendingResolver.Source source) {
    return source != null && (source.isAvailable()
        || source.getUnavailability() == FollowUpException.Reason.NOTHING_PENDING);
  }

  private static void requireFits(String invoiceLineId, Product product, BigDecimal requested,
      BigDecimal maximum) {
    if (requested.signum() <= 0) {
      throw DocumentLineImport.quantityNotPositive(invoiceLineId, productName(product), requested,
          maximum);
    }
    if (!DocumentLineImport.fits(requested, maximum)) {
      throw DocumentLineImport.exceeds(invoiceLineId, productName(product), requested, maximum);
    }
  }

  private static String productName(Product product) {
    return product == null ? "" : product.getName();
  }

  private static InvoicePendingResolver salesResolver() {
    return new InvoicePendingResolver(InOutTargetBuilder.Direction.SALES, docTypeId -> true);
  }

  /**
   * The follow-up's verdict on each invoice, once the invoices are locked (in id order, so two
   * writes cannot wait on each other) and what this transaction wrote is visible to its JDBC read.
   */
  private static Map<String, PendingResolver.Source> lockAndLoad(Collection<String> invoiceIds) {
    Set<String> ids = new TreeSet<>(invoiceIds);
    InvoicePendingResolver resolver = salesResolver();
    ids.forEach(resolver::lockSource);
    OBDal.getInstance().flush();
    return resolver.loadSources(ids);
  }

  /** The invoice lines linked to the movement line, id to invoice id, in line id order. */
  // The statement is built from a fixed enum literal; the id is bound.
  @SuppressWarnings("java:S2077")
  private static Map<String, String> linkedInvoiceLines(String inOutLineId) {
    Map<String, String> links = new TreeMap<>();
    try (PreparedStatement ps = OBDal.getInstance().getConnection()
        .prepareStatement(LINKED_INVOICE_LINES_SQL)) {
      ps.setString(1, inOutLineId);
      ps.setString(2, inOutLineId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          links.put(rs.getString(1), rs.getString(2));
        }
      }
    } catch (SQLException e) {
      throw new OBException("Could not read the invoice lines of shipment line " + inOutLineId, e);
    }
    return links;
  }
}
