/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNotNull;

import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Greeting;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.uom.UOM;

import com.etendoerp.go.roles.SystemRoleTemplates;
import com.etendoerp.go.roles.UserRoleCompositionService;
import com.etendoerp.go.schemaforge.email.DalEmailSafetyStore;
import com.etendoerp.go.schemaforge.email.EmailAuditRecord;
import com.etendoerp.go.schemaforge.email.EmailContractCommand;
import com.etendoerp.go.schemaforge.email.EmailContractCommandSupport;
import com.etendoerp.go.schemaforge.email.EmailProviderRequest;
import com.etendoerp.go.schemaforge.email.EmailRecipientResolution;
import com.etendoerp.go.schemaforge.email.EmailSendContext;
import com.etendoerp.go.schemaforge.email.EmailThrottleResult;
import com.etendoerp.go.schemaforge.email.EmailThrottleRule;
import com.etendoerp.go.schemaforge.email.TransactionalEmailService;

/**
 * Two-tenant coverage of {@link NeoTenantWriteGuard} against a real database (ETP-5819).
 *
 * <p>The caller is the standard test user of the F&amp;B client; the "other tenant" is the QA
 * Testing client of the same sample data. Every write goes straight to the DAL under
 * {@code OBContext.setAdminMode()} — the mode NeoServlet opens, with the client/organization check
 * off — so nothing of NEO's own guards runs: what is proven is the backstop for a write path that
 * skipped them.
 *
 * <p>Nothing here persists: every test rolls back in {@link #rollBack()}.
 *
 * @covers com.etendoerp.go.schemaforge.NeoTenantWriteGuard
 * @covers com.etendoerp.go.schemaforge.email.DalEmailSafetyStore
 */
public class NeoTenantWriteGuardIntegrationTest extends WeldBaseTest {

  /** "Unit", a System (client 0) unit of measure. */
  private static final String SYSTEM_UOM_ID = "100";
  private static final String MARKER = "NeoTenantWriteGuardIntegrationTest";

  private String foreignPartnerId;
  private String ownPartnerId;
  private boolean requestAdminMode;

  @Before
  public void loadFixtures() {
    OBContext.setAdminMode(true);
    try {
      foreignPartnerId = firstIdOfClient(BusinessPartner.class, QA_TEST_CLIENT_ID);
      ownPartnerId = firstIdOfClient(BusinessPartner.class, TEST_CLIENT_ID);
    } finally {
      OBContext.restorePreviousMode();
    }
    assumeNotNull("the sample data has no partner of the QA Testing client", foreignPartnerId);
    assumeNotNull("the sample data has no partner of the F&B client", ownPartnerId);

    // The request seam: NeoServlet's admin mode, which skips the DAL's client/org write check.
    OBContext.setAdminMode();
    requestAdminMode = true;
  }

