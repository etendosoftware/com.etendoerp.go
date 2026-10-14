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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.Session;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;

/**
 * Unit tests for {@link NeoBatchScope}'s per-request lookup memo (ETP-5676).
 *
 * @covers com.etendoerp.go.schemaforge.NeoBatchScope
 */
public class NeoBatchScopeTest {

  @Test
  public void testMemoizeLoadsOncePerKeyInsideABatchAndNeverOutside() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));
      AtomicInteger loads = new AtomicInteger();

      NeoBatchScope.memoize("k", loads::incrementAndGet);
      NeoBatchScope.memoize("k", loads::incrementAndGet);
      assertEquals("outside a batch every call loads", 2, loads.get());

      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        assertEquals(Integer.valueOf(3), NeoBatchScope.memoize("k", loads::incrementAndGet));
        assertEquals(Integer.valueOf(3), NeoBatchScope.memoize("k", loads::incrementAndGet));
        NeoBatchScope.memoize("other", loads::incrementAndGet);
        assertEquals("one load per distinct key", 4, loads.get());
      } finally {
        NeoBatchScope.close(previous);
      }
      assertNull("the scope dies with the request", NeoBatchScope.current());

      NeoBatchScope next = NeoBatchScope.open(false);
      try {
        NeoBatchScope.memoize("k", loads::incrementAndGet);
        assertEquals("a new request starts with an empty memo", 5, loads.get());
      } finally {
        NeoBatchScope.close(next);
      }
    }
  }

  /** The close-time summary counts hits and misses per namespace (class|lookup), not per key. */
  @Test
  public void testMemoizeCountsHitsAndMissesPerNamespace() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class));

      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        NeoBatchScope.memoize("a.Owner|spec|x", () -> 1);
        NeoBatchScope.memoize("a.Owner|spec|x", () -> 1);
        NeoBatchScope.memoize("a.Owner|spec|y", () -> 2);
        int[] spec = NeoBatchScope.currentMemoStats().get("a.Owner|spec");
        assertEquals(1, spec[0]);
        assertEquals(2, spec[1]);
      } finally {
        NeoBatchScope.close(previous);
      }
    }
  }

  /** A process that commits mid-batch closes the session; DAL objects loaded before are stale. */
  @Test
  public void testMemoizeForgetsEverythingWhenTheSessionChanges() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getSession()).thenReturn(mock(Session.class), mock(Session.class));
      AtomicInteger loads = new AtomicInteger();

      NeoBatchScope previous = NeoBatchScope.open(false);
      try {
        NeoBatchScope.memoize("k", loads::incrementAndGet);
        NeoBatchScope.memoize("k", loads::incrementAndGet);
        assertEquals(2, loads.get());
      } finally {
        NeoBatchScope.close(previous);
      }
    }
  }
}
