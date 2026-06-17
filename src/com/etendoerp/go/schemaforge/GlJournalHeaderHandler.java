/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
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

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.secureApp.VariablesSecureApp;

/**
 * Pre-hook for the Simple G/L Journal header entity.
 *
 * <p>The {@code gl_journal_multiacct_check} DB constraint requires {@code C_AcctSchema_ID} to be
 * non-null whenever {@code Multi_Gl = 'N'}. The field is hidden from the UI (system visibility)
 * so the frontend never sends it. This hook resolves the accounting schema from the current
 * session ({@code $C_AcctSchema_ID}) and injects it before the INSERT (ETP-4244).
 */
@ApplicationScoped
@Named("glJournalHeaderHandler")
public class GlJournalHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(GlJournalHeaderHandler.class);

  private static final String FIELD_ACCOUNTING_SCHEMA = "accountingSchema";
  private static final String FIELD_MULTI_GL = "multigeneralLedger";
  private static final String SESSION_KEY_ACCT_SCHEMA = "$C_AcctSchema_ID";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"POST".equalsIgnoreCase(context.getHttpMethod())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    try {
      // When Multi_Gl = 'Y' the constraint allows a null AcctSchema — don't inject.
      String multiGl = body.optString(FIELD_MULTI_GL, "N");
      if ("Y".equalsIgnoreCase(multiGl)) {
        return null;
      }
      // Only inject when the field is absent (handler never overwrites an explicit caller value).
      if (body.has(FIELD_ACCOUNTING_SCHEMA) && !body.isNull(FIELD_ACCOUNTING_SCHEMA)) {
        return null;
      }
      VariablesSecureApp vars = NeoDefaultsService.buildVariablesSecureApp(context.getObContext());
      String acctSchemaId = vars.getSessionValue(SESSION_KEY_ACCT_SCHEMA);
      if (acctSchemaId != null && !acctSchemaId.isEmpty()) {
        body.put(FIELD_ACCOUNTING_SCHEMA, acctSchemaId);
        log.debug("[GL-JOURNAL] Injected accountingSchema={} from session", acctSchemaId);
      } else {
        log.warn("[GL-JOURNAL] No $C_AcctSchema_ID in session — gl_journal_multiacct_check may fire");
      }
    } catch (Exception e) {
      log.warn("[GL-JOURNAL] Could not inject accountingSchema: {}", e.getMessage(), e);
    }
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }
}
