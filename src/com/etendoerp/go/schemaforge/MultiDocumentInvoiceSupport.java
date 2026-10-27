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
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.hibernate.query.NativeQuery;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;
import org.openbravo.model.pricing.pricelist.PriceList;

/**
 * Shared plumbing for building ONE invoice from MULTIPLE source shipments/receipts
 * ({@link ShipmentInOut}, i.e. {@code M_InOut} — the same entity for goods shipments
 * and goods receipts; direction is {@code issotrx}, not the type). Both {@link
 * CreateDraftInvoiceHandler} (goods-shipment, sales) and {@link CreatePurchaseInvoiceHandler}
 * (goods-receipt, purchase) invoice N selected documents into a single header, and this class
 * is what is now shared between them.
 *
 * <p><b>Why this exists separately from {@link NeoInvoiceSupport}:</b> that class is a
 * single-concern SQL helper (pending-quantity computation). This one is generic
 * multi-document plumbing — id parsing, cross-document validation, and per-line quantity
 * resolution. {@link #resolveProductPrices} and {@link #resolveEffectivePriceListId} are the
 * two methods here with queries of their own — see each one's doc for why it lives here
 * instead of a third helper class.
 *
 * <p>Every user-facing message is a caller-supplied parameter, not a literal here, so the
 * sales handler's existing wording stays byte-identical after delegating to these methods —
 * see each method's callers.
 */
final class MultiDocumentInvoiceSupport {

  private MultiDocumentInvoiceSupport() {
  }

  /**
   * Extracts a list of document IDs from the request body's {@code arrayKey} JSON array,
   * falling back to a single {@code fallbackRecordId} when the array is absent, empty, or
   * malformed. This is what lets a single-record caller (which sends no array at all) and a
   * bulk caller (which sends the array) share one endpoint: an empty result here always means
   * "invoice exactly the record the URL already names".
   *
   * @param body
   *     optional request body (may be {@code null})
   * @param arrayKey
   *     the JSON key holding the array of ids (e.g. {@code shipmentIds}, {@code receiptIds})
   * @param fallbackRecordId
   *     id used when {@code arrayKey} is absent, empty, or unparseable
   * @param log
   *     the caller's logger, so a parse failure is attributed to the right class
   * @return non-empty list of document IDs to invoice
   */
  static List<String> parseDocumentIds(JSONObject body, String arrayKey, String fallbackRecordId, Logger log) {
    List<String> ids = new ArrayList<>();
    if (body != null && body.has(arrayKey)) {
      try {
        JSONArray arr = body.getJSONArray(arrayKey);
        for (int i = 0; i < arr.length(); i++) {
          ids.add(arr.getString(i));
        }
      } catch (Exception e) {
        log.warn("Failed to parse {}: {}", arrayKey, e.getMessage());
      }
    }
    if (ids.isEmpty()) {
      ids.add(fallbackRecordId);
    }
    return ids;
  }

  /**
   * Loads the {@link ShipmentInOut} records for the given IDs and validates that all belong to
   * the same Business Partner — the one cross-document invariant a combined invoice cannot
   * relax, since {@code C_Invoice.C_BPartner_ID} is a single column.
   *
   * <p>Hardened against a null Business Partner on either side of the comparison (an id-only
   * equality check via {@link Objects#equals}), unlike a direct {@code .getId()} call, which
   * would NPE on a document with no Business Partner set.
   *
   * @param ids
   *     list of {@code M_InOut_ID} values to load
   * @param notFoundPrefix
   *     message prefix when an id does not resolve to a record (the id itself is appended)
   * @param emptyMessage
   *     message when {@code ids} is empty
   * @param mismatchMessage
   *     message when the documents do not all share one Business Partner
   * @return validated list of documents in the same order as the input IDs
   * @throws OBException
   *     if any ID is not found, the list is empty, or the documents span multiple
   *     Business Partners
   */
  static List<ShipmentInOut> loadAndValidateSameBusinessPartner(List<String> ids, String notFoundPrefix,
      String emptyMessage, String mismatchMessage) {
    List<ShipmentInOut> docs = new ArrayList<>();
    for (String id : ids) {
      ShipmentInOut s = OBDal.getInstance().get(ShipmentInOut.class, id);
      if (s == null) {
        throw new OBException(notFoundPrefix + id);
      }
      docs.add(s);
    }
    if (docs.isEmpty()) {
      throw new OBException(emptyMessage);
    }
    String firstBpId = businessPartnerId(docs.get(0));
    for (ShipmentInOut s : docs) {
      if (!Objects.equals(businessPartnerId(s), firstBpId)) {
        throw new OBException(mismatchMessage);
      }
    }
    return docs;
  }

