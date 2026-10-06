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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAuditTokenRefresh;

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

    // ETP-5558: before the customization or the AD button sees the record, whatever the entity.
    if (endpointType == NeoEndpointType.ACTION && actionParams != null) {
      NeoResponse refusal = NeoActionRecordGuard.refusalFor(entity, actionParams.recordId);
      if (refusal != null) {
        return refusal;
      }
    }

    NeoContext hookCtx = buildHookContext(spec, entityName, endpointType, fieldName,
        httpMethod, entity, actionParams);

    // ETP-5415: routed through NeoExtensionDispatcher, which is what brings the SELECTOR,
    // CALLOUT and EVALUATE_DISPLAY surfaces into the trace — they reach an entity's
    // customization only through this method. Two deliberate consequences:
    //
    //  - The blank-qualifier early return is gone. It was correct while Java_Qualifier was the
    //    only way to declare a customization; with @NeoExtension an entity that has no qualifier
    //    can still have one, and returning early here would make the annotation work on CRUD and
    //    silently not on any sub-endpoint. The dispatcher answers a blank qualifier itself.
    //  - "No customization is configured" is now logged as such (NO_CUSTOMIZATION) instead of
    //    being indistinguishable from a handler that declined.
    //
    // Everything else is preserved byte for byte: the same resolver (REST_SINGLE →
    // NeoServletSupport.lookupHandler), the same hook order, the same 500 on a throwing hook,
    // and the same audit-token refresh in runPostHook.
    NeoExtensionRequest request = NeoExtensionRequest.builder()
        .qualifier(qualifier)
        .specName(spec.getName())
        .entityName(entityName)
        .surface(NeoExtensionSurface.of(hookCtx))
        .channel(NeoExtensionChannel.REST_SINGLE)
        .context(hookCtx)
        .build();

    return executeHookChain(request, defaultAction, endpointType, entityName);
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
      NeoExtensionRequest request,
      java.util.function.Supplier<NeoResponse> defaultAction,
      NeoEndpointType endpointType, String entityName) {
    try {
      NeoExtensionResult pre = NeoExtensionDispatcher.dispatch(request);
      if (pre.customization() == null) {
        return defaultAction.get();
      }

      // A non-null pre-result short-circuits the default action but NOT the post hook: a
      // customization that produces the whole payload (a selector taking over its own field's
      // candidate list) is still offered the chance to refine it. This is the REST shape and it
      // is deliberately not the MCP write shape — see divergence D4.
      NeoResponse previousResult = pre.response() != null ? pre.response() : defaultAction.get();
      return runPostHook(request, pre.customization(), previousResult);

    } catch (Exception e) {
      log.error("Error in hook dispatch for {}/{}: {}",
          endpointType, entityName, e.getMessage(), e);
      return NeoResponse.error(500, HOOK_ERROR_MSG);
    }
  }

  /**
   * Runs the post-hook and refreshes the optimistic-lock token on the response.
   * Sub-endpoint handlers can persist changes during {@code afterHandle()}, so
   * the response must carry the version produced by those writes.
   */
  private NeoResponse runPostHook(NeoExtensionRequest request, NeoHandler customization,
      NeoResponse previousResult) {
    NeoExtensionResult post = NeoExtensionDispatcher.dispatch(
        request.post(customization).withPreviousResult(previousResult));
    NeoResponse effectiveResult = post.response() != null ? post.response() : previousResult;
    NeoAuditTokenRefresh.refreshInResponse(request.context(), effectiveResult);
    return effectiveResult;
  }
}
