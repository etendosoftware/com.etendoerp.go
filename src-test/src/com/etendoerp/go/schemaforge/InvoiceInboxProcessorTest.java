/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;

import com.etendoerp.go.schemaforge.InvoiceInboxWorkStore.Claimed;
import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.OcrDocType;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.AddressState;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Candidate;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicates;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Tax;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.TaxAnswer;
import com.etendoerp.go.schemaforge.ocr.OcrServiceClient;
import com.etendoerp.go.schemaforge.ocr.OcrUnavailableException;

/**
 * One inbox row through the worker, with a fake store, a fake extraction service and a fake sleeper:
 * which end state each outcome reaches, how many times the paid service is called and how long the
 * worker waits between tries, that the stored extraction is never paid for twice, that a row ended
 * meanwhile is not overwritten, that the whole unit (attachment read, matching and final write) runs in
 * admin mode under the row's own context, and that the context, the session and the temporary file are
 * always cleaned.
 * The SQL of the store is covered by {@code InvoiceInboxWorkStoreIntegrationTest}.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxProcessor
 */
class InvoiceInboxProcessorTest {

  private static final String ID = "ROW1";
  private static final String USER = "U1";
  private static final String ROLE = "R1";
  private static final String CLIENT = "C1";
  private static final String ORG = "O1";
  private static final String ORG_TAX_ID = "A28000727";
  private static final String VENDOR_TAX_ID = "B81639719";
  private static final String UNREADABLE = "UNREADABLE";
  private static final String SERVICE_FAILURE = "SERVICE_FAILURE";
  private static final int MAX_ATTEMPTS = 3;

  private MockedStatic<OBContext> context;
  private MockedStatic<OBDal> dalStatic;
  private MockedStatic<NeoAttachmentsHelper> attachments;
  private MockedStatic<OcrLookupsRegistry> registry;
  private OBDal dal;
  private InvoiceInboxWorkStore store;
  private OcrServiceClient client;
  private OcrLookups lookups;
  private IncomingDocument row;
  private BusinessPartner vendor;
  private File tempFile;
  private InvoiceInboxProcessor processor;

  /** What the worker did to the context, in order. */
  private final List<String> contextCalls = new ArrayList<>();
  /** Seconds the worker waited before each OCR attempt. */
  private final List<Long> sleeps = new ArrayList<>();
  /** The admin-mode depth the fake context keeps, as OBContext's stack would. */
  private int adminDepth;
  private final List<Boolean> adminAtMatching = new ArrayList<>();
  /** The last context call seen each time admin mode was entered. */
  private final List<String> contextWhenAdminEntered = new ArrayList<>();
  private final List<Boolean> adminAtLock = new ArrayList<>();
  private final List<Boolean> adminAtSave = new ArrayList<>();
  private final List<Boolean> adminAtAttachment = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    context = mockStatic(OBContext.class);
    context.when(() -> OBContext.setOBContext(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> contextCalls.add(describe(invocation.getArguments())));
    context.when(() -> OBContext.setOBContext(org.mockito.ArgumentMatchers.<OBContext>any()))
        .thenAnswer(invocation -> contextCalls.add("cleared"));
    context.when(() -> OBContext.setAdminMode(anyBoolean())).thenAnswer(invocation -> {
      contextWhenAdminEntered.add(contextCalls.get(contextCalls.size() - 1));
      adminDepth++;
      return null;
    });
    context.when(OBContext::restorePreviousMode).thenAnswer(invocation -> {
      adminDepth--;
      return null;
    });

    dal = mock(OBDal.class);
    dalStatic = mockStatic(OBDal.class);
    dalStatic.when(OBDal::getInstance).thenReturn(dal);
    row = mock(IncomingDocument.class);
    Client client1 = mock(Client.class);
    when(client1.getId()).thenReturn(CLIENT);
    when(row.getClient()).thenReturn(client1);
    when(row.getId()).thenReturn(ID);
    when(row.getStatus()).thenReturn("PENDING_REVIEW");
    when(dal.get(IncomingDocument.class, ID)).thenReturn(row);
    vendor = mock(BusinessPartner.class);
    when(dal.get(BusinessPartner.class, "V1")).thenReturn(vendor);
    doAnswer(invocation -> {
      adminAtSave.add(adminDepth > 0);
      return null;
    }).when(dal).save(any());
    @SuppressWarnings("unchecked")
    OBQuery<IncomingDocument> query = mock(OBQuery.class);
    when(query.list()).thenReturn(List.of());
    when(dal.createQuery(eq(IncomingDocument.class), anyString())).thenReturn(query);

    store = mock(InvoiceInboxWorkStore.class);
    when(store.claim(ID, InvoiceInboxProcessor.LEASE_MINUTES, MAX_ATTEMPTS)).thenReturn(claimed(null));
    when(store.roleFor(USER, CLIENT)).thenReturn(ROLE);
    when(store.lockIfProcessing(ID)).thenAnswer(invocation -> {
      adminAtLock.add(adminDepth > 0);
      return true;
    });

    tempFile = new File(System.getProperty("java.io.tmpdir"), "inbox-processor-test/invoice.pdf");
    attachments = mockStatic(NeoAttachmentsHelper.class);
    attachments.when(() -> NeoAttachmentsHelper.copyMainAttachmentToTemp(anyString(), anyString()))
        .thenAnswer(invocation -> {
          adminAtAttachment.add(adminDepth > 0);
          return tempFile;
        });

    lookups = fakeLookups();
    registry = mockStatic(OcrLookupsRegistry.class);
    registry.when(() -> OcrLookupsRegistry.forResolver("purchase-invoice"))
        .thenReturn(Optional.of(lookups));

    client = mock(OcrServiceClient.class);
    when(client.extract(any(File.class), any(OcrDocType.class))).thenReturn(readable());
    processor = new InvoiceInboxProcessor(store, client, seconds -> sleeps.add(seconds));
  }

