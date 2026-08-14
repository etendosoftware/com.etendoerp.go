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

import java.util.List;

import javax.enterprise.context.ApplicationScoped;

import org.openbravo.advpaymentmngt.FundsTransferPostProcessHook;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinaccTransaction;

/**
 * Makes the funds-transfer link between two financial accounts navigable in BOTH directions.
 *
 * <p>Classic only sets the backwards half: the deposit ({@code BPD}) created in the destination
 * account points at the withdrawal ({@code BPW}) of the source through
 * {@code EM_APRM_FINACC_TRANS_ORIGIN}. From the source transaction there was no way to tell where
 * the money went. This hook closes the loop by setting the mirror column
 * {@code EM_ETGO_FINACC_TRANS_DEST} on the source transaction.
 *
 * <p>Runs for both transfer entry points, since Etendo Go's {@code POST ?action=transfer} delegates
 * to the very same {@code FundsTransferActionHandler.createTransfer} that the Classic popup uses.
 */
@ApplicationScoped
public class FundsTransferDestinationHook implements FundsTransferPostProcessHook {

  /** Deposit leg — the transaction created in the DESTINATION account. */
  private static final String BP_DEPOSIT = "BPD";

  @Override
  public void exec(List<FIN_FinaccTransaction> transactions) throws Exception {
    if (transactions == null) {
      return;
    }
    for (FIN_FinaccTransaction target : transactions) {
      // Only a BPD with an origin is the deposit leg of a transfer: filtering by BPD keeps the
      // destination-side bank fee (BF, which also carries an origin) from overwriting the link
      // with the wrong counterpart; a null origin means a plain deposit, not part of a transfer.
      if (!BP_DEPOSIT.equals(target.getTransactionType())) {
        continue;
      }
      FIN_FinaccTransaction origin = target.getAprmFinaccTransOrigin();
      if (origin != null) {
        origin.setETGOFinaccTransDest(target);
        OBDal.getInstance().save(origin);
      }
    }
    OBDal.getInstance().flush();
  }
}
