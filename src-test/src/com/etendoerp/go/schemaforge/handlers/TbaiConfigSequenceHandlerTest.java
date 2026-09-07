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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Role;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.SiiTbaiAutoSendScheduleService;
import com.smf.ticketbai.data.TbaiConfig;

/**
 * Unit tests for {@link TbaiConfigSequenceHandler} (ETP-4401).
 *
 * <p>Covers the method/endpoint guard clauses in {@link TbaiConfigSequenceHandler#afterHandle},
 * record-id resolution for both PUT (from the URL) and POST (from the just-committed CRUD
 * response envelope, mirroring {@code VerifactuConfigReadyHandlerTest}), the happy path where a
 * single new chaining {@link Sequence} is created and shared by every qualifying invoice
 * {@link DocumentType} in scope, the scope-wide reuse rule (when one Document Type already has a
 * {@code tbaiAdSequence}, that same instance — not a copy — is assigned to the others instead of
 * creating a new one), the idempotency guarantee (a Document Type that already has a
 * {@code tbaiAdSequence} is left untouched), the {@code C_Invoice} table scoping of the query
 * (replacing the old {@code documentCategory} filter), the no-op case where the organization tree
 * has no active invoice Document Types, and that any exception raised while resolving/creating
 * sequences is swallowed rather than propagated.
 *
 * <p>{@link TbaiConfigSequenceHandler#handle} is always a pre-hook no-op and is asserted
 * separately; all the interesting behavior lives in {@code afterHandle}.
 */
public class TbaiConfigSequenceHandlerTest {

  private static final String RECORD_ID = "tbai-config-001";
  private static final String CLIENT_ID = "client-001";
  private static final String ORG_ID = "org-001";
  private static final String ORG_NAME = "Acme Spain";

  // ─── handle(): smart deactivation dispatch guards ────────────────────────────

  @Test
  public void handleReturnsNullForNonPutMethod() {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    // POST, GET, PATCH, DELETE all fall through — only PUT triggers smart deactivation.
    assertNull(handler.handle(NeoContext.builder().httpMethod("POST").build()));
    assertNull(handler.handle(NeoContext.builder().httpMethod("GET").build()));
    assertNull(handler.handle(NeoContext.builder().httpMethod("PATCH").build()));
    assertNull(handler.handle(NeoContext.builder().httpMethod("DELETE").build()));
  }

