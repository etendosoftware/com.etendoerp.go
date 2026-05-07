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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

/**
 * Unit tests for the parent-tab cache in {@link CalloutRequestBuilder}.
 *
 * <p>The cache memoizes parent-tab resolution per child tab id so the
 * {@code window.getADTabList()} sort+walk runs only once per tab over the
 * lifetime of the cache. Invalidation is exposed through
 * {@link NeoCalloutService#clearMetadataCache()}.</p>
 */
public class CalloutRequestBuilderTest {

  @After
  public void clearCaches() {
    NeoCalloutService.clearMetadataCache();
  }

  private static Tab mockTab(String id, long sequence, long level) {
    Tab tab = mock(Tab.class);
    when(tab.getId()).thenReturn(id);
    when(tab.getSequenceNumber()).thenReturn(sequence);
    when(tab.getTabLevel()).thenReturn(level);
    return tab;
  }

  @Test
  public void testFindParentTabCacheHitDoesNotResortWindowTabs() {
    Tab parent = mockTab("parent", 10L, 0L);
    Tab child = mockTab("child", 20L, 1L);
    Window window = mock(Window.class);
    when(window.getADTabList()).thenReturn(Arrays.asList(parent, child));
    when(child.getWindow()).thenReturn(window);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(Tab.class, "parent")).thenReturn(parent);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      Tab first = CalloutRequestBuilder.findParentTab(child);
      Tab second = CalloutRequestBuilder.findParentTab(child);

      assertSame(parent, first);
      assertSame(parent, second);
      // Only the first call walks the window tab list; the second hits the cache.
      verify(window, times(1)).getADTabList();
      // OBDal lookup runs each call — cheap because Hibernate L1/L2 cache it.
      verify(obDal, times(2)).get(Tab.class, "parent");
    }
  }

  @Test
  public void testFindParentTabTopLevelSentinelSkipsObdalOnSecondCall() {
    Tab topLevel = mockTab("top", 10L, 0L);
    Window window = mock(Window.class);
    when(window.getADTabList()).thenReturn(Collections.singletonList(topLevel));
    when(topLevel.getWindow()).thenReturn(window);

    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      Tab first = CalloutRequestBuilder.findParentTab(topLevel);
      Tab second = CalloutRequestBuilder.findParentTab(topLevel);

      assertNull(first);
      assertNull(second);
      // Cache stores the "no parent" sentinel after the first walk.
      verify(window, times(1)).getADTabList();
      // The sentinel short-circuits before reaching OBDal on either call.
      verify(obDal, never()).get(Tab.class, "");
    }
  }

  @Test
  public void testClearMetadataCacheInvalidatesParentTabCache() {
    Tab parent = mockTab("parent", 10L, 0L);
    Tab child = mockTab("child", 20L, 1L);
    Window window = mock(Window.class);
    when(window.getADTabList()).thenReturn(Arrays.asList(parent, child));
    when(child.getWindow()).thenReturn(window);

    OBDal obDal = mock(OBDal.class);
    when(obDal.get(Tab.class, "parent")).thenReturn(parent);

    try (MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      CalloutRequestBuilder.findParentTab(child);
      NeoCalloutService.clearMetadataCache();
      CalloutRequestBuilder.findParentTab(child);

      // After invalidation the next call must re-resolve from the window tab list.
      verify(window, times(2)).getADTabList();
    }
  }
}
