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

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.Level;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.exception.GenericJDBCException;
import org.hibernate.resource.transaction.spi.TransactionStatus;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.service.json.DefaultJsonDataService;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.payment.TestLogCapture;
import com.etendoerp.go.usage.quota.QuotaClientCache;
import com.etendoerp.go.usage.quota.QuotaDefinition;
import com.etendoerp.go.usage.quota.QuotaEvaluation;
import com.etendoerp.go.usage.quota.QuotaPeriod;
import com.etendoerp.go.usage.quota.QuotaVerdict;
import com.etendoerp.go.usage.quota.QuotaWriteGuard;

/**
 * The plan-quota gate around a NEO create (REST single, {@code /sws/neo/batch} and MCP).
 *
 * <p>The check runs after the insert and before the commit, so the gate decides who ends the
 * transaction: itself for a checked create outside a batch (commit when the row may stand,
 * rollback when it is refused), nobody inside a batch (the batch commits or rolls back the whole
 * request). A failure of the check itself is logged under {@code [QUOTA]}: one raised before any
 * SQL leaves the create standing (fails open); one from the database, or a transaction marked
 * rollback-only, has poisoned the transaction and fails closed with {@code QUOTA_CHECK_FAILED}.
 *
 * @covers com.etendoerp.go.schemaforge.NeoQuotaWriteGate
 * @covers com.etendoerp.go.schemaforge.NeoCrudHandler
 */
@SuppressWarnings("java:S2187")
class NeoQuotaWriteGateTest {

  private static final String ENTITY = "Invoice";
  /** The request's client's cache entry the gate reads before the insert and checks after it. */
  private static final QuotaClientCache.Entry ENTRY = mock(QuotaClientCache.Entry.class);

  private QuotaWriteGuard guard;
  private OBDal dal;
  private Session session;
  private Transaction transaction;
  private MockedStatic<OBDal> obDal;
  private TestLogCapture logs;

  @BeforeEach
  void setUp() {
    guard = mock(QuotaWriteGuard.class);
    dal = mock(OBDal.class);
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
    session = mock(Session.class);
    transaction = mock(Transaction.class);
    when(dal.getSession()).thenReturn(session);
    when(session.getTransaction()).thenReturn(transaction);
    when(transaction.getStatus()).thenReturn(TransactionStatus.ACTIVE);
    logs = TestLogCapture.attachTo(NeoQuotaWriteGate.class, Level.INFO);
  }

  @AfterEach
  void tearDown() {
    logs.detach();
    obDal.close();
  }

  private static String created(String... ids) throws Exception {
    JSONArray data = new JSONArray();
    for (String id : ids) {
      data.put(new JSONObject().put(JsonConstants.ID, id));
    }
    return new JSONObject().put(JsonConstants.RESPONSE_RESPONSE, new JSONObject()
        .put(JsonConstants.RESPONSE_STATUS, JsonConstants.RPCREQUEST_STATUS_SUCCESS)
        .put(JsonConstants.RESPONSE_DATA, data)).toString();
  }

  private static String failedAdd() throws Exception {
    return new JSONObject().put(JsonConstants.RESPONSE_RESPONSE, new JSONObject()
        .put(JsonConstants.RESPONSE_STATUS, JsonConstants.RPCREQUEST_STATUS_FAILURE)).toString();
  }

  private static QuotaEvaluation denial() {
    QuotaDefinition quota = QuotaDefinition.builder().resourceId("RES-DOCS")
        .resourceKey("POSTED_SALES_INVOICES").resourceName("Posted sales invoices")
        .unitLabel("Inv").enforcementMode("block").includedQty(100L).warningThreshold(80L)
        .build();
    Date start = new Date(1_790_000_000_000L);
    Date end = new Date(1_792_600_000_000L);
    return new QuotaEvaluation(quota, "month", QuotaPeriod.current(start, end, false,
        new Date(1_791_000_000_000L)), 100L, QuotaVerdict.DENY);
  }

