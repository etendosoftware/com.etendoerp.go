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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Named;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.system.Language;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

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

  /** Stubs {@code NeoAccessHelper.hasObuiappProcessAccess} to grant access for the "not-posted-documents" process. */
  private MockedStatic<NeoAccessHelper> mockAccessGranted() {
    MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class);
    accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(
        NotPostedDocumentsHandler.NOT_POSTED_DOCUMENTS_PROCESS_ID)).thenReturn(true);
    return accessMock;
  }

  // ── handle — access control guard (ETP-4510 follow-up) ────────────────────────

  /**
   * When the current role does not have {@code hasObuiappProcessAccess} for the "Not Posted
   * Documents" OBUIAPP process, {@code handle} must deny with a 403 before doing anything else
   * — in particular, it must never reach {@code postingService}, since that would mean business
   * logic ran despite the caller lacking access.
   */
  @Test
  public void handleReturns403WhenAccessDenied() {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    DocumentPostingService service = mock(DocumentPostingService.class);
    handler.setPostingService(service);

    try (MockedStatic<NeoAccessHelper> accessMock = mockStatic(NeoAccessHelper.class)) {
      accessMock.when(() -> NeoAccessHelper.hasObuiappProcessAccess(
          NotPostedDocumentsHandler.NOT_POSTED_DOCUMENTS_PROCESS_ID)).thenReturn(false);

      NeoContext ctx = mock(NeoContext.class);
      // Deliberately not stubbing getEndpointType()/getFieldName(): the guard must short-circuit
      // before any of that is read.
      NeoResponse resp = handler.handle(ctx);

      assertNotNull(resp);
      assertEquals(403, resp.getHttpStatus());
      verifyNoInteractions(service);
    }
  }

  /**
   * ETP-4254: this spec is tab-less, so the MCP catalog rule would hide it as "handler-only"
   * unless the handler declares its {@code post} / {@code bulk-post} action surface. Losing the
   * declaration removes the spec from neo_discover AND from neo_action — a silent regression
   * with no other failing test, which is why it is asserted here.
   */
  @Test
  public void declaresItsActionSurface() {
    assertTrue("NotPostedDocumentsHandler serves post/bulk-post, so it must declare "
        + "servesActions() — otherwise ETP-4254's catalog rule hides the spec from agents",
        new NotPostedDocumentsHandler().servesActions());
  }

  @Test
  public void carriesNotPostedDocumentsNamedQualifier() {
    Named named = NotPostedDocumentsHandler.class.getAnnotation(Named.class);
    assertNotNull("NotPostedDocumentsHandler must be annotated @Named", named);
    assertEquals("not-posted-documents", named.value());
  }

  @Test
  public void handleReturnsNullForUnknownEndpointType() {
    try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted()) {
      NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
      NeoContext ctx = mock(NeoContext.class);
      when(ctx.getEndpointType()).thenReturn(null);

      assertNull(handler.handle(ctx));
    }
  }

  @Test
  public void handleActionReturnsNullForUnknownAction() {
    try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted()) {
      NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
      NeoContext ctx = mock(NeoContext.class);
      when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
      when(ctx.getFieldName()).thenReturn("unknown-action");

      assertNull(handler.handle(ctx));
    }
  }

  @Test
  public void handleSinglePostReturns200OnSuccess() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted()) {
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
  }

  @Test
  public void handleSinglePostReturns422OnFailure() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted()) {
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
      // ETP-4706: same double-encoding bug class as DocumentPostingService#handleAction — the
      // message must be a flat top-level field, not re-wrapped/escaped inside error.message.
      assertEquals("Posting failed", resp.getBody().getString("message"));
    }
  }

  /**
   * ETP-5175 pasada 1: the single post must forward the Invalid-Account identity
   * ({@code messageKeys} + {@code messageParams}) exactly like {@code
   * DocumentPostingService#handleAction}, so Documentos no contabilizados renders the same
   * localized sentence as the document windows.
   */
  @Test
  public void handleSinglePostForwardsMessageKeysAndParamsOnFailure() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted()) {
      NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
      DocumentPostingService service = mock(DocumentPostingService.class);
      handler.setPostingService(service);

      when(service.post("318", "REC-1")).thenReturn(invalidAccountResult());

      JSONObject body = new JSONObject();
      body.put("tableId", "318");
      body.put("recordId", "REC-1");

      NeoContext ctx = mock(NeoContext.class);
      when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
      when(ctx.getFieldName()).thenReturn("post");
      when(ctx.getRequestBody()).thenReturn(body);

      NeoResponse resp = handler.handle(ctx);

      assertEquals(422, resp.getHttpStatus());
      assertEquals("ETGO_InvalidAccountBpOnly", resp.getBody().getJSONArray("messageKeys").getString(1));
      assertEquals("Acme", resp.getBody().getJSONObject("messageParams").getString("bpName"));
    }
  }

  /**
   * ETP-5175 pasada 1: each bulk-post row carries the same identity as a single post (the SPA
   * shows only counts today, but a row result must not lose information the single post has).
   */
  @Test
  public void handleBulkPostForwardsMessageKeysAndParamsPerRow() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted()) {
      NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
      DocumentPostingService service = mock(DocumentPostingService.class);
      handler.setPostingService(service);

      when(service.post("318", "REC-1")).thenReturn(invalidAccountResult());

      JSONObject row = new JSONObject();
      row.put("tableId", "318");
      row.put("recordId", "REC-1");
      JSONObject body = new JSONObject();
      body.put("rows", new JSONArray().put(row));

      NeoContext ctx = mock(NeoContext.class);
      when(ctx.getEndpointType()).thenReturn(NeoEndpointType.ACTION);
      when(ctx.getFieldName()).thenReturn("bulk-post");
      when(ctx.getRequestBody()).thenReturn(body);

      NeoResponse resp = handler.handle(ctx);

      JSONObject rowResult = resp.getBody().getJSONArray("results").getJSONObject(0);
      assertEquals("InvalidAccount", rowResult.getJSONArray("messageKeys").getString(0));
      assertEquals("Acme", rowResult.getJSONObject("messageParams").getString("bpName"));
    }
  }

  /** A BP-only Invalid-Account failure with its identity. */
  private static DocumentPostingService.PostResult invalidAccountResult() {
    return new DocumentPostingService.PostResult(false, "Account could not be found. (Contact: Acme)",
        List.of("InvalidAccount", "ETGO_InvalidAccountBpOnly"), Map.of("bpName", "Acme"));
  }

  @Test
  public void handleBulkPostAggregatesResults() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted()) {
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
  }

  @Test
  public void handleReturns500WhenPostBodyIsMissingRequiredFields() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted()) {
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
  }

  @Test
  public void handleReturns500WhenBulkPostBodyIsMissingRows() throws Exception {
    try (MockedStatic<NeoAccessHelper> accessMock = mockAccessGranted()) {
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

  /**
   * The 5 document types globally excluded by product decision (ETP-4452) must resolve to their
   * real {@code tableId} via {@link NotPostedDocumentsHandler#DOCUMENT_TYPE_TO_TABLE_ID} (the
   * defensive fix) AND be dropped from the grid because their table is in
   * {@code AccountingDocumentTypeSupport.APRM_DISABLED_TABLE_IDS} (the exclusion, ETP-4948:
   * extracted out of this handler into a shared utility). Before the fix these labels were absent
   * from the map, so {@code tableId} resolved to {@code null} and the row was never dropped here.
   */
  @Test
  public void buildRowDropsGloballyExcludedBillOfMaterialsProductionRow() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Bill of Materials Production");

    assertNull(handler.buildRow(row));
  }

  @Test
  public void buildRowDropsGloballyExcludedDoubtfulDebtRow() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Doubtful Debt");

    assertNull(handler.buildRow(row));
  }

  @Test
  public void buildRowDropsGloballyExcludedLandedCostRow() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Landed Cost");

    assertNull(handler.buildRow(row));
  }

  @Test
  public void buildRowDropsGloballyExcludedLandedCostCostRow() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Landed Cost Cost");

    assertNull(handler.buildRow(row));
  }

  @Test
  public void buildRowDropsGloballyExcludedCostAdjustmentRow() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Cost Adjustment");

    assertNull(handler.buildRow(row));
  }

  /**
   * Defensive-fix regression guard: even without going through
   * {@code AccountingDocumentTypeSupport.APRM_DISABLED_TABLE_IDS}, {@code tableIdForLabel}
   * must resolve the real table id for these 5 labels — verified directly on the map so a future
   * exclusion-policy change does not silently regress the {@code tableId} mapping bug.
   */
  @Test
  public void documentTypeToTableIdMapsAllFiveGloballyExcludedLabels() {
    assertEquals("325", NotPostedDocumentsHandler.tableIdForLabel("Bill of Materials Production"));
    assertEquals("30721072789F410E9606D2235CB2A226",
        NotPostedDocumentsHandler.tableIdForLabel("Doubtful Debt"));
    assertEquals("082F967CDF7245EB9A150941F326C45C",
        NotPostedDocumentsHandler.tableIdForLabel("Landed Cost"));
    assertEquals("55A984C314FD4C4FB5E7C32DE36BB07B",
        NotPostedDocumentsHandler.tableIdForLabel("Landed Cost Cost"));
    assertEquals("D022B92163074E5E82449C8E0B5AFDF6",
        NotPostedDocumentsHandler.tableIdForLabel("Cost Adjustment"));
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

  /**
   * ETP-5075 regression guard — before this entry existed, every Matched Purchase Invoices
   * ("Relación albarán-factura") row in this grid resolved a null tableId despite table 472
   * (M_MatchInv) already having active accounting (so {@code refListDocumentTypes()} listed
   * "MI" in the filter dropdown, but the row itself could never be posted:
   * {@code postRow()} in {@code NotPostedDocumentsPage.jsx} short-circuits client-side with
   * "unknown tableId for Matched Invoice" whenever {@code tableId} is null).
   */
  @Test
  public void buildRowResolvesTableIdForMatchedInvoice() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Matched Invoice");
    row.put("documentId", "doc-9");

    JSONObject result = handler.buildRow(row);

    assertNotNull(result);
    assertEquals("472", result.get("tableId"));
  }

  /**
   * ETP-5445 regression guard — Internal Consumption rows had a filter code ("IC") but no
   * row-enrichment entry, so their tableId resolved to null and {@code postRow()} in
   * {@code NotPostedDocumentsPage.jsx} failed client-side. "Internal Consumption" is
   * bulk.posting's own label ({@code NoPostedConstans.INTERNAL_CONSUMPTION}); it must resolve to
   * table 800168 (M_Internal_Consumption) and the row must NOT be dropped by the global exclusion.
   */
  @Test
  public void testBuildRowResolvesTableIdForInternalConsumption() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Internal Consumption");
    row.put("documentId", "ic-9");

    JSONObject result = handler.buildRow(row);

    assertNotNull(result);
    assertEquals("800168", result.get("tableId"));
  }

  /** ETP-5445 — the map entry itself, pinned directly so a later refactor cannot drop it. */
  @Test
  public void testDocumentTypeToTableIdMapsInternalConsumption() {
    assertEquals("800168",
        NotPostedDocumentsHandler.tableIdForLabel("Internal Consumption"));
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
      assertEquals(6, statuses.length()); // N, E, C, i, p, NC (ETP-5591)
    }
  }

  /** ETP-5591 — "Cost Not Calculated" is selectable and reaches the datasource as its UUID. */
  @Test
  public void buildDsParamsTranslatesCostNotCalculatedKey() throws Exception {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class)) {
      mockOrgContext(ctxMock, "org-1");
      Map<String, String> params = new HashMap<>();
      params.put("accountingStatus", "NC");

      Map<String, String> result = new NotPostedDocumentsHandler().buildDsParams(params);

      JSONArray statuses = new JSONArray(result.get("accounting_status"));
      assertEquals(1, statuses.length());
      assertEquals("EF3E057A84CD4BE88A9EF57BE9598DA3", statuses.getString(0));
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

  /**
   * The 5 document types globally excluded by product decision (ETP-4452) must never appear in
   * the dropdown even when their table has an active accounting schema entry — the tradeoff of
   * hiding legitimate documents for tenants that actively use them (e.g. QA Testing, F&amp;B
   * International Group) was accepted by the product owner.
   */
  @Test
  public void refListDocumentTypesExcludesAllFiveGloballyExcludedTypesEvenWhenSchemaActive()
      throws Exception {
    try (MockedStatic<OBContext> ctxMock = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      mockAccountedTableIds(dal,
          "325",                                 // M_Production
          "30721072789F410E9606D2235CB2A226",    // FIN_Doubtful_Debt
          "082F967CDF7245EB9A150941F326C45C",    // M_LandedCost
          "55A984C314FD4C4FB5E7C32DE36BB07B",    // M_LC_Cost
          "D022B92163074E5E82449C8E0B5AFDF6");   // M_CostAdjustment

      Reference ref = mock(Reference.class);
      when(dal.get(eq(Reference.class), eq(NotPostedDocumentsHandler.DOCUMENT_TYPE_REF_ID)))
          .thenReturn(ref);
      java.util.List<org.openbravo.model.ad.domain.List> items = java.util.Arrays.asList(
          mockListItem("BMP", "Bill of Materials Production", true),
          mockListItem("DD", "Doubtful Debt", true),
          mockListItem("LC", "Landed Cost", true),
          mockListItem("LCC", "Landed Cost Cost", true),
          mockListItem("CA", "Cost Adjustment", true));
      when(ref.getADListList()).thenReturn(items);

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

  // ── ETP-5591 — documentTypeCode, accountingStatus, financialAccountId ─────────

  /** Every label the datasource emits must resolve to a table through its code. */
  @Test
  public void everyDatasourceLabelResolvesToATableThroughItsCode() {
    for (Map.Entry<String, String> e
        : NotPostedDocumentsHandler.DS_LABEL_TO_DOCUMENT_TYPE_CODE.entrySet()) {
      assertNotNull("no tableId for label " + e.getKey(),
          NotPostedDocumentsHandler.tableIdForLabel(e.getKey()));
    }
  }

  @Test
  public void tableIdForLabelReturnsNullForUnknownOrNullLabel() {
    assertNull(NotPostedDocumentsHandler.tableIdForLabel("Some Future Document Type"));
    assertNull(NotPostedDocumentsHandler.tableIdForLabel(null));
  }

  @Test
  public void buildRowEmitsDocumentTypeCodeAndNullStatusPlaceholder() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Return Material Receipt");
    row.put("documentId", "doc-1");

    JSONObject result = handler.buildRow(row);

    assertEquals("RMR", result.getString("documentTypeCode"));
    assertEquals("319", result.getString("tableId"));
    assertEquals(JSONObject.NULL, result.get("accountingStatus"));
    // The raw datasource label is kept untouched.
    assertEquals("Return Material Receipt", result.getString("documentType"));
  }

  @Test
  public void buildRowSetsNullDocumentTypeCodeForUnmappedLabel() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Some Future Document Type");
    row.put("documentId", "doc-9");

    assertEquals(JSONObject.NULL, handler.buildRow(row).get("documentTypeCode"));
  }

  /**
   * ETP-5591 regression guard — "T" was a filter option, but "Transaction" rows had no
   * label → table entry, so their tableId was null and the row "Post" failed client-side (the
   * same bug ETP-5075 and ETP-5445 fixed for other types).
   */
  @Test
  public void buildRowResolvesTableIdForTransaction() throws Exception {
    NotPostedDocumentsHandler handler = new NotPostedDocumentsHandler();
    Map<String, Object> row = new HashMap<>();
    row.put("documentType", "Transaction");
    row.put("documentId", "trx-1");

    JSONObject result = handler.buildRow(row);

    assertNotNull(result);
    assertEquals("4D8C3B3C31D1410DA046140C9F024D17", result.get("tableId"));
    assertEquals("T", result.get("documentTypeCode"));
  }

  /** Handler whose state loader is stubbed per table and records every call. */
  private static class StubStateHandler extends NotPostedDocumentsHandler {
    final Map<String, Map<String, AccountingState>> byTable = new HashMap<>();
    final Map<String, Set<String>> calls = new HashMap<>();
    String failingTable;

    @Override
    Map<String, AccountingState> loadAccountingStates(String tableId, Set<String> ids) {
      calls.put(tableId, ids);
      if (tableId.equals(failingTable)) {
        throw new IllegalStateException("boom");
      }
      return byTable.getOrDefault(tableId, Collections.emptyMap());
    }
  }

  private static JSONObject gridRow(String tableId, String documentId) throws Exception {
    JSONObject j = new JSONObject();
    j.put("tableId", tableId != null ? tableId : JSONObject.NULL);
    j.put("documentId", documentId);
    j.put("accountingStatus", JSONObject.NULL);
    return j;
  }

  @Test
  public void enrichWithAccountingStateMergesStatusAndAccountPerRow() throws Exception {
    StubStateHandler handler = new StubStateHandler();
    handler.byTable.put("318", Map.of(
        "inv-1", new NotPostedDocumentsHandler.AccountingState("E", null),
        "inv-2", new NotPostedDocumentsHandler.AccountingState("p", null)));
    handler.byTable.put("4D8C3B3C31D1410DA046140C9F024D17", Map.of(
        "trx-1", new NotPostedDocumentsHandler.AccountingState("N", "acc-1")));
    JSONObject inv1 = gridRow("318", "inv-1");
    JSONObject inv2 = gridRow("318", "inv-2");
    JSONObject trx = gridRow("4D8C3B3C31D1410DA046140C9F024D17", "trx-1");
    JSONObject unknown = gridRow(null, "x-1");

    handler.enrichWithAccountingState(List.of(inv1, inv2, trx, unknown));

    assertEquals("E", inv1.get("accountingStatus"));
    assertEquals("p", inv2.get("accountingStatus"));
    assertFalse(inv1.has("financialAccountId"));
    assertEquals("N", trx.get("accountingStatus"));
    assertEquals("acc-1", trx.get("financialAccountId"));
    // A row without tableId is never queried and keeps its null status.
    assertEquals(JSONObject.NULL, unknown.get("accountingStatus"));
    // One lookup per distinct table, carrying all of that table's ids.
    assertEquals(2, handler.calls.size());
    assertEquals(Set.of("inv-1", "inv-2"), handler.calls.get("318"));
  }

  @Test
  public void enrichWithAccountingStateNeverFailsTheGridWhenATableCannotBeRead() throws Exception {
    StubStateHandler handler = new StubStateHandler();
    handler.failingTable = "318";
    handler.byTable.put("319", Map.of(
        "io-1", new NotPostedDocumentsHandler.AccountingState("i", null)));
    JSONObject inv = gridRow("318", "inv-1");
    JSONObject io = gridRow("319", "io-1");

    handler.enrichWithAccountingState(List.of(inv, io));

    assertEquals(JSONObject.NULL, inv.get("accountingStatus"));
    assertEquals("i", io.get("accountingStatus"));
  }

  @Test
  public void enrichWithAccountingStateLeavesRowsMissingFromTheLookupUntouched() throws Exception {
    StubStateHandler handler = new StubStateHandler();
    JSONObject inv = gridRow("318", "gone-1");

    handler.enrichWithAccountingState(List.of(inv));

    assertEquals(JSONObject.NULL, inv.get("accountingStatus"));
  }

  @Test
  public void loadAccountingStatesReturnsEmptyForTableWithoutStatusColumn() {
    Entity entity = mock(Entity.class);
    when(entity.hasProperty(NotPostedDocumentsHandler.ACCOUNTING_STATUS_PROPERTY)).thenReturn(false);
    ModelProvider provider = mock(ModelProvider.class);
    when(provider.getEntityByTableId("999")).thenReturn(entity);
    try (MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      mp.when(ModelProvider::getInstance).thenReturn(provider);

      assertTrue(new NotPostedDocumentsHandler()
          .loadAccountingStates("999", Set.of("a")).isEmpty());
      obDalMock.verifyNoInteractions();
    }
  }

  @Test
  public void loadAccountingStatesReturnsEmptyForUnknownTable() {
    ModelProvider provider = mock(ModelProvider.class);
    try (MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class)) {
      mp.when(ModelProvider::getInstance).thenReturn(provider);

      assertTrue(new NotPostedDocumentsHandler()
          .loadAccountingStates("nope", Set.of("a")).isEmpty());
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, NotPostedDocumentsHandler.AccountingState> runLoader(String entityName,
      List<Object[]> dbRows, String[] capturedHql) {
    Entity entity = mock(Entity.class);
    when(entity.hasProperty(NotPostedDocumentsHandler.ACCOUNTING_STATUS_PROPERTY)).thenReturn(true);
    when(entity.getName()).thenReturn(entityName);
    ModelProvider provider = mock(ModelProvider.class);
    when(provider.getEntityByTableId("t1")).thenReturn(entity);
    Query<Object[]> query = mock(Query.class);
    when(query.setParameterList(eq("ids"), anyCollection())).thenReturn(query);
    when(query.list()).thenReturn(dbRows);
    Session session = mock(Session.class);
    when(session.createQuery(anyString(), eq(Object[].class))).thenAnswer(inv -> {
      capturedHql[0] = inv.getArgument(0);
      return query;
    });
    OBDal dal = mock(OBDal.class);
    when(dal.getSession()).thenReturn(session);
    try (MockedStatic<ModelProvider> mp = mockStatic(ModelProvider.class);
         MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      mp.when(ModelProvider::getInstance).thenReturn(provider);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      return new NotPostedDocumentsHandler().loadAccountingStates("t1", Set.of("d1", "d2"));
    }
  }

  @Test
  public void loadAccountingStatesReadsTheBulkPostingStatusColumn() {
    String[] hql = new String[1];
    Map<String, NotPostedDocumentsHandler.AccountingState> states = runLoader("Invoice",
        List.of(new Object[] { "d1", "E" }, new Object[] { "d2", "N" }), hql);

    assertEquals("select e.id, e.etblkpAccountingstatus from Invoice e where e.id in (:ids)",
        hql[0]);
    assertEquals("E", states.get("d1").status());
    assertNull(states.get("d1").financialAccountId());
    assertEquals("N", states.get("d2").status());
  }

  @Test
  public void loadAccountingStatesAlsoReadsTheAccountOfATransaction() {
    String[] hql = new String[1];
    Map<String, NotPostedDocumentsHandler.AccountingState> states = runLoader(
        "FIN_Finacc_Transaction", List.<Object[]>of(new Object[] { "d1", "p", "acc-9" }), hql);

    assertTrue(hql[0].contains(", e.account.id from FIN_Finacc_Transaction e"));
    assertEquals("p", states.get("d1").status());
    assertEquals("acc-9", states.get("d1").financialAccountId());
  }
}
