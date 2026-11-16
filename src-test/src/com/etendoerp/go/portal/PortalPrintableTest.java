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
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.NeoAttachmentTarget;
import com.etendoerp.go.schemaforge.NeoDocumentPdf;
import com.etendoerp.go.schemaforge.NeoPdfSourceConfig;
import com.etendoerp.go.schemaforge.NeoPrintAfterComplete;
import com.etendoerp.go.schemaforge.NeoPrintableRenderer;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link PortalPrintable}: whose identity the portal renders a missing PDF as, which
 * render outcomes give the customer a file, and the stored-PDF freshness check. The render itself
 * ({@link NeoPrintAfterComplete}) and the lookups are mocked; this pins the portal's decisions.
 *
 * @covers com.etendoerp.go.portal.PortalPrintable
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
public class PortalPrintableTest {

  private static final String DOC_ID = "QUO-1";
  private static final PortalDocumentKind KIND = PortalDocumentKind.QUOTATION;

  private MockedStatic<OBDal> obDalStatic;
  private MockedStatic<NeoPrintableRenderer> renderer;
  private MockedStatic<NeoPdfSourceConfig> pdfConfig;
  private MockedStatic<NeoPrintAfterComplete> worker;
  private MockedStatic<PortalDocumentsEndpoint> endpoint;
  private OBDal obDal;
  private Session session;
  private NativeQuery senderQuery;
  private NativeQuery roleQuery;
  private SFEntity entity;
  private BaseOBObject document;

