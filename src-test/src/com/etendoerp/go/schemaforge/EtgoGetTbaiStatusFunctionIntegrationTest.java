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
import static org.junit.Assert.assertNotNull;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.test.base.OBBaseTest;

import com.smf.ticketbai.data.TbaiSyncinvoice;

/**
 * ETP-5216 — DB-backed proof that {@code ETGO_GET_TBAI_STATUS} is TOTAL, covering all five edge
 * cases specified in the migration plan's §5.8 and required by its R1/R3 risks:
 * {@code docs/plans/2026-09-08-tbai-status-computed-column-migration.md}.
 *
 * <p><strong>Why this function must never raise.</strong> {@code EM_ETGO_Tbai_Status} is declared
 * {@code Refresh_Mode = 'S'} (synchronous): the stored-computed-column engine recomputes it
 * <em>inside the same business transaction</em> that dirtied its source row, immediately before
 * COMMIT. Any exception this function raises therefore rolls back the whole transaction —
 * including the TicketBAI submission that wrote {@code tbai_syncinvoice} in the first place. An
 * invoice that fails to save because computing its fiscal *display* status failed would be far
 * worse than the filtering bug this migration fixes (see {@code CLAUDE.md} § Computed Column
 * Policy, "a computation error in synchronous mode rolls back the whole transaction"). Every test
 * below therefore asserts a VALUE, never an exception — a thrown exception is itself a test
 * failure regardless of which assertion line it interrupts.</p>
 *
 * <p><strong>R3 — the tie-break must be deterministic.</strong> {@code ad_scd_recompute} writes
 * the computed value unconditionally on every recompute, with no {@code IS DISTINCT FROM} guard.
 * If the function could return different values for unchanged data (which a bare
 * {@code ORDER BY created DESC LIMIT 1} can, when two rows share a {@code created} timestamp), the
 * stored column would flap and {@code ad_scd_check} would report permanent phantom drift. The
 * {@code , tbai_syncinvoice_id DESC} tiebreak in the function body is what prevents this, and
 * {@link #testPicksDeterministicWinnerAmongRowsTiedOnCreatedAndStaysStableAcrossRecomputes()} both
 * proves the tiebreak resolves the tie and that two independent recomputes of the exact same data
 * agree.</p>
 *
 * <p>Fixtures are scoped to the currently logged-in test client/org
 * ({@link #setTestUserContext()}) and every {@code TbaiSyncinvoice} row created here piggybacks on
 * an EXISTING fixture {@link Invoice} (never a hand-built one — {@code Invoice} carries too many
 * required associations to build safely as a throwaway) so no orphaned invoice is ever created.
 * Nothing is ever committed: the whole scenario is rolled back in {@link #rollbackChanges()},
 * mirroring {@code TbaiSyncStatusInjectorIntegrationTest}'s convention (the class this migration
 * deleted) and {@code EtgoInvitationUserCascadeDeleteIntegrationTest}'s.</p>
 *
 * <p><strong>NOT RUN.</strong> {@code ETGO_GET_TBAI_STATUS} does not exist in the local database
 * yet — {@code update.database} has not been run for this migration (ETP-5216 plan §8 Step 7 is
 * pending). This test is written correctly against the function's specified contract (§5.8) but
 * has not been executed; do not read a green CI run of this file as proof until that step lands.
 * A run against a database missing the function fails with a Postgres
 * {@code 42883 function etgo_get_tbai_status(...) does not exist} error, not a test assertion
 * failure — that is the expected failure mode before Step 7.</p>
 */
public class EtgoGetTbaiStatusFunctionIntegrationTest extends OBBaseTest {

  @After
  public void rollbackChanges() {
    while (OBContext.getOBContext() != null
        && OBContext.getOBContext().isInAdministratorMode()) {
      OBContext.restorePreviousMode();
    }
    OBDal.getInstance().rollbackAndClose();
  }

  /**
   * Calls {@code etgo_get_tbai_status(p_c_invoice_id)} directly over the live Hibernate session,
   * exactly like the {@code ad_scd_recompute} trigger would. {@code invoiceId == null} builds a
   * literal-{@code NULL} query rather than a bound parameter — Hibernate cannot infer a SQL type
   * for a {@code null} bind value on a raw native query, and a literal NULL is the unambiguous way
   * to exercise edge case 5 (a NULL argument).
   */
  @SuppressWarnings("rawtypes")
  private String callFunction(Session session, String invoiceId) {
    NativeQuery query;
    if (invoiceId == null) {
      query = session.createNativeQuery("SELECT etgo_get_tbai_status(NULL)");
    } else {
      query = session.createNativeQuery("SELECT etgo_get_tbai_status(:invoiceId)");
      query.setParameter("invoiceId", invoiceId);
    }
    return (String) query.uniqueResult();
  }

