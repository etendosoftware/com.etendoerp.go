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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.NeoTenantWriteGuard;

/**
 * Unit tests for {@link DalEmailSafetyStore}.
 *
 * @covers com.etendoerp.go.schemaforge.email.DalEmailSafetyStore
 */
public class DalEmailSafetyStoreTest {

  private OBDal obDal;
  private SessionHandler sessionHandler;
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<SessionHandler> sessionHandlerMock;
  // ETGO_Email_Safety writes run in admin mode (setAdminMode/restorePreviousMode); mock the static
  // so the unit tests do not touch a real OBContext.
  private MockedStatic<OBContext> obContextMock;

  @Before
  public void setUp() {
    obDal = mock(OBDal.class);
    sessionHandler = mock(SessionHandler.class);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    sessionHandlerMock = mockStatic(SessionHandler.class);
    sessionHandlerMock.when(SessionHandler::getInstance).thenReturn(sessionHandler);
    obContextMock = mockStatic(OBContext.class);
    when(obDal.get(eq(Client.class), anyString())).thenReturn(mock(Client.class));
    when(obDal.get(eq(Organization.class), anyString())).thenReturn(mock(Organization.class));
  }

  @After
  public void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (sessionHandlerMock != null) {
      sessionHandlerMock.close();
    }
    if (obContextMock != null) {
      obContextMock.close();
    }
  }

  @Test
  public void recordAuditPersistsRedactedPayload() throws Exception {
    DalEmailSafetyStore store = new DalEmailSafetyStore(() -> 1000L, MapRecord::new);
    EmailAuditRecord.Snapshot snapshot = new EmailAuditRecord.Snapshot();
    snapshot.contractName = "reset-password";
    snapshot.idempotencyKey = "reset-password:tenant-1:record-1:v1";
    snapshot.tenantId = "tenant-1";
    snapshot.userId = "user-1";
    snapshot.recordId = "record-1";
    snapshot.template = "reset-password";
    snapshot.recipient = "person@example.com";
    snapshot.recipientDomain = "example.com";
    snapshot.httpStatus = 200;
    snapshot.status = TransactionalEmailService.STATUS_SENT;
    snapshot.message = "sent";
    snapshot.providerStatus = 202;
    snapshot.createdAtMillis = 1000L;
    EmailAuditRecord auditRecord = EmailAuditRecord.persisted(snapshot);
    // ETGO_Email_Safety is SYSTEM-level: records must be owned by client 0.
    Client systemClient = mock(Client.class);
    when(obDal.get(eq(Client.class), eq("0"))).thenReturn(systemClient);

    store.recordAudit(auditRecord);

    ArgumentCaptor<BaseOBObject> captor = ArgumentCaptor.forClass(BaseOBObject.class);
    verify(obDal).save(captor.capture());
    BaseOBObject saved = captor.getValue();
    assertEquals(systemClient, saved.get("client"));
    assertEquals("AUDIT", saved.get("recordType"));
    assertEquals("reset-password", saved.get("contractName"));
    assertEquals("reset-password", saved.get("template"));
    assertEquals("tenant-1", saved.get("tenantID"));
    assertEquals("reset-password:tenant-1:record-1:v1", saved.get("idempotencyKey"));
    assertEquals(TransactionalEmailService.STATUS_SENT, saved.get("status"));
    JSONObject payload = new JSONObject((String) saved.get("payload"));
    assertEquals("user-1", payload.getString("userId"));
    assertEquals("record-1", payload.getString("recordId"));
    assertEquals("example.com", payload.getString("recipientDomain"));
    assertTrue(payload.has("recipientHash"));
    assertFalse(payload.toString().contains("person@example.com"));
    verify(obDal).flush();
    verify(sessionHandler).commitAndStart();
  }

  @Test
  public void findSentByIdempotencyKeyRehydratesAuditRecord() throws Exception {
    @SuppressWarnings("unchecked")
    OBQuery<BaseOBObject> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(DalEmailSafetyStore.ENTITY_EMAIL_SAFETY), anyString()))
        .thenReturn(query);
    when(query.list()).thenReturn(List.of(sentAuditRecord()));

    DalEmailSafetyStore store = new DalEmailSafetyStore(() -> 1000L, MapRecord::new);
    Optional<EmailAuditRecord> result = store.findSentByIdempotencyKey(sendContext(),
        "reset-password:tenant-1:record-1:v1");

    assertTrue(result.isPresent());
    assertEquals("reset-password", result.get().getContractName());
    assertEquals("tenant-1", result.get().getTenantId());
    assertEquals("record-1", result.get().getRecordId());
    assertEquals(202, result.get().getProviderStatus().intValue());
    verify(query).setNamedParameter("recordType", "AUDIT");
    verify(query).setNamedParameter("status", TransactionalEmailService.STATUS_SENT);
    verify(query).setFilterOnReadableClients(false);
    verify(query).setFilterOnReadableOrganization(false);
    verify(query).setMaxResult(1);
  }

  @Test
  public void checkAndIncrementCreatesThrottleBucketWithDalRecord() {
    @SuppressWarnings("unchecked")
    OBQuery<BaseOBObject> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(DalEmailSafetyStore.ENTITY_EMAIL_SAFETY), anyString()))
        .thenReturn(query);
    when(query.list()).thenReturn(Collections.emptyList());

    DalEmailSafetyStore store = new DalEmailSafetyStore(() -> 1000L, MapRecord::new);
    EmailThrottleResult result = store.checkAndIncrement(sendContext(),
        List.of(EmailThrottleRule.perRecipient(1, 60)));

    assertTrue(result.isAllowed());
    ArgumentCaptor<BaseOBObject> captor = ArgumentCaptor.forClass(BaseOBObject.class);
    verify(obDal).save(captor.capture());
    BaseOBObject saved = captor.getValue();
    assertEquals("THROTTLE", saved.get("recordType"));
    assertEquals(EmailThrottleRule.SCOPE_RECIPIENT, saved.get("scope"));
    assertEquals("person@example.com", saved.get("bucketKey"));
    assertEquals(1L, saved.get("attemptCount"));
    verify(obDal).flush();
  }

  // ETP-6114: the System rows are written inside NeoTenantWriteGuard.allowingSystemRows.

  @Test
  public void checkAndIncrementWritesInsideTheSystemRowsScope() throws Exception {
    @SuppressWarnings("unchecked")
    OBQuery<BaseOBObject> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(DalEmailSafetyStore.ENTITY_EMAIL_SAFETY), anyString()))
        .thenReturn(query);
    when(query.list()).thenReturn(Collections.emptyList());
    List<Boolean> admitted = recordAdmissionOnSaveAndFlush();
    DalEmailSafetyStore store = new DalEmailSafetyStore(() -> 1000L, MapRecord::new);

    guarded(() -> store.checkAndIncrement(sendContext(),
        List.of(EmailThrottleRule.perRecipient(5, 60))));

    assertEquals(List.of(Boolean.TRUE, Boolean.TRUE), admitted);
  }

  /** The commit and anything after the call run with the scope closed again. */
  @Test
  public void recordAuditWritesInsideTheScopeAndCommitsOutsideIt() throws Exception {
    List<Boolean> admitted = recordAdmissionOnSaveAndFlush();
    doAnswer(invocation -> admitted.add(systemRowAdmitted())).when(sessionHandler)
        .commitAndStart();
    DalEmailSafetyStore store = new DalEmailSafetyStore(() -> 1000L, MapRecord::new);
    EmailAuditRecord sent = EmailAuditRecord.create(sendContext(),
        "reset-password:tenant-1:record-1:v1", 200, TransactionalEmailService.STATUS_SENT, "sent",
        202, false);

    boolean admittedAfter = guarded(() -> {
      store.recordAudit(sent);
      return systemRowAdmitted();
    });

    assertEquals(List.of(Boolean.TRUE, Boolean.TRUE, Boolean.FALSE), admitted);
    assertFalse(admittedAfter);
  }

  @Test
  public void checkAndIncrementRejectsExistingThrottleInsideWindow() {
    @SuppressWarnings("unchecked")
    OBQuery<BaseOBObject> query = mock(OBQuery.class);
    MapRecord existing = new MapRecord();
    existing.set("windowStart", new Date(1000L));
    existing.set("attemptCount", 1L);
    when(obDal.createQuery(eq(DalEmailSafetyStore.ENTITY_EMAIL_SAFETY), anyString()))
        .thenReturn(query);
    when(query.list()).thenReturn(List.of(existing));

    DalEmailSafetyStore store = new DalEmailSafetyStore(() -> 1500L, MapRecord::new);
    EmailThrottleResult result = store.checkAndIncrement(sendContext(),
        List.of(EmailThrottleRule.perRecipient(1, 60)));

    assertFalse(result.isAllowed());
    assertEquals(EmailThrottleRule.SCOPE_RECIPIENT, result.getScope());
    verify(obDal, never()).save(existing);
  }

  @Test
  public void checkKillSwitchSuppressesGlobalRecordWithReason() {
    @SuppressWarnings("unchecked")
    OBQuery<BaseOBObject> query = mock(OBQuery.class);
    MapRecord killSwitch = new MapRecord();
    killSwitch.set("payload", "{\"reason\":\"maintenance\"}");
    when(obDal.createQuery(eq(DalEmailSafetyStore.ENTITY_EMAIL_SAFETY), anyString()))
        .thenReturn(query);
    when(query.list()).thenReturn(List.of(killSwitch));

    DalEmailSafetyStore store = new DalEmailSafetyStore(() -> 1000L, MapRecord::new);
    EmailKillSwitchResult result = store.checkKillSwitch(sendContext());

    assertFalse(result.isAllowed());
    assertEquals(EmailThrottleRule.SCOPE_GLOBAL, result.getScope());
    assertEquals("maintenance", result.getMessage());
  }

  @Test
  public void isRecipientSuppressedMatchesAddressHashAndDomain() {
    @SuppressWarnings("unchecked")
    OBQuery<BaseOBObject> query = mock(OBQuery.class);
    when(obDal.createQuery(eq(DalEmailSafetyStore.ENTITY_EMAIL_SAFETY), anyString()))
        .thenReturn(query);
    MapRecord suppression = new MapRecord();
    when(query.list()).thenReturn(List.of(suppression));

    DalEmailSafetyStore store = new DalEmailSafetyStore(() -> 1000L, MapRecord::new);

    assertTrue(store.isRecipientSuppressed("tenant-1", " Person@Example.COM "));
    ArgumentCaptor<String> scopeCaptor = ArgumentCaptor.forClass(String.class);
    verify(query).setNamedParameter(eq("scope"), scopeCaptor.capture());
    assertEquals(DalEmailSafetyStore.SCOPE_ADDRESS, scopeCaptor.getValue());
  }

  @Test
  public void isRecipientSuppressedReturnsFalseForBlankAddress() {
    DalEmailSafetyStore store = new DalEmailSafetyStore(() -> 1000L, MapRecord::new);

    assertFalse(store.isRecipientSuppressed("tenant-1", " "));
  }

  /** Records, on every save and flush, whether the real guard admits a System row right then. */
  private List<Boolean> recordAdmissionOnSaveAndFlush() {
    List<Boolean> admitted = new ArrayList<>();
    doAnswer(invocation -> admitted.add(systemRowAdmitted())).when(obDal)
        .save(any(BaseOBObject.class));
    doAnswer(invocation -> admitted.add(systemRowAdmitted())).when(obDal).flush();
    return admitted;
  }

  /** Fires a client-0 insert at the real guard, as Hibernate does on save. */
  private static boolean systemRowAdmitted() {
    Client system = mock(Client.class);
    when(system.getId()).thenReturn("0");
    EntityNewEvent event = mock(EntityNewEvent.class);
    when(event.getTargetInstance()).thenReturn(system);
    try {
      new NeoTenantWriteGuard().onSave(event);
      return true;
    } catch (OBSecurityException e) {
      return false;
    }
  }

  /** Runs {@code body} with the guard active; its closing flush is not counted as a store write. */
  private <T> T guarded(Callable<T> body) throws Exception {
    return NeoTenantWriteGuard.call(() -> {
      T result = body.call();
      doNothing().when(obDal).flush();
      return result;
    });
  }

  private static BaseOBObject sentAuditRecord() throws Exception {
    MapRecord auditEntry = new MapRecord();
    auditEntry.set("contractName", "reset-password");
    auditEntry.set("idempotencyKey", "reset-password:tenant-1:record-1:v1");
    auditEntry.set("tenantID", "tenant-1");
    auditEntry.set("template", "reset-password");
    auditEntry.set("status", TransactionalEmailService.STATUS_SENT);
    auditEntry.set("auditTime", new Date(1000L));
    JSONObject payload = new JSONObject();
    payload.put("userId", "user-1");
    payload.put("recordId", "record-1");
    payload.put("recipientDomain", "example.com");
    payload.put("httpStatus", 200);
    payload.put("message", "sent");
    payload.put("providerStatus", 202);
    payload.put("duplicate", false);
    auditEntry.set("payload", payload.toString());
    return auditEntry;
  }

  private static EmailSendContext sendContext() {
    try {
      JSONObject body = new JSONObject();
      body.put(EmailContractCommandSupport.FIELD_TENANT_ID, "tenant-1");
      body.put(EmailContractCommandSupport.FIELD_RECORD_ID, "record-1");
      EmailContractCommand command = new EmailContractCommand("reset-password", body);
      EmailRecipientResolution recipient = EmailRecipientResolution.serverResolved(
          "person@example.com");
      EmailProviderRequest request = new EmailProviderRequest("person@example.com",
          "reset-password", new JSONObject(), null);
      return new EmailSendContext(command, recipient, request);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class MapRecord extends BaseOBObject {
    private static final long serialVersionUID = 1L;
    private final Map<String, Object> values = new HashMap<>();

    @Override
    public String getEntityName() {
      return DalEmailSafetyStore.ENTITY_EMAIL_SAFETY;
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
