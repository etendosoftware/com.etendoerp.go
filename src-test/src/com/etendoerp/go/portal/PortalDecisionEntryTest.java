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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.order.Order;

/**
 * Unit tests for {@link PortalDecisionEntry}: the decision landing's answer and the portal link it
 * mints so the customer enters the portal on the quotation.
 *
 * <p>The DAL boundary ({@link PortalLinkDal}, {@code OBDal}, {@code SessionHandler}) is mocked
 * statically and time comes from a fixed {@link Clock}, so the minted row can be read back field
 * by field.
 *
 * @covers com.etendoerp.go.portal.PortalDecisionEntry
 */
public class PortalDecisionEntryTest {

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
  private static final Duration TTL = Duration.ofHours(48);
  private static final String DECISION_LINK_ID = "LINK-1";
  private static final String QUOTATION_ID = "Q-1";
  private static final String NEW_LINK_ID = "NEW-LINK";
  private static final String CLIENT_ID = "CLIENT-1";
  private static final String ORG_ID = "ORG-1";
  private static final String BP_ID = "BP-1";
  private static final String TOKEN = "raw-portal-token";

  private MockedStatic<PortalLinkDal> dal;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<SessionHandler> sessionHandlerStatic;
  private MockedStatic<SequenceIdData> ids;
  private MockedStatic<PortalLinkConfig> config;

  private OBDal obDal;
  private SessionHandler sessionHandler;
  private PortalLinkService linkService;
  private BaseOBObject newRow;
  private Client client;
  private Organization organization;
  private BusinessPartner businessPartner;

  @Before
  public void setUp() {
    dal = mockStatic(PortalLinkDal.class);
    obContext = mockStatic(OBContext.class);
    obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    sessionHandler = mock(SessionHandler.class);
    sessionHandlerStatic = mockStatic(SessionHandler.class);
    sessionHandlerStatic.when(SessionHandler::getInstance).thenReturn(sessionHandler);
    ids = mockStatic(SequenceIdData.class);
    ids.when(SequenceIdData::getUUID).thenReturn(NEW_LINK_ID);
    config = mockStatic(PortalLinkConfig.class);
    config.when(PortalLinkConfig::ttl).thenReturn(TTL);

    client = mock(Client.class);
    organization = mock(Organization.class);
    businessPartner = mock(BusinessPartner.class);
    when(obDal.get(Client.class, CLIENT_ID)).thenReturn(client);
    when(obDal.get(Organization.class, ORG_ID)).thenReturn(organization);
    when(obDal.get(BusinessPartner.class, BP_ID)).thenReturn(businessPartner);
    newRow = mock(BaseOBObject.class);
    dal.when(() -> PortalLinkDal.findById(NEW_LINK_ID)).thenReturn(newRow);

    linkService = mock(PortalLinkService.class);
    when(linkService.findLink(DECISION_LINK_ID))
        .thenReturn(Optional.of(decisionLink("ROOT-1")));
  }

  @After
  public void tearDown() {
    config.close();
    ids.close();
    sessionHandlerStatic.close();
    obDalStatic.close();
    obContext.close();
    dal.close();
  }

  // ── entersPortal ────────────────────────────────────────────────────────────

  @Test
  public void onlyAKnownDecisionOfAnOpenCustomerEntersThePortal() {
    PortalDecisionService.Decision decision = decision("ACCEPT", null, null);
    // Columns: label, view, expected.
    Object[][] cases = {
        { "no view", null, false },
        { "invalid", new PortalDecisionService.View(PortalDecisionService.State.INVALID, null,
            null), false },
        { "no decision", new PortalDecisionService.View(PortalDecisionService.State.CONFIRMED,
            null, null), false },
        { "closed for the customer",
            PortalDecisionService.View.closedForCustomer(decision, null), false },
        { "confirmed", view(PortalDecisionService.State.CONFIRMED, decision), true },
        { "voided", view(PortalDecisionService.State.VOIDED, decision), true },
        { "not pending", view(PortalDecisionService.State.NOT_PENDING, decision), true },
        { "expired", view(PortalDecisionService.State.EXPIRED, decision), true },
    };
    for (Object[] c : cases) {
      assertEquals((String) c[0], c[2],
          PortalDecisionEntry.entersPortal((PortalDecisionService.View) c[1]));
    }
  }

