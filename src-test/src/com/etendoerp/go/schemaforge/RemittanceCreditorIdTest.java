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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.module.remittance.Remittance;

/**
 * Unit tests for {@link RemittanceCreditorId} (ETP-6247): reading the identifier saved on the
 * account through its DAL property, its validity, the one computed from an organization, and the
 * write.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceCreditorId
 */
class RemittanceCreditorIdTest {

  private static final String CI = "ES82000B87654323";

  @Test
  void thePropertyIsTheOneNamingUtilGivesTheRemColumn() {
    // EM_REM_CreditorIdentifier: "EM_" stripped, camel-cased, first letter lower-cased.
    assertEquals("rEMCreditorIdentifier", RemittanceCreditorId.PROPERTY);
  }

  @Test
  void theStoredValueIsNormalizedAndBlankIsNone() {
    assertEquals(CI, RemittanceCreditorId.stored(account(" es82 000b-87654323 ")));
    assertNull(RemittanceCreditorId.stored(account("  ")));
    assertNull(RemittanceCreditorId.stored(account(null)));
    assertNull(RemittanceCreditorId.stored(null));
  }

  @Test
  void invalidLegacyAccountValuesCannotEnterTheFile() {
    Remittance remittance = mock(Remittance.class);
    FIN_FinancialAccount account = account("ES00000B87654323");
    when(remittance.getFinancialAccount()).thenReturn(account);
    assertNull(RemittanceCreditorId.forFile(remittance));
    verify(remittance, org.mockito.Mockito.never()).set(
        org.mockito.ArgumentMatchers.anyString(), any());
  }

  @Test
  void anOlderRemittanceCopiesTheValidAccountDefaultOnGeneration() {
    Remittance remittance = mock(Remittance.class);
    FIN_FinancialAccount account = account(CI);
    when(remittance.getFinancialAccount()).thenReturn(account);
    assertEquals(CI, RemittanceCreditorId.forFile(remittance));
    verify(remittance).set(RemittanceCreditorId.REMITTANCE_PROPERTY, CI);
  }

  @Test
  void regenerationUsesTheSnapshotEvenIfTheAccountChanges() {
    Remittance remittance = mock(Remittance.class);
    when(remittance.get(RemittanceCreditorId.REMITTANCE_PROPERTY)).thenReturn(CI);
    assertEquals(CI, RemittanceCreditorId.forFile(remittance));
    verify(remittance, org.mockito.Mockito.never()).getFinancialAccount();
  }

  @Test
  void theSuggestedOneIsWhatClassicWouldComputeFromTheOrganization() {
    OrganizationInformation info = info("ES", "B-87654323");
    assertEquals(CI, RemittanceCreditorId.suggested(info, null));
    assertEquals("ES97ABCB12345678",
        RemittanceCreditorId.suggested(info("ES", "B12345678"), " a b c d "));
    assertNull(RemittanceCreditorId.suggested(info("ES", null), null));
    assertNull(RemittanceCreditorId.suggested(info(null, "B87654323"), null));
    assertNull(RemittanceCreditorId.suggested((OrganizationInformation) null, null));
    assertNull(RemittanceCreditorId.suggested((Organization) null));
  }

  @Test
  void theSuggestedOneOfAnOrganizationUsesItsOwnInformationAndTheClientsBusinessCode() {
    Organization organization = mock(Organization.class);
    OrganizationInformation orgInfo = info("ES", "B87654323");
    when(organization.getOrganizationInformationList()).thenReturn(List.of(orgInfo));
    try (MockedStatic<RemittanceSepaProvisioning> provisioning =
        mockStatic(RemittanceSepaProvisioning.class)) {
      provisioning.when(() -> RemittanceSepaProvisioning.creditorBusinessCode(any()))
          .thenReturn("000");

      assertEquals(CI, RemittanceCreditorId.suggested(organization));
    }
  }

  // ---------------------------------------------------------------- remittance copy

  @Test
  void theRemittanceCopyIsNormalizedAndBlankIsNone() {
    assertEquals("creditorIdentifier", RemittanceCreditorId.REMITTANCE_PROPERTY);
    assertEquals(CI, RemittanceCreditorId.snapshot(remittance(" es82 000b-87654323 ", null)));
    assertNull(RemittanceCreditorId.snapshot(remittance("  ", null)));
    assertNull(RemittanceCreditorId.snapshot(remittance(null, null)));
    assertNull(RemittanceCreditorId.snapshot(null));
  }