  private static String businessPartnerId(ShipmentInOut doc) {
    BusinessPartner bp = doc.getBusinessPartner();
    return bp != null ? bp.getId() : null;
  }

  /**
   * Determines the quantity to invoice for a single shipment/receipt line, capped at the
   * uninvoiced (pending) quantity so that already-invoiced lines are skipped. Returns {@code
   * null} when the line is inactive, excluded by overrides, has zero/null movement quantity, or
   * is already fully invoiced. When overrides are present the result is capped at
   * min(|override|, |pendingQty|), with the line's own sign reapplied to the result.
   *
   * <p><b>ETP-4567:</b> movement quantity can be NEGATIVE (a return-style line). {@code
   * pendingQtyMap} is itself magnitude-only — its underlying query {@code ABS()}s both movement
   * and invoiced quantities at the SQL level, so that a negative return-style line and a
   * positive shipment/receipt line are tracked on the same sign-agnostic "remaining magnitude"
   * scale. This method therefore does all its pending-quantity arithmetic in magnitude
   * (absolute-value) space and reapplies the line's own sign only at the very end — comparing
   * or clamping a negative {@code movementQty} directly against that magnitude-only map (or a
   * {@code max(ZERO)} clamp) would silently destroy the sign or drop the line outright.
   *
   * @param line
   *     the shipment/receipt line to evaluate
   * @param hasOverrides
   *     whether {@code lineOverrides} is non-empty
   * @param lineOverrides
   *     caller-supplied quantity caps per line
   * @param pendingQtyMap
   *     pre-computed uninvoiced qty MAGNITUDE per {@code M_InOutLine_ID}. A NON-NULL map is
   *     authoritative: a line absent from it has nothing pending and is skipped (the pending
   *     queries omit lines with pending &lt;= 0, so falling back to the movement quantity would
   *     re-invoice an already-invoiced line, ETP-5709). {@code null} means "no pending
   *     information was computed" and is the only case that falls back to the line's own
   *     movement quantity (the backward-compatible 3-arg seam of the sales handler)
   * @return quantity to invoice (sign matches the line), or {@code null} to skip this line
   */
  static BigDecimal resolveInOutLineQty(ShipmentInOutLine line, boolean hasOverrides,
      Map<String, BigDecimal> lineOverrides, Map<String, BigDecimal> pendingQtyMap) {
    if (!line.isActive() || (hasOverrides && !lineOverrides.containsKey(line.getId()))) {
      return null;
    }
    BigDecimal movementQty = line.getMovementQuantity();
    if (movementQty == null || movementQty.compareTo(BigDecimal.ZERO) == 0) {
      return null;
    }
    int sign = movementQty.signum();
    BigDecimal movementQtyAbs = movementQty.abs();
    BigDecimal pendingQtyAbs = pendingQtyMap == null
        ? movementQtyAbs
        : pendingQtyMap.getOrDefault(line.getId(), BigDecimal.ZERO).min(movementQtyAbs);
    if (pendingQtyAbs.compareTo(BigDecimal.ZERO) <= 0) {
      return null; // already fully invoiced
    }
    BigDecimal qtyAbs = hasOverrides
        ? lineOverrides.get(line.getId()).abs().min(pendingQtyAbs)
        : pendingQtyAbs;
    if (qtyAbs.compareTo(BigDecimal.ZERO) <= 0) {
      return null;
    }
    return sign < 0 ? qtyAbs.negate() : qtyAbs;
  }

