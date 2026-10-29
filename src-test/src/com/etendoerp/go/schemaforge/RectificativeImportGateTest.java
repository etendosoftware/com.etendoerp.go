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

import static com.etendoerp.go.schemaforge.LineImportTestSupport.CLIENT;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.client;
import static com.etendoerp.go.schemaforge.LineImportTestSupport.errorCode;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;

/**
 * Unit tests for {@link RectificativeImportGate} and {@link RectificativeImportPolicy} (ETP-5876):
 * the order / goods imports belong to a normal invoice and the return / origin ones to a
 * rectificative, on every channel, with a 422 and nothing created for the wrong pairing, and the
 * same rule as per-record advice for the action catalog.
 *
 * @covers com.etendoerp.go.schemaforge.RectificativeImportGate
 * @covers com.etendoerp.go.schemaforge.RectificativeImportPolicy
 */
public class RectificativeImportGateTest {

  private MockedStatic<OBDal> dalMock;
  private MockedStatic<OBContext> contextMock;
  private OBDal dal;
  private Invoice invoice;

  private static final RectificativeImportPolicy POLICY = new RectificativeImportPolicy(
      candidate -> "rect".equals(candidate.getId()), false);

  @Before
  public void setUp() {
    dal = mock(OBDal.class);
    dalMock = Mockito.mockStatic(OBDal.class);
    dalMock.when(OBDal::getInstance).thenReturn(dal);
    OBContext context = mock(OBContext.class);
    doReturn(client(CLIENT)).when(context).getCurrentClient();
    contextMock = Mockito.mockStatic(OBContext.class);
    contextMock.when(OBContext::getOBContext).thenReturn(context);
    invoice = invoice("i1");
    when(dal.get(Invoice.class, "i1")).thenReturn(invoice);
  }

  @After
  public void tearDown() {
    contextMock.close();
    dalMock.close();
  }

  private static Invoice invoice(String id) {
    Invoice mocked = mock(Invoice.class);
    when(mocked.getId()).thenReturn(id);
    when(mocked.getDocumentNo()).thenReturn("DOC-" + id);
    doReturn(client(CLIENT)).when(mocked).getClient();
    return mocked;
  }

  private void useRectificative() {
    invoice = invoice("rect");
    when(dal.get(Invoice.class, "i1")).thenReturn(invoice);
  }

  private static NeoContext request(String action, String id, String method) {
    return NeoContext.builder().endpointType(NeoEndpointType.ACTION).httpMethod(method)
        .fieldName(action).recordId(id).build();
  }

  private static NeoResponse refuse(String action) {
    return RectificativeImportGate.refuse(request(action, "i1", "POST"), POLICY);
  }

  // ── the enforcement ───────────────────────────────────────────────────────

  @Test
  public void aNormalInvoice_takesTheOrderAndGoodsImports_butNotTheReturnAndOriginOnes()
      throws Exception {
    for (String action : RectificativeImportGate.STANDARD_ACTIONS) {
      assertNull(action, refuse(action));
    }
    for (String action : RectificativeImportGate.RECTIFICATIVE_ACTIONS) {
      NeoResponse response = refuse(action);
      assertNotNull(action, response);
      assertEquals(422, response.getHttpStatus());
      assertEquals("importRequiresRectificative", errorCode(response));
    }
  }

  @Test
  public void aRectificative_takesTheReturnAndOriginImports_butNotTheOrderAndGoodsOnes()
      throws Exception {
    useRectificative();
    for (String action : RectificativeImportGate.RECTIFICATIVE_ACTIONS) {
      assertNull(action, refuse(action));
    }
    for (String action : RectificativeImportGate.STANDARD_ACTIONS) {
      NeoResponse response = refuse(action);
      assertNotNull(action, response);
      assertEquals(422, response.getHttpStatus());
      assertEquals("importNotAvailableOnRectificative", errorCode(response));
    }
  }

  @Test
  public void theRefusalNamesTheInvoiceAndWhereTheLinesComeFrom() throws Exception {
    useRectificative();
    String message = refuse("importOrderLines").getBody().getJSONObject("error")
        .getString("message");
    assertTrue(message, message.contains("DOC-rect") && message.contains("importReturnLines")
        && message.contains("importOriginInvoiceLines"));

    invoice = invoice("i1");
    when(dal.get(Invoice.class, "i1")).thenReturn(invoice);
    String other = refuse("importReturnLines").getBody().getJSONObject("error")
        .getString("message");
    assertTrue(other, other.contains("DOC-i1") && other.contains("importOrderLines"));
  }

