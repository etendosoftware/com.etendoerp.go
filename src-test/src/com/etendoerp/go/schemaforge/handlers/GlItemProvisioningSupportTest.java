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
  public void resolveNaturalCombinationAppliesAllNineDimensionRestrictions() {
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

    // account + schema (2 eq) + the 9 dimension-IS-NULL checks mirrored from
    // C_ELEMENTVALUE_TRG.xml (product, businessPartner, trxOrganization, salesRegion, project,
    // salesCampaign, activity, stDimension, ndDimension) = 11 total restrictions.
    assertEquals(11, captor.getAllValues().size());
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
      verify(glItem).setName("Caja Euros");
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

  // ── ensureGlItemForSubaccount — idempotency ────────────────────────────────

  @Test
  public void ensureGlItemForSubaccountReusesExistingLinkAndSyncsRenamedName() {
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.getName()).thenReturn("New Name");

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
      verify(existingGlItem).setName("New Name");
      verify(dal).save(existingGlItem);
    }
  }

  @Test
  public void ensureGlItemForSubaccountDoesNotResaveWhenNameAlreadyMatches() {
    ElementValue subaccount = mock(ElementValue.class);
    when(subaccount.getName()).thenReturn("Same Name");

    AcctSchema schema = mock(AcctSchema.class);
    AccountingCombination combo = mock(AccountingCombination.class);
    GLItem existingGlItem = mock(GLItem.class);
    when(existingGlItem.getName()).thenReturn("Same Name");
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
  public void setGlItemAccountsActiveNoOpsWhenSubaccountNull() {
    support.setGlItemAccountsActiveForSubaccount(null, Collections.singletonList(mock(AcctSchema.class)),
        true);
  }
}
