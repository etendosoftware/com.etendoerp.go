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

package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Single source of truth for the per-entity HTTP method gate configured on
 * {@code ETGO_SF_ENTITY} ({@code ISGET}, {@code ISGETBYID}, {@code ISPOST}, {@code ISPUT},
 * {@code ISPATCH}, {@code ISDELETE}).
 *
 * <p>Before ETP-4254 this logic existed three times: once live in
 * {@code NeoCrudHandler#handleWindowEntityCrud} (the REST path), once dead in
 * {@code NeoServlet}, and not at all on the MCP write path — so turning a monitor/log
 * window's mutation flags off blocked the React UI with a {@code 405} while an MCP agent
 * could still write through {@code neo_create}/{@code neo_update}/{@code neo_delete}/
 * {@code neo_batch}. All four entry points now consult this class.</p>
 *
 * <p><b>Scope:</b> the gate covers entity CRUD only. Sub-endpoints
 * ({@code /action/*}, {@code /process}, {@code /callout}, {@code /selector},
 * {@code /defaults}) are intentionally NOT gated by these flags — a read-only-CRUD
 * monitor window may still legitimately expose a button action (e.g. {@code fiscal-monitor}'s
 * {@code Correct_Invoice}). Do not extend this class to sub-endpoints.</p>
 */
public final class NeoMethodPolicy {

  /** HTTP method names, kept here so callers do not re-declare private copies. */
  public static final String METHOD_GET = "GET";
  public static final String METHOD_POST = "POST";
  public static final String METHOD_PUT = "PUT";
  public static final String METHOD_PATCH = "PATCH";
  public static final String METHOD_DELETE = "DELETE";

  private NeoMethodPolicy() {
  }

  /**
   * Reports whether the given HTTP method is enabled on the entity's configuration.
   *
   * <p>{@code GET} is enabled when EITHER {@code ISGET} (list) or {@code ISGETBYID}
   * (single record) is set — the two flags share one HTTP verb.</p>
   *
   * @param entity the SF entity to test (a {@code null} entity enables nothing)
   * @param method the HTTP method name, case-sensitive and upper-case
   *               ({@code GET}/{@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE})
   * @return {@code true} when the method is enabled; {@code false} for a disabled method,
   *         an unknown method name, or a {@code null} entity
   */
  public static boolean isMethodEnabled(SFEntity entity, String method) {
    if (entity == null || method == null) {
      return false;
    }
    switch (method) {
      case METHOD_GET:
        return Boolean.TRUE.equals(entity.isGet()) || Boolean.TRUE.equals(entity.isGetByID());
      case METHOD_POST:
        return Boolean.TRUE.equals(entity.isPost());
      case METHOD_PUT:
        return Boolean.TRUE.equals(entity.isPut());
      case METHOD_PATCH:
        return Boolean.TRUE.equals(entity.isPatch());
      case METHOD_DELETE:
        return Boolean.TRUE.equals(entity.isDelete());
      default:
        return false;
    }
  }

  /**
   * Reports whether the entity declares at least one mutation method
   * ({@code POST}, {@code PUT}, {@code PATCH} or {@code DELETE}).
   *
   * @param entity the SF entity to test (may be {@code null})
   * @return {@code true} when any mutation method is enabled
   */
  public static boolean hasMutableMethod(SFEntity entity) {
    return isMethodEnabled(entity, METHOD_POST)
        || isMethodEnabled(entity, METHOD_PUT)
        || isMethodEnabled(entity, METHOD_PATCH)
        || isMethodEnabled(entity, METHOD_DELETE);
  }

  /**
   * Reports whether the entity declares at least one read method and no mutation method.
   *
   * <p>An entity with every flag off is NOT read-only — it is fully disabled, which is a
   * distinct (and misconfigured) state. This mirrors the pre-existing
   * {@code neo_discover} {@code readOnly} semantics.</p>
   *
   * @param entity the SF entity to test (may be {@code null})
   * @return {@code true} when the entity is readable and immutable
   */
  public static boolean isReadOnly(SFEntity entity) {
    return isMethodEnabled(entity, METHOD_GET) && !hasMutableMethod(entity);
  }

  /**
   * List the HTTP methods enabled on the entity, in canonical order
   * ({@code GET}, {@code POST}, {@code PUT}, {@code PATCH}, {@code DELETE}).
   * {@code GET} appears once even when both read flags are set.
   *
   * @param entity the SF entity to inspect (may be {@code null})
   * @return an ordered, possibly empty list of enabled method names
   */
  public static List<String> enabledMethods(SFEntity entity) {
    if (entity == null) {
      return Collections.emptyList();
    }
    List<String> methods = new ArrayList<>(5);
    for (String method : new String[] {
        METHOD_GET, METHOD_POST, METHOD_PUT, METHOD_PATCH, METHOD_DELETE }) {
      if (isMethodEnabled(entity, method)) {
        methods.add(method);
      }
    }
    return methods;
  }

  /**
   * Canonical REST {@code 405} message for a disabled method. The exact wording is part of
   * the NEO Headless HTTP contract (documented in {@code docs/neo-headless.md} §4.3) and is
   * asserted by tests — do not reword it.
   *
   * @param method     the rejected HTTP method
   * @param entityName the entity name as it appeared in the request path
   * @return the canonical message, e.g. {@code "POST not enabled for header"}
   */
  public static String buildNotEnabledMessage(String method, String entityName) {
    return method + " not enabled for " + entityName;
  }

  /**
   * Agent-facing explanation for an MCP tool call that targets a method the entity does not
   * enable. Unlike the terse REST {@code 405}, this text names the spec, lists the methods
   * that ARE available, and tells the agent what to do instead — the same "explain, do not
   * just refuse" contract as
   * {@code McpToolRouterSupport#resolveIncludedEntityOrExplain}.
   *
   * @param specName   the spec name from the tool call
   * @param entityName the entity name from the tool call
   * @param method     the HTTP-method equivalent of the refused MCP operation
   * @param entity     the resolved entity, used to list its enabled methods
   * @return a human/LLM-readable explanation
   */
  public static String buildMcpNotEnabledMessage(String specName, String entityName,
      String method, SFEntity entity) {
    List<String> enabled = enabledMethods(entity);
    String enabledText = enabled.isEmpty() ? "none" : String.join(", ", enabled);
    StringBuilder message = new StringBuilder()
        .append("Entity '").append(entityName).append("' of spec '").append(specName)
        .append("' does not enable ").append(method)
        .append(". Enabled methods: ").append(enabledText).append('.');
    if (isReadOnly(entity)) {
      message.append(" This entity is read-only by configuration — use neo_list or neo_get "
          + "to read it. CRUD writes to it are not allowed; a separately configured "
          + "neo_action may still be available. Do not retry this CRUD operation.");
    } else {
      message.append(" Pick a tool that matches an enabled method, or use neo_discover to "
          + "inspect this spec's entities before retrying.");
    }
    return message.toString();
  }
}
