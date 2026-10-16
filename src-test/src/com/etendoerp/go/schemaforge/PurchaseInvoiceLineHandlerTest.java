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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.schemaforge.DefaultTaxResolver.Determination;
import com.etendoerp.go.schemaforge.DefaultTaxResolver.Result;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Unit tests for the {@code defaultTax} action of {@link PurchaseInvoiceLineHandler}: binding,
 * declared contract, delegation to the parent and the validation / ownership error paths. The
 * resolver itself is covered by {@link DefaultTaxResolverTest}.
 *
 * <p>New class (not an extension of {@code SalesLineCustomizationHandlersTest}): that class is a
 * parameterized check of the three sales customizations' shared shape; this handler has its own
 * action surface with static-guard mocking.</p>
 *
 * @covers com.etendoerp.go.schemaforge.PurchaseInvoiceLineHandler
 */
public class PurchaseInvoiceLineHandlerTest {

  private final PurchaseInvoiceLineHandler handler = new PurchaseInvoiceLineHandler();

  private static NeoContext ctx(String method, NeoEndpointType type, String action,
      JSONObject body) {
    return NeoContext.builder().specName("purchase-invoice").entityName("lines")
        .httpMethod(method).endpointType(type).fieldName(action).requestBody(body).build();
  }

  private static NeoContext action(String method, JSONObject body) {
    return ctx(method, NeoEndpointType.ACTION, PurchaseInvoiceLineHandler.ACTION_DEFAULT_TAX,
        body);
  }

  private static String message(NeoResponse r) throws Exception {
    return r.getBody().getJSONObject("error").getString("message");
  }

  @Test
  public void bindingIsTheAnnotationOnly() {
    NeoExtension binding = PurchaseInvoiceLineHandler.class.getAnnotation(NeoExtension.class);

    assertNotNull(binding);
    assertEquals("purchase-invoice", binding.spec());
    assertEquals("lines", binding.entity());
    assertNull(PurchaseInvoiceLineHandler.class.getAnnotation(Named.class));
    assertEquals(InvoiceLineHandler.class, PurchaseInvoiceLineHandler.class.getSuperclass());
  }

  @Test
  public void defaultTaxIsDeclaredAsANonMutatingContract() {
    Map<String, NeoActionContract> contracts = handler.actionContracts();

    NeoActionContract c = contracts.get("defaultTax");
    assertNotNull(c);
    assertFalse(c.isMutating());
    assertEquals(1, contracts.size());
    assertEquals(4, c.getParams().size());
    NeoActionContract.Param address = c.getParams().get(0);
    assertEquals("partnerAddressId", address.getName());
    assertEquals(NeoActionContract.TYPE_STRING, address.getType());
    assertTrue(address.isRequired());
    assertEquals("invoiceDate", c.getParams().get(1).getName());
    assertEquals(NeoActionContract.TYPE_DATE, c.getParams().get(1).getType());
    assertEquals("rate", c.getParams().get(2).getName());
    assertEquals(NeoActionContract.TYPE_NUMBER, c.getParams().get(2).getType());
    assertEquals("productId", c.getParams().get(3).getName());
    assertFalse(c.getParams().get(3).isRequired());
  }

  @Test
  public void nonDefaultTaxRequestsDelegateToTheParent() {
    assertNull(handler.handle(ctx("POST", NeoEndpointType.ACTION, "somethingElse",
        new JSONObject())));
    assertNull(handler.handle(ctx("GET", NeoEndpointType.CRUD, null, null)));
  }

  @Test
  public void nonPostIsRejectedWith405() throws Exception {
    NeoResponse r = handler.handle(action("GET", new JSONObject()));

    assertEquals(405, r.getHttpStatus());
  }

  @Test
  public void missingPartnerAddressIdIsA422() {
    NeoResponse r = handler.handle(action("POST", new JSONObject()));

    assertEquals(422, r.getHttpStatus());
  }

  @Test
  public void unknownParameterIsA422() throws Exception {
    JSONObject body = new JSONObject().put("partnerAddressId", "ADDR").put("bogus", "x");

    assertEquals(422, handler.handle(action("POST", body)).getHttpStatus());
  }

