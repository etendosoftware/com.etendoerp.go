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

import java.util.ArrayList;
import java.util.List;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoMethodPolicy;

/**
 * The methods the MCP may use on an entity: the {@code ETGO_SF_ENTITY} flags
 * ({@link NeoMethodPolicy}), minus the verbs {@code MCP_CONFIG.verbs} hides ({@link McpVerbsSection},
 * ETP-5558).
 *
 * <p>The single MCP-side source of that answer. {@code etendo_discover}, {@code etendo_schema}, the tool
 * catalogue, the MCP resources and every write verb ask here, never {@link NeoMethodPolicy}
 * directly: a surface that kept reading the raw flags would advertise or execute what the others
 * refuse. {@code McpVerbsSectionTest} pins that. REST keeps reading {@link NeoMethodPolicy} and is
 * unaffected.</p>
 */
final class McpMethodPolicy {

  private static final String[] ALL_METHODS = {
      NeoMethodPolicy.METHOD_GET, NeoMethodPolicy.METHOD_POST, NeoMethodPolicy.METHOD_PUT,
      NeoMethodPolicy.METHOD_PATCH, NeoMethodPolicy.METHOD_DELETE };

  private McpMethodPolicy() {
  }

  /**
   * @param entity the SchemaForge entity
   * @param method the HTTP-method equivalent of the MCP operation
   * @return {@code true} when the flag enables it and {@code MCP_CONFIG.verbs} does not hide it
   */
  static boolean isMethodEnabled(SFEntity entity, String method) {
    return NeoMethodPolicy.isMethodEnabled(entity, method)
        && McpVerbsSection.hiddenFor(entity, method) == null;
  }

  /**
   * The raw {@code ETGO_SF_ENTITY} flag, ignoring {@code MCP_CONFIG.verbs}.
   *
   * <p>Only for telling the two refusals apart ({@code McpToolRouterSupport#requireMethodEnabled}):
   * a flag that is off and a verb that is hidden answer differently. Never use it to decide what to
   * advertise or execute — that is {@link #isMethodEnabled}.</p>
   *
   * @param entity the SchemaForge entity
   * @param method the HTTP-method equivalent of the MCP operation
   * @return whether the flag enables the method
   */
  static boolean isFlagEnabled(SFEntity entity, String method) {
    return NeoMethodPolicy.isMethodEnabled(entity, method);
  }

  /**
   * @param entity the SchemaForge entity, may be {@code null}
   * @return the methods the MCP may use, in {@code GET, POST, PUT, PATCH, DELETE} order
   */
  static List<String> enabledMethods(SFEntity entity) {
    List<String> methods = new ArrayList<>(ALL_METHODS.length);
    if (entity == null) {
      return methods;
    }
    for (String method : ALL_METHODS) {
      if (isMethodEnabled(entity, method)) {
        methods.add(method);
      }
    }
    return methods;
  }

  /**
   * @param entity the SchemaForge entity
   * @return {@code true} when the MCP may use at least one write method
   */
  static boolean hasMutableMethod(SFEntity entity) {
    return isMethodEnabled(entity, NeoMethodPolicy.METHOD_POST)
        || isMethodEnabled(entity, NeoMethodPolicy.METHOD_PUT)
        || isMethodEnabled(entity, NeoMethodPolicy.METHOD_PATCH)
        || isMethodEnabled(entity, NeoMethodPolicy.METHOD_DELETE);
  }

  /**
   * The refusal for a method whose {@code ETGO_SF_ENTITY} flag is off, worded as
   * {@link NeoMethodPolicy#buildMcpNotEnabledMessage} but listing the methods the <b>MCP</b> may use
   * (ETP-5558). The shared message lists the raw flags, so on an entity that also hides a verb it
   * would name that verb as available and send the agent into a second refusal. The shared method
   * is left as it is.
   *
   * @param specName   the spec being called
   * @param entityName the entity
   * @param method     the refused method
   * @param entity     the SchemaForge entity
   * @return the message
   */
  static String buildNotEnabledMessage(String specName, String entityName, String method,
      SFEntity entity) {
    List<String> enabled = enabledMethods(entity);
    String enabledText = enabled.isEmpty() ? "none" : String.join(", ", enabled);
    StringBuilder message = new StringBuilder()
        .append("Entity '").append(entityName).append("' of spec '").append(specName)
        .append("' does not enable ").append(method)
        .append(". Enabled methods: ").append(enabledText).append('.');
    if (isReadOnly(entity)) {
      message.append(" This entity is read-only by configuration — use etendo_list or etendo_get "
          + "to read it. CRUD writes to it are not allowed; a separately configured "
          + "etendo_action may still be available. Do not retry this CRUD operation.");
    } else {
      message.append(" Pick a tool that matches an enabled method, or use etendo_discover to "
          + "inspect this spec's entities before retrying.");
    }
    return message.toString();
  }

  /**
   * @param entity the SchemaForge entity
   * @return {@code true} when the entity is readable and the MCP may not write it
   */
  static boolean isReadOnly(SFEntity entity) {
    return isMethodEnabled(entity, NeoMethodPolicy.METHOD_GET) && !hasMutableMethod(entity);
  }
}
