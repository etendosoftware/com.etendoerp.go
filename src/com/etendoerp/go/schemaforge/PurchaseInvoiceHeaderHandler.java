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

import javax.inject.Inject;
import javax.inject.Named;

/**
 * NeoHandler for the Purchase Invoice header entity.
 *
 * Dispatches custom ACTION requests to the appropriate handler:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler} (uses {@code CloneInvoiceHook})</li>
 *   <li>{@code registerPayment} / {@code invoicePayments} / {@code invoiceAccounts} → {@link RegisterPaymentOutHandler}</li>
 * </ul>
 *
 * <p>Before the Complete action (documentAction=CO), creates the total discount line so it is
 * included in the completed invoice. Delegates to {@link TotalDiscountService} via the shared
 * helper in {@link AbstractOrderHeaderHandler}.
 */
@Named("purchaseInvoiceHeaderHandler")
public class PurchaseInvoiceHeaderHandler implements NeoHandler {

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private RegisterPaymentOutHandler registerPaymentOutHandler;

  @Inject
  private TotalDiscountService totalDiscountService;

  @Override
  public NeoResponse handle(NeoContext context) {
    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(context, totalDiscountService, true);
    return NeoHeaderActionRouter.dispatch(
        context,
        cloneRecordHandler,
        registerPaymentOutHandler);
  }
}
