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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetupAccountingContext;
import org.openbravo.erpCommon.businessUtility.InitialOrgSetupAccountingResult;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationType;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Calendar;

/** Unit tests for {@link GoInitialOrgSetupAccountingHandler}. */
public class GoInitialOrgSetupAccountingHandlerTest {
  private static final String CLIENT_ID = "CLIENT-1";
  private static final String CURRENCY_ID = "EUR-CURRENCY";

  @Test
  public void testAppliesOnlyForLegalAccountingRequestWithoutUploadedChart() {
    GoInitialOrgSetupAccountingHandler handler = new GoInitialOrgSetupAccountingHandler();

    assertFalse(handler.applies(null));
    assertFalse(handler.applies(contextBuilder().createAccountingRequested(false).build()));
    assertFalse(handler.applies(contextBuilder().hasUploadedCoAFile(true).build()));
    assertFalse(handler.applies(contextBuilder().client(null).build()));
    assertFalse(handler.applies(contextBuilder().organization(null).build()));
    assertFalse(handler.applies(contextBuilder().organizationType(null).build()));
    assertFalse(handler.applies(contextBuilder().organizationType(organizationType(false)).build()));
    assertTrue(handler.applies(contextBuilder().build()));
  }

  @Test
  public void testWireReturnsErrorWhenNoReadyPackageExists() throws Exception {
    AccountingPackageResolver resolver = mock(AccountingPackageResolver.class);
    GoInitialOrgSetupAccountingHandler handler = handler(resolver,
        mock(AccountingPackageValidator.class), mock(AccountingPackageCloner.class));
    when(resolver.resolve(CLIENT_ID, CURRENCY_ID)).thenReturn(List.of());

    InitialOrgSetupAccountingResult result = wire(handler, contextBuilder().build());

    assertTrue(result.isHandled());
    assertFalse(result.isSuccess());
    assertTrue(result.getMessage().contains("No ready legal-with-accounting organization package"));
    assertTrue(result.getMessage().contains(CURRENCY_ID));
  }

  @Test
  public void testWireSkipsInvalidCandidateAndClonesFirstValidPackage() throws Exception {
    AccountingPackageResolver resolver = mock(AccountingPackageResolver.class);
    AccountingPackageValidator validator = mock(AccountingPackageValidator.class);
    AccountingPackageCloner cloner = mock(AccountingPackageCloner.class);
    GoInitialOrgSetupAccountingHandler handler = handler(resolver, validator, cloner);
    InitialOrgSetupAccountingContext context = contextBuilder().build();
    AccountingPackageCandidate invalidCandidate = candidate("INVALID-ORG", "Invalid Org");
    AccountingPackageCandidate validCandidate = candidate("VALID-ORG", "Valid Org");
    when(resolver.resolve(CLIENT_ID, CURRENCY_ID)).thenReturn(List.of(invalidCandidate, validCandidate));
    when(validator.validate(invalidCandidate)).thenReturn(Optional.of("invalid package"));
    when(validator.validate(validCandidate)).thenReturn(Optional.empty());

    InitialOrgSetupAccountingResult result = wire(handler, context);

    assertTrue(result.isHandled());
    assertTrue(result.isSuccess());
    verify(cloner, never()).cloneInto(context, invalidCandidate);
    verify(cloner).cloneInto(context, validCandidate);
  }