  @AfterEach
  void tearDown() {
    registry.close();
    attachments.close();
    dalStatic.close();
    context.close();
    Thread.interrupted();
  }

  // ---------------------------------------------------------------- fixtures

  private static String describe(Object[] args) {
    return "ctx(" + String.join(",", java.util.Arrays.stream(args).map(String::valueOf)
        .toArray(String[]::new)) + ")";
  }

  private static Claimed claimed(String extractedData) {
    return new Claimed(USER, CLIENT, ORG, InvoiceInboxIntake.TARGET_PURCHASE_INVOICE, 1L,
        extractedData);
  }

  /** The tool's answer for a readable invoice from {@code B81639719} to the organization. */
  private static JSONObject readable() throws JSONException {
    return new JSONObject().put("answer", answerWith(ORG_TAX_ID));
  }

  private static JSONObject answerWith(String receiverTaxId) throws JSONException {
    return new JSONObject().put("vendor_name", "Acme S.L.").put("document_no", "F-1")
        .put("invoice_date", "2026-10-30").put("total_amount", 121.0)
        .put("issuer", new JSONObject().put("name", "Acme S.L.").put("tax_id_raw", VENDOR_TAX_ID))
        .put("receiver", new JSONObject().put("tax_id_raw", receiverTaxId))
        .put("line_items", new JSONArray().put(new JSONObject().put("description", "Widget")
            .put("quantity", 2).put("unit_price", 50).put("tax_rate", 21).put("tax_label", "IVA")));
  }

  /** Lookups that know the vendor, its address, the product and the tax; they note the admin mode. */
  private OcrLookups fakeLookups() {
    OcrLookups fake = mock(OcrLookups.class);
    when(fake.organizationTaxId()).thenReturn(ORG_TAX_ID);
    when(fake.vendorsByTaxId(anyString(), anyInt())).thenAnswer(invocation -> {
      adminAtMatching.add(adminDepth > 0);
      return List.of(new Party("V1", "Acme S.L."));
    });
    when(fake.vendorAddress("V1")).thenReturn(new Address(AddressState.PRESENT, "LOC1"));
    when(fake.duplicates(anyString(), anyString())).thenReturn(new Duplicates(true, List.of()));
    when(fake.productCandidates(any())).thenReturn(List.of(List.of(
        new Candidate("P1", "Widget", "99"))));
    when(fake.defaultTax(anyString(), any(), any())).thenReturn(new TaxAnswer(true,
        new Tax("T21", "IVA 21%", new BigDecimal("21")), List.of()));
    return fake;
  }

