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

import java.util.Locale;
import java.util.Set;

import javax.inject.Named;

/**
 * NeoHandler of the {@code importedBankStatements} and {@code bankStatementLines} entities of the
 * {@code financial-account} W spec (ETP-5447), registered via
 * {@code ETGO_SF_ENTITY.Java_Qualifier = "bankStatementEntityHandler"}.
 *
 * <p><b>Generic CRUD writes are refused (405).</b> A generic create, update or delete on either
 * entity goes straight to the DAL and skips every rule the bank-statements engine
 * ({@link BankStatementsHandler}, served at {@code /sws/neo/bank-statements}) enforces. A live probe
 * on 2026-09-25 showed the damage: a generic create stamped TODAY on both header dates (the
 * required dates of ETP-5447 never reach it), a line's date could not be set, {@code referenceNo}
 * became mandatory, and deleting a statement with lines failed with a Hibernate cascade error
 * because {@code BankStatementLineAggregateHandler} saves the parent while the cascade removes it.
 * The refusal names the {@code bank-statements} action that does the job
 * ({@link BankStatementAgentActions}: createStatement / importStatement, updateStatement,
 * deleteStatement), so an agent can correct itself. The SPA never uses these generic paths — all
 * its statement traffic goes to {@code /sws/neo/bank-statements}.</p>
 *
 * <p>Everything else passes through to the generic service untouched: reads (list, get), defaults,
 * selectors and any non-CRUD endpoint. The HTTP method is compared case-insensitively; a request
 * without a method passes through.</p>
 *
 * <p>Note: carrying a {@code Java_Qualifier} exempts these entities from {@code NeoFieldFilter}'s
 * IMP-28 rejection of read-only fields on create. It is irrelevant here, because every generic
 * create is refused in the pre-hook before the filter could matter.</p>
 */
@Named("bankStatementEntityHandler")
public class BankStatementEntityHandler implements NeoHandler {

  static final String ENTITY_STATEMENTS = "importedBankStatements";
  static final String ENTITY_LINES = "bankStatementLines";

  private static final String METHOD_POST = "POST";
  private static final String METHOD_DELETE = "DELETE";
  private static final Set<String> WRITE_METHODS = Set.of(METHOD_POST, "PUT", "PATCH",
      METHOD_DELETE);

  /**
   * How a caller reaches the supported write: {@code neo_action} on the {@code bank-statements}
   * report spec, whose one entity is also named {@code bank-statements}
   * ({@link BankStatementAgentActions}). Each message below names the concrete action.
   */
  private static final String VIA_NEO_ACTION =
      " via neo_action {spec:\"bank-statements\", entity:\"bank-statements\"";
  private static final String ID_ACCOUNT = ", id: <financial account id>}";
  private static final String ID_STATEMENT = ", id: <bank statement id>}";
  private static final String PARAMETERS_HINT =
      " (neo_schema({spec:\"bank-statements\", view:\"actions\"}) lists its parameters).";

  static final String MSG_STATEMENT_CREATE_DISABLED =
      "Bank statements are created with action createStatement (or importStatement from a file)"
          + VIA_NEO_ACTION + ID_ACCOUNT + PARAMETERS_HINT + " Generic create on this entity is"
          + " disabled because it bypasses the required dates, the BSF document type and line"
          + " validation.";
  static final String MSG_STATEMENT_UPDATE_DISABLED =
      "Bank statements are changed with action updateStatement" + VIA_NEO_ACTION + ID_STATEMENT
          + PARAMETERS_HINT + " Generic update on this entity is disabled because it bypasses"
          + " the required dates and line validation.";
  static final String MSG_STATEMENT_DELETE_DISABLED =
      "Bank statements are deleted with action deleteStatement" + VIA_NEO_ACTION + ID_STATEMENT
          + PARAMETERS_HINT + " Generic delete on this entity is disabled because it bypasses"
          + " the draft, matched-line and bank-connection checks.";
  static final String MSG_LINES_WRITE_DISABLED =
      "Bank statement lines are changed with action updateStatement on their statement (its"
          + " lines parameter replaces the unmatched lines)" + VIA_NEO_ACTION + ID_STATEMENT
          + PARAMETERS_HINT + " Generic writes on this entity are disabled because they bypass"
          + " line validation.";

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoEndpointType endpointType = context.getEndpointType();
    boolean crud = endpointType == null || NeoEndpointType.CRUD.equals(endpointType);
    String rawMethod = context.getHttpMethod();
    // Normalized (case-insensitive) and null-checked first: Set.of(...).contains(null) throws.
    String method = rawMethod == null ? null : rawMethod.toUpperCase(Locale.ROOT);
    if (crud && method != null && WRITE_METHODS.contains(method)) {
      return refuseGenericWrite(context.getEntityName(), method);
    }
    // Reads, defaults, selectors and non-CRUD endpoints flow through to the generic service.
    return null;
  }

  /**
   * The 405 for a generic write. 405 is what the MCP maps to {@code method_not_allowed}, a 4xx the
   * agent can act on by switching tools.
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
}