  private NeoQuotaWriteGate.Create begin(boolean batchOwnsTransaction) {
    when(guard.entryIfChecked(ENTITY)).thenReturn(Optional.of(ENTRY));
    return NeoQuotaWriteGate.begin(ENTITY, guard, batchOwnsTransaction);
  }

  @Nested
  @DisplayName("an entity no block quota counts")
  class Unchecked {

    @Test
    void runsExactlyAsBeforeNoCheckNoCommitNoRollback() throws Exception {
      when(guard.entryIfChecked(ENTITY)).thenReturn(Optional.empty());

      try (NeoQuotaWriteGate.Create create = NeoQuotaWriteGate.begin(ENTITY, guard, false)) {
        assertFalse(create.isChecked());
        assertFalse(create.ownsTransaction());
        assertNull(create.jsonService(null), "the caller's own service is kept");
        assertNull(create.finish(created("ROW-1")));
      }

      verify(guard, never()).refusalFor(any(), anyString(), any());
      obDal.verifyNoInteractions();
    }
  }

  /**
   * REPRO (ETP-5051 redesign): the request's client's quota entry is built on the request's own
   * session. A failure there may have aborted the transaction, and nothing says whether the create
   * is capped, so it fails closed: QUOTA_CHECK_FAILED, nothing saved.
   */
  @Nested
  @DisplayName("the client's quota entry cannot be read")
  class EntryBuildFails {

    @BeforeEach
    void entryBuildFails() {
      when(guard.entryIfChecked(ENTITY)).thenThrow(new IllegalStateException("wrapped",
          new GenericJDBCException("could not execute query",
              new java.sql.SQLException("canceling statement due to statement timeout"))));
    }

    @Test
    void outsideABatchTheCreateIsRolledBackAndAnsweredQuotaCheckFailed() throws Exception {
      NeoResponse response;
      try (NeoQuotaWriteGate.Create create = NeoQuotaWriteGate.begin(ENTITY, guard, false)) {
        assertTrue(create.isChecked());
        assertTrue(create.ownsTransaction(), "the add must not commit on its own");
        response = create.finish(failedAdd());
      }

      assertCheckFailed(response);
      verify(dal, times(1)).rollbackAndClose();
      verify(dal, never()).commitAndClose();
      verify(guard, never()).refusalFor(any(), anyString(), any());
      assertTrue(logs.messagesAt(Level.ERROR).get(0)
          .startsWith("[QUOTA] Could not read the quotas of the request's client"),
          String.valueOf(logs.messagesAt(Level.ERROR)));
    }

    /**
     * REPRO (ETP-5051 re-review W1): the gate already knows before the insert that the create
     * fails closed, so the create itself — callout cascade, add — never runs.
     */
    @Test
    void theCreateNeverRunsAndIsRefusedBeforeTheWrite() throws Exception {
      NeoQuotaWriteGate.GatedCreate add = mock(NeoQuotaWriteGate.GatedCreate.class);

      NeoQuotaWriteGate.Gated gated = NeoQuotaWriteGate.run(ENTITY, null, add, guard, false);

      assertCheckFailed(gated.refusal());
      verify(add, never()).run(any());
      verify(dal, times(1)).rollbackAndClose();
      verify(dal, never()).commitAndClose();
    }

    @Test
    void insideABatchTheCreateNeverRunsAndTheOperationFails() throws Exception {
      NeoQuotaWriteGate.GatedCreate add = mock(NeoQuotaWriteGate.GatedCreate.class);

      NeoQuotaWriteGate.Gated gated = NeoQuotaWriteGate.run(ENTITY, null, add, guard, true);

      assertCheckFailed(gated.refusal());
      verify(add, never()).run(any());
      verify(dal, never()).rollbackAndClose();
    }

    @Test
    void insideABatchTheOperationFailsAndTheBatchEndsTheTransaction() throws Exception {
      NeoResponse response;
      try (NeoQuotaWriteGate.Create create = NeoQuotaWriteGate.begin(ENTITY, guard, true)) {
        assertFalse(create.ownsTransaction());
        response = create.finish(created("ROW-1"));
      }

      assertCheckFailed(response);
      verify(dal, never()).rollbackAndClose();
      verify(dal, never()).commitAndClose();
    }
  }

