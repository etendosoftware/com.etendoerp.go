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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * Unit tests for {@link CloneShipmentHook}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code shouldCopyChildren()} — must always return true so the framework copies
 *       lines via DalUtil.copy; the hook no longer manages line copying manually.</li>
 *   <li>{@code preCopy()} — must return the original record unchanged.</li>
 *   <li>{@code postCopy()} — must reset header state fields to draft on the clone.</li>
 * </ul>
 */
public class CloneShipmentHookTest {

  // ── shouldCopyChildren() ──────────────────────────────────────────────────

  /**
   * Verifies that shouldCopyChildren always returns true regardless of the uiCopyChildren flag,
   * so the framework (DalUtil.copy) handles line copying and the hook only resets header state.
   */
  @Test
  public void testShouldCopyChildrenReturnsTrue() {
    CloneShipmentHook hook = new CloneShipmentHook();
    assertTrue(hook.shouldCopyChildren(true));
    assertTrue(hook.shouldCopyChildren(false));
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

  // ── postCopy() ────────────────────────────────────────────────────────────

  /**
   * Verifies that postCopy resets documentStatus, documentAction, posted, processed,
   * and documentNo on the clone to put it in a clean Draft state.
   */
  @Test
  public void testPostCopyResetsDocumentStatusToDraft() throws Exception {
    CloneShipmentHook hook = new CloneShipmentHook();
    ShipmentInOut original = mock(ShipmentInOut.class);
    ShipmentInOut clone = mock(ShipmentInOut.class);

    User currentUser = mock(User.class);
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      OBContext obContext = mock(OBContext.class);
      obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getUser()).thenReturn(currentUser);

      BaseOBObject result = hook.postCopy(original, clone);

      assertSame(clone, result);
      Mockito.verify(clone).setDocumentStatus("DR");
      Mockito.verify(clone).setDocumentAction("CO");
      Mockito.verify(clone).setPosted("N");
      Mockito.verify(clone).setProcessed(false);
      Mockito.verify(clone).setDocumentNo(null);
    }
  }

  /**
   * Verifies that postCopy sets movementDate to today (not null) on the clone.
   */
  @Test
  public void testPostCopySetsMovementDateToToday() throws Exception {
    CloneShipmentHook hook = new CloneShipmentHook();
    ShipmentInOut original = mock(ShipmentInOut.class);
    ShipmentInOut clone = mock(ShipmentInOut.class);

    User currentUser = mock(User.class);
    long beforeMs = System.currentTimeMillis();
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      OBContext obContext = mock(OBContext.class);
      obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getUser()).thenReturn(currentUser);

      hook.postCopy(original, clone);
      long afterMs = System.currentTimeMillis();

      // Capture the Date passed to setMovementDate via an ArgumentCaptor-style check:
      // verify it was called with a non-null date that is <= today (truncated to day).
      org.mockito.ArgumentCaptor<Date> captor = org.mockito.ArgumentCaptor.forClass(Date.class);
      Mockito.verify(clone).setMovementDate(captor.capture());
      Date movementDate = captor.getValue();
      assertNotNull(movementDate);
      // Truncated to day — should be <= today and >= start of today
      assertTrue(movementDate.getTime() <= afterMs);
      assertTrue(movementDate.getTime() >= beforeMs - 86400_000L);
    }
  }

  /**
   * Verifies that postCopy sets creationDate and updatedDate to non-null values.
   */
  @Test
  public void testPostCopySetsAuditDatesAsNonNull() throws Exception {
    CloneShipmentHook hook = new CloneShipmentHook();
    ShipmentInOut original = mock(ShipmentInOut.class);
    ShipmentInOut clone = mock(ShipmentInOut.class);

    User currentUser = mock(User.class);
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      OBContext obContext = mock(OBContext.class);
      obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getUser()).thenReturn(currentUser);

      hook.postCopy(original, clone);

      org.mockito.ArgumentCaptor<Date> creationCaptor = org.mockito.ArgumentCaptor.forClass(Date.class);
      Mockito.verify(clone).setCreationDate(creationCaptor.capture());
      assertNotNull(creationCaptor.getValue());

      org.mockito.ArgumentCaptor<Date> updatedCaptor = org.mockito.ArgumentCaptor.forClass(Date.class);
      Mockito.verify(clone).setUpdated(updatedCaptor.capture());
      assertNotNull(updatedCaptor.getValue());
    }
  }

  /**
   * Verifies that postCopy assigns the current OBContext user to both createdBy and updatedBy.
   */
  @Test
  public void testPostCopySetsCreatedByAndUpdatedByToCurrentUser() throws Exception {
    CloneShipmentHook hook = new CloneShipmentHook();
    ShipmentInOut original = mock(ShipmentInOut.class);
    ShipmentInOut clone = mock(ShipmentInOut.class);

    User currentUser = mock(User.class);
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class)) {
      OBContext obContext = mock(OBContext.class);
      obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getUser()).thenReturn(currentUser);

      hook.postCopy(original, clone);

      Mockito.verify(clone).setCreatedBy(currentUser);
      Mockito.verify(clone).setUpdatedBy(currentUser);
    }
  }
}
