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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Drives the pre/post hook pipeline for sub-endpoint dispatch. Resolves the
 * {@link NeoHandler} qualifier on the entity, builds the {@link NeoContext},
 * and runs the handler chain around the default action.
 */
class NeoHookDispatcher {

  private static final Logger log = LogManager.getLogger(NeoHookDispatcher.class);
  private static final String HOOK_ERROR_MSG = "An internal error occurred while processing the hook handler";

  private final NeoServlet servlet;

  NeoHookDispatcher(NeoServlet servlet) {
    this.servlet = servlet;
  }

  /**
   * Execute a sub-endpoint through the hook pipeline.
   * If a handler exists for the entity qualifier, it gets pre/post hook calls.
   *
   * @param spec          the SF spec
   * @param entityName    the entity name within the spec
   * @param endpointType  which sub-endpoint is being invoked
   * @param fieldName     optional field name (selector column, action column, etc.)
   * @param httpMethod    the HTTP method
   * @param defaultAction supplier that executes the default service logic
   * @return the final NeoResponse
   */
  NeoResponse dispatchWithHooks(
      SFSpec spec, String entityName,
      NeoEndpointType endpointType, String fieldName,
      String httpMethod,
      java.util.function.Supplier<NeoResponse> defaultAction) {
    return dispatchWithHooks(spec, entityName, endpointType, fieldName, httpMethod, null,
        defaultAction);
  }

  /**
   * Overload that passes recordId and requestBody to the hook context.
   * Used by action endpoints where the record context matters.
   */
  NeoResponse dispatchWithHooks(
      SFSpec spec, String entityName,
      NeoEndpointType endpointType, String fieldName,
      String httpMethod, NeoSubEndpointDispatcher.ActionDispatchParams actionParams,
      java.util.function.Supplier<NeoResponse> defaultAction) {

    SFEntity entity = servlet.findEntity(spec.getId(), entityName);
    String qualifier = (entity != null) ? entity.getJavaQualifier() : null;

    if (StringUtils.isBlank(qualifier)) {
      return defaultAction.get();
    }

    NeoHandler handler = servlet.lookupHandler(qualifier);
    if (handler == null) {
      return defaultAction.get();
    }

    NeoContext hookCtx = buildHookContext(spec, entityName, endpointType, fieldName,
        httpMethod, entity, actionParams);
    return executeHookChain(handler, hookCtx, defaultAction, endpointType, entityName);
  }

  private NeoContext buildHookContext(SFSpec spec, String entityName,
      NeoEndpointType endpointType, String fieldName, String httpMethod,
      SFEntity entity, NeoSubEndpointDispatcher.ActionDispatchParams actionParams) {
    Tab adTab = entity != null ? entity.getADTab() : null;
    NeoContext.Builder contextBuilder = NeoContext.builder()
        .specName(spec.getName())
        .entityName(entityName)
        .httpMethod(httpMethod)
        .endpointType(endpointType)
        .fieldName(fieldName)
        .sfEntity(entity)
        .adTab(adTab)
        .obContext(OBContext.getOBContext());
    if (actionParams != null) {
      contextBuilder.recordId(actionParams.recordId)
          .requestBody(actionParams.requestBody);
    }
    return contextBuilder.build();
  }

  private NeoResponse executeHookChain(
      NeoHandler handler, NeoContext hookCtx,
      java.util.function.Supplier<NeoResponse> defaultAction,
      NeoEndpointType endpointType, String entityName) {
    try {
      NeoResponse preResult = handler.handle(hookCtx);
      if (preResult != null) {
        hookCtx.setPreviousResult(preResult);
        NeoResponse afterResult = handler.afterHandle(hookCtx);
        return afterResult != null ? afterResult : preResult;
      }

      NeoResponse defaultResult = defaultAction.get();

      hookCtx.setPreviousResult(defaultResult);
      NeoResponse afterResult = handler.afterHandle(hookCtx);
      return afterResult != null ? afterResult : defaultResult;

    } catch (Exception e) {
      log.error("Error in hook dispatch for {}/{}: {}",
          endpointType, entityName, e.getMessage(), e);
      return NeoResponse.error(500, HOOK_ERROR_MSG);
    }
  }
}
