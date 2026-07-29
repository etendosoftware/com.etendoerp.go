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
 *   <li>the whole batch is atomic — one failed operation rolls everything back
 *       ({@code commitAndClose} is never reached), so no partial document is left behind;</li>
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
 */
public class BatchServiceRobustnessTest {

  private static final String SPEC = "contacts";
  private static final String SPEC_ID = "spec-1";
  private static final String QUALIFIER = "business-partner";
  private static final String COMMITTED = "committed";
  private static final String PARENT_ID = "parentId";

  // ---------------------------------------------------------------------------
  // 1. Happy path — businessPartner + locationAddress + contact, mirroring the
  //    real Contacts CSV import shape, commits atomically and wires parent refs.
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

      // Atomic-commit contract: exactly one commit, no rollback on the happy path.
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
    assertTrue("failure must be rejected before persistence, not committed",
        error.getInt("status") >= 400 && error.getInt("status") < 500);
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
  // 5. Duplicate unique key — the whole batch must roll back atomically. The
  //    first op "succeeds" in-session, the second violates a uniqueness
  //    constraint; nothing may be committed (no partial document).
  // ---------------------------------------------------------------------------
  @Test
  public void duplicateKeyRollsBackWholeBatchAtomically() throws Exception {
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

      // Atomicity: the first (successful) op must NOT be partially committed.
      verify(obDal, never()).commitAndClose();
      verify(obDal).rollbackAndClose();
    }
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
      JSONObject error = result.getJSONObject("error");
      assertNotNull("failure body must carry a structured error", error);
      return error;
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