  private Invoice anyFixtureInvoice() {
    Invoice invoice = (Invoice) OBDal.getInstance().createCriteria(Invoice.class)
        .setMaxResults(1)
        .uniqueResult();
    assertNotNull("Test fixture must contain at least one invoice to attach sync rows to",
        invoice);
    return invoice;
  }

  /**
   * Builds (but does not save) a {@code TbaiSyncinvoice} fixture row scoped to the given
   * invoice's own client/org. When {@code created} is non-null it is set BEFORE
   * {@code OBDal.save()} — {@code OBInterceptor.onSave()} only auto-populates
   * {@code creationDate} when it is still {@code null} (src/org/openbravo/dal/core/
   * OBInterceptor.java:394), so an explicit value set here survives the save and lets this test
   * control the exact "latest row" ordering deterministically instead of racing real wall-clock
   * time.
   */
  private TbaiSyncinvoice newSyncRow(Invoice invoice, String estado, Date created) {
    TbaiSyncinvoice row = OBProvider.getInstance().get(TbaiSyncinvoice.class);
    row.setClient(invoice.getClient());
    row.setOrganization(invoice.getOrganization());
    row.setInvoice(invoice);
    row.setEstado(estado);
    row.setDescripcion(estado == null || estado.isEmpty() ? null : "00");
    if (created != null) {
      row.setCreationDate(created);
    }
    return row;
  }

  private Date farFutureTimestamp() {
    // Far enough in the future that it beats any pre-existing fixture data's
    // `created` timestamp, so this test does not depend on the chosen fixture
    // invoice being otherwise free of tbai_syncinvoice rows.
    Calendar cal = Calendar.getInstance();
    cal.clear();
    cal.set(2999, Calendar.DECEMBER, 31, 23, 59, 59);
    return new Timestamp(cal.getTimeInMillis());
  }

  private Date pastTimestamp() {
    Calendar cal = Calendar.getInstance();
    cal.clear();
    cal.set(2000, Calendar.JANUARY, 1, 0, 0, 0);
    return new Timestamp(cal.getTimeInMillis());
  }

  // ── Edge case 1 — no tbai_syncinvoice row at all ────────────────────────────
  // `SELECT ... INTO` (not `INTO STRICT`) assigns NULL for zero matching rows
  // and does not raise NO_DATA_FOUND. Uses a random id belonging to NO real
  // invoice at all: the function never joins to c_invoice, so an id with zero
  // matching rows is a stronger proof than hunting for a real, sync-row-free
  // invoice in shared fixture data.
  @Test
  public void testReturnsPendienteWhenNoSyncRowExists() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Session session = OBDal.getInstance().getSession();
      String randomId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
      String result = callFunction(session, randomId);
      assertEquals(
          "Zero matching tbai_syncinvoice rows must resolve to 'Pendiente', not NO_DATA_FOUND",
          "Pendiente", result);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ── Edge case 5 — a NULL argument ────────────────────────────────────────────
  // The engine always passes a real PK, but the function must still degrade
  // gracefully to the "no resolved submission" state rather than propagating a
  // bare SQL NULL or raising.
  @Test
  public void testReturnsPendienteForNullArgument() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Session session = OBDal.getInstance().getSession();
      String result = callFunction(session, null);
      assertEquals("A NULL argument must resolve to 'Pendiente', never to SQL NULL or an error",
          "Pendiente", result);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ── Edge case 3 (first half) — ESTADO is NULL on the latest row ─────────────
  // A tbai_syncinvoice row is created before the submission response is parsed
  // (see SynchronizeUtils.java:374-385), so ESTADO can genuinely be NULL for a
  // real, existing row — this is not a hypothetical schema-nullability check.
  @Test
  public void testReturnsPendienteWhenEstadoIsNull() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Invoice invoice = anyFixtureInvoice();
      TbaiSyncinvoice row = newSyncRow(invoice, null, farFutureTimestamp());
      OBDal.getInstance().save(row);
      OBDal.getInstance().flush();

