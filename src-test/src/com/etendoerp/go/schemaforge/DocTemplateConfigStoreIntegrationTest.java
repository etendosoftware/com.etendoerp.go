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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.test.base.OBBaseTest;

/**
 * Persistence of the document template configuration (ETP-5988) against the real DAL and table:
 * one row per organization and document type, kept apart across organizations, document types and
 * clients, and guarded by the table's own constraints.
 *
 * <p>Nothing is committed: every spec works inside the test's transaction, which {@link #cleanUp()}
 * rolls back. The NEO request runs in admin mode, so the specs do too.</p>
 *
 * @covers com.etendoerp.go.schemaforge.DocTemplateConfigStore
 */
public class DocTemplateConfigStoreIntegrationTest extends OBBaseTest {

  private static final String INVOICE = "sales-invoice";
  private static final String ORDER = "sales-order";
  private static final String LOGO = "https://cdn.example.com/logo.png";

  private final DocTemplateConfigStore store = new DocTemplateConfigStore();
  private String orgA;
  private String orgB;

  @Before
  public void setUp() {
    setTestUserContext();
    OBContext.setAdminMode(true);
    List<String> orgs = writableOrgs();
    assertTrue("the test client needs two non-summary organizations", orgs.size() >= 2);
    orgA = orgs.get(0);
    orgB = orgs.get(1);
  }

