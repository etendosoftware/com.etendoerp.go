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

package com.etendoerp.go.portal;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Attachment;

import com.etendoerp.go.common.CompanyLogoResolver;
import com.etendoerp.go.schemaforge.NeoAttachmentsDownloader;
import com.etendoerp.go.schemaforge.NeoAttachmentsHelper;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link PortalServlet}, the customer portal's session-less API: where the token is
 * read from, which methods are served, rate limiting, caching headers, the single invalid-link
 * answer, the routing of the document reads and the tenant + Business Partner scope they get.
 *
 * <p>The link service and the three rate limiters are field-initialised by the servlet, so they are
 * replaced with {@code mockConstruction}; the one test that needs the real service's verdict on a
 * token drives it through a static mock of {@link PortalLinkDal} instead.
 *
 * @covers com.etendoerp.go.portal.PortalServlet
 */
public class PortalServletTest {

  private static final String CLIENT_ID = "CLIENT-1";
  private static final String BP_ID = "BP-1";
  private static final String INVALID_BODY = "{\"error\":\"This link is no longer valid\"}";
  private static final String[] DATA_PATHS = { "/home", "/documents",
      "/documents/INVOICE/INV-1", "/documents/INVOICE/INV-1/pdf" };

  private MockedStatic<OBContext> obContext;
  private MockedStatic<CompanyLogoResolver> logos;
  private MockedStatic<PortalUsageEvents> events;

  private PortalServlet servlet;
  private PortalLinkService linkService;
  private PortalRateLimiter meLimiter;
  private PortalRateLimiter renewLimiter;
  private PortalRateLimiter dataLimiter;

  @Before
  public void setUp() {
    obContext = mockStatic(OBContext.class);
    logos = mockStatic(CompanyLogoResolver.class);
    events = mockStatic(PortalUsageEvents.class);
    try (MockedConstruction<PortalLinkService> services =
        mockConstruction(PortalLinkService.class);
        MockedConstruction<PortalRateLimiter> limiters = allowingLimiters()) {
      servlet = new PortalServlet();
      linkService = services.constructed().get(0);
      // Field order in PortalServlet: the /me limiter first, then the /renew one, then the one
      // shared by the data reads (/home, /documents*).
      meLimiter = limiters.constructed().get(0);
      renewLimiter = limiters.constructed().get(1);
      dataLimiter = limiters.constructed().get(2);
    }
    when(linkService.validate(any()))
        .thenReturn(new PortalLinkService.Validation(PortalLinkService.State.INVALID, null));
  }

  @After
  public void tearDown() {
    events.close();
    logos.close();
    obContext.close();
  }

  @Test
  public void tokenIsReadFromThePortalHeaderThenTheMePathThenABearerHeader() throws Exception {
    // { X-Portal-Token, path, Authorization, token the service is asked about }
    String[][] cases = {
        { "portal-tok", "/me/path-tok", "Bearer go-session-jwt", "portal-tok" },
        // A signed-in GO user's own bearer JWT never stands in for the link's token.
        { "portal-tok", "/me", "Bearer go-session-jwt", "portal-tok" },
        { null, "/me/path-tok", "Bearer go-session-jwt", "path-tok" },
        { null, "/me", "Bearer legacy-tok", "legacy-tok" },
        { null, "/me", "Basic dXNlcjpwYXNz", null },
        { null, "/me", null, null },
    };
    for (String[] c : cases) {
      HttpServletRequest request = request("GET", c[1]);
      when(request.getHeader(PortalServlet.HEADER_PORTAL_TOKEN)).thenReturn(c[0]);
      when(request.getHeader("Authorization")).thenReturn(c[2]);
      clearInvocations(linkService);

      servlet.service(request, new Call().response);

      ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
      verify(linkService).validate(token.capture());
      assertEquals(Arrays.toString(c), c[3], token.getValue());
    }
  }

  @Test
  public void anyMethodButGetOrPostRenewIsRejectedWith405() throws Exception {
    String[][] cases = { { "PUT", "/me" }, { "DELETE", "/me" }, { "PATCH", "/documents" },
        { "POST", "/me" }, { "POST", "/home" }, { "DELETE", "/documents/INVOICE/INV-1" },
        { "HEAD", "/renew" } };
    for (String[] c : cases) {
      Call call = new Call();

      servlet.service(request(c[0], c[1]), call.response);

      assertEquals(c[0] + " " + c[1], HttpServletResponse.SC_METHOD_NOT_ALLOWED, call.status());
    }
    verifyNoInteractions(linkService);
  }