  /** The copy wins; without one the account's default is used. */
  @Test
  void theEffectiveOneIsTheCopyElseTheAccountDefault() {
    FIN_FinancialAccount account = account("ES97000B12345678");

    assertEquals(CI, RemittanceCreditorId.effective(remittance(CI, account)));
    assertEquals("ES97000B12345678", RemittanceCreditorId.effective(remittance(null, account)));
    assertNull(RemittanceCreditorId.effective(remittance(null, account(null))));
  }

  /** The file takes the remittance's copy; an old remittance without one gets the account's saved. */
  @Test
  void theFileTakesTheCopyAndSnapshotsTheAccountDefaultWhenThereIsNone() {
    Remittance withCopy = remittance(CI, account("ES97000B12345678"));
    assertEquals(CI, RemittanceCreditorId.forFile(withCopy));
    verify(withCopy, never()).set(anyString(), any());

    Remittance old = remittance(null, account(CI));
    assertEquals(CI, RemittanceCreditorId.forFile(old));
    verify(old).set(RemittanceCreditorId.REMITTANCE_PROPERTY, CI);
  }

  /** An invalid value is never written to a file, and never copied. */
  @Test
  void anInvalidValueIsNeverUsedForTheFile() {
    Remittance invalidCopy = remittance("ES00000B87654323", account(CI));
    assertNull(RemittanceCreditorId.forFile(invalidCopy));
    verify(invalidCopy, never()).set(anyString(), any());

    Remittance invalidAccount = remittance(null, account("ES00000B87654323"));
    assertNull(RemittanceCreditorId.forFile(invalidAccount));
    verify(invalidAccount, never()).set(anyString(), any());
  }

  /** With none anywhere, the organization's computed one becomes the remittance's copy. */
  @Test
  void withNoneAnywhereTheComputedOneIsKeptAsTheCopyNeverOnTheAccount() {
    FIN_FinancialAccount account = account(null);
    Remittance remittance = remittance(null, account);
    org.openbravo.model.common.enterprise.Organization organization =
        mock(org.openbravo.model.common.enterprise.Organization.class);
    OrganizationInformation orgInfo = info("ES", "B87654323");
    when(organization.getOrganizationInformationList()).thenReturn(List.of(orgInfo));
    when(remittance.getOrganization()).thenReturn(organization);
    try (MockedStatic<RemittanceSepaProvisioning> provisioning =
        mockStatic(RemittanceSepaProvisioning.class)) {
      provisioning.when(() -> RemittanceSepaProvisioning.creditorBusinessCode(any()))
          .thenReturn("000");

      assertEquals(CI, RemittanceCreditorId.forFile(remittance));
    }

    verify(remittance).set(RemittanceCreditorId.REMITTANCE_PROPERTY, CI);
    verify(account, never()).set(anyString(), any());
  }

  private static Remittance remittance(String copy, FIN_FinancialAccount account) {
    Remittance remittance = mock(Remittance.class);
    when(remittance.get(RemittanceCreditorId.REMITTANCE_PROPERTY)).thenReturn(copy);
    when(remittance.getFinancialAccount()).thenReturn(account);
    return remittance;
  }

  @Test
  void theWriteSetsTheProperty() {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);

      RemittanceCreditorId.store(account, CI);

      verify(account).set("rEMCreditorIdentifier", CI);
      verify(dal).save(account);
      verify(dal).flush();
    }
  }

  private static FIN_FinancialAccount account(String stored) {
    FIN_FinancialAccount account = mock(FIN_FinancialAccount.class);
    when(account.get(RemittanceCreditorId.PROPERTY)).thenReturn(stored);
    return account;
  }

  private static OrganizationInformation info(String iso, String tax) {
    Country country = mock(Country.class);
    when(country.getISOCountryCode()).thenReturn(iso);
    Location location = mock(Location.class);
    when(location.getCountry()).thenReturn(iso != null ? country : null);
    OrganizationInformation info = mock(OrganizationInformation.class);
    when(info.getLocationAddress()).thenReturn(location);
    when(info.getTaxID()).thenReturn(tax);
    return info;
  }
}