      Session session = OBDal.getInstance().getSession();
      String result = callFunction(session, invoice.getId());
      assertEquals("A NULL ESTADO on the latest row must resolve to 'Pendiente'",
          "Pendiente", result);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ── Edge case 3 (second half) — ESTADO is an empty string, not NULL ─────────
  // `btrim(v_estado) = ''` is what catches this branch — a plain `IS NULL`
  // check alone would miss it.
  @Test
  public void testReturnsPendienteWhenEstadoIsBlank() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Invoice invoice = anyFixtureInvoice();
      TbaiSyncinvoice row = newSyncRow(invoice, "", farFutureTimestamp());
      OBDal.getInstance().save(row);
      OBDal.getInstance().flush();

      Session session = OBDal.getInstance().getSession();
      String result = callFunction(session, invoice.getId());
      assertEquals("A blank (empty-string) ESTADO on the latest row must resolve to 'Pendiente'",
          "Pendiente", result);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ── Edge case 4 — an unexpected status value ────────────────────────────────
  // TicketBAI adding a new state, or legacy tenant data, must pass through
  // UNCHANGED — never normalised to a known state, never raised. Normalising
  // here would invent information the database does not have; the frontend's
  // FiscalStatusBadge already degrades gracefully for an unmapped value.
  @Test
  public void testPassesThroughAnUnrecognizedStatusValueUnchanged() throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Invoice invoice = anyFixtureInvoice();
      String unexpectedStatus = "SomeFutureStatus";
      TbaiSyncinvoice row = newSyncRow(invoice, unexpectedStatus, farFutureTimestamp());
      OBDal.getInstance().save(row);
      OBDal.getInstance().flush();

      Session session = OBDal.getInstance().getSession();
      String result = callFunction(session, invoice.getId());
      assertEquals(
          "An unrecognized status must be returned verbatim — normalising here would invent "
              + "information, and raising would block the invoice save",
          unexpectedStatus, result);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ── Edge case 2 — several rows, INCLUDING a tie on `created` ────────────────
  // See the class-level Javadoc for why the deterministic tiebreak and the
  // stability-across-recomputes assertion both matter (R1/R3 of the migration
  // plan).
  @Test
  public void testPicksDeterministicWinnerAmongRowsTiedOnCreatedAndStaysStableAcrossRecomputes()
      throws Exception {
    setTestUserContext();
    OBContext.setAdminMode(true);
    try {
      Invoice invoice = anyFixtureInvoice();

      // An older row: must lose to both tied rows below purely on `created
      // DESC`, proving that ordering is honored before the tiebreak is ever
      // consulted.
      TbaiSyncinvoice older = newSyncRow(invoice, "Error", pastTimestamp());
      OBDal.getInstance().save(older);
      OBDal.getInstance().flush();

      // Two rows sharing the exact same `created` instant — the real tie.
      Date tiedInstant = farFutureTimestamp();
      TbaiSyncinvoice tiedA = newSyncRow(invoice, "Recibido", tiedInstant);
      TbaiSyncinvoice tiedB = newSyncRow(invoice, "Rechazado", tiedInstant);
      OBDal.getInstance().save(tiedA);
      OBDal.getInstance().save(tiedB);
      OBDal.getInstance().flush();

      // The winner is whichever tied row has the GREATER tbai_syncinvoice_id —
      // computed from the real ids the engine assigned (never assumed), since
      // the id-generation strategy is not something this test should need to
      // know about.
      String winnerId = tiedA.getId().compareTo(tiedB.getId()) > 0 ? tiedA.getId() : tiedB.getId();
      String expectedWinnerEstado = winnerId.equals(tiedA.getId())
          ? tiedA.getEstado()
          : tiedB.getEstado();

      Session session = OBDal.getInstance().getSession();

      // Two independent round trips, exactly like two separate
      // ad_scd_recompute invocations against unchanged source data.
      String firstCall = callFunction(session, invoice.getId());
      String secondCall = callFunction(session, invoice.getId());

      assertEquals("The tie on `created` must resolve to the row with the greater "
          + "tbai_syncinvoice_id, and the older row must never win",
          expectedWinnerEstado, firstCall);
      assertEquals(
          "Repeated recomputes of UNCHANGED data must return the SAME value — a flapping "
              + "value would permanently desync ad_scd_check (R3 of the ETP-5216 migration plan)",
          firstCall, secondCall);
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
