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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;

/**
 * Unit tests for {@link OnboardingAccountingWiringService} (Gap A1/A2).
 *
 * <p>The service is exercised through a {@code TestableService} subclass that overrides the
 * protected DB "seam" methods inherited from {@link OnboardingContextSupport} and the service's own
 * resolution/provisioning seams, so no database, OBContext or OBDal access is required. Context
 * capture/apply/restore are stubbed to plain {@link OBContext} swaps so context-restoration can be
 * asserted with {@code assertSame}.
 *
 * <p>Not covered here: the private XML-parsing / tree-node methods ({@code loadSourceTreeNodes},
 * {@code resolveParentTenantId}, {@code parseSeqno}) — they depend on bundled classpath sourcedata
 * XML and cannot be exercised by a pure unit test.
 */
public class OnboardingAccountingWiringServiceTest {

  // ---------------------------------------------------------------------------------------------
  // wire() — context validation
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testWireFailsWhenClientIdMissing() {
    try {
      new TestableService().wire(null, "ORG-1", "USER-1", "ROLE-1");
      fail("Expected OBException for missing client");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing client"));
    }
  }

  @Test
  public void testWireFailsWhenOrgIdMissing() {
    try {
      new TestableService().wire("CLIENT-1", "", "USER-1", "ROLE-1");
      fail("Expected OBException for missing organization");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing organization"));
    }
  }

  @Test
  public void testWireFailsWhenAdminUserMissing() {
    try {
      new TestableService().wire("CLIENT-1", "ORG-1", null, "ROLE-1");
      fail("Expected OBException for missing admin user");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin user"));
    }
  }

