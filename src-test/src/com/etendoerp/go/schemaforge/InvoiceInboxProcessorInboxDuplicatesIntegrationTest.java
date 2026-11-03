/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.UUID;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.TestConstants;

import com.etendoerp.go.schemaforge.data.IncomingDocument;

/**
 * Real-HQL specs for the in-inbox duplicate warning the worker adds to a document: the rows of the
 * same issuer (by vendor when linked, by the extracted tax id in every spelling when not) and the
 * same document number, ignoring discarded rows, the row itself and other clients. Nothing is
 * committed: rows live in the test transaction.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxProcessor
 */
public class InvoiceInboxProcessorInboxDuplicatesIntegrationTest extends WeldBaseTest {

  private static final String CLIENT_ID = TestConstants.Clients.FB_GRP;
  private static final String ORG_ID = TestConstants.Orgs.ESP;
  private static final String NOTICE = "duplicate_in_inbox";

  private final String marker = "etgo-dup-" + UUID.randomUUID().toString().substring(0, 8);
  private final String base = "Q" + (1_000_000 + (int) (Math.random() * 8_999_999)) + "J";

  @Before
  public void prepareContext() {
    OBContext.clearAdminModeStack();
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN, CLIENT_ID,
        ORG_ID);
  }

  @After
  public void cleanUp() {
    OBDal.getInstance().rollbackAndClose();
    OBContext.setOBContext((OBContext) null);
  }

  @Test
  public void anUnlinkedRowWithTheSameTaxIdInAnySpellingAndTheSameNumberFindsTheFirst() throws Exception {
    for (String spelling : new String[] { base, base.charAt(0) + "-" + base.substring(1, 4) + "."
        + base.substring(4), "ES" + base, " es " + base }) {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN, CLIENT_ID,
          ORG_ID);
      IncomingDocument first = row(CLIENT_ID, "PENDING_REVIEW", spelling, "F-1", null);
      IncomingDocument second = row(CLIENT_ID, "PROCESSING", base, "f-1", null);

      JSONObject document = run(second, unlinked(base, "F-1"));

      JSONArray inbox = inbox(document);
      assertEquals(spelling, 1, inbox.length());
      assertEquals(first.getId(), inbox.getJSONObject(0).getString("id"));
      assertTrue(document.getJSONArray("notices").toString().contains(NOTICE));
    }
  }

  @Test
  public void aDifferentNumberOrADifferentTaxIdIsNotADuplicate() throws Exception {
    row(CLIENT_ID, "PENDING_REVIEW", base, "F-2", null);
    row(CLIENT_ID, "PENDING_REVIEW", "Q0000000X", "F-1", null);
    IncomingDocument second = row(CLIENT_ID, "PROCESSING", base, "F-1", null);

    JSONObject document = run(second, unlinked(base, "F-1"));

    assertEquals(0, inbox(document).length());
    assertTrue(!document.getJSONArray("notices").toString().contains(NOTICE));
  }

  @Test
  public void discardedRowsOtherClientsAndTheRowItselfAreIgnored() throws Exception {
    row(CLIENT_ID, "DISCARDED", base, "F-1", null);
    row(otherClientId(), "PENDING_REVIEW", base, "F-1", null);
    IncomingDocument self = row(CLIENT_ID, "PROCESSING", base, "F-1", null);

    assertEquals(0, inbox(run(self, unlinked(base, "F-1"))).length());
  }

  @Test
  public void aBlankOrShortTaxIdOrABadNumberAsksNothing() throws Exception {
    row(CLIENT_ID, "PENDING_REVIEW", "AB-1", "F-1", null);
    IncomingDocument self = row(CLIENT_ID, "PROCESSING", "AB-1", "F-1", null);

    assertEquals(0, inbox(run(self, unlinked("AB-1", "F-1"))).length());
    assertEquals(0, inbox(run(self, unlinked("", "F-1"))).length());
    assertEquals(0, inbox(run(self, unlinked(base, "  "))).length());
    assertEquals(0, inbox(run(self, unlinked(base, "A".repeat(21)))).length());
  }

  @Test
  public void aLinkedVendorStillMatchesByTheVendorId() throws Exception {
    BusinessPartner vendor = vendor();
    IncomingDocument first = row(CLIENT_ID, "PENDING_REVIEW", "Q0000000X", "F-1", vendor);
    IncomingDocument second = row(CLIENT_ID, "PROCESSING", base, "F-1", null);
    JSONObject document = unlinked(base, "F-1");
    document.getJSONObject("header").put("vendor", new JSONObject().put("state", "linked")
        .put("id", vendor.getId()));

    JSONArray inbox = inbox(run(second, document));

    assertEquals(1, inbox.length());
    assertEquals(first.getId(), inbox.getJSONObject(0).getString("id"));
  }

  // ---------------------------------------------------------------- helpers

  private static JSONObject unlinked(String taxId, String documentNo) throws Exception {
    return new JSONObject().put("header", new JSONObject()
        .put("vendor", new JSONObject().put("state", "new").put("extracted",
            new JSONObject().put("taxId", taxId)))
        .put("documentNo", documentNo).put("duplicate", new JSONObject()))
        .put("notices", new JSONArray());
  }

  private static JSONArray inbox(JSONObject document) throws Exception {
    return document.getJSONObject("header").getJSONObject("duplicate").getJSONArray("inbox");
  }

  /** Runs the private worker step on the document, as the worker does in admin mode. */
  private static JSONObject run(IncomingDocument self, JSONObject document) throws Exception {
    Method method = InvoiceInboxProcessor.class.getDeclaredMethod("addInboxDuplicates",
        IncomingDocument.class, JSONObject.class);
    method.setAccessible(true);
    method.invoke(null, self, document);
    return document;
  }

  private IncomingDocument row(String clientId, String status, String taxId, String reference,
      BusinessPartner vendor) {
    OBContext.setAdminMode(false);
    try {
      IncomingDocument row = OBProvider.getInstance().get(IncomingDocument.class);
      row.setClient(OBDal.getInstance().get(Client.class, clientId));
      row.setOrganization(OBDal.getInstance().get(Organization.class,
          CLIENT_ID.equals(clientId) ? ORG_ID : "0"));
      row.setActive(true);
      row.setFileName(marker + ".pdf");
      row.setSource("UPLOAD");
      row.setTargetType("PURCHASE_INVOICE");
      row.setStatus(status);
      row.setAdjusted(false);
      row.setReviewed(false);
      row.setProcessingAttempts(0L);
      row.setExtractedTaxID(taxId);
      row.setOrderReference(reference);
      row.setBusinessPartner(vendor);
      OBDal.getInstance().save(row);
      OBDal.getInstance().flush();
      return row;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * A vendor inserted with native SQL on the DAL session's connection: saving a C_BPartner through
   * the DAL fails in F&amp;B because EM_Etgo_Identifier is filled by a transactional sequence that
   * only exists for the product tenants. Visible to the HQL at once; undone by the rollback.
   */
  private BusinessPartner vendor() {
    OBContext.setAdminMode(false);
    try {
      NativeQuery<?> group = OBDal.getInstance().getSession().createNativeQuery(
          "SELECT c_bp_group_id FROM c_bp_group WHERE ad_client_id = :c AND isactive = 'Y' LIMIT 1");
      group.setParameter("c", CLIENT_ID);
      Object groupId = group.uniqueResult();
      assertNotNull(groupId);
      String id = (String) OBDal.getInstance().getSession()
          .createNativeQuery("SELECT get_uuid()").uniqueResult();
      NativeQuery<?> insert = OBDal.getInstance().getSession().createNativeQuery(
          "INSERT INTO c_bpartner (c_bpartner_id, ad_client_id, ad_org_id, isactive, createdby, "
              + "updatedby, value, name, isvendor, c_bp_group_id) "
              + "VALUES (:id, :client, :org, 'Y', :user, :user, :value, :name, 'Y', :cat)");
      insert.setParameter("id", id);
      insert.setParameter("client", CLIENT_ID);
      insert.setParameter("org", ORG_ID);
      insert.setParameter("user", TestConstants.Users.ADMIN);
      insert.setParameter("value", marker + "-v");
      insert.setParameter("name", marker + " vendor");
      insert.setParameter("cat", (String) groupId);
      insert.executeUpdate();
      return OBDal.getInstance().get(BusinessPartner.class, id);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String otherClientId() {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT ad_client_id FROM ad_client WHERE ad_client_id NOT IN ('0', :fb) AND isactive = 'Y'"
            + " ORDER BY ad_client_id LIMIT 1");
    query.setParameter("fb", CLIENT_ID);
    Object id = query.uniqueResult();
    assertNotNull("the fence needs a second client", id);
    return (String) id;
  }
}
