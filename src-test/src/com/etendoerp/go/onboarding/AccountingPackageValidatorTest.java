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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationAcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchemaDefault;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchemaElement;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchemaGL;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchemaTable;
import org.openbravo.model.financialmgmt.accounting.coa.Element;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.openbravo.model.financialmgmt.calendar.Calendar;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.model.financialmgmt.calendar.PeriodControl;
import org.openbravo.model.financialmgmt.calendar.Year;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.model.financialmgmt.tax.TaxZone;

/** Unit tests for {@link AccountingPackageValidator}. */
public class AccountingPackageValidatorTest {
  private static final String SOURCE_ORG_ID = "SOURCE-ORG";
  private static final String SOURCE_ORG_IDENTIFIER = "Source Org";
  private static final String LEDGER_ID = "LEDGER-1";
  private static final String CALENDAR_ID = "CALENDAR-1";
  private static final String ACCOUNT_ELEMENT_ID = "ACCOUNT-ELEMENT-1";

  @Test
  public void testValidateAcceptsCompleteAccountingPackage() {
    QueryScenario scenario = QueryScenario.complete();

    Optional<String> validationError = validateWithScenario(scenario);

    assertFalse(validationError.isPresent());
  }

  @Test
  public void testValidateReportsAllMissingAccountingAndCalendarParts() {
    QueryScenario scenario = QueryScenario.complete();
    scenario.counts.put(OrganizationAcctSchema.class, 0);
    scenario.counts.put(AcctSchemaDefault.class, 0);
    scenario.counts.put(AcctSchemaElement.class, 0);
    scenario.counts.put(AcctSchemaGL.class, 0);
    scenario.counts.put(AcctSchemaTable.class, 0);
    scenario.accountElementPresent = false;
    scenario.counts.put(Year.class, 0);
    scenario.counts.put(Period.class, 0);
    scenario.counts.put(PeriodControl.class, 0);
    scenario.counts.put(TaxRate.class, 0);

    Optional<String> validationError = validateWithScenario(scenario);

    assertTrue(validationError.isPresent());
    String message = validationError.get();
    assertTrue(message.contains(SOURCE_ORG_IDENTIFIER));
    assertTrue(message.contains(SOURCE_ORG_ID));
    assertTrue(message.contains("organization accounting schema"));
    assertTrue(message.contains("accounting schema defaults"));
    assertTrue(message.contains("accounting schema elements"));
    assertTrue(message.contains("accounting schema GL entries"));
    assertTrue(message.contains("accounting schema tables"));
    assertTrue(message.contains("accounting element"));
    assertTrue(message.contains("calendar years"));
    assertTrue(message.contains("calendar periods"));
    assertTrue(message.contains("period controls"));
    assertTrue(message.contains("tax rates"));
  }

  @Test
  public void testValidateReportsMissingAccountValuesWhenAccountElementExists() {
    QueryScenario scenario = QueryScenario.complete();
    scenario.counts.put(ElementValue.class, 0);

    Optional<String> validationError = validateWithScenario(scenario);

    assertTrue(validationError.isPresent());
    assertTrue(validationError.get().contains("account element values"));
  }

  @Test
  public void testValidateReportsTaxesMissingAccounts() {
    QueryScenario scenario = QueryScenario.complete();
    scenario.taxRatesMissingAccounts = 2;

    Optional<String> validationError = validateWithScenario(scenario);

    assertTrue(validationError.isPresent());
    assertTrue(validationError.get().contains("tax accounts"));
  }

  @Test
  public void testValidateReportsUnscopedTaxZones() {
    QueryScenario scenario = QueryScenario.complete();
    scenario.taxZoneCount = 3;
    scenario.scopedTaxZoneCount = 2;

    Optional<String> validationError = validateWithScenario(scenario);

    assertTrue(validationError.isPresent());
    assertTrue(validationError.get().contains("tax zones"));
  }

  private Optional<String> validateWithScenario(QueryScenario scenario) {
    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.createQuery(eq(AcctSchemaElement.class), anyString()))
          .thenAnswer(invocation -> scenario.acctSchemaElementQuery(invocation.getArgument(1)));
      when(obDal.createQuery(eq(TaxRate.class), anyString()))
          .thenAnswer(invocation -> scenario.taxRateQuery(invocation.getArgument(1)));
      when(obDal.createQuery(eq(TaxZone.class), anyString()))
          .thenAnswer(invocation -> scenario.taxZoneQuery(invocation.getArgument(1)));
      stubCountQuery(obDal, OrganizationAcctSchema.class, scenario);
      stubCountQuery(obDal, AcctSchemaDefault.class, scenario);
      stubCountQuery(obDal, AcctSchemaGL.class, scenario);
      stubCountQuery(obDal, AcctSchemaTable.class, scenario);
      stubCountQuery(obDal, ElementValue.class, scenario);
      stubCountQuery(obDal, Year.class, scenario);
      stubCountQuery(obDal, Period.class, scenario);
      stubCountQuery(obDal, PeriodControl.class, scenario);

