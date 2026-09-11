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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;

import com.etendoerp.copilot.toolpack.webhooks.SimSearch;
import com.etendoerp.go.schemaforge.util.NeoLanguage;
import com.etendoerp.go.schemaforge.util.NeoTrl;
import com.smf.securewebservices.utils.WSResult;

/** Tests for {@link NeoSimSearchEndpoint}. */
public class NeoSimSearchEndpointTest {

  private static HttpServletRequest requestWith(String entityName, String items, String qty, String minPct) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getParameter("entityName")).thenReturn(entityName);
    when(req.getParameter("items")).thenReturn(items);
    when(req.getParameter("qtyResults")).thenReturn(qty);
    when(req.getParameter("minSimPercent")).thenReturn(minPct);
    return req;
  }

  @Test
  public void missingEntityNameReturnsBadRequest() {
    NeoSimSearchEndpoint endpoint = new NeoSimSearchEndpoint();
    HttpServletRequest req = requestWith(null, "[\"term\"]", null, null);

    NeoResponse resp = endpoint.handle(req);

    assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.getHttpStatus());
  }

  @Test
  public void missingItemsReturnsBadRequest() {
    NeoSimSearchEndpoint endpoint = new NeoSimSearchEndpoint();
    HttpServletRequest req = requestWith("Country", null, null, null);

    NeoResponse resp = endpoint.handle(req);

    assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.getHttpStatus());
  }

  @Test
  public void nonIntegerQtyResultsReturnsBadRequest() {
    NeoSimSearchEndpoint endpoint = new NeoSimSearchEndpoint();
    HttpServletRequest req = requestWith("Country", "[\"term\"]", "not-a-number", null);

    NeoResponse resp = endpoint.handle(req);

    assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.getHttpStatus());
  }

  @Test
  public void malformedItemsJsonReturnsBadRequest() {
    NeoSimSearchEndpoint endpoint = new NeoSimSearchEndpoint();
    HttpServletRequest req = requestWith("Country", "not-json", null, null);

    NeoResponse resp = endpoint.handle(req);

    assertEquals(HttpServletResponse.SC_BAD_REQUEST, resp.getHttpStatus());
  }

  @Test
  public void entityNotFoundReturnsUnprocessableEntity() throws Exception {
    NeoSimSearchEndpoint endpoint = new NeoSimSearchEndpoint();
    HttpServletRequest req = requestWith("NotAnEntity", "[\"term\"]", null, null);

    try (MockedStatic<SimSearch> simSearchMock = mockStatic(SimSearch.class)) {
      simSearchMock
          .when(() -> SimSearch.handleSimSearch(anyString(), anyString(), anyInt(), anyString()))
          .thenThrow(new ClassNotFoundException("NotAnEntity"));

      NeoResponse resp = endpoint.handle(req);

      assertEquals(422, resp.getHttpStatus());
    }
  }

  @Test
  public void unexpectedExceptionReturnsInternalError() throws Exception {
    NeoSimSearchEndpoint endpoint = new NeoSimSearchEndpoint();
    HttpServletRequest req = requestWith("Country", "[\"term\"]", null, null);

    try (MockedStatic<SimSearch> simSearchMock = mockStatic(SimSearch.class)) {
      simSearchMock
          .when(() -> SimSearch.handleSimSearch(anyString(), anyString(), anyInt(), anyString()))
          .thenThrow(new RuntimeException("boom"));

      NeoResponse resp = endpoint.handle(req);

      assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp.getHttpStatus());
    }
  }

  @Test
  public void successDelegatesOncePerItemAndReturnsOk() throws Exception {
    NeoSimSearchEndpoint endpoint = new NeoSimSearchEndpoint();
    HttpServletRequest req = requestWith("Country", "[\"Espana\",\"Argentina\"]", "5", "40");

    WSResult result = mock(WSResult.class);
    when(result.getJSONResponse()).thenReturn(mock(JSONObject.class));

    try (MockedStatic<SimSearch> simSearchMock = mockStatic(SimSearch.class)) {
      simSearchMock
          .when(() -> SimSearch.handleSimSearch(anyString(), anyString(), anyInt(), anyString()))
          .thenReturn(result);

      NeoResponse resp = endpoint.handle(req);

      assertEquals(200, resp.getHttpStatus());
      simSearchMock.verify(
          () -> SimSearch.handleSimSearch("Espana", "Country", 5, "40"), times(1));
      simSearchMock.verify(
          () -> SimSearch.handleSimSearch("Argentina", "Country", 5, "40"), times(1));
    }
  }

  @Test
  public void blankSearchTermIsSkippedWithoutCallingSimSearch() throws Exception {
    NeoSimSearchEndpoint endpoint = new NeoSimSearchEndpoint();
    HttpServletRequest req = requestWith("Country", "[\"\", \"  \"]", null, null);

    try (MockedStatic<SimSearch> simSearchMock = mockStatic(SimSearch.class)) {
      NeoResponse resp = endpoint.handle(req);

      assertEquals(200, resp.getHttpStatus());
      simSearchMock.verifyNoInteractions();
    }
  }

  @Test
  public void translatedTermIsRewrittenToTheBaseNameBeforeDelegating() throws Exception {
    // SimSearch only ever compares trigrams against the BASE row — translated text lives in a
    // sibling *_Trl table it never reads — so a Spanish session searching "España" scores 0.083
    // against "Spain" and resolves nothing. Substituting the base name first is what makes the
    // match possible; SimSearch itself is untouched and still does the matching.
    NeoSimSearchEndpoint endpoint = new NeoSimSearchEndpoint();
    HttpServletRequest req = requestWith("Country", "[\"España\"]", "1", "30");

    WSResult result = mock(WSResult.class);
    when(result.getJSONResponse()).thenReturn(mock(JSONObject.class));

    try (MockedStatic<SimSearch> simSearchMock = mockStatic(SimSearch.class);
        MockedStatic<NeoLanguage> language = mockStatic(NeoLanguage.class);
        MockedStatic<NeoTrl> trl = mockStatic(NeoTrl.class)) {
      language.when(NeoLanguage::currentCode).thenReturn("es_ES");
      trl.when(() -> NeoTrl.baseNameForTranslation("Country", "España", "es_ES"))
          .thenReturn("Spain");
      simSearchMock
          .when(() -> SimSearch.handleSimSearch(anyString(), anyString(), anyInt(), anyString()))
          .thenReturn(result);

      NeoResponse resp = endpoint.handle(req);

      assertEquals(200, resp.getHttpStatus());
      simSearchMock.verify(
          () -> SimSearch.handleSimSearch("Spain", "Country", 1, "30"), times(1));
    }
  }

  @Test
  public void untranslatableTermIsDelegatedVerbatim() throws Exception {
    // No translation row, no *_Trl sibling, or a translation shared by several base rows:
    // NeoTrl returns null and the request behaves exactly as it did before translation existed.
    // This fall-through is what keeps typo tolerance and base-language sessions unchanged.
    NeoSimSearchEndpoint endpoint = new NeoSimSearchEndpoint();
    HttpServletRequest req = requestWith("Country", "[\"Sparta\"]", "1", "30");

    WSResult result = mock(WSResult.class);
    when(result.getJSONResponse()).thenReturn(mock(JSONObject.class));

    try (MockedStatic<SimSearch> simSearchMock = mockStatic(SimSearch.class);
        MockedStatic<NeoLanguage> language = mockStatic(NeoLanguage.class);
        MockedStatic<NeoTrl> trl = mockStatic(NeoTrl.class)) {
      language.when(NeoLanguage::currentCode).thenReturn("es_ES");
      trl.when(() -> NeoTrl.baseNameForTranslation(anyString(), anyString(), anyString()))
          .thenReturn(null);
      simSearchMock
          .when(() -> SimSearch.handleSimSearch(anyString(), anyString(), anyInt(), anyString()))
          .thenReturn(result);

      NeoResponse resp = endpoint.handle(req);

      assertEquals(200, resp.getHttpStatus());
      simSearchMock.verify(
          () -> SimSearch.handleSimSearch("Sparta", "Country", 1, "30"), times(1));
    }
  }
}
