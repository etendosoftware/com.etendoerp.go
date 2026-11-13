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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;
import com.etendoerp.go.schemaforge.RemittanceSepaProvisioning;

/**
 * Specifies the ETP-6148 onboarding step that gives a new tenant's Bank accounts the SEPA
 * remittance payment methods while the {@code sepa-remittances} flag is on for the account
 * receiving the tenant: flag off or no Bank account answers {@code false} (the caller commits
 * nothing), every Bank account found is provisioned through
 * {@link RemittanceSepaProvisioning#provisionAccount}, and the flag is read for that account and
 * tenant, not for the System user the onboarding runs as.
 *
 * <p>{@link GoFeatureFlags}, {@link OBDal}, {@link OBContext} and
 * {@link RemittanceSepaProvisioning} are mocked statically; the Bank-account query is a recorded
 * {@link OBQuery}.
 *
 * <p>A class of its own: {@code OnboardingRemittanceSetupService} is a new production class.
 *
 * @covers com.etendoerp.go.onboarding.OnboardingRemittanceSetupService
 */
class OnboardingRemittanceSetupServiceTest {

  private static final String CLIENT_ID = "CLIENT-NEW";
  private static final String ACCOUNT_EMAIL = "owner@example.com";

  private MockedStatic<GoFeatureFlags> flagsStatic;
  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<RemittanceSepaProvisioning> provisioningStatic;
  private OBDal dal;
  private OBQuery<FIN_FinancialAccount> bankQuery;
  private String bankQueryHql;
  private final List<FIN_FinancialAccount> bankAccounts = new ArrayList<>();

  private final OnboardingRemittanceSetupService service = new OnboardingRemittanceSetupService();

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    dal = mock(OBDal.class);
    bankQuery = mock(OBQuery.class, RETURNS_SELF);
    when(bankQuery.list()).thenAnswer(inv -> new ArrayList<>(bankAccounts));
    when(dal.createQuery(eq(FIN_FinancialAccount.class), anyString())).thenAnswer(inv -> {
      bankQueryHql = inv.getArgument(1);
      return bankQuery;
    });
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(dal);
    obContextStatic = mockStatic(OBContext.class);
    provisioningStatic = mockStatic(RemittanceSepaProvisioning.class);
    flagsStatic = mockStatic(GoFeatureFlags.class);
    givenFlag(true);
  }

  @AfterEach
  void tearDown() {
    flagsStatic.close();
    provisioningStatic.close();
    obContextStatic.close();
    obDalStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  @Test
  void flagOffAnswersFalseWithoutQueryingOrProvisioning() {
    givenFlag(false);
    bankAccounts.add(mock(FIN_FinancialAccount.class));

    assertFalse(service.applyIfEnabled(CLIENT_ID, ACCOUNT_EMAIL));

    verify(dal, never()).createQuery(any(Class.class), anyString());
    provisioningStatic.verifyNoInteractions();
    obContextStatic.verifyNoInteractions();
  }

  @Test
  void aTenantWithoutBankAccountsAnswersFalseAndProvisionsNothing() {
    assertFalse(service.applyIfEnabled(CLIENT_ID, ACCOUNT_EMAIL));

    verify(bankQuery).list();
    provisioningStatic.verifyNoInteractions();
    obContextStatic.verify(OBContext::restorePreviousMode);
  }

  /**
   * Two Bank accounts (the sample data's «Cuenta de Banco» and another): each is provisioned, and
   * the step answers {@code true} so the caller commits. The query asks for the tenant's Bank
   * accounts, across organizations and outside the System user's readable clients.
   */
  @Test
  void everyBankAccountOfTheTenantIsProvisionedAndTheStepAnswersTrue() {
    FIN_FinancialAccount first = mock(FIN_FinancialAccount.class);
    FIN_FinancialAccount second = mock(FIN_FinancialAccount.class);
    bankAccounts.add(first);
    bankAccounts.add(second);

    assertTrue(service.applyIfEnabled(CLIENT_ID, ACCOUNT_EMAIL));

    provisioningStatic.verify(() -> RemittanceSepaProvisioning.provisionAccount(first));
    provisioningStatic.verify(() -> RemittanceSepaProvisioning.provisionAccount(second));
    assertTrue(bankQueryHql.contains("a.client.id = :client"), bankQueryHql);
    assertTrue(bankQueryHql.contains("a.type = :bank"), bankQueryHql);
    verify(bankQuery).setNamedParameter("client", CLIENT_ID);
    verify(bankQuery).setNamedParameter("bank", "B");
    verify(bankQuery).setFilterOnReadableClients(false);
    verify(bankQuery).setFilterOnReadableOrganization(false);
    obContextStatic.verify(() -> OBContext.setAdminMode(true));
    obContextStatic.verify(OBContext::restorePreviousMode);
  }

  /** A provisioning failure propagates (the caller rolls back) and the admin mode is restored. */
  @Test
  void aProvisioningFailurePropagatesAndRestoresTheAdminMode() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    bankAccounts.add(account);
    IllegalStateException boom = new IllegalStateException("boom");
    provisioningStatic.when(() -> RemittanceSepaProvisioning.provisionAccount(account))
        .thenThrow(boom);

    IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> service.applyIfEnabled(CLIENT_ID, ACCOUNT_EMAIL));

    assertEquals(boom, thrown);
    obContextStatic.verify(OBContext::restorePreviousMode);
  }

  /**
   * The flag is evaluated for the account receiving the tenant and for the tenant itself — the
   * onboarding runs as System, so the current context would target the wrong user and client.
   */
  @Test
  void theFlagIsReadForTheReceivingAccountAndTheNewTenant() {
    service.applyIfEnabled(CLIENT_ID, ACCOUNT_EMAIL);

    ArgumentCaptor<FeatureFlagContext> captor = ArgumentCaptor.forClass(FeatureFlagContext.class);
    flagsStatic.verify(() -> GoFeatureFlags.isEnabled(
        eq(GoFeatureFlags.FLAG_SEPA_REMITTANCES), captor.capture()));
    assertEquals(ACCOUNT_EMAIL, captor.getValue().getTargetingKey());
    assertEquals(CLIENT_ID,
        captor.getValue().getAttributes().get(FeatureFlagContext.ATTRIBUTE_CLIENT_ID));
  }

  /** A null tenant short-circuits before the flag is even read. */
  @Test
  void aNullTenantDoesNothing() {
    assertFalse(service.applyIfEnabled(null, ACCOUNT_EMAIL));

    flagsStatic.verify(() -> GoFeatureFlags.isEnabled(anyString(), any()), never());
    verify(dal, never()).createQuery(any(Class.class), anyString());
    provisioningStatic.verifyNoInteractions();
  }

  private void givenFlag(boolean enabled) {
    flagsStatic.when(() -> GoFeatureFlags.isEnabled(eq(GoFeatureFlags.FLAG_SEPA_REMITTANCES),
        any())).thenReturn(enabled);
  }
}
