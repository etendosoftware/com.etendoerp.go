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
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code GET|PUT /sws/neo/doctemplateconfig} (ETP-5988): reading and saving the template choice of
 * an organization, and refusing what is not the caller's.
 *
 * @covers com.etendoerp.go.schemaforge.NeoDocTemplateConfigEndpoint
 * @covers com.etendoerp.go.schemaforge.DocTemplateConfigAccess
 */
@DisplayName("ETP-5988 — NeoDocTemplateConfigEndpoint")
class NeoDocTemplateConfigEndpointTest {

  private static final String ORG_A = "ORGA00000000000000000000000000AA";
  private static final String ORG_B = "ORGB00000000000000000000000000BB";

  private DocTemplateConfigStore store;
  private DocTemplateConfigAccess access;
  private NeoDocTemplateConfigEndpoint endpoint;

  @BeforeEach
  void setUp() {
    store = mock(DocTemplateConfigStore.class);
    access = mock(DocTemplateConfigAccess.class);
    when(access.currentOrgId()).thenReturn(ORG_A);
    when(access.canRead(ORG_A)).thenReturn(true);
    when(access.canWrite(ORG_A)).thenReturn(true);
    when(access.isAdmin()).thenReturn(true);
    endpoint = new NeoDocTemplateConfigEndpoint(store, access);
  }

  private static final class BytesInputStream extends ServletInputStream {
    private final ByteArrayInputStream in;

    BytesInputStream(byte[] bytes) {
      this.in = new ByteArrayInputStream(bytes);
    }

    @Override
    public int read() {
      return in.read();
    }

    @Override
    public int read(byte[] b, int off, int len) {
      return in.read(b, off, len);
    }

    @Override
    public boolean isFinished() {
      return in.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener listener) {
      throw new UnsupportedOperationException();
    }
  }