      return new AccountingPackageValidator().validate(candidate());
    }
  }

  private <T extends org.openbravo.base.structure.BaseOBObject> void stubCountQuery(OBDal obDal,
      Class<T> entityClass, QueryScenario scenario) {
    when(obDal.createQuery(eq(entityClass), anyString()))
        .thenAnswer(invocation -> scenario.countQuery(entityClass));
  }

  private AccountingPackageCandidate candidate() {
    Organization sourceOrganization = mock(Organization.class);
    when(sourceOrganization.getId()).thenReturn(SOURCE_ORG_ID);
    when(sourceOrganization.getIdentifier()).thenReturn(SOURCE_ORG_IDENTIFIER);
    AcctSchema ledger = mock(AcctSchema.class);
    when(ledger.getId()).thenReturn(LEDGER_ID);
    Calendar calendar = mock(Calendar.class);
    when(calendar.getId()).thenReturn(CALENDAR_ID);
    return new AccountingPackageCandidate(sourceOrganization, ledger, calendar);
  }

  private static final class QueryScenario {
    private final Map<Class<?>, Integer> counts = new HashMap<>();
    private boolean accountElementPresent = true;
    private int taxRatesMissingAccounts;
    private int taxZoneCount;
    private int scopedTaxZoneCount;

    private static QueryScenario complete() {
      QueryScenario scenario = new QueryScenario();
      scenario.counts.put(OrganizationAcctSchema.class, 1);
      scenario.counts.put(AcctSchemaDefault.class, 1);
      scenario.counts.put(AcctSchemaElement.class, 1);
      scenario.counts.put(AcctSchemaGL.class, 1);
      scenario.counts.put(AcctSchemaTable.class, 1);
      scenario.counts.put(ElementValue.class, 1);
      scenario.counts.put(Year.class, 1);
      scenario.counts.put(Period.class, 12);
      scenario.counts.put(PeriodControl.class, 12);
      scenario.counts.put(TaxRate.class, 1);
      return scenario;
    }

    @SuppressWarnings("unchecked")
    private <T extends org.openbravo.base.structure.BaseOBObject> OBQuery<T> countQuery(
        Class<T> entityClass) {
      OBQuery<T> query = mock(OBQuery.class);
      when(query.count()).thenReturn(counts.getOrDefault(entityClass, 0));
      return query;
    }

    @SuppressWarnings("unchecked")
    private OBQuery<AcctSchemaElement> acctSchemaElementQuery(String whereClause) {
      return whereClause.contains("e.type = :type") ? accountElementQuery()
          : countQuery(AcctSchemaElement.class);
    }

    private OBQuery<AcctSchemaElement> accountElementQuery() {
      OBQuery<AcctSchemaElement> query = mock(OBQuery.class);
      AcctSchemaElement element = accountElementPresent ? accountElement() : null;
      when(query.uniqueResult()).thenReturn(element);
      return query;
    }

    @SuppressWarnings("unchecked")
    private OBQuery<TaxRate> taxRateQuery(String whereClause) {
      OBQuery<TaxRate> query = mock(OBQuery.class);
      int count = whereClause.contains("not exists")
          ? taxRatesMissingAccounts
          : counts.getOrDefault(TaxRate.class, 0);
      when(query.count()).thenReturn(count);
      return query;
    }

    @SuppressWarnings("unchecked")
    private OBQuery<TaxZone> taxZoneQuery(String whereClause) {
      OBQuery<TaxZone> query = mock(OBQuery.class);
      int count = whereClause.contains("fromCountry") ? scopedTaxZoneCount : taxZoneCount;
      when(query.count()).thenReturn(count);
      return query;
    }

    private AcctSchemaElement accountElement() {
      Element element = mock(Element.class);
      when(element.getId()).thenReturn(ACCOUNT_ELEMENT_ID);
      AcctSchemaElement accountElement = mock(AcctSchemaElement.class);
      when(accountElement.getAccountingElement()).thenReturn(element);
      return accountElement;
    }
  }
}
