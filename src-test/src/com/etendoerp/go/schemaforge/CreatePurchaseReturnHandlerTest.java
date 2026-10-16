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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.SimpleExpression;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Unit tests for {@link CreatePurchaseReturnHandler}.
 *
 * <p>Covers the ETP-4028 currency-inheritance requirement (the newly created return receipt must
 * carry {@code EM_Etgo_Currency_ID}, mandatory on {@code M_InOut}, copied from the original goods
 * receipt, since {@code createPurchaseReturn} builds the return header directly via
 * {@code OBProvider} rather than through {@link NeoCommercialDocumentFactory}) and the ETP-5711
 * rules: the over-return cap, lines optional ("everything still returnable"), the return always
 * left in draft and the response that lets an agent report the linkage.
 *
 * @covers com.etendoerp.go.schemaforge.CreatePurchaseReturnHandler
 * @covers com.etendoerp.go.schemaforge.ReturnFromDocumentSupport
 */
public class CreatePurchaseReturnHandlerTest {

  /** What one run of the handler produced, with the mocks a test asserts on. */
  private static final class Run {
    NeoResponse response;
    ShipmentInOut returnReceipt;
    Currency originalCurrency;
    final List<ShipmentInOutLine> createdLines = new ArrayList<>();
    boolean headerBuilt;
  }

  private static JSONObject entry(String lineId, String quantity) throws Exception {
    return new JSONObject().put("lineId", lineId).put("returnQuantity", quantity);
  }

  private static JSONObject body(JSONObject... entries) throws Exception {
    JSONArray lines = new JSONArray();
    for (JSONObject e : entries) {
      lines.put(e);
    }
    return new JSONObject().put("lines", lines);
  }

  /**
   * Runs {@code createPurchaseReturn} on receipt {@code receipt-1} made of {@code sourceLines},
   * where {@code returned} says how much of each source line other non-voided returns already hold.
   */
  private static Run run(JSONObject requestBody, BiFunction<String, String, BigDecimal> returned,
      ShipmentInOutLine... sourceLines) throws Exception {
    return runWithDocTypes(requestBody, returned, null, sourceLines);
  }

  /** A return document type of client {@code clientId} living in organization {@code orgId}. */
  private static DocumentType docType(String name, String clientId, String orgId) {
    DocumentType dt = mock(DocumentType.class);
    when(dt.getId()).thenReturn(name);
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    when(dt.getClient()).thenReturn(client);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    when(dt.getOrganization()).thenReturn(org);
    return dt;
  }

