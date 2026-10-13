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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;

/**
 * Unit tests for {@link PostingAccountCombinations} (ETP-5681): the account catalog of the
 * Esquema contable and financial-account accounting tabs lists only active combinations of the
 * ledger whose account is an active POSTING (non-summary) account.
 *
 * @covers com.etendoerp.go.schemaforge.util.PostingAccountCombinations
 */
class PostingAccountCombinationsTest {

  private OBDal obDal;
  private MockedStatic<OBDal> obDalMock;
  private OBCriteria<AccountingCombination> criteria;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    obDal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(AccountingCombination.class)).thenReturn(criteria);
    when(criteria.add(any())).thenReturn(criteria);
    when(criteria.addOrder(any())).thenReturn(criteria);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  @Test
  @DisplayName("Restricts to the ledger's active combinations on active posting accounts")
  void restrictsToActivePostingAccountsOfTheLedger() {
    AcctSchema ledger = mock(AcctSchema.class);
    List<AccountingCombination> rows = Collections.singletonList(mock(AccountingCombination.class));
    when(criteria.list()).thenReturn(rows);

    assertSame(rows, PostingAccountCombinations.forSchema(ledger));

    verify(criteria).createAlias(AccountingCombination.PROPERTY_ACCOUNT, "acct");
    ArgumentCaptor<Criterion> added = ArgumentCaptor.forClass(Criterion.class);
    verify(criteria, atLeastOnce()).add(added.capture());
    String restrictions = added.getAllValues().stream().map(Object::toString)
        .collect(Collectors.joining(" | "));
    assertTrue(restrictions.contains("acct.summaryLevel=false"), restrictions);
    assertTrue(restrictions.contains("acct.active=true"), restrictions);
    assertTrue(restrictions.contains("active=true"), restrictions);
    assertTrue(restrictions.contains("accountingSchema="), restrictions);
    verify(criteria).addOrder(any());
  }
}
