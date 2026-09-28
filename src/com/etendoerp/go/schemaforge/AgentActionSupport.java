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

package com.etendoerp.go.schemaforge;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Plumbing shared by the dispatchers that expose a report spec's SPA routes to agents through
 * {@code neo_action} ({@link ReconciliationAgentActions} for ETP-5468,
 * {@link BankStatementAgentActions} for ETP-5469).
 *
 * <p>Each dispatcher owns its contracts and its routing; what is identical — the role gate, the
 * derived SPA-shaped context and the flush-to-clean on success — lives here once, so a fix to one
 * of them reaches every agent surface.</p>
 */
final class AgentActionSupport {

  /** Same give-up point as Core's {@code SessionHandler#flushRemainingChanges}. */
  private static final int MAX_FLUSHES = 100;

  private static final Logger log = LogManager.getLogger(AgentActionSupport.class);

  private AgentActionSupport() {
  }

  /**
   * The same role gate the SPA route passes through {@code NeoRequestRouter}: report-spec access
   * with the HTTP method the SPA would use. {@code neo_action} is authorized as a read by the MCP
   * router, so a write needs the POST check here. Fails closed when the spec cannot be resolved.
   *
   * @param context  the ACTION context (its entity names the spec)
   * @param mutating whether the action writes
   * @return {@code true} when the current role may run it
   */
  static boolean hasAccess(NeoContext context, boolean mutating) {
    SFSpec spec = context.getSfEntity() != null ? context.getSfEntity().getETGOSFSpec() : null;
    return spec != null && NeoAccessHelper.hasReportSpecAccess(spec, mutating ? "POST" : "GET");
  }

  /**
   * A context shaped like the SPA request the action re-enters: same spec, entity, role and
   * origin, with the given method, body and query params and no endpoint type.
   *
   * @param source the ACTION context
   * @param method {@code GET} or {@code POST}
   * @param body   the request body, or {@code null}
   * @param query  the query params, or {@code null} for none
   * @return the derived context
   */
  static NeoContext derive(NeoContext source, String method, JSONObject body,
      Map<String, String> query) {
    return NeoContext.builder()
        .specName(source.getSpecName())
        .entityName(source.getEntityName())
        .httpMethod(method)
        .recordId(source.getRecordId())
        .requestBody(body)
        .queryParams(query != null ? query : Collections.emptyMap())
        .adTab(source.getAdTab())
        .sfEntity(source.getSfEntity())
        .obContext(source.getObContext())
        .mcpOrigin(source.isMcpOrigin())
        .build();
  }

  /**
   * A shallow copy, so the dispatcher can add the record id without mutating the caller's object.
   *
   * @param source the object to copy
   * @return a new object with the same keys and values
   * @throws JSONException if a value cannot be read
   */
  static JSONObject copy(JSONObject source) throws JSONException {
    JSONObject out = new JSONObject();
    for (Iterator<?> it = source.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      out.put(key, source.get(key));
    }
    return out;
  }

  /**
   * Flushes the session to a clean state while the caller's {@code OBContext} is still set, and
   * turns a flush failure into a rolled-back JSON error (ETP-5468, BUG-2).
   *
   * <p><b>Why.</b> Business event handlers change data during a flush, so Core flushes repeatedly
   * until the session is clean ({@code SessionHandler#flushRemainingChanges}). The SPA route is
   * committed by {@code DalRequestFilter} with the request's {@code OBContext} still in place, so
   * those extra flushes succeed. The MCP servlet runs each tool inside
   * {@code McpSessionManager#executeInContext}, which flushes ONCE and then restores the previous
   * (null) {@code OBContext}; whatever the first flush left dirty is flushed again by
   * {@code DalThreadCleaner} at request end with no context, {@code OBInterceptor} throws a
   * NullPointerException, the commit fails and the client gets a Tomcat HTML 500 — after the
   * tool had already reported success. Flushing to clean HERE, inside the tool, leaves nothing for
   * that late flush, and a failure is rolled back and answered as JSON like any other refusal.</p>
   *
   * <p>Local to the agent dispatchers on purpose: the defect is in the generic MCP session scope
   * and a fix there changes every MCP tool. An error response is returned untouched — the SPA
   * route already rolled it back.</p>
   *
   * @param action   the action name, for the log
   * @param subject  what was being saved ("reconciliation", "bank statement"), for the messages
   * @param written  the SPA route's response
   * @param rollback how to roll back when the flush fails
   * @return {@code written}, or a JSON 500 when the flush failed and was rolled back
   */
  static NeoResponse flushWhileContextIsSet(String action, String subject, NeoResponse written,
      Runnable rollback) {
    if (written == null || written.getHttpStatus() >= HttpServletResponse.SC_BAD_REQUEST) {
      return written;
    }
    try {
      int flushes = 0;
      while (OBDal.getInstance().getSession().isDirty() && flushes < MAX_FLUSHES) {
        OBDal.getInstance().flush();
        flushes++;
      }
      return written;
    } catch (Exception e) {
      log.error("{}: could not persist the {} changes; rolled back", action, subject, e);
      rollback.run();
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "The " + subject + " changes could not be saved and were rolled back: "
              + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
    }
  }
}
