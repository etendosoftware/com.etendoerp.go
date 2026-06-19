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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.model.Entity;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationAcctSchema;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.tax.TaxCategory;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.financialmgmt.tax.TaxRateAccounts;
import org.openbravo.model.financialmgmt.tax.TaxZone;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.marketing.Campaign;
import org.openbravo.model.materialmgmt.cost.ABCActivity;
import org.openbravo.model.project.Project;
import org.openbravo.model.sales.SalesRegion;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.accounting.UserDimension1;
import org.openbravo.model.financialmgmt.accounting.UserDimension2;

/** Unit tests for {@link AccountingPackageCloner}. */
public class AccountingPackageClonerTest {
  private static final String SOURCE_ORG_ID = "SOURCE-ORG";
  private static final String TARGET_ORG_ID = "TARGET-ORG";
  private static final String LEDGER_ID = "LEDGER-1";

  // -- validateNamedParameters ------------------------------------------

  @Test
  public void testValidateNamedParametersAcceptsEvenNumberOfArgs() throws Exception {
    Method method = AccountingPackageCloner.class.getDeclaredMethod("validateNamedParameters",
        Object[].class);
    method.setAccessible(true);
    method.invoke(new AccountingPackageCloner(), new Object[] { new Object[] { "key1", "val1" } });
  }

  @Test
  public void testValidateNamedParametersAcceptsEmptyArgs() throws Exception {
    Method method = AccountingPackageCloner.class.getDeclaredMethod("validateNamedParameters",
        Object[].class);
    method.setAccessible(true);
    method.invoke(new AccountingPackageCloner(), new Object[] { new Object[] {} });
  }

  @Test
  public void testValidateNamedParametersRejectsOddNumberOfArgs() throws Exception {
    Method method = AccountingPackageCloner.class.getDeclaredMethod("validateNamedParameters",
        Object[].class);
    method.setAccessible(true);
    try {
      method.invoke(new AccountingPackageCloner(), new Object[] { new Object[] { "key1" } });
      fail("Expected IllegalArgumentException for odd number of parameters");
    } catch (InvocationTargetException e) {
      assertTrue(e.getCause() instanceof IllegalArgumentException);
      assertTrue(e.getCause().getMessage().contains("pairs"));
    }
  }

  @Test
  public void testValidateNamedParametersRejectsThreeArgs() throws Exception {
    Method method = AccountingPackageCloner.class.getDeclaredMethod("validateNamedParameters",
        Object[].class);
    method.setAccessible(true);
    try {
      method.invoke(new AccountingPackageCloner(),
          new Object[] { new Object[] { "key1", "val1", "key2" } });
      fail("Expected IllegalArgumentException");
    } catch (InvocationTargetException e) {
      assertTrue(e.getCause() instanceof IllegalArgumentException);
    }
  }

  // -- isUnsafeOrgScopedReference ---------------------------------------

  @Test
  public void testIsUnsafeReturnsFalseForNull() throws Exception {
    boolean result = invokeIsUnsafe(null, SOURCE_ORG_ID);
    assertFalse(result);
  }

  @Test
  public void testIsUnsafeReturnsFalseWhenEntityHasNoOrganizationProperty() throws Exception {
    BaseOBObject reference = mock(BaseOBObject.class);
    Entity entity = mock(Entity.class);
    when(reference.getEntity()).thenReturn(entity);
    when(entity.hasProperty("organization")).thenReturn(false);

    boolean result = invokeIsUnsafe(reference, SOURCE_ORG_ID);
    assertFalse(result);
  }

  @Test
  public void testIsUnsafeReturnsFalseWhenOrganizationValueIsNotOrganizationType()
      throws Exception {
    BaseOBObject reference = mock(BaseOBObject.class);
    Entity entity = mock(Entity.class);
    when(reference.getEntity()).thenReturn(entity);
    when(entity.hasProperty("organization")).thenReturn(true);
    when(reference.get("organization")).thenReturn("not-an-organization-object");

    boolean result = invokeIsUnsafe(reference, SOURCE_ORG_ID);
    assertFalse(result);
  }

  @Test
  public void testIsUnsafeReturnsTrueWhenReferenceBelongsToSourceOrg() throws Exception {
    Product product = orgScopedReference(Product.class, SOURCE_ORG_ID);

    boolean result = invokeIsUnsafe(product, SOURCE_ORG_ID);
    assertTrue(result);
  }

  @Test
  public void testIsUnsafeReturnsFalseWhenReferenceBelongsToDifferentOrg() throws Exception {
    Product product = orgScopedReference(Product.class, "DIFFERENT-ORG");

    boolean result = invokeIsUnsafe(product, SOURCE_ORG_ID);
    assertFalse(result);
  }

  // -- sanitizeCombinationDimensions ------------------------------------

  @Test
  public void testSanitizeStripsAllSourceOrgDimensions() throws Exception {
    AccountingCombination combination = mock(AccountingCombination.class);
    stubAllDimensions(combination, SOURCE_ORG_ID);

    boolean stripped = invokeSanitize(combination, SOURCE_ORG_ID);

    assertTrue(stripped);
    verify(combination).setTrxOrganization(null);
    verify(combination).setProduct(null);
    verify(combination).setBusinessPartner(null);
    verify(combination).setLocationFromAddress(null);
    verify(combination).setLocationToAddress(null);
    verify(combination).setSalesRegion(null);
    verify(combination).setProject(null);
    verify(combination).setSalesCampaign(null);
    verify(combination).setActivity(null);
    verify(combination).setStDimension(null);
    verify(combination).setNdDimension(null);
  }

