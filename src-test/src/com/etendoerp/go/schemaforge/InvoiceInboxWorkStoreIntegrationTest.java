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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.TestConstants;

import com.etendoerp.go.schemaforge.InvoiceInboxWorkStore.Claimed;
import com.etendoerp.go.schemaforge.data.IncomingDocument;

/**
 * Real-database specs for the SQL of the inbox worker ({@link InvoiceInboxWorkStore}): the atomic
 * claim and its lease, the cap of passes, the write-once extraction, the conditioned terminal
 * writes, the lock, the sweep queries and the role the worker acts under. A mock cannot show that
 * two nodes claiming the same row at the same instant produce exactly one winner, or that the
 * {@code STATUS = 'PROCESSING'} condition keeps a discarded row untouched.
 *
 * <p>Every store method commits, so the fixtures are committed too: each file name carries a
 * per-run marker and {@link #deleteCommittedFixtures()} removes the rows. Rows are made in the F&amp;B
 * group client; the queries that are not scoped by client ({@code stalledIds}, {@code failExhausted})
 * are asserted only on this run's rows.</p>
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxWorkStore
 */
public class InvoiceInboxWorkStoreIntegrationTest extends WeldBaseTest {

  private static final String CLIENT_ID = TestConstants.Clients.FB_GRP;
  private static final String ORG_ID = TestConstants.Orgs.ESP;
  private static final String PROCESSING = "PROCESSING";
  private static final int LEASE_MINUTES = 10;
  private static final int MAX_ATTEMPTS = 3;
  private static final String ID_PARAM = "id";

  private final String marker = "etgo-store-it-" + UUID.randomUUID().toString().substring(0, 8) + "-";
  private final InvoiceInboxWorkStore store = new InvoiceInboxWorkStore();
  private int counter;

  @Before
  public void prepareContext() {
    OBContext.clearAdminModeStack();
    login();
  }

  @After
  public void cleanUp() {
    try {
      deleteCommittedFixtures();
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  // ---------------------------------------------------------------- the claim

  @Test
  public void aClaimCountsThePassSetsTheLeaseAndReturnsWhatTheWorkerNeeds() {
    String id = row(PROCESSING, 0);

    Claimed claimed = claim(id);

    assertNotNull(claimed);
    assertEquals(TestConstants.Users.ADMIN, claimed.userId());
    assertEquals(CLIENT_ID, claimed.clientId());
    assertEquals(ORG_ID, claimed.orgId());
    assertEquals("PURCHASE_INVOICE", claimed.targetType());
    assertEquals(1L, claimed.attempts());
    assertNull(claimed.extractedData());
    assertEquals(1L, number("SELECT processing_attempts FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", id));
    assertEquals("the lease runs about ten minutes ahead of the database clock", 1L,
        number("SELECT count(*) FROM etgo_incoming_document WHERE etgo_incoming_document_id = :id"
            + " AND processing_lease > now() + interval '9 minutes'"
            + " AND processing_lease < now() + interval '11 minutes'", id));
  }

  @Test
  public void aLiveLeaseKeepsEveryoneElseOut() {
    String id = row(PROCESSING, 0);
    assertNotNull(claim(id));

    assertNull("the lease is live", claim(id));

    assertEquals("a refused claim does not count a pass", 1L, attempts(id));
  }

  @Test
  public void anExpiredLeaseCanBeTakenAgainAndCountsAnotherPass() {
    String id = row(PROCESSING, 1);
    update("UPDATE etgo_incoming_document SET processing_lease = now() - interval '1 minute'"
        + " WHERE etgo_incoming_document_id = :id", id);

    Claimed claimed = claim(id);

    assertNotNull(claimed);
    assertEquals(2L, claimed.attempts());
  }

  @Test
  public void twoNodesClaimingTheSameRowAtOnceProduceExactlyOneWinner() throws Exception {
    String id = row(PROCESSING, 0);
    int nodes = 4;
    ExecutorService pool = Executors.newFixedThreadPool(nodes);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Claimed>> results = new ArrayList<>();
      for (int i = 0; i < nodes; i++) {
        Callable<Claimed> node = () -> {
          OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN,
              CLIENT_ID, ORG_ID);
          try {
            start.await(10, TimeUnit.SECONDS);
            return store.claim(id, LEASE_MINUTES, MAX_ATTEMPTS);
          } finally {
            OBDal.getInstance().rollbackAndClose();
            OBContext.setOBContext((OBContext) null);
          }
        };
        results.add(pool.submit(node));
      }
      start.countDown();
      int winners = 0;
      for (Future<Claimed> result : results) {
        winners += result.get(30, TimeUnit.SECONDS) == null ? 0 : 1;
      }
      assertEquals("exactly one node processes the row", 1, winners);
    } finally {
      pool.shutdownNow();
    }
    login();
    assertEquals("and only one pass was counted", 1L, attempts(id));
  }

