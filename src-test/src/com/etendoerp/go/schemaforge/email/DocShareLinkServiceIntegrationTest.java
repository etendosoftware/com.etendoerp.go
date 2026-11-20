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

package com.etendoerp.go.schemaforge.email;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.service.OBDal;
import org.openbravo.test.base.OBBaseTest;

import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService.Claims;

/**
 * Runs {@link DocShareLinkService} against the real {@code ETGO_Doc_Share_Link} table: only live
 * links resolve, opens are counted atomically, and the unique index on the share code
 * holds. Everything is rolled back at the end.
 *
 * @covers com.etendoerp.go.schemaforge.email.DocShareLinkService
 */
public class DocShareLinkServiceIntegrationTest extends OBBaseTest {

  private static final String CLIENT = "0";
  private static final long HOUR_MILLIS = 3600L * 1000L;

  @Before
  public void adminContext() {
    setTestAdminContext();
  }

  @After
  public void rollbackChanges() {
    OBDal.getInstance().rollbackAndClose();
  }

  private static String create(long expiresInMillis) {
    return DocShareLinkService.create("sales-order-send", "sales-order", "REC1", CLIENT, "key",
        System.currentTimeMillis() + expiresInMillis);
  }

  @Test
  public void onlyALiveLinkResolves() {
    String live = create(HOUR_MILLIS);
    String expired = create(-HOUR_MILLIS);
    String revoked = create(HOUR_MILLIS);
    assertTrue(DocShareLinkService.revoke(revoked));

    Optional<Claims> claims = DocShareLinkService.resolve(live);
    assertTrue(claims.isPresent());
    assertEquals("REC1", claims.get().getRecordId());
    assertEquals("sales-order", claims.get().getSpecName());
    assertFalse(DocShareLinkService.resolve(expired).isPresent());
    assertFalse(DocShareLinkService.resolve(revoked).isPresent());
    assertFalse(DocShareLinkService.resolve("zzzzzzzzzz").isPresent());
    assertFalse(DocShareLinkService.revoke(revoked));
  }

  @Test
  public void opensAreCountedAndTheFirstOneIsStampedOnce() {
    String code = create(HOUR_MILLIS);
    assertEquals(0, openCount(code));
    assertNull(firstOpenedAt(code));

    DocShareLinkService.recordOpen(code);
    Timestamp first = firstOpenedAt(code);
    assertNotNull(first);
    DocShareLinkService.recordOpen(code);

    assertEquals(2, openCount(code));
    assertEquals(first, firstOpenedAt(code));
  }

  @Test
  public void theShareCodeIsUnique() {
    String code = create(HOUR_MILLIS);
    insertRowWithCode(code.equals("OTHERCODE1") ? "OTHERCODE2" : "OTHERCODE1");
    try {
      insertRowWithCode(code);
      fail("a second row with the same share code must be rejected");
    } catch (RuntimeException expected) {
      assertTrue(causeChain(expected), causeChain(expected).toLowerCase()
          .contains("etgo_doc_share_link_code_uq"));
    }
  }

  private static void insertRowWithCode(String code) {
    OBDal.getInstance().getSession().createNativeQuery(
        "INSERT INTO etgo_doc_share_link (etgo_doc_share_link_id, ad_client_id, ad_org_id,"
            + " createdby, updatedby, share_code, contract_name, spec_name, record_id,"
            + " idempotency_key, expires_at)"
            + " VALUES (:id, '0', '0', '0', '0', :code, 'c', 's', 'r', 'k', now())")
        .setParameter("id", UUID.randomUUID().toString().replace("-", "").toUpperCase())
        .setParameter("code", code)
        .executeUpdate();
  }

  private static String causeChain(Throwable error) {
    StringBuilder chain = new StringBuilder();
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      chain.append(cause.getMessage()).append(" | ");
    }
    return chain.toString();
  }

  @Test
  public void purgeRemovesOnlyRowsExpiredBeyondRetention() {
    String old = create(-(DocShareLinkService.PURGE_RETENTION_DAYS + 1) * 24L * HOUR_MILLIS);
    String recent = create(-HOUR_MILLIS);

    // A purge deletes a bounded batch, so drain every older row a shared database may hold.
    while (DocShareLinkService.purgeExpired(OBDal.getInstance().getSession(),
        System.currentTimeMillis()) > 0) {
      // keep purging
    }

    assertEquals(0, count(old));
    assertEquals(1, count(recent));
  }

  private static int count(String code) {
    return ((Number) OBDal.getInstance().getSession().createNativeQuery(
        "SELECT count(*) FROM etgo_doc_share_link WHERE share_code = :code")
        .setParameter("code", code).uniqueResult()).intValue();
  }

  private static int openCount(String code) {
    return ((Number) OBDal.getInstance().getSession().createNativeQuery(
        "SELECT open_count FROM etgo_doc_share_link WHERE share_code = :code")
        .setParameter("code", code).uniqueResult()).intValue();
  }

  private static Timestamp firstOpenedAt(String code) {
    List<?> rows = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT first_opened_at FROM etgo_doc_share_link WHERE share_code = :code")
        .setParameter("code", code).list();
    return (Timestamp) rows.get(0);
  }
}