  @Test
  public void testSanitizeReturnsFalseWhenAllDimensionsAreNull() throws Exception {
    AccountingCombination combination = mock(AccountingCombination.class);

    boolean stripped = invokeSanitize(combination, SOURCE_ORG_ID);

    assertFalse(stripped);
  }

  @Test
  public void testSanitizeKeepsDimensionsFromDifferentOrg() throws Exception {
    AccountingCombination combination = mock(AccountingCombination.class);
    Product product = orgScopedReference(Product.class, "OTHER-ORG");
    when(combination.getProduct()).thenReturn(product);

    boolean stripped = invokeSanitize(combination, SOURCE_ORG_ID);

    assertFalse(stripped);
    verify(combination, never()).setProduct(null);
  }

  @Test
  public void testSanitizePartialStripWhenMixedOrgs() throws Exception {
    AccountingCombination combination = mock(AccountingCombination.class);
    Product sourceProduct = orgScopedReference(Product.class, SOURCE_ORG_ID);
    BusinessPartner otherBP = orgScopedReference(BusinessPartner.class, "OTHER-ORG");
    when(combination.getProduct()).thenReturn(sourceProduct);
    when(combination.getBusinessPartner()).thenReturn(otherBP);

    boolean stripped = invokeSanitize(combination, SOURCE_ORG_ID);

    assertTrue(stripped);
    verify(combination).setProduct(null);
    verify(combination, never()).setBusinessPartner(null);
  }

  // -- cloneDerivedCombination ------------------------------------------

  @Test
  public void testCloneDerivedCombinationReturnsNullForNullSource() throws Exception {
    AccountingCombination result = invokeCloneDerivedCombination(null, mock(Organization.class),
        mock(AcctSchema.class), new HashMap<>(), SOURCE_ORG_ID);

    assertNull(result);
  }

  @Test
  public void testCloneDerivedCombinationReturnsCachedClone() throws Exception {
    AccountingCombination sourceCombination = mock(AccountingCombination.class);
    when(sourceCombination.getId()).thenReturn("COMBO-1");
    AccountingCombination cachedClone = mock(AccountingCombination.class);
    Map<String, AccountingCombination> cache = new HashMap<>();
    cache.put("COMBO-1", cachedClone);

    AccountingCombination result = invokeCloneDerivedCombination(sourceCombination,
        mock(Organization.class), mock(AcctSchema.class), cache, SOURCE_ORG_ID);

    assertSame(cachedClone, result);
  }

  @Test
  public void testCloneDerivedCombinationClonesAndCaches() throws Exception {
    AccountingCombination sourceCombination = mock(AccountingCombination.class);
    when(sourceCombination.getId()).thenReturn("COMBO-1");
    AccountingCombination clonedCombination = mock(AccountingCombination.class);
    Organization targetOrg = mock(Organization.class);
    when(targetOrg.getClient()).thenReturn(mock(org.openbravo.model.ad.system.Client.class));
    AcctSchema targetLedger = mock(AcctSchema.class);
    Map<String, AccountingCombination> cache = new HashMap<>();

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
         MockedStatic<DalUtil> dalUtilStatic = mockStatic(DalUtil.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dalUtilStatic.when(() -> DalUtil.copy(sourceCombination, false))
          .thenReturn(clonedCombination);

      AccountingCombination result = invokeCloneDerivedCombination(sourceCombination, targetOrg,
          targetLedger, cache, SOURCE_ORG_ID);

      assertSame(clonedCombination, result);
      assertSame(clonedCombination, cache.get("COMBO-1"));
      verify(clonedCombination).setClient(targetOrg.getClient());
      verify(clonedCombination).setOrganization(targetOrg);
      verify(clonedCombination).setAccountingSchema(targetLedger);
      verify(obDal).save(clonedCombination);
    }
  }

  @Test
  public void testCloneDerivedCombinationClearsMetadataWhenDimensionsStripped() throws Exception {
    AccountingCombination sourceCombination = mock(AccountingCombination.class);
    when(sourceCombination.getId()).thenReturn("COMBO-2");

    AccountingCombination clonedCombination = mock(AccountingCombination.class);
    Product sourceProduct = orgScopedReference(Product.class, SOURCE_ORG_ID);
    when(clonedCombination.getProduct()).thenReturn(sourceProduct);

    Organization targetOrg = mock(Organization.class);
    when(targetOrg.getClient()).thenReturn(mock(org.openbravo.model.ad.system.Client.class));
    AcctSchema targetLedger = mock(AcctSchema.class);
    Map<String, AccountingCombination> cache = new HashMap<>();

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
         MockedStatic<DalUtil> dalUtilStatic = mockStatic(DalUtil.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dalUtilStatic.when(() -> DalUtil.copy(sourceCombination, false))
          .thenReturn(clonedCombination);

      AccountingCombination result = invokeCloneDerivedCombination(sourceCombination, targetOrg,
          targetLedger, cache, SOURCE_ORG_ID);

      assertSame(clonedCombination, result);
      verify(clonedCombination).setAlias(null);
      verify(clonedCombination).setCombination(null);
      verify(clonedCombination).setDescription(null);
      verify(clonedCombination).setFullyQualified(false);
    }
  }

