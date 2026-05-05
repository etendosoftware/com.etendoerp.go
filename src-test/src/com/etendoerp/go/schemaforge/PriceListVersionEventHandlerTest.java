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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListVersion;

/**
 * Unit tests for {@link PriceListVersionEventHandler}.
 *
 * <p>{@link PriceListVersionEventHandler} extends {@link
 * org.openbravo.client.kernel.event.EntityPersistenceEventObserver}, which requires
 * {@link ModelProvider} to be initialized at class-load time. A static mock is set up in
 * {@link #setUpClass()} so the JVM can load the class before any test method runs.
 *
 * <p>All tests use a spy on the handler with {@code isValidEvent} stubbed explicitly,
 * since the real implementation checks identity equality against the mock entity, which
 * would depend on Etendo application context.
 */
public class PriceListVersionEventHandlerTest {

  private static MockedStatic<ModelProvider> modelProviderMock;

  /**
   * Stubs {@link ModelProvider#getInstance()} before any test method references
   * {@link PriceListVersionEventHandler}, allowing the class static initializer to run
   * without an Etendo application context.
   */
  @BeforeClass
  public static void setUpClass() {
    Entity mockEntity = mock(Entity.class);
    ModelProvider mockMp = mock(ModelProvider.class);
    when(mockMp.getEntity(anyString())).thenReturn(mockEntity);
    modelProviderMock = Mockito.mockStatic(ModelProvider.class);
    modelProviderMock.when(ModelProvider::getInstance).thenReturn(mockMp);
  }

  /** Releases the static ModelProvider mock after all tests in this class complete. */
  @AfterClass
  public static void tearDownClass() {
    modelProviderMock.close();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /**
   * Returns a spy handler with {@code isValidEvent} stubbed to return the given value.
   * Using {@code doReturn} avoids calling the real method during stubbing, which would
   * require a live Etendo application context.
   */
  private static PriceListVersionEventHandler handlerWithValidEvent(boolean validEvent) {
    PriceListVersionEventHandler handler = spy(new PriceListVersionEventHandler());
    doReturn(validEvent).when(handler).isValidEvent(any());
    return handler;
  }

  // ── onSave() ──────────────────────────────────────────────────────────────

  /**
   * Verifies that onSave returns immediately when isValidEvent returns false,
   * without touching any OBDal state.
   */
  @Test
  public void testOnSaveSkipsWhenIsValidEventReturnsFalse() {
    PriceListVersionEventHandler handler = handlerWithValidEvent(false);
    handler.onSave(mock(EntityNewEvent.class));
  }

  /**
   * Verifies that onSave returns immediately when the new version has a null price list,
   * without querying OBDal.
   */
  @Test
  public void testOnSaveSkipsWhenPriceListIsNull() {
    PriceListVersionEventHandler handler = handlerWithValidEvent(true);
    EntityNewEvent event = mock(EntityNewEvent.class);
    PriceListVersion version = mock(PriceListVersion.class);
    when(event.getTargetInstance()).thenReturn(version);
    when(version.getPriceList()).thenReturn(null);

    handler.onSave(event);
  }

  /**
   * Verifies that onSave does not throw when the price list has no prior version
   * (i.e., this is the first version being created, which is valid).
   */
  @Test
  public void testOnSaveDoesNotThrowWhenNoPriorVersionExists() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<PriceListVersion> crit = mock(OBCriteria.class);
      when(dal.createCriteria(PriceListVersion.class)).thenReturn(crit);
      when(crit.count()).thenReturn(0L);

      PriceListVersionEventHandler handler = handlerWithValidEvent(true);
      EntityNewEvent event = mock(EntityNewEvent.class);
      PriceListVersion version = mock(PriceListVersion.class);
      when(event.getTargetInstance()).thenReturn(version);
      when(version.getPriceList()).thenReturn(mock(PriceList.class));

      handler.onSave(event);
    }
  }

  /**
   * Verifies that onSave throws {@link OBException} when the price list already has a version,
   * enforcing the one-price-list-one-version invariant.
   */
  @Test(expected = OBException.class)
  public void testOnSaveThrowsOBExceptionWhenVersionAlreadyExists() {
    try (MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      @SuppressWarnings("unchecked")
      OBCriteria<PriceListVersion> crit = mock(OBCriteria.class);
      when(dal.createCriteria(PriceListVersion.class)).thenReturn(crit);
      when(crit.count()).thenReturn(1L);

      PriceListVersionEventHandler handler = handlerWithValidEvent(true);
      EntityNewEvent event = mock(EntityNewEvent.class);
      PriceListVersion version = mock(PriceListVersion.class);
      when(event.getTargetInstance()).thenReturn(version);
      PriceList pl = mock(PriceList.class);
      when(pl.getName()).thenReturn("Test Price List");
      when(version.getPriceList()).thenReturn(pl);

      handler.onSave(event);
    }
  }
}
