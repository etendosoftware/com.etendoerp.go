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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.schemaforge.util;

import java.util.List;

import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;

/**
 * ETP-5681: the account combinations a user may pick for an accounting field of a ledger — the
 * active combinations of that accounting schema whose account is a POSTING account (a leaf of the
 * chart of accounts, {@code IsSummary = 'N'}, active).
 *
 * <p>Shared by the catalogs that hand the UI a full account list instead of going through the
 * generic {@code /selectors} endpoint: the Esquema contable aggregate
 * ({@code GeneralLedgerConfigurationHandler}) and the financial-account accounting tab
 * ({@code FinancialAccountAccountingHandler}). The generic selectors apply the same rule through
 * {@code PostingAccountCombinationSelectorPolicy}.
 */
public final class PostingAccountCombinations {

  private static final String ACCOUNT_ALIAS = "acct";

  private PostingAccountCombinations() {
  }

  /**
   * Active combinations of {@code schema} whose account is an active posting (non-summary)
   * account, ordered by combination.
   *
   * @param schema the accounting schema (ledger); must not be {@code null}
   * @return the selectable combinations, possibly empty
   */
  public static List<AccountingCombination> forSchema(AcctSchema schema) {
    OBCriteria<AccountingCombination> criteria = OBDal.getInstance().createCriteria(AccountingCombination.class);
    criteria.createAlias(AccountingCombination.PROPERTY_ACCOUNT, ACCOUNT_ALIAS);
    criteria.add(Restrictions.eq(AccountingCombination.PROPERTY_ACCOUNTINGSCHEMA, schema));
    criteria.add(Restrictions.eq(AccountingCombination.PROPERTY_ACTIVE, true));
    criteria.add(Restrictions.eq(ACCOUNT_ALIAS + "." + ElementValue.PROPERTY_SUMMARYLEVEL, false));
    criteria.add(Restrictions.eq(ACCOUNT_ALIAS + "." + ElementValue.PROPERTY_ACTIVE, true));
    criteria.addOrder(Order.asc(AccountingCombination.PROPERTY_COMBINATION));
    return criteria.list();
  }
}