  @After
  public void cleanUp() {
    try {
      OBContext.restorePreviousMode();
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  private static List<String> writableOrgs() {
    OBCriteria<Organization> criteria = OBDal.getInstance().createCriteria(Organization.class);
    criteria.add(Restrictions.eq(Organization.PROPERTY_CLIENT,
        OBContext.getOBContext().getCurrentClient()));
    criteria.add(Restrictions.ne(Organization.PROPERTY_ID, "0"));
    List<String> ids = new ArrayList<>();
    for (Organization org : criteria.list()) {
      if (!Boolean.TRUE.equals(org.isSummaryLevel())) {
        ids.add(org.getId());
      }
    }
    return ids;
  }

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase();
  }

  private static int insertRaw(String clientId, String orgId, String docType, String variant) {
    return OBDal.getInstance().getSession().createNativeQuery(
        "INSERT INTO etgo_doc_template_config (etgo_doc_template_config_id, ad_client_id, ad_org_id,"
            + " createdby, updatedby, template_doc_type, template_variant)"
            + " VALUES (:id, :client, :org, '0', '0', :type, :variant)")
        .setParameter("id", newId()).setParameter("client", clientId).setParameter("org", orgId)
        .setParameter("type", docType).setParameter("variant", variant).executeUpdate();
  }

  @Test
  public void savesThenReadsBackEveryField() {
    store.save(orgA, INVOICE, "modern", LOGO, "Thanks", DocTemplateStyle.NONE);
    OBDal.getInstance().getSession().clear();

    DocTemplateConfig found = store.find(orgA, INVOICE);

    assertEquals(orgA, found.getOrgId());
    assertEquals(INVOICE, found.getDocType());
    assertEquals("modern", found.getVariant());
    assertEquals(LOGO, found.getLogo());
    assertEquals("Thanks", found.getFooterText());
    assertTrue(found.isConfigured());
  }

  @Test
  public void savesThenReadsBackTheStyle() {
    store.save(orgA, INVOICE, "classic", null, null,
        new DocTemplateStyle("#1A73E8", "serif", "large", "off", "on"));
    OBDal.getInstance().getSession().clear();

    DocTemplateStyle style = store.find(orgA, INVOICE).getStyle();

    assertEquals("#1A73E8", style.getColor());
    assertEquals("serif", style.getFont());
    assertEquals("large", style.getFontSize());
    assertEquals("off", style.getRowStripes());
    assertEquals("on", style.getShowLogo());
  }

  @Test
  public void savingAgainUpdatesTheSameRow() {
    store.save(orgA, INVOICE, "modern", LOGO, "Thanks", DocTemplateStyle.NONE);
    store.save(orgA, INVOICE, "compact", null, null, DocTemplateStyle.NONE);
    OBDal.getInstance().getSession().clear();

    DocTemplateConfig found = store.find(orgA, INVOICE);

    assertEquals("compact", found.getVariant());
    assertNull(found.getLogo());
    assertNull(found.getFooterText());
    assertEquals(1, store.list(orgA).size());
  }

  @Test
  public void organizationsKeepTheirOwnChoiceForTheSameDocumentType() {
    store.save(orgA, INVOICE, "modern", LOGO, "A footer", DocTemplateStyle.NONE);
    store.save(orgB, INVOICE, "compact", null, "B footer", DocTemplateStyle.NONE);
    OBDal.getInstance().getSession().clear();

    assertEquals("modern", store.find(orgA, INVOICE).getVariant());
    assertEquals("A footer", store.find(orgA, INVOICE).getFooterText());
    assertEquals("compact", store.find(orgB, INVOICE).getVariant());
    assertEquals("B footer", store.find(orgB, INVOICE).getFooterText());
  }

  @Test
  public void documentTypesOfAnOrganizationAreIndependent() {
    store.save(orgA, INVOICE, "modern", null, null, DocTemplateStyle.NONE);
    store.save(orgA, ORDER, "compact", null, null, DocTemplateStyle.NONE);
    OBDal.getInstance().getSession().clear();

    assertEquals("modern", store.find(orgA, INVOICE).getVariant());
    assertEquals("compact", store.find(orgA, ORDER).getVariant());
    List<DocTemplateConfig> listed = store.list(orgA);
    assertEquals(2, listed.size());
    assertEquals(INVOICE, listed.get(0).getDocType());
    assertEquals(ORDER, listed.get(1).getDocType());
  }

  @Test
  public void listAndFindNeverReturnAnotherOrganization() {
    store.save(orgB, INVOICE, "modern", null, null, DocTemplateStyle.NONE);
    OBDal.getInstance().getSession().clear();

    assertNull(store.find(orgA, INVOICE));
    assertTrue(store.list(orgA).isEmpty());
    assertEquals(1, store.list(orgB).size());
  }

  @Test
  public void anotherClientsRowIsInvisible() {
    insertRaw("0", "0", INVOICE, "modern");
    OBDal.getInstance().flush();

    assertNull(store.find("0", INVOICE));
    assertTrue(store.list("0").isEmpty());
  }

  @Test
  public void anInactiveRowIsReadAsNotConfiguredAndSavingReactivatesIt() {
    store.save(orgA, INVOICE, "modern", null, null, DocTemplateStyle.NONE);
    OBDal.getInstance().getSession().createNativeQuery(
        "UPDATE etgo_doc_template_config SET isactive = 'N' WHERE ad_org_id = :org")
        .setParameter("org", orgA).executeUpdate();
    OBDal.getInstance().getSession().clear();

    assertNull(store.find(orgA, INVOICE));

    store.save(orgA, INVOICE, "compact", null, null, DocTemplateStyle.NONE);
    OBDal.getInstance().getSession().clear();
    assertEquals("compact", store.find(orgA, INVOICE).getVariant());
    assertEquals(1, store.list(orgA).size());
  }

  @Test
  public void theUniqueIndexRefusesASecondRowForTheSameOrganizationAndType() {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    insertRaw(clientId, orgA, INVOICE, "modern");
    try {
      insertRaw(clientId, orgA, INVOICE, "compact");
      fail("a second row for the same client, organization and document type must be refused");
    } catch (RuntimeException expected) {
      assertTrue(causes(expected), causes(expected).contains("etgo_doctplcfg_org_type_uq"));
    }
  }

  @Test
  public void theTableRefusesAVariantOutsideTheThree() {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    try {
      insertRaw(clientId, orgA, INVOICE, "fancy");
      fail("the CHECK constraint must refuse an unknown variant");
    } catch (RuntimeException expected) {
      assertTrue(causes(expected), causes(expected).contains("etgo_doctplcfg_variant_chk"));
    }
  }

  /** Every message of the exception chain, lower-cased: the constraint name is in the driver's. */
  private static String causes(Throwable error) {
    StringBuilder text = new StringBuilder();
    for (Throwable t = error; t != null; t = t.getCause()) {
      text.append(t.getMessage()).append(' ');
    }
    return text.toString().toLowerCase();
  }
}
