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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
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
    JSONObject data = new JSONObject().put("id", "new-inv-from-post");
    JSONObject response = new JSONObject().put("data", data);
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
}
