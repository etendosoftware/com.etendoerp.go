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
package com.etendoerp.go.onboarding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.enterprise.context.ApplicationScoped;

import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetupAccountingContext;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationAcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.tax.TaxCategory;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.financialmgmt.tax.TaxRateAccounts;
import org.openbravo.model.financialmgmt.tax.TaxZone;

@ApplicationScoped
class AccountingPackageCloner {
  private static final String ZERO_ORG_ID = "0";

  void cloneInto(InitialOrgSetupAccountingContext context, AccountingPackageCandidate candidate) {
    final Organization targetOrganization = context.getOrganization();
    final AcctSchema targetLedger = candidate.getLedger();

    wireOrganization(targetOrganization, targetLedger, candidate);
    ensureOrganizationAcctSchema(targetOrganization, targetLedger);

    final Map<String, TaxCategory> taxCategoriesBySourceId = cloneTaxCategories(candidate,
        targetOrganization);
    final Map<String, org.openbravo.model.common.businesspartner.TaxCategory> bpTaxCategoriesBySourceId =
        cloneBusinessPartnerTaxCategories(candidate, targetOrganization);
    final Map<String, TaxRate> taxesBySourceId = cloneTaxes(candidate, targetOrganization,
        taxCategoriesBySourceId, bpTaxCategoriesBySourceId);

    restoreTaxRelationships(candidate, taxesBySourceId);
    cloneTaxZones(candidate, targetOrganization, taxesBySourceId);
    cloneTaxAccounts(candidate, targetOrganization, targetLedger, taxesBySourceId);

    OBDal.getInstance().flush();
  }

  private void wireOrganization(Organization targetOrganization, AcctSchema targetLedger,
      AccountingPackageCandidate candidate) {
    targetOrganization.setCurrency(targetLedger.getCurrency());
    targetOrganization.setGeneralLedger(targetLedger);
    targetOrganization.setCalendar(candidate.getCalendar());
    targetOrganization.setAllowPeriodControl(true);
    OBDal.getInstance().save(targetOrganization);
  }

  private void ensureOrganizationAcctSchema(Organization targetOrganization, AcctSchema targetLedger) {
    final OrganizationAcctSchema existing = uniqueResult(OrganizationAcctSchema.class,
        "as e where e.organization.id = :orgId and e.accountingSchema.id = :ledgerId",
        "orgId", targetOrganization.getId(), "ledgerId", targetLedger.getId());
    if (existing != null) {
      return;
    }

    final OrganizationAcctSchema organizationAcctSchema = OBProvider.getInstance()
        .get(OrganizationAcctSchema.class);
    organizationAcctSchema.setClient(targetOrganization.getClient());
    organizationAcctSchema.setOrganization(targetOrganization);
    organizationAcctSchema.setAccountingSchema(targetLedger);
    OBDal.getInstance().save(organizationAcctSchema);
  }

  private Map<String, TaxCategory> cloneTaxCategories(AccountingPackageCandidate candidate,
      Organization targetOrganization) {
    final Map<String, TaxCategory> clonesBySourceId = new HashMap<>();
    final List<TaxCategory> sourceTaxCategories = list(TaxCategory.class,
        "as e where e.organization.id = :orgId order by e.name asc, e.id asc",
        "orgId", candidate.getSourceOrganization().getId());

    for (TaxCategory sourceTaxCategory : sourceTaxCategories) {
      final TaxCategory clonedTaxCategory = (TaxCategory) DalUtil.copy(sourceTaxCategory, false);
      clonedTaxCategory.setClient(targetOrganization.getClient());
      clonedTaxCategory.setOrganization(targetOrganization);
      OBDal.getInstance().save(clonedTaxCategory);
      clonesBySourceId.put(sourceTaxCategory.getId(), clonedTaxCategory);
    }

    return clonesBySourceId;
  }