  private void assertCleanFinish() {
    assertEquals(0, adminDepth, "admin mode must be balanced");
    assertEquals("cleared", contextCalls.get(contextCalls.size() - 1), "the context is cleared last");
    verify(dal, org.mockito.Mockito.atLeastOnce()).rollbackAndClose();
  }

  private void assertTempFileRemoved() {
    attachments.verify(() -> NeoAttachmentsHelper.cleanupTempFile(tempFile), times(1));
  }

  // ---------------------------------------------------------------- the good path

  @Test
  @DisplayName("a readable invoice ends in PENDING_REVIEW: extraction stored first, then the document, the lease released")
  void aReadableInvoiceEndsPendingReview() throws Exception {
    processor.runOne(ID);

    InOrder order = inOrder(store, dal);
    ArgumentCaptor<String> extracted = ArgumentCaptor.forClass(String.class);
    order.verify(store).storeExtracted(eq(ID), extracted.capture());
    order.verify(store).lockIfProcessing(ID);
    order.verify(dal).save(row);
    order.verify(dal).commitAndClose();
    JSONObject stored = new JSONObject(extracted.getValue());
    assertEquals("purchase-invoice", stored.getJSONObject("_meta").getString("docType"));
    assertEquals("F-1", stored.getString("document_no"));

    ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
    verify(row, org.mockito.Mockito.atLeastOnce()).setStatus(status.capture());
    assertEquals("PENDING_REVIEW", status.getValue());
    ArgumentCaptor<String> current = ArgumentCaptor.forClass(String.class);
    verify(row).setCurrentData(current.capture());
    JSONObject document = new JSONObject(current.getValue());
    assertTrue(document.getJSONObject("validation").getBoolean("valid"));
    assertEquals("linked", document.getJSONObject("header").getJSONObject("vendor")
        .getString("state"));
    verify(row).setBusinessPartner(vendor);
    verify(row).setProcessingLease(null);
    verify(row).setProcessedAt(any());
    verify(row, org.mockito.Mockito.atLeastOnce()).setErrorCode(null);
    verify(store, never()).fail(anyString(), anyString());
    verify(store, never()).failOrRelease(anyString(), anyInt());
    assertEquals(List.of(0L), sleeps, "one attempt, no wait before the first");
    assertTempFileRemoved();
    assertCleanFinish();
  }

  @Test
  @DisplayName("the worker never writes ADJUSTED or REVIEWED: automatic links are not a user adjustment")
  void theWorkerNeverMarksTheRowAdjusted() throws Exception {
    processor.runOne(ID);

    verify(row, never()).setAdjusted(any());
    verify(row, never()).setReviewed(any());
  }

  @Test
  @DisplayName("the worker acts as the row's user, client and organization with the user's role")
  void theContextIsTheRows() throws Exception {
    processor.runOne(ID);

    assertTrue(contextCalls.contains("ctx(" + USER + "," + ROLE + "," + CLIENT + "," + ORG + ")"),
        contextCalls.toString());
    assertEquals("ctx(100,0,0,0)", contextCalls.get(0), "it starts as the system user to claim");
  }

  @Test
  @DisplayName("the attachment read, the matching and the final write all run in admin mode under the row's context")
  void theWholeUnitRunsInAdminMode() throws Exception {
    processor.runOne(ID);

    assertEquals(List.of(true), adminAtAttachment, "the attachment is read in admin mode");
    assertEquals(List.of(true), adminAtMatching, "the matching lookups run in admin mode");
    assertEquals(List.of(true), adminAtLock, "the row is locked in admin mode");
    assertEquals(List.of(true), adminAtSave, "the row is saved in admin mode");
    assertEquals(List.of("ctx(" + USER + "," + ROLE + "," + CLIENT + "," + ORG + ")"),
        contextWhenAdminEntered, "admin mode is entered once, after the row's own context is set");
  }

  @Test
  @DisplayName("admin mode is balanced and the context cleared after a normal pass")
  void adminModeDoesNotLeakAfterAPass() throws Exception {
    processor.runOne(ID);

    assertEquals(0, adminDepth, "no admin mode left on the pool thread");
    assertEquals("cleared", contextCalls.get(contextCalls.size() - 1), "the context is cleared last");
  }