  private static HttpServletRequest get(String orgId, String docType) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getParameter("orgId")).thenReturn(orgId);
    when(request.getParameter("docType")).thenReturn(docType);
    return request;
  }

  private static HttpServletRequest put(String body) throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("PUT");
    when(request.getContentLengthLong()).thenReturn((long) bytes.length);
    when(request.getInputStream()).thenReturn(new BytesInputStream(bytes));
    return request;
  }

  private static DocTemplateConfig saved(String orgId, String docType, String variant) {
    return new DocTemplateConfig(orgId, docType, variant, null, "Thanks", true);
  }

  // ---------------------------------------------------------------- read

  @Test
  @DisplayName("GET with no saved row answers the defaults, configured:false, never a 404")
  void readsDefaultsWhenNothingIsSaved() throws Exception {
    NeoResponse response = endpoint.handle(get(null, "sales-invoice"));

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals(ORG_A, body.getString("orgId"));
    assertEquals("classic", body.getString("variant"));
    assertEquals(false, body.getBoolean("configured"));
  }

  @Test
  @DisplayName("GET answers the saved row of exactly the organization and document type asked")
  void readsTheSavedRow() throws Exception {
    when(store.find(ORG_A, "sales-order")).thenReturn(saved(ORG_A, "sales-order", "modern"));

    NeoResponse response = endpoint.handle(get(null, "sales-order"));

    assertEquals("modern", response.getBody().getString("variant"));
    assertEquals(true, response.getBody().getBoolean("configured"));
    verify(store, never()).find(eq(ORG_B), anyString());
  }

  @Test
  @DisplayName("GET without a doc type lists only the organization's saved configurations")
  void listsTheOrganization() throws Exception {
    when(store.list(ORG_A)).thenReturn(Arrays.asList(saved(ORG_A, "sales-invoice", "compact"),
        saved(ORG_A, "sales-order", "modern")));

    JSONObject body = endpoint.handle(get(null, null)).getBody();

    JSONArray configs = body.getJSONArray("configs");
    assertEquals(ORG_A, body.getString("orgId"));
    assertEquals(2, configs.length());
    assertEquals("compact", configs.getJSONObject(0).getString("variant"));
  }

  @Test
  @DisplayName("GET of an organization the caller cannot read is a 403 and touches no data")
  void readOfAnotherOrganizationIsForbidden() throws Exception {
    when(access.canRead(ORG_B)).thenReturn(false);

    NeoResponse response = endpoint.handle(get(ORG_B, "sales-invoice"));

    assertEquals(403, response.getHttpStatus());
    verify(store, never()).find(anyString(), anyString());
    verify(store, never()).list(anyString());
  }

  @Test
  @DisplayName("GET of an unknown document type is a 400")
  void readOfUnknownDocType() throws Exception {
    assertEquals(400, endpoint.handle(get(null, "invoice")).getHttpStatus());
  }

  @Test
  @DisplayName("organization * (0) holds no templates: 400 on both verbs, before any access question")
  void rootOrganizationIsRefused() throws Exception {
    assertEquals(400, endpoint.handle(get("0", "sales-invoice")).getHttpStatus());
    assertEquals(400, endpoint.handle(put("{\"orgId\":\"0\",\"docType\":\"sales-invoice\"}"))
        .getHttpStatus());
    verify(store, never()).save(anyString(), anyString(), anyString(), any(), any());
  }

  // ---------------------------------------------------------------- save

  @Test
  @DisplayName("PUT creates the row from the defaults for the caller's own organization")
  void createsFromDefaults() throws Exception {
    when(store.save(ORG_A, "sales-invoice", "modern", null, null))
        .thenReturn(new DocTemplateConfig(ORG_A, "sales-invoice", "modern", null, null, true));

    NeoResponse response = endpoint
        .handle(put("{\"docType\":\"sales-invoice\",\"variant\":\"modern\"}"));

    assertEquals(200, response.getHttpStatus());
    assertEquals("modern", response.getBody().getString("variant"));
    verify(store).save(ORG_A, "sales-invoice", "modern", null, null);
  }

  @Test
  @DisplayName("PUT keeps what a key left out had saved, and an explicit null or blank clears it")
  void mergesAndClears() throws Exception {
    when(store.find(ORG_A, "sales-invoice")).thenReturn(new DocTemplateConfig(ORG_A,
        "sales-invoice", "compact", "https://cdn.example.com/l.png", "Old footer", true));
    when(store.save(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(saved(ORG_A, "sales-invoice", "compact"));

    endpoint.handle(put("{\"docType\":\"sales-invoice\",\"footerText\":\"New footer\"}"));
    verify(store).save(ORG_A, "sales-invoice", "compact", "https://cdn.example.com/l.png",
        "New footer");

    endpoint.handle(put("{\"docType\":\"sales-invoice\",\"logo\":null,\"footerText\":\"  \"}"));
    verify(store).save(ORG_A, "sales-invoice", "compact", null, null);
  }

  @Test
  @DisplayName("PUT by a caller who is not an administrator is a 403 and saves nothing")
  void nonAdminCannotSave() throws Exception {
    when(access.isAdmin()).thenReturn(false);

    NeoResponse response = endpoint.handle(put("{\"docType\":\"sales-invoice\"}"));

    assertEquals(403, response.getHttpStatus());
    verify(store, never()).save(anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("PUT into an organization the role cannot write is a 403, whatever the body says")
  void crossOrganizationWriteIsForbidden() throws Exception {
    when(access.canWrite(ORG_B)).thenReturn(false);

    NeoResponse response = endpoint
        .handle(put("{\"orgId\":\"" + ORG_B + "\",\"docType\":\"sales-invoice\"}"));

    assertEquals(403, response.getHttpStatus());
    verify(store, never()).save(anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("PUT cannot name the client: a clientId in the body is ignored")
  void clientIsNeverTakenFromTheBody() throws Exception {
    when(store.save(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(saved(ORG_A, "sales-invoice", "classic"));

    endpoint.handle(put("{\"docType\":\"sales-invoice\",\"clientId\":\"OTHER\",\"client\":\"OTHER\"}"));

    verify(store).save(ORG_A, "sales-invoice", "classic", null, null);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "{}",
      "{\"docType\":\"nope\"}",
      "{\"docType\":\"sales-invoice\",\"variant\":\"fancy\"}",
      "{\"docType\":\"sales-invoice\",\"variant\":null}",
      "{\"docType\":\"sales-invoice\",\"logo\":\"http://cdn.example.com/l.png\"}",
      "{\"docType\":\"sales-invoice\",\"logo\":\"data:image/svg+xml;base64,PHN2Zz4=\"}",
      "{\"docType\":\"sales-invoice\",\"logo\":42}",
      "{\"docType\":\"sales-invoice\",\"footerText\":7}",
      "{\"docType\":42}",
      "not json",
      "[]",
      ""
  })
  @DisplayName("PUT with invalid input is a 400 and saves nothing")
  void invalidInputIsRefused(String body) throws Exception {
    NeoResponse response = endpoint.handle(put(body));

    assertEquals(400, response.getHttpStatus(), body);
    verify(store, never()).save(anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("PUT with a body over the cap is a 413")
  void oversizedBody() throws Exception {
    String body = "{\"docType\":\"sales-invoice\",\"logo\":\"" + "A".repeat(
        NeoDocTemplateConfigEndpoint.MAX_BODY_BYTES) + "\"}";

    assertEquals(413, endpoint.handle(put(body)).getHttpStatus());
    verify(store, never()).save(anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  @DisplayName("another method is a 405")
  void otherMethods() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("POST");

    assertEquals(405, endpoint.handle(request).getHttpStatus());
    verify(store, never()).list(anyString());
    verify(store, never()).save(anyString(), anyString(), anyString(), isNull(), isNull());
  }
}
