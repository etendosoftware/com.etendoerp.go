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

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentMethod;
import org.openbravo.model.financialmgmt.payment.FinAccPaymentMethod;
import org.openbravo.model.financialmgmt.payment.PaymentExecutionProcess;
import org.openbravo.module.remittance.RemittanceType;
import org.openbravo.module.remittance.RemittanceTypeParameter;
import org.openbravo.test.base.OBBaseTest;

/**
 * Real-database specs for {@link RemittanceSepaProvisioning}, the part a mock cannot prove: that
 * the rows it writes satisfy the database (mandatory columns, triggers, check constraints of
 * {@code FIN_PaymentMethod}, {@code C_DocType}, {@code FIN_FinAcc_PaymentMethod}…), that its HQL
 * resolves against the real mapping ({@code p.remittanceType.client.id}, REM's {@code r.remittanceStatus},
 * …), and that a second run inside the same session finds — through those queries — what the
 * first one wrote instead of writing it again. It also pins the {@code REM_REM} document type
 * against the real {@code c_doctype_name} unique index: only an active one counts, and a name
 * already taken in organization {@code 0} (another category, or an inactive {@code REM_REM})
 * yields {@code «Remesa (SEPA)»}. The Mockito specs of the same class live in
 * {@code RemittanceSepaProvisioningTest}.
 *
 * <p>Runs on the {@link #TEST_CLIENT_ID} sample client in admin mode, the way the callers run it.
 * <b>Nothing is committed</b>: provisioning only flushes, and every test asks the session handler
 * to roll back ({@code setDoRollback(true)}), so {@code OBBaseTest} — which commits a successful
 * test by default — discards the rows.
 *
 * @covers com.etendoerp.go.schemaforge.RemittanceSepaProvisioning
 */
public class RemittanceSepaProvisioningIdempotencyIntegrationTest extends OBBaseTest {

  /** {@code Cost Adjustment} of the F&B sample client, organization 0: a template to copy. */
  private static final String SOME_DOC_TYPE_ID = "82000D718BDA40C38F83FA1A5FFF6419";

  /** {@code Bank - Account 1} of the F&B sample client: a Bank-type financial account. */
  private static final String BANK_ACCOUNT_ID = "2C059762760F4F3D8A91342ED988DEB8";

  private Client client;

  @Before
  public void rollbackEverything() {
    setTestAdminContext();
    SessionHandler.getInstance().setDoRollback(true);
    client = OBDal.getInstance().get(Client.class, TEST_CLIENT_ID);
    assertNotNull("the sample client must exist", client);
  }

