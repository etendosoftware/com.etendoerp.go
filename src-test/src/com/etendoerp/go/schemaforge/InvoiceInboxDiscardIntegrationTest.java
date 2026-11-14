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

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.TestConstants;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;

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
 * <p>ETP-6050: the same class also pins how a discard interacts with the review panel's writing
 * actions on a real row: an edit after a discard, a stale token, the token that moves with each
 * save. The window ACL is granted by a mock (it is not what is under test); the row, the lock
 * query, the token and the DAL are real. A truly concurrent discard needs two connections, which
 * a single test transaction cannot give: the {@code SELECT ... FOR UPDATE} is asserted at the SQL
 * level in {@code InvoiceInboxEditGateTest} and the second judgement of the state under the lock
 * there too.</p>
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxDiscard
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxEditGate
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxApply
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxMarkReviewed
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

  // ---------------------------------------------------------------- the review panel's writes (ETP-6050)

  /** Grants the window write access to the test role: the ACL is not what these specs are about. */
  private static MockedStatic<NeoAccessHelper> grantWindowAccess() {
    return Mockito.mockStatic(NeoAccessHelper.class, inv -> {
      if ("hasWindowAccess".equals(inv.getMethod().getName()) && inv.getArguments().length == 2) {
        return true;
      }
      return inv.callRealMethod();
    });
  }

  private static JSONObject body(String token, String... operations) throws Exception {
    JSONArray array = new JSONArray();
    for (String operation : operations) {
      array.put(new JSONObject(operation));
    }
    return new JSONObject().put("updated", token).put("operations", array);
  }

  private static String tokenOf(String id) {
    OBDal.getInstance().refresh(OBDal.getInstance().get(IncomingDocument.class, id));
    return NeoDateFormat.toAuditToken(
        OBDal.getInstance().get(IncomingDocument.class, id).getUpdated());
  }

  @Test
  public void anEditAfterADiscardIsNotEditableAndLeavesTheRowDiscarded() throws Exception {
    String id = row("PENDING_REVIEW", ORG_ID);
    String token = tokenOf(id);
    InvoiceInboxDiscard.discard(id);

    try (MockedStatic<NeoAccessHelper> access = grantWindowAccess()) {
      NeoResponse edit = InvoiceInboxApply.apply(id,
          body(token, "{op:'setHeader',documentNo:'FC-9'}"));
      NeoResponse mark = InvoiceInboxMarkReviewed.markReviewed(id,
          new JSONObject().put("updated", token));

      for (NeoResponse response : new NeoResponse[] { edit, mark }) {
        assertEquals(409, response.getHttpStatus());
        JSONObject error = response.getBody().getJSONObject("error");
        assertEquals("not_editable", error.getString("code"));
        assertEquals("DISCARDED", error.getString("documentStatus"));
      }
    }
    assertEquals("DISCARDED", text("SELECT status" + BY_ID, id));
    assertNull(text("SELECT current_data" + BY_ID, id));
    assertEquals("N", text("SELECT adjusted" + BY_ID, id));
  }

  @Test
  public void aStaleOrMalformedTokenIs409AndWritesNothing() throws Exception {
    String id = row("PENDING_REVIEW", ORG_ID);

    try (MockedStatic<NeoAccessHelper> access = grantWindowAccess()) {
      for (String token : new String[] { "2020-01-01T00:00:00.000+00:00", "yesterday" }) {
        NeoResponse response = InvoiceInboxApply.apply(id,
            body(token, "{op:'setHeader',documentNo:'FC-9'}"));

        assertEquals(token, 409, response.getHttpStatus());
        assertEquals("stale_document",
            response.getBody().getJSONObject("error").getString("code"));
      }
    }
    assertNull(text("SELECT current_data" + BY_ID, id));
    assertEquals("PENDING_REVIEW", text("SELECT status" + BY_ID, id));
  }

  @Test
  public void anEditWithTheCurrentTokenPersistsTheSkeletonMarksAdjustedAndMovesTheToken()
      throws Exception {
    String id = row("PENDING_REVIEW", ORG_ID);
    String token = tokenOf(id);

    try (MockedStatic<NeoAccessHelper> access = grantWindowAccess()) {
      NeoResponse response = InvoiceInboxApply.apply(id,
          body(token, "{op:'setHeader',documentNo:'FC-9'}"));

      assertEquals(response.getBody().toString(), 200, response.getHttpStatus());
      JSONObject answer = response.getBody();
      assertTrue(answer.getBoolean("adjusted"));
      assertEquals("ERROR", answer.getString("status"));
      assertEquals("MISSING_FIELDS", answer.getString("errorCode"));
      assertFalse("the answer carries the NEW token", token.equals(answer.getString("updated")));
      assertEquals(answer.getString("updated"), tokenOf(id));
    }
    assertEquals("FC-9", new JSONObject(text("SELECT current_data" + BY_ID, id))
        .getJSONObject("header").getString("documentNo"));
    assertEquals("FC-9", text("SELECT poreference" + BY_ID, id));
    assertEquals("Y", text("SELECT adjusted" + BY_ID, id));
    assertNull(text("SELECT extracted_data" + BY_ID, id));
  }

  @Test
  public void theTokenOfAnAnswerIsTheOneTheNextRequestNeeds() throws Exception {
    String id = row("PENDING_REVIEW", ORG_ID);

    try (MockedStatic<NeoAccessHelper> access = grantWindowAccess()) {
      NeoResponse first = InvoiceInboxApply.apply(id,
          body(tokenOf(id), "{op:'setHeader',documentNo:'FC-1'}"));
      String next = first.getBody().getString("updated");

      NeoResponse second = InvoiceInboxApply.apply(id,
          body(next, "{op:'setHeader',documentNo:'FC-2'}"));
      NeoResponse replay = InvoiceInboxApply.apply(id,
          body(next, "{op:'setHeader',documentNo:'FC-3'}"));

      assertEquals(200, second.getHttpStatus());
      assertEquals("the first token is spent", 409, replay.getHttpStatus());
    }
    assertEquals("FC-2", text("SELECT poreference" + BY_ID, id));
  }

  @Test
  public void aNoOpEditWritesNothingAndTheTokenDoesNotMove() throws Exception {
    String id = row("PENDING_REVIEW", ORG_ID);
    String token = tokenOf(id);

    try (MockedStatic<NeoAccessHelper> access = grantWindowAccess()) {
      NeoResponse response = InvoiceInboxApply.apply(id,
          body(token, "{op:'setHeader',documentNo:''}"));

      assertEquals(200, response.getHttpStatus());
      assertEquals(token, response.getBody().getString("updated"));
    }
    assertNull(text("SELECT current_data" + BY_ID, id));
    assertEquals("N", text("SELECT adjusted" + BY_ID, id));
  }

  @Test
  public void markReviewedOfAnIncompleteDocumentIs422AndTheRowIsUnchanged() throws Exception {
    String id = row("PENDING_REVIEW", ORG_ID);

    try (MockedStatic<NeoAccessHelper> access = grantWindowAccess()) {
      NeoResponse response = InvoiceInboxMarkReviewed.markReviewed(id,
          new JSONObject().put("updated", tokenOf(id)));

      assertEquals(422, response.getHttpStatus());
      JSONObject error = response.getBody().getJSONObject("error");
      assertEquals("not_valid", error.getString("code"));
      assertTrue(error.getJSONArray("missing").toString().contains("vendor"));
    }
    assertEquals("PENDING_REVIEW", text("SELECT status" + BY_ID, id));
    assertEquals("N", text("SELECT reviewed" + BY_ID, id));
  }

  @Test
  public void markReviewedOfAReadyRowIsIdempotentAndAnErrorRowIsNotReviewable() throws Exception {
    String ready = row("READY", ORG_ID);
    String error = row("ERROR", ORG_ID);

    try (MockedStatic<NeoAccessHelper> access = grantWindowAccess()) {
      NeoResponse again = InvoiceInboxMarkReviewed.markReviewed(ready,
          new JSONObject().put("updated", tokenOf(ready)));
      NeoResponse refused = InvoiceInboxMarkReviewed.markReviewed(error,
          new JSONObject().put("updated", tokenOf(error)));

      assertEquals(200, again.getHttpStatus());
      assertEquals("READY", again.getBody().getString("status"));
      assertEquals(409, refused.getHttpStatus());
      assertEquals("not_reviewable",
          refused.getBody().getJSONObject("error").getString("code"));
    }
  }

  @Test
  public void aRowOfAnotherOrganizationTheRoleCannotWriteIsRefusedByTheEditToo() throws Exception {
    String id = row("PENDING_REVIEW", STAR_ORG);

    try (MockedStatic<NeoAccessHelper> access = grantWindowAccess()) {
      NeoResponse response = InvoiceInboxApply.apply(id,
          body(tokenOf(id), "{op:'setHeader',documentNo:'FC-9'}"));

      assertEquals(403, response.getHttpStatus());
      assertEquals("org_not_allowed",
          response.getBody().getJSONObject("error").getString("code"));
    }
    assertNull(text("SELECT current_data" + BY_ID, id));
  }

  @Test
  public void anUnknownRowAndAProcessingRowAreRefusedByTheEdit() throws Exception {
    String processing = row("PROCESSING", ORG_ID);

    try (MockedStatic<NeoAccessHelper> access = grantWindowAccess()) {
      NeoResponse missing = InvoiceInboxApply.apply("NO-SUCH-ROW",
          body("2020-01-01T00:00:00.000+00:00", "{op:'addLine'}"));
      NeoResponse busy = InvoiceInboxApply.apply(processing,
          body(tokenOf(processing), "{op:'addLine'}"));

      assertEquals(404, missing.getHttpStatus());
      assertEquals(409, busy.getHttpStatus());
      assertEquals("processing", busy.getBody().getJSONObject("error").getString("code"));
    }
  }

  @Test
  public void withoutWriteAccessToTheWindowTheEditIs403() throws Exception {
    String id = row("PENDING_REVIEW", ORG_ID);

    try (MockedStatic<NeoAccessHelper> access = Mockito.mockStatic(NeoAccessHelper.class,
        inv -> "hasWindowAccess".equals(inv.getMethod().getName()) ? Boolean.FALSE
            : inv.callRealMethod())) {
      NeoResponse response = InvoiceInboxApply.apply(id,
          body(tokenOf(id), "{op:'addLine'}"));

      assertEquals(403, response.getHttpStatus());
      assertEquals("access_denied", response.getBody().getJSONObject("error").getString("code"));
    }
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
