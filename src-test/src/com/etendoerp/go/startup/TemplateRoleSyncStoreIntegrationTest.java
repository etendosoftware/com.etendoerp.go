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
package com.etendoerp.go.startup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

/**
 * ETP-5565 — real-DB tests of {@link TemplateRoleSyncStore}'s SQL: lease acquire / renew /
 * release / expiry takeover / seeding, and the fingerprint store's newer-version guard.
 *
 * <p>The lease methods commit their own transactions by design, so this test cannot rely on a
 * final rollback: it snapshots the database's real lease row before each test and restores it
 * afterwards. The fingerprint tests never commit.</p>
 */
public class TemplateRoleSyncStoreIntegrationTest extends OBBaseTest {

  private static final String ME = "ETP-5565-test-me";
  private static final String OTHER = "ETP-5565-test-other";
  private static final String FINANCE = "B88A34B5D1874F8685FA6F3C3A609412";

  private final TemplateRoleSyncStore store = new TemplateRoleSyncStore();
  private Object[] savedLease;

  @Before
  public void snapshotLease() {
    setTestUserContext();
    OBContext.setAdminMode(true);
    List<Object[]> rows = rows("SELECT holder, CAST(lease_until AS varchar), last_error "
        + "FROM etgo_tpl_role_lease WHERE etgo_tpl_role_lease_id = '0'");
    savedLease = rows.isEmpty() ? null : rows.get(0);
    resetLease();
  }

  @After
  public void restoreLease() {
    OBDal.getInstance().rollbackAndClose();
    if (savedLease == null) {
      exec("DELETE FROM etgo_tpl_role_lease WHERE etgo_tpl_role_lease_id = '0'");
    } else {
      seed();
      NativeQuery<?> restore = OBDal.getInstance().getSession().createNativeQuery(
          "UPDATE etgo_tpl_role_lease SET holder = CAST(:holder AS varchar), "
              + "lease_until = CAST(CAST(:until AS varchar) AS timestamp), "
              + "last_error = CAST(:error AS varchar) WHERE etgo_tpl_role_lease_id = '0'");
      restore.setParameter("holder", savedLease[0]);
      restore.setParameter("until", savedLease[1]);
      restore.setParameter("error", savedLease[2]);
      restore.executeUpdate();
    }
    OBDal.getInstance().commitAndClose();
    OBContext.restorePreviousMode();
  }

  @Test
  public void freeLeaseIsTakenOnceAndHeldUntilReleased() {
    assertTrue(store.acquireLease(ME));
    assertFalse("Another task must not take a live lease", store.acquireLease(OTHER));
    assertTrue("The holder may re-acquire its own lease", store.acquireLease(ME));
    assertEquals(ME, holder());

    store.releaseLease(ME, null);

    assertNull(holder());
    assertTrue(store.acquireLease(OTHER));
  }

  @Test
  public void onlyTheHolderRenewsOrReleases() {
    assertTrue(store.acquireLease(ME));

    assertFalse(store.renewLease(OTHER));
    store.releaseLease(OTHER, "not mine");
    assertEquals("A non-holder's release is a no-op", ME, holder());
    assertTrue(store.renewLease(ME));

    store.releaseLease(ME, "boom");
    assertNull(holder());
    assertEquals("boom", string("SELECT last_error FROM etgo_tpl_role_lease "
        + "WHERE etgo_tpl_role_lease_id = '0'"));
  }

  @Test
  public void expiredLeaseIsTakenOverAndTheOldHolderCannotRenew() {
    assertTrue(store.acquireLease(ME));
    exec("UPDATE etgo_tpl_role_lease SET lease_until = now() - interval '1 minute' "
        + "WHERE etgo_tpl_role_lease_id = '0'");
    OBDal.getInstance().commitAndClose();

    assertTrue(store.acquireLease(OTHER));
    assertFalse("The old holder lost the lease", store.renewLease(ME));
    assertEquals(OTHER, holder());
  }

  @Test
  public void missingLeaseRowIsSeededOnAcquire() {
    exec("DELETE FROM etgo_tpl_role_lease WHERE etgo_tpl_role_lease_id = '0'");
    OBDal.getInstance().commitAndClose();

    assertTrue(store.acquireLease(ME));
    assertEquals(ME, holder());
  }

  @Test
  public void fingerprintFromANewerVersionIsNeverOverwrittenByAnOlderOne() {
    store.storeFingerprint(FINANCE, "newer", 2, 1);
    store.storeFingerprint(FINANCE, "older", 1, 1);
    assertEquals("newer", store.readFingerprints().get(FINANCE).getFingerprint());
    assertEquals(2, store.readFingerprints().get(FINANCE).getAlgoVersion());

    store.storeFingerprint(FINANCE, "same-version", 2, 5);
    assertEquals("same-version", store.readFingerprints().get(FINANCE).getFingerprint());
  }

  // ---------------------------------------------------------------------

  private static void resetLease() {
    seed();
    exec("UPDATE etgo_tpl_role_lease SET holder = NULL, lease_until = NULL, last_error = NULL "
        + "WHERE etgo_tpl_role_lease_id = '0'");
    OBDal.getInstance().commitAndClose();
  }

  private static void seed() {
    exec("INSERT INTO etgo_tpl_role_lease (etgo_tpl_role_lease_id, ad_client_id, ad_org_id, "
        + "isactive, created, createdby, updated, updatedby) VALUES ('0', '0', '0', 'Y', now(), "
        + "'0', now(), '0') ON CONFLICT DO NOTHING");
  }

  private static String holder() {
    String holder = string("SELECT holder FROM etgo_tpl_role_lease "
        + "WHERE etgo_tpl_role_lease_id = '0'");
    OBDal.getInstance().commitAndClose();
    return holder;
  }

  private static void exec(String sql) {
    OBDal.getInstance().getSession().createNativeQuery(sql).executeUpdate();
  }

  private static String string(String sql) {
    return (String) OBDal.getInstance().getSession().createNativeQuery(sql).getSingleResult();
  }

  @SuppressWarnings("unchecked")
  private static List<Object[]> rows(String sql) {
    return (List<Object[]>) OBDal.getInstance().getSession().createNativeQuery(sql).list();
  }
}
