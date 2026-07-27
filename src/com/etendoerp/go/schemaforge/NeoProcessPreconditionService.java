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

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Generic, metadata-driven precondition choke-point for NEO process execution (ETP-4275).
 *
 * <p>This is the single, process-agnostic entry point ({@link #validate(Process, JSONObject)})
 * invoked by {@link NeoProcessService} right after mandatory-parameter validation and
 * <em>before</em> any legacy process fires. It resolves the target record from the request
 * context and delegates the actual rule evaluation to {@link NeoProcessPreconditionValidator},
 * returning a structured {@code PRECONDITIONS_UNMET} 400 when declared preconditions are unmet
 * so clients get an explicit list of missing NEO fields instead of an opaque late PL/pgSQL
 * error. No process-specific logic lives here — everything is driven by the declared data on
 * {@code ETGO_SF_ENTITY.preconditions}.</p>
 */
public final class NeoProcessPreconditionService {

  private static final Logger log = LogManager.getLogger(NeoProcessPreconditionService.class);

  private NeoProcessPreconditionService() {
  }

  /**
   * Validates the record-level preconditions declared on {@code ETGO_SF_ENTITY.preconditions}
   * for the process being executed. This is the single generic choke-point that returns a
   * structured {@code PRECONDITIONS_UNMET} 400 <em>before</em> the legacy process fires.
   *
   * <p>No-ops (returns {@code null}, i.e. continue) when there is no tab context, no matching
   * {@link SFEntity}, or no resolvable record — standalone process-specs already rely on
   * {@code NeoProcessService.validateMandatoryParams}. Any unexpected error is swallowed
   * (fail-open) so the legacy guards remain the backstop and process execution is never
   * blocked by validator bugs.
   *
   * @param process the process about to run
   * @param params  the request params (must carry {@code inpTabId} and a record id to apply)
   * @return a structured 400 {@link NeoResponse} when preconditions are unmet, else {@code null}
   */
  public static NeoResponse validate(Process process, JSONObject params) {
    try {
      String tabId = params.optString(NeoProcessService.INP_TAB_ID, params.optString("tabId", null));
      if (StringUtils.isBlank(tabId)) {
        return null;
      }
      SFEntity entity = resolveSfEntityByTab(tabId);
      if (entity == null || entity.getADTab() == null || entity.getADTab().getTable() == null) {
        return null;
      }
      Tab tab = entity.getADTab();
      String recordId = resolvePreconditionRecordId(params, tab);
      if (StringUtils.isBlank(recordId)) {
        return null;
      }
      BaseOBObject targetRecord = OBDal.getInstance().get(tab.getTable().getName(), recordId);
      if (targetRecord == null) {
        return null;
      }
      List<String> missing = NeoProcessPreconditionValidator
          .findUnmetPreconditions(process, entity, targetRecord, params);
      if (missing != null && !missing.isEmpty()) {
        return buildPreconditionsUnmetResponse(new PreconditionsUnmetException(missing));
      }
      return null;
    } catch (Exception e) {
      log.warn("Precondition validation skipped due to error for process {}: {}",
          process.getName(), e.getMessage());
      return null;
    }
  }

  private static SFEntity resolveSfEntityByTab(String tabId) {
    OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ADTAB + ".id", tabId));
    criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    criteria.setMaxResults(1);
    return (SFEntity) criteria.uniqueResult();
  }

  private static String resolvePreconditionRecordId(JSONObject params, Tab tab) {
    String recordId = params.optString(NeoProcessService.RECORD_ID,
        params.optString(NeoProcessService.INP_RECORD_ID, null));
    if (StringUtils.isNotBlank(recordId)) {
      return recordId;
    }
    if (tab.getTable() == null) {
      return null;
    }
    for (Column col : tab.getTable().getADColumnList()) {
      if (Boolean.TRUE.equals(col.isKeyColumn())) {
        return params.optString(col.getDBColumnName(), null);
      }
    }
    return null;
  }

  /**
   * Builds the structured 400 response returned when a process is rejected because declared
   * preconditions are not met. Body shape:
   * <pre>{
   *   "error": {
   *     "code": "PRECONDITIONS_UNMET",
   *     "status": 400,
   *     "message": "Preconditions not met",
   *     "missing": ["usableLifeMonths", "currency"]
   *   }
   * }</pre>
   */
  private static NeoResponse buildPreconditionsUnmetResponse(PreconditionsUnmetException e) {
    try {
      JSONArray missingArr = new JSONArray();
      for (String field : e.getMissing()) {
        missingArr.put(field);
      }
      JSONObject errorObj = new JSONObject();
      errorObj.put("code", PreconditionsUnmetException.ERROR_CODE);
      errorObj.put(NeoProcessService.STATUS, 400);
      errorObj.put(NeoProcessService.MESSAGE, "Preconditions not met");
      errorObj.put("missing", missingArr);
      JSONObject body = new JSONObject();
      body.put(NeoProcessService.ERROR, errorObj);
      return NeoResponse.error(400, body);
    } catch (JSONException fallback) {
      log.warn("Could not build PRECONDITIONS_UNMET body: {}", fallback.getMessage());
      return NeoResponse.error(400, PreconditionsUnmetException.ERROR_CODE);
    }
  }
}
