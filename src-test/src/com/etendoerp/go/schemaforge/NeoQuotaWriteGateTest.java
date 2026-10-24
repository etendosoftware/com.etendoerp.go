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
import org.mockito.MockedStatic;
import org.openbravo.dal.service.OBDal;
import org.openbravo.service.json.JsonConstants;

import com.etendoerp.go.payment.TestLogCapture;
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
 * request). A failure of the check itself never refuses a create: it is logged under
 * {@code [QUOTA]} and the create stands.
 *
 * @covers com.etendoerp.go.schemaforge.NeoQuotaWriteGate
 * @covers com.etendoerp.go.schemaforge.NeoCrudHandler
 */
@SuppressWarnings("java:S2187")
class NeoQuotaWriteGateTest {

  private static final String ENTITY = "Invoice";

  private QuotaWriteGuard guard;
  private OBDal dal;
  private MockedStatic<OBDal> obDal;
  private TestLogCapture logs;

  @BeforeEach
  void setUp() {
    guard = mock(QuotaWriteGuard.class);
    dal = mock(OBDal.class);
    obDal = mockStatic(OBDal.class);
    obDal.when(OBDal::getInstance).thenReturn(dal);
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
    when(guard.appliesTo(ENTITY)).thenReturn(true);
    return NeoQuotaWriteGate.begin(ENTITY, guard, batchOwnsTransaction);
  }

  @Nested
  @DisplayName("an entity no block quota counts")
  class Unchecked {

    @Test
    void runsExactlyAsBeforeNoCheckNoCommitNoRollback() throws Exception {
      when(guard.appliesTo(ENTITY)).thenReturn(false);

      try (NeoQuotaWriteGate.Create create = NeoQuotaWriteGate.begin(ENTITY, guard, false)) {
        assertFalse(create.isChecked());
        assertFalse(create.ownsTransaction());
        assertNull(create.jsonService(null), "the caller's own service is kept");
        assertNull(create.finish(created("ROW-1")));
      }

      verify(guard, never()).refusalFor(anyString(), any());
      obDal.verifyNoInteractions();
    }

    @Test
    void aFailingApplicabilityCheckFailsOpenAndIsLogged() throws Exception {
      when(guard.appliesTo(ENTITY)).thenThrow(new IllegalStateException("index broken"));

      try (NeoQuotaWriteGate.Create create = NeoQuotaWriteGate.begin(ENTITY, guard, false)) {
        assertFalse(create.isChecked());
        assertNull(create.finish(created("ROW-1")));
      }

      obDal.verifyNoInteractions();
      assertTrue(logs.messagesAt(Level.ERROR).get(0).startsWith("[QUOTA] Could not decide"),
          String.valueOf(logs.messagesAt(Level.ERROR)));
    }
  }

  @Nested
  @DisplayName("a checked create outside a batch")
  class OwnTransaction {

    @Test
    void aMatchingRowOverQuotaIsRolledBackAndRefusedWithTheContractBody() throws Exception {
      QuotaEvaluation denial = denial();
      when(guard.refusalFor(ENTITY, List.of("ROW-1"))).thenReturn(Optional.of(denial));

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
      when(guard.refusalFor(ENTITY, List.of("ROW-1"))).thenReturn(Optional.empty());

      try (NeoQuotaWriteGate.Create create = begin(false)) {
        assertNull(create.finish(created("ROW-1")));
      }

      verify(dal, times(1)).commitAndClose();
      verify(dal, never()).rollbackAndClose();
    }

