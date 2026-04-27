/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.calendar.Calendar;

/** Unit tests for {@link AccountingPackageResolver} and selected {@link AccountingPackageCloner} invariants. */
public class AccountingPackageResolverAndClonerTest {
  private static final String CLIENT_ID = "CLIENT-1";
  private static final String CURRENCY_ID = "CURRENCY-1";
  private static final String SOURCE_ORG_ID = "SOURCE-ORG";

  @Test
  public void testResolverFindsReadyLegalAccountingOrganizationsForCurrency() {
    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<Organization> query = mock(OBQuery.class);
    Organization sourceOrganization = sourceOrganization(SOURCE_ORG_ID, mock(AcctSchema.class),
        mock(Calendar.class));
    when(obDal.createQuery(eq(Organization.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceOrganization));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      List<AccountingPackageCandidate> candidates = new AccountingPackageResolver()
          .resolve(CLIENT_ID, CURRENCY_ID);

      assertEquals(1, candidates.size());
      assertSame(sourceOrganization, candidates.get(0).getSourceOrganization());
      assertSame(sourceOrganization.getGeneralLedger(), candidates.get(0).getLedger());
      assertSame(sourceOrganization.getCalendar(), candidates.get(0).getCalendar());
      verify(query).setNamedParameter("clientId", CLIENT_ID);
      verify(query).setNamedParameter("currencyId", CURRENCY_ID);
      verify(query).setFilterOnReadableClients(false);
      verify(query).setFilterOnReadableOrganization(false);
    }
  }

  @Test
  public void testClonerSanitizesUnsafeSourceOrganizationScopedCombinationDimensions()
      throws Exception {
    AccountingCombination combination = mock(AccountingCombination.class);
    Product product = orgScopedReference(Product.class, SOURCE_ORG_ID);
    BusinessPartner businessPartner = orgScopedReference(BusinessPartner.class, SOURCE_ORG_ID);
    when(combination.getProduct()).thenReturn(product);
    when(combination.getBusinessPartner()).thenReturn(businessPartner);

    boolean stripped = sanitizeCombinationDimensions(combination, SOURCE_ORG_ID);

    assertTrue(stripped);
    verify(combination).setProduct(null);
    verify(combination).setBusinessPartner(null);
  }

  @Test
  public void testClonerKeepsCombinationDimensionsOutsideSourceOrganization() throws Exception {
    AccountingCombination combination = mock(AccountingCombination.class);
    Product otherOrgReference = orgScopedReference(Product.class, "OTHER-ORG");
    when(combination.getProduct()).thenReturn(otherOrgReference);

    boolean stripped = sanitizeCombinationDimensions(combination, SOURCE_ORG_ID);

    org.junit.Assert.assertFalse(stripped);
    org.mockito.Mockito.verify(combination, org.mockito.Mockito.never()).setProduct(null);
  }

  private boolean sanitizeCombinationDimensions(AccountingCombination combination, String sourceOrgId)
      throws Exception {
    Method method = AccountingPackageCloner.class.getDeclaredMethod("sanitizeCombinationDimensions",
        AccountingCombination.class, String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(new AccountingPackageCloner(), combination, sourceOrgId);
  }

  private Organization sourceOrganization(String id, AcctSchema ledger, Calendar calendar) {
    Organization organization = mock(Organization.class);
    when(organization.getId()).thenReturn(id);
    when(organization.getGeneralLedger()).thenReturn(ledger);
    when(organization.getCalendar()).thenReturn(calendar);
    return organization;
  }

  private <T extends BaseOBObject> T orgScopedReference(Class<T> referenceClass, String orgId) {
    T reference = mock(referenceClass);
    Entity entity = mock(Entity.class);
    Organization organization = mock(Organization.class);
    when(entity.hasProperty("organization")).thenReturn(true);
    when(reference.getEntity()).thenReturn(entity);
    when(reference.get("organization")).thenReturn(organization);
    when(organization.getId()).thenReturn(orgId);
    return reference;
  }
}
