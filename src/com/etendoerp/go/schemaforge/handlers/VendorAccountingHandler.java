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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * NeoHandler for the {@code vendorAccounting} entity in the Contacts window.
 *
 * <p>On POST (create), auto-fills {@code accountingSchema} with the default
 * accounting schema for the current client when the field is absent from the
 * request body. This handles the first-line scenario where no existing sibling
 * rows are available to copy the value from.
 *
 * <p>All other endpoints pass through to the default service unchanged.
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'vendorAccountingHandler'} on
 * the ETGO_SF_ENTITY record for the vendor accounting tab.
 */
@Named("vendorAccountingHandler")
public class VendorAccountingHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(VendorAccountingHandler.class);

  private static final String FIELD_ACCOUNTING_SCHEMA = "accountingSchema";
  private static final String HTTP_POST = "POST";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    if (!HTTP_POST.equalsIgnoreCase(context.getHttpMethod())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    // Only fill when the field is absent or explicitly null
    if (!body.isNull(FIELD_ACCOUNTING_SCHEMA) && body.has(FIELD_ACCOUNTING_SCHEMA)) {
      return null;
    }
    try {
      String schemaId = resolveDefaultSchemaId();
      if (schemaId != null) {
        body.put(FIELD_ACCOUNTING_SCHEMA, schemaId);
        log.debug("Auto-filled accountingSchema={} for vendor accounting line", schemaId);
      }
    } catch (Exception e) {
      log.warn("Could not auto-fill accountingSchema for vendor accounting line", e);
    }
    return null; // continue with default CRUD
  }

  private static String resolveDefaultSchemaId() {
    OBContext.setAdminMode();
    try {
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      OBCriteria<AcctSchema> crit = OBDal.getInstance().createCriteria(AcctSchema.class);
      crit.add(Restrictions.eq(AcctSchema.PROPERTY_CLIENT + ".id", clientId));
      crit.add(Restrictions.eq(AcctSchema.PROPERTY_ACTIVE, true));
      crit.setMaxResults(1);
      List<AcctSchema> list = crit.list();
      return list.isEmpty() ? null : list.get(0).getId();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
