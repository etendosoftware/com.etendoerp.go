/* Etendo License. */
package com.etendoerp.go.onboarding;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Image;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.common.currency.Currency;

import com.etendoerp.go.schemaforge.util.NeoImageHelper;

/** Regression contract for copying a demo company's identity into its productive organization. */
class OnboardingCompanyProfileTransferServiceTest {

  private static final String SOURCE_CLIENT_ID = "demo-client";
  private static final String TARGET_CLIENT_ID = "productive-client";
  private static final String TARGET_ORG_ID = "productive-org";

  @Test
  void copiesProfileToExactTargetOrganizationWithANewTargetOwnedAddress() {
    OBDal dal = mock(OBDal.class);
    OBProvider provider = mock(OBProvider.class);
    Organization sourceOrg = mock(Organization.class);
    Organization targetOrg = mock(Organization.class);
    OrganizationInformation sourceInfo = mock(OrganizationInformation.class);
    OrganizationInformation targetInfo = mock(OrganizationInformation.class);
    Image sourceLogo = mock(Image.class);
    Image targetLogo = mock(Image.class);
    Location sourceLocation = mock(Location.class);
    Location targetLocation = mock(Location.class);
    Country sourceCountry = mock(Country.class);
    Client targetClient = mock(Client.class);
    Currency targetCurrency = mock(Currency.class);

    when(sourceOrg.getId()).thenReturn("demo-org");
    when(sourceOrg.getName()).thenReturn("Demo Company SL");
    when(sourceOrg.getSocialName()).thenReturn("Demo Trade");
    when(sourceOrg.getEtgoBusinessType()).thenReturn("FL");
    when(sourceInfo.getTaxID()).thenReturn("B12345678");
    byte[] logoBytes = new byte[] { 1, 2, 3, 4 };
    when(sourceInfo.getYourCompanyDocumentImage()).thenReturn(sourceLogo);
    when(sourceLogo.getId()).thenReturn("demo-logo-id");
    when(sourceLogo.getName()).thenReturn("company-logo.png");
    when(sourceLogo.getMimetype()).thenReturn("image/png");
    when(sourceLogo.getBindaryData()).thenReturn(logoBytes);
    when(sourceInfo.getLocationAddress()).thenReturn(sourceLocation);
    when(sourceLocation.getId()).thenReturn("demo-location");
    when(sourceLocation.getAddressLine1()).thenReturn("Calle Demo 12");
    when(sourceLocation.getAddressLine2()).thenReturn("Piso 3");
    when(sourceLocation.getPostalCode()).thenReturn("28001");
    when(sourceLocation.getCityName()).thenReturn("Madrid");
    when(sourceLocation.getCountry()).thenReturn(sourceCountry);
    when(sourceCountry.getId()).thenReturn("country-es");
    when(targetOrg.getId()).thenReturn(TARGET_ORG_ID);
    when(targetOrg.getClient()).thenReturn(targetClient);
    when(targetOrg.getCurrency()).thenReturn(targetCurrency);
    when(targetClient.getId()).thenReturn(TARGET_CLIENT_ID);
    when(targetLocation.getId()).thenReturn("productive-location");
    when(targetLogo.getId()).thenReturn("productive-logo-id");

    @SuppressWarnings("unchecked")
    OBQuery<Organization> sourceOrgQuery = mock(OBQuery.class);
    @SuppressWarnings("unchecked")
    OBQuery<OrganizationInformation> sourceInfoQuery = mock(OBQuery.class);
    @SuppressWarnings("unchecked")
    OBQuery<OrganizationInformation> targetInfoQuery = mock(OBQuery.class);
    when(sourceOrgQuery.list()).thenReturn(List.of(sourceOrg));
    when(sourceInfoQuery.uniqueResult()).thenReturn(sourceInfo);
    when(targetInfoQuery.uniqueResult()).thenReturn(targetInfo);
    when(targetInfo.getLocationAddress()).thenReturn(null, targetLocation);
    when(targetInfo.getYourCompanyDocumentImage()).thenReturn(null, targetLogo);
    when(targetLogo.getClient()).thenReturn(targetClient);
    when(targetLogo.getOrganization()).thenReturn(targetOrg);
    when(targetLogo.getName()).thenReturn("company-logo.png");
    when(targetLogo.getMimetype()).thenReturn("image/png");
    when(targetLogo.getBindaryData()).thenReturn(logoBytes);
    when(targetLocation.getClient()).thenReturn(targetClient);
    when(targetLocation.getOrganization()).thenReturn(targetOrg);

    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
        MockedStatic<OBProvider> providerStatic = mockStatic(OBProvider.class);
        MockedStatic<OBContext> contextStatic = mockStatic(OBContext.class);
        MockedStatic<NeoImageHelper> imageHelper = mockStatic(NeoImageHelper.class,
            CALLS_REAL_METHODS)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      providerStatic.when(OBProvider::getInstance).thenReturn(provider);
      when(dal.get(Organization.class, TARGET_ORG_ID)).thenReturn(targetOrg);
      when(dal.get(Client.class, TARGET_CLIENT_ID)).thenReturn(targetClient);
      when(dal.createQuery(eq(Organization.class), anyString())).thenReturn(sourceOrgQuery);
      when(dal.createQuery(eq(OrganizationInformation.class), anyString()))
          .thenReturn(sourceInfoQuery, targetInfoQuery, sourceInfoQuery, targetInfoQuery);
      when(provider.get(Location.class)).thenReturn(targetLocation);
      imageHelper.when(() -> NeoImageHelper.createImage(eq("company-logo.png"), eq("image/png"),
          eq(logoBytes), eq(targetClient), eq(targetOrg))).thenReturn(targetLogo);

      new OnboardingCompanyProfileTransferService().copy(
          SOURCE_CLIENT_ID, TARGET_CLIENT_ID, TARGET_ORG_ID);
      // Provisioning may resume after the tenant exists; matching target-owned assets are reused.
      new OnboardingCompanyProfileTransferService().copy(
          SOURCE_CLIENT_ID, TARGET_CLIENT_ID, TARGET_ORG_ID);

      verify(sourceOrgQuery, times(2)).setNamedParameter("clientId", SOURCE_CLIENT_ID);
      verify(sourceOrg, times(2)).getName();
      verify(sourceOrg, times(2)).getSocialName();
      verify(targetOrg, times(2)).setName("Demo Company SL");
      verify(targetOrg, times(2)).setSocialName("Demo Trade");
      verify(targetOrg, times(2)).setEtgoBusinessType("FL");
      verify(targetInfo, times(2)).setTaxID("B12345678");
      verify(targetInfo).setYourCompanyDocumentImage(targetLogo);
      verify(targetInfo, times(2)).setOrganization(targetOrg);
      verify(targetInfo).setLocationAddress(targetLocation);
      verify(targetLocation).setClient(targetClient);
      verify(targetLocation).setOrganization(targetOrg);
      verify(targetLocation, times(2)).setAddressLine1("Calle Demo 12");
      verify(targetLocation, times(2)).setAddressLine2("Piso 3");
      verify(targetLocation, times(2)).setPostalCode("28001");
      verify(targetLocation, times(2)).setCityName("Madrid");
      verify(targetLocation, times(2)).setCountry(sourceCountry);
      assertNotEquals(sourceLocation.getId(), targetLocation.getId(),
          "The source location ID must not be reused in the productive tenant");
      assertNotEquals(sourceLogo.getId(), targetLogo.getId(),
          "The source image ID must not be reused in the productive tenant");
      imageHelper.verify(() -> NeoImageHelper.createImage(eq("company-logo.png"),
          eq("image/png"), eq(logoBytes), eq(targetClient), eq(targetOrg)), times(1));
      verify(targetOrg, never()).setCurrency(any(Currency.class));
      verify(dal, times(2)).save(targetOrg);
      verify(dal, times(2)).save(targetInfo);
      verify(dal, times(3)).save(targetLocation);
    }
  }

  @Test
  void refusesAmbiguousSourceOrganizationsInsteadOfCopyingAnArbitraryProfile() {
    OBDal dal = mock(OBDal.class);
    Client targetClient = mock(Client.class);
    Organization targetOrg = mock(Organization.class);
    Organization firstSourceOrg = mock(Organization.class);
    Organization secondSourceOrg = mock(Organization.class);
    @SuppressWarnings("unchecked")
    OBQuery<Organization> sourceOrgQuery = mock(OBQuery.class);
    when(sourceOrgQuery.list()).thenReturn(List.of(firstSourceOrg, secondSourceOrg));
    when(targetClient.getId()).thenReturn(TARGET_CLIENT_ID);
    when(targetOrg.getId()).thenReturn(TARGET_ORG_ID);
    when(targetOrg.getClient()).thenReturn(targetClient);

    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
        MockedStatic<OBContext> contextStatic = mockStatic(OBContext.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Client.class, TARGET_CLIENT_ID)).thenReturn(targetClient);
      when(dal.get(Organization.class, TARGET_ORG_ID)).thenReturn(targetOrg);
      when(dal.createQuery(eq(Organization.class), anyString())).thenReturn(sourceOrgQuery);

      org.junit.jupiter.api.Assertions.assertThrows(OBException.class,
          () -> new OnboardingCompanyProfileTransferService().copy(
              SOURCE_CLIENT_ID, TARGET_CLIENT_ID, TARGET_ORG_ID));
    }
  }

  @Test
  void failsWhenKnownDemoClientHasNoActiveBusinessOrganization() {
    OBDal dal = mock(OBDal.class);
    Client targetClient = mock(Client.class);
    Organization targetOrg = mock(Organization.class);
    @SuppressWarnings("unchecked")
    OBQuery<Organization> sourceOrgQuery = mock(OBQuery.class);
    when(sourceOrgQuery.list()).thenReturn(List.of());
    when(targetClient.getId()).thenReturn(TARGET_CLIENT_ID);
    when(targetOrg.getId()).thenReturn(TARGET_ORG_ID);
    when(targetOrg.getClient()).thenReturn(targetClient);

    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
        MockedStatic<OBContext> contextStatic = mockStatic(OBContext.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Client.class, TARGET_CLIENT_ID)).thenReturn(targetClient);
      when(dal.get(Organization.class, TARGET_ORG_ID)).thenReturn(targetOrg);
      when(dal.createQuery(eq(Organization.class), anyString())).thenReturn(sourceOrgQuery);

      org.junit.jupiter.api.Assertions.assertThrows(OBException.class,
          () -> new OnboardingCompanyProfileTransferService().copy(
              SOURCE_CLIENT_ID, TARGET_CLIENT_ID, TARGET_ORG_ID));

      verify(dal, never()).createQuery(eq(OrganizationInformation.class), anyString());
      verify(dal, never()).save(any());
    }
  }
}
