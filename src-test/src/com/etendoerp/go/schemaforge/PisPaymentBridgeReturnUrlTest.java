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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.session.OBPropertiesProvider;

import com.etendoerp.psd2.bank.integration.utils.BankIntegrationUrlUtils;

/**
 * The address Salt Edge is told to bring the browser back to after SCA (ETP-4895).
 *
 * <p>It used to be composed from {@code context.url} + {@code context.name} through PSD2's
 * {@code buildBaseUrl}. That holds for PSD2's own convention — {@code context.url} = bare server,
 * as its README documents — but Etendo's {@code Openbravo.properties.template} ships
 * {@code context.url} WITH the context path, and on a server configured that way the composition
 * repeated it: Salt Edge sent the user to {@code /etendo/etendo/sws/pis-return}, which matches no
 * servlet mapping, so the browser landed on Etendo's generic error page right after paying.
 */
class PisPaymentBridgeReturnUrlTest {

  private static final String PATH = "/sws/pis-return";

  private static String resolve(HttpServletRequest request) throws Exception {
    Method m = PisPaymentBridge.class.getDeclaredMethod("resolveBackendReturnUrl",
        HttpServletRequest.class);
    m.setAccessible(true);
    return (String) m.invoke(null, request);
  }

  /** A request as a reverse proxy in front of Tomcat presents it. */
  private static HttpServletRequest proxiedRequest(String proto, String host, String contextPath) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("X-Forwarded-Proto")).thenReturn(proto);
    when(request.getHeader("X-Forwarded-Host")).thenReturn(host);
    when(request.getContextPath()).thenReturn(contextPath);
    return request;
  }

  /** A request straight off Tomcat, with no proxy headers at all. */
  private static HttpServletRequest directRequest(String scheme, String host, int port,
      String contextPath) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getScheme()).thenReturn(scheme);
    when(request.getServerName()).thenReturn(host);
    when(request.getServerPort()).thenReturn(port);
    when(request.getContextPath()).thenReturn(contextPath);
    return request;
  }

  private static Properties props(String contextUrl, String contextName) {
    Properties p = new Properties();
    p.setProperty("context.url", contextUrl);
    p.setProperty("context.name", contextName);
    return p;
  }

  /** Runs {@code body} with the given Openbravo properties in place. */
  private static String withProperties(Properties properties, HttpServletRequest request)
      throws Exception {
    OBPropertiesProvider provider = mock(OBPropertiesProvider.class);
    when(provider.getOpenbravoProperties()).thenReturn(properties);
    try (MockedStatic<OBPropertiesProvider> propsMock = mockStatic(OBPropertiesProvider.class);
        MockedStatic<BankIntegrationUrlUtils> urlMock = mockStatic(BankIntegrationUrlUtils.class)) {
      propsMock.when(OBPropertiesProvider::getInstance).thenReturn(provider);
      // Mirrors what the real helper composes, so the collapse is exercised against its actual
      // output rather than against an assumption about it.
      String composed = properties.getProperty("context.url").replaceAll("/$", "")
          + "/" + properties.getProperty("context.name");
      urlMock.when(BankIntegrationUrlUtils::buildBaseUrl).thenReturn(composed);
      return resolve(request);
    }
  }

  /**
   * The case that was reported: a server whose {@code context.url} follows Etendo's own template
   * and therefore already carries the context path. Composing {@code context.name} on top of that
   * sent Salt Edge to {@code /etendo/etendo/sws/pis-return}, and the user landed on Etendo's
   * generic error page right after paying. Kept at the top of this class rather than inside a
   * grouping, because it is the behaviour the whole change exists for.
   */
  @Test
  @DisplayName("the reported case: a context path already in context.url is not repeated")
  void doesNotRepeatTheContextPath() throws Exception {
    String url = withProperties(props("https://core.experimental.etendo.cloud/etendo", "etendo"),
        proxiedRequest("https", "core.experimental.etendo.cloud", "/etendo"));

    assertEquals("https://core.experimental.etendo.cloud/etendo" + PATH, url);
  }

  @Nested
  @DisplayName("taken from the request, where the context path comes from Tomcat")
  class FromRequest {

    @Test
    @DisplayName("reads only the first hop of a forwarded chain")
    void takesTheFirstForwardedHop() throws Exception {
      String url = withProperties(props("https://server/etendo", "etendo"),
          proxiedRequest("https, http", "public.example.com, internal:8080", "/etendo"));

      assertEquals("https://public.example.com/etendo" + PATH, url);
    }

    @Test
    @DisplayName("falls back to the request itself when the proxy forwards nothing")
    void usesTheRequestWithoutProxyHeaders() throws Exception {
      String url = withProperties(props("https://server/etendo", "etendo"),
          directRequest("https", "bank.example.com", 443, "/etendo"));

      // 443 on https is the default the browser omits.
      assertEquals("https://bank.example.com/etendo" + PATH, url);
    }

    @Test
    @DisplayName("keeps a non-default port")
    void keepsANonDefaultPort() throws Exception {
      String url = withProperties(props("https://server/etendo", "etendo"),
          directRequest("http", "bank.example.com", 8080, "/etendo"));

      assertEquals("http://bank.example.com:8080/etendo" + PATH, url);
    }

    @Test
    @DisplayName("a root deployment has no context path to add")
    void handlesARootContext() throws Exception {
      String url = withProperties(props("https://server", "etendo"),
          proxiedRequest("https", "bank.example.com", ""));

      assertEquals("https://bank.example.com" + PATH, url);
    }
  }

  @Nested
  @DisplayName("falling back to the configured base URL")
  class FromProperties {

    @Test
    @DisplayName("an address only reachable from inside the deployment is not offered to a bank")
    void fallsBackForAnInternalHost() throws Exception {
      // No proxy headers and Tomcat sees its own container name: a bank cannot redirect a browser
      // there, so the configured base URL is the better guess.
      String url = withProperties(props("https://public.example.com", "etendo"),
          directRequest("http", "tomcat", 8080, "/etendo"));

      assertEquals("https://public.example.com/etendo" + PATH, url);
    }

    @Test
    @DisplayName("collapses the context path the composition repeats")
    void collapsesTheRepeatedContextPath() throws Exception {
      // context.url per Etendo's own template (with the path) + context.name composed on top.
      String url = withProperties(props("https://public.example.com/etendo", "etendo"),
          directRequest("http", "localhost", 8080, "/etendo"));

      assertEquals("https://public.example.com/etendo" + PATH, url);
    }

    @Test
    @DisplayName("leaves PSD2's own convention untouched")
    void keepsTheBareServerConvention() throws Exception {
      String url = withProperties(props("https://public.example.com/", "etendo"),
          directRequest("http", "127.0.0.1", 8080, "/etendo"));

      assertEquals("https://public.example.com/etendo" + PATH, url);
    }

    @Test
    @DisplayName("a path that merely resembles the context name is not mangled")
    void onlyCollapsesAnExactRepetition() throws Exception {
      // Ends with /etendo once, not twice: nothing to collapse.
      String url = withProperties(props("https://public.example.com/apps", "etendo"),
          directRequest("http", "localhost", 8080, "/etendo"));

      assertEquals("https://public.example.com/apps/etendo" + PATH, url);
    }
  }
}
