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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;

/**
 * Bulk-loads {@link FinAccPaymentMethod} link rows for {@link PaymentRegistrationService
 * #handleListAccounts}, grouped by financial account (ETP-5434). Extracted out of
 * {@code PaymentRegistrationService} purely to keep that class under Sonar's method-count limit
 * (S1448) — no behavior changed by the move.
 */
final class PaymentAccountMethodsLoader {

  // ETP-5434: chunk size for the IN list of the grouped FinAccPaymentMethod query (see
  // loadAllowedMethodsByAccount). One invoice's org tree realistically holds a few dozen financial
  // accounts, so a single chunk is the normal case; the split exists only so a pathological tenant
  // cannot build an unbounded IN list — Hibernate re-plans the statement for every distinct
  // parameter count, so an ever-growing IN also pollutes the query-plan cache.
  private static final int ACCOUNT_ID_CHUNK_SIZE = 500;

  private PaymentAccountMethodsLoader() {
  }

  /**
   * Loads the {@link FinAccPaymentMethod} link rows allowed for the given direction across ALL the
   * listed accounts at once, grouped by account id (ETP-5434). Replaces the per-account query that
   * used to run inside {@link PaymentRegistrationService#appendAccountItem}, so the query count no
   * longer grows with the number of financial accounts in the invoice's org tree.
   *
   * <p><b>Deliberately an {@link OBCriteria} and NOT hand-written HQL.</b> The per-account criteria
   * this replaces was relying on three filters that {@code OBCriteria#initialize()} adds by itself
   * and that a raw {@code createQuery(...)} does NOT inherit: {@code isActive = 'Y'}
   * ({@code filterOnActive} defaults to {@code true}), the readable-clients filter and the
   * readable-organizations filter. Porting this to HQL to "make it one query" would silently start
   * returning inactive link rows — and rows belonging to other clients — which is a data and
   * security regression, not a speed-up. None of the three is touched here, so the row set is
   * exactly the union of the per-account queries it replaces.
   *
   * <p>The order is pinned by payment-method name so that {@code defaultPaymentMethod} — the first
   * element of an account's list, see {@link PaymentRegistrationService#appendAccountItem} — is
   * deterministic; the replaced per-account query had no {@code order by} at all, leaving that pick
   * to the engine. The join {@code addOrderBy} creates for the dotted path is an INNER join, which
   * is safe here: {@code FIN_FINACC_PAYMENTMETHOD.FIN_PAYMENTMETHOD_ID} is {@code NOT NULL} and
   * carries a foreign key, so it cannot drop a link row. It also does not constrain the payment
   * method's own {@code isActive} — {@code OBCriteria} only applies that to the root entity — which
   * is precisely the previous behaviour.
   */
  static Map<String, List<FinAccPaymentMethod>> loadAllowedMethodsByAccount(
      List<FIN_FinancialAccount> accounts, String allowProp) {
    Map<String, List<FinAccPaymentMethod>> byAccountId = new HashMap<>();
    List<String> accountIds = new ArrayList<>(accounts.size());
    for (FIN_FinancialAccount acc : accounts) {
      accountIds.add(acc.getId());
    }
    for (int from = 0; from < accountIds.size(); from += ACCOUNT_ID_CHUNK_SIZE) {
      List<String> chunk = accountIds.subList(from,
          Math.min(from + ACCOUNT_ID_CHUNK_SIZE, accountIds.size()));
      OBCriteria<FinAccPaymentMethod> methodCrit = OBDal.getInstance()
          .createCriteria(FinAccPaymentMethod.class);
      methodCrit.add(Restrictions.in(FinAccPaymentMethod.PROPERTY_ACCOUNT + ".id", chunk));
      methodCrit.add(Restrictions.eq(allowProp, Boolean.TRUE));
      methodCrit.addOrderBy(
          FinAccPaymentMethod.PROPERTY_PAYMENTMETHOD + "." + FIN_PaymentMethod.PROPERTY_NAME, true);
      for (FinAccPaymentMethod fapm : methodCrit.list()) {
        // getAccount() costs no round trip: every account reachable here was just loaded by the
        // caller's own query in this same session, so the proxy resolves from the first-level cache.
        byAccountId.computeIfAbsent(fapm.getAccount().getId(), id -> new ArrayList<>()).add(fapm);
      }
    }
    return byAccountId;
  }
}
