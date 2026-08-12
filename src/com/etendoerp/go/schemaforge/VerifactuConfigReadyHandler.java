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
import org.openbravo.model.common.invoice.Invoice;

/**
 * NeoHandler for {@code etvfac_verifactu_config} that owns two behaviors:
 *
 * <ol>
 *   <li><b>Smart deactivation (pre-hook, ETP-4785):</b> when a PUT request explicitly sets
 *       {@code active=false}, checks whether any invoice was sent through Verifactu during
 *       this config's active period (i.e. with {@code dateinvoiced >= in_vfactu_system} and
 *       matching org). If the config has no adoption date ({@code in_vfactu_system IS NULL})
 *       or no invoice was sent through it, the record is <em>deleted</em> rather than merely
 *       deactivated, preventing orphan inactive configs from accumulating. If invoices were
 *       sent, the request falls through so the default CRUD deactivates the record normally.
 *   </li>
 *   <li><b>Adoption-date auto-fill (post-hook, ETP-4389):</b> after every POST/PUT/PATCH,
 *       sets {@code is_ready='Y'} and {@code in_vfactu_system=now()} on the saved record
 *       when those fields are not already both set — closing the gap left by Etendo Go not
 *       calling the classic "Marcar como listo" process.
 *   </li>
 * </ol>
 *
 * <p>Uses native SQL against {@code etvfac_verifactu_config} (rather than the generated DAL
 * class {@code com.etendoerp.verifactu.data.VerifactuConfig}) to avoid a compile-time
 * dependency from {@code com.etendoerp.go} on {@code com.etendoerp.verifactu}, mirroring
 * {@link MarkSubsanationHandler}.
 */
