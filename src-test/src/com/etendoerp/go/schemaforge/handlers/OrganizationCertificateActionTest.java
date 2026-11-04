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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.fiscal.FiscalSystemConfigureAction;
import com.etendoerp.go.schemaforge.NeoCertificateHelper;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * ETP-5852 (M-170): the certificate status an agent reads — the expiry as a date and the days left,
 * negative once expired; no certificate answers exists:false and nothing else. As the single
 * {@code @NeoExtension} of {@code organization/organization} it also declares and serves
 * {@code configureFiscalSystem}, by delegating to {@code FiscalSystemConfigureAction}.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.OrganizationCertificateAction
 */
class OrganizationCertificateActionTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

  @Test
  void aValidCertificateSaysWhenItExpiresAndHowManyDaysAreLeft() throws Exception {
    JSONObject status = OrganizationCertificateAction.status(
        java.sql.Date.valueOf("2026-11-28"), TODAY);

    assertTrue(status.getBoolean("exists"));
    assertEquals("2026-11-28", status.getString("validTo"));
    assertEquals(30, status.getLong("daysLeft"));
  }

  @Test
  void anExpiredCertificateHasNegativeDaysLeft() throws Exception {
    JSONObject status = OrganizationCertificateAction.status(
        java.sql.Date.valueOf("2026-10-19"), TODAY);

    assertEquals(-10, status.getLong("daysLeft"));
  }

  @Test
  void noCertificateIsReportedAsMissingOnly() throws Exception {
    JSONObject status = OrganizationCertificateAction.status(null, TODAY);

    assertFalse(status.getBoolean("exists"));
    assertFalse(status.has("validTo"));
    assertFalse(status.has("daysLeft"));
  }

  @Test
  void anOrganizationTheRoleCannotReadIsNotFoundAndNeverQueried() {
    OBContext obContext = mock(OBContext.class);
    when(obContext.getReadableOrganizations()).thenReturn(new String[] { "0", "ORG_A" });
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn(OrganizationCertificateAction.ACTION);
    when(ctx.getRecordId()).thenReturn("ORG_B");
    try (MockedStatic<OBContext> obContextMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<NeoCertificateHelper> helperMock = mockStatic(NeoCertificateHelper.class)) {
      obContextMock.when(OBContext::getOBContext).thenReturn(obContext);

      NeoResponse response = new OrganizationCertificateAction().handle(ctx);

      assertEquals(404, response.getHttpStatus());
      obDalMock.verifyNoInteractions();
      helperMock.verifyNoInteractions();
    }
  }

  @Test
  void declaresTheCertificateAndTheFiscalSystemActions() {
    List<String> actions = new ArrayList<>(new OrganizationCertificateAction().actionContracts()
        .keySet());

    assertEquals(List.of(OrganizationCertificateAction.ACTION,
        FiscalSystemConfigureAction.CATALOG_ACTION, FiscalSystemConfigureAction.ACTION), actions);
  }

  @Test
  void configureFiscalSystemIsServedByTheFiscalSystemAction() throws Exception {
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn(FiscalSystemConfigureAction.ACTION);
    when(ctx.getHttpMethod()).thenReturn("POST");
    when(ctx.getRecordId()).thenReturn("ORG_A");
    when(ctx.getRequestBody()).thenReturn(new JSONObject());
    try (MockedStatic<NeoCertificateHelper> helperMock = mockStatic(NeoCertificateHelper.class)) {

      NeoResponse response = new OrganizationCertificateAction().handle(ctx);

      // The configureFiscalSystem contract answers: its required parameter is missing.
      assertEquals(422, response.getHttpStatus());
      JSONArray missing = response.getBody().getJSONObject("error")
          .getJSONArray("missingParameters");
      assertEquals("system", missing.getString(0));
      helperMock.verifyNoInteractions();
    }
  }
}