  @Test
  public void everyOrderGoodsReturnAndOriginActionIsCovered() {
    assertEquals(Set.of("availableOrders", "availableOrderLines", "importOrderLines",
        "availableReceipts", "availableReceiptLines", "importReceiptLines", "availableShipments",
        "availableShipmentLines", "importShipmentLines"),
        RectificativeImportGate.STANDARD_ACTIONS);
    assertEquals(Set.of("availableReturns", "availableReturnLines", "importReturnLines",
        "availableOriginInvoices", "availableOriginInvoiceLines", "importOriginInvoiceLines"),
        RectificativeImportGate.RECTIFICATIVE_ACTIONS);
  }

  @Test
  public void whatIsNotAnImportActionOrNotAnActionPostIsLeftAlone() {
    assertNull(refuse("documentAction"));
    assertNull(refuse("cloneRecord"));
    assertNull(RectificativeImportGate.refuse(request("importOrderLines", "i1", "GET"), POLICY));
    assertNull(RectificativeImportGate.refuse(NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD).httpMethod("POST").fieldName("importOrderLines")
        .recordId("i1").build(), POLICY));
    assertNull(RectificativeImportGate.refuse(NeoContext.builder()
        .endpointType(NeoEndpointType.ACTION).httpMethod("POST").recordId("i1").build(), POLICY));
  }

  /** The delegate answers a missing or foreign invoice (404) or a missing id (400) itself. */
  @Test
  public void anInvoiceThatCannotBeReadIsLeftToTheDelegate() {
    useRectificative();
    assertNull(RectificativeImportGate.refuse(request("importOrderLines", "nope", "POST"),
        POLICY));
    assertNull(RectificativeImportGate.refuse(request("importOrderLines", null, "POST"), POLICY));
    assertNull(RectificativeImportGate.refuse(request("importOrderLines", " ", "POST"), POLICY));

    doReturn(client("other")).when(invoice).getClient();
    assertNull("a foreign invoice reads as a missing one", refuse("importOrderLines"));
  }

  @Test
  public void aLookupThatFailsLeavesTheRequestToTheDelegate() {
    when(dal.get(Invoice.class, "i1")).thenThrow(new IllegalStateException("boom"));
    assertNull(refuse("importReturnLines"));
  }

  @Test
  public void theDefaultPolicyJudgesNothingARectificative() {
    assertFalse(RectificativeImportPolicy.NONE.isRectificative(invoice));
    assertFalse(RectificativeImportPolicy.NONE.keepsOrderLink());
    assertEquals(RectificativeImportPolicy.NONE, RectificativeImportPolicy.from(null));
    NeoContext context = request("importOrderLines", "i1", "POST");
    assertEquals(RectificativeImportPolicy.NONE, RectificativeImportPolicy.from(context));
    context.setAttribute(RectificativeImportPolicy.ATTRIBUTE, POLICY);
    assertEquals(POLICY, RectificativeImportPolicy.from(context));
    assertFalse(POLICY.isRectificative(null));
  }

  // ── the advice ────────────────────────────────────────────────────────────

  @Test
  public void unavailable_aNormalInvoiceRulesOutTheRectificativeImports() {
    Map<String, String> unavailable = RectificativeImportGate.unavailable(invoice, POLICY);

    assertEquals(RectificativeImportGate.RECTIFICATIVE_ACTIONS, unavailable.keySet());
    assertTrue(unavailable.get("importReturnLines").contains("importOrderLines"));
  }

  @Test
  public void unavailable_aRectificativeRulesOutTheOrderAndGoodsImports() {
    useRectificative();

    Map<String, String> unavailable = RectificativeImportGate.unavailable(invoice, POLICY);

    assertEquals(RectificativeImportGate.STANDARD_ACTIONS, unavailable.keySet());
    assertTrue(unavailable.get("importOrderLines").contains("availableOriginInvoices"));
  }

  @Test
  public void unavailable_aRecordThatIsNotAnInvoice_orAFailingRule_ruleNothingOut() {
    assertTrue(RectificativeImportGate.unavailable(mock(Order.class), POLICY).isEmpty());
    assertTrue(RectificativeImportGate.unavailable(invoice, new RectificativeImportPolicy(
        candidate -> {
          throw new IllegalStateException("boom");
        }, false)).isEmpty());
  }
}