  @Test
  public void theCapOfPassesStopsFurtherClaims() {
    String atCap = row(PROCESSING, MAX_ATTEMPTS);
    assertNull("three passes were made", claim(atCap));

    String last = row(PROCESSING, MAX_ATTEMPTS - 1);
    Claimed third = claim(last);
    assertNotNull("the third pass is allowed", third);
    assertEquals(3L, third.attempts());
    update("UPDATE etgo_incoming_document SET processing_lease = now() - interval '1 minute'"
        + " WHERE etgo_incoming_document_id = :id", last);
    assertNull("a fourth is not, even with the lease expired", claim(last));
  }

  @Test
  public void aRowThatIsNoLongerProcessingIsNotClaimed() {
    String pending = row("PENDING_REVIEW", 0);
    String discarded = row("DISCARDED", 0);

    assertNull(claim(pending));
    assertNull(claim(discarded));
    assertNull(claim("NO-SUCH-ROW"));
    assertEquals(0L, attempts(pending));
  }

  // ---------------------------------------------------------------- the writes

  @Test
  public void theExtractionIsStoredOnceAndOnlyWhileProcessing() {
    String id = row(PROCESSING, 0);
    String pending = row("PENDING_REVIEW", 0);

    store.storeExtracted(id, "{\"first\":true}");
    login();
    store.storeExtracted(id, "{\"second\":true}");
    login();
    store.storeExtracted(pending, "{\"never\":true}");
    login();

    assertEquals("write-once: the second does not replace the first", "{\"first\":true}",
        text("SELECT extracted_data FROM etgo_incoming_document WHERE etgo_incoming_document_id = :id",
            id));
    assertNull("a row that is not processing keeps no extraction", text(
        "SELECT extracted_data FROM etgo_incoming_document WHERE etgo_incoming_document_id = :id",
        pending));
  }

  @Test
  public void aStoredExtractionComesBackWithTheClaim() {
    String id = row(PROCESSING, 0);
    store.storeExtracted(id, "{\"vendor_name\":\"Acme\"}");
    login();

    Claimed claimed = claim(id);

    assertEquals("{\"vendor_name\":\"Acme\"}", claimed.extractedData());
  }

  @Test
  public void failEndsOnlyAProcessingRowWithItsCodeAndClearsTheLease() {
    String id = row(PROCESSING, 1);
    assertNotNull(claim(id));
    String discarded = row("DISCARDED", 0);

    store.fail(id, "UNREADABLE");
    login();
    store.fail(discarded, "UNREADABLE");
    login();

    assertEquals("ERROR", text("SELECT status FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", id));
    assertEquals("UNREADABLE", text("SELECT error_code FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", id));
    assertNull(text("SELECT processing_lease FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", id));
    assertEquals("the end is stamped", 1L, number("SELECT count(*) FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id AND processed_at IS NOT NULL", id));
    assertEquals("a discarded row is never overwritten", "DISCARDED", text(
        "SELECT status FROM etgo_incoming_document WHERE etgo_incoming_document_id = :id",
        discarded));
    assertNull(text("SELECT error_code FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", discarded));
  }

  @Test
  public void anUnexpectedFailureBeforeTheLastPassReleasesTheLeaseAndKeepsTheRowProcessing() {
    String id = row(PROCESSING, 0);
    assertNotNull(claim(id));

    store.failOrRelease(id, MAX_ATTEMPTS);
    login();

    assertEquals(PROCESSING, text("SELECT status FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", id));
    assertNull("the sweeper can take it at once", text("SELECT processing_lease FROM"
        + " etgo_incoming_document WHERE etgo_incoming_document_id = :id", id));
    assertNotNull("and it can be claimed again right away", claim(id));
  }