  @Nested
  @DisplayName("a checked create outside a batch")
  class OwnTransaction {

    /**
     * REPRO (merge of ETP-5782 into ETP-5051): since ETP-5782 the write path commits a single write
     * itself unless a caller owns the transaction. A create the gate owns must therefore run with
     * the thread marked caller-owned, or the insert is committed before the quota check can refuse
     * it. The mark is released afterwards, also when the create throws.
     */
    @Test
    void aCreateTheGateOwnsRunsWithTheThreadMarkedCallerOwned() throws Exception {
      assertFalse(BatchService.callerOwnsTransaction());

      String seen = NeoQuotaWriteGate.runDeferringCommit(
          service -> String.valueOf(BatchService.callerOwnsTransaction()), null);

      assertEquals("true", seen, "the write path must defer its commit to the gate");
      assertFalse(BatchService.callerOwnsTransaction(), "the mark is released after the create");

      IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(
          IllegalStateException.class, () -> NeoQuotaWriteGate.runDeferringCommit(service -> {
            throw new IllegalStateException("add failed");
          }, null));
      assertEquals("add failed", thrown.getMessage());
      assertFalse(BatchService.callerOwnsTransaction(), "the mark is released when it throws");
    }

    /**
     * ETP-5051 re-review N1: the entry read before the insert is the one checked after it — no
     * second read, so no rebuild in between can change the answer or fail open.
     */
    @Test
    void theEntryReadBeforeTheInsertIsTheOneChecked() throws Exception {
      when(guard.entryIfChecked(ENTITY)).thenReturn(Optional.of(ENTRY));
      when(guard.refusalFor(any(), eq(ENTITY), any())).thenReturn(Optional.empty());
      String added = created("ROW-1");

      // Inside a batch, so the caller's own service is kept and no CDI lookup is needed.
      NeoQuotaWriteGate.Gated gated = NeoQuotaWriteGate.run(ENTITY, null, service -> added,
          guard, true);

      assertNull(gated.refusal());
      assertEquals(added, gated.result());
      verify(guard, times(1)).entryIfChecked(ENTITY);
      verify(guard).refusalFor(org.mockito.ArgumentMatchers.same(ENTRY), eq(ENTITY),
          eq(List.of("ROW-1")));
    }

    @Test
    void aMatchingRowOverQuotaIsRolledBackAndRefusedWithTheContractBody() throws Exception {
      QuotaEvaluation denial = denial();
      when(guard.refusalFor(ENTRY, ENTITY, List.of("ROW-1"))).thenReturn(Optional.of(denial));

      NeoResponse response;
      try (NeoQuotaWriteGate.Create create = begin(false)) {
        assertTrue(create.isChecked());
        assertTrue(create.ownsTransaction());
        response = create.finish(created("ROW-1"));
      }

      assertNotNull(response);
      assertEquals(403, response.getHttpStatus());
      JSONObject error = response.getBody().getJSONObject("error");
      assertEquals("Quota exceeded: Posted sales invoices (100/100 Inv)",
          error.getString("message"));
      assertEquals(403, error.getInt("status"));
      assertEquals("QUOTA_EXCEEDED", error.getString("code"));
      JSONObject quota = error.getJSONObject("quota");
      assertEquals("POSTED_SALES_INVOICES", quota.getString("resourceKey"));
      assertEquals("Posted sales invoices", quota.getString("resourceName"));
      assertEquals("Inv", quota.getString("unitLabel"));
      assertEquals(100L, quota.getLong("includedQty"));
      assertEquals(100L, quota.getLong("consumed"));
      assertEquals("DENY", quota.getString("verdict"));
      assertEquals("block", quota.getString("enforcementMode"));
      // Rolled back exactly once — close() after finish() must not end it again — never committed.
      verify(dal, times(1)).rollbackAndClose();
      verify(dal, never()).commitAndClose();
      assertTrue(logs.messagesAt(Level.INFO).get(0).startsWith("[QUOTA] Refused a create of "
          + ENTITY), String.valueOf(logs.messagesAt(Level.INFO)));
    }

