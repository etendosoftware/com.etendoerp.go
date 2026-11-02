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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * The executor of the server-side render of a document's printable (ETP-6013): the eager render
 * right after an MCP Complete, and the isolated render the MCP PDF tools ask for when the stored PDF
 * is missing or stale. Both go through here, so both run the same way and never twice at once.
 *
 * <h2>Where it is called — MCP only</h2>
 * <ul>
 *   <li>{@link #afterAction} from {@code McpToolRouter.handleAction} — {@code etendo_action}, and
 *       {@code etendo_action_batch}, which runs every document through it and commits each one
 *       itself — after the action's pipeline answered a success. Fire and forget, but held by
 *       {@code McpServlet} ({@link #deferUntilEnd}) until the whole MCP call has done its work.</li>
 *   <li>{@link #renderAndWait} / {@link #queue} from {@link NeoDocumentPdf#resolve}, whose only
 *       callers are {@code etendo_get_document_pdf} and {@code etendo_send_document_email}: it renders
 *       in isolation and waits (joining a render already running for the document), and queues the
 *       ones beyond its per-call budget.</li>
 * </ul>
 * <p><b>Never from the GO app.</b> A Complete or a preview made in the app keeps producing its PDF in
 * the browser exactly as before ETP-6013.</p>
 *
 * <p>It does not parse the action's value. Any successful call of the {@code DocAction} button on an
 * entity that declares {@code MCP_CONFIG.pdf.print} queues one render; the worker reads the
 * document's status <b>after</b> the action committed and lets the document's builder decide
 * ({@link NeoPrintableDataBuilder#storesFor}). A Complete or a re-Complete after Reactivate therefore
 * renders, a Reactivate or a Void does not.</p>
 *
 * <h2>Isolated from every caller's transaction</h2>
 * <ul>
 *   <li><b>Own thread, own session, own commit</b> — for every render, the on-request one included:
 *       a failure there (a store that rolls back, an SQL error in a read) cannot touch what the MCP
 *       request already did. The on-request path waits for the outcome with a bound
 *       ({@link #REQUEST_WAIT}) and then reads the committed file in its own session.</li>
 *   <li><b>After the completing commit.</b> The worker first takes a {@code FOR SHARE} lock on the
 *       document's row: the completing transaction holds its row lock until it commits or rolls
 *       back, so the worker waits exactly that long (bounded by {@value #LOCK_WAIT_SECONDS}s) and then
 *       reads the committed status — whoever commits (the request filter, the batch's own
 *       {@code commitAndClose}, a {@code ProcessInvoiceUtil} that committed mid-request).</li>
 *   <li><b>One render per document at a time.</b> In this JVM, a render already queued or running for
 *       the same record is joined, never duplicated ({@link #IN_FLIGHT}): the agent's usual "Complete,
 *       then ask for the PDF" waits for the eager render instead of starting a second one. Across
 *       nodes, the store takes {@code pg_advisory_xact_lock} on the record and re-checks whether a
 *       current PDF is already there, so two nodes never store two main files.</li>
 *   <li><b>Bounded.</b> {@value #THREADS} daemon threads and a queue of {@value #QUEUE_CAPACITY}; a
 *       burst beyond it is dropped with one log line and the document gets its PDF on a later
 *       request.</li>
 *   <li><b>Never throws into the caller</b>, and every worker run ends with its session rolled back
 *       or committed and closed, and its context cleared.</li>
 *   <li><b>As the caller.</b> The worker runs under the caller's user, role, client and
 *       organization. The renderer refuses to store for a role without write access to the
 *       document's attachments (D23).</li>
 *   <li><b>In the MCP's language.</b> The PDF is printed in the language the MCP caller passes: its
 *       default ({@code es_ES}, the email tool's default) for the eager and the
 *       {@code etendo_get_document_pdf} renders, the email's own language for
 *       {@code etendo_send_document_email}, so an email and its PDF always match. An MCP call carries
 *       no {@code Accept-Language}, so the context's language would be the user's AD default.</li>
 *   <li><b>Inert</b> when no jsreport / printables location is configured.</li>
 * </ul>
 *
 * <p>Shared code: it names no document. The entity's declaration decides whether there is anything
 * to render, and the document's own builder decides for which status.</p>
 */
public final class NeoPrintAfterComplete {

  private static final Logger log = LogManager.getLogger(NeoPrintAfterComplete.class);

  static final int THREADS = 2;
  static final int QUEUE_CAPACITY = 200;
  static final int LOCK_WAIT_SECONDS = 30;
  /** How long an MCP PDF request waits for an isolated render before answering without it. */
  static final Duration REQUEST_WAIT = Duration.ofSeconds(40);

  private static final ExecutorService EXECUTOR = buildExecutor();

  /** Renders queued or running in this JVM, by {@code table/record}: joined, never duplicated. */
  static final Map<String, CompletableFuture<NeoPrintableRenderer.Outcome>> IN_FLIGHT =
      new ConcurrentHashMap<>();

  /**
   * Jobs of the current MCP call, held until the call has done all its work ({@link #deferUntilEnd}).
   * {@code null} outside such a call: the job is then submitted at once.
   */
  private static final ThreadLocal<List<Job>> DEFERRED = new ThreadLocal<>();

  private NeoPrintAfterComplete() {
  }

  /** The scope of one MCP call's deferred renders. */
  public interface Deferral extends AutoCloseable {
    /** Submits the call's renders: call it once the call's work is done and its answer written. */
    void release();

    /** Ends the scope; renders not released are dropped (the on-request fallback covers them). */
    @Override
    void close();
  }

  /**
   * Holds the renders queued during one MCP call until the call has finished its work.
   *
   * <p><b>Why.</b> A completion may commit in the middle of the call ({@code ProcessInvoiceUtil}
   * commits and closes the session) and the call may keep changing the document afterwards. A render
   * queued right after the action would wait only for that first commit and store a PDF of a state
   * that is about to change — stale at once (seen live: the invoice's {@code updated} 4 s after the
   * stored file). Released after the call's last write has been flushed, the worker's
   * {@code FOR SHARE} wait then covers the call's final commit (the request filter's, or
   * {@code etendo_action_batch}'s per document), whatever path completed the document.</p>
   *
   * @return the scope; {@link Deferral#release()} once the answer is written, then close it
   */
  public static Deferral deferUntilEnd() {
    List<Job> previous = DEFERRED.get();
    List<Job> jobs = new java.util.ArrayList<>();
    DEFERRED.set(jobs);
    return new Deferral() {
      @Override
      public void release() {
        List<Job> toSubmit = new java.util.ArrayList<>(jobs);
        jobs.clear();
        for (Job job : toSubmit) {
          try {
            submit(job);
          } catch (RuntimeException e) {
            log.warn("[print] could not queue the server-side render: {}",
                e.getClass().getSimpleName());
          }
        }
      }

      @Override
      public void close() {
        if (!jobs.isEmpty()) {
          log.debug("[print] {} render(s) not released (the call failed); left to the fallback",
              jobs.size());
        }
        jobs.clear();
        if (previous == null) {
          DEFERRED.remove();
        } else {
          DEFERRED.set(previous);
        }
      }
    };
  }

  /** What the worker needs, captured on the request thread: ids and strings only. */
  static final class Job {
    final String specName;
    final String entityId;
    final String tableName;
    final String recordId;
    final String userId;
    final String roleId;
    final String clientId;
    final String orgId;
    final String language;

    Job(String specName, String entityId, String tableName, String recordId, String userId,
        String roleId, String clientId, String orgId, String language) {
      this.specName = specName;
      this.entityId = entityId;
      this.tableName = tableName;
      this.recordId = recordId;
      this.userId = userId;
      this.roleId = roleId;
      this.clientId = clientId;
      this.orgId = orgId;
      this.language = language;
    }

    /** The key a render of this document is serialized on. */
    String key() {
      return tableName + "/" + recordId;
    }
  }

  /**
   * Queues the server-side render of a document whose DocAction button just ran successfully through
   * the MCP. Returns at once; never throws.
   *
   * @param entity     the root entity the action ran on (it carries the declaration)
   * @param recordId   the document's id
   * @param actionName the button the caller fired, by column or field name
   * @param succeeded  whether the action answered a success
   * @param language   the language to print in (the MCP passes its default, the email's)
   */
  public static void afterAction(SFEntity entity, String recordId, String actionName,
      boolean succeeded, String language) {
    try {
      Job job = succeeded && isDocActionButtonOf(entity, actionName)
          ? jobFor(entity, recordId, language) : null;
      if (job == null) {
        return;
      }
      List<Job> deferred = DEFERRED.get();
      if (deferred != null) {
        deferred.add(job);
      } else {
        submit(job);
      }
    } catch (RuntimeException e) {
      log.warn("[print] could not queue the server-side render: {}", e.getClass().getSimpleName());
    }
  }

  /**
   * Renders a document in isolation — on a worker, with its own session and commit — and waits for
   * the outcome, bounded by {@link #REQUEST_WAIT} (the MCP PDF tools' fallback, B1). A render already
   * queued or running for the document is joined instead.
   *
   * @param entity   the document's root entity, declaring a printable
   * @param recordId the document's id
   * @param language the language to print in
   * @return the outcome, or {@code null} when nothing could be queued or the render did not finish in
   *         time (it keeps running; a later request finds its file)
   */
  public static NeoPrintableRenderer.Outcome renderAndWait(SFEntity entity, String recordId,
      String language) {
    Job job;
    try {
      job = jobFor(entity, recordId, language);
    } catch (RuntimeException e) {
      log.warn("[print] could not queue the server-side render: {}", e.getClass().getSimpleName());
      return null;
    }
    return job == null ? null : await(submit(job), recordId);
  }

  /** The outcome of a render, waited for at most {@link #REQUEST_WAIT}; {@code null} past it. */
  private static NeoPrintableRenderer.Outcome await(
      CompletableFuture<NeoPrintableRenderer.Outcome> render, String recordId) {
    try {
      return render.get(REQUEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      log.info("[print] {}: render still running after {}s", recordId, REQUEST_WAIT.getSeconds());
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (ExecutionException e) {
      return NeoPrintableRenderer.Outcome.FAILED;
    }
  }

  /** The serialization key of a document ({@code table/record}), or {@code null}. */
  static String keyOf(SFEntity entity, String recordId) {
    Table table = entity == null || entity.getADTab() == null ? null
        : entity.getADTab().getTable();
    return table == null || StringUtils.isBlank(recordId) ? null
        : table.getDBTableName() + "/" + recordId;
  }

  /**
   * Queues an isolated render without waiting (the fallback once its per-call budget is spent).
   *
   * @param entity   the document's root entity
   * @param recordId the document's id
   * @param language the language to print in
   */
  public static void queue(SFEntity entity, String recordId, String language) {
    try {
      Job job = jobFor(entity, recordId, language);
      if (job != null) {
        submit(job);
      }
    } catch (RuntimeException e) {
      log.warn("[print] could not queue the server-side render: {}", e.getClass().getSimpleName());
    }
  }

  /**
   * The job for a document of a printable entity, as the current caller, or {@code null} when there
   * is nothing to render: no declaration, an environment that cannot render, or no user/role in the
   * context (never an admin fallback).
   *
   * @param language the language to print in; blank falls back to the context's language
   */
  static Job jobFor(SFEntity entity, String recordId, String language) {
    if (entity == null || StringUtils.isBlank(recordId)
        || NeoPdfSourceConfig.printDocumentOf(entity) == null
        || !NeoPrintableRenderer.isConfigured()) {
      return null;
    }
    SFSpec spec = entity.getETGOSFSpec();
    Table table = entity.getADTab() == null ? null : entity.getADTab().getTable();
    OBContext context = OBContext.getOBContext();
    if (spec == null || table == null || context == null || context.getUser() == null
        || context.getRole() == null) {
      return null;
    }
    return new Job(spec.getName(), entity.getId(), table.getDBTableName(), recordId,
        context.getUser().getId(), context.getRole().getId(), context.getCurrentClient().getId(),
        context.getCurrentOrganization().getId(),
        StringUtils.isNotBlank(language) ? language
            : context.getLanguage() == null ? null : context.getLanguage().getLanguage());
  }

  /** {@link #isDocActionButton} for a printable entity only (the cheap declaration check first). */
  private static boolean isDocActionButtonOf(SFEntity entity, String actionName) {
    return entity != null && NeoPdfSourceConfig.printDocumentOf(entity) != null
        && isDocActionButton(entity, actionName);
  }

  /**
   * Whether the fired action is the document-action button: named by its column or its field, or
   * any name that resolves to the entity's {@code DocAction} button column. Structure (the standard
   * AD document-action column), not identity.
   */
  static boolean isDocActionButton(SFEntity entity, String actionName) {
    if (DocActionRequestSupport.BUTTON_COLUMN.equals(actionName)
        || DocActionRequestSupport.BUTTON_FIELD.equals(actionName)) {
      return true;
    }
    if (StringUtils.isBlank(actionName)) {
      return false;
    }
    org.openbravo.model.ad.datamodel.Column column =
        com.etendoerp.go.schemaforge.util.NeoButtonActionHelper.findButtonColumn(entity.getId(),
            actionName);
    return column != null
        && DocActionRequestSupport.BUTTON_COLUMN.equalsIgnoreCase(column.getDBColumnName());
  }

  /**
   * Queues the job, or joins the render of the same document already queued or running.
   *
   * @return the render's outcome to come
   */
  static CompletableFuture<NeoPrintableRenderer.Outcome> submit(Job job) {
    CompletableFuture<NeoPrintableRenderer.Outcome> created = new CompletableFuture<>();
    CompletableFuture<NeoPrintableRenderer.Outcome> existing =
        IN_FLIGHT.putIfAbsent(job.key(), created);
    if (existing != null) {
      return existing;
    }
    try {
      EXECUTOR.execute(() -> {
        try {
          created.complete(run(job));
        } finally {
          IN_FLIGHT.remove(job.key(), created);
        }
      });
    } catch (RejectedExecutionException e) {
      IN_FLIGHT.remove(job.key(), created);
      created.complete(NeoPrintableRenderer.Outcome.FAILED);
      log.warn("[print] render queue full; {}/{} gets its PDF on a later request", job.specName,
          job.recordId);
    }
    return created;
  }

  /** The worker side: never throws, always leaves the session closed and the context clean. */
  static NeoPrintableRenderer.Outcome run(Job job) {
    NeoPrintableRenderer.Outcome outcome = NeoPrintableRenderer.Outcome.FAILED;
    try {
      OBContext.setOBContext(job.userId, job.roleId, job.clientId, job.orgId, job.language);
      OBContext.setAdminMode(true);
      try {
        outcome = render(job);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      // Classes only — the exception's and its root cause's: a message may carry record data.
      log.warn("[print] {}/{}: server-side render failed: {}", job.specName, job.recordId,
          causeChain(e));
      outcome = NeoPrintableRenderer.Outcome.FAILED;
    } finally {
      // Whatever path ended the render (an early return included), its session ends here.
      rollbackQuietly();
      try {
        OBContext.setOBContext((OBContext) null);
      } catch (RuntimeException e) {
        log.debug("[print] could not clear the worker context: {}", e.getClass().getSimpleName());
      }
    }
    return outcome;
  }

  /**
   * The exception's class and its root cause's ({@code PersistenceException <- MappingException}),
   * never a message.
   */
  static String causeChain(Throwable e) {
    Throwable root = e;
    int depth = 0;
    while (root.getCause() != null && root.getCause() != root && depth++ < 20) {
      root = root.getCause();
    }
    return root == e ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + " <- " + root.getClass().getSimpleName();
  }

  private static NeoPrintableRenderer.Outcome render(Job job) {
    SFEntity entity = OBDal.getInstance().get(SFEntity.class, job.entityId);
    if (entity == null || entity.getETGOSFSpec() == null) {
      return NeoPrintableRenderer.Outcome.NOT_PRINTABLE;
    }
    waitForTheCompletionToEnd(entity, job.recordId);
    // The wait closed the session: read the definitions again in the fresh one.
    entity = OBDal.getInstance().get(SFEntity.class, job.entityId);
    NeoAttachmentTarget target = NeoAttachmentTarget.resolve(entity.getETGOSFSpec(), entity,
        job.recordId, false);
    // Across nodes: one store per document at a time, and none when a current PDF is already there
    // (another node, or the eager render this request did not see in this JVM, stored it).
    lockDocument(job.key());
    if (NeoDocumentPdf.resolveStored(target, entity).getStatus() == NeoDocumentPdf.Status.FOUND) {
      return NeoPrintableRenderer.Outcome.ALREADY_CURRENT;
    }
    String status = NeoDocumentPdf.documentStatus(target);
    NeoPrintableRenderer.Outcome outcome = new NeoPrintableRenderer().renderAndStore(
        entity.getETGOSFSpec(), entity, job.recordId, status, job.language);
    if (outcome == NeoPrintableRenderer.Outcome.STORED) {
      OBDal.getInstance().commitAndClose();
    } else {
      log.debug("[print] {}/{}: nothing stored ({})", job.specName, job.recordId, outcome);
    }
    return outcome;
  }

  /**
   * {@code pg_advisory_xact_lock} on the document, held until the worker's transaction ends. Every
   * server-side store of a document takes it first and then re-checks the stored PDF, so two stores
   * (on two nodes) never race.
   *
   * @param key {@link #keyOf}
   */
  private static void lockDocument(String key) {
    OBDal.getInstance().getSession().createNativeQuery(LOCK_DOCUMENT_SQL)
        .setParameter("key", "etgo-print:" + key).list();
  }

  /**
   * The advisory lock. {@code pg_advisory_xact_lock} returns {@code void}, which Hibernate cannot map
   * (JDBC type 1111: every render failed with a {@code PersistenceException}), so its result is cast
   * to text — the pattern {@code UserRoleWriteLock} already uses. A mocked session cannot catch this;
   * {@code NeoPrintAfterCompleteTest} pins the cast.
   */
  static final String LOCK_DOCUMENT_SQL = "SELECT CAST(pg_advisory_xact_lock(hashtext(:key)) AS text)";

  /**
   * Blocks until the transaction that changed the document has ended: a {@code FOR SHARE} lock on
   * its row waits for the completing transaction's row lock. The lock is released at once (the
   * session is committed), so this worker never holds the document.
   */
  @SuppressWarnings("unchecked")
  static void waitForTheCompletionToEnd(SFEntity entity, String recordId) {
    Table table = entity.getADTab() == null ? null : entity.getADTab().getTable();
    Entity dalEntity = table == null ? null
        : ModelProvider.getInstance().getEntityByTableId(table.getId());
    if (dalEntity == null || dalEntity.getIdProperties().size() != 1) {
      return;
    }
    String tableName = table.getDBTableName();
    String keyColumn = dalEntity.getIdProperties().get(0).getColumnName();
    if (!tableName.matches("[A-Za-z0-9_]+") || !keyColumn.matches("[A-Za-z0-9_]+")) {
      return;
    }
    org.hibernate.Session session = OBDal.getInstance().getSession();
    session.createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
        .setParameter("timeout", LOCK_WAIT_SECONDS + "s").list();
    List<Object> rows = session.createNativeQuery("SELECT 1 FROM " + tableName + " WHERE "
        + keyColumn + " = :id FOR SHARE").setParameter("id", recordId).list();
    OBDal.getInstance().commitAndClose();
    if (rows.isEmpty()) {
      log.debug("[print] the document is gone; nothing to wait for");
    }
  }

  private static void rollbackQuietly() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } catch (RuntimeException e) {
      log.debug("[print] nothing to roll back: {}", e.getMessage());
    }
  }

  private static ExecutorService buildExecutor() {
    AtomicInteger counter = new AtomicInteger();
    return new ThreadPoolExecutor(THREADS, THREADS, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
          Thread thread = new Thread(runnable, "neo-print-" + counter.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
  }
}
