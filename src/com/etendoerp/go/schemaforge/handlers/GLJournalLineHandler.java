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

package com.etendoerp.go.schemaforge.handlers;

import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.gl.GLJournal;
import org.openbravo.model.financialmgmt.gl.GLJournalLine;

import com.etendoerp.go.schemaforge.NeoActionRecordGuard;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoExtension;
import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.PostingAccountGuard;

/**
 * ETP-5693: the account combination of a simple G/L journal line must be a posting account of the
 * journal's ledger, and a line cannot move to another journal on update.
 *
 * <p>The line has no ledger of its own: it is the parent journal's, found from the stored line
 * (update), else the body's {@code journalEntry} (MCP), else {@code parentId} in the body (batch)
 * or the query (REST).
 */
@NeoExtension(spec = "simple-g-l-journal", entity = "gLJournalLine")
public class GLJournalLineHandler implements NeoHandler {

  private static final String PARENT_ID = "parentId";

  static final PostingAccountGuard.LedgerResolver JOURNAL_LEDGER = new PostingAccountGuard.LedgerResolver() {
    @Override
    public AcctSchema resolve(NeoContext context, BaseOBObject stored, Map<String, Object> body) {
      GLJournal journal = stored != null ? ((GLJournalLine) stored).getJournalEntry()
          : NeoActionRecordGuard.loadOwned(GLJournal.class, parentJournalId(context, body));
      return journal == null ? null : journal.getAccountingSchema();
    }

    @Override
    public String ledgerProperty() {
      return GLJournalLine.PROPERTY_JOURNALENTRY;
    }
  };

  @Override
  public NeoResponse handle(NeoContext context) {
    return PostingAccountGuard.checkWrite(context, JOURNAL_LEDGER);
  }

  private static String parentJournalId(NeoContext context, Map<String, Object> body) {
    Object fromBody = body.get(GLJournalLine.PROPERTY_JOURNALENTRY);
    if (fromBody instanceof String) {
      return (String) fromBody;
    }
    JSONObject raw = context.getRequestBody();
    if (raw != null && raw.optString(PARENT_ID, null) != null) {
      return raw.optString(PARENT_ID);
    }
    return context.getQueryParams() == null ? null : context.getQueryParams().get(PARENT_ID);
  }
}
