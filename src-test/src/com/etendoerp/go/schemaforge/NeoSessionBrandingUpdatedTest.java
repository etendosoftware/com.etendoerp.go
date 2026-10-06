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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.stream.Stream;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Image;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationInformation;
import org.openbravo.model.common.geography.Location;

import com.etendoerp.go.common.CompanyLogoResolver;

/**
 * Unit tests for the session's {@code brandingUpdated} (ETP-5541): the latest {@code updated}
 * among the logo image, AD_OrgInfo, its address and AD_Org, serialized in whole seconds so the
 * web client's strict comparison with the attachment's own whole-second timestamp holds.
 */
class NeoSessionBrandingUpdatedTest {

  private static final String ORG_ID = "ORG_1";
  private static final Date OLD = Date.from(Instant.parse("2026-01-01T00:00:00Z"));
  private static final Date NEW = Date.from(Instant.parse("2026-09-29T10:15:30Z"));

  /** Timestamps for logo, orgInfo, location, org — in that order. */
  private static Date resolveWith(Date logoUpdated, Date infoUpdated, Date locationUpdated,
      Date orgUpdated) {
    Image logo = null;
    if (logoUpdated != null) {
      logo = mock(Image.class);
      when(logo.getUpdated()).thenReturn(logoUpdated);
    }
    OBDal dal = mock(OBDal.class);
    OrganizationInformation info = mock(OrganizationInformation.class);
    when(info.getUpdated()).thenReturn(infoUpdated);
    if (locationUpdated != null) {
      Location location = mock(Location.class);
      when(location.getUpdated()).thenReturn(locationUpdated);
      when(info.getLocationAddress()).thenReturn(location);
    }
    when(dal.get(OrganizationInformation.class, ORG_ID)).thenReturn(info);
    Organization org = mock(Organization.class);
    when(org.getUpdated()).thenReturn(orgUpdated);
    when(dal.get(Organization.class, ORG_ID)).thenReturn(org);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      return NeoSessionService.resolveBrandingUpdated(ORG_ID, logo);
    }
  }

  static Stream<Arguments> newestRowWins() {
    return Stream.of(
        Arguments.of("logo image", NEW, OLD, OLD, OLD),
        Arguments.of("AD_OrgInfo", OLD, NEW, OLD, OLD),
        Arguments.of("AD_OrgInfo address", OLD, OLD, NEW, OLD),
        Arguments.of("AD_Org", OLD, OLD, OLD, NEW),
        Arguments.of("newest row among nulls", null, null, NEW, null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  void newestRowWins(String label, Date logo, Date info, Date location, Date org) {
    assertEquals(NEW, resolveWith(logo, info, location, org), label);
  }

  @Test
  void isNullWhenNoRowCarriesATimestamp() {
    assertNull(resolveWith(null, null, null, null));
  }

  /** Without an organization only the logo can contribute, and the DAL is not consulted. */
  @Test
  void withoutAnOrganizationOnlyTheLogoCounts() {
    Image logo = mock(Image.class);
    when(logo.getUpdated()).thenReturn(NEW);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      assertEquals(NEW, NeoSessionService.resolveBrandingUpdated(null, logo));
      dalMock.verifyNoInteractions();
    }
  }

  /** Drives {@code resolveSession} with only AD_Org carrying {@code orgUpdated}. */
  private static JSONObject sessionBodyWithOrgUpdated(Date orgUpdated) throws Exception {
    OBContext context = mock(OBContext.class);
    Organization currentOrg = mock(Organization.class);
    when(currentOrg.getId()).thenReturn(ORG_ID);
    Client currentClient = mock(Client.class);
    when(currentClient.getId()).thenReturn("CLIENT_A");
    when(context.getCurrentOrganization()).thenReturn(currentOrg);
    when(context.getCurrentClient()).thenReturn(currentClient);

    OBDal dal = mock(OBDal.class);
    Organization org = mock(Organization.class);
    when(org.getUpdated()).thenReturn(orgUpdated);
    when(dal.get(Organization.class, ORG_ID)).thenReturn(org);

    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
        MockedStatic<OBCurrencyUtils> currMock = mockStatic(OBCurrencyUtils.class);
        MockedStatic<CompanyLogoResolver> logoMock = mockStatic(CompanyLogoResolver.class)) {
      ctxMock.when(OBContext::getOBContext).thenReturn(context);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      currMock.when(() -> OBCurrencyUtils.getOrgCurrency(anyString())).thenReturn(null);
      logoMock.when(() -> CompanyLogoResolver.resolve(any(), any())).thenReturn(null);
      return NeoSessionService.resolveSession().getBody();
    }
  }

  @Test
  void sessionSerializesBrandingUpdatedTruncatedToWholeSeconds() throws Exception {
    JSONObject body = sessionBodyWithOrgUpdated(
        Date.from(Instant.parse("2026-09-29T10:15:30.987Z")));

    assertEquals("2026-09-29T10:15:30Z", body.getString("brandingUpdated"));
  }

  @Test
  void sessionSerializesAnUnknownBrandingUpdatedAsJsonNull() throws Exception {
    JSONObject body = sessionBodyWithOrgUpdated(null);

    assertTrue(body.has("brandingUpdated"));
    assertTrue(body.isNull("brandingUpdated"));
  }
}
