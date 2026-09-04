package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit coverage for the global vector-search request contract. */
public class NeoVectorSearchEndpointTest {
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
