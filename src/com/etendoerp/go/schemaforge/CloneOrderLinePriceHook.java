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

import com.smf.jobs.defaults.CloneOrderHook;
import com.smf.jobs.hooks.CloneRecordHook;

import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.ComponentProvider.Qualifier;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;

import javax.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * GO-specific override of {@link CloneOrderHook}. Delegates the actual clone to the
 * core hook, then restores each cloned line's listPrice to match the source line —
 * the core hook resets listPrice to the current catalog price, which Schema Forge's
 * UI displays as the line's editable "Precio" field (ETP-4801).
 *
 * <p>Registered with a lower priority than the core hook's default (100) so this
 * one is selected by {@code NeoCloneRecordHandler} instead.
 */
@ApplicationScoped
@Qualifier(Order.ENTITY_NAME)
public class CloneOrderLinePriceHook extends CloneRecordHook {

  private final CloneRecordHook delegate;

  public CloneOrderLinePriceHook() {
    this(new CloneOrderHook());
  }

  // package-private constructor for testing with a mocked delegate
  CloneOrderLinePriceHook(CloneRecordHook delegate) {
    this.delegate = delegate;
  }

  @Override
  public int getPriority() {
    return 10; // lower than the core hook's default (100) so this one is selected
  }

  @Override
  public boolean shouldCopyChildren(boolean uiCopyChildren) {
    return delegate.shouldCopyChildren(uiCopyChildren);
  }

  @Override
  public BaseOBObject preCopy(BaseOBObject originalRecord) throws Exception {
    return delegate.preCopy(originalRecord);
  }

  @Override
  public BaseOBObject postCopy(BaseOBObject originalRecord, BaseOBObject newRecord) throws Exception {
    BaseOBObject copied = delegate.postCopy(originalRecord, newRecord);
    restoreListPrices((Order) originalRecord, (Order) copied);
    return copied;
  }

  /**
   * Restores each cloned line's listPrice to match the corresponding source line's
   * listPrice. Lines are matched one-to-one by their position in the list, the same
   * order {@link CloneOrderHook} clones them in via {@code DalUtil.copy}.
   */
  private void restoreListPrices(Order original, Order clone) {
    List<OrderLine> sourceLines = original.getOrderLineList();
    List<OrderLine> clonedLines = clone.getOrderLineList();
    int lineCount = Math.min(sourceLines.size(), clonedLines.size());
    for (int i = 0; i < lineCount; i++) {
      clonedLines.get(i).setListPrice(sourceLines.get(i).getListPrice());
    }
  }
}
