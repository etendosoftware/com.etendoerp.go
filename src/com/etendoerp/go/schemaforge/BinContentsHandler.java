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
 * Post-hook for the warehouse {@code binContents} entity (M_Storage_Detail).
 *
 * <p>Computes {@code etgoValuation = qtyonhand × current cost} live for every row at
 * read time, instead of relying on the persisted {@code em_etgo_valuation} column.
 *
 * <p><b>Why read-time:</b> stock leaves the warehouse through outbound documents
 * (shipments, consumptions) that (a) do not create an {@code M_Costing} row and
 * (b) update {@code M_Storage_Detail.qtyonhand} via the {@code M_Update_Inventory}
 * database routine rather than the Java DAL. As a result neither the costing-event
 * handler nor a storage-detail event handler fires on a sale, leaving the persisted
 * column stale. Computing the valuation here — from the current quantity on hand and
 * the current cost — is always correct regardless of how stock moved.
 *
 * <p>The "current cost" is the permanent, active, not-yet-expired {@code M_Costing}
 * row with the most recent starting date (matching the catalog's costing convention).
 * Products without such a row resolve to a zero valuation. A single batched native
 * query values every row in the page.
 */
@Named("binContentsHandler")
public class BinContentsHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(BinContentsHandler.class);

  // storage detail id -> qtyonhand × current cost; resolved in one batched query.
  private static final String VALUATION_SQL =
      "SELECT sd.m_storage_detail_id AS sd_id, "
      + "  sd.qtyonhand * COALESCE(( "
      + "    SELECT c.cost FROM m_costing c "
      + "    WHERE c.m_product_id = sd.m_product_id "
      + "      AND c.ispermanent = 'Y' AND c.isactive = 'Y' AND c.dateto > now() "
      + "    ORDER BY c.datefrom DESC LIMIT 1 "
      + "  ), 0) AS valuation "
      + "FROM m_storage_detail sd "
      + "WHERE sd.m_storage_detail_id IN (:ids)";

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
      List<String> ids = NeoHandlerUtils.collectIds(dataArr);
      if (ids.isEmpty()) {
        return null;
      }
      Map<String, BigDecimal> valuations = computeValuations(ids);
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        BigDecimal valuation = valuations.get(rec.optString("id", null));
        if (valuation != null) {
          rec.put("etgoValuation", valuation);
        }
      }
      return NeoResponse.ok(previousResult.getBody());
    } catch (Exception e) {
      log.error("Error enriching bin contents with valuation", e);
      return context.getPreviousResult();
    }
  }

  /**
   * Computes {@code qtyonhand × current cost} for each storage detail id in one query.
   *
   * @param ids the M_Storage_Detail ids visible in the current page
   * @return a map of storage detail id to its current valuation
   */
  private Map<String, BigDecimal> computeValuations(List<String> ids) {
    Map<String, BigDecimal> result = new HashMap<>();
    OBContext.setAdminMode(true);
    try {
      @SuppressWarnings("unchecked")
      NativeQuery<Object[]> query = OBDal.getInstance().getSession().createNativeQuery(VALUATION_SQL);
      query.setParameterList("ids", ids);
      for (Object[] row : query.list()) {
        String sdId = (String) row[0];
        BigDecimal valuation = (row[1] == null) ? BigDecimal.ZERO : new BigDecimal(row[1].toString());
        result.put(sdId, valuation);
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    return result;
  }
}
