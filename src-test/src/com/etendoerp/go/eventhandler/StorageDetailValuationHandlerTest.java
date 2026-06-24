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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEvent;
import org.openbravo.client.kernel.event.EntityUpdateEvent;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.materialmgmt.cost.Costing;
import org.openbravo.model.materialmgmt.onhandquantity.StorageDetail;

/**
 * Unit tests for {@link StorageDetailValuationHandler}.
 *
 * <p>Coverage goals:
 * <ul>
 *   <li>Invalid events are ignored.</li>
 *   <li>Non-positive quantity → valuation set to zero.</li>
 *   <li>Positive quantity, no current cost found → valuation set to zero.</li>
 *   <li>Positive quantity with current cost → valuation = qty × cost.</li>
 *   <li>Unexpected exception inside recalculate is swallowed (no re-throw).</li>
 *   <li>{@link StorageDetailValuationHandler#getCurrentCost} returns null when no results.</li>
 *   <li>{@link StorageDetailValuationHandler#getCurrentCost} returns cost when found.</li>
 * </ul>
 */
class StorageDetailValuationHandlerTest {

  private StorageDetailValuationHandler handler;

  @BeforeEach
  void setUp() {
    handler = newHandler();
  }

  // ---------------------------------------------------------------------------
  // Helper: subclass that always reports isValidEvent = true or false
  // ---------------------------------------------------------------------------

  private StorageDetailValuationHandler newHandler() {
    return new StorageDetailValuationHandler() {
      @Override
      protected boolean isValidEvent(EntityPersistenceEvent e) {
        return true;
      }
    };
  }

  private StorageDetailValuationHandler newInvalidHandler() {
    return new StorageDetailValuationHandler() {
      @Override
      protected boolean isValidEvent(EntityPersistenceEvent e) {
        return false;
      }
    };
  }

  private void withModelProvider(Runnable body) {
    try (MockedStatic<ModelProvider> mp = Mockito.mockStatic(ModelProvider.class)) {
      ModelProvider mockMp = mock(ModelProvider.class);
      Entity entity = mock(Entity.class);
      when(mockMp.getEntity(StorageDetail.ENTITY_NAME)).thenReturn(entity);
      mp.when(ModelProvider::getInstance).thenReturn(mockMp);
      body.run();
    }
  }

  // ---------------------------------------------------------------------------
  // onNew / onUpdate — invalid events are ignored
  // ---------------------------------------------------------------------------

  @Test
  void onNew_invalidEvent_doesNothing() {
    withModelProvider(() -> {
      StorageDetail detail = mock(StorageDetail.class);
      EntityNewEvent event = mock(EntityNewEvent.class);
      when(event.getTargetInstance()).thenReturn(detail);

      StorageDetailValuationHandler invalidHandler = newInvalidHandler();
      assertDoesNotThrow(() -> invalidHandler.onNew(event));
      verify(detail, never()).setEtgoValuation(any());
    });
  }

  @Test
  void onUpdate_invalidEvent_doesNothing() {
    withModelProvider(() -> {
      StorageDetail detail = mock(StorageDetail.class);
      EntityUpdateEvent event = mock(EntityUpdateEvent.class);
      when(event.getTargetInstance()).thenReturn(detail);

      StorageDetailValuationHandler invalidHandler = newInvalidHandler();
      assertDoesNotThrow(() -> invalidHandler.onUpdate(event));
      verify(detail, never()).setEtgoValuation(any());
    });
  }

  // ---------------------------------------------------------------------------
  // recalculate — qty null
  // ---------------------------------------------------------------------------

  @Test
  void recalculate_qtyNull_setsValuationToZero() {
    withModelProvider(() -> {
      StorageDetail detail = mock(StorageDetail.class);
      when(detail.getQuantityOnHand()).thenReturn(null);

      EntityNewEvent event = mock(EntityNewEvent.class);
      when(event.getTargetInstance()).thenReturn(detail);

      assertDoesNotThrow(() -> handler.onNew(event));
      verify(detail).setEtgoValuation(BigDecimal.ZERO);
    });
  }

  // ---------------------------------------------------------------------------
  // recalculate — qty zero
  // ---------------------------------------------------------------------------

  @Test
  void recalculate_qtyZero_setsValuationToZero() {
    withModelProvider(() -> {
      StorageDetail detail = mock(StorageDetail.class);
      when(detail.getQuantityOnHand()).thenReturn(BigDecimal.ZERO);

      EntityNewEvent event = mock(EntityNewEvent.class);
      when(event.getTargetInstance()).thenReturn(detail);

      assertDoesNotThrow(() -> handler.onNew(event));
      verify(detail).setEtgoValuation(BigDecimal.ZERO);
    });
  }

  // ---------------------------------------------------------------------------
  // recalculate — qty negative
  // ---------------------------------------------------------------------------