  /**
   * Same as {@link #run} but with {@code docTypes} as every active MMR return document type of the
   * database, whatever its client. The criteria answers like the database: only the types of the
   * client the query was restricted to. Receipt {@code receipt-1} belongs to client
   * {@code client-A}. {@code null} means the single default type of the receipt's own org.
   */
  @SuppressWarnings("unchecked")
  private static Run runWithDocTypes(JSONObject requestBody,
      BiFunction<String, String, BigDecimal> returned, List<DocumentType> docTypes,
      ShipmentInOutLine... sourceLines) throws Exception {
    Run run = new Run();
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
      ReturnedQuantityTestSupport.stubReturnedQuantities(dal, returned);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn("org-100");

      run.originalCurrency = mock(Currency.class);

      ShipmentInOut original = ReturnedQuantityTestSupport.completedSource("receipt-1", sourceLines);
      when(original.getOrganization()).thenReturn(org);
      when(original.getPartnerAddress()).thenReturn(null);
      when(original.getEtgoCurrency()).thenReturn(run.originalCurrency);
      when(dal.get(eq(ShipmentInOut.class), eq("receipt-1"))).thenReturn(original);

      Client clientA = mock(Client.class);
      when(clientA.getId()).thenReturn("client-A");
      when(original.getClient()).thenReturn(clientA);

      List<DocumentType> all = docTypes != null ? docTypes
          : Collections.singletonList(docType("own", "client-A", "org-100"));
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      List<Criterion> restrictions = new ArrayList<>();
      when(dal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenAnswer(inv -> {
        restrictions.add(inv.getArgument(0));
        return criteria;
      });
      when(criteria.addOrderBy(anyString(), anyBoolean())).thenReturn(criteria);
      when(criteria.list()).thenAnswer(inv -> {
        String clientRestriction = null;
        for (Criterion c : restrictions) {
          if (c instanceof SimpleExpression
              && "client".equals(((SimpleExpression) c).getPropertyName())) {
            clientRestriction = ((Client) ((SimpleExpression) c).getValue()).getId();
          }
        }
        List<DocumentType> visible = new ArrayList<>();
        for (DocumentType dt : all) {
          if (clientRestriction == null || clientRestriction.equals(dt.getClient().getId())) {
            visible.add(dt);
          }
        }
        return visible;
      });

      run.returnReceipt = mock(ShipmentInOut.class);
      // Non-blank, not starting with "<" — skips the ensureDocumentNo body entirely.
      when(run.returnReceipt.getDocumentNo()).thenReturn("PR-001");
      when(run.returnReceipt.getId()).thenReturn("return-1");
      when(run.returnReceipt.getDocumentStatus()).thenReturn("DR");
      OBProvider provider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(provider);
      when(provider.get(ShipmentInOut.class)).thenAnswer(inv -> {
        run.headerBuilt = true;
        return run.returnReceipt;
      });
      when(provider.get(ShipmentInOutLine.class)).thenAnswer(inv -> {
        ShipmentInOutLine line = mock(ShipmentInOutLine.class);
        when(line.getId()).thenReturn("ret-line-" + (run.createdLines.size() + 1));
        run.createdLines.add(line);
        return line;
      });

      NeoContext neoCtx = NeoContext.builder()
          .httpMethod("POST")
          .endpointType(NeoEndpointType.ACTION)
          .fieldName("createPurchaseReturn")
          .recordId("receipt-1")
          .requestBody(requestBody)
          .build();

      run.response = new CreatePurchaseReturnHandler().handle(neoCtx);
    }
    return run;
  }

  private static BiFunction<String, String, BigDecimal> nothingReturned() {
    return (src, excl) -> BigDecimal.ZERO;
  }

  /**
   * Verifies that {@code returnReceipt.setEtgoCurrency(original.getEtgoCurrency())} is called
   * while building the return receipt header, so the mandatory currency column is never left
   * null on the new {@code M_InOut} record.
   */
  @Test
  public void testHandleCopiesCurrencyFromOriginalReceiptToReturnReceipt() throws Exception {
    Run run = run(body(entry("any-line", "1")), nothingReturned(),
        ReturnedQuantityTestSupport.sourceLine("any-line", "Agua", "5"));

    assertNotNull(run.response);
    assertEquals(201, run.response.getHttpStatus());
    verify(run.returnReceipt).setEtgoCurrency(run.originalCurrency);
  }

