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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.hibernate.criterion.Criterion;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;
import org.openbravo.model.financialmgmt.gl.GLItem;
import org.openbravo.model.financialmgmt.gl.GLItemAccounts;

/**
 * Unit tests for {@link GlItemProvisioningSupport} (ETP-5020).
 *
 * <p>Every DB-touching method is a {@code protected} seam; these tests exercise the class through
 * {@code OBDal}/{@code OBProvider} static mocks, following the same convention as
 * {@code OnboardingAccountingWiringServiceTest} and this package's own
 * {@code ChartOfAccountsHandlerTest}. No live database is required.
 */
@SuppressWarnings("unchecked")
public class GlItemProvisioningSupportTest {

  private final GlItemProvisioningSupport support = new GlItemProvisioningSupport();

  // ── resolveActiveSchemas ────────────────────────────────────────────────────

  @Test
  public void resolveActiveSchemasReturnsCriteriaListResult() {
    Client client = mock(Client.class);
    AcctSchema s1 = mock(AcctSchema.class);
    AcctSchema s2 = mock(AcctSchema.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AcctSchema> crit = mock(OBCriteria.class);
    when(dal.createCriteria(AcctSchema.class)).thenReturn(crit);
    when(crit.list()).thenReturn(Arrays.asList(s1, s2));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      List<AcctSchema> result = support.resolveActiveSchemas(client);
      assertEquals(Arrays.asList(s1, s2), result);
    }
  }

  // ── resolveNaturalCombination — predicate shape ────────────────────────────

  @Test
  public void resolveNaturalCombinationAppliesAllElevenDimensionRestrictions() {
    ElementValue subaccount = mock(ElementValue.class);
    AcctSchema schema = mock(AcctSchema.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> crit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(crit);
    ArgumentCaptor<Criterion> captor = ArgumentCaptor.forClass(Criterion.class);
    when(crit.add(captor.capture())).thenReturn(crit);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      support.resolveNaturalCombination(subaccount, schema);
    }

    // account + schema (2 eq) + the 11 dimension-IS-NULL checks mirrored from
    // C_ELEMENTVALUE_TRG.xml (product, businessPartner, trxOrganization, locationFromAddress,
    // locationToAddress, salesRegion, project, salesCampaign, activity, stDimension, ndDimension)
    // = 13 total restrictions.
    assertEquals(13, captor.getAllValues().size());
    assertTrue(captor.getAllValues().stream()
        .anyMatch(c -> c.toString().contains(AccountingCombination.PROPERTY_LOCATIONFROMADDRESS)));
    assertTrue(captor.getAllValues().stream()
        .anyMatch(c -> c.toString().contains(AccountingCombination.PROPERTY_LOCATIONTOADDRESS)));
    verify(crit).addOrderBy(AccountingCombination.PROPERTY_ID, true);
    verify(crit).setMaxResults(1);
    // ETP-5101 regression: without this, a subaccount's own cascaded deactivation makes its
    // natural combination invisible to this lookup — see the method's javadoc.
    verify(crit).setFilterOnActive(false);
  }

  // ── findGlItemAccountsByCombination — active-filter regression (ETP-5101) ─────────────────

  /**
   * ETP-5101 regression: without {@code setFilterOnActive(false)} here, the {@code
   * GLItemAccounts} row for an already-deactivated subaccount becomes invisible to this
   * idempotency lookup the moment {@link GlItemProvisioningSupport#setGlItemAccountsActiveForSchema}
   * correctly deactivates it — the very next rename/edit would then read "nothing provisioned
   * yet" and mint a duplicate row instead of resyncing the existing one.
   */
  @Test
  public void findGlItemAccountsByCombinationFiltersOnActiveFalse() {
    AccountingCombination combo = mock(AccountingCombination.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<GLItemAccounts> crit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(crit);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      support.findGlItemAccountsByCombination(combo);
    }

    verify(crit).setFilterOnActive(false);
    verify(crit).setMaxResults(1);
  }

  // ── findGlItemLinkedToAnyCombinationOf — active-filter regression (ETP-5101) ──────────────

  /**
   * ETP-5101 regression: this method's inner {@code AccountingCombination} criteria (the
   * multi-schema idempotency fallback) must ALSO ignore active state — a deactivated
   * subaccount's combinations must stay findable here too, or a later reactivation / new-schema
   * pass would mint a second GL Item instead of reusing the existing one.
   */
  @Test
  public void findGlItemLinkedToAnyCombinationOfFiltersOnActiveFalseForComboCriteria() {
    ElementValue subaccount = mock(ElementValue.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> comboCriteria = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCriteria);
    when(comboCriteria.list()).thenReturn(Collections.emptyList());

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      support.findGlItemLinkedToAnyCombinationOf(subaccount);
    }

