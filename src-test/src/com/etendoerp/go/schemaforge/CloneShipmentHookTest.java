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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

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

  /**
   * postCopy must reset all header fields to draft state, copy each source line
   * with its C_OrderLine_ID cleared (to avoid MovementQtyCheck trigger violations),
   * and flush + refresh via OBDal.
   *
   * <p>The {@code setSalesOrderLine(null)} call is specifically verified here because
   * it was added to prevent {@code m_inoutline_trg} from double-counting delivered
   * quantities against {@code QtyOrdered} when the cloned receipt is later completed.
   */
  @Test
  public void testPostCopyResetsHeaderAndClearsOrderLineLinkOnClonedLines() {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {

      OBContext obCtx = mock(OBContext.class);
      User user = mock(User.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(obCtx);
      when(obCtx.getUser()).thenReturn(user);

      ShipmentInOut original = mock(ShipmentInOut.class);
      ShipmentInOut clone = mock(ShipmentInOut.class);

      ShipmentInOutLine clonedLine = mock(ShipmentInOutLine.class);
      // shouldCopyChildren=true means DalUtil already populated clone's line list before postCopy runs
      List<ShipmentInOutLine> cloneLines = new ArrayList<>();
      cloneLines.add(clonedLine);
      when(clone.getMaterialMgmtShipmentInOutLineList()).thenReturn(cloneLines);

      BaseOBObject result = new CloneShipmentHook().postCopy(original, clone);

      assertSame(clone, result);

      // Header reset to draft
      verify(clone).setDocumentStatus("DR");
      verify(clone).setDocumentAction("CO");
      verify(clone).setPosted("N");
      verify(clone).setProcessed(false);
      verify(clone).setDocumentNo(null);
      verify(clone).setCompletelyInvoiced(false);
      verify(clone).setInvoice(null);

      // C_OrderLine_ID must be cleared to prevent trigger double-count
      verify(clonedLine).setSalesOrderLine(null);
      verify(clonedLine).setCanceledInoutLine(null);
    }
  }
}
