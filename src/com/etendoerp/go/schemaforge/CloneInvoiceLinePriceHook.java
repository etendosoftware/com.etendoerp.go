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

import com.smf.jobs.defaults.CloneInvoiceHook;
import com.smf.jobs.hooks.CloneRecordHook;

import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.ComponentProvider.Qualifier;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;

import javax.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * GO-specific override of {@link CloneInvoiceHook}. Delegates the actual clone to the
 * core hook, then restores each cloned line's listPrice to match the source line —
 * the core hook resets listPrice to the current catalog price, which Schema Forge's
 * UI displays as the line's editable "Precio" field (ETP-4801).
 *
 * <p>Registered with a lower priority than the core hook's default (100) so this
 * one is selected by {@code NeoCloneRecordHandler} instead.
 */
@ApplicationScoped
@Qualifier(Invoice.ENTITY_NAME)
public class CloneInvoiceLinePriceHook extends CloneRecordHook {

  private final CloneRecordHook delegate;

  public CloneInvoiceLinePriceHook() {
    this(new CloneInvoiceHook());
  }

  // package-private constructor for testing with a mocked delegate
  CloneInvoiceLinePriceHook(CloneRecordHook delegate) {
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
    restoreListPrices((Invoice) originalRecord, (Invoice) copied);
    return copied;
  }

  /**
   * Restores each cloned line's listPrice to match the corresponding source line's
   * listPrice. Lines are matched one-to-one by their position in the list, the same
   * order {@link CloneInvoiceHook} clones them in via {@code DalUtil.copy}.
   */
  private void restoreListPrices(Invoice original, Invoice clone) {
    List<InvoiceLine> sourceLines = original.getInvoiceLineList();
    List<InvoiceLine> clonedLines = clone.getInvoiceLineList();
    int lineCount = Math.min(sourceLines.size(), clonedLines.size());
    for (int i = 0; i < lineCount; i++) {
      clonedLines.get(i).setListPrice(sourceLines.get(i).getListPrice());
    }
  }
}
