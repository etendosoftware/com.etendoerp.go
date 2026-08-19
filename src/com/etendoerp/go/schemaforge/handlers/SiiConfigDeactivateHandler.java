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

import java.text.SimpleDateFormat;
import java.util.Date;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
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
 * {@code AEATSII_SII_CONFIG}).
 *
 * <h3>POST — upsert on pre-existing inactive config (ETP-4783)</h3>
 * <p>When the onboarding wizard creates a new SII config via POST, a DB unique constraint on
 * {@code ad_org_id} blocks the insert if an inactive record already exists (e.g. created in
 * Classic and later deactivated). This handler intercepts the POST, queries OBDal for any
 * inactive {@code AEATSIIConfig} with the same org, and if found <em>reactivates</em> it
 * (updating the onboarding fields from the request body) instead of creating a new record.
 * When no inactive record exists the handler returns {@code null} and the default CRUD insert
 * runs as usual.
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
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2 (this qualifier silently stops being
 * discovered if a scope annotation such as {@code @ApplicationScoped} is added).
 */
@Named("sii-config-deactivate-handler")
public class SiiConfigDeactivateHandler extends AbstractSmartDeactivationHandler {

  private static final Logger log = LogManager.getLogger(SiiConfigDeactivateHandler.class);

  /**
   * Routes POST requests to {@link #handleCreate} (upsert on inactive record) and delegates
   * PUT+{@code active=false} to the parent smart-deactivation logic.
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    if ("POST".equalsIgnoreCase(context.getHttpMethod())) {
      return handleCreate(context);
    }
    return super.handle(context);
  }

  /**
   * POST upsert: finds an inactive {@code AEATSIIConfig} for the same org. If found,
   * reactivates it (applying onboarding fields from the request body) and returns a
   * 201 response carrying {@code {"response":{"data":[{"id":"<recordId>"}]}}} so the
   * caller can fetch the full record by ID. If no inactive record exists, returns
   * {@code null} and the default CRUD insert runs.
   */
  private NeoResponse handleCreate(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    String orgId = body.optString("adOrgId", null);
    if (StringUtils.isBlank(orgId)) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        OBCriteria<AEATSIIConfig> crit = OBDal.getInstance().createCriteria(AEATSIIConfig.class);
        crit.setFilterOnActive(false);
        crit.add(Restrictions.eq(AEATSIIConfig.PROPERTY_ORGANIZATION + ".id", orgId));
        crit.add(Restrictions.eq(AEATSIIConfig.PROPERTY_ACTIVE, false));
        crit.setMaxResults(1);
        AEATSIIConfig existing = (AEATSIIConfig) crit.uniqueResult();

        if (existing == null) {
          // No inactive record for this org — let default CRUD create a new one.
          return null;
        }

        existing.setActive(true);
        applyPayloadFields(existing, body);
        OBDal.getInstance().save(existing);
        OBDal.getInstance().flush();
        // INSIISYSTEM is not mapped in the Java entity; set it via native SQL so the
        // AEATSII_CHECK_SIFS_CONFIGS_TRG trigger marks the org as having an active SII config.
        setInSiiSystemY(existing.getId());
        log.info("SiiConfigDeactivateHandler: reactivated inactive config {} for org {}",
            existing.getId(), orgId);

        // Return the ID so the wizard can GET the full record.
        JSONObject recordData = new JSONObject();
        recordData.put("id", existing.getId());
        JSONArray dataArray = new JSONArray();
        dataArray.put(recordData);
        JSONObject response = new JSONObject();
        response.put("data", dataArray);
        JSONObject wrapper = new JSONObject();
        wrapper.put("response", response);
        return NeoResponse.created(wrapper);

      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("SiiConfigDeactivateHandler.handleCreate: error reactivating config for org {}: {}",
          orgId, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Error reactivating SII config: " + e.getMessage());
    }
  }

  /**
   * Applies wizard onboarding fields from the POST body onto the existing entity.
   * Only fields present in the body are updated; absent fields keep their current value.
   */
  private void applyPayloadFields(AEATSIIConfig config, JSONObject body) throws Exception {
    if (body.has("acogidaAlSII")) {
      config.setAcogidaAlSII("Y".equalsIgnoreCase(body.optString("acogidaAlSII")));
    }
    if (body.has("entornoDeProduccin")) {
      config.setEntornoDeProduccin("Y".equalsIgnoreCase(body.optString("entornoDeProduccin")));
    }
    if (body.has("adjuntarArchivosXML")) {
      config.setAdjuntarArchivosXML("Y".equalsIgnoreCase(body.optString("adjuntarArchivosXML")));
    }
    if (body.has("navarra")) {
      config.setNavarra("Y".equalsIgnoreCase(body.optString("navarra")));
    }
    if (body.has("guipuzcoa")) {
      config.setGuipuzcoa("Y".equalsIgnoreCase(body.optString("guipuzcoa")));
    }
    if (body.has("taxtype")) {
      config.setTaxtype(body.optString("taxtype"));
    }
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    if (body.has("fechaAcogidaSII")) {
      String v = body.optString("fechaAcogidaSII", "");
      if (!v.isEmpty()) {
        config.setFechaAcogidaSII(sdf.parse(v));
      }
    }
    if (body.has("monitordate")) {
      String v = body.optString("monitordate", "");
      if (!v.isEmpty()) {
        config.setMonitordate(sdf.parse(v));
      }
    }
  }

  /**
   * Decides between deleting the config record (no invoices sent through it) and letting the
   * default CRUD deactivate it (invoices exist — audit trail must be preserved).
   *
   * @param recordId the primary key of the {@code aeatsii_sii_config} record
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
   * POST-CRUD hook: after a successful PUT that saves an active SII config, ensures
   * {@code INSIISYSTEM = 'Y'} in the DB so the {@code AEATSII_CHECK_SIFS_CONFIGS_TRG}
   * trigger correctly marks the org's {@code em_etsg_has_sii_config} flag.
   *
   * <p>{@code INSIISYSTEM} is not mapped in the generated {@link AEATSIIConfig} Java entity
   * class (no {@code getInSiiSystem()} / {@code setInSiiSystem()} methods exist). Its DB
   * default is {@code 'N'}, so without this hook every PUT leaves the column {@code 'N'} and
   * the trigger resets the org flag to {@code 'N'} on each save (ETP-4783).
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
   * causes the {@code AEATSII_CHECK_SIFS_CONFIGS_TRG} trigger (which fires on every UPDATE of
   * {@code AEATSII_CONFIG}) to mark the organisation as having an active SII configuration by
   * writing {@code em_etsg_has_sii_config = 'Y'} into {@code AD_ORGINFO} — the flag that the
   * frontend reads to decide whether to show the SII section.
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

}
