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
package com.etendoerp.go.oauth2;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import com.etendoerp.go.common.PublicUrlResolver;

/** Tests OAuth2 discovery metadata used by remote MCP clients. */
public class OAuth2ServletMetadataTest {

  @After
  public void clearProperties() {
    System.clearProperty(PublicUrlResolver.OAUTH2_PUBLIC_URL_PROPERTY);
  }

  @Test
  public void metadataPublishesServletAuthorizationEndpoint() throws Exception {
    OAuth2Servlet servlet = new OAuth2Servlet();
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();

    when(request.getPathInfo()).thenReturn("/.well-known/oauth-authorization-server");
    when(request.getScheme()).thenReturn("https");
    when(request.getServerName()).thenReturn("core.experimental.etendo.cloud");
    when(request.getServerPort()).thenReturn(443);
    when(request.getContextPath()).thenReturn("/etendo");
    when(response.getWriter()).thenReturn(new PrintWriter(body));

    servlet.doGet(request, response);

    JSONObject metadata = new JSONObject(body.toString());
    assertEquals("https://core.experimental.etendo.cloud/etendo/oauth2",
        metadata.getString("issuer"));
    assertEquals("https://core.experimental.etendo.cloud/etendo/oauth2/authorize",
        metadata.getString("authorization_endpoint"));
    assertEquals("https://core.experimental.etendo.cloud/etendo/oauth2/token",
        metadata.getString("token_endpoint"));
    assertEquals("https://core.experimental.etendo.cloud/etendo/oauth2/register",
        metadata.getString("registration_endpoint"));
  }

  @Test
  public void metadataPublishesConfiguredPublicAuthorizationServer() throws Exception {
    OAuth2Servlet servlet = new OAuth2Servlet();
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();

    System.setProperty(PublicUrlResolver.OAUTH2_PUBLIC_URL_PROPERTY,
        "https://go.experimental.etendo.cloud/etendo/oauth2");
    when(request.getPathInfo()).thenReturn("/.well-known/oauth-authorization-server");
    when(response.getWriter()).thenReturn(new PrintWriter(body));

    servlet.doGet(request, response);

    JSONObject metadata = new JSONObject(body.toString());
    assertEquals("https://go.experimental.etendo.cloud/etendo/oauth2",
        metadata.getString("issuer"));
    assertEquals("https://go.experimental.etendo.cloud/etendo/oauth2/authorize",
        metadata.getString("authorization_endpoint"));
    assertEquals("https://go.experimental.etendo.cloud/etendo/oauth2/token",
        metadata.getString("token_endpoint"));
    assertEquals("https://go.experimental.etendo.cloud/etendo/oauth2/register",
        metadata.getString("registration_endpoint"));
  }
}
