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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.HashMap;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.cost.Costing;
import org.openbravo.model.materialmgmt.onhandquantity.StorageDetail;

/**
 * Unit tests for {@link CostingValuationHandler}.
 *
 * <p>Coverage goals:
 * <ul>
 *   <li>Invalid events are ignored.</li>
 *   <li>Non-permanent or inactive Costing records are skipped.</li>
 *   <li>Null product or null cost short-circuits the handler.</li>
 *   <li>First event for a product executes the native UPDATE.</li>
 *   <li>A subsequent event with a LOWER cumstock is skipped (older record loses).</li>
 *   <li>A subsequent event with a HIGHER cumstock re-runs the UPDATE (newer wins).</li>
 *   <li>A subsequent event with EQUAL cumstock is skipped.</li>
 *   <li>Exceptions inside the UPDATE block are swallowed (no re-throw).</li>
 * </ul>
 */
class CostingValuationHandlerTest {

  // ---------------------------------------------------------------------------
  // Thread-local reset between tests
  // ---------------------------------------------------------------------------

  /**
   * Clears the per-thread cumstock map so tests do not bleed into each other.
   * Uses reflection to reach the private static ThreadLocal.
   */
  @BeforeEach
  void resetThreadLocal() throws Exception {
    Field field = CostingValuationHandler.class.getDeclaredField("MAX_CUMSTOCK_BY_PRODUCT");
    field.setAccessible(true);
    @SuppressWarnings("unchecked") ThreadLocal<HashMap<String, BigDecimal>> tl = (ThreadLocal<HashMap<String, BigDecimal>>) field.get(
        null);
    tl.get().clear();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private CostingValuationHandler newHandler() {
    return new CostingValuationHandler() {
      @Override
      protected boolean isValidEvent(EntityPersistenceEvent e) {
        return true;
      }
    };
  }

  private CostingValuationHandler newInvalidHandler() {
    return new CostingValuationHandler() {
      @Override
      protected boolean isValidEvent(EntityPersistenceEvent e) {
        return false;
      }
    };
  }

  private void withModelProvider(Runnable body) {
    try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
      ModelProvider mockMp = mock(ModelProvider.class);
      when(mockMp.getEntity(Costing.ENTITY_NAME)).thenReturn(mock(Entity.class));
      mp.when(ModelProvider::getInstance).thenReturn(mockMp);
      body.run();
    }
  }

  @SuppressWarnings("unchecked")
  private NativeQuery<Object> mockUpdateQuery(Session session) {
    NativeQuery<Object> query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(1);
    return query;
  }

  private Costing permanentActiveCosting(String productId, BigDecimal cost, BigDecimal cumstock) {
    Product product = mock(Product.class);
    when(product.getId()).thenReturn(productId);

    Costing costing = mock(Costing.class);
    when(costing.isPermanent()).thenReturn(Boolean.TRUE);
    when(costing.isActive()).thenReturn(Boolean.TRUE);
    when(costing.getProduct()).thenReturn(product);
    when(costing.getCost()).thenReturn(cost);
    when(costing.getTotalMovementQuantity()).thenReturn(cumstock);
    return costing;
  }

  // ---------------------------------------------------------------------------
  // Invalid events
  // ---------------------------------------------------------------------------

  @Test
  void onNew_invalidEvent_doesNothing() {
    withModelProvider(() -> {
      EntityNewEvent event = mock(EntityNewEvent.class);
      assertDoesNotThrow(() -> newInvalidHandler().onNew(event));
      verify(event, never()).getTargetInstance();
    });
  }

  @Test
  void onUpdate_invalidEvent_doesNothing() {
    withModelProvider(() -> {
      EntityUpdateEvent event = mock(EntityUpdateEvent.class);
      assertDoesNotThrow(() -> newInvalidHandler().onUpdate(event));
      verify(event, never()).getTargetInstance();
    });
  }

  // ---------------------------------------------------------------------------
  // Guard: not permanent
  // ---------------------------------------------------------------------------

  @Test
  void handleCostingChange_notPermanent_skips() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
          OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

        Costing costing = mock(Costing.class);
        when(costing.isPermanent()).thenReturn(Boolean.FALSE);
        when(costing.isActive()).thenReturn(Boolean.TRUE);

        EntityNewEvent event = mock(EntityNewEvent.class);
        when(event.getTargetInstance()).thenReturn(costing);

        assertDoesNotThrow(() -> newHandler().onNew(event));
        obDal.verifyNoInteractions();
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Guard: not active
  // ---------------------------------------------------------------------------

