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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Criterion;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Sequence;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
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

  // ─── handle(): fail-safe on unexpected exception ─────────────────────────────

  @Test
  public void handleReturnsNullOnUnexpectedException() throws Exception {
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
      // Fail safe: returns null (let default CRUD handle it).
      assertNull(result);
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

  @Test
  public void afterHandleReturnsNullForCrudDeleteMethod() {
    TbaiConfigSequenceHandler handler = new TbaiConfigSequenceHandler();
    NeoContext ctx = NeoContext.builder()
        .endpointType(NeoEndpointType.CRUD)
        .httpMethod("DELETE")
        .recordId(RECORD_ID)
        .build();

    try (MockedStatic<OBContext> obCtxMock = mockStatic(OBContext.class)) {
      assertNull(handler.afterHandle(ctx));
      obCtxMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
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
}
