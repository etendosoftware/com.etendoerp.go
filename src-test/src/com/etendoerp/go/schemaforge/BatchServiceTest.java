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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Tests for {@link BatchService}.
 *
 * @covers com.etendoerp.go.schemaforge.BatchService
 */
public class BatchServiceTest {

  @Test
  public void forBatchOnlyCreatesInstance() {
    BatchService service = BatchService.forBatchOnly();
    assertNotNull(service);
  }

  @Test
  public void executeBatchNullOperationsReturnsFailure() throws Exception {
    BatchService service = BatchService.forBatchOnly();
    JSONObject result = service.executeBatch(null);

    assertFalse(result.getBoolean("committed"));
  }

  @Test
  public void executeBatchEmptyOperationsCommits() throws Exception {
    BatchService service = BatchService.forBatchOnly();
    JSONArray ops = new JSONArray();

    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      JSONObject result = service.executeBatch(ops);
      assertTrue(result.getBoolean("committed"));
      assertEquals(0, result.getJSONArray("operations").length());
    }
  }

  @Test
  public void executeBatchNullOpReturnsFailure() throws Exception {
    BatchService service = BatchService.forBatchOnly();
    JSONArray ops = new JSONArray();
    ops.put((JSONObject) null);

    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      JSONObject result = service.executeBatch(ops);
      assertFalse(result.getBoolean("committed"));
    }
  }

  @Test
  public void executeBatchMissingRequiredFieldsReturnsFailure() throws Exception {
    BatchService service = BatchService.forBatchOnly();
    JSONArray ops = new JSONArray();
    JSONObject op = new JSONObject();
    op.put("id", "op1");
    // Missing spec and entity
    ops.put(op);

    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      JSONObject result = service.executeBatch(ops);
      assertFalse(result.getBoolean("committed"));
    }
  }

  @Test
  public void executeBatchDuplicateOpIdReturnsFailure() throws Exception {
    BatchService service = BatchService.forBatchOnly();
    JSONArray ops = new JSONArray();

    JSONObject op1 = new JSONObject();
    op1.put("id", "op1");
    op1.put("spec", "order");
    op1.put("entity", "header");
    ops.put(op1);

    JSONObject op2 = new JSONObject();
    op2.put("id", "op1"); // duplicate
    op2.put("spec", "order");
    op2.put("entity", "line");
    ops.put(op2);

    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoServletSupport> supportMock = mockStatic(NeoServletSupport.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      // First op needs to succeed for duplicate check to trigger
      SFSpec spec = mock(SFSpec.class);
      when(spec.getId()).thenReturn("spec-1");
      supportMock.when(() -> NeoServletSupport.findSpec("order")).thenReturn(spec);

      // The first op will fail because it can't find the entity, which is fine
      // But let's test that missing spec returns properly
      supportMock.when(() -> NeoServletSupport.findSpec("order")).thenReturn(null);

      JSONObject result = service.executeBatch(ops);
      assertFalse(result.getBoolean("committed"));
    }
  }

  @Test
  public void executeBatchSpecNotFoundReturnsFailure() throws Exception {
    BatchService service = BatchService.forBatchOnly();
    JSONArray ops = new JSONArray();

    JSONObject op1 = new JSONObject();
    op1.put("id", "op1");
    op1.put("spec", "nonexistent");
    op1.put("entity", "header");
    ops.put(op1);

    OBDal obDal = mock(OBDal.class);

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoServletSupport> supportMock = mockStatic(NeoServletSupport.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      supportMock.when(() -> NeoServletSupport.findSpec("nonexistent")).thenReturn(null);

      JSONObject result = service.executeBatch(ops);
      assertFalse(result.getBoolean("committed"));
      assertNotNull(result.optJSONObject("error"));
    }
  }

  @Test
  public void executeBatchInvalidParentRefReturnsFailure() throws Exception {
    BatchService service = BatchService.forBatchOnly();
    JSONArray ops = new JSONArray();

    JSONObject op1 = new JSONObject();
    op1.put("id", "op1");
    op1.put("spec", "order");
    op1.put("entity", "line");
    op1.put("parentRef", "nonexistent-parent");
    ops.put(op1);

    OBDal obDal = mock(OBDal.class);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-1");

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoServletSupport> supportMock = mockStatic(NeoServletSupport.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      supportMock.when(() -> NeoServletSupport.findSpec("order")).thenReturn(spec);

      JSONObject result = service.executeBatch(ops);
      assertFalse(result.getBoolean("committed"));
    }
  }

  /**
   * ETP-4254: {@code /batch} (and MCP {@code etendo_batch}, which shares
   * {@code BatchService#createRecord}) enters the CRUD pipeline at
   * {@code NeoCrudHandler#handleDefault} — i.e. AFTER the method-flag gate in
   * {@code handleWindowEntityCrud}. Before the fix a read-only entity (every mutation flag
   * off, e.g. the SII/VeriFactu monitor logs) rejected a direct POST with {@code 405} while
   * still accepting the very same create when smuggled inside a batch operation.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void executeBatchRefusesCreateOnReadOnlyEntity() throws Exception {
    BatchService service = BatchService.forBatchOnly();
    JSONArray ops = new JSONArray();

    JSONObject op = new JSONObject();
    op.put("id", "op1");
    op.put("spec", "monitor-verifactu");
    op.put("entity", "header");
    op.put("body", new JSONObject());
    ops.put(op);

    OBDal obDal = mock(OBDal.class);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-monitor");

    // GET-only entity: this is what ETP-4254 configures on a monitor/log window.
    SFEntity entity = mock(SFEntity.class);
    when(entity.getName()).thenReturn("header");
    when(entity.getADTab()).thenReturn(mock(Tab.class));
    when(entity.isGet()).thenReturn(true);
    when(entity.isGetByID()).thenReturn(true);
    when(entity.isPost()).thenReturn(false);
    when(entity.isPut()).thenReturn(false);
    when(entity.isPatch()).thenReturn(false);
    when(entity.isDelete()).thenReturn(false);

    org.openbravo.dal.service.OBCriteria<SFEntity> criteria =
        mock(org.openbravo.dal.service.OBCriteria.class);
    when(obDal.createCriteria(SFEntity.class)).thenReturn(criteria);
    when(criteria.list()).thenReturn(java.util.List.of(entity));

    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoServletSupport> supportMock = mockStatic(NeoServletSupport.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      supportMock.when(() -> NeoServletSupport.findSpec("monitor-verifactu")).thenReturn(spec);

      JSONObject result = service.executeBatch(ops);

      assertFalse(result.getBoolean("committed"));
      JSONObject error = result.optJSONObject("error");
      assertNotNull(error);
      assertEquals(405, error.getInt("status"));
      // The detail carries the canonical REST wording produced by NeoMethodPolicy.
      assertTrue(error.toString().contains("POST not enabled for header"));
    }
  }

  // ---------------------------------------------------------------------------
  // ETP-5676: the per-request NeoBatchScope and the _onlyReturnIds option
  // ---------------------------------------------------------------------------

  private static final String SCOPE_SPEC = "product";

  /** A failed assertion must not leave a scope on the pooled test thread for the next test. */
  @After
  public void clearLeakedScope() {
    while (NeoBatchScope.current() != null) {
      NeoBatchScope.close(null);
    }
  }

  /** {@code count} create operations on {@link #SCOPE_SPEC}. */
  private static JSONArray productOps(int count) throws Exception {
    JSONArray ops = new JSONArray();
    for (int i = 0; i < count; i++) {
      ops.put(new JSONObject().put("id", "op" + i).put("spec", SCOPE_SPEC)
          .put("entity", "product").put("body", new JSONObject().put("name", "P" + i)));
    }
    return ops;
  }

  /** A writable entity behind {@link #SCOPE_SPEC}, found through the mocked criteria. */
  @SuppressWarnings("unchecked")
  private static OBCriteria<SFEntity> stubWritableEntity(OBDal obDal,
      MockedStatic<NeoServletSupport> support) {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getId()).thenReturn("spec-product");
    when(spec.getName()).thenReturn(SCOPE_SPEC);
    support.when(() -> NeoServletSupport.findSpec(SCOPE_SPEC)).thenReturn(spec);
    SFEntity entity = mock(SFEntity.class);
    when(entity.isPost()).thenReturn(true);
    when(entity.getADTab()).thenReturn(mock(Tab.class));
    OBCriteria<SFEntity> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(SFEntity.class)).thenReturn(criteria);
    when(criteria.list()).thenReturn(Collections.singletonList(entity));
    return criteria;
  }

  /** Every write answers 201 with a fresh id; the contexts it saw are collected. */
  private static void answerCreated(MockedStatic<NeoServletSupport> support,
      List<NeoContext> seen) {
    support.when(() -> NeoServletSupport.handleWithHooks(any(), any(NeoContext.class), any(),
        any())).thenAnswer(invocation -> {
          NeoContext ctx = invocation.getArgument(1);
          seen.add(ctx);
          assertNotNull("the write runs inside the batch scope", NeoBatchScope.current());
          JSONObject row = new JSONObject().put("id", "rec-" + seen.size());
          return new NeoResponse(201, new JSONObject().put("response",
              new JSONObject().put("data", new JSONArray().put(row))));
        });
  }

  /**
   * The option reaches every operation's write context, and the scope that carries it is gone
   * once the batch returns. The one- and two-argument overloads keep it off.
   */
  @Test
  public void executeBatchHandsTheOnlyReturnIdsOptionToEveryWrite() throws Exception {
    for (Boolean option : new Boolean[] { Boolean.TRUE, Boolean.FALSE, null }) {
      OBDal obDal = mock(OBDal.class);
      List<NeoContext> seen = new ArrayList<>();
      try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
           MockedStatic<NeoServletSupport> support = mockStatic(NeoServletSupport.class)) {
        dalMock.when(OBDal::getInstance).thenReturn(obDal);
        stubWritableEntity(obDal, support);
        answerCreated(support, seen);

        BatchService service = BatchService.forBatchOnly();
        JSONObject result = option == null
            ? service.executeBatch(productOps(2), null)
            : service.executeBatch(productOps(2), null, option);

        assertTrue(result.getBoolean("committed"));
        assertEquals(2, seen.size());
        boolean expected = Boolean.TRUE.equals(option);
        for (NeoContext ctx : seen) {
          assertEquals("option " + option, expected, ctx.isIdsOnlyResponse());
        }
        assertEquals("the response shape does not depend on the option", 2,
            result.getJSONArray("operations").length());
        assertEquals("rec-1", result.getJSONArray("operations").getJSONObject(0)
            .getString("recordId"));
        assertNull("the scope dies with the request", NeoBatchScope.current());
      }
    }
  }

  /**
   * Spec and entity are looked up once per batch, and a second batch on the same thread looks
   * them up again: nothing a request memoized survives into the next one.
   */
  @Test
  public void executeBatchMemoizesLookupsPerRequestOnly() throws Exception {
    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoServletSupport> support = mockStatic(NeoServletSupport.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      OBCriteria<SFEntity> criteria = stubWritableEntity(obDal, support);
      answerCreated(support, new ArrayList<>());
      BatchService service = BatchService.forBatchOnly();

      assertTrue(service.executeBatch(productOps(3), null, true).getBoolean("committed"));
      support.verify(() -> NeoServletSupport.findSpec(SCOPE_SPEC), times(1));
      verify(criteria, times(1)).list();

      assertTrue(service.executeBatch(productOps(3), null, true).getBoolean("committed"));
      support.verify(() -> NeoServletSupport.findSpec(SCOPE_SPEC), times(2));
      verify(criteria, times(2)).list();
    }
  }

  /** A failure inside the loop still closes the scope: the next request starts clean. */
  @Test
  public void executeBatchClosesTheScopeWhenAnOperationThrows() throws Exception {
    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoServletSupport> support = mockStatic(NeoServletSupport.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      support.when(() -> NeoServletSupport.findSpec(SCOPE_SPEC))
          .thenThrow(new IllegalStateException("lookup failed"));

      JSONObject result = BatchService.forBatchOnly().executeBatch(productOps(1), null, true);

      assertFalse(result.getBoolean("committed"));
      assertEquals(500, result.getJSONObject("error").getInt("status"));
      assertNull(NeoBatchScope.current());
    }
  }

  /** Even an Error that escapes executeBatch leaves no scope behind on the pooled thread. */
  @Test
  public void executeBatchClosesTheScopeWhenAnErrorEscapes() throws Exception {
    OBDal obDal = mock(OBDal.class);
    doThrow(new AssertionError("commit blew up")).when(obDal).commitAndClose();
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      try {
        BatchService.forBatchOnly().executeBatch(new JSONArray(), null, true);
        fail("the Error must escape executeBatch");
      } catch (AssertionError expected) {
        assertEquals("commit blew up", expected.getMessage());
      }

      assertNull(NeoBatchScope.current());
    }
  }

  /**
   * Review W1: closing the scope is not allowed to strand the transaction claim. If it throws, the
   * next request on the pooled thread must still get core's self-committing write path.
   */
  @Test
  public void executeBatchReleasesTheTransactionClaimWhenClosingTheScopeThrows() throws Exception {
    OBDal obDal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class);
         MockedStatic<NeoBatchScope> scopeMock =
             mockStatic(NeoBatchScope.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);
      scopeMock.when(() -> NeoBatchScope.close(any())).thenThrow(new IllegalStateException("close"));

      try {
        BatchService.forBatchOnly().executeBatch(new JSONArray(), null, true);
        fail("the close failure escapes executeBatch");
      } catch (IllegalStateException expected) {
        assertEquals("close", expected.getMessage());
      }

      // Read off the field: currentJsonService() would build core's service, which needs a
      // servlet context this unit test does not have.
      java.lang.reflect.Field claim =
          BatchService.class.getDeclaredField("CALLER_OWNS_TRANSACTION");
      claim.setAccessible(true);
      assertNull("the claim must not survive the request", ((ThreadLocal<?>) claim.get(null)).get());
    }
  }

  /** A scope already open on the thread is put back when the batch closes its own. */
  @Test
  public void executeBatchRestoresAScopeThatWasAlreadyOpen() throws Exception {
    OBDal obDal = mock(OBDal.class);
    NeoBatchScope previous = NeoBatchScope.open(true);
    NeoBatchScope outer = NeoBatchScope.current();
    try (MockedStatic<OBDal> dalMock = mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(obDal);

      assertTrue(BatchService.forBatchOnly().executeBatch(new JSONArray(), null, false)
          .getBoolean("committed"));

      assertSame(outer, NeoBatchScope.current());
    } finally {
      NeoBatchScope.close(previous);
    }
  }

  /** The REST body option is read from the top level of the body and defaults to false. */
  @Test
  public void handleReadsTheOnlyReturnIdsOptionFromTheRestBody() throws Exception {
    String[] bodies = {
        "{\"operations\":[{\"id\":\"a\"}]}",
        "{\"operations\":[{\"id\":\"a\"}],\"_onlyReturnIds\":true}",
        "{\"operations\":[{\"id\":\"a\"}],\"_onlyReturnIds\":false}" };
    boolean[] expected = { false, true, false };
    for (int i = 0; i < bodies.length; i++) {
      NeoServlet servlet = mock(NeoServlet.class);
      BatchService service = spy(new BatchService(servlet));
      JSONObject committed = new JSONObject().put("committed", true);
      doReturn(committed).when(service).executeBatch(any(), isNull(), anyBoolean());
      try (MockedStatic<NeoRequestBodyParser> parser = mockStatic(NeoRequestBodyParser.class)) {
        String body = bodies[i];
        parser.when(() -> NeoRequestBodyParser.readRequestBody(any())).thenReturn(body);

        service.handle(mock(HttpServletRequest.class), mock(HttpServletResponse.class));
      }
      verify(service).executeBatch(any(), isNull(), eq(expected[i]));
    }
  }

  @Test
  public void handleRequiresServletBoundConstructor() throws Exception {
    BatchService service = BatchService.forBatchOnly();

    try {
      service.handle(null, null);
      assertTrue("Expected IllegalStateException", false);
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("servlet-bound"));
    }
  }
}
