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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.LazyInitializationException;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.RejectReason;

import com.etendoerp.go.common.PublicUrlResolver;
import com.etendoerp.go.payment.SystemContext;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.QuotationDecisions;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

/**
 * Unit tests for {@link PortalDecisionService}: accepting or rejecting a quotation from the
 * customer portal, confirmed by email.
 *
 * <p>The decision table is native SQL, so the session is a small stand-in: each native query
 * records its SQL and parameters and answers from the fields below ({@link #decisionRow},
 * {@link #recentRequests}, {@link #otherPending}). The email service is replaced with
 * {@code mockConstruction}, so a test reads which contract was asked to send what.
 *
 * @covers com.etendoerp.go.portal.PortalDecisionService
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class PortalDecisionServiceTest {

  private static final String TOKEN = "decision-token";
  private static final String QUOTATION_ID = "Q-1";
  private static final String ROOT_ID = "ROOT-1";
  private static final String DECISION_ID = "D-1";
  private static final String CLIENT_ID = "CLIENT-1";

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<SystemContext> system;
  private MockedStatic<PortalDecisionEvents> events;
  private MockedStatic<PortalDocumentQuery> documents;
  private MockedStatic<PortalKpiTelemetry> kpi;
  private MockedConstruction<TransactionalEmailService> emails;

  private OBDal obDal;
  private Order quotation;
  private PortalLinkService linkService;
  private PortalAccessChecks accessChecks;
  private PortalSession session;

  /** The status every email the service sends ends in. */
  private String emailStatus = TransactionalEmailService.STATUS_SENT;
  /** The decision row the table holds (by token or by id), or {@code null}. */
  private Object[] decisionRow;
  /** A failure the decision row read throws, or {@code null}. */
  private RuntimeException decisionReadFailure;
  /** Requests made for the quotation inside the cool-down. */
  private long recentRequests;
  /** Other pending requests of the quotation, voided by a confirmation. */
  private List<String> otherPending = new ArrayList<>();
  /** Every native statement run, as {@code [sql, params]}. */
  private final List<Object[]> statements = new ArrayList<>();

  @Before
  public void setUp() {
    obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    Session dbSession = mock(Session.class);
    when(obDal.getSession()).thenReturn(dbSession);
    when(dbSession.createNativeQuery(anyString()))
        .thenAnswer(inv -> nativeQuery(inv.getArgument(0)));
    obContext = mockStatic(OBContext.class);
    system = mockStatic(SystemContext.class);
    system.when(() -> SystemContext.call(anyString(), any()))
        .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    events = mockStatic(PortalDecisionEvents.class);
    documents = mockStatic(PortalDocumentQuery.class);
    kpi = mockStatic(PortalKpiTelemetry.class);
    emails = mockConstruction(TransactionalEmailService.class,
        (service, context) -> when(service.send(anyString(), any(JSONObject.class)))
            .thenAnswer(inv -> emailAnswer()));

    quotation = mock(Order.class);
    when(quotation.getId()).thenReturn(QUOTATION_ID);
    when(quotation.getDocumentStatus()).thenReturn(QuotationDecisions.STATUS_UNDER_EVALUATION);
    when(obDal.get(Order.class, QUOTATION_ID)).thenReturn(quotation);
    documents.when(() -> PortalDocumentQuery.findInScope(any(), eq(PortalDocumentKind.QUOTATION),
        eq(QUOTATION_ID))).thenReturn(quotation);

    linkService = mock(PortalLinkService.class);
    PortalLink root = link("not-an-address, first@customer.test, second@customer.test",
        "cc@customer.test");
    when(linkService.findLink(ROOT_ID)).thenReturn(Optional.of(root));
    PortalLink sessionLink = link("first@customer.test", null);
    when(linkService.findLink("LINK-1")).thenReturn(Optional.of(sessionLink));
    session = new PortalSession(sessionLink);
    // An open customer unless a test closes it (Optional.empty: not blocked, active, plan current).
    accessChecks = mock(PortalAccessChecks.class);
    when(accessChecks.blockReasonOrClosed(anyString(), anyString())).thenReturn(Optional.empty());
  }

  @After
  public void tearDown() {
    emails.close();
    kpi.close();
    documents.close();
    events.close();
    system.close();
    obContext.close();
    obDalStatic.close();
  }

  // ── request ─────────────────────────────────────────────────────────────────

  @Test
  public void aRequestIsRefusedWithoutSendingAnyEmail() {
    // Columns: label, decision, reason code, text typed.
    Object[][] cases = {
        { "invalid decision", "MAYBE", null, null },
        { "reject without reason", "REJECT", null, null },
        { "unknown reason", "REJECT", "CHEAPER", null },
        { "OTHER without text", "REJECT", "OTHER", "   " },
        { "OTHER text too long", "REJECT", "OTHER", repeat('x', 2001) },
    };
    for (Object[] c : cases) {
      PortalDecisionService.RequestResult result = service().request(session, QUOTATION_ID,
          (String) c[1], (String) c[2], (String) c[3]);

      assertEquals(c[0].toString(), PortalDecisionService.RequestOutcome.INVALID_REASON,
          result.getOutcome());
    }
    assertTrue("nothing may be emailed", emails.constructed().isEmpty());
  }

  @Test
  public void aQuotationThatCannotBeDecidedIsBlocked() {
    // Out of scope.
    assertEquals(PortalDecisionService.RequestOutcome.NOT_FOUND,
        service().request(session, "OTHER-BPS-QUOTATION", "ACCEPT", null, null).getOutcome());
    // Not Under evaluation.
    when(quotation.getDocumentStatus()).thenReturn("CO");
    assertBlocked(PortalDecisionService.Blocked.NOT_PENDING);
    // Expired: ValidUntil before today.
    when(quotation.getDocumentStatus()).thenReturn(QuotationDecisions.STATUS_UNDER_EVALUATION);
    when(quotation.getValidUntil()).thenReturn(day(-1));
    assertBlocked(PortalDecisionService.Blocked.EXPIRED);
    // No To on the root send: a CC alone never confirms.
    when(quotation.getValidUntil()).thenReturn(null);
    PortalLink ccOnly = link(null, "cc@customer.test");
    when(linkService.findLink(ROOT_ID)).thenReturn(Optional.of(ccOnly));
    assertBlocked(PortalDecisionService.Blocked.NO_RECIPIENT);

    assertTrue(emails.constructed().isEmpty());
  }

  @Test
  public void aQuotationWithoutValidUntilOrValidTodayIsNotExpired() {
    for (Date validUntil : Arrays.asList(null, day(0), day(5))) {
      when(quotation.getValidUntil()).thenReturn(validUntil);

      assertNull("validUntil " + validUntil, service().blockedReason(session, quotation));
    }
  }

  @Test
  public void theConfirmationGoesToTheFirstValidToInFullAndNeverToCc() throws Exception {
    PortalDecisionService.RequestResult result = service().request(session, QUOTATION_ID,
        "REJECT", "PRICE", null);

    assertEquals(PortalDecisionService.RequestOutcome.SENT, result.getOutcome());
    assertEquals("first@customer.test", result.getEmail());
    assertEquals("first@customer.test", service().confirmationRecipient(ROOT_ID));
    JSONObject command = sentCommand(PortalDecisionService.CONFIRM_CONTRACT);
    assertEquals(QUOTATION_ID, command.getString("recordId"));
    assertEquals("REJECT", command.getString(PortalDecisionService.FIELD_DECISION));
    events.verify(() -> PortalDecisionEvents.requested(session, QUOTATION_ID, "REJECT"));
  }

  @Test
  public void freeTextIsKeptForOtherOnlyAndAcceptCarriesNoReason() throws Exception {
    // Columns: decision, reason, text typed, expected reasonCode, expected reasonText.
    Object[][] cases = {
        { "REJECT", "PRICE", "typed before switching", "PRICE", null },
        { "REJECT", "other", "  muy caro  ", "OTHER", "muy caro" },
        { "ACCEPT", "PRICE", "ignored", null, null },
    };
    for (Object[] c : cases) {
      service().request(session, QUOTATION_ID, (String) c[0], (String) c[1], (String) c[2]);

      JSONObject command = sentCommand(PortalDecisionService.CONFIRM_CONTRACT);
      assertEquals(c[0].toString(), c[3],
          command.optString(PortalDecisionService.FIELD_REASON_CODE, null));
      assertEquals(c[0].toString(), c[4],
          command.optString(PortalDecisionService.FIELD_REASON_TEXT, null));
    }
  }

  @Test
  public void aSecondRequestInsideTheCooldownSendsNothing() {
    recentRequests = 1;
    assertEquals(PortalDecisionService.RequestOutcome.COOLDOWN,
        service().request(session, QUOTATION_ID, "ACCEPT", null, null).getOutcome());
    assertTrue(emails.constructed().isEmpty());

    // The email service's own throttle or duplicate check also reads as the cool-down.
    recentRequests = 0;
    for (String status : Arrays.asList(TransactionalEmailService.STATUS_THROTTLED,
        TransactionalEmailService.STATUS_DUPLICATE)) {
      emailStatus = status;
      assertEquals(status, PortalDecisionService.RequestOutcome.COOLDOWN,
          service().request(session, QUOTATION_ID, "ACCEPT", null, null).getOutcome());
    }
    emailStatus = TransactionalEmailService.STATUS_PROVIDER_FAILED;
    assertEquals(PortalDecisionService.RequestOutcome.FAILED,
        service().request(session, QUOTATION_ID, "ACCEPT", null, null).getOutcome());
  }

  @Test
  public void aRequestWhoseEmailDidNotLeaveDoesNotCountTowardTheCooldown() {
    emailStatus = TransactionalEmailService.STATUS_PROVIDER_FAILED;

    assertEquals(PortalDecisionService.RequestOutcome.FAILED,
        service().request(session, QUOTATION_ID, "ACCEPT", null, null).getOutcome());

    // Its pending row is switched off, and the cool-down only counts active rows.
    Map<String, Object> deactivated = onlyStatement("SET isactive = 'N'");
    assertEquals(QUOTATION_ID, deactivated.get("id"));
    assertTrue(executed("sent_at IS NULL"));
    assertTrue(statements.stream().anyMatch(st -> ((String) st[0]).startsWith("SELECT count(*)")
        && ((String) st[0]).contains("isactive = 'Y'")));
    // The quotation is locked before the cool-down is read, so two clicks cannot both pass it.
    int lock = indexOf("FOR UPDATE");
    int count = indexOf("SELECT count(*)");
    assertTrue("lock " + lock + " before count " + count, lock >= 0 && lock < count);

    // A request whose email left keeps its row: it is what the cool-down counts.
    statements.clear();
    emailStatus = TransactionalEmailService.STATUS_SENT;
    service().request(session, QUOTATION_ID, "ACCEPT", null, null);
    assertFalse(executed("SET isactive = 'N'"));
  }

  // ── mint and view ───────────────────────────────────────────────────────────

  @Test
  public void mintStoresOnlyTheTokensHash() {
    when(quotation.getClient()).thenReturn(mock(Client.class));
    when(quotation.getOrganization()).thenReturn(mock(Organization.class));
    try (MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class);
        MockedStatic<SequenceIdData> ids = mockStatic(SequenceIdData.class)) {
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn("https://app.test/");
      urls.when(() -> PublicUrlResolver.appendPath(anyString(), anyString()))
          .thenAnswer(inv -> inv.<String>getArgument(0) + inv.<String>getArgument(1));
      ids.when(SequenceIdData::getUUID).thenReturn(DECISION_ID);

      PortalDecisionService.MintedDecision minted = service().mint("LINK-1", QUOTATION_ID,
          "REJECT", "PRICE", null, "first@customer.test", "es_ES").get();

      assertEquals(DECISION_ID, minted.getId());
      String token = minted.getUrl().substring("https://app.test/portal/decision/".length());
      Map<String, Object> inserted = onlyStatement("INSERT INTO etgo_portal_decision");
      assertEquals(PortalTokens.hash(token), inserted.get("hash"));
      assertFalse("the raw token is never stored", inserted.containsValue(token));
    }
  }

  @Test
  public void mintWithoutAnAppBaseUrlWritesNothing() {
    try (MockedStatic<PublicUrlResolver> urls = mockStatic(PublicUrlResolver.class)) {
      urls.when(PublicUrlResolver::resolveConfiguredAppBaseUrl).thenReturn(" ");

      assertFalse(service().mint("LINK-1", QUOTATION_ID, "ACCEPT", null, null,
          "first@customer.test", "es_ES").isPresent());
    }
    assertTrue(statements.isEmpty());
  }

  @Test
  public void viewNeverWritesAndAnUnknownTokenIsInvalid() {
    decisionRow = row("REJECT", "PRICE", null, PortalDecisionService.STATUS_PENDING, true);

    assertEquals(PortalDecisionService.State.PENDING, service().view(TOKEN).getState());
    assertEquals(PortalDecisionService.State.INVALID, service().view("unknown").getState());
    // A decision whose email never left is not usable either.
    decisionRow = row("REJECT", "PRICE", null, PortalDecisionService.STATUS_PENDING, false);
    assertEquals(PortalDecisionService.State.INVALID, service().view(TOKEN).getState());

    assertTrue("view runs no write", statements.stream()
        .allMatch(s -> ((String) s[0]).startsWith("SELECT")));
    verify(obDal, never()).commitAndClose();
    verify(quotation, never()).setDocumentStatus(anyString());
  }

  // ── confirm ─────────────────────────────────────────────────────────────────

  @Test
  public void aConfirmedRejectClosesTheQuotationAndNotesTheReasonOnly() throws Exception {
    // Columns: reason, text, existing notes, expected notes.
    Object[][] cases = {
        { "PRICE", null, null, "Rechazado por el cliente desde el portal. Motivo: Precio." },
        { "OTHER", "Llegó tarde", "Nota previa",
            "Nota previa\nRechazado por el cliente desde el portal. Motivo: Otros: Llegó tarde" },
    };
    for (Object[] c : cases) {
      Order rejected = mock(Order.class);
      when(rejected.getDocumentStatus()).thenReturn(QuotationDecisions.STATUS_UNDER_EVALUATION);
      when(rejected.getDescription()).thenReturn((String) c[2]);
      when(obDal.get(Order.class, QUOTATION_ID)).thenReturn(rejected);
      decisionRow = row("REJECT", (String) c[0], (String) c[1],
          PortalDecisionService.STATUS_PENDING, true);

      PortalDecisionService.View view = service().confirm(TOKEN);

      assertEquals(PortalDecisionService.State.CONFIRMED, view.getState());
      assertTrue(view.isAppliedNow());
      ArgumentCaptor<String> notes = ArgumentCaptor.forClass(String.class);
      verify(rejected).setDescription(notes.capture());
      assertEquals(c[3], notes.getValue());
      assertFalse(notes.getValue().contains("null"));
      verify(rejected).setDocumentStatus(QuotationDecisions.STATUS_REJECTED);
      verify(rejected).setProcessed(true);
      // The portal's reason never becomes the tenant's internal reject reason.
      verify(rejected, never()).setRejectReason(any(RejectReason.class));
    }
    // Each applied rejection is handed to the KPI gate, for the decision's tenant and quotation.
    kpi.verify(() -> PortalKpiTelemetry.portalQuotationConfirmed(false, CLIENT_ID, DECISION_ID,
        QUOTATION_ID), times(cases.length));
  }

  @Test
  public void aConfirmedAcceptMarksTheQuotationAccepted() {
    decisionRow = row("ACCEPT", null, null, PortalDecisionService.STATUS_PENDING, true);

    PortalDecisionService.View view = service().confirm(TOKEN);

    assertEquals(PortalDecisionService.State.CONFIRMED, view.getState());
    verify(quotation).setDocumentStatus(QuotationDecisions.STATUS_ACCEPTED);
    verify(quotation).setProcessed(true);
    verify(quotation, never()).setDescription(anyString());
    assertTrue(executed("SET status = 'CONFIRMED'"));
    assertFalse(view.isCustomerClosed());
    kpi.verify(() -> PortalKpiTelemetry.portalQuotationConfirmed(true, CLIENT_ID, DECISION_ID,
        QUOTATION_ID));
  }

  @Test
  public void theFirstConfirmationVoidsTheOtherPendingRequestsAndNoticesTheSeller()
      throws Exception {
    decisionRow = row("ACCEPT", null, null, PortalDecisionService.STATUS_PENDING, true);
    otherPending = new ArrayList<>(Arrays.asList("D-2", "D-3"));

    service().confirm(TOKEN);

    Map<String, Object> voided = onlyStatement("SET status = 'VOIDED'");
    assertEquals(Arrays.asList("D-2", "D-3"), voided.get("ids"));
    events.verify(() -> PortalDecisionEvents.voided(QUOTATION_ID, 2));
    events.verify(() -> PortalDecisionEvents.confirmed(DECISION_ID, QUOTATION_ID, "ACCEPT"));
    assertEquals(DECISION_ID,
        sentCommand(PortalDecisionService.NOTICE_CONTRACT).getString("decisionId"));
  }

  @Test
  public void reconfirmingAnswersConfirmedWithoutApplyingOrNoticingAgain() {
    decisionRow = row("REJECT", "PRICE", null, PortalDecisionService.STATUS_CONFIRMED, true);
    when(quotation.getDocumentStatus()).thenReturn(QuotationDecisions.STATUS_REJECTED);

    PortalDecisionService.View view = service().confirm(TOKEN);

    assertEquals(PortalDecisionService.State.CONFIRMED, view.getState());
    assertFalse(view.isAppliedNow());
    verify(quotation, never()).setDocumentStatus(anyString());
    assertTrue("no second notice", emails.constructed().isEmpty());
    assertFalse(updated());
    assertFalse(view.isCustomerClosed());
    kpi.verifyNoInteractions();
  }

  @Test
  public void aDecisionIsNotAppliedWhenTheQuotationChangedOrExpired() {
    // Columns: status of the quotation now, validUntil, expected state.
    Object[][] cases = {
        { "CO", null, PortalDecisionService.State.NOT_PENDING },
        { QuotationDecisions.STATUS_UNDER_EVALUATION, day(-1),
            PortalDecisionService.State.EXPIRED },
    };
    for (Object[] c : cases) {
      statements.clear();
      when(quotation.getDocumentStatus()).thenReturn((String) c[0]);
      when(quotation.getValidUntil()).thenReturn((Date) c[1]);
      decisionRow = row("ACCEPT", null, null, PortalDecisionService.STATUS_PENDING, true);

      PortalDecisionService.View view = service().confirm(TOKEN);

      assertEquals(c[2], view.getState());
      assertFalse(view.isAppliedNow());
      assertTrue(executed("SET status = 'NOT_APPLIED'"));
      verify(quotation, never()).setProcessed(anyBoolean());
      // Not applied is not closed for the customer: the answer still leads into the portal.
      assertFalse(view.isCustomerClosed());
    }
    assertTrue(emails.constructed().isEmpty());
    kpi.verifyNoInteractions();
  }

  /**
   * Both paths that commit answer after {@code commitAndClose}, with the quotation detached: its
   * lazy currency then throws, which answered 500 to the first confirmation. The summary must be
   * read after the decision is applied and before the session closes.
   */
  @Test
  public void aConfirmationThatCommitsCarriesTheSummaryReadBeforeTheSessionCloses() {
    AtomicBoolean sessionClosed = new AtomicBoolean();
    doAnswer(inv -> {
      sessionClosed.set(true);
      return null;
    }).when(obDal).commitAndClose();
    // Columns: label, status when confirming, expected state, applied now, status in the summary.
    Object[][] cases = {
        { "applied", QuotationDecisions.STATUS_UNDER_EVALUATION,
            PortalDecisionService.State.CONFIRMED, true, QuotationDecisions.STATUS_ACCEPTED },
        { "no longer decidable", "CO", PortalDecisionService.State.NOT_PENDING, false, "CO" },
    };
    for (Object[] c : cases) {
      String label = (String) c[0];
      sessionClosed.set(false);
      Date orderDate = day(-3);
      BigDecimal grandTotal = new BigDecimal("2450.00");
      Order detachable = quotationDetachedWhenClosed(sessionClosed, (String) c[1], orderDate,
          grandTotal);
      when(obDal.get(Order.class, QUOTATION_ID)).thenReturn(detachable);
      decisionRow = row("ACCEPT", null, null, PortalDecisionService.STATUS_PENDING, true);

      PortalDecisionService.View view = service().confirm(TOKEN);

      assertTrue(label + ": the session closed", sessionClosed.get());
      assertEquals(label, c[2], view.getState());
      assertEquals(label, c[3], view.isAppliedNow());
      PortalDecisionService.Summary summary = view.getSummary();
      assertEquals(label, "PR-0042", summary.getDocumentNo());
      assertEquals(label, orderDate, summary.getDate());
      assertEquals(label, grandTotal, summary.getGrandTotal());
      assertEquals(label, "EUR", summary.getCurrency());
      assertEquals(label, c[4], summary.getDocumentStatus());
    }
  }

  @Test
  public void aConfirmForAClosedCustomerAppliesNothing() {
    // A decision token never expires: a customer blocked, inactive or out of plan since asking
    // must not still apply it. Nothing is written; the row stays pending.
    for (String closed : Arrays.asList(PortalAccessChecks.BLOCKED_CUSTOMER,
        PortalAccessChecks.INACTIVE, PortalAccessChecks.PLAN_EXPIRED)) {
      statements.clear();
      when(accessChecks.blockReasonOrClosed(CLIENT_ID, "BP-1")).thenReturn(Optional.of(closed));
      decisionRow = row("ACCEPT", null, null, PortalDecisionService.STATUS_PENDING, true);

      PortalDecisionService.View view = service().confirm(TOKEN);

      assertEquals(closed, PortalDecisionService.State.NOT_PENDING, view.getState());
      assertFalse(closed, view.isAppliedNow());
      assertFalse(closed, updated());
      assertTrue(closed, view.isCustomerClosed());
    }
    // A link that can no longer be read fails closed the same way.
    when(linkService.findLink("LINK-1")).thenReturn(Optional.empty());
    PortalDecisionService.View unreadable = service().confirm(TOKEN);
    assertEquals(PortalDecisionService.State.NOT_PENDING, unreadable.getState());
    assertTrue(unreadable.isCustomerClosed());
    kpi.verifyNoInteractions();

    verify(quotation, never()).setDocumentStatus(anyString());
    verify(quotation, never()).setProcessed(anyBoolean());
    assertTrue("no notice", emails.constructed().isEmpty());
  }

  @Test
  public void anUnknownTokenConfirmsNothing() {
    assertEquals(PortalDecisionService.State.INVALID, service().confirm("unknown").getState());
    assertEquals(PortalDecisionService.State.INVALID, service().confirm(" ").getState());

    assertTrue(statements.stream().noneMatch(s -> ((String) s[0]).contains("FOR UPDATE")));
    verify(quotation, never()).setDocumentStatus(anyString());
    // Nothing was applied, so nothing reaches the KPI gate.
    kpi.verifyNoInteractions();
  }

  // ── markSent (ETP-6209: the decision email KPI) ─────────────────────────────

  /** With the failure case below: the KPI follows a committed stamp, never a rolled-back one. */
  @Test
  public void markSentStampsTheDecisionCommitsAndCountsItsEmail() {
    // Columns: decision, accepted as the KPI receives it.
    Object[][] cases = { { "ACCEPT", true }, { "REJECT", false } };
    for (Object[] c : cases) {
      decisionRow = row((String) c[0], null, null, PortalDecisionService.STATUS_PENDING, false);
      boolean accepted = (Boolean) c[1];

      service().markSent(DECISION_ID);

      kpi.verify(() -> PortalKpiTelemetry.decisionEmailSent(accepted, CLIENT_ID));
    }
    verify(obDal, times(2)).commitAndClose();
    verify(obDal, never()).rollbackAndClose();
    assertTrue("the decision is stamped sent", statements.stream().anyMatch(
        s -> ((String) s[0]).startsWith("UPDATE etgo_portal_decision SET sent_at")
            && DECISION_ID.equals(((Map<?, ?>) s[1]).get("id"))));
  }

  @Test
  public void aMarkSentThatFailsToCommitRollsBackAndCountsNothing() {
    decisionRow = row("ACCEPT", null, null, PortalDecisionService.STATUS_PENDING, false);
    doThrow(new IllegalStateException("could not commit")).when(obDal).commitAndClose();

    service().markSent(DECISION_ID);

    verify(obDal).rollbackAndClose();
    kpi.verify(() -> PortalKpiTelemetry.decisionEmailSent(anyBoolean(), any()), never());
  }

  /**
   * The decision is read for the KPI only after the stamp committed: whether that read fails or
   * finds no row, the stamp stays committed, nothing is counted and nothing escapes.
   */
  @Test
  public void aKpiReadThatFailsOrFindsNothingNeverUndoesTheCommittedStamp() {
    // Columns: label, decision row, failure of the read.
    Object[][] cases = {
        { "the read throws", row("ACCEPT", null, null, PortalDecisionService.STATUS_PENDING, false),
            new IllegalStateException("session closed") },
        { "no decision row", null, null },
    };
    for (Object[] c : cases) {
      decisionRow = (Object[]) c[1];
      decisionReadFailure = (RuntimeException) c[2];
      statements.clear();
      List<Integer> statementsAtCommit = new ArrayList<>();
      doAnswer(inv -> {
        statementsAtCommit.add(statements.size());
        return null;
      }).when(obDal).commitAndClose();

      service().markSent(DECISION_ID);

      String label = (String) c[0];
      assertEquals(label, 2, statements.size());
      assertTrue(label, ((String) statements.get(0)[0]).startsWith(
          "UPDATE etgo_portal_decision SET sent_at"));
      assertTrue(label, ((String) statements.get(1)[0]).startsWith(
          "SELECT etgo_portal_decision_id"));
      assertEquals(label + ": committed once, after the UPDATE and before the read",
          Collections.singletonList(1), statementsAtCommit);
    }
    kpi.verify(() -> PortalKpiTelemetry.decisionEmailSent(anyBoolean(), any()), never());
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private PortalDecisionService service() {
    return new PortalDecisionService(linkService, accessChecks);
  }

  /**
   * A quotation whose status follows what the confirmation writes, and whose lazy currency stops
   * loading once the session has closed, as a detached Hibernate proxy does.
   */
  private static Order quotationDetachedWhenClosed(AtomicBoolean sessionClosed, String status,
      Date orderDate, BigDecimal grandTotal) {
    Currency currency = mock(Currency.class);
    when(currency.getISOCode()).thenAnswer(inv -> {
      if (sessionClosed.get()) {
        throw new LazyInitializationException("could not initialize proxy - no Session");
      }
      return "EUR";
    });
    AtomicReference<String> currentStatus = new AtomicReference<>(status);
    Order detachable = mock(Order.class);
    when(detachable.getDocumentStatus()).thenAnswer(inv -> currentStatus.get());
    doAnswer(inv -> {
      currentStatus.set(inv.getArgument(0));
      return null;
    }).when(detachable).setDocumentStatus(anyString());
    when(detachable.getDocumentNo()).thenReturn("PR-0042");
    when(detachable.getOrderDate()).thenReturn(orderDate);
    when(detachable.getGrandTotalAmount()).thenReturn(grandTotal);
    when(detachable.getCurrency()).thenReturn(currency);
    return detachable;
  }

  private void assertBlocked(PortalDecisionService.Blocked expected) {
    PortalDecisionService.RequestResult result = service().request(session, QUOTATION_ID,
        "ACCEPT", null, null);
    assertEquals(PortalDecisionService.RequestOutcome.BLOCKED, result.getOutcome());
    assertEquals(expected, result.getBlocked());
  }

  private JSONObject sentCommand(String contract) {
    assertFalse("no email was sent", emails.constructed().isEmpty());
    TransactionalEmailService sender = emails.constructed().get(emails.constructed().size() - 1);
    ArgumentCaptor<JSONObject> command = ArgumentCaptor.forClass(JSONObject.class);
    verify(sender).send(eq(contract), command.capture());
    return command.getValue();
  }

  private NeoResponse emailAnswer() throws Exception {
    return new NeoResponse(200, new JSONObject().put("response",
        new JSONObject().put("data", new JSONObject().put("status", emailStatus))));
  }

  /** A native query that records its statement and answers from the fixture's fields. */
  private NativeQuery nativeQuery(String sql) {
    Map<String, Object> params = new HashMap<>();
    statements.add(new Object[] { sql, params });
    return mock(NativeQuery.class, inv -> {
      String method = inv.getMethod().getName();
      if ("setParameter".equals(method) || "setParameterList".equals(method)) {
        params.put(inv.getArgument(0), inv.getArgument(1));
        return inv.getMock();
      }
      if ("list".equals(method)) {
        return rows(sql, params);
      }
      if ("executeUpdate".equals(method)) {
        return 1;
      }
      return null;
    });
  }

  private List<?> rows(String sql, Map<String, Object> params) {
    if (sql.contains("status = 'PENDING' AND")) {
      return otherPending;
    }
    if (sql.startsWith("SELECT count(*)")) {
      return Collections.singletonList(recentRequests);
    }
    if (sql.startsWith("SELECT etgo_portal_decision_id, ad_client_id")
        && decisionReadFailure != null) {
      throw decisionReadFailure;
    }
    if (sql.startsWith("SELECT etgo_portal_decision_id, ad_client_id") && decisionRow != null) {
      boolean byToken = params.containsKey("hash");
      boolean matches = byToken ? PortalTokens.hash(TOKEN).equals(params.get("hash"))
          : DECISION_ID.equals(params.get("id"));
      return matches ? Collections.singletonList(decisionRow) : Collections.emptyList();
    }
    return Collections.emptyList();
  }

  private Map<String, Object> onlyStatement(String fragment) {
    List<Object[]> matching = new ArrayList<>();
    for (Object[] statement : statements) {
      if (((String) statement[0]).contains(fragment)) {
        matching.add(statement);
      }
    }
    assertEquals(fragment, 1, matching.size());
    return (Map<String, Object>) matching.get(0)[1];
  }

  private int indexOf(String fragment) {
    for (int index = 0; index < statements.size(); index++) {
      if (((String) statements.get(index)[0]).contains(fragment)) {
        return index;
      }
    }
    return -1;
  }

  private boolean executed(String fragment) {
    return statements.stream().anyMatch(s -> ((String) s[0]).contains(fragment));
  }

  /** Whether any UPDATE statement ran (a {@code SELECT ... FOR UPDATE} lock is not one). */
  private boolean updated() {
    return statements.stream().anyMatch(s -> ((String) s[0]).trim().startsWith("UPDATE"));
  }

  private static Object[] row(String decision, String reasonCode, String reasonText,
      String status, boolean sent) {
    return new Object[] { DECISION_ID, CLIENT_ID, QUOTATION_ID, "LINK-1", decision, reasonCode,
        reasonText, "first@customer.test", "es_ES", status, sent ? new Date() : null };
  }

  private static PortalLink link(String to, String cc) {
    return new PortalLink("LINK-1", CLIENT_ID, "Acme", "ORG-1", "BP-1", "Customer SL",
        PortalDocumentKind.QUOTATION, QUOTATION_ID, PortalLink.ORIGIN_SEND, null, ROOT_ID,
        "sales-quotation-send", to, cc, "es_ES", new Date(0L), new Date(1L), true, false);
  }

  private static Date day(int offsetFromToday) {
    return Date.from(LocalDate.now().plusDays(offsetFromToday).atStartOfDay(ZoneId.systemDefault())
        .toInstant());
  }

  private static String repeat(char character, int length) {
    StringBuilder builder = new StringBuilder(length);
    for (int index = 0; index < length; index++) {
      builder.append(character);
    }
    return builder.toString();
  }
}