  // ── addPortalEntry ──────────────────────────────────────────────────────────

  @Test
  public void addPortalEntryAddsTheTokenAndQuotationOnlyWhenMintingSucceeds() throws Exception {
    PortalDecisionService.View confirmed = view(PortalDecisionService.State.CONFIRMED,
        decision("ACCEPT", null, null));
    // Columns: what mint answers, whether the fields are added.
    Object[][] cases = { { TOKEN, true }, { null, false } };
    for (Object[] c : cases) {
      PortalDecisionEntry entry = spy(new PortalDecisionEntry(linkService, clock()));
      doReturn(c[0]).when(entry).mint(DECISION_LINK_ID, QUOTATION_ID);
      JSONObject body = new JSONObject().put("state", "confirmed");

      entry.addPortalEntry(body, confirmed);

      boolean added = (Boolean) c[1];
      assertEquals(added, body.has(PortalDecisionEntry.FIELD_PORTAL_TOKEN));
      assertEquals(added, body.has(PortalDecisionEntry.FIELD_QUOTATION_ID));
      if (added) {
        assertEquals(TOKEN, body.getString(PortalDecisionEntry.FIELD_PORTAL_TOKEN));
        assertEquals(QUOTATION_ID, body.getString(PortalDecisionEntry.FIELD_QUOTATION_ID));
      }
    }
  }

  @Test
  public void addPortalEntryNeverMintsForACustomerClosedAnswer() throws Exception {
    PortalDecisionEntry entry = spy(new PortalDecisionEntry(linkService, clock()));
    JSONObject body = new JSONObject();

    entry.addPortalEntry(body, PortalDecisionService.View.closedForCustomer(
        decision("ACCEPT", null, null), null));

    verify(entry, never()).mint(anyString(), anyString());
    assertEquals(0, body.length());
  }

  // ── mint ────────────────────────────────────────────────────────────────────

  @Test
  public void mintChainsAConfirmedDecisionLinkToTheDecisionsLinkAndCommits() {
    String token = entry().mint(DECISION_LINK_ID, QUOTATION_ID);

    PortalLinkDal.NewLink inserted = lastInsertedLink();
    PortalLink source = decisionLink("ROOT-1");
    assertEquals(NEW_LINK_ID, inserted.id);
    assertEquals(PortalLink.ORIGIN_DECISION, inserted.origin);
    assertEquals(DECISION_LINK_ID, inserted.parentLinkId);
    assertEquals(PortalDocumentKind.QUOTATION, inserted.kind);
    assertEquals(QUOTATION_ID, inserted.recordId);
    assertSame(client, inserted.client);
    assertSame(organization, inserted.organization);
    assertSame(businessPartner, inserted.businessPartner);
    assertEquals(source.getRecipientsTo(), inserted.recipientsTo);
    assertEquals(source.getRecipientsCc(), inserted.recipientsCc);
    assertEquals("en_US", inserted.language);
    // Only the hash is stored; the raw token is what the confirmation answers.
    assertEquals(PortalTokens.hash(token), inserted.tokenHash);
    assertNotEquals(token, inserted.tokenHash);
    // Usable at once: sent now, and the full lifetime from now.
    dal.verify(() -> PortalLinkDal.confirm(newRow, Date.from(NOW), Date.from(NOW.plus(TTL)),
        null));
    verify(sessionHandler).commitAndStart();
    obContext.verify(OBContext::restorePreviousMode);
  }

  @Test
  public void mintTakesTheRootFromTheDecisionsLinkOrFallsBackToItsId() {
    // Columns: the decision link's root, expected root of the minted link.
    String[][] cases = { { "ROOT-1", "ROOT-1" }, { null, DECISION_LINK_ID },
        { " ", DECISION_LINK_ID } };
    for (String[] c : cases) {
      when(linkService.findLink(DECISION_LINK_ID)).thenReturn(Optional.of(decisionLink(c[0])));

      entry().mint(DECISION_LINK_ID, QUOTATION_ID);

      assertEquals(Arrays.toString(c), c[1], lastInsertedLink().rootLinkId);
    }
  }

