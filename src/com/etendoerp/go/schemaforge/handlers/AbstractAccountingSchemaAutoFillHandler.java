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
 * Shared behavior for {@link NeoHandler} implementations that auto-fill the
 * {@code accountingSchema} field with the current client's default active
 * {@link AcctSchema} on POST (create), when the field is absent from the
 * request body.
 *
 * <p>This covers the first-line scenario where no existing sibling rows are
 * available to copy the value from. All other endpoints and HTTP methods
 * pass through to the default service unchanged.
 *
 * <p>Concrete subclasses only need to supply their own {@code @Named}
 * qualifier (matched against {@code ETGO_SF_ENTITY.Java_Qualifier}) and a
 * short {@link #describeContext()} used purely for log messages.
 */
public abstract class AbstractAccountingSchemaAutoFillHandler implements NeoHandler {

  private static final String FIELD_ACCOUNTING_SCHEMA = "accountingSchema";
  private static final String HTTP_POST = "POST";

  protected final Logger log = LogManager.getLogger(getClass());

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
        log.debug("Auto-filled accountingSchema={} for {}", schemaId, describeContext());
      }
    } catch (Exception e) {
      log.warn("Could not auto-fill accountingSchema for " + describeContext(), e);
    }
    return null; // continue with default CRUD
  }

  /**
   * Short description of the window/entity this handler serves, used only to
   * enrich log messages (e.g. {@code "product accounting line"}).
   *
   * @return a human-readable description of this handler's context
   */
  protected abstract String describeContext();

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