  @Test
  public void eachEndpointAnswers429OnlyWhenItsOwnRateLimiterRefuses() throws Exception {
    when(linkService.renew(any())).thenReturn(PortalLinkService.RenewalOutcome.SENT);

    // Only the /me limiter refuses: /me is throttled, /renew is served.
    when(meLimiter.tryAcquire(anyString())).thenReturn(false);
    Call me = new Call();
    servlet.service(request("GET", "/me"), me.response);
    Call renew = new Call();
    servlet.service(request("POST", "/renew"), renew.response);
    assertEquals(429, me.status());
    assertEquals(HttpServletResponse.SC_OK, renew.status());
    verify(linkService, never()).validate(any());

    // Only the /renew limiter refuses: /renew is throttled, /me is served.
    when(meLimiter.tryAcquire(anyString())).thenReturn(true);
    when(renewLimiter.tryAcquire(anyString())).thenReturn(false);
    clearInvocations(linkService);
    Call meAgain = new Call();
    servlet.service(request("GET", "/me"), meAgain.response);
    Call renewAgain = new Call();
    servlet.service(request("POST", "/renew"), renewAgain.response);
    assertEquals("an invalid token, not a throttled request",
        HttpServletResponse.SC_UNAUTHORIZED, meAgain.status());
    assertEquals(429, renewAgain.status());
    verify(linkService, never()).renew(any());

    // Only the data limiter refuses: every data read is throttled, /me and /renew are served.
    when(renewLimiter.tryAcquire(anyString())).thenReturn(true);
    when(dataLimiter.tryAcquire(anyString())).thenReturn(false);
    clearInvocations(linkService);
    for (String path : DATA_PATHS) {
      Call data = new Call();
      servlet.service(request("GET", path), data.response);
      assertEquals(path, 429, data.status());
    }
    verify(linkService, never()).validate(any());
    Call meServed = new Call();
    servlet.service(request("GET", "/me"), meServed.response);
    Call renewServed = new Call();
    servlet.service(request("POST", "/renew"), renewServed.response);
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, meServed.status());
    assertEquals(HttpServletResponse.SC_OK, renewServed.status());
  }

  @Test
  public void everyResponseIsMarkedNoStore() throws Exception {
    when(linkService.renew(any())).thenReturn(PortalLinkService.RenewalOutcome.SENT);
    String[][] cases = { { "GET", "/me" }, { "PUT", "/me" }, { "GET", "/nowhere" },
        { "POST", "/renew" }, { "GET", "/home" }, { "GET", "/documents" } };
    for (String[] c : cases) {
      Call call = new Call();

      servlet.service(request(c[0], c[1]), call.response);

      verify(call.response).setHeader(eq("Cache-Control"), contains("no-store"));
    }
  }

  @Test
  public void unknownPendingAndInactiveTokensGetTheSameBytes() throws Exception {
    PortalServlet realService;
    try (MockedConstruction<PortalRateLimiter> limiters = allowingLimiters()) {
      realService = new PortalServlet();
    }
    Date sentAt = new Date(System.currentTimeMillis() - 60_000L);
    Date expiresAt = new Date(System.currentTimeMillis() + 3_600_000L);
    PortalLink pending = link(null, null, true);
    PortalLink inactive = link(sentAt, expiresAt, false);
    List<String> bodies = new ArrayList<>();

    try (MockedStatic<PortalLinkDal> dal = mockStatic(PortalLinkDal.class)) {
      for (PortalLink candidate : Arrays.asList(null, pending, inactive)) {
        BaseOBObject row = candidate == null ? null : mock(BaseOBObject.class);
        dal.when(() -> PortalLinkDal.findByTokenHash(PortalTokens.hash("tok"))).thenReturn(row);
        dal.when(() -> PortalLinkDal.toLink(row)).thenReturn(candidate);
        HttpServletRequest request = request("GET", "/me");
        when(request.getHeader(PortalServlet.HEADER_PORTAL_TOKEN)).thenReturn("tok");
        Call call = new Call();

        realService.service(request, call.response);

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, call.status());
        bodies.add(call.body.toString());
      }
    }
    assertEquals(Collections.nCopies(3, INVALID_BODY), bodies);
  }

  @Test
  public void aLinkThatIsNotValidGetsTheSame401OnEveryDataRoute() throws Exception {
    Date past = new Date(0L);
    Date future = new Date(System.currentTimeMillis() + 3_600_000L);
    List<PortalLinkService.Validation> notValid = Arrays.asList(
        new PortalLinkService.Validation(PortalLinkService.State.INVALID, null),
        new PortalLinkService.Validation(PortalLinkService.State.EXPIRED,
            link(past, new Date(1L), true)),
        new PortalLinkService.Validation(PortalLinkService.State.UNAVAILABLE,
            link(past, future, true)));
    try (MockedStatic<PortalDocumentsEndpoint> endpoint =
        mockStatic(PortalDocumentsEndpoint.class);
        MockedStatic<PortalDocumentQuery> documents = mockStatic(PortalDocumentQuery.class)) {
      for (PortalLinkService.Validation validation : notValid) {
        when(linkService.validate(any())).thenReturn(validation);
        for (String path : DATA_PATHS) {
          Call call = new Call();

          servlet.service(request("GET", path), call.response);

          assertEquals(validation.getState() + " " + path, HttpServletResponse.SC_UNAUTHORIZED,
              call.status());
          assertEquals(INVALID_BODY, call.body.toString());
        }
      }
      endpoint.verifyNoInteractions();
      documents.verifyNoInteractions();
    }
  }

  @Test
  public void theDocumentListIsReadWithTheValidatedLinksScopeOnly() throws Exception {
    validLink();
    try (MockedStatic<PortalDocumentsEndpoint> endpoint =
        mockStatic(PortalDocumentsEndpoint.class)) {
      JSONObject page = new JSONObject().put("totalCount", 0);
      endpoint.when(() -> PortalDocumentsEndpoint.list(any(), any(), any(), any(), any(), any(),
          any(), any())).thenReturn(page);
      HttpServletRequest request = request("GET", "/documents");
      when(request.getParameter("type")).thenReturn("invoice");
      // Nothing a caller sends can widen the scope: there is no parameter for it to come from.
      when(request.getParameter("clientId")).thenReturn("CLIENT-2");
      when(request.getParameter("businessPartnerId")).thenReturn("BP-2");
      Call call = new Call();

      servlet.service(request, call.response);

      assertEquals(HttpServletResponse.SC_OK, call.status());
      ArgumentCaptor<PortalSession> scope = ArgumentCaptor.forClass(PortalSession.class);
      endpoint.verify(() -> PortalDocumentsEndpoint.list(scope.capture(), eq("invoice"), any(),
          any(), any(), any(), any(), any()));
      assertEquals(CLIENT_ID, scope.getValue().getClientId());
      assertEquals(BP_ID, scope.getValue().getBusinessPartnerId());
    }
  }

  @Test
  public void anUnknownTypeAMalformedPathOrAnOutOfScopeDetailIs404() throws Exception {
    validLink();
    // The endpoint answers null (its "not found") for anything it does not resolve.
    String[] paths = { "/documents", "/documents/INVOICE", "/documents/INVOICE/INV-1/xml",
        "/documents/INVOICE/INV-1/pdf/more", "/documents/INVOICE/OTHER-BPS-INVOICE" };
    try (MockedStatic<PortalDocumentsEndpoint> endpoint =
        mockStatic(PortalDocumentsEndpoint.class)) {
      for (String path : paths) {
        HttpServletRequest request = request("GET", path);
        when(request.getParameter("type")).thenReturn("receipt");
        Call call = new Call();

        servlet.service(request, call.response);

        assertEquals(path, HttpServletResponse.SC_NOT_FOUND, call.status());
      }
      // Only the well-formed detail path reached the endpoint; the malformed ones never did.
      endpoint.verify(() -> PortalDocumentsEndpoint.detail(any(), any(), any()));
      endpoint.verify(() -> PortalDocumentsEndpoint.detail(any(), eq("INVOICE"),
          eq("OTHER-BPS-INVOICE")));
    }
  }

  @Test
  public void aPdfOutsideTheLinksScopeIs404AndNeverReachesTheAttachments() throws Exception {
    validLink();
    try (MockedStatic<PortalDocumentQuery> documents = mockStatic(PortalDocumentQuery.class);
        MockedStatic<NeoAttachmentsHelper> attachments = mockStatic(NeoAttachmentsHelper.class)) {
      Call call = new Call();

      servlet.service(request("GET", "/documents/INVOICE/OTHER-TENANTS-INVOICE/pdf"),
          call.response);

      assertEquals(HttpServletResponse.SC_NOT_FOUND, call.status());
      ArgumentCaptor<PortalSession> scope = ArgumentCaptor.forClass(PortalSession.class);
      documents.verify(() -> PortalDocumentQuery.findInScope(scope.capture(),
          eq(PortalDocumentKind.INVOICE), eq("OTHER-TENANTS-INVOICE")));
      assertEquals(CLIENT_ID, scope.getValue().getClientId());
      assertEquals(BP_ID, scope.getValue().getBusinessPartnerId());
      attachments.verifyNoInteractions();
    }
  }

  @Test
  public void aPdfIsStreamedOnlyWhenItsAttachmentIsTheLinksTenants() throws Exception {
    validLink();
    BaseOBObject invoice = mock(BaseOBObject.class);
    OBDal obDal = mock(OBDal.class);
    NeoResponse main = new NeoResponse(200, new JSONObject().put("id", "ATT-1"));
    // { attachment's tenant, streamed }
    Object[][] cases = { { "CLIENT-2", false }, { CLIENT_ID, true } };
    for (Object[] c : cases) {
      Client owner = mock(Client.class);
      when(owner.getId()).thenReturn((String) c[0]);
      Attachment attachment = mock(Attachment.class);
      when(attachment.getClient()).thenReturn(owner);
      when(obDal.get(Attachment.class, "ATT-1")).thenReturn(attachment);
      try (MockedStatic<PortalDocumentQuery> documents = mockStatic(PortalDocumentQuery.class);
          MockedStatic<NeoAttachmentsHelper> attachments = mockStatic(NeoAttachmentsHelper.class);
          MockedStatic<NeoAttachmentsDownloader> downloads =
              mockStatic(NeoAttachmentsDownloader.class);
          MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
        obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
        documents.when(() -> PortalDocumentQuery.findInScope(any(),
            eq(PortalDocumentKind.INVOICE), eq("INV-1"))).thenReturn(invoice);
        attachments.when(() -> NeoAttachmentsHelper.handleGetMain("C_Invoice", "INV-1"))
            .thenReturn(main);
        Call call = new Call();

        servlet.service(request("GET", "/documents/INVOICE/INV-1/pdf"), call.response);

        if ((Boolean) c[1]) {
          downloads.verify(() -> NeoAttachmentsDownloader.handleDownload("ATT-1", call.response));
          events.verify(() -> PortalUsageEvents.downloaded(any(), eq("INV-1")));
        } else {
          assertEquals(HttpServletResponse.SC_NOT_FOUND, call.status());
          downloads.verifyNoInteractions();
          events.verify(() -> PortalUsageEvents.downloaded(any(), any()), never());
        }
      }
    }
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private static MockedConstruction<PortalRateLimiter> allowingLimiters() {
    return mockConstruction(PortalRateLimiter.class,
        (limiter, context) -> when(limiter.tryAcquire(any())).thenReturn(true));
  }

  private void validLink() {
    Date sentAt = new Date(System.currentTimeMillis() - 60_000L);
    Date expiresAt = new Date(System.currentTimeMillis() + 3_600_000L);
    when(linkService.validate(any())).thenReturn(new PortalLinkService.Validation(
        PortalLinkService.State.VALID, link(sentAt, expiresAt, true)));
  }

  private static PortalLink link(Date sentAt, Date expiresAt, boolean active) {
    return new PortalLink("LINK-1", CLIENT_ID, "Acme", "ORG-1", BP_ID, "Customer SL",
        PortalDocumentKind.INVOICE, "INV-1", PortalLink.ORIGIN_SEND, null, "LINK-1",
        "sales-invoice-send", "a@customer.test", null, "es_ES", sentAt, expiresAt, active, false);
  }

  private static HttpServletRequest request(String method, String path) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn(method);
    when(request.getPathInfo()).thenReturn(path);
    when(request.getRemoteAddr()).thenReturn("203.0.113.7");
    return request;
  }

  /** A response mock that remembers its last status and its JSON body. */
  private static final class Call {
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final StringWriter body = new StringWriter();

    Call() throws IOException {
      when(response.getWriter()).thenReturn(new PrintWriter(body, true));
    }

    int status() {
      ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
      verify(response, atLeastOnce()).setStatus(status.capture());
      return status.getValue();
    }
  }
}