  /** The return is ALWAYS left in draft: it is never completed by the action. */
  @Test
  public void testHandleLeavesTheReturnInDraft() throws Exception {
    Run run = run(body(entry("L1", "2")), nothingReturned(),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"));

    assertEquals(201, run.response.getHttpStatus());
    verify(run.returnReceipt).setDocumentStatus("DR");
    verify(run.returnReceipt).setProcessed(false);
    assertEquals("DR", run.response.getBody().getJSONObject("response").getJSONObject("data")
        .getString("documentStatus"));
  }

  /** The response carries what an agent needs to report the linkage back to the user. */
  @Test
  public void testHandleAnswersTheReturnAndItsLinkedLines() throws Exception {
    Run run = run(body(entry("L1", "2"), entry("L2", "1")), nothingReturned(),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"),
        ReturnedQuantityTestSupport.sourceLine("L2", "Cerveza", "3"));

    JSONObject data = run.response.getBody().getJSONObject("response").getJSONObject("data");
    assertEquals("return-1", data.getString("id"));
    assertEquals("PR-001", data.getString("documentNo"));
    JSONArray lines = data.getJSONArray("lines");
    assertEquals(2, lines.length());
    JSONObject first = lines.getJSONObject(0);
    assertEquals("ret-line-1", first.getString("id"));
    assertEquals("L1", first.getString("sourceLineId"));
    assertEquals("Agua", first.getString("productName"));
    assertEquals(2, first.getDouble("returnedQuantity"), 0.0001);
    assertEquals(5, first.getDouble("originalQuantity"), 0.0001);
    assertEquals("L2", lines.getJSONObject(1).getString("sourceLineId"));
  }

  /** Lines omitted: everything still returnable of every line is returned. */
  @Test
  public void testHandleWithoutLinesReturnsEverythingStillReturnable() throws Exception {
    Run run = run(new JSONObject(), (src, excl) -> "L2".equals(src) ? new BigDecimal("1") : BigDecimal.ZERO,
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"),
        ReturnedQuantityTestSupport.sourceLine("L2", "Cerveza", "3"));

    assertEquals(201, run.response.getHttpStatus());
    JSONArray lines = run.response.getBody().getJSONObject("response").getJSONObject("data")
        .getJSONArray("lines");
    assertEquals(2, lines.length());
    assertEquals(5, lines.getJSONObject(0).getDouble("returnedQuantity"), 0.0001);
    assertEquals(2, lines.getJSONObject(1).getDouble("returnedQuantity"), 0.0001);
    verify(run.returnReceipt).setDocumentStatus("DR");
  }

  /** An empty lines array means the same as no lines: all of it. */
  @Test
  public void testHandleWithEmptyLinesArrayReturnsEverythingStillReturnable() throws Exception {
    Run run = run(new JSONObject().put("lines", new JSONArray()), nothingReturned(),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"));

    assertEquals(201, run.response.getHttpStatus());
    assertEquals(1, run.createdLines.size());
  }

  /** A line with nothing left is skipped in "all" mode, not refused. */
  @Test
  public void testHandleWithoutLinesSkipsFullyReturnedLines() throws Exception {
    Run run = run(new JSONObject(), (src, excl) -> "L1".equals(src) ? new BigDecimal("5") : BigDecimal.ZERO,
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"),
        ReturnedQuantityTestSupport.sourceLine("L2", "Cerveza", "3"));

    assertEquals(201, run.response.getHttpStatus());
    assertEquals(1, run.createdLines.size());
  }

  /** Nothing left to return on any line: a clear 422, and no header is built. */
  @Test
  public void testHandleWithNothingLeftToReturnIsRefused() throws Exception {
    Run run = run(new JSONObject(), (src, excl) -> new BigDecimal("5"),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"));

    assertEquals(422, run.response.getHttpStatus());
    assertEquals("nothingLeftToReturn",
        run.response.getBody().getJSONObject("error").getString("code"));
    assertFalse("a refused return builds no header", run.headerBuilt);
  }

  /** More than what is left is a 422 naming product, requested and available; no header. */
  @Test
  public void testHandleOverReturnIsRefusedBeforeAnythingIsCreated() throws Exception {
    Run run = run(body(entry("L1", "5")), (src, excl) -> new BigDecimal("6"),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "10"));

    assertEquals(422, run.response.getHttpStatus());
    JSONObject error = run.response.getBody().getJSONObject("error");
    assertEquals("returnQuantityExceedsAvailable", error.getString("code"));
    assertEquals("Agua", error.getString("product"));
    assertEquals(5, error.getDouble("requested"), 0.0001);
    assertEquals(4, error.getDouble("available"), 0.0001);
    assertEquals("L1", error.getString("sourceLineId"));
    assertTrue(error.getString("message"), error.getString("message").contains("Agua"));
    assertFalse("a refused return builds no header", run.headerBuilt);
    assertTrue(run.createdLines.isEmpty());
  }

  /** A second partial return with exactly the remainder works. */
  @Test
  public void testHandleSecondPartialWithTheRemainderIsAccepted() throws Exception {
    Run run = run(body(entry("L1", "4")), (src, excl) -> new BigDecimal("6"),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "10"));

    assertEquals(201, run.response.getHttpStatus());
    assertEquals(1, run.createdLines.size());
  }

  /** The same line listed twice accumulates: 3 + 3 against 5 is refused. */
  @Test
  public void testHandleDuplicateLinesAccumulate() throws Exception {
    Run run = run(body(entry("L1", "3"), entry("L1", "3")), nothingReturned(),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"));

    assertEquals(422, run.response.getHttpStatus());
    assertEquals(6, run.response.getBody().getJSONObject("error").getDouble("requested"), 0.0001);
    assertFalse(run.headerBuilt);
  }

  /** A line that is not on the receipt is refused instead of silently dropped. */
  @Test
  public void testHandleLineNotOnTheReceiptIsRefused() throws Exception {
    Run run = run(body(entry("OTHER", "1")), nothingReturned(),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"));

    assertEquals(422, run.response.getHttpStatus());
    assertEquals("returnLineNotInDocument",
        run.response.getBody().getJSONObject("error").getString("code"));
    assertFalse(run.headerBuilt);
  }

  /** Lines given but none with a usable quantity: still a 400, as before. */
  @Test
  public void testHandleLinesWithoutUsableQuantityAre400() throws Exception {
    Run run = run(body(entry("L1", "0")), nothingReturned(),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"));

    assertEquals(400, run.response.getHttpStatus());
    assertFalse(run.headerBuilt);
  }

  /** The declared {@code reason} is kept on the return instead of being silently ignored. */
  @Test
  public void testHandleKeepsTheReasonAsTheDescription() throws Exception {
    Run run = run(body(entry("L1", "1")).put("reason", "damaged on arrival"), nothingReturned(),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"));

    assertEquals(201, run.response.getHttpStatus());
    verify(run.returnReceipt).setDescription("damaged on arrival");
  }

  /**
   * The return takes the document type of the SOURCE receipt's client. Another client's own RTV
   * type must never win, not even when it matches the organization or sorts first: it would book
   * the return on that client's document sequence.
   */
  @Test
  public void testHandleUsesTheDocTypeOfTheReceiptsClientNeverAnotherClients() throws Exception {
    DocumentType otherClientSameOrg = docType("other-org", "client-B", "org-100");
    DocumentType otherClientOrgZero = docType("other-0", "client-B", "0");
    DocumentType ownOrgZero = docType("own-0", "client-A", "0");
    Run run = runWithDocTypes(body(entry("L1", "1")), nothingReturned(),
        java.util.Arrays.asList(otherClientSameOrg, otherClientOrgZero, ownOrgZero),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"));

    assertEquals(201, run.response.getHttpStatus());
    verify(run.returnReceipt).setDocumentType(ownOrgZero);
  }

  /** Inside the receipt's client the organization's own type beats the client's org-0 one. */
  @Test
  public void testHandlePrefersTheReceiptsOrgOverOrgZeroWithinItsClient() throws Exception {
    DocumentType ownOrgZero = docType("own-0", "client-A", "0");
    DocumentType ownOrg = docType("own-org", "client-A", "org-100");
    Run run = runWithDocTypes(body(entry("L1", "1")), nothingReturned(),
        java.util.Arrays.asList(ownOrgZero, ownOrg),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"));

    verify(run.returnReceipt).setDocumentType(ownOrg);
  }

  /** The receipt's client has no RTV type: refused, never borrowed from another client. */
  @Test
  public void testHandleWithNoDocTypeForTheReceiptsClientIsRefusedNotBorrowed() throws Exception {
    Run run = runWithDocTypes(body(entry("L1", "1")), nothingReturned(),
        java.util.Arrays.asList(docType("other-org", "client-B", "org-100"),
            docType("other-0", "client-B", "0")),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"));

    assertEquals(500, run.response.getHttpStatus());
    verify(run.returnReceipt, never()).setDocumentType(any(DocumentType.class));
  }

  @Test
  public void testHandleWithoutReasonLeavesTheDescription() throws Exception {
    Run run = run(body(entry("L1", "1")), nothingReturned(),
        ReturnedQuantityTestSupport.sourceLine("L1", "Agua", "5"));

    verify(run.returnReceipt, never()).setDescription(anyString());
  }
}
