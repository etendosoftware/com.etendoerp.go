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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SequenceIdData;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.common.ConfigPropertyReader;
import com.etendoerp.go.payment.EnvironmentAccessGuard;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

/**
 * Unit tests for {@link PortalLinkService}: the lifecycle of a customer portal link (mint pending,
 * confirm on delivery, validate, renew) and the three closures {@link PortalAccessChecks} applies.
 *
 * <p>The DAL boundary ({@link PortalLinkDal}) is mocked statically; the access checks run for real
 * against a mocked Business Partner and plan guard, so "blocked", "inactive" and "plan expired" are
 * produced the way production produces them. Time comes from a fixed {@link Clock}.
 *
 * @covers com.etendoerp.go.portal.PortalLinkService
 * @covers com.etendoerp.go.portal.PortalAccessChecks
 * @covers com.etendoerp.go.portal.PortalLinkConfig
 */
public class PortalLinkServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
  private static final String TOKEN = "opened-token";
  private static final String TOKEN_HASH = PortalTokens.hash(TOKEN);
  private static final String LINK_ID = "LINK-1";
  private static final String ROOT_ID = "ROOT-1";
  private static final String CLIENT_ID = "CLIENT-1";
  private static final String ORG_ID = "ORG-1";
  private static final String BP_ID = "BP-1";
  private static final String RECORD_ID = "INV-1";
  private static final String TO = "billing@customer.test, ap@customer.test";
  private static final String CC = "boss@customer.test";
  private static final String URL_PREFIX = "https://app.example.test/portal/";

  private MockedStatic<PortalLinkDal> dal;
  private MockedStatic<PortalUsageEvents> events;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<ConfigPropertyReader> config;
  private MockedStatic<SessionHandler> sessionHandlerStatic;

  private OBDal obDal;
  private SessionHandler sessionHandler;
  private BusinessPartner businessPartner;
  private EnvironmentAccessGuard guard;

  private final List<PortalLink> renewalEmails = new ArrayList<>();
  private String renewalStatus = TransactionalEmailService.STATUS_SENT;

  @Before
  public void setUp() {
    dal = mockStatic(PortalLinkDal.class);
    events = mockStatic(PortalUsageEvents.class);
    obContext = mockStatic(OBContext.class);
    config = mockStatic(ConfigPropertyReader.class);

    obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    sessionHandler = mock(SessionHandler.class);
    sessionHandlerStatic = mockStatic(SessionHandler.class);
    sessionHandlerStatic.when(SessionHandler::getInstance).thenReturn(sessionHandler);

    // An open customer: active, not blocked, and a plan the guard has no objection to.
    businessPartner = mock(BusinessPartner.class);
    when(businessPartner.isActive()).thenReturn(true);
    when(businessPartner.isCustomerBlocking()).thenReturn(false);
    when(obDal.get(BusinessPartner.class, BP_ID)).thenReturn(businessPartner);
    guard = mock(EnvironmentAccessGuard.class);
  }

  @After
  public void tearDown() {
    sessionHandlerStatic.close();
    obDalStatic.close();
    config.close();
    obContext.close();
    events.close();
    dal.close();
  }

  // ── validate ────────────────────────────────────────────────────────────────

  @Test
  public void validateAnswersValidAndStampsTheLinkAsUsed() {
    BaseOBObject row = present(sentLink(NOW.plus(Duration.ofHours(1)), true));

    PortalLinkService.Validation validation = service().validate(TOKEN);

    assertEquals(PortalLinkService.State.VALID, validation.getState());
    assertEquals(CLIENT_ID, validation.getSession().get().getClientId());
    assertEquals(BP_ID, validation.getSession().get().getBusinessPartnerId());
    dal.verify(() -> PortalLinkDal.touchUsed(row, Date.from(NOW)));
  }

  @Test
  public void validateExpiresTheLinkExactlyAtExpiresAt() {
    Object[][] cases = {
        { NOW, PortalLinkService.State.EXPIRED },
        { NOW.minusMillis(1), PortalLinkService.State.EXPIRED },
        { NOW.plusMillis(1), PortalLinkService.State.VALID },
    };
    for (Object[] c : cases) {
      present(sentLink((Instant) c[0], true));

      PortalLinkService.Validation validation = service().validate(TOKEN);

      assertEquals("expiresAt " + c[0], c[1], validation.getState());
      assertEquals("an expired link carries no session", c[1] == PortalLinkService.State.VALID,
          validation.getSession().isPresent());
    }
  }

  @Test
  public void validateAnswersInvalidForPendingInactiveAndUnknownTokensAlike() {
    PortalLink pending = link(null, null, true);
    PortalLink inactive = sentLink(NOW.plus(Duration.ofHours(1)), false);
    for (PortalLink candidate : Arrays.asList(pending, inactive, null)) {
      BaseOBObject row = candidate == null ? null : mock(BaseOBObject.class);
      dal.when(() -> PortalLinkDal.findByTokenHash(TOKEN_HASH)).thenReturn(row);
      dal.when(() -> PortalLinkDal.toLink(row)).thenReturn(candidate);

      PortalLinkService.Validation validation = service().validate(TOKEN);

      assertEquals(PortalLinkService.State.INVALID, validation.getState());
      assertFalse("an invalid answer must not reveal the link", validation.getLink().isPresent());
    }
    // None of the three may reach the customer checks or be stamped as used.
    verify(obDal, never()).get(BusinessPartner.class, BP_ID);
    dal.verify(() -> PortalLinkDal.touchUsed(any(), any()), never());
  }

  @Test
  public void validateAnswersUnavailableForABlockedInactiveOrPlanExpiredCustomer() {
    for (Closure closure : Closure.values()) {
      closeFor(closure);
      present(sentLink(NOW.plus(Duration.ofHours(1)), true));

      PortalLinkService.Validation validation = service().validate(TOKEN);

      assertEquals(closure.name(), PortalLinkService.State.UNAVAILABLE, validation.getState());
      assertFalse(closure.name(), validation.getSession().isPresent());
    }
    dal.verify(() -> PortalLinkDal.touchUsed(any(), any()), never());
  }

  @Test
  public void validateFailsClosedWhenACustomerCheckCannotBeAnswered() {
    present(sentLink(NOW.plus(Duration.ofHours(1)), true));

    // The Business Partner cannot be read.
    when(obDal.get(BusinessPartner.class, BP_ID)).thenThrow(new IllegalStateException("db down"));
    assertEquals(PortalLinkService.State.UNAVAILABLE, service().validate(TOKEN).getState());

    // The plan check cannot be answered.
    doReturn(businessPartner).when(obDal).get(BusinessPartner.class, BP_ID);
    when(guard.checkAsSystem(CLIENT_ID, "portal")).thenThrow(new IllegalStateException("down"));
    assertEquals(PortalLinkService.State.UNAVAILABLE, service().validate(TOKEN).getState());
  }

  // ── mint and confirm ────────────────────────────────────────────────────────

  @Test
  public void mintForSendWritesNothingWhileTheFlagIsClosed() {
    try (MockedStatic<PortalLinkPolicy> policy = mockStatic(PortalLinkPolicy.class)) {
      policy.when(PortalLinkPolicy::isLinkEnabled).thenReturn(false);

      Optional<PortalLinkService.MintedLink> minted = service().mintForSend(
          PortalDocumentKind.INVOICE, RECORD_ID, Collections.singletonList("a@customer.test"),
          Collections.<String>emptyList(), "es_ES", "sales-invoice-send");

      assertFalse(minted.isPresent());
      dal.verifyNoInteractions();
      verify(obDal, never()).get(eq(Invoice.class), anyString());
    }
  }

  @Test
  public void mintForSendInsertsOnePendingRowCarryingToAndCc() {
    Invoice invoice = mock(Invoice.class);
    Client client = mock(Client.class);
    Organization organization = mock(Organization.class);
    when(invoice.getClient()).thenReturn(client);
    when(invoice.getOrganization()).thenReturn(organization);
    when(invoice.getBusinessPartner()).thenReturn(businessPartner);
    when(obDal.get(Invoice.class, RECORD_ID)).thenReturn(invoice);
    List<String> to = Arrays.asList("billing@customer.test", "ap@customer.test");
    List<String> cc = Collections.singletonList("boss@customer.test");

    try (MockedStatic<PortalLinkPolicy> policy = mockStatic(PortalLinkPolicy.class);
        MockedStatic<SequenceIdData> ids = mockStatic(SequenceIdData.class)) {
      policy.when(PortalLinkPolicy::isLinkEnabled).thenReturn(true);
      policy.when(() -> PortalLinkPolicy.buildLink(anyString()))
          .thenAnswer(inv -> Optional.of(URL_PREFIX + inv.getArgument(0)));
      ids.when(SequenceIdData::getUUID).thenReturn(LINK_ID);

      PortalLinkService.MintedLink minted = service().mintForSend(PortalDocumentKind.INVOICE,
          RECORD_ID, to, cc, "en_US", "sales-invoice-send").get();

      ArgumentCaptor<PortalLinkDal.NewLink> row =
          ArgumentCaptor.forClass(PortalLinkDal.NewLink.class);
      dal.verify(() -> PortalLinkDal.insertPending(row.capture()));
      PortalLinkDal.NewLink inserted = row.getValue();
      assertEquals(LINK_ID, minted.getId());
      assertEquals(to, inserted.recipientsTo);
      assertEquals(cc, inserted.recipientsCc);
      assertEquals(PortalLink.ORIGIN_SEND, inserted.origin);
      assertEquals("a SEND link is its own root", LINK_ID, inserted.rootLinkId);
      assertNull(inserted.parentLinkId);
      assertEquals("en_US", inserted.language);
      // Only the hash is stored; the token travels in the email's URL alone.
      String token = minted.getUrl().substring(URL_PREFIX.length());
      assertEquals(PortalTokens.hash(token), inserted.tokenHash);
      assertNotEquals(token, inserted.tokenHash);
      // Pending: nothing starts its lifetime until the email is confirmed delivered.
      dal.verify(() -> PortalLinkDal.confirm(any(), any(), any(), any()), never());
    }
  }

  @Test
  public void confirmStampsSentAndExpiryFromTheClockAndCommitsAtOnce() {
    BaseOBObject row = mock(BaseOBObject.class);
    dal.when(() -> PortalLinkDal.findById(LINK_ID)).thenReturn(row);
    PortalLink confirmed = sentLink(NOW.plus(Duration.ofHours(48)), true);
    dal.when(() -> PortalLinkDal.toLink(row)).thenReturn(confirmed);

    service().confirm(LINK_ID, "LOG-1", 3);

    dal.verify(() -> PortalLinkDal.confirm(row, Date.from(NOW),
        Date.from(NOW.plus(Duration.ofHours(48))), "LOG-1"));
    verify(sessionHandler).commitAndStart();
    verify(obDal, never()).rollbackAndClose();
  }

  @Test
  public void confirmLeavesAnAlreadyConfirmedLinkUntouched() {
    BaseOBObject row = mock(BaseOBObject.class);
    when(row.get(PortalLinkDal.PROP_SENT_AT)).thenReturn(Date.from(NOW.minusSeconds(60)));
    dal.when(() -> PortalLinkDal.findById(LINK_ID)).thenReturn(row);

    service().confirm(LINK_ID, "LOG-2", 1);

    dal.verify(() -> PortalLinkDal.confirm(any(), any(), any(), any()), never());
    verify(sessionHandler, never()).commitAndStart();
  }

  @Test
  public void aFailedConfirmRollsBackAndNeverThrowsIntoTheEmailService() {
    BaseOBObject row = mock(BaseOBObject.class);
    dal.when(() -> PortalLinkDal.findById(LINK_ID)).thenReturn(row);
    dal.when(() -> PortalLinkDal.confirm(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("could not flush"));

    service().confirm(LINK_ID, "LOG-1", 1);

    verify(obDal).rollbackAndClose();
    verify(sessionHandler, never()).commitAndStart();
  }

  // ── renew ───────────────────────────────────────────────────────────────────

  @Test
  public void renewRefusesAClosedCustomerBeforeLookingAtExpiry() {
    for (Closure closure : Closure.values()) {
      closeFor(closure);
      // Not expired yet: if expiry were checked first this would answer NOT_EXPIRED.
      PortalLink notExpired = sentLink(NOW.plus(Duration.ofHours(1)), true);
      present(notExpired);

      assertEquals(closure.name(), PortalLinkService.RenewalOutcome.UNAVAILABLE,
          service().renew(TOKEN));

      events.verify(() -> PortalUsageEvents.renewalRefused(notExpired, closure.code));
    }
    assertTrue("a closed customer must never be emailed", renewalEmails.isEmpty());
    dal.verify(() -> PortalLinkDal.lockForRenewal(anyString()), never());
  }

  @Test
  public void renewSendsForALegacyTenantWhosePlanGuardHasNoDecision() {
    // checkAsSystem answers null for a tenant that predates lifecycle metadata: allowed.
    when(guard.checkAsSystem(CLIENT_ID, "portal")).thenReturn(null);
    PortalLink expired = expiredLink();
    present(expired);

    assertEquals(PortalLinkService.RenewalOutcome.SENT, service().renew(TOKEN));
    assertEquals(Collections.singletonList(expired), renewalEmails);
  }

  @Test
  public void renewAnswersNotExpiredAndInvalidWithoutSendingOrLocking() {
    present(sentLink(NOW.plus(Duration.ofMinutes(5)), true));
    assertEquals(PortalLinkService.RenewalOutcome.NOT_EXPIRED, service().renew(TOKEN));

    dal.when(() -> PortalLinkDal.findByTokenHash(TOKEN_HASH)).thenReturn(null);
    assertEquals(PortalLinkService.RenewalOutcome.INVALID, service().renew(TOKEN));

    assertTrue(renewalEmails.isEmpty());
    dal.verify(() -> PortalLinkDal.lockForRenewal(anyString()), never());
  }

  @Test
  public void renewHonoursTheFifteenMinuteCooldownPerLink() {
    // One earlier renewal of this link, created `age` ago, as the database would count it
    // (CREATED >= since). Pending rows are counted just the same: see
    // PortalLinkDalTest#renewalCountsIncludePendingRowsByCreationButNotDeactivatedOnes.
    Object[][] cases = {
        { Duration.ofMinutes(14), PortalLinkService.RenewalOutcome.RECENTLY_SENT },
        { Duration.ofMinutes(15), PortalLinkService.RenewalOutcome.RECENTLY_SENT },
        { Duration.ofMinutes(16), PortalLinkService.RenewalOutcome.SENT },
    };
    for (Object[] c : cases) {
      Date earlierRenewal = Date.from(NOW.minus((Duration) c[0]));
      dal.when(() -> PortalLinkDal.countRenewalsOf(eq(LINK_ID), any(Date.class)))
          .thenAnswer(inv -> earlierRenewal.before(inv.getArgument(1)) ? 0 : 1);
      present(expiredLink());
      renewalEmails.clear();

      assertEquals("earlier renewal " + c[0] + " ago", c[1], service().renew(TOKEN));
      assertEquals(c[1] == PortalLinkService.RenewalOutcome.SENT ? 1 : 0, renewalEmails.size());
    }
  }

  @Test
  public void aFailedRenewalDeactivatesItsPendingRowSoARetrySendsAgain() {
    // A tiny stand-in for the table: the send mints a pending renewal row, a failed send's row is
    // deactivated, and only active rows count towards the cool-down.
    AtomicInteger activeRenewals = new AtomicInteger();
    dal.when(() -> PortalLinkDal.countRenewalsOf(eq(LINK_ID), any(Date.class)))
        .thenAnswer(inv -> activeRenewals.get());
    dal.when(() -> PortalLinkDal.deactivatePendingRenewalsOf(LINK_ID))
        .thenAnswer(inv -> {
          activeRenewals.set(0);
          return null;
        });
    present(expiredLink());
    List<String> statuses = new ArrayList<>(Arrays.asList(
        TransactionalEmailService.STATUS_PROVIDER_FAILED, TransactionalEmailService.STATUS_SENT));
    PortalLinkService service = new PortalLinkService(new PortalAccessChecks(() -> guard),
        expired -> {
          activeRenewals.incrementAndGet();
          renewalEmails.add(expired);
          return statuses.remove(0);
        }, Clock.fixed(NOW, ZoneOffset.UTC));

    assertEquals(PortalLinkService.RenewalOutcome.FAILED, service.renew(TOKEN));
    assertEquals("a retry inside the cool-down must not be blocked by the failed attempt",
        PortalLinkService.RenewalOutcome.SENT, service.renew(TOKEN));
    assertEquals(2, renewalEmails.size());
  }

  @Test
  public void renewMapsTheEmailOutcome() {
    Object[][] cases = {
        { TransactionalEmailService.STATUS_SENT, PortalLinkService.RenewalOutcome.SENT },
        { TransactionalEmailService.STATUS_DUPLICATE,
            PortalLinkService.RenewalOutcome.RECENTLY_SENT },
        { TransactionalEmailService.STATUS_THROTTLED,
            PortalLinkService.RenewalOutcome.RECENTLY_SENT },
        { TransactionalEmailService.STATUS_PROVIDER_FAILED,
            PortalLinkService.RenewalOutcome.FAILED },
        { TransactionalEmailService.STATUS_SUPPRESSED, PortalLinkService.RenewalOutcome.FAILED },
        { TransactionalEmailService.STATUS_VALIDATION_FAILED,
            PortalLinkService.RenewalOutcome.FAILED },
        { null, PortalLinkService.RenewalOutcome.FAILED },
    };
    for (Object[] c : cases) {
      present(expiredLink());
      renewalStatus = (String) c[0];

      assertEquals("email status " + c[0], c[1], service().renew(TOKEN));
    }
  }

  @Test
  public void renewDailyCeilingIsOffByDefaultAndHonouredWhenConfigured() {
    // { configured maxPerDay, renewals already made for the Business Partner today, outcome }
    Object[][] cases = {
        { null, 99, PortalLinkService.RenewalOutcome.SENT },
        { "0", 99, PortalLinkService.RenewalOutcome.SENT },
        { "3", 2, PortalLinkService.RenewalOutcome.SENT },
        { "3", 3, PortalLinkService.RenewalOutcome.RECENTLY_SENT },
        { "-1", 99, PortalLinkService.RenewalOutcome.SENT },
        { "lots", 99, PortalLinkService.RenewalOutcome.SENT },
    };
    for (Object[] c : cases) {
      String configured = (String) c[0];
      int alreadyToday = (Integer) c[1];
      config.when(() -> ConfigPropertyReader.readConfigValue(
          PortalLinkConfig.PROP_RENEWAL_MAX_PER_DAY, PortalLinkConfig.ENV_RENEWAL_MAX_PER_DAY,
          null)).thenReturn(configured);
      dal.when(() -> PortalLinkDal.countRenewalsForBusinessPartner(eq(CLIENT_ID), eq(BP_ID),
          eq(Date.from(NOW.minus(Duration.ofDays(1)))))).thenReturn(alreadyToday);
      present(expiredLink());

      assertEquals("maxPerDay=" + configured + ", today=" + alreadyToday, c[2],
          service().renew(TOKEN));
    }
  }

  @Test
  public void renewLocksTheExpiredRowBeforeReadingTheLimits() {
    config.when(() -> ConfigPropertyReader.readConfigValue(
        PortalLinkConfig.PROP_RENEWAL_MAX_PER_DAY, PortalLinkConfig.ENV_RENEWAL_MAX_PER_DAY, null))
        .thenReturn("5");
    present(expiredLink());

    assertEquals(PortalLinkService.RenewalOutcome.SENT, service().renew(TOKEN));

    InOrder order = inOrder(PortalLinkDal.class);
    order.verify(dal, () -> PortalLinkDal.lockForRenewal(LINK_ID));
    order.verify(dal, () -> PortalLinkDal.countRenewalsOf(eq(LINK_ID), any(Date.class)));
    order.verify(dal, () -> PortalLinkDal.countRenewalsForBusinessPartner(eq(CLIENT_ID),
        eq(BP_ID), any(Date.class)));
  }

  @Test
  public void mintRenewalChainsToTheExpiredLinkAndKeepsItsRecipientsAndLanguage() {
    BaseOBObject row = mock(BaseOBObject.class);
    dal.when(() -> PortalLinkDal.findById(LINK_ID)).thenReturn(row);
    dal.when(() -> PortalLinkDal.toLink(row)).thenReturn(expiredLink());

    try (MockedStatic<PortalLinkPolicy> policy = mockStatic(PortalLinkPolicy.class);
        MockedStatic<SequenceIdData> ids = mockStatic(SequenceIdData.class)) {
      policy.when(() -> PortalLinkPolicy.buildLink(anyString()))
          .thenAnswer(inv -> Optional.of(URL_PREFIX + inv.getArgument(0)));
      ids.when(SequenceIdData::getUUID).thenReturn("LINK-2");

      assertEquals("LINK-2", service().mintRenewal(LINK_ID).get().getId());

      ArgumentCaptor<PortalLinkDal.NewLink> captor =
          ArgumentCaptor.forClass(PortalLinkDal.NewLink.class);
      dal.verify(() -> PortalLinkDal.insertPending(captor.capture()));
      PortalLinkDal.NewLink renewal = captor.getValue();
      assertEquals(PortalLink.ORIGIN_RENEWAL, renewal.origin);
      assertEquals(LINK_ID, renewal.parentLinkId);
      assertEquals("the root stays the original send", ROOT_ID, renewal.rootLinkId);
      assertEquals(PortalLinkService.RENEWAL_CONTRACT, renewal.contractName);
      assertEquals(Arrays.asList("billing@customer.test", "ap@customer.test"),
          renewal.recipientsTo);
      assertEquals(Collections.singletonList("boss@customer.test"), renewal.recipientsCc);
      assertEquals("en_US", renewal.language);
      assertEquals(RECORD_ID, renewal.recordId);
      assertEquals(PortalDocumentKind.INVOICE, renewal.kind);
    }
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  /** The three conditions that close the portal, each with the code it is refused with. */
  private enum Closure {
    BLOCKED(PortalAccessChecks.BLOCKED_CUSTOMER),
    INACTIVE(PortalAccessChecks.INACTIVE),
    PLAN_EXPIRED(PortalAccessChecks.PLAN_EXPIRED);

    private final String code;

    Closure(String code) {
      this.code = code;
    }
  }

  /** Re-creates the customer and the plan guard so that exactly {@code closure} applies. */
  private void closeFor(Closure closure) {
    businessPartner = mock(BusinessPartner.class);
    when(businessPartner.isActive()).thenReturn(closure != Closure.INACTIVE);
    when(businessPartner.isCustomerBlocking()).thenReturn(closure == Closure.BLOCKED);
    when(obDal.get(BusinessPartner.class, BP_ID)).thenReturn(businessPartner);
    guard = mock(EnvironmentAccessGuard.class);
    if (closure == Closure.PLAN_EXPIRED) {
      EnvironmentAccessGuard.Denial denial = mock(EnvironmentAccessGuard.Denial.class);
      when(guard.checkAsSystem(CLIENT_ID, "portal")).thenReturn(denial);
    }
  }

  /** Makes {@link #TOKEN} resolve to {@code link}; answers the row it is read from. */
  private BaseOBObject present(PortalLink link) {
    BaseOBObject row = mock(BaseOBObject.class);
    dal.when(() -> PortalLinkDal.findByTokenHash(TOKEN_HASH)).thenReturn(row);
    dal.when(() -> PortalLinkDal.toLink(row)).thenReturn(link);
    dal.when(() -> PortalLinkDal.findById(link.getId())).thenReturn(row);
    return row;
  }

  private PortalLinkService service() {
    return new PortalLinkService(new PortalAccessChecks(() -> guard), expired -> {
      renewalEmails.add(expired);
      return renewalStatus;
    }, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static PortalLink expiredLink() {
    return sentLink(NOW.minus(Duration.ofHours(1)), true);
  }

  private static PortalLink sentLink(Instant expiresAt, boolean active) {
    return link(Date.from(expiresAt.minus(Duration.ofHours(48))), Date.from(expiresAt), active);
  }

  private static PortalLink link(Date sentAt, Date expiresAt, boolean active) {
    return new PortalLink(LINK_ID, CLIENT_ID, "Acme", ORG_ID, BP_ID, "Customer SL",
        PortalDocumentKind.INVOICE, RECORD_ID, PortalLink.ORIGIN_SEND, null, ROOT_ID,
        "sales-invoice-send", TO, CC, "en_US", sentAt, expiresAt, active, false);
  }
}
