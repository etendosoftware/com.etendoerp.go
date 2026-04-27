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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetupAccountingContext;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetupAccountingHandler;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetupAccountingResult;


@ApplicationScoped
public class GoInitialOrgSetupAccountingHandler implements InitialOrgSetupAccountingHandler {

  @Inject
  private AccountingPackageResolver packageResolver;

  @Inject
  private AccountingPackageValidator packageValidator;

  @Inject
  private AccountingPackageCloner packageCloner;

  @Override
  public int getPriority() {
    return 10;
  }

  @Override
  public boolean applies(InitialOrgSetupAccountingContext context) {
    return context != null
        && context.isCreateAccountingRequested()
        && !context.hasUploadedCoAFile()
        && context.getClient() != null
        && context.getOrganization() != null
        && context.getOrganizationType() != null
        && context.getOrganizationType().isLegalEntityWithAccounting();
  }

  @Override
  public InitialOrgSetupAccountingResult wire(InitialOrgSetupAccountingContext context) {
    OBContext.setAdminMode(true);
    try {
      return cloneReadyAccountingPackage(context);
    } catch (Exception e) {
      return InitialOrgSetupAccountingResult.error(e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private InitialOrgSetupAccountingResult cloneReadyAccountingPackage(
      InitialOrgSetupAccountingContext context) {
    final List<AccountingPackageCandidate> candidates = packageResolver.resolve(
        context.getClient().getId(), context.getCurrencyId());
    if (candidates.isEmpty()) {
      return InitialOrgSetupAccountingResult.error(String.format(
          "No ready legal-with-accounting organization package found for ledger currency '%s'.",
          context.getCurrencyId()));
    }

    final List<String> validationErrors = new ArrayList<>();
    for (AccountingPackageCandidate candidate : candidates) {
      final Optional<String> validationError = packageValidator.validate(candidate);
      if (validationError.isPresent()) {
        validationErrors.add(validationError.get());
        continue;
      }

      packageCloner.cloneInto(context, candidate);
      return InitialOrgSetupAccountingResult.success();
    }

    return InitialOrgSetupAccountingResult.error(String.join(" ", validationErrors));
  }

}
