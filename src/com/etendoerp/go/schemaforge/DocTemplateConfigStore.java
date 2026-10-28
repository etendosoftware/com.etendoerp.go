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
package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * Persistence of {@code ETGO_DOC_TEMPLATE_CONFIG}: at most one row per client, organization and
 * document type, which the table's unique index also guarantees.
 *
 * <p>The entity is reached by name rather than through its generated class, so this compiles
 * without the generated sources. Every query names the client and the organization it wants;
 * the NEO request runs in admin mode, where the DAL applies no organization filter of its own, so
 * isolation is this class's and {@link DocTemplateConfigAccess}'s job and nothing else's.</p>
 */
class DocTemplateConfigStore {

  static final String ENTITY_NAME = "ETGO_Doc_Template_Config";

  private static final String P_CLIENT = "client.id";
  private static final String P_ORG = "organization.id";
  private static final String P_DOC_TYPE = "templateDocType";
  private static final String P_VARIANT = "templateVariant";
  private static final String P_LOGO = "templateLogo";
  private static final String P_FOOTER = "templateFooter";

  /** @return the saved configuration, or null when the organization has none for the type */
  DocTemplateConfig find(String orgId, String docType) {
    OBCriteria<BaseOBObject> criteria = scoped(orgId);
    criteria.add(Restrictions.eq(P_DOC_TYPE, docType));
    criteria.setMaxResults(1);
    BaseOBObject row = (BaseOBObject) criteria.uniqueResult();
    return row == null ? null : toConfig(row);
  }

  /** @return every active configuration of the organization, by document type; never null */
  List<DocTemplateConfig> list(String orgId) {
    OBCriteria<BaseOBObject> criteria = scoped(orgId);
    criteria.addOrder(Order.asc(P_DOC_TYPE));
    List<DocTemplateConfig> configs = new ArrayList<>();
    for (BaseOBObject row : criteria.list()) {
      configs.add(toConfig(row));
    }
    return configs;
  }

  /**
   * Creates the row of the organization and type, or updates the existing one, and flushes.
   * The client is the caller's own, never one a request named.
   */
  DocTemplateConfig save(String orgId, String docType, String variant, String logo,
      String footerText) {
    OBCriteria<BaseOBObject> criteria = scoped(orgId);
    criteria.setFilterOnActive(false);
    criteria.add(Restrictions.eq(P_DOC_TYPE, docType));
    criteria.setMaxResults(1);
    BaseOBObject row = (BaseOBObject) criteria.uniqueResult();
    if (row == null) {
      row = (BaseOBObject) OBProvider.getInstance().get(ENTITY_NAME);
      row.set("client", OBDal.getInstance().getProxy(Client.ENTITY_NAME, currentClientId()));
      row.set("organization", OBDal.getInstance().getProxy(Organization.ENTITY_NAME, orgId));
      row.set(P_DOC_TYPE, docType);
    }
    row.set("active", Boolean.TRUE);
    row.set(P_VARIANT, variant);
    row.set(P_LOGO, logo);
    row.set(P_FOOTER, footerText);
    OBDal.getInstance().save(row);
    OBDal.getInstance().flush();
    return toConfig(row);
  }

  private OBCriteria<BaseOBObject> scoped(String orgId) {
    OBCriteria<BaseOBObject> criteria = OBDal.getInstance().createCriteria(ENTITY_NAME);
    criteria.add(Restrictions.eq(P_CLIENT, currentClientId()));
    criteria.add(Restrictions.eq(P_ORG, orgId));
    return criteria;
  }

  private static String currentClientId() {
    return OBContext.getOBContext().getCurrentClient().getId();
  }

  private static DocTemplateConfig toConfig(BaseOBObject row) {
    return new DocTemplateConfig(((Organization) row.get("organization")).getId(),
        (String) row.get(P_DOC_TYPE), (String) row.get(P_VARIANT), (String) row.get(P_LOGO),
        (String) row.get(P_FOOTER), true);
  }
}