  private Map<String, org.openbravo.model.common.businesspartner.TaxCategory> cloneBusinessPartnerTaxCategories(
      AccountingPackageCandidate candidate, Organization targetOrganization) {
    final Map<String, org.openbravo.model.common.businesspartner.TaxCategory> clonesBySourceId =
        new HashMap<>();
    final List<org.openbravo.model.common.businesspartner.TaxCategory> sourceTaxCategories = list(
        org.openbravo.model.common.businesspartner.TaxCategory.class,
        "as e where e.organization.id = :orgId order by e.name asc, e.id asc",
        "orgId", candidate.getSourceOrganization().getId());

    for (org.openbravo.model.common.businesspartner.TaxCategory sourceTaxCategory : sourceTaxCategories) {
      final org.openbravo.model.common.businesspartner.TaxCategory clonedTaxCategory =
          (org.openbravo.model.common.businesspartner.TaxCategory) DalUtil.copy(sourceTaxCategory,
              false);
      clonedTaxCategory.setClient(targetOrganization.getClient());
      clonedTaxCategory.setOrganization(targetOrganization);
      OBDal.getInstance().save(clonedTaxCategory);
      clonesBySourceId.put(sourceTaxCategory.getId(), clonedTaxCategory);
    }

    return clonesBySourceId;
  }

  private Map<String, TaxRate> cloneTaxes(AccountingPackageCandidate candidate,
      Organization targetOrganization, Map<String, TaxCategory> taxCategoriesBySourceId,
      Map<String, org.openbravo.model.common.businesspartner.TaxCategory> bpTaxCategoriesBySourceId) {
    final Map<String, TaxRate> clonesBySourceId = new HashMap<>();
    final List<TaxRate> sourceTaxes = list(TaxRate.class,
        "as e where e.organization.id = :orgId order by e.lineNo asc, e.name asc, e.id asc",
        "orgId", candidate.getSourceOrganization().getId());

    for (TaxRate sourceTax : sourceTaxes) {
      final TaxRate clonedTax = (TaxRate) DalUtil.copy(sourceTax, false);
      clonedTax.setClient(targetOrganization.getClient());
      clonedTax.setOrganization(targetOrganization);
      final TaxCategory sourceTaxCategory = sourceTax.getTaxCategory();
      final TaxCategory clonedTaxCategory = sourceTaxCategory == null ? null
          : taxCategoriesBySourceId.get(sourceTaxCategory.getId());
      clonedTax.setTaxCategory(clonedTaxCategory != null ? clonedTaxCategory : sourceTaxCategory);
      final org.openbravo.model.common.businesspartner.TaxCategory sourceBPTaxCategory =
          sourceTax.getBusinessPartnerTaxCategory();
      final org.openbravo.model.common.businesspartner.TaxCategory clonedBPTaxCategory =
          sourceBPTaxCategory == null ? null
              : bpTaxCategoriesBySourceId.get(sourceBPTaxCategory.getId());
      clonedTax.setBusinessPartnerTaxCategory(
          clonedBPTaxCategory != null ? clonedBPTaxCategory : sourceBPTaxCategory);
      clonedTax.setParentTaxRate(null);
      clonedTax.setTaxBase(null);
      OBDal.getInstance().save(clonedTax);
      clonesBySourceId.put(sourceTax.getId(), clonedTax);
    }

    return clonesBySourceId;
  }

  private void restoreTaxRelationships(AccountingPackageCandidate candidate,
      Map<String, TaxRate> taxesBySourceId) {
    final List<TaxRate> sourceTaxes = list(TaxRate.class,
        "as e where e.organization.id = :orgId order by e.id asc",
        "orgId", candidate.getSourceOrganization().getId());

    for (TaxRate sourceTax : sourceTaxes) {
      final TaxRate clonedTax = taxesBySourceId.get(sourceTax.getId());
      if (clonedTax == null) {
        continue;
      }
      if (sourceTax.getParentTaxRate() != null) {
        TaxRate clonedParentTaxRate = taxesBySourceId.get(sourceTax.getParentTaxRate().getId());
        clonedTax.setParentTaxRate(
            clonedParentTaxRate != null ? clonedParentTaxRate : sourceTax.getParentTaxRate());
      }
      if (sourceTax.getTaxBase() != null) {
        TaxRate clonedTaxBase = taxesBySourceId.get(sourceTax.getTaxBase().getId());
        clonedTax.setTaxBase(clonedTaxBase != null ? clonedTaxBase : sourceTax.getTaxBase());
      }
      OBDal.getInstance().save(clonedTax);
    }
  }

