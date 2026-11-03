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

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DUPLICATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_HEADER;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_EXTRACTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INBOX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NOTICES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VALIDATION;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.MAX_DOCUMENT_NO;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;

import java.io.File;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;

import com.etendoerp.go.schemaforge.InvoiceInboxWorkStore.Claimed;
import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.CopilotOcrServiceClient;
import com.etendoerp.go.schemaforge.ocr.InboxMatcher;
import com.etendoerp.go.schemaforge.ocr.OcrConfig;
import com.etendoerp.go.schemaforge.ocr.OcrDocType;
import com.etendoerp.go.schemaforge.ocr.OcrExtractionService;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.ocr.OcrServiceClient;
import com.etendoerp.go.schemaforge.ocr.OcrUnavailableException;
import com.etendoerp.go.schemaforge.ocr.TaxIdNormalizer;

/**
 * The background worker of the invoice inbox (ETP-6048): takes each {@code PROCESSING} row, reads its
 * main attachment through the OCR, matches vendor, products, taxes and duplicates, validates, and
 * leaves it in {@code PENDING_REVIEW} or {@code ERROR} with a code.
 *
 * <p><b>The {@code PROCESSING} row is the queue.</b> {@link #submit} is the fast path (an in-memory
 * executor, {@value #THREADS} daemon threads, queue of {@value #QUEUE_CAPACITY}); a full queue or a
 * restart loses nothing, because {@link #sweep} (run by {@code InvoiceInboxSweeperStartup}) re-submits
 * every row nobody is working on.</p>
 *
 * <p><b>One document, one isolated unit.</b> The row is claimed atomically with a lease (several
 * nodes may run), the OCR runs outside any transaction, the extraction is stored write-once before
 * the matching (a later pass never pays for the OCR twice), and the final write is one transaction
 * conditioned on the row still being {@code PROCESSING}. It never throws out of its thread. A
 * service failure retries {@value #RETRIES} times with a growing wait; an unexpected failure leaves
 * the row for the sweeper, and after {@value #MAX_ATTEMPTS} passes it ends as
 * {@code SERVICE_FAILURE}.</p>
 *
 * <p>It acts as the user who uploaded: the context is the row's user, client and organization with
 * the user's default role, in admin mode. It does not evaluate the feature flag: a {@code PROCESSING}
 * row exists only because the flag was on at upload.</p>
 */
public final class InvoiceInboxProcessor {

  static final int THREADS = 2;
  static final int QUEUE_CAPACITY = 200;
  static final int MAX_ATTEMPTS = 3;
  static final int LEASE_MINUTES = 10;
  static final int RETRIES = 3;
  static final int SWEEP_BATCH = 50;
  /** Shortest cleaned tax id worth identifying an issuer by (as the vendor match). */
  private static final int MIN_ISSUER_TAX_ID = 5;
  /** Seconds to wait before each OCR attempt of a pass. */
  private static final long[] BACKOFF_SECONDS = { 0L, 15L, 45L };
  private static final String ERR_UNREADABLE = "UNREADABLE";
  private static final String ERR_SERVICE = "SERVICE_FAILURE";
  private static final String SYSTEM_USER = "100";
  private static final String SYSTEM_ID = "0";
  private static final String SPEC_PURCHASE_INVOICE = "purchase-invoice";
  private static final Map<String, String> SPEC_BY_TARGET = Map.of(
      InvoiceInboxIntake.TARGET_PURCHASE_INVOICE, SPEC_PURCHASE_INVOICE);
  private static final Logger log = LogManager.getLogger(InvoiceInboxProcessor.class);

  /** Waits between OCR attempts; a seam so tests do not sleep. */
  interface Sleeper {
    void sleep(long seconds) throws InterruptedException;
  }

  private static final class Holder {
    static final InvoiceInboxProcessor INSTANCE = new InvoiceInboxProcessor(
        new InvoiceInboxWorkStore(), new CopilotOcrServiceClient(), TimeUnit.SECONDS::sleep);
  }