    verify(comboCriteria).setFilterOnActive(false);
  }

  // ── ensureGlItemForSubaccount — guards ─────────────────────────────────────

  @Test
  public void ensureGlItemForSubaccountNoOpsWhenSubaccountNull() {
    support.ensureGlItemForSubaccount(null, Collections.singletonList(mock(AcctSchema.class)));
    // no exception, no static OBDal/OBProvider touch — nothing to assert beyond "did not throw"
  }

  @Test
  public void ensureGlItemForSubaccountNoOpsWhenSchemasEmpty() {
    support.ensureGlItemForSubaccount(mock(ElementValue.class), Collections.emptyList());
  }

  @Test
  public void ensureGlItemForSubaccountNoOpsWhenSchemasNull() {
    support.ensureGlItemForSubaccount(mock(ElementValue.class), null);
  }

  // ── ensureGlItemForSubaccount — Case 3: no natural combination ─────────────

  @Test
  public void ensureGlItemForSubaccountSkipsSchemaWithNoNaturalCombination() {
    ElementValue subaccount = mock(ElementValue.class);
    AcctSchema schema = mock(AcctSchema.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCrit);
    when(comboCrit.uniqueResult()).thenReturn(null); // summary/heading account — no combination

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      support.ensureGlItemForSubaccount(subaccount, Collections.singletonList(schema));

      obProvider.verify(OBProvider::getInstance, never());
      verify(dal, never()).createCriteria(GLItemAccounts.class);
    }
  }

  // ── ensureGlItemForSubaccount — fresh creation ─────────────────────────────

  @Test
  public void ensureGlItemForSubaccountCreatesGlItemAndAccountsWhenNoneExist() {
    ElementValue subaccount = mock(ElementValue.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(subaccount.getClient()).thenReturn(client);
    when(subaccount.getOrganization()).thenReturn(org);
    when(subaccount.getName()).thenReturn("Caja Euros");
    when(subaccount.getSearchKey()).thenReturn("20000001"); // ETP-5101: code+name composition
    // ETP-5101: both created records now DERIVE their active state from the subaccount instead of
    // hardcoding true, so the happy path has to say the subaccount is active for it to stay so.
    when(subaccount.isActive()).thenReturn(true);

    AcctSchema schema = mock(AcctSchema.class);
    AccountingCombination combo = mock(AccountingCombination.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCrit);
    when(comboCrit.uniqueResult()).thenReturn(combo);
    when(comboCrit.list()).thenReturn(Collections.emptyList()); // no other combos linked yet

    OBCriteria<GLItemAccounts> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(linkCrit);
    when(linkCrit.uniqueResult()).thenReturn(null); // no existing GLItemAccounts row

    GLItem glItem = mock(GLItem.class);
    when(glItem.getClient()).thenReturn(client);
    when(glItem.getOrganization()).thenReturn(org);
    GLItemAccounts link = mock(GLItemAccounts.class);

    OBProvider obProviderInstance = mock(OBProvider.class);
    when(obProviderInstance.get(GLItem.class)).thenReturn(glItem);
    when(obProviderInstance.get(GLItemAccounts.class)).thenReturn(link);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(obProviderInstance);

      support.ensureGlItemForSubaccount(subaccount, Collections.singletonList(schema));

      verify(glItem).setNewOBObject(true);
      verify(glItem).setClient(client);
      verify(glItem).setOrganization(org);
      verify(glItem).setName("20000001-Caja Euros");
      verify(glItem).setActive(true);
      verify(dal).save(glItem);

      verify(link).setNewOBObject(true);
      verify(link).setClient(client);
      verify(link).setOrganization(org);
      verify(link).setGLItem(glItem);
      verify(link).setAccountingSchema(schema);
      verify(link).setGlitemDebitAcct(combo);
      verify(link).setGlitemCreditAcct(combo);
      verify(link).setActive(true);
      verify(dal).save(link);
    }
  }

  // ── ensureGlItemForSubaccount — created records mirror the subaccount's active state ──────
  //
  // ETP-5101 review finding (B1). resolveNaturalCombination's setFilterOnActive(false) — added so
  // active-state sync and rename sync keep working after a subaccount is deactivated — also made
  // the CREATE path reachable for an inactive, not-yet-provisioned subaccount: hook H
  // (ChartOfAccountsHandler.syncGlItemNameAfterUpdate) fires on any PATCH/PUT touching
  // name/searchKey, with no active-state guard upstream. With createGlItem/createGlItemAccounts
  // hardcoding setActive(true), a plain rename of an INACTIVE subaccount silently minted an
  // ACTIVE, selectable GL Item (and link) behind it — the very divergence hook G exists to
  // prevent, just on the create path instead of the update path.

  /**
   * The bug scenario end-to-end: renaming an inactive, never-provisioned subaccount reaches the
   * create branch (its natural combination is still findable thanks to
   * {@code setFilterOnActive(false)}, and no {@code GLItemAccounts} row exists yet), and BOTH the
   * new {@link GLItem} and its {@link GLItemAccounts} row must come out INACTIVE.
   */
  @Test
  public void ensureGlItemForSubaccountCreatesInactiveRecordsForInactiveSubaccount() {
    ElementValue subaccount = mock(ElementValue.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(subaccount.getClient()).thenReturn(client);
    when(subaccount.getOrganization()).thenReturn(org);
    when(subaccount.getName()).thenReturn("Caja Euros");
    when(subaccount.getSearchKey()).thenReturn("20000001");
    when(subaccount.isActive()).thenReturn(false); // deactivated, then renamed (hook H)

    AcctSchema schema = mock(AcctSchema.class);
    AccountingCombination combo = mock(AccountingCombination.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCrit);
    // setFilterOnActive(false) keeps the deactivated subaccount's natural combination findable.
    when(comboCrit.uniqueResult()).thenReturn(combo);
    when(comboCrit.list()).thenReturn(Collections.emptyList());

    OBCriteria<GLItemAccounts> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(linkCrit);
    when(linkCrit.uniqueResult()).thenReturn(null); // never provisioned — create branch

    GLItem glItem = mock(GLItem.class);
    when(glItem.getClient()).thenReturn(client);
    when(glItem.getOrganization()).thenReturn(org);
    GLItemAccounts link = mock(GLItemAccounts.class);

    OBProvider obProviderInstance = mock(OBProvider.class);
    when(obProviderInstance.get(GLItem.class)).thenReturn(glItem);
    when(obProviderInstance.get(GLItemAccounts.class)).thenReturn(link);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(obProviderInstance);

      support.ensureGlItemForSubaccount(subaccount, Collections.singletonList(schema));

      // The GL Item is created, but INACTIVE — never active behind an inactive subaccount.
      verify(glItem).setActive(false);
      verify(glItem, never()).setActive(true);
      verify(dal).save(glItem);

      // Same for the GLItemAccounts link — an active link would leave the account selectable.
      verify(link).setActive(false);
      verify(link, never()).setActive(true);
      verify(dal).save(link);
    }
  }

  // ── createGlItem / createGlItemAccounts — focused active-state pins (ETP-5101) ────────────

  @Test
  public void createGlItemMirrorsInactiveSubaccountActiveState() {
    ElementValue subaccount = mock(ElementValue.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(subaccount.getClient()).thenReturn(client);
    when(subaccount.getOrganization()).thenReturn(org);
    when(subaccount.getName()).thenReturn("Caja Euros");
    when(subaccount.getSearchKey()).thenReturn("20000001");
    when(subaccount.isActive()).thenReturn(false);

    GLItem glItem = mock(GLItem.class);
    OBProvider obProviderInstance = mock(OBProvider.class);
    when(obProviderInstance.get(GLItem.class)).thenReturn(glItem);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(obProviderInstance);

      GLItem created = support.createGlItem(subaccount);

      assertEquals(glItem, created);
      verify(glItem).setActive(false);
      verify(glItem, never()).setActive(true);
    }
  }

  @Test
  public void createGlItemMirrorsActiveSubaccountActiveState() {
    ElementValue subaccount = mock(ElementValue.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(subaccount.getClient()).thenReturn(client);
    when(subaccount.getOrganization()).thenReturn(org);
    when(subaccount.getName()).thenReturn("Caja Euros");
    when(subaccount.getSearchKey()).thenReturn("20000001");
    when(subaccount.isActive()).thenReturn(true);

    GLItem glItem = mock(GLItem.class);
    OBProvider obProviderInstance = mock(OBProvider.class);
    when(obProviderInstance.get(GLItem.class)).thenReturn(glItem);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(obProviderInstance);

      support.createGlItem(subaccount);

      verify(glItem).setActive(true);
      verify(glItem, never()).setActive(false);
    }
  }

  /**
   * {@code ElementValue.isActive()} is a {@code Boolean}, so it can legitimately read {@code null}
   * for a not-yet-flushed object. {@code Boolean.TRUE.equals(...)} makes that resolve to INACTIVE
   * (fail-closed) rather than throwing or defaulting to active — pinned here so a future rewrite
   * to {@code subaccount.isActive()} (auto-unboxing) is caught.
   */
  @Test
  public void createGlItemTreatsNullActiveFlagAsInactive() {
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.getClient()).thenReturn(mock(Client.class));
    when(subaccount.getOrganization()).thenReturn(mock(Organization.class));
    when(subaccount.getName()).thenReturn("Caja Euros");
    when(subaccount.getSearchKey()).thenReturn("20000001");
    when(subaccount.isActive()).thenReturn(null);

    GLItem glItem = mock(GLItem.class);
    OBProvider obProviderInstance = mock(OBProvider.class);
    when(obProviderInstance.get(GLItem.class)).thenReturn(glItem);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(obProviderInstance);

      support.createGlItem(subaccount);

      verify(glItem).setActive(false);
    }
  }

  @Test
  public void createGlItemAccountsMirrorsInactiveSubaccountActiveState() {
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.isActive()).thenReturn(false);
    AcctSchema schema = mock(AcctSchema.class);
    AccountingCombination combo = mock(AccountingCombination.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    GLItem glItem = mock(GLItem.class);
    when(glItem.getClient()).thenReturn(client);
    when(glItem.getOrganization()).thenReturn(org);

    GLItemAccounts link = mock(GLItemAccounts.class);
    OBProvider obProviderInstance = mock(OBProvider.class);
    when(obProviderInstance.get(GLItemAccounts.class)).thenReturn(link);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(obProviderInstance);

      support.createGlItemAccounts(glItem, schema, combo, subaccount);

      // The link's active state comes from the SUBACCOUNT, not from a hardcoded true (and not
      // from the GL Item either — the two are provisioned together and must agree).
      verify(link).setActive(false);
      verify(link, never()).setActive(true);
      verify(link).setGLItem(glItem);
      verify(link).setAccountingSchema(schema);
      verify(link).setGlitemDebitAcct(combo);
      verify(link).setGlitemCreditAcct(combo);
      verify(dal).save(link);
    }
  }

  @Test
  public void createGlItemAccountsMirrorsActiveSubaccountActiveState() {
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.isActive()).thenReturn(true);
    AcctSchema schema = mock(AcctSchema.class);
    AccountingCombination combo = mock(AccountingCombination.class);
    GLItem glItem = mock(GLItem.class);
    when(glItem.getClient()).thenReturn(mock(Client.class));
    when(glItem.getOrganization()).thenReturn(mock(Organization.class));

    GLItemAccounts link = mock(GLItemAccounts.class);
    OBProvider obProviderInstance = mock(OBProvider.class);
    when(obProviderInstance.get(GLItemAccounts.class)).thenReturn(link);
    OBDal dal = mock(OBDal.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(obProviderInstance);

      support.createGlItemAccounts(glItem, schema, combo, subaccount);

      verify(link).setActive(true);
      verify(link, never()).setActive(false);
      verify(dal).save(link);
    }
  }

  // ── ensureGlItemForSubaccount — idempotency ────────────────────────────────

  @Test
  public void ensureGlItemForSubaccountReusesExistingLinkAndSyncsRenamedName() {
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.getName()).thenReturn("New Name");
    when(subaccount.getSearchKey()).thenReturn("30000002"); // ETP-5101: code+name composition

    AcctSchema schema = mock(AcctSchema.class);
    AccountingCombination combo = mock(AccountingCombination.class);
    GLItem existingGlItem = mock(GLItem.class);
    when(existingGlItem.getName()).thenReturn("Old Name");
    GLItemAccounts existingLink = mock(GLItemAccounts.class);
    when(existingLink.getGLItem()).thenReturn(existingGlItem);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCrit);
    when(comboCrit.uniqueResult()).thenReturn(combo);

    OBCriteria<GLItemAccounts> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(linkCrit);
    when(linkCrit.uniqueResult()).thenReturn(existingLink);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      support.ensureGlItemForSubaccount(subaccount, Collections.singletonList(schema));

      // Idempotent: no new GLItem/GLItemAccounts minted.
      obProvider.verify(OBProvider::getInstance, never());
      // Rename propagated onto the already-linked GL Item.
      verify(existingGlItem).setName("30000002-New Name");
      verify(dal).save(existingGlItem);
    }
  }

  @Test
  public void ensureGlItemForSubaccountDoesNotResaveWhenNameAlreadyMatches() {
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.getName()).thenReturn("Same Name");
    when(subaccount.getSearchKey()).thenReturn("40000003"); // ETP-5101: code+name composition

    AcctSchema schema = mock(AcctSchema.class);
    AccountingCombination combo = mock(AccountingCombination.class);
    GLItem existingGlItem = mock(GLItem.class);
    // Already stores the composed name — nothing to resync.
    when(existingGlItem.getName()).thenReturn("40000003-Same Name");
    GLItemAccounts existingLink = mock(GLItemAccounts.class);
    when(existingLink.getGLItem()).thenReturn(existingGlItem);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCrit);
    when(comboCrit.uniqueResult()).thenReturn(combo);

    OBCriteria<GLItemAccounts> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(linkCrit);
    when(linkCrit.uniqueResult()).thenReturn(existingLink);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      support.ensureGlItemForSubaccount(subaccount, Collections.singletonList(schema));

      verify(existingGlItem, never()).setName(any());
      verify(dal, never()).save(existingGlItem);
    }
  }

  // ── ensureGlItemForSubaccount — multi-schema (2+ active AcctSchema) ────────

  @Test
  public void ensureGlItemForSubaccountCreatesOneAccountsRowPerSchemaReusingSameGlItem() {
    ElementValue subaccount = mock(ElementValue.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(subaccount.getClient()).thenReturn(client);
    when(subaccount.getOrganization()).thenReturn(org);
    when(subaccount.getName()).thenReturn("Caja Euros");

    AcctSchema schema1 = mock(AcctSchema.class);
    AcctSchema schema2 = mock(AcctSchema.class);
    AccountingCombination combo1 = mock(AccountingCombination.class);
    AccountingCombination combo2 = mock(AccountingCombination.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCrit);
    when(comboCrit.uniqueResult()).thenReturn(combo1, combo2); // one call per schema
    when(comboCrit.list()).thenReturn(Collections.emptyList()); // fallback lookup, schema1 only

    OBCriteria<GLItemAccounts> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(linkCrit);
    when(linkCrit.uniqueResult()).thenReturn(null, null); // neither schema provisioned yet

    GLItem glItem = mock(GLItem.class);
    when(glItem.getClient()).thenReturn(client);
    when(glItem.getOrganization()).thenReturn(org);
    GLItemAccounts link1 = mock(GLItemAccounts.class);
    GLItemAccounts link2 = mock(GLItemAccounts.class);

    OBProvider obProviderInstance = mock(OBProvider.class);
    when(obProviderInstance.get(GLItem.class)).thenReturn(glItem);
    when(obProviderInstance.get(GLItemAccounts.class)).thenReturn(link1, link2);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(obProviderInstance);

      support.ensureGlItemForSubaccount(subaccount, Arrays.asList(schema1, schema2));

      verify(obProviderInstance, times(1)).get(GLItem.class); // one GL Item for both schemas
      verify(obProviderInstance, times(2)).get(GLItemAccounts.class);
      verify(link1).setGLItem(glItem);
      verify(link1).setAccountingSchema(schema1);
      verify(link1).setGlitemDebitAcct(combo1);
      verify(link2).setGLItem(glItem);
      verify(link2).setAccountingSchema(schema2);
      verify(link2).setGlitemDebitAcct(combo2);
    }
  }

  @Test
  public void ensureGlItemForSubaccountReusesGlItemFoundViaOtherSchemaCombination() {
    // Simulates a schema becoming active AFTER the subaccount already has a GL Item from an
    // earlier schema — the multi-schema idempotency fallback (findGlItemLinkedToAnyCombinationOf).
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.getName()).thenReturn("Caja Euros");

    AcctSchema newSchema = mock(AcctSchema.class);
    AccountingCombination comboForNewSchema = mock(AccountingCombination.class);
    AccountingCombination otherSchemaCombo = mock(AccountingCombination.class);
    GLItem existingGlItem = mock(GLItem.class);
    GLItemAccounts existingOtherLink = mock(GLItemAccounts.class);
    when(existingOtherLink.getGLItem()).thenReturn(existingGlItem);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCrit);
    when(comboCrit.uniqueResult()).thenReturn(comboForNewSchema);
    when(comboCrit.list()).thenReturn(Collections.singletonList(otherSchemaCombo));

    OBCriteria<GLItemAccounts> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(linkCrit);
    // 1st call: findGlItemAccountsByCombination(comboForNewSchema) -> null (not provisioned yet)
    // 2nd call: findGlItemAccountsByCombination(otherSchemaCombo) inside the fallback -> found
    when(linkCrit.uniqueResult()).thenReturn(null, existingOtherLink);

    GLItemAccounts newLink = mock(GLItemAccounts.class);
    OBProvider obProviderInstance = mock(OBProvider.class);
    when(obProviderInstance.get(GLItemAccounts.class)).thenReturn(newLink);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(obProviderInstance);

      support.ensureGlItemForSubaccount(subaccount, Collections.singletonList(newSchema));

      verify(obProviderInstance, never()).get(GLItem.class); // reused, not created
      verify(newLink).setGLItem(existingGlItem);
      verify(newLink).setAccountingSchema(newSchema);
      verify(newLink).setGlitemDebitAcct(comboForNewSchema);
    }
  }

  // ── ensureGlItemForSubaccount — failure isolation ──────────────────────────

  @Test
  public void ensureGlItemForSubaccountSwallowsExceptionsAndNeverPropagates() {
    ElementValue subaccount = mock(ElementValue.class);
    AcctSchema schema = mock(AcctSchema.class);

    OBDal dal = mock(OBDal.class);
    when(dal.createCriteria(AccountingCombination.class))
        .thenThrow(new RuntimeException("simulated DB failure"));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      // Must not throw — a GL Item provisioning defect can never block the caller's save.
      support.ensureGlItemForSubaccount(subaccount, Collections.singletonList(schema));
    }
  }

  @Test
  public void ensureGlItemForSubaccountContinuesAfterOneSchemaFails() {
    ElementValue subaccount = mock(ElementValue.class);
    AcctSchema failingSchema = mock(AcctSchema.class);
    when(failingSchema.getId()).thenReturn("SCHEMA-FAIL");
    AcctSchema succeedingSchema = mock(AcctSchema.class);
    GLItem glItem = mock(GLItem.class);

    class TestableSupport extends GlItemProvisioningSupport {
      private int calls;

      @Override
      protected GLItem ensureGlItemForSchema(ElementValue subaccount, AcctSchema schema,
          GLItem reusableGlItem) {
        calls++;
        if (schema == failingSchema) {
          throw new RuntimeException("schema-specific failure");
        }
        return glItem;
      }
    }

    TestableSupport testableSupport = new TestableSupport();

    testableSupport.ensureGlItemForSubaccount(subaccount,
        Arrays.asList(failingSchema, succeedingSchema));

    assertEquals(2, testableSupport.calls);
  }

  /**
   * Closes the coverage gap flagged in Alex's last review round: {@code
   * ensureGlItemForSubaccountContinuesAfterOneSchemaFails} above overrides {@code
   * ensureGlItemForSchema} entirely, so it never exercises the REAL {@code reusableGlItem}
   * threading through {@link GlItemProvisioningSupport#doEnsureGlItemForSubaccount}'s loop. This
   * test runs the actual (unoverridden) production logic for 3 schemas, with the middle schema
   * failing for a real reason (a DB-lookup exception, not a test double), and asserts that the
   * GL Item minted for schema 1 is still correctly reused for schema 3 — i.e. a schema-2 failure
   * does not corrupt or reset the {@code reusableGlItem} the loop threads across iterations.
   */
  @Test
  public void ensureGlItemForSubaccountThreadsReusableGlItemAcrossARealMidLoopFailure() {
    ElementValue subaccount = mock(ElementValue.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(subaccount.getClient()).thenReturn(client);
    when(subaccount.getOrganization()).thenReturn(org);
    when(subaccount.getName()).thenReturn("Caja Euros");

    AcctSchema schema1 = mock(AcctSchema.class);
    AcctSchema failingSchema2 = mock(AcctSchema.class);
    AcctSchema schema3 = mock(AcctSchema.class);
    AccountingCombination combo1 = mock(AccountingCombination.class);
    AccountingCombination combo3 = mock(AccountingCombination.class);

    OBDal dal = mock(OBDal.class);

    // dal.createCriteria(AccountingCombination.class) is called 4 times across the whole run:
    //   #1 schema1 resolveNaturalCombination -> comboCrit
    //   #2 schema1 findGlItemLinkedToAnyCombinationOf fallback (reusableGlItem still null)
    //   #3 failingSchema2 resolveNaturalCombination -> throws a REAL exception (simulated DB
    //      failure), never reaching the reusableGlItem parameter at all
    //   #4 schema3 resolveNaturalCombination -> comboCrit (no fallback call: reusableGlItem
    //      carried over from schema1 is already non-null, so findGlItemLinkedToAnyCombinationOf
    //      is never invoked for schema3)
    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    RuntimeException simulatedFailure = new RuntimeException("simulated schema-2 DB failure");
    when(dal.createCriteria(AccountingCombination.class))
        .thenReturn(comboCrit, comboCrit)
        .thenThrow(simulatedFailure)
        .thenReturn(comboCrit);
    when(comboCrit.uniqueResult()).thenReturn(combo1, combo3);
    when(comboCrit.list()).thenReturn(Collections.emptyList()); // schema1's fallback: nothing else linked yet

    OBCriteria<GLItemAccounts> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(linkCrit);
    when(linkCrit.uniqueResult()).thenReturn(null, null); // neither schema1 nor schema3 provisioned yet

    GLItem glItem = mock(GLItem.class);
    when(glItem.getClient()).thenReturn(client);
    when(glItem.getOrganization()).thenReturn(org);
    GLItemAccounts link1 = mock(GLItemAccounts.class);
    GLItemAccounts link3 = mock(GLItemAccounts.class);

    OBProvider obProviderInstance = mock(OBProvider.class);
    when(obProviderInstance.get(GLItem.class)).thenReturn(glItem);
    when(obProviderInstance.get(GLItemAccounts.class)).thenReturn(link1, link3);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(obProviderInstance);

      // Real production code path — no method overrides, no test double.
      support.ensureGlItemForSubaccount(subaccount,
          Arrays.asList(schema1, failingSchema2, schema3));

      // Exactly one GL Item minted for the whole run: schema1 created it, schema3 reused the
      // SAME instance despite schema2 throwing in between — the reusableGlItem thread survived.
      verify(obProviderInstance, times(1)).get(GLItem.class);
      verify(obProviderInstance, times(2)).get(GLItemAccounts.class);

      verify(link1).setGLItem(glItem);
      verify(link1).setAccountingSchema(schema1);
      verify(link1).setGlitemDebitAcct(combo1);

      verify(link3).setGLItem(glItem);
      verify(link3).setAccountingSchema(schema3);
      verify(link3).setGlitemDebitAcct(combo3);

      // Exactly 2 GLItemAccounts rows were saved for the whole run (schema1 + schema3) — nothing
      // was ever created for the failing schema in between.
      verify(dal, times(2)).save(any(GLItemAccounts.class));
    }
  }

  // ── composeGlItemName — ETP-5101 GL_ITEM_NAME_MAX_LENGTH (60) truncation ──────────────────
  //
  // C_Glitem.Name is varchar(60); composeGlItemName must never let the composed "<code>-<name>"
  // exceed that, and must NEVER truncate the code itself (see class javadoc on
  // GL_ITEM_NAME_MAX_LENGTH / composeGlItemName). composeGlItemName is a protected static pure
  // function, so it's exercised directly here (same package); one end-to-end test further below
  // additionally pins the real GLItem.setName(...) call so a regression in the wiring — not just
  // the pure function — is also caught.

  @Test
  public void composeGlItemNameDoesNotTruncateWhenWellUnderLimit() {
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.getName()).thenReturn("Caja Euros");
    when(subaccount.getSearchKey()).thenReturn("20000001");

    String composed = GlItemProvisioningSupport.composeGlItemName(subaccount);

    assertEquals("20000001-Caja Euros", composed);
    assertTrue(composed.length() < 60);
  }

  @Test
  public void composeGlItemNameTruncatesLongNameKeepingCodeIntact() {
    String longName = repeat('A', 80);
    String code = "20000001"; // 8 chars -> prefix code + "-" = 9 chars -> name budget = 51
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.getName()).thenReturn(longName);
    when(subaccount.getSearchKey()).thenReturn(code);

    String composed = GlItemProvisioningSupport.composeGlItemName(subaccount);

    assertEquals(60, composed.length());
    assertEquals(code + "-" + longName.substring(0, 51), composed);
    assertTrue("code must survive intact, never truncated", composed.startsWith(code + "-"));
  }

  @Test
  public void composeGlItemNameDoesNotTruncateAtExactSixtyCharBoundary() {
    String code = "20000001"; // prefix code + "-" = 9 chars -> name budget = 51
    String name = repeat('B', 51); // 51 + 9 = 60 exactly
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.getName()).thenReturn(name);
    when(subaccount.getSearchKey()).thenReturn(code);

    String composed = GlItemProvisioningSupport.composeGlItemName(subaccount);

    assertEquals(60, composed.length());
    assertEquals(code + "-" + name, composed); // full name preserved, nothing cut
  }

  @Test
  public void composeGlItemNameTruncatesAtSixtyOneCharsByExactlyOneChar() {
    String code = "20000001";
    String name = repeat('C', 52); // 52 + 9 = 61 -> exactly 1 char over the limit
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.getName()).thenReturn(name);
    when(subaccount.getSearchKey()).thenReturn(code);

    String composed = GlItemProvisioningSupport.composeGlItemName(subaccount);

    assertEquals(60, composed.length());
    assertEquals(code + "-" + name.substring(0, 51), composed);
    assertTrue("code must survive intact at the start, never truncated", composed.startsWith(code));
  }

  @Test
  public void composeGlItemNameTruncatesLongNameWhenNoCodePresent() {
    String longName = repeat('D', 80);
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.getName()).thenReturn(longName);
    when(subaccount.getSearchKey()).thenReturn(null);

    String composed = GlItemProvisioningSupport.composeGlItemName(subaccount);

    assertEquals(60, composed.length());
    assertEquals(longName.substring(0, 60), composed);
    assertTrue("no trailing space/code artifact from the absent code-append logic",
        !composed.endsWith(" "));
  }

  @Test
  public void composeGlItemNameTruncatesLongNameWhenCodeIsEmptyString() {
    String longName = repeat('E', 80);
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.getName()).thenReturn(longName);
    when(subaccount.getSearchKey()).thenReturn("");

    String composed = GlItemProvisioningSupport.composeGlItemName(subaccount);

    assertEquals(60, composed.length());
    assertEquals(longName.substring(0, 60), composed);
  }

  /**
   * End-to-end pin: {@link GlItemProvisioningSupport#createGlItem} (via
   * {@link GlItemProvisioningSupport#ensureGlItemForSubaccount}) must actually call
   * {@code GLItem.setName(...)} with the truncated composed name — not just
   * {@code composeGlItemName} in isolation — so a regression in the wiring between the two is
   * also caught, mirroring {@code ensureGlItemForSubaccountCreatesGlItemAndAccountsWhenNoneExist}
   * above but with a name long enough to require truncation.
   */
  @Test
  public void ensureGlItemForSubaccountTruncatesLongNameToSixtyCharsWhenCreatingGlItem() {
    ElementValue subaccount = mock(ElementValue.class);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(subaccount.getClient()).thenReturn(client);
    when(subaccount.getOrganization()).thenReturn(org);
    String longName = repeat('F', 80);
    String code = "20000001";
    when(subaccount.getName()).thenReturn(longName);
    when(subaccount.getSearchKey()).thenReturn(code);

    AcctSchema schema = mock(AcctSchema.class);
    AccountingCombination combo = mock(AccountingCombination.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCrit);
    when(comboCrit.uniqueResult()).thenReturn(combo);
    when(comboCrit.list()).thenReturn(Collections.emptyList());

    OBCriteria<GLItemAccounts> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(linkCrit);
    when(linkCrit.uniqueResult()).thenReturn(null);

    GLItem glItem = mock(GLItem.class);
    when(glItem.getClient()).thenReturn(client);
    when(glItem.getOrganization()).thenReturn(org);
    GLItemAccounts link = mock(GLItemAccounts.class);

    OBProvider obProviderInstance = mock(OBProvider.class);
    when(obProviderInstance.get(GLItem.class)).thenReturn(glItem);
    when(obProviderInstance.get(GLItemAccounts.class)).thenReturn(link);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(obProviderInstance);

      support.ensureGlItemForSubaccount(subaccount, Collections.singletonList(schema));

      ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
      verify(glItem).setName(nameCaptor.capture());
      String expectedName = code + "-" + longName.substring(0, 51);
      assertEquals(60, nameCaptor.getValue().length());
      assertEquals(expectedName, nameCaptor.getValue());
      assertTrue("code must survive intact at the start, never truncated",
          nameCaptor.getValue().startsWith(code));
    }
  }

  /** Builds a {@code length}-char string of {@code c} repeated, without relying on Java 11's {@code String.repeat}. */
  private static String repeat(char c, int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(c);
    }
    return sb.toString();
  }

  // ── setGlItemAccountsActiveForSubaccount — deactivate / reactivate ─────────

  @Test
  public void setGlItemAccountsActiveDeactivatesExistingLink() {
    ElementValue subaccount = mock(ElementValue.class);
    AcctSchema schema = mock(AcctSchema.class);
    AccountingCombination combo = mock(AccountingCombination.class);
    GLItemAccounts link = mock(GLItemAccounts.class);
    when(link.isActive()).thenReturn(true);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCrit);
    when(comboCrit.uniqueResult()).thenReturn(combo);

    OBCriteria<GLItemAccounts> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(linkCrit);
    when(linkCrit.uniqueResult()).thenReturn(link);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      support.setGlItemAccountsActiveForSubaccount(subaccount, Collections.singletonList(schema),
          false);

      verify(link).setActive(false);
      verify(dal).save(link);
    }
  }

  @Test
  public void setGlItemAccountsActiveReactivatesExistingLink() {
    ElementValue subaccount = mock(ElementValue.class);
    AcctSchema schema = mock(AcctSchema.class);
    AccountingCombination combo = mock(AccountingCombination.class);
    GLItemAccounts link = mock(GLItemAccounts.class);
    when(link.isActive()).thenReturn(false);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCrit);
    when(comboCrit.uniqueResult()).thenReturn(combo);

    OBCriteria<GLItemAccounts> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(linkCrit);
    when(linkCrit.uniqueResult()).thenReturn(link);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      support.setGlItemAccountsActiveForSubaccount(subaccount, Collections.singletonList(schema),
          true);

      verify(link).setActive(true);
      verify(dal).save(link);
    }
  }

  @Test
  public void setGlItemAccountsActiveIsNoOpWhenAlreadyInDesiredState() {
    ElementValue subaccount = mock(ElementValue.class);
    AcctSchema schema = mock(AcctSchema.class);
    AccountingCombination combo = mock(AccountingCombination.class);
    GLItemAccounts link = mock(GLItemAccounts.class);
    when(link.isActive()).thenReturn(true);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCrit);
    when(comboCrit.uniqueResult()).thenReturn(combo);

    OBCriteria<GLItemAccounts> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(linkCrit);
    when(linkCrit.uniqueResult()).thenReturn(link);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      support.setGlItemAccountsActiveForSubaccount(subaccount, Collections.singletonList(schema),
          true);

      verify(link, never()).setActive(any(Boolean.class));
      verify(dal, never()).save(link);
    }
  }

  @Test
  public void setGlItemAccountsActiveNoOpsWhenNothingProvisionedYet() {
    ElementValue subaccount = mock(ElementValue.class);
    AcctSchema schema = mock(AcctSchema.class);
    AccountingCombination combo = mock(AccountingCombination.class);

    OBDal dal = mock(OBDal.class);
    OBCriteria<AccountingCombination> comboCrit = mock(OBCriteria.class);
    when(dal.createCriteria(AccountingCombination.class)).thenReturn(comboCrit);
    when(comboCrit.uniqueResult()).thenReturn(combo);

    OBCriteria<GLItemAccounts> linkCrit = mock(OBCriteria.class);
    when(dal.createCriteria(GLItemAccounts.class)).thenReturn(linkCrit);
    when(linkCrit.uniqueResult()).thenReturn(null); // never provisioned (e.g. pre-ETP-5020 data)

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      // Must not throw even though there is nothing to (de)activate.
      support.setGlItemAccountsActiveForSubaccount(subaccount, Collections.singletonList(schema),
          false);
    }
  }

  @Test
  public void setGlItemAccountsActiveSwallowsExceptions() {
    ElementValue subaccount = mock(ElementValue.class);
    AcctSchema schema = mock(AcctSchema.class);

    OBDal dal = mock(OBDal.class);
    when(dal.createCriteria(AccountingCombination.class))
        .thenThrow(new RuntimeException("simulated DB failure"));

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      support.setGlItemAccountsActiveForSubaccount(subaccount, Collections.singletonList(schema),
          true);
    }
  }

  @Test
  public void setGlItemAccountsActiveContinuesAfterOneSchemaFails() {
    ElementValue subaccount = mock(ElementValue.class);
    AcctSchema failingSchema = mock(AcctSchema.class);
    when(failingSchema.getId()).thenReturn("SCHEMA-FAIL");
    AcctSchema succeedingSchema = mock(AcctSchema.class);

    class TestableSupport extends GlItemProvisioningSupport {
      private int calls;

      @Override
      protected void setGlItemAccountsActiveForSchema(ElementValue subaccount, AcctSchema schema,
          boolean active) {
        calls++;
        if (schema == failingSchema) {
          throw new RuntimeException("schema-specific failure");
        }
      }
    }

    TestableSupport testableSupport = new TestableSupport();

    testableSupport.setGlItemAccountsActiveForSubaccount(subaccount,
        Arrays.asList(failingSchema, succeedingSchema), false);

    assertEquals(2, testableSupport.calls);
  }

  @Test
  public void setGlItemAccountsActiveNoOpsWhenSubaccountNull() {
    support.setGlItemAccountsActiveForSubaccount(null, Collections.singletonList(mock(AcctSchema.class)),
        true);
  }

  @Test
  public void setGlItemAccountsActiveNoOpsWhenSchemasNull() {
    // Symmetric guard to ensureGlItemForSubaccountNoOpsWhenSchemasNull — no exception, no static
    // OBDal touch — nothing to assert beyond "did not throw".
    support.setGlItemAccountsActiveForSubaccount(mock(ElementValue.class), null, true);
  }

  @Test
  public void setGlItemAccountsActiveNoOpsWhenSchemasEmpty() {
    // Symmetric guard to ensureGlItemForSubaccountNoOpsWhenSchemasEmpty.
    support.setGlItemAccountsActiveForSubaccount(mock(ElementValue.class), Collections.emptyList(),
        false);
  }

  // ── Full bug-scenario regression (ETP-5101) ────────────────────────────────
  //
  // The other tests above pin the QUERY-level half of the fix (setFilterOnActive(false) is
  // actually called). This test pins the CALLER-level half: even when resolveNaturalCombination
  // and findGlItemAccountsByCombination hand back an already-INACTIVE AccountingCombination /
  // GLItemAccounts row (exactly what an active-filtered query would have hidden, and exactly what
  // core's deactivation cascade produces — see class javadoc), setGlItemAccountsActiveForSchema
  // must still treat them as found and proceed to sync, never silently re-derive "no accounting
  // use" (Case 3) from their inactive state. The two lookup seams are overridden directly,
  // mirroring OnboardingAccountingWiringServiceTest's TestableService pattern referenced in this
  // class's own javadoc, so this test is independent of the OBCriteria/setFilterOnActive plumbing
  // already covered above.

  @Test
  public void setGlItemAccountsActiveForSchemaSyncsAnAlreadyInactiveLinkFoundDespiteFilter() {
    ElementValue subaccount = mock(ElementValue.class);
    AcctSchema schema = mock(AcctSchema.class);
    AccountingCombination inactiveCombo = mock(AccountingCombination.class);
    when(inactiveCombo.isActive()).thenReturn(false); // the exact ETP-5101 scenario: the
        // subaccount's own natural combination cascaded to isactive='N'
    GLItemAccounts inactiveLink = mock(GLItemAccounts.class);
    when(inactiveLink.isActive()).thenReturn(false); // already deactivated by an earlier sync

    class TestableSupport extends GlItemProvisioningSupport {
      @Override
      protected AccountingCombination resolveNaturalCombination(ElementValue s, AcctSchema sch) {
        // simulates setFilterOnActive(false) finding it despite isactive='N'
        return inactiveCombo;
      }

      @Override
      protected GLItemAccounts findGlItemAccountsByCombination(AccountingCombination combo) {
        assertEquals(inactiveCombo, combo);
        return inactiveLink; // ditto for the GLItemAccounts row
      }
    }

    TestableSupport testableSupport = new TestableSupport();

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      // Reactivating: the subaccount comes back active, the link must follow even though both
      // the combo AND the link were found in an inactive state — neither is treated as absent.
      testableSupport.setGlItemAccountsActiveForSubaccount(subaccount,
          Collections.singletonList(schema), true);

      verify(inactiveLink).setActive(true);
      verify(dal).save(inactiveLink);
    }
  }

  // ── ensureGlItemForSchema — multi-schema reuse blast radius (ETP-5101 QA) ─────────────────
  //
  // setFilterOnActive(false) on findGlItemLinkedToAnyCombinationOf's inner criteria (added so a
  // deactivated subaccount's OTHER combinations stay findable for GL Item reuse) means the GL
  // Item handed back to ensureGlItemForSchema for a NOT-yet-provisioned schema can now be one
  // discovered via an inactive link on a DIFFERENT schema. This test pins that the NEW
  // GLItemAccounts row minted for the current schema still derives its active state solely from
  // the CURRENT subaccount.isActive() read — never from the reused GLItem's own (unrelated,
  // possibly stale) active flag — so a reused-but-happens-to-be-flagged-active GL Item can never
  // resurrect an active link behind an inactive subaccount.

  @Test
  public void ensureGlItemForSchemaDerivesNewLinkActiveStateFromSubaccountNotFromReusedGlItem() {
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.isActive()).thenReturn(false); // inactive subaccount being provisioned for a
        // second, newly-active schema
    AcctSchema newSchema = mock(AcctSchema.class);
    AccountingCombination comboForNewSchema = mock(AccountingCombination.class);

    // Reused GL Item, found via findGlItemLinkedToAnyCombinationOf's now-active-filter-free scan
    // of this subaccount's OTHER combinations — deliberately left "active" on its own flag to
    // prove that flag is never consulted by the new link's active derivation.
    GLItem reusedGlItem = mock(GLItem.class);
    when(reusedGlItem.isActive()).thenReturn(true);
    Client client = mock(Client.class);
    Organization org = mock(Organization.class);
    when(reusedGlItem.getClient()).thenReturn(client);
    when(reusedGlItem.getOrganization()).thenReturn(org);

    GLItemAccounts newLink = mock(GLItemAccounts.class);
    OBProvider obProviderInstance = mock(OBProvider.class);
    when(obProviderInstance.get(GLItemAccounts.class)).thenReturn(newLink);

    class TestableSupport extends GlItemProvisioningSupport {
      @Override
      protected AccountingCombination resolveNaturalCombination(ElementValue s, AcctSchema sch) {
        assertEquals(newSchema, sch);
        return comboForNewSchema;
      }

      @Override
      protected GLItemAccounts findGlItemAccountsByCombination(AccountingCombination combo) {
        return null; // nothing provisioned yet for the current schema
      }

      @Override
      protected GLItem findGlItemLinkedToAnyCombinationOf(ElementValue s) {
        return reusedGlItem; // reuse path: some OTHER schema's link already exists
      }
    }

    TestableSupport testableSupport = new TestableSupport();

    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
        MockedStatic<OBProvider> obProvider = mockStatic(OBProvider.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      obProvider.when(OBProvider::getInstance).thenReturn(obProviderInstance);

      GLItem result = testableSupport.ensureGlItemForSchema(subaccount, newSchema, null);

      assertEquals(reusedGlItem, result);
      // No brand-new GLItem was minted — the reused one was threaded through as-is.
      verify(obProviderInstance, never()).get(GLItem.class);
      // The reused GLItem's own (stale) active flag was never touched or re-derived.
      verify(reusedGlItem, never()).setActive(true);
      verify(reusedGlItem, never()).setActive(false);
      // The new link mirrors the CURRENT subaccount state (inactive), not the reused GL Item's.
      verify(newLink).setActive(false);
      verify(newLink, never()).setActive(true);
      verify(dal).save(newLink);
    }
  }
}
