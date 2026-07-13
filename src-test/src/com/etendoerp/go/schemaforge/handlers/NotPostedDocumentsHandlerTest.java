/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link NotPostedDocumentsHandler}.
 *
 * <p>Covers the ACTION dispatch path, the annotation contract, and — via the package-private
 * seams {@code buildRow}, {@code buildDsParams} and {@code refListDocumentTypes} — the CRUD-side
 * logic that does not require a live {@link com.etendoerp.bulk.posting.datasource.NoPostedDocumentDS}:
 * APRM row filtering, accounting-status key-to-UUID translation, and the dynamic
 * {@code c_acctschema_table}-driven document-type filter. The grid fetch itself (which delegates
 * to {@code NoPostedDocumentDS.getData}) still requires a live OBDal session and is excluded.
 * The {@code setPostingService(...)} package-private seam allows injection of a mock
 * {@link DocumentPostingService} so post / bulk-post paths can be exercised without a database.</p>
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class NotPostedDocumentsHandlerTest {

  @Test
  public void carriesNotPostedDocumentsNamedQualifier() {
    Named named = NotPostedDocumentsHandler.class.getAnnotation(Named.class);
    assertNotNull("NotPostedDocumentsHandler must be annotated @Named", named);
    assertEquals("not-posted-documents", named.value());
  }

  @Test
  public void handleReturnsNullForUnknownEndpointType() {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(null);

    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleActionReturnsNullForUnknownAction() {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("unknown-action");

    assertNull(handler.handle(ctx));
  }

  @Test
  public void handleSinglePostReturns200OnSuccess() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    DocumentPostingService service = mock(DocumentPostingService.class);
    handler.setPostingService(service);

    when(service.post("318", "REC-1"))
        .thenReturn(new DocumentPostingService.PostResult(true, "posted"));

    JSONObject body = new JSONObject();
    body.put("tableId", "318");
    body.put("recordId", "REC-1");

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("post");
    when(ctx.getRequestBody()).thenReturn(body);

    NeoResponse resp = handler.handle(ctx);

    assertNotNull(resp);
    assertEquals(200, resp.getHttpStatus());
  }

  @Test
  public void handleSinglePostReturns422OnFailure() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    DocumentPostingService service = mock(DocumentPostingService.class);
    handler.setPostingService(service);

    when(service.post("318", "REC-1"))
        .thenReturn(new DocumentPostingService.PostResult(false, "Posting failed"));

    JSONObject body = new JSONObject();
    body.put("tableId", "318");
    body.put("recordId", "REC-1");

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("post");
    when(ctx.getRequestBody()).thenReturn(body);

    NeoResponse resp = handler.handle(ctx);

    assertNotNull(resp);
    assertEquals(422, resp.getHttpStatus());
  }

  @Test
  public void handleBulkPostAggregatesResults() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    DocumentPostingService service = mock(DocumentPostingService.class);
    handler.setPostingService(service);

    when(service.post("318", "REC-1"))
        .thenReturn(new DocumentPostingService.PostResult(true, "ok"));
    when(service.post("319", "REC-2"))
        .thenReturn(new DocumentPostingService.PostResult(false, "err"));

    JSONObject row1 = new JSONObject();
    row1.put("tableId", "318");
    row1.put("recordId", "REC-1");
    JSONObject row2 = new JSONObject();
    row2.put("tableId", "319");
    row2.put("recordId", "REC-2");
    JSONArray rows = new JSONArray();
    rows.put(row1);
    rows.put(row2);
    JSONObject body = new JSONObject();
    body.put("rows", rows);

    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("bulk-post");
    when(ctx.getRequestBody()).thenReturn(body);

    NeoResponse resp = handler.handle(ctx);

    assertNotNull(resp);
    assertEquals(200, resp.getHttpStatus());
    assertEquals(1, resp.getBody().getInt("ok"));
    assertEquals(2, resp.getBody().getInt("total"));
  }

  @Test
  public void handleReturns500WhenPostBodyIsMissingRequiredFields() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    DocumentPostingService service = mock(DocumentPostingService.class);
    handler.setPostingService(service);

    // Missing "tableId" and "recordId" → getString() throws JSONException → caught → 500
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("post");
    when(ctx.getRequestBody()).thenReturn(new JSONObject());

    NeoResponse resp = handler.handle(ctx);

    assertNotNull(resp);
    assertEquals(500, resp.getHttpStatus());
  }

  @Test
  public void handleReturns500WhenBulkPostBodyIsMissingRows() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    DocumentPostingService service = mock(DocumentPostingService.class);
    handler.setPostingService(service);

    // Missing "rows" key → getJSONArray() throws JSONException → caught → 500
    NeoContext ctx = mock(NeoContext.class);
    when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
    when(ctx.getFieldName()).thenReturn("bulk-post");
    when(ctx.getRequestBody()).thenReturn(new JSONObject());

    NeoResponse resp = handler.handle(ctx);

    assertNotNull(resp);
    assertEquals(500, resp.getHttpStatus());
  }

  // ── buildRow — APRM filtering + tableId enrichment ────────────────────────────

  @Test
  public void buildRowEnrichesKnownDocumentTypeWithTableId() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Sales Invoice");
    row.put("documentId", "doc-1");

    JSONObject result = handler.buildRow(row);

    assertNotNull(result);
    assertEquals("318", result.getString("tableId"));
    assertEquals("doc-1", result.getString("documentId"));
  }

  /**
   * A freshly-created payment can still be {@code posted='N'} before the APRM background
   * process runs, so it can reach this method — but direct bulk-posting on FIN_Payment always
   * fails, so the row must be dropped rather than reach the frontend.
   */
  @Test
  public void buildRowDropsAprmManagedPaymentInRow() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Payment In");
    row.put("documentId", "pay-1");

    assertNull(handler.buildRow(row));
  }

  @Test
  public void buildRowDropsAprmManagedPaymentOutRow() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Payment Out");

    assertNull(handler.buildRow(row));
  }

  @Test
  public void buildRowDropsAprmManagedBankStatementRow() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Bank Statement");

    assertNull(handler.buildRow(row));
  }

  @Test
  public void buildRowDropsAprmManagedReconciliationRow() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Reconciliation");

    assertNull(handler.buildRow(row));
  }

  @Test
  public void buildRowSetsNullTableIdForUnmappedDocumentType() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Some Future Document Type");
    row.put("documentId", "doc-9");

    JSONObject result = handler.buildRow(row);

    assertNotNull(result);
    assertEquals(JSONObject.NULL, result.get("tableId"));
  }

  @Test
  public void buildRowSetsNullTableIdWhenDocumentTypeIsMissing() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentId", "doc-9");

    JSONObject result = handler.buildRow(row);

    assertNotNull(result);
    assertEquals(JSONObject.NULL, result.get("tableId"));
  }

  // ── buildDsParams — accounting-status key → UUID translation ─────────────────

  private OBContext mockOrgContext(MockedStatic<OBContext> ctxMock, String orgId) {
    OBContext obContext = mock(OBContext.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    when(obContext.getCurrentOrganization()).thenReturn(org);
    ctxMock.when(OBContext::getOBContext).thenReturn(obContext);
    return obContext;
  }

  /**
   * When no accounting-status filter is applied (initial page load), the handler must default
   * to the curated key set — otherwise {@code NoPostedDocumentDS.searchAllDocuments} short-
   * circuits to zero results on an empty status list.
   */
  @Test
  public void buildDsParamsDefaultsToCuratedStatusKeysWhenFilterIsEmpty() throws Exception {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      mockOrgContext(ctxMock, "org-1");

      NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
      Map<String, String> result = handler.buildDsParams(new HashMap<>());

      assertEquals("org-1", result.get("_org"));
      JSONArray statuses = new JSONArray(result.get("accounting_status"));
      assertEquals(5, statuses.length()); // N, E, C, i, p
    }
  }

  @Test
  public void buildDsParamsTranslatesExplicitStatusKeyToRefListUuid() throws Exception {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      mockOrgContext(ctxMock, "org-1");

      Map<String, String> params = new HashMap<>();
      params.put("accountingStatus", "N");

      NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
      Map<String, String> result = handler.buildDsParams(params);

      JSONArray statuses = new JSONArray(result.get("accounting_status"));
      assertEquals(1, statuses.length());
      assertEquals("D16B6411F4CB4708AE05E7F6E109920E", statuses.getString(0));
    }
  }

  @Test
  public void buildDsParamsOmitsAccountingStatusKeyWhenNoKeysResolve() throws Exception {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      mockOrgContext(ctxMock, "org-1");

      Map<String, String> params = new HashMap<>();
      params.put("accountingStatus", "not-a-real-key");

      NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
      Map<String, String> result = handler.buildDsParams(params);

      assertNull(result.get("accounting_status"));
    }
  }

  @Test
  public void buildDsParamsPassesThroughDocumentAndDateFilters() throws Exception {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      mockOrgContext(ctxMock, "org-1");

      Map<String, String> params = new HashMap<>();
      params.put("document", "SI");
      params.put("dateFrom", "2026-01-01");
      params.put("dateTo", "2026-01-31");

      NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
      Map<String, String> result = handler.buildDsParams(params);

      assertEquals("SI", result.get("document"));
      assertEquals("2026-01-01", result.get("DateFrom"));
      assertEquals("2026-01-31", result.get("DateTo"));
    }
  }

  // ── refListDocumentTypes — dynamic c_acctschema_table filter ──────────────────

  private org.openbravo.model.ad.domain.List mockListItem(String searchKey, String name, boolean active) {
    org.openbravo.model.ad.domain.List item = mock(org.openbravo.model.ad.domain.List.class);
    when(item.getSearchKey()).thenReturn(searchKey);
    when(item.getName()).thenReturn(name);
    when(item.isActive()).thenReturn(active);
    when(item.getADListTrlList()).thenReturn(Collections.emptyList());
    return item;
  }

  @SuppressWarnings("unchecked")
  private void mockAccountedTableIds(OBDal dal, Object... tableIds) {
    Session session = mock(Session.class);
    NativeQuery<Object> query = mock(NativeQuery.class);
    when(dal.getSession()).thenReturn(session);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.list()).thenReturn(java.util.Arrays.asList(tableIds));
  }

  /**
   * A document type whose backing table has an active {@code c_acctschema_table} entry must
   * appear in the dropdown — this is the core of the dynamic (no-code-change) filter.
   */
  @Test
  public void refListDocumentTypesIncludesTypeWithActiveAccountingSchema() throws Exception {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      mockAccountedTableIds(dal, "318"); // C_Invoice

      Reference ref = mock(Reference.class);
      when(dal.get(eq(Reference.class), eq(NotPostedDocumentsHandler.DOCUMENT_TYPE_REF_ID)))
          .thenReturn(ref);
      org.openbravo.model.ad.domain.List salesInvoice = mockListItem("SI", "Sales Invoice", true);
      when(ref.getADListList()).thenReturn(Collections.singletonList(salesInvoice));

      OBContext obContext = mock(OBContext.class);
      Language language = mock(Language.class);
      when(language.getLanguage()).thenReturn("en_US");
      when(obContext.getLanguage()).thenReturn(language);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);

      JSONArray result = new NotPostedDocumentsHandler().refListDocumentTypes();

      assertEquals(1, result.length());
      assertEquals("SI", result.getJSONObject(0).getString("value"));
      assertEquals("Sales Invoice", result.getJSONObject(0).getString("label"));
    }
  }

  /**
   * A document type not present in {@code c_acctschema_table} (no accounting configured) must
   * never appear, even though it is active and mapped to a known table — e.g. Internal
   * Consumption before that module registers its accounting schema entry.
   */
  @Test
  public void refListDocumentTypesExcludesTypeWithoutAccountingSchemaEntry() throws Exception {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      mockAccountedTableIds(dal); // nothing active

      Reference ref = mock(Reference.class);
      when(dal.get(eq(Reference.class), eq(NotPostedDocumentsHandler.DOCUMENT_TYPE_REF_ID)))
          .thenReturn(ref);
      org.openbravo.model.ad.domain.List internalConsumption =
          mockListItem("IC", "Internal Consumption", true);
      when(ref.getADListList()).thenReturn(Collections.singletonList(internalConsumption));

      OBContext obContext = mock(OBContext.class);
      Language language = mock(Language.class);
      when(language.getLanguage()).thenReturn("en_US");
      when(obContext.getLanguage()).thenReturn(language);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);

      JSONArray result = new NotPostedDocumentsHandler().refListDocumentTypes();

      assertEquals(0, result.length());
    }
  }

  /**
   * APRM-managed types (Payment In/Out, Bank Statement, Reconciliation) must stay excluded even
   * when their table has an active accounting schema entry — APRM structurally disables direct
   * bulk-posting on them.
   */
  @Test
  public void refListDocumentTypesExcludesAprmDisabledTypeEvenWhenSchemaActive() throws Exception {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      mockAccountedTableIds(dal, "D1A97202E832470285C9B1EB026D54E2"); // FIN_Payment, active

      Reference ref = mock(Reference.class);
      when(dal.get(eq(Reference.class), eq(NotPostedDocumentsHandler.DOCUMENT_TYPE_REF_ID)))
          .thenReturn(ref);
      org.openbravo.model.ad.domain.List paymentIn = mockListItem("PIN", "Payment In", true);
      when(ref.getADListList()).thenReturn(Collections.singletonList(paymentIn));

      OBContext obContext = mock(OBContext.class);
      Language language = mock(Language.class);
      when(language.getLanguage()).thenReturn("en_US");
      when(obContext.getLanguage()).thenReturn(language);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);

      JSONArray result = new NotPostedDocumentsHandler().refListDocumentTypes();

      assertEquals(0, result.length());
    }
  }

  @Test
  public void refListDocumentTypesExcludesInactiveListItem() throws Exception {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      mockAccountedTableIds(dal, "318"); // C_Invoice, active

      Reference ref = mock(Reference.class);
      when(dal.get(eq(Reference.class), eq(NotPostedDocumentsHandler.DOCUMENT_TYPE_REF_ID)))
          .thenReturn(ref);
      org.openbravo.model.ad.domain.List inactiveSalesInvoice =
          mockListItem("SI", "Sales Invoice", false);
      when(ref.getADListList()).thenReturn(Collections.singletonList(inactiveSalesInvoice));

      OBContext obContext = mock(OBContext.class);
      Language language = mock(Language.class);
      when(language.getLanguage()).thenReturn("en_US");
      when(obContext.getLanguage()).thenReturn(language);
      ctxMock.when(OBContext::getOBContext).thenReturn(obContext);

      JSONArray result = new NotPostedDocumentsHandler().refListDocumentTypes();

      assertEquals(0, result.length());
    }
  }

  @Test
  public void refListDocumentTypesReturnsEmptyWhenReferenceNotFound() throws Exception {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      mockAccountedTableIds(dal, "318");
      when(dal.get(eq(Reference.class), eq(NotPostedDocumentsHandler.DOCUMENT_TYPE_REF_ID)))
          .thenReturn(null);

      JSONArray result = new NotPostedDocumentsHandler().refListDocumentTypes();

      assertEquals(0, result.length());
    }
  }
}
