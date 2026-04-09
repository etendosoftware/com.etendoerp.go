/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoDiscoveryHelper;

/**
 * Handles discovery and spec-describe endpoints for NEO Headless.
 *
 * <p>GET /sws/neo/             — list all specs the current user can access
 * <p>GET /sws/neo/{specName}  — describe a window spec with entities and fields
 */
class NeoDiscoveryHandler {

  private static final Logger log = LogManager.getLogger(NeoDiscoveryHandler.class);

  private final NeoServlet servlet;

  NeoDiscoveryHandler(NeoServlet servlet) {
    this.servlet = servlet;
  }

  /**
   * Handle GET /sws/neo/ — list all active specs the current user can access.
   */
  void handleDiscovery(HttpServletResponse response) throws IOException {
    try {
      OBCriteria<SFSpec> specCriteria = OBDal.getInstance().createCriteria(SFSpec.class);
      specCriteria.addOrder(Order.asc(SFSpec.PROPERTY_NAME));
      List<SFSpec> allSpecs = specCriteria.list();

      JSONArray specsArray = new JSONArray();
      for (SFSpec spec : allSpecs) {
        if (!hasSpecAccess(spec)) {
          continue;
        }
        specsArray.put(buildSpecSummaryObject(spec));
      }

      JSONObject result = new JSONObject();
      result.put("specs", specsArray);
      servlet.writeResponse(response, NeoResponse.ok(result));
    } catch (Exception e) {
      log.error("Error in discovery endpoint: {}", e.getMessage(), e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Discovery error: " + e.getMessage());
    }
  }

  /**
   * Returns true if the current user has access to the given spec (window, process, or report).
   */
  boolean hasSpecAccess(SFSpec spec) {
    String specType = spec.getSpecType();
    if ("W".equals(specType)) {
      Window specWindow = spec.getADWindow();
      return specWindow == null || NeoAccessHelper.hasWindowAccess(specWindow.getId());
    }
    if ("P".equals(specType) || "R".equals(specType)) {
      Process adProcess = spec.getProcess();
      return adProcess == null || NeoAccessHelper.hasProcessAccess(adProcess.getId());
    }
    return true;
  }

  /**
   * Builds the discovery summary JSON object for a single spec.
   * Includes id, name, type, description, module, and type-specific fields.
   */
  JSONObject buildSpecSummaryObject(SFSpec spec) throws Exception {
    String specType = spec.getSpecType();
    JSONObject specObj = new JSONObject();
    specObj.put("id", spec.getId());
    specObj.put("name", spec.getName());
    specObj.put("type", specType);
    specObj.put("description", spec.getDescription());

    if ("W".equals(specType)) {
      Window specWindow = spec.getADWindow();
      if (specWindow != null) specObj.put("windowId", specWindow.getId());
      specObj.put("entities", buildEntitySummaryArray(spec.getId()));
    } else if ("P".equals(specType) || "R".equals(specType)) {
      Process adProcess = spec.getProcess();
      if (adProcess != null) specObj.put("processId", adProcess.getId());
      if ("R".equals(specType)) specObj.put("isReport", true);
    }

    Module specModule = spec.getADModule();
    if (specModule != null) specObj.put("moduleId", specModule.getId());
    return specObj;
  }

  /**
   * Handle GET /sws/neo/{specName} — describe a window spec with entities and fields.
   */
  void handleSpecDescribe(HttpServletResponse response, SFSpec spec) throws IOException {
    try {
      String specId = spec.getId();
      String specType = spec.getSpecType();

      JSONObject result = new JSONObject();
      result.put("id", spec.getId());
      result.put("name", spec.getName());
      result.put("type", specType);
      result.put("description", spec.getDescription());
      Module specModule = spec.getADModule();
      if (specModule != null) result.put("moduleId", specModule.getId());

      OBCriteria<SFEntity> entityCriteria = OBDal.getInstance().createCriteria(SFEntity.class);
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", specId));
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
      entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
      entityCriteria.addOrder(Order.asc(SFEntity.PROPERTY_SEQNO));
      List<SFEntity> entities = entityCriteria.list();

      JSONArray entitiesArray = new JSONArray();
      for (SFEntity entity : entities) {
        JSONObject entityObj = new JSONObject();
        entityObj.put("id", entity.getId());
        entityObj.put("name", entity.getName());
        entityObj.put("methods", buildMethodsArray(entity));

        Tab adTab = entity.getADTab();
        if (adTab != null) {
          entityObj.put("tabLevel", adTab.getTabLevel());
          entityObj.put("tabId", adTab.getId());
        }

        entityObj.put("isGet", Boolean.TRUE.equals(entity.isGet()));
        entityObj.put("isGetbyid", Boolean.TRUE.equals(entity.isGetByID()));
        entityObj.put("isPost", Boolean.TRUE.equals(entity.isPost()));
        entityObj.put("isPut", Boolean.TRUE.equals(entity.isPut()));
        entityObj.put("isPatch", Boolean.TRUE.equals(entity.isPatch()));
        entityObj.put("isDelete", Boolean.TRUE.equals(entity.isDelete()));

        entityObj.put("fields", buildFieldsArray(entity.getId()));
        entitiesArray.put(entityObj);
      }

      result.put("entities", entitiesArray);
      servlet.writeResponse(response, NeoResponse.ok(result));
    } catch (Exception e) {
      log.error("Error describing spec '{}': {}", spec.getName(), e.getMessage(), e);
      servlet.sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Spec describe error: " + e.getMessage());
    }
  }

  /**
   * Build a summary of entities for the discovery endpoint (name + methods only).
   */
  private JSONArray buildEntitySummaryArray(String specId) throws Exception {
    return NeoDiscoveryHelper.buildEntitySummaryArray(specId);
  }

  /**
   * Build a JSON array of enabled HTTP methods for an entity.
   */
  private JSONArray buildMethodsArray(SFEntity entity) {
    return NeoDiscoveryHelper.buildMethodsArray(entity);
  }

  /**
   * Build the fields array for a given entity, resolving AD_Column metadata.
   */
  private JSONArray buildFieldsArray(String entityId) throws Exception {
    return NeoDiscoveryHelper.buildFieldsArray(entityId);
  }
}
