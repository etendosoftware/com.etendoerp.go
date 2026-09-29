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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Unit tests for {@link AgentActionSupport} (ETP-5469): the plumbing shared by the
 * {@code neo_action} dispatchers of {@code bank-reconciliation} and {@code bank-statements} — the
 * role gate, the SPA-shaped derived context and the flush-to-clean with its rollback (the
 * parameter copy is exercised through {@link BankStatementAgentActionsTest}'s write routing). {@link ReconciliationAgentActionsTest} keeps covering the same helpers through the
 * reconciliation dispatcher; this class pins them in isolation.
 */
@SuppressWarnings("java:S2187")
@DisplayName("AgentActionSupport (ETP-5469)")
class AgentActionSupportTest {

  private static final String SPEC = "bank-statements";
  private static final String ACTION = "createStatement";
  private static final String SUBJECT = "bank statement";
  private static final String POST = "POST";
  private static final String GET = "GET";
  private static final String ERROR = "error";
  private static final String MESSAGE = "message";

  private SFSpec spec;
  private SFEntity sfEntity;

  @BeforeEach
  void setUp() {
    spec = mock(SFSpec.class);
    sfEntity = mock(SFEntity.class);
    when(sfEntity.getETGOSFSpec()).thenReturn(spec);
  }

  @AfterEach
  void tearDown() {
    Mockito.framework().clearInlineMocks();
  }

  // ── role gate ──────────────────────────────────────────────────────────

  @Nested
  @DisplayName("hasAccess")
  class HasAccess {

    private MockedStatic<NeoAccessHelper> access;

    @BeforeEach
    void mockAccess() {
      access = mockStatic(NeoAccessHelper.class);
    }

    @AfterEach
    void closeAccess() {
      access.close();
    }

    private NeoContext contextWith(SFEntity entity) {
      return NeoContext.builder().specName(SPEC).sfEntity(entity).build();
    }

    @Test
    @DisplayName("a mutating action is checked with POST, a read with GET")
    void methodFollowsMutatingFlag() {
      access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, POST)).thenReturn(false);
      access.when(() -> NeoAccessHelper.hasReportSpecAccess(spec, GET)).thenReturn(true);

      assertFalse(AgentActionSupport.hasAccess(contextWith(sfEntity), true));
      assertTrue(AgentActionSupport.hasAccess(contextWith(sfEntity), false));
      access.verify(() -> NeoAccessHelper.hasReportSpecAccess(spec, POST));
      access.verify(() -> NeoAccessHelper.hasReportSpecAccess(spec, GET));
    }

    @Test
    @DisplayName("fails closed when the context carries no entity")
    void noEntityFailsClosed() {
      assertFalse(AgentActionSupport.hasAccess(contextWith(null), false));
      access.verifyNoInteractions();
    }

    @Test
    @DisplayName("fails closed when the entity has no spec")
    void noSpecFailsClosed() {
      SFEntity orphan = mock(SFEntity.class);
      when(orphan.getETGOSFSpec()).thenReturn(null);
      assertFalse(AgentActionSupport.hasAccess(contextWith(orphan), true));
      access.verify(() -> NeoAccessHelper.hasReportSpecAccess(any(), anyString()), never());
    }
  }

  // ── derived context ────────────────────────────────────────────────────

  @Nested
  @DisplayName("derive")
  class Derive {

    @Test
    @DisplayName("keeps spec, entity, record, tab, OBContext and origin; drops the endpoint type")
    void copiesIdentityAndDropsEndpointType() throws Exception {
      Tab tab = mock(Tab.class);
      OBContext obContext = mock(OBContext.class);
      NeoContext source = NeoContext.builder()
          .specName(SPEC).entityName("statements").httpMethod(POST).recordId("ACC-1")
          .requestBody(new JSONObject().put("ignored", true))
          .queryParams(Map.of("action", "create"))
          .adTab(tab).sfEntity(sfEntity).obContext(obContext).mcpOrigin(true)
          .endpointType(NeoEndpointType.ACTION).fieldName(ACTION)
          .build();
      JSONObject body = new JSONObject().put("name", "S1");
      Map<String, String> query = Map.of("statementId", "ST-1");

      NeoContext derived = AgentActionSupport.derive(source, GET, body, query);

      assertEquals(SPEC, derived.getSpecName());
      assertEquals("statements", derived.getEntityName());
      assertEquals(GET, derived.getHttpMethod());
      assertEquals("ACC-1", derived.getRecordId());
      assertSame(body, derived.getRequestBody());
      assertEquals(query, derived.getQueryParams());
      assertSame(tab, derived.getAdTab());
      assertSame(sfEntity, derived.getSfEntity());
      assertSame(obContext, derived.getObContext());
      assertTrue(derived.isMcpOrigin());
      assertNull(derived.getEndpointType(), "looks like the SPA request: no endpoint type");
      assertNull(derived.getFieldName(), "the action name does not leak into the SPA context");
    }
  }

  // ── flush-to-clean ─────────────────────────────────────────────────────

  @Nested
  @DisplayName("flushWhileContextIsSet")
  class Flush {

    private MockedStatic<OBDal> obDal;
    private OBDal dal;
    private Session session;
    private final AtomicInteger rollbacks = new AtomicInteger();
    private final Runnable rollback = rollbacks::incrementAndGet;

    @BeforeEach
    void mockDal() {
      dal = mock(OBDal.class);
      session = mock(Session.class);
      when(dal.getSession()).thenReturn(session);
      when(session.isDirty()).thenReturn(false);
      obDal = mockStatic(OBDal.class);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      rollbacks.set(0);
    }

    @AfterEach
    void closeDal() {
      obDal.close();
    }

    private NeoResponse flush(NeoResponse written) {
      return AgentActionSupport.flushWhileContextIsSet(ACTION, SUBJECT, written, rollback);
    }

    @Test
    @DisplayName("a null response is returned untouched without touching OBDal")
    void nullPassesThrough() {
      assertNull(flush(null));
      obDal.verify(OBDal::getInstance, never());
      assertEquals(0, rollbacks.get());
    }

    @ParameterizedTest(name = "status {0}")
    @ValueSource(ints = { 400, 403, 422, 500 })
    @DisplayName("a response >= 400 is returned untouched: no flush, no rollback")
    void errorPassesThrough(int status) {
      when(session.isDirty()).thenReturn(true);
      NeoResponse error = NeoResponse.error(status, "refused");
      assertSame(error, flush(error));
      obDal.verify(OBDal::getInstance, never());
      assertEquals(0, rollbacks.get());
    }

    @ParameterizedTest(name = "status {0}")
    @ValueSource(ints = { 200, 201, 399 })
    @DisplayName("a response < 400 is flushed until the session is clean")
    void successIsFlushedUntilClean(int status) {
      when(session.isDirty()).thenReturn(true, true, true, false);
      NeoResponse ok = NeoResponse.error(status, "ok-ish");
      assertSame(ok, flush(ok));
      verify(dal, times(3)).flush();
      assertEquals(0, rollbacks.get());
    }

    @Test
    @DisplayName("stops at the 100-flush cap when the session never gets clean")
    void stopsAtCap() throws Exception {
      when(session.isDirty()).thenReturn(true);
      NeoResponse ok = NeoResponse.ok(new JSONObject());
      assertSame(ok, flush(ok));
      verify(dal, times(100)).flush();
      assertEquals(0, rollbacks.get());
    }

    @Test
    @DisplayName("a flush failure runs the rollback once and answers a JSON 500 naming the subject")
    void flushFailureRollsBack() throws Exception {
      when(session.isDirty()).thenReturn(true);
      Mockito.doThrow(new IllegalStateException("constraint violated")).when(dal).flush();

      NeoResponse response = flush(NeoResponse.ok(new JSONObject().put("id", "ST-1")));

      assertEquals(500, response.getHttpStatus());
      assertEquals("The bank statement changes could not be saved and were rolled back: "
          + "constraint violated", response.getBody().getJSONObject(ERROR).getString(MESSAGE));
      assertEquals(1, rollbacks.get());
      verify(dal, times(1)).flush();
    }

    @Test
    @DisplayName("a flush failure without a message names the exception class")
    void flushFailureWithoutMessage() throws Exception {
      when(session.isDirty()).thenReturn(true);
      Mockito.doThrow(new NullPointerException()).when(dal).flush();

      NeoResponse response = flush(NeoResponse.ok(new JSONObject()));

      assertEquals(500, response.getHttpStatus());
      assertTrue(response.getBody().getJSONObject(ERROR).getString(MESSAGE)
          .endsWith("rolled back: NullPointerException"));
      assertEquals(1, rollbacks.get());
    }
  }
}