  @Test
  public void handleReturnsNullWhenBodyHasNoActiveField() throws Exception {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("name", "foo"))
        .recordId(RECORD_ID)
        .build()));
  }

  @Test
  public void handleReturnsNullWhenActiveIsTrue() throws Exception {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", true))
        .recordId(RECORD_ID)
        .build()));
  }

  @Test
  public void handleReturnsNullWhenRecordIdIsBlank() throws Exception {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    assertNull(handler.handle(NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", false))
        .recordId("   ")
        .build()));
  }

  // ─── handle(): smartDeactivate scenarios ─────────────────────────────────────

  @Test
  public void smartDeactivateReturnsNullWhenConfigNotFound() throws Exception {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(TbaiConfig.class), eq(RECORD_ID))).thenReturn(null);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build());

      assertNull(result);
      verify(dal, never()).remove(any());
    }
  }

  @Test
  public void smartDeactivateDeletesAndReturnsDeletedWhenTbaisystemdateIsNull() throws Exception {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      TbaiConfig config = mock(TbaiConfig.class);
      when(dal.get(eq(TbaiConfig.class), eq(RECORD_ID))).thenReturn(config);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn(ORG_ID);
      when(config.getOrganization()).thenReturn(org);
      // tbaisystemdate is null — config never entered the fiscal system
      when(config.getTbaisystemdate()).thenReturn(null);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build());

      verify(dal).remove(config);
      verify(dal).flush();
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(true, result.getBody().getBoolean("deleted"));
    }
  }

  @Test
  public void smartDeactivateDeletesWhenAdoptionDateSetButNoTbaiInvoices() throws Exception {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    Date adoptionDate = new Date();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      TbaiConfig config = mock(TbaiConfig.class);
      when(dal.get(eq(TbaiConfig.class), eq(RECORD_ID))).thenReturn(config);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn(ORG_ID);
      when(config.getOrganization()).thenReturn(org);
      when(config.getTbaisystemdate()).thenReturn(adoptionDate);

      // hasTbaiInvoicesSince → OBCriteria returns count 0
      @SuppressWarnings("unchecked")
      OBCriteria<Invoice> crit = mock(OBCriteria.class);
      when(dal.createCriteria(Invoice.class)).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setProjection(any())).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(0L);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build());

      verify(dal).remove(config);
      verify(dal).flush();
      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(true, result.getBody().getBoolean("deleted"));
    }
  }

  @Test
  public void smartDeactivateReturnsNullWhenTbaiInvoicesExist() throws Exception {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    Date adoptionDate = new Date();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);

      TbaiConfig config = mock(TbaiConfig.class);
      when(dal.get(eq(TbaiConfig.class), eq(RECORD_ID))).thenReturn(config);

      Organization org = mock(Organization.class);
      when(org.getId()).thenReturn(ORG_ID);
      when(config.getOrganization()).thenReturn(org);
      when(config.getTbaisystemdate()).thenReturn(adoptionDate);

      // hasTbaiInvoicesSince → OBCriteria returns count > 0
      @SuppressWarnings("unchecked")
      OBCriteria<Invoice> crit = mock(OBCriteria.class);
      when(dal.createCriteria(Invoice.class)).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setProjection(any())).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(4L);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build());

      // TBAI invoices found → fallthrough to default CRUD deactivation
      assertNull(result);
      verify(dal, never()).remove(any());
    }
  }

  // ─── handle(): 500 on unexpected exception (must NOT fall through to default CRUD) ───

  @Test
  public void handleReturns500OnUnexpectedException() throws Exception {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal dal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(dal);
      when(dal.get(eq(TbaiConfig.class), Mockito.anyString()))
          .thenThrow(new RuntimeException("DB exploded"));

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build());
      // Must return 500, NOT null — returning null would let the default CRUD deactivate
      // the record without verifying pending invoices, bypassing the business rule.
      assertNotNull(result);
      assertEquals(500, result.getHttpStatus());
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ─── afterHandle: endpoint/method guards ─────────────────────────────────────

  @Test
  public void afterHandleReturnsNullForNonCrudEndpoint() {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.SELECTOR)
        .httpMethod("POST")
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      assertNull(handler.afterHandle(ctx));
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  @Test
  public void afterHandleReturnsNullForCrudGetMethod() {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("GET")
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      assertNull(handler.afterHandle(ctx));
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  /**
   * Since the ETP-5117 follow-up, a CRUD DELETE is a bare {@code afterHandle} no-op again, exactly
   * like GET and the non-CRUD endpoints above: its auto-send schedule cleanup moved to the
   * {@code beforeDelete} pre-hook, where the config record still exists and can answer for its own
   * client/organization. So {@code afterHandle} short-circuits before ever entering admin mode.
   */
  @Test
  public void afterHandleReturnsNullForCrudDeleteMethod() {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("DELETE")
        .recordId(RECORD_ID)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      assertNull(handler.afterHandle(ctx));

      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
      verifyNoInteractions(obDal);
    }
  }

  // ─── afterHandle: happy path (PUT, id from URL) ──────────────────────────────

  @Test
  public void afterHandleCreatesAndAssignsOneSharedSequenceForInvoiceDocTypesWithoutOne() {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .build();

    Client client = mock(Client.class);
    Organization configOrg = mock(Organization.class);
    when(configOrg.getId()).thenReturn(ORG_ID);
    when(configOrg.getName()).thenReturn(ORG_NAME);
    TbaiConfig config = mock(TbaiConfig.class);
    when(config.getClient()).thenReturn(client);
    when(config.getOrganization()).thenReturn(configOrg);

    // Two qualifying Document Types, neither has a sequence yet.
    DocumentType docType1 = mock(DocumentType.class);
    when(docType1.getTbaiAdSequence()).thenReturn(null);
    DocumentType docType2 = mock(DocumentType.class);
    when(docType2.getTbaiAdSequence()).thenReturn(null);

    Sequence sequence = mock(Sequence.class);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBContext obContext = mock(OBContext.class);
      OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
      when(osp.getNaturalTree(ORG_ID)).thenReturn(Collections.singleton(ORG_ID));
      when(obContext.getOrganizationStructureProvider()).thenReturn(osp);
      obCtxMock.when(OBContext::getOBContext).thenReturn(obContext);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      @SuppressWarnings("unchecked")
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Arrays.asList(docType1, docType2));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Sequence.class)).thenReturn(sequence);

      assertNull(handler.afterHandle(ctx));

      // Exactly ONE Sequence created for the whole batch, not one per Document Type.
      verify(obProvider, times(1)).get(Sequence.class);
      verify(sequence).setNewOBObject(true);
      verify(sequence).setClient(client);
      verify(sequence).setOrganization(configOrg);
      verify(sequence).setName("TBAI - " + ORG_NAME);
      verify(sequence).setPrefix("TBAI-");
      verify(sequence).setStartingNo(1L);
      verify(sequence).setNextAssignedNumber(1L);
      verify(sequence).setIncrementBy(1L);
      verify(sequence).setAutoNumbering(true);
      verify(obDal).save(sequence);

      // The SAME instance is assigned to BOTH Document Types.
      verify(docType1).setTbaiAdSequence(sequence);
      verify(docType2).setTbaiAdSequence(sequence);
      verify(obDal).save(docType1);
      verify(obDal).save(docType2);
      verify(obDal, times(1)).flush();
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ─── afterHandle: happy path (POST, id from CRUD response envelope) ─────────

  @Test
  public void afterHandlePostResolvesIdFromDataArrayEnvelopeAndCreatesSequence() throws Exception {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    JSONObject dataRow = new JSONObject().put("id", RECORD_ID);
    JSONObject response = new JSONObject().put("data", new JSONArray().put(dataRow));
    JSONObject body = new JSONObject().put("response", response);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("POST")
        .previousResult(new NeoResponse(201, body))
        .build();

    Client client = mock(Client.class);
    Organization configOrg = mock(Organization.class);
    when(configOrg.getId()).thenReturn(ORG_ID);
    when(configOrg.getName()).thenReturn(ORG_NAME);
    TbaiConfig config = mock(TbaiConfig.class);
    when(config.getClient()).thenReturn(client);
    when(config.getOrganization()).thenReturn(configOrg);

    DocumentType docType = mock(DocumentType.class);
    when(docType.getTbaiAdSequence()).thenReturn(null);

    Sequence sequence = mock(Sequence.class);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBContext obContext = mock(OBContext.class);
      OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
      when(osp.getNaturalTree(ORG_ID)).thenReturn(Collections.singleton(ORG_ID));
      when(obContext.getOrganizationStructureProvider()).thenReturn(osp);
      obCtxMock.when(OBContext::getOBContext).thenReturn(obContext);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      @SuppressWarnings("unchecked")
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(docType));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Sequence.class)).thenReturn(sequence);

      assertNull(handler.afterHandle(ctx));

      verify(docType).setTbaiAdSequence(sequence);
      verify(obDal).save(docType);
      verify(obDal, times(1)).flush();
    }
  }

  // ─── afterHandle: idempotency (single Document Type already assigned) ───────

  @Test
  public void afterHandleLeavesDocTypeUntouchedWhenSequenceAlreadyAssigned() {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .build();

    Client client = mock(Client.class);
    Organization configOrg = mock(Organization.class);
    when(configOrg.getId()).thenReturn(ORG_ID);
    TbaiConfig config = mock(TbaiConfig.class);
    when(config.getClient()).thenReturn(client);
    when(config.getOrganization()).thenReturn(configOrg);

    Sequence existingSequence = mock(Sequence.class);
    DocumentType docType = mock(DocumentType.class);
    // Already has a chaining sequence — must be left untouched (idempotency).
    when(docType.getTbaiAdSequence()).thenReturn(existingSequence);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBContext obContext = mock(OBContext.class);
      OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
      when(osp.getNaturalTree(ORG_ID)).thenReturn(Collections.singleton(ORG_ID));
      when(obContext.getOrganizationStructureProvider()).thenReturn(osp);
      obCtxMock.when(OBContext::getOBContext).thenReturn(obContext);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      @SuppressWarnings("unchecked")
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(docType));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);

      assertNull(handler.afterHandle(ctx));

      // No new sequence is created, the existing assignment is never replaced,
      // and no extra Sequence/DocumentType save happens for this doc type.
      verify(obProvider, never()).get(Sequence.class);
      verify(docType, never()).setTbaiAdSequence(any());
      verify(obDal, never()).save(existingSequence);
      verify(obDal, never()).save(docType);
      // flush() is unconditional after the loop, regardless of whether anything changed.
      verify(obDal, times(1)).flush();
    }
  }

  // ─── afterHandle: scope-wide reuse — one DocType already has it, others don't ───

  /**
   * Covers the core post-QA fix: when one Document Type in scope already carries a
   * {@code tbaiAdSequence}, that exact instance is the shared scope sequence and must be
   * assigned (same object identity, not a copy) to every other qualifying Document Type that
   * lacks one — no new {@link Sequence} is ever created while one is already in use in scope.
   */
  @Test
  public void afterHandleReusesExistingSharedSequenceForOtherDocTypesInScope() {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .build();

    Client client = mock(Client.class);
    Organization configOrg = mock(Organization.class);
    when(configOrg.getId()).thenReturn(ORG_ID);
    TbaiConfig config = mock(TbaiConfig.class);
    when(config.getClient()).thenReturn(client);
    when(config.getOrganization()).thenReturn(configOrg);

    // DocType #1: already has the shared chaining sequence.
    Sequence existingSequence = mock(Sequence.class);
    DocumentType docTypeWithSequence = mock(DocumentType.class);
    when(docTypeWithSequence.getTbaiAdSequence()).thenReturn(existingSequence);

    // DocType #2: no sequence yet — must receive the SAME existing instance.
    DocumentType docTypeWithoutSequence = mock(DocumentType.class);
    when(docTypeWithoutSequence.getTbaiAdSequence()).thenReturn(null);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBContext obContext = mock(OBContext.class);
      OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
      when(osp.getNaturalTree(ORG_ID)).thenReturn(Collections.singleton(ORG_ID));
      when(obContext.getOrganizationStructureProvider()).thenReturn(osp);
      obCtxMock.when(OBContext::getOBContext).thenReturn(obContext);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      @SuppressWarnings("unchecked")
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list())
          .thenReturn(Arrays.asList(docTypeWithSequence, docTypeWithoutSequence));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);

      assertNull(handler.afterHandle(ctx));

      // No new Sequence is ever created — one is already in use in scope.
      verify(obProvider, never()).get(Sequence.class);

      // The already-assigned Document Type is left untouched.
      verify(docTypeWithSequence, never()).setTbaiAdSequence(any());
      verify(obDal, never()).save(docTypeWithSequence);

      // The other one receives the EXACT SAME instance (object identity, not a copy).
      ArgumentCaptor<Sequence> assignedCaptor = ArgumentCaptor.forClass(Sequence.class);
      verify(docTypeWithoutSequence).setTbaiAdSequence(assignedCaptor.capture());
      assertSame(existingSequence, assignedCaptor.getValue());
      verify(obDal).save(docTypeWithoutSequence);

      verify(obDal, times(1)).flush();
    }
  }

  // ─── afterHandle: mixed batch + criteria scoping ─────────────────────────────

  /**
   * Covers two gaps left open by the tests above:
   *
   * <p>1. Every other test stubs {@code criteria.add(any())}, which accepts whatever
   * {@link Criterion} is passed without checking it — so the client/organization/table/active
   * scoping in {@code findInvoiceDocumentTypes} was only asserted by the production code's
   * comments, never by a test. This test captures every {@link Criterion} added and asserts
   * (via {@code toString()}, which Hibernate's {@code SimpleExpression}/{@code InExpression}
   * render deterministically as {@code "property=value"} / {@code "property in (…)"}) that the
   * criteria is scoped to the config's client id, the resolved org tree, the {@code C_Invoice}
   * table (via the {@code tbl} alias, replacing the old {@code documentCategory} filter), and
   * active records only.
   *
   * <p>2. No test exercises more than one {@link DocumentType} per run with BOTH lacking a
   * sequence. Here the query returns two Document Types, neither with a sequence yet, to confirm
   * a single new {@link Sequence} is created and shared by both in the SAME {@code afterHandle}
   * call — never one per Document Type.
   */
  @Test
  public void afterHandleScopesCriteriaByInvoiceTableAndSharesOneSequenceAcrossBatch() {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .build();

    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    Organization configOrg = mock(Organization.class);
    when(configOrg.getId()).thenReturn(ORG_ID);
    when(configOrg.getName()).thenReturn(ORG_NAME);
    TbaiConfig config = mock(TbaiConfig.class);
    when(config.getClient()).thenReturn(client);
    when(config.getOrganization()).thenReturn(configOrg);

    // Two Document Types in scope, neither has a chaining sequence yet.
    DocumentType docType1 = mock(DocumentType.class);
    when(docType1.getTbaiAdSequence()).thenReturn(null);
    DocumentType docType2 = mock(DocumentType.class);
    when(docType2.getTbaiAdSequence()).thenReturn(null);

    Sequence newSequence = mock(Sequence.class);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBContext obContext = mock(OBContext.class);
      OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
      when(osp.getNaturalTree(ORG_ID)).thenReturn(Collections.singleton(ORG_ID));
      when(obContext.getOrganizationStructureProvider()).thenReturn(osp);
      obCtxMock.when(OBContext::getOBContext).thenReturn(obContext);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      @SuppressWarnings("unchecked")
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(DocumentType.class)).thenReturn(criteria);
      ArgumentCaptor<Criterion> criterionCaptor = ArgumentCaptor.forClass(Criterion.class);
      when(criteria.add(criterionCaptor.capture())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Arrays.asList(docType1, docType2));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Sequence.class)).thenReturn(newSequence);

      assertNull(handler.afterHandle(ctx));

      // --- gap 1: the criteria is actually scoped by client/org/table/active, not just ---
      // --- accepted blindly by an any() matcher. ---
      verify(criteria).createAlias("table", "tbl");
      List<String> addedCriteria = criterionCaptor.getAllValues().stream()
          .map(Criterion::toString)
          .collect(java.util.stream.Collectors.toList());
      assertEquals(4, addedCriteria.size());
      assertTrue("expected the client id filter, got: " + addedCriteria,
          addedCriteria.stream().anyMatch(s -> s.equals("client.id=" + CLIENT_ID)));

      // The org filter must include BOTH the resolved natural-tree org AND org "0" (the "*"
      // org) — DocumentTypes are very commonly defined at org "*", and SelectorOrgFilter's
      // buildOrganizationPredicate establishes the precedent of always adding "0" for this
      // reason (see class Javadoc / SelectorOrgFilter#buildOrganizationPredicate).
      String orgFilter = addedCriteria.stream()
          .filter(s -> s.contains("organization.id in"))
          .findFirst()
          .orElse("");
      assertTrue("expected an organization.id in filter, got: " + addedCriteria, !orgFilter.isEmpty());
      String orgValuesPart = orgFilter.substring(orgFilter.indexOf('(') + 1, orgFilter.lastIndexOf(')'));
      List<String> orgValues = Arrays.asList(orgValuesPart.split(",\\s*"));
      assertTrue("expected org filter to include the resolved natural-tree org, got: " + orgValues,
          orgValues.contains(ORG_ID));
      assertTrue("expected org filter to include org \"0\" (the \"*\" org), got: " + orgValues,
          orgValues.contains("0"));

      assertTrue("expected the C_Invoice table filter, got: " + addedCriteria,
          addedCriteria.stream().anyMatch(s -> s.equals("tbl.dBTableName=C_Invoice")));
      assertTrue("expected the active=true filter, got: " + addedCriteria,
          addedCriteria.stream().anyMatch(s -> s.equals("active=true")));

      // --- gap 2: a SINGLE new Sequence is created and shared by BOTH Document Types ---
      // --- in the same afterHandle() call — never one per Document Type. ---
      verify(obProvider, times(1)).get(Sequence.class);
      verify(newSequence).setOrganization(configOrg);
      verify(newSequence).setName("TBAI - " + ORG_NAME);
      verify(docType1).setTbaiAdSequence(newSequence);
      verify(docType2).setTbaiAdSequence(newSequence);
      verify(obDal).save(newSequence);
      verify(obDal).save(docType1);
      verify(obDal).save(docType2);

      // flush() runs once for the whole batch, not once per Document Type.
      verify(obDal, times(1)).flush();
    }
  }

  // ─── afterHandle: no invoice Document Types in org ───────────────────────────

  @Test
  public void afterHandleIsNoOpWhenNoInvoiceDocumentTypesFound() {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .build();

    Client client = mock(Client.class);
    Organization configOrg = mock(Organization.class);
    when(configOrg.getId()).thenReturn(ORG_ID);
    TbaiConfig config = mock(TbaiConfig.class);
    when(config.getClient()).thenReturn(client);
    when(config.getOrganization()).thenReturn(configOrg);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBContext obContext = mock(OBContext.class);
      OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
      when(osp.getNaturalTree(ORG_ID)).thenReturn(Collections.singleton(ORG_ID));
      when(obContext.getOrganizationStructureProvider()).thenReturn(osp);
      obCtxMock.when(OBContext::getOBContext).thenReturn(obContext);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      @SuppressWarnings("unchecked")
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.emptyList());

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);

      assertNull(handler.afterHandle(ctx));

      verify(obProvider, never()).get(Sequence.class);
      // The method returns early (empty list) before reaching the unconditional flush().
      verify(obDal, never()).flush();
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ─── afterHandle: config record cannot be resolved ───────────────────────────

  @Test
  public void afterHandleSkipsWhenConfigRecordNotFoundAndNoCurrentContextFallback() {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      // TbaiConfig record not found for the given id.
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(null);
      // context.getObContext() (the NeoContext-carried context, not the static current one)
      // is null in this NeoContext, so the fallback also yields no scope.

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);

      assertNull(handler.afterHandle(ctx));

      verify(obDal, never()).createCriteria(DocumentType.class);
      verify(obProvider, never()).get(Sequence.class);
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ─── afterHandle: failures are swallowed (best-effort side effect) ──────────

  @Test
  public void afterHandleSwallowsExceptionAndStillRestoresContextMode() {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID))
          .thenThrow(new RuntimeException("DB unavailable"));

      assertNull(handler.afterHandle(ctx));
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  // ─── afterHandle: ETP-5117 twice-a-day auto-send schedule ───────────────────

  private static final String AUTO_SEND_SCHEDULE_DESCRIPTION =
      "Automatic TicketBAI invoice sending (Etendo GO)";
  private static final String USER_ID = "user-001";
  private static final String ROLE_ID = "role-001";

  /**
   * Injects a mock {@link SiiTbaiAutoSendScheduleService} into the handler's private final
   * {@code scheduleService} field via reflection, bypassing its real construction so the
   * ETP-5117 auto-send scheduling call can be verified in isolation from the DAL wiring
   * {@link SiiTbaiAutoSendScheduleService} itself needs — same convention as
   * {@code SalesInvoiceHeaderHandlerTest#handlerWithTotalDiscountMock}.
   */
  private static TbaiConfigSequenceHandler handlerWithScheduleServiceMock(
      SiiTbaiAutoSendScheduleService mockScheduleService) throws Exception {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    Field field = TbaiConfigSequenceHandler.class.getDeclaredField("scheduleService");
    field.setAccessible(true);
    field.set(handler, mockScheduleService);
    return handler;
  }

  private static OBContext mockObContextWithUserAndRole() {
    OBContext obContext = mock(OBContext.class);
    User user = mock(User.class);
    when(user.getId()).thenReturn(USER_ID);
    Role role = mock(Role.class);
    when(role.getId()).thenReturn(ROLE_ID);
    when(obContext.getUser()).thenReturn(user);
    when(obContext.getRole()).thenReturn(role);
    return obContext;
  }

  /**
   * A non-deactivating PUT on an active config triggers {@code scheduleAutoSendIfActive}
   * ALONGSIDE (not instead of) {@code ensureTbaiSequences} — both the chaining sequence
   * assignment and the auto-send schedule creation happen in the same {@code afterHandle} call.
   */
  @Test
  public void afterHandleTriggersAutoSendScheduleAlongsideSequenceAssignmentForActiveConfig() {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler;
    try {
      handler = handlerWithScheduleServiceMock(scheduleService);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .obContext(mockObContextWithUserAndRole())
        .build();

    Client client = mock(Client.class);
    when(client.getId()).thenReturn(CLIENT_ID);
    Organization configOrg = mock(Organization.class);
    when(configOrg.getId()).thenReturn(ORG_ID);
    when(configOrg.getName()).thenReturn(ORG_NAME);
    TbaiConfig config = mock(TbaiConfig.class);
    when(config.getClient()).thenReturn(client);
    when(config.getOrganization()).thenReturn(configOrg);
    when(config.isActive()).thenReturn(true);

    DocumentType docType = mock(DocumentType.class);
    when(docType.getTbaiAdSequence()).thenReturn(null);

    Sequence sequence = mock(Sequence.class);

    when(scheduleService.ensureAutoSendSchedule(eq(CLIENT_ID), eq(ORG_ID), eq(USER_ID), eq(ROLE_ID),
        eq(SiiTbaiAutoSendScheduleService.TBAI_PROCESS_SEARCH_KEY), eq(AUTO_SEND_SCHEDULE_DESCRIPTION)))
        .thenReturn("req-new");

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBContext staticObContext = mock(OBContext.class);
      OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
      when(osp.getNaturalTree(ORG_ID)).thenReturn(Collections.singleton(ORG_ID));
      when(staticObContext.getOrganizationStructureProvider()).thenReturn(osp);
      obCtxMock.when(OBContext::getOBContext).thenReturn(staticObContext);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      @SuppressWarnings("unchecked")
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(docType));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Sequence.class)).thenReturn(sequence);

      assertNull(handler.afterHandle(ctx));

      // ensureTbaiSequences still ran (regression: existing sequence-assignment behavior).
      verify(docType).setTbaiAdSequence(sequence);
      verify(obDal).save(docType);
      verify(obDal).save(sequence);

      // scheduleAutoSendIfActive ran alongside it.
      verify(scheduleService).ensureAutoSendSchedule(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID,
          SiiTbaiAutoSendScheduleService.TBAI_PROCESS_SEARCH_KEY, AUTO_SEND_SCHEDULE_DESCRIPTION);
      verify(scheduleService).activateSchedule("req-new");
      // Regression (ETP-5117 follow-up): a non-deactivating save never triggers the cleanup path.
      verify(scheduleService, never()).unscheduleAutoSend(any(), any(), any());
    }
  }

  /**
   * When the saved config resolves {@code isActive() == false}, {@code ensureTbaiSequences}
   * still runs (chaining sequences must survive a pause/resume — unaffected regression), but
   * {@code scheduleAutoSendIfActive} does not create a schedule for a config that isn't active.
   */
  @Test
  public void afterHandleStillAssignsSequencesButSkipsAutoSendScheduleForInactiveConfig() {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler;
    try {
      handler = handlerWithScheduleServiceMock(scheduleService);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .obContext(mockObContextWithUserAndRole())
        .build();

    Client client = mock(Client.class);
    Organization configOrg = mock(Organization.class);
    when(configOrg.getId()).thenReturn(ORG_ID);
    when(configOrg.getName()).thenReturn(ORG_NAME);
    TbaiConfig config = mock(TbaiConfig.class);
    when(config.getClient()).thenReturn(client);
    when(config.getOrganization()).thenReturn(configOrg);
    when(config.isActive()).thenReturn(false);

    DocumentType docType = mock(DocumentType.class);
    when(docType.getTbaiAdSequence()).thenReturn(null);

    Sequence sequence = mock(Sequence.class);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBContext staticObContext = mock(OBContext.class);
      OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
      when(osp.getNaturalTree(ORG_ID)).thenReturn(Collections.singleton(ORG_ID));
      when(staticObContext.getOrganizationStructureProvider()).thenReturn(osp);
      obCtxMock.when(OBContext::getOBContext).thenReturn(staticObContext);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      @SuppressWarnings("unchecked")
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(docType));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Sequence.class)).thenReturn(sequence);

      assertNull(handler.afterHandle(ctx));

      // Regression: sequence assignment is unaffected by the config's active flag.
      verify(docType).setTbaiAdSequence(sequence);
      verify(obDal).save(docType);

      // No schedule is created for an inactive config, and — since this is a non-deactivating PUT
      // (no "active" field in the request body) — the ETP-5117 follow-up cleanup path never
      // triggers either.
      verifyNoInteractions(scheduleService);
    }
  }

  /**
   * {@code scheduleAutoSendIfActive} must not attempt to schedule when the request carries no
   * {@link OBContext} (or one with no {@code Role}) — there is no user/role to run the scheduled
   * process as. {@code ensureTbaiSequences} is unaffected and still runs (it needs no
   * {@link OBContext} beyond the static {@code OBContext.getOBContext()} used for the org tree).
   * Guards against a regression that would call {@code ensureAutoSendSchedule} with a null
   * user/role id, producing a broken {@code AD_Process_Request} row.
   */
  @Test
  public void afterHandleSkipsAutoSendScheduleWhenObContextHasNoRole() {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler;
    try {
      handler = handlerWithScheduleServiceMock(scheduleService);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }

    OBContext obContextNoRole = mock(OBContext.class);
    User user = mock(User.class);
    when(user.getId()).thenReturn(USER_ID);
    when(obContextNoRole.getUser()).thenReturn(user);
    when(obContextNoRole.getRole()).thenReturn(null);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .obContext(obContextNoRole)
        .build();

    Client client = mock(Client.class);
    Organization configOrg = mock(Organization.class);
    when(configOrg.getId()).thenReturn(ORG_ID);
    when(configOrg.getName()).thenReturn(ORG_NAME);
    TbaiConfig config = mock(TbaiConfig.class);
    when(config.getClient()).thenReturn(client);
    when(config.getOrganization()).thenReturn(configOrg);
    when(config.isActive()).thenReturn(true);

    DocumentType docType = mock(DocumentType.class);
    when(docType.getTbaiAdSequence()).thenReturn(null);

    Sequence sequence = mock(Sequence.class);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBContext staticObContext = mock(OBContext.class);
      OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
      when(osp.getNaturalTree(ORG_ID)).thenReturn(Collections.singleton(ORG_ID));
      when(staticObContext.getOrganizationStructureProvider()).thenReturn(osp);
      obCtxMock.when(OBContext::getOBContext).thenReturn(staticObContext);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      @SuppressWarnings("unchecked")
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(docType));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Sequence.class)).thenReturn(sequence);

      assertNull(handler.afterHandle(ctx));

      // Regression: sequence assignment is unaffected by the missing Role.
      verify(docType).setTbaiAdSequence(sequence);
      verify(obDal).save(docType);

      // No schedule is created without a resolvable user/role, even though the config is active.
      verifyNoInteractions(scheduleService);
    }
  }

  // ─── afterHandle: skips sequence assignment when handle() already deleted ─────

  /**
   * Regression guard for ETP-4785: when {@link TbaiConfigSequenceHandler#handle} already
   * deleted the config record (smart deactivation), {@code afterHandle} must detect the
   * {@code deleted:true} marker in the {@code preResult} and skip sequence assignment — trying
   * to load a deleted config would either return null (and silently no-op) or fail with a DB
   * error. This asserts the guard is active so the behaviour is explicit and stable.
   */
  @Test
  public void afterHandleSkipsSequenceAssignmentWhenPreResultIndicatesDeleted() throws Exception {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();

    JSONObject deletedBody = new JSONObject().put("deleted", true);
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .recordId(RECORD_ID)
        .previousResult(new com.etendoerp.go.schemaforge.NeoResponse(200, deletedBody))
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(anyBoolean())).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      assertNull(handler.afterHandle(ctx));

      // OBContext.setAdminMode should never be called — the guard fires before entering
      // the try-block that wraps ensureTbaiSequences.
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
      verify(obDal, never()).get(eq(TbaiConfig.class), Mockito.anyString());
    }
  }

  // ─── ETP-5117 follow-up: unschedule cleanup runs in the PRE-hook, record-scoped ──

  /**
   * The organization id a real Etendo GO client-admin session reports: {@code '0'}, the {@code '*'}
   * org. The acting client-admin role has {@code ad_role.ad_org_id = '0'} and the user carries no
   * {@code default_ad_org_id}, so {@code OBContext.getCurrentOrganization()} is <em>never</em> the
   * business organization the config record — and therefore its auto-send schedule — belongs to.
   * Resolving the cleanup scope from the session is what made this feature silently no-op in live
   * testing while four rounds of unit tests stayed green, because those tests mocked the session
   * organization equal to the record's.
   */
  private static final String SESSION_ORG_ID = "0";

  /** A session client id deliberately different from the record's, for the same reason. */
  private static final String SESSION_CLIENT_ID = "session-client-999";

  /** A real business organization id, the kind a config record actually belongs to. */
  private static final String RECORD_ORG_ID = "C0376D5E8CFA4D4A8870D956E14CE5A4";

  /**
   * Builds an {@link OBContext} shaped like a real Etendo GO client-admin session: current
   * organization {@code '0'} (the {@code '*'} org) and a client id different from the record's.
   * Any test that resolves the cleanup scope from this context instead of from the record proves
   * nothing — the production bug is precisely that this context does not describe the record.
   */
  private static OBContext mockGoClientAdminSessionObContext() {
    OBContext session = mock(OBContext.class);
    Client sessionClient = mock(Client.class);
    when(sessionClient.getId()).thenReturn(SESSION_CLIENT_ID);
    Organization starOrg = mock(Organization.class);
    when(starOrg.getId()).thenReturn(SESSION_ORG_ID);
    when(session.getCurrentClient()).thenReturn(sessionClient);
    when(session.getCurrentOrganization()).thenReturn(starOrg);
    return session;
  }

  /** A {@link TbaiConfig} that answers for its own client/organization. */
  private static TbaiConfig mockConfigOwnedBy(String clientId, String orgId) {
    Client client = mock(Client.class);
    when(client.getId()).thenReturn(clientId);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(orgId);
    TbaiConfig config = mock(TbaiConfig.class);
    when(config.getClient()).thenReturn(client);
    when(config.getOrganization()).thenReturn(org);
    return config;
  }

  /**
   * THE regression test for the live bug. A deactivating PUT arrives on a GO client-admin session
   * whose current organization is {@code '0'} and whose current client differs from the record's;
   * the config record itself belongs to a business organization. The schedule must be removed with
   * the <b>record's</b> client/organization, and the session's must never be consulted at all.
   */
  @Test
  public void handlePutDeactivatingUnschedulesWithTheRecordScopeNotTheSessionOrg() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler = handlerWithScheduleServiceMock(scheduleService);

    OBContext session = mockGoClientAdminSessionObContext();
    NeoContext ctx = NeoContext.builder()
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", false))
        .recordId(RECORD_ID)
        .obContext(session)
        .build();

    TbaiConfig config = mockConfigOwnedBy(CLIENT_ID, RECORD_ORG_ID);
    when(config.getTbaisystemdate()).thenReturn(null);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      NeoResponse result = handler.handle(ctx);

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());

      // The record's own scope — the only one that can match the schedule row.
      verify(scheduleService).unscheduleAutoSend(CLIENT_ID, RECORD_ORG_ID,
          SiiTbaiAutoSendScheduleService.TBAI_PROCESS_SEARCH_KEY);
      // Never the session's client, never the session's org '0'.
      verify(scheduleService, never()).unscheduleAutoSend(eq(SESSION_CLIENT_ID), any(), any());
      verify(scheduleService, never()).unscheduleAutoSend(any(), eq(SESSION_ORG_ID), any());
      // Stronger still: the session context is not even asked.
      verify(session, never()).getCurrentOrganization();
      verify(session, never()).getCurrentClient();
    }
  }

  /**
   * Same regression, DELETE entry point: GO's "Eliminar" action sends a real {@code DELETE}, which
   * never reaches {@code smartDeactivate}. The {@code beforeDelete} pre-hook must resolve the scope
   * from the record while it still exists, never from the session's {@code '0'} organization.
   */
  @Test
  public void handleDeleteUnschedulesWithTheRecordScopeNotTheSessionOrg() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler = handlerWithScheduleServiceMock(scheduleService);

    OBContext session = mockGoClientAdminSessionObContext();
    NeoContext ctx = NeoContext.builder()
        .httpMethod("DELETE")
        .recordId(RECORD_ID)
        .obContext(session)
        .build();

    TbaiConfig config = mockConfigOwnedBy(CLIENT_ID, RECORD_ORG_ID);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      // null so NEO's default CRUD still performs the delete.
      assertNull(handler.handle(ctx));

      verify(scheduleService).unscheduleAutoSend(CLIENT_ID, RECORD_ORG_ID,
          SiiTbaiAutoSendScheduleService.TBAI_PROCESS_SEARCH_KEY);
      verify(scheduleService, never()).unscheduleAutoSend(eq(SESSION_CLIENT_ID), any(), any());
      verify(scheduleService, never()).unscheduleAutoSend(any(), eq(SESSION_ORG_ID), any());
      verify(session, never()).getCurrentOrganization();
      verify(session, never()).getCurrentClient();
    }
  }

  /**
   * Deactivating PUT, delete outcome (no adoption date): the schedule is removed <em>before</em>
   * the record is removed. Ordering is the whole point — after the removal the record can no longer
   * answer for its client/organization, which is exactly how the old design ended up guessing.
   */
  @Test
  public void handlePutDeactivatingUnschedulesBeforeRemovingTheRecord() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler = handlerWithScheduleServiceMock(scheduleService);

    TbaiConfig config = mockConfigOwnedBy(CLIENT_ID, ORG_ID);
    when(config.getTbaisystemdate()).thenReturn(null);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build());

      assertNotNull(result);
      assertEquals(true, result.getBody().getBoolean("deleted"));

      InOrder ordered = inOrder(scheduleService, obDal);
      ordered.verify(scheduleService).unscheduleAutoSend(CLIENT_ID, ORG_ID,
          SiiTbaiAutoSendScheduleService.TBAI_PROCESS_SEARCH_KEY);
      ordered.verify(obDal).remove(config);
      ordered.verify(obDal).flush();
    }
  }

  /**
   * Deactivating PUT, fall-through outcome (adoption date set AND TicketBAI invoices exist, so the
   * record survives and default CRUD deactivates it): the schedule is still removed, and
   * {@code smartDeactivate} still returns {@code null} so the fall-through happens.
   */
  @Test
  public void handlePutDeactivatingFallThroughStillUnschedulesAndReturnsNull() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler = handlerWithScheduleServiceMock(scheduleService);

    TbaiConfig config = mockConfigOwnedBy(CLIENT_ID, ORG_ID);
    when(config.getTbaisystemdate()).thenReturn(new Date());

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      @SuppressWarnings("unchecked")
      OBCriteria<Invoice> crit = mock(OBCriteria.class);
      when(obDal.createCriteria(Invoice.class)).thenReturn(crit);
      when(crit.add(any())).thenReturn(crit);
      when(crit.setProjection(any())).thenReturn(crit);
      when(crit.uniqueResult()).thenReturn(9L);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build()));

      // Both outcomes of smartDeactivate unschedule — this is the fall-through one.
      verify(scheduleService).unscheduleAutoSend(CLIENT_ID, ORG_ID,
          SiiTbaiAutoSendScheduleService.TBAI_PROCESS_SEARCH_KEY);
      verify(obDal, never()).remove(any());
    }
  }

  /**
   * A genuine {@code DELETE} must leave NEO's default CRUD alone: {@code handle()} returns
   * {@code null} so the hard delete still happens, and the schedule is removed with the record's
   * own client/organization on the way there.
   */
  @Test
  public void handleDeleteReturnsNullSoDefaultCrudProceedsAndUnschedulesRecordScope()
      throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler = handlerWithScheduleServiceMock(scheduleService);

    TbaiConfig config = mockConfigOwnedBy(CLIENT_ID, ORG_ID);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("DELETE")
          .recordId(RECORD_ID)
          .build()));

      verify(scheduleService).unscheduleAutoSend(CLIENT_ID, ORG_ID,
          SiiTbaiAutoSendScheduleService.TBAI_PROCESS_SEARCH_KEY);
      // The pre-hook never deletes anything itself — default CRUD owns the delete.
      verify(obDal, never()).remove(any());
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  /**
   * A {@code DELETE} with a blank/absent record id: nothing to look up and nothing to unschedule.
   * The guard fires before admin mode is even entered, and {@code handle()} still returns
   * {@code null}.
   */
  @Test
  public void handleDeleteWithBlankRecordIdDoesNothingAtAll() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler = handlerWithScheduleServiceMock(scheduleService);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("DELETE")
          .recordId("   ")
          .build()));
      assertNull(handler.handle(NeoContext.builder().httpMethod("DELETE").build()));

      verifyNoInteractions(scheduleService);
      verifyNoInteractions(obDal);
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }

  /**
   * A {@code DELETE} whose record cannot be loaded (already gone, or invisible to this session):
   * the cleanup is skipped entirely rather than falling back to a guessed scope. Guessing is the
   * bug — a wrongly-scoped {@code unscheduleAutoSend} silently matches nothing and hides the
   * failure. {@code handle()} still returns {@code null}.
   */
  @Test
  public void handleDeleteWithUnloadableRecordNeverGuessesAScope() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler = handlerWithScheduleServiceMock(scheduleService);

    OBContext session = mockGoClientAdminSessionObContext();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(null);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("DELETE")
          .recordId(RECORD_ID)
          .obContext(session)
          .build()));

      verifyNoInteractions(scheduleService);
      verify(session, never()).getCurrentOrganization();
      verify(session, never()).getCurrentClient();
    }
  }

  /**
   * The cleanup is a side effect and must never block the operation the user asked for: when
   * {@code unscheduleAutoSend} blows up during a {@code DELETE}, the exception is swallowed and
   * {@code handle()} still returns {@code null} so default CRUD deletes the record.
   */
  @Test
  public void handleDeleteSwallowsUnscheduleFailureAndStillLetsTheDeleteProceed() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler = handlerWithScheduleServiceMock(scheduleService);
    doThrow(new RuntimeException("scheduler down")).when(scheduleService)
        .unscheduleAutoSend(any(), any(), any());

    TbaiConfig config = mockConfigOwnedBy(CLIENT_ID, ORG_ID);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      assertNull(handler.handle(NeoContext.builder()
          .httpMethod("DELETE")
          .recordId(RECORD_ID)
          .build()));

      verify(scheduleService).unscheduleAutoSend(CLIENT_ID, ORG_ID,
          SiiTbaiAutoSendScheduleService.TBAI_PROCESS_SEARCH_KEY);
      obCtxMock.verify(OBContext::restorePreviousMode, times(1));
    }
  }

  /**
   * Same swallow contract on the deactivating-PUT entry point: an {@code unscheduleAutoSend}
   * failure must not abort {@code smartDeactivate} — the record is still deleted and the
   * {@code {"deleted":true}} response is still returned.
   */
  @Test
  public void handlePutDeactivatingSwallowsUnscheduleFailureAndStillDeletes() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler = handlerWithScheduleServiceMock(scheduleService);
    doThrow(new RuntimeException("scheduler down")).when(scheduleService)
        .unscheduleAutoSend(any(), any(), any());

    TbaiConfig config = mockConfigOwnedBy(CLIENT_ID, ORG_ID);
    when(config.getTbaisystemdate()).thenReturn(null);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class)) {
      obCtxMock.when(() -> OBContext.setAdminMode(true)).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      NeoResponse result = handler.handle(NeoContext.builder()
          .httpMethod("PUT")
          .requestBody(new JSONObject().put("active", false))
          .recordId(RECORD_ID)
          .build());

      assertNotNull(result);
      assertEquals(200, result.getHttpStatus());
      assertEquals(true, result.getBody().getBoolean("deleted"));
      verify(obDal).remove(config);
    }
  }

  // ─── afterHandle(): no longer unschedules — the pre-hook owns cleanup now ────

  /**
   * TBAI-specific: a deactivating PUT still runs {@code ensureTbaiSequences} in
   * {@code afterHandle} — chaining sequences must survive a pause/resume — but it no longer
   * unschedules there. That cleanup already ran in {@code smartDeactivate}, scoped to the record.
   * {@code scheduleAutoSendIfActive} also correctly no-ops for a config that resolved inactive.
   */
  @Test
  public void afterHandlePutDeactivatingStillRunsSequenceAssignmentAndNoLongerUnschedules()
      throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler = handlerWithScheduleServiceMock(scheduleService);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("PUT")
        .requestBody(new JSONObject().put("active", false))
        .recordId(RECORD_ID)
        .obContext(mockGoClientAdminSessionObContext())
        .build();

    TbaiConfig config = mockConfigOwnedBy(CLIENT_ID, ORG_ID);
    when(config.getOrganization().getName()).thenReturn(ORG_NAME);
    when(config.isActive()).thenReturn(false); // deactivated by the incoming PUT

    DocumentType docType = mock(DocumentType.class);
    when(docType.getTbaiAdSequence()).thenReturn(null);

    Sequence sequence = mock(Sequence.class);

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      obCtxMock.when(() -> OBContext.setAdminMode(anyBoolean())).then(inv -> null);
      obCtxMock.when(OBContext::restorePreviousMode).then(inv -> null);

      OBContext staticObContext = mock(OBContext.class);
      OrganizationStructureProvider osp = mock(OrganizationStructureProvider.class);
      when(osp.getNaturalTree(ORG_ID)).thenReturn(Collections.singleton(ORG_ID));
      when(staticObContext.getOrganizationStructureProvider()).thenReturn(osp);
      obCtxMock.when(OBContext::getOBContext).thenReturn(staticObContext);

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(TbaiConfig.class, RECORD_ID)).thenReturn(config);

      @SuppressWarnings("unchecked")
      OBCriteria<DocumentType> criteria = mock(OBCriteria.class);
      when(obDal.createCriteria(DocumentType.class)).thenReturn(criteria);
      when(criteria.add(any())).thenReturn(criteria);
      when(criteria.list()).thenReturn(Collections.singletonList(docType));

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);
      when(obProvider.get(Sequence.class)).thenReturn(sequence);

      assertNull(handler.afterHandle(ctx));

      // ensureTbaiSequences still ran — the TBAI-specific difference from the SII handler.
      verify(docType).setTbaiAdSequence(sequence);
      verify(obDal).save(docType);
      verify(obDal).save(sequence);

      // But afterHandle no longer touches the schedule at all.
      verifyNoInteractions(scheduleService);
    }
  }

  /**
   * TBAI-specific: a genuine {@code DELETE} returns from {@code afterHandle} without touching the
   * sequences (the config record is going away, so there is no scope left to assign chaining
   * sequences to) and without unscheduling — that ran in the {@code beforeDelete} pre-hook.
   */
  @Test
  public void afterHandleDeleteSkipsSequenceAssignmentAndNoLongerUnschedules() throws Exception {
    SiiTbaiAutoSendScheduleService scheduleService = mock(SiiTbaiAutoSendScheduleService.class);
    TbaiConfigSequenceHandler handler = handlerWithScheduleServiceMock(scheduleService);

    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("DELETE")
        .recordId(RECORD_ID)
        .obContext(mockGoClientAdminSessionObContext())
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class);
        MockedStatic<OBDal> obDalMock = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProviderMock = mockStatic(OBProvider.class)) {

      OBDal obDal = mock(OBDal.class);
      obDalMock.when(OBDal::getInstance).thenReturn(obDal);

      OBProvider obProvider = mock(OBProvider.class);
      obProviderMock.when(OBProvider::getInstance).thenReturn(obProvider);

      assertNull(handler.afterHandle(ctx));

      verifyNoInteractions(scheduleService);
      verifyNoInteractions(obDal);
      verify(obProvider, never()).get(Sequence.class);
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    }
  }
}
