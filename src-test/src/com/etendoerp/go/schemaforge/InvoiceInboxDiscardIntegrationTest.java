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
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.codehaus.jettison.json.JSONObject;
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

import com.etendoerp.go.schemaforge.data.IncomingDocument;

/**
 * Real-database specs for {@link InvoiceInboxDiscard}: the state each source status moves to, what
 * a discard leaves untouched, idempotence, the refusals, and the conditional {@code UPDATE}.
 *
 * <p>Everything runs in the test's own transaction: rows are flushed, never committed, and the
 * rollback in {@link #cleanUp()} removes them. That is also the limit of this class: the
 * production code relies on the request-end commit, which a single-transaction test cannot prove.
 * The race between the read and the {@code UPDATE} is covered with mocks in
 * {@code InvoiceInboxDiscardTest}.</p>
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxDiscard
 */
public class InvoiceInboxDiscardIntegrationTest extends WeldBaseTest {

  private static final String CLIENT_ID = TestConstants.Clients.FB_GRP;
  private static final String ORG_ID = TestConstants.Orgs.ESP;
  private static final String STAR_ORG = "0";
  private static final String ID_PARAM = "id";
  private static final String BY_ID = " FROM etgo_incoming_document WHERE etgo_incoming_document_id = :id";

  private final String marker = "etgo-discard-it-" + UUID.randomUUID().toString().substring(0, 8) + "-";
  private int counter;

