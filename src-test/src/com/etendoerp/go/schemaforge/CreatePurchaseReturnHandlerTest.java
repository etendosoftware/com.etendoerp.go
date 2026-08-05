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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * Unit tests for {@link CreatePurchaseReturnHandler}.
 *
 * <p>Focused on the ETP-4028 currency-inheritance requirement: the newly created return
 * receipt must carry {@code EM_Etgo_Currency_ID} (mandatory column on {@code M_InOut|})
 * copied from the original goods receipt, since {@code createPurchaseReturn} builds the
 * return header directly via {@code OBProvider} rather than through
 * {@link NeoCommercialDocumentFactory}.
 */
public class CreatePurchaseReturnHandlerTest {

  /**
   * Verifies that {@code returnReceipt.setEtgoCurrency(original.getEtgoCurrency())} is called
   * while building the return receipt header, so the mandatory currency column is never left
   * null on the new {@code M_InOut} record.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testHandleCopiesCurrencyFromOriginalReceiptToReturnReceipt() throws Exception {
    try (MockedStatic<OBContext> obContextMock = Mockito.mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = Mockito.mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = Mockito.mockStatic(OBProvider.class)) {

      obContextMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      obContextMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBContext ctx = mock(OBContext.class);
      User user = mock(User.class);
      when(ctx.getUser()).thenReturn(user);
      obContextMock.when(OBContext::getOBContext).thenReturn(ctx);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("org-100");

      Currency originalCurrency = mock(Currency.class);

      ShipmentInOut original = mock(ShipmentInOut.class);
      when(original.getOrganization()).thenReturn(org);
      when(original.getPartnerAddress()).thenReturn(null);
      when(original.getEtgoCurrency()).thenReturn(originalCurrency);
      when(original.getMaterialMgmtShipmentInOutLineList()).thenReturn(Collections.emptyList());
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-1"))).thenReturn(original);

      Session session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      Query<DocumentType> query = mock(Query.class);
      when(session.createQuery(anyString(), eq(DocumentType.class))).thenReturn(query);
      DocumentType rtvDocType = mock(DocumentType.class);
      when(rtvDocType.getOrganization()).thenReturn(org);
      when(query.list()).thenReturn(Collections.singletonList(rtvDocType));

      ShipmentInOut returnReceipt = mock(ShipmentInOut.class);
      // Non-blank, not starting with "<" — skips the ensureDocumentNo body entirely.
      when(returnReceipt.getDocumentNo()).thenReturn("PR-001");
      when(returnReceipt.getId()).thenReturn("return-1");
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOut.class)).thenReturn(returnReceipt);

      JSONObject body = new JSONObject();
      JSONArray lines = new JSONArray();
      JSONObject line = new JSONObject();
      line.put("lineId", "any-line");
      line.put("returnQuantity", 1);
      lines.put(line);
      body.put("lines", lines);

      NeoContext neoCtx = NeoContext.builder()
          .httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION)
          .fieldName("createPurchaseReturn")
          .recordId("receipt-1")
          .requestBody(body)
          .build();

      NeoResponse response = new CreatePurchaseReturnHandler().handle(neoCtx);

      assertNotNull(response);
      assertEquals(201, response.getHttpStatus());
      verify(returnReceipt).setEtgoCurrency(originalCurrency);
    }
  }
}
