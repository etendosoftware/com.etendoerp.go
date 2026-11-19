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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.hibernate.Session;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import com.etendoerp.go.schemaforge.TenantOwnership;

/**
 * Unit tests for {@link PortalContactShare} (ETP-5850): every refusal writes nothing, and a shared
 * link is a confirmed {@code SHARED} row on the customer's latest document, renewed to the contact
 * email. The DAL boundary is mocked statically and time comes from a fixed clock.
 *
 * @covers com.etendoerp.go.portal.PortalContactShare
 */
class PortalContactShareTest {

  private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
  private static final Duration TTL = Duration.ofHours(48);
  private static final String BP_ID = "BP-1";
  private static final String CLIENT_ID = "CLIENT-1";
  private static final String LINK_ID = "LINK-1";
  private static final String EMAIL = "compras@cliente.test";

  private MockedStatic<PortalLinkPolicy> policy;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<TenantOwnership> ownership;
  private MockedStatic<PortalDocumentQuery> documents;
  private MockedStatic<PortalLinkDal> dal;
  private MockedStatic<SessionHandler> sessionHandlerStatic;
  private MockedStatic<SequenceIdData> ids;
  private MockedStatic<PortalLinkConfig> config;
  private MockedStatic<OBDal> obDalStatic;

  private PortalAccessChecks accessChecks;
  private SessionHandler sessionHandler;
  private Query<String> emailQuery;
  private BusinessPartner customer;
  private PortalContactShare share;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    policy = mockStatic(PortalLinkPolicy.class);
    policy.when(PortalLinkPolicy::isLinkEnabled).thenReturn(true);
    policy.when(() -> PortalLinkPolicy.buildLink(anyString()))
        .thenAnswer(inv -> Optional.of("https://app.test/portal/" + inv.getArgument(0)));
    obContext = mockStatic(OBContext.class);
    ownership = mockStatic(TenantOwnership.class);
    documents = mockStatic(PortalDocumentQuery.class);
    dal = mockStatic(PortalLinkDal.class);
    sessionHandler = mock(SessionHandler.class);
    sessionHandlerStatic = mockStatic(SessionHandler.class);
    sessionHandlerStatic.when(SessionHandler::getInstance).thenReturn(sessionHandler);
    ids = mockStatic(SequenceIdData.class);
    ids.when(SequenceIdData::getUUID).thenReturn(LINK_ID);
    config = mockStatic(PortalLinkConfig.class);
    config.when(PortalLinkConfig::ttl).thenReturn(TTL);

