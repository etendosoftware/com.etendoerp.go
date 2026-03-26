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

package com.etendoerp.go.schemaforge.webhooks;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;

import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook that returns the list of active AD processes.
 * Supports optional search filter via "q" parameter.
 *
 * GET /webhooks/SFListProcesses
 * GET /webhooks/SFListProcesses?q=report
 */
public class SFListProcesses extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFListProcesses.class);

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    OBContext.setAdminMode();
    try {
      String query = parameter.get("q");

      OBCriteria<Process> criteria = OBDal.getInstance().createCriteria(Process.class);
      criteria.add(Restrictions.eq(Process.PROPERTY_ACTIVE, true));

      if (query != null && !query.trim().isEmpty()) {
        criteria.add(Restrictions.ilike(Process.PROPERTY_NAME, "%" + query.trim() + "%"));
      }

      criteria.addOrderBy(Process.PROPERTY_NAME, true);
      criteria.setMaxResults(100);

      List<Process> processes = criteria.list();

      JSONArray arr = new JSONArray();
      for (Process p : processes) {
        JSONObject obj = new JSONObject();
        obj.put("id", p.getId());
        obj.put("name", p.getName());
        arr.put(obj);
      }

      responseVars.put("result", arr.toString());
      responseVars.put("count", String.valueOf(processes.size()));

    } catch (Exception e) {
      log.error("Error in SFListProcesses", e);
      responseVars.put("error", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
