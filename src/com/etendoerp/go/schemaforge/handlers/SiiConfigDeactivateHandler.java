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
 * <h3>PUT/DELETE pre-hook — auto-send schedule cleanup on deactivation (ETP-5117 follow-up)</h3>
 * <p>The schedule created above must not outlive the config it was created for. Cleanup runs in
 * the <b>pre-hook</b>, never in {@link #afterHandle}, because it needs the config's <em>own</em>
 * client/organization and by the time {@code afterHandle} runs the record is typically already
 * gone. Resolving the scope from the session ({@code OBContext.getCurrentOrganization()}) instead
 * is not a viable fallback: a GO client-admin role normally has {@code ad_org_id = '0'} (the
 * {@code '*'} org), which never matches the business organization the schedule was created under,
 * so the cleanup silently found nothing and no-opped. Two entry points, both resolving the scope
 * from the record itself:
 * <ul>
 *   <li><b>Deactivating PUT</b> ({@code active=false}) — {@link #smartDeactivate} already has the
 *       loaded config in hand, so it unschedules there in <em>both</em> of its outcomes: the
 *       record deleted outright (no invoices were ever sent through it) and the default-CRUD
 *       fall-through (invoices exist, audit trail preserved).</li>
 *   <li><b>Genuine {@code DELETE}</b> — GO's UI "Eliminar" action calls {@code apiFetch(..., {
 *       method: 'DELETE' })}, it never sends a PUT with {@code active: false} (see {@code
 *       DetailView.jsx}), and a DELETE never reaches {@link #smartDeactivate}. It is handled by
 *       {@link #beforeDelete}, the base-class hook that runs while the record still exists and
 *       then lets the default CRUD delete proceed untouched. No {@link #isExplicitlyDeactivating}
 *       check is needed there — the method itself is unconditionally "this config is going
 *       away."</li>
 * </ul>
 * <p>Without this, the schedule would keep firing the SII sending process twice a day for an
 * organization whose fiscal config no longer exists or is no longer active.
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
   * default CRUD deactivate it (invoices exist — audit trail must be preserved). Either way the
   * config is on its way out, so the auto-send schedule is removed first, while the record is
   * still loaded and can answer for its own client/organization — see the "auto-send schedule
   * cleanup on deactivation" class Javadoc section.
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

    unscheduleAutoSendFor(config, recordId);

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
   * Removes the auto-send schedule for a config a genuine {@code DELETE} is about to hard-delete,
   * while the record still exists and still carries its own client/organization. Returns without
   * touching the delete itself — see {@link AbstractSmartDeactivationHandler#beforeDelete}.
   */
  @Override
  protected void beforeDelete(NeoContext context, String recordId) {
    unscheduleAutoSendFor(OBDal.getInstance().get(AEATSIIConfig.class, recordId), recordId);
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
   * <p>Deactivation PUTs ({@code active=false}) and genuine DELETEs are skipped entirely — the
   * org flag should be cleared, and a config that is not becoming active must not get a
   * schedule. Their auto-send schedule cleanup runs in the pre-hook ({@link #smartDeactivate} /
   * {@link #beforeDelete}), not here — see class Javadoc.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    String method = context.getHttpMethod();
    boolean isPost = METHOD_POST.equalsIgnoreCase(method);
    boolean isPut = METHOD_PUT.equalsIgnoreCase(method);
    if (!isPost && !isPut) {
      // Includes a genuine DELETE: its schedule cleanup already ran in beforeDelete, where the
      // config record still existed and could answer for its own client/organization.
      return null;
    }
    if (isPut && isExplicitlyDeactivating(context.getRequestBody())) {
      // Cleanup already ran in smartDeactivate, for the same reason. Nothing left to do here.
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
   * Removes the auto-send schedule (if any) belonging to {@code config}, scoped to the record's
   * <b>own</b> client and organization — never the session's, which for a GO client-admin role is
   * normally organization {@code '0'} (the {@code '*'} org) and would match no schedule at all
   * (ETP-5117 follow-up; see the "auto-send schedule cleanup on deactivation" class Javadoc
   * section). Callers therefore must invoke this while the record still exists.
   *
   * <p>Non-fatal by design: a cleanup failure is logged at WARN — never at DEBUG, whose
   * invisibility at the default INFO level is precisely what hid this bug — and never allowed to
   * fail the delete/deactivate the user asked for.
   *
   * @param config   the config being deleted or deactivated, or {@code null} when it could not be
   *                 loaded (logged at WARN and skipped)
   * @param recordId the config's primary key, for logging only
   */
  private void unscheduleAutoSendFor(AEATSIIConfig config, String recordId) {
    try {
      if (config == null || config.getClient() == null || config.getOrganization() == null) {
        log.warn("SiiConfigDeactivateHandler: could not resolve client/organization from config "
            + "record {}; SKIPPING auto-send schedule cleanup — the SII sending process may keep "
            + "firing for an organization with no active configuration", recordId);
        return;
      }
      scheduleService.unscheduleAutoSend(config.getClient().getId(),
          config.getOrganization().getId(), SiiTbaiAutoSendScheduleService.SII_PROCESS_SEARCH_KEY);
    } catch (Exception e) {
      log.warn("SiiConfigDeactivateHandler: could not unschedule auto-send for config {}: {}",
          recordId, e.getMessage(), e);
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
