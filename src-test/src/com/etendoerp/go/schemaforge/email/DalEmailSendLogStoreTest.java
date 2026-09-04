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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link DalEmailSendLogStore} (ETP-5069), the readable per-document email send
 * history.
 *
 * <p>Mirrors {@code DalEmailSafetyStoreTest}'s DB-free approach — static mocks of
 * {@code OBDal}/{@code OBContext} plus a map-backed {@link BaseOBObject} fake injected through the
 * package-private constructor — and deliberately asserts the OPPOSITE privacy property: where the
 * anti-abuse ledger must never persist a raw address, this table must persist recipients, subject
 * and the operator's own message in clear, or the history panel has nothing to show.</p>
 */
public class DalEmailSendLogStoreTest {

  private static final String CUSTOMER_ADDRESS = "customer@example.com";
  private static final String BILLING_ADDRESS = "billing@example.com";
  private static final String PROJECT_MANAGER_ADDRESS = "pm@example.com";
  private static final String SUBJECT = "Su factura INV/0001";
  private static final String OPERATOR_MESSAGE = "Buenas tardes, aqui va la factura.";
  private static final String DOWNLOAD_LINK = "https://example.test/download/doc-1";
  private static final String CONTRACT_NAME = "sales-invoice-send";
  private static final String SPEC_NAME = "sales-invoice";
  private static final String RECORD_ID = "doc-1";

  private OBDal obDal;
  private OBContext obContext;
  private Client currentClient;
  private Organization currentOrganization;
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<SessionHandler> sessionHandlerMock;
  private SessionHandler sessionHandler;

  @Before
  public void setUp() {
    obDal = mock(OBDal.class);
    obContext = mock(OBContext.class);
    currentClient = mock(Client.class);
    currentOrganization = mock(Organization.class);
    sessionHandler = mock(SessionHandler.class);

    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    obContextMock = mockStatic(OBContext.class);
    obContextMock.when(OBContext::getOBContext).thenReturn(obContext);
    sessionHandlerMock = mockStatic(SessionHandler.class);
    sessionHandlerMock.when(SessionHandler::getInstance).thenReturn(sessionHandler);

    when(obContext.getCurrentClient()).thenReturn(currentClient);
    when(obContext.getCurrentOrganization()).thenReturn(currentOrganization);

    // By default the spec resolves to no window, so the optional AD_Table stays unset.
    mockCriteria(SFSpec.class, Collections.<SFSpec>emptyList());
  }

  @After
  public void tearDown() {
    if (obDalMock != null) {
      obDalMock.close();
    }
    if (obContextMock != null) {
      obContextMock.close();
    }
    if (sessionHandlerMock != null) {
      sessionHandlerMock.close();
    }
  }

  @Test
  public void recordPersistsEveryHistoryColumn() throws Exception {
    EmailSendHistoryRecord historyRecord = fullHistoryRecord(
        TransactionalEmailService.STATUS_SENT, null);

    new DalEmailSendLogStore(MapRecord::new).recordSend(historyRecord);

    BaseOBObject saved = savedRecord();
    assertEquals(Boolean.TRUE, saved.get("active"));
    assertEquals(CONTRACT_NAME, saved.get("contractName"));
    assertEquals(SPEC_NAME, saved.get("specName"));
    assertEquals(RECORD_ID, saved.get("recordID"));
    assertEquals(new Date(historyRecord.getSentAtMillis()), saved.get("sentAt"));
    assertEquals(TransactionalEmailService.STATUS_SENT, saved.get("status"));
    assertNull(saved.get("errorMessage"));
    assertEquals(SUBJECT, saved.get("subject"));
    assertEquals(DOWNLOAD_LINK, saved.get("downloadLink"));
    assertEquals("es_ES", saved.get("emailLanguage"));
    assertEquals("sales-invoice-send:tenant-1:doc-1:v1", saved.get("idempotencyKey"));
  }

  @Test
  public void recordPersistsRecipientsSubjectAndOperatorMessageInClear() throws Exception {
    // The inverse of DalEmailSafetyStoreTest's privacy invariant, and the whole point of the new
    // table: the anti-abuse ledger still hashes these, this one must not.
    new DalEmailSendLogStore(MapRecord::new).recordSend(
        fullHistoryRecord(TransactionalEmailService.STATUS_SENT, null));

    BaseOBObject saved = savedRecord();
    String recipientsTo = (String) saved.get("recipientsTo");
    assertTrue("expected the raw To address, got: " + recipientsTo,
        recipientsTo.contains(CUSTOMER_ADDRESS));
    assertTrue("expected every To address, got: " + recipientsTo,
        recipientsTo.contains(BILLING_ADDRESS));
    assertEquals(CUSTOMER_ADDRESS + ", " + BILLING_ADDRESS, recipientsTo);
    assertEquals(PROJECT_MANAGER_ADDRESS, saved.get("recipientsCC"));
    assertEquals(SUBJECT, saved.get("subject"));
    assertEquals(OPERATOR_MESSAGE, saved.get("messageBody"));
  }