  @Test
  public void mintCountsTheDecisionLinksOfTheLastDayAndStopsAtTheLimit() {
    // Columns: links minted in the last day, whether one more is minted.
    Object[][] cases = { { PortalDecisionEntry.MAX_LINKS_PER_DAY - 1, true },
        { PortalDecisionEntry.MAX_LINKS_PER_DAY, false } };
    Date since = Date.from(NOW.minus(Duration.ofDays(1)));
    for (Object[] c : cases) {
      dal.when(() -> PortalLinkDal.countChildrenOf(DECISION_LINK_ID, PortalLink.ORIGIN_DECISION,
          since)).thenReturn(c[0]);

      String token = entry().mint(DECISION_LINK_ID, QUOTATION_ID);

      assertEquals(String.valueOf(c[0]), c[1], token != null);
    }
    dal.verify(() -> PortalLinkDal.insertPending(any()), times(1));
  }

  @Test
  public void mintWritesNothingWithoutTheDecisionsLinkOrAQuotation() {
    assertNull(entry().mint(DECISION_LINK_ID, " "));
    assertNull(entry().mint(DECISION_LINK_ID, null));
    when(linkService.findLink(DECISION_LINK_ID)).thenReturn(Optional.empty());
    assertNull(entry().mint(DECISION_LINK_ID, QUOTATION_ID));

    dal.verify(() -> PortalLinkDal.insertPending(any()), never());
    verify(sessionHandler, never()).commitAndStart();
  }

  @Test
  public void aFailedMintRollsBackAndAnswersNull() {
    dal.when(() -> PortalLinkDal.insertPending(any()))
        .thenThrow(new IllegalStateException("could not flush"));

    assertNull(entry().mint(DECISION_LINK_ID, QUOTATION_ID));

    verify(obDal).rollbackAndClose();
    verify(sessionHandler, never()).commitAndStart();
    obContext.verify(OBContext::restorePreviousMode);
  }

  // ── body ────────────────────────────────────────────────────────────────────