  /**
   * Refuses explicit per-line quantities that exceed what is still pending (ETP-5709). An agent
   * (or any API caller) must learn that its request cannot be honoured, so nothing is silently
   * capped: a quantity is accepted only when it has the SAME sign as the line's movement and its
   * magnitude does not exceed the pending magnitude. A line absent from {@code pendingQtyMap}
   * has nothing pending, so any explicit quantity for it is refused too.
   *
   * <p>Line ids in {@code overrides} that belong to none of {@code docs} are ignored here, as
   * they always were: they select nothing.
   *
   * @param docs
   *     the source shipments/receipts the overrides refer to
   * @param overrides
   *     explicit quantity per {@code M_InOutLine_ID}; empty means nothing to validate
   * @param pendingQtyMap
   *     uninvoiced qty MAGNITUDE per line (lines with nothing pending are absent), merged across
   *     {@code docs}; must come from the throwing computation so a DB failure is not read as
   *     "nothing pending"
   * @param errFormat
   *     message with three {@code %s}: the line description, the requested quantity and the
   *     pending quantity (signed like the line)
   * @throws OBException
   *     naming the first offending line and the quantity still pending
   */
  static void requireOverridesWithinPending(List<ShipmentInOut> docs,
      Map<String, BigDecimal> overrides, Map<String, BigDecimal> pendingQtyMap, String errFormat) {
    if (overrides == null || overrides.isEmpty()) {
      return;
    }
    for (ShipmentInOut doc : docs) {
      for (ShipmentInOutLine line : doc.getMaterialMgmtShipmentInOutLineList()) {
        BigDecimal requested = overrides.get(line.getId());
        if (requested != null) {
          requireWithinPending(doc, line, requested, pendingQtyMap, errFormat);
        }
      }
    }
  }

  private static void requireWithinPending(ShipmentInOut doc, ShipmentInOutLine line,
      BigDecimal requested, Map<String, BigDecimal> pendingQtyMap, String errFormat) {
    BigDecimal movement = line.getMovementQuantity();
    int sign = movement == null ? 0 : movement.signum();
    BigDecimal pendingAbs = pendingQtyMap.getOrDefault(line.getId(), BigDecimal.ZERO);
    if (movement != null) {
      pendingAbs = pendingAbs.min(movement.abs());
    }
    if (isOverridePending(requested, sign, pendingAbs)) {
      return;
    }
    BigDecimal pendingSigned = sign < 0 ? pendingAbs.negate() : pendingAbs;
    throw new OBException(String.format(errFormat, describeLine(doc, line),
        requested.toPlainString(), pendingSigned.toPlainString()));
  }

  /** True when {@code requested} has the line's sign and does not exceed the pending magnitude. */
  private static boolean isOverridePending(BigDecimal requested, int sign, BigDecimal pendingAbs) {
    return sign != 0 && requested.signum() == sign && requested.abs().compareTo(pendingAbs) <= 0;
  }

  private static String describeLine(ShipmentInOut doc, ShipmentInOutLine line) {
    return doc.getDocumentNo() + " line " + line.getLineNo() + " (" + line.getId() + ")";
  }

