package com.etendoerp.go.schemaforge.webhooks;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.webhookevents.services.BaseWebhookService;

/**
 * Webhook that returns the list of active AD windows.
 * Supports optional search filter via "q" parameter.
 *
 * GET /webhooks/SFListWindows
 * GET /webhooks/SFListWindows?q=sales
 */
public class SFListWindows extends BaseWebhookService {

  private static final Logger log = LogManager.getLogger(SFListWindows.class);

  @Override
  public void get(Map<String, String> parameter, Map<String, String> responseVars) {
    OBContext.setAdminMode();
    try {
      String query = parameter.get("q");

      OBCriteria<Window> criteria = OBDal.getInstance().createCriteria(Window.class);
      criteria.add(Restrictions.eq(Window.PROPERTY_ACTIVE, true));

      if (query != null && !query.trim().isEmpty()) {
        criteria.add(Restrictions.ilike(Window.PROPERTY_NAME, "%" + query.trim() + "%"));
      }

      criteria.addOrderBy(Window.PROPERTY_NAME, true);
      criteria.setMaxResults(100);

      List<Window> windows = criteria.list();

      JSONArray arr = new JSONArray();
      for (Window w : windows) {
        JSONObject obj = new JSONObject();
        obj.put("id", w.getId());
        obj.put("name", w.getName());
        arr.put(obj);
      }

      responseVars.put("result", arr.toString());
      responseVars.put("count", String.valueOf(windows.size()));

    } catch (Exception e) {
      log.error("Error in SFListWindows", e);
      responseVars.put("error", e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
