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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Tree;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationAcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.Element;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.gl.GLItemAccounts;

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
    assertEquals(1, service.provisionGlItemsCount);
    assertEquals(1, service.provisionEntityCount);
    assertTrue("wire() must flush", service.flushed);
    assertSame("wire() must restore the previous context", previous, OBContext.getOBContext());
  }

  /**
   * ETP-5020 — GL Items must be provisioned AFTER chart names are finalized (a GL Item created
   * against the dataset's generic "GOClient" names would immediately diverge from the rebranded
   * subaccount name) and BEFORE the unrelated per-entity posting-account provisioning step, per
   * the placement rationale documented on {@code provisionGlItemsForImportedChart}.
   */
  @Test
  public void testWireProvisionsGlItemsAfterRebrandBeforePosting() {
    TestableService service = new TestableService();
    service.wire("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    int rebrandIndex = service.callOrder.indexOf("rebrand");
    int glItemsIndex = service.callOrder.indexOf("provisionGlItems");
    int postingIndex = service.callOrder.indexOf("provisionEntity");

    assertTrue("rebrand must run before GL Item provisioning", rebrandIndex < glItemsIndex);
    assertTrue("GL Item provisioning must run before posting-account provisioning",
        glItemsIndex < postingIndex);
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
  // patchBpGroupAcctMissingColumns() — ETP-4720, preventive twin of R21-bp-group-acct-remaining-columns.sql
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testPatchBpGroupAcctMissingColumnsFailsWhenClientIdMissing() {
    try {
      new TestableService().patchBpGroupAcctMissingColumns(null, "ORG-1", "USER-1", "ROLE-1");
      fail("Expected OBException for missing client");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing client"));
    }
  }

  @Test
  public void testPatchBpGroupAcctMissingColumnsFailsWhenOrgIdMissing() {
    try {
      new TestableService().patchBpGroupAcctMissingColumns("CLIENT-1", null, "USER-1", "ROLE-1");
      fail("Expected OBException for missing organization");
    } catch (OBException e) {
      assertTrue(e.getMessage().contains("Missing organization"));
    }
  }

  @Test
  public void testPatchBpGroupAcctMissingColumnsRunsPatchFlushesAndRestoresContext() {
    OBContext previous = mock(OBContext.class);
    OBContext.setOBContext(previous);

    TestableService service = new TestableService();
    service.bpGroupAcctPatchRowsToReturn = 3;

    service.patchBpGroupAcctMissingColumns("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals("the patch must run exactly once", 1, service.bpGroupAcctPatchCount);
    assertEquals("the patch must be scoped to the target client", "CLIENT-1",
        service.bpGroupAcctPatchClientId);
    assertTrue("patchBpGroupAcctMissingColumns() must flush", service.flushed);
    assertSame("must restore the previous context", previous, OBContext.getOBContext());
  }

  @Test
  public void testPatchBpGroupAcctMissingColumnsDoesNotResolveClientOrLedger() {
    // Unlike wire()/wireBusinessPartnerAccounts(), this method needs neither a Client entity nor a
    // resolved AcctSchema — it patches every schema the tenant has via one client-scoped statement
    // (see the corrective R21 fix it mirrors). Prove that by leaving clientMissing/ledgerMissing
    // set and confirming no exception is thrown (those seams are never consulted).
    TestableService service = new TestableService();
    service.clientMissing = true;
    service.ledgerMissing = true;

    service.patchBpGroupAcctMissingColumns("CLIENT-1", "ORG-1", "USER-1", "ROLE-1");

    assertEquals(1, service.bpGroupAcctPatchCount);
  }

  // ---------------------------------------------------------------------------------------------
  // provisionEntityPostingAccounts() — direct invocation
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testProvisionEntityPostingAccountsRunsEightInsertsWithClientAndSchemaId() {
    // Use a double that records inserts but keeps the REAL provisionEntityPostingAccounts body,
    // so the eight runEntityAcctInsert calls (and their ordering of clientId/schemaId) are
    // exercised. ensureAcreedorPrepaymentAccount()/overrideAcreedorGroupAccounts() are stubbed by
    // InsertRecordingService: they bypass the runEntityAcctInsert seam and hit
    // OBDal.getInstance().getSession() directly, so leaving them real would reach an uninitialized
    // Hibernate session in this pure-unit test.
    //
    // ETP-4565: count went from six to eight when the financial-account and warehouse posting-
    // account backfills were added (see the dedicated test below for their SQL content).
    InsertRecordingService service = new InsertRecordingService();
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("C1");
    AcctSchema ledger = mock(AcctSchema.class);
    when(ledger.getId()).thenReturn("S1");

    service.provisionEntityPostingAccounts(client, ledger);

    assertEquals("eight per-entity posting inserts", 8, service.acctInserts.size());
    for (AcctInsert insert : service.acctInserts) {
      assertEquals("C1", insert.clientId);
      assertEquals("S1", insert.schemaId);
      assertTrue("each insert must carry a non-empty SQL", insert.sql != null && !insert.sql.isEmpty());
    }
    assertEquals("Acreedor prepayment account provisioning must run once", 1,
        service.ensureAcreedorPrepaymentAccountCount);
    assertEquals("Acreedor group posting-account override must run once", 1,
        service.overrideAcreedorGroupAccountsCount);
  }

  /**
   * ETP-4565 — reproduces the confirmed 0% auto-creation gap for {@code financial-account} and
   * {@code warehouse}: {@code FIN_FINANCIAL_ACCOUNT} and {@code M_WAREHOUSE} are bulk-imported by
   * the onboarding dataset importer (triggers disabled during that import — see
   * {@code OnboardingDatasetDefinition.INCLUDED_TABLES}), so their native {@code _trg} triggers
   * never fire for the bundled template rows ("Caja"/"Cuenta de Banco"/"Tarjeta",
   * "Almacen GO"/"Almacén Secundario"). Every other included entity in this same method
   * (BP group, product category, BP customer/vendor, product, tax) already gets a matching
   * backfill {@code runEntityAcctInsert} call right here; {@code FIN_Financial_Account_Acct} and
   * {@code M_Warehouse_Acct} do not, which is the gap this ticket closes.
   */
  @Test
  public void testProvisionEntityPostingAccountsIncludesFinancialAccountAndWarehouseInserts() {
    InsertRecordingService service = new InsertRecordingService();
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("C1");
    AcctSchema ledger = mock(AcctSchema.class);
    when(ledger.getId()).thenReturn("S1");

    service.provisionEntityPostingAccounts(client, ledger);

    boolean hasFinancialAccountInsert = service.acctInserts.stream()
        .anyMatch(insert -> insert.sql.toLowerCase(java.util.Locale.ROOT)
            .contains("fin_financial_account_acct"));
    boolean hasWarehouseInsert = service.acctInserts.stream()
        .anyMatch(insert -> insert.sql.toLowerCase(java.util.Locale.ROOT)
            .contains("m_warehouse_acct"));

    assertTrue("must provision FIN_Financial_Account_Acct rows so new financial accounts inherit"
        + " default posting accounts from the Esquema Contable", hasFinancialAccountInsert);
    assertTrue("must provision M_Warehouse_Acct rows so new warehouses inherit default posting"
        + " accounts from the Esquema Contable", hasWarehouseInsert);
    assertEquals("eight per-entity posting inserts (six pre-existing + financial-account +"
        + " warehouse)", 8, service.acctInserts.size());
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
  // resolveImportedLedger() — real OBCriteria interaction
  // ---------------------------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveImportedLedgerReturnsNullWhenNoSchema() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AcctSchema> crit = mock(OBCriteria.class);
    when(dal.createCriteria(AcctSchema.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertNull(service.resolveImportedLedger(client));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveImportedLedgerReturnsFirstWhenSingleSchema() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);
    AcctSchema schema = mock(AcctSchema.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AcctSchema> crit = mock(OBCriteria.class);
    when(dal.createCriteria(AcctSchema.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.singletonList(schema));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(schema, service.resolveImportedLedger(client));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveImportedLedgerWarnsAndReturnsFirstWhenMultiple() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("C1");
    AcctSchema first = mock(AcctSchema.class);
    when(first.getId()).thenReturn("S1");
    AcctSchema second = mock(AcctSchema.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AcctSchema> crit = mock(OBCriteria.class);
    when(dal.createCriteria(AcctSchema.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Arrays.asList(first, second));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(first, service.resolveImportedLedger(client));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // ensureOrganizationAcctSchema() — real OBCriteria/OBProvider interaction
  // ---------------------------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testEnsureOrganizationAcctSchemaSkipsWhenLinkExists() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    AcctSchema ledger = mock(AcctSchema.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<OrganizationAcctSchema> crit = mock(OBCriteria.class);
    when(dal.createCriteria(OrganizationAcctSchema.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(mock(OrganizationAcctSchema.class));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.ensureOrganizationAcctSchema(client, org, ledger);
    }

    verify(dal, never()).save(any(OrganizationAcctSchema.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testEnsureOrganizationAcctSchemaCreatesLinkWhenMissing() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    AcctSchema ledger = mock(AcctSchema.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<OrganizationAcctSchema> crit = mock(OBCriteria.class);
    when(dal.createCriteria(OrganizationAcctSchema.class)).thenReturn(crit);
    when(crit.uniqueResult()).thenReturn(null);

    OBProvider provider = mock(OBProvider.class);
    OrganizationAcctSchema link = mock(OrganizationAcctSchema.class);
    when(provider.get(OrganizationAcctSchema.class)).thenReturn(link);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(provider);

      service.ensureOrganizationAcctSchema(client, org, ledger);
    }

    verify(link).setNewOBObject(true);
    verify(link).setClient(client);
    verify(link).setOrganization(org);
    verify(link).setAccountingSchema(ledger);
    verify(dal).save(link);
  }

  // ---------------------------------------------------------------------------------------------
  // wireAccountElementTree() — real OBCriteria interaction
  // ---------------------------------------------------------------------------------------------

  /**
   * Subclass that stubs only the tree-resolution and node-provisioning seams so the body of
   * {@code wireAccountElementTree} (the element re-pointing loop) runs against mocked OBDal.
   */
  private static class TreeWiringService extends OnboardingAccountingWiringService {
    Tree treeToReturn;
    int provisionNodesCount;
    Client provisionedClient;
    Tree provisionedTree;

    @Override
    protected Tree resolveTenantElementValueTree(Client client) {
      return treeToReturn;
    }

    @Override
    protected void provisionElementTreeNodes(Client client, Tree tree) {
      provisionNodesCount++;
      provisionedClient = client;
      provisionedTree = tree;
    }
  }

  @Test
  public void testWireAccountElementTreeWarnsAndReturnsWhenNoTree() {
    TreeWiringService service = new TreeWiringService();
    service.treeToReturn = null;
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("C1");

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.wireAccountElementTree(client);
    }

    assertEquals("no node provisioning when there is no tree", 0, service.provisionNodesCount);
    verify(dal, never()).createCriteria(Element.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testWireAccountElementTreeRepointsUntreedElementsAndProvisionsNodes() {
    TreeWiringService service = new TreeWiringService();
    Tree tree = mock(Tree.class);
    service.treeToReturn = tree;
    Client client = mock(Client.class);

    Element untreed = mock(Element.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Element> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Element.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.singletonList(untreed));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.wireAccountElementTree(client);
    }

    verify(untreed).setTree(tree);
    verify(dal).save(untreed);
    assertEquals(1, service.provisionNodesCount);
    assertSame(client, service.provisionedClient);
    assertSame(tree, service.provisionedTree);
  }

  // ---------------------------------------------------------------------------------------------
  // provisionElementTreeNodes() — empty-source warn branch (bundled XML absent on test classpath)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testProvisionElementTreeNodesWarnsWhenNoSourceNodes() {
    // The bundled sourcedata XML is staged at build time and is not on the unit-test classpath,
    // so loadSourceTreeNodes() returns empty and the method takes the early warn-and-return branch.
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("C1");
    Tree tree = mock(Tree.class);

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      // Must not throw and must not attempt any tenant-value lookups when source is empty.
      service.provisionElementTreeNodes(client, tree);
    }

    verify(dal, never()).createCriteria(ElementValue.class);
  }

  // ---------------------------------------------------------------------------------------------
  // loadSourceTreeNodes() / loadSourceElementValues() — absent classpath resource → empty
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testLoadSourceTreeNodesReturnsEmptyWhenResourceAbsent() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    assertTrue("no bundled tree-node XML on the test classpath",
        service.loadSourceTreeNodes().isEmpty());
  }

  @Test
  public void testLoadSourceElementValuesReturnsEmptyWhenResourceAbsent() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    assertTrue("no bundled element-value XML on the test classpath",
        service.loadSourceElementValues().isEmpty());
  }

  // ---------------------------------------------------------------------------------------------
  // loadTenantElementValueIds() — real OBCriteria interaction
  // ---------------------------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testLoadTenantElementValueIdsMapsSearchKeyToId() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);

    ElementValue ev = mock(ElementValue.class);
    when(ev.getSearchKey()).thenReturn("4000");
    when(ev.getId()).thenReturn("ev-1");

    OBDal dal = mock(OBDal.class);
    OBCriteria<ElementValue> crit = mock(OBCriteria.class);
    when(dal.createCriteria(ElementValue.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.singletonList(ev));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      Map<String, String> byValue = service.loadTenantElementValueIds(client);
      assertEquals(1, byValue.size());
      assertEquals("ev-1", byValue.get("4000"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // insertTreeNode() — native query parameter binding
  // ---------------------------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testInsertTreeNodeBindsParametersAndReturnsRowCount() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(1);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      int rows = service.insertTreeNode("tree-1", "node-1", "C1", "0", 10L);
      assertEquals(1, rows);
    }

    verify(query).setParameter("treeId", "tree-1");
    verify(query).setParameter("nodeId", "node-1");
    verify(query).setParameter("clientId", "C1");
    verify(query).setParameter("parentId", "0");
    verify(query).setParameter("seqno", 10L);
    verify(query).executeUpdate();
  }

  // ---------------------------------------------------------------------------------------------
  // runEntityAcctInsert() — native query parameter binding (real implementation)
  // ---------------------------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testRunEntityAcctInsertBindsClientAndSchemaIds() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    when(session.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(3);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.runEntityAcctInsert("INSERT INTO x ...", "C1", "S1");
    }

    verify(session).createNativeQuery("INSERT INTO x ...");
    verify(query).setParameter("clientId", "C1");
    verify(query).setParameter("schemaId", "S1");
    verify(query).executeUpdate();
  }

  // ---------------------------------------------------------------------------------------------
  // overrideAcreedorGroupAccounts() — native query parameter binding, both outcome branches
  // (real implementation; the SQL text itself is captured rather than referenced by constant,
  // since ACREEDOR_GROUP_ACCT_OVERRIDE_SQL is a private field of the class under test)
  // ---------------------------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testOverrideAcreedorGroupAccountsBindsAllFiveParametersWhenRowsAffected() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(session.createNativeQuery(sqlCaptor.capture())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(1);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.overrideAcreedorGroupAccounts("C1", "S1");
    }

    String sql = sqlCaptor.getValue();
    assertTrue("override SQL must target c_bp_group_acct", sql.contains("c_bp_group_acct"));
    assertTrue("override SQL must scope to the Acreedor group", sql.contains("'Acreedor'"));
    verify(query).setParameter("clientId", "C1");
    verify(query).setParameter("schemaId", "S1");
    verify(query).setParameter("liabilityAcctValue", "41000000");
    verify(query).setParameter("notInvoicedReceivablesAcctValue", "41090000");
    verify(query).setParameter("prepaymentAcctValue", "41700000");
    verify(query).executeUpdate();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testOverrideAcreedorGroupAccountsWarnsWhenZeroRowsAffected() {
    // Covers the defensive "0 rows" branch (the group, its C_BP_Group_Acct row, or one of the
    // 3 target accounts' C_ValidCombination may be missing) — see the log.warn on the else path.
    // All 5 parameters are still bound before the branch check runs, so this asserts the exact
    // same binding contract holds on the 0-row outcome as on the successful one above.
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(session.createNativeQuery(sqlCaptor.capture())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(0);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      // Must not throw when the defensive 0-row outcome is hit; it only logs a warning.
      service.overrideAcreedorGroupAccounts("C1", "S1");
    }

    assertTrue("override SQL must still target c_bp_group_acct on the 0-row outcome",
        sqlCaptor.getValue().contains("c_bp_group_acct"));
    verify(query).setParameter("clientId", "C1");
    verify(query).setParameter("schemaId", "S1");
    verify(query).setParameter("liabilityAcctValue", "41000000");
    verify(query).setParameter("notInvoicedReceivablesAcctValue", "41090000");
    verify(query).setParameter("prepaymentAcctValue", "41700000");
    verify(query).executeUpdate();
  }

  // ---------------------------------------------------------------------------------------------
  // ensureAcreedorPrepaymentAccount() — SQL-dispatch sequencing (real implementation)
  // ---------------------------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testEnsureAcreedorPrepaymentAccountRunsSevenDistinctNativeQueriesBoundToClientId() {
    // The method fires 7 sequential statements (group/subgroup/leaf INSERT, the defensive
    // VALIDCOMBINATION INSERT, then 3 AD_TREENODE re-parent UPDATEs) — none conditional, so a
    // single invocation exercises the whole dispatch chain. schemaId is accepted but intentionally
    // unused by every one of the 7 statements (they resolve the schema dynamically via
    // C_AcctSchema_Element instead), so only clientId binding is asserted.
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();

    OBDal dal = mock(OBDal.class);
    Session session = mock(Session.class);
    when(dal.getSession()).thenReturn(session);
    NativeQuery query = mock(NativeQuery.class);
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(session.createNativeQuery(sqlCaptor.capture())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(1);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.ensureAcreedorPrepaymentAccount("C1", "S1");
    }

    List<String> dispatched = sqlCaptor.getAllValues();
    assertEquals("group/subgroup/leaf/validcombination inserts + 3 reparent updates", 7,
        dispatched.size());
    Set<String> distinctStatements = new HashSet<>(dispatched);
    assertEquals("all 7 statements must be distinct SQL text", 7, distinctStatements.size());

    // Spot-check the chain mirrors 407/4070/40700000 with the 417/4170/41700000 values, in order.
    assertTrue("statement 1 must insert the 417 group", dispatched.get(0).contains("'417'"));
    assertTrue("statement 2 must insert the 4170 subgroup", dispatched.get(1).contains("'4170'"));
    assertTrue("statement 3 must insert the 41700000 leaf", dispatched.get(2).contains("'41700000'"));
    assertTrue("statement 4 must defensively create the C_VALIDCOMBINATION row",
        dispatched.get(3).contains("c_validcombination"));
    assertTrue("statement 5 must reparent the 417 node", dispatched.get(4).contains("ad_treenode")
        && dispatched.get(4).contains("'417'"));
    assertTrue("statement 6 must reparent the 4170 node", dispatched.get(5).contains("ad_treenode")
        && dispatched.get(5).contains("'4170'"));
    assertTrue("statement 7 must reparent the 41700000 node", dispatched.get(6).contains("ad_treenode")
        && dispatched.get(6).contains("'41700000'"));

    verify(session, times(7)).createNativeQuery(anyString());
    verify(query, times(7)).setParameter("clientId", "C1");
    verify(query, times(7)).executeUpdate();
  }

  // ---------------------------------------------------------------------------------------------
  // rebrandImportedChartNames() — real OBCriteria interaction
  // ---------------------------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testRebrandImportedChartNamesRewritesSchemaAndElements() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);
    when(client.getName()).thenReturn("Acme");
    AcctSchema ledger = mock(AcctSchema.class);
    when(ledger.getName()).thenReturn("Esquema GO");

    Element element = mock(Element.class);
    when(element.getName()).thenReturn("Arbol de cuentas GO");
    when(element.getDescription()).thenReturn("GOClient Account");

    OBDal dal = mock(OBDal.class);
    OBCriteria<Element> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Element.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.singletonList(element));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.rebrandImportedChartNames(client, ledger);
    }

    verify(ledger).setName("Esquema Acme");
    verify(dal).save(ledger);
    verify(element).setName("Arbol de cuentas Acme");
    verify(element).setDescription("Acme Account");
    verify(dal).save(element);
  }

  // ---------------------------------------------------------------------------------------------
  // resolveTenantElementValueTree() — real OBCriteria interaction
  // ---------------------------------------------------------------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveTenantElementValueTreeReturnsNullWhenNoTree() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Tree> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Tree.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertNull(service.resolveTenantElementValueTree(client));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveTenantElementValueTreeReturnsFirstWhenSingle() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);
    Tree tree = mock(Tree.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Tree> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Tree.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.singletonList(tree));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(tree, service.resolveTenantElementValueTree(client));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testResolveTenantElementValueTreeWarnsAndReturnsFirstWhenMultiple() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);
    when(client.getId()).thenReturn("C1");
    Tree first = mock(Tree.class);
    when(first.getId()).thenReturn("T1");
    Tree second = mock(Tree.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<Tree> crit = mock(OBCriteria.class);
    when(dal.createCriteria(Tree.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Arrays.asList(first, second));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(first, service.resolveTenantElementValueTree(client));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // resolveClient() — real OBDal.get delegation
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testResolveClientDelegatesToObDalGet() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);

    OBDal dal = mock(OBDal.class);
    when(dal.get(Client.class, "C1")).thenReturn(client);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertSame(client, service.resolveClient("C1"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // contextSubject()
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testContextSubjectIsAccountingWiring() {
    assertEquals("accounting wiring", new OnboardingAccountingWiringService().contextSubject());
  }

  // ---------------------------------------------------------------------------------------------
  // provisionGlItemsForImportedChart() / loadLeafElementValues() — ETP-5020
  // ---------------------------------------------------------------------------------------------

  @Test
  public void testProvisionGlItemsForImportedChartNoOpsWhenLedgerNull() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    // No OBDal mocking at all — a real (unmocked) static touch would blow up this test if the
    // null-ledger guard did not return immediately.
    service.provisionGlItemsForImportedChart(mock(Client.class), null);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testProvisionGlItemsForImportedChartNoOpsWhenNoActiveSchemas() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);
    AcctSchema ledger = mock(AcctSchema.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AcctSchema> schemaCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AcctSchema.class)).thenReturn(schemaCrit);
    when(schemaCrit.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      service.provisionGlItemsForImportedChart(client, ledger);
      verify(dal, never()).createCriteria(ElementValue.class);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testProvisionGlItemsForImportedChartProvisionsOneGlItemPerLeaf() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);
    AcctSchema ledger = mock(AcctSchema.class);
    AcctSchema activeSchema = mock(AcctSchema.class);

    ElementValue leaf1 = mock(ElementValue.class);
    ElementValue leaf2 = mock(ElementValue.class);
    AccountingCombination combo1 = mock(AccountingCombination.class);
    AccountingCombination combo2 = mock(AccountingCombination.class);
    GLItem glItem1 = mock(GLItem.class);
    GLItem glItem2 = mock(GLItem.class);
    GLItemAccounts link1 = mock(GLItemAccounts.class);
    GLItemAccounts link2 = mock(GLItemAccounts.class);

    OBDal dal = mock(OBDal.class);

    OBCriteria<AcctSchema> schemaCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AcctSchema.class)).thenReturn(schemaCrit);
    when(schemaCrit.list()).thenReturn(Collections.singletonList(activeSchema));

    OBCriteria<ElementValue> evCrit = mock(OBCriteria.class);
    when(dal.createCriteria(ElementValue.class)).thenReturn(evCrit);
    when(evCrit.list()).thenReturn(Arrays.asList(leaf1, leaf2));

    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCrit);
    when(comboCrit.uniqueResult()).thenReturn(combo1, combo2); // one natural combo per leaf
    when(comboCrit.list()).thenReturn(Collections.emptyList()); // no cross-schema GL Item to reuse

    OBCriteria<GLItemAccounts> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(linkCrit);
    when(linkCrit.uniqueResult()).thenReturn(null, null); // neither leaf provisioned yet

    OBProvider obProviderInstance = mock(OBProvider.class);
    when(obProviderInstance.get(GLItem.class)).thenReturn(glItem1, glItem2);
    when(obProviderInstance.get(GLItemAccounts.class)).thenReturn(link1, link2);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(obProviderInstance);

      service.provisionGlItemsForImportedChart(client, ledger);

      verify(obProviderInstance, times(2)).get(GLItem.class);
      verify(dal).save(glItem1);
      verify(dal).save(glItem2);
      verify(link1).setGlitemDebitAcct(combo1);
      verify(link2).setGlitemDebitAcct(combo2);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testLoadLeafElementValuesReturnsCriteriaListResult() {
    OnboardingAccountingWiringService service = new OnboardingAccountingWiringService();
    Client client = mock(Client.class);
    ElementValue leaf = mock(ElementValue.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<ElementValue> crit = mock(OBCriteria.class);
    when(dal.createCriteria(ElementValue.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Collections.singletonList(leaf));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      assertEquals(Collections.singletonList(leaf), service.loadLeafElementValues(client));
    }
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
    int provisionGlItemsCount;
    int provisionEntityCount;
    int bpGroupAcctPatchCount;
    String bpGroupAcctPatchClientId;
    int bpGroupAcctPatchRowsToReturn;

    final List<AcctInsert> acctInserts = new ArrayList<>();

    /** ETP-5020 — records call order for {@link #testWireProvisionsGlItemsAfterRebrandBeforePosting}. */
    final List<String> callOrder = new ArrayList<>();

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
      callOrder.add("rebrand");
    }

    @Override
    protected void provisionGlItemsForImportedChart(Client client, AcctSchema ledger) {
      provisionGlItemsCount++;
      callOrder.add("provisionGlItems");
    }

    @Override
    protected void provisionEntityPostingAccounts(Client client, AcctSchema ledger) {
      provisionEntityCount++;
      callOrder.add("provisionEntity");
    }

    @Override
    protected void runEntityAcctInsert(String sql, String clientId, String schemaId) {
      acctInserts.add(new AcctInsert(sql, clientId, schemaId));
    }

    @Override
    protected int runBpGroupAcctMissingColumnsPatch(String clientId) {
      bpGroupAcctPatchCount++;
      bpGroupAcctPatchClientId = clientId;
      return bpGroupAcctPatchRowsToReturn;
    }
  }

  /**
   * Records {@code runEntityAcctInsert} calls while preserving the real
   * {@code provisionEntityPostingAccounts} body, so the production statement-dispatch logic is the
   * code actually under test. {@code ensureAcreedorPrepaymentAccount} and
   * {@code overrideAcreedorGroupAccounts} are also stubbed (call counts only) because — unlike
   * {@code runEntityAcctInsert} — they run their native queries directly against
   * {@code OBDal.getInstance().getSession()} rather than through the recording seam.
   */
  private static final class InsertRecordingService extends OnboardingAccountingWiringService {
    final List<AcctInsert> acctInserts = new ArrayList<>();
    int ensureAcreedorPrepaymentAccountCount;
    int overrideAcreedorGroupAccountsCount;

    @Override
    protected void runEntityAcctInsert(String sql, String clientId, String schemaId) {
      acctInserts.add(new AcctInsert(sql, clientId, schemaId));
    }

    @Override
    protected void ensureAcreedorPrepaymentAccount(String clientId, String schemaId) {
      ensureAcreedorPrepaymentAccountCount++;
    }

    @Override
    protected void overrideAcreedorGroupAccounts(String clientId, String schemaId) {
      overrideAcreedorGroupAccountsCount++;
    }
  }
}