  @Test
  public void testWireFailsWhenAdminRoleMissing() {
    try {
      new TestableService().wire("CLIENT-1", "ORG-1", "USER-1", null);
      fail("Expected OBException for missing admin role");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing admin role"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // wire() — resolution failures
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testWireFailsWhenClientNotFound() {
    TestableService service = new TestableService();
    service.clientMissing = true;

    try {
      service.wire("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected OBException for missing client");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Client not found for accounting wiring"));
    }
  }

  @Test
  public void testWireFailsWhenOrganizationNotFound() {
    TestableService service = new TestableService();
    service.orgMissing = true;

    try {
      service.wire("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected OBException for missing organization");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Organization not found for accounting wiring"));
    }
  }

  @Test
  public void testWireFailsWhenNoLedgerImported() {
    TestableService service = new TestableService();
    service.ledgerMissing = true;

    try {
      service.wire("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected OBException for missing accounting schema");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("No accounting schema was imported"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // wire() — happy path
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testWireRunsAllStepsFlushesAndRestoresContext() {
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    TestableService service = new TestableService();
    service.wire("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals(1, service.wireGeneralLedgerCount);
    assertEquals(1, service.ensureAcctSchemaCount);
    assertEquals(1, service.wireTreeCount);
    assertEquals(1, service.rebrandCount);
    assertEquals(1, service.provisionEntityCount);
    assertTrue("wire() must flush", service.flushed);
    assertSame("wire() must restore the previous context", previous, OBContext.getOBContext());
  }

  // ---------------------------------------------------------------------------------------------
  // wireBusinessPartnerAccounts()
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testWireBusinessPartnerAccountsFailsWhenClientIdMissing() {
    try {
      new TestableService().wireBusinessPartnerAccounts(null, "ORG-1", "USER-1", "ROLE-1");
      fail("Expected OBException for missing client");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing client"));
    }
  }

  @Test
  public void testWireBusinessPartnerAccountsFailsWhenClientNotFound() {
    TestableService service = new TestableService();
    service.clientMissing = true;

    try {
      service.wireBusinessPartnerAccounts("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected OBException for missing client");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Client not found for business-partner accounting"));
    }
  }

  @Test
  public void testWireBusinessPartnerAccountsFailsWhenNoLedgerImported() {
    TestableService service = new TestableService();
    service.ledgerMissing = true;

    try {
      service.wireBusinessPartnerAccounts("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");
      fail("Expected OBException for missing accounting schema");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("cannot provision business-partner posting accounts"));
    }
  }

  @Test
  public void testWireBusinessPartnerAccountsRunsTwoInsertsFlushesAndRestoresContext() {
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    TestableService service = new TestableService();
    service.wireBusinessPartnerAccounts("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals("exactly two posting-account inserts", 2, service.acctInserts.size());
    assertTrue("wireBusinessPartnerAccounts() must flush", service.flushed);
    assertSame("must restore the previous context", previous, OBContext.getOBContext());
  }

  // ---------------------------------------------------------------------------------------------
  // provisionEntityPostingAccounts() — direct invocation
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testProvisionEntityPostingAccountsRunsSixInsertsWithClientAndSchemaId() {
    // Use a double that records inserts but keeps the REAL provisionEntityPostingAccounts body,
    // so the six runEntityAcctInsert calls (and their ordering of clientId/schemaId) are exercised.
    InsertRecordingService service = new InsertRecordingService();
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("C1");
    AcctSchema ledger = mock(AcctSchema.class);
    when(ledger.getId()).thenReturn("S1");

    service.provisionEntityPostingAccounts(client, ledger);

    assertEquals("six per-entity posting inserts", 6, service.acctInserts.size());
    for (AcctInsert insert : service.acctInserts) {
      assertEquals("C1", insert.clientId);
      assertEquals("S1", insert.schemaId);
      assertTrue("each insert must carry a non-empty SQL", insert.sql != null && !insert.sql.isEmpty());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // wireOrganizationGeneralLedger() — direct OBDal interaction (real implementation)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testWireOrganizationGeneralLedgerSkipsWhenAlreadyWired() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    AcctSchema existing = mock(AcctSchema.class);
    AcctSchema ledger = mock(AcctSchema.class);
    Organization org = mock(Organization.class);
    when(org.getGeneralLedger()).thenReturn(existing);

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      service.wireOrganizationGeneralLedger(org, ledger);
    }

    verify(org, never()).setGeneralLedger(ledger);
    verify(dal, never()).save(org);
  }

  @Test
  public void testWireOrganizationGeneralLedgerWiresWhenUnset() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    AcctSchema ledger = mock(AcctSchema.class);
    Organization org = mock(Organization.class);
    when(org.getGeneralLedger()).thenReturn(null);

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      service.wireOrganizationGeneralLedger(org, ledger);
    }

    verify(org).setGeneralLedger(ledger);
    verify(dal).save(org);
  }

  // ---------------------------------------------------------------------------------------------
  // replaceSourceMoniker() — delegation to OnboardingSourceMoniker
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testReplaceSourceMonikerDelegatesAndSubstitutes() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    assertEquals("Esquema Acme", service.replaceSourceMoniker("Esquema GO", "Acme"));
  }

  @Test
  public void testReplaceSourceMonikerPassesNullThrough() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    assertNull(service.replaceSourceMoniker(null, "Acme"));
  }

  // ---------------------------------------------------------------------------------------------
  // Test double
  // ---------------------------------------------------------------------------------------------

  /** Immutable record of one {@code runEntityAcctInsert} invocation. */
  private static final class AcctInsert {
    final String sql;
    final String clientId;
    final String schemaId;

    AcctInsert(String sql, String clientId, String schemaId) {
      this.sql = sql;
      this.clientId = clientId;
      this.schemaId = schemaId;
    }
  }

  /**
   * Subclass that overrides every DB/context seam so the orchestration flow runs entirely in memory.
   */
  private static final class TestableService extends OnboardingAccountingWiringService {

    boolean clientMissing;
    boolean orgMissing;
    boolean ledgerMissing;

    boolean flushed;
    int wireGeneralLedgerCount;
    int ensureAcctSchemaCount;
    int wireTreeCount;
    int rebrandCount;
    int provisionEntityCount;

    final List<AcctInsert> acctInserts = new ArrayList<>();

    // --- OnboardingContextSupport seams ---------------------------------------------------------

    @Override
    protected OBContext captureCurrentContext() {
      return OBContext.getOBContext();
    }

    @Override
    protected void applyExecutionContext(String adminUserId, String adminRoleId,
        String clientId, String orgId) {
      OBContext.setOBContext(mock(OBContext.class));
    }

    @Override
    protected void restoreExecutionContext(OBContext previousContext) {
      OBContext.setOBContext(previousContext);
    }

    @Override
    protected void enterAdminMode() {
      // no-op: avoid touching the real OBContext admin-mode stack
    }

    @Override
    protected void exitAdminMode() {
      // no-op: avoid touching the real OBContext admin-mode stack
    }

    @Override
    protected void flushChanges() {
      flushed = true;
    }

    @Override
    protected Organization resolveOrganization(String orgId) {
      return orgMissing ? null : mock(Organization.class);
    }

    // --- OnboardingAccountingWiringService seams ------------------------------------------------

    @Override
    protected Client resolveClient(String clientId) {
      return clientMissing ? null : mock(Client.class);
    }

    @Override
    protected AcctSchema resolveImportedLedger(Client client) {
      if (ledgerMissing) {
        return null;
      }
      AcctSchema ledger = mock(AcctSchema.class);
      when(ledger.getId()).thenReturn("S1");
      return ledger;
    }

    @Override
    protected void wireOrganizationGeneralLedger(Organization org, AcctSchema ledger) {
      wireGeneralLedgerCount++;
    }

    @Override
    protected void ensureOrganizationAcctSchema(Client client, Organization org, AcctSchema ledger) {
      ensureAcctSchemaCount++;
    }

    @Override
    protected void wireAccountElementTree(Client client) {
      wireTreeCount++;
    }

    @Override
    protected void rebrandImportedChartNames(Client client, AcctSchema ledger) {
      rebrandCount++;
    }

    @Override
    protected void provisionEntityPostingAccounts(Client client, AcctSchema ledger) {
      provisionEntityCount++;
    }

    @Override
    protected void runEntityAcctInsert(String sql, String clientId, String schemaId) {
      acctInserts.add(new AcctInsert(sql, clientId, schemaId));
    }
  }

  /**
   * Records {@code runEntityAcctInsert} calls while preserving the real
   * {@code provisionEntityPostingAccounts} body, so the production statement-dispatch logic is the
   * code actually under test.
   */
  private static final class InsertRecordingService extends OnboardingAccountingWiringService {
    final List<AcctInsert> acctInserts = new ArrayList<>();

    @Override
    protected void runEntityAcctInsert(String sql, String clientId, String schemaId) {
      acctInserts.add(new AcctInsert(sql, clientId, schemaId));
    }
  }
}