  @Test
  public void recordWritesTheRowInTheCallersOwnSession() throws Exception {
    // No admin mode and no forced client 0: DAL must stamp AD_Client_ID/AD_Org_ID/CreatedBy from
    // the caller's session, or the read endpoint's readable-client filter stops being an access
    // rule and every history row becomes unreadable for the tenant that produced it.
    new DalEmailSendLogStore(MapRecord::new).recordSend(
        fullHistoryRecord(TransactionalEmailService.STATUS_SENT, null));

    BaseOBObject saved = savedRecord();
    assertSame(currentClient, saved.get("client"));
    assertSame(currentOrganization, saved.get("organization"));
    obContextMock.verify(() -> OBContext.setAdminMode(), never());
    obContextMock.verify(() -> OBContext.setAdminMode(anyBoolean()), never());
    obContextMock.verify(OBContext::restorePreviousMode, never());
    verify(obDal, never()).get(eq(Client.class), anyString());
  }

  @Test
  public void recordSharesTheCallersTransactionInsteadOfCommittingItsOwn() throws Exception {
    // The audit row written right after this one ends a successful send with commitAndStart();
    // saving first (and never committing here) is what keeps both rows in the same transaction.
    new DalEmailSendLogStore(MapRecord::new).recordSend(
        fullHistoryRecord(TransactionalEmailService.STATUS_SENT, null));

    verify(obDal, never()).flush();
    verifyNoInteractions(sessionHandler);
  }

  @Test
  public void recordStoresTheFailureMessageOfAFailedAttempt() throws Exception {
    new DalEmailSendLogStore(MapRecord::new).recordSend(
        fullHistoryRecord(TransactionalEmailService.STATUS_PROVIDER_FAILED,
            "Transactional email provider rejected the request"));

    BaseOBObject saved = savedRecord();
    assertEquals(TransactionalEmailService.STATUS_PROVIDER_FAILED, saved.get("status"));
    assertEquals("Transactional email provider rejected the request", saved.get("errorMessage"));
  }

  @Test
  public void recordToleratesAnEntryWithoutOptionalValues() throws Exception {
    // A default-copy send to a single recipient: no operator message, no cc, no download link,
    // no subject and no language must not cost the row.
    EmailRecipientSet recipients = EmailRecipientSet.singleTo(CUSTOMER_ADDRESS);
    JSONObject commandBody = new JSONObject();
    commandBody.put(EmailContractCommandSupport.FIELD_RECORD_ID, RECORD_ID);

    new DalEmailSendLogStore(MapRecord::new).recordSend(
        historyRecord(recipients, new JSONObject(), commandBody,
            TransactionalEmailService.STATUS_SENT, null, SPEC_NAME));

    BaseOBObject saved = savedRecord();
    assertEquals(CUSTOMER_ADDRESS, saved.get("recipientsTo"));
    assertNull(saved.get("recipientsCC"));
    assertNull(saved.get("subject"));
    assertNull(saved.get("messageBody"));
    assertNull(saved.get("downloadLink"));
    assertNull(saved.get("emailLanguage"));
  }

  @Test
  public void recordKeepsHistoryForAContractWithoutASpecName() throws Exception {
    // return-to-vendor-send derives a spec name that resolves no window; the column is nullable
    // and best effort, so the row must still be written.
    EmailRecipientSet recipients = EmailRecipientSet.singleTo(CUSTOMER_ADDRESS);
    JSONObject commandBody = new JSONObject();
    commandBody.put(EmailContractCommandSupport.FIELD_RECORD_ID, RECORD_ID);

    new DalEmailSendLogStore(MapRecord::new).recordSend(
        historyRecord(recipients, new JSONObject(), commandBody,
            TransactionalEmailService.STATUS_SENT, null, null));

    BaseOBObject saved = savedRecord();
    assertNull(saved.get("specName"));
    assertNull(saved.get("table"));
    assertEquals(RECORD_ID, saved.get("recordID"));
  }

  @Test
  public void recordTruncatesValuesThatOverflowTheirColumn() throws Exception {
    String longSubject = repeat('a', 450);
    String longMessage = repeat('b', 4200);
    JSONObject providerData = new JSONObject();
    providerData.put("subject", longSubject);
    JSONObject commandBody = new JSONObject();
    commandBody.put(EmailContractCommandSupport.FIELD_RECORD_ID, RECORD_ID);
    commandBody.put("messageEdits", new JSONObject().put("message", longMessage));

    new DalEmailSendLogStore(MapRecord::new).recordSend(
        historyRecord(EmailRecipientSet.singleTo(CUSTOMER_ADDRESS), providerData, commandBody,
            TransactionalEmailService.STATUS_SENT, null, SPEC_NAME));

    BaseOBObject saved = savedRecord();
    assertEquals(400, ((String) saved.get("subject")).length());
    assertEquals(4000, ((String) saved.get("messageBody")).length());
  }