  @Test
  public void badDateIsA422() throws Exception {
    JSONObject body = new JSONObject().put("partnerAddressId", "ADDR")
        .put("invoiceDate", "2026-02-30");

    assertEquals(422, handler.handle(action("POST", body)).getHttpStatus());
  }

  @Test
  public void nonNumericRateIsA422() throws Exception {
    JSONObject body = new JSONObject().put("partnerAddressId", "ADDR").put("rate", "abc");

    assertEquals(422, handler.handle(action("POST", body)).getHttpStatus());
  }

  @Test
  public void foreignOrUnknownPartnerAddressIs404() throws Exception {
    JSONObject body = new JSONObject().put("partnerAddressId", "FOREIGN");

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<NeoActionRecordGuard> guard = mockStatic(NeoActionRecordGuard.class);
        MockedStatic<DefaultTaxResolver> resolver = mockStatic(DefaultTaxResolver.class)) {
      guard.when(() -> NeoActionRecordGuard.loadOwned(eq(Location.class), anyString()))
          .thenReturn(null);

      NeoResponse r = handler.handle(action("POST", body));

      assertEquals(404, r.getHttpStatus());
      assertEquals("Record not found", message(r));
      resolver.verifyNoInteractions();
    }
  }

  @Test
  public void foreignOrUnknownProductIs404() throws Exception {
    JSONObject body = new JSONObject().put("partnerAddressId", "ADDR").put("productId", "FOREIGN");

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<NeoActionRecordGuard> guard = mockStatic(NeoActionRecordGuard.class);
        MockedStatic<DefaultTaxResolver> resolver = mockStatic(DefaultTaxResolver.class)) {
      guard.when(() -> NeoActionRecordGuard.loadOwned(eq(Location.class), eq("ADDR")))
          .thenReturn(mock(Location.class));
      guard.when(() -> NeoActionRecordGuard.loadOwned(eq(Product.class), anyString()))
          .thenReturn(null);

      NeoResponse r = handler.handle(action("POST", body));

      assertEquals(404, r.getHttpStatus());
      assertEquals("Record not found", message(r));
      resolver.verifyNoInteractions();
    }
  }

  @Test
  public void ownedIdsReturnTheResolvedTaxWithSessionOrgAndClient() throws Exception {
    JSONObject body = new JSONObject().put("partnerAddressId", "ADDR").put("rate", 21);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn("ORG1");
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("CLI1");
    OBContext session = mock(OBContext.class);
    when(session.getCurrentOrganization()).thenReturn(org);
    when(session.getCurrentClient()).thenReturn(client);
    Determination chosen = new Determination("TAX21", "IVA 21%", new BigDecimal("21"), false,
        "CAT1", "Standard", true, "CLI1", 3);
    Result result = new Result(chosen, DefaultTaxResolver.SOURCE_DEFAULT_CATEGORY,
        Collections.emptyList());

    try (MockedStatic<OBContext> obc = mockStatic(OBContext.class);
        MockedStatic<NeoActionRecordGuard> guard = mockStatic(NeoActionRecordGuard.class);
        MockedStatic<DefaultTaxResolver> resolver = mockStatic(DefaultTaxResolver.class)) {
      obc.when(OBContext::getOBContext).thenReturn(session);
      guard.when(() -> NeoActionRecordGuard.loadOwned(eq(Location.class), eq("ADDR")))
          .thenReturn(mock(Location.class));
      resolver.when(() -> DefaultTaxResolver.resolve(eq("ORG1"), eq("CLI1"), eq("ADDR"),
          any(Date.class), eq(new BigDecimal("21")), isNull(), eq(false))).thenReturn(result);

      NeoResponse r = handler.handle(action("POST", body));

      assertEquals(200, r.getHttpStatus());
      JSONObject data = r.getBody().getJSONObject("response").getJSONObject("data");
      assertEquals("TAX21", data.getString("taxId"));
      assertEquals("DEFAULT_CATEGORY", data.getString("source"));
      assertEquals(21.0, data.getDouble("rate"), 0.0001);
      assertEquals(0, data.getJSONArray("candidates").length());
    }
  }
}