  // -- wireOrganization -------------------------------------------------

  @Test
  public void testWireOrganizationSetsAllFieldsAndSaves() throws Exception {
    Organization targetOrg = mock(Organization.class);
    AcctSchema ledger = mock(AcctSchema.class);
    org.openbravo.model.common.currency.Currency currency =
        mock(org.openbravo.model.common.currency.Currency.class);
    when(ledger.getCurrency()).thenReturn(currency);
    Calendar calendar = mock(Calendar.class);
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        mock(Organization.class), ledger, calendar);

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      invokeWireOrganization(targetOrg, ledger, candidate);

      verify(targetOrg).setCurrency(currency);
      verify(targetOrg).setGeneralLedger(ledger);
      verify(targetOrg).setCalendar(calendar);
      verify(targetOrg).setAllowPeriodControl(true);
      verify(obDal).save(targetOrg);
    }
  }

  // -- ensureOrganizationAcctSchema -------------------------------------

  @Test
  public void testEnsureOrganizationAcctSchemaDoesNothingWhenExists() throws Exception {
    Organization targetOrg = mock(Organization.class);
    when(targetOrg.getId()).thenReturn(TARGET_ORG_ID);
    AcctSchema ledger = mock(AcctSchema.class);
    when(ledger.getId()).thenReturn(LEDGER_ID);
    OrganizationAcctSchema existing = mock(OrganizationAcctSchema.class);

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<OrganizationAcctSchema> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(OrganizationAcctSchema.class), anyString())).thenReturn(query);
    when(query.uniqueResult()).thenReturn(existing);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      invokeEnsureOrganizationAcctSchema(targetOrg, ledger);

      verify(obDal, never()).save(any(OrganizationAcctSchema.class));
    }
  }

  @Test
  public void testEnsureOrganizationAcctSchemaCreatesNewWhenNotExists() throws Exception {
    Organization targetOrg = mock(Organization.class);
    when(targetOrg.getId()).thenReturn(TARGET_ORG_ID);
    when(targetOrg.getClient()).thenReturn(mock(org.openbravo.model.ad.system.Client.class));
    AcctSchema ledger = mock(AcctSchema.class);
    when(ledger.getId()).thenReturn(LEDGER_ID);
    OrganizationAcctSchema newSchema = mock(OrganizationAcctSchema.class);

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<OrganizationAcctSchema> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(OrganizationAcctSchema.class), anyString())).thenReturn(query);
    when(query.uniqueResult()).thenReturn(null);

    OBProvider obProvider = mock(OBProvider.class);
    when(obProvider.get(OrganizationAcctSchema.class)).thenReturn(newSchema);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
         MockedStatic<OBProvider> obProviderStatic = mockStatic(OBProvider.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      obProviderStatic.when(OBProvider::getInstance).thenReturn(obProvider);

      invokeEnsureOrganizationAcctSchema(targetOrg, ledger);

      verify(newSchema).setClient(targetOrg.getClient());
      verify(newSchema).setOrganization(targetOrg);
      verify(newSchema).setAccountingSchema(ledger);
      verify(obDal).save(newSchema);
    }
  }

  // -- cloneTaxCategories -----------------------------------------------

  @Test
  public void testCloneTaxCategoriesReturnsEmptyMapWhenNoSource() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    Organization targetOrg = mock(Organization.class);
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, mock(AcctSchema.class), mock(Calendar.class));

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxCategory> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxCategory.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      @SuppressWarnings("unchecked")
      Map<String, TaxCategory> result = (Map<String, TaxCategory>) invokePrivateMethod(
          "cloneTaxCategories",
          new Class<?>[] { AccountingPackageCandidate.class, Organization.class },
          candidate, targetOrg);

      assertNotNull(result);
      assertTrue(result.isEmpty());
    }
  }

  @Test
  public void testCloneTaxCategoriesClonesAndMapsById() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    Organization targetOrg = mock(Organization.class);
    when(targetOrg.getClient()).thenReturn(mock(org.openbravo.model.ad.system.Client.class));
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, mock(AcctSchema.class), mock(Calendar.class));

    TaxCategory sourceTaxCat = mock(TaxCategory.class);
    when(sourceTaxCat.getId()).thenReturn("TC-1");
    TaxCategory clonedTaxCat = mock(TaxCategory.class);

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxCategory> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxCategory.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceTaxCat));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
         MockedStatic<DalUtil> dalUtilStatic = mockStatic(DalUtil.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dalUtilStatic.when(() -> DalUtil.copy(sourceTaxCat, false)).thenReturn(clonedTaxCat);

      @SuppressWarnings("unchecked")
      Map<String, TaxCategory> result = (Map<String, TaxCategory>) invokePrivateMethod(
          "cloneTaxCategories",
          new Class<?>[] { AccountingPackageCandidate.class, Organization.class },
          candidate, targetOrg);

      assertEquals(1, result.size());
      assertSame(clonedTaxCat, result.get("TC-1"));
      verify(clonedTaxCat).setClient(targetOrg.getClient());
      verify(clonedTaxCat).setOrganization(targetOrg);
      verify(obDal).save(clonedTaxCat);
    }
  }

  // -- cloneBusinessPartnerTaxCategories --------------------------------

  @Test
  public void testCloneBusinessPartnerTaxCategoriesReturnsEmptyMapWhenNoSource() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    Organization targetOrg = mock(Organization.class);
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, mock(AcctSchema.class), mock(Calendar.class));

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<org.openbravo.model.common.businesspartner.TaxCategory> query = mock(OBQuery.class);
    when(obDal.createQuery(
        eq(org.openbravo.model.common.businesspartner.TaxCategory.class), anyString()))
        .thenReturn(query);
    when(query.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      @SuppressWarnings("unchecked")
      Map<String, org.openbravo.model.common.businesspartner.TaxCategory> result =
          (Map<String, org.openbravo.model.common.businesspartner.TaxCategory>)
              invokePrivateMethod("cloneBusinessPartnerTaxCategories",
                  new Class<?>[] { AccountingPackageCandidate.class, Organization.class },
                  candidate, targetOrg);

      assertNotNull(result);
      assertTrue(result.isEmpty());
    }
  }

  @Test
  public void testCloneBusinessPartnerTaxCategoriesClonesAndMaps() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    Organization targetOrg = mock(Organization.class);
    when(targetOrg.getClient()).thenReturn(mock(org.openbravo.model.ad.system.Client.class));
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, mock(AcctSchema.class), mock(Calendar.class));

    org.openbravo.model.common.businesspartner.TaxCategory sourceBPTaxCat =
        mock(org.openbravo.model.common.businesspartner.TaxCategory.class);
    when(sourceBPTaxCat.getId()).thenReturn("BPTC-1");
    org.openbravo.model.common.businesspartner.TaxCategory clonedBPTaxCat =
        mock(org.openbravo.model.common.businesspartner.TaxCategory.class);

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<org.openbravo.model.common.businesspartner.TaxCategory> query = mock(OBQuery.class);
    when(obDal.createQuery(
        eq(org.openbravo.model.common.businesspartner.TaxCategory.class), anyString()))
        .thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceBPTaxCat));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
         MockedStatic<DalUtil> dalUtilStatic = mockStatic(DalUtil.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dalUtilStatic.when(() -> DalUtil.copy(sourceBPTaxCat, false)).thenReturn(clonedBPTaxCat);

      @SuppressWarnings("unchecked")
      Map<String, org.openbravo.model.common.businesspartner.TaxCategory> result =
          (Map<String, org.openbravo.model.common.businesspartner.TaxCategory>)
              invokePrivateMethod("cloneBusinessPartnerTaxCategories",
                  new Class<?>[] { AccountingPackageCandidate.class, Organization.class },
                  candidate, targetOrg);

      assertEquals(1, result.size());
      assertSame(clonedBPTaxCat, result.get("BPTC-1"));
      verify(clonedBPTaxCat).setClient(targetOrg.getClient());
      verify(clonedBPTaxCat).setOrganization(targetOrg);
      verify(obDal).save(clonedBPTaxCat);
    }
  }

  // -- cloneTaxes -------------------------------------------------------

  @Test
  public void testCloneTaxesMapsTaxCategoryFromCloneMap() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    Organization targetOrg = mock(Organization.class);
    when(targetOrg.getClient()).thenReturn(mock(org.openbravo.model.ad.system.Client.class));
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, mock(AcctSchema.class), mock(Calendar.class));

    TaxCategory sourceTaxCat = mock(TaxCategory.class);
    when(sourceTaxCat.getId()).thenReturn("TC-1");
    TaxCategory clonedTaxCat = mock(TaxCategory.class);
    Map<String, TaxCategory> taxCatMap = new HashMap<>();
    taxCatMap.put("TC-1", clonedTaxCat);

    org.openbravo.model.common.businesspartner.TaxCategory sourceBPTaxCat =
        mock(org.openbravo.model.common.businesspartner.TaxCategory.class);
    when(sourceBPTaxCat.getId()).thenReturn("BPTC-1");
    org.openbravo.model.common.businesspartner.TaxCategory clonedBPTaxCat =
        mock(org.openbravo.model.common.businesspartner.TaxCategory.class);
    Map<String, org.openbravo.model.common.businesspartner.TaxCategory> bpTaxCatMap =
        new HashMap<>();
    bpTaxCatMap.put("BPTC-1", clonedBPTaxCat);

    TaxRate sourceTax = mock(TaxRate.class);
    when(sourceTax.getId()).thenReturn("TAX-1");
    when(sourceTax.getTaxCategory()).thenReturn(sourceTaxCat);
    when(sourceTax.getBusinessPartnerTaxCategory()).thenReturn(sourceBPTaxCat);
    TaxRate clonedTax = mock(TaxRate.class);

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxRate> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxRate.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceTax));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
         MockedStatic<DalUtil> dalUtilStatic = mockStatic(DalUtil.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dalUtilStatic.when(() -> DalUtil.copy(sourceTax, false)).thenReturn(clonedTax);

      @SuppressWarnings("unchecked")
      Map<String, TaxRate> result = (Map<String, TaxRate>) invokePrivateMethod(
          "cloneTaxes",
          new Class<?>[] { AccountingPackageCandidate.class, Organization.class, Map.class,
              Map.class },
          candidate, targetOrg, taxCatMap, bpTaxCatMap);

      assertEquals(1, result.size());
      assertSame(clonedTax, result.get("TAX-1"));
      verify(clonedTax).setTaxCategory(clonedTaxCat);
      verify(clonedTax).setBusinessPartnerTaxCategory(clonedBPTaxCat);
      verify(clonedTax).setParentTaxRate(null);
      verify(clonedTax).setTaxBase(null);
      verify(obDal).save(clonedTax);
    }
  }

  @Test
  public void testCloneTaxesFallsBackToSourceTaxCategoryWhenNotInMap() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    Organization targetOrg = mock(Organization.class);
    when(targetOrg.getClient()).thenReturn(mock(org.openbravo.model.ad.system.Client.class));
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, mock(AcctSchema.class), mock(Calendar.class));

    TaxCategory sourceTaxCat = mock(TaxCategory.class);
    when(sourceTaxCat.getId()).thenReturn("TC-UNMAPPED");

    TaxRate sourceTax = mock(TaxRate.class);
    when(sourceTax.getId()).thenReturn("TAX-1");
    when(sourceTax.getTaxCategory()).thenReturn(sourceTaxCat);
    when(sourceTax.getBusinessPartnerTaxCategory()).thenReturn(null);
    TaxRate clonedTax = mock(TaxRate.class);

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxRate> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxRate.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceTax));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
         MockedStatic<DalUtil> dalUtilStatic = mockStatic(DalUtil.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dalUtilStatic.when(() -> DalUtil.copy(sourceTax, false)).thenReturn(clonedTax);

      invokePrivateMethod(
          "cloneTaxes",
          new Class<?>[] { AccountingPackageCandidate.class, Organization.class, Map.class,
              Map.class },
          candidate, targetOrg, new HashMap<>(), new HashMap<>());

      verify(clonedTax).setTaxCategory(sourceTaxCat);
      verify(clonedTax).setBusinessPartnerTaxCategory(null);
    }
  }

  @Test
  public void testCloneTaxesHandlesNullTaxCategory() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    Organization targetOrg = mock(Organization.class);
    when(targetOrg.getClient()).thenReturn(mock(org.openbravo.model.ad.system.Client.class));
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, mock(AcctSchema.class), mock(Calendar.class));

    TaxRate sourceTax = mock(TaxRate.class);
    when(sourceTax.getId()).thenReturn("TAX-1");
    when(sourceTax.getTaxCategory()).thenReturn(null);
    when(sourceTax.getBusinessPartnerTaxCategory()).thenReturn(null);
    TaxRate clonedTax = mock(TaxRate.class);

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxRate> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxRate.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceTax));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
         MockedStatic<DalUtil> dalUtilStatic = mockStatic(DalUtil.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dalUtilStatic.when(() -> DalUtil.copy(sourceTax, false)).thenReturn(clonedTax);

      invokePrivateMethod(
          "cloneTaxes",
          new Class<?>[] { AccountingPackageCandidate.class, Organization.class, Map.class,
              Map.class },
          candidate, targetOrg, new HashMap<>(), new HashMap<>());

      verify(clonedTax).setTaxCategory(null);
      verify(clonedTax).setBusinessPartnerTaxCategory(null);
    }
  }

  // -- restoreTaxRelationships ------------------------------------------

  @Test
  public void testRestoreTaxRelationshipsLinksParentAndBase() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, mock(AcctSchema.class), mock(Calendar.class));

    TaxRate sourceParent = mock(TaxRate.class);
    when(sourceParent.getId()).thenReturn("PARENT-TAX");
    TaxRate sourceBase = mock(TaxRate.class);
    when(sourceBase.getId()).thenReturn("BASE-TAX");

    TaxRate sourceTax = mock(TaxRate.class);
    when(sourceTax.getId()).thenReturn("TAX-1");
    when(sourceTax.getParentTaxRate()).thenReturn(sourceParent);
    when(sourceTax.getTaxBase()).thenReturn(sourceBase);

    TaxRate clonedTax = mock(TaxRate.class);
    TaxRate clonedParent = mock(TaxRate.class);
    TaxRate clonedBase = mock(TaxRate.class);

    Map<String, TaxRate> taxesBySourceId = new HashMap<>();
    taxesBySourceId.put("TAX-1", clonedTax);
    taxesBySourceId.put("PARENT-TAX", clonedParent);
    taxesBySourceId.put("BASE-TAX", clonedBase);

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxRate> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxRate.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceTax));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      invokePrivateMethod("restoreTaxRelationships",
          new Class<?>[] { AccountingPackageCandidate.class, Map.class },
          candidate, taxesBySourceId);

      verify(clonedTax).setParentTaxRate(clonedParent);
      verify(clonedTax).setTaxBase(clonedBase);
      verify(obDal).save(clonedTax);
    }
  }

  @Test
  public void testRestoreTaxRelationshipsSkipsWhenClonedTaxNotFound() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, mock(AcctSchema.class), mock(Calendar.class));

    TaxRate sourceTax = mock(TaxRate.class);
    when(sourceTax.getId()).thenReturn("TAX-MISSING");

    Map<String, TaxRate> taxesBySourceId = new HashMap<>();

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxRate> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxRate.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceTax));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      invokePrivateMethod("restoreTaxRelationships",
          new Class<?>[] { AccountingPackageCandidate.class, Map.class },
          candidate, taxesBySourceId);

      verify(obDal, never()).save(any());
    }
  }

  @Test
  public void testRestoreTaxRelationshipsFallsBackToSourceParentWhenClonedParentNotFound()
      throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, mock(AcctSchema.class), mock(Calendar.class));

    TaxRate sourceParent = mock(TaxRate.class);
    when(sourceParent.getId()).thenReturn("PARENT-NOT-CLONED");

    TaxRate sourceTax = mock(TaxRate.class);
    when(sourceTax.getId()).thenReturn("TAX-1");
    when(sourceTax.getParentTaxRate()).thenReturn(sourceParent);
    when(sourceTax.getTaxBase()).thenReturn(null);

    TaxRate clonedTax = mock(TaxRate.class);
    Map<String, TaxRate> taxesBySourceId = new HashMap<>();
    taxesBySourceId.put("TAX-1", clonedTax);

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxRate> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxRate.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceTax));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      invokePrivateMethod("restoreTaxRelationships",
          new Class<?>[] { AccountingPackageCandidate.class, Map.class },
          candidate, taxesBySourceId);

      verify(clonedTax).setParentTaxRate(sourceParent);
      verify(obDal).save(clonedTax);
    }
  }

  @Test
  public void testRestoreTaxRelationshipsNoParentNoBase() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, mock(AcctSchema.class), mock(Calendar.class));

    TaxRate sourceTax = mock(TaxRate.class);
    when(sourceTax.getId()).thenReturn("TAX-1");
    when(sourceTax.getParentTaxRate()).thenReturn(null);
    when(sourceTax.getTaxBase()).thenReturn(null);

    TaxRate clonedTax = mock(TaxRate.class);
    Map<String, TaxRate> taxesBySourceId = new HashMap<>();
    taxesBySourceId.put("TAX-1", clonedTax);

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxRate> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxRate.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceTax));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      invokePrivateMethod("restoreTaxRelationships",
          new Class<?>[] { AccountingPackageCandidate.class, Map.class },
          candidate, taxesBySourceId);

      verify(clonedTax, never()).setParentTaxRate(any());
      verify(clonedTax, never()).setTaxBase(any());
      verify(obDal).save(clonedTax);
    }
  }

  // -- cloneTaxZones ----------------------------------------------------

  @Test
  public void testCloneTaxZonesClonesAndLinks() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    Organization targetOrg = mock(Organization.class);
    when(targetOrg.getClient()).thenReturn(mock(org.openbravo.model.ad.system.Client.class));
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, mock(AcctSchema.class), mock(Calendar.class));

    TaxRate sourceTaxOnZone = mock(TaxRate.class);
    when(sourceTaxOnZone.getId()).thenReturn("TAX-1");

    TaxZone sourceTaxZone = mock(TaxZone.class);
    when(sourceTaxZone.getTax()).thenReturn(sourceTaxOnZone);
    TaxZone clonedTaxZone = mock(TaxZone.class);

    TaxRate clonedTax = mock(TaxRate.class);
    Map<String, TaxRate> taxesBySourceId = new HashMap<>();
    taxesBySourceId.put("TAX-1", clonedTax);

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxZone> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxZone.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceTaxZone));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
         MockedStatic<DalUtil> dalUtilStatic = mockStatic(DalUtil.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dalUtilStatic.when(() -> DalUtil.copy(sourceTaxZone, false)).thenReturn(clonedTaxZone);

      invokePrivateMethod("cloneTaxZones",
          new Class<?>[] { AccountingPackageCandidate.class, Organization.class, Map.class },
          candidate, targetOrg, taxesBySourceId);

      verify(clonedTaxZone).setClient(targetOrg.getClient());
      verify(clonedTaxZone).setOrganization(targetOrg);
      verify(clonedTaxZone).setTax(clonedTax);
      verify(obDal).save(clonedTaxZone);
    }
  }

  @Test
  public void testCloneTaxZonesSkipsWhenClonedTaxNotFound() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    Organization targetOrg = mock(Organization.class);
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, mock(AcctSchema.class), mock(Calendar.class));

    TaxRate sourceTaxOnZone = mock(TaxRate.class);
    when(sourceTaxOnZone.getId()).thenReturn("TAX-MISSING");

    TaxZone sourceTaxZone = mock(TaxZone.class);
    when(sourceTaxZone.getTax()).thenReturn(sourceTaxOnZone);

    Map<String, TaxRate> taxesBySourceId = new HashMap<>();

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxZone> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxZone.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceTaxZone));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      invokePrivateMethod("cloneTaxZones",
          new Class<?>[] { AccountingPackageCandidate.class, Organization.class, Map.class },
          candidate, targetOrg, taxesBySourceId);

      verify(obDal, never()).save(any());
    }
  }

  // -- cloneTaxAccounts -------------------------------------------------

  @Test
  public void testCloneTaxAccountsSkipsWhenClonedTaxNotFound() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    AcctSchema ledger = mock(AcctSchema.class);
    when(ledger.getId()).thenReturn(LEDGER_ID);
    Organization targetOrg = mock(Organization.class);
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, ledger, mock(Calendar.class));

    TaxRate sourceTaxOnAccount = mock(TaxRate.class);
    when(sourceTaxOnAccount.getId()).thenReturn("TAX-MISSING");

    TaxRateAccounts sourceTaxAccount = mock(TaxRateAccounts.class);
    when(sourceTaxAccount.getTax()).thenReturn(sourceTaxOnAccount);

    Map<String, TaxRate> taxesBySourceId = new HashMap<>();

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxRateAccounts> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxRateAccounts.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceTaxAccount));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      invokePrivateMethod("cloneTaxAccounts",
          new Class<?>[] { AccountingPackageCandidate.class, Organization.class, AcctSchema.class,
              Map.class },
          candidate, targetOrg, ledger, taxesBySourceId);

      verify(obDal, never()).save(any());
    }
  }

  @Test
  public void testCloneTaxAccountsClonesWithNullCombinations() throws Exception {
    Organization sourceOrg = mock(Organization.class);
    when(sourceOrg.getId()).thenReturn(SOURCE_ORG_ID);
    AcctSchema ledger = mock(AcctSchema.class);
    when(ledger.getId()).thenReturn(LEDGER_ID);
    Organization targetOrg = mock(Organization.class);
    when(targetOrg.getClient()).thenReturn(mock(org.openbravo.model.ad.system.Client.class));
    AccountingPackageCandidate candidate = new AccountingPackageCandidate(
        sourceOrg, ledger, mock(Calendar.class));

    TaxRate sourceTaxOnAccount = mock(TaxRate.class);
    when(sourceTaxOnAccount.getId()).thenReturn("TAX-1");
    TaxRate clonedTax = mock(TaxRate.class);
    Map<String, TaxRate> taxesBySourceId = new HashMap<>();
    taxesBySourceId.put("TAX-1", clonedTax);

    TaxRateAccounts sourceTaxAccount = mock(TaxRateAccounts.class);
    when(sourceTaxAccount.getTax()).thenReturn(sourceTaxOnAccount);
    when(sourceTaxAccount.getTaxDue()).thenReturn(null);
    when(sourceTaxAccount.getTaxLiability()).thenReturn(null);
    when(sourceTaxAccount.getTaxCredit()).thenReturn(null);
    when(sourceTaxAccount.getTaxReceivables()).thenReturn(null);
    when(sourceTaxAccount.getTaxExpense()).thenReturn(null);
    when(sourceTaxAccount.getTaxDueTransitory()).thenReturn(null);
    when(sourceTaxAccount.getTaxCreditTransitory()).thenReturn(null);

    TaxRateAccounts clonedTaxAccount = mock(TaxRateAccounts.class);

    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxRateAccounts> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxRateAccounts.class), anyString())).thenReturn(query);
    when(query.list()).thenReturn(List.of(sourceTaxAccount));

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class);
         MockedStatic<DalUtil> dalUtilStatic = mockStatic(DalUtil.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      dalUtilStatic.when(() -> DalUtil.copy(sourceTaxAccount, false)).thenReturn(clonedTaxAccount);

      invokePrivateMethod("cloneTaxAccounts",
          new Class<?>[] { AccountingPackageCandidate.class, Organization.class, AcctSchema.class,
              Map.class },
          candidate, targetOrg, ledger, taxesBySourceId);

      verify(clonedTaxAccount).setClient(targetOrg.getClient());
      verify(clonedTaxAccount).setOrganization(targetOrg);
      verify(clonedTaxAccount).setTax(clonedTax);
      verify(clonedTaxAccount).setAccountingSchema(ledger);
      verify(clonedTaxAccount).setTaxDue(null);
      verify(clonedTaxAccount).setTaxLiability(null);
      verify(clonedTaxAccount).setTaxCredit(null);
      verify(clonedTaxAccount).setTaxReceivables(null);
      verify(clonedTaxAccount).setTaxExpense(null);
      verify(clonedTaxAccount).setTaxDueTransitory(null);
      verify(clonedTaxAccount).setTaxCreditTransitory(null);
      verify(obDal).save(clonedTaxAccount);
    }
  }

  // -- query helper -----------------------------------------------------

  @Test
  public void testQuerySetsFiltersAndNamedParameters() throws Exception {
    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxRate> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxRate.class), anyString())).thenReturn(query);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      Method method = AccountingPackageCloner.class.getDeclaredMethod("query",
          Class.class, String.class, Object[].class);
      method.setAccessible(true);
      OBQuery<?> result = (OBQuery<?>) method.invoke(new AccountingPackageCloner(),
          TaxRate.class, "as e where e.organization.id = :orgId",
          new Object[] { "orgId", "ORG-1" });

      assertSame(query, result);
      verify(query).setFilterOnReadableClients(false);
      verify(query).setFilterOnReadableOrganization(false);
      verify(query).setNamedParameter("orgId", "ORG-1");
    }
  }

  @Test
  public void testQueryWithMultipleNamedParameters() throws Exception {
    OBDal obDal = mock(OBDal.class);
    @SuppressWarnings("unchecked")
    OBQuery<TaxRateAccounts> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(TaxRateAccounts.class), anyString())).thenReturn(query);

    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

      Method method = AccountingPackageCloner.class.getDeclaredMethod("query",
          Class.class, String.class, Object[].class);
      method.setAccessible(true);
      method.invoke(new AccountingPackageCloner(),
          TaxRateAccounts.class, "as e where e.id = :orgId and e.id = :ledgerId",
          new Object[] { "orgId", "ORG-1", "ledgerId", "LEDGER-1" });

      verify(query).setNamedParameter("orgId", "ORG-1");
      verify(query).setNamedParameter("ledgerId", "LEDGER-1");
    }
  }

  // -- Reflection helpers -----------------------------------------------

  private boolean invokeIsUnsafe(BaseOBObject reference, String sourceOrgId) throws Exception {
    Method method = AccountingPackageCloner.class.getDeclaredMethod(
        "isUnsafeOrgScopedReference", BaseOBObject.class, String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(new AccountingPackageCloner(), reference, sourceOrgId);
  }

  private boolean invokeSanitize(AccountingCombination combination, String sourceOrgId)
      throws Exception {
    Method method = AccountingPackageCloner.class.getDeclaredMethod(
        "sanitizeCombinationDimensions", AccountingCombination.class, String.class);
    method.setAccessible(true);
    return (boolean) method.invoke(new AccountingPackageCloner(), combination, sourceOrgId);
  }

  private AccountingCombination invokeCloneDerivedCombination(
      AccountingCombination sourceCombination, Organization targetOrg, AcctSchema targetLedger,
      Map<String, AccountingCombination> cache, String sourceOrgId) throws Exception {
    Method method = AccountingPackageCloner.class.getDeclaredMethod("cloneDerivedCombination",
        AccountingCombination.class, Organization.class, AcctSchema.class, Map.class,
        String.class);
    method.setAccessible(true);
    return (AccountingCombination) method.invoke(new AccountingPackageCloner(),
        sourceCombination, targetOrg, targetLedger, cache, sourceOrgId);
  }

  private void invokeWireOrganization(Organization targetOrg, AcctSchema ledger,
      AccountingPackageCandidate candidate) throws Exception {
    Method method = AccountingPackageCloner.class.getDeclaredMethod("wireOrganization",
        Organization.class, AcctSchema.class, AccountingPackageCandidate.class);
    method.setAccessible(true);
    method.invoke(new AccountingPackageCloner(), targetOrg, ledger, candidate);
  }

  private void invokeEnsureOrganizationAcctSchema(Organization targetOrg, AcctSchema ledger)
      throws Exception {
    Method method = AccountingPackageCloner.class.getDeclaredMethod(
        "ensureOrganizationAcctSchema", Organization.class, AcctSchema.class);
    method.setAccessible(true);
    method.invoke(new AccountingPackageCloner(), targetOrg, ledger);
  }

  private Object invokePrivateMethod(String methodName, Class<?>[] paramTypes, Object... args)
      throws Exception {
    Method method = AccountingPackageCloner.class.getDeclaredMethod(methodName, paramTypes);
    method.setAccessible(true);
    return method.invoke(new AccountingPackageCloner(), args);
  }

  // -- Mock helpers -----------------------------------------------------

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

  private void stubAllDimensions(AccountingCombination combination, String orgId) {
    Organization trxOrg = orgScopedReference(Organization.class, orgId);
    when(combination.getTrxOrganization()).thenReturn(trxOrg);
    Location locFrom = orgScopedReference(Location.class, orgId);
    when(combination.getLocationFromAddress()).thenReturn(locFrom);
    Location locTo = orgScopedReference(Location.class, orgId);
    when(combination.getLocationToAddress()).thenReturn(locTo);
    Product product = orgScopedReference(Product.class, orgId);
    when(combination.getProduct()).thenReturn(product);
    BusinessPartner bp = orgScopedReference(BusinessPartner.class, orgId);
    when(combination.getBusinessPartner()).thenReturn(bp);
    SalesRegion salesRegion = orgScopedReference(SalesRegion.class, orgId);
    when(combination.getSalesRegion()).thenReturn(salesRegion);
    Project project2 = orgScopedReference(Project.class, orgId);
    when(combination.getProject()).thenReturn(project2);
    Campaign campaign = orgScopedReference(Campaign.class, orgId);
    when(combination.getSalesCampaign()).thenReturn(campaign);
    ABCActivity activity = orgScopedReference(ABCActivity.class, orgId);
    when(combination.getActivity()).thenReturn(activity);
    UserDimension1 stDim = orgScopedReference(UserDimension1.class, orgId);
    when(combination.getStDimension()).thenReturn(stDim);
    UserDimension2 ndDim = orgScopedReference(UserDimension2.class, orgId);
    when(combination.getNdDimension()).thenReturn(ndDim);
  }
}
