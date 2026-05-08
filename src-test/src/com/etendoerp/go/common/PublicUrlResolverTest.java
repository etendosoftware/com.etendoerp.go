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

package com.etendoerp.go.common;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.servlet.http.HttpServletRequest;

import org.junit.After;
import org.junit.Test;

/** Tests public URL resolution for proxied OAuth2/MCP deployments. */
public class PublicUrlResolverTest {

  @After
  public void clearProperties() {
    System.clearProperty(PublicUrlResolver.MCP_PUBLIC_URL_PROPERTY);
    System.clearProperty(PublicUrlResolver.OAUTH2_PUBLIC_URL_PROPERTY);
  }

  @Test
  public void resolvesConfiguredPublicUrlsWithoutTrailingSlash() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    System.setProperty(PublicUrlResolver.MCP_PUBLIC_URL_PROPERTY,
        "https://go.experimental.etendo.cloud/mcp/");
    System.setProperty(PublicUrlResolver.OAUTH2_PUBLIC_URL_PROPERTY,
        "https://go.experimental.etendo.cloud/etendo/oauth2/");

    assertEquals("https://go.experimental.etendo.cloud/mcp",
        PublicUrlResolver.resolveMcpResourceUrl(request));
    assertEquals("https://go.experimental.etendo.cloud/etendo/oauth2",
        PublicUrlResolver.resolveOAuth2Url(request));
  }

  @Test
  public void ignoresSlashOnlyConfiguredUrls() {
    HttpServletRequest request = buildLocalRequest();

    System.setProperty(PublicUrlResolver.MCP_PUBLIC_URL_PROPERTY, "////");
    System.setProperty(PublicUrlResolver.OAUTH2_PUBLIC_URL_PROPERTY, "/");

    assertEquals("http://localhost:8080/etendo_sf2/sws/mcp",
        PublicUrlResolver.resolveMcpResourceUrl(request));
    assertEquals("http://localhost:8080/etendo_sf2/oauth2",
        PublicUrlResolver.resolveOAuth2Url(request));
  }

  @Test
  public void appendPathNormalizesSlashes() {
    assertEquals("https://go.experimental.etendo.cloud/mcp/.well-known/oauth-protected-resource",
        PublicUrlResolver.appendPath("https://go.experimental.etendo.cloud/mcp/",
            "/.well-known/oauth-protected-resource"));
  }

  @Test
  public void fallsBackToInternalServletUrls() {
    HttpServletRequest request = buildLocalRequest();

    assertEquals("http://localhost:8080/etendo_sf2/sws/mcp",
        PublicUrlResolver.resolveMcpResourceUrl(request));
    assertEquals("http://localhost:8080/etendo_sf2/oauth2",
        PublicUrlResolver.resolveOAuth2Url(request));
  }

  private HttpServletRequest buildLocalRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getScheme()).thenReturn("http");
    when(request.getServerName()).thenReturn("localhost");
    when(request.getServerPort()).thenReturn(8080);
    when(request.getContextPath()).thenReturn("/etendo_sf2");
    return request;
  }
}