    @Test
    void aFailingQuotaCheckFailsOpenTheCreateStandsAndIsLogged() throws Exception {
      when(guard.refusalFor(eq(ENTITY), any())).thenThrow(new IllegalStateException("db down"));

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

    @Test
    void aFailedAddIsRolledBackAndLeftToTheCallerToReport() throws Exception {
      NeoResponse response;
      try (NeoQuotaWriteGate.Create create = begin(false)) {
        response = create.finish(failedAdd());
      }

      assertNull(response);
      verify(guard, never()).refusalFor(anyString(), any());
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

  @Nested
  @DisplayName("a checked create inside a batch")
  class BatchOwnsTransaction {

    @Test
    void aRefusalIsReturnedButTheTransactionIsLeftToTheBatch() throws Exception {
      when(guard.refusalFor(ENTITY, List.of("ROW-1"))).thenReturn(Optional.of(denial()));

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
      when(guard.refusalFor(ENTITY, List.of("ROW-1"))).thenReturn(Optional.empty());

      try (NeoQuotaWriteGate.Create create = begin(true)) {
        assertNull(create.finish(created("ROW-1")));
      }

      verify(guard).refusalFor(ENTITY, List.of("ROW-1"));
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

  @Nested
  @DisplayName("the production entry point")
  class ProductionBegin {

    @Test
    void insideABatchTheDefaultGateLeavesTheTransactionToTheBatch() {
      when(guard.appliesTo(ENTITY)).thenReturn(true);
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

    /**
     * The REST single create runs its insert inside the gate too. {@code NeoCrudHandler}'s POST
     * path needs a live DAL and an {@code AD_Tab}, so the wiring is pinned on its source.
     */
    @Test
    void theRestCreateRunsItsInsertInsideTheGate() throws Exception {
      String source = readSource("com/etendoerp/go/schemaforge/NeoCrudHandler.java")
          .replaceAll("\\s+", " ");

      int begin = source.indexOf("try (NeoQuotaWriteGate.Create quota = "
          + "NeoQuotaWriteGate.begin(dalEntityName))");
      int insert = source.indexOf("quota.jsonService(jsonService), params)", begin);
      int finish = source.indexOf("NeoResponse quotaRefusal = quota.finish(result);", insert);
      int refusal = source.indexOf("return quotaRefusal;", finish);

      assertTrue(begin >= 0, "NeoCrudHandler no longer opens the quota gate around its create");
      assertTrue(insert > begin, "the create no longer runs through the gate's service");
      assertTrue(finish > insert, "the created row is no longer checked after the insert");
      assertTrue(refusal > finish, "a quota refusal is no longer returned to the caller");
    }

    private String readSource(String relative) throws java.io.IOException {
      java.nio.file.Path fromRoot = java.nio.file.Paths.get("modules/com.etendoerp.go/src",
          relative);
      java.nio.file.Path file = java.nio.file.Files.exists(fromRoot) ? fromRoot
          : java.nio.file.Paths.get("src", relative);
      return new String(java.nio.file.Files.readAllBytes(file),
          java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @Nested
  @DisplayName("reading the ids an add created")
  class CreatedIds {

    @Test
    void theIdsOfEverySuccessfulRowAreRead() throws Exception {
      assertEquals(List.of("ROW-1", "ROW-2"), NeoQuotaWriteGate.createdIds(created("ROW-1",
          "ROW-2")));
    }

    @Test
    void rowsWithoutAnIdAreSkipped() throws Exception {
      String result = new JSONObject().put(JsonConstants.RESPONSE_RESPONSE, new JSONObject()
          .put(JsonConstants.RESPONSE_STATUS, JsonConstants.RPCREQUEST_STATUS_SUCCESS)
          .put(JsonConstants.RESPONSE_DATA, new JSONArray().put(new JSONObject())
              .put("not an object").put(new JSONObject().put(JsonConstants.ID, "ROW-3"))))
          .toString();

      assertEquals(List.of("ROW-3"), NeoQuotaWriteGate.createdIds(result));
    }

    @Test
    void aSuccessWithoutDataIsAnEmptyList() throws Exception {
      String result = new JSONObject().put(JsonConstants.RESPONSE_RESPONSE, new JSONObject()
          .put(JsonConstants.RESPONSE_STATUS, JsonConstants.RPCREQUEST_STATUS_SUCCESS))
          .toString();

      assertEquals(Collections.emptyList(), NeoQuotaWriteGate.createdIds(result));
    }

    @Test
    void aFailedBlankOrUnreadableResultIsNull() throws Exception {
      assertNull(NeoQuotaWriteGate.createdIds(failedAdd()));
      assertNull(NeoQuotaWriteGate.createdIds(" "));
      assertNull(NeoQuotaWriteGate.createdIds(null));
      assertNull(NeoQuotaWriteGate.createdIds("{not json"));
      assertNull(NeoQuotaWriteGate.createdIds("{}"));
    }
  }

  @Test
  void aSuccessfulAddThatCreatedNoRowsIsCommitted() throws Exception {
    when(guard.refusalFor(ENTITY, Collections.emptyList())).thenReturn(Optional.empty());

    try (NeoQuotaWriteGate.Create create = begin(false)) {
      assertNull(create.finish(created()));
    }

    verify(dal).commitAndClose();
    assertTrue(logs.messagesAt(Level.ERROR).isEmpty(), String.valueOf(logs.messagesAt(Level.ERROR)));
  }
}
