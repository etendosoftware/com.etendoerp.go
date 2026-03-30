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

import javax.inject.Named;

/**
 * Composite NeoHandler for the payment-in finPayment entity.
 * Routes action requests to the appropriate handler based on fieldName:
 * <ul>
 *   <li>{@code pendingInvoices} (GET) — delegates to {@link PendingInvoicesHandler}</li>
 *   <li>{@code applyToInvoices} (POST) — delegates to {@link ApplyToInvoicesHandler}</li>
 * </ul>
 * All other actions (aPRMProcessPayment, aPRMReversePayment, etc.) pass through
 * to default NeoProcessService handling by returning {@code null}.
 */
@Named("paymentInHandler")
public class PaymentInHandler implements NeoHandler {

  private final PendingInvoicesHandler pendingInvoicesHandler = new PendingInvoicesHandler();
  private final ApplyToInvoicesHandler applyToInvoicesHandler = new ApplyToInvoicesHandler();

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.ACTION) {
      return null;
    }

    String fieldName = context.getFieldName();
    if ("pendingInvoices".equals(fieldName)) {
      return pendingInvoicesHandler.handle(context);
    }
    if ("applyToInvoices".equals(fieldName)) {
      return applyToInvoicesHandler.handle(context);
    }

    // Pass through to default process handling (aPRMProcessPayment, etc.)
    return null;
  }
}
