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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.pricing.pricelist.PriceList;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * NeoHandler for the Goods Receipt header entity.
 *
 * <p>Routes custom ACTION requests:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code createPurchaseInvoice} → {@link CreatePurchaseInvoiceHandler}</li>
 *   <li>{@code createPurchaseReturn} → {@link CreatePurchaseReturnHandler}</li>
 * </ul>
 */
@Named("goodsReceiptHeaderHandler")
public class GoodsReceiptHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(GoodsReceiptHeaderHandler.class);
  private static final String FIELD_DOCUMENT_NO = "documentNo";
  private static final String FIELD_DOCUMENT_STATUS = "documentStatus";
  private static final String FIELD_MOVEMENT_DATE = "movementDate";
  private static final String FIELD_ACCOUNTING_DATE = "accountingDate";
  private static final String ACTION_DOCUMENT_ACTION = "documentAction";
  private static final String FIELD_INVOICE_STATUS = "invoiceStatus";
  private static final String FIELD_LINKED_ORDERS = "linkedOrders";
  private static final String FIELD_RESOLVED_PRICE_LIST_ID = "resolvedPriceListId";
  private static final String FIELD_RESOLVED_PRICE_LIST_IDENTIFIER = "resolvedPriceList$_identifier";
  private static final String MATCH_INV_LINE_IDS_SCOPE = "SELECT m_inoutline_id FROM receipt_lines";

  /**
   * Restricts an aggregation arm to the requested receipt's lines. It is applied to each arm's own
   * {@code GROUP BY} key ({@code m_inoutline_id}), so whole groups are kept or dropped and no sum
   * changes; the planner can then drive the arm from the receipt instead of aggregating every
   * invoice in the instance (ETP-5653).
   */
  private static final String RECEIPT_LINES_FILTER =
      " IN (SELECT m_inoutline_id FROM receipt_lines)";

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private CreatePurchaseInvoiceHandler createPurchaseInvoiceHandler;

  @Inject
  private CreatePurchaseReturnHandler createPurchaseReturnHandler;

  @Inject
  private ImportOrderLinesIntoInOutHandler importOrderLinesHandler;

  @Inject
  private DocumentPostingService postingService;

  /**
   * The actions this header serves through its delegates, declared for agents (ETP-5709):
   * {@code documentAction} (CO only, {@link InOutCompletionContract}), the order import
   * ({@code availableOrders}, {@code availableOrderLines}, {@code importOrderLines},
   * {@link ImportOrderLinesIntoInOutHandler}, ETP-5713), {@code createPurchaseInvoice},
   * {@code createPurchaseReturn} ({@link CreatePurchaseReturnHandler#CONTRACT}, ETP-5711) and
   * {@code cloneRecord}. Published by the MCP next to the AD
   * buttons; REST and the SPA do not read it.
   */
  @Override
  public Map<String, NeoActionContract> actionContracts() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(InOutCompletionContract.CONTRACT.getName(), InOutCompletionContract.CONTRACT);
    contracts.putAll(ImportOrderLinesIntoInOutHandler.contracts(false));
    contracts.put(CreatePurchaseInvoiceHandler.RECEIPT_CONTRACT.getName(),
        CreatePurchaseInvoiceHandler.RECEIPT_CONTRACT);
    contracts.put(CreatePurchaseReturnHandler.CONTRACT.getName(),
        CreatePurchaseReturnHandler.CONTRACT);
    contracts.put(NeoCloneRecordHandler.CONTRACT.getName(), NeoCloneRecordHandler.CONTRACT);
    contracts.putAll(GoodsPostingContracts.CONTRACTS);
    return contracts;
  }

  /**
   * The keys {@link #afterHandle} adds to a GET record that no spec field backs (ETP-5714):
   * {@code invoiceStatus} (0 to 100: the percentage of the receipt already invoiced),
   * {@code returnStatus} (the percentage already returned), {@code linkedInvoices},
   * {@code linkedReturns}, {@code linkedOrders} and the resolved price list. Declared so an MCP
   * {@code fields:[…]} projection does not report them in {@code unknownFields} while the same
   * response carries them.
   */
  static final Set<String> RESPONSE_ENRICHED_FIELDS = Set.of(FIELD_INVOICE_STATUS, "returnStatus",
      "linkedInvoices", "linkedReturns", FIELD_LINKED_ORDERS, FIELD_RESOLVED_PRICE_LIST_ID,
      FIELD_RESOLVED_PRICE_LIST_IDENTIFIER);

  /**
   * {@inheritDoc} The keys {@link #afterHandle} adds to a GET record (see
   * {@link #RESPONSE_ENRICHED_FIELDS}).
   */
  @Override
  public Set<String> responseEnrichedFields() {
    return RESPONSE_ENRICHED_FIELDS;
  }

  /**
   * {@inheritDoc} The invoice action does not apply once nothing is left to invoice or a draft
   * invoice already covers it (ETP-5714).
   */
  @Override
  public Map<String, String> unavailableActions(BaseOBObject subject) {
    return InOutInvoiceAvailability.unavailable(subject,
        CreatePurchaseInvoiceHandler.RECEIPT_CONTRACT.getName(), "goods receipt");
  }

  /** Package-private seam so unit tests can inject a mocked {@link DocumentPostingService}. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    mirrorAccountingDate(context);
    NeoResponse unpostRefusal = GoodsPostingContracts.checkUnpost(context);
    if (unpostRefusal != null) {
      return unpostRefusal;
    }
    NeoResponse posting = postingService != null ? postingService.handleAction(context) : null;
    if (posting != null) {
      return posting;
    }
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && "POST".equals(context.getHttpMethod())
        && context.getRecordId() == null) {
      injectPartnerAddressIfMissing(context);
    }
    if (NeoEndpointType.ACTION.equals(context.getEndpointType())
        && ACTION_DOCUMENT_ACTION.equals(context.getFieldName())
        && "POST".equals(context.getHttpMethod())) {
      // ETP-4863: the header warehouse stays editable until the document is Processed, so a
      // line created while the header pointed at warehouse A can be left anchored to A even
      // after the user switches the header to warehouse B. Re-anchor every line to the
      // header's CURRENT warehouse right before the native completion flow runs.
      NeoHandlerUtils.reanchorLinesToHeaderWarehouse(context.getRecordId(), log);
      return null; // let NEO native process handle completion
    }
    return NeoHeaderActionRouter.dispatch(context,
        cloneRecordHandler, importOrderLinesHandler, createPurchaseInvoiceHandler,
        createPurchaseReturnHandler);
  }

  /**
   * Mirrors the single visible {@code movementDate} field into the hidden
   * {@code accountingDate} field on the request body, unconditionally, before the default CRUD
   * path persists it (ETP-4531 — unified date). The user never sees or edits accountingDate
   * directly; whatever value is saved for movementDate (create or update) must also become the
   * receipt's accounting date.
   *
   * <p><b>ETP-4531 fix:</b> must fire on {@code PATCH} as well as {@code POST}/{@code PUT} — the
   * live React UI ({@code useEntity.js#getMethod}) always sends {@code PATCH} for edits to an
   * EXISTING receipt; it never sends a full {@code PUT}. See {@link NeoHandlerUtils#isWriteMethod}.
   */
  static void mirrorAccountingDate(NeoContext context) {
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
      NeoHandlerUtils.mirrorFieldValue(context.getRequestBody(), FIELD_MOVEMENT_DATE, FIELD_ACCOUNTING_DATE);
    }
  }

  private void injectPartnerAddressIfMissing(NeoContext context) {
    try {
      JSONObject body = context.getRequestBody();
      if (body == null || body.has("partnerAddress")) {
        return;
      }
      String bpId = body.optString("businessPartner", null);
      if (bpId == null || bpId.isEmpty()) {
        return;
      }
      OBContext.setAdminMode(true);
      try {
        Location loc = findShipToLocation(bpId);
        if (loc == null) {
          loc = findAnyActiveLocation(bpId);
        }
        if (loc != null) {
          body.put("partnerAddress", loc.getId());
          log.debug("Injected partnerAddress {} for BP {} on goods receipt create", loc.getId(), bpId);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.debug("Could not inject partnerAddress for goods receipt: {}", e.getMessage());
    }
  }

  private Location findShipToLocation(String bpId) {
    OBCriteria<Location> crit = OBDal.getInstance().createCriteria(Location.class);
    crit.add(Restrictions.eq(Location.PROPERTY_BUSINESSPARTNER + ".id", bpId));
    crit.add(Restrictions.eq(Location.PROPERTY_SHIPTOADDRESS, true));
    crit.add(Restrictions.eq(Location.PROPERTY_ACTIVE, true));
    crit.setMaxResults(1);
    return (Location) crit.uniqueResult();
  }

  private Location findAnyActiveLocation(String bpId) {
    OBCriteria<Location> crit = OBDal.getInstance().createCriteria(Location.class);
    crit.add(Restrictions.eq(Location.PROPERTY_BUSINESSPARTNER + ".id", bpId));
    crit.add(Restrictions.eq(Location.PROPERTY_ACTIVE, true));
    crit.setMaxResults(1);
    return (Location) crit.uniqueResult();
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    try {
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null) {
        return null;
      }
      JSONObject body = context.getPreviousResult().getBody();
      if (context.getRecordId() != null) {
        JSONObject rec = dataArr.getJSONObject(0);
        rec.put(FIELD_INVOICE_STATUS, computeInvoiceStatus(context.getRecordId()));
        rec.put("returnStatus", computeReturnStatus(context.getRecordId()));
        enrichLinkedInvoices(rec, context.getRecordId());
        enrichLinkedOrder(rec, context.getRecordId());
        enrichResolvedPriceList(rec, context.getRecordId());
        enrichLinkedReturns(rec, context.getRecordId());
      } else {
        List<String> ids = NeoHandlerUtils.collectIds(dataArr);
        Map<String, Integer> statusMap = computeInvoiceStatusBatch(ids);
        for (int i = 0; i < dataArr.length(); i++) {
          JSONObject rec = dataArr.getJSONObject(i);
          String id = rec.optString("id", null);
          if (id != null) {
            rec.put(FIELD_INVOICE_STATUS, statusMap.getOrDefault(id, 0));
          }
        }
        if (context.isMcpOrigin()) {
          // ETP-5852: as on goods-shipment — the page's invoices in one query, MCP only.
          InOutInvoiceLinks.putLinkedInvoices(dataArr, InOutInvoiceLinks.linkedInvoicesByInOut(
              InOutInvoiceLinks.MatchTable.PURCHASE, ids));
        }
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching goods receipt header", e);
      return null;
    }
  }

  private int computeInvoiceStatus(String receiptId) {
    String sql = buildInvoiceStatusSql("iol.m_inout_id = ?");
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, receiptId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(2) : 0;
      }
    } catch (Exception e) {
      log.error("DB error computing invoice status for receipt {}", receiptId, e);
      return 0;
    }
  }

  // placeholders contains only "?" literals — no injection risk.
  @SuppressWarnings("java:S2077")
  private Map<String, Integer> computeInvoiceStatusBatch(List<String> ids) {
    if (ids.isEmpty()) return new HashMap<>();
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
    String sql = buildInvoiceStatusSql("iol.m_inout_id IN (" + placeholders + ")");
    Map<String, Integer> result = new HashMap<>();
    try (PreparedStatement ps = OBDal.getInstance().getConnection().prepareStatement(sql)) {
      for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) result.put(rs.getString(1), rs.getInt(2));
      }
    } catch (Exception e) {
      log.error("DB error in batch invoice status for receipts", e);
    }
    return result;
  }

  private static String buildInvoiceStatusSql(String whereClause) {
    // Three detection paths per receipt line, to handle the full invoice lifecycle:
    //
    // msi_qty   — via M_MatchInv (the purchase-side match table — a receipt is never matched in
    //              M_MatchSI): written by m_inout_post when the receipt is completed, and by
    //              InvoiceLineLinker for a second or later partial receipt of an invoice line,
    //              which the m_inoutline_id column cannot hold (ETP-5576).
    // direct_qty — via c_invoiceline.m_inoutline_id: set by InvoiceLineLinker when the
    //              invoice was created directly from this receipt.
    // ol_qty    — via c_orderline_id fallback: covers invoices created from the purchase
    //              order (not from the receipt) where m_inoutline_id is never set.
    //              Each line's contribution is capped at movementqty to avoid over-stating
    //              when the order invoice covers more units than this receipt delivered.
    //
    // The arms are combined with GREATEST, never added: the first receipt of an invoice line is
    // linked by the column AND by the M_MatchInv row m_inout_post writes for it.
    //
    // The requested receipt's lines are resolved once, in a CTE, so `whereClause` keeps its
    // placeholders exactly once and every arm can be restricted to that set. The restriction is
    // applied to each arm's own GROUP BY key, so it keeps or drops whole groups and the sums are
    // unchanged — it only spares the planner from aggregating every invoice in the instance.
    return
      "WITH receipt_lines AS ( "
      + "  SELECT iol.m_inoutline_id FROM m_inoutline iol "
      + "  WHERE iol.isactive = 'Y' AND " + whereClause + " "
      + ") "
      + "SELECT iol.m_inout_id, "
      + "  CASE WHEN SUM(ABS(iol.movementqty)) = 0 THEN 0 "
      + "       ELSE LEAST(100, ROUND("
      + "         COALESCE(SUM(LEAST("
      + "           GREATEST("
      + "             COALESCE(msi_qty.qtymatched, 0),"
      + "             COALESCE(direct_qty.qtyinvoiced, 0),"
      + "             COALESCE(ol_qty.qtyinvoiced_via_ol, 0)"
      + "           ),"
      + "           ABS(iol.movementqty)"
      + "         )), 0) / SUM(ABS(iol.movementqty)) * 100"
      + "       )) "
      + "  END "
      + "FROM m_inoutline iol "
      + "LEFT JOIN ("
      + InOutInvoiceLinks.matchedQtyPerInOutLineSql(InOutInvoiceLinks.MatchTable.PURCHASE,
          MATCH_INV_LINE_IDS_SCOPE)
      + ") msi_qty ON msi_qty.m_inoutline_id = iol.m_inoutline_id "
      + "LEFT JOIN ("
      + "  SELECT il2.m_inoutline_id, SUM(ABS(il2.qtyinvoiced)) AS qtyinvoiced "
      + "  FROM c_invoiceline il2 "
      + "  JOIN c_invoice i2 ON i2.c_invoice_id = il2.c_invoice_id "
      + "  WHERE i2.docstatus NOT IN ('VO','CL','DR') AND i2.isactive = 'Y' "
      + "    AND il2.m_inoutline_id" + RECEIPT_LINES_FILTER + " "
      + "  GROUP BY il2.m_inoutline_id "
      + ") direct_qty ON direct_qty.m_inoutline_id = iol.m_inoutline_id "
      + "LEFT JOIN ("
      + "  SELECT iol2.m_inoutline_id, SUM(ABS(il3.qtyinvoiced)) AS qtyinvoiced_via_ol "
      + "  FROM c_invoiceline il3 "
      + "  JOIN c_invoice i3 ON i3.c_invoice_id = il3.c_invoice_id "
      + "  JOIN m_inoutline iol2 ON iol2.c_orderline_id = il3.c_orderline_id "
      + "  WHERE i3.docstatus NOT IN ('VO','CL','DR') AND i3.isactive = 'Y' "
      + "    AND il3.m_inoutline_id IS NULL "
      + "    AND il3.c_orderline_id IS NOT NULL "
      + "    AND iol2.m_inoutline_id" + RECEIPT_LINES_FILTER + " "
      + "  GROUP BY iol2.m_inoutline_id "
      + ") ol_qty ON ol_qty.m_inoutline_id = iol.m_inoutline_id "
      + "WHERE iol.m_inoutline_id" + RECEIPT_LINES_FILTER + " "
      + "GROUP BY iol.m_inout_id";
  }

  /**
   * Injects {@code linkedInvoices}: every invoice linked to one of this receipt's lines, through
   * {@link InOutInvoiceLinks#linkedInvoiceIdsSql} — the invoice line's {@code M_InOutLine_ID},
   * the {@code M_MatchInv} match table (read since ETP-5576: a second partial receipt of an
   * invoice line can only be linked there) and the pre-existing shared {@code C_OrderLine_ID} arm.
   */
  // The sub-select is built from a fixed enum literal; every value is bound — no injection risk.
  @SuppressWarnings("java:S2077")
  private void enrichLinkedInvoices(JSONObject rec, String receiptId) {
    String sql =
        "SELECT DISTINCT i.c_invoice_id, i.documentno, i.grandtotal, i.docstatus, cur.iso_code "
        + "FROM (" + InOutInvoiceLinks.linkedInvoiceIdsSql(InOutInvoiceLinks.MatchTable.PURCHASE) + ") lk "
        + "JOIN c_invoice i ON i.c_invoice_id = lk.c_invoice_id "
        + "LEFT JOIN c_currency cur ON cur.c_currency_id = i.c_currency_id "
        + "WHERE i.isactive = 'Y' AND i.docstatus NOT IN ('VO','CL')";
    try (PreparedStatement ps = OBDal.getReadOnlyInstance().getConnection().prepareStatement(sql)) {
      InOutInvoiceLinks.bindRepeated(ps, 1, receiptId, InOutInvoiceLinks.LINKED_INVOICES_PARAMS);
      JSONArray invoices = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject inv = new JSONObject();
          inv.put("id", rs.getString(1));
          inv.put(FIELD_DOCUMENT_NO, rs.getString(2));
          BigDecimal total = rs.getBigDecimal(3);
          inv.put("grandTotalAmount", total != null ? total : JSONObject.NULL);
          inv.put(FIELD_DOCUMENT_STATUS, rs.getString(4));
          inv.put("currency$_identifier", rs.getString(5));
          invoices.put(inv);
        }
      }
      rec.put("linkedInvoices", invoices);
    } catch (Exception e) {
      log.warn("Could not enrich linked invoices for receipt {}: {}", receiptId, e.getMessage());
    }
  }

  /**
   * One row per active receipt line: what it received and what the returns hold of it. The
   * returned quantity is aggregated per source line in a sub-select, so the received quantity of
   * a line with several returns is read once (a plain JOIN would repeat the line once per return
   * and inflate the denominator, ETP-6054). The returns counted are exactly those of
   * {@link ReturnableQuantity} (non-voided, drafts included), the same question the picker asks.
   */
  static final String RETURN_STATUS_SQL =
      "SELECT ABS(iol.movementqty), COALESCE(ret.returned_qty, 0) "
      + "FROM m_inoutline iol "
      + "LEFT JOIN (SELECT rl.canceled_inoutline_id AS source_line_id, "
      + "    SUM(ABS(rl.movementqty)) AS returned_qty "
      + "  FROM " + ReturnableQuantity.COUNTED_RETURN_LINES_FROM + " "
      + "  WHERE rl.canceled_inoutline_id IS NOT NULL AND "
      + ReturnableQuantity.COUNTED_RETURN_LINES_FILTER + " "
      + "  GROUP BY rl.canceled_inoutline_id) ret "
      + "  ON ret.source_line_id = iol.m_inoutline_id "
      + "WHERE iol.m_inout_id = ? AND iol.isactive = 'Y'";

  private int computeReturnStatus(String receiptId) {
    try (PreparedStatement ps =
        OBDal.getInstance().getConnection().prepareStatement(RETURN_STATUS_SQL)) {
      ps.setString(1, receiptId);
      try (ResultSet rs = ps.executeQuery()) {
        return returnPercent(rs);
      }
    } catch (Exception e) {
      log.error("DB error computing return status for receipt {}", receiptId, e);
      return 0;
    }
  }

  /**
   * Units returned over units received, summed across every line (so it mixes the units of
   * different products), as an integer percentage capped at 100.
   *
   * @param rs rows of {@link #RETURN_STATUS_SQL}: received quantity, returned quantity
   * @return the percentage, 0 when nothing was received
   * @throws SQLException when a row cannot be read
   */
  static int returnPercent(ResultSet rs) throws SQLException {
    BigDecimal received = BigDecimal.ZERO;
    BigDecimal returned = BigDecimal.ZERO;
    while (rs.next()) {
      BigDecimal rec = rs.getBigDecimal(1);
      BigDecimal ret = rs.getBigDecimal(2);
      received = received.add(rec == null ? BigDecimal.ZERO : rec);
      returned = returned.add(ret == null ? BigDecimal.ZERO : ret);
    }
    if (received.signum() == 0) {
      return 0;
    }
    return returned.multiply(BigDecimal.valueOf(100))
        .divide(received, 0, RoundingMode.HALF_UP).min(BigDecimal.valueOf(100)).intValue();
  }

  @SuppressWarnings("java:S2077")
  private void enrichLinkedReturns(JSONObject rec, String receiptId) {
    String sql =
        "SELECT DISTINCT rio.m_inout_id, rio.documentno, rio.docstatus "
        + "FROM m_inoutline ril "
        + "JOIN m_inout rio ON rio.m_inout_id = ril.m_inout_id "
        + "WHERE ril.canceled_inoutline_id IN ("
        + "  SELECT sil.m_inoutline_id FROM m_inoutline sil "
        + "  WHERE sil.m_inout_id = ? AND sil.isactive = 'Y'"
        + ") AND ril.isactive = 'Y' AND rio.isactive = 'Y'";
    try (PreparedStatement ps = OBDal.getReadOnlyInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, receiptId);
      JSONArray returns = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject ret = new JSONObject();
          ret.put("id", rs.getString(1));
          ret.put(FIELD_DOCUMENT_NO, rs.getString(2));
          ret.put(FIELD_DOCUMENT_STATUS, rs.getString(3));
          returns.put(ret);
        }
      }
      rec.put("linkedReturns", returns);
    } catch (Exception e) {
      log.warn("Could not enrich linked returns for receipt {}: {}", receiptId, e.getMessage());
    }
  }

  @SuppressWarnings("java:S2077")
  private void enrichLinkedOrder(JSONObject rec, String receiptId) {
    // ETP-4942: also brings the order's own purchase price list (id + name) via a
    // LEFT JOIN to m_pricelist, so enrichResolvedPriceList can reuse this SAME
    // "first linked order" resolution instead of duplicating the query — mirrors
    // GoodsShipmentHeaderHandler#enrichLinkedOrder (ETP-5052).
    String sql =
        "SELECT DISTINCT co.c_order_id, co.documentno, co.grandtotal, co.docstatus, cur.iso_code, "
        + "  co.m_pricelist_id, pl.name "
        + "FROM c_order co "
        + "LEFT JOIN c_currency cur ON cur.c_currency_id = co.c_currency_id "
        + "LEFT JOIN m_pricelist pl ON pl.m_pricelist_id = co.m_pricelist_id "
        + "WHERE co.isactive = 'Y' AND co.c_order_id IN ("
        + "  SELECT io.c_order_id FROM m_inout io WHERE io.m_inout_id = ? AND io.c_order_id IS NOT NULL"
        + "  UNION"
        + "  SELECT ol.c_order_id FROM m_inoutline il JOIN c_orderline ol ON ol.c_orderline_id = il.c_orderline_id"
        + "  WHERE il.m_inout_id = ? AND il.isactive = 'Y'"
        + ")";
    try (PreparedStatement ps = OBDal.getReadOnlyInstance().getConnection().prepareStatement(sql)) {
      ps.setString(1, receiptId);
      ps.setString(2, receiptId);
      JSONArray orders = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject order = new JSONObject();
          order.put("id", rs.getString(1));
          order.put(FIELD_DOCUMENT_NO, rs.getString(2));
          BigDecimal total = rs.getBigDecimal(3);
          order.put("grandTotalAmount", total != null ? total : JSONObject.NULL);
          order.put(FIELD_DOCUMENT_STATUS, rs.getString(4));
          order.put("currency$_identifier", rs.getString(5));
          String orderPriceListId = rs.getString(6);
          order.put("priceListId", orderPriceListId != null ? orderPriceListId : JSONObject.NULL);
          String orderPriceListName = rs.getString(7);
          order.put("priceList$_identifier", orderPriceListName != null ? orderPriceListName : JSONObject.NULL);
          orders.put(order);
        }
      }
      rec.put(FIELD_LINKED_ORDERS, orders);
    } catch (Exception e) {
      log.warn("Could not enrich linked orders for receipt {}: {}", receiptId, e.getMessage());
    }
  }

  /**
   * Resolves the effective purchase price list to preselect in the frontend's tariff picker
   * (ETP-4942 — extending the sales-side fix, {@link GoodsShipmentHeaderHandler
   * #enrichResolvedPriceList}, to Goods Receipt → Purchase Invoice), replicating the EXACT
   * priority {@code CreatePurchaseInvoiceHandler#createFromReceiptNoPo} uses to build the
   * real invoice header:
   * <ol>
   *   <li>the price list of the first linked purchase order — same "linked order" notion as
   *       {@link #enrichLinkedOrder} (i.e. {@code rec.linkedOrders[0]}), which this method
   *       reads rather than re-querying;</li>
   *   <li>otherwise, the Business Partner's own configured PURCHASE price list
   *       ({@code BusinessPartner#getPurchasePricelist()} — never {@code getPriceList()},
   *       which is the sales tariff);</li>
   *   <li>otherwise, the client's own DEFAULT purchase price list — matching what Etendo
   *       Classic falls back to when a Business Partner has none configured (ETP-5410
   *       follow-up: this tier used to be missing, leaving the field empty — this modal's own
   *       picker never fills it in on its own, since {@code CreateInvoiceConfirmModal} always
   *       disables the generic fallback here).</li>
   * </ol>
   * Adds {@code resolvedPriceListId} / {@code resolvedPriceList$_identifier} to the header
   * JSON. Must run AFTER {@link #enrichLinkedOrder} in {@link #afterHandle}, since it reads
   * the {@code linkedOrders} array that method just populated. Defensive by design — like
   * every other {@code enrich*} method here, a failure is logged and swallowed, never
   * propagated to the response.
   */
  private void enrichResolvedPriceList(JSONObject rec, String receiptId) {
    try {
      if (applyPriceListFromLinkedOrder(rec)) {
        return;
      }
      if (applyPriceListFromBusinessPartner(rec, receiptId)) {
        return;
      }
      applyClientDefaultPriceList(rec);
    } catch (Exception e) {
      log.warn("Could not resolve price list for receipt {}: {}", receiptId, e.getMessage());
    }
  }

  private boolean applyPriceListFromLinkedOrder(JSONObject rec) throws JSONException {
    JSONArray linkedOrders = rec.optJSONArray(FIELD_LINKED_ORDERS);
    if (linkedOrders == null || linkedOrders.length() == 0) {
      return false;
    }
    JSONObject firstOrder = linkedOrders.getJSONObject(0);
    Object rawPriceListId = firstOrder.opt("priceListId");
    String priceListId = (rawPriceListId == null || JSONObject.NULL.equals(rawPriceListId))
        ? null : rawPriceListId.toString();
    if (priceListId == null || priceListId.isEmpty()) {
      return false;
    }
    Object rawPriceListName = firstOrder.opt("priceList$_identifier");
    Object priceListName = (rawPriceListName == null || JSONObject.NULL.equals(rawPriceListName))
        ? JSONObject.NULL : rawPriceListName.toString();
    rec.put(FIELD_RESOLVED_PRICE_LIST_ID, priceListId);
    rec.put(FIELD_RESOLVED_PRICE_LIST_IDENTIFIER, priceListName);
    return true;
  }

  private boolean applyPriceListFromBusinessPartner(JSONObject rec, String receiptId)
      throws JSONException {
    try {
      OBContext.setAdminMode(true);
      ShipmentInOut receipt = OBDal.getReadOnlyInstance().get(ShipmentInOut.class, receiptId);
      if (receipt == null || receipt.getBusinessPartner() == null) {
        return false;
      }
      PriceList priceList = receipt.getBusinessPartner().getPurchasePricelist();
      if (priceList != null) {
        rec.put(FIELD_RESOLVED_PRICE_LIST_ID, priceList.getId());
        rec.put(FIELD_RESOLVED_PRICE_LIST_IDENTIFIER, priceList.getName());
        return true;
      }
      return false;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Tier-3 fallback of {@link #enrichResolvedPriceList}: the client's own DEFAULT purchase
   * price list, reusing {@code MultiDocumentInvoiceSupport}'s lookup rather than a second copy
   * of the same Criteria query.
   */
  private void applyClientDefaultPriceList(JSONObject rec) throws JSONException {
    try {
      OBContext.setAdminMode(true);
      PriceList priceList = MultiDocumentInvoiceSupport.findDefaultPriceList(false);
      if (priceList != null) {
        rec.put(FIELD_RESOLVED_PRICE_LIST_ID, priceList.getId());
        rec.put(FIELD_RESOLVED_PRICE_LIST_IDENTIFIER, priceList.getName());
      }
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
