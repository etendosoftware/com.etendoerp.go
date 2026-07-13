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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.advpaymentmngt.ProcessInvoiceUtil;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.ReversedInvoice;

/**
 * Unit tests for {@link AbstractInvoiceHeaderHandler}.
 *
 * <p>Uses a minimal concrete subclass ({@link TestHandler}) to expose the protected methods
 * for direct testing. Covers all branches of:
 * <ul>
 *   <li>{@code validateDocTypeLock}</li>
 *   <li>{@code validateOriginInvoiceRequired}</li>
 *   <li>{@code persistOriginInvoice} (including private helpers)</li>
 *   <li>{@code enrichOriginInvoice}</li>
 *   <li>{@code enrichInvoiceSubtype}</li>
 *   <li>{@code enrichDocTypeLocked}</li>
 *   <li>{@code completeInvoiceIfNeeded} (ETP-4388 — Verifactu/ProcessInvoiceHook dispatch fix)</li>
 * </ul>
 */
public class AbstractInvoiceHeaderHandlerTest {

  /**
   * Minimal concrete subclass — AR-style classification (ARC→NC, ARI_RM→DEV).
   * Exposes all protected methods as public for direct testing.
   */
  private static class TestHandler extends AbstractInvoiceHeaderHandler {
    @Override
    protected String classifyDocType(DocumentType dt) {
      String cat = dt.getDocumentCategory();
      if ("ARC".equals(cat)) {
        return SUBTYPE_NC;
      }
      if ("ARI_RM".equals(cat)) {
        return SUBTYPE_DEV;
      }
      return SUBTYPE_FAC;
    }

    @Override
    protected String getInvoiceSubtypeKey() {
      return "arInvoiceSubtype";
    }

    public NeoResponse callValidateDocTypeLock(NeoContext ctx) {
      return validateDocTypeLock(ctx);
    }

    public NeoResponse callValidateOriginInvoiceRequired(NeoContext ctx) {
      return validateOriginInvoiceRequired(ctx);
    }

    public void callPersistOriginInvoice(NeoContext ctx) {
      persistOriginInvoice(ctx);
    }

    public void callEnrichOriginInvoice(JSONObject rec, String id) throws Exception {
      enrichOriginInvoice(rec, id);
    }

    public void callEnrichInvoiceSubtype(JSONObject rec, String key) throws Exception {
      enrichInvoiceSubtype(rec, key);
    }

    public void callEnrichDocTypeLocked(JSONObject rec) throws Exception {
      enrichDocTypeLocked(rec);
    }
  }

  // ── ETP-4029: currency / exchange-rate hooks — test doubles ─────────────────

  /**
   * Exposes the package-private {@code autoCreateOrUpdateConversionRateDocument(String)}
   * overload for direct testing (it's declared {@code protected static} on the abstract
   * class, so a subclass reference is enough — no instance state needed).
   */
  private static void callAutoCreateOrUpdate(String invoiceId) {
    AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(invoiceId);
  }

  private static void callAutoCreateOrUpdate(NeoContext ctx) {
    AbstractInvoiceHeaderHandler.autoCreateOrUpdateConversionRateDocument(ctx);
  }

  private final TestHandler handler = new TestHandler();

  // ── validateDocTypeLock ──────────────────────────────────────────────────────

