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
package com.etendoerp.go.schemaforge.webhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.access.User;

/**
 * Unit tests for {@link SFDocumentEmailHistory} (ETP-5069).
 *
 * <p>Pins the JSON row shape the frontend's Emails card consumes. The two halves live in
 * different repositories, so this class is the only place the contract can be enforced: every
 * field name asserted here is read by
 * {@code tools/app-shell/src/windows/custom/shared/preview-cards/EmailsCard.jsx} in
 * {@code etendo_schema_forge}.</p>
 */
class SFDocumentEmailHistoryTest {

  private static final String RECORD_ID = "doc-1";
  private static final String ENTITY = "ETGO_Email_Send_Log";

  private OBDal obDal;
  private MockedStatic<OBDal> obDalMock;
  private OBQuery<BaseOBObject> query;
  private SFDocumentEmailHistory webhook;
  private Map<String, String> parameters;
  private Map<String, String> responseVars;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    obDal = mock(OBDal.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    query = mock(OBQuery.class);
    when(obDal.createQuery(eq(ENTITY), anyString())).thenReturn(query);
    when(query.list()).thenReturn(Collections.emptyList());
    webhook = new SFDocumentEmailHistory();
    parameters = new HashMap<>();
    responseVars = new HashMap<>();
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
  }

  // ── input guards ───────────────────────────────────────────────────────────

  @Test
  void aMissingRecordIdAnswersWithAnErrorInsteadOfCrashing() {
    webhook.get(parameters, responseVars);

    assertEquals("recordId is required", responseVars.get("error"));
    assertFalse(responseVars.containsKey("result"));
    verifyNoInteractions(obDal);
  }

  @Test
  void aBlankRecordIdAnswersWithAnErrorInsteadOfCrashing() {
    parameters.put("recordId", "   ");

    webhook.get(parameters, responseVars);

    assertEquals("recordId is required", responseVars.get("error"));
    assertFalse(responseVars.containsKey("result"));
    verifyNoInteractions(obDal);
  }

  @Test
  void aReadFailureAnswersWithAnErrorInsteadOfPropagating() {
    when(obDal.createQuery(eq(ENTITY), anyString()))
        .thenThrow(new IllegalStateException("session is closed"));
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    assertEquals("session is closed", responseVars.get("error"));
    assertFalse(responseVars.containsKey("result"));
  }

  // ── query shape ────────────────────────────────────────────────────────────

  @Test
  void readsTheDocumentsHistoryNewestFirstAndCapped() {
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    ArgumentCaptor<String> where = ArgumentCaptor.forClass(String.class);
    verify(obDal).createQuery(eq(ENTITY), where.capture());
    assertTrue(where.getValue().contains("h.recordID = :recordId"),
        "expected a recordId filter, got: " + where.getValue());
    assertTrue(where.getValue().contains("order by h.sentAt desc"),
        "history must come back newest first, got: " + where.getValue());
    verify(query).setNamedParameter("recordId", RECORD_ID);
    verify(query).setMaxResult(200);
  }

  @Test
  void scopesTheLookupToOneWindowWhenASpecNameIsGiven() {
    parameters.put("recordId", RECORD_ID);
    parameters.put("specName", "sales-invoice");

    webhook.get(parameters, responseVars);

    ArgumentCaptor<String> where = ArgumentCaptor.forClass(String.class);
    verify(obDal).createQuery(eq(ENTITY), where.capture());
    assertTrue(where.getValue().contains("h.specName = :specName"),
        "expected a specName filter, got: " + where.getValue());
    verify(query).setNamedParameter("specName", "sales-invoice");
  }

  @Test
  void doesNotFilterBySpecNameWhenNoneIsGiven() {
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    ArgumentCaptor<String> where = ArgumentCaptor.forClass(String.class);
    verify(obDal).createQuery(eq(ENTITY), where.capture());
    assertFalse(where.getValue().contains("specName"));
    verify(query, never()).setNamedParameter(eq("specName"), anyString());
  }

