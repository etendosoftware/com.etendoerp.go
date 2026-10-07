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

import java.math.BigDecimal;

import org.apache.commons.lang3.StringUtils;

/**
 * The original-currency trio Core stores on a {@code FIN_FINACC_TRANSACTION} whose payment crossed
 * currencies: {@code FOREIGN_AMOUNT}, the ISO code of {@code FOREIGN_CURRENCY_ID} and
 * {@code FOREIGN_CONVERT_RATE} (the same rate Core writes to the transaction's
 * {@code C_Conversion_Rate_Document}). Shared by every read that re-exposes it — the
 * reconciliation candidates ({@link CandidatesSupport#appendForeignOriginal}), the statement-line
 * transactions ({@link BankStatementsSupport#appendTxnForeignOriginal}) and the Movements list
 * ({@link FinancialAccountTransactionsSupport#putForeignOriginal}) — so the "is this a foreign pair"
 * rule lives once.
 *
 * <p>Structural only: it compares two currency codes, never an entity or a currency by name.
 *
 * @param storedAmount the {@code FOREIGN_AMOUNT} exactly as Core stored it (a magnitude: Core
 *                     writes it unsigned for deposits and withdrawals alike)
 * @param currencyIso  the foreign currency's ISO code
 * @param rate         the {@code FOREIGN_CONVERT_RATE}, or {@code null} when none is stored
 */
record ForeignOriginal(BigDecimal storedAmount, String currencyIso, BigDecimal rate) {

  /**
   * The trio, or {@code null} when the transaction is not a foreign pair: no foreign currency, no
   * known account currency, the same currency as the account, or no stored foreign amount.
   */
  static ForeignOriginal of(String foreignIso, String accountIso, BigDecimal foreignAmount,
      BigDecimal rate) {
    String foreign = StringUtils.trimToEmpty(foreignIso);
    String account = StringUtils.trimToEmpty(accountIso);
    if (foreign.isEmpty() || account.isEmpty() || foreign.equals(account)
        || foreignAmount == null) {
      return null;
    }
    return new ForeignOriginal(foreignAmount, foreign, rate);
  }

  /** The stored magnitude with the sign of {@code signedBase} (negative for an outflow). */
  BigDecimal signedLike(BigDecimal signedBase) {
    return signedBase.signum() < 0 ? storedAmount.abs().negate() : storedAmount.abs();
  }
}
