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

import java.util.List;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;

import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Calendar;

/**
 * Resolves ready legal-with-accounting organizations that can provide an onboarding
 * accounting package for the requested client and ledger currency.
 */
@ApplicationScoped
class AccountingPackageResolver {

  private static final String RESOLVE_CANDIDATES_WHERE = "as org where org.client.id = :clientId"
      + " and org.organizationType.legalEntityWithAccounting = true"
      + " and org.ready = true"
      + " and org.allowPeriodControl = true"
      + " and org.generalLedger is not null"
      + " and org.calendar is not null"
      + " and org.generalLedger.currency.id = :currencyId"
      + " order by org.name asc";

  /**
   * Resolves accounting package candidates for the given client and currency.
   */
  List<AccountingPackageCandidate> resolve(String clientId, String currencyId) {
    final OBQuery<Organization> query = OBDal.getInstance().createQuery(Organization.class,
        RESOLVE_CANDIDATES_WHERE);

    query.setNamedParameter("clientId", clientId);
    query.setNamedParameter("currencyId", currencyId);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);

    return query.list().stream()
        .map(this::toCandidate)
        .collect(Collectors.toList());
  }

  private AccountingPackageCandidate toCandidate(Organization sourceOrganization) {
    final AcctSchema ledger = sourceOrganization.getGeneralLedger();
    final Calendar calendar = sourceOrganization.getCalendar();
    return new AccountingPackageCandidate(sourceOrganization, ledger, calendar);
  }
}