  @Test
  void keepsDalsOwnReadableClientAndOrganizationFiltering() {
    // The access rule for this endpoint IS OBQuery's default filtering over a client-level table.
    // Turning either off (or entering admin mode) would be the security regression, so the
    // endpoint must never call these.
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    verify(query, never()).setFilterOnReadableClients(false);
    verify(query, never()).setFilterOnReadableOrganization(false);
  }

  @Test
  void answersWithAnEmptyArrayWhenTheDocumentHasNoHistory() throws Exception {
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    assertNull(responseVars.get("error"));
    assertEquals(0, new JSONArray(responseVars.get("result")).length());
  }

  // ── row shape consumed by the frontend ─────────────────────────────────────

  @Test
  void emitsEveryFieldTheEmailsCardReads() throws Exception {
    // sentRow() must be materialized before the when(...) call: it opens its own nested
    // when(sender.getName())...thenReturn(...) internally, and Mockito cannot have two unfinished
    // stubbing chains open at once (UnfinishedStubbingException).
    MapRecord entry = sentRow();
    when(query.list()).thenReturn(Collections.singletonList(entry));
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    JSONObject row = singleRow();
    assertEquals("row-1", row.getString("id"));
    assertEquals("2026-08-20T09:31:00Z", row.getString("sentAt"));
    assertEquals("SENT", row.getString("status"));
    assertEquals("Su factura INV/0001", row.getString("subject"));
    assertEquals("Buenas tardes, aqui va la factura.", row.getString("messageBody"));
    assertEquals("https://example.test/download/doc-1", row.getString("downloadLink"));
    assertEquals("sales-invoice-send", row.getString("contractName"));
    assertEquals("sales-invoice", row.getString("specName"));
    assertTrue(row.isNull("errorMessage"));
    // The sender travels as sentBy, resolved from CreatedBy.
    assertEquals("Irina Urricelqui", row.getString("sentBy"));
  }

  @Test
  void emitsRecipientsAsJsonArraysRatherThanADelimitedString() throws Exception {
    // See emitsEveryFieldTheEmailsCardReads: sentRow() must be materialized before when(...).
    MapRecord entry = sentRow();
    when(query.list()).thenReturn(Collections.singletonList(entry));
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    JSONObject row = singleRow();
    JSONArray to = row.getJSONArray("recipientsTo");
    assertEquals(2, to.length());
    assertEquals("customer@example.com", to.getString(0));
    assertEquals("billing@example.com", to.getString(1));
    JSONArray cc = row.getJSONArray("recipientsCc");
    assertEquals(1, cc.length());
    assertEquals("pm@example.com", cc.getString(0));
  }

  @Test
  void splitsRecipientsOnEitherStoredSeparator() throws Exception {
    MapRecord row = sentRow();
    row.set("recipientsTo", "a@example.com; b@example.com , c@example.com");
    when(query.list()).thenReturn(Collections.singletonList(row));
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    JSONArray to = singleRow().getJSONArray("recipientsTo");
    assertEquals(Arrays.asList("a@example.com", "b@example.com", "c@example.com"),
        Arrays.asList(to.getString(0), to.getString(1), to.getString(2)));
  }

  @Test
  void emitsAnEmptyArrayForARowWithoutCcRecipients() throws Exception {
    MapRecord row = sentRow();
    row.set("recipientsCC", null);
    when(query.list()).thenReturn(Collections.singletonList(row));
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    assertEquals(0, singleRow().getJSONArray("recipientsCc").length());
  }

  @Test
  void reportsAFailedAttemptAsFailedWithItsOwnMessage() throws Exception {
    MapRecord row = sentRow();
    row.set("status", "PROVIDER_FAILED");
    row.set("errorMessage", "Transactional email provider rejected the request");
    when(query.list()).thenReturn(Collections.singletonList(row));
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    JSONObject json = singleRow();
    assertEquals("PROVIDER_FAILED", json.getString("status"));
    assertEquals("Transactional email provider rejected the request",
        json.getString("errorMessage"));
  }

  @Test
  void reportsNullsRatherThanBlanksForTheOptionalColumns() throws Exception {
    // A default-copy send: no operator message, no download link, no subject.
    MapRecord row = sentRow();
    row.set("subject", "   ");
    row.set("messageBody", null);
    row.set("downloadLink", null);
    when(query.list()).thenReturn(Collections.singletonList(row));
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    JSONObject json = singleRow();
    assertTrue(json.isNull("subject"));
    assertTrue(json.isNull("messageBody"));
    assertTrue(json.isNull("downloadLink"));
  }

