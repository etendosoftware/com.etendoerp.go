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

package com.etendoerp.go.common;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.system.ClientInformation;
import org.openbravo.model.ad.utility.Image;
import org.openbravo.model.common.enterprise.OrganizationInformation;

/**
 * Unit tests for {@link CompanyLogoResolver} (ETP-5541): the three-step logo resolution
 * (current org's AD_OrgInfo, first client org with a logo, AD_ClientInfo), the tenant guard
 * on step 1, and the never-throws contract.
 */
class CompanyLogoResolverTest {

  private static final String CLIENT_ID = "CLIENT_A";
  private static final String FOREIGN_CLIENT_ID = "CLIENT_B";
  private static final String ORG_ID = "ORG_1";

  private OBDal dal;
  private OBQuery<OrganizationInformation> firstOrgQuery;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    dal = mock(OBDal.class);
    firstOrgQuery = mock(OBQuery.class);
    when(dal.createQuery(eq(OrganizationInformation.class), anyString())).thenReturn(firstOrgQuery);
  }

  private Image resolve(String clientId, String orgId) {
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      return CompanyLogoResolver.resolve(clientId, orgId);
    }
  }

  private static OrganizationInformation orgInfo(String clientId, Image logo) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    OrganizationInformation info = mock(OrganizationInformation.class);
    when(info.getClient()).thenReturn(client);
    when(info.getYourCompanyDocumentImage()).thenReturn(logo);
    return info;
  }

  private void givenClientInfoLogo(Image logo) {
    ClientInformation clientInfo = mock(ClientInformation.class);
    when(clientInfo.getYourCompanyDocumentImage()).thenReturn(logo);
    when(dal.get(ClientInformation.class, CLIENT_ID)).thenReturn(clientInfo);
  }

  @Test
  void step1ReturnsTheCurrentOrganizationLogo() {
    Image orgLogo = mock(Image.class);
    OrganizationInformation current = orgInfo(CLIENT_ID, orgLogo);
    when(dal.get(OrganizationInformation.class, ORG_ID)).thenReturn(current);

    assertSame(orgLogo, resolve(CLIENT_ID, ORG_ID));
    verify(dal, never()).createQuery(eq(OrganizationInformation.class), anyString());
  }

  /** The portal passes no organization: the client's first org logo is what it prints. */
  @Test
  void step2ReturnsTheFirstClientOrganizationLogoWhenNoOrgIsGiven() {
    Image firstOrgLogo = mock(Image.class);
    OrganizationInformation first = orgInfo(CLIENT_ID, firstOrgLogo);
    when(firstOrgQuery.uniqueResult()).thenReturn(first);

    assertSame(firstOrgLogo, resolve(CLIENT_ID, null));
    verify(firstOrgQuery).setNamedParameter("clientId", CLIENT_ID);
  }

  /**
   * Tenant guard: an orgId whose AD_OrgInfo belongs to another client must never leak that
   * client's logo; resolution continues at the caller's own client.
   */
  @Test
  void anOrganizationOfAnotherClientFallsToStep2AndNeverLeaksItsLogo() {
    Image foreignLogo = mock(Image.class);
    Image ownLogo = mock(Image.class);
    OrganizationInformation foreign = orgInfo(FOREIGN_CLIENT_ID, foreignLogo);
    when(dal.get(OrganizationInformation.class, ORG_ID)).thenReturn(foreign);
    OrganizationInformation own = orgInfo(CLIENT_ID, ownLogo);
    when(firstOrgQuery.uniqueResult()).thenReturn(own);

    assertSame(ownLogo, resolve(CLIENT_ID, ORG_ID));
    verify(firstOrgQuery).setNamedParameter("clientId", CLIENT_ID);
  }

  @Test
  void step3FallsBackToTheClientInformationLogo() {
    Image clientLogo = mock(Image.class);
    OrganizationInformation withoutLogo = orgInfo(CLIENT_ID, null);
    when(dal.get(OrganizationInformation.class, ORG_ID)).thenReturn(withoutLogo);
    when(firstOrgQuery.uniqueResult()).thenReturn(null);
    givenClientInfoLogo(clientLogo);

    assertSame(clientLogo, resolve(CLIENT_ID, ORG_ID));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = { "   " })
  void aBlankClientResolvesToNullWithoutTouchingTheDal(String clientId) {
    assertNull(resolve(clientId, ORG_ID));
    verify(dal, never()).get(any(Class.class), any());
    verify(dal, never()).createQuery(eq(OrganizationInformation.class), anyString());
  }

  @Test
  void aLookupFailureYieldsNullAndRestoresTheAdminMode() {
    when(dal.get(OrganizationInformation.class, ORG_ID))
        .thenThrow(new IllegalStateException("db down"));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      assertNull(CompanyLogoResolver.resolve(CLIENT_ID, ORG_ID));
      ctxMock.verify(() -> OBContext.setAdminMode(true));
      ctxMock.verify(OBContext::restorePreviousMode);
    }
  }
}