    @Test
    void aRowOutsideTheRestrictionIsCommitted() throws Exception {
      when(guard.refusalFor(ENTRY, ENTITY, List.of("ROW-1"))).thenReturn(Optional.empty());

      try (NeoQuotaWriteGate.Create create = begin(false)) {
        assertNull(create.finish(created("ROW-1")));
      }

      verify(dal, times(1)).commitAndClose();
      verify(dal, never()).rollbackAndClose();
    }

    /**
     * An error raised before any SQL — an invalid restriction fragment — leaves the transaction
     * intact, so the create stands and is committed.
     */
    @Test
    void aCheckFailingBeforeAnySqlFailsOpenTheCreateIsCommittedAndLogged() throws Exception {
      when(guard.refusalFor(any(), eq(ENTITY), any())).thenThrow(
          new IllegalArgumentException("HQL restriction has unbalanced parentheses"));

      NeoResponse response;
      try (NeoQuotaWriteGate.Create create = begin(false)) {
        response = create.finish(created("ROW-1"));
      }

      assertNull(response);
      verify(dal).commitAndClose();
      verify(dal, never()).rollbackAndClose();
      List<String> errors = logs.messagesAt(Level.ERROR);
      assertEquals(1, errors.size(), String.valueOf(errors));
      assertTrue(errors.get(0).startsWith("[QUOTA]"), errors.get(0));
      assertTrue(errors.get(0).contains("the create stands"), errors.get(0));
    }

    /**
     * REPRO (ETP-5051 review B1): a check that failed on the database leaves Hibernate's transaction
     * marked rollback-only. Committing it would silently roll the row back while the caller is told
     * it exists. The gate must fail closed: roll back, never commit, answer QUOTA_CHECK_FAILED.
     */
    @Test
    void aCheckThatLeftTheTransactionRollbackOnlyFailsClosedAndSavesNothing() throws Exception {
      when(guard.refusalFor(any(), eq(ENTITY), any()))
          .thenThrow(new IllegalStateException("boom"));
      when(transaction.getStatus()).thenReturn(TransactionStatus.MARKED_ROLLBACK);

      NeoResponse response;
      try (NeoQuotaWriteGate.Create create = begin(false)) {
        response = create.finish(created("ROW-1"));
      }

      assertCheckFailed(response);
      verify(dal, times(1)).rollbackAndClose();
      verify(dal, never()).commitAndClose();
    }

    /** A JDBC error anywhere in the cause chain is a poisoned transaction too. */
    @Test
    void aDatabaseErrorInTheCauseChainFailsClosed() throws Exception {
      when(guard.refusalFor(any(), eq(ENTITY), any())).thenThrow(
          new IllegalStateException("wrapped",
          new GenericJDBCException("could not execute query",
              new java.sql.SQLException("current transaction is aborted"))));

      NeoResponse response;
      try (NeoQuotaWriteGate.Create create = begin(false)) {
        response = create.finish(created("ROW-1"));
      }

      assertCheckFailed(response);
      verify(dal, times(1)).rollbackAndClose();
      verify(dal, never()).commitAndClose();
    }

    /**
     * ETP-5051 re-review W1: a {@code QueryException} in the cause chain is not by itself a
     * database failure. This mock keeps the transaction ACTIVE, so the create stands. Live,
     * Hibernate marks the transaction rollback-only when it fails to parse HQL, and the create then
     * fails closed (QA BUG-4, accepted) — see the rollback-only test above. Fail-open in practice
     * applies only to a fragment {@code UsageQueryComposer} rejects before Hibernate sees it.
     */
    @Test
    void aQueryExceptionAloneWithTheTransactionIntactIsNotADatabaseFailure() throws Exception {
      when(guard.refusalFor(any(), eq(ENTITY), any())).thenThrow(
          new IllegalStateException("wrapped",
          new org.hibernate.QueryException("unexpected token: )")));

      NeoResponse response;
      try (NeoQuotaWriteGate.Create create = begin(false)) {
        response = create.finish(created("ROW-1"));
      }

      assertNull(response);
      verify(dal).commitAndClose();
      verify(dal, never()).rollbackAndClose();
    }