  @Test
  void keepsTheOrderTheQueryReturnedRowsIn() throws Exception {
    MapRecord newest = sentRow();
    newest.set("id", "row-newest");
    MapRecord oldest = sentRow();
    oldest.set("id", "row-oldest");
    oldest.set("sentAt", instant("2026-08-19T09:31:00Z"));
    when(query.list()).thenReturn(Arrays.<BaseOBObject>asList(newest, oldest));
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    JSONArray history = new JSONArray(responseVars.get("result"));
    assertEquals(2, history.length());
    assertEquals("row-newest", history.getJSONObject(0).getString("id"));
    assertEquals("row-oldest", history.getJSONObject(1).getString("id"));
    assertEquals("2026-08-19T09:31:00Z", history.getJSONObject(1).getString("sentAt"));
  }

  // ── sender resolution ──────────────────────────────────────────────────────

  @Test
  void fallsBackToTheUsernameWhenTheSenderHasNoDisplayName() throws Exception {
    User sender = mock(User.class);
    when(sender.getName()).thenReturn("  ");
    when(sender.getUsername()).thenReturn("irina");
    MapRecord row = sentRow();
    row.set("createdBy", sender);
    when(query.list()).thenReturn(Collections.singletonList(row));
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    assertEquals("irina", singleRow().getString("sentBy"));
  }

  @Test
  void reportsNoSenderWhenCreatedByCannotBeResolved() throws Exception {
    MapRecord row = sentRow();
    row.set("createdBy", null);
    when(query.list()).thenReturn(Collections.singletonList(row));
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    // Best effort: an unreadable user costs the "Sent by" line, never the whole history.
    assertTrue(singleRow().isNull("sentBy"));
  }

  @Test
  void reportsNoSentAtWhenTheColumnIsEmpty() throws Exception {
    MapRecord row = sentRow();
    row.set("sentAt", null);
    when(query.list()).thenReturn(Collections.singletonList(row));
    parameters.put("recordId", RECORD_ID);

    webhook.get(parameters, responseVars);

    assertTrue(singleRow().isNull("sentAt"));
  }

  private JSONObject singleRow() throws Exception {
    assertNull(responseVars.get("error"));
    String result = responseVars.get("result");
    assertNotNull(result, "the endpoint must answer with a result string");
    JSONArray history = new JSONArray(result);
    assertEquals(1, history.length());
    return history.getJSONObject(0);
  }

  private static MapRecord sentRow() {
    User sender = mock(User.class);
    when(sender.getName()).thenReturn("Irina Urricelqui");
    MapRecord row = new MapRecord();
    row.set("id", "row-1");
    row.set("sentAt", instant("2026-08-20T09:31:00Z"));
    row.set("status", "SENT");
    row.set("recipientsTo", "customer@example.com, billing@example.com");
    row.set("recipientsCC", "pm@example.com");
    row.set("subject", "Su factura INV/0001");
    row.set("messageBody", "Buenas tardes, aqui va la factura.");
    row.set("downloadLink", "https://example.test/download/doc-1");
    row.set("contractName", "sales-invoice-send");
    row.set("specName", "sales-invoice");
    row.set("errorMessage", null);
    row.set("createdBy", sender);
    return row;
  }

  private static Date instant(String isoInstant) {
    return Date.from(java.time.Instant.parse(isoInstant));
  }

  /** Map-backed stand-in for the generated {@code ETGO_Email_Send_Log} entity. */
  private static final class MapRecord extends BaseOBObject {
    private static final long serialVersionUID = 1L;
    private final Map<String, Object> values = new HashMap<>();

    @Override
    public String getEntityName() {
      return ENTITY;
    }

    @Override
    public Object getId() {
      return values.get("id");
    }

    @Override
    public Object get(String propName) {
      return values.get(propName);
    }

    @Override
    public void set(String propName, Object value) {
      values.put(propName, value);
    }
  }
}
