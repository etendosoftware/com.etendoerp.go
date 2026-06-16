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

package com.etendoerp.go.mcp;

import java.util.Set;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Executes NeoHandler pre/post hooks for MCP write operations, providing the same
 * hook parity with the REST CRUD path ({@code NeoServlet}) so validation and field
 * derivation run identically regardless of whether the write originates from the
 * REST API or from an AI agent via MCP.
 */
final class McpHookExecutor {

  private McpHookExecutor() {
  }

  /**
   * Resolve the {@link NeoHandler} registered for the entity's Java_Qualifier.
   * Uses {@link Bean#getName()} — the CDI-standard way to read the {@code @Named}
   * value; works correctly on scoped proxies ({@code @ApplicationScoped}, etc.).
   *
   * @return the matching handler, or {@code null} when the entity declares no
   *         qualifier or no matching {@code @Named} handler is deployed
   */
  @SuppressWarnings("unchecked")
  static NeoHandler resolveEntityHandler(SFEntity sfEntity) {
    String qualifier = sfEntity.getJavaQualifier();
    if (StringUtils.isBlank(qualifier)) {
      return null;
    }
    BeanManager bm = WeldUtils.getStaticInstanceBeanManager();
    Set<Bean<?>> beans = bm.getBeans(NeoHandler.class, WeldUtils.ANY_LITERAL);
    for (Bean<?> bean : beans) {
      if (qualifier.equals(bean.getName())) {
        return (NeoHandler) bm.getReference(bean, NeoHandler.class,
            bm.createCreationalContext(bean));
      }
    }
    return null;
  }

  /**
   * Build the {@link NeoContext} an MCP write passes to its entity hook.
   * The body is the live DAL-property map the handler may mutate (e.g. inject
   * derived FK values) before the generic service persists it.
   */
  static NeoContext buildHookContext(String specName, String entityName, String method,
      String recordId, JSONObject body, Tab adTab, SFEntity sfEntity) {
    return NeoContext.builder()
        .specName(specName)
        .entityName(entityName)
        .httpMethod(method)
        .recordId(recordId)
        .requestBody(body)
        .adTab(adTab)
        .sfEntity(sfEntity)
        .obContext(OBContext.getOBContext())
        .endpointType(NeoEndpointType.CRUD)
        .build();
  }

  /**
   * Run the entity hook's pre-phase. Returns an MCP result to short-circuit to
   * write (a validation error, or a handler that fully handled the request such
   * as a soft-archive on DELETE), or {@code null} to proceed with generic
   * persistence. The handler may have mutated the request body in place.
   */
  static JSONObject runPreHook(NeoHandler handler, NeoContext ctx) throws JSONException {
    if (handler == null) {
      return null;
    }
    NeoResponse pre = handler.handle(ctx);
    return pre != null ? neoResponseToMcpResult(pre) : null;
  }

  /**
   * Run the entity hook's post-phase after a successful persist. Returns an MCP
   * result when the handler replaced the response, or {@code null} to keep the
   * default response.
   */
  static JSONObject runPostHook(NeoHandler handler, NeoContext ctx, JSONObject responseJson)
      throws JSONException {
    if (handler == null) {
      return null;
    }
    ctx.setPreviousResult(NeoResponse.ok(responseJson));
    NeoResponse post = handler.afterHandle(ctx);
    return post != null ? neoResponseToMcpResult(post) : null;
  }

  /**
   * Convert a {@link NeoResponse} to MCP result format.
   * Responses with status &ge; 400 set {@code isError: true}.
   */
  static JSONObject neoResponseToMcpResult(NeoResponse neoResponse) throws JSONException {
    if (neoResponse.getHttpStatus() >= 400) {
      String errorText = neoResponse.getBody() != null
          ? neoResponse.getBody().toString(2)
          : "Request failed with status " + neoResponse.getHttpStatus();
      return McpToolRouter.wrapAsErrorContent(errorText);
    }
    String text = neoResponse.getBody() != null
        ? neoResponse.getBody().toString(2)
        : "{}";
    return McpToolRouter.wrapAsTextContent(text);
  }
}
