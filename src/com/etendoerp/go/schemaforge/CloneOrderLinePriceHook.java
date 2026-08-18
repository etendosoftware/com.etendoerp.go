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
 * GO-specific override of {@link CloneOrderHook} (ETP-4801). See {@link CloneLinePriceHook}
 * for the shared clone-price-fix behavior; this class only supplies the Order-specific
 * delegate and line matching.
 */
@ApplicationScoped
@Qualifier(Order.ENTITY_NAME)
public class CloneOrderLinePriceHook extends CloneLinePriceHook {

  /**
   * Creates the hook using the core {@link CloneOrderHook} as the clone delegate.
   */
  public CloneOrderLinePriceHook() {
    this(new CloneOrderHook());
  }

  // package-private constructor for testing with a mocked delegate
  CloneOrderLinePriceHook(CloneRecordHook delegate) {
    super(delegate);
  }

  @Override
  protected void restoreListPrices(BaseOBObject originalRecord, BaseOBObject copied) {
    List<OrderLine> sourceLines = ((Order) originalRecord).getOrderLineList();
    List<OrderLine> clonedLines = ((Order) copied).getOrderLineList();
    int lineCount = Math.min(sourceLines.size(), clonedLines.size());
    for (int i = 0; i < lineCount; i++) {
      clonedLines.get(i).setListPrice(sourceLines.get(i).getListPrice());
    }
  }
}