  @Before
  public void prepareContext() {
    OBContext.clearAdminModeStack();
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN, CLIENT_ID,
        ORG_ID);
  }

  @After
  public void cleanUp() {
    OBDal.getInstance().rollbackAndClose();
    OBContext.setOBContext((OBContext) null);
  }

  // ---------------------------------------------------------------- discarding

  @Test
  public void aDocumentPendingReviewIsDiscarded() throws Exception {
    assertDiscardedFrom("PENDING_REVIEW");
  }

  @Test
  public void aDocumentInErrorIsDiscarded() throws Exception {
    assertDiscardedFrom("ERROR");
  }

  @Test
  public void aReadyDocumentIsDiscarded() throws Exception {
    assertDiscardedFrom("READY");
  }

  private void assertDiscardedFrom(String status) throws Exception {
    String id = row(status, ORG_ID);

    NeoResponse response = InvoiceInboxDiscard.discard(id);

    assertEquals(200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals(id, body.getString("id"));
    assertEquals("DISCARDED", body.getString("status"));
    assertFalse(body.getBoolean("alreadyDiscarded"));
    assertFalse(body.getString("discardedAt").isEmpty());
    assertEquals("DISCARDED", text("SELECT status" + BY_ID, id));
    assertNotNull(text("SELECT discarded_at" + BY_ID, id));
  }

  @Test
  public void discardingChangesOnlyTheStatusAndTheDiscardTimestamp() {
    String id = row("ERROR", ORG_ID);
    update("UPDATE etgo_incoming_document SET error_code = 'SERVICE_FAILURE', reviewed = 'Y',"
        + " adjusted = 'Y', extracted_data = '{\"a\":1}', current_data = '{\"b\":2}'"
        + " WHERE etgo_incoming_document_id = :id", id);
    String before = fingerprint(id);

    NeoResponse response = InvoiceInboxDiscard.discard(id);

    assertEquals(200, response.getHttpStatus());
    assertEquals("SERVICE_FAILURE", text("SELECT error_code" + BY_ID, id));
    assertEquals("Y", text("SELECT reviewed" + BY_ID, id));
    assertEquals("Y", text("SELECT adjusted" + BY_ID, id));
    assertNull(text("SELECT c_invoice_id" + BY_ID, id));
    assertTrue(text("SELECT extracted_data" + BY_ID, id).contains("\"a\""));
    assertEquals(before, fingerprint(id));
  }

  @Test
  public void discardingAgainIsIdempotentAndKeepsTheFirstTimestamp() throws Exception {
    String id = row("PENDING_REVIEW", ORG_ID);
    String first = InvoiceInboxDiscard.discard(id).getBody().getString("discardedAt");
    String stored = text("SELECT CAST(discarded_at AS text)" + BY_ID, id);

    NeoResponse again = InvoiceInboxDiscard.discard(id);

    assertEquals(200, again.getHttpStatus());
    assertTrue(again.getBody().getBoolean("alreadyDiscarded"));
    assertEquals("DISCARDED", again.getBody().getString("status"));
    assertEquals(first, again.getBody().getString("discardedAt"));
    assertEquals(stored, text("SELECT CAST(discarded_at AS text)" + BY_ID, id));
  }

  // ---------------------------------------------------------------- refusals

  @Test
  public void aProcessingDocumentIsNotDiscardableAndStaysIntact() throws Exception {
    assertRefusedAndIntact("PROCESSING");
  }

  @Test
  public void aConfirmedDocumentIsNotDiscardableAndStaysIntact() throws Exception {
    assertRefusedAndIntact("CONFIRMED");
  }

  private void assertRefusedAndIntact(String status) throws Exception {
    String id = row(status, ORG_ID);

    NeoResponse response = InvoiceInboxDiscard.discard(id);

    assertEquals(409, response.getHttpStatus());
    JSONObject error = response.getBody().getJSONObject("error");
    assertEquals("not_discardable", error.getString("code"));
    assertTrue(error.getString("message"), error.getString("message").contains(status));
    assertEquals(status, text("SELECT status" + BY_ID, id));
    assertNull(text("SELECT discarded_at" + BY_ID, id));
  }

  @Test
  public void anUnknownIdIs404NotFound() throws Exception {
    NeoResponse response = InvoiceInboxDiscard.discard("NO-SUCH-ROW");

    assertEquals(404, response.getHttpStatus());
    assertEquals("not_found", response.getBody().getJSONObject("error").getString("code"));
  }

  @Test
  public void anOrganizationTheRoleCannotWriteIs403AndTheRowIsUnchanged() throws Exception {
    // The star organization is never writable for a discard.
    String id = row("PENDING_REVIEW", STAR_ORG);

    NeoResponse response = InvoiceInboxDiscard.discard(id);

    assertEquals(403, response.getHttpStatus());
    assertEquals("org_not_allowed", response.getBody().getJSONObject("error").getString("code"));
    assertEquals("PENDING_REVIEW", text("SELECT status" + BY_ID, id));
    assertNull(text("SELECT discarded_at" + BY_ID, id));
  }

  // ---------------------------------------------------------------- the worker

  @Test
  public void aProcessingRowIsNeverDiscardedSoTheWorkerKeepsItsRow() {
    String id = row("PROCESSING", ORG_ID);

    InvoiceInboxDiscard.discard(id);

    assertEquals("PROCESSING", text("SELECT status" + BY_ID, id));
  }

  @Test
  public void aWorkerWriteGuardedByProcessingDoesNotTouchADiscardedRow() {
    String id = row("PENDING_REVIEW", ORG_ID);
    InvoiceInboxDiscard.discard(id);

    // The predicate the worker's terminal writes carry (InvoiceInboxWorkStore).
    int moved = update("UPDATE etgo_incoming_document SET status = 'ERROR', error_code = 'X'"
        + " WHERE etgo_incoming_document_id = :id AND status = 'PROCESSING'", id);

    assertEquals(0, moved);
    assertEquals("DISCARDED", text("SELECT status" + BY_ID, id));
    assertNull(text("SELECT error_code" + BY_ID, id));
  }

  // ---------------------------------------------------------------- helpers

  /** A flushed (uncommitted) row in the F&amp;B client, in the given status and organization. */
  private String row(String status, String orgId) {
    OBContext.setAdminMode(false);
    try {
      IncomingDocument row = OBProvider.getInstance().get(IncomingDocument.class);
      row.setClient(OBDal.getInstance().get(Client.class, CLIENT_ID));
      row.setOrganization(OBDal.getInstance().get(Organization.class, orgId));
      row.setActive(true);
      row.setFileName(marker + (++counter) + ".pdf");
      row.setSource("UPLOAD");
      row.setTargetType("PURCHASE_INVOICE");
      row.setStatus(status);
      row.setAdjusted(false);
      row.setReviewed(false);
      row.setProcessingAttempts(0L);
      OBDal.getInstance().save(row);
      OBDal.getInstance().flush();
      return row.getId();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static int update(String sql, String id) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(ID_PARAM, id);
    return query.executeUpdate();
  }

  private static String text(String sql, String id) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(ID_PARAM, id);
    Object value = query.uniqueResult();
    return value == null ? null : String.valueOf(value);
  }

  /** Everything a discard must not change. */
  private static String fingerprint(String id) {
    return text("SELECT concat_ws('|', error_code, reviewed, adjusted, c_invoice_id,"
        + " extracted_data, current_data, file_name, processing_attempts)" + BY_ID, id);
  }
}