  /**
   * Refuses a request whose {@code lines} array carries entries but none survived parsing
   * (zero, blank or malformed quantity, missing line id). The parsers drop such entries, which
   * leaves an EMPTY override map, and an empty map means "no explicit lines: invoice everything
   * pending" — so a caller that asked for specific quantities and made a mistake would get the
   * whole document invoiced instead of an error (ETP-5709).
   *
   * @param body
   *     the request body; may be {@code null}
   * @param linesKey
   *     the body key of the array (always {@code lines})
   * @param parsedOverrides
   *     what the handler's parser produced from {@code body}
   * @param errMessage
   *     the message of the 400
   */
  static void requireParsedLines(JSONObject body, String linesKey,
      Map<String, BigDecimal> parsedOverrides, String errMessage) {
    if (body == null || !parsedOverrides.isEmpty()) {
      return;
    }
    JSONArray lines = body.optJSONArray(linesKey);
    if (lines != null && lines.length() > 0) {
      throw new OBException(errMessage);
    }
  }

  /**
   * Builds the {@code [{id, orderedQuantity}, ...]} selection for every invoiceable line across
   * {@code docs}, via {@link #resolveInOutLineQty}. Used by a NEW multi-document path that has
   * no pre-existing overridable per-line seam to preserve (unlike {@link
   * CreateDraftInvoiceHandler#addShipmentLinesToInvoice}, which keeps its own loop so its
   * subclass test doubles keep intercepting {@code resolveShipmentLineQty} virtually).
   *
   * @param docs
   *     the source shipments/receipts, already validated
   * @param lineOverrides
   *     map of {@code M_InOutLine_ID → quantity} caps; empty means use full pending quantity
   * @param pendingQtyMap
   *     pre-computed uninvoiced qty MAGNITUDE per {@code M_InOutLine_ID}, merged across all
   *     of {@code docs} (line ids are globally unique, so a flat merge is safe)
   * @param log
   *     the caller's logger, so a malformed entry is attributed to the right class
   * @return JSON array of {@code {id, orderedQuantity}} entries; empty when nothing is pending
   */
  static JSONArray buildInOutLineSelection(List<ShipmentInOut> docs, Map<String, BigDecimal> lineOverrides,
      Map<String, BigDecimal> pendingQtyMap, Logger log) {
    boolean hasOverrides = !lineOverrides.isEmpty();
    JSONArray selectedLines = new JSONArray();
    for (ShipmentInOut doc : docs) {
      for (ShipmentInOutLine line : doc.getMaterialMgmtShipmentInOutLineList()) {
        BigDecimal qty = resolveInOutLineQty(line, hasOverrides, lineOverrides, pendingQtyMap);
        if (qty == null) {
          continue;
        }
        try {
          JSONObject entry = new JSONObject();
          entry.put("id", line.getId());
          entry.put("orderedQuantity", qty.toPlainString());
          selectedLines.put(entry);
        } catch (Exception e) {
          log.warn("Failed to build invoice line entry for line {}: {}", line.getId(), e.getMessage());
        }
      }
    }
    return selectedLines;
  }

  /**
   * Builds the order-line entry that invoices ONE movement (shipment/receipt) line at a given
   * quantity (ETP-5709): {@code {id: orderLineId, orderedQuantity, shipmentInOutLine: lineId}},
   * plus {@code orderQuantity} / {@code operativeQuantity} / {@code operativeUOM} scaled to the
   * invoiced fraction of the line when it carries them, mirroring what the native process builds
   * for such an entry itself ({@code CreateInvoiceLinesFromProcess#getInOutLineJson}).
   *
   * <p>Why the entry must name its movement line: the native process replaces an order-line entry
   * WITHOUT a {@code shipmentInOutLine} that has related movement lines by ALL of them at their
   * full uninvoiced quantity, silently discarding the quantity sent here (a receipt lowered to 3,
   * {@code lines [{rl, 1}]} still invoiced 3, and the other receipts of the same order line were
   * dragged in). An entry that names it is used as is. One entry PER movement line, never one per
   * order line: the native process creates one invoice line per entry.
   *
   * @param orderLineId
   *     the order line the invoice line is priced from
   * @param line
   *     the movement line being invoiced
   * @param qty
   *     the quantity to invoice, signed like the line
   */
  static JSONObject buildOrderLineEntryForInOutLine(String orderLineId, ShipmentInOutLine line,
      BigDecimal qty) throws JSONException {
    JSONObject entry = new JSONObject();
    entry.put("id", orderLineId);
    entry.put("orderedQuantity", qty.toPlainString());
    entry.put("shipmentInOutLine", line.getId());
    BigDecimal movement = line.getMovementQuantity();
    if (movement != null && movement.signum() != 0) {
      BigDecimal ratio = qty.divide(movement, MathContext.DECIMAL128);
      if (line.getOrderQuantity() != null) {
        entry.put("orderQuantity", scaled(line.getOrderQuantity(), ratio));
      }
      if (line.getOperativeQuantity() != null) {
        entry.put("operativeQuantity", scaled(line.getOperativeQuantity(), ratio));
      }
    }
    if (line.getOperativeUOM() != null) {
      entry.put("operativeUOM", line.getOperativeUOM().getId());
    }
    return entry;
  }

