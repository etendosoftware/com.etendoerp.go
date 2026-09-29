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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.advpaymentmngt.utility.FIN_Utility;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.ad_reports.AgingDao;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Unit tests for {@link AgingPayableReportHandler} (ETP-5483) — the payables-only sibling of
 * {@link AgingReportHandler}, published as its own MCP report tool ({@code generate_aging_payable})
 * distinct from {@code generate_aging_receivable}, mirroring Classic's two separate "Aging Balance
 * Process Definition" reports (Receivables / Payables).
 *
 * <p>Covers exactly the three behaviors that differ from the parent: the side served is always
 * forced to PAYABLES regardless of any {@code recOrPay} in the request, {@code recOrPay} is not
 * part of its declared parameter contract, and its {@code @Named} qualifier is its own (required
 * for CDI resolution, since {@code @Named} is not {@code @Inherited}).
 */
class AgingPayableReportHandlerTest {

  private final AgingPayableReportHandler handler = new AgingPayableReportHandler();

  private static final String AGING_RECEIVABLE_PROCESS_ID = "0D37A9F6109549DEB058373EF2DAEB6A";
  private static final String AGING_PAYABLE_PROCESS_ID = "EB4C4053F3B94A17A08D1DD7E89CEB7E";

  // -------------------------------------------------------------------------
  // @Named qualifier
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("carries its own @Named qualifier, distinct from the parent's")
  void carriesOwnNamedQualifier() {
    Named named = AgingPayableReportHandler.class.getAnnotation(Named.class);
    assertNotNull(named, "@Named is not @Inherited — a subclass without its own annotation "
        + "would not be resolvable by WeldUtils.getInstances(NeoHandler.class)");
    assertEquals("agingPayableReportHandler", named.value());

    Named parentNamed = AgingReportHandler.class.getAnnotation(Named.class);
    assertNotNull(parentNamed);
    assertEquals("agingReportHandler", parentNamed.value());
  }

  // -------------------------------------------------------------------------
  // reportParameters — no recOrPay
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("declares every parameter its parent does, except recOrPay")
  void reportParametersOmitsRecOrPay() {
    List<NeoReportParam> parentParams = new AgingReportHandler().reportParameters().orElseThrow();
    List<NeoReportParam> payableParams = handler.reportParameters().orElseThrow();

    assertEquals(parentParams.size() - 1, payableParams.size());
    assertTrue(payableParams.stream().noneMatch(p -> "recOrPay".equals(p.getName())),
        "The payables-only report has nothing to choose — recOrPay must not be in its contract");

    List<String> parentNames = parentParams.stream().map(NeoReportParam::getName)
        .collect(java.util.stream.Collectors.toList());
    List<String> payableNames = payableParams.stream().map(NeoReportParam::getName)
        .collect(java.util.stream.Collectors.toList());
    for (String name : parentNames) {
      if (!"recOrPay".equals(name)) {
        assertTrue(payableNames.contains(name), "Missing parameter: " + name);
      }
    }
  }