  private void cloneTaxZones(AccountingPackageCandidate candidate, Organization targetOrganization,
      Map<String, TaxRate> taxesBySourceId) {
    final List<TaxZone> sourceTaxZones = list(TaxZone.class,
        "as e where e.tax.organization.id = :orgId order by e.id asc",
        "orgId", candidate.getSourceOrganization().getId());

    for (TaxZone sourceTaxZone : sourceTaxZones) {
      final TaxRate clonedTax = taxesBySourceId.get(sourceTaxZone.getTax().getId());
      if (clonedTax == null) {
        continue;
      }

      final TaxZone clonedTaxZone = (TaxZone) DalUtil.copy(sourceTaxZone, false);
      clonedTaxZone.setClient(targetOrganization.getClient());
      clonedTaxZone.setOrganization(targetOrganization);
      clonedTaxZone.setTax(clonedTax);
      OBDal.getInstance().save(clonedTaxZone);
    }
  }

  private void cloneTaxAccounts(AccountingPackageCandidate candidate, Organization targetOrganization,
      AcctSchema targetLedger, Map<String, TaxRate> taxesBySourceId) {
    final Map<String, AccountingCombination> combinationsBySourceId = new HashMap<>();
    final List<TaxRateAccounts> sourceTaxAccounts = list(TaxRateAccounts.class,
        "as e where e.tax.organization.id = :orgId and e.accountingSchema.id = :ledgerId"
            + " order by e.tax.id asc, e.id asc",
        "orgId", candidate.getSourceOrganization().getId(), "ledgerId", candidate.getLedger().getId());

    for (TaxRateAccounts sourceTaxAccount : sourceTaxAccounts) {
      final TaxRate clonedTax = taxesBySourceId.get(sourceTaxAccount.getTax().getId());
      if (clonedTax == null) {
        continue;
      }

      final TaxRateAccounts clonedTaxAccount = (TaxRateAccounts) DalUtil.copy(sourceTaxAccount, false);
      clonedTaxAccount.setClient(targetOrganization.getClient());
      clonedTaxAccount.setOrganization(targetOrganization);
      clonedTaxAccount.setTax(clonedTax);
      clonedTaxAccount.setAccountingSchema(targetLedger);
      clonedTaxAccount.setTaxDue(cloneDerivedCombination(sourceTaxAccount.getTaxDue(),
          targetOrganization, targetLedger, combinationsBySourceId,
          candidate.getSourceOrganization().getId()));
      clonedTaxAccount.setTaxLiability(cloneDerivedCombination(sourceTaxAccount.getTaxLiability(),
          targetOrganization, targetLedger, combinationsBySourceId,
          candidate.getSourceOrganization().getId()));
      clonedTaxAccount.setTaxCredit(cloneDerivedCombination(sourceTaxAccount.getTaxCredit(),
          targetOrganization, targetLedger, combinationsBySourceId,
          candidate.getSourceOrganization().getId()));
      clonedTaxAccount.setTaxReceivables(cloneDerivedCombination(
          sourceTaxAccount.getTaxReceivables(), targetOrganization, targetLedger,
          combinationsBySourceId, candidate.getSourceOrganization().getId()));
      clonedTaxAccount.setTaxExpense(cloneDerivedCombination(sourceTaxAccount.getTaxExpense(),
          targetOrganization, targetLedger, combinationsBySourceId,
          candidate.getSourceOrganization().getId()));
      clonedTaxAccount.setTaxDueTransitory(cloneDerivedCombination(
          sourceTaxAccount.getTaxDueTransitory(), targetOrganization, targetLedger,
          combinationsBySourceId, candidate.getSourceOrganization().getId()));
      clonedTaxAccount.setTaxCreditTransitory(cloneDerivedCombination(
          sourceTaxAccount.getTaxCreditTransitory(), targetOrganization, targetLedger,
          combinationsBySourceId, candidate.getSourceOrganization().getId()));
      OBDal.getInstance().save(clonedTaxAccount);
    }
  }

