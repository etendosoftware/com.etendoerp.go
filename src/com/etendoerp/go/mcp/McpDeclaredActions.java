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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;
import com.etendoerp.go.schemaforge.util.NeoHandlerLookup;

/**
 * The actions an entity's customization declares, as the MCP publishes and runs them
 * (ETP-5468, extended to window entities by ETP-5558).
 *
 * <p>Resolution follows the dispatcher's order — the {@code @NeoExtension} annotation first, the
 * {@code Java_Qualifier} second — so the handler whose contracts are published is the handler
 * {@code neo_action} actually runs. A composite header handler declares the union of what its
 * delegates serve. {@code MCP_CONFIG.actions} then removes what the MCP must not offer.</p>
 *
 * <p>Two shapes, chosen by the spec's structure, never its name: on a report spec the
 * declaration <b>is</b> the action surface and replaces the AD schema (the AD tab there only gates
 * the role); on a window spec the declared actions sit <b>next to</b> the AD buttons.</p>
 */
final class McpDeclaredActions {

  private static final Logger log = LogManager.getLogger(McpDeclaredActions.class);

  private static final String SPEC_TYPE_REPORT = "R";

  private McpDeclaredActions() {
  }

  /**
   * The declared actions the MCP offers on an entity.
   *
   * @param entity the SchemaForge entity
   * @return the contracts by name, minus those {@code MCP_CONFIG.actions} hides; empty when the
   *         customization declares none or cannot be resolved
   */
  static Map<String, NeoActionContract> of(SFEntity entity) {
    Map<String, NeoActionContract> declared = declaredBy(customizationOf(entity));
    if (declared.isEmpty()) {
      return declared;
    }
    McpActionsSection.View config = McpActionsSection.forEntity(entity);
    Map<String, NeoActionContract> offered = new LinkedHashMap<>();
    for (Map.Entry<String, NeoActionContract> e : declared.entrySet()) {
      if (!config.isHidden(e.getKey())) {
        offered.put(e.getKey(), e.getValue());
      }
    }
    return offered;
  }

  /**
   * Whether the declaration replaces the entity's AD schema ({@code view:"actions"} and every
   * other view) instead of sitting next to its AD buttons.
   *
   * @param entity the SchemaForge entity
   * @return {@code true} for an entity of a report spec
   */
  static boolean replacesSchema(SFEntity entity) {
    SFSpec spec = entity == null ? null : entity.getETGOSFSpec();
    return spec != null && SPEC_TYPE_REPORT.equals(spec.getSpecType());
  }

  /**
   * Judge a {@code neo_action} call before the customization runs.
   *
   * <ol>
   *   <li>An unusable {@code MCP_CONFIG}, a hidden action or a redirected button is refused (405).
   *       The customization would still serve it — that is the point: the refusal is MCP-only.</li>
   *   <li>A declared action is validated against its contract: an undeclared key, a missing
   *       required one or a value of the wrong shape is a 422 that names it, before anything
   *       runs.</li>
   *   <li>Anything else — an AD button — is not judged here.</li>
   * </ol>
   *
   * @param entity     the SchemaForge entity
   * @param action     the requested action
   * @param parameters the call's parameters, may be {@code null}
   * @return the action's contract when it is declared (the caller reads its HTTP method), else
   *         {@code null}
   * @throws McpRoutingException when the call must not run
   * @throws JSONException       if a refusal cannot be built
   */
  static NeoActionContract precheck(SFEntity entity, String action, JSONObject parameters)
      throws JSONException {
    SFSpec spec = entity == null ? null : entity.getETGOSFSpec();
    String specName = spec == null ? null : spec.getName();
    String entityName = entity == null ? null : entity.getName();
    McpActionsSection.View config = McpActionsSection.forEntity(entity);
    if (config.isUnusable() || config.isHidden(action)) {
      throw McpRoutingException.actionHidden(specName, entityName, action, config.getReason());
    }
    String redirect = config.redirectOf(action);
    if (redirect != null) {
      throw McpRoutingException.actionRedirected(specName, entityName, action, redirect,
          config.getReason());
    }
    Map<String, NeoActionContract> contracts = of(entity);
    NeoActionContract contract = contracts.get(action);
    if (contract == null) {
      return null;
    }
    NeoResponse invalid = NeoActionContract.validate(contracts, action, parameters);
    if (invalid != null) {
      JSONObject body = invalid.getBody();
      JSONObject err = body == null ? null : body.optJSONObject("error");
      throw McpRoutingException.actionParametersInvalid(specName, entityName, action,
          err != null ? err : new JSONObject());
    }
    return contract;
  }

  /** Annotation first, qualifier second — the order {@code NeoExtensionDispatcher} resolves in. */
  private static NeoHandler customizationOf(SFEntity entity) {
    if (entity == null) {
      return null;
    }
    SFSpec spec = entity.getETGOSFSpec();
    try {
      NeoHandler annotated = NeoExtensionIndex.resolve(spec == null ? null : spec.getName(),
          entity.getName(), entity.getJavaQualifier());
      if (annotated != null) {
        return annotated;
      }
    } catch (RuntimeException e) {
      log.debug("@NeoExtension lookup failed for {}: {}", entity.getName(), e.getMessage());
    }
    return NeoHandlerLookup.byQualifierQuietly(entity.getJavaQualifier());
  }

  /** Looked up quietly: a failing customization must not break discovery for the entity. */
  private static Map<String, NeoActionContract> declaredBy(NeoHandler handler) {
    if (handler == null) {
      return Collections.emptyMap();
    }
    try {
      Map<String, NeoActionContract> contracts = handler.actionContracts();
      return contracts != null ? contracts : Collections.emptyMap();
    } catch (RuntimeException e) {
      log.warn("Could not read the declared actions of {}: {}", handler.getClass().getName(),
          e.getMessage());
      return Collections.emptyMap();
    }
  }
}
