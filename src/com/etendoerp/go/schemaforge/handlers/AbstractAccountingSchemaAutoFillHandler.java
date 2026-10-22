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

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.OrganizationEnabled;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBLedgerUtils;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;

import com.etendoerp.go.schemaforge.NeoActionRecordGuard;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.PostingAccountGuard;

/**
 * Shared behavior for the {@link NeoHandler} customizations of the accounting tabs that carry an
 * {@code accountingSchema} (ledger) and account combinations.
 *
 * <ul>
 *   <li>On POST (create), when {@code accountingSchema} is absent from the body, it is filled with
 *       the ledger of the parent record's organization (walking the org tree), else of the
 *       caller's organization, else the client's first active ledger by name (ETP-5693: it used to
 *       be an arbitrary active ledger of the client).</li>
 *   <li>On POST/PUT/PATCH, the submitted combinations go through {@link PostingAccountGuard}:
 *       posting accounts of the row's ledger only, and the ledger of an existing row cannot
 *       change (ETP-5693).</li>
 * </ul>
 *
 * <p>Concrete subclasses bind with {@code @Named} (matched against
 * {@code ETGO_SF_ENTITY.Java_Qualifier}) or, for new ones, {@code @NeoExtension}, and supply a
 * short {@link #describeContext()} used purely for log messages.
 */
public abstract class AbstractAccountingSchemaAutoFillHandler implements NeoHandler {

  private static final String FIELD_ACCOUNTING_SCHEMA = "accountingSchema";
  private static final String PARENT_ID = "parentId";
  private static final String HTTP_POST = "POST";

  protected final Logger log = LogManager.getLogger(getClass());

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    if (HTTP_POST.equalsIgnoreCase(context.getHttpMethod())) {
      autoFillAccountingSchema(context);
    }
    return PostingAccountGuard.checkWrite(context,
        PostingAccountGuard.ledgerOfRow(FIELD_ACCOUNTING_SCHEMA));
  }

  /**
   * Short description of the window/entity this handler serves, used only to
   * enrich log messages (e.g. {@code "product accounting line"}).
   *
   * @return a human-readable description of this handler's context
   */
  protected abstract String describeContext();

  private void autoFillAccountingSchema(NeoContext context) {
    JSONObject body = context.getRequestBody();
    // Only fill when the field is absent or explicitly null
    if (body == null || (!body.isNull(FIELD_ACCOUNTING_SCHEMA) && body.has(FIELD_ACCOUNTING_SCHEMA))) {
      return;
    }
    try {
      String schemaId = resolveDefaultSchemaId(context, body);
      if (schemaId != null) {
        body.put(FIELD_ACCOUNTING_SCHEMA, schemaId);
        log.debug("Auto-filled accountingSchema={} for {}", schemaId, describeContext());
      }
    } catch (Exception e) {
      log.warn("Could not auto-fill accountingSchema for " + describeContext(), e);
    }
  }

  private static String resolveDefaultSchemaId(NeoContext context, JSONObject body) {
    OBContext.setAdminMode();
    try {
      BaseOBObject parent = parentRecord(context, body);
      if (parent instanceof AcctSchema) {
        return (String) parent.getId(); // a tab that hangs off the ledger itself
      }
      String ledgerId = parent instanceof OrganizationEnabled
          ? OBLedgerUtils.getOrgLedger(((OrganizationEnabled) parent).getOrganization().getId())
          : null;
      Organization contextOrg = OBContext.getOBContext().getCurrentOrganization();
      if (StringUtils.isBlank(ledgerId) && contextOrg != null) {
        ledgerId = OBLedgerUtils.getOrgLedger(contextOrg.getId());
      }
      return StringUtils.isNotBlank(ledgerId) ? ledgerId : firstClientLedgerId();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The record this accounting tab hangs off: {@code parentId} from the body (batch) or the query
   * (REST), else the body's own link-to-parent property (MCP), loaded as the first link-to-parent
   * property's target it belongs to.
   */
  private static BaseOBObject parentRecord(NeoContext context, JSONObject body) {
    Tab adTab = context.getAdTab();
    if (adTab == null) {
      return null;
    }
    String parentId = body.optString(PARENT_ID, null);
    if (parentId == null && context.getQueryParams() != null) {
      parentId = context.getQueryParams().get(PARENT_ID);
    }
    Entity entity = ModelProvider.getInstance().getEntity(adTab.getTable().getName());
    for (Property property : entity.getProperties()) {
      if (!property.isParent() || property.getTargetEntity() == null) {
        continue;
      }
      String id = parentId != null ? parentId : body.optString(property.getName(), null);
      @SuppressWarnings("unchecked")
      BaseOBObject parent = NeoActionRecordGuard.loadOwned(
          (Class<? extends BaseOBObject>) property.getTargetEntity().getMappingClass(), id);
      if (parent != null) {
        return parent;
      }
    }
    return null;
  }

  private static String firstClientLedgerId() {
    String clientId = OBContext.getOBContext().getCurrentClient().getId();
    OBCriteria<AcctSchema> crit = OBDal.getInstance().createCriteria(AcctSchema.class);
    crit.add(Restrictions.eq(AcctSchema.PROPERTY_CLIENT + ".id", clientId));
    crit.add(Restrictions.eq(AcctSchema.PROPERTY_ACTIVE, true));
    crit.addOrder(Order.asc(AcctSchema.PROPERTY_NAME));
    crit.setMaxResults(1);
    List<AcctSchema> list = crit.list();
    return list.isEmpty() ? null : list.get(0).getId();
  }
}