  private final InvoiceInboxWorkStore store;
  private final OcrServiceClient client;
  private final Sleeper sleeper;
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
  private final ExecutorService executor = buildExecutor();

  InvoiceInboxProcessor(InvoiceInboxWorkStore store, OcrServiceClient client, Sleeper sleeper) {
    this.store = store;
    this.client = client;
    this.sleeper = sleeper;
  }

  /**
   * Queues rows for processing. Never throws: a full queue is logged and the sweeper picks the row up.
   *
   * @param incomingDocumentId the row id
   */
  public static void submit(String incomingDocumentId) {
    Holder.INSTANCE.enqueue(incomingDocumentId);
  }

  /**
   * The safety net: ends the rows that ran out of passes and re-submits those nobody is working on.
   * Run at startup and periodically; safe to run on every node.
   */
  public static void sweep() {
    Holder.INSTANCE.sweepNow();
  }

  /** Stops accepting work; what is in flight returns through its lease. */
  public static void shutdown() {
    Holder.INSTANCE.executor.shutdownNow();
  }

  void enqueue(String id) {
    if (id == null || !inFlight.add(id)) {
      return;
    }
    try {
      executor.execute(() -> runOne(id));
    } catch (RejectedExecutionException e) {
      inFlight.remove(id);
      log.warn("Invoice inbox: queue full, {} waits for the sweeper", id);
    }
  }

  void sweepNow() {
    try {
      OBContext.setOBContext(SYSTEM_USER, SYSTEM_ID, SYSTEM_ID, SYSTEM_ID);
      int failed = store.failExhausted(MAX_ATTEMPTS);
      if (failed > 0) {
        log.warn("Invoice inbox: {} document(s) ended as {} after {} passes", failed, ERR_SERVICE,
            MAX_ATTEMPTS);
      }
      store.stalledIds(MAX_ATTEMPTS, SWEEP_BATCH).forEach(this::enqueue);
    } catch (RuntimeException e) {
      log.warn("Invoice inbox: sweep failed: {}", e.getClass().getSimpleName());
    } finally {
      rollbackQuietly();
      clearContext();
    }
  }

