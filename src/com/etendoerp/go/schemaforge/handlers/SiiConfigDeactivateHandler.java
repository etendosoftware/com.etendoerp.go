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
import com.etendoerp.go.schemaforge.SiiTbaiAutoSendScheduleService;

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
 * <h3>POST/PUT afterHandle — twice-a-day auto-send schedule (ETP-5117)</h3>
 * <p>After a successful create (POST) or non-deactivating update (PUT) that leaves the config
 * active, ensures a scheduled {@code AD_Process_Request} exists for the SII sending process,
 * scoped to the config's own client + organization (SII configs are per-organization) — see
 * {@link SiiTbaiAutoSendScheduleService} for the full scope/idempotency reasoning. GO-only by
 * design: this never runs for a config saved through Classic UI.
 *
 * <h3>PUT afterHandle — auto-send schedule cleanup on deactivation (ETP-5117 follow-up)</h3>
 * <p>The schedule created above must not outlive the config it was created for. When a PUT
 * explicitly sets {@code active=false}, {@link #afterHandle} now calls {@link
 * #unscheduleAutoSendForDeactivatedConfig} instead of returning immediately — covering both
 * outcomes of {@link #smartDeactivate}: the record deleted outright (no invoices were ever sent
 * through it) or deactivated by the default-CRUD fall-through (invoices exist, audit trail
 * preserved). Either way, without this the schedule would keep firing the SII sending process
 * twice a day for an organization whose fiscal config no longer exists or is no longer active.
 * See {@link #unscheduleAutoSendForDeactivatedConfig} for how client/organization is resolved
 * even though the record may already be gone by the time this hook runs.
 *
 * <p>{@code @Named} only — never a normal CDI scope. See CLAUDE.md §NeoHandler Pattern and
 * {@code docs/neo-headless-extensibility.md} §2.2 (this qualifier silently stops being
 * discovered if a scope annotation such as {@code @ApplicationScoped} is added).
 */
@Named("sii-config-deactivate-handler")
public class SiiConfigDeactivateHandler extends AbstractSmartDeactivationHandler {

  private static final Logger log = LogManager.getLogger(SiiConfigDeactivateHandler.class);

  private static final String METHOD_POST = "POST";

  private static final String AUTO_SEND_SCHEDULE_DESCRIPTION =
      "Automatic SII invoice sending (Etendo GO)";

  private final SiiTbaiAutoSendScheduleService scheduleService = new SiiTbaiAutoSendScheduleService();

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
   * After a successful create (POST) or non-deactivating update (PUT) of an SII config:
   * <ul>
   *   <li>PUT only — ensures {@code INSIISYSTEM = 'Y'} in the DB so the
   *       {@code AEATSII_CHECK_SIFS_CONFIGS_TRG} trigger correctly marks the org's
   *       {@code em_etsg_has_sii_config} flag (ETP-4783, unchanged).</li>
   *   <li>POST or PUT — ensures the twice-a-day auto-send schedule exists for the config's
   *       organization (ETP-5117). POST is included (not just PUT) because SII configuration is
   *       most commonly created via POST; gating only on PUT would silently skip the schedule for
   *       every tenant that never edits the config after the initial save.</li>
   * </ul>
   *
   * <p>Deactivation PUTs ({@code active=false}) are skipped entirely — the org flag should be
   * cleared, and a config that is not becoming active must not get a schedule.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    String method = context.getHttpMethod();
    boolean isPost = METHOD_POST.equalsIgnoreCase(method);
    boolean isPut = METHOD_PUT.equalsIgnoreCase(method);
    if (!isPost && !isPut) {
      return null;
    }
    if (isPut && isExplicitlyDeactivating(context.getRequestBody())) {
      unscheduleAutoSendForDeactivatedConfig(context);
      return null;
    }
    String recordId = isPut ? context.getRecordId() : resolveCreatedRecordId(context);
    if (StringUtils.isBlank(recordId)) {
      return null;
    }
    try {
      OBContext.setAdminMode(true);
      try {
        if (isPut) {
          setInSiiSystemY(recordId);
        }
        scheduleAutoSendIfActive(context, recordId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      // Non-fatal — the save already committed; log and continue.
      log.warn("SiiConfigDeactivateHandler.afterHandle: could not complete post-save side "
          + "effects for {}: {}", recordId, e.getMessage(), e);
    }
    return null;
  }

  /**
   * Resolves the {@code id} of a just-created record from a POST response
   * ({@code response.data[0].id}). Etendo's {@code DefaultJsonDataService} always serializes
   * {@code data} as a {@link JSONArray} for a create response.
   */
  private String resolveCreatedRecordId(NeoContext context) {
    NeoResponse prev = context.getPreviousResult();
    if (prev == null || prev.getBody() == null) {
      return null;
    }
    JSONObject response = prev.getBody().optJSONObject("response");
    if (response == null) {
      return null;
    }
    JSONArray dataArr = response.optJSONArray("data");
    if (dataArr != null && dataArr.length() > 0) {
      JSONObject first = dataArr.optJSONObject(0);
      return first == null ? null : StringUtils.trimToNull(first.optString("id", null));
    }
    JSONObject dataObj = response.optJSONObject("data");
    return dataObj == null ? null : StringUtils.trimToNull(dataObj.optString("id", null));
  }

  /**
   * Ensures the twice-a-day auto-send schedule exists (and attempts to activate it) for the
   * saved config's client/organization, but only when the config is actually active — a config
   * that was created inactive, or whose deactivation slipped through some other path, must not
   * get a schedule.
   */
  private void scheduleAutoSendIfActive(NeoContext context, String recordId) {
    AEATSIIConfig config = OBDal.getInstance().get(AEATSIIConfig.class, recordId);
    if (config == null || !Boolean.TRUE.equals(config.isActive())
        || config.getClient() == null || config.getOrganization() == null) {
      return;
    }
    OBContext obContext = context.getObContext();
    if (obContext == null || obContext.getUser() == null || obContext.getRole() == null) {
      return;
    }
    String requestId = scheduleService.ensureAutoSendSchedule(config.getClient().getId(),
        config.getOrganization().getId(), obContext.getUser().getId(), obContext.getRole().getId(),
        SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY, AUTO_SEND_SCHEDULE_DESCRIPTION);
    scheduleService.activateSchedule(requestId);
  }

  /**
   * Removes the auto-send schedule (if any) for a config that a deactivating PUT just deleted or
   * deactivated (ETP-5117 follow-up) — see the "auto-send schedule cleanup on deactivation"
   * class Javadoc section.
   *
   * <p><b>Client/organization resolution.</b> By the time {@link #afterHandle} runs, the record
   * may already be gone: {@link #smartDeactivate} deletes it outright when no SII invoices were
   * ever sent through it. Two sources are tried, in order:
   * <ol>
   *   <li>{@code OBDal.getInstance().get(AEATSIIConfig.class, recordId)} — a primary-key lookup
   *       is <b>not</b> filtered by {@code Active}, so it still succeeds for the "deactivated by
   *       default CRUD, not deleted" case and returns the config's own organization, which is
   *       the most precise source available.</li>
   *   <li>Only when that lookup returns nothing (the "deleted" case) does this fall back to
   *       {@code context.getObContext()}'s {@link OBContext#getCurrentClient()}/{@link
   *       OBContext#getCurrentOrganization()} — the session's client/organization at the time of
   *       the request. SII configs are per-organization records normally edited from within that
   *       same organization's context (the same assumption {@code
   *       TbaiConfigSequenceHandler#resolveConfigScope} already relies on for its own defensive
   *       fallback), so this is a safe substitute for the one case where the record itself can no
   *       longer answer the question. Neither {@link NeoContext} nor {@link NeoResponse} carry a
   *       request-scoped attribute bag that could thread the about-to-be-deleted record's
   *       client/organization from {@link #smartDeactivate} into this hook, so adding one just
   *       for this would add more surface than this fallback avoids.</li>
   * </ol>
   */
  private void unscheduleAutoSendForDeactivatedConfig(NeoContext context) {
    String recordId = context.getRecordId();
    try {
      OBContext.setAdminMode(true);
      try {
        String clientId = null;
        String orgId = null;
        if (StringUtils.isNotBlank(recordId)) {
          AEATSIIConfig config = OBDal.getInstance().get(AEATSIIConfig.class, recordId);
          if (config != null && config.getClient() != null && config.getOrganization() != null) {
            clientId = config.getClient().getId();
            orgId = config.getOrganization().getId();
          }
        }
        if (clientId == null || orgId == null) {
          OBContext obContext = context.getObContext();
          if (obContext == null || obContext.getCurrentClient() == null
              || obContext.getCurrentOrganization() == null) {
            log.debug("SiiConfigDeactivateHandler: could not resolve client/organization for "
                + "the deactivated config {}; skipping auto-send schedule cleanup", recordId);
            return;
          }
          clientId = obContext.getCurrentClient().getId();
          orgId = obContext.getCurrentOrganization().getId();
        }
        scheduleService.unscheduleAutoSend(clientId, orgId,
            SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      // Non-fatal — the deactivate/delete already committed; log and continue.
      log.warn("SiiConfigDeactivateHandler.afterHandle: could not unschedule auto-send for "
          + "deactivated config {}: {}", recordId, e.getMessage(), e);
    }
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
