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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.BatchJobService.JobException;
import com.etendoerp.go.schemaforge.BatchJobService.Owner;

/**
 * @covers com.etendoerp.go.schemaforge.BatchJobService
 * @covers com.etendoerp.go.schemaforge.BatchJobState
 *
 * <p>The async batch job (ETP-5677) against an in-memory store with a manual clock and a manual
 * executor, so submit / worker / heartbeat / sweep interleavings are deterministic and no database
 * is needed.</p>
 */
public class BatchJobServiceTest {

  private static final Owner ALICE = new Owner("U1", "R1", "C1", "O1", "en_US", null);
  private static final Owner BOB = new Owner("U2", "R1", "C1", "O1", "en_US", null);
  private static final Owner EVE = new Owner("U9", "R9", "C2", "O9", "en_US", null);

  /**
   * The synchronous batch runs in admin mode (NeoServlet.service), because the NEO services read
   * ETGO_SF_* entities the caller's role cannot. The worker of an async job must do the same, and
   * leave the pooled thread's admin stack balanced.
   */
  @Test
  public void realBinderRunsTheWorkerInAdminModeAndRestoresItOnUnbind() {
    BatchJobService.OBContextBinder real = new BatchJobService.OBContextBinder();
    try (MockedStatic<OBContext> context = Mockito.mockStatic(OBContext.class)) {
      real.bind(ALICE);
      context.verify(OBContext::setAdminMode, Mockito.times(1));
      context.verify(OBContext::restorePreviousMode, Mockito.never());

      real.unbind();
      context.verify(OBContext::restorePreviousMode, Mockito.times(1));

      // A second unbind on the same thread has nothing of its own to pop.
      real.unbind();
      context.verify(OBContext::restorePreviousMode, Mockito.times(1));
    }
  }

  @Test
  public void realBinderThatFailsToBindPopsNoAdminMode() {
    BatchJobService.OBContextBinder real = new BatchJobService.OBContextBinder();
    try (MockedStatic<OBContext> context = Mockito.mockStatic(OBContext.class)) {
      context.when(() -> OBContext.setOBContext("U1", "R1", "C1", "O1", "en_US", null))
          .thenThrow(new IllegalStateException("cannot bind"));
      try {
        real.bind(ALICE);
        fail("bind must propagate");
      } catch (IllegalStateException expected) {
        // the worker's finally still calls unbind
      }
      real.unbind();
      context.verify(OBContext::setAdminMode, Mockito.never());
      context.verify(OBContext::restorePreviousMode, Mockito.never());
    }
  }

  private FakeStore store;
  private ManualExecutor executor;
  private RecordingBinder binder;
  private BatchJobService service;