  private static String scaled(BigDecimal value, BigDecimal ratio) {
    return value.multiply(ratio, MathContext.DECIMAL128).stripTrailingZeros().toPlainString();
  }

  /**
   * Gives {@code qty} the sign of {@code line}'s movement quantity. The pending-quantity queries
   * are magnitude-only, so a quantity seeded from them is positive even for a negative
   * (return-style) line; an entry that names its movement line is used as is by the native process
   * (it no longer re-derives the quantity), so the sign has to be right here.
   */
  static BigDecimal signedLikeLine(ShipmentInOutLine line, BigDecimal qty) {
    BigDecimal movement = line.getMovementQuantity();
    if (qty == null || movement == null || movement.signum() == 0) {
      return qty;
    }
    return movement.signum() < 0 ? qty.abs().negate() : qty.abs();
  }

  /**
   * Resolves the standard price of a specific, known set of products under a price list, via a
   * single indexed {@code m_productprice} lookup keyed on an {@code IN} clause of exactly those
   * ids — never the generic product-browse selector.
   *
   * <p><b>Why this exists:</b> the "Crear factura" quote feature (ETP-5410) originally priced
   * unpriced-by-order lines by calling {@code GET .../selectors/M_Product_ID?limit=500}, the same
   * generic "browse products" selector the manual line-entry UI uses to let a human search a
   * catalog. That selector has to run its full generic query — joins, identifiers, pagination —
   * for up to 500 products before {@code ProductPriceSelectorPolicy} even starts pricing them,
   * even though the caller already knows the exact 1-3 product ids it needs a price for. This
   * method is the same indexed {@code IN (...)} query {@code ProductPriceSelectorPolicy} already
   * uses to price selector rows, extracted so a caller that isn't browsing a catalog can reach it
   * directly, without paying for the browse query at all.
   *
   * @param priceListId
   *     the price list to price against
   * @param productIds
   *     the specific products to price; duplicates and blanks are ignored
   * @return map of {@code M_Product_ID -> standard price}; a product with no active price-list
   *     entry is simply absent from the result, never zero-padded
   */
  static Map<String, BigDecimal> resolveProductPrices(String priceListId, List<String> productIds) {
    Map<String, BigDecimal> prices = new HashMap<>();
    if (StringUtils.isBlank(priceListId) || productIds == null || productIds.isEmpty()) {
      return prices;
    }
    Set<String> distinctIds = new LinkedHashSet<>();
    for (String id : productIds) {
      if (StringUtils.isNotBlank(id)) {
        distinctIds.add(id);
      }
    }
    if (distinctIds.isEmpty()) {
      return prices;
    }

    StringBuilder inClause = new StringBuilder();
    List<String> orderedIds = new ArrayList<>(distinctIds);
    for (int i = 0; i < orderedIds.size(); i++) {
      if (i > 0) {
        inClause.append(", ");
      }
      inClause.append(":pid").append(i);
    }
    String sql = "SELECT pp.m_product_id, pp.pricestd"
        + " FROM m_productprice pp"
        + " JOIN m_pricelist_version plv"
        + "   ON plv.m_pricelist_version_id = pp.m_pricelist_version_id"
        + " WHERE plv.m_pricelist_id = :priceListId"
        + "   AND pp.m_product_id IN (" + inClause + ")"
        + "   AND pp.isactive = 'Y'"
        + "   AND plv.isactive = 'Y'"
        + "   AND plv.validfrom = ("
        + "     SELECT MAX(v.validfrom) FROM m_pricelist_version v"
        + "     WHERE v.m_pricelist_id = :priceListId"
        + "       AND v.isactive = 'Y'"
        + "       AND v.validfrom <= NOW()"
        + "   )";
    @SuppressWarnings("rawtypes")
    NativeQuery nq = OBDal.getInstance().getSession().createNativeQuery(sql);
    nq.setParameter("priceListId", priceListId);
    for (int i = 0; i < orderedIds.size(); i++) {
      nq.setParameter("pid" + i, orderedIds.get(i));
    }
    for (Object row : nq.list()) {
      Object[] cols = (Object[]) row;
      if (cols[1] != null) {
        prices.put(String.valueOf(cols[0]), new BigDecimal(cols[1].toString()));
      }
    }
    return prices;
  }