@Named("verifactu-config-ready-handler")
public class VerifactuConfigReadyHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(VerifactuConfigReadyHandler.class);

  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";

  private static final String FIELD_ACTIVE = "active";

  private static final String SELECT_IS_READY_SQL =
      "SELECT is_ready, in_vfactu_system, ad_org_id FROM etvfac_verifactu_config "
          + "WHERE etvfac_verifactu_config_id = :id";

  // Timestamp is computed in SQL (now()) rather than passed as a Java Date, matching the
  // convention already used for this kind of native-SQL side effect (see
  // AbstractInOutLineHandler#linkInvoiceLineIfPresent, "updated = now()").
  private static final String SET_READY_SQL =
      "UPDATE etvfac_verifactu_config SET is_ready = 'Y', in_vfactu_system = now() "
          + "WHERE etvfac_verifactu_config_id = :id";

  private static final String DELETE_CONFIG_SQL =
      "DELETE FROM etvfac_verifactu_config WHERE etvfac_verifactu_config_id = :id";

  /**
   * Pre-hook: implements smart deactivation (ETP-4785). When a PUT explicitly sets
   * {@code active=false}, checks whether invoices were sent through this config during its
   * active period. If none were sent, deletes the record and returns 200
   * {@code {"deleted":true}}. If invoices exist, returns {@code null} so the default CRUD
   * proceeds and deactivates the record normally (audit-trail preserved).
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
      log.error("VerifactuConfigReadyHandler.handle: error during smart deactivation for {}: {}",
          recordId, e.getMessage(), e);
      // Fail safe: let default CRUD deactivate normally rather than blocking the request.
      return null;
    }
  }

  /**
   * Decides between deleting the config record (no invoices sent through it) and letting the
   * default CRUD deactivate it (invoices exist — audit trail must be preserved).
   *
   * @param recordId the primary key of the {@code etvfac_verifactu_config} record
   * @return a 200 {@code {"deleted":true}} response when the record was deleted, or
   *     {@code null} to let default CRUD deactivate it.
   */
  NeoResponse smartDeactivate(String recordId) throws JSONException {
    Object[] row = (Object[]) OBDal.getInstance().getSession()
        .createNativeQuery(SELECT_IS_READY_SQL)
        .setParameter("id", recordId)
        .uniqueResult();

    if (row == null) {
      // Record already gone — nothing to do.
      return null;
    }

    java.util.Date adoptionDate = (java.util.Date) row[1];
    String orgId = (String) row[2];

    // If the config never entered the fiscal system (adoption date not set), delete directly.
    if (adoptionDate == null) {
      deleteConfig(recordId);
      return deletedResponse();
    }

    // Check whether any invoice was sent through Verifactu during this config's active period.
    if (hasVerifactuInvoicesSince(orgId, adoptionDate)) {
      // Invoices exist — let the default CRUD deactivate (preserve audit trail).
      return null;
    }

    deleteConfig(recordId);
    return deletedResponse();
  }

  /**
   * Returns {@code true} if at least one invoice was sent through Verifactu for the given
   * org with an invoice date on or after {@code since}. The date filter scopes the check to
   * this config's active period so that invoices from a prior config of the same org do not
   * incorrectly prevent deletion.
   */
  private boolean hasVerifactuInvoicesSince(String orgId, java.util.Date since) {
    OBCriteria<Invoice> crit = OBDal.getInstance().createCriteria(Invoice.class);
    crit.add(Restrictions.eq(Invoice.PROPERTY_ORGANIZATION + ".id", orgId));
    crit.add(Restrictions.eq(Invoice.PROPERTY_ETVFACSENTTOVERIFAC, Boolean.TRUE));
    crit.add(Restrictions.ge(Invoice.PROPERTY_INVOICEDATE, since));
    crit.setProjection(Projections.rowCount());
    Number count = (Number) crit.uniqueResult();
    return count != null && count.longValue() > 0;
  }

  private void deleteConfig(String recordId) {
    OBDal.getInstance().getSession()
        .createNativeQuery(DELETE_CONFIG_SQL)
        .setParameter("id", recordId)
        .executeUpdate();
    OBDal.getInstance().flush();
    log.info("VerifactuConfigReadyHandler: deleted unused config record {}", recordId);
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

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    String method = context.getHttpMethod();
    if (!METHOD_POST.equalsIgnoreCase(method)
        && !METHOD_PUT.equalsIgnoreCase(method)
        && !METHOD_PATCH.equalsIgnoreCase(method)) {
      return null;
    }
    // If handle() already deleted the record (smart deactivation), skip the adoption-date fill.
    NeoResponse preResult = context.getPreviousResult();
    if (preResult != null && preResult.getBody() != null
        && preResult.getBody().optBoolean("deleted", false)) {
      return null;
    }

    String recordId = resolveRecordId(context, method);
    if (StringUtils.isBlank(recordId)) {
      // Nothing to key the update on — the parent create/update already succeeded, so we
      // just skip the auto-fill rather than fail the request.
      return null;
    }

    try {
      OBContext.setAdminMode(true);
      try {
        markReadyIfNeeded(recordId);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.error("Error auto-filling Verifactu adoption date for record {}: {}",
          recordId, e.getMessage(), e);
    }
    // Best-effort side effect: never replace the original CRUD response.
    return null;
  }

  /**
   * Sets {@code is_ready='Y'} and {@code in_vfactu_system=now()} unless the record is already
   * fully adopted, i.e. {@code is_ready='Y'} AND {@code in_vfactu_system IS NOT NULL}.
   *
   * <p>Both conditions are checked (not just {@code is_ready}) to also cover legacy or
   * partially-migrated records that already have {@code is_ready='Y'} but a {@code null}
   * {@code in_vfactu_system} — {@code InvoiceSendingListener#getAllIssuersWithVfactuConfig()}
   * requires both fields to be set to schedule an issuer for Verifactu submission, so such a
   * record must still be backfilled here rather than skipped.
   *
   * <p>When the update runs, it unconditionally re-applies {@code SET_READY_SQL} (both
   * {@code is_ready='Y'} and {@code in_vfactu_system=now()}) instead of only patching the
   * missing column: re-setting {@code is_ready} to {@code 'Y'} when it is already {@code 'Y'}
   * is a no-op with no observable difference, and reusing the existing unconditional statement
   * keeps this method's SQL surface small. This intentionally means a legacy record's adoption
   * date is backfilled to "now" rather than reconstructed from history, which is acceptable
   * since there is no reliable original date to recover.
   *
   * <p>Idempotent by design for the common case: re-saving an already fully-adopted config
   * (e.g. a later PUT from {@code VerifactuSection.jsx}) must not reset the original adoption
   * date, and it does not, since {@code in_vfactu_system} is already non-null in that case.
   */
  void markReadyIfNeeded(String recordId) {
    Object[] currentState = (Object[]) OBDal.getInstance().getSession()
        .createNativeQuery(SELECT_IS_READY_SQL)
        .setParameter("id", recordId)
        .uniqueResult();

    if (currentState == null) {
      // Record not found (e.g. deleted concurrently) — nothing to update.
      return;
    }

    boolean isReady = "Y".equals(currentState[0]);
    boolean hasAdoptionDate = currentState[1] != null;
    if (isReady && hasAdoptionDate) {
      return;
    }

    OBDal.getInstance().getSession()
        .createNativeQuery(SET_READY_SQL)
        .setParameter("id", recordId)
        .executeUpdate();

    OBDal.getInstance().flush();
  }

  /**
   * Resolves the id of the record the auto-fill applies to. For PUT/PATCH the id comes straight
   * from the URL ({@link NeoContext#getRecordId()}). For POST there is no id in the create URL,
   * so it is read from the just-committed CRUD response envelope
   * ({@code response.data[0].id} or, defensively, {@code response.data.id} for a single-object
   * envelope), the same way {@code AbstractInOutLineHandler#linkInvoiceLineIfPresent} recovers
   * the id of a newly created line.
   */
  String resolveRecordId(NeoContext context, String method) {
    if (METHOD_PUT.equalsIgnoreCase(method) || METHOD_PATCH.equalsIgnoreCase(method)) {
      return context.getRecordId();
    }

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
}