  @Test
  void recalculate_qtyNegative_setsValuationToZero() {
    withModelProvider(() -> {
      StorageDetail detail = mock(StorageDetail.class);
      when(detail.getQuantityOnHand()).thenReturn(new BigDecimal("-5"));

      EntityNewEvent event = mock(EntityNewEvent.class);
      when(event.getTargetInstance()).thenReturn(detail);

      assertDoesNotThrow(() -> handler.onNew(event));
      verify(detail).setEtgoValuation(BigDecimal.ZERO);
    });
  }

  // ---------------------------------------------------------------------------
  // recalculate — qty > 0, no current cost found
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void recalculate_positiveQty_noCostFound_setsValuationToZero() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
        OBDal mockDal = mock(OBDal.class);
        OBCriteria<Costing> criteria = mock(OBCriteria.class);
        when(mockDal.createCriteria(Costing.class)).thenReturn(criteria);
        when(criteria.list()).thenReturn(Collections.emptyList());
        obDal.when(OBDal::getInstance).thenReturn(mockDal);

        Product product = mock(Product.class);
        when(product.getId()).thenReturn("PROD-001");

        StorageDetail detail = mock(StorageDetail.class);
        when(detail.getQuantityOnHand()).thenReturn(new BigDecimal("100"));
        when(detail.getProduct()).thenReturn(product);

        EntityUpdateEvent event = mock(EntityUpdateEvent.class);
        when(event.getTargetInstance()).thenReturn(detail);

        assertDoesNotThrow(() -> handler.onUpdate(event));
        verify(detail).setEtgoValuation(BigDecimal.ZERO);
      }
    });
  }

  // ---------------------------------------------------------------------------
  // recalculate — qty > 0, cost found → valuation = qty × cost
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void recalculate_positiveQtyWithCost_setsCorrectValuation() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
        OBDal mockDal = mock(OBDal.class);
        OBCriteria<Costing> criteria = mock(OBCriteria.class);
        when(mockDal.createCriteria(Costing.class)).thenReturn(criteria);

        Costing costing = mock(Costing.class);
        when(costing.getCost()).thenReturn(new BigDecimal("35.80"));
        when(criteria.list()).thenReturn(List.of(costing));
        obDal.when(OBDal::getInstance).thenReturn(mockDal);

        Product product = mock(Product.class);
        StorageDetail detail = mock(StorageDetail.class);
        when(detail.getQuantityOnHand()).thenReturn(new BigDecimal("1000"));
        when(detail.getProduct()).thenReturn(product);

        EntityUpdateEvent event = mock(EntityUpdateEvent.class);
        when(event.getTargetInstance()).thenReturn(detail);

        assertDoesNotThrow(() -> handler.onUpdate(event));
        // 1000 × 35.80 = 35800.00
        verify(detail).setEtgoValuation(new BigDecimal("35800.00"));
      }
    });
  }

  // ---------------------------------------------------------------------------
  // recalculate — unexpected exception is swallowed
  // ---------------------------------------------------------------------------

  @Test
  void recalculate_unexpectedException_doesNotRethrow() {
    withModelProvider(() -> {
      StorageDetail detail = mock(StorageDetail.class);
      when(detail.getQuantityOnHand()).thenThrow(new RuntimeException("DB down"));

      EntityNewEvent event = mock(EntityNewEvent.class);
      when(event.getTargetInstance()).thenReturn(detail);

      assertDoesNotThrow(() -> handler.onNew(event));
    });
  }

  // ---------------------------------------------------------------------------
  // getCurrentCost — static helper
  // ---------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void getCurrentCost_noResults_returnsNull() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
        OBDal mockDal = mock(OBDal.class);
        OBCriteria<Costing> criteria = mock(OBCriteria.class);
        when(mockDal.createCriteria(Costing.class)).thenReturn(criteria);
        when(criteria.list()).thenReturn(Collections.emptyList());
        obDal.when(OBDal::getInstance).thenReturn(mockDal);

        BigDecimal result = StorageDetailValuationHandler.getCurrentCost(mock(Product.class));
        assertNull(result);
      }
    });
  }

  @Test
  @SuppressWarnings("unchecked")
  void getCurrentCost_withResults_returnsCost() {
    withModelProvider(() -> {
      try (MockedStatic<OBDal> obDal = Mockito.mockStatic(OBDal.class)) {
        OBDal mockDal = mock(OBDal.class);
        OBCriteria<Costing> criteria = mock(OBCriteria.class);
        when(mockDal.createCriteria(Costing.class)).thenReturn(criteria);

        Costing costing = mock(Costing.class);
        when(costing.getCost()).thenReturn(new BigDecimal("98.66"));
        when(criteria.list()).thenReturn(List.of(costing));
        obDal.when(OBDal::getInstance).thenReturn(mockDal);

        BigDecimal result = StorageDetailValuationHandler.getCurrentCost(mock(Product.class));
        assertEquals(new BigDecimal("98.66"), result);
      }
    });
  }
}
