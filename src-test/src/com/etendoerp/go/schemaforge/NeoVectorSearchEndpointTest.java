package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.model.ad.datamodel.Table;
import com.etendoerp.db.extended.data.VectorSource;
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
}