  /**
   * Resolves the effective price list to preselect for a shipment/receipt's invoice quote, in
   * priority order:
   * <ol>
   *   <li>the price list of the order the document resolves to — its own header {@code
   *       C_Order_ID}, or, for a header-less document, the order of its first order-linked
   *       line (same notion {@code CreatePurchaseInvoiceHandler#deriveOrderFromLines}
   *       already uses for the create path);</li>
   *   <li>otherwise, the Business Partner's own configured price list for the matching trade
   *       direction ({@code getPriceList()} for sales, {@code getPurchasePricelist()} for
   *       purchase);</li>
   *   <li>otherwise, the client's own DEFAULT price list for that direction — the fallback
   *       Etendo Classic uses when a Business Partner has no price list configured at all.
   *       Both {@code GoodsShipmentHeaderHandler#enrichResolvedPriceList} and its
   *       goods-receipt counterpart used to stop at tier 2 and leave the field empty; this is
   *       the tier they were missing.</li>
   * </ol>
   * Used by {@code CreateDraftInvoiceHandler#handlePendingLines} /
   * {@code CreatePurchaseInvoiceHandler#handlePendingLines} to return the resolved price list
   * in the SAME response as the pending quantities, instead of the caller needing a separate
   * full single-record GET just for this one field (ETP-5410 follow-up).
   *
   * @param doc     the shipment/receipt to resolve for
   * @param isSOTrx {@code true} for a sales shipment, {@code false} for a purchase receipt
   * @return the resolved {@code M_PriceList_ID}, or {@code null} if no default price list
   *     exists for the client/direction either
   */
  static String resolveEffectivePriceListId(ShipmentInOut doc, boolean isSOTrx) {
    Order order = doc.getSalesOrder();
    if (order == null) {
      order = deriveOrderFromLines(doc);
    }
    if (order != null && order.getPriceList() != null) {
      return order.getPriceList().getId();
    }
    BusinessPartner bp = doc.getBusinessPartner();
    if (bp != null) {
      PriceList bpPriceList = isSOTrx ? bp.getPriceList() : bp.getPurchasePricelist();
      if (bpPriceList != null) {
        return bpPriceList.getId();
      }
    }
    PriceList defaultPriceList = findDefaultPriceList(isSOTrx);
    return defaultPriceList != null ? defaultPriceList.getId() : null;
  }

