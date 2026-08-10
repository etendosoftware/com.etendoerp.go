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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Robustness / e2e-style coverage for the generic {@code /sws/neo/batch} endpoint
 * ({@link BatchService}) against the edge-case payloads the Contacts CSV import
 * (ETP-4447) sends through it. Added under ETP-4669 to close the "zero backend test
 * coverage for /batch" gap that the ETP-4668 {@code invoiceGrouping} coercion bug
 * exposed — that bug reached production through exactly this path with no test between
 * it and the client.
 *
 * <p><b>What these tests pin, and what they deliberately do not.</b> A true end-to-end
 * test would drive the real NEO CRUD pipeline against a seeded DB inside an
 * {@code OBBaseTest}. That is not runnable in this sandbox — the module's standalone
 * worktree has no wired core checkout, and (as documented on
 * {@code ReactivatePaymentHandlerRemoveIntegrationTest}) {@code OBBaseTest} boot fails
 * here with {@code MappingNotFoundException} for several classes. Following the project's
 * blessed fallback (drive {@link BatchService#executeBatch(JSONArray)} directly with a
 * constructed operations array), these tests instead assert the contract the batch
 * endpoint itself owns and that only it can break:
 * <ul>
 *   <li>{@link BatchService}'s own transaction lifecycle — one {@code commitAndClose} on success,
 *       {@code rollbackAndClose} and no commit on failure;</li>
 *   <li>the failure body's atomicity report — {@code atomic}, {@code persisted} and {@code hint}
 *       (IMP-23);</li>
 *   <li>a sub-operation failure is surfaced as a structured
 *       {@code {committed:false, failedAt, error:{status,message,detail}}} body carrying the
 *       sub-operation's own HTTP status — never masked into an opaque 500 or a raw stack trace;</li>
 *   <li>parent/child wiring ({@code parentRef} → {@code parentId}, {@code $ref:} substitution)
 *       reaches the CRUD layer, mirroring the real Contacts business-partner +
 *       location + contact shape.</li>
 * </ul>
 *
 * <p>The single generic CRUD seam — {@link NeoServletSupport#handleWithHooks} — is stubbed
 * per scenario to return the status the real pipeline returns for that failure MODE (missing
 * FK, over-length value, invalid list value, duplicate unique key). Whether the NEO CRUD layer
 * genuinely maps each specific input to that specific status (e.g. an over-length name to a 400
 * rather than a 500) is a core-Etendo / DB-constraint concern verified at the CRUD layer and in
 * CI, orthogonal to the batch-orchestration contract asserted here.
 *
 * <p><b>What that stub necessarily hides, and why no test in this class can prove the batch is
 * atomic (IMP-23).</b> The per-op commit that made the batch non-atomic happened inside the stubbed
 * seam — core's {@code DefaultJsonDataService#update} ends its success branch with its own
 * {@code OBDal.getInstance().commitAndClose()}. With that seam mocked, {@code verify(obDal,
 * never()).commitAndClose()} passes for a reason unrelated to atomicity: nothing downstream ever
 * ran. The same blindness applies to the fix: option B removes that commit by routing the loop
 * through {@link NeoBatchJsonDataService}, and a mocked seam never reaches either write path, so
 * these tests cannot tell the two apart. Only a DB-backed {@code OBBaseTest} could, and this
 * sandbox cannot boot one — the fix was verified by a live probe instead, recorded in IMP-23 §9.
 * What these assertions do pin is {@link BatchService}'s own lifecycle (never the broken part) and
 * the failure body's atomicity report, which is the caller-visible contract that changed twice.
 */
public class BatchServiceRobustnessTest {

  private static final String SPEC = "contacts";
  private static final String SPEC_ID = "spec-1";
  private static final String QUALIFIER = "business-partner";
  private static final String COMMITTED = "committed";
  private static final String PARENT_ID = "parentId";

  // ---------------------------------------------------------------------------
  // 1. Happy path — businessPartner + locationAddress + contact, mirroring the
  //    real Contacts CSV import shape, commits and wires parent refs.
  // ---------------------------------------------------------------------------
  @Test
  public void happyPathContactsBatchCommitsAndWiresParentRefs() throws Exception {
    BatchService service = BatchService.forBatchOnly();

    JSONArray ops = new JSONArray();
    ops.put(op("bp", "businessPartner").put("body", new JSONObject().put("name", "ACME S.L.")));
    ops.put(op("loc", "locationAddress").put("parentRef", "bp")
        .put("body", new JSONObject().put("addressLine1", "Main St 1")));
    ops.put(op("ct", "contact").put("parentRef", "bp")
        .put("body", new JSONObject().put("firstName", "Jane").put("businessPartner", "$ref:bp")));

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoServletSupport> support = mockStatic(NeoServletSupport.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      stubEntityResolution(support, obDal);
      support.when(() -> NeoServletSupport.handleWithHooks(anyString(), any(NeoContext.class), any()))
          .thenReturn(created("bp-1"), created("loc-1"), created("ct-1"));

      JSONObject result = service.executeBatch(ops);

      assertTrue("full contacts batch must commit", result.getBoolean(COMMITTED));
      JSONArray opResults = result.getJSONArray("operations");
      assertEquals("all three operations must be reported", 3, opResults.length());
      assertEquals("bp-1", opResults.getJSONObject(0).getString("recordId"));
      assertEquals("loc-1", opResults.getJSONObject(1).getString("recordId"));
      assertEquals("ct-1", opResults.getJSONObject(2).getString("recordId"));

      // Exactly one commit and no rollback on the happy path. Unchanged by IMP-23: the
      // success path was never the problem and was deliberately left byte-identical.
      verify(obDal).commitAndClose();
      verify(obDal, never()).rollbackAndClose();

      // Parent/child wiring actually reached the CRUD layer for the two child ops.
      ArgumentCaptor<NeoContext> ctxCaptor = ArgumentCaptor.forClass(NeoContext.class);
      support.verify(() -> NeoServletSupport.handleWithHooks(anyString(), ctxCaptor.capture(), any()),
          times(3));
      List<NeoContext> contexts = ctxCaptor.getAllValues();

      NeoContext locCtx = contexts.get(1);
      assertEquals("locationAddress parentRef must resolve to the bp record id in the body",
          "bp-1", locCtx.getRequestBody().getString(PARENT_ID));
      assertEquals("locationAddress parentId must also be exposed as a query param, like the "
          + "real HTTP endpoint", "bp-1", locCtx.getQueryParams().get(PARENT_ID));

      NeoContext contactCtx = contexts.get(2);
      assertEquals("contact $ref:bp must be substituted with the resolved bp record id",
          "bp-1", contactCtx.getRequestBody().getString("businessPartner"));
      assertEquals("contact parentRef must resolve to the bp record id",
          "bp-1", contactCtx.getRequestBody().getString(PARENT_ID));
    }
  }

  // ---------------------------------------------------------------------------
  // 2. Missing / unresolvable FK — a server-side "referenced record not found"
  //    must surface as the sub-status, never a raw 500, and roll everything back.
  // ---------------------------------------------------------------------------
  @Test
  public void missingForeignKeyFailsGracefullyWithoutRawServerError() throws Exception {
    JSONObject error = runSingleOpAndExpectFailure(
        NeoResponse.error(404, "Referenced record not found: BusinessPartner Category XYZ"));

    assertEquals("a missing/unresolvable FK must surface the sub-operation status, not 500",
        404, error.getInt("status"));
    assertNotNull("the underlying NEO error must be attached as detail for correlation",
        error.optJSONObject("detail"));
  }

  // ---------------------------------------------------------------------------
  // 3. Oversized field value — exceeding an AD_Column max length must be a clean
  //    validation failure, not a corrupted commit and not an opaque 500.
  // ---------------------------------------------------------------------------
  @Test
  public void oversizedFieldValueFailsAsValidationErrorNotCorruption() throws Exception {
    JSONObject error = runSingleOpAndExpectFailure(
        NeoResponse.error(400, "Value too long for column NAME (max 60)"));

    assertEquals("an over-length field must be a 400 validation failure, not a 500",
        400, error.getInt("status"));
    // The second assertion here used to read "failure must be rejected before persistence, not
    // committed" while only re-checking that 400 is in the 4xx range — a tautology standing in for
    // a claim the code could not make. What matters is that the status is one the caller can act
    // on, i.e. fix-and-retry rather than server_error; whether anything persisted is asserted
    // through 'persisted' in test 6, not inferred from a status code.
    assertEquals("an over-length value is the caller's to fix, so it must not be a 5xx",
        4, error.getInt("status") / 100);
  }

  // ---------------------------------------------------------------------------
  // 4. Invalid / malformed List-reference value — independent of the ETP-4668
  //    coercion fix: this pins /batch's robustness to the failure MODE, not that
  //    specific bug. The endpoint must reject it gracefully, never 500 or corrupt.
  // ---------------------------------------------------------------------------
  @Test
  public void invalidListReferenceValueFailsGracefully() throws Exception {
    JSONObject error = runSingleOpAndExpectFailure(
        NeoResponse.error(400, "Invalid value '999' for list reference InvoiceGrouping"));

    assertEquals("an out-of-set list value must fail as a 400, not a 500", 400, error.getInt("status"));
    assertNotNull(error.optJSONObject("detail"));
  }

  // ---------------------------------------------------------------------------
  // 5. Duplicate unique key on the SECOND op — the batch stops and reports itself as
  //    atomic with nothing persisted, because no commit happened underneath it.
  //    This test has been rewritten twice as the product changed: it first asserted
  //    all-or-nothing (true of BatchService's own lifecycle, false of the batch —
  //    IMP-23), then asserted the surviving record was named, and now asserts the
  //    rollback really is clean. What it must never do is assert the mock: see the
  //    class javadoc on why no test here can see the per-op commit either way.
  // ---------------------------------------------------------------------------
  @Test
  public void duplicateKeyRollsBackTheWholeBatchAndReportsNothingPersisted() throws Exception {
    BatchService service = BatchService.forBatchOnly();

    JSONArray ops = new JSONArray();
    ops.put(op("bp1", "businessPartner").put("body", new JSONObject().put("searchKey", "ACME")));
    ops.put(op("bp2", "businessPartner").put("body", new JSONObject().put("searchKey", "ACME")));

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoServletSupport> support = mockStatic(NeoServletSupport.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      stubEntityResolution(support, obDal);
      support.when(() -> NeoServletSupport.handleWithHooks(anyString(), any(NeoContext.class), any()))
          .thenReturn(created("bp-1"),
              NeoResponse.error(409, "duplicate key value violates unique constraint \"c_bpartner_key\""));

      JSONObject result = service.executeBatch(ops);

      assertFalse("a duplicate key anywhere in the batch must fail the whole batch",
          result.getBoolean(COMMITTED));
      assertEquals("the second op is the one that failed", 1,
          result.getJSONObject("failedAt").getInt("index"));
      assertEquals("bp2", result.getJSONObject("failedAt").getString("id"));

      // IMP-23 option B: nothing committed underneath the batch (the session never changed), so
      // the rollback undid the first op too and the caller has nothing to clean up. Saying so
      // matters as much as naming survivors did: a spurious 'persisted' would send an agent
      // hunting for records that no longer exist.
      assertTrue("no commit happened underneath, so the batch must report itself atomic",
          result.getBoolean("atomic"));
      assertEquals("a clean rollback leaves nothing to report as persisted",
          0, result.getJSONArray("persisted").length());
      assertTrue("the hint must tell the caller to retry the whole batch",
          result.getString("hint").contains("retry the whole batch"));

      // BatchService's own lifecycle, which was never the broken part: it commits once at the end
      // and not at all on failure. This says nothing about the per-op commit below the stub.
      verify(obDal, never()).commitAndClose();
      verify(obDal).rollbackAndClose();
    }
  }

  // ---------------------------------------------------------------------------
  // 5b. A commit underneath the batch — the one case option B cannot fix, because a
  //     process that commits internally (ProcessInvoiceUtil#process) is doing so by
  //     design. Detected by the Hibernate session identity changing mid-batch, which
  //     is the only signal that does not depend on a hand-maintained list of which
  //     handlers commit. Here the batch must go back to naming the survivor.
  // ---------------------------------------------------------------------------
  @Test
  public void aCommitUnderneathTheBatchIsReportedWithTheSurvivingRecord() throws Exception {
    BatchService service = BatchService.forBatchOnly();

    JSONArray ops = new JSONArray();
    ops.put(op("bp1", "businessPartner").put("body", new JSONObject().put("searchKey", "ACME")));
    ops.put(op("bp2", "businessPartner").put("body", new JSONObject().put("searchKey", "ACME")));

    OBDal obDal = mock(OBDal.class);
    Session before = mock(Session.class);
    Session afterCommit = mock(Session.class);
    // Session reads, in order: tracker construction, after op 0, after op 1. The swap on the
    // second read is what a commitAndClose() underneath op 0 looks like from up here.
    when(obDal.getSession()).thenReturn(before, afterCommit, afterCommit);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoServletSupport> support = mockStatic(NeoServletSupport.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      stubEntityResolution(support, obDal);
      support.when(() -> NeoServletSupport.handleWithHooks(anyString(), any(NeoContext.class), any()))
          .thenReturn(created("bp-1"),
              NeoResponse.error(409, "duplicate key value violates unique constraint \"c_bpartner_key\""));

      JSONObject result = service.executeBatch(ops);

      assertFalse("a commit underneath the batch makes it non-atomic, and it must admit that",
          result.getBoolean("atomic"));
      JSONArray persisted = result.getJSONArray("persisted");
      assertEquals("the op that was committed underneath must be named", 1, persisted.length());
      assertEquals("bp1", persisted.getJSONObject(0).getString("id"));
      assertEquals("bp-1", persisted.getJSONObject(0).getString("recordId"));
      assertTrue("the hint must warn that retrying the whole batch duplicates the survivor",
          result.getString("hint").contains("duplicates"));
    }
  }

  // ---------------------------------------------------------------------------
  // 6. Failure on the FIRST op — nothing ran before it, so 'persisted' must be
  //    present and empty. "Nothing landed" and "we are not saying" must not look
  //    alike to a caller, which is why the key is never omitted.
  // ---------------------------------------------------------------------------
  @Test
  public void failureOnFirstOpReportsAnEmptyPersistedArray() throws Exception {
    JSONObject result = runSingleOpBatch(
        NeoResponse.error(400, "Value too long for column NAME (max 60)"));

    assertEquals("no op ran before the failure, so nothing can have persisted",
        0, result.getJSONArray("persisted").length());
    assertTrue("nothing persisted means the batch was atomic", result.getBoolean("atomic"));
    assertTrue("the hint must say plainly that nothing was written, so the retry is unconditional",
        result.getString("hint").contains("Nothing was persisted"));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Runs a one-operation batch whose single CRUD call returns {@code subFailure},
   * asserts the batch reports a structured failure and rolled back, and returns
   * the {@code error} object for scenario-specific assertions.
   */
  private JSONObject runSingleOpAndExpectFailure(NeoResponse subFailure) throws Exception {
    JSONObject result = runSingleOpBatch(subFailure);
    JSONObject error = result.getJSONObject("error");
    assertNotNull("failure body must carry a structured error", error);
    return error;
  }

  /**
   * Same as {@link #runSingleOpAndExpectFailure} but returns the whole failure body, for the
   * assertions that are about the envelope itself ({@code persisted}, {@code atomic}, {@code hint})
   * rather than about the {@code error} inside it.
   */
  private JSONObject runSingleOpBatch(NeoResponse subFailure) throws Exception {
    BatchService service = BatchService.forBatchOnly();
    JSONArray ops = new JSONArray();
    ops.put(op("bp", "businessPartner").put("body", new JSONObject().put("name", "x")));

    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoServletSupport> support = mockStatic(NeoServletSupport.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      stubEntityResolution(support, obDal);
      support.when(() -> NeoServletSupport.handleWithHooks(anyString(), any(NeoContext.class), any()))
          .thenReturn(subFailure);

      JSONObject result = service.executeBatch(ops);

      assertFalse("sub-operation failure must fail the batch", result.getBoolean(COMMITTED));
      verify(obDal, never()).commitAndClose();
      verify(obDal).rollbackAndClose();
      assertNotNull("every failure body must carry 'persisted', empty included (IMP-23)",
          result.optJSONArray("persisted"));
      return result;
    }
  }

  /**
   * Stubs the entity-resolution seams so {@code createRecord} reaches the CRUD
   * dispatch: the spec is found, the entity is an active/included row with an
   * AD tab and a Java qualifier (so dispatch routes through
   * {@code handleWithHooks}, the mockable seam).
   */
  private void stubEntityResolution(MockedStatic<NeoServletSupport> support, OBDal obDal) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn(SPEC_ID);
    support.when(() -> NeoServletSupport.findSpec(SPEC)).thenReturn(spec);

    SFEntity entity = mock(SFEntity.class);
    // These robustness scenarios model writable Contacts entities. ETP-4254 enforces
    // ISPOST on batch creates, so the fixture must declare the method explicitly instead
    // of relying on Mockito's false default and being rejected before the behavior under test.
    when(entity.isPost()).thenReturn(true);
    when(entity.getADTab()).thenReturn(mock(Tab.class));
    when(entity.getJavaQualifier()).thenReturn(QUALIFIER);

    @SuppressWarnings("unchecked")
    OBCriteria<SFEntity> criteria = (OBCriteria<SFEntity>) mock(OBCriteria.class);
    when(obDal.createCriteria(SFEntity.class)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(entity));
  }

  private static JSONObject op(String id, String entity) throws JSONException {
    return new JSONObject().put("id", id).put("spec", SPEC).put("entity", entity);
  }

  /** A 201 response in the {@code {response:{data:[{id}]}}} envelope the batch reads ids from. */
  private static NeoResponse created(String recordId) throws JSONException {
    JSONObject row = new JSONObject().put("id", recordId);
    JSONObject data = new JSONObject().put("data", new JSONArray().put(row));
    return new NeoResponse(201, new JSONObject().put("response", data));
  }
}
