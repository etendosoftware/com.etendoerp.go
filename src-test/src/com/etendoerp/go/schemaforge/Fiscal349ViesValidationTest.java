/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.dal.service.OBDal;
import org.openbravo.module.bptaxidkey.ViesService;

/**
 * Unit tests for the {@code POST /neo/fiscal349/validate-vies} verb of
 * {@link Fiscal349BoxesHandler}.
 *
 * <p>{@link ViesService} is ALWAYS mocked — the suite must never reach
 * {@code ec.europa.eu}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Fiscal349ViesValidationTest {

  private static final String SELECT_SQL = "SELECT taxid";
  private static final String UPDATE_SQL = "UPDATE c_bpartner";

  private NeoServlet servlet;
  private Fiscal349BoxesHandler handler;

  @Mock
  private OBDal obDal;

  private MockedStatic<OBDal> obDalMock;

  private Connection connMock;
  private PreparedStatement selectPs;
  private PreparedStatement updatePs;
  private ResultSet selectRs;

  @BeforeEach
  void setUp() throws Exception {
    servlet = mock(NeoServlet.class);
    // A SPY, so ViesService can be stubbed out through the handler's checkVat seam. mockStatic
    // is thread-local and the VIES calls run on a worker pool, so a static mock would leak real
    // network traffic from those threads. Default: every partner answers "pending".
    handler = spy(new Fiscal349BoxesHandler(servlet));
    doReturn(ViesService.STATUS_PENDING).when(handler).checkVat(anyString());

    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);

    connMock = mock(Connection.class);
    selectPs = mock(PreparedStatement.class);
    updatePs = mock(PreparedStatement.class);
    selectRs = mock(ResultSet.class);

    when(selectPs.executeQuery()).thenReturn(selectRs);
    // Default: every write-back affects its row. persistViesStatuses now reports only what it
    // actually wrote, so the happy path has to say the UPDATE landed.
    when(updatePs.executeUpdate()).thenReturn(1);
    when(connMock.prepareStatement(startsWith(SELECT_SQL))).thenReturn(selectPs);
    when(connMock.prepareStatement(startsWith(UPDATE_SQL))).thenReturn(updatePs);
    when(obDal.getConnection()).thenReturn(connMock);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  // ── helpers ───────────────────────────────────────────────────────

  /**
   * Stubs the candidate SELECT so that, for the {@code n} ids queried in order, row {@code i}
   * answers with {@code taxIds[i]} / {@code keys[i]}. A null taxId means "row not found".
   */
  private void stubCandidateRows(String[] taxIds, String[] keys) throws Exception {
    Boolean[] found = new Boolean[taxIds.length];
    Arrays.fill(found, Boolean.TRUE);
    when(selectRs.next()).thenReturn(found[0], Arrays.copyOfRange(found, 1, found.length));
    when(selectRs.getString("taxid")).thenReturn(taxIds[0],
        Arrays.copyOfRange(taxIds, 1, taxIds.length));
    when(selectRs.getString("em_obtik_tax_id_key")).thenReturn(keys[0],
        Arrays.copyOfRange(keys, 1, keys.length));
  }

  private static JSONArray operators(String... viesAndBpIdPairs) throws Exception {
    JSONArray arr = new JSONArray();
    for (int i = 0; i < viesAndBpIdPairs.length; i += 2) {
      JSONObject op = new JSONObject();
      op.put("bpId", viesAndBpIdPairs[i]);
      op.put("vies", viesAndBpIdPairs[i + 1]);
      arr.put(op);
    }
    return arr;
  }

  // ── routing ───────────────────────────────────────────────────────

  /** The verb is mutating, so a GET must be rejected rather than silently writing. */
  @Test
  void testGetValidateViesReturns405() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);

    handler.handle("validate-vies", "GET", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_METHOD_NOT_ALLOWED), anyString());
  }

  /** POST is the accepted method; missing year/period still fails validation with a 400. */
  @Test
  void testPostValidateViesIsKnownAndRequiresYearPeriod() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    handler.handle("validate-vies", "POST", req, resp);

    verify(servlet).sendError(eq(resp), eq(HttpServletResponse.SC_BAD_REQUEST), anyString());
    verify(servlet, never()).sendError(eq(resp), eq(HttpServletResponse.SC_NOT_FOUND), anyString());
  }

  // ── pendingBpIds ──────────────────────────────────────────────────

  /** Only pending rows are collected, and a partner appearing on several rows is checked once. */
  @Test
  void testPendingBpIdsFiltersAndDeduplicates() throws Exception {
    JSONArray arr = operators(
        "bp-1", "pending",
        "bp-2", "valid",
        "bp-1", "pending",
        "bp-3", "invalid",
        "bp-4", "pending");

    assertEquals(Arrays.asList("bp-1", "bp-4"), Fiscal349BoxesHandler.pendingBpIds(arr));
  }

  /** A null/empty operators array yields no work rather than an exception. */
  @Test
  void testPendingBpIdsHandlesNullAndBlankIds() throws Exception {
    assertTrue(Fiscal349BoxesHandler.pendingBpIds(null).isEmpty());

    JSONArray arr = new JSONArray();
    JSONObject blank = new JSONObject();
    blank.put("bpId", "");
    blank.put("vies", "pending");
    arr.put(blank);
    assertTrue(Fiscal349BoxesHandler.pendingBpIds(arr).isEmpty());
  }

  // ── eligibility gate (must match ViesStatusObserver exactly) ──────

  /** Key '2' (NOI) plus a non-blank taxId is the only combination that qualifies. */
  @Test
  void testGateKeepsOnlyNoiPartnersWithTaxId() throws Exception {
    stubCandidateRows(
        new String[] { "FR12345678901", "IT15667431009", "", "DE111111111" },
        new String[] { "2",             "1",             "2", null });

    Fiscal349BoxesHandler.ViesGateResult gate =
        handler.loadViesCandidates(Arrays.asList("bp-noi", "bp-key1", "bp-blank", "bp-nokey"));

    assertEquals(1, gate.eligible.size());
    assertEquals("bp-noi", gate.eligible.get(0).bpId);
    assertEquals("FR12345678901", gate.eligible.get(0).taxId);
    // The other three fail the gate PERMANENTLY and are reported as such, not as pending.
    assertEquals(3, gate.notEligible);
  }

  /**
   * A partner failing the gate is counted as still-pending and is NEVER handed to
   * {@link ViesService} — the whole point of matching Classic's gate.
   */
  @Test
  void testGatedOutPartnerIsStillPendingAndNeverSentToVies() throws Exception {
    stubCandidateRows(new String[] { "ESB12345678" }, new String[] { "1" });

    try (MockedStatic<ViesService> vies = mockStatic(ViesService.class)) {
      JSONObject out = handler.validatePendingVies(Collections.singletonList("bp-key1"));

      verify(handler, never()).checkVat(anyString());
      vies.verify(() -> ViesService.checkVat(anyString()), never());
      assertEquals(1, out.getInt("validated"));
      assertEquals(0, out.getInt("valid"));
      assertEquals(0, out.getInt("invalid"));
      // ETP-5027 (QA F5): a gate failure is PERMANENT, so it is reported separately and must
      // NOT land in the re-runnable stillPending bucket.
      assertEquals(1, out.getInt("notEligible"));
      assertEquals(0, out.getInt("stillPending"));
    }
    verify(connMock, never()).prepareStatement(startsWith(UPDATE_SQL));
  }

  // ── counts ────────────────────────────────────────────────────────

  /** valid + invalid + stillPending always equals validated. */
  @Test
  void testCountsSumToValidated() throws Exception {
    stubCandidateRows(
        new String[] { "FR11111111111", "IT22222222222", "DE333333333", "PT444444444" },
        new String[] { "2", "2", "2", "1" });

    doReturn("V").when(handler).checkVat("FR11111111111");
    doReturn("I").when(handler).checkVat("IT22222222222");
    doReturn("P").when(handler).checkVat("DE333333333");

    JSONObject out = handler.validatePendingVies(
        Arrays.asList("bp-v", "bp-i", "bp-p", "bp-gated"));

    assertEquals(4, out.getInt("validated"));
    assertEquals(1, out.getInt("valid"));
    assertEquals(1, out.getInt("invalid"));
    assertEquals(1, out.getInt("notEligible")); // bp-gated: tax-id key is not NOI
    assertEquals(0, out.getInt("failed"));
    assertEquals(1, out.getInt("stillPending")); // bp-p: VIES answered inconclusively
    assertSumsToValidated(out);
  }

  /** An all-pending run is a normal outcome: 200 with zeroed conclusive counts, no writes. */
  @Test
  void testAllPendingOutcomeReturnsSensibleCounts() throws Exception {
    stubCandidateRows(
        new String[] { "FR11111111111", "FR22222222222" },
        new String[] { "2", "2" });

    JSONObject out = handler.validatePendingVies(Arrays.asList("bp-1", "bp-2"));

    assertEquals(2, out.getInt("validated"));
    assertEquals(0, out.getInt("valid"));
    assertEquals(0, out.getInt("invalid"));
    assertEquals(0, out.getInt("notEligible"));
    assertEquals(0, out.getInt("failed"));
    assertEquals(2, out.getInt("stillPending"));
    verify(connMock, never()).prepareStatement(startsWith(UPDATE_SQL));
  }

  /** The emitted JSON carries exactly the six contract keys. */
  @Test
  void testResponseShapeHasOnlyContractKeys() throws Exception {
    JSONObject out = handler.validatePendingVies(Collections.emptyList());

    List<String> keys = new ArrayList<>();
    out.keys().forEachRemaining(k -> keys.add(String.valueOf(k)));
    Collections.sort(keys);
    assertEquals(
        Arrays.asList("failed", "invalid", "notEligible", "stillPending", "valid", "validated"),
        keys);
    assertEquals(0, out.getInt("validated"));
  }

  // ── persistence ───────────────────────────────────────────────────

  /** V and I are written via JDBC; P is skipped because nothing was learned. */
  @Test
  void testOnlyConclusiveStatusesArePersisted() throws Exception {
    Map<String, String> statuses = new LinkedHashMap<>();
    statuses.put("bp-v", "V");
    statuses.put("bp-p", "P");
    statuses.put("bp-i", "I");

    assertEquals(new LinkedHashSet<>(Arrays.asList("bp-v", "bp-i")),
        handler.persistViesStatuses(statuses));

    verify(connMock).prepareStatement(
        "UPDATE c_bpartner SET em_obtik_viesstatus = ? WHERE c_bpartner_id = ?");
    verify(updatePs, times(2)).executeUpdate();
    verify(updatePs).setString(1, "V");
    verify(updatePs).setString(2, "bp-v");
    verify(updatePs).setString(1, "I");
    verify(updatePs).setString(2, "bp-i");
    verify(updatePs, never()).setString(2, "bp-p");
  }

  /** An all-pending status map performs no DB write at all. */
  @Test
  void testPendingOnlyMapPerformsNoUpdate() throws Exception {
    assertTrue(handler.persistViesStatuses(Collections.singletonMap("bp-p", "P")).isEmpty());
    verify(connMock, never()).prepareStatement(startsWith(UPDATE_SQL));
  }

  // ── DAL connection release (ETP-5027, QA F3) ──────────────────────

  /**
   * The pooled DB connection must be released BEFORE the VIES network phase and never during
   * it. {@code DalRequestFilter} pins the session (and its connection) to the request thread,
   * and {@code handleValidateVies} runs {@code computeOperators} first, so without an explicit
   * release the whole 120 s network phase would hold a connection — several concurrent clicks
   * would exhaust the pool instance-wide.
   */
  @Test
  void testDalConnectionIsReleasedBeforeTheViesPhase() throws Exception {
    stubCandidateRows(new String[] { "FR11111111111" }, new String[] { "2" });
    doReturn("V").when(handler).checkVat(anyString());

    handler.validatePendingVies(Collections.singletonList("bp-1"));

    InOrder order = inOrder(connMock, handler);
    order.verify(connMock).prepareStatement(startsWith(SELECT_SQL)); // gate: DB work
    order.verify(handler).releaseDalConnection();                    // release
    order.verify(handler).checkVat(anyString());                     // network, no connection
    order.verify(connMock).prepareStatement(startsWith(UPDATE_SQL)); // persist: fresh session
  }

  /** The release itself is a flush + commitAndClose on the DAL session. */
  @Test
  void testReleaseDalConnectionCommitsAndClosesTheSession() {
    handler.releaseDalConnection();

    verify(obDal).flush();
    verify(obDal).commitAndClose();
  }

  /**
   * A failure to release must not abort the run: it degrades to the old (pinned) behaviour,
   * which is worse but still correct, and the validation has not even started yet.
   */
  @Test
  void testReleaseDalConnectionSwallowsFailures() {
    doThrow(new RuntimeException("pool down")).when(obDal).commitAndClose();

    assertDoesNotThrow(() -> handler.releaseDalConnection());
  }

  // ── batch cap ─────────────────────────────────────────────────────

  /**
   * At most {@link Fiscal349BoxesHandler#VIES_BATCH_CAP} partners are looked up and sent to
   * VIES per call; the overflow is reported as still-pending so the next click picks it up.
   */
  @Test
  void testBatchCapIsHonoured() throws Exception {
    int pendingCount = Fiscal349BoxesHandler.VIES_BATCH_CAP + 7;
    List<String> ids = new ArrayList<>();
    String[] taxIds = new String[pendingCount];
    String[] keys = new String[pendingCount];
    for (int i = 0; i < pendingCount; i++) {
      ids.add("bp-" + i);
      taxIds[i] = "FR0000000000" + i;
      keys[i] = "2";
    }
    stubCandidateRows(taxIds, keys);

    doReturn("V").when(handler).checkVat(anyString());

    JSONObject out = handler.validatePendingVies(ids);

    // Only the capped slice was queried, checked and persisted.
    verify(selectPs, times(Fiscal349BoxesHandler.VIES_BATCH_CAP)).executeQuery();
    verify(handler, times(Fiscal349BoxesHandler.VIES_BATCH_CAP)).checkVat(anyString());
    verify(updatePs, times(Fiscal349BoxesHandler.VIES_BATCH_CAP)).executeUpdate();

    assertEquals(pendingCount, out.getInt("validated"));
    assertEquals(Fiscal349BoxesHandler.VIES_BATCH_CAP, out.getInt("valid"));
    assertEquals(0, out.getInt("invalid"));
    assertEquals(0, out.getInt("notEligible"));
    assertEquals(0, out.getInt("failed"));
    assertEquals(7, out.getInt("stillPending")); // deferred past the cap: retry picks them up
    assertSumsToValidated(out);
  }

  /** An empty pending set short-circuits: no DB access, no VIES traffic. */
  @Test
  void testEmptyPendingSetDoesNothing() throws Exception {
    try (MockedStatic<ViesService> vies = mockStatic(ViesService.class)) {
      JSONObject out = handler.validatePendingVies(null);

      verify(handler, never()).checkVat(anyString());
      vies.verify(() -> ViesService.checkVat(anyString()), never());
      assertEquals(0, out.getInt("validated"));
      assertEquals(0, out.getInt("stillPending"));
    }
    verify(connMock, never()).prepareStatement(anyString());
  }

  /** A row that no longer exists in C_BPartner is skipped, and counted as not eligible. */
  @Test
  void testMissingBusinessPartnerRowIsSkipped() throws Exception {
    when(selectRs.next()).thenReturn(false);

    Fiscal349BoxesHandler.ViesGateResult gate =
        handler.loadViesCandidates(Collections.singletonList("bp-gone"));

    assertTrue(gate.eligible.isEmpty());
    assertEquals(1, gate.notEligible);
  }

  // ── persistence failures must not be reported as success (QA F2) ──

  /**
   * The exact regression: VIES answered {@code V} but the write-back threw. The old code
   * swallowed it in a {@code log.warn} and still returned {@code valid: 1}, so the user reloaded
   * to find the partner still pending. It must now surface as {@code failed}.
   */
  @Test
  void testFailedWriteBackIsNotCountedAsValid() throws Exception {
    stubCandidateRows(new String[] { "FR11111111111" }, new String[] { "2" });
    doReturn("V").when(handler).checkVat(anyString());
    when(updatePs.executeUpdate()).thenThrow(new SQLException("connection reset"));

    JSONObject out = handler.validatePendingVies(Collections.singletonList("bp-1"));

    assertEquals(1, out.getInt("validated"));
    assertEquals(0, out.getInt("valid"));
    assertEquals(0, out.getInt("invalid"));
    assertEquals(1, out.getInt("failed"));
    assertEquals(0, out.getInt("stillPending"));
    assertSumsToValidated(out);
  }

  /** An UPDATE that matches no row is a silent failure too — the partner is gone. */
  @Test
  void testWriteBackAffectingNoRowCountsAsFailed() throws Exception {
    stubCandidateRows(new String[] { "FR11111111111" }, new String[] { "2" });
    doReturn("I").when(handler).checkVat(anyString());
    when(updatePs.executeUpdate()).thenReturn(0);

    JSONObject out = handler.validatePendingVies(Collections.singletonList("bp-1"));

    assertEquals(0, out.getInt("invalid"));
    assertEquals(1, out.getInt("failed"));
    assertSumsToValidated(out);
  }

  /** One bad write must not discard the rest of the batch. */
  @Test
  void testOneFailedWriteDoesNotDiscardTheOthers() throws Exception {
    stubCandidateRows(
        new String[] { "FR11111111111", "IT22222222222", "DE333333333" },
        new String[] { "2", "2", "2" });
    doReturn("V").when(handler).checkVat(anyString());
    when(updatePs.executeUpdate())
        .thenReturn(1).thenThrow(new SQLException("deadlock")).thenReturn(1);

    JSONObject out = handler.validatePendingVies(Arrays.asList("bp-1", "bp-2", "bp-3"));

    assertEquals(2, out.getInt("valid"));
    assertEquals(1, out.getInt("failed"));
    assertSumsToValidated(out);
  }

  /**
   * The candidate-loading catch used to sit OUTSIDE the per-id loop, so one unreadable row
   * silently discarded every candidate that had not been processed yet — a partial batch
   * reported as a complete run.
   */
  @Test
  void testOneUnreadableRowDoesNotDiscardTheRemainingCandidates() throws Exception {
    when(selectPs.executeQuery())
        .thenReturn(selectRs)
        .thenThrow(new SQLException("row read error"))
        .thenReturn(selectRs);
    when(selectRs.next()).thenReturn(true);
    when(selectRs.getString("taxid")).thenReturn("FR11111111111", "DE333333333");
    when(selectRs.getString("em_obtik_tax_id_key")).thenReturn("2", "2");

    Fiscal349BoxesHandler.ViesGateResult gate =
        handler.loadViesCandidates(Arrays.asList("bp-1", "bp-bad", "bp-3"));

    assertEquals(2, gate.eligible.size());
    assertEquals("bp-1", gate.eligible.get(0).bpId);
    assertEquals("bp-3", gate.eligible.get(1).bpId);
    // An unreadable row is TRANSIENT, so it is neither eligible nor permanently ineligible.
    assertEquals(0, gate.notEligible);
  }

  /** The five outcome buckets always partition `validated`. */
  private static void assertSumsToValidated(JSONObject out) throws Exception {
    assertEquals(out.getInt("validated"),
        out.getInt("valid") + out.getInt("invalid") + out.getInt("notEligible")
            + out.getInt("failed") + out.getInt("stillPending"));
  }
}
