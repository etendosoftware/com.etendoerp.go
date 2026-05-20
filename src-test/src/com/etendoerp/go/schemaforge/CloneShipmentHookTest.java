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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

import org.junit.Test;
import org.openbravo.base.structure.BaseOBObject;

/**
 * Unit tests for {@link CloneShipmentHook}.
 *
 * <p>Covers the two lifecycle methods that can be tested without CDI or DB access:
 * <ul>
 *   <li>{@code shouldCopyChildren()} — must always return false so the hook manages
 *       line copying itself instead of delegating to the framework's generic copy.</li>
 *   <li>{@code preCopy()} — must return the original record unchanged so the framework
 *       proceeds with the unmodified source before {@code postCopy} resets the clone.</li>
 * </ul>
 *
 * <p>{@code postCopy()} requires {@link org.openbravo.dal.core.OBContext} and
 * {@link org.openbravo.dal.service.OBDal} and is covered by integration tests only.
 */
public class CloneShipmentHookTest {

  // ── shouldCopyChildren() ──────────────────────────────────────────────────

  /**
   * Verifies that shouldCopyChildren always returns false regardless of the uiCopyChildren flag,
   * so the hook manages line copying in postCopy and avoids the framework's generic child copy.
   */
  @Test
  public void testShouldCopyChildrenReturnsFalse() {
    CloneShipmentHook hook = new CloneShipmentHook();
    assertFalse(hook.shouldCopyChildren(true));
    assertFalse(hook.shouldCopyChildren(false));
  }

  // ── preCopy() ─────────────────────────────────────────────────────────────

  /**
   * Verifies that preCopy returns the original record instance unchanged, allowing the
   * framework to proceed with the unmodified source object.
   */
  @Test
  public void testPreCopyReturnsOriginalRecordUnchanged() throws Exception {
    CloneShipmentHook hook = new CloneShipmentHook();
    BaseOBObject original = mock(BaseOBObject.class);
    assertSame(original, hook.preCopy(original));
  }
}
