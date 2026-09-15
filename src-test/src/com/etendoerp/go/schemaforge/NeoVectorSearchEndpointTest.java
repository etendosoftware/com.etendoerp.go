package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.model.ad.datamodel.Table;
import com.etendoerp.db.extended.data.VectorSource;
import com.etendoerp.db.extended.data.VectorSearchTarget;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import org.openbravo.model.ad.ui.Window;
import java.util.concurrent.atomic.AtomicBoolean;

/** Unit coverage for the global vector-search request contract. */
public class NeoVectorSearchEndpointTest {
  @Test
  public void sharedWindowMetadataAllowsAuthorizedCallerAfterRestoringMode() throws Exception {
    assertWindowMetadataAccess(true, false);
  }

  @Test
  public void sharedWindowMetadataDoesNotBypassCallerPermissions() throws Exception {
    assertWindowMetadataAccess(false, false);
  }

  @Test
  public void missingWindowMetadataDeniesAccess() throws Exception {
    assertWindowMetadataAccess(true, true);
  }

  @SuppressWarnings("unchecked")
  private void assertWindowMetadataAccess(boolean allowed, boolean missing) throws Exception {
    OBDal dal = mock(OBDal.class);
    OBContext context = mock(OBContext.class);
    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    SFEntity entity = mock(SFEntity.class);
    SFSpec spec = mock(SFSpec.class);
    Window window = mock(Window.class);
    when(dal.createCriteria(SFEntity.class)).thenReturn(criteria);
    when(context.getReadableClients()).thenReturn(new String[] {"caller-client", "0"});
    when(entity.getETGOSFSpec()).thenReturn(spec);
    when(spec.getADWindow()).thenReturn(window);
    when(window.getId()).thenReturn("invoice-window");
    when(criteria.list()).thenReturn(missing ? Collections.emptyList() : Collections.singletonList(entity));
    AtomicBoolean admin = new AtomicBoolean(false);
    try (MockedStatic<OBDal> dals = mockStatic(OBDal.class);
        MockedStatic<OBContext> contexts = mockStatic(OBContext.class);
        MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class)) {
      dals.when(OBDal::getInstance).thenReturn(dal);
      contexts.when(OBContext::getOBContext).thenReturn(context);
      contexts.when(() -> OBContext.setAdminMode(true)).thenAnswer(call -> { admin.set(true); return null; });
      contexts.when(OBContext::restorePreviousMode).thenAnswer(call -> { admin.set(false); return null; });
      access.when(() -> NeoAccessHelper.hasWindowAccess("invoice-window", "GET")).thenAnswer(call -> {
        assertEquals("Window permissions must run as the caller", false, admin.get());
        return allowed;
      });
      Method method = Class.forName(NeoVectorSearchEndpoint.class.getName() + "$TargetEntityAuthorizer")
          .getDeclaredMethod("hasAuthorizedSchemaForgeWindow", String.class);
      method.setAccessible(true);
      assertEquals(allowed && !missing, method.invoke(null, "invoice-table"));
      contexts.verify(OBContext::restorePreviousMode);
      if (missing) access.verifyNoInteractions();
      else access.verify(() -> NeoAccessHelper.hasWindowAccess("invoice-window", "GET"));
    }
  }

  @Test
  public void sourceMetadataUsesDalAndRestoresCallerMode() throws Exception {
    assertSourceMetadataLookup(false, false);
  }

  @Test
  public void missingSourceRestoresCallerMode() throws Exception {
    assertSourceMetadataLookup(true, false);
  }

  @Test
  public void failedMetadataReadRestoresCallerMode() throws Exception {
    assertSourceMetadataLookup(false, true);
  }

  @SuppressWarnings("unchecked")
  private void assertSourceMetadataLookup(boolean missing, boolean failed) throws Exception {
    OBDal dal = mock(OBDal.class);
    OBCriteria<VectorSource> criteria = mock(OBCriteria.class);
    VectorSource source = mock(VectorSource.class);
    Table table = mock(Table.class);
    when(dal.createCriteria(VectorSource.class)).thenReturn(criteria);
    when(source.getTable()).thenReturn(table);
    when(table.getId()).thenReturn("invoice-table");
    when(criteria.list()).thenReturn(missing ? Collections.emptyList() : Collections.singletonList(source));
    if (failed) doThrow(new IllegalStateException("metadata unavailable")).when(criteria).list();
    try (MockedStatic<OBDal> dals = mockStatic(OBDal.class);
        MockedStatic<OBContext> contexts = mockStatic(OBContext.class)) {
      dals.when(OBDal::getInstance).thenReturn(dal);
      Method method = Class.forName(NeoVectorSearchEndpoint.class.getName() + "$SourceEntityAuthorizer")
          .getDeclaredMethod("findSourceTableId", String.class);
      method.setAccessible(true);
      try {
        Object result = method.invoke(null, "go.invoice");
        if (failed) throw new AssertionError("Expected metadata read failure");
        assertEquals(missing ? null : "invoice-table", result);
      } catch (InvocationTargetException ex) {
        if (!failed) throw ex;
        assertEquals(IllegalStateException.class, ex.getCause().getClass());
      }
      verify(criteria).list();
      contexts.verify(() -> OBContext.setAdminMode(true));
      contexts.verify(OBContext::restorePreviousMode);
    }
  }

  private static HttpServletRequest requestWith(String query, String namespaces, String topK) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("query")).thenReturn(query);
    when(request.getParameter("namespaces")).thenReturn(namespaces);
    when(request.getParameter("topK")).thenReturn(topK);
    return request;
  }

  @Test
  public void missingQueryReturnsBadRequestWithoutSearching() {
    NeoVectorSearchEndpoint endpoint = new NeoVectorSearchEndpoint((namespaces, query, topK, filter, minScore, maxScore) -> {
      throw new AssertionError("search must not be called");
    });

    assertEquals(HttpServletResponse.SC_BAD_REQUEST,
        endpoint.handle(requestWith(null, "products", null)).getHttpStatus());
  }

  @Test
  public void invalidTopKReturnsBadRequestWithoutSearching() {
    NeoVectorSearchEndpoint endpoint = new NeoVectorSearchEndpoint((namespaces, query, topK, filter, minScore, maxScore) -> {
      throw new AssertionError("search must not be called");
    });

    assertEquals(HttpServletResponse.SC_BAD_REQUEST,
        endpoint.handle(requestWith("paper", "products", "51")).getHttpStatus());
  }

  @Test
  public void unauthorizedNamespaceReturnsForbiddenWithoutSearching() {
    NeoVectorSearchEndpoint endpoint = new NeoVectorSearchEndpoint(
        (namespaces, query, topK, filter, minScore, maxScore) -> { throw new AssertionError("search must not be called"); },
        namespaces -> false);

    assertEquals(HttpServletResponse.SC_FORBIDDEN,
        endpoint.handle(requestWith("paper", "products", null)).getHttpStatus());
  }

  @Test
  public void unauthorizedSalesTargetReturnsForbiddenWithoutSearching() {
    NeoVectorSearchEndpoint endpoint = endpointWithTargetAccess(false);

    assertEquals(HttpServletResponse.SC_FORBIDDEN,
        endpoint.handle("quotation", null, "sales-quotation", null, null, null, null)
            .getHttpStatus());
  }

  @Test
  public void unauthorizedPurchaseTargetReturnsForbiddenWithoutSearching() {
    NeoVectorSearchEndpoint endpoint = endpointWithTargetAccess(false);

    assertEquals(HttpServletResponse.SC_FORBIDDEN,
        endpoint.handle("purchase order", null, "purchase-order", null, null, null, null)
            .getHttpStatus());
  }

  @Test
  public void authorizedSalesAndPurchaseTargetsReachSearchGateway() {
    NeoVectorSearchEndpoint endpoint = endpointWithTargetAccess(true);

    assertEquals(HttpServletResponse.SC_OK,
        endpoint.handle("quotation", null, "sales-quotation", null, null, null, null)
            .getHttpStatus());
    assertEquals(HttpServletResponse.SC_OK,
        endpoint.handle("purchase order", null, "purchase-order", null, null, null, null)
            .getHttpStatus());
  }

  private NeoVectorSearchEndpoint endpointWithTargetAccess(boolean authorized) {
    return new NeoVectorSearchEndpoint(
        (namespaces, query, topK, filter, minScore, maxScore) -> "{\"items\":[]}",
        namespaces -> true,
        (targets, query, topK, minScore, maxScore) -> "{\"items\":[]}",
        targets -> authorized);
  }

  private NeoVectorSearchEndpoint endpointWithCatalog(List<String> knownKeys, boolean authorized) {
    return new NeoVectorSearchEndpoint(
        (namespaces, query, topK, filter, minScore, maxScore) -> { throw new AssertionError("namespace search must not be called"); },
        namespaces -> true,
        (targets, query, topK, minScore, maxScore) -> "{\"items\":[]}",
        targets -> authorized,
        () -> java.util.Optional.ofNullable(knownKeys));
  }

  // ── IMP-41: unknown-vs-forbidden target refusal ────────────────────────

  /**
   * The regression test for IMP-41 itself: an unknown target key must come back as 422
   * "unknown_vector_target" with the offending key AND the available ones, not as the generic
   * 403 "Access denied" that made a misspelling indistinguishable from a real permission denial.
   */
  @Test
  public void unknownTargetReturns422NotForbidden() throws Exception {
    NeoVectorSearchEndpoint endpoint =
        endpointWithCatalog(Collections.singletonList("sales-quotation"), true);

    NeoResponse response =
        endpoint.handle("quotation", null, "not-a-real-target", null, null, null, null);

    assertEquals(422, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("unknown_vector_target", error.getString("code"));
    JSONArray unknownTargets = error.getJSONArray("unknownTargets");
    assertEquals(1, unknownTargets.length());
    assertEquals("not-a-real-target", unknownTargets.getString(0));
    JSONArray available = error.getJSONArray("available");
    assertEquals(1, available.length());
    assertEquals("sales-quotation", available.getString(0));
    // Every error response must also carry a top-level message (NeoResponse convention).
    assertTrue(response.getBody().has("message"));
  }

  /** A known key that the role cannot read must still be refused with 403, not 422. */
  @Test
  public void knownButUnauthorizedTargetStillReturnsForbidden() {
    NeoVectorSearchEndpoint endpoint =
        endpointWithCatalog(Collections.singletonList("sales-quotation"), false);

    NeoResponse response =
        endpoint.handle("quotation", null, "sales-quotation", null, null, null, null);

    assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getHttpStatus());
  }

  /** A known and authorized target still reaches the gateway (the happy path is unaffected). */
  @Test
  public void knownAndAuthorizedTargetReachesGateway() {
    NeoVectorSearchEndpoint endpoint =
        endpointWithCatalog(Collections.singletonList("sales-quotation"), true);

    NeoResponse response =
        endpoint.handle("quotation", null, "sales-quotation", null, null, null, null);

    assertEquals(HttpServletResponse.SC_OK, response.getHttpStatus());
  }

  /** No target search gateway wired at all (DB Extended not installed) must return 503, not 403. */
  @Test
  public void missingTargetGatewayReturnsServiceUnavailable() {
    NeoVectorSearchEndpoint endpoint = new NeoVectorSearchEndpoint(
        (namespaces, query, topK, filter, minScore, maxScore) -> { throw new AssertionError("must not be called"); },
        namespaces -> true);

    NeoResponse response = endpoint.handle("quotation", null, "sales-quotation", null, null, null, null);

    assertEquals(503, response.getHttpStatus());
    assertFalse("A 503 for a missing gateway must not read as a permission denial",
        response.getBody().toString().contains("Access denied"));
  }

  /**
   * A {@code null} catalog disables the unknown-target check entirely and restores the pre-IMP-41
   * behaviour where the authorizer alone decides — this is what protects every pre-existing
   * 4-arg-constructor test above from becoming an unexpected 422.
   */
  @Test
  public void nullCatalogPreservesPreFixAuthorizerOnlyPath() {
    NeoVectorSearchEndpoint denying = endpointWithTargetAccess(false);
    NeoVectorSearchEndpoint allowing = endpointWithTargetAccess(true);

    assertEquals(HttpServletResponse.SC_FORBIDDEN,
        denying.handle("quotation", null, "totally-made-up-key", null, null, null, null)
            .getHttpStatus());
    assertEquals(HttpServletResponse.SC_OK,
        allowing.handle("quotation", null, "totally-made-up-key", null, null, null, null)
            .getHttpStatus());
  }

  /** An empty catalogue means nothing is configured: 422 with an empty {@code available} list. */
  @Test
  public void emptyCatalogReturns422WithEmptyAvailableList() throws Exception {
    NeoVectorSearchEndpoint endpoint = endpointWithCatalog(Collections.emptyList(), true);

    NeoResponse response = endpoint.handle("quotation", null, "sales-quotation", null, null, null, null);

    assertEquals(422, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(0, error.getJSONArray("available").length());
    assertTrue(error.getString("message").contains("No search target is configured on this instance"));
  }

  /** The {@code available} list is capped at 20 keys even when more targets are configured. */
  @Test
  public void availableListIsCappedAtTwenty() throws Exception {
    List<String> manyKeys = new ArrayList<>();
    for (int i = 0; i < 25; i++) manyKeys.add("target-" + i);
    NeoVectorSearchEndpoint endpoint = endpointWithCatalog(manyKeys, true);

    NeoResponse response = endpoint.handle("quotation", null, "not-a-real-target", null, null, null, null);

    assertEquals(422, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(20, error.getJSONArray("available").length());
  }

  // ── IMP-41 follow-up: configuredTargetKeys() must degrade, not propagate ──

  /**
   * The bug this pins: {@code OBContext.setAdminMode(true)} used to be called BEFORE the
   * try/catch in {@code configuredTargetKeys()}, so when it failed — exactly the case here, a
   * plain unit-test JVM with no live Hibernate session, which is also what tool generation hits
   * outside of a request — the exception escaped uncaught, took down {@code buildVectorSearchTool}
   * and with it the entire MCP tool list. No mocking on purpose: the absence of a live session
   * IS the failure mode under test.
   */
  @Test
  public void configuredTargetKeysReturnsEmptyOptionalWhenNoLiveSessionExists() {
    assertFalse(NeoVectorSearchEndpoint.configuredTargetKeys().isPresent());
  }

  /**
   * {@code authorizedTargetKeys()} must propagate an unreadable catalogue as {@code
   * Optional.empty()}, not collapse it into a present-but-empty (all-denied) list — the two drive
   * different branches in {@code McpToolRouter.handleVectorSearch}. Same no-mocking rationale as
   * the test above. The distinction used to be null-vs-empty and is now absent-vs-empty; what must
   * not change is that there are still two answers.
   */
  @Test
  public void authorizedTargetKeysReturnsEmptyOptionalWhenCatalogueCannotBeRead() {
    assertFalse(NeoVectorSearchEndpoint.authorizedTargetKeys().isPresent());
  }

  /**
   * IMP-41: {@code authorizedTargetKeys()} must authorize each configured key independently.
   * {@code TargetEntityAuthorizer#isAuthorized} is all-or-nothing over the list it is handed, so
   * if the whole configured catalog were authorized in one call, a single unreadable target would
   * deny every other target too. This is the assertion that matters most: it is the exact
   * all-or-nothing trap IMP-41's own javadoc warns against reintroducing.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void authorizedTargetKeysFiltersPerKeyNotAllOrNothing() throws Exception {
    VectorSearchTarget readable = mock(VectorSearchTarget.class);
    when(readable.getSearchKey()).thenReturn("readable-target");
    VectorSearchTarget unreadable = mock(VectorSearchTarget.class);
    when(unreadable.getSearchKey()).thenReturn("unreadable-target");

    OBDal dal = mock(OBDal.class);
    OBCriteria<VectorSearchTarget> catalogCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(VectorSearchTarget.class)).thenReturn(catalogCriteria);
    when(catalogCriteria.list()).thenReturn(Arrays.asList(readable, unreadable));

    Class<Object> authorizerClass = targetEntityAuthorizerClass();
    try (MockedStatic<OBDal> dals = mockStatic(OBDal.class);
        // Needed so configuredTargetKeys()'s own setAdminMode/restorePreviousMode succeed as
        // no-ops instead of hitting the no-live-session failure covered by the tests above.
        MockedStatic<OBContext> contexts = mockStatic(OBContext.class);
        MockedConstruction<Object> construction = mockConstruction(authorizerClass,
            (authorizerMock, context) -> {
              NeoVectorSearchEndpoint.NamespaceAuthorizer stub =
                  (NeoVectorSearchEndpoint.NamespaceAuthorizer) authorizerMock;
              when(stub.isAuthorized(Collections.singletonList("readable-target"))).thenReturn(true);
              when(stub.isAuthorized(Collections.singletonList("unreadable-target"))).thenReturn(false);
            })) {
      dals.when(OBDal::getInstance).thenReturn(dal);

      List<String> allowed = NeoVectorSearchEndpoint.authorizedTargetKeys().orElseThrow();

      assertEquals(Collections.singletonList("readable-target"), allowed);
    }
  }

  @SuppressWarnings("unchecked")
  private static Class<Object> targetEntityAuthorizerClass() throws ClassNotFoundException {
    return (Class<Object>) Class.forName(
        NeoVectorSearchEndpoint.class.getName() + "$TargetEntityAuthorizer");
  }
}