  @Test
  public void anUnexpectedFailureOnTheLastPassEndsTheRowAsServiceFailure() {
    String id = row(PROCESSING, MAX_ATTEMPTS - 1);
    assertNotNull(claim(id));

    store.failOrRelease(id, MAX_ATTEMPTS);
    login();

    assertEquals("ERROR", text("SELECT status FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", id));
    assertEquals("SERVICE_FAILURE", text("SELECT error_code FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", id));
    assertNull(text("SELECT processing_lease FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", id));
  }

  @Test
  public void failOrReleaseNeverTouchesARowThatIsNoLongerProcessing() {
    String id = row("CONFIRMED", MAX_ATTEMPTS);

    store.failOrRelease(id, MAX_ATTEMPTS);
    login();

    assertEquals("CONFIRMED", text("SELECT status FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", id));
  }

  @Test
  public void lockIfProcessingSaysWhetherTheRowCanStillBeWritten() {
    String processing = row(PROCESSING, 0);
    String discarded = row("DISCARDED", 0);

    assertTrue(store.lockIfProcessing(processing));
    OBDal.getInstance().rollbackAndClose();
    login();
    assertFalse(store.lockIfProcessing(discarded));
    OBDal.getInstance().rollbackAndClose();
    login();
    assertFalse("an id that does not exist", store.lockIfProcessing("NO-SUCH-ROW"));
  }

  // ---------------------------------------------------------------- the sweep

  @Test
  public void stalledIdsAreTheOldProcessingRowsWithNoLiveLeaseAndPassesLeft() {
    String young = row(PROCESSING, 0);
    String old = oldRow(PROCESSING, 0);
    String leased = oldRow(PROCESSING, 1);
    update("UPDATE etgo_incoming_document SET processing_lease = now() + interval '5 minutes'"
        + " WHERE etgo_incoming_document_id = :id", leased);
    String expired = oldRow(PROCESSING, 1);
    update("UPDATE etgo_incoming_document SET processing_lease = now() - interval '1 minute'"
        + " WHERE etgo_incoming_document_id = :id", expired);
    String exhausted = oldRow(PROCESSING, MAX_ATTEMPTS);
    String finished = oldRow("PENDING_REVIEW", 0);

    List<String> stalled = store.stalledIds(MAX_ATTEMPTS, 10_000);

    assertTrue("old, unleased", stalled.contains(old));
    assertTrue("old, lease expired", stalled.contains(expired));
    assertFalse("younger than a minute: its own node is on it", stalled.contains(young));
    assertFalse("a live lease", stalled.contains(leased));
    assertFalse("out of passes", stalled.contains(exhausted));
    assertFalse("not processing", stalled.contains(finished));
  }

  @Test
  public void stalledIdsAreOldestFirstAndCappedByTheLimit() {
    String older = oldRow(PROCESSING, 0);
    update("UPDATE etgo_incoming_document SET created = now() - interval '2 hours'"
        + " WHERE etgo_incoming_document_id = :id", older);
    oldRow(PROCESSING, 0);

    List<String> one = store.stalledIds(MAX_ATTEMPTS, 1);

    assertEquals(1, one.size());
    List<String> all = store.stalledIds(MAX_ATTEMPTS, 10_000);
    assertEquals("the list is ordered by age", all.get(0), one.get(0));
    assertTrue(all.indexOf(older) >= 0);
    assertTrue("the oldest of this run comes before the newer one",
        all.indexOf(older) < all.size());
  }

  @Test
  public void exhaustedRowsEndAsServiceFailureUnlessTheirLeaseIsLive() {
    String exhausted = row(PROCESSING, MAX_ATTEMPTS);
    String leased = row(PROCESSING, MAX_ATTEMPTS);
    update("UPDATE etgo_incoming_document SET processing_lease = now() + interval '5 minutes'"
        + " WHERE etgo_incoming_document_id = :id", leased);
    String withPassesLeft = row(PROCESSING, 1);

    int ended = store.failExhausted(MAX_ATTEMPTS);
    login();

    assertTrue("at least this run's exhausted row", ended >= 1);
    assertEquals("ERROR", text("SELECT status FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", exhausted));
    assertEquals("SERVICE_FAILURE", text("SELECT error_code FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", exhausted));
    assertEquals("a worker may still be on the leased one", PROCESSING, text(
        "SELECT status FROM etgo_incoming_document WHERE etgo_incoming_document_id = :id", leased));
    assertEquals(PROCESSING, text("SELECT status FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", withPassesLeft));
  }

  // ---------------------------------------------------------------- the role

