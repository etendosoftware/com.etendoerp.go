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

import com.smf.jobs.hooks.CloneRecordHook;

import org.openbravo.base.structure.BaseOBObject;

/**
 * Shared scaffolding for the GO-specific clone-price-fix hooks (ETP-4801):
 * {@link CloneOrderLinePriceHook} and {@link CloneInvoiceLinePriceHook}.
 *
 * <p>Both delegate the actual clone to their respective core hook, then restore each
 * cloned line's listPrice to match the source line — the core hooks reset listPrice to
 * the current catalog price, which Schema Forge's UI displays as the line's editable
 * "Precio" field. Only the line-matching logic differs per entity, so it is left to
 * {@link #restoreListPrices(BaseOBObject, BaseOBObject)}.
 *
 * <p>Registered with a lower priority than the core hooks' default (100) so these are
 * selected by {@code NeoCloneRecordHandler} instead.
 */
abstract class CloneLinePriceHook extends CloneRecordHook {

  private final CloneRecordHook delegate;

  /**
   * @param delegate the core {@link CloneRecordHook} that performs the actual clone
   */
  protected CloneLinePriceHook(CloneRecordHook delegate) {
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
    restoreListPrices(originalRecord, copied);
    return copied;
  }

  /**
   * Restores each cloned line's listPrice to match the corresponding source line's
   * listPrice. Lines are matched one-to-one by their position in the list, the same
   * order the core hook clones them in via {@code DalUtil.copy}.
   */
  protected abstract void restoreListPrices(BaseOBObject originalRecord, BaseOBObject copied);
}
