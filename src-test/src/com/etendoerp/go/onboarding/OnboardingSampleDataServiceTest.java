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
package com.etendoerp.go.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.service.db.ImportResult;

/**
 * ETP-5426 — {@link OnboardingSampleDataService}, with its DAL seams replaced so the ordering and
 * the failure contract can be asserted without a database.
 */
public class OnboardingSampleDataServiceTest {

  private static final String CLIENT_ID = "CLIENT";
  private static final String ORG_ID = "ORG";
  private static final String USER_ID = "USER";
  private static final String ROLE_ID = "ROLE";

  private OnboardingDatasetNormalizer normalizer;
  private OnboardingAccountingWiringService wiring;
  private Client client;
  private Organization organization;
  private AcctSchema ledger;
  private ImportResult importResult;
  private RecordingService service;

  @BeforeEach
  public void setUp() {
    normalizer = mock(OnboardingDatasetNormalizer.class);
    wiring = mock(OnboardingAccountingWiringService.class);
    client = mock(Client.class);
    organization = mock(Organization.class);
    ledger = mock(AcctSchema.class);
    importResult = mock(ImportResult.class);
    when(normalizer.buildDatasetXml(ORG_ID)).thenReturn("<Openbravo/>");
    when(wiring.resolveImportedLedger(client)).thenReturn(ledger);
    when(importResult.getInsertedObjects()).thenReturn(new ArrayList<>());
    service = new RecordingService();
  }

  @Test
  public void testImportsThenProvisionsPostingAccountsThenAlignsSequences() {
    OnboardingSampleDataService.Outcome outcome =
        service.importSampleData(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID);

    assertEquals(OnboardingSampleDataService.Outcome.IMPORTED, outcome);
    assertEquals(List.of("context", "adminMode", "presence", "import", "flush", "identifiers",
        "sequences", "flush", "exitAdminMode", "restoreContext"), service.calls);
    assertEquals("<Openbravo/>", service.importedXml);
    verify(wiring).provisionSampleDataPostingAccounts(client, ledger);
  }

  @Test
  public void testAnAlreadyImportedTenantIsLeftUntouched() {
    service.alreadyImported = true;

    OnboardingSampleDataService.Outcome outcome =
        service.importSampleData(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID);

    assertEquals(OnboardingSampleDataService.Outcome.ALREADY_PRESENT, outcome);
    assertFalse(service.calls.contains("import"));
    assertFalse(service.calls.contains("sequences"));
    verify(normalizer, never()).buildDatasetXml(any());
    assertTrue(service.calls.contains("restoreContext"),
        "the context is restored on the early exit too");
  }

  @Test
  public void testAFailedImportThrowsAndWritesNothingMore() {
    when(importResult.hasErrorOccured()).thenReturn(true);
    when(importResult.getErrorMessages()).thenReturn("Referenced object not present");

    OBException failure = assertThrows(OBException.class,
        () -> service.importSampleData(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID));

    assertTrue(failure.getMessage().contains("Referenced object not present"));
    assertFalse(service.calls.contains("sequences"));
    assertFalse(service.calls.contains("identifiers"));
    verify(wiring, never()).provisionSampleDataPostingAccounts(any(), any());
    assertTrue(service.calls.contains("restoreContext"),
        "the DAL context must never stay on the tenant");
  }

  @Test
  public void testAMissingLedgerFailsBeforeTheSequencesAreTouched() {
    when(wiring.resolveImportedLedger(client)).thenReturn(null);

    assertThrows(OBException.class,
        () -> service.importSampleData(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID));
    assertFalse(service.calls.contains("sequences"));
  }

  @Test
  public void testAMissingClientOrOrganizationFails() {
    service.resolvedClient = null;
    assertThrows(OBException.class,
        () -> service.importSampleData(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID));

    service = new RecordingService();
    service.resolvedOrganization = null;
    assertThrows(OBException.class,
        () -> service.importSampleData(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID));
  }

  @Test
  public void testBlankArgumentsAreRejectedBeforeTouchingTheContext() {
    assertThrows(IllegalArgumentException.class,
        () -> service.importSampleData(CLIENT_ID, " ", USER_ID, ROLE_ID));
    assertTrue(service.calls.isEmpty());
  }

  @Test
  public void testTheContextInPlaceBeforeTheCallIsTheOneRestored() {
    service.importSampleData(CLIENT_ID, ORG_ID, USER_ID, ROLE_ID);

    assertSame(service.previousContext, service.restoredContext);
  }

  /** Replaces every DAL touchpoint of the service with a recorded, in-memory stand-in. */
  private final class RecordingService extends OnboardingSampleDataService {
    private final List<String> calls = new ArrayList<>();
    private final OBContext previousContext = mock(OBContext.class);
    private OBContext restoredContext;
    private Client resolvedClient = client;
    private Organization resolvedOrganization = organization;
    private boolean alreadyImported;
    private String importedXml;

    private RecordingService() {
      super(() -> normalizer, wiring);
    }

    @Override
    protected OBContext captureCurrentContext() {
      return previousContext;
    }

    @Override
    protected void applyExecutionContext(String adminUserId, String adminRoleId, String clientId,
        String orgId) {
      calls.add("context");
    }

    @Override
    protected void restoreExecutionContext(OBContext context) {
      calls.add("restoreContext");
      restoredContext = context;
    }

    @Override
    protected void enterAdminMode() {
      calls.add("adminMode");
    }

    @Override
    protected void exitAdminMode() {
      calls.add("exitAdminMode");
    }

    @Override
    protected Client resolveClient(String clientId) {
      return resolvedClient;
    }

    @Override
    protected Organization resolveOrganization(String orgId) {
      return resolvedOrganization;
    }

    @Override
    protected boolean isAlreadyImported(String clientId) {
      calls.add("presence");
      return alreadyImported;
    }

    @Override
    protected ImportResult importXml(Client targetClient, Organization targetOrganization,
        String xml) {
      calls.add("import");
      importedXml = xml;
      return importResult;
    }

    @Override
    protected void renumberPartnerIdentifiers(String clientId, String orgId) {
      calls.add("identifiers");
    }

    @Override
    protected void alignDocumentSequences(String clientId) {
      calls.add("sequences");
    }

    @Override
    protected void flush() {
      calls.add("flush");
    }
  }
}
