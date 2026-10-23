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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.common.SpanishTaxIdValidator;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Which organization's country decides whether the Spanish tax ID rules apply on a PATCH of
 * {@code organization/information/{orgId}}.
 *
 * <p>ETP-5793: the record id comes from the URL and the CRUD path checks no ownership before this
 * pre-hook, so another tenant's org must be treated like an unknown id (fall back to the session
 * org) — otherwise the 400-or-not answer reveals whether that tenant is Spanish.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.OrganizationInformationHandler
 */
class OrganizationInformationHandlerTest {

  private static final String BAD_TAX_ID = "NOT-A-NIF";

  private static Organization orgOf(String clientId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    Organization org = mock(Organization.class);
    when(org.getClient()).thenReturn(client);
    return org;
  }

  private static NeoContext patch(String recordId, OBContext obContext) throws Exception {
    return NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PATCH")
        .recordId(recordId)
        .requestBody(new JSONObject().put(OrganizationInformationHandler.FIELD_TAX_ID, BAD_TAX_ID))
        .obContext(obContext)
        .build();
  }

  /**
   * Runs a PATCH with a malformed tax ID on {@code recordId}, where only {@code spanishOrg} is in
   * Spain and the session org is not.
   */
  private static NeoResponse patchAs(String recordId, Organization target, Organization spanishOrg,
      MockedStatic<OrganizationCountrySupport> countryMock) throws Exception {
    Organization sessionOrg = orgOf("OWN");
    OBContext obContext = mock(OBContext.class);
    when(obContext.getReadableClients()).thenReturn(new String[] { "OWN", "0" });
    when(obContext.getCurrentOrganization()).thenReturn(sessionOrg);
    OBDal dal = mock(OBDal.class);
    when(dal.get(Organization.class, recordId)).thenReturn(target);
    countryMock.when(() -> OrganizationCountrySupport.isSpain(any()))
        .thenAnswer(inv -> inv.getArgument(0) == spanishOrg);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      return new OrganizationInformationHandler().handle(patch(recordId, obContext));
    }
  }

  @Test
  void ownOrganizationDecidesTheRules() throws Exception {
    Organization ownSpanish = orgOf("OWN");
    try (MockedStatic<OrganizationCountrySupport> countryMock =
        mockStatic(OrganizationCountrySupport.class)) {
      NeoResponse response = patchAs("ORG-OWN", ownSpanish, ownSpanish, countryMock);
      assertEquals(400, response.getHttpStatus());
      assertEquals(SpanishTaxIdValidator.ERR_FORMAT,
          response.getBody().getJSONObject("error").getString("message"));
    }
  }

  @Test
  void anotherTenantsOrganizationIsNotConsulted() throws Exception {
    Organization foreignSpanish = orgOf("OTHER");
    try (MockedStatic<OrganizationCountrySupport> countryMock =
        mockStatic(OrganizationCountrySupport.class)) {
      // The foreign org is Spanish, the session org is not: answering 400 would leak the former.
      assertNull(patchAs("ORG-FOREIGN", foreignSpanish, foreignSpanish, countryMock));
      countryMock.verify(() -> OrganizationCountrySupport.isSpain(foreignSpanish), never());
    }
  }
}
