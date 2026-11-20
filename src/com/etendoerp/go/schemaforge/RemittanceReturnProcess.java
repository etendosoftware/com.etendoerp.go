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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import org.codehaus.jettison.json.JSONArray;
import org.openbravo.model.financialmgmt.payment.FIN_Payment;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.module.remittance.Remittance;

import com.etendoerp.automated.remittance.process.AutomatedReturnProcess;

/**
 * ETP-6247 — the remittance module's own return, {@link AutomatedReturnProcess}, run without its
 * HTTP action handler. Its work method is {@code protected}, so this subclass only exposes it; no
 * logic of the module is copied or changed. What the module does for the Cancel (settlement) rows
 * it is given: unposts and undoes each settlement ({@code REM_RemittanceSettleUndo}), creates ONE
 * negative payment for the total, writes the return rows ({@code REM_SettleProtest#returnPayment})
 * and voids the original payments, which gives the invoices' dues back as pending.
 *
 * <p>One override: the module looks its return payment method up by flag and organization
 * {@code 0} without a client filter, so in admin mode it could pick ANOTHER tenant's method. This
 * subclass hands it the remittance client's own ({@link
 * RemittanceSepaProvisioning#ensureReturnPaymentMethod}).
 */
final class RemittanceReturnProcess extends AutomatedReturnProcess {

  private final FIN_PaymentMethod returnMethod;

  RemittanceReturnProcess(FIN_PaymentMethod returnMethod) {
    this.returnMethod = returnMethod;
  }

  /**
   * Returns the payments of the given settlement rows.
   *
   * @param cancelIds        ids of the processed {@code REM_RemittanceLine_Cancel} rows
   * @param date             the return date, {@code yyyy-MM-dd}
   * @param remittance       the remittance
   * @param paymentReference a payment of the remittance, the model of the negative one
   * @return the negative payment
   */
  FIN_Payment returnPayments(JSONArray cancelIds, String date, Remittance remittance,
      FIN_Payment paymentReference) throws Exception {
    return processRemittanceLineCancel(cancelIds, date, remittance, paymentReference);
  }

  @Override
  protected FIN_PaymentMethod getPaymentMethod() {
    return returnMethod;
  }
}
