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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;
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
    NeoHandler customization = customizationOf(entity);
    Map<String, NeoActionContract> declared = declaredBy(customization);
    if (declared.isEmpty()) {
      return declared;
    }
    Set<String> excluded = excludedBy(customization);
    McpActionsSection.View config = McpActionsSection.forEntity(entity);
    Map<String, NeoActionContract> offered = new LinkedHashMap<>();
    for (Map.Entry<String, NeoActionContract> e : declared.entrySet()) {
      if (!config.isHidden(e.getKey()) && !excluded.contains(e.getKey())) {
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
   * Refuse a value of a list-backed button that {@code MCP_CONFIG.actions.values} does not offer
   * (ETP-5558). The value travels as {@code docAction} (what the catalogue advertises) or
   * {@code action} (what classic processes read); sending neither keeps the button's default.
   */
  private static void requireAllowedValue(String specName, String entityName, String action,
      Set<String> allowed, JSONObject parameters) throws JSONException {
    if (allowed == null || parameters == null) {
      return;
    }
    for (String key : List.of(McpConstants.PARAM_DOC_ACTION, "action")) {
      String value = parameters.isNull(key) ? null : parameters.optString(key, null);
      if (StringUtils.isNotBlank(value) && !allowed.contains(value)) {
        JSONObject error = new JSONObject();
        error.put("message", "Value '" + value + "' of '" + key + "' is not offered for action '"
            + action + "' through MCP; send one of " + allowed + ", or none for the default.");
        error.put("allowedValues", new JSONArray(allowed));
        throw McpRoutingException.actionParametersInvalid(specName, entityName, action, error);
      }
    }
  }

  /**
   * Judge a {@code neo_action} call before the customization runs.
   *
   * <ol>
   *   <li>An unusable {@code MCP_CONFIG}, a hidden action or a redirected button is refused (405).
   *       The customization would still serve it — that is the point: the refusal is MCP-only. A
   *       button is matched under every name {@code neo_action} fires it by (field name or DB column
   *       name, {@link NeoButtonActionHelper#findButtonColumn}), so the configuration cannot be
   *       bypassed by spelling it the other way.</li>
   *   <li>A declared action is validated against its contract: an undeclared key, a missing
   *       required one or a value of the wrong shape is a 422 that names it, before anything
   *       runs.</li>
   *   <li>An action the customization excludes from agents
   *       ({@code NeoHandler#agentExcludedActions()}) is refused (405) in code, whatever the
   *       configuration says; {@code MCP_CONFIG.actions} is a second guard.</li>
   *   <li>Anything else — an AD button, or an action the customization serves without declaring
   *       it — is not judged here: it stays callable, as the UI offers it.</li>
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
    if (config.isUnusable()) {
      throw McpRoutingException.actionHidden(specName, entityName, action, config.getReason());
    }
    NeoHandler customization = customizationOf(entity);
    Map<String, NeoActionContract> declared = declaredBy(customization);
    Set<String> excluded = excludedBy(customization);
    Column button = declared.containsKey(action) ? null : buttonOf(entity, action);
    for (String name : namesOf(action, button)) {
      if (excluded.contains(name)) {
        throw McpRoutingException.actionHidden(specName, entityName, action,
            "its customization keeps it for people only");
      }
      if (config.isHidden(name)) {
        throw McpRoutingException.actionHidden(specName, entityName, action, config.getReason());
      }
      String redirect = config.redirectOf(name);
      if (redirect != null) {
        throw McpRoutingException.actionRedirected(specName, entityName, action, redirect,
            config.getRedirectReason());
      }
      requireAllowedValue(specName, entityName, action, config.allowedValuesOf(name), parameters);
    }
    NeoActionContract contract = declared.get(action);
    if (contract != null) {
      NeoResponse invalid = NeoActionContract.validate(declared, action, parameters);
      if (invalid != null) {
        JSONObject body = invalid.getBody();
        JSONObject err = body == null ? null : body.optJSONObject("error");
        throw McpRoutingException.actionParametersInvalid(specName, entityName, action,
            err != null ? err : new JSONObject());
      }
      return contract;
    }
    return null;
  }

  /**
   * The actions the entity's customization excludes from agents
   * ({@code NeoHandler#agentExcludedActions()}).
   *
   * @param entity the SchemaForge entity
   * @return the names, empty when none or the customization cannot be resolved
   */
  static Set<String> excludedOf(SFEntity entity) {
    return excludedBy(customizationOf(entity));
  }

  /** Looked up quietly, like {@link #declaredBy}. */
  private static Set<String> excludedBy(NeoHandler handler) {
    if (handler == null) {
      return Collections.emptySet();
    }
    try {
      Set<String> excluded = handler.agentExcludedActions();
      return excluded != null ? excluded : Collections.emptySet();
    } catch (RuntimeException e) {
      log.warn("Could not read the agent-excluded actions of {}: {}",
          handler.getClass().getName(), e.getMessage());
      return Collections.emptySet();
    }
  }

  /**
   * The AD button {@code action} names on the entity, or {@code null}: the one {@code neo_action}
   * would fire ({@link NeoButtonActionHelper#findButtonColumn} — included fields, by DB column or
   * field name), else a button column of the entity's table under either name. The second lookup
   * matters for a button whose field curation left out: it cannot fire, but its alias must still
   * meet the hidden/redirect check, so the agent gets the reason and the replacement rather than a
   * bare "Action not found".
   */
  private static Column buttonOf(SFEntity entity, String action) {
    if (entity == null || action == null) {
      return null;
    }
    try {
      Column fired = NeoButtonActionHelper.findButtonColumn(entity.getId(), action);
      return fired != null ? fired : tableButton(entity, action);
    } catch (RuntimeException e) {
      log.debug("Button lookup failed for {} on {}: {}", action, entity.getName(), e.getMessage());
      return null;
    }
  }

  private static Column tableButton(SFEntity entity, String action) {
    Tab tab = entity.getADTab();
    Table table = tab == null ? null : tab.getTable();
    List<Column> columns = table == null ? null : table.getADColumnList();
    if (columns == null) {
      return null;
    }
    for (Column column : columns) {
      if (Boolean.TRUE.equals(column.isActive()) && McpSchemaActionFields.isButtonColumn(column)
          && (action.equals(column.getDBColumnName()) || action.equals(propertyNameOf(column)))) {
        return column;
      }
    }
    return null;
  }

  /**
   * Every name the call is known by: as typed, and — for a button — its DB column name and the
   * field name {@code neo_schema} publishes (the DAL property name), which is what
   * {@code MCP_CONFIG.actions} lists.
   */
  private static Set<String> namesOf(String action, Column button) {
    Set<String> names = new LinkedHashSet<>();
    names.add(action);
    if (button != null) {
      names.add(button.getDBColumnName());
      String property = propertyNameOf(button);
      if (property != null) {
        names.add(property);
      }
    }
    return names;
  }

  private static String propertyNameOf(Column column) {
    try {
      Entity dal = ModelProvider.getInstance()
          .getEntityByTableName(column.getTable().getDBTableName());
      Property property = dal == null ? null : dal.getPropertyByColumnName(column.getDBColumnName());
      return property == null ? null : property.getName();
    } catch (RuntimeException e) {
      return null;
    }
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
