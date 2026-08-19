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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import com.smf.jobs.hooks.CloneRecordHook;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;

/**
 * Unit tests for {@link CloneOrderLinePriceHook}.
 *
 * <p>ETP-4801: cloning a Sales/Purchase Order whose line price was manually modified
 * (different from the price list) resets the displayed price back to the catalog price
 * in the clone. The core {@link com.smf.jobs.defaults.CloneOrderHook} copies unitPrice /
 * grossUnitPrice / lineNetAmount / lineGrossAmount correctly but explicitly overwrites
 * listPrice with a freshly-queried catalog price — Schema Forge's UI shows listPrice as
 * the editable "Precio" field, so the user sees the wrong price even though totals are right.
 *
 * <p>These tests assert that {@link CloneOrderLinePriceHook#postCopy} restores each
 * cloned line's listPrice to match the corresponding source line's listPrice, after
 * delegating the actual clone work to the core hook. RED phase: the skeleton hook only
 * returns the delegate's result untouched, so these tests are expected to fail until the
 * GREEN-phase fix is implemented.
 */
public class CloneOrderLinePriceHookTest {

  // ── shouldCopyChildren() / preCopy() delegation ────────────────────────────

  @Test
  public void testShouldCopyChildrenDelegatesToCoreHook() {
    CloneRecordHook delegate = mock(CloneRecordHook.class);
    when(delegate.shouldCopyChildren(true)).thenReturn(false);
    when(delegate.shouldCopyChildren(false)).thenReturn(false);

    CloneOrderLinePriceHook hook = new CloneOrderLinePriceHook(delegate);

    assertTrue(!hook.shouldCopyChildren(true));
    assertTrue(!hook.shouldCopyChildren(false));
  }

  @Test
  public void testPreCopyDelegatesToCoreHook() throws Exception {
    CloneRecordHook delegate = mock(CloneRecordHook.class);
    BaseOBObject original = mock(BaseOBObject.class);
    when(delegate.preCopy(original)).thenReturn(original);

    CloneOrderLinePriceHook hook = new CloneOrderLinePriceHook(delegate);

    assertSame(original, hook.preCopy(original));
  }

  // ── postCopy() — single line ────────────────────────────────────────────────

  /**
   * The core hook resets the cloned line's listPrice to the current catalog price
   * (44.00) even though the source line's manually-modified price was 30.00. postCopy
   * must restore the clone's listPrice back to 30.00.
   *
   * <p>RED: the skeleton's postCopy never calls setListPrice, so this verification fails.
   */
  @Test
  public void testPostCopyRestoresListPriceToMatchSourceLine() throws Exception {
    CloneRecordHook delegate = mock(CloneRecordHook.class);

    Order original = mock(Order.class);
    Order newRecordPlaceholder = mock(Order.class);
    Order clone = mock(Order.class);

    OrderLine sourceLine = mock(OrderLine.class);
    when(sourceLine.getListPrice()).thenReturn(new BigDecimal("30.00"));
    when(original.getOrderLineList()).thenReturn(Arrays.asList(sourceLine));

    OrderLine clonedLine = mock(OrderLine.class);
    when(clonedLine.getListPrice()).thenReturn(new BigDecimal("44.00"));
    List<OrderLine> cloneLines = new ArrayList<>();
    cloneLines.add(clonedLine);
    when(clone.getOrderLineList()).thenReturn(cloneLines);

    when(delegate.postCopy(original, newRecordPlaceholder)).thenReturn(clone);

    CloneOrderLinePriceHook hook = new CloneOrderLinePriceHook(delegate);
    BaseOBObject result = hook.postCopy(original, newRecordPlaceholder);

    assertSame(clone, result);
    verify(clonedLine).setListPrice(new BigDecimal("30.00"));
  }

  // ── postCopy() — multiple lines, matching contract ──────────────────────────

  /**
   * With 2+ lines, the fix must match each cloned line back to its corresponding
   * source line (e.g. by list order/index) rather than assuming a single-line list.
   * Documents the expected matching contract for the GREEN-phase implementation.
   *
   * <p>RED: fails for the same reason as the single-line case.
   */
  @Test
  public void testPostCopyRestoresListPriceForEachLineWhenMultipleLinesExist() throws Exception {
    CloneRecordHook delegate = mock(CloneRecordHook.class);

    Order original = mock(Order.class);
    Order newRecordPlaceholder = mock(Order.class);
    Order clone = mock(Order.class);

    OrderLine sourceLine1 = mock(OrderLine.class);
    when(sourceLine1.getListPrice()).thenReturn(new BigDecimal("30.00"));
    OrderLine sourceLine2 = mock(OrderLine.class);
    when(sourceLine2.getListPrice()).thenReturn(new BigDecimal("15.50"));
    when(original.getOrderLineList()).thenReturn(Arrays.asList(sourceLine1, sourceLine2));

    OrderLine clonedLine1 = mock(OrderLine.class);
    when(clonedLine1.getListPrice()).thenReturn(new BigDecimal("44.00"));
    OrderLine clonedLine2 = mock(OrderLine.class);
    when(clonedLine2.getListPrice()).thenReturn(new BigDecimal("20.00"));
    List<OrderLine> cloneLines = new ArrayList<>();
    cloneLines.add(clonedLine1);
    cloneLines.add(clonedLine2);
    when(clone.getOrderLineList()).thenReturn(cloneLines);

    when(delegate.postCopy(original, newRecordPlaceholder)).thenReturn(clone);

    CloneOrderLinePriceHook hook = new CloneOrderLinePriceHook(delegate);
    hook.postCopy(original, newRecordPlaceholder);

    verify(clonedLine1).setListPrice(new BigDecimal("30.00"));
    verify(clonedLine2).setListPrice(new BigDecimal("15.50"));
  }
}