  @Test
  public void testWireReturnsAggregatedValidationErrorsWhenAllCandidatesAreInvalid() throws Exception {
    AccountingPackageResolver resolver = mock(AccountingPackageResolver.class);
    AccountingPackageValidator validator = mock(AccountingPackageValidator.class);
    AccountingPackageCloner cloner = mock(AccountingPackageCloner.class);
    GoInitialOrgSetupAccountingHandler handler = handler(resolver, validator, cloner);
    AccountingPackageCandidate firstCandidate = candidate("ORG-1", "First Org");
    AccountingPackageCandidate secondCandidate = candidate("ORG-2", "Second Org");
    when(resolver.resolve(CLIENT_ID, CURRENCY_ID)).thenReturn(List.of(firstCandidate, secondCandidate));
    when(validator.validate(firstCandidate)).thenReturn(Optional.of("missing taxes"));
    when(validator.validate(secondCandidate)).thenReturn(Optional.of("missing periods"));

    InitialOrgSetupAccountingResult result = wire(handler, contextBuilder().build());

    assertTrue(result.isHandled());
    assertFalse(result.isSuccess());
    assertTrue(result.getMessage().contains("missing taxes"));
    assertTrue(result.getMessage().contains("missing periods"));
    verify(cloner, never()).cloneInto(any(), any());
  }

  @Test
  public void testWireSurfacesCloneFailureAsAccountingSetupError() throws Exception {
    AccountingPackageResolver resolver = mock(AccountingPackageResolver.class);
    AccountingPackageValidator validator = mock(AccountingPackageValidator.class);
    AccountingPackageCloner cloner = mock(AccountingPackageCloner.class);
    GoInitialOrgSetupAccountingHandler handler = handler(resolver, validator, cloner);
    InitialOrgSetupAccountingContext context = contextBuilder().build();
    AccountingPackageCandidate validCandidate = candidate("VALID-ORG", "Valid Org");
    when(resolver.resolve(CLIENT_ID, CURRENCY_ID)).thenReturn(List.of(validCandidate));
    when(validator.validate(validCandidate)).thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new IllegalStateException("clone failed"))
        .when(cloner).cloneInto(context, validCandidate);

    InitialOrgSetupAccountingResult result = wire(handler, context);

    assertTrue(result.isHandled());
    assertFalse(result.isSuccess());
    assertEquals("clone failed", result.getMessage());
  }

  private InitialOrgSetupAccountingResult wire(GoInitialOrgSetupAccountingHandler handler,
      InitialOrgSetupAccountingContext context) {
    try (MockedStatic<OBContext> obContext = mockStatic(OBContext.class)) {
      InitialOrgSetupAccountingResult result = handler.wire(context);
      obContext.verify(() -> OBContext.setAdminMode(true));
      obContext.verify(OBContext::restorePreviousMode);
      return result;
    }
  }

  private GoInitialOrgSetupAccountingHandler handler(AccountingPackageResolver resolver,
      AccountingPackageValidator validator, AccountingPackageCloner cloner) throws Exception {
    GoInitialOrgSetupAccountingHandler handler = new GoInitialOrgSetupAccountingHandler();
    setField(handler, "packageResolver", resolver);
    setField(handler, "packageValidator", validator);
    setField(handler, "packageCloner", cloner);
    return handler;
  }

  private void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private InitialOrgSetupAccountingContext.Builder contextBuilder() {
    return InitialOrgSetupAccountingContext.builder()
        .client(client(CLIENT_ID))
        .organization(mock(Organization.class))
        .organizationType(organizationType(true))
        .currencyId(CURRENCY_ID)
        .createAccountingRequested(true)
        .hasUploadedCoAFile(false);
  }

  private Client client(String id) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(id);
    return client;
  }

  private OrganizationType organizationType(boolean legalEntityWithAccounting) {
    OrganizationType organizationType = mock(OrganizationType.class);
    when(organizationType.isLegalEntityWithAccounting()).thenReturn(legalEntityWithAccounting);
    return organizationType;
  }

  private AccountingPackageCandidate candidate(String sourceOrgId, String sourceOrgIdentifier) {
    Organization sourceOrganization = mock(Organization.class);
    when(sourceOrganization.getId()).thenReturn(sourceOrgId);
    when(sourceOrganization.getIdentifier()).thenReturn(sourceOrgIdentifier);
    return new AccountingPackageCandidate(sourceOrganization, mock(AcctSchema.class),
        mock(Calendar.class));
  }
}
