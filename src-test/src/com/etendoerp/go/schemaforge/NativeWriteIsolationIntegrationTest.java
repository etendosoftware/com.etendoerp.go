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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.order.Order;

/**
 * Two-tenant coverage of the request paths that write with native SQL (ETP-5819), which the DAL
 * write guard cannot see, against a real database.
 *
 * <p>The caller is the standard test user of the F&amp;B client; the "other tenant" is the QA
 * Testing client of the same sample data. Its attachment is a fixture created here; everything
 * rolls back in {@link #rollBack()}.
 *
 * @covers com.etendoerp.go.schemaforge.NeoAttachmentsHelper
 * @covers com.etendoerp.go.schemaforge.NeoCertificateHelper
 */
public class NativeWriteIsolationIntegrationTest extends WeldBaseTest {

  /** {@code C_Order}. */
  private static final String ORDER_TABLE_ID = "259";
  private static final String MARKER = "NativeWriteIsolationIntegrationTest";
  private static final String PARAM_ORG_ID = "orgId";

  private String foreignAttachmentId;
  private String ownAttachmentId;
  private boolean requestAdminMode;

  @Before
  public void loadFixtures() {
    // Without the client check: the fixture is a row of the other tenant, which a check-on admin
    // mode (correctly) refuses to the F&B test user.
    OBContext.setAdminMode();
    try {
      String foreignOrderId = firstIdOfClient(Order.class, QA_TEST_CLIENT_ID);
      assumeNotNull("the sample data has no order of the QA Testing client", foreignOrderId);
      foreignAttachmentId = createMainAttachment(QA_TEST_CLIENT_ID, QA_TEST_ORG_ID, foreignOrderId);
      String ownOrderId = firstIdOfClient(Order.class, TEST_CLIENT_ID);
      assumeNotNull("the sample data has no order of the F&B client", ownOrderId);
      ownAttachmentId = createMainAttachment(TEST_CLIENT_ID, TEST_ORG_ID, ownOrderId);
    } finally {
      OBContext.restorePreviousMode();
    }

    OBContext.setAdminMode();
    requestAdminMode = true;
  }

  @After
  public void rollBack() {
    try {
      OBDal.getInstance().rollbackAndClose();
    } finally {
      if (requestAdminMode) {
        OBContext.restorePreviousMode();
        requestAdminMode = false;
      }
    }
  }

  // ── attachments ────────────────────────────────────────────────────────────

  @Test
  public void testUnmarkingAnotherTenantsMainAttachmentIsA404AndChangesNothing() {
    NeoResponse response = NeoAttachmentsHelper.handleMarkMain(foreignAttachmentId, false);

    assertEquals(404, response.getHttpStatus());
    assertTrue("the foreign attachment must still be its record's main", isMain(foreignAttachmentId));
  }

  /** Positive pair: the same call on the caller's own attachment does change it. */
  @Test
  public void testUnmarkingTheCallersOwnMainAttachmentChangesIt() {
    NeoResponse response = NeoAttachmentsHelper.handleMarkMain(ownAttachmentId, false);

    assertEquals(200, response.getHttpStatus());
    assertFalse("the caller's own attachment must no longer be main", isMain(ownAttachmentId));
  }

  @Test
  public void testDeletingAnotherTenantsAttachmentIsA404AndKeepsIt() {
    NeoResponse response = NeoAttachmentsHelper.handleDelete(foreignAttachmentId);

    assertEquals(404, response.getHttpStatus());
    OBDal.getInstance().getSession().clear();
    assertNotNull(OBDal.getInstance().get(Attachment.class, foreignAttachmentId));
  }

  // ── certificates of another tenant's organization ──────────────────────────

  @Test
  public void testCertificateStatusOfAnotherTenantsOrgReadsAsNone() throws Exception {
    NeoResponse response = NeoCertificateHelper.handleCertificateGet(requestForOrg(QA_TEST_ORG_ID));

    assertEquals(200, response.getHttpStatus());
    assertFalse(response.getBody().getBoolean("exists"));
  }

  @Test
  public void testDeletingCertificatesOfAnotherTenantsOrgDeletesNothing() throws Exception {
    NeoResponse response = NeoCertificateHelper.handleCertificateDelete(
        requestForOrg(QA_TEST_ORG_ID));

    assertEquals(200, response.getHttpStatus());
    assertEquals(0, response.getBody().getInt("deleted"));
  }

  /** Refused before the certificate is parsed, so its NIF never reaches the foreign org. */
  @Test
  public void testUploadingACertificateForAnotherTenantsOrgIsA404() throws Exception {
    HttpServletRequest request = requestForOrg(QA_TEST_ORG_ID);
    when(request.getContentType()).thenReturn("multipart/form-data; boundary=x");
    when(request.getPart("certificate")).thenReturn(mock(Part.class));
    when(request.getParameter("password")).thenReturn("secret");
    when(request.getParameter("setOrgNif")).thenReturn("true");

    NeoResponse response = NeoCertificateHelper.handleCertificateUpload(request);

    assertEquals(404, response.getHttpStatus());
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static HttpServletRequest requestForOrg(String orgId) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter(PARAM_ORG_ID)).thenReturn(orgId);
    return request;
  }

  private static String createMainAttachment(String clientId, String orgId, String orderId) {
    Attachment attachment = OBProvider.getInstance().get(Attachment.class);
    attachment.setClient(OBDal.getInstance().get(Client.class, clientId));
    attachment.setOrganization(OBDal.getInstance().get(Organization.class, orgId));
    attachment.setTable(OBDal.getInstance().get(Table.class, ORDER_TABLE_ID));
    attachment.setRecord(orderId);
    attachment.setName(MARKER + ".pdf");
    attachment.setSequenceNumber(10L);
    attachment.setETGOIsPreviewMain(true);
    OBDal.getInstance().save(attachment);
    OBDal.getInstance().flush();
    return attachment.getId();
  }

  private static boolean isMain(String attachmentId) {
    OBDal.getInstance().getSession().clear();
    return Boolean.TRUE.equals(
        OBDal.getInstance().get(Attachment.class, attachmentId).isETGOIsPreviewMain());
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