  @Test
  @DisplayName("admin mode is balanced and the context cleared when the matching throws")
  void adminModeDoesNotLeakWhenTheMatchingFails() throws Exception {
    when(lookups.vendorAddress("V1")).thenThrow(new IllegalStateException("db"));

    processor.runOne(ID);

    assertEquals(List.of(true), adminAtMatching, "the matching ran in admin mode");
    assertEquals(0, adminDepth, "admin mode restored even though the unit failed");
    assertEquals("cleared", contextCalls.get(contextCalls.size() - 1), "the context is cleared last");
  }

  @Test
  @DisplayName("a stored extraction skips the attachment but the matching still runs in admin mode")
  void aStoredExtractionStillMatchesInAdminMode() throws Exception {
    String stored = new JSONObject().put("vendor_name", "Acme S.L.").put("document_no", "F-1")
        .put("invoice_date", "2026-10-30").put("total_amount", 121.0)
        .put("issuer", new JSONObject().put("tax_id_raw", VENDOR_TAX_ID))
        .put("receiver", new JSONObject().put("tax_id_raw", ORG_TAX_ID))
        .put("line_items", new JSONArray().put(new JSONObject().put("description", "Widget")
            .put("quantity", 2).put("unit_price", 50).put("tax_rate", 21)))
        .put("_meta", new JSONObject().put("docType", "purchase-invoice")).toString();
    when(store.claim(ID, InvoiceInboxProcessor.LEASE_MINUTES, MAX_ATTEMPTS))
        .thenReturn(claimed(stored));

    processor.runOne(ID);

    assertTrue(adminAtAttachment.isEmpty(), "no attachment read");
    assertEquals(List.of(true), adminAtMatching);
    assertEquals(0, adminDepth);
  }

  @Test
  @DisplayName("a document of the same vendor and number already in the inbox is a warning inside the document")
  void aDuplicateInTheInboxIsAWarning() throws Exception {
    IncomingDocument other = mock(IncomingDocument.class);
    when(other.getId()).thenReturn("ROW0");
    when(other.getFileName()).thenReturn("earlier.pdf");
    @SuppressWarnings("unchecked")
    OBQuery<IncomingDocument> query = mock(OBQuery.class);
    when(query.list()).thenReturn(List.of(other));
    when(dal.createQuery(eq(IncomingDocument.class), anyString())).thenReturn(query);

    processor.runOne(ID);

    ArgumentCaptor<String> current = ArgumentCaptor.forClass(String.class);
    verify(row).setCurrentData(current.capture());
    JSONObject document = new JSONObject(current.getValue());
    JSONObject inbox = document.getJSONObject("header").getJSONObject("duplicate");
    assertEquals("ROW0", inbox.getJSONArray("inbox").getJSONObject(0).getString("id"));
    assertEquals("earlier.pdf", inbox.getJSONArray("inbox").getJSONObject(0).getString("fileName"));
    assertEquals("duplicate_in_inbox", document.getJSONArray("notices").getString(0));
    verify(query).setNamedParameter("vendor", "V1");
    verify(query).setNamedParameter("reference", "F-1");
    verify(query).setNamedParameter("self", ID);
    ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
    verify(row, org.mockito.Mockito.atLeastOnce()).setStatus(status.capture());
    assertEquals("PENDING_REVIEW", status.getValue(), "a duplicate is a warning, not an error");
  }

  // ---------------------------------------------------------------- not read

  @Test
  @DisplayName("an answer with nothing readable ends the row as ERROR / UNREADABLE, after one attempt")
  void nothingReadableIsUnreadable() throws Exception {
    when(client.extract(any(File.class), any(OcrDocType.class)))
        .thenReturn(new JSONObject().put("error", "could not read the file"));

    processor.runOne(ID);

    verify(store).fail(ID, UNREADABLE);
    verify(client, times(1)).extract(any(File.class), any(OcrDocType.class));
    verify(store, never()).storeExtracted(anyString(), anyString());
    verify(dal, never()).save(any());
    assertEquals(List.of(0L), sleeps);
    assertTempFileRemoved();
    assertCleanFinish();
  }