  @After
  public void rollBack() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } finally {
      SessionHandler.getInstance().setDoRollback(false);
      if (requestAdminMode) {
        OBContext.restorePreviousMode();
        requestAdminMode = false;
      }
    }
  }

  /** The hole this guard closes: admin mode alone lets a write to another tenant through. */
  @Test
  public void testAdminModeAloneWritesAnotherTenantsRow() {
    OBDal.getInstance().get(BusinessPartner.class, foreignPartnerId).setDescription(MARKER);
    OBDal.getInstance().flush();

    assertEquals(MARKER,
        OBDal.getInstance().get(BusinessPartner.class, foreignPartnerId).getDescription());
  }

  @Test
  public void testUpdatingAnotherTenantsRowIsRefused() {
    assertRefused(() -> OBDal.getInstance()
        .get(BusinessPartner.class, foreignPartnerId)
        .setDescription(MARKER));
  }

  @Test
  public void testDeletingAnotherTenantsRowIsRefused() {
    assertRefused(() -> OBDal.getInstance()
        .remove(OBDal.getInstance().get(BusinessPartner.class, foreignPartnerId)));
  }

  @Test
  public void testInsertingIntoAnotherTenantIsRefused() {
    assertRefused(() -> {
      Greeting greeting = OBProvider.getInstance().get(Greeting.class);
      greeting.setClient(OBDal.getInstance().get(Client.class, QA_TEST_CLIENT_ID));
      greeting.setOrganization(OBDal.getInstance().get(Organization.class, QA_TEST_ORG_ID));
      greeting.setName(MARKER);
      OBDal.getInstance().save(greeting);
    });
  }

  /** The decision for shared system data: a tenant request never changes a client 0 row. */
  @Test
  public void testUpdatingASystemRowIsRefused() {
    assertRefused(() -> OBDal.getInstance().get(UOM.class, SYSTEM_UOM_ID).setDescription(MARKER));
  }

  @Test
  public void testUpdatingTheCallersOwnRowPasses() throws Exception {
    NeoTenantWriteGuard.call(() -> {
      OBDal.getInstance().get(BusinessPartner.class, ownPartnerId).setDescription(MARKER);
      return null;
    });

    assertEquals(MARKER,
        OBDal.getInstance().get(BusinessPartner.class, ownPartnerId).getDescription());
  }

  /**
   * Regression: composing a tenant user's role from a System template copies the template's
   * process accesses with the template's client {@code 0}, on purpose. The guard admits exactly
   * that write ({@link NeoTenantWriteGuard#allowingSystemRows}); before, the invitation failed.
   */
  @Test
  public void testComposingARoleFromASystemTemplatePasses() throws Exception {
    NeoTenantWriteGuard.call(() -> new UserRoleCompositionService().assignTemplateRoles(
        TEST_USER_ID, Collections.singletonList(SystemRoleTemplates.SALES_ROLE_ID)));

    assertTrue(new UserRoleCompositionService().getAppliedTemplateRoleIds(TEST_USER_ID)
        .contains(SystemRoleTemplates.SALES_ROLE_ID));
  }

  /**
   * Regression: before sending an email from a tenant request, the anti-abuse store writes its
   * throttle and audit rows, which belong to client {@code 0} by design. The guard admits them;
   * before, every email sent from NEO failed with "Write access denied".
   */
  @Test
  public void testRecordingEmailSafetyRowsPasses() throws Exception {
    DalEmailSafetyStore store = new DalEmailSafetyStore();
    EmailSendContext context = emailSendContext();

    EmailThrottleResult throttle = NeoTenantWriteGuard.call(() -> {
      EmailThrottleResult result = store.checkAndIncrement(context,
          Collections.singletonList(EmailThrottleRule.perTenant(1000, 60)));
      store.recordAudit(EmailAuditRecord.create(context, null, 500,
          TransactionalEmailService.STATUS_PROVIDER_FAILED, MARKER, null, false));
      return result;
    });

    assertTrue(throttle.isAllowed());
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** Runs {@code write} inside the guard and expects its closing flush to refuse it. */
  private static void assertRefused(Runnable write) {
    try {
      NeoTenantWriteGuard.call(() -> {
        write.run();
        return null;
      });
      fail("the write was not refused");
    } catch (Exception e) {
      assertTrue("expected a security refusal, got " + e, causedBySecurity(e));
    }
    assertTrue("a refusal must mark the transaction for rollback",
        SessionHandler.getInstance().getDoRollback());
  }

  private static EmailSendContext emailSendContext() throws Exception {
    JSONObject body = new JSONObject()
        .put(EmailContractCommandSupport.FIELD_TENANT_ID, TEST_CLIENT_ID)
        .put(EmailContractCommandSupport.FIELD_RECORD_ID, MARKER);
    return new EmailSendContext(new EmailContractCommand("document-send", body),
        EmailRecipientResolution.serverResolved("person@example.test"),
        new EmailProviderRequest("person@example.test", "document-send", new JSONObject(), null));
  }

  /** The refusal surfaces from inside Hibernate's flush, possibly wrapped. */
  private static boolean causedBySecurity(Throwable thrown) {
    for (Throwable t = thrown; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof OBSecurityException) {
        return true;
      }
    }
    return false;
  }

  private static <T extends BaseOBObject> String firstIdOfClient(Class<T> type, String clientId) {
    OBCriteria<T> criteria = OBDal.getInstance().createCriteria(type);
    criteria.setFilterOnReadableClients(false);
    criteria.setFilterOnReadableOrganization(false);
    criteria.add(Restrictions.eq("client.id", clientId));
    criteria.setMaxResults(1);
    List<T> rows = criteria.list();
    return rows.isEmpty() ? null : (String) rows.get(0).getId();
  }
}