  // -------------------------------------------------------------------------
  // describeReport — payables-specific name/description
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("GET describes itself as the payables report, not the generic one")
  void describesItselfAsPayables() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(anyString())).thenReturn(true);

      NeoResponse result = handler.handle(NeoContext.builder().httpMethod("GET").build());

      assertEquals(200, result.getHttpStatus());
      JSONObject body = result.getBody();
      assertEquals("Aging of Payables", body.getString("name"));
      assertFalse(body.getString("description").isEmpty());

      assertFalse(body.getJSONArray("parameters").toString().contains("\"recOrPay\""),
          "The rendered descriptor must not advertise recOrPay either");
    }
  }

  // -------------------------------------------------------------------------
  // Access gate — payables process id only
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("gates on the payables process id even for a POST body that says RECEIVABLES")
  void gatesOnPayablesProcessIdRegardlessOfBody() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedConstruction<AgingDao> daoConstruction = mockConstruction(AgingDao.class)) {
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(AGING_PAYABLE_PROCESS_ID))
          .thenReturn(false);
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(AGING_RECEIVABLE_PROCESS_ID))
          .thenReturn(true);

      JSONObject body = new JSONObject();
      body.put("recOrPay", "RECEIVABLES");
      NeoResponse result = handler.handle(
          NeoContext.builder().httpMethod("POST").requestBody(body).build());

      assertEquals(403, result.getHttpStatus(),
          "A role with only the RECEIVABLES grant must be denied — the payables-only handler "
              + "must never be satisfied by the receivables grant, no matter what recOrPay says");
      assertTrue(daoConstruction.constructed().isEmpty());
    }
  }

  @Test
  @DisplayName("GET is gated on the payables process id too")
  void getGatedOnPayablesProcessId() {
    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(AGING_PAYABLE_PROCESS_ID))
          .thenReturn(true);
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(AGING_RECEIVABLE_PROCESS_ID))
          .thenReturn(false);

      NeoResponse result = handler.handle(NeoContext.builder().httpMethod("GET").build());

      assertEquals(200, result.getHttpStatus());
    }
  }

  // -------------------------------------------------------------------------
  // executeReport — always serves PAYABLES, never RECEIVABLES
  // -------------------------------------------------------------------------

  /**
   * The core guarantee of this handler: even a request body that explicitly asks for
   * {@code RECEIVABLES} must still be served as PAYABLES. Verified end to end by asserting the
   * {@code recOrPay} value actually passed into {@link AgingDao#getOpenReceivablesAgingSchedule}.
   */
  @Test
  @DisplayName("forces PAYABLES into AgingDao even when the body says RECEIVABLES")
  void forcesPayablesIntoAgingDaoRegardlessOfBody() throws Exception {
    OBDal dal = mock(OBDal.class);
    Organization org = mock(Organization.class);
    AcctSchema schema = mock(AcctSchema.class);
    org.openbravo.model.common.currency.Currency currency =
        mock(org.openbravo.model.common.currency.Currency.class);

    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
         MockedStatic<FIN_Utility> paymentStatusMock = mockStatic(FIN_Utility.class);
         MockedStatic<OBContext> contextMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
         MockedConstruction<OrganizationStructureProvider> orgProviderConstruction =
             mockConstruction(OrganizationStructureProvider.class, (provider, ignored) ->
                when(provider.getChildTree("org-id", true))
                     .thenReturn(Collections.singleton("org-id")));
         MockedConstruction<AgingDao> daoConstruction = mockConstruction(AgingDao.class,
             (mock, ignored) -> when(mock.getOpenReceivablesAgingSchedule(
                     any(), anyString(), anyString(), any(), anyString(), anyString(),
                     anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(),
                     anyBoolean()))
                 .thenReturn(new org.openbravo.data.FieldProvider[0]))) {
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(anyString())).thenReturn(true);
      paymentStatusMock.when(FIN_Utility::getListPaymentConfirmed)
          .thenReturn(Collections.singletonList("RPR"));
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Organization.class, "org-id")).thenReturn(org);
      when(org.getGeneralLedger()).thenReturn(schema);
      when(schema.getId()).thenReturn("SCHEMA-1");
      when(schema.getCurrency()).thenReturn(currency);

      JSONObject body = new JSONObject();
      body.put("recOrPay", "RECEIVABLES");
      body.put("orgId", "org-id");

      NeoResponse result = handler.handle(
          NeoContext.builder().httpMethod("POST").requestBody(body).build());

      assertEquals(200, result.getHttpStatus());
      assertEquals(1, daoConstruction.constructed().size());
      AgingDao dao = daoConstruction.constructed().get(0);
      verify(dao).getOpenReceivablesAgingSchedule(
          any(), anyString(), anyString(), any(), anyString(), anyString(), anyString(),
          anyString(), anyString(), any(), ArgumentMatchers.eq("PAYABLES"),
          anyBoolean(), anyBoolean());

      JSONObject responseBody = result.getBody().getJSONObject("response");
      assertEquals("PAYABLES", responseBody.getJSONObject("meta").getString("recOrPay"));
    }
  }
}