    /**
     * ETP-5051 review W1: the gate's own commit failing (a deferred constraint) must reach the
     * caller as core's error JSON, exactly as a failing self-committing add did, not as an
     * exception.
     */
    @Test
    void aFailingCommitHandsTheCallerCoresErrorJson() throws Exception {
      when(guard.refusalFor(any(), eq(ENTITY), any())).thenReturn(Optional.empty());
      org.mockito.Mockito.doThrow(new IllegalStateException("deferred constraint violated"))
          .when(dal).commitAndClose();

      NeoResponse response;
      String result;
      try (NeoQuotaWriteGate.Create create = begin(false)) {
        response = create.finish(created("ROW-1"));
        result = create.result();
      }

      assertNull(response);
      JSONObject inner = new JSONObject(result).getJSONObject(JsonConstants.RESPONSE_RESPONSE);
      assertEquals(JsonConstants.RPCREQUEST_STATUS_FAILURE,
          inner.getInt(JsonConstants.RESPONSE_STATUS));
      // Committed once; core closed the session itself when the commit failed.
      verify(dal, times(1)).commitAndClose();
    }

    @Test
    void aFailedAddIsRolledBackAndLeftToTheCallerToReport() throws Exception {
      NeoResponse response;
      try (NeoQuotaWriteGate.Create create = begin(false)) {
        response = create.finish(failedAdd());
      }

      assertNull(response);
      verify(guard, never()).refusalFor(any(), anyString(), any());
      verify(dal, times(1)).rollbackAndClose();
      verify(dal, never()).commitAndClose();
    }

    @Test
    void aCreateThatThrowsIsRolledBackOnClose() {
      try (NeoQuotaWriteGate.Create create = begin(false)) {
        assertTrue(create.ownsTransaction());
        // the add throws here: finish() is never reached
      }

      verify(dal, times(1)).rollbackAndClose();
      verify(dal, never()).commitAndClose();
    }
  }

  /**
   * ETP-5051 (Alex MAJOR-1) — the transaction branch of the public {@code run(...)}, the one entry
   * both create channels use. Since ETP-5782 {@link NeoBatchJsonDataService} commits a single write
   * itself unless the thread is marked caller-owned, so a checked create the gate owns must run
   * through the deferred-commit service WITH the mark set — then only the gate commits (the row
   * may stand) or rolls back (refused, or the create threw). Driven through {@code run}, not
   * {@code runDeferringCommit}: deleting or inverting the ownership branch must turn these red.
   */
  @Nested
  @DisplayName("run(...) decides who ends the transaction")
  class RunOwnership {

    private MockedStatic<NeoBatchJsonDataService> deferred;
    private NeoBatchJsonDataService deferredService;
    private DefaultJsonDataService callerService;

    @BeforeEach
    void mockDeferredService() {
      // DefaultJsonDataService's static initialiser asks Weld for its singleton; instrumenting the
      // services initialises the class, so it must happen with Weld stubbed.
      try (MockedStatic<org.openbravo.base.weld.WeldUtils> weld =
          mockStatic(org.openbravo.base.weld.WeldUtils.class)) {
        deferredService = mock(NeoBatchJsonDataService.class);
        callerService = mock(DefaultJsonDataService.class);
        deferred = mockStatic(NeoBatchJsonDataService.class);
      }
      deferred.when(NeoBatchJsonDataService::deferredCommitInstance).thenReturn(deferredService);
      assertFalse(BatchService.callerOwnsTransaction(), "no leftover mark from another test");
    }

    @AfterEach
    void closeDeferredService() {
      if (deferred != null) {
        deferred.close();
      }
    }

    /** What the create saw while it ran. */
    private final class Probe implements NeoQuotaWriteGate.GatedCreate {
      private final String result;
      Boolean markedWhileRunning;
      DefaultJsonDataService serviceUsed;

      Probe(String result) {
        this.result = result;
      }

      @Override
      public String run(DefaultJsonDataService service) {
        markedWhileRunning = BatchService.callerOwnsTransaction();
        serviceUsed = service;
        return result;
      }
    }