  private AccountingCombination cloneDerivedCombination(AccountingCombination sourceCombination,
      Organization targetOrganization, AcctSchema targetLedger,
      Map<String, AccountingCombination> combinationsBySourceId, String sourceOrgId) {
    if (sourceCombination == null) {
      return null;
    }

    AccountingCombination clonedCombination = combinationsBySourceId.get(sourceCombination.getId());
    if (clonedCombination != null) {
      return clonedCombination;
    }

    clonedCombination = (AccountingCombination) DalUtil.copy(sourceCombination, false);
    clonedCombination.setClient(targetOrganization.getClient());
    clonedCombination.setOrganization(targetOrganization);
    clonedCombination.setAccountingSchema(targetLedger);

    final boolean strippedDimensions = sanitizeCombinationDimensions(clonedCombination, sourceOrgId);
    if (strippedDimensions) {
      clonedCombination.setAlias(null);
      clonedCombination.setCombination(null);
      clonedCombination.setDescription(null);
      clonedCombination.setFullyQualified(false);
    }

    OBDal.getInstance().save(clonedCombination);
    combinationsBySourceId.put(sourceCombination.getId(), clonedCombination);
    return clonedCombination;
  }

  private boolean sanitizeCombinationDimensions(AccountingCombination combination, String sourceOrgId) {
    boolean stripped = false;

    if (isUnsafeOrgScopedReference(combination.getTrxOrganization(), sourceOrgId)) {
      combination.setTrxOrganization(null);
      stripped = true;
    }
    if (isUnsafeOrgScopedReference(combination.getLocationFromAddress(), sourceOrgId)) {
      combination.setLocationFromAddress(null);
      stripped = true;
    }
    if (isUnsafeOrgScopedReference(combination.getLocationToAddress(), sourceOrgId)) {
      combination.setLocationToAddress(null);
      stripped = true;
    }
    if (isUnsafeOrgScopedReference(combination.getProduct(), sourceOrgId)) {
      combination.setProduct(null);
      stripped = true;
    }
    if (isUnsafeOrgScopedReference(combination.getBusinessPartner(), sourceOrgId)) {
      combination.setBusinessPartner(null);
      stripped = true;
    }
    if (isUnsafeOrgScopedReference(combination.getSalesRegion(), sourceOrgId)) {
      combination.setSalesRegion(null);
      stripped = true;
    }
    if (isUnsafeOrgScopedReference(combination.getProject(), sourceOrgId)) {
      combination.setProject(null);
      stripped = true;
    }
    if (isUnsafeOrgScopedReference(combination.getSalesCampaign(), sourceOrgId)) {
      combination.setSalesCampaign(null);
      stripped = true;
    }
    if (isUnsafeOrgScopedReference(combination.getActivity(), sourceOrgId)) {
      combination.setActivity(null);
      stripped = true;
    }
    if (isUnsafeOrgScopedReference(combination.getStDimension(), sourceOrgId)) {
      combination.setStDimension(null);
      stripped = true;
    }
    if (isUnsafeOrgScopedReference(combination.getNdDimension(), sourceOrgId)) {
      combination.setNdDimension(null);
      stripped = true;
    }

    return stripped;
  }

  private boolean isUnsafeOrgScopedReference(BaseOBObject reference, String sourceOrgId) {
    if (reference == null || !reference.getEntity().hasProperty("organization")) {
      return false;
    }

    final Object organizationValue = reference.get("organization");
    if (!(organizationValue instanceof Organization)) {
      return false;
    }

    final Organization referencedOrganization = (Organization) organizationValue;
    return referencedOrganization != null && sourceOrgId.equals(referencedOrganization.getId());
  }

  private <T extends BaseOBObject> List<T> list(Class<T> entityClass, String whereClause,
      Object... parameters) {
    final OBQuery<T> query = query(entityClass, whereClause, parameters);
    return query.list();
  }

  private <T extends BaseOBObject> T uniqueResult(Class<T> entityClass, String whereClause,
      Object... parameters) {
    final OBQuery<T> query = query(entityClass, whereClause, parameters);
    query.setMaxResult(1);
    return query.uniqueResult();
  }

  private <T extends BaseOBObject> OBQuery<T> query(Class<T> entityClass, String whereClause,
      Object... parameters) {
    final OBQuery<T> query = OBDal.getInstance().createQuery(entityClass, whereClause);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    for (int i = 0; i < parameters.length; i += 2) {
      query.setNamedParameter((String) parameters[i], parameters[i + 1]);
    }
    return query;
  }
}
