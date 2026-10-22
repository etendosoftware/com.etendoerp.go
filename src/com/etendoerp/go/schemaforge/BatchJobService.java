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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * Asynchronous batch jobs (ETP-5677): submit a batch, get a {@code jobId} straight away, poll its
 * status, cancel it. The one implementation behind both channels — REST
 * ({@code POST /sws/neo/batch} with {@code async:true}, {@code GET|DELETE
 * /sws/neo/batch/jobs/{id}}) and MCP ({@code etendo_batch} with {@code async:true},
 * {@code etendo_job_status}, {@code etendo_job_cancel}).
 *
 * <p><b>Why it exists.</b> A long synchronous batch commits on the server but the response can be
 * lost to an idle-timeout in front of Tomcat, so the client sees an error for records that exist
 * and a retry duplicates them. Here the POST only validates and records the job; a background
 * worker does the writing and stores the outcome, so the answer cannot be lost.</p>
 *
 * <p><b>Commit unit: one row.</b> The synchronous {@code /batch} stays one atomic transaction. The
 * async worker splits the operations into rows ({@link BatchRowGrouper}: an operation and
 * everything it references through {@code parentRef} / {@code $ref}) and runs each row through the
 * unchanged {@link BatchService#executeBatch} — atomic per row, a rejected row does not stop the
 * others, and the result says per row whether it committed (with its record ids) or why not.</p>
 *
 * <p><b>Liveness, not identity.</b> The node that holds a job (queued or running) refreshes its
 * heartbeat every {@value #HEARTBEAT_INTERVAL_SECONDS} s; a QUEUED/RUNNING job whose heartbeat is
 * older than {@value #STALE_SECONDS} s belongs to a dead node and is reported {@code INTERRUPTED}.
 * Which node answers a poll is irrelevant, so a load balancer cannot flip a live job.</p>
 */
public final class BatchJobService {

  private static final Logger log = LogManager.getLogger(BatchJobService.class);

  /** Request/response key of the async switch, on the REST body and the MCP argument. */
  public static final String PARAM_ASYNC = "async";
  /** Most operations accepted by one async job. */
  static final int MAX_OPS = 5000;
  /** Active (QUEUED + RUNNING) jobs allowed per user. */
  static final int MAX_ACTIVE_PER_USER = 2;
  /** Active (QUEUED + RUNNING) jobs allowed per client (tenant). */
  static final int MAX_ACTIVE_PER_CLIENT = 4;
  /** How often the holding node refreshes the heartbeat of its jobs. */
  static final int HEARTBEAT_INTERVAL_SECONDS = 10;
  /** A QUEUED/RUNNING job without a heartbeat for this long belongs to a dead node. */
  static final int STALE_SECONDS = 90;
  /** Finished jobs are kept this long, then purged at the next submit. */
  static final int RETENTION_HOURS = 24;
  /** Back-off hint sent with a 429. */
  static final int RETRY_AFTER_SECONDS = 5;
  private static final int WORKER_THREADS = 2;
  private static final int QUEUE_CAPACITY = 50;

  private static final String KEY_ROWS = "rows";
  private static final String KEY_COMMITTED = BatchService.FIELD_COMMITTED;
  private static final String KEY_JOB_ID = "jobId";
  private static final String KEY_STATE = "state";
  private static final String KEY_ERROR = "error";
  private static final String KEY_STATUS = "status";
  private static final String KEY_MESSAGE = "message";
  /**
   * Per-row flag: {@code true} only when the row is proven rolled back ({@code atomic:true} and an
   * empty {@code persisted}), the one case in which sending it again cannot duplicate a record.
   */
  static final String KEY_SAFE_TO_RESEND = "safeToResend";

  /** The one production instance of the service; {@code null} until the first use. */
  private static final AtomicReference<BatchJobService> INSTANCE = new AtomicReference<>();

  /** The authenticated principal a job runs as, captured on the request thread. */
  public record Owner(String userId, String roleId, String clientId, String orgId,
      String language, String warehouseId) {

    /**
     * @return the owner of the current request, read from the thread's {@link OBContext}
     * @throws JobException 401 when the thread has no authenticated user
     */
    public static Owner current() {
      OBContext ctx = OBContext.getOBContext();
      if (ctx == null || ctx.getUser() == null) {
        throw new JobException(401, "No authenticated user", null);
      }
      return new Owner(ctx.getUser().getId(),
          ctx.getRole() == null ? null : ctx.getRole().getId(),
          ctx.getCurrentClient() == null ? null : ctx.getCurrentClient().getId(),
          ctx.getCurrentOrganization() == null ? null : ctx.getCurrentOrganization().getId(),
          ctx.getLanguage() == null ? null : ctx.getLanguage().getLanguage(),
          ctx.getWarehouse() == null ? null : ctx.getWarehouse().getId());
    }
  }

  /**
   * Runs ONE row's operations as an atomic batch and returns the {@code executeBatch} envelope.
   */
  @FunctionalInterface
  public interface RowRunner {
    /**
     * Runs one row's operations as an atomic batch.
     *
     * @param rowOps the operations of one row, in request order
     * @return the batch envelope ({@code committed}, {@code operations} / {@code failedAt}, …)
     * @throws JSONException on an unexpected serialisation failure; it becomes that row's error
     */
    JSONObject run(JSONArray rowOps) throws JSONException;
  }

  /** Puts the owner's identity on the worker thread and removes it again. */
  interface ContextBinder {
    /**
     * Binds the owner's identity to the current thread.
     *
     * @param owner the principal the job runs as
     */
    void bind(Owner owner);

    /** Discards whatever DAL transaction the thread still holds. Must never throw. */
    void rollback();

    /** Removes the owner's identity from the current thread. Must never throw. */
    void unbind();
  }

  /** A refusal with its HTTP status; {@code retryAfter} is a seconds hint for a 429. */
  public static final class JobException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final Integer retryAfter;

    /**
     * Builds a refusal.
     *
     * @param status     HTTP status to answer with
     * @param message    what to tell the caller
     * @param retryAfter seconds to wait before retrying (429), or {@code null}
     */
    public JobException(int status, String message, Integer retryAfter) {
      super(message);
      this.status = status;
      this.retryAfter = retryAfter;
    }

    /** @return the HTTP status to answer with */
    public int getStatus() {
      return status;
    }

    /** @return seconds the caller should wait before retrying, or {@code null} */
    public Integer getRetryAfter() {
      return retryAfter;
    }
  }

  private final BatchJobStore store;
  private final ExecutorService executor;
  private final ScheduledExecutorService heartbeatTimer;
  private final ContextBinder binder;
  /** Jobs this node holds, queued in its executor or running — the ones whose heartbeat it owes. */
  private final Set<String> held = ConcurrentHashMap.newKeySet();

  BatchJobService(BatchJobStore store, ExecutorService executor,
      ScheduledExecutorService heartbeatTimer, ContextBinder binder) {
    this.store = store;
    this.executor = executor;
    this.heartbeatTimer = heartbeatTimer;
    this.binder = binder;
    if (heartbeatTimer != null) {
      heartbeatTimer.scheduleWithFixedDelay(this::heartbeatTick, HEARTBEAT_INTERVAL_SECONDS,
          HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }
  }

  /** @return the production service (JDBC store, two worker threads, heartbeat timer) */
  public static BatchJobService getInstance() {
    BatchJobService local = INSTANCE.get();
    if (local == null) {
      BatchJobService created = new BatchJobService(new BatchJobJdbcStore(), buildExecutor(),
          Executors.newSingleThreadScheduledExecutor(daemonFactory("batch-job-heartbeat")),
          new OBContextBinder());
      // The first caller wins; a loser reads the winner's instance and drops its own.
      local = INSTANCE.compareAndSet(null, created) ? created : INSTANCE.get();
    }
    return local;
  }

  private static ExecutorService buildExecutor() {
    return new ThreadPoolExecutor(WORKER_THREADS, WORKER_THREADS, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY), daemonFactory("batch-job-worker"),
        new ThreadPoolExecutor.AbortPolicy());
  }

  private static ThreadFactory daemonFactory(String prefix) {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  // ── submit ────────────────────────────────────────────────────────────────

  /**
   * Validates the payload, records the job and hands it to a worker. Returns before any write to
   * the business tables, so it answers in milliseconds whatever the payload size.
   *
   * @param owner   the principal the job runs as
   * @param ops     the batch operations (not modified)
   * @param channel {@code rest} or {@code mcp}
   * @param runner  runs one row's operations atomically
   * @return {@code {jobId, state:"QUEUED", totalRows, totalOps}}
   * @throws JobException 400 malformed payload, 429 over the concurrency limit or queue full
   * @throws JSONException on an unexpected serialisation failure
   *
   * <p>The per-user / per-client limits are <i>soft</i>: the count and the insert are not one atomic
   * step, so simultaneous submits can overshoot them by the number of racing requests. They bound
   * a runaway client, not a precise quota.</p>
   */
  public JSONObject submit(Owner owner, JSONArray ops, String channel, RowRunner runner)
      throws JSONException {
    String invalid = validate(ops);
    if (invalid != null) {
      throw new JobException(400, invalid, null);
    }
    housekeeping(true);
    if (store.countActiveForUser(owner.clientId(), owner.userId()) >= MAX_ACTIVE_PER_USER
        || store.countActiveForClient(owner.clientId()) >= MAX_ACTIVE_PER_CLIENT) {
      throw tooBusy("Too many batch jobs are running; retry shortly");
    }
    List<List<Integer>> rows = BatchRowGrouper.group(ops);
    BatchJobStore.JobRecord job = new BatchJobStore.JobRecord();
    job.id = UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
    job.clientId = owner.clientId();
    job.orgId = StringUtils.defaultIfBlank(owner.orgId(), "0");
    job.userId = owner.userId();
    job.roleId = owner.roleId();
    job.channel = channel;
    job.totalRows = rows.size();
    job.totalOps = ops.length();
    store.insert(job);
    held.add(job.id);
    try {
      executor.execute(() -> execute(job.id, owner, ops, rows, runner));
    } catch (RejectedExecutionException e) {
      // No node holds it: drop the row now instead of leaving a QUEUED job that would only turn
      // INTERRUPTED after the stale window.
      held.remove(job.id);
      store.delete(job.id);
      throw tooBusy("The batch job queue is full; retry shortly");
    }
    JSONObject accepted = new JSONObject();
    accepted.put(KEY_JOB_ID, job.id);
    accepted.put(KEY_STATE, BatchJobState.QUEUED.name());
    accepted.put("totalRows", job.totalRows);
    accepted.put("totalOps", job.totalOps);
    return accepted;
  }

  private static JobException tooBusy(String message) {
    return new JobException(429, message, RETRY_AFTER_SECONDS);
  }

  private static String opPrefix(int index) {
    return "operations[" + index + "]";
  }

  /** @return the reason the payload is unacceptable, or {@code null} when it is well-formed */
  static String validate(JSONArray ops) {
    if (ops == null || ops.length() == 0) {
      return "Missing or empty 'operations' array";
    }
    if (ops.length() > MAX_OPS) {
      return "Too many operations for an async batch: " + ops.length() + " (max " + MAX_OPS + ")";
    }
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < ops.length(); i++) {
      JSONObject op = ops.optJSONObject(i);
      if (op == null) {
        return opPrefix(i) + " must be an object";
      }
      String id = op.optString("id", null);
      if (StringUtils.isBlank(id) || StringUtils.isBlank(op.optString("spec", null))
          || StringUtils.isBlank(op.optString("entity", null))) {
        return opPrefix(i) + " requires 'id', 'spec', 'entity'";
      }
      if (!ids.add(id)) {
        return opPrefix(i) + ".id duplicates an earlier op";
      }
      String parentRef = op.optString("parentRef", null);
      if (StringUtils.isNotBlank(parentRef) && !ids.contains(parentRef)) {
        return opPrefix(i) + ".parentRef '" + parentRef
            + "' does not match any earlier op id";
      }
    }
    return null;
  }

  // ── status / cancel ───────────────────────────────────────────────────────

  /**
   * Reads the progress and the per-row results of a job.
   *
   * @param owner the caller; only the job's own user and client can read it
   * @param jobId the id returned by {@link #submit}
   * @return the job as seen by its owner: state, progress and the per-row results so far
   * @throws JobException 404 when the job does not exist or belongs to someone else
   * @throws JSONException on an unexpected serialisation failure
   */
  public JSONObject status(Owner owner, String jobId) throws JSONException {
    housekeeping(false);
    return render(load(owner, jobId));
  }

  /**
   * Asks the worker to stop between rows. Rows already committed stay committed.
   *
   * @param owner the caller; only the job's own user and client can cancel it
   * @param jobId the id returned by {@link #submit}
   * @return the job after the request
   * @throws JobException 404 unknown/foreign job; 409 when the job already finished
   * @throws JSONException on an unexpected serialisation failure
   */
  public JSONObject cancel(Owner owner, String jobId) throws JSONException {
    BatchJobStore.JobRecord job = load(owner, jobId);
    if (job.state.isTerminal()) {
      throw new JobException(409, "Job already finished: " + job.state, null);
    }
    store.requestCancel(jobId);
    // A job nobody has started cancels at once; a running one stops before its next row.
    store.transition(jobId, BatchJobState.CANCELLED, List.of(BatchJobState.QUEUED), null);
    return render(load(owner, jobId));
  }

  private BatchJobStore.JobRecord load(Owner owner, String jobId) {
    BatchJobStore.JobRecord job = StringUtils.isBlank(jobId) ? null : store.find(jobId);
    // 404, not 403: whether somebody else's job exists is not the caller's to learn.
    if (job == null || !StringUtils.equals(job.userId, owner.userId())
        || !StringUtils.equals(job.clientId, owner.clientId())) {
      throw new JobException(404, "Job not found: " + jobId, null);
    }
    return job;
  }

  private void housekeeping(boolean purge) {
    try {
      int swept = store.sweepStale(STALE_SECONDS);
      if (swept > 0) {
        log.warn("[BATCH-JOB] {} job(s) interrupted: their node stopped refreshing the heartbeat",
            swept);
      }
      if (purge) {
        store.purgeFinished(RETENTION_HOURS);
      }
    } catch (RuntimeException e) {
      log.warn("[BATCH-JOB] housekeeping failed: {}", e.getMessage());
    }
  }

  // ── worker ────────────────────────────────────────────────────────────────

  /** One timer tick: refresh the heartbeat of every job this node holds. */
  void heartbeatTick() {
    try {
      if (!held.isEmpty()) {
        store.heartbeat(new ArrayList<>(held));
      }
    } catch (RuntimeException e) {
      log.warn("[BATCH-JOB] heartbeat failed: {}", e.getMessage());
    }
  }

  /**
   * Stops the worker and heartbeat threads of the production instance, if one was ever created.
   * Called when the servlet is destroyed (a webapp stop or redeploy), so the daemon threads do not
   * keep the old classloader alive. Jobs still running stop refreshing their heartbeat and are
   * reported {@code INTERRUPTED} by the next node that reads them.
   */
  public static void shutdownInstance() {
    BatchJobService local = INSTANCE.getAndSet(null);
    if (local != null) {
      local.shutdown();
    }
  }

  void shutdown() {
    if (heartbeatTimer != null) {
      heartbeatTimer.shutdownNow();
    }
    executor.shutdownNow();
  }

  /** @return the job ids this node currently holds (test seam) */
  Set<String> heldJobs() {
    return Set.copyOf(held);
  }

  void execute(String jobId, Owner owner, JSONArray ops, List<List<Integer>> rows,
      RowRunner runner) {
    boolean bound = false;
    try {
      if (!store.claim(jobId)) {
        return;
      }
      // Set first: a bind that fails halfway has still touched the pooled thread, and unbind is
      // idempotent, so the cleanup must run either way.
      bound = true;
      binder.bind(owner);
      JSONArray results = new JSONArray();
      BatchJobState outcome = null;
      for (int r = 0; r < rows.size(); r++) {
        BatchJobStore.Control control = store.readControl(jobId);
        if (control == null || control.state != BatchJobState.RUNNING) {
          return; // swept or cancelled underneath us: whoever changed it owns the record now
        }
        if (control.cancelRequested) {
          outcome = BatchJobState.CANCELLED;
          break;
        }
        results.put(runRow(r, ops, rows.get(r), runner));
        if (!store.flush(jobId, results.length(), results.toString())) {
          return;
        }
      }
      finish(jobId, outcome != null ? outcome : verdict(results, rows.size()), results);
    } catch (RuntimeException | JSONException e) {
      log.error("[BATCH-JOB] job {} failed", jobId, e);
      store.transition(jobId, BatchJobState.FAILED, List.of(BatchJobState.RUNNING), null);
    } finally {
      held.remove(jobId);
      if (bound) {
        // Per job, not only per row: the pool thread is reused for another user's job next.
        binder.rollback();
        binder.unbind();
      }
    }
  }

  private void finish(String jobId, BatchJobState state, JSONArray results) {
    boolean applied = store.transition(jobId, state, List.of(BatchJobState.RUNNING),
        results.toString());
    if (!applied) {
      log.warn("[BATCH-JOB] job {} could not move to {}: it was changed meanwhile", jobId, state);
    }
  }

  private static BatchJobState verdict(JSONArray results, int totalRows) {
    int committed = countCommitted(results);
    if (committed == totalRows) {
      return BatchJobState.SUCCEEDED;
    }
    return committed == 0 ? BatchJobState.FAILED : BatchJobState.PARTIAL;
  }

  private static int countCommitted(JSONArray results) {
    int committed = 0;
    for (int i = 0; i < results.length(); i++) {
      if (results.optJSONObject(i) != null
          && results.optJSONObject(i).optBoolean(KEY_COMMITTED, false)) {
        committed++;
      }
    }
    return committed;
  }

  private JSONObject runRow(int rowIndex, JSONArray ops, List<Integer> indexes, RowRunner runner)
      throws JSONException {
    JSONArray rowOps = new JSONArray();
    JSONArray ids = new JSONArray();
    for (int index : indexes) {
      JSONObject op = ops.optJSONObject(index);
      rowOps.put(op);
      ids.put(op.optString("id"));
    }
    JSONObject row = new JSONObject();
    row.put("index", rowIndex);
    row.put("ids", ids);
    JSONObject envelope;
    try {
      envelope = runner.run(rowOps);
    } catch (Exception e) { // NOSONAR — a row's failure is its result, never the job's death
      log.error("[BATCH-JOB] row {} threw", rowIndex, e);
      row.put(KEY_COMMITTED, false);
      row.put(KEY_SAFE_TO_RESEND, false);
      row.put(KEY_ERROR, new JSONObject().put(KEY_STATUS, 500)
          .put(KEY_MESSAGE, "Batch row failed: " + e.getMessage()));
      return row;
    } finally {
      binder.rollback();
    }
    boolean committed = envelope != null && envelope.optBoolean(KEY_COMMITTED, false);
    row.put(KEY_COMMITTED, committed);
    if (envelope == null) {
      row.put(KEY_SAFE_TO_RESEND, false);
      row.put(KEY_ERROR, new JSONObject().put(KEY_STATUS, 500).put(KEY_MESSAGE, "Empty batch outcome"));
      return row;
    }
    for (String key : Arrays.asList("operations", "failedAt", KEY_ERROR, BatchService.FIELD_ATOMIC,
        BatchService.FIELD_PERSISTED, BatchService.FIELD_HINT)) {
      if (envelope.has(key)) {
        row.put(key, envelope.get(key));
      }
    }
    if (!committed) {
      row.put(KEY_SAFE_TO_RESEND, provenRollback(envelope));
    }
    return row;
  }

  /**
   * A rejected row may be sent again only when the batch itself vouches that nothing of it exists:
   * {@code atomic:true} and an empty {@code persisted}. {@code atomic:false} means a process
   * committed underneath the row (its records survive and a resend would duplicate them), and an
   * envelope that says nothing proves nothing.
   */
  private static boolean provenRollback(JSONObject envelope) {
    JSONArray persisted = envelope.optJSONArray(BatchService.FIELD_PERSISTED);
    return envelope.optBoolean(BatchService.FIELD_ATOMIC, false) && persisted != null
        && persisted.length() == 0;
  }

  // ── rendering (one serialiser for both channels) ──────────────────────────

  static JSONObject render(BatchJobStore.JobRecord job) throws JSONException {
    JSONArray rows = parseRows(job.result);
    int committed = countCommitted(rows);
    JSONObject out = new JSONObject();
    out.put(KEY_JOB_ID, job.id);
    out.put(KEY_STATE, job.state.name());
    out.put("done", job.state.isTerminal());
    out.put("channel", job.channel);
    out.put("totalRows", job.totalRows);
    out.put("totalOps", job.totalOps);
    out.put("processedRows", rows.length());
    out.put("committedRows", committed);
    out.put("failedRows", rows.length() - committed);
    out.put(KEY_ROWS, rows);
    if (job.cancelRequested && job.state.isActive()) {
      out.put("cancelRequested", true);
    }
    String hint = hintFor(job.state);
    if (hint != null) {
      out.put(BatchService.FIELD_HINT, hint);
    }
    return out;
  }

  private static String hintFor(BatchJobState state) {
    switch (state) {
      case INTERRUPTED:
        return "The node running this job stopped. Rows listed are final; rows not listed may or "
            + "may not have been created: look them up before resending.";
      case CANCELLED:
        return "Cancelled between rows. Rows listed as committed stay created; rows not listed "
            + "were never started.";
      case PARTIAL:
        return "Some rows were rejected. Committed rows already exist. Resend only the rows with "
            + "committed:false AND safeToResend:true; a row with safeToResend:false (atomic:false "
            + "or no outcome) may have left records, see its 'persisted': look them up first.";
      default:
        return null;
    }
  }

  private static JSONArray parseRows(String stored) {
    if (StringUtils.isBlank(stored)) {
      return new JSONArray();
    }
    try {
      return new JSONArray(stored);
    } catch (JSONException e) {
      log.warn("[BATCH-JOB] unreadable stored result ignored: {}", e.getMessage());
      return new JSONArray();
    }
  }

  /**
   * Binds the owner on the worker thread and clears everything the thread may carry afterwards.
   * The worker runs in admin mode exactly like {@code NeoServlet.service} does for a synchronous
   * batch: the NEO services read the {@code ETGO_SF_*} configuration entities, which the caller's
   * role may not read. Without it every row of an async job is rejected.
   */
  static final class OBContextBinder implements ContextBinder {
    /** Whether this thread pushed an admin mode that {@link #unbind} still has to pop. */
    private final ThreadLocal<Boolean> adminModeSet = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Override
    public void bind(Owner owner) {
      OBContext.setOBContext(owner.userId(), owner.roleId(), owner.clientId(), owner.orgId(),
          owner.language(), owner.warehouseId());
      OBContext.setAdminMode();
      adminModeSet.set(Boolean.TRUE);
    }

    @Override
    public void rollback() {
      try {
        OBDal.getInstance().rollbackAndClose();
      } catch (RuntimeException e) {
        log.debug("[BATCH-JOB] nothing to roll back: {}", e.getMessage());
      }
    }

    @Override
    public void unbind() {
      try {
        // Pop before the context goes: the admin stack belongs to the pooled thread, not to it.
        if (Boolean.TRUE.equals(adminModeSet.get())) {
          OBContext.restorePreviousMode();
        }
      } catch (RuntimeException e) {
        log.debug("[BATCH-JOB] could not restore the worker mode: {}", e.getMessage());
      } finally {
        adminModeSet.remove();
      }
      try {
        OBContext.setOBContext((OBContext) null);
        RequestContext.clear();
      } catch (OBException e) {
        log.debug("[BATCH-JOB] could not clear the worker context: {}", e.getMessage());
      }
    }
  }
}