  @Test
  public void validateDocTypeLock_nonPutMethod_returnsNull() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").recordId("inv-1").build();
    assertNull(handler.callValidateDocTypeLock(ctx));
  }

  @Test
  public void validateDocTypeLock_putWithNullRecordId_returnsNull() {
    NeoContext ctx = NeoContext.builder().httpMethod("PUT").recordId(null).build();
    assertNull(handler.callValidateDocTypeLock(ctx));
  }

  @Test
  public void validateDocTypeLock_putWithNullBody_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").recordId("inv-1").requestBody(null).build();
    assertNull(handler.callValidateDocTypeLock(ctx));
  }

  @Test
  public void validateDocTypeLock_putBodyWithoutTransactionDocument_returnsNull() throws Exception {
    JSONObject body = new JSONObject().put("someOtherField", "value");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").recordId("inv-1").requestBody(body).build();
    assertNull(handler.callValidateDocTypeLock(ctx));
  }

  @Test
  public void validateDocTypeLock_putWithBlankTransactionDocument_returnsNull() throws Exception {
    JSONObject body = new JSONObject().put("transactionDocument", "");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").recordId("inv-1").requestBody(body).build();
    assertNull(handler.callValidateDocTypeLock(ctx));
  }

  @Test
  public void validateDocTypeLock_invoiceNotFoundInDb_returnsNull() throws Exception {
    JSONObject body = new JSONObject().put("transactionDocument", "dt-new");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").recordId("inv-missing").requestBody(body).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-missing")).thenReturn(null);

      assertNull(handler.callValidateDocTypeLock(ctx));
    }
  }

  @Test
  public void validateDocTypeLock_invoiceHasNoDocumentNo_returnsNull() throws Exception {
    JSONObject body = new JSONObject().put("transactionDocument", "dt-new");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").recordId("inv-draft").requestBody(body).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      when(dal.get(Invoice.class, "inv-draft")).thenReturn(invoice);
      when(invoice.getDocumentNo()).thenReturn(null);

      assertNull(handler.callValidateDocTypeLock(ctx));
    }
  }

  @Test
  public void validateDocTypeLock_sameDocTypeId_returnsNull() throws Exception {
    JSONObject body = new JSONObject().put("transactionDocument", "dt-same");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").recordId("inv-saved").requestBody(body).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      DocumentType currentDt = mock(DocumentType.class);
      when(dal.get(Invoice.class, "inv-saved")).thenReturn(invoice);
      when(invoice.getDocumentNo()).thenReturn("FAC-001");
      when(invoice.getTransactionDocument()).thenReturn(currentDt);
      when(currentDt.getId()).thenReturn("dt-same");

      assertNull(handler.callValidateDocTypeLock(ctx));
    }
  }

  @Test
  public void validateDocTypeLock_differentDocTypeId_returns400() throws Exception {
    JSONObject body = new JSONObject().put("transactionDocument", "dt-new");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").recordId("inv-saved").requestBody(body).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      DocumentType currentDt = mock(DocumentType.class);
      when(dal.get(Invoice.class, "inv-saved")).thenReturn(invoice);
      when(invoice.getDocumentNo()).thenReturn("FAC-001");
      when(invoice.getTransactionDocument()).thenReturn(currentDt);
      when(currentDt.getId()).thenReturn("dt-original");

      NeoResponse result = handler.callValidateDocTypeLock(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  @Test
  public void validateDocTypeLock_dbException_returnsNull() throws Exception {
    JSONObject body = new JSONObject().put("transactionDocument", "dt-any");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").recordId("inv-err").requestBody(body).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-err")).thenThrow(new RuntimeException("DB error"));

      assertNull(handler.callValidateDocTypeLock(ctx));
    }
  }

  // ── validateOriginInvoiceRequired ────────────────────────────────────────────

  @Test
  public void validateOriginInvoiceRequired_getMethod_returnsNull() {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").build();
    assertNull(handler.callValidateOriginInvoiceRequired(ctx));
  }

  @Test
  public void validateOriginInvoiceRequired_deleteMethod_returnsNull() {
    NeoContext ctx = NeoContext.builder().httpMethod("DELETE").build();
    assertNull(handler.callValidateOriginInvoiceRequired(ctx));
  }

  @Test
  public void validateOriginInvoiceRequired_postNullBody_returnsNull() {
    NeoContext ctx = NeoContext.builder().httpMethod("POST").requestBody(null).build();
    assertNull(handler.callValidateOriginInvoiceRequired(ctx));
  }

  @Test
  public void validateOriginInvoiceRequired_postBlankDocTypeId_returnsNull() throws Exception {
    JSONObject body = new JSONObject().put("transactionDocument", "");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").requestBody(body).build();
    assertNull(handler.callValidateOriginInvoiceRequired(ctx));
  }

  @Test
  public void validateOriginInvoiceRequired_facSubtype_returnsNull() throws Exception {
    JSONObject body = new JSONObject().put("transactionDocument", "dt-ari");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").requestBody(body).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARI");
      when(dal.get(DocumentType.class, "dt-ari")).thenReturn(dt);

      assertNull(handler.callValidateOriginInvoiceRequired(ctx));
    }
  }

  @Test
  public void validateOriginInvoiceRequired_ncSubtypeWithOrigin_returnsNull() throws Exception {
    JSONObject body = new JSONObject()
        .put("transactionDocument", "dt-arc")
        .put("originInvoice", "inv-origin-1");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").requestBody(body).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARC");
      when(dal.get(DocumentType.class, "dt-arc")).thenReturn(dt);

      assertNull(handler.callValidateOriginInvoiceRequired(ctx));
    }
  }

  @Test
  public void validateOriginInvoiceRequired_ncSubtypeWithoutOrigin_returns400WithCreditNoteLabel() throws Exception {
    JSONObject body = new JSONObject().put("transactionDocument", "dt-arc");
    NeoContext ctx = NeoContext.builder().httpMethod("PUT").requestBody(body).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARC");
      when(dal.get(DocumentType.class, "dt-arc")).thenReturn(dt);

      NeoResponse result = handler.callValidateOriginInvoiceRequired(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      assertTrue(result.getBody().toString().contains("Credit Note"));
    }
  }

  @Test
  public void validateOriginInvoiceRequired_devSubtypeWithoutOrigin_returns400WithReturnInvoiceLabel() throws Exception {
    JSONObject body = new JSONObject().put("transactionDocument", "dt-ari-rm");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").requestBody(body).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARI_RM");
      when(dal.get(DocumentType.class, "dt-ari-rm")).thenReturn(dt);

      NeoResponse result = handler.callValidateOriginInvoiceRequired(ctx);
      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      assertTrue(result.getBody().toString().contains("Return Invoice"));
    }
  }

  @Test
  public void validateOriginInvoiceRequired_exception_returnsNull() throws Exception {
    JSONObject body = new JSONObject().put("transactionDocument", "dt-err");
    NeoContext ctx = NeoContext.builder().httpMethod("POST").requestBody(body).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(DocumentType.class, "dt-err")).thenThrow(new RuntimeException("DB fail"));

      assertNull(handler.callValidateOriginInvoiceRequired(ctx));
    }
  }

  // ── persistOriginInvoice ─────────────────────────────────────────────────────

  @Test
  public void persistOriginInvoice_nullBody_returnsEarlyWithoutDbCalls() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").recordId("inv-1").requestBody(null).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      handler.callPersistOriginInvoice(ctx);

      Mockito.verifyNoInteractions(dal);
    }
  }

  @Test
  public void persistOriginInvoice_noPreviousResultOnPost_returnsEarly() throws Exception {
    JSONObject body = new JSONObject().put("originInvoice", "orig-1");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").recordId(null).requestBody(body).build();
    // no previousResult set — getPreviousResult() returns null

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      handler.callPersistOriginInvoice(ctx);

      Mockito.verify(dal, Mockito.never()).flush();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void persistOriginInvoice_putWithRecordId_deletesExistingAndCreatesLink() throws Exception {
    JSONObject body = new JSONObject().put("originInvoice", "origin-inv-1");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").recordId("inv-put").requestBody(body).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      Invoice origin = mock(Invoice.class);
      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      when(dal.get(Invoice.class, "inv-put")).thenReturn(invoice);
      when(dal.get(Invoice.class, "origin-inv-1")).thenReturn(origin);
      when(invoice.getClient()).thenReturn(client);
      when(invoice.getOrganization()).thenReturn(org);

      OBCriteria<ReversedInvoice> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(ReversedInvoice.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.emptyList());

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      ReversedInvoice link = mock(ReversedInvoice.class);
      when(provider.get(ReversedInvoice.class)).thenReturn(link);

      handler.callPersistOriginInvoice(ctx);

      verify(dal).save(link);
      verify(dal).flush();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void persistOriginInvoice_withoutOriginId_onlyDeletesExistingLinks() throws Exception {
    JSONObject body = new JSONObject();
    // no "originInvoice" field
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT").recordId("inv-del").requestBody(body).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      ReversedInvoice existing = mock(ReversedInvoice.class);
      when(dal.get(Invoice.class, "inv-del")).thenReturn(invoice);

      OBCriteria<ReversedInvoice> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(ReversedInvoice.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(existing));

      handler.callPersistOriginInvoice(ctx);

      verify(dal).remove(existing);
      verify(dal).flush();
      Mockito.verify(dal, Mockito.never()).save(any(ReversedInvoice.class));
    }
  }

  @Test
  public void persistOriginInvoice_extractsIdFromPostResponse() throws Exception {
    // Real shape produced by DefaultJsonDataService.add() for POST/create: response.data is
    // always a JSONArray with exactly one element — never a plain JSONObject. See
    // NeoHandlerUtils#extractCreatedIdFromPreviousResult javadoc for the underlying rationale.
    JSONObject data = new JSONObject().put("id", "new-inv-from-post");
    JSONArray dataArray = new JSONArray().put(data);
    JSONObject response = new JSONObject().put("data", dataArray);
    JSONObject respBody = new JSONObject().put("response", response);
    NeoResponse prevResult = new NeoResponse(201, respBody);

    JSONObject body = new JSONObject().put("originInvoice", "orig-2");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").recordId(null).requestBody(body)
        .previousResult(prevResult).build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBProvider> providerMock = Mockito.mockStatic(OBProvider.class)) {

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      Invoice origin = mock(Invoice.class);
      Client client = mock(Client.class);
      Organization org = mock(Organization.class);
      when(dal.get(Invoice.class, "new-inv-from-post")).thenReturn(invoice);
      when(dal.get(Invoice.class, "orig-2")).thenReturn(origin);
      when(invoice.getClient()).thenReturn(client);
      when(invoice.getOrganization()).thenReturn(org);

      @SuppressWarnings("unchecked")
      OBCriteria<ReversedInvoice> criteria = mock(OBCriteria.class);
      when(dal.createCriteria(ReversedInvoice.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.emptyList());

      OBProvider provider = mock(OBProvider.class);
      providerMock.when(OBProvider::getInstance).thenReturn(provider);
      ReversedInvoice link = mock(ReversedInvoice.class);
      when(provider.get(ReversedInvoice.class)).thenReturn(link);

      handler.callPersistOriginInvoice(ctx);

      verify(dal).save(link);
      verify(dal).flush();
    }
  }

  // ── enrichOriginInvoice ──────────────────────────────────────────────────────

  @Test
  public void enrichOriginInvoice_rowFound_setsBothFields() throws Exception {
    JSONObject rec = new JSONObject();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal roInst = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(roInst);

      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(roInst.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(any())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true);
      when(rs.getString(1)).thenReturn("origin-inv-id");
      when(rs.getString(2)).thenReturn("ORIG-001");

      handler.callEnrichOriginInvoice(rec, "inv-1");

      assertEquals("origin-inv-id", rec.getString("originInvoice"));
      assertEquals("ORIG-001", rec.getString("originInvoice$_identifier"));
    }
  }

  @Test
  public void enrichOriginInvoice_noRowFound_setsBothFieldsToNull() throws Exception {
    JSONObject rec = new JSONObject();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal roInst = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(roInst);

      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(roInst.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(any())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      handler.callEnrichOriginInvoice(rec, "inv-no-origin");

      assertTrue(rec.isNull("originInvoice"));
      assertTrue(rec.isNull("originInvoice$_identifier"));
    }
  }

  @Test
  public void enrichOriginInvoice_sqlException_silentlyIgnored() throws Exception {
    JSONObject rec = new JSONObject();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal roInst = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(roInst);

      Connection conn = mock(Connection.class);
      when(roInst.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(any())).thenThrow(new RuntimeException("SQL error"));

      handler.callEnrichOriginInvoice(rec, "inv-sql-err");
      // no exception thrown, rec unchanged
      assertEquals(0, rec.length());
    }
  }

  // ── enrichInvoiceSubtype ─────────────────────────────────────────────────────

  @Test
  public void enrichInvoiceSubtype_noDocTypeId_setsFacSubtype() throws Exception {
    JSONObject rec = new JSONObject();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      handler.callEnrichInvoiceSubtype(rec, "arInvoiceSubtype");

      assertEquals("FAC", rec.getString("arInvoiceSubtype"));
    }
  }

  @Test
  public void enrichInvoiceSubtype_arcDocTypeId_setsNcSubtype() throws Exception {
    JSONObject rec = new JSONObject().put("transactionDocument", "dt-arc");

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      DocumentType dt = mock(DocumentType.class);
      when(dt.getDocumentCategory()).thenReturn("ARC");
      when(dal.get(DocumentType.class, "dt-arc")).thenReturn(dt);

      handler.callEnrichInvoiceSubtype(rec, "arInvoiceSubtype");

      assertEquals("NC", rec.getString("arInvoiceSubtype"));
    }
  }

  // ── enrichDocTypeLocked ──────────────────────────────────────────────────────

  @Test
  public void enrichDocTypeLocked_setsDocTypeLockedToTrue() throws Exception {
    JSONObject rec = new JSONObject();
    handler.callEnrichDocTypeLocked(rec);
    assertTrue(rec.getBoolean("docTypeLocked"));
  }

  // ── validateLineQtyBeforeComplete — guard conditions ─────────────────────────

  /**
   * When the context is a GET (not PATCH/PUT/ACTION with CO), the method returns null immediately.
   */
  @Test
  public void validateLineQtyBeforeComplete_nonCompleteAction_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-1")
        .build();
    assertNull(AbstractInvoiceHeaderHandler.validateLineQtyBeforeComplete(ctx));
  }

  /**
   * PATCH with documentAction=CO but empty recordId returns null (nothing to check).
   */
  @Test
  public void validateLineQtyBeforeComplete_completeActionButNoRecordId_returnsNull() throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .requestBody(body)
        .build();
    assertNull(AbstractInvoiceHeaderHandler.validateLineQtyBeforeComplete(ctx));
  }

  /**
   * PATCH with documentAction=CO, invoice has no lines linked to shipment lines
   * (SQL returns no rows) — returns null (no over-invoice risk).
   */
  @Test
  public void validateLineQtyBeforeComplete_completeActionNoLinkedLines_returnsNull()
      throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-no-lines")
        .requestBody(body)
        .build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false); // no rows

      assertNull(AbstractInvoiceHeaderHandler.validateLineQtyBeforeComplete(ctx));
    }
  }

  /**
   * PATCH with documentAction=CO, invoice line qty <= pending — no error (guard passes).
   */
  @Test
  public void validateLineQtyBeforeComplete_completeActionLineQtyWithinPending_returnsNull()
      throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-ok")
        .requestBody(body)
        .build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoInvoiceSupport> supportMock =
             Mockito.mockStatic(NeoInvoiceSupport.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // One invoice line linked to inout-1/line-1, draftQty=3
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-1");
      when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("3"));
      when(rs.getString(3)).thenReturn("inout-1");
      when(rs.getString(4)).thenReturn("R-2024-001");

      // pending=5 >= draftQty=3 → no error
      Map<String, BigDecimal> pendingMap = new HashMap<>();
      pendingMap.put("line-1", new BigDecimal("5"));
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLine(eq("inout-1"), eq(false)))
          .thenReturn(pendingMap);

      assertNull(AbstractInvoiceHeaderHandler.validateLineQtyBeforeComplete(ctx));
    }
  }

  /**
   * PATCH with documentAction=CO, invoice line qty exceeds pending — returns 400.
   */
  @Test
  public void validateLineQtyBeforeComplete_completeActionOverInvoiced_returns400()
      throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-over")
        .requestBody(body)
        .build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoInvoiceSupport> supportMock =
             Mockito.mockStatic(NeoInvoiceSupport.class);
         MockedStatic<OBMessageUtils> msgMock =
             Mockito.mockStatic(OBMessageUtils.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // draftQty=10 > pending=3 → over-invoiced
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-2");
      when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("10"));
      when(rs.getString(3)).thenReturn("inout-2");
      when(rs.getString(4)).thenReturn("R-2024-002");

      Map<String, BigDecimal> pendingMap = new HashMap<>();
      pendingMap.put("line-2", new BigDecimal("3"));
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLine(eq("inout-2"), eq(false)))
          .thenReturn(pendingMap);

      msgMock.when(() -> OBMessageUtils.messageBD("ETGO_InvoiceLineAlreadyInvoiced"))
          .thenReturn("Document @docNo@ invoiced @invoiced@ pending @pending@");

      NeoResponse result = AbstractInvoiceHeaderHandler.validateLineQtyBeforeComplete(ctx);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  /**
   * PUT with documentAction=CO also triggers the over-invoice guard (both PATCH and PUT are valid).
   */
  @Test
  public void validateLineQtyBeforeComplete_putWithCompleteAction_alsoChecksGuard()
      throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-put-over")
        .requestBody(body)
        .build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoInvoiceSupport> supportMock =
             Mockito.mockStatic(NeoInvoiceSupport.class);
         MockedStatic<OBMessageUtils> msgMock =
             Mockito.mockStatic(OBMessageUtils.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-3");
      when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("5"));
      when(rs.getString(3)).thenReturn("inout-3");
      when(rs.getString(4)).thenReturn("R-PUT");

      Map<String, BigDecimal> pendingMap = new HashMap<>();
      pendingMap.put("line-3", new BigDecimal("2")); // 5 > 2 → error
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLine(eq("inout-3"), eq(false)))
          .thenReturn(pendingMap);

      msgMock.when(() -> OBMessageUtils.messageBD("ETGO_InvoiceLineAlreadyInvoiced"))
          .thenReturn("Document @docNo@ invoiced @invoiced@ pending @pending@");

      NeoResponse result = AbstractInvoiceHeaderHandler.validateLineQtyBeforeComplete(ctx);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
    }
  }

  /**
   * ACTION endpoint with fieldName=documentAction and fieldValues.documentAction=CO
   * also triggers the guard.
   */
  @Test
  public void validateLineQtyBeforeComplete_actionEndpointWithCoFieldValue_triggersGuard()
      throws Exception {
    JSONObject fieldValues = new JSONObject().put("documentAction", "CO");
    JSONObject body = new JSONObject().put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .recordId("inv-action")
        .requestBody(body)
        .build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false); // no linked lines → passes guard

      assertNull(AbstractInvoiceHeaderHandler.validateLineQtyBeforeComplete(ctx));
    }
  }

  /**
   * ACTION endpoint with fieldName=documentAction and docAction=RE (not CO)
   * does not trigger the guard.
   */
  @Test
  public void validateLineQtyBeforeComplete_actionEndpointNonCoAction_returnsNull()
      throws Exception {
    JSONObject fieldValues = new JSONObject().put("documentAction", "RE");
    JSONObject body = new JSONObject().put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .recordId("inv-re")
        .requestBody(body)
        .build();
    assertNull(AbstractInvoiceHeaderHandler.validateLineQtyBeforeComplete(ctx));
  }

  /**
   * ACTION endpoint with mismatched fieldName does not trigger the guard.
   */
  @Test
  public void validateLineQtyBeforeComplete_actionEndpointWrongFieldName_returnsNull()
      throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("someOtherAction")
        .recordId("inv-other")
        .requestBody(body)
        .build();
    assertNull(AbstractInvoiceHeaderHandler.validateLineQtyBeforeComplete(ctx));
  }

  /**
   * When the invoice lines SQL throws an unexpected exception the method catches it
   * and returns null (fail-open so completion is not blocked by a technical error).
   */
  @Test
  public void validateLineQtyBeforeComplete_sqlException_returnsNull() throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-sql-err")
        .requestBody(body)
        .build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("connection lost"));

      assertNull(AbstractInvoiceHeaderHandler.validateLineQtyBeforeComplete(ctx));
    }
  }

  /**
   * When draftQty is zero or negative the line is skipped, and no error is returned.
   */
  @Test
  public void validateLineQtyBeforeComplete_zeroOrNegativeDraftQty_lineSkipped()
      throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-zero-qty")
        .requestBody(body)
        .build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<NeoInvoiceSupport> supportMock =
             Mockito.mockStatic(NeoInvoiceSupport.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getReadOnlyInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);

      // draftQty=0 → should be skipped regardless of pending
      when(rs.next()).thenReturn(true, false);
      when(rs.getString(1)).thenReturn("line-zero");
      when(rs.getBigDecimal(2)).thenReturn(BigDecimal.ZERO);
      when(rs.getString(3)).thenReturn("inout-z");
      when(rs.getString(4)).thenReturn("R-ZERO");

      // Even if pending is also zero, no error should be triggered
      supportMock.when(() -> NeoInvoiceSupport.computePendingQtyPerLine(eq("inout-z"), eq(false)))
          .thenReturn(Collections.emptyMap());

      assertNull(AbstractInvoiceHeaderHandler.validateLineQtyBeforeComplete(ctx));
    }
  }

  // ── completeInvoiceIfNeeded — ETP-4388 ───────────────────────────────────────

  /**
   * Not a completion request (plain GET) → returns null immediately, no CDI/DB interaction.
   */
  @Test
  public void completeInvoiceIfNeeded_nonCompleteAction_returnsNull() {
    NeoContext ctx = NeoContext.builder()
        .httpMethod("GET")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-1")
        .build();
    assertNull(AbstractInvoiceHeaderHandler.completeInvoiceIfNeeded(ctx));
  }

  /**
   * PATCH with documentAction != "CO" is not a completion request → returns null.
   */
  @Test
  public void completeInvoiceIfNeeded_docActionNotCO_returnsNull() throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "RE");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-1")
        .requestBody(body)
        .build();
    assertNull(AbstractInvoiceHeaderHandler.completeInvoiceIfNeeded(ctx));
  }

  /**
   * Completion action with a blank record id → 400, no CDI/DB interaction attempted.
   */
  @Test
  public void completeInvoiceIfNeeded_completeActionWithBlankRecordId_returns400() throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("")
        .requestBody(body)
        .build();

    NeoResponse result = AbstractInvoiceHeaderHandler.completeInvoiceIfNeeded(ctx);

    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  /**
   * Completion action with a null record id → 400.
   */
  @Test
  public void completeInvoiceIfNeeded_completeActionWithNullRecordId_returns400() throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .endpointType(NeoEndpointType.CRUD)
        .recordId(null)
        .requestBody(body)
        .build();

    NeoResponse result = AbstractInvoiceHeaderHandler.completeInvoiceIfNeeded(ctx);

    assertNotNull(result);
    assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
  }

  /**
   * CRUD PATCH completion action: obtains {@link ProcessInvoiceUtil} via
   * {@link WeldUtils#getInstanceFromStaticBeanManager}, calls {@code process(...)} with the
   * expected args (id, "CO", empty void-date/supplier-reference defaults, vars, conn), and
   * translates a success {@link OBError} into a 200 response.
   */
  @Test
  public void completeInvoiceIfNeeded_success_invokesProcessInvoiceUtilAndReturnsOk() throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-complete-1")
        .requestBody(body)
        .obContext(null)
        .build();

    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    OBError success = new OBError();
    success.setType("Success");
    success.setMessage("Document completed");

    ProcessInvoiceUtil processInvoiceUtil = mock(ProcessInvoiceUtil.class);
    when(processInvoiceUtil.process(
        eq("inv-complete-1"), eq("CO"), eq(""), eq(""), eq(""), any(), any()))
        .thenReturn(success);

    try (MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
         MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any())).thenReturn(vars);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Process process = mock(Process.class);
      when(dal.get(Process.class, "111")).thenReturn(process);

      NeoResponse result = AbstractInvoiceHeaderHandler.completeInvoiceIfNeeded(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      verify(processInvoiceUtil).process(
          eq("inv-complete-1"), eq("CO"), eq(""), eq(""), eq(""), eq(vars), any());
    }
  }

  /**
   * An error {@link OBError} (e.g. a business-rule failure inside {@code ProcessInvoiceUtil})
   * is translated into a 400 error response carrying the OBError message.
   */
  @Test
  public void completeInvoiceIfNeeded_errorOBError_returns400WithMessage() throws Exception {
    JSONObject fieldValues = new JSONObject().put("documentAction", "CO");
    JSONObject body = new JSONObject().put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .recordId("inv-complete-err")
        .requestBody(body)
        .build();

    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    OBError error = new OBError();
    error.setType("Error");
    error.setTitle("Error");
    error.setMessage("BP currency is not set");

    ProcessInvoiceUtil processInvoiceUtil = mock(ProcessInvoiceUtil.class);
    when(processInvoiceUtil.process(
        eq("inv-complete-err"), eq("CO"), eq(""), eq(""), eq(""), any(), any()))
        .thenReturn(error);

    try (MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
         MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any())).thenReturn(vars);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Process.class, "111")).thenReturn(mock(Process.class));

      NeoResponse result = AbstractInvoiceHeaderHandler.completeInvoiceIfNeeded(ctx);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_BAD_REQUEST, result.getHttpStatus());
      assertTrue(result.getBody().toString().contains("BP currency is not set"));
    }
  }

  /**
   * An unexpected exception while resolving CDI/session state (e.g. Weld unavailable) is caught
   * and translated into a 500 — never propagated to the caller.
   */
  @Test
  public void completeInvoiceIfNeeded_unexpectedException_returns500() throws Exception {
    JSONObject body = new JSONObject().put("documentAction", "CO");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-complete-boom")
        .requestBody(body)
        .build();

    try (MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class)) {
      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any()))
          .thenThrow(new RuntimeException("session unavailable"));

      NeoResponse result = AbstractInvoiceHeaderHandler.completeInvoiceIfNeeded(ctx);

      assertNotNull(result);
      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, result.getHttpStatus());
    }
  }

  /**
   * The ACTION endpoint (POST /action/documentAction with fieldValues.documentAction=CO — the
   * shape sent by the draft-mode confirm button) also triggers completion, using the same
   * detection as {@code validateLineQtyBeforeComplete}.
   */
  @Test
  public void completeInvoiceIfNeeded_actionEndpointFieldValuesShape_triggersCompletion()
      throws Exception {
    JSONObject fieldValues = new JSONObject().put("documentAction", "CO");
    JSONObject body = new JSONObject().put("fieldValues", fieldValues);
    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST")
        .endpointType(NeoEndpointType.ACTION)
        .fieldName("documentAction")
        .recordId("inv-action-complete")
        .requestBody(body)
        .build();

    VariablesSecureApp vars = new VariablesSecureApp("u", "c", "o", "r", "en_US");
    OBError success = new OBError();
    success.setType("Success");

    ProcessInvoiceUtil processInvoiceUtil = mock(ProcessInvoiceUtil.class);
    when(processInvoiceUtil.process(
        eq("inv-action-complete"), eq("CO"), eq(""), eq(""), eq(""), any(), any()))
        .thenReturn(success);

    try (MockedStatic<NeoDefaultsService> defaultsMock = Mockito.mockStatic(NeoDefaultsService.class);
         MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      defaultsMock.when(() -> NeoDefaultsService.buildVariablesSecureApp(any())).thenReturn(vars);
      weldMock.when(() -> WeldUtils.getInstanceFromStaticBeanManager(ProcessInvoiceUtil.class))
          .thenReturn(processInvoiceUtil);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Process.class, "111")).thenReturn(mock(Process.class));

      NeoResponse result = AbstractInvoiceHeaderHandler.completeInvoiceIfNeeded(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
    }
  }

  /**
   * Regression safety: a normal (non-completion) CRUD PATCH does not attempt CDI/process
   * resolution at all — verifies no interaction with WeldUtils occurs.
   */
  @Test
  public void completeInvoiceIfNeeded_regularPatchWithoutDocumentAction_returnsNullNoWeldLookup()
      throws Exception {
    JSONObject body = new JSONObject().put("invoiceDate", "2026-07-03");
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PATCH")
        .endpointType(NeoEndpointType.CRUD)
        .recordId("inv-regular")
        .requestBody(body)
        .build();

    try (MockedStatic<WeldUtils> weldMock = Mockito.mockStatic(WeldUtils.class)) {
      assertNull(AbstractInvoiceHeaderHandler.completeInvoiceIfNeeded(ctx));
      weldMock.verifyNoInteractions();
    }
  }

  // ── ETP-4029: blockCalloutCurrencyUpdate ─────────────────────────────────────

  @Test
  public void blockCalloutCurrencyUpdate_currencyPushedByOtherTrigger_removesIt() throws Exception {
    JSONObject updates = new JSONObject().put("currency", "EUR-id").put("otherField", "x");

    AbstractInvoiceHeaderHandler.blockCalloutCurrencyUpdate(updates, "businessPartner");

    assertTrue(!updates.has("currency"));
    assertTrue(updates.has("otherField"));
  }

  @Test
  public void blockCalloutCurrencyUpdate_currencyIsTheTriggerField_keepsIt() throws Exception {
    JSONObject updates = new JSONObject().put("currency", "USD-id");

    AbstractInvoiceHeaderHandler.blockCalloutCurrencyUpdate(updates, "currency");

    assertEquals("USD-id", updates.getString("currency"));
  }

  @Test
  public void blockCalloutCurrencyUpdate_nullUpdates_doesNotThrow() {
    // Must be a no-op guard: null updates map is a valid callout response shape.
    AbstractInvoiceHeaderHandler.blockCalloutCurrencyUpdate(null, "businessPartner");
  }

  @Test
  public void blockCalloutCurrencyUpdate_updatesWithoutCurrencyKey_noop() throws Exception {
    JSONObject updates = new JSONObject().put("otherField", "unchanged");

    AbstractInvoiceHeaderHandler.blockCalloutCurrencyUpdate(updates, "otherField");

    assertEquals("unchanged", updates.getString("otherField"));
    assertTrue(!updates.has("currency"));
  }

  // ── ETP-4029: checkExchangeRateWarning ───────────────────────────────────────

  @Test
  public void checkExchangeRateWarning_nullFormState_noop() throws Exception {
    JSONObject body = new JSONObject();
    JSONObject requestBody = new JSONObject().put("value", "usd-id");

    AbstractInvoiceHeaderHandler.checkExchangeRateWarning(body, requestBody, null, "currency");

    assertTrue(!body.has("messages"));
  }

  @Test
  public void checkExchangeRateWarning_triggerFieldNotCurrency_noop() throws Exception {
    JSONObject body = new JSONObject();
    JSONObject requestBody = new JSONObject().put("value", "usd-id");
    JSONObject formState = new JSONObject()
        .put("currencyid", "usd-id").put("invoiceDate", "2026-07-01");

    AbstractInvoiceHeaderHandler.checkExchangeRateWarning(body, requestBody, formState, "businessPartner");

    assertTrue(!body.has("messages"));
  }

  @Test
  public void checkExchangeRateWarning_currencyChangeNoRateAvailable_appendsWarning() throws Exception {
    JSONObject body = new JSONObject();
    JSONObject requestBody = new JSONObject().put("value", "usd-id");
    JSONObject formState = new JSONObject()
        .put("currencyid", "usd-id").put("invoiceDate", "2026-07-01");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      OBContext obContext = mock(OBContext.class);
      Organization org = mock(Organization.class);
      Client client = mock(Client.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getCurrentOrganization()).thenReturn(org);
      when(obContext.getCurrentClient()).thenReturn(client);
      when(org.getId()).thenReturn("org-1");
      when(client.getId()).thenReturn("client-1");

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      // hasConversionRate queries via OBDal.getInstance().getConnection() — return no rows.
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false); // no conversion rate found

      AbstractInvoiceHeaderHandler.checkExchangeRateWarning(body, requestBody, formState, "currency");

      assertTrue(body.has("messages"));
      org.codehaus.jettison.json.JSONArray messages = body.getJSONArray("messages");
      assertEquals(1, messages.length());
      assertEquals("WARNING", messages.getJSONObject(0).getString("type"));
      assertEquals("noExchangeRateAvailable", messages.getJSONObject(0).getString("text"));
    }
  }

  @Test
  public void checkExchangeRateWarning_currencyChangeRateExists_noWarning() throws Exception {
    JSONObject body = new JSONObject();
    JSONObject requestBody = new JSONObject().put("value", "usd-id");
    JSONObject formState = new JSONObject()
        .put("currencyid", "usd-id").put("invoiceDate", "2026-07-01");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      OBContext obContext = mock(OBContext.class);
      Organization org = mock(Organization.class);
      Client client = mock(Client.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getCurrentOrganization()).thenReturn(org);
      when(obContext.getCurrentClient()).thenReturn(client);
      when(org.getId()).thenReturn("org-1");
      when(client.getId()).thenReturn("client-1");

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(true); // conversion rate exists

      AbstractInvoiceHeaderHandler.checkExchangeRateWarning(body, requestBody, formState, "currency");

      assertTrue(!body.has("messages"));
    }
  }

  @Test
  public void checkExchangeRateWarning_sameCurrencyAsOrg_noWarning() throws Exception {
    JSONObject body = new JSONObject();
    JSONObject requestBody = new JSONObject().put("value", "eur-id");
    JSONObject formState = new JSONObject()
        .put("currencyid", "eur-id").put("invoiceDate", "2026-07-01");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {

      OBContext obContext = mock(OBContext.class);
      Organization org = mock(Organization.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getCurrentOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      AbstractInvoiceHeaderHandler.checkExchangeRateWarning(body, requestBody, formState, "currency");

      assertTrue(!body.has("messages"));
    }
  }

  @Test
  public void checkExchangeRateWarning_blankInvoiceDate_noWarning() throws Exception {
    JSONObject body = new JSONObject();
    JSONObject requestBody = new JSONObject().put("value", "usd-id");
    JSONObject formState = new JSONObject().put("currencyid", "usd-id").put("invoiceDate", "");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {

      OBContext obContext = mock(OBContext.class);
      Organization org = mock(Organization.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getCurrentOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      AbstractInvoiceHeaderHandler.checkExchangeRateWarning(body, requestBody, formState, "currency");

      assertTrue(!body.has("messages"));
    }
  }

  @Test
  public void checkExchangeRateWarning_valueFallsBackToFormStateCurrencyId() throws Exception {
    // requestBody carries no "value" — must fall back to formState.currencyid.
    JSONObject body = new JSONObject();
    JSONObject requestBody = new JSONObject();
    JSONObject formState = new JSONObject()
        .put("currencyid", "usd-id").put("invoiceDate", "2026-07-01");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      OBContext obContext = mock(OBContext.class);
      Organization org = mock(Organization.class);
      Client client = mock(Client.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getCurrentOrganization()).thenReturn(org);
      when(obContext.getCurrentClient()).thenReturn(client);
      when(org.getId()).thenReturn("org-1");
      when(client.getId()).thenReturn("client-1");

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Connection conn = mock(Connection.class);
      PreparedStatement ps = mock(PreparedStatement.class);
      ResultSet rs = mock(ResultSet.class);
      when(dal.getConnection()).thenReturn(conn);
      when(conn.prepareStatement(anyString())).thenReturn(ps);
      when(ps.executeQuery()).thenReturn(rs);
      when(rs.next()).thenReturn(false);

      AbstractInvoiceHeaderHandler.checkExchangeRateWarning(body, requestBody, formState, "currencyid");

      assertTrue(body.has("messages"));
    }
  }

  @Test
  public void checkExchangeRateWarning_hasConversionRateThrows_failsOpenNoWarning() throws Exception {
    // hasConversionRate's own catch block returns true (fail-open) on any exception —
    // so an unexpected DB failure must NOT produce a false warning.
    JSONObject body = new JSONObject();
    JSONObject requestBody = new JSONObject().put("value", "usd-id");
    JSONObject formState = new JSONObject()
        .put("currencyid", "usd-id").put("invoiceDate", "2026-07-01");

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {

      OBContext obContext = mock(OBContext.class);
      Organization org = mock(Organization.class);
      Client client = mock(Client.class);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
      when(obContext.getCurrentOrganization()).thenReturn(org);
      when(obContext.getCurrentClient()).thenReturn(client);
      when(org.getId()).thenReturn("org-1");
      when(client.getId()).thenReturn("client-1");

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.getConnection()).thenThrow(new RuntimeException("connection lost"));

      AbstractInvoiceHeaderHandler.checkExchangeRateWarning(body, requestBody, formState, "currency");

      assertTrue(!body.has("messages"));
    }
  }

  // ── ETP-4029: autoCreateOrUpdateConversionRateDocument(String) ───────────────

  @Test
  public void autoCreateOrUpdate_blankInvoiceId_noDbCalls() {
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      callAutoCreateOrUpdate("");
      callAutoCreateOrUpdate((String) null);

      Mockito.verifyNoInteractions(dal);
    }
  }

  @Test
  public void autoCreateOrUpdate_invoiceNotFound_noop() {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-missing")).thenReturn(null);

      callAutoCreateOrUpdate("inv-missing");

      Mockito.verify(dal, Mockito.never()).getConnection();
    }
  }

  @Test
  public void autoCreateOrUpdate_invoiceCurrencyNull_noop() {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      Invoice invoice = mock(Invoice.class);
      when(dal.get(Invoice.class, "inv-no-currency")).thenReturn(invoice);
      when(invoice.getCurrency()).thenReturn(null);

      callAutoCreateOrUpdate("inv-no-currency");

      Mockito.verify(dal, Mockito.never()).getConnection();
    }
  }

  @Test
  public void autoCreateOrUpdate_sameAsOrgCurrency_noop() {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      Currency currency = mock(Currency.class);
      Organization org = mock(Organization.class);
      when(dal.get(Invoice.class, "inv-same-cur")).thenReturn(invoice);
      when(invoice.getCurrency()).thenReturn(currency);
      when(currency.getId()).thenReturn("eur-id");
      when(invoice.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      callAutoCreateOrUpdate("inv-same-cur");

      Mockito.verify(dal, Mockito.never()).getConnection();
    }
  }

  @Test
  public void autoCreateOrUpdate_orgCurrencyUnresolved_noop() {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      Currency currency = mock(Currency.class);
      Organization org = mock(Organization.class);
      when(dal.get(Invoice.class, "inv-no-org-cur")).thenReturn(invoice);
      when(invoice.getCurrency()).thenReturn(currency);
      when(currency.getId()).thenReturn("usd-id");
      when(invoice.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-unresolved");

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-unresolved")).thenReturn(null);

      callAutoCreateOrUpdate("inv-no-org-cur");

      Mockito.verify(dal, Mockito.never()).getConnection();
    }
  }

  @Test
  public void autoCreateOrUpdate_nullRateOverride_noop() {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      Currency currency = mock(Currency.class);
      Organization org = mock(Organization.class);
      when(dal.get(Invoice.class, "inv-no-rate")).thenReturn(invoice);
      when(invoice.getCurrency()).thenReturn(currency);
      when(currency.getId()).thenReturn("usd-id");
      when(invoice.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");
      when(invoice.getETGOCurrencyRate()).thenReturn(null);

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);

      callAutoCreateOrUpdate("inv-no-rate");

      Mockito.verify(dal, Mockito.never()).getConnection();
    }
  }

  /**
   * Happy path — insert branch: no existing {@code C_Conversion_Rate_Document} row.
   * Verifies docRate = 1/rate and foreignAmount = grandTotal × docRate are computed
   * correctly and passed to the INSERT statement.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void autoCreateOrUpdate_insertBranch_correctDocRateAndForeignAmount() throws Exception {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBContext obContext = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-1");
      when(obContext.getUser()).thenReturn(user);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      Currency currency = mock(Currency.class);
      Organization org = mock(Organization.class);
      Client client = mock(Client.class);
      when(dal.get(Invoice.class, "inv-insert")).thenReturn(invoice);
      when(invoice.getId()).thenReturn("inv-insert");
      when(invoice.getCurrency()).thenReturn(currency);
      when(currency.getId()).thenReturn("usd-id");
      when(invoice.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");
      when(invoice.getClient()).thenReturn(client);
      when(client.getId()).thenReturn("client-1");
      // rate (org→doc) = 2.0 → docRate (doc→org) = 0.5
      when(invoice.getETGOCurrencyRate()).thenReturn(new BigDecimal("2.0"));
      when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("100.00"));

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      // findConversionRateDocumentId (SELECT) → no existing row.
      PreparedStatement findPs = mock(PreparedStatement.class);
      ResultSet findRs = mock(ResultSet.class);
      when(findRs.next()).thenReturn(false);
      when(findPs.executeQuery()).thenReturn(findRs);

      PreparedStatement insertPs = mock(PreparedStatement.class);

      when(conn.prepareStatement(anyString()))
          .thenReturn(findPs)
          .thenReturn(insertPs);

      callAutoCreateOrUpdate("inv-insert");

      verify(session).refresh(invoice);
      verify(insertPs).executeUpdate();
      verify(insertPs).setString(eq(6), eq("inv-insert"));
      verify(insertPs).setString(eq(7), eq("usd-id"));
      verify(insertPs).setString(eq(8), eq("eur-id"));

      ArgumentCaptor<BigDecimal> captor =
          ArgumentCaptor.forClass(BigDecimal.class);
      verify(insertPs, times(2)).setBigDecimal(any(Integer.class), captor.capture());
      BigDecimal capturedDocRate = captor.getAllValues().get(0);
      BigDecimal capturedForeignAmount = captor.getAllValues().get(1);
      assertEquals(0, new BigDecimal("0.5").compareTo(capturedDocRate.stripTrailingZeros()));
      // foreignAmount = 100.00 × 0.5 = 50.00
      assertEquals(0, new BigDecimal("50.00").compareTo(capturedForeignAmount));
    }
  }

  /**
   * Regression test — real-world scenario manually verified end-to-end in the browser
   * (invoice 1000157, GO + Classic Etendo + accounting journal report all agreed): a USD
   * sales invoice with header rate (org→doc) 1.20 and grandTotalAmount 339.02 must produce
   * docRate = 1/1.20 = 0.833333333333 (scale 12, HALF_UP — matches the production
   * {@code BigDecimal.ONE.divide(rate, 12, RoundingMode.HALF_UP)} call) and
   * foreignAmount = 339.02 × 0.833333333333 rounded HALF_UP to 2 decimals = 282.52. That
   * same 282.52 was independently confirmed by Etendo core's accounting engine, which posted
   * a balanced journal entry (Debit 282.52 = Credit 256.84 + Credit 25.68) from this exact
   * {@code C_Conversion_Rate_Document} row — our code is only responsible for this rate/
   * foreign_amount pair, not for the net/tax split, which is out of scope here.
   */
  @Test
  public void autoCreateOrUpdate_realWorldInvoice1000157_pinsExactRateAndForeignAmount()
      throws Exception {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBContext obContext = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-1000157");
      when(obContext.getUser()).thenReturn(user);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      Currency currency = mock(Currency.class);
      Organization org = mock(Organization.class);
      Client client = mock(Client.class);
      when(dal.get(Invoice.class, "inv-1000157")).thenReturn(invoice);
      when(invoice.getId()).thenReturn("inv-1000157");
      when(invoice.getCurrency()).thenReturn(currency);
      when(currency.getId()).thenReturn("usd-id");
      when(invoice.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");
      when(invoice.getClient()).thenReturn(client);
      when(client.getId()).thenReturn("client-1");
      // EM_ETGO_Currency_Rate (org→doc) as observed on the real header.
      when(invoice.getETGOCurrencyRate()).thenReturn(new BigDecimal("1.20"));
      // grandTotalAmount as observed on the real invoice (339.02 USD).
      when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("339.02"));

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement findPs = mock(PreparedStatement.class);
      ResultSet findRs = mock(ResultSet.class);
      when(findRs.next()).thenReturn(false);
      when(findPs.executeQuery()).thenReturn(findRs);

      PreparedStatement insertPs = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString()))
          .thenReturn(findPs)
          .thenReturn(insertPs);

      callAutoCreateOrUpdate("inv-1000157");

      verify(insertPs).executeUpdate();

      ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
      verify(insertPs, times(2)).setBigDecimal(any(Integer.class), captor.capture());
      BigDecimal capturedDocRate = captor.getAllValues().get(0);
      BigDecimal capturedForeignAmount = captor.getAllValues().get(1);

      // docRate = BigDecimal.ONE.divide(1.20, 12, HALF_UP) = 0.833333333333
      assertEquals(0, new BigDecimal("0.833333333333").compareTo(capturedDocRate));
      // foreignAmount = 339.02 × 0.833333333333, HALF_UP to 2 decimals = 282.52 — matches
      // both the manually verified C_Conversion_Rate_Document.foreign_amount and the
      // Debit total (282.52) posted by Classic's accounting engine for this invoice.
      assertEquals(0, new BigDecimal("282.52").compareTo(capturedForeignAmount));
    }
  }

  /**
   * Second real value for variety, proving the formula generalizes beyond the single
   * hardcoded 1.20/339.02 case above — a different, still-realistic rate/total combination
   * (EUR-denominated purchase invoice equivalent: rate 0.85, grandTotal 500.00). The
   * production method is shared between sales and purchase invoices, so this also confirms
   * there is no direction-specific bug in the formula.
   */
  @Test
  public void autoCreateOrUpdate_differentRealisticRate_generalizesFormula() throws Exception {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBContext obContext = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-purchase");
      when(obContext.getUser()).thenReturn(user);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      Currency currency = mock(Currency.class);
      Organization org = mock(Organization.class);
      Client client = mock(Client.class);
      when(dal.get(Invoice.class, "inv-purchase")).thenReturn(invoice);
      when(invoice.getId()).thenReturn("inv-purchase");
      when(invoice.getCurrency()).thenReturn(currency);
      when(currency.getId()).thenReturn("usd-id");
      when(invoice.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");
      when(invoice.getClient()).thenReturn(client);
      when(client.getId()).thenReturn("client-1");
      when(invoice.getETGOCurrencyRate()).thenReturn(new BigDecimal("0.85"));
      when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("500.00"));

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement findPs = mock(PreparedStatement.class);
      ResultSet findRs = mock(ResultSet.class);
      when(findRs.next()).thenReturn(false);
      when(findPs.executeQuery()).thenReturn(findRs);

      PreparedStatement insertPs = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString()))
          .thenReturn(findPs)
          .thenReturn(insertPs);

      callAutoCreateOrUpdate("inv-purchase");

      ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
      verify(insertPs, times(2)).setBigDecimal(any(Integer.class), captor.capture());
      BigDecimal capturedDocRate = captor.getAllValues().get(0);
      BigDecimal capturedForeignAmount = captor.getAllValues().get(1);

      // docRate = BigDecimal.ONE.divide(0.85, 12, HALF_UP) = 1.176470588235
      assertEquals(0, new BigDecimal("1.176470588235").compareTo(capturedDocRate));
      // foreignAmount = 500.00 × 1.176470588235, HALF_UP to 2 decimals = 588.24
      assertEquals(0, new BigDecimal("588.24").compareTo(capturedForeignAmount));
    }
  }

  /**
   * Rounding-boundary case: rate = 3 produces a repeating decimal (1/3 = 0.333...), so the
   * scale-12 HALF_UP truncation of docRate and the subsequent scale-2 HALF_UP rounding of
   * foreignAmount both land on a non-obvious digit — a good canary for any future change to
   * the divide/multiply scale or rounding mode in the production method.
   */
  @Test
  public void autoCreateOrUpdate_repeatingDecimalRate_roundsHalfUpAtBothScales()
      throws Exception {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBContext obContext = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-rounding");
      when(obContext.getUser()).thenReturn(user);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      Currency currency = mock(Currency.class);
      Organization org = mock(Organization.class);
      Client client = mock(Client.class);
      when(dal.get(Invoice.class, "inv-rounding")).thenReturn(invoice);
      when(invoice.getId()).thenReturn("inv-rounding");
      when(invoice.getCurrency()).thenReturn(currency);
      when(currency.getId()).thenReturn("usd-id");
      when(invoice.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");
      when(invoice.getClient()).thenReturn(client);
      when(client.getId()).thenReturn("client-1");
      when(invoice.getETGOCurrencyRate()).thenReturn(new BigDecimal("3"));
      when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("10.00"));

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement findPs = mock(PreparedStatement.class);
      ResultSet findRs = mock(ResultSet.class);
      when(findRs.next()).thenReturn(false);
      when(findPs.executeQuery()).thenReturn(findRs);

      PreparedStatement insertPs = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString()))
          .thenReturn(findPs)
          .thenReturn(insertPs);

      callAutoCreateOrUpdate("inv-rounding");

      ArgumentCaptor<BigDecimal> captor = ArgumentCaptor.forClass(BigDecimal.class);
      verify(insertPs, times(2)).setBigDecimal(any(Integer.class), captor.capture());
      BigDecimal capturedDocRate = captor.getAllValues().get(0);
      BigDecimal capturedForeignAmount = captor.getAllValues().get(1);

      // docRate = BigDecimal.ONE.divide(3, 12, HALF_UP) = 0.333333333333
      assertEquals(0, new BigDecimal("0.333333333333").compareTo(capturedDocRate));
      // foreignAmount = 10.00 × 0.333333333333, HALF_UP to 2 decimals = 3.33
      assertEquals(0, new BigDecimal("3.33").compareTo(capturedForeignAmount));
    }
  }

  /**
   * Happy path — update branch: an existing row is found, so the UPDATE path runs
   * instead of INSERT.
   */
  @Test
  public void autoCreateOrUpdate_updateBranch_whenRecordExists() throws Exception {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBContext obContext = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-2");
      when(obContext.getUser()).thenReturn(user);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      Currency currency = mock(Currency.class);
      Organization org = mock(Organization.class);
      when(dal.get(Invoice.class, "inv-update")).thenReturn(invoice);
      when(invoice.getId()).thenReturn("inv-update");
      when(invoice.getCurrency()).thenReturn(currency);
      when(currency.getId()).thenReturn("usd-id");
      when(invoice.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");
      when(invoice.getETGOCurrencyRate()).thenReturn(new BigDecimal("2.0"));
      when(invoice.getGrandTotalAmount()).thenReturn(new BigDecimal("200.00"));

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement findPs = mock(PreparedStatement.class);
      ResultSet findRs = mock(ResultSet.class);
      when(findRs.next()).thenReturn(true); // existing row found
      when(findRs.getString(1)).thenReturn("existing-crd-id");
      when(findPs.executeQuery()).thenReturn(findRs);

      PreparedStatement updatePs = mock(PreparedStatement.class);

      when(conn.prepareStatement(anyString()))
          .thenReturn(findPs)
          .thenReturn(updatePs);

      callAutoCreateOrUpdate("inv-update");

      verify(updatePs).executeUpdate();
      verify(updatePs).setString(eq(4), eq("existing-crd-id"));
      // Only two prepareStatement calls: the SELECT and the UPDATE — no INSERT attempted.
      verify(conn, times(2)).prepareStatement(anyString());
    }
  }

  /**
   * Regression test for the bug fixed today (manual QA on a real invoice): before
   * {@code session.refresh(invoice)} was added, {@code getGrandTotalAmount()} could return
   * the stale pre-line-insert total because Hibernate's L1 cache returned an already-loaded
   * Invoice instance. This test proves refresh() happens BEFORE the total is read, using a
   * mock that returns a different (stale) value the first time and only the fresh value once
   * refresh() has been invoked.
   */
  @Test
  public void autoCreateOrUpdate_refreshesBeforeReadingGrandTotal() throws Exception {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<OBCurrencyUtils> curMock = Mockito.mockStatic(OBCurrencyUtils.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBContext obContext = mock(OBContext.class);
      org.openbravo.model.ad.access.User user = mock(org.openbravo.model.ad.access.User.class);
      when(user.getId()).thenReturn("user-3");
      when(obContext.getUser()).thenReturn(user);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      Invoice invoice = mock(Invoice.class);
      Currency currency = mock(Currency.class);
      Organization org = mock(Organization.class);
      Client client = mock(Client.class);
      when(dal.get(Invoice.class, "inv-refresh")).thenReturn(invoice);
      when(invoice.getId()).thenReturn("inv-refresh");
      when(invoice.getCurrency()).thenReturn(currency);
      when(currency.getId()).thenReturn("usd-id");
      when(invoice.getOrganization()).thenReturn(org);
      when(org.getId()).thenReturn("org-1");
      when(invoice.getClient()).thenReturn(client);
      when(client.getId()).thenReturn("client-1");
      when(invoice.getETGOCurrencyRate()).thenReturn(BigDecimal.ONE);

      curMock.when(() -> OBCurrencyUtils.getOrgCurrency("org-1")).thenReturn("eur-id");

      // Track whether refresh() has been called; grandTotalAmount reflects the PRE-refresh
      // (stale) value until refresh() runs, then the POST-refresh (correct) value.
      final boolean[] refreshed = {false};
      org.hibernate.Session session = mock(org.hibernate.Session.class);
      when(dal.getSession()).thenReturn(session);
      Mockito.doAnswer(invocation -> {
        refreshed[0] = true;
        return null;
      }).when(session).refresh(invoice);
      when(invoice.getGrandTotalAmount()).thenAnswer(invocation ->
          refreshed[0] ? new BigDecimal("500.00") : new BigDecimal("0.00"));

      Connection conn = mock(Connection.class);
      when(dal.getConnection()).thenReturn(conn);

      PreparedStatement findPs = mock(PreparedStatement.class);
      ResultSet findRs = mock(ResultSet.class);
      when(findRs.next()).thenReturn(false);
      when(findPs.executeQuery()).thenReturn(findRs);

      PreparedStatement insertPs = mock(PreparedStatement.class);
      when(conn.prepareStatement(anyString()))
          .thenReturn(findPs)
          .thenReturn(insertPs);

      callAutoCreateOrUpdate("inv-refresh");

      verify(session).refresh(invoice);
      // foreignAmount = grandTotal(500.00, POST-refresh) × docRate(1.0) = 500.00 — proves
      // the total was read AFTER refresh(), not the stale 0.00 pre-refresh value.
      ArgumentCaptor<BigDecimal> captor =
          ArgumentCaptor.forClass(BigDecimal.class);
      verify(insertPs, times(2)).setBigDecimal(any(Integer.class), captor.capture());
      BigDecimal capturedForeignAmount = captor.getAllValues().get(1);
      assertEquals(0, new BigDecimal("500.00").compareTo(capturedForeignAmount));
    }
  }

  @Test
  public void autoCreateOrUpdate_exceptionDuringUpsert_swallowed() {
    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-boom")).thenThrow(new RuntimeException("DB down"));

      // Must not throw.
      callAutoCreateOrUpdate("inv-boom");
    }
  }

  // ── ETP-4029: autoCreateOrUpdateConversionRateDocument(NeoContext) overload ──

  @Test
  public void autoCreateOrUpdateFromContext_getMethod_noop() {
    NeoContext ctx = NeoContext.builder().httpMethod("GET").recordId("inv-1").build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      callAutoCreateOrUpdate(ctx);

      Mockito.verifyNoInteractions(dal);
    }
  }

  @Test
  public void autoCreateOrUpdateFromContext_deleteMethod_noop() {
    NeoContext ctx = NeoContext.builder().httpMethod("DELETE").recordId("inv-1").build();

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);

      callAutoCreateOrUpdate(ctx);

      Mockito.verifyNoInteractions(dal);
    }
  }

  @Test
  public void autoCreateOrUpdateFromContext_patchWithRecordId_resolvesAndDelegates() {
    NeoContext ctx = NeoContext.builder().httpMethod("PATCH").recordId("inv-patch").build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      // invoice not found → no-op, but proves resolveInvoiceIdFromContext used recordId directly
      when(dal.get(Invoice.class, "inv-patch")).thenReturn(null);

      callAutoCreateOrUpdate(ctx);

      verify(dal).get(Invoice.class, "inv-patch");
    }
  }

  @Test
  public void autoCreateOrUpdateFromContext_postResolvesIdFromPreviousResult() throws Exception {
    JSONArray dataArray = new JSONArray().put(new JSONObject().put("id", "inv-from-post"));
    JSONObject response = new JSONObject().put("data", dataArray);
    JSONObject respBody = new JSONObject().put("response", response);
    NeoResponse prevResult = new NeoResponse(201, respBody);

    NeoContext ctx = NeoContext.builder()
        .httpMethod("POST").recordId(null).previousResult(prevResult).build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-from-post")).thenReturn(null);

      callAutoCreateOrUpdate(ctx);

      verify(dal).get(Invoice.class, "inv-from-post");
    }
  }

  @Test
  public void autoCreateOrUpdateFromContext_putResolvesViaRecordId() {
    NeoContext ctx = NeoContext.builder().httpMethod("PUT").recordId("inv-put").build();

    try (MockedStatic<OBContext> ctxMock = Mockito.mockStatic(OBContext.class);
         MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      ctxMock.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(i -> null);
      ctxMock.when(OBContext::restorePreviousMode).thenAnswer(i -> null);

      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(Invoice.class, "inv-put")).thenReturn(null);

      callAutoCreateOrUpdate(ctx);

      verify(dal).get(Invoice.class, "inv-put");
    }
  }
}
