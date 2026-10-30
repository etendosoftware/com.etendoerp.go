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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Post-hook for the warehouse {@code productTransactions} entity (M_Transaction).
 *
 * <p>Each inventory transaction is linked to exactly one source-document line
 * (goods shipment/receipt, goods movement, or physical inventory). The frontend
 * renders a navigable "Document" column that must open the related document
 * window at its header record. The transaction row only carries the <em>line</em>
 * FK, while the window route needs the <em>header</em> id, so this handler resolves
 * line&rarr;header server-side and injects, per row:
 *
 * <ul>
 *   <li>{@code etgoDocHeaderId} &mdash; the header record id to navigate to.</li>
 *   <li>{@code etgoDocWindow} &mdash; the GO window key for that header
 *       ({@code goods-shipment}, {@code goods-receipt}, {@code return-material-receipt},
 *       {@code return-to-vendor-shipment}, {@code goods-movements},
 *       {@code physical-inventory} or {@code internal-consumption}).</li>
 *   <li>{@code etgoDocLabel} &mdash; the header's existing document number, used as the
 *       link label (read straight from the header column; nothing is computed).</li>
 * </ul>
 *
 * <p>Movement types with no GO window yet (production) are left without these
 * fields, so the frontend renders them as plain, non-navigable text. A single
 * batched native query resolves every row in the page.
 *
 * <p>It also serves the {@code transactions} entity of the Product window (the "Movements" tab,
 * ETP-5889), where it adds one more field per row:
 *
 * <ul>
 *   <li>{@code etgoStockAfter} &mdash; the stock of the product, across every warehouse, once that
 *       movement is applied. It is a running sum of {@code MovementQty} over the whole history of
 *       the product, in the order movement date, processing time, id, so it does not depend on the
 *       filters, the sort or the page of the list. M_Transaction has no such column, and a field
 *       injected here cannot be filtered or sorted, which is why the tab does not offer to.</li>
 *   <li>{@code response.etgoTotalRows} &mdash; the exact number of movements of the product, only when
 *       the request has no criteria. NEO's own {@code totalRows} is a lower bound on a partial page
 *       (the rows returned plus one), so the tab counter cannot read it.</li>
 * </ul>
 *
 * <p>All of that is behind {@link ProductMovementsPolicy} (the {@code product-movements-tab} flag,
 * evaluated per account): off, the handler adds the document fields only, exactly as before ETP-5889.
 *
 * <p>Both only count what the user can read: the native queries filter by the readable
 * organizations of the session, which a native query would otherwise bypass.
 *
 * <p>The stock is resolved apart from the document: if it fails, the rows keep their document
 * fields and simply carry no {@code etgoStockAfter} (the tab shows a dash, never a zero).
 */