  @Test
  @DisplayName("a blank document (valid JSON, every field empty) is UNREADABLE too")
  void aBlankDocumentIsUnreadable() throws Exception {
    when(client.extract(any(File.class), any(OcrDocType.class))).thenReturn(
        new JSONObject().put("answer", new JSONObject().put("vendor_name", "")
            .put("line_items", new JSONArray())));

    processor.runOne(ID);

    verify(store).fail(ID, UNREADABLE);
    verify(store, never()).storeExtracted(anyString(), anyString());
  }

  @Test
  @DisplayName("a row with no main attachment is ERROR / UNREADABLE and the service is never called")
  void noAttachmentIsUnreadable() throws Exception {
    attachments.when(() -> NeoAttachmentsHelper.copyMainAttachmentToTemp(anyString(), anyString()))
        .thenReturn(null);

    processor.runOne(ID);

    verify(store).fail(ID, UNREADABLE);
    verify(client, never()).extract(any(File.class), any(OcrDocType.class));
    assertCleanFinish();
  }

  // ---------------------------------------------------------------- service failures and retries

  @Test
  @DisplayName("a service that keeps answering 5xx is tried exactly 3 times, waiting 0, 15 and 45 seconds")
  void aFailingServiceIsRetriedThreeTimes() throws Exception {
    when(client.extract(any(File.class), any(OcrDocType.class)))
        .thenThrow(new OcrUnavailableException("down", null, true));

    processor.runOne(ID);

    verify(client, times(3)).extract(any(File.class), any(OcrDocType.class));
    assertEquals(List.of(0L, 15L, 45L), sleeps);
    verify(store).fail(ID, SERVICE_FAILURE);
    verify(store, never()).storeExtracted(anyString(), anyString());
    assertTempFileRemoved();
    assertCleanFinish();
  }

  @Test
  @DisplayName("a client error (400) is not retried: one attempt, SERVICE_FAILURE")
  void aClientErrorIsNotRetried() throws Exception {
    when(client.extract(any(File.class), any(OcrDocType.class)))
        .thenThrow(new OcrUnavailableException("bad request", null, false));

    processor.runOne(ID);

    verify(client, times(1)).extract(any(File.class), any(OcrDocType.class));
    assertEquals(List.of(0L), sleeps);
    verify(store).fail(ID, SERVICE_FAILURE);
    assertTempFileRemoved();
  }

  @Test
  @DisplayName("a failure that passes on the second try is read: only one wait, and the row goes on")
  void aTransientFailureThenSuccess() throws Exception {
    when(client.extract(any(File.class), any(OcrDocType.class)))
        .thenThrow(new OcrUnavailableException("down", null, true)).thenReturn(readable());

    processor.runOne(ID);

    verify(client, times(2)).extract(any(File.class), any(OcrDocType.class));
    assertEquals(List.of(0L, 15L), sleeps);
    verify(store).storeExtracted(eq(ID), anyString());
    verify(store, never()).fail(anyString(), anyString());
    verify(dal).save(row);
  }

  @Test
  @DisplayName("a transient failure that turns into a client error stops retrying at once")
  void aClientErrorAfterATransientOneStops() throws Exception {
    when(client.extract(any(File.class), any(OcrDocType.class)))
        .thenThrow(new OcrUnavailableException("down", null, true))
        .thenThrow(new OcrUnavailableException("bad request", null, false));

    processor.runOne(ID);

    verify(client, times(2)).extract(any(File.class), any(OcrDocType.class));
    assertEquals(List.of(0L, 15L), sleeps);
    verify(store).fail(ID, SERVICE_FAILURE);
  }

  // ---------------------------------------------------------------- never paying twice