  @Test
  void handleCostingChange_notActive_skips() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
          OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

        Costing costing = mock(Costing.class);
        when(costing.isPermanent()).thenReturn(Boolean.TRUE);
        when(costing.isActive()).thenReturn(Boolean.FALSE);

        EntityUpdateEvent event = mock(EntityUpdateEvent.class);
        when(event.getTargetInstance()).thenReturn(costing);

        assertDoesNotThrow(() -> newHandler().onUpdate(event));
        obDal.verifyNoInteractions();
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Guard: null product
  // ---------------------------------------------------------------------------

  @Test
  void handleCostingChange_nullProduct_skips() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
          OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

        Costing costing = mock(Costing.class);
        when(costing.isPermanent()).thenReturn(Boolean.TRUE);
        when(costing.isActive()).thenReturn(Boolean.TRUE);
        when(costing.getProduct()).thenReturn(null);

        EntityNewEvent event = mock(EntityNewEvent.class);
        when(event.getTargetInstance()).thenReturn(costing);

        assertDoesNotThrow(() -> newHandler().onNew(event));
        obDal.verifyNoInteractions();
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Guard: null cost
  // ---------------------------------------------------------------------------

  @Test
  void handleCostingChange_nullCost_skips() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
          OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

        Costing costing = mock(Costing.class);
        when(costing.isPermanent()).thenReturn(Boolean.TRUE);
        when(costing.isActive()).thenReturn(Boolean.TRUE);
        when(costing.getProduct()).thenReturn(mock(Product.class));
        when(costing.getCost()).thenReturn(null);

        EntityUpdateEvent event = mock(EntityUpdateEvent.class);
        when(event.getTargetInstance()).thenReturn(costing);