    @Test
    @DisplayName("a gate-owned create that passes runs marked, deferred, and is committed once")
    void aPassingGateOwnedCreateRunsMarkedAndIsCommittedExactlyOnce() throws Exception {
      when(guard.entryIfChecked(ENTITY)).thenReturn(Optional.of(ENTRY));
      when(guard.refusalFor(ENTRY, ENTITY, List.of("ROW-1"))).thenReturn(Optional.empty());
      Probe create = new Probe(created("ROW-1"));

      NeoQuotaWriteGate.Gated gated =
          NeoQuotaWriteGate.run(ENTITY, callerService, create, guard, false);

      assertTrue(create.markedWhileRunning, "the write path must defer its commit to the gate");
      assertFalse(BatchService.callerOwnsTransaction(), "the mark is released after the create");
      org.junit.jupiter.api.Assertions.assertSame(deferredService, create.serviceUsed);
      assertNull(gated.refusal());
      assertEquals(create.result, gated.result());
      verify(dal, times(1)).commitAndClose();
      verify(dal, never()).rollbackAndClose();
    }

    @Test
    @DisplayName("a gate-owned create that is refused is rolled back and never committed")
    void aRefusedGateOwnedCreateIsRolledBackAndNeverCommitted() throws Exception {
      when(guard.entryIfChecked(ENTITY)).thenReturn(Optional.of(ENTRY));
      when(guard.refusalFor(ENTRY, ENTITY, List.of("ROW-1"))).thenReturn(Optional.of(denial()));
      Probe create = new Probe(created("ROW-1"));

      NeoQuotaWriteGate.Gated gated =
          NeoQuotaWriteGate.run(ENTITY, callerService, create, guard, false);

      assertTrue(create.markedWhileRunning, "the insert must not have been committed already");
      assertFalse(BatchService.callerOwnsTransaction());
      assertNotNull(gated.refusal());
      assertEquals(403, gated.refusal().getHttpStatus());
      assertEquals("QUOTA_EXCEEDED",
          gated.refusal().getBody().getJSONObject("error").getString("code"));
      verify(dal, times(1)).rollbackAndClose();
      verify(dal, never()).commitAndClose();
    }

    @Test
    @DisplayName("a gate-owned create that throws leaves no mark and commits nothing")
    void aThrowingGateOwnedCreateLeavesNoMarkAndCommitsNothing() throws Exception {
      when(guard.entryIfChecked(ENTITY)).thenReturn(Optional.of(ENTRY));
      boolean[] marked = new boolean[1];

      IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(
          IllegalStateException.class, () -> NeoQuotaWriteGate.run(ENTITY, callerService,
              service -> {
                marked[0] = BatchService.callerOwnsTransaction();
                throw new IllegalStateException("add failed");
              }, guard, false));

      assertEquals("add failed", thrown.getMessage());
      assertTrue(marked[0], "the create ran marked");
      assertFalse(BatchService.callerOwnsTransaction(), "the mark is released when it throws");
      verify(dal, never()).commitAndClose();
      verify(dal, times(1)).rollbackAndClose();
      verify(guard, never()).refusalFor(any(), anyString(), any());
    }

    @Test
    @DisplayName("inside a batch the gate neither sets nor ends the mark, and never commits")
    void insideABatchTheGateLeavesTheMarkAndTheTransactionToTheBatch() throws Exception {
      when(guard.entryIfChecked(ENTITY)).thenReturn(Optional.of(ENTRY));
      when(guard.refusalFor(ENTRY, ENTITY, List.of("ROW-1"))).thenReturn(Optional.empty());

      // Unmarked thread: the gate must not add a mark of its own.
      Probe unmarked = new Probe(created("ROW-1"));
      NeoQuotaWriteGate.run(ENTITY, callerService, unmarked, guard, true);
      assertFalse(unmarked.markedWhileRunning, "a batch-owned create is not marked by the gate");
      org.junit.jupiter.api.Assertions.assertSame(callerService, unmarked.serviceUsed);

      // Marked by the batch: the gate must not end the batch's mark.
      Probe inBatch = new Probe(created("ROW-1"));
      BatchService.beginCallerOwnedTransaction();
      try {
        NeoQuotaWriteGate.run(ENTITY, callerService, inBatch, guard, true);
        assertTrue(BatchService.callerOwnsTransaction(), "the batch's mark survives the create");
      } finally {
        BatchService.endCallerOwnedTransaction();
      }
      assertTrue(inBatch.markedWhileRunning);

      verify(dal, never()).commitAndClose();
      verify(dal, never()).rollbackAndClose();
      deferred.verify(NeoBatchJsonDataService::deferredCommitInstance, never());
    }