  @Test
  public void aSecondRunCreatesNothingNewAndTheMarkersAreUnique() {
    OBContext.setAdminMode(true);
    try {
      long docTypesBefore = count(DocumentType.class,
          "as d where d.client.id = :client and d.documentCategory = 'REM_REM'");
      long methodsBefore = count(FIN_PaymentMethod.class, "as m where m.client.id = :client");

      RemittanceSepaProvisioning.ensureClient(client);
      long methodsAfterFirst = count(FIN_PaymentMethod.class, "as m where m.client.id = :client");
      long processesAfterFirst = count(PaymentExecutionProcess.class,
          "as p where p.client.id = :client and p.javaClassName = '"
              + RemittanceSepaProvisioning.IN_REMITTANCE_CLASS + "'");
      RemittanceSepaProvisioning.ensureClient(client);

      assertEquals("no payment method on the second run", methodsAfterFirst,
          count(FIN_PaymentMethod.class, "as m where m.client.id = :client"));
      assertEquals(methodsBefore + 2, methodsAfterFirst);
      assertEquals(processesAfterFirst, count(PaymentExecutionProcess.class,
          "as p where p.client.id = :client and p.javaClassName = '"
              + RemittanceSepaProvisioning.IN_REMITTANCE_CLASS + "'"));
      assertEquals(1, processesAfterFirst);
      assertEquals(Math.max(docTypesBefore, 1), count(DocumentType.class,
          "as d where d.client.id = :client and d.documentCategory = 'REM_REM'"));
      assertEquals(1, markers(RemittanceSepaProvisioning.SCHEME_CORE));
      assertEquals(1, markers(RemittanceSepaProvisioning.SCHEME_SCT));

      RemittanceType core = RemittanceSepaProvisioning.findType(client,
          RemittanceSepaProvisioning.SCHEME_CORE);
      RemittanceType sct = RemittanceSepaProvisioning.findType(client,
          RemittanceSepaProvisioning.SCHEME_SCT);
      assertEquals("000", parameterValue(core,
          RemittanceSepaProvisioning.PARAM_CREDITOR_BUSINESS_CODE));
      assertNull("only CORE carries the creditor business code",
          parameterValue(sct, RemittanceSepaProvisioning.PARAM_CREDITOR_BUSINESS_CODE));
      assertTrue(RemittanceSepaProvisioning.isProvisionedType(core));
      assertTrue(RemittanceSepaProvisioning.isRemittanceMethod(core.getPaymentMethod()));
      assertTrue(RemittanceSepaProvisioning.isRemittanceMethod(sct.getPaymentMethod()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void theMethodsCarryOneDirectionExecutedAutomaticallyThroughInRemittance() {
    OBContext.setAdminMode(true);
    try {
      RemittanceSepaProvisioning.ensureClient(client);

      FIN_PaymentMethod core = RemittanceSepaProvisioning
          .findType(client, RemittanceSepaProvisioning.SCHEME_CORE).getPaymentMethod();
      FIN_PaymentMethod sct = RemittanceSepaProvisioning
          .findType(client, RemittanceSepaProvisioning.SCHEME_SCT).getPaymentMethod();
      assertTrue(core.isPayinAllow());
      assertFalse(core.isPayoutAllow());
      assertEquals("A", core.getPayinExecutionType());
      assertTrue(core.isPayinDeferred());
      assertEquals(RemittanceSepaProvisioning.IN_REMITTANCE_CLASS,
          core.getPayinExecutionProcess().getJavaClassName());
      assertFalse(sct.isPayinAllow());
      assertTrue(sct.isPayoutAllow());
      assertEquals("A", sct.getPayoutExecutionType());
      assertTrue(sct.isPayoutDeferred());
      assertEquals(RemittanceSepaProvisioning.IN_REMITTANCE_CLASS,
          sct.getPayoutExecutionProcess().getJavaClassName());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void theAccountLinksPassCheckPmSetupAndAreDeactivatedNotDeleted() {
    OBContext.setAdminMode(true);
    try {
      RemittanceSepaProvisioning.ensureClient(client);
      FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class,
          BANK_ACCOUNT_ID);
      assertNotNull("the sample bank account must exist", account);
      account.setETGOAllowRemittance(true);
      OBDal.getInstance().save(account);
      FIN_PaymentMethod core = RemittanceSepaProvisioning
          .findType(client, RemittanceSepaProvisioning.SCHEME_CORE).getPaymentMethod();
      FIN_PaymentMethod sct = RemittanceSepaProvisioning
          .findType(client, RemittanceSepaProvisioning.SCHEME_SCT).getPaymentMethod();

      RemittanceSepaProvisioning.ensureAccountLinks(account, true);

      FinAccPaymentMethod coreLink = link(account, core);
      // REMRemittanceProcess#getErrorResult, direction IN.
      assertTrue(coreLink.isActive());
      assertTrue(coreLink.isPayinAllow());
      assertTrue(coreLink.isPayinDeferred());
      assertNotNull(coreLink.getPayinExecutionProcess());
      assertEquals("A", coreLink.getPayinExecutionType());
      assertFalse(coreLink.isPayoutAllow());
      assertFalse(coreLink.isDefault());
      assertFalse(coreLink.isPayinIsMulticurrency());
      FinAccPaymentMethod sctLink = link(account, sct);
      // Direction OUT.
      assertTrue(sctLink.isPayoutAllow());
      assertTrue(sctLink.isPayoutDeferred());
      assertNotNull(sctLink.getPayoutExecutionProcess());
      assertEquals("A", sctLink.getPayoutExecutionType());
      assertFalse(sctLink.isPayinAllow());
      assertTrue(RemittanceSepaProvisioning.hasActiveLink(account, core));

      String coreLinkId = coreLink.getId();
      RemittanceSepaProvisioning.ensureAccountLinks(account, false);
      assertFalse(RemittanceSepaProvisioning.hasActiveLink(account, core));
      assertFalse(RemittanceSepaProvisioning.hasActiveLink(account, sct));
      assertEquals("deactivated, not deleted", coreLinkId, link(account, core).getId());

      RemittanceSepaProvisioning.ensureAccountLinks(account, true);
      assertTrue(RemittanceSepaProvisioning.hasActiveLink(account, core));
      assertEquals("the same link is reactivated", coreLinkId, link(account, core).getId());
      assertEquals(1, count(FinAccPaymentMethod.class,
          "as l where l.account.id = '" + BANK_ACCOUNT_ID + "' and l.paymentMethod.id = '"
              + core.getId() + "' and l.client.id = :client"));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void countOpenRemittancesResolvesAgainstTheRealMapping() {
    OBContext.setAdminMode(true);
    try {
      RemittanceSepaProvisioning.ensureClient(client);
      FIN_FinancialAccount account = OBDal.getInstance().get(FIN_FinancialAccount.class,
          BANK_ACCOUNT_ID);

      assertEquals("a freshly provisioned type has no remittance on the account", 0L,
          RemittanceSepaProvisioning.countOpenRemittances(account));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void aDocumentTypeOfAnotherCategoryHoldingTheNameMakesTheRemittanceOneDisambiguated() {
    OBContext.setAdminMode(true);
    try {
      // F&B has no language, so it is provisioned in Spanish: the base name is «Remesa».
      DocumentType squatter = docTypeInOrgZero("Remesa", "CAD", true);

      RemittanceSepaProvisioning.ensureClient(client);

      DocumentType provisioned = activeRemittanceDocumentType();
      assertEquals("c_doctype_name is unique on (client, org, name)", "Remesa (SEPA)",
          provisioned.getName());
      assertEquals("Remesa (SEPA)", provisioned.getPrintText());
      assertEquals("the other category's row is untouched", "Remesa",
          OBDal.getInstance().get(DocumentType.class, squatter.getId()).getName());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  @Test
  public void anInactiveRemittanceDocumentTypeDoesNotCountAndKeepsItsName() {
    OBContext.setAdminMode(true);
    try {
      DocumentType inactive = docTypeInOrgZero("Remesa",
          RemittanceSepaProvisioning.DOC_CATEGORY_REMITTANCE, false);

      RemittanceSepaProvisioning.ensureClient(client);

      DocumentType provisioned = activeRemittanceDocumentType();
      assertFalse("a new, active one is created", inactive.getId().equals(provisioned.getId()));
      assertEquals("Remesa (SEPA)", provisioned.getName());
      RemittanceSepaProvisioning.ensureClient(client);
      assertEquals("and only once", 1, count(DocumentType.class,
          "as d where d.client.id = :client and d.documentCategory = 'REM_REM' and d.active = true"));
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ---------------------------------------------------------------- helpers

  private <T extends BaseOBObject> long count(Class<T> entity, String hql) {
    OBQuery<T> query = OBDal.getInstance().createQuery(entity, hql);
    query.setNamedParameter("client", TEST_CLIENT_ID);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    return query.count();
  }

  private long markers(String scheme) {
    OBQuery<RemittanceTypeParameter> query = OBDal.getInstance().createQuery(
        RemittanceTypeParameter.class,
        "as p where p.name = :name and p.value = :scheme and p.remittanceType.client.id = :client");
    query.setNamedParameter("name", RemittanceSepaProvisioning.PARAM_SCHEME);
    query.setNamedParameter("scheme", scheme);
    query.setNamedParameter("client", TEST_CLIENT_ID);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    return query.count();
  }

  private static String parameterValue(RemittanceType type, String name) {
    OBQuery<RemittanceTypeParameter> query = OBDal.getInstance().createQuery(
        RemittanceTypeParameter.class, "as p where p.remittanceType.id = :type and p.name = :name");
    query.setNamedParameter("type", type.getId());
    query.setNamedParameter("name", name);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    List<RemittanceTypeParameter> found = query.list();
    return found.isEmpty() ? null : found.get(0).getValue();
  }

  private static FinAccPaymentMethod link(FIN_FinancialAccount account, FIN_PaymentMethod method) {
    OBQuery<FinAccPaymentMethod> query = OBDal.getInstance().createQuery(FinAccPaymentMethod.class,
        "as l where l.account.id = :account and l.paymentMethod.id = :method");
    query.setNamedParameter("account", account.getId());
    query.setNamedParameter("method", method.getId());
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setFilterOnActive(false);
    FinAccPaymentMethod link = query.uniqueResult();
    assertNotNull("a link to " + method.getName(), link);
    return link;
  }
  /** A copy of {@link #SOME_DOC_TYPE_ID} in organization 0 with the given name and category. */
  private static DocumentType docTypeInOrgZero(String name, String category, boolean active) {
    DocumentType template = OBDal.getInstance().get(DocumentType.class, SOME_DOC_TYPE_ID);
    assertNotNull("the sample document type must exist", template);
    DocumentType copy = (DocumentType) DalUtil.copy(template, false);
    copy.setName(name);
    copy.setPrintText(name);
    copy.setDocumentCategory(category);
    copy.setActive(active);
    OBDal.getInstance().save(copy);
    OBDal.getInstance().flush();
    return copy;
  }

  private static DocumentType activeRemittanceDocumentType() {
    OBQuery<DocumentType> query = OBDal.getInstance().createQuery(DocumentType.class,
        "as d where d.client.id = :client and d.documentCategory = 'REM_REM' and d.active = true");
    query.setNamedParameter("client", TEST_CLIENT_ID);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    List<DocumentType> found = query.list();
    assertEquals("exactly one active REM_REM document type", 1, found.size());
    return found.get(0);
  }
}