  @Test
  public void recordLinksTheDocumentTableResolvedFromTheSpec() throws Exception {
    Table documentTable = mock(Table.class);
    Window window = mock(Window.class);
    SFSpec spec = mock(SFSpec.class);
    when(spec.getADWindow()).thenReturn(window);
    Tab headerTab = mock(Tab.class);
    when(headerTab.getTable()).thenReturn(documentTable);
    mockCriteria(SFSpec.class, Collections.singletonList(spec));
    mockCriteria(Tab.class, Collections.singletonList(headerTab));

    new DalEmailSendLogStore(MapRecord::new).recordSend(
        fullHistoryRecord(TransactionalEmailService.STATUS_SENT, null));

    assertSame(documentTable, savedRecord().get("table"));
  }

  @Test
  public void recordLeavesTheTableUnsetWhenTheSpecResolvesNoWindow() throws Exception {
    SFSpec spec = mock(SFSpec.class);
    when(spec.getADWindow()).thenReturn(null);
    mockCriteria(SFSpec.class, Collections.singletonList(spec));

    new DalEmailSendLogStore(MapRecord::new).recordSend(
        fullHistoryRecord(TransactionalEmailService.STATUS_SENT, null));

    assertNull(savedRecord().get("table"));
  }

  @Test
  public void recordRejectsANullHistoryRecord() {
    DalEmailSendLogStore store = new DalEmailSendLogStore(MapRecord::new);

    NullPointerException error = assertThrows(NullPointerException.class,
        () -> store.recordSend(null));

    assertEquals("Email send history record cannot be null", error.getMessage());
    verify(obDal, never()).save(any());
  }

  @Test
  public void constructorRejectsANullRecordSupplier() {
    NullPointerException error = assertThrows(NullPointerException.class,
        () -> new DalEmailSendLogStore(null));

    assertEquals("Email send log record supplier cannot be null", error.getMessage());
  }

  private BaseOBObject savedRecord() {
    ArgumentCaptor<BaseOBObject> captor = ArgumentCaptor.forClass(BaseOBObject.class);
    verify(obDal).save(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private <T extends BaseOBObject> void mockCriteria(Class<T> entityClass, List<T> results) {
    OBCriteria<T> criteria = mock(OBCriteria.class);
    when(obDal.createCriteria(entityClass)).thenReturn(criteria);
    when(criteria.setMaxResults(anyInt())).thenReturn(criteria);
    when(criteria.list()).thenReturn(results);
  }

  private static EmailSendHistoryRecord fullHistoryRecord(String status, String message)
      throws Exception {
    EmailRecipientSet recipients = EmailRecipientSet.of(
        Arrays.asList(CUSTOMER_ADDRESS, BILLING_ADDRESS),
        Collections.singletonList(PROJECT_MANAGER_ADDRESS));
    JSONObject providerData = new JSONObject();
    providerData.put("subject", SUBJECT);
    providerData.put("download_link", DOWNLOAD_LINK);
    JSONObject commandBody = new JSONObject();
    commandBody.put(EmailContractCommandSupport.FIELD_TENANT_ID, "tenant-1");
    commandBody.put(EmailContractCommandSupport.FIELD_RECORD_ID, RECORD_ID);
    commandBody.put(EmailContractCommandSupport.FIELD_LANGUAGE, "es_ES");
    commandBody.put("messageEdits", new JSONObject().put("message", OPERATOR_MESSAGE));
    return historyRecord(recipients, providerData, commandBody, status, message, SPEC_NAME);
  }

  private static EmailSendHistoryRecord historyRecord(EmailRecipientSet recipients,
      JSONObject providerData, JSONObject commandBody, String status, String message,
      String specName) {
    EmailContractCommand command = new EmailContractCommand(CONTRACT_NAME, commandBody);
    EmailRecipientResolution recipient = EmailRecipientResolution.serverResolved(recipients);
    EmailProviderRequest request = new EmailProviderRequest(recipients, "custom", providerData,
        null);
    EmailSendContext context = new EmailSendContext(command, recipient, request);
    EmailAuditRecord audit = EmailAuditRecord.create(context,
        "sales-invoice-send:tenant-1:doc-1:v1", 200, status, message, 202, false);
    return EmailSendHistoryRecord.create(context, audit, specName);
  }

  private static String repeat(char character, int length) {
    StringBuilder builder = new StringBuilder(length);
    for (int index = 0; index < length; index++) {
      builder.append(character);
    }
    return builder.toString();
  }

  /** Map-backed stand-in for the generated {@code ETGO_Email_Send_Log} entity. */
  private static final class MapRecord extends BaseOBObject {
    private static final long serialVersionUID = 1L;
    private final Map<String, Object> values = new HashMap<>();

    @Override
    public String getEntityName() {
      return DalEmailSendLogStore.ENTITY_EMAIL_SEND_LOG;
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
