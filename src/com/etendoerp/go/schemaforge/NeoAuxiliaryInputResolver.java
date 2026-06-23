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

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.ui.AuxiliaryInput;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Evaluates a tab's auxiliary inputs and injects the results into the session so that
 * column defaults referencing them resolve correctly — mirroring classic
 * {@code FormInitializationComponent.computeAuxiliaryInputs}.
 *
 * <p>Background: in classic Etendo, a column default can reference a tab auxiliary input by
 * name. For example, the GL Journal <em>Lines</em> tab defines an auxiliary input
 * {@code DESCRIPTION1} with code
 * {@code @SQL=SELECT description FROM gl_journal WHERE gl_journal_id=@GL_Journal_ID@}, and the
 * line {@code Description} column defaults to {@code @DESCRIPTION1@}. When a new line is created,
 * the FIC evaluates the auxiliary input (pulling the parent journal's description) and stores it
 * in the session as {@code windowId|DESCRIPTION1}; the column default {@code @DESCRIPTION1@} then
 * resolves from that session value.
 *
 * <p>NEO Headless previously skipped this step, so any column default referencing an auxiliary
 * input resolved to empty. This resolver closes that gap generically — no window-specific code:
 * it evaluates every active auxiliary input on the tab and sets {@code windowId|name} in the
 * session before column defaults are resolved.
 *
 * <p>Auxiliary input codes are evaluated the same way classic does:
 * <ul>
 *   <li>{@code @SQL=...} — executed via {@link NeoDefaultsSqlHelper#resolveSQLDefault}, with
 *       {@code @param@} tokens resolved from the parent record values first (so
 *       {@code @GL_Journal_ID@} maps to the parent id) and the session context as fallback.</li>
 *   <li>{@code @token@} — resolved from the parent values or the session context.</li>
 *   <li>anything else — treated as a literal value.</li>
 * </ul>
 */
final class NeoAuxiliaryInputResolver {

  private static final Logger log = LogManager.getLogger();

  private NeoAuxiliaryInputResolver() {
  }

  /**
   * Evaluate the tab's active auxiliary inputs and store each result in the session as
   * {@code windowId|<auxName>}. Failures on individual inputs are logged and skipped so a single
   * bad expression never blocks the defaults response.
   *
   * @param adTab        the (child) tab whose auxiliary inputs to evaluate; may be {@code null}
   * @param windowId     the AD_Window id used both for session keys and context resolution
   * @param vars         the session bridge to write resolved values into
   * @param conn         the DAL connection provider for context/SQL resolution
   * @param parentValues parent record values (uppercase column name → value); may be {@code null}
   */
  static void injectAuxiliaryInputs(Tab adTab, String windowId, VariablesSecureApp vars,
      DalConnectionProvider conn, Map<String, Object> parentValues) {
    if (adTab == null) {
      return;
    }
    List<AuxiliaryInput> auxInputs = adTab.getADAuxiliaryInputList();
    if (auxInputs == null || auxInputs.isEmpty()) {
      return;
    }
    for (AuxiliaryInput auxIn : auxInputs) {
      if (!Boolean.TRUE.equals(auxIn.isActive())) {
        continue;
      }
      try {
        String value = evaluate(auxIn, windowId, vars, conn, parentValues);
        if (value != null && !value.isEmpty()) {
          vars.setSessionValue(windowId + "|" + auxIn.getName(), value);
        }
      } catch (Exception e) {
        log.debug("Could not compute auxiliary input {}: {}", auxIn.getName(), e.getMessage());
      }
    }
  }

  private static String evaluate(AuxiliaryInput auxIn, String windowId, VariablesSecureApp vars,
      DalConnectionProvider conn, Map<String, Object> parentValues) {
    String code = auxIn.getValidationCode();
    if (code == null || code.trim().isEmpty()) {
      return null;
    }
    code = code.trim();

    if (code.startsWith("@SQL=")) {
      return NeoDefaultsSqlHelper.resolveSQLDefault(code, vars, conn, windowId, null, parentValues);
    }

    // Single context token: @Token@
    if (code.startsWith("@") && code.endsWith("@") && code.length() > 2) {
      String token = code.substring(1, code.length() - 1);
      if (parentValues != null && !token.startsWith("#")) {
        Object pv = parentValues.get(token.toUpperCase(Locale.ROOT));
        if (pv != null) {
          return String.valueOf(pv);
        }
      }
      String ctxVal = Utility.getContext(conn, vars, token, windowId);
      return (ctxVal == null || ctxVal.isEmpty()) ? null : ctxVal;
    }

    // Literal value
    return code;
  }
}