    @Test
    @DisplayName("an unchecked create outside a batch runs unmarked and is not committed by the gate")
    void anUncheckedCreateRunsUnmarkedAndIsNotCommittedByTheGate() throws Exception {
      when(guard.entryIfChecked(ENTITY)).thenReturn(Optional.empty());
      Probe create = new Probe(created("ROW-1"));

      NeoQuotaWriteGate.Gated gated =
          NeoQuotaWriteGate.run(ENTITY, callerService, create, guard, false);

      assertFalse(create.markedWhileRunning, "an unchecked create keeps core's own commit");
      org.junit.jupiter.api.Assertions.assertSame(callerService, create.serviceUsed);
      assertNull(gated.refusal());
      assertEquals(create.result, gated.result());
      verify(dal, never()).commitAndClose();
      verify(dal, never()).rollbackAndClose();
      deferred.verify(NeoBatchJsonDataService::deferredCommitInstance, never());
    }
  }

  @Nested
  @DisplayName("a checked create inside a batch")
  class BatchOwnsTransaction {

    @Test
    void aRefusalIsReturnedButTheTransactionIsLeftToTheBatch() throws Exception {
      when(guard.refusalFor(ENTRY, ENTITY, List.of("ROW-1"))).thenReturn(Optional.of(denial()));

      NeoResponse response;
      try (NeoQuotaWriteGate.Create create = begin(true)) {
        assertTrue(create.isChecked());
        assertFalse(create.ownsTransaction());
        assertNull(create.jsonService(null), "the batch's deferred service is kept");
        response = create.finish(created("ROW-1"));
      }

      assertEquals(403, response.getHttpStatus());
      assertEquals("QUOTA_EXCEEDED",
          response.getBody().getJSONObject("error").getString("code"));
      verify(dal, never()).rollbackAndClose();
      verify(dal, never()).commitAndClose();
    }

    @Test
    void anAllowedRowIsNeverCommittedByTheGate() throws Exception {
      when(guard.refusalFor(ENTRY, ENTITY, List.of("ROW-1"))).thenReturn(Optional.empty());

      try (NeoQuotaWriteGate.Create create = begin(true)) {
        assertNull(create.finish(created("ROW-1")));
      }

      verify(guard).refusalFor(ENTRY, ENTITY, List.of("ROW-1"));
      obDal.verifyNoInteractions();
    }

    @Test
    void aFailedAddOrAThrowIsNotRolledBackByTheGate() throws Exception {
      try (NeoQuotaWriteGate.Create create = begin(true)) {
        assertNull(create.finish(failedAdd()));
      }
      try (NeoQuotaWriteGate.Create create = begin(true)) {
        assertTrue(create.isChecked());
      }

      obDal.verifyNoInteractions();
    }
  }

  /** Inside a batch a poisoned check fails the operation, so the batch rolls back visibly. */
  @Test
  void insideABatchAPoisonedCheckFailsTheOperationAndLeavesTheRollbackToTheBatch()
      throws Exception {
    when(guard.refusalFor(any(), eq(ENTITY), any()))
          .thenThrow(new IllegalStateException("boom"));
    when(transaction.getStatus()).thenReturn(TransactionStatus.MARKED_ROLLBACK);

    NeoResponse response;
    try (NeoQuotaWriteGate.Create create = begin(true)) {
      response = create.finish(created("ROW-1"));
    }

    assertCheckFailed(response);
    verify(dal, never()).rollbackAndClose();
    verify(dal, never()).commitAndClose();
  }

