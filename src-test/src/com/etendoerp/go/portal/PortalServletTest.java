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
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.common.CompanyLogoResolver;
import com.etendoerp.go.schemaforge.NeoAttachmentsDownloader;
import com.etendoerp.go.schemaforge.NeoAttachmentsHelper;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * Unit tests for {@link PortalServlet}, the customer portal's session-less API: where the token is
 * read from, which methods are served, rate limiting, caching headers, the single invalid-link
 * answer and the tenant + Business Partner scope of every invoice read.
 *
 * <p>The link service and both rate limiters are field-initialised by the servlet, so they are
 * replaced with {@code mockConstruction}; the one test that needs the real service's verdict on a
 * token drives it through a static mock of {@link PortalLinkDal} instead.
 *
 * @covers com.etendoerp.go.portal.PortalServlet
 */
public class PortalServletTest {

  private static final String CLIENT_ID = "CLIENT-1";
  private static final String BP_ID = "BP-1";
  private static final String INVALID_BODY = "{\"error\":\"This link is no longer valid\"}";

  private MockedStatic<OBContext> obContext;
  private MockedStatic<CompanyLogoResolver> logos;
  private MockedStatic<PortalUsageEvents> events;

  private PortalServlet servlet;
  private PortalLinkService linkService;
  private PortalRateLimiter meLimiter;
  private PortalRateLimiter renewLimiter;

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
      // Field order in PortalServlet: the /me limiter first, then the /renew one.
      meLimiter = limiters.constructed().get(0);
      renewLimiter = limiters.constructed().get(1);
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
    String[][] cases = { { "PUT", "/me" }, { "DELETE", "/me" }, { "PATCH", "/invoices" },
        { "POST", "/me" }, { "POST", "/invoices" }, { "HEAD", "/renew" } };
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
  }

  @Test
  public void everyResponseIsMarkedNoStore() throws Exception {
    when(linkService.renew(any())).thenReturn(PortalLinkService.RenewalOutcome.SENT);
    String[][] cases = { { "GET", "/me" }, { "PUT", "/me" }, { "GET", "/nowhere" },
        { "POST", "/renew" }, { "GET", "/invoices" } };
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
  public void anInvoiceOutsideTheLinksScopeIs404AndNeverReachesTheAttachments()
      throws Exception {
    validLink();
    try (MockedStatic<PortalInvoiceQuery> invoices = mockStatic(PortalInvoiceQuery.class);
        MockedStatic<NeoAttachmentsHelper> attachments = mockStatic(NeoAttachmentsHelper.class)) {
      invoices.when(() -> PortalInvoiceQuery.findInScope(any(), eq("OTHER-TENANTS-INVOICE")))
          .thenReturn(null);
      Call call = new Call();

      servlet.service(request("GET", "/invoices/OTHER-TENANTS-INVOICE/pdf"), call.response);

      assertEquals(HttpServletResponse.SC_NOT_FOUND, call.status());
      ArgumentCaptor<PortalSession> scope = ArgumentCaptor.forClass(PortalSession.class);
      invoices.verify(() -> PortalInvoiceQuery.findInScope(scope.capture(),
          eq("OTHER-TENANTS-INVOICE")));
      assertEquals(CLIENT_ID, scope.getValue().getClientId());
      assertEquals(BP_ID, scope.getValue().getBusinessPartnerId());
      attachments.verifyNoInteractions();
    }
  }

  @Test
  public void anAttachmentOfAnotherTenantIs404AndIsNeverStreamed() throws Exception {
    validLink();
    Invoice invoice = mock(Invoice.class);
    when(invoice.getId()).thenReturn("INV-1");
    Client otherTenant = mock(Client.class);
    when(otherTenant.getId()).thenReturn("CLIENT-2");
    Attachment attachment = mock(Attachment.class);
    when(attachment.getClient()).thenReturn(otherTenant);
    OBDal obDal = mock(OBDal.class);
    when(obDal.get(Attachment.class, "ATT-1")).thenReturn(attachment);
    NeoResponse main = new NeoResponse(200, new JSONObject().put("id", "ATT-1"));

    try (MockedStatic<PortalInvoiceQuery> invoices = mockStatic(PortalInvoiceQuery.class);
        MockedStatic<NeoAttachmentsHelper> attachments = mockStatic(NeoAttachmentsHelper.class);
        MockedStatic<NeoAttachmentsDownloader> downloads =
            mockStatic(NeoAttachmentsDownloader.class);
        MockedStatic<OBDal> obDalStatic = mockStatic(OBDal.class)) {
      obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
      invoices.when(() -> PortalInvoiceQuery.findInScope(any(), eq("INV-1"))).thenReturn(invoice);
      attachments.when(() -> NeoAttachmentsHelper.handleGetMain("C_Invoice", "INV-1"))
          .thenReturn(main);
      Call call = new Call();

      servlet.service(request("GET", "/invoices/INV-1/pdf"), call.response);

      assertEquals(HttpServletResponse.SC_NOT_FOUND, call.status());
      downloads.verifyNoInteractions();
    }
  }

  @Test
  public void theInvoiceListIsReadWithTheValidatedLinksScopeOnly() throws Exception {
    validLink();
    try (MockedStatic<PortalInvoiceQuery> invoices = mockStatic(PortalInvoiceQuery.class)) {
      invoices.when(() -> PortalInvoiceQuery.listAll(any()))
          .thenReturn(Collections.<Invoice>emptyList());
      HttpServletRequest request = request("GET", "/invoices");
      // Nothing a caller sends can widen the scope: there is no parameter for it to come from.
      when(request.getParameter("clientId")).thenReturn("CLIENT-2");
      when(request.getParameter("businessPartnerId")).thenReturn("BP-2");
      Call call = new Call();

      servlet.service(request, call.response);

      assertEquals(HttpServletResponse.SC_OK, call.status());
      ArgumentCaptor<PortalSession> scope = ArgumentCaptor.forClass(PortalSession.class);
      invoices.verify(() -> PortalInvoiceQuery.listAll(scope.capture()));
      assertEquals(CLIENT_ID, scope.getValue().getClientId());
      assertEquals(BP_ID, scope.getValue().getBusinessPartnerId());
    }
  }

  @Test
  public void anExpiredLinkSeesNoInvoices() throws Exception {
    Date sentAt = new Date(0L);
    when(linkService.validate(any())).thenReturn(new PortalLinkService.Validation(
        PortalLinkService.State.EXPIRED, link(sentAt, new Date(1L), true)));
    try (MockedStatic<PortalInvoiceQuery> invoices = mockStatic(PortalInvoiceQuery.class)) {
      Call call = new Call();

      servlet.service(request("GET", "/invoices"), call.response);

      assertEquals(HttpServletResponse.SC_UNAUTHORIZED, call.status());
      assertEquals(INVALID_BODY, call.body.toString());
      invoices.verifyNoInteractions();
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
