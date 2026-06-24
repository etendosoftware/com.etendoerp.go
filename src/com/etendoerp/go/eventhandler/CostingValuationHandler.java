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

package com.etendoerp.go.eventhandler;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import javax.enterprise.event.Observes;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.query.NativeQuery;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.cost.Costing;

/**
 * When the costing engine writes a new current cost for a product, recalculates
 * em_etgo_valuation for all M_Storage_Detail rows of that product so valuations stay
 * current even though costing runs asynchronously (deferred, after the stock movement).
 *
 * <p>The costing engine ({@code CostingBackground}) writes several M_Costing rows per
 * product in a single run, and our handler fires on each one — while the engine still
 * holds M_Transaction collections in its Hibernate session. Two design choices keep this
 * safe and correct:
 *
 * <ul>
 *   <li><b>Cost is read from the event record itself</b> ({@code costing.getCost()}), not
 *       from a query. A query could miss the just-written, not-yet-flushed row, and would
 *       force a session auto-flush that corrupts the engine's session ("Found shared
 *       references to a collection").</li>
 *   <li><b>Highest cumstock wins</b> (per-thread guard, {@link #MAX_CUMSTOCK_BY_PRODUCT}).
 *       In Average costing the unit cost only changes on inbound movements, so the most
 *       recent cost is always the row with the greatest Total Movement Quantity (cumstock).
 *       Tracking the max seen per product makes the newest cost win regardless of the order
 *       in which the engine fires the events.</li>
 * </ul>
 */
public class CostingValuationHandler extends EntityPersistenceEventObserver {

  private static final Logger LOG = LogManager.getLogger();
  // Per-thread: greatest cumstock already applied per product in the current costing run.
  // The engine writes many M_Costing rows; only the newest (highest cumstock) should win.
  private static final ThreadLocal<Map<String, BigDecimal>> MAX_CUMSTOCK_BY_PRODUCT = ThreadLocal.withInitial(
      HashMap::new);
  private static final Entity[] ENTITIES = { ModelProvider.getInstance().getEntity(Costing.ENTITY_NAME) };

  @Override
  protected Entity[] getObservedEntities() {
    return ENTITIES;
  }

  public void onNew(@Observes EntityNewEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    handleCostingChange((Costing) event.getTargetInstance());
  }

  public void onUpdate(@Observes EntityUpdateEvent event) {
    if (!isValidEvent(event)) {
      return;
    }
    handleCostingChange((Costing) event.getTargetInstance());
  }

  private void handleCostingChange(Costing costing) {
    // Only the permanent active record with a still-open end date defines the current cost.
    if (!Boolean.TRUE.equals(costing.isPermanent()) || !Boolean.TRUE.equals(costing.isActive())) {
      return;
    }

    Product product = costing.getProduct();
    BigDecimal cost = costing.getCost();
    if (product == null || cost == null) {
      return;
    }

    String productId = product.getId();
    BigDecimal cumstock = costing.getTotalMovementQuantity();
    if (cumstock == null) {
      cumstock = BigDecimal.ZERO;
    }

    // Newest cost wins: skip this event if a row with an equal-or-greater cumstock
    // was already applied for this product in the current run.
    Map<String, BigDecimal> seen = MAX_CUMSTOCK_BY_PRODUCT.get();
    BigDecimal appliedCumstock = seen.get(productId);
    if (appliedCumstock != null && appliedCumstock.compareTo(cumstock) >= 0) {
      return;
    }
    seen.put(productId, cumstock);

    OBContext.setAdminMode(true);
    try {
      // Native UPDATE: does not touch managed entities, so it cannot trigger the costing
      // engine's session flush (avoids "Found shared references to a collection").
      NativeQuery<?> update = OBDal.getInstance().getSession().createNativeQuery(
          "UPDATE m_storage_detail SET em_etgo_valuation = " + "CASE WHEN qtyonhand > 0 THEN qtyonhand * :cost ELSE 0 END " + "WHERE m_product_id = :productId");
      update.setParameter("cost", cost);
      update.setParameter("productId", productId);
      int rows = update.executeUpdate();
      LOG.debug("Recalculated valuation for {} storage detail rows of product {} at cost {}", rows, productId, cost);
    } catch (Exception e) {
      LOG.error("Error recalculating valuations after costing change for product {}: {}", productId, e.getMessage(), e);
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