  private static void assertCheckFailed(NeoResponse response) throws Exception {
    assertNotNull(response);
    assertEquals(500, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals(NeoQuotaWriteGate.CHECK_FAILED_CODE, error.getString("code"));
    assertEquals(500, error.getInt("status"));
    assertEquals(NeoQuotaWriteGate.CHECK_FAILED_MESSAGE, error.getString("message"));
  }

  @Nested
  @DisplayName("the production entry point")
  class ProductionBegin {

    @Test
    void insideABatchTheDefaultGateLeavesTheTransactionToTheBatch() {
      when(guard.entryIfChecked(ENTITY)).thenReturn(Optional.of(ENTRY));
      try (MockedStatic<QuotaWriteGuard> defaults = mockStatic(QuotaWriteGuard.class)) {
        defaults.when(QuotaWriteGuard::getDefault).thenReturn(guard);

        BatchService.beginCallerOwnedTransaction();
        try (NeoQuotaWriteGate.Create create = NeoQuotaWriteGate.begin(ENTITY)) {
          assertTrue(create.isChecked());
          assertFalse(create.ownsTransaction());
        } finally {
          BatchService.endCallerOwnedTransaction();
        }
        try (NeoQuotaWriteGate.Create create = NeoQuotaWriteGate.begin(ENTITY)) {
          assertTrue(create.ownsTransaction(), "outside a batch the gate owns its create");
          // close() without finish(): the create threw, so it is rolled back.
        }
      }

      verify(dal, times(1)).rollbackAndClose();
    }
  }

  @Nested
  @DisplayName("reading the ids an add created")
  class CreatedIds {

    @Test
    void theIdsOfEverySuccessfulRowAreRead() throws Exception {
      assertEquals(Optional.of(List.of("ROW-1", "ROW-2")),
          NeoQuotaWriteGate.createdIds(created("ROW-1", "ROW-2")));
    }

    @Test
    void rowsWithoutAnIdAreSkipped() throws Exception {
      String result = new JSONObject().put(JsonConstants.RESPONSE_RESPONSE, new JSONObject()
          .put(JsonConstants.RESPONSE_STATUS, JsonConstants.RPCREQUEST_STATUS_SUCCESS)
          .put(JsonConstants.RESPONSE_DATA, new JSONArray().put(new JSONObject())
              .put("not an object").put(new JSONObject().put(JsonConstants.ID, "ROW-3"))))
          .toString();

      assertEquals(Optional.of(List.of("ROW-3")), NeoQuotaWriteGate.createdIds(result));
    }

    @Test
    void aSuccessWithoutDataIsAnEmptyList() throws Exception {
      String result = new JSONObject().put(JsonConstants.RESPONSE_RESPONSE, new JSONObject()
          .put(JsonConstants.RESPONSE_STATUS, JsonConstants.RPCREQUEST_STATUS_SUCCESS))
          .toString();

      assertEquals(Optional.of(Collections.emptyList()), NeoQuotaWriteGate.createdIds(result));
    }

    @Test
    void aFailedBlankOrUnreadableResultIsEmpty() throws Exception {
      assertFalse(NeoQuotaWriteGate.createdIds(failedAdd()).isPresent());
      assertFalse(NeoQuotaWriteGate.createdIds(" ").isPresent());
      assertFalse(NeoQuotaWriteGate.createdIds(null).isPresent());
      assertFalse(NeoQuotaWriteGate.createdIds("{not json").isPresent());
      assertFalse(NeoQuotaWriteGate.createdIds("{}").isPresent());
    }
  }

  @Test
  void aSuccessfulAddThatCreatedNoRowsIsCommitted() throws Exception {
    when(guard.refusalFor(ENTRY, ENTITY, Collections.emptyList())).thenReturn(Optional.empty());

    try (NeoQuotaWriteGate.Create create = begin(false)) {
      assertNull(create.finish(created()));
    }

    verify(dal).commitAndClose();
    assertTrue(logs.messagesAt(Level.ERROR).isEmpty(), String.valueOf(logs.messagesAt(Level.ERROR)));
  }
}