@Named("productTransactionsHandler")
public class ProductTransactionsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ProductTransactionsHandler.class);

  // tx id -> [headerId, windowKey, docLabel]; resolved in one batched query.
  private static final String RESOLVE_SQL =
      "SELECT t.m_transaction_id AS tx_id, "
      + "  COALESCE(io.m_inout_id, mv.m_movement_id, inv.m_inventory_id, ic.m_internal_consumption_id) AS header_id, "
      + "  CASE "
      + "    WHEN t.m_inoutline_id IS NOT NULL AND io.issotrx = 'Y' AND dt.isreturn = 'N' THEN 'goods-shipment' "
      + "    WHEN t.m_inoutline_id IS NOT NULL AND io.issotrx = 'Y' AND dt.isreturn = 'Y' THEN 'return-material-receipt' "
      + "    WHEN t.m_inoutline_id IS NOT NULL AND io.issotrx = 'N' AND dt.isreturn = 'N' THEN 'goods-receipt' "
      + "    WHEN t.m_inoutline_id IS NOT NULL AND io.issotrx = 'N' AND dt.isreturn = 'Y' THEN 'return-to-vendor-shipment' "
      + "    WHEN t.m_movementline_id IS NOT NULL THEN 'goods-movements' "
      + "    WHEN t.m_inventoryline_id IS NOT NULL THEN 'physical-inventory' "
      + "    WHEN t.m_internal_consumptionline_id IS NOT NULL THEN 'internal-consumption' "
      + "    ELSE NULL "
      + "  END AS window_key, "
      // m_inventory and m_internal_consumption have no documentno column; their identifier is `name`.
      + "  COALESCE(io.documentno, mv.documentno, inv.name, ic.name) AS doc_label "
      + "FROM m_transaction t "
      + "  LEFT JOIN m_inoutline iol ON iol.m_inoutline_id = t.m_inoutline_id "
      + "  LEFT JOIN m_inout io ON io.m_inout_id = iol.m_inout_id "
      + "  LEFT JOIN c_doctype dt ON dt.c_doctype_id = io.c_doctype_id "
      + "  LEFT JOIN m_movementline ml ON ml.m_movementline_id = t.m_movementline_id "
      + "  LEFT JOIN m_movement mv ON mv.m_movement_id = ml.m_movement_id "
      + "  LEFT JOIN m_inventoryline invl ON invl.m_inventoryline_id = t.m_inventoryline_id "
      + "  LEFT JOIN m_inventory inv ON inv.m_inventory_id = invl.m_inventory_id "
      + "  LEFT JOIN m_internal_consumptionline icl ON icl.m_internal_consumptionline_id = t.m_internal_consumptionline_id "
      + "  LEFT JOIN m_internal_consumption ic ON ic.m_internal_consumption_id = icl.m_internal_consumption_id "
      + "WHERE t.m_transaction_id IN (:ids)";

  // tx id -> stock of the product after that movement (all warehouses) and stock of the movement's own
  // warehouse after it; the caller picks one by whether the list is filtered by warehouse. The window runs over the history of the
  // products in the page up to the newest movement date of the page (a movement after every row of the
  // page cannot change their running sum), then only the page rows are kept. The order key ends
  // in the transaction id, so two movements never tie and the sum is deterministic.
  private static final String STOCK_AFTER_SQL =
      "SELECT s.m_transaction_id AS tx_id, s.stock_after AS stock_after, "
      + "  s.stock_after_warehouse AS stock_after_warehouse "
      + "FROM ( "
      + "  SELECT t.m_transaction_id, "
      + "    SUM(t.movementqty) OVER (PARTITION BY t.m_product_id "
      + "      ORDER BY t.movementdate, t.trxprocessdate, t.m_transaction_id) AS stock_after, "
      + "    SUM(t.movementqty) OVER (PARTITION BY t.m_product_id, t.em_etgo_warehouse_id "
      + "      ORDER BY t.movementdate, t.trxprocessdate, t.m_transaction_id) AS stock_after_warehouse "
      + "  FROM m_transaction t "
      + "  WHERE t.m_product_id IN (SELECT p.m_product_id FROM m_transaction p WHERE p.m_transaction_id IN (:ids)) "
      + "    AND t.ad_org_id IN (:orgs) "
      + "    AND t.movementdate <= (SELECT MAX(q.movementdate) FROM m_transaction q WHERE q.m_transaction_id IN (:ids)) "
      + ") s "
      + "WHERE s.m_transaction_id IN (:ids)";

  // Every movement of a product the session can read; the exact total the list cannot report.
  private static final String TOTAL_ROWS_SQL =
      "SELECT COUNT(*) FROM m_transaction t WHERE t.m_product_id = :productId AND t.ad_org_id IN (:orgs)";

  @Override
  public NeoResponse handle(NeoContext context) {
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    try {
      NeoResponse previousResult = context.getPreviousResult();
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null || previousResult == null) {
        return null;
      }
      List<String> txIds = NeoHandlerUtils.collectIds(dataArr);
      if (txIds.isEmpty()) {
        return null;
      }
      Map<String, String[]> targets = resolveDocumentTargets(txIds);
      // The extra work of the Product "Movements" tab (ETP-5889) runs only for the Product window and
      // only for an account the flag is on for: the Warehouse window shares this handler and has no use
      // for it, and an account that is off must not pay for it on the requests the side panel makes.
      boolean movementsTab = isMovementsTab(context);
      // The tab also asks for a single row only to read etgoTotalRows; that row needs no running sum.
      Map<String, BigDecimal> stockAfter = movementsTab && !isCountOnly(context)
          ? resolveStockAfter(txIds, isFilteredByWarehouse(context)) : new HashMap<>();
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String id = rec.optString("id", null);
        String[] target = targets.get(id);
        if (target != null && target[0] != null && target[1] != null) {
          rec.put("etgoDocHeaderId", target[0]);
          rec.put("etgoDocWindow", target[1]);
          if (target[2] != null) {
            rec.put("etgoDocLabel", target[2]);
          }
        }
        BigDecimal stock = stockAfter.get(id);
        if (stock != null) {
          rec.put("etgoStockAfter", stock);
        }
      }
      if (movementsTab) {
        addTotalRows(context, previousResult.getBody());
      }
      return NeoResponse.ok(previousResult.getBody());
    } catch (Exception e) {
      log.error("Error enriching product transactions with document targets", e);
      return context.getPreviousResult();
    }
  }

  /**
   * Resolves, for each transaction id, the stock of its product once the movement is applied.
   *
   * <p>Never throws: a failure here must not cost the rows their document fields, so it is logged and
   * an empty map is returned. A row with no value, or an unreadable one, is left out rather than
   * reported as zero stock.
   *
   * @param txIds the M_Transaction ids visible in the current page
   * @param perWarehouse {@code true} when the list is filtered by warehouse: the stock of that
   *     warehouse after each movement instead of the stock of the product as a whole
   * @return a map of transaction id to the stock after that movement
   */
  private Map<String, BigDecimal> resolveStockAfter(List<String> txIds, boolean perWarehouse) {
    Map<String, BigDecimal> result = new HashMap<>();
    try {
      OBContext.setAdminMode(true);
      try {
        @SuppressWarnings("unchecked")
        NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(STOCK_AFTER_SQL);
        query.setParameterList("ids", txIds);
        query.setParameterList("orgs", readableOrganizations());
        for (Object[] row : query.list()) {
          BigDecimal stock = toStock(perWarehouse ? row[2] : row[1]);
          if (stock != null) {
            result.put((String) row[0], stock);
          }
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error resolving the stock after each product transaction", e);
      return new HashMap<>();
    }
    return result;
  }

  /**
   * Whether this request is served for the Product "Movements" tab: the Product window's spec, for an
   * account the {@code product-movements-tab} flag is on for ({@link ProductMovementsPolicy}).
   */
  private static boolean isMovementsTab(NeoContext context) {
    return "product".equals(context.getSpecName()) && ProductMovementsPolicy.isEnabled();
  }

  /**
   * Whether the list is filtered by warehouse: its {@code criteria} carry an {@code etgoWarehouse}
   * equality. Then the resulting stock is the one of that warehouse, so it agrees with the rows shown.
   * Anything that cannot be read as that criterion means "not filtered" (the stock of the product).
   */
  private static boolean isFilteredByWarehouse(NeoContext context) {
    Map<String, String> params = context.getQueryParams();
    String criteria = params == null ? null : params.get("criteria");
    if (StringUtils.isBlank(criteria)) {
      return false;
    }
    try {
      JSONArray list = new JSONArray(criteria);
      for (int i = 0; i < list.length(); i++) {
        JSONObject criterion = list.optJSONObject(i);
        if (criterion != null && "etgoWarehouse".equals(criterion.optString("fieldName"))
            && "equals".equals(criterion.optString("operator"))
            && StringUtils.isNotBlank(criterion.optString("value"))) {
          return true;
        }
      }
    } catch (Exception e) {
      log.debug("Unreadable criteria; the resulting stock stays the one of the product", e);
    }
    return false;
  }

  /**
   * Whether the request is the tab's counter probe: one row ({@code _endRow=0}), asked only to read
   * {@code etgoTotalRows}. A page of the list is never this small.
   */
  private static boolean isCountOnly(NeoContext context) {
    Map<String, String> params = context.getQueryParams();
    return params != null && "0".equals(params.get("_endRow"));
  }

  /**
   * Adds {@code etgoTotalRows} to the response of a Product-window request that has no criteria and
   * names its product, so the tab can show how many movements the product has. Never throws: without
   * it the tab simply shows no counter.
   */
  private void addTotalRows(NeoContext context, JSONObject body) {
    try {
      Map<String, String> params = context.getQueryParams();
      if (params == null || StringUtils.isNotBlank(params.get("criteria"))) {
        return;
      }
      String productId = params.get("parentId");
      JSONObject response = body == null ? null : body.optJSONObject("response");
      if (StringUtils.isBlank(productId) || response == null) {
        return;
      }
      OBContext.setAdminMode(true);
      try {
        @SuppressWarnings("unchecked")
        NativeQuery<Object> query = OBDal.getInstance().getSession().createNativeQuery(TOTAL_ROWS_SQL);
        query.setParameter("productId", productId);
        query.setParameterList("orgs", readableOrganizations());
        Object total = query.uniqueResult();
        if (total instanceof Number) {
          response.put("etgoTotalRows", ((Number) total).longValue());
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error counting the movements of the product", e);
    }
  }

  /** Organizations the session can read: a native query does not apply the DAL read restrictions. */
  private static List<String> readableOrganizations() {
    return Arrays.asList(OBContext.getOBContext().getReadableOrganizations());
  }

  /** Null for a missing or unreadable value: unlike {@code ProductHandlerUtils.toBigDecimal}, never 0. */
  private static BigDecimal toStock(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    try {
      return new BigDecimal(value.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Resolves, for each transaction id, the source document header id and its GO window key.
   *
   * @param txIds the M_Transaction ids visible in the current page
   * @return a map of transaction id to {@code [headerId, windowKey, docLabel]}; entries
   *         without a navigable target (production) are simply absent
   */
  private Map<String, String[]> resolveDocumentTargets(List<String> txIds) {
    Map<String, String[]> result = new HashMap<>();
    OBContext.setAdminMode(true);
    try {
      @SuppressWarnings("unchecked")
      NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(RESOLVE_SQL);
      query.setParameterList("ids", txIds);
      for (Object[] row : query.list()) {
        String txId = (String) row[0];
        String headerId = (String) row[1];
        String windowKey = (String) row[2];
        String docLabel = (String) row[3];
        if (headerId != null && windowKey != null) {
          result.put(txId, new String[] { headerId, windowKey, docLabel });
        }
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    return result;
  }
}