  @Before
  public void setUp() {
    obDal = mock(OBDal.class);
    obDalStatic = mockStatic(OBDal.class);
    obDalStatic.when(OBDal::getInstance).thenReturn(obDal);
    session = mock(Session.class);
    when(obDal.getSession()).thenReturn(session);
    senderQuery = nativeQuery(Collections.singletonList("SENDER"));
    roleQuery = nativeQuery(Collections.singletonList("SENDER_ROLE"));
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.contains("etgo_portal_link"))))
        .thenReturn(senderQuery);
    when(session.createNativeQuery(argThat(sql -> sql != null && sql.contains("ad_user_roles"))))
        .thenReturn(roleQuery);

    // One active spec of the kind, whose entity declares a printable.
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("SPEC1");
    entity = mock(SFEntity.class);
    when(entity.getETGOSFSpec()).thenReturn(spec);
    OBCriteria specs = mock(OBCriteria.class);
    when(specs.list()).thenReturn(Collections.singletonList(spec));
    OBCriteria entities = mock(OBCriteria.class);
    when(entities.list()).thenReturn(Collections.singletonList(entity));
    when(obDal.createCriteria(SFSpec.class)).thenReturn(specs);
    when(obDal.createCriteria(SFEntity.class)).thenReturn(entities);
    pdfConfig = mockStatic(NeoPdfSourceConfig.class);
    pdfConfig.when(() -> NeoPdfSourceConfig.printDocumentOf(entity)).thenReturn("sales-quotation");
    renderer = mockStatic(NeoPrintableRenderer.class);
    renderer.when(NeoPrintableRenderer::isConfigured).thenReturn(true);
    worker = mockStatic(NeoPrintAfterComplete.class);
    endpoint = mockStatic(PortalDocumentsEndpoint.class);

    Organization organization = mock(Organization.class);
    when(organization.getId()).thenReturn("DOC_ORG");
    document = mock(BaseOBObject.class);
    when(document.getId()).thenReturn(DOC_ID);
    when(document.get("organization")).thenReturn(organization);
  }

  @After
  public void tearDown() {
    endpoint.close();
    worker.close();
    renderer.close();
    pdfConfig.close();
    obDalStatic.close();
  }

  @Test
  public void theSenderIsTheRootLinksCreatorUnlessASystemUser() {
    // Columns: creator of the root row (null: no row), sender.
    Object[][] cases = { { "SENDER", "SENDER" }, { " SENDER ", "SENDER" }, { "0", null },
        { "100", null }, { " ", null }, { null, null } };
    for (Object[] c : cases) {
      List<String> rows = c[0] == null ? Collections.<String>emptyList()
          : Collections.singletonList((String) c[0]);
      when(senderQuery.list()).thenReturn(rows);

      assertEquals("creator " + c[0], c[1], PortalPrintable.senderOfLink("LINK-2"));
    }
    verify(senderQuery, atLeastOnce()).setParameter("linkId", "LINK-2");
  }

  @Test
  public void aMissingPdfIsRenderedAsTheSenderInTheLinksClientAndTheDocumentsOrganization() {
    Attachment stored = mock(Attachment.class);
    PortalSession portal = session("en_US");
    worker.when(() -> NeoPrintAfterComplete.renderAndWaitAs(any(), anyString(), anyString(),
        anyString(), anyString(), anyString(), anyString()))
        .thenReturn(NeoPrintableRenderer.Outcome.STORED);
    endpoint.when(() -> PortalDocumentsEndpoint.mainAttachment(portal, KIND, DOC_ID))
        .thenReturn(stored);

    assertSame(stored, PortalPrintable.renderAndFind(portal, KIND, document));

    worker.verify(() -> NeoPrintAfterComplete.renderAndWaitAs(entity, DOC_ID, "en_US", "SENDER",
        "SENDER_ROLE", "CLIENT-1", "DOC_ORG"));
    // The role is the sender's in the link's client (default first, else the first active).
    verify(roleQuery).setParameter("userId", "SENDER");
    verify(roleQuery).setParameter("clientId", "CLIENT-1");
    // The worker committed in its own session: the attachment is read fresh.
    verify(session).clear();
  }

  @Test
  public void aBlankLinkLanguageRendersInSpanish() {
    worker.when(() -> NeoPrintAfterComplete.renderAndWaitAs(any(), anyString(), anyString(),
        anyString(), anyString(), anyString(), anyString()))
        .thenReturn(NeoPrintableRenderer.Outcome.ALREADY_CURRENT);

    PortalPrintable.renderAndFind(session(null), KIND, document);

    worker.verify(() -> NeoPrintAfterComplete.renderAndWaitAs(any(), anyString(), eq("es_ES"),
        anyString(), anyString(), anyString(), anyString()));
  }

  @Test
  public void noSenderNoRoleOrNotConfiguredMeansNoRenderAtAll() {
    PortalSession portal = session("es_ES");
    // Columns: root creator, the creator's roles in the client, render configured.
    Object[][] cases = {
        { "100", Collections.singletonList("SENDER_ROLE"), true },
        { "SENDER", Collections.emptyList(), true },
        { "SENDER", Collections.singletonList("SENDER_ROLE"), false } };
    for (Object[] c : cases) {
      List<String> sender = Collections.singletonList((String) c[0]);
      when(senderQuery.list()).thenReturn(sender);
      when(roleQuery.list()).thenReturn((List<String>) c[1]);
      renderer.when(NeoPrintableRenderer::isConfigured).thenReturn((Boolean) c[2]);

      assertNull(Arrays.toString(c), PortalPrintable.renderAndFind(portal, KIND, document));
    }
    worker.verifyNoInteractions();
    endpoint.verifyNoInteractions();
    assertFalse("not configured: not renderable", PortalPrintable.isRenderable(KIND));
  }

  @Test
  public void onlyAStoredOrAlreadyCurrentPdfIsReadBack() {
    PortalSession portal = session("es_ES");
    Attachment stored = mock(Attachment.class);
    endpoint.when(() -> PortalDocumentsEndpoint.mainAttachment(portal, KIND, DOC_ID))
        .thenReturn(stored);
    for (NeoPrintableRenderer.Outcome outcome : NeoPrintableRenderer.Outcome.values()) {
      worker.when(() -> NeoPrintAfterComplete.renderAndWaitAs(any(), anyString(), anyString(),
          anyString(), anyString(), anyString(), anyString())).thenReturn(outcome);

      Attachment found = PortalPrintable.renderAndFind(portal, KIND, document);

      boolean served = outcome == NeoPrintableRenderer.Outcome.STORED
          || outcome == NeoPrintableRenderer.Outcome.ALREADY_CURRENT;
      assertEquals(outcome.name(), served ? stored : null, found);
    }
    // Timed out (null) and a worker failure: no file, never an exception to the customer.
    worker.when(() -> NeoPrintAfterComplete.renderAndWaitAs(any(), anyString(), anyString(),
        anyString(), anyString(), anyString(), anyString())).thenReturn(null);
    assertNull(PortalPrintable.renderAndFind(portal, KIND, document));
    worker.when(() -> NeoPrintAfterComplete.renderAndWaitAs(any(), anyString(), anyString(),
        anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("worker"));
    assertNull(PortalPrintable.renderAndFind(portal, KIND, document));
    endpoint.verify(() -> PortalDocumentsEndpoint.mainAttachment(portal, KIND, DOC_ID),
        times(2));
  }

  @Test
  public void aKindWithNoPrintableIsNeitherRenderableNorRendered() {
    pdfConfig.when(() -> NeoPdfSourceConfig.printDocumentOf(entity)).thenReturn(null);

    assertFalse(PortalPrintable.isRenderable(KIND));
    assertNull(PortalPrintable.renderAndFind(session("es_ES"), KIND, document));
    worker.verifyNoInteractions();
  }

  @Test
  public void isStaleIsTheAppsFreshnessRuleAndAFailedCheckReadsAsFresh() {
    NeoAttachmentTarget target = mock(NeoAttachmentTarget.class);
    try (MockedStatic<NeoAttachmentTarget> targets = mockStatic(NeoAttachmentTarget.class);
        MockedStatic<NeoDocumentPdf> pdfs = mockStatic(NeoDocumentPdf.class)) {
      targets.when(() -> NeoAttachmentTarget.resolve(any(), eq(entity), eq(DOC_ID), eq(false)))
          .thenReturn(target);
      for (boolean stale : new boolean[] { true, false }) {
        pdfs.when(() -> NeoDocumentPdf.isStoredStale(target, entity)).thenReturn(stale);

        assertEquals(stale, PortalPrintable.isStale(KIND, DOC_ID));
      }
      pdfs.when(() -> NeoDocumentPdf.isStoredStale(target, entity))
          .thenThrow(new IllegalStateException("lookup"));
      assertFalse("a failed check serves the stored file", PortalPrintable.isStale(KIND, DOC_ID));

      pdfConfig.when(() -> NeoPdfSourceConfig.printDocumentOf(entity)).thenReturn(null);
      assertFalse("no printable entity", PortalPrintable.isStale(KIND, DOC_ID));
    }
  }

  // ── fixtures ────────────────────────────────────────────────────────────────

  private static NativeQuery nativeQuery(List<String> rows) {
    NativeQuery query = mock(NativeQuery.class);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.list()).thenReturn(rows);
    return query;
  }

  private static PortalSession session(String language) {
    return new PortalSession(new PortalLink("LINK-2", "CLIENT-1", "Acme", "ORG-1", "BP-1",
        "Customer SL", KIND, DOC_ID, PortalLink.ORIGIN_RENEWAL, "LINK-1", "LINK-1",
        "portal-link-renewal", "a@customer.test", null, language, new Date(0L), new Date(1L),
        true, false));
  }
}