    emailQuery = mock(Query.class, RETURNS_SELF);
    when(emailQuery.list()).thenReturn(List.of(EMAIL));
    Session session = mock(Session.class);
    when(session.createQuery(anyString(), eq(String.class))).thenReturn(emailQuery);
    OBDal obDal = mock(OBDal.class);
    when(obDal.getSession()).thenReturn(session);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);

    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    customer = mock(BusinessPartner.class);
    when(customer.getId()).thenReturn(BP_ID);
    when(customer.getClient()).thenReturn(client);
    when(customer.getOrganization()).thenReturn(mock(Organization.class));
    ownership.when(() -> TenantOwnership.loadOwned(BusinessPartner.class, BP_ID))
        .thenReturn(customer);

    accessChecks = mock(PortalAccessChecks.class);
    when(accessChecks.blockReasonOrClosed(CLIENT_ID, BP_ID)).thenReturn(Optional.empty());

    BaseOBObject invoice = mock(BaseOBObject.class);
    when(invoice.getId()).thenReturn("INV-1");
    when(invoice.get("documentNo")).thenReturn("FV-1");
    documents.when(() -> PortalDocumentQuery.latestVisible(CLIENT_ID, BP_ID))
        .thenReturn(Optional.of(new Object[] { PortalDocumentKind.INVOICE, invoice }));

    share = new PortalContactShare(accessChecks, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    obDalStatic.close();
    config.close();
    ids.close();
    sessionHandlerStatic.close();
    dal.close();
    documents.close();
    ownership.close();
    obContext.close();
    policy.close();
  }

  @Test
  @DisplayName("with the bp-portal-link flag off, refuses before reading the contact")
  void flagOffRefusesBeforeAnything() {
    policy.when(PortalLinkPolicy::isLinkEnabled).thenReturn(false);

    assertEquals(PortalContactShare.State.DISABLED, share.share(BP_ID).getState());
    ownership.verify(() -> TenantOwnership.loadOwned(any(), anyString()), never());
    assertNothingWritten();
  }

  @Test
  @DisplayName("refuses another tenant's or an unknown contact, a closed portal, no email, no documents")
  void refusalsWriteNothing() {
    assertEquals(PortalContactShare.State.NOT_FOUND, share.share("OTHER").getState());

    when(accessChecks.blockReasonOrClosed(CLIENT_ID, BP_ID))
        .thenReturn(Optional.of("BLOCKED_CUSTOMER"));
    assertEquals(PortalContactShare.State.UNAVAILABLE, share.share(BP_ID).getState());
    when(accessChecks.blockReasonOrClosed(CLIENT_ID, BP_ID)).thenReturn(Optional.empty());

    when(emailQuery.list()).thenReturn(Collections.emptyList());
    assertEquals(PortalContactShare.State.NO_EMAIL, share.share(BP_ID).getState());
    when(emailQuery.list()).thenReturn(List.of(EMAIL));

    documents.when(() -> PortalDocumentQuery.latestVisible(CLIENT_ID, BP_ID))
        .thenReturn(Optional.empty());
    assertEquals(PortalContactShare.State.NO_DOCUMENTS, share.share(BP_ID).getState());

    assertNothingWritten();
  }

  @Test
  @DisplayName("mints a confirmed SHARED link on the latest document, renewed to the contact email")
  void sharesAConfirmedLink() {
    BaseOBObject row = mock(BaseOBObject.class);
    dal.when(() -> PortalLinkDal.findById(LINK_ID)).thenReturn(row);

    PortalContactShare.Outcome outcome = share.share(BP_ID);

    assertEquals(PortalContactShare.State.SHARED, outcome.getState());
    assertEquals(NOW.plus(TTL), outcome.getExpiresAt());
    assertEquals("sales-invoice", outcome.getDocumentSpec());
    assertEquals("FV-1", outcome.getDocumentNo());
    assertEquals(EMAIL, outcome.getRecipient());

    ArgumentCaptor<PortalLinkDal.NewLink> inserted =
        ArgumentCaptor.forClass(PortalLinkDal.NewLink.class);
    dal.verify(() -> PortalLinkDal.insertPending(inserted.capture()));
    PortalLinkDal.NewLink link = inserted.getValue();
    assertEquals(PortalLink.ORIGIN_SHARED, link.origin);
    assertEquals(PortalDocumentKind.INVOICE, link.kind);
    assertEquals("INV-1", link.recordId);
    assertEquals(List.of(EMAIL), link.recipientsTo);
    assertEquals(LINK_ID, link.rootLinkId);
    assertEquals(PortalTokens.hash(outcome.getUrl().substring(outcome.getUrl().lastIndexOf('/')
        + 1)), link.tokenHash, "the URL carries the token whose hash is stored");
    dal.verify(() -> PortalLinkDal.confirm(row, Date.from(NOW), Date.from(NOW.plus(TTL)), null));
    verify(sessionHandler).commitAndStart();
  }

  @Test
  @DisplayName("renews to the email of the Contacts form before a contact person's")
  void prefersTheContactsOwnEmail() {
    when(customer.get(PortalContactShare.PROPERTY_CONTACT_EMAIL)).thenReturn("ventas@cliente.test");
    dal.when(() -> PortalLinkDal.findById(LINK_ID)).thenReturn(mock(BaseOBObject.class));

    assertEquals("ventas@cliente.test", share.share(BP_ID).getRecipient());
  }

  private void assertNothingWritten() {
    dal.verify(() -> PortalLinkDal.insertPending(any()), never());
    verify(sessionHandler, never()).commitAndStart();
  }
}