  @Test
  @DisplayName("a pass that finds the extraction already stored does not call the service or read the file")
  void aStoredExtractionIsNotReadAgain() throws Exception {
    String stored = new JSONObject().put("vendor_name", "Acme S.L.").put("document_no", "F-1")
        .put("invoice_date", "2026-10-30").put("total_amount", 121.0)
        .put("issuer", new JSONObject().put("tax_id_raw", VENDOR_TAX_ID))
        .put("receiver", new JSONObject().put("tax_id_raw", ORG_TAX_ID))
        .put("line_items", new JSONArray().put(new JSONObject().put("description", "Widget")
            .put("quantity", 2).put("unit_price", 50).put("tax_rate", 21)))
        .put("_meta", new JSONObject().put("docType", "purchase-invoice")).toString();
    when(store.claim(ID, InvoiceInboxProcessor.LEASE_MINUTES, MAX_ATTEMPTS))
        .thenReturn(claimed(stored));

    processor.runOne(ID);

    verify(client, never()).extract(any(File.class), any(OcrDocType.class));
    attachments.verify(() -> NeoAttachmentsHelper.copyMainAttachmentToTemp(anyString(), anyString()),
        never());
    verify(store, never()).storeExtracted(anyString(), anyString());
    assertTrue(sleeps.isEmpty());
    verify(dal).save(row);
    ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
    verify(row, org.mockito.Mockito.atLeastOnce()).setStatus(status.capture());
    assertEquals("PENDING_REVIEW", status.getValue());
    assertCleanFinish();
  }

  // ---------------------------------------------------------------- rows decided elsewhere

  @Test
  @DisplayName("a row discarded or confirmed meanwhile is not overwritten: nothing is saved")
  void aRowEndedMeanwhileIsNotOverwritten() throws Exception {
    when(store.lockIfProcessing(ID)).thenReturn(false);

    processor.runOne(ID);

    verify(dal, never()).save(any());
    verify(row, never()).setStatus(any());
    verify(row, never()).setCurrentData(any());
    verify(dal, never()).commitAndClose();
    verify(store, never()).fail(anyString(), anyString());
    assertCleanFinish();
  }

  @Test
  @DisplayName("a row another node took (nothing claimed) is left alone: no role, no service, no write")
  void aRowTakenByAnotherNodeIsSkipped() throws Exception {
    when(store.claim(ID, InvoiceInboxProcessor.LEASE_MINUTES, MAX_ATTEMPTS)).thenReturn(null);

    processor.runOne(ID);

    verify(store, never()).roleFor(anyString(), anyString());
    verify(client, never()).extract(any(File.class), any(OcrDocType.class));
    verify(store, never()).fail(anyString(), anyString());
    verify(dal, never()).save(any());
    assertCleanFinish();
  }

  @Test
  @DisplayName("a document addressed to another company ends ERROR / FOREIGN_RECEIVER, keeps the extraction and is not matched")
  void aForeignReceiverEndsTheRowWithoutMatching() throws Exception {
    when(client.extract(any(File.class), any(OcrDocType.class))).thenReturn(
        new JSONObject().put("answer", answerWith("X9999999A")));

    processor.runOne(ID);

    verify(store).storeExtracted(eq(ID), anyString());
    ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
    verify(row, org.mockito.Mockito.atLeastOnce()).setStatus(status.capture());
    assertEquals("ERROR", status.getValue());
    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    verify(row, org.mockito.Mockito.atLeastOnce()).setErrorCode(code.capture());
    assertEquals("FOREIGN_RECEIVER", code.getValue());
    verify(lookups, never()).vendorsByTaxId(anyString(), anyInt());
    verify(lookups, never()).productCandidates(any());
    verify(store, never()).fail(anyString(), anyString());
    verify(row).setProcessingLease(null);
  }

  @Test
  @DisplayName("missing mandatory data ends ERROR / MISSING_FIELDS, with the data kept for the review")
  void missingDataEndsAsMissingFields() throws Exception {
    JSONObject answer = answerWith(ORG_TAX_ID);
    answer.put("invoice_date", "not a date");
    when(client.extract(any(File.class), any(OcrDocType.class)))
        .thenReturn(new JSONObject().put("answer", answer));

    processor.runOne(ID);

    ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
    verify(row, org.mockito.Mockito.atLeastOnce()).setErrorCode(code.capture());
    assertEquals("MISSING_FIELDS", code.getValue());
    ArgumentCaptor<String> current = ArgumentCaptor.forClass(String.class);
    verify(row).setCurrentData(current.capture());
    assertEquals("invoiceDate", new JSONObject(current.getValue()).getJSONObject("validation")
        .getJSONArray("missing").getString(0));
  }

  // ---------------------------------------------------------------- cannot be processed