  /** The worker side of one row: never throws, always leaves the session closed and the context clean. */
  void runOne(String id) {
    try {
      OBContext.setOBContext(SYSTEM_USER, SYSTEM_ID, SYSTEM_ID, SYSTEM_ID);
      Claimed claimed = store.claim(id, LEASE_MINUTES, MAX_ATTEMPTS);
      if (claimed != null) {
        processClaimed(id, claimed);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      handleUnexpected(id, e);
    } finally {
      rollbackQuietly();
      clearContext();
      inFlight.remove(id);
    }
  }

  private void handleUnexpected(String id, Exception e) {
    log.warn("Invoice inbox: processing {} failed: {}", id, e.getClass().getSimpleName());
    rollbackQuietly();
    try {
      OBContext.setOBContext(SYSTEM_USER, SYSTEM_ID, SYSTEM_ID, SYSTEM_ID);
      store.failOrRelease(id, MAX_ATTEMPTS);
    } catch (RuntimeException inner) {
      log.warn("Invoice inbox: could not close {}: {}", id, inner.getClass().getSimpleName());
    }
  }

  private void processClaimed(String id, Claimed claimed) throws Exception {
    String spec = SPEC_BY_TARGET.get(claimed.targetType());
    Optional<OcrDocType> type = spec == null ? Optional.empty() : OcrConfig.get().forSpec(spec);
    String roleId = store.roleFor(claimed.userId(), claimed.clientId());
    if (type.isEmpty() || roleId == null) {
      log.warn("Invoice inbox: {} cannot be processed (no document type or no usable role)", id);
      store.fail(id, ERR_SERVICE);
      return;
    }
    OBContext.setOBContext(claimed.userId(), roleId, claimed.clientId(), claimed.orgId());
    // Admin mode, as on every NEO and MCP request: it only skips the role's entity-access check, which
    // the Schema Forge configuration tables (spec, entity, field) read by the selector lookups fail
    // for a business role. The DAL still filters every query by the role's readable clients and
    // organizations (OBQuery/OBCriteria apply them in admin mode too), so no other tenant's data
    // is reachable.
    inAdminMode(() -> {
      JSONObject extraction = extractionOf(id, claimed, type.get());
      if (extraction != null) {
        matchAndStore(id, extraction, type.get());
      }
      return null;
    });
  }

  /** The body of a step that runs in admin mode. */
  private interface AdminStep<T> {
    T run() throws Exception;
  }

  /** Runs a step in admin mode and restores the mode after it, whatever happens. */
  private static <T> T inAdminMode(AdminStep<T> step) throws Exception {
    OBContext.setAdminMode(true);
    try {
      return step.run();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** The stored extraction, or a fresh one (stored write-once); {@code null} when the row was ended. */
  private JSONObject extractionOf(String id, Claimed claimed, OcrDocType type) throws Exception {
    if (claimed.extractedData() != null) {
      return new JSONObject(claimed.extractedData());
    }
    File file = NeoAttachmentsHelper.copyMainAttachmentToTemp(IncomingDocument.TABLE_NAME, id);
    OBDal.getInstance().rollbackAndClose();
    if (file == null) {
      store.fail(id, ERR_UNREADABLE);
      return null;
    }
    try {
      JSONObject payload = readWithRetries(file, type);
      if (payload == null) {
        store.fail(id, ERR_UNREADABLE);
        return null;
      }
      payload.put("_meta", new JSONObject().put("docType", type.getSpec())
          .put("extractedAt", new Date().getTime()));
      store.storeExtracted(id, payload.toString());
      return payload;
    } catch (OcrUnavailableException e) {
      log.warn("Invoice inbox: the extraction service failed for {} (retryable: {})", id,
          e.isRetryable());
      store.fail(id, ERR_SERVICE);
      return null;
    } finally {
      NeoAttachmentsHelper.cleanupTempFile(file);
    }
  }

  /** Reads the document, retrying only failures that may pass; outside any transaction. */
  private JSONObject readWithRetries(File file, OcrDocType type) throws OcrUnavailableException,
      JSONException, InterruptedException {
    OcrExtractionService service = new OcrExtractionService(client);
    OcrUnavailableException last = null;
    for (int attempt = 0; attempt < RETRIES; attempt++) {
      sleeper.sleep(BACKOFF_SECONDS[attempt]);
      try {
        return service.extract(file, type);
      } catch (OcrUnavailableException e) {
        if (!e.isRetryable()) {
          throw e;
        }
        last = e;
      }
    }
    throw last;
  }

  private void matchAndStore(String id, JSONObject extraction, OcrDocType type)
      throws Exception {
    Optional<OcrLookups> lookups = OcrLookupsRegistry.forResolver(type.getResolver());
    if (lookups.isEmpty()) {
      store.fail(id, ERR_SERVICE);
      return;
    }
    JSONObject document = new InboxMatcher(lookups.get()).build(extraction);
    OBDal.getInstance().rollbackAndClose();
    persist(id, document);
  }

  /** The final write: one transaction, only while the row is still {@code PROCESSING}. */
  private void persist(String id, JSONObject document) throws JSONException {
    if (!store.lockIfProcessing(id)) {
      OBDal.getInstance().rollbackAndClose();
      return;
    }
    IncomingDocument row = OBDal.getInstance().get(IncomingDocument.class, id);
    addInboxDuplicates(row, document);
    InvoiceInboxValidator.Result verdict = InvoiceInboxValidator.validate(document);
    document.put(K_VALIDATION, verdict.toJson());
    row.setCurrentData(document.toString());
    InvoiceInboxSummary.syncFrom(document, row);
    row.setStatus(InvoiceInboxStatusPolicy.PENDING_REVIEW);
    row.setErrorCode(null);
    InvoiceInboxStatusPolicy.apply(row, verdict);
    row.setProcessingLease(null);
    row.setProcessedAt(new Date());
    OBDal.getInstance().save(row);
    OBDal.getInstance().commitAndClose();
  }

  /**
   * Other live inbox rows with the same issuer and document number: a warning in the document
   * (ETP-6048 D6). Best-effort for documents processed at the same time.
   */
  private static void addInboxDuplicates(IncomingDocument row, JSONObject document)
      throws JSONException {
    JSONObject header = document.getJSONObject(K_HEADER);
    String documentNo = header.optString(K_DOCUMENT_NO, "").trim();
    JSONArray found = new JSONArray();
    if (!documentNo.isEmpty() && documentNo.length() <= MAX_DOCUMENT_NO) {
      OBQuery<IncomingDocument> query = issuerQuery(header.optJSONObject(K_VENDOR));
      if (query != null) {
        query.setNamedParameter("client", row.getClient().getId());
        query.setNamedParameter("reference", documentNo.toUpperCase(java.util.Locale.ROOT));
        query.setNamedParameter("self", row.getId());
        query.setMaxResult(SWEEP_BATCH);
        for (IncomingDocument other : query.list()) {
          found.put(new JSONObject().put(K_ID, other.getId()).put("fileName",
              other.getFileName()));
        }
      }
    }
    header.getJSONObject(K_DUPLICATE).put(K_INBOX, found);
    if (found.length() > 0) {
      document.getJSONArray(K_NOTICES).put("duplicate_in_inbox");
    }
  }

  /**
   * The query for the rows of the same issuer, still missing the client, reference and self
   * parameters: by vendor when it is linked, else by the extracted tax id (the common case of a
   * vendor that does not exist yet). {@code null} when the issuer cannot be identified.
   */
  private static OBQuery<IncomingDocument> issuerQuery(JSONObject vendor) {
    String common = " and upper(trim(d.orderReference)) = :reference and d.status <> 'DISCARDED'"
        + " and d.id <> :self";
    if (vendor != null && S_LINKED.equals(vendor.optString(K_STATE))) {
      OBQuery<IncomingDocument> query = OBDal.getInstance().createQuery(IncomingDocument.class,
          "as d where d.client.id = :client and d.businessPartner.id = :vendor" + common);
      query.setNamedParameter("vendor", vendor.optString(K_ID));
      return query;
    }
    JSONObject extracted = vendor == null ? null : vendor.optJSONObject(K_EXTRACTED);
    String taxId = TaxIdNormalizer.clean(extracted == null ? "" : extracted.optString(K_TAX_ID));
    if (taxId.length() < MIN_ISSUER_TAX_ID) {
      return null;
    }
    // The stored value is the raw text read from the document: compare it the way
    // PurchaseInvoiceOcrLookups.vendorsByTaxId does, cleaned and in every prefix spelling.
    OBQuery<IncomingDocument> query = OBDal.getInstance().createQuery(IncomingDocument.class,
        "as d where d.client.id = :client and upper(replace(replace(replace(replace("
            + "d.extractedTaxID, '-', ''), ' ', ''), '.', ''), '/', '')) in (:spellings)" + common);
    query.setNamedParameter("spellings", TaxIdNormalizer.variants(taxId));
    return query;
  }

  private static void clearContext() {
    try {
      OBContext.setOBContext((OBContext) null);
    } catch (RuntimeException e) {
      log.debug("Invoice inbox: could not clear the worker context: {}",
          e.getClass().getSimpleName());
    }
  }

  private static void rollbackQuietly() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (RuntimeException e) {
      log.debug("Invoice inbox: nothing to roll back: {}", e.getMessage());
    }
  }

  private static ExecutorService buildExecutor() {
    AtomicInteger counter = new AtomicInteger();
    return new ThreadPoolExecutor(THREADS, THREADS, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
          Thread thread = new Thread(runnable, "invoice-inbox-" + counter.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
  }
}
