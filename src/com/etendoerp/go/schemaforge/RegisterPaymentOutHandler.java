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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NeoHandler that registers a payment against a Purchase Invoice installment.
 * Invoked as:
 *   POST /sws/neo/purchase-invoice/header/{invoiceId}/action/registerPayment
 *   POST /sws/neo/purchase-invoice/header/{invoiceId}/action/invoicePayments
 *   POST /sws/neo/purchase-invoice/header/{invoiceId}/action/invoiceAccounts
 *
 * All logic is delegated to {@link PaymentRegistrationService}.
 */
@Named("registerPaymentOutHandler")
public class RegisterPaymentOutHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(RegisterPaymentOutHandler.class);

  @Override
  public NeoResponse handle(NeoContext context) {
    return PaymentActionHandlerSupport.handle(context, false, log);
  }
}
