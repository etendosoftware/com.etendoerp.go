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

import com.etendoerp.go.schemaforge.AbstractSmartDeactivationHandler;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * NeoHandler for the {@code sii-config} spec ({@code siiConfiguration} entity, table
 * {@code AEATSII_CONFIG}).
 *
 * <h3>POST — default CRUD insert (ETP-4783 / ETP-4785)</h3>
 * <p>The old "upsert on inactive record" logic was removed when ETP-4785 dropped the
 * {@code AEATSII_CONF_ORG_UQ} unique constraint on {@code ad_org_id} and replaced it with
 * the {@code AEATSII_ONE_ACTIVE_CONFIG_TRG} trigger, which only blocks a second <em>active</em>
 * record for the same org. With no unique constraint, the wizard POST always inserts a fresh
 * record (the old inactive row stays as audit trail), so the reactivation upsert is both
 * unnecessary and dangerous — it tried to mutate a stale row whose {@code NAVARRA}/{@code GUIPUZCOA}
 * combination could violate {@code AEATSII_CONF_AGENCY_CHK} on territory change.
 * POST now falls through to the default CRUD unconditionally.
 *
 * <h3>PUT — smart deactivation (ETP-4785)</h3>
 * <p>When a PUT request explicitly sets {@code active=false}, checks whether any invoice was
 * sent through SII during this config's active period (i.e. a row exists in
 * {@code aeatsii_facturas} linked to an invoice with {@code dateinvoiced >= fechaAcogidaSII}
 * and matching org). If the config has no acogida date ({@code fechaAcogidaSII IS NULL}) or
 * no SII invoice exists, the record is <em>deleted</em> rather than deactivated, preventing
 * orphan inactive configs from accumulating. If SII invoices were sent, the request falls
 * through so the default CRUD deactivates the record normally (audit trail preserved).
 *
 * <h3>PUT afterHandle — INSIISYSTEM flag (ETP-4783)</h3>
 * <p>After a successful non-deactivating PUT, sets {@code INSIISYSTEM = 'Y'} via native SQL
 * so the {@code AEATSII_CHECK_SIFS_CONFIGS_TRG} trigger marks the org as having an active SII
 * config. This column is not mapped in the generated {@link AEATSIIConfig} entity class and
 * defaults to {@code 'N'}, so without this hook every PUT would clear the org flag.
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2 (this qualifier silently stops being
 * discovered if a scope annotation such as {@code @ApplicationScoped} is added).
 */
@Named("sii-config-deactivate-handler")
public class SiiConfigDeactivateHandler extends AbstractSmartDeactivationHandler {

  private static final Logger log = LogManager.getLogger(SiiConfigDeactivateHandler.class);

  /**
   * Decides between deleting the config record (no invoices sent through it) and letting the
   * default CRUD deactivate it (invoices exist — audit trail must be preserved).
   *
   * @param recordId the primary key of the {@code aeatsii_config} record
   * @return a 200 {@code {"deleted":true}} response when the record was deleted, or
   *     {@code null} to let default CRUD deactivate it.
   */
  @Override
  protected NeoResponse smartDeactivate(String recordId) throws JSONException {
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
   * After a successful PUT that saves an active SII config, ensures {@code INSIISYSTEM = 'Y'}
   * in the DB so the {@code AEATSII_CHECK_SIFS_CONFIGS_TRG} trigger correctly marks the org's
   * {@code em_etsg_has_sii_config} flag.
   *
   * <p>Deactivation PUTs ({@code active=false}) are skipped — the org flag should be cleared
   * when the config is deactivated.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!"PUT".equalsIgnoreCase(context.getHttpMethod())) {
      return null;
    }
    // Skip when this PUT is deactivating the record; the trigger should clear the org flag.
    JSONObject body = context.getRequestBody();
    if (body != null && body.has("active") && !body.optBoolean("active", true)) {
      return null;
    }
    String recordId = context.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        setInSiiSystemY(recordId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      // Non-fatal — the save already committed; log and continue.
      log.warn("SiiConfigDeactivateHandler.afterHandle: could not set INSIISYSTEM='Y' for {}: {}",
          recordId, e.getMessage(), e);
    }
    return null;
  }

  /**
   * Sets {@code INSIISYSTEM = 'Y'} for the given {@code AEATSII_CONFIG} record via native SQL.
   *
   * <p>This column is not mapped in the generated {@link AEATSIIConfig} entity class, so a
   * native {@code UPDATE} is the only way to set it through OBDal. Setting it to {@code 'Y'}
   * causes the {@code AEATSII_CHECK_SIFS_CONFIGS_TRG} trigger to mark the organisation as
   * having an active SII config ({@code em_etsg_has_sii_config = 'Y'} in {@code AD_ORGINFO}).
   *
   * @param recordId primary key of the {@code AEATSII_CONFIG} row to update
   */
  private void setInSiiSystemY(String recordId) {
    // Flush pending Hibernate changes before the native SQL so ordering is consistent.
    OBDal.getInstance().flush();
    OBDal.getInstance().getSession()
        .createNativeQuery("UPDATE AEATSII_CONFIG SET INSIISYSTEM = 'Y' WHERE AEATSII_CONFIG_ID = :id")
        .setParameter("id", recordId)
        .executeUpdate();
    log.info("SiiConfigDeactivateHandler: set INSIISYSTEM='Y' for config {}", recordId);
  }

  /**
   * Returns {@code true} if at least one SII transmission record ({@code aeatsii_facturas})
   * exists for the given org where the linked invoice's date is on or after {@code since}.
   * This scopes the check to the config's active period so that transmissions from a prior
   * config of the same org do not incorrectly prevent deletion.
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

}
