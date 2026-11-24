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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.ConversionRate;

/**
 * Unit tests for {@link ConversionRateWrites#writableRate} over the real
 * {@link ConversionRateStore#readableRate} / {@link ConversionRateStore#isOwn} (a spy whose DAL
 * reads are stubbed): which rows a tenant may change or delete (ETP-6365). A row it cannot read is
 * unknown ({@code null}, a 404 for the caller); a downloaded row it can read — its own or a System
 * one — is the synced lock (409); a System manual row is not writable ({@code null}); only its own
 * manual row is returned.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.ConversionRateWrites
 * @covers com.etendoerp.go.schemaforge.handlers.ConversionRateStore
 */
public class ConversionRateWritesTest {

  private static final String OWN = "client-own";
  private static final String OTHER = "client-other";
  private static final String SYSTEM = "0";
  private static final String ID = "rate-1";
  private static final String RETURNED = "row";
  private static final String NONE = "null";
  private static final String LOCKED = "409";

  @Test
  public void writableRateAnswersPerOwnerAndSyncedState() {
    // name, owner (null = no such row), synced, expected
    Object[][] cases = {
        { "unknown id", null, false, NONE },
        { "another tenant's downloaded row", OTHER, true, NONE },
        { "another tenant's manual row", OTHER, false, NONE },
        { "System downloaded row", SYSTEM, true, LOCKED },
        { "System manual row", SYSTEM, false, NONE },
        { "own downloaded row", OWN, true, LOCKED },
        { "own manual row", OWN, false, RETURNED },
    };
    for (Object[] c : cases) {
      String name = (String) c[0];
      ConversionRate row = c[1] == null ? null : row((String) c[1]);
      ConversionRateStore store = spy(new ConversionRateStore());
      doReturn(OWN).when(store).currentClientId();
      doReturn(row).when(store).rate(ID);
      if (row != null) {
        doReturn(c[2]).when(store).isSynced(row);
      }

      ConversionRate answer;
      try {
        answer = new ConversionRateWrites(store).writableRate(ID);
      } catch (ConversionRateRefusal refusal) {
        assertEquals(name, LOCKED, c[3]);
        assertEquals(name, 409, refusal.getStatus());
        assertEquals(name, ConversionRateRefusal.SYNCED_LOCKED, refusal.getKey());
        continue;
      }
      if (LOCKED.equals(c[3])) {
        fail(name + ": expected the synced lock");
      } else if (RETURNED.equals(c[3])) {
        assertSame(name, row, answer);
      } else {
        assertNull(name, answer);
      }
    }
  }

  private static ConversionRate row(String ownerId) {
    Client owner = mock(Client.class);
    when(owner.getId()).thenReturn(ownerId);
    ConversionRate row = mock(ConversionRate.class);
    when(row.getClient()).thenReturn(owner);
    return row;
  }
}