  @Test
  @DisplayName("a user with no usable role, or a target type the worker does not know, ends SERVICE_FAILURE without the service")
  void aRowThatCannotBeProcessedEnds() throws Exception {
    when(store.roleFor(USER, CLIENT)).thenReturn(null);

    processor.runOne(ID);

    verify(store).fail(ID, SERVICE_FAILURE);
    verify(client, never()).extract(any(File.class), any(OcrDocType.class));

    when(store.roleFor(USER, CLIENT)).thenReturn(ROLE);
    when(store.claim(ID, InvoiceInboxProcessor.LEASE_MINUTES, MAX_ATTEMPTS))
        .thenReturn(new Claimed(USER, CLIENT, ORG, "SALES_INVOICE", 1L, null));

    processor.runOne(ID);

    verify(store, times(2)).fail(ID, SERVICE_FAILURE);
    verify(client, never()).extract(any(File.class), any(OcrDocType.class));
  }

  @Test
  @DisplayName("lookups that are not bound to the document type end SERVICE_FAILURE, keeping the stored extraction")
  void noLookupsEndsTheRow() throws Exception {
    registry.when(() -> OcrLookupsRegistry.forResolver("purchase-invoice"))
        .thenReturn(Optional.empty());

    processor.runOne(ID);

    verify(store).storeExtracted(eq(ID), anyString());
    verify(store).fail(ID, SERVICE_FAILURE);
    verify(dal, never()).save(any());
  }

  // ---------------------------------------------------------------- unexpected failures

  @Test
  @DisplayName("an unexpected exception leaves the row PROCESSING for the sweeper: the lease is released, nothing is ended here")
  void anUnexpectedFailureReleasesTheLease() throws Exception {
    when(client.extract(any(File.class), any(OcrDocType.class)))
        .thenThrow(new IllegalStateException("boom"));

    processor.runOne(ID);

    verify(store).failOrRelease(ID, MAX_ATTEMPTS);
    verify(store, never()).fail(anyString(), anyString());
    verify(dal, never()).save(any());
    assertTempFileRemoved();
    assertCleanFinish();
  }

  @Test
  @DisplayName("a failure in the matching leaves the stored extraction for the next pass and releases the lease")
  void aMatchingFailureKeepsTheExtraction() throws Exception {
    when(lookups.vendorAddress("V1")).thenThrow(new IllegalStateException("db"));

    processor.runOne(ID);

    verify(store).storeExtracted(eq(ID), anyString());
    verify(store).failOrRelease(ID, MAX_ATTEMPTS);
    verify(dal, never()).save(any());
    assertCleanFinish();
  }

  @Test
  @DisplayName("every pass asks the store to count against the cap of three passes")
  void theClaimCarriesTheCaps() throws Exception {
    processor.runOne(ID);

    verify(store).claim(ID, 10, 3);
  }

  @Test
  @DisplayName("an interrupted wait stops the worker without ending the row, and keeps the interrupt")
  void anInterruptedWaitStopsTheWorker() throws Exception {
    InvoiceInboxProcessor interrupted = new InvoiceInboxProcessor(store, client, seconds -> {
      throw new InterruptedException("shutdown");
    });

    interrupted.runOne(ID);

    assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is restored");
    Thread.interrupted();
    verify(store, never()).fail(anyString(), anyString());
    verify(store, never()).failOrRelease(anyString(), anyInt());
    verify(client, never()).extract(any(File.class), any(OcrDocType.class));
    assertTempFileRemoved();
    assertEquals(0, adminDepth);
  }

  // ---------------------------------------------------------------- the sweep

  @Test
  @DisplayName("the sweep ends the rows that ran out of passes and asks for the stalled ones, as the system user")
  void theSweepEndsExhaustedRowsAndListsStalledOnes() {
    when(store.failExhausted(MAX_ATTEMPTS)).thenReturn(2);
    when(store.stalledIds(MAX_ATTEMPTS, InvoiceInboxProcessor.SWEEP_BATCH)).thenReturn(List.of());

    processor.sweepNow();

    InOrder order = inOrder(store);
    order.verify(store).failExhausted(MAX_ATTEMPTS);
    order.verify(store).stalledIds(MAX_ATTEMPTS, InvoiceInboxProcessor.SWEEP_BATCH);
    assertEquals("ctx(100,0,0,0)", contextCalls.get(0));
    assertCleanFinish();
  }