  @Before
  public void setUp() {
    store = new FakeStore();
    executor = new ManualExecutor();
    binder = new RecordingBinder();
    service = new BatchJobService(store, executor, null, binder);
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  /** n independent product-like ops, each its own row. */
  private static JSONArray rows(int n) throws Exception {
    JSONArray ops = new JSONArray();
    for (int i = 0; i < n; i++) {
      ops.put(op("p" + i, null));
    }
    return ops;
  }

  private static JSONObject op(String id, String parentRef) throws Exception {
    JSONObject op = new JSONObject().put("id", id).put("spec", "product").put("entity", "product")
        .put("body", new JSONObject().put("name", id));
    if (parentRef != null) {
      op.put("parentRef", parentRef);
    }
    return op;
  }

  /** A runner that commits every row, answering one recordId per op. */
  private static BatchJobService.RowRunner committing(List<JSONArray> seen) {
    return rowOps -> {
      seen.add(rowOps);
      JSONArray out = new JSONArray();
      for (int i = 0; i < rowOps.length(); i++) {
        out.put(new JSONObject().put("id", rowOps.getJSONObject(i).getString("id")).put("ok", true)
            .put("recordId", "REC-" + rowOps.getJSONObject(i).getString("id")));
      }
      return new JSONObject().put("committed", true).put("operations", out);
    };
  }

  private static JSONObject rejected(String opId) throws org.codehaus.jettison.json.JSONException {
    return new JSONObject().put("committed", false).put("atomic", true)
        .put("persisted", new JSONArray())
        .put("failedAt", new JSONObject().put("id", opId).put("index", 0))
        .put("error", new JSONObject().put("status", 400).put("message", "bad " + opId));
  }

  private String submit(Owner owner, JSONArray ops, BatchJobService.RowRunner runner)
      throws Exception {
    return service.submit(owner, ops, "rest", runner).getString("jobId");
  }

  private JSONObject status(Owner owner, String jobId)
      throws org.codehaus.jettison.json.JSONException {
    return service.status(owner, jobId);
  }

  private static int httpStatusOf(Runnable action) {
    try {
      action.run();
    } catch (JobException e) {
      return e.getStatus();
    }
    return -1;
  }

  // ── submit ───────────────────────────────────────────────────────────────

  @Test
  public void submitAnswersWithAJobIdBeforeTheRunnerStarts() throws Exception {
    List<JSONArray> seen = new ArrayList<>();
    JSONObject accepted = service.submit(ALICE, rows(1000), "rest", committing(seen));

    assertNotNull(accepted.getString("jobId"));
    assertEquals("QUEUED", accepted.getString("state"));
    assertEquals(1000, accepted.getInt("totalRows"));
    assertEquals(1000, accepted.getInt("totalOps"));
    assertTrue("nothing was written while answering", seen.isEmpty());
    assertTrue(service.heldJobs().contains(accepted.getString("jobId")));
  }

  @Test
  public void aMalformedPayloadIsRejectedSynchronouslyAndLeavesNoJob() throws Exception {
    JSONArray duplicate = new JSONArray().put(op("a", null)).put(op("a", null));
    JSONArray noSpec = new JSONArray().put(new JSONObject().put("id", "x").put("entity", "e"));
    JSONArray badParent = new JSONArray().put(op("a", "nope"));
    JSONArray notObject = new JSONArray().put("text");

    for (JSONArray bad : new JSONArray[] {new JSONArray(), duplicate, noSpec, badParent,
        notObject, rows(BatchJobService.MAX_OPS + 1)}) {
      try {
        service.submit(ALICE, bad, "rest", committing(new ArrayList<>()));
        fail("expected 400");
      } catch (JobException e) {
        assertEquals(400, e.getStatus());
      }
    }
    assertTrue(store.jobs.isEmpty());
    assertTrue(service.heldJobs().isEmpty());
  }

  @Test
  public void aUserOverTheLimitGetsA429WithARetryHintAndNoRow() throws Exception {
    submit(ALICE, rows(1), committing(new ArrayList<>()));
    submit(ALICE, rows(1), committing(new ArrayList<>()));
    try {
      submit(ALICE, rows(1), committing(new ArrayList<>()));
      fail("expected 429");
    } catch (JobException e) {
      assertEquals(429, e.getStatus());
      assertEquals(Integer.valueOf(BatchJobService.RETRY_AFTER_SECONDS), e.getRetryAfter());
    }
    assertEquals(2, store.jobs.size());
  }

  @Test
  public void aClientOverItsLimitIsRefusedEvenForAnotherUser() throws Exception {
    Owner carol = new Owner("U3", "R1", "C1", "O1", "en_US", null);
    submit(ALICE, rows(1), committing(new ArrayList<>()));
    submit(ALICE, rows(1), committing(new ArrayList<>()));
    submit(BOB, rows(1), committing(new ArrayList<>()));
    submit(BOB, rows(1), committing(new ArrayList<>()));
    assertEquals(429, httpStatusOf(() -> {
      try {
        submit(carol, rows(1), committing(new ArrayList<>()));
      } catch (JobException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }));
    // another tenant is not affected
    submit(EVE, rows(1), committing(new ArrayList<>()));
    assertEquals(5, store.jobs.size());
  }

  @Test
  public void aFullExecutorQueueDropsTheRowAndAnswers429() throws Exception {
    executor.reject = true;
    try {
      submit(ALICE, rows(1), committing(new ArrayList<>()));
      fail("expected 429");
    } catch (JobException e) {
      assertEquals(429, e.getStatus());
    }
    assertTrue("no QUEUED row left that no node holds", store.jobs.isEmpty());
    assertTrue(service.heldJobs().isEmpty());
  }

  // ── worker ───────────────────────────────────────────────────────────────

  @Test
  public void aHappyJobCommitsEveryRowAndReportsItsRecordIds() throws Exception {
    List<JSONArray> seen = new ArrayList<>();
    JSONArray ops = new JSONArray().put(op("p1", null)).put(op("p1.price", "p1"))
        .put(op("p2", null)).put(op("p2.price", "p2"));
    String id = submit(ALICE, ops, committing(seen));

    executor.runAll();

    JSONObject job = status(ALICE, id);
    assertEquals("SUCCEEDED", job.getString("state"));
    assertTrue(job.getBoolean("done"));
    assertEquals(2, job.getInt("totalRows"));
    assertEquals(2, job.getInt("processedRows"));
    assertEquals(2, job.getInt("committedRows"));
    assertEquals(0, job.getInt("failedRows"));
    assertEquals(2, seen.size());
    assertEquals("a product and its price are one atomic row", 2, seen.get(0).length());
    JSONObject first = job.getJSONArray("rows").getJSONObject(0);
    assertEquals("REC-p1.price",
        first.getJSONArray("operations").getJSONObject(1).getString("recordId"));
    assertTrue(service.heldJobs().isEmpty());
  }

  @Test
  public void aRejectedRowDoesNotStopTheOthersAndIsReportedWithItsMessage() throws Exception {
    BatchJobService.RowRunner runner = rowOps -> {
      String id = rowOps.getJSONObject(0).getString("id");
      return "p1".equals(id) ? rejected(id) : committing(new ArrayList<>()).run(rowOps);
    };
    String id = submit(ALICE, rows(3), runner);

    executor.runAll();

    JSONObject job = status(ALICE, id);
    assertEquals("PARTIAL", job.getString("state"));
    assertEquals(2, job.getInt("committedRows"));
    assertEquals(1, job.getInt("failedRows"));
    JSONArray rowResults = job.getJSONArray("rows");
    assertTrue(rowResults.getJSONObject(0).getBoolean("committed"));
    assertEquals("REC-p0", rowResults.getJSONObject(0).getJSONArray("operations")
        .getJSONObject(0).getString("recordId"));
    JSONObject failed = rowResults.getJSONObject(1);
    assertFalse(failed.getBoolean("committed"));
    assertEquals("bad p1", failed.getJSONObject("error").getString("message"));
    assertEquals("p1", failed.getJSONArray("ids").getString(0));
    assertTrue(rowResults.getJSONObject(2).getBoolean("committed"));
    assertTrue(job.getString("hint").contains("committed:false"));
  }

  @Test
  public void onlyAProvenRollbackIsMarkedSafeToResend() throws Exception {
    BatchJobService.RowRunner runner = rowOps -> {
      String id = rowOps.getJSONObject(0).getString("id");
      if ("p1".equals(id)) {
        // a process committed underneath the row: its records survive
        return rejected(id).put("atomic", false).put("persisted", new JSONArray().put("REC-9"));
      }
      if ("p2".equals(id)) {
        return new JSONObject().put("committed", false).put("error", new JSONObject().put("message", "?"));
      }
      return "p0".equals(id) ? committing(new ArrayList<>()).run(rowOps) : rejected(id);
    };
    String id = submit(ALICE, rows(4), runner);

    executor.runAll();

    JSONArray rowResults = status(ALICE, id).getJSONArray("rows");
    assertFalse("a committed row has nothing to resend", rowResults.getJSONObject(0).has("safeToResend"));
    assertFalse("atomic:false: records survive", rowResults.getJSONObject(1).getBoolean("safeToResend"));
    assertEquals("REC-9", rowResults.getJSONObject(1).getJSONArray("persisted").getString(0));
    assertFalse("no outcome proves nothing", rowResults.getJSONObject(2).getBoolean("safeToResend"));
    assertTrue("rolled back: safe", rowResults.getJSONObject(3).getBoolean("safeToResend"));
    assertTrue(status(ALICE, id).getString("hint").contains("safeToResend:true"));
  }

  @Test
  public void aRowWhoseRunnerThrewIsNeverMarkedSafeToResend() throws Exception {
    String id = submit(ALICE, rows(1), rowOps -> {
      throw new IllegalStateException("boom");
    });
    executor.runAll();
    assertFalse(status(ALICE, id).getJSONArray("rows").getJSONObject(0).getBoolean("safeToResend"));
  }

  @Test
  public void aFailedBindStillReleasesTheThread() throws Exception {
    binder.failBind = true;
    submit(ALICE, rows(1), committing(new ArrayList<>()));
    executor.runAll();
    assertEquals(1, binder.unbinds);
    assertTrue(service.heldJobs().isEmpty());
  }

  @Test
  public void shutdownStopsTheWorkerThreads() throws Exception {
    service.shutdown();
    assertTrue(executor.stopped);
  }

  @Test
  public void aJobWhoseEveryRowIsRejectedFails() throws Exception {
    String id = submit(ALICE, rows(2), rowOps -> rejected("x"));
    executor.runAll();
    assertEquals("FAILED", status(ALICE, id).getString("state"));
  }

  @Test
  public void aRunnerThatThrowsFailsOnlyItsOwnRow() throws Exception {
    BatchJobService.RowRunner runner = rowOps -> {
      if ("p0".equals(rowOps.getJSONObject(0).getString("id"))) {
        throw new IllegalStateException("boom");
      }
      return committing(new ArrayList<>()).run(rowOps);
    };
    String id = submit(ALICE, rows(2), runner);
    executor.runAll();

    JSONObject job = status(ALICE, id);
    assertEquals("PARTIAL", job.getString("state"));
    assertTrue(job.getJSONArray("rows").getJSONObject(0).getJSONObject("error")
        .getString("message").contains("boom"));
    assertTrue("the DAL session is discarded after the failed row", binder.rollbacks >= 1);
  }

  @Test
  public void progressIsVisibleWhileTheJobRuns() throws Exception {
    int[] seenProcessed = {-1};
    String[] holder = new String[1];
    BatchJobService.RowRunner runner = rowOps -> {
      if ("p2".equals(rowOps.getJSONObject(0).getString("id"))) {
        JSONObject mid = status(ALICE, holder[0]);
        seenProcessed[0] = mid.getInt("processedRows");
        assertEquals("RUNNING", mid.getString("state"));
        assertFalse(mid.getBoolean("done"));
      }
      return committing(new ArrayList<>()).run(rowOps);
    };
    holder[0] = submit(ALICE, rows(3), runner);
    executor.runAll();
    assertEquals(2, seenProcessed[0]);
  }

  @Test
  public void theWorkerBindsTheOwnerAndAlwaysReleasesTheThread() throws Exception {
    String id = submit(ALICE, rows(1), rowOps -> {
      throw new IllegalStateException("x");
    });
    executor.runAll();
    assertNotNull(id);
    assertEquals(1, binder.binds);
    assertEquals("one unbind per bind", 1, binder.unbinds);
    assertEquals(ALICE, binder.lastOwner);
  }

  // ── cancel ───────────────────────────────────────────────────────────────

  @Test
  public void cancelStopsBetweenRowsAndKeepsTheCommittedOnes() throws Exception {
    String[] holder = new String[1];
    BatchJobService.RowRunner runner = rowOps -> {
      if ("p0".equals(rowOps.getJSONObject(0).getString("id"))) {
        service.cancel(ALICE, holder[0]);
      }
      return committing(new ArrayList<>()).run(rowOps);
    };
    holder[0] = submit(ALICE, rows(3), runner);
    executor.runAll();

    JSONObject job = status(ALICE, holder[0]);
    assertEquals("CANCELLED", job.getString("state"));
    assertEquals("row 0 committed before the stop; rows 1 and 2 never started", 1,
        job.getInt("processedRows"));
    assertTrue(job.getJSONArray("rows").getJSONObject(0).getBoolean("committed"));
  }

  @Test
  public void aQueuedJobCancelsAtOnceAndTheWorkerSkipsIt() throws Exception {
    List<JSONArray> seen = new ArrayList<>();
    String id = submit(ALICE, rows(2), committing(seen));

    JSONObject cancelled = service.cancel(ALICE, id);
    executor.runAll();

    assertEquals("CANCELLED", cancelled.getString("state"));
    assertTrue("the failed QUEUED to RUNNING claim makes the worker skip", seen.isEmpty());
    assertEquals(0, binder.binds);
    assertTrue(service.heldJobs().isEmpty());
  }

  @Test
  public void aFinishedJobCannotBeCancelled() throws Exception {
    String id = submit(ALICE, rows(1), committing(new ArrayList<>()));
    executor.runAll();
    try {
      service.cancel(ALICE, id);
      fail("expected 409");
    } catch (JobException e) {
      assertEquals(409, e.getStatus());
    }
  }

  // ── access control ───────────────────────────────────────────────────────

  @Test
  public void anotherUserOrAnotherTenantCannotSeeOrCancelTheJob() throws Exception {
    String id = submit(ALICE, rows(1), committing(new ArrayList<>()));
    for (Owner stranger : new Owner[] {BOB, EVE}) {
      try {
        service.status(stranger, id);
        fail("expected 404");
      } catch (JobException e) {
        assertEquals(404, e.getStatus());
      }
      try {
        service.cancel(stranger, id);
        fail("expected 404");
      } catch (JobException e) {
        assertEquals(404, e.getStatus());
      }
    }
    assertEquals("the stranger's cancel changed nothing", "QUEUED",
        status(ALICE, id).getString("state"));
    assertEquals(404, httpStatusOf(() -> {
      try {
        service.status(ALICE, "NOPE");
      } catch (org.codehaus.jettison.json.JSONException e) {
        throw new IllegalStateException(e);
      }
    }));
  }

  // ── liveness: heartbeat, sweep, conditional updates ──────────────────────

  @Test
  public void aQueuedJobWhoseNodeKeepsBeatingIsNotInterruptedPastTheStaleWindow() throws Exception {
    String id = submit(ALICE, rows(1), committing(new ArrayList<>()));
    for (int t = 0; t < 12; t++) {
      store.now += BatchJobService.HEARTBEAT_INTERVAL_SECONDS;
      service.heartbeatTick();
    }
    assertTrue(store.now > BatchJobService.STALE_SECONDS);
    assertEquals("QUEUED", status(ALICE, id).getString("state"));
  }

  @Test
  public void aQueuedJobHeldByADeadNodeIsInterrupted() throws Exception {
    String id = submit(ALICE, rows(1), committing(new ArrayList<>()));
    store.now += BatchJobService.STALE_SECONDS + 1; // nobody refreshes it

    JSONObject job = status(ALICE, id);

    assertEquals("INTERRUPTED", job.getString("state"));
    assertTrue(job.getBoolean("done"));
    assertTrue(job.getString("hint").contains("look them up before resending"));
  }

  @Test
  public void twoNodesOneWithAFreshHeartbeatIsLeftAloneOneWithAStaleHeartbeatIsInterrupted()
      throws Exception {
    String live = submit(ALICE, rows(1), committing(new ArrayList<>()));
    String dead = submit(BOB, rows(1), committing(new ArrayList<>()));
    store.now += BatchJobService.STALE_SECONDS - 5;
    store.beat(live); // the live node's timer
    store.now += 10;

    assertEquals("QUEUED", status(ALICE, live).getString("state"));
    assertEquals("INTERRUPTED", status(BOB, dead).getString("state"));
  }

  @Test
  public void aRunningJobInterruptedMidwayKeepsTheRowsAlreadyFlushedAndTheWorkerStops()
      throws Exception {
    List<JSONArray> seen = new ArrayList<>();
    String[] holder = new String[1];
    BatchJobService.RowRunner runner = rowOps -> {
      JSONObject out = committing(seen).run(rowOps);
      if ("p1".equals(rowOps.getJSONObject(0).getString("id"))) {
        store.now += BatchJobService.STALE_SECONDS + 1; // a stall: the heartbeat goes stale
        store.sweepStale(BatchJobService.STALE_SECONDS); // another node's status read
      }
      return out;
    };
    holder[0] = submit(ALICE, rows(4), runner);
    executor.runAll();

    JSONObject job = status(ALICE, holder[0]);
    assertEquals("INTERRUPTED", job.getString("state"));
    assertEquals("rows 0 was flushed; row 1 finished after the sweep and could not be written", 1,
        job.getInt("processedRows"));
    assertEquals("the worker did not carry on committing rows it can no longer report", 2,
        seen.size());
  }

  @Test
  public void aWorkerCannotOverwriteTerminalStates() throws Exception {
    String id = submit(ALICE, rows(1), committing(new ArrayList<>()));
    service.cancel(ALICE, id);

    boolean moved = store.transition(id, BatchJobState.SUCCEEDED,
        List.of(BatchJobState.RUNNING), "[]");

    assertFalse(moved);
    assertEquals("CANCELLED", status(ALICE, id).getString("state"));
  }

  @Test
  public void parityBothChannelsRenderTheSameStoredResultIdentically() throws Exception {
    List<JSONArray> restSeen = new ArrayList<>();
    List<JSONArray> mcpSeen = new ArrayList<>();
    JSONArray ops = new JSONArray().put(op("a", null)).put(op("a.c", "a")).put(op("b", null));

    String rest = service.submit(ALICE, ops, "rest", committing(restSeen)).getString("jobId");
    executor.runAll();
    String mcp = service.submit(ALICE, ops, "mcp", committing(mcpSeen)).getString("jobId");
    executor.runAll();

    assertEquals("both channels feed executeBatch the same rows",
        restSeen.toString(), mcpSeen.toString());
    JSONObject a = status(ALICE, rest);
    JSONObject b = status(ALICE, mcp);
    assertEquals("rest", a.remove("channel"));
    assertEquals("mcp", b.remove("channel"));
    a.remove("jobId");
    b.remove("jobId");
    assertEquals(a.toString(), b.toString());
  }

  // ── fakes ────────────────────────────────────────────────────────────────

  /** In-memory store; {@code now} is the shared clock in seconds. */
  private static final class FakeStore implements BatchJobStore {
    final Map<String, JobRecord> jobs = new LinkedHashMap<>();
    final Map<String, Long> heartbeat = new LinkedHashMap<>();
    long now = 1_000;

    void beat(String id) {
      heartbeat.put(id, now);
    }

    @Override
    public synchronized void insert(JobRecord job) {
      job.state = BatchJobState.QUEUED;
      jobs.put(job.id, job);
      beat(job.id);
    }

    @Override
    public synchronized JobRecord find(String jobId) {
      JobRecord original = jobs.get(jobId);
      if (original == null) {
        return null;
      }
      JobRecord copy = new JobRecord();
      copy.id = original.id;
      copy.clientId = original.clientId;
      copy.orgId = original.orgId;
      copy.userId = original.userId;
      copy.roleId = original.roleId;
      copy.channel = original.channel;
      copy.state = original.state;
      copy.totalRows = original.totalRows;
      copy.totalOps = original.totalOps;
      copy.processedRows = original.processedRows;
      copy.cancelRequested = original.cancelRequested;
      copy.result = original.result;
      return copy;
    }

    @Override
    public synchronized Control readControl(String jobId) {
      JobRecord job = jobs.get(jobId);
      return job == null ? null : new Control(job.state, job.cancelRequested);
    }

    private long active(java.util.function.Predicate<JobRecord> filter) {
      return jobs.values().stream().filter(j -> j.state.isActive()).filter(filter).count();
    }

    @Override
    public synchronized int countActiveForUser(String clientId, String userId) {
      return (int) active(j -> j.clientId.equals(clientId) && j.userId.equals(userId));
    }

    @Override
    public synchronized int countActiveForClient(String clientId) {
      return (int) active(j -> j.clientId.equals(clientId));
    }

    @Override
    public synchronized boolean claim(String jobId) {
      JobRecord job = jobs.get(jobId);
      if (job == null || job.state != BatchJobState.QUEUED) {
        return false;
      }
      job.state = BatchJobState.RUNNING;
      beat(jobId);
      return true;
    }

    @Override
    public synchronized boolean flush(String jobId, int processedRows, String result) {
      JobRecord job = jobs.get(jobId);
      if (job == null || job.state != BatchJobState.RUNNING) {
        return false;
      }
      job.processedRows = processedRows;
      job.result = result;
      beat(jobId);
      return true;
    }

    @Override
    public synchronized boolean transition(String jobId, BatchJobState to,
        Collection<BatchJobState> from, String result) {
      JobRecord job = jobs.get(jobId);
      if (job == null || !from.contains(job.state)) {
        return false;
      }
      job.state = to;
      if (result != null) {
        job.result = result;
      }
      return true;
    }

    @Override
    public synchronized boolean requestCancel(String jobId) {
      JobRecord job = jobs.get(jobId);
      if (job == null || job.state.isTerminal()) {
        return false;
      }
      job.cancelRequested = true;
      return true;
    }

    @Override
    public synchronized void heartbeat(Collection<String> jobIds) {
      for (String id : jobIds) {
        JobRecord job = jobs.get(id);
        if (job != null && job.state.isActive()) {
          beat(id);
        }
      }
    }

    @Override
    public synchronized int sweepStale(int staleSeconds) {
      int swept = 0;
      for (JobRecord job : jobs.values()) {
        if (job.state.isActive() && now - heartbeat.get(job.id) > staleSeconds) {
          job.state = BatchJobState.INTERRUPTED;
          swept++;
        }
      }
      return swept;
    }

    @Override
    public synchronized int purgeFinished(int retentionHours) {
      return 0;
    }

    @Override
    public synchronized void delete(String jobId) {
      jobs.remove(jobId);
      heartbeat.remove(jobId);
    }
  }

  /** Collects submitted tasks; the test decides when the worker thread "runs". */
  private static final class ManualExecutor extends AbstractExecutorService {
    final List<Runnable> queue = new ArrayList<>();
    boolean reject;
    boolean stopped;

    void runAll() {
      while (!queue.isEmpty()) {
        queue.remove(0).run();
      }
    }

    @Override
    public void execute(Runnable command) {
      if (reject) {
        throw new RejectedExecutionException("queue full");
      }
      queue.add(command);
    }

    @Override
    public void shutdown() {
      // The fake executor has no pool to stop: the tests assert on its queue, not on threads.
    }

    @Override
    public List<Runnable> shutdownNow() {
      stopped = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }

  private static final class RecordingBinder implements BatchJobService.ContextBinder {
    int binds;
    int unbinds;
    int rollbacks;
    boolean failBind;
    Owner lastOwner;

    @Override
    public void bind(Owner owner) {
      binds++;
      lastOwner = owner;
      if (failBind) {
        throw new IllegalStateException("cannot bind");
      }
    }

    @Override
    public void rollback() {
      rollbacks++;
    }

    @Override
    public void unbind() {
      unbinds++;
    }
  }
}
