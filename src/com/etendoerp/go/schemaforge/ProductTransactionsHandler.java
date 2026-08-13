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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;

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
 *       {@code return-to-vendor-shipment}, {@code goods-movements}
 *       or {@code physical-inventory}).</li>
 *   <li>{@code etgoDocLabel} &mdash; the header's existing document number, used as the
 *       link label (read straight from the header column; nothing is computed).</li>
 * </ul>
 *
 * <p>Movement types with no GO window yet (production, internal consumption) are
 * left without these fields, so the frontend renders them as plain, non-navigable
 * text. A single batched native query resolves every row in the page.
 */
@Named("productTransactionsHandler")
public class ProductTransactionsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ProductTransactionsHandler.class);

  // tx id -> [headerId, windowKey, docLabel]; resolved in one batched query.
  private static final String RESOLVE_SQL =
      "SELECT t.m_transaction_id AS tx_id, "
      + "  COALESCE(io.m_inout_id, mv.m_movement_id, inv.m_inventory_id) AS header_id, "
      + "  CASE "
      + "    WHEN t.m_inoutline_id IS NOT NULL AND io.issotrx = 'Y' AND dt.isreturn = 'N' THEN 'goods-shipment' "
      + "    WHEN t.m_inoutline_id IS NOT NULL AND io.issotrx = 'Y' AND dt.isreturn = 'Y' THEN 'return-material-receipt' "
      + "    WHEN t.m_inoutline_id IS NOT NULL AND io.issotrx = 'N' AND dt.isreturn = 'N' THEN 'goods-receipt' "
      + "    WHEN t.m_inoutline_id IS NOT NULL AND io.issotrx = 'N' AND dt.isreturn = 'Y' THEN 'return-to-vendor-shipment' "
      + "    WHEN t.m_movementline_id IS NOT NULL THEN 'goods-movements' "
      + "    WHEN t.m_inventoryline_id IS NOT NULL THEN 'physical-inventory' "
      + "    ELSE NULL "
      + "  END AS window_key, "
      // m_inventory has no documentno column; its identifier is `name`.
      + "  COALESCE(io.documentno, mv.documentno, inv.name) AS doc_label "
      + "FROM m_transaction t "
      + "  LEFT JOIN m_inoutline iol ON iol.m_inoutline_id = t.m_inoutline_id "
      + "  LEFT JOIN m_inout io ON io.m_inout_id = iol.m_inout_id "
      + "  LEFT JOIN c_doctype dt ON dt.c_doctype_id = io.c_doctype_id "
      + "  LEFT JOIN m_movementline ml ON ml.m_movementline_id = t.m_movementline_id "
      + "  LEFT JOIN m_movement mv ON mv.m_movement_id = ml.m_movement_id "
      + "  LEFT JOIN m_inventoryline invl ON invl.m_inventoryline_id = t.m_inventoryline_id "
      + "  LEFT JOIN m_inventory inv ON inv.m_inventory_id = invl.m_inventory_id "
      + "WHERE t.m_transaction_id IN (:ids)";

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
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String[] target = targets.get(rec.optString("id", null));
        if (target != null && target[0] != null && target[1] != null) {
          rec.put("etgoDocHeaderId", target[0]);
          rec.put("etgoDocWindow", target[1]);
          if (target[2] != null) {
            rec.put("etgoDocLabel", target[2]);
          }
        }
      }
      return NeoResponse.ok(previousResult.getBody());
    } catch (Exception e) {
      log.error("Error enriching product transactions with document targets", e);
      return context.getPreviousResult();
    }
  }

  /**
   * Resolves, for each transaction id, the source document header id and its GO window key.
   *
   * @param txIds the M_Transaction ids visible in the current page
   * @return a map of transaction id to {@code [headerId, windowKey, docLabel]}; entries
   *         without a navigable target (production, internal consumption) are simply absent
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
