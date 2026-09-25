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

import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * NeoHandler of the {@code importedBankStatements} and {@code bankStatementLines} entities of the
 * {@code financial-account} W spec (ETP-5447), registered via
 * {@code ETGO_SF_ENTITY.Java_Qualifier = "bankStatementEntityHandler"}.
 *
 * <p><b>Named actions.</b> ACTION requests on {@code importedBankStatements} (update, process,
 * reactivate, delete — all POST) are answered by {@link BankStatementActionsSupport}, which reuses
 * {@link BankStatementsHandler} — the engine the SPA calls at {@code /sws/neo/bank-statements}.</p>
 *
 * <p><b>Generic CRUD writes are refused (405).</b> A generic create, update or delete on either
 * entity goes straight to the DAL and skips every rule the engine enforces. A live probe on
 * 2026-09-25 showed the damage: a generic create stamped TODAY on both header dates (the required
 * dates of ETP-5447 never reach it), a line's date could not be set, {@code referenceNo} became
 * mandatory, and deleting a statement with lines failed with a Hibernate cascade error because
 * {@code BankStatementLineAggregateHandler} saves the parent while the cascade removes it. The
 * refusal names the action to use instead, so an agent can correct itself. The SPA never uses
 * these generic paths — all its statement traffic goes to {@code /sws/neo/bank-statements}.</p>
 *
 * <p>Reads (list, get, defaults, selectors) pass through to the generic service untouched: they
 * are how an agent reads statements and their lines, so no named read action exists.</p>
 *
 * <p>Note: carrying a {@code Java_Qualifier} exempts these entities from {@code NeoFieldFilter}'s
 * IMP-28 rejection of read-only fields on create. It is irrelevant here, because every generic
 * create is refused in the pre-hook before the filter could matter.</p>
 */
@Named("bankStatementEntityHandler")
public class BankStatementEntityHandler implements NeoHandler {

  static final String ENTITY_STATEMENTS = BankStatementActionsSupport.ENTITY_STATEMENTS;
  static final String ENTITY_LINES = "bankStatementLines";

  private static final String METHOD_POST = "POST";
  private static final String METHOD_DELETE = "DELETE";
  private static final Set<String> WRITE_METHODS = Set.of(METHOD_POST, "PUT", "PATCH",
      METHOD_DELETE);

  static final String MSG_STATEMENT_CREATE_DISABLED =
      "Bank statements are created with the account action 'createStatement' (neo_action on"
          + " financial-account/account, id = the financial account), or from a file with"
          + " 'importStatement'. Generic create is disabled because it bypasses the required"
          + " dates, the BSF document type and line validation.";
  static final String MSG_STATEMENT_UPDATE_DISABLED =
      "Edit a bank statement with neo_action 'update' on financial-account/importedBankStatements"
          + " (id = the statement; 'reactivate' it first when it is processed), or change its"
          + " state with 'process' / 'reactivate'. Generic update is disabled because it bypasses"
          + " the required dates and line validation.";
  static final String MSG_STATEMENT_DELETE_DISABLED =
      "Delete a bank statement with neo_action 'delete' on financial-account/importedBankStatements"
          + " (id = the statement). Generic delete is disabled because it bypasses the draft,"
          + " matched-line and bank-connection checks.";
  static final String MSG_LINES_WRITE_DISABLED =
      "Edit a statement's lines with neo_action 'update' on financial-account/importedBankStatements"
          + " (id = the statement; it replaces the unmatched lines). Generic writes on"
          + " bankStatementLines are disabled because they bypass line validation.";

  @Inject
  private BankStatementActionsSupport bankStatementActions;

  /** Package-private seam so unit tests can supply a mocked {@link BankStatementActionsSupport}. */
  void setBankStatementActions(BankStatementActionsSupport bankStatementActions) {
    this.bankStatementActions = bankStatementActions;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoEndpointType endpointType = context.getEndpointType();
    String entityName = context.getEntityName();
    if (NeoEndpointType.ACTION.equals(endpointType)) {
      return ENTITY_STATEMENTS.equals(entityName) && bankStatementActions != null
          ? bankStatementActions.handle(context)
          : null;
    }
    boolean crud = endpointType == null || NeoEndpointType.CRUD.equals(endpointType);
    String rawMethod = context.getHttpMethod();
    // Normalized (case-insensitive, like BankStatementActionsSupport) and null-checked first:
    // Set.of(...).contains(null) throws.
    String method = rawMethod == null ? null : rawMethod.toUpperCase(Locale.ROOT);
    if (crud && method != null && WRITE_METHODS.contains(method)) {
      return refuseGenericWrite(entityName, method);
    }
    // Reads, defaults and selectors flow through to the generic service.
    return null;
  }

  /**
   * The 405 for a generic write, naming the action that does the job. 405 is what the MCP maps to
   * {@code method_not_allowed}, a 4xx the agent can act on by switching tools.
   *
   * @return the refusal, or {@code null} for an entity this handler does not guard
   */
  static NeoResponse refuseGenericWrite(String entityName, String method) {
    if (ENTITY_LINES.equals(entityName)) {
      return NeoResponse.error(405, MSG_LINES_WRITE_DISABLED);
    }
    if (!ENTITY_STATEMENTS.equals(entityName)) {
      return null;
    }
    if (METHOD_POST.equalsIgnoreCase(method)) {
      return NeoResponse.error(405, MSG_STATEMENT_CREATE_DISABLED);
    }
    if (METHOD_DELETE.equalsIgnoreCase(method)) {
      return NeoResponse.error(405, MSG_STATEMENT_DELETE_DISABLED);
    }
    return NeoResponse.error(405, MSG_STATEMENT_UPDATE_DISABLED);
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    return null;
  }

  @Override
  public List<NeoActionContract> declaredActions(String specName, String entityName) {
    if (!ENTITY_STATEMENTS.equals(entityName) || bankStatementActions == null) {
      return List.of();
    }
    return bankStatementActions.declaredActions(ENTITY_STATEMENTS);
  }

  /** {@code importedBankStatements} answers ACTION requests (ETP-5447). */
  @Override
  public boolean servesActions() {
    return true;
  }
}