  private static Order deriveOrderFromLines(ShipmentInOut doc) {
    for (ShipmentInOutLine line : doc.getMaterialMgmtShipmentInOutLineList()) {
      if (line.getSalesOrderLine() != null && line.getSalesOrderLine().getSalesOrder() != null) {
        return line.getSalesOrderLine().getSalesOrder();
      }
    }
    return null;
  }

  /**
   * Package-visible (not {@code private}) so {@code GoodsShipmentHeaderHandler} /
   * {@code GoodsReceiptHeaderHandler} can reuse it as the tier-3 fallback of their own
   * {@code enrichResolvedPriceList}, instead of a second copy of this query.
   */
  static PriceList findDefaultPriceList(boolean isSOTrx) {
    @SuppressWarnings("unchecked")
    List<PriceList> matches = OBDal.getInstance().createCriteria(PriceList.class)
        .add(Restrictions.eq(PriceList.PROPERTY_SALESPRICELIST, isSOTrx))
        .add(Restrictions.eq(PriceList.PROPERTY_DEFAULT, true))
        .add(Restrictions.eq(PriceList.PROPERTY_ACTIVE, true))
        .setMaxResults(1)
        .list();
    return matches.isEmpty() ? null : matches.get(0);
  }

  /**
   * Maps each active line of {@code doc} to its {@code [productId, salesOrderLineId]} pair
   * (either entry may be {@code null}). Shared by both handlers' {@code handlePendingLines} so
   * the quote feature gets product/order-line data in the SAME response as the pending
   * quantities, instead of a second request. Takes the already-loaded entity (rather than an
   * id) because the caller also needs it for {@link #resolveEffectivePriceListId}.
   */
  static Map<String, String[]> loadLineProductAndOrderLine(ShipmentInOut doc) {
    Map<String, String[]> details = new HashMap<>();
    if (doc == null) {
      return details;
    }
    for (ShipmentInOutLine line : doc.getMaterialMgmtShipmentInOutLineList()) {
      if (!line.isActive()) {
        continue;
      }
      String productId = line.getProduct() != null ? line.getProduct().getId() : null;
      String orderLineId = line.getSalesOrderLine() != null ? line.getSalesOrderLine().getId() : null;
      details.put(line.getId(), new String[] { productId, orderLineId });
    }
    return details;
  }

  /**
   * Prices a caller-supplied list of products against a price list — shared body of both
   * handlers' POST {@code .../action/productPrices}, body {@code {productIds: [...],
   * priceListId: "..."}}. Exists only so the "Crear factura" quote feature can price the
   * handful of products its own pending lines actually reference, instead of running the
   * generic product-browse selector (up to 500 rows) and discarding all but a few of them —
   * see {@link #resolveProductPrices}.
   *
   * @param log the caller's own Logger, so a parse/DB warning or error is attributed to the
   *     calling handler class rather than to this shared support class.
   */
  static NeoResponse buildProductPricesResponse(JSONObject body, Logger log) {
    String priceListId = body != null ? body.optString("priceListId", null) : null;
    List<String> productIds = new ArrayList<>();
    if (body != null && body.has("productIds")) {
      try {
        JSONArray idsArr = body.getJSONArray("productIds");
        for (int i = 0; i < idsArr.length(); i++) {
          productIds.add(idsArr.getString(i));
        }
      } catch (Exception e) {
        log.warn("Failed to parse productIds: {}", e.getMessage());
      }
    }
    try {
      OBContext.setAdminMode(true);
      try {
        Map<String, BigDecimal> prices = resolveProductPrices(priceListId, productIds);
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, BigDecimal> entry : prices.entrySet()) {
          JSONObject item = new JSONObject();
          item.put("productId", entry.getKey());
          item.put("price", entry.getValue());
          arr.put(item);
        }
        JSONObject responseData = new JSONObject();
        responseData.put("data", arr);
        JSONObject wrapper = new JSONObject();
        wrapper.put("response", responseData);
        return new NeoResponse(200, wrapper);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error resolving product prices: {}", e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }
}
