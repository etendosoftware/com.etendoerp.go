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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

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
import org.openbravo.model.ad.utility.Attachment;

import com.etendoerp.go.common.CompanyLogoResolver;
import com.etendoerp.go.schemaforge.AnonymousAttachmentStreamer;

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
  private static final String DECISION_TOKEN = "dec-tok";
  private static final String LINK_ID = "LINK-1";
  private static final String QUOTATION_ID = "Q-1";
  private static final String INVALID_BODY = "{\"error\":\"This link is no longer valid\"}";
  private static final String[] DATA_PATHS = { "/home", "/search", "/documents",
      "/documents/INVOICE/INV-1", "/documents/INVOICE/INV-1/pdf" };

  private MockedStatic<OBContext> obContext;
  private MockedStatic<CompanyLogoResolver> logos;
  private MockedStatic<PortalUsageEvents> events;
  private MockedStatic<PortalKpiTelemetry> kpi;

  private PortalServlet servlet;
  private PortalLinkService linkService;
  private PortalDecisionService decisionService;
  private MockedStatic<PortalPrintable> printable;
  private PortalRateLimiter meLimiter;
  private PortalRateLimiter renewLimiter;
  private PortalRateLimiter dataLimiter;

  @Before
  public void setUp() {
    obContext = mockStatic(OBContext.class);
    logos = mockStatic(CompanyLogoResolver.class);
    events = mockStatic(PortalUsageEvents.class);
    kpi = mockStatic(PortalKpiTelemetry.class);
    // No PDF is rendered unless a test says so, and no stored one is stale.
    printable = mockStatic(PortalPrintable.class);
    try (MockedConstruction<PortalLinkService> services =
        mockConstruction(PortalLinkService.class);
        MockedConstruction<PortalDecisionService> decisions =
            mockConstruction(PortalDecisionService.class);
        MockedConstruction<PortalRateLimiter> limiters = allowingLimiters()) {
      servlet = new PortalServlet();
      linkService = services.constructed().get(0);
      decisionService = decisions.constructed().get(0);
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
    printable.close();
    kpi.close();
    events.close();
    logos.close();
    obContext.close();
  }

  @Test
  public void tokenIsReadFromThePortalHeaderThenTheMePathThenABearerHeader() throws Exception {
    // Columns: X-Portal-Token, path, Authorization, token the service is asked about.
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
        { "HEAD", "/renew" }, { "POST", "/quotations/Q-1" }, { "PUT", "/decisions/confirm" } };
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
          any(), any(), any())).thenReturn(page);
      HttpServletRequest request = request("GET", "/documents");
      when(request.getParameter("type")).thenReturn("invoice");
      when(request.getParameter("group")).thenReturn("pending");
      // Nothing a caller sends can widen the scope: there is no parameter for it to come from.
      when(request.getParameter("clientId")).thenReturn("CLIENT-2");
      when(request.getParameter("businessPartnerId")).thenReturn("BP-2");
      Call call = new Call();

      servlet.service(request, call.response);

      assertEquals(HttpServletResponse.SC_OK, call.status());
      ArgumentCaptor<PortalSession> scope = ArgumentCaptor.forClass(PortalSession.class);
      endpoint.verify(() -> PortalDocumentsEndpoint.list(scope.capture(), eq("invoice"),
          eq("pending"), any(), any(), any(), any(), any(), any()));
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
        MockedStatic<AnonymousAttachmentStreamer> attachments =
            mockStatic(AnonymousAttachmentStreamer.class)) {
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
  public void aPdfIsCountedAsADownloadOnlyWhenItsFileWasServed() throws Exception {
    validLink();
    BaseOBObject invoice = mock(BaseOBObject.class);
    Attachment attachment = mock(Attachment.class);
    // Columns: the tenant's main attachment (null: none, or another tenant's), streamed, counted.
    Object[][] cases = { { null, false, false }, { attachment, false, false },
        { attachment, true, true } };
    for (Object[] c : cases) {
      try (MockedStatic<PortalDocumentQuery> documents = mockStatic(PortalDocumentQuery.class);
          MockedStatic<PortalDocumentsEndpoint> endpoint =
              mockStatic(PortalDocumentsEndpoint.class);
          MockedStatic<AnonymousAttachmentStreamer> streamer =
              mockStatic(AnonymousAttachmentStreamer.class)) {
        events.clearInvocations();
        kpi.clearInvocations();
        endpoint.when(() -> PortalDocumentsEndpoint.kindOf(any())).thenCallRealMethod();
        documents.when(() -> PortalDocumentQuery.findInScope(any(),
            eq(PortalDocumentKind.INVOICE), eq("INV-1"))).thenReturn(invoice);
        endpoint.when(() -> PortalDocumentsEndpoint.mainAttachment(any(),
            eq(PortalDocumentKind.INVOICE), eq("INV-1"))).thenReturn((Attachment) c[0]);
        streamer.when(() -> AnonymousAttachmentStreamer.stream(any(), any(), anyString()))
            .thenReturn((Boolean) c[1]);
        Call call = new Call();

        servlet.service(request("GET", "/documents/INVOICE/INV-1/pdf"), call.response);

        if (c[0] == null) {
          assertEquals(HttpServletResponse.SC_NOT_FOUND, call.status());
          streamer.verifyNoInteractions();
        } else {
          streamer.verify(() -> AnonymousAttachmentStreamer.stream(eq(attachment),
              eq(call.response), anyString()));
        }
        events.verify(() -> PortalUsageEvents.downloaded(any(), eq("INV-1")),
            times((Boolean) c[2] ? 1 : 0));
        // The first-download KPI is offered the same served files, with the validated scope.
        kpi.verify(() -> PortalKpiTelemetry.documentDownloaded(any(PortalSession.class),
            eq(PortalDocumentKind.INVOICE), eq("INV-1")), times((Boolean) c[2] ? 1 : 0));
      }
    }
  }

  @Test
  public void aPdfWithoutAStoredFileIsRenderedAndAStaleOneRenderedAgain() throws Exception {
    validLink();
    BaseOBObject quotation = mock(BaseOBObject.class);
    Attachment stored = mock(Attachment.class);
    Attachment rendered = mock(Attachment.class);
    // Columns: stored main attachment, stored one is stale, what a render produces, file served.
    Object[][] cases = {
        { stored, false, rendered, stored },
        { stored, true, rendered, rendered },
        { stored, true, null, stored },
        { null, false, rendered, rendered },
        { null, false, null, null },
    };
    for (Object[] c : cases) {
      try (MockedStatic<PortalDocumentQuery> documents = mockStatic(PortalDocumentQuery.class);
          MockedStatic<PortalDocumentsEndpoint> endpoint =
              mockStatic(PortalDocumentsEndpoint.class);
          MockedStatic<AnonymousAttachmentStreamer> streamer =
              mockStatic(AnonymousAttachmentStreamer.class)) {
        printable.reset();
        endpoint.when(() -> PortalDocumentsEndpoint.kindOf(any())).thenCallRealMethod();
        documents.when(() -> PortalDocumentQuery.findInScope(any(),
            eq(PortalDocumentKind.QUOTATION), eq(QUOTATION_ID))).thenReturn(quotation);
        endpoint.when(() -> PortalDocumentsEndpoint.mainAttachment(any(),
            eq(PortalDocumentKind.QUOTATION), eq(QUOTATION_ID))).thenReturn((Attachment) c[0]);
        printable.when(() -> PortalPrintable.isStale(PortalDocumentKind.QUOTATION, QUOTATION_ID))
            .thenReturn((Boolean) c[1]);
        printable.when(() -> PortalPrintable.renderAndFind(any(),
            eq(PortalDocumentKind.QUOTATION), eq(quotation))).thenReturn((Attachment) c[2]);
        streamer.when(() -> AnonymousAttachmentStreamer.stream(any(), any(), anyString()))
            .thenReturn(true);
        Call call = new Call();

        servlet.service(request("GET", "/documents/QUOTATION/Q-1/pdf"), call.response);

        String label = Arrays.toString(c);
        if (c[3] == null) {
          assertEquals(label, HttpServletResponse.SC_NOT_FOUND, call.status());
          streamer.verifyNoInteractions();
        } else {
          streamer.verify(() -> AnonymousAttachmentStreamer.stream(eq((Attachment) c[3]),
              eq(call.response), anyString()));
        }
        // A fresh stored file is served as it is: no render.
        boolean renders = c[0] == null || (Boolean) c[1];
        printable.verify(() -> PortalPrintable.renderAndFind(any(), any(), any()),
            times(renders ? 1 : 0));
      }
    }
  }

  @Test
  public void searchReadsEveryKindWithTheValidatedLinksScope() throws Exception {
    validLink();
    try (MockedStatic<PortalDocumentsEndpoint> endpoint =
        mockStatic(PortalDocumentsEndpoint.class)) {
      JSONObject found = new JSONObject().put("documents", new org.codehaus.jettison.json
          .JSONArray());
      endpoint.when(() -> PortalDocumentsEndpoint.search(any(), any())).thenReturn(found);
      HttpServletRequest request = request("GET", "/search");
      when(request.getParameter("q")).thenReturn("F-001");
      Call call = new Call();

      servlet.service(request, call.response);

      assertEquals(HttpServletResponse.SC_OK, call.status());
      ArgumentCaptor<PortalSession> scope = ArgumentCaptor.forClass(PortalSession.class);
      endpoint.verify(() -> PortalDocumentsEndpoint.search(scope.capture(), eq("F-001")));
      assertEquals(CLIENT_ID, scope.getValue().getClientId());
      assertEquals(BP_ID, scope.getValue().getBusinessPartnerId());
    }
  }

  // ── Part C: quotation decisions ─────────────────────────────────────────────

  @Test
  public void textFieldReadsAbsentNullAndBlankAsNoValue() throws Exception {
    JSONObject body = new JSONObject("{\"nul\": null, \"empty\": \"\", \"blank\": \"   \","
        + " \"text\": \"  Precio alto \"}");

    for (String key : Arrays.asList("absent", "nul", "empty", "blank")) {
      assertEquals(key, null, PortalServlet.textField(body, key));
    }
    assertEquals("Precio alto", PortalServlet.textField(body, "text"));
    assertEquals(null, PortalServlet.textField(null, "text"));
  }

  @Test
  public void aDecisionRequestAnswersOneStatusPerOutcome() throws Exception {
    validLink();
    // Columns: outcome, blocked reason, HTTP status, error code (null: the "sent" body).
    Object[][] cases = {
        { PortalDecisionService.RequestOutcome.SENT, null, 200, null },
        { PortalDecisionService.RequestOutcome.NOT_FOUND, null, 404, "Not found" },
        { PortalDecisionService.RequestOutcome.BLOCKED, PortalDecisionService.Blocked.NOT_PENDING,
            409, "not_pending" },
        { PortalDecisionService.RequestOutcome.BLOCKED, PortalDecisionService.Blocked.EXPIRED,
            409, "expired" },
        { PortalDecisionService.RequestOutcome.ACCEPT_UNAVAILABLE, null, 409,
            "accept_unavailable" },
        { PortalDecisionService.RequestOutcome.INVALID_REASON, null, 422, "invalid_reason" },
        { PortalDecisionService.RequestOutcome.COOLDOWN, null, 429, "cooldown" },
        { PortalDecisionService.RequestOutcome.FAILED, null, 503,
            "The portal is temporarily unavailable" },
    };
    for (Object[] c : cases) {
      PortalDecisionService.RequestResult result = new PortalDecisionService.RequestResult(
          (PortalDecisionService.RequestOutcome) c[0], (PortalDecisionService.Blocked) c[1],
          "ju\u2022\u2022\u2022@example.com");
      when(decisionService.request(any(), any(), any(), any(), any())).thenReturn(result);
      Call call = new Call();

      servlet.service(decisionRequest("{\"decision\": \"reject\", \"reasonCode\": \"OTHER\","
          + " \"reasonText\": null}"), call.response);

      assertEquals(c[0].toString(), (int) (Integer) c[2], call.status());
      JSONObject body = new JSONObject(call.body.toString());
      if (c[3] == null) {
        assertEquals("sent", body.getString("state"));
        assertEquals("ju\u2022\u2022\u2022@example.com", body.getString("maskedEmail"));
      } else {
        assertEquals(c[3], body.getString("error"));
      }
    }
    // The quotation id comes from the path; a JSON null reason text reaches the service as null.
    ArgumentCaptor<PortalSession> scope = ArgumentCaptor.forClass(PortalSession.class);
    verify(decisionService, times(cases.length)).request(scope.capture(), eq(QUOTATION_ID),
        eq("reject"), eq("OTHER"), eq(null));
    assertEquals(CLIENT_ID, scope.getValue().getClientId());
  }

  @Test
  public void aDecisionRequestWithANotValidLinkIs401AndAsksNothing() throws Exception {
    Call call = new Call();

    servlet.service(decisionRequest("{\"decision\": \"accept\"}"), call.response);

    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, call.status());
    assertEquals(INVALID_BODY, call.body.toString());
    verifyNoInteractions(decisionService);
  }

  @Test
  public void decisionViewAndConfirmReadTheDecisionHeaderAndAnswerItsState() throws Exception {
    PortalDecisionService.Decision decision = new PortalDecisionService.Decision();
    decision.linkId = LINK_ID;
    decision.action = "reject";
    decision.reasonCode = "OTHER";
    decision.reasonText = "Plazo";
    org.openbravo.model.common.order.Order quotation =
        mock(org.openbravo.model.common.order.Order.class);
    when(quotation.getDocumentNo()).thenReturn("PR-0042");
    when(linkService.findLink(LINK_ID)).thenReturn(Optional.of(link(new Date(0L),
        new Date(1L), true)));
    when(decisionService.view("dec-tok")).thenReturn(new PortalDecisionService.View(
        PortalDecisionService.State.PENDING, decision, quotation));
    when(decisionService.confirm("dec-tok")).thenReturn(new PortalDecisionService.View(
        PortalDecisionService.State.CONFIRMED, decision, quotation, true));

    Call view = new Call();
    servlet.service(decisionCall("GET", "/decisions/view", "dec-tok"), view.response);
    Call confirm = new Call();
    servlet.service(decisionCall("POST", "/decisions/confirm", "dec-tok"), confirm.response);

    JSONObject viewed = new JSONObject(view.body.toString());
    assertEquals("pending", viewed.getString("state"));
    assertFalse(viewed.getBoolean("appliedNow"));
    JSONObject confirmed = new JSONObject(confirm.body.toString());
    assertEquals("confirmed", confirmed.getString("state"));
    assertTrue(confirmed.getBoolean("appliedNow"));
    assertEquals("reject", confirmed.getString("decision"));
    assertEquals("OTHER", confirmed.getString("reasonCode"));
    assertEquals("Plazo", confirmed.getString("reasonText"));
    assertEquals("PR-0042", confirmed.getString("documentNo"));
    assertEquals("Acme", confirmed.getString("tenantName"));
    // The portal token plays no part: the decision token travels in its own header.
    verify(linkService, never()).validate(any());
  }

  @Test
  public void onlyAConfirmThatLetsTheCustomerInCarriesThePortalToken() throws Exception {
    PortalDecisionEntry entry = mintingEntry("raw-portal-token");
    PortalDecisionService.Decision decision = quotationDecision();
    PortalDecisionService.View confirmed = new PortalDecisionService.View(
        PortalDecisionService.State.CONFIRMED, decision, null, true);
    when(decisionService.view(DECISION_TOKEN)).thenReturn(confirmed);
    // Columns: what confirm answers, whether the answer leads into the portal.
    Object[][] cases = {
        { confirmed, true },
        { PortalDecisionService.View.closedForCustomer(decision, null), false },
    };
    for (Object[] c : cases) {
      when(decisionService.confirm(DECISION_TOKEN)).thenReturn((PortalDecisionService.View) c[0]);
      Call confirm = new Call();

      servlet.service(decisionCall("POST", "/decisions/confirm", DECISION_TOKEN),
          confirm.response);

      JSONObject body = new JSONObject(confirm.body.toString());
      if ((Boolean) c[1]) {
        assertEquals("raw-portal-token", body.getString(PortalDecisionEntry.FIELD_PORTAL_TOKEN));
        assertEquals(QUOTATION_ID, body.getString(PortalDecisionEntry.FIELD_QUOTATION_ID));
      } else {
        assertFalse(body.has(PortalDecisionEntry.FIELD_PORTAL_TOKEN));
        assertFalse(body.has(PortalDecisionEntry.FIELD_QUOTATION_ID));
      }
    }
    verify(entry, times(1)).mint(LINK_ID, QUOTATION_ID);

    // The view (a GET, whose URL a server may log) never carries a token, nor mints one.
    Call view = new Call();
    servlet.service(decisionCall("GET", "/decisions/view", DECISION_TOKEN), view.response);
    JSONObject viewed = new JSONObject(view.body.toString());
    assertNull(viewed.optString(PortalDecisionEntry.FIELD_PORTAL_TOKEN, null));
    assertFalse(viewed.has(PortalDecisionEntry.FIELD_QUOTATION_ID));
    verify(entry, times(1)).mint(anyString(), anyString());
  }

  @Test
  public void anUnknownDecisionTokenGetsTheInvalidLinkAnswer() throws Exception {
    when(decisionService.view(any())).thenReturn(new PortalDecisionService.View(
        PortalDecisionService.State.INVALID, null, null));
    when(decisionService.confirm(any())).thenReturn(new PortalDecisionService.View(
        PortalDecisionService.State.INVALID, null, null));

    for (String[] route : new String[][] { { "GET", "/decisions/view" },
        { "POST", "/decisions/confirm" } }) {
      Call call = new Call();

      servlet.service(decisionCall(route[0], route[1], "nope"), call.response);

      assertEquals(route[1], HttpServletResponse.SC_UNAUTHORIZED, call.status());
      assertEquals(INVALID_BODY, call.body.toString());
    }
  }

  @Test
  public void decisionRoutesAreRateLimitedByTheirOwnCounters() throws Exception {
    validLink();
    when(meLimiter.tryAcquire(anyString())).thenReturn(false);
    when(renewLimiter.tryAcquire(anyString())).thenReturn(false);
    when(dataLimiter.tryAcquire(anyString())).thenReturn(false);

    Call view = new Call();
    servlet.service(decisionCall("GET", "/decisions/view", "t"), view.response);
    Call confirm = new Call();
    servlet.service(decisionCall("POST", "/decisions/confirm", "t"), confirm.response);
    Call ask = new Call();
    servlet.service(decisionRequest("{}"), ask.response);

    assertEquals(429, view.status());
    assertEquals(429, confirm.status());
    assertEquals(429, ask.status());
    verifyNoInteractions(decisionService);
  }

  // ── /me ─────────────────────────────────────────────────────────────────────

  @Test
  public void meNamesTheEntryDocumentOnlyWhenItIsStillInScope() throws Exception {
    validLink();
    BaseOBObject visible = mock(BaseOBObject.class);
    // Columns: the entry document in scope?, expected entryDocument id or null.
    for (boolean inScope : new boolean[] { true, false }) {
      try (MockedStatic<PortalDocumentQuery> documents = mockStatic(PortalDocumentQuery.class)) {
        documents.when(() -> PortalDocumentQuery.findInScope(any(),
            eq(PortalDocumentKind.INVOICE), eq("INV-1"))).thenReturn(inScope ? visible : null);
        Call call = new Call();

        servlet.service(request("GET", "/me"), call.response);

        JSONObject body = new JSONObject(call.body.toString());
        assertEquals("valid", body.getString("state"));
        if (inScope) {
          assertEquals("INV-1", body.getJSONObject("entryDocument").getString("id"));
          assertEquals("INVOICE", body.getJSONObject("entryDocument").getString("kind"));
          assertFalse(body.has("entryKind"));
        } else {
          // Hidden since it was sent (voided to a hidden status, or out of scope): the page
          // opens the kind's list instead, and the record is not named.
          assertTrue(body.isNull("entryDocument"));
          assertEquals("INVOICE", body.getString("entryKind"));
        }
      }
    }
  }

  @Test
  public void theLogoIsTheIssuingOrganizationsOnMeAndOnLogo() throws Exception {
    validLink();
    when(linkService.findSentLink(any())).thenReturn(Optional.of(link(new Date(0L),
        new Date(System.currentTimeMillis() + 3_600_000L), true)));
    try (MockedStatic<PortalDocumentQuery> documents = mockStatic(PortalDocumentQuery.class)) {
      servlet.service(request("GET", "/me"), new Call().response);
      servlet.service(request("GET", "/logo"), new Call().response);
    }

    logos.verify(() -> CompanyLogoResolver.resolve(CLIENT_ID, "ORG-1"), times(2));
    logos.verify(() -> CompanyLogoResolver.resolve(any(), eq(null)), never());
  }

  @Test
  public void openedIsRecordedOncePerValidMeAndNeverOtherwise() throws Exception {
    Date future = new Date(System.currentTimeMillis() + 3_600_000L);
    PortalLink valid = link(new Date(0L), future, true);
    List<PortalLinkService.Validation> answers = Arrays.asList(
        new PortalLinkService.Validation(PortalLinkService.State.VALID, valid),
        new PortalLinkService.Validation(PortalLinkService.State.EXPIRED,
            link(new Date(0L), new Date(1L), true)),
        new PortalLinkService.Validation(PortalLinkService.State.UNAVAILABLE, valid),
        new PortalLinkService.Validation(PortalLinkService.State.INVALID, null));
    try (MockedStatic<PortalDocumentQuery> documents = mockStatic(PortalDocumentQuery.class)) {
      for (PortalLinkService.Validation answer : answers) {
        when(linkService.validate(any())).thenReturn(answer);

        servlet.service(request("GET", "/me"), new Call().response);
      }
    }

    events.verify(() -> PortalUsageEvents.opened(any()));
    events.verify(() -> PortalUsageEvents.opened(valid));
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  /** Replaces the servlet's decision entry with one whose mint answers {@code token}. */
  private PortalDecisionEntry mintingEntry(String token) throws Exception {
    PortalDecisionEntry entry = spy(new PortalDecisionEntry(linkService));
    doReturn(token).when(entry).mint(anyString(), anyString());
    Field field = PortalServlet.class.getDeclaredField("decisionEntry");
    field.setAccessible(true);
    field.set(servlet, entry);
    return entry;
  }

  private static PortalDecisionService.Decision quotationDecision() {
    PortalDecisionService.Decision decision = new PortalDecisionService.Decision();
    decision.linkId = LINK_ID;
    decision.quotationId = QUOTATION_ID;
    decision.action = "ACCEPT";
    return decision;
  }

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
    return new PortalLink(LINK_ID, CLIENT_ID, "Acme", "ORG-1", BP_ID, "Customer SL",
        PortalDocumentKind.INVOICE, "INV-1", PortalLink.ORIGIN_SEND, null, LINK_ID,
        "sales-invoice-send", "a@customer.test", null, "es_ES", sentAt, expiresAt, active, false);
  }

  private static HttpServletRequest decisionRequest(String json) throws IOException {
    HttpServletRequest request = request("POST", "/quotations/Q-1/decision");
    when(request.getReader()).thenReturn(new java.io.BufferedReader(new java.io.StringReader(json)));
    return request;
  }

  private static HttpServletRequest decisionCall(String method, String path, String token) {
    HttpServletRequest request = request(method, path);
    when(request.getHeader(PortalServlet.HEADER_DECISION_TOKEN)).thenReturn(token);
    return request;
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
