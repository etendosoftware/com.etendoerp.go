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
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler that auto-fills the Verifactu adoption fields on {@code etvfac_verifactu_config}
 * (ETP-4389).
 *
 * <p>In classic Etendo, the "Marcar como listo" process
 * ({@code com.etendoerp.verifactu.process.SetAsReady}) sets {@code IS_Ready = true} and
 * {@code IN_Vfactu_System = <now>} ("Fecha de Acogida") when a fiscal configuration is marked
 * ready for Verifactu submission. In Etendo Go, neither the {@code /fiscal-config} onboarding
 * wizard (POST) nor {@code VerifactuSection.jsx} (PUT) triggers that side effect, so
 * {@code in_vfactu_system} stays {@code null} and invoices are never flagged for Verifactu
 * submission. This handler closes that gap by running the same field-fill after every
 * create/update of the entity.
 *
 * <p>It uses native SQL against {@code etvfac_verifactu_config} (columns
 * {@code is_ready} and {@code in_vfactu_system}) instead of the generated DAL class
 * ({@code com.etendoerp.verifactu.data.VerifactuConfig}) to avoid introducing a compile-time
 * dependency from {@code com.etendoerp.go} on the {@code com.etendoerp.verifactu} module,
 * mirroring the approach used by {@link MarkSubsanationHandler}.
 *
 * <p>This is a best-effort, secondary side-effect (unlike a primary-purpose handler such as
 * {@link MarkSubsanationHandler}): the fiscal config record has already been created/updated
 * by the generic CRUD service by the time {@link #afterHandle(NeoContext)} runs, so a failure
 * here must never fail the parent request. Any exception is logged and swallowed, and
 * {@code null} is returned so the original CRUD response is kept untouched.
 */
@Named("verifactu-config-ready-handler")
public class VerifactuConfigReadyHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(VerifactuConfigReadyHandler.class);

  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";

  private static final String SELECT_IS_READY_SQL =
      "SELECT is_ready FROM etvfac_verifactu_config WHERE etvfac_verifactu_config_id = :id";

  // Timestamp is computed in SQL (now()) rather than passed as a Java Date, matching the
  // convention already used for this kind of native-SQL side effect (see
  // AbstractInOutLineHandler#linkInvoiceLineIfPresent, "updated = now()").
  private static final String SET_READY_SQL =
      "UPDATE etvfac_verifactu_config SET is_ready = 'Y', in_vfactu_system = now() "
          + "WHERE etvfac_verifactu_config_id = :id";

  @Override
  public NeoResponse handle(NeoContext context) {
    // No pre-hook behavior: this handler only reacts after the record is persisted.
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    String method = context.getHttpMethod();
    if (!METHOD_POST.equalsIgnoreCase(method)
        && !METHOD_PUT.equalsIgnoreCase(method)
        && !METHOD_PATCH.equalsIgnoreCase(method)) {
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
   * marked ready. Idempotent by design: re-saving an already-adopted config (e.g. a later PUT
   * from {@code VerifactuSection.jsx}) must not reset the original adoption date.
   */
  void markReadyIfNeeded(String recordId) {
    Object currentReady = OBDal.getInstance().getSession()
        .createNativeQuery(SELECT_IS_READY_SQL)
        .setParameter("id", recordId)
        .uniqueResult();

    if ("Y".equals(currentReady)) {
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