        assertDoesNotThrow(() -> newHandler().onUpdate(event));
        obDal.verifyNoInteractions();
      }
    });
  }

  // ---------------------------------------------------------------------------
  // First event for a product → UPDATE is executed
  // ---------------------------------------------------------------------------

  @Test
  void handleCostingChange_firstEventForProduct_executesUpdate() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
          OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

        OBDal mockDal = mock(OBDal.class);
        Session session = mock(Session.class);
        when(mockDal.getSession()).thenReturn(session);
        obDal.when(OBDal::getInstance).thenReturn(mockDal);
        NativeQuery<Object> query = mockUpdateQuery(session);

        Costing costing = permanentActiveCosting("PROD-001", new BigDecimal("98.66"), new BigDecimal("2280"));
        EntityNewEvent event = mock(EntityNewEvent.class);
        when(event.getTargetInstance()).thenReturn(costing);

        assertDoesNotThrow(() -> newHandler().onNew(event));
        verify(query).setParameter(eq("cost"), eq(new BigDecimal("98.66")));
        verify(query).setParameter(eq("productId"), eq("PROD-001"));
        verify(query).executeUpdate();
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Second event, LOWER cumstock → skipped (older record loses)
  // ---------------------------------------------------------------------------

  @Test
  void handleCostingChange_lowerCumstock_skipsUpdate() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
          OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

        OBDal mockDal = mock(OBDal.class);
        Session session = mock(Session.class);
        when(mockDal.getSession()).thenReturn(session);
        obDal.when(OBDal::getInstance).thenReturn(mockDal);
        NativeQuery<Object> query = mockUpdateQuery(session);

        CostingValuationHandler handler = newHandler();

        // First event: cumstock 2280 → applied
        Costing first = permanentActiveCosting("PROD-001", new BigDecimal("98.66"), new BigDecimal("2280"));
        EntityNewEvent ev1 = mock(EntityNewEvent.class);
        when(ev1.getTargetInstance()).thenReturn(first);
        handler.onNew(ev1);

        // Second event: cumstock 1780 (older) → should be skipped
        Costing older = permanentActiveCosting("PROD-001", new BigDecimal("98.71"), new BigDecimal("1780"));
        EntityUpdateEvent ev2 = mock(EntityUpdateEvent.class);
        when(ev2.getTargetInstance()).thenReturn(older);
        handler.onUpdate(ev2);

        // executeUpdate called exactly once (for the first event only)
        verify(query, Mockito.times(1)).executeUpdate();
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Second event, HIGHER cumstock → UPDATE re-runs (newer record wins)
  // ---------------------------------------------------------------------------

  @Test
  void handleCostingChange_higherCumstock_executesSecondUpdate() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
          OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

        OBDal mockDal = mock(OBDal.class);
        Session session = mock(Session.class);
        when(mockDal.getSession()).thenReturn(session);
        obDal.when(OBDal::getInstance).thenReturn(mockDal);
        NativeQuery<Object> query = mockUpdateQuery(session);

        CostingValuationHandler handler = newHandler();

        // First event: cumstock 1780
        Costing first = permanentActiveCosting("PROD-001", new BigDecimal("98.71"), new BigDecimal("1780"));
        EntityNewEvent ev1 = mock(EntityNewEvent.class);
        when(ev1.getTargetInstance()).thenReturn(first);
        handler.onNew(ev1);

        // Second event: cumstock 2280 (newer) → should execute again
        Costing newer = permanentActiveCosting("PROD-001", new BigDecimal("98.66"), new BigDecimal("2280"));
        EntityUpdateEvent ev2 = mock(EntityUpdateEvent.class);
        when(ev2.getTargetInstance()).thenReturn(newer);
        handler.onUpdate(ev2);

        // executeUpdate called twice, second with the newer cost
        verify(query, Mockito.times(2)).executeUpdate();
        verify(query).setParameter(eq("cost"), eq(new BigDecimal("98.66")));
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Second event, EQUAL cumstock → skipped
  // ---------------------------------------------------------------------------

  @Test
  void handleCostingChange_equalCumstock_skipsSecondUpdate() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
          OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

        OBDal mockDal = mock(OBDal.class);
        Session session = mock(Session.class);
        when(mockDal.getSession()).thenReturn(session);
        obDal.when(OBDal::getInstance).thenReturn(mockDal);
        NativeQuery<Object> query = mockUpdateQuery(session);

        CostingValuationHandler handler = newHandler();

        Costing first = permanentActiveCosting("PROD-001", new BigDecimal("98.66"), new BigDecimal("2280"));
        EntityNewEvent ev1 = mock(EntityNewEvent.class);
        when(ev1.getTargetInstance()).thenReturn(first);
        handler.onNew(ev1);

        Costing same = permanentActiveCosting("PROD-001", new BigDecimal("98.66"), new BigDecimal("2280"));
        EntityUpdateEvent ev2 = mock(EntityUpdateEvent.class);
        when(ev2.getTargetInstance()).thenReturn(same);
        handler.onUpdate(ev2);

        verify(query, Mockito.times(1)).executeUpdate();
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Exception inside UPDATE → swallowed, no re-throw
  // ---------------------------------------------------------------------------

  @Test
  void handleCostingChange_exceptionInUpdate_doesNotRethrow() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
          OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

        OBDal mockDal = mock(OBDal.class);
        Session session = mock(Session.class);
        when(mockDal.getSession()).thenReturn(session);
        obDal.when(OBDal::getInstance).thenReturn(mockDal);

        @SuppressWarnings("unchecked") NativeQuery<Object> query = mock(NativeQuery.class);
        when(session.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenThrow(new RuntimeException("DB error"));

        Costing costing = permanentActiveCosting("PROD-001", new BigDecimal("98.66"), new BigDecimal("2280"));
        EntityNewEvent event = mock(EntityNewEvent.class);
        when(event.getTargetInstance()).thenReturn(costing);

        assertDoesNotThrow(() -> newHandler().onNew(event));
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Different products are tracked independently
  // ---------------------------------------------------------------------------

  @Test
  void handleCostingChange_differentProducts_eachExecutesUpdate() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(
          OBDal.class); MockedStatic<OBContext> obCtx = Mockito.mockStatic(OBContext.class)) {

        OBDal mockDal = mock(OBDal.class);
        Session session = mock(Session.class);
        when(mockDal.getSession()).thenReturn(session);
        obDal.when(OBDal::getInstance).thenReturn(mockDal);
        NativeQuery<Object> query = mockUpdateQuery(session);

        CostingValuationHandler handler = newHandler();

        Costing c1 = permanentActiveCosting("PROD-001", new BigDecimal("98.66"), new BigDecimal("2280"));
        EntityNewEvent ev1 = mock(EntityNewEvent.class);
        when(ev1.getTargetInstance()).thenReturn(c1);
        handler.onNew(ev1);

        Costing c2 = permanentActiveCosting("PROD-002", new BigDecimal("35.80"), new BigDecimal("1000"));
        EntityNewEvent ev2 = mock(EntityNewEvent.class);
        when(ev2.getTargetInstance()).thenReturn(c2);
        handler.onNew(ev2);

        // One UPDATE per product
        verify(query, Mockito.times(2)).executeUpdate();
      }
    });
  }
}
