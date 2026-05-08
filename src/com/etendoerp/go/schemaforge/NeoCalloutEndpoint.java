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

import java.util.Iterator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoServlet.NeoPathInfo;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Callout endpoint collaborator for {@link NeoServlet}. Resolves the target
 * entity, runs the callout via {@link NeoCalloutService}, cascades dependent
 * callouts, and gives the entity's {@link NeoHandler} an {@code afterCallout}
 * hook to enrich the response.
 */
class NeoCalloutEndpoint {

  private static final Logger log = LogManager.getLogger(NeoCalloutEndpoint.class);
  private static final String KEY_UPDATES = "updates";
  private static final String KEY_COMBOS = "combos";

  private final NeoServlet servlet;

  NeoCalloutEndpoint(NeoServlet servlet) {
    this.servlet = servlet;
  }

  /**
   * Handle callout execution requests.
   * POST /sws/neo/{specName}/{entityName}/callout
   * Delegates to NeoCalloutService for callout resolution, execution, and response transformation.
   */
  NeoResponse handleCallout(SFSpec spec,
      NeoPathInfo pathInfo, HttpServletRequest request) {
    try {
      // Find entity
      SFEntity sfEntity = servlet.findEntity(spec.getId(), pathInfo.entityName);
      if (sfEntity == null) {
        return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
            NeoServlet.ERR_ENTITY_NOT_FOUND + pathInfo.entityName);
      }

      Tab tab = sfEntity.getADTab();
      if (tab == null) {
        return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            NeoServlet.ERR_NO_LINKED_TAB + pathInfo.entityName);
      }

      // Parse request body
      String bodyStr = NeoRequestBodyParser.readRequestBody(request);
      if (StringUtils.isBlank(bodyStr)) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Request body is required for callout execution");
      }

      Object parsed = parseRequestBody(bodyStr);
      if (parsed instanceof NeoResponse neoResponse) {
        return neoResponse;
      }
      JSONObject requestBody = (JSONObject) parsed;

      if (!requestBody.has("field")) {
        return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
            "Missing required field: 'field'");
      }

      // Build context
      NeoContext neoContext = NeoContext.builder()
          .specName(pathInfo.specName)
          .entityName(pathInfo.entityName)
          .httpMethod("POST")
          .endpointType(NeoEndpointType.CALLOUT)
          .requestBody(requestBody)
          .adTab(tab)
          .sfEntity(sfEntity)
          .obContext(OBContext.getOBContext())
          .build();

      NeoResponse calloutResult = NeoCalloutService.executeCallout(neoContext, requestBody);
      if (calloutResult.getHttpStatus() == 200 && calloutResult.getBody() != null) {
        applyCascade(neoContext, tab, requestBody, calloutResult);
        applyAfterCalloutHook(neoContext, sfEntity, calloutResult);
      }
      return calloutResult;
    } catch (Exception e) {
      log.error("Error handling callout for {}/{}: {}",
          pathInfo.specName, pathInfo.entityName, e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Callout error: " + e.getMessage());
    }
  }

  private void applyCascade(NeoContext neoContext, Tab tab, JSONObject requestBody,
      NeoResponse calloutResult) {
    String fieldName = requestBody.optString("field", "");
    JSONObject formState = requestBody.optJSONObject("formState");
    NeoDefaultsService.CalloutCascadeResult cascade =
        NeoDefaultsCascadeHelper.cascadeInteractiveCallout(
            neoContext, tab, fieldName, formState, calloutResult.getBody());
    if (cascade.hasResults()) {
      mergeCalloutResponse(calloutResult.getBody(), cascade.toJSON());
      log.debug("[NEO-CALLOUT] Cascade merged additional fields into response");
    }
  }

  private void applyAfterCalloutHook(NeoContext neoContext, SFEntity sfEntity,
      NeoResponse calloutResult) {
    String qualifier = sfEntity.getJavaQualifier();
    if (StringUtils.isBlank(qualifier)) {
      return;
    }
    NeoHandler handler = servlet.lookupHandler(qualifier);
    if (handler != null) {
      runAfterCalloutHook(neoContext, calloutResult, handler, qualifier);
    }
  }

  private void runAfterCalloutHook(NeoContext neoContext, NeoResponse calloutResult,
      NeoHandler handler, String qualifier) {
    try {
      neoContext.setPreviousResult(calloutResult);
      NeoResponse handlerResult = handler.afterCallout(neoContext);
      if (handlerResult != null && handlerResult.getBody() != null) {
        mergeCalloutResponse(calloutResult.getBody(), handlerResult.getBody());
        log.debug("[NEO-CALLOUT] Handler '{}' merged additional fields via afterCallout",
            qualifier);
      }
    } catch (Exception e) {
      log.warn("[NEO-CALLOUT] afterCallout for handler '{}' failed (non-fatal): {}",
          qualifier, e.getMessage());
    }
  }

  /**
   * Merge cascade results into an existing REST callout response.
   * Does not overwrite fields already set by the initial callout.
   */
  private static Object parseRequestBody(String bodyStr) {
    try {
      return NeoRequestBodyParser.parseJsonObject(bodyStr);
    } catch (Exception e) {
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          "Invalid JSON body: " + e.getMessage());
    }
  }

  private void mergeCalloutResponse(JSONObject base, JSONObject addition) {
    try {
      mergeJsonSection(base, addition, KEY_UPDATES);
      mergeJsonSection(base, addition, KEY_COMBOS);
    } catch (Exception e) {
      log.debug("[NEO-CALLOUT] Failed to merge cascade results: {}", e.getMessage());
    }
  }

  private static void mergeJsonSection(JSONObject base, JSONObject addition, String sectionKey)
      throws JSONException {
    JSONObject addSection = addition.optJSONObject(sectionKey);
    if (addSection == null) {
      return;
    }
    JSONObject baseSection = base.optJSONObject(sectionKey);
    if (baseSection == null) {
      base.put(sectionKey, addSection);
      return;
    }
    @SuppressWarnings("unchecked")
    Iterator<String> keys = addSection.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!baseSection.has(key)) {
        baseSection.put(key, addSection.get(key));
      }
    }
  }
}