  @Test
  public void theRoleOfAUserIsOneOfItsOwnActiveRolesInTheClient() {
    String role = store.roleFor(TestConstants.Users.ADMIN, CLIENT_ID);

    assertNotNull("the admin user has a role in the F&B group", role);
    assertEquals("an active role of that client", 1L, number("SELECT count(*) FROM ad_role"
        + " WHERE ad_role_id = :id AND ad_client_id = '" + CLIENT_ID + "' AND isactive = 'Y'", role));
    assertEquals("that the user holds", 1L, number("SELECT count(*) FROM ("
        + "SELECT ad_role_id FROM ad_user_roles WHERE ad_user_id = '" + TestConstants.Users.ADMIN
        + "' AND isactive = 'Y' UNION SELECT default_ad_role_id FROM ad_user WHERE ad_user_id = '"
        + TestConstants.Users.ADMIN + "') r WHERE r.ad_role_id = :id", role));
  }

  @Test
  public void theDefaultRoleWinsWhenItBelongsToTheClient() {
    Object[] defaults = (Object[]) OBDal.getInstance().getSession().createNativeQuery(
        "SELECT u.ad_user_id, r.ad_client_id, r.ad_role_id FROM ad_user u JOIN ad_role r"
            + " ON r.ad_role_id = u.default_ad_role_id WHERE r.isactive = 'Y' AND r.ad_client_id <> '0'"
            + " ORDER BY u.ad_user_id LIMIT 1").uniqueResult();
    assertNotNull("the database has a user whose default role is in a client", defaults);

    String role = store.roleFor((String) defaults[0], (String) defaults[1]);

    assertEquals(defaults[2], role);
  }

  @Test
  public void aUserWithNoRoleInTheClientHasNone() {
    assertNull(store.roleFor("NO-SUCH-USER", CLIENT_ID));
    assertNull(store.roleFor(TestConstants.Users.ADMIN, "NO-SUCH-CLIENT"));
  }

  // ---------------------------------------------------------------- helpers

  private static void login() {
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN, CLIENT_ID,
        ORG_ID);
  }

  /** Claims as the worker does, then starts a fresh session (the claim commits and closes). */
  private Claimed claim(String id) {
    Claimed claimed = store.claim(id, LEASE_MINUTES, MAX_ATTEMPTS);
    login();
    return claimed;
  }

  /** A committed row in the given status with the given passes already made. */
  private String row(String status, long attempts) {
    OBContext.setAdminMode(false);
    String id;
    try {
      IncomingDocument row = OBProvider.getInstance().get(IncomingDocument.class);
      row.setClient(OBDal.getInstance().get(Client.class, CLIENT_ID));
      row.setOrganization(OBDal.getInstance().get(Organization.class, ORG_ID));
      row.setActive(true);
      row.setFileName(marker + (++counter) + ".pdf");
      row.setSource("UPLOAD");
      row.setTargetType("PURCHASE_INVOICE");
      row.setStatus(status);
      row.setAdjusted(false);
      row.setReviewed(false);
      row.setProcessingAttempts(attempts);
      OBDal.getInstance().save(row);
      OBDal.getInstance().flush();
      id = row.getId();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
    login();
    return id;
  }

  /** Like {@link #row} but created five minutes ago, so the sweep considers it. */
  private String oldRow(String status, long attempts) {
    String id = row(status, attempts);
    update("UPDATE etgo_incoming_document SET created = now() - interval '5 minutes'"
        + " WHERE etgo_incoming_document_id = :id", id);
    return id;
  }

  private void update(String sql, String id) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(ID_PARAM, id);
    query.executeUpdate();
    OBDal.getInstance().commitAndClose();
    login();
  }

  private long attempts(String id) {
    return number("SELECT processing_attempts FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id", id);
  }

  private static long number(String sql, String id) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(ID_PARAM, id);
    return ((Number) query.uniqueResult()).longValue();
  }

  private static String text(String sql, String id) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(ID_PARAM, id);
    Object value = query.uniqueResult();
    return value == null ? null : String.valueOf(value);
  }

  /** Removes every row this run committed. */
  private void deleteCommittedFixtures() {
    login();
    OBContext.setAdminMode(false);
    try {
      NativeQuery<?> delete = OBDal.getInstance().getSession().createNativeQuery(
          "DELETE FROM etgo_incoming_document WHERE file_name LIKE :p");
      delete.setParameter("p", marker + "%");
      delete.executeUpdate();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
