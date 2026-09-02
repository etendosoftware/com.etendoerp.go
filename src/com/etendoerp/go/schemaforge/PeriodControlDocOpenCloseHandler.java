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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.Set;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.process.ProcessInstance;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.financialmgmt.calendar.PeriodControl;
import org.openbravo.service.db.CallProcess;

import com.etendoerp.go.schemaforge.util.AccountingDocumentTypeSupport;

/**
 * NeoHandler for the {@code documents} entity (C_PeriodControl records).
 *
 * <p>Intercepts the {@code openClose} ACTION endpoint. When the user selects an action
 * (Open / Close / Permanently Close) in the process parameter dialog, this handler:
 *
 * <ol>
 *   <li>Reads the chosen action code from the request body ({@code fieldValues.openClose}).</li>
 *   <li>Sets {@code C_PeriodControl.PeriodAction} to the chosen value (the procedure reads
 *       this directly).</li>
 *   <li>Calls stored procedure {@code C_PeriodControl_Process} (AD Process 168) passing the
 *       C_PeriodControl_ID as the {@code AD_PInstance.Record_ID}.</li>
 *   <li>Returns the translated process result.</li>
 * </ol>
 *
 * <p>Also overrides {@link #afterHandle} (ETP-4948 Issue 3) to filter the plain generic-CRUD
 * list this entity otherwise serves unfiltered: without this, every {@code C_PeriodControl} row
 * for a period is returned — one per registered {@code DocumentCategory} (DocBaseType) — even
 * for base types that never post to accounting at all (e.g. {@code SOO} Sales Order,
 * {@code POO} Purchase Order, {@code POR} Purchase Requisition) or that are structurally
 * excluded elsewhere in the app ({@link AccountingDocumentTypeSupport}, shared with the
 * Not Posted Documents window so the two never diverge on what counts as accounting-relevant).
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'period-control-doc-openclose'} on the
 * {@code documents} ETGO_SF_ENTITY record for the
 * {@code open-close-period-control} spec.
 */
@Named("period-control-doc-openclose")
public class PeriodControlDocOpenCloseHandler extends AbstractPeriodOpenCloseHandler {

  private static final Logger log = LogManager.getLogger(PeriodControlDocOpenCloseHandler.class);
  private static final String PROCESS_168_ID = "168";
  private static final String METHOD_GET = "GET";
  private static final String FIELD_DOCUMENT_CATEGORY = "documentCategory";
  private static final String KEY_RESPONSE = "response";
  private static final String KEY_DATA = "data";

  @Override
  protected NeoResponse doHandle(String openCloseValue, String recordId) throws Exception {
    PeriodControl periodControl = OBDal.getInstance().get(PeriodControl.class, recordId);
    if (periodControl == null) {
      return NeoResponse.error(404, "PeriodControl not found: " + recordId);
    }

    // C_PeriodControl_Process reads PeriodAction directly from the DB record
    periodControl.setPeriodAction(openCloseValue);
    OBDal.getInstance().save(periodControl);
    OBDal.getInstance().flush();

    Process process168 = OBDal.getInstance().get(Process.class, PROCESS_168_ID);
    if (process168 == null) {
      return NeoResponse.error(500, "AD Process 168 (C_PeriodControl_Process) not found");
    }

    ProcessInstance pInstance = CallProcess.getInstance().call(process168, recordId, null);
    OBDal.getInstance().getSession().refresh(pInstance);

    return PeriodOpenCloseSupport.translateResult(pInstance, process168);
  }

  /**
   * Filters the plain generic-CRUD list response for this entity down to accounting-relevant
   * document categories only (ETP-4948 Issue 3). Every {@code C_PeriodControl} row for a period
   * covers one registered {@code DocumentCategory} (DocBaseType) — including base types that
   * never post to accounting at all ({@code SOO}, {@code POO}, {@code POR}, …) or that are
   * structurally excluded elsewhere ({@link AccountingDocumentTypeSupport}) — none of which are
   * useful in a period-closing breakdown.
   *
   * <p>Follows the same {@code GET} + {@code CRUD} convention {@code
   * FinancialAccountHandler.afterHandle} uses to detect a list/getById fetch. No selector/
   * defaults GET path exists on the {@code documents} entity today, so the extra {@code
   * NeoEndpointType.CRUD} check is currently a no-op here in practice — kept anyway to match the
   * documented convention exactly rather than relying on that happening to be true. Every other
   * method — namely the {@code openClose} ACTION's own POST, handled by {@link #doHandle} —
   * returns immediately via the guard below, so this override only ever touches the plain
   * list/getById response.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!METHOD_GET.equals(context.getHttpMethod())
        || !NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      return null;
    }
    JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
    if (dataArr == null) {
      return null;
    }
    try {
      Set<String> accountedTableIds = AccountingDocumentTypeSupport.loadTablesWithActiveAccounting();
      JSONArray filtered = new JSONArray();
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject row = dataArr.getJSONObject(i);
        String category = row.optString(FIELD_DOCUMENT_CATEGORY, null);
        if (AccountingDocumentTypeSupport.isAccountingRelevant(category, accountedTableIds)) {
          filtered.put(row);
        }
      }
      JSONObject body = context.getPreviousResult().getBody();
      body.getJSONObject(KEY_RESPONSE).put(KEY_DATA, filtered);
      return NeoResponse.ok(body);
    } catch (JSONException e) {
      log.error("Error filtering accounting-relevant document categories", e);
      return null;
    }
  }

  @Override
  protected NeoResponse onError(String recordId, Exception e) {
    log.error("Error executing period control doc open/close for record {}", recordId, e);
    return NeoResponse.error(500, "Period control doc open/close failed: " + e.getMessage());
  }
}
