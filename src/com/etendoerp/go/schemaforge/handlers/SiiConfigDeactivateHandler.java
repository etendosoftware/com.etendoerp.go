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

import java.util.Date;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.data.AEATSIIFacturas;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * NeoHandler for the {@code sii-config} spec ({@code siiConfiguration} entity, table
 * {@code AEATSII_SII_CONFIG}) that implements smart deactivation (ETP-4785).
 *
 * <p>When a PUT request explicitly sets {@code active=false}, checks whether any invoice was
 * sent through SII during this config's active period (i.e. a row exists in
 * {@code aeatsii_facturas} linked to an invoice with {@code dateinvoiced >= fechaAcogidaSII}
 * and matching org). If the config has no acogida date ({@code fechaAcogidaSII IS NULL}) or
 * no SII invoice exists, the record is <em>deleted</em> rather than deactivated, preventing
 * orphan inactive configs from accumulating. If SII invoices were sent, the request falls
 * through so the default CRUD deactivates the record normally (audit trail preserved).
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2 (this qualifier silently stops being
 * discovered if a scope annotation such as {@code @ApplicationScoped} is added).
 */
@Named("sii-config-deactivate-handler")
public class SiiConfigDeactivateHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(SiiConfigDeactivateHandler.class);

  private static final String METHOD_PUT = "PUT";
  private static final String FIELD_ACTIVE = "active";

  /**
   * Pre-hook: implements smart deactivation for SII config records. When a PUT explicitly
   * sets {@code active=false}, decides between deleting the record (no invoices sent) or
   * falling through to the default CRUD deactivation (invoices sent — audit trail required).
   *
   * @return 200 {@code {"deleted":true}} when the record was deleted; {@code null} to let
   *     default CRUD deactivate it normally.
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    if (!METHOD_PUT.equalsIgnoreCase(context.getHttpMethod())) {
      return null;
    }
    if (!isExplicitlyDeactivating(context.getRequestBody())) {
      return null;
    }
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        return smartDeactivate(recordId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("SiiConfigDeactivateHandler.handle: error during smart deactivation for {}: {}",
          recordId, e.getMessage(), e);
      // Fail safe: let default CRUD deactivate normally rather than blocking the request.
      return null;
    }
  }

  /** Post-hook: no behavior — smart deactivation is entirely a pre-hook concern. */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }

  /**
   * Decides between deleting the config record (no invoices sent through it) and letting the
   * default CRUD deactivate it (invoices exist — audit trail must be preserved).
   *
   * @param recordId the primary key of the {@code aeatsii_sii_config} record
   * @return a 200 {@code {"deleted":true}} response when the record was deleted, or
   *     {@code null} to let default CRUD deactivate it.
   */
  NeoResponse smartDeactivate(String recordId) throws JSONException {
    AEATSIIConfig config = OBDal.getInstance().get(AEATSIIConfig.class, recordId);
    if (config == null) {
      return null;
    }

    Date adoptionDate = config.getFechaAcogidaSII();
    String orgId = config.getOrganization().getId();

    // If the config never entered the fiscal system (no acogida date), delete directly.
    if (adoptionDate == null) {
      OBDal.getInstance().remove(config);
      OBDal.getInstance().flush();
      log.info("SiiConfigDeactivateHandler: deleted unused config record {} (no acogida date)",
          recordId);
      return deletedResponse();
    }

    if (hasSiiInvoicesSince(orgId, adoptionDate)) {
      // SII invoices exist — let default CRUD deactivate (preserve audit trail).
      return null;
    }

    OBDal.getInstance().remove(config);
    OBDal.getInstance().flush();
    log.info("SiiConfigDeactivateHandler: deleted unused config record {} (no SII invoices sent)",
        recordId);
    return deletedResponse();
  }

  /**
   * Returns {@code true} if at least one SII transmission record ({@code aeatsii_facturas})
   * exists for the given org where the linked invoice's date is on or after {@code since}.
   * This scopes the check to the config's active period so that transmissions from a prior
   * config of the same org do not incorrectly prevent deletion.
   *
   * <p>The join goes: {@code aeatsii_facturas -> c_invoice} filtered by
   * {@code c_invoice.dateinvoiced >= since} and {@code c_invoice.ad_org_id = orgId}.
   */
  private boolean hasSiiInvoicesSince(String orgId, Date since) {
    OBCriteria<AEATSIIFacturas> crit = OBDal.getInstance().createCriteria(AEATSIIFacturas.class);
    crit.createAlias(AEATSIIFacturas.PROPERTY_INVOICE, "inv");
    crit.add(Restrictions.eq("inv." + Invoice.PROPERTY_ORGANIZATION + ".id", orgId));
    crit.add(Restrictions.ge("inv." + Invoice.PROPERTY_INVOICEDATE, since));
    crit.setProjection(Projections.rowCount());
    Number count = (Number) crit.uniqueResult();
    return count != null && count.longValue() > 0;
  }

  private static NeoResponse deletedResponse() throws JSONException {
    JSONObject body = new JSONObject();
    body.put("deleted", true);
    return NeoResponse.ok(body);
  }

  private static boolean isExplicitlyDeactivating(JSONObject body) {
    if (body == null || !body.has(FIELD_ACTIVE)) {
      return false;
    }
    Object value = body.opt(FIELD_ACTIVE);
    if (value instanceof Boolean) {
      return !(Boolean) value;
    }
    if (value instanceof String) {
      return "false".equalsIgnoreCase((String) value) || "N".equalsIgnoreCase((String) value);
    }
    return false;
  }
}