  @Test
  public void bodyCarriesTheDecisionTheQuotationAndTheTenantName() throws Exception {
    Order quotation = mock(Order.class);
    when(quotation.getDocumentNo()).thenReturn("PR-0042");
    when(quotation.getOrderDate()).thenReturn(java.util.Date.from(NOW));
    when(quotation.getGrandTotalAmount()).thenReturn(new java.math.BigDecimal("2450.00"));
    org.openbravo.model.common.currency.Currency euro =
        mock(org.openbravo.model.common.currency.Currency.class);
    when(euro.getISOCode()).thenReturn("EUR");
    when(quotation.getCurrency()).thenReturn(euro);
    when(quotation.getDocumentStatus()).thenReturn("CJ");
    PortalDecisionService.View confirmed = new PortalDecisionService.View(
        PortalDecisionService.State.CONFIRMED, decision("REJECT", "OTHER", "Plazo"), quotation,
        true);

    JSONObject body = entry().body(confirmed);

    assertEquals("confirmed", body.getString("state"));
    assertEquals(true, body.getBoolean("appliedNow"));
    assertEquals("REJECT", body.getString("decision"));
    assertEquals("OTHER", body.getString("reasonCode"));
    assertEquals("Plazo", body.getString("reasonText"));
    assertEquals("PR-0042", body.getString("documentNo"));
    assertEquals("Acme", body.getString("tenantName"));
    // The confirmation page's summary.
    assertEquals(new java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date.from(NOW)),
        body.getString("date"));
    assertEquals(0, new java.math.BigDecimal("2450.00")
        .compareTo(new java.math.BigDecimal(body.getString("grandTotal"))));
    assertEquals("EUR", body.getString("currency"));
    assertEquals("CJ", body.getString("documentStatus"));
    assertFalse("the body alone never carries a portal token",
        body.has(PortalDecisionEntry.FIELD_PORTAL_TOKEN));
  }

  /**
   * An applying confirmation answers after commitAndClose, with the quotation detached: its lazy
   * currency then throws. The summary read before the session closed must carry the body (it
   * answered 500 to every first confirmation, ETP-6209 live test).
   */
  @Test
  public void bodyOfAnAppliedConfirmationNeverReadsTheDetachedQuotation() throws Exception {
    Order quotation = mock(Order.class);
    org.openbravo.model.common.currency.Currency euro =
        mock(org.openbravo.model.common.currency.Currency.class);
    when(quotation.getDocumentNo()).thenReturn("PR-0042");
    when(quotation.getGrandTotalAmount()).thenReturn(new java.math.BigDecimal("2450.00"));
    when(quotation.getDocumentStatus()).thenReturn("CO");
    when(quotation.getCurrency()).thenReturn(euro);
    when(euro.getISOCode()).thenReturn("EUR");
    PortalDecisionService.View applied = PortalDecisionService.View.beforeSessionCloses(
        PortalDecisionService.State.CONFIRMED, decision("ACCEPT", null, null), quotation, true);
    // The session closes: the currency proxy can no longer load.
    when(euro.getISOCode()).thenThrow(
        new org.hibernate.LazyInitializationException("could not initialize proxy - no Session"));

    JSONObject body = entry().body(applied);

    assertEquals(true, body.getBoolean("appliedNow"));
    assertEquals("PR-0042", body.getString("documentNo"));
    assertEquals("EUR", body.getString("currency"));
    assertEquals("CO", body.getString("documentStatus"));
    assertEquals(0, new java.math.BigDecimal("2450.00")
        .compareTo(new java.math.BigDecimal(body.getString("grandTotal"))));
  }

  @Test
  public void bodyLeavesOutAbsentReasonsAndDefaultsTheQuotationAndTenant() throws Exception {
    when(linkService.findLink(DECISION_LINK_ID)).thenReturn(Optional.empty());

    JSONObject body = entry().body(view(PortalDecisionService.State.NOT_PENDING,
        decision("ACCEPT", null, null)));

    assertFalse(body.getBoolean("appliedNow"));
    assertFalse(body.has("reasonCode"));
    assertFalse(body.has("reasonText"));
    assertEquals("", body.getString("documentNo"));
    assertEquals("", body.getString("tenantName"));
    assertFalse("no quotation, no summary", body.has("grandTotal"));
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private PortalDecisionEntry entry() {
    return new PortalDecisionEntry(linkService, clock());
  }

  private static Clock clock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }

  /** The last link inserted. */
  private PortalLinkDal.NewLink lastInsertedLink() {
    ArgumentCaptor<PortalLinkDal.NewLink> row = ArgumentCaptor.forClass(PortalLinkDal.NewLink.class);
    dal.verify(() -> PortalLinkDal.insertPending(row.capture()), atLeastOnce());
    return row.getValue();
  }

  private static PortalDecisionService.View view(PortalDecisionService.State state,
      PortalDecisionService.Decision decision) {
    return new PortalDecisionService.View(state, decision, null);
  }

  private static PortalDecisionService.Decision decision(String action, String reasonCode,
      String reasonText) {
    PortalDecisionService.Decision decision = new PortalDecisionService.Decision();
    decision.linkId = DECISION_LINK_ID;
    decision.quotationId = QUOTATION_ID;
    decision.action = action;
    decision.reasonCode = reasonCode;
    decision.reasonText = reasonText;
    return decision;
  }

  /** The link the decision was requested from: a send of the quotation, or a renewal of one. */
  private static PortalLink decisionLink(String rootLinkId) {
    return new PortalLink(DECISION_LINK_ID, CLIENT_ID, "Acme", ORG_ID, BP_ID, "Customer SL",
        PortalDocumentKind.QUOTATION, QUOTATION_ID, PortalLink.ORIGIN_SEND, null, rootLinkId,
        "sales-quotation-send", "first@customer.test, second@customer.test", "cc@customer.test",
        "en_US", new Date(0L), new Date(1L), true, false);
  }
}
