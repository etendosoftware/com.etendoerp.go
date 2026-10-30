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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Language;

import com.etendoerp.go.schemaforge.email.contracts.SalesQuotationSendEmailContract;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * ETP-5205 — the document email-send endpoint is dispatched before the NEO spec router, so
 * {@link DefaultDocumentSendEmailContract#authorize} is where a Solo-Lectura role must be refused.
 * These tests pin that it asks for WRITE access on the contract's own spec, fails closed, and
 * does not even resolve the document for a denied role. ETP-6013 adds the status rule each contract
 * declares, enforced on the same path.
 *
 * @covers com.etendoerp.go.schemaforge.email.DefaultDocumentSendEmailContract
 * @covers com.etendoerp.go.schemaforge.email.contracts.SalesQuotationSendEmailContract
 * @covers com.etendoerp.go.schemaforge.email.DocumentStatusLabel
 */
public class DocumentSendAuthorizeTierTest {

  private static final String CONTRACT_NAME = "sales-order-send";
  private static final String SPEC_NAME = "sales-order";
  private static final String RECORD_ID = "doc-1";

  @Test
  public void fullAccessRoleIsAllowed() throws Exception {
    try (MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class)) {
      access.when(() -> NeoAccessHelper.canWriteSpec(SPEC_NAME)).thenReturn(true);

      EmailAuthorizationResult result = contract(new AtomicInteger()).authorize(command());

      assertTrue(result.isAllowed());
    }
  }

  @Test
  public void readOnlyRoleIsRejectedWith403BeforeTheDocumentIsResolved() throws Exception {
    AtomicInteger resolverCalls = new AtomicInteger();
    try (MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class)) {
      // canWriteSpec answers false for a Solo-Lectura tier and for no access at all alike.
      access.when(() -> NeoAccessHelper.canWriteSpec(SPEC_NAME)).thenReturn(false);

      EmailAuthorizationResult result = contract(resolverCalls).authorize(command());

      assertFalse(result.isAllowed());
      // 403 is what TransactionalEmailService maps to UNAUTHORIZED → sendModalUnauthorized.
      assertEquals(403, result.getHttpStatus());
      assertEquals("A denied role must learn nothing about the record", 0, resolverCalls.get());
    }
  }

  @Test
  public void theCheckUsesTheSpecDerivedFromTheContractName() throws Exception {
    try (MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class)) {
      access.when(() -> NeoAccessHelper.canWriteSpec(any())).thenReturn(false);
      access.when(() -> NeoAccessHelper.canWriteSpec(SPEC_NAME)).thenReturn(true);

      assertTrue(contract(new AtomicInteger()).authorize(command()).isAllowed());
    }
  }

  @Test
  public void invalidCommandStillFailsValidationBeforeTheTierCheck() throws Exception {
    try (MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class)) {
      EmailAuthorizationResult result = contract(new AtomicInteger())
          .authorize(new EmailContractCommand(CONTRACT_NAME, new JSONObject()));

      assertFalse(result.isAllowed());
      assertEquals(400, result.getHttpStatus());
      access.verifyNoInteractions();
    }
  }

  @Test
  public void blankSpecNameIsDenied() {
    // Fail closed: a contract whose name does not follow `<spec>-send` has no spec to check.
    assertFalse(NeoAccessHelper.canWriteSpec(null));
    assertFalse(NeoAccessHelper.canWriteSpec(" "));
  }

  @Test
  public void allowedRoleStillGets404ForAMissingDocument() throws Exception {
    DefaultDocumentSendEmailContract contract = new DefaultDocumentSendEmailContract(
        CONTRACT_NAME, "Documento", recordId -> Optional.empty());
    try (MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class)) {
      access.when(() -> NeoAccessHelper.canWriteSpec(SPEC_NAME)).thenReturn(true);

      EmailAuthorizationResult result = contract.authorize(command());

      assertFalse(result.isAllowed());
      assertEquals(404, result.getHttpStatus());
    }
  }

  /**
   * ETP-6013 repro — the send itself (the path the SPA's REST call and the MCP tool both take) is
   * refused by status, with the rule the contract declares: a completed-only contract refuses a
   * draft or closed document, the quotation refuses only a draft, and a resolver that reports no
   * status leaves the rule out. The refusal is 409 with the message in the command's language.
   */
  @Test
  public void sendIsRefusedByTheStatusTheContractDeclares() throws Exception {
    Object[][] cases = {
        // contract, status, allowed, expected refusal (es_ES, no database: the code is the name)
        {"sales-order-send", "CO", true, null},
        {"sales-order-send", "DR", false,
            "doc-1 est\u00e1 en DR: el env\u00edo por correo solo aplica a documentos Completados."},
        {"sales-order-send", "CL", false,
            "doc-1 est\u00e1 en CL: el env\u00edo por correo solo aplica a documentos Completados."},
        {"sales-order-send", null, true, null},
        {"sales-quotation-send", "UE", true, null},
        {"sales-quotation-send", "DR", false,
            "doc-1 est\u00e1 en DR: el env\u00edo por correo no aplica a documentos en Borrador."},
    };
    OBDal dal = mock(OBDal.class);
    when(dal.getConnection()).thenThrow(new IllegalStateException("no database in this test"));
    Language adLanguage = mock(Language.class);
    when(adLanguage.getLanguage()).thenReturn("es_ES");
    OBContext context = mock(OBContext.class);
    when(context.getLanguage()).thenReturn(adLanguage);
    // OBContext is mocked so setAdminMode/restorePreviousMode never run for real without a context.
    try (MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class);
         MockedStatic<OBContext> obContext = mockStatic(OBContext.class);
         MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      access.when(() -> NeoAccessHelper.canWriteSpec(any())).thenReturn(true);
      obContext.when(OBContext::getOBContext).thenReturn(context);
      obDal.when(OBDal::getInstance).thenReturn(dal);
      for (Object[] row : cases) {
        String name = (String) row[0];
        String status = (String) row[1];
        EmailDocumentRecordResolver resolver = recordId -> Optional.of(new EmailDocumentRecord(
            "Cliente", "customer@example.com", RECORD_ID, RECORD_ID, null,
            "https://example.test/download/doc-1", "client-1").withDocumentStatus(status));
        DefaultDocumentSendEmailContract contract = "sales-quotation-send".equals(name)
            ? new SalesQuotationSendEmailContract(resolver)
            : new DefaultDocumentSendEmailContract(name, "Documento", resolver);

        EmailAuthorizationResult result = contract.authorize(new EmailContractCommand(name,
            new JSONObject().put(EmailContractCommandSupport.FIELD_RECORD_ID, RECORD_ID)
                .put(EmailContractCommandSupport.FIELD_LANGUAGE, "es_ES")));

        String label = name + "/" + status;
        assertEquals(label, row[2], result.isAllowed());
        if (!result.isAllowed()) {
          assertEquals(label, DefaultDocumentSendEmailContract.STATUS_NOT_SENDABLE,
              result.getHttpStatus());
          assertEquals(label, row[3], result.getMessage());
        }
      }
    }
  }

  private static EmailContractCommand command() throws Exception {
    return new EmailContractCommand(CONTRACT_NAME,
        new JSONObject().put(EmailContractCommandSupport.FIELD_RECORD_ID, RECORD_ID));
  }

  private static DefaultDocumentSendEmailContract contract(AtomicInteger resolverCalls) {
    return new DefaultDocumentSendEmailContract(CONTRACT_NAME, "Documento", recordId -> {
      resolverCalls.incrementAndGet();
      return Optional.of(new EmailDocumentRecord("Cliente", "customer@example.com", RECORD_ID,
          RECORD_ID, null, "https://example.test/download/doc-1", "client-1"));
    });
  }
}
