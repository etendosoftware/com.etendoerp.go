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
package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.ConversionRate;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link ConversionRateHandler#refusalForReadOnlyTarget}: the delete of a row the
 * tenant can read but not delete — a System rate — refused by the CRUD guard before the
 * customization runs (ETP-6365). A downloaded one is worded as the synced lock (409
 * {@code ETGO_ConvRateSyncedLocked}); any other keeps the guard's 404 ({@code null}). The store is
 * a mock; OBContext/OBDal are mocked statically so the message text falls back to the catalogue.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.ConversionRateHandler
 */
public class ConversionRateHandlerTest {

  private static final String ID = "rate-1";

  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDal;
  private ConversionRateStore store;
  private ConversionRateHandler handler;

  @Before
  public void setUp() {
    obContext = Mockito.mockStatic(OBContext.class);
    obDal = Mockito.mockStatic(OBDal.class);
    store = mock(ConversionRateStore.class);
    handler = spy(new ConversionRateHandler());
    doReturn(store).when(handler).store();
  }

  @After
  public void tearDown() {
    obDal.close();
    obContext.close();
  }

  private static NeoContext delete(String recordId) {
    return NeoContext.builder().httpMethod("DELETE").recordId(recordId).build();
  }

  private ConversionRate systemRow(boolean synced) {
    Client system = mock(Client.class);
    when(system.getId()).thenReturn("0");
    ConversionRate row = mock(ConversionRate.class);
    when(row.getClient()).thenReturn(system);
    when(store.readableRate(ID)).thenReturn(row);
    when(store.isSynced(row)).thenReturn(synced);
    when(store.isOwn(row)).thenReturn(false);
    return row;
  }

  @Test
  public void aDownloadedSystemRateIsAnsweredWithTheSyncedLock() throws Exception {
    systemRow(true);

    NeoResponse refusal = handler.refusalForReadOnlyTarget(delete(ID));

    assertEquals(409, refusal.getHttpStatus());
    assertEquals(ConversionRateRefusal.SYNCED_LOCKED, refusal.getBody().getJSONObject("error")
        .getJSONArray("messageKeys").getString(0));
    obContext.verify(() -> OBContext.setAdminMode(true));
    obContext.verify(OBContext::restorePreviousMode);
  }

  @Test
  public void aManualSystemRateKeepsTheGuardsNotFound() {
    systemRow(false);

    assertNull(handler.refusalForReadOnlyTarget(delete(ID)));
    obContext.verify(OBContext::restorePreviousMode);
  }

  @Test
  public void noRecordIdAsksNothing() {
    assertNull(handler.refusalForReadOnlyTarget(delete("  ")));
    assertNull(handler.refusalForReadOnlyTarget(null));
    verify(store, never()).readableRate(Mockito.anyString());
  }
}
