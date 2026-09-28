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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatement;
import org.openbravo.model.financialmgmt.payment.FIN_BankStatementLine;
import org.openbravo.model.financialmgmt.payment.FIN_FinancialAccount;

/**
 * ETP-5471 — keeps the generic {@code financial-account} bank-statement entities from writing
 * around the PSD2 rule {@link BankStatementsHandler} enforces.
 *
 * <p><b>Why a second handler.</b> {@code importedBankStatements} (FIN_BankStatement) and
 * {@code bankStatementLines} (FIN_BankStatementLine) had no Java qualifier, so a REST
 * {@code POST/DELETE /sws/neo/financial-account/<entity>}, an MCP {@code neo_create}/{@code neo_delete}
 * and a {@code neo_batch} step went straight to the default DAL persist. That let a caller create a
 * statement on a bank-connected account — whose statements arrive from the bank sync — and delete one
 * despite the refusal {@code ?action=delete} already had. The SPA never writes through these
 * entities (it reads them for the grids and writes through {@code bank-statements}), so this guard
 * only ever answers API clients.</p>
 *
 * <p><b>What it refuses</b>, only when the account is connected
 * ({@link BankStatementsSupport#isBankConnected}):</p>
 * <ul>
 *   <li>{@code importedBankStatements} POST — creating a statement;</li>
 *   <li>{@code bankStatementLines} POST — adding a line to a statement of that account;</li>
 *   <li>{@code importedBankStatements} DELETE — with the same text as {@code ?action=delete}.</li>
 * </ul>
 * <p>Everything else — reads, edits, other endpoint types, a body with no resolvable account — is
 * left to the default CRUD ({@code null}), which keeps answering exactly as before. The bank sync is
 * never affected: it creates statements through OBDal and does not reach any NEO handler.</p>
 *
 * <p>{@code @Named} only, no normal scope: see the NeoHandler section of the project CLAUDE.md.</p>
 */
@Named("bankStatementWriteGuard")
public class BankStatementWriteGuardHandler implements NeoHandler {

  static final String ENTITY_STATEMENTS = "importedBankStatements";
  static final String ENTITY_LINES = "bankStatementLines";
  private static final String METHOD_POST = "POST";
  private static final String METHOD_DELETE = "DELETE";
  /** Generic parent link NEO accepts on a child create before mapping it to the FK property. */
  private static final String PARAM_PARENT_ID = "parentId";
  private static final String KEY_ID = "id";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!NeoEndpointTypes.isCrud(context)) {
      return null;
    }
    String entity = context.getEntityName();
    String method = context.getHttpMethod();
    OBContext.setAdminMode(true);
    try {
      if (ENTITY_STATEMENTS.equals(entity) && METHOD_POST.equals(method)) {
        return refuseIfConnected(accountFromBody(context.getRequestBody()),
            BankStatementsHandler.MSG_STATEMENT_BANK_CONNECTED_NOT_CREATABLE);
      }
      if (ENTITY_LINES.equals(entity) && METHOD_POST.equals(method)) {
        return refuseIfConnected(accountOfParentStatement(context.getRequestBody()),
            BankStatementsHandler.MSG_STATEMENT_BANK_CONNECTED_NOT_CREATABLE);
      }
      if (ENTITY_STATEMENTS.equals(entity) && METHOD_DELETE.equals(method)) {
        FIN_BankStatement statement =
            TenantOwnership.loadOwned(FIN_BankStatement.class, context.getRecordId());
        return refuseIfConnected(statement != null ? statement.getAccount() : null,
            BankStatementsHandler.MSG_STATEMENT_BANK_CONNECTED);
      }
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static NeoResponse refuseIfConnected(FIN_FinancialAccount account, String message) {
    return BankStatementsSupport.isBankConnected(account) ? NeoResponse.error(409, message) : null;
  }

  private static FIN_FinancialAccount accountFromBody(JSONObject body) {
    String accountId = referencedId(body, FIN_BankStatement.PROPERTY_ACCOUNT);
    return TenantOwnership.loadOwned(FIN_FinancialAccount.class, accountId);
  }

  private static FIN_FinancialAccount accountOfParentStatement(JSONObject body) {
    String statementId = referencedId(body, FIN_BankStatementLine.PROPERTY_BANKSTATEMENT);
    FIN_BankStatement statement = TenantOwnership.loadOwned(FIN_BankStatement.class, statementId);
    return statement != null ? statement.getAccount() : null;
  }

  /**
   * The id of the parent record a create body points at. REST may still carry the generic
   * {@code parentId} (mapped to the FK property only later, by the default CRUD), while MCP has
   * already resolved it into the DAL property; the property wins when both are present. A property
   * sent as a {@code {"id": ...}} object is accepted too.
   *
   * @return the id, or {@code null} when the body names no parent
   */
  static String referencedId(JSONObject body, String property) {
    if (body == null) {
      return null;
    }
    JSONObject asObject = body.optJSONObject(property);
    String id = asObject != null ? asObject.optString(KEY_ID, null) : body.optString(property, null);
    if (StringUtils.isBlank(id)) {
      id = body.optString(PARAM_PARENT_ID, null);
    }
    return StringUtils.isBlank(id) ? null : id;
  }
}