  @Test
  @DisplayName("a sweep whose store fails rolls back, clears the context and throws nothing")
  void aFailingSweepIsContained() {
    when(store.failExhausted(MAX_ATTEMPTS)).thenThrow(new IllegalStateException("db down"));

    processor.sweepNow();

    verify(store, never()).stalledIds(anyInt(), anyInt());
    assertCleanFinish();
  }

  @Test
  @DisplayName("enqueueing nothing (a null id) is a no-op")
  void aNullIdIsIgnored() {
    processor.enqueue(null);

    verify(store, never()).claim(anyString(), anyInt(), anyInt());
    assertFalse(Thread.currentThread().isInterrupted());
  }

  // ---------------------------------------------------------------- in-inbox duplicates of an unlinked vendor

  @Test
  @DisplayName("an unlinked vendor is looked up in the inbox by its extracted tax id in every spelling, not by vendor")
  void anUnlinkedVendorIsFoundByTaxId() throws Exception {
    when(lookups.vendorsByTaxId(anyString(), anyInt())).thenReturn(List.of());
    when(lookups.searchVendors(anyString(), anyInt())).thenReturn(List.of());
    IncomingDocument other = mock(IncomingDocument.class);
    when(other.getId()).thenReturn("ROW0");
    when(other.getFileName()).thenReturn("earlier.pdf");
    @SuppressWarnings("unchecked")
    OBQuery<IncomingDocument> query = mock(OBQuery.class);
    when(query.list()).thenReturn(List.of(other));
    ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
    when(dal.createQuery(eq(IncomingDocument.class), hql.capture())).thenReturn(query);

    processor.runOne(ID);

    assertTrue(hql.getValue().contains("d.extractedTaxID"), hql.getValue());
    assertFalse(hql.getValue().contains("businessPartner"));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<java.util.Set<String>> spellings = ArgumentCaptor.forClass(java.util.Set.class);
    verify(query).setNamedParameter(eq("spellings"), spellings.capture());
    assertTrue(spellings.getValue().contains(VENDOR_TAX_ID));
    assertTrue(spellings.getValue().contains("ES" + VENDOR_TAX_ID));
    verify(query).setNamedParameter("client", CLIENT);
    verify(query).setNamedParameter("reference", "F-1");
    verify(query).setNamedParameter("self", ID);
    ArgumentCaptor<String> current = ArgumentCaptor.forClass(String.class);
    verify(row).setCurrentData(current.capture());
    JSONObject document = new JSONObject(current.getValue());
    assertEquals("ROW0", document.getJSONObject("header").getJSONObject("duplicate")
        .getJSONArray("inbox").getJSONObject(0).getString("id"));
    assertTrue(document.getJSONArray("notices").toString().contains("duplicate_in_inbox"));
  }

  @Test
  @DisplayName("no query is made for an unlinked vendor with a tax id under five characters, or a number that is blank or too long")
  void noInboxQueryWithoutAnIdentity() throws Exception {
    when(lookups.vendorsByTaxId(anyString(), anyInt())).thenReturn(List.of());
    when(lookups.searchVendors(anyString(), anyInt())).thenReturn(List.of());
    JSONObject shortId = answerWith(ORG_TAX_ID);
    shortId.getJSONObject("issuer").put("tax_id_raw", "AB-1");
    when(client.extract(any(File.class), any(OcrDocType.class)))
        .thenReturn(new JSONObject().put("answer", shortId));
    processor.runOne(ID);

    JSONObject blankNo = answerWith(ORG_TAX_ID).put("document_no", "  ");
    when(client.extract(any(File.class), any(OcrDocType.class)))
        .thenReturn(new JSONObject().put("answer", blankNo));
    processor.runOne(ID);

    JSONObject longNo = answerWith(ORG_TAX_ID).put("document_no", "A".repeat(21));
    when(client.extract(any(File.class), any(OcrDocType.class)))
        .thenReturn(new JSONObject().put("answer", longNo));
    processor.runOne(ID);

    verify(dal, never()).createQuery(eq(IncomingDocument.class), anyString());
  }
}
