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

package com.etendoerp.go.schemaforge;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * SQL/DB helper utilities extracted from {@link NeoDefaultsService} to keep that class within
 * its method-count budget.
 *
 * <p>Groups the low-level SQL concerns used during NEO default resolution:
 * <ul>
 *   <li>parsing and executing {@code @SQL=...} default expressions
 *   <li>reading DB-level column defaults from {@code information_schema}
 *   <li>resolving the first real organization for a client
 * </ul>
 * All methods are pure utilities with no dependency on {@code NeoDefaultsService} internal state.
 */
final class NeoDefaultsSqlHelper {

  private static final Logger log = LogManager.getLogger(NeoDefaultsSqlHelper.class);

  private NeoDefaultsSqlHelper() {
  }

  /**
   * Resolve a @SQL= default expression.
   * Adapted from UIDefinition.getDefaultValueFromSQLExpression — parses the SQL,
   * resolves @parameter@ tokens via Utility.getContext, and executes the query.
   */
  static String resolveSQLDefault(String defaultExpr, VariablesSecureApp vars,
      DalConnectionProvider conn, String windowId, Column adColumn) {
    return resolveSQLDefault(defaultExpr, vars, conn, windowId, adColumn, null);
  }

  /**
   * Resolve a @SQL= default expression, preferring parent record values over session context
   * for non-session parameters. This ensures that columns like @M_Warehouse_ID@ and @AD_Client_ID@
   * resolve to the parent record's values (e.g. the inventory's warehouse and client) rather than
   * the session user's warehouse/client, which may differ when the user belongs to a different org.
   *
   * Session parameters (prefixed with #, e.g. @#Date@) always use session context.
   */
  static String resolveSQLDefault(String defaultExpr, VariablesSecureApp vars,
      DalConnectionProvider conn, String windowId, Column adColumn,
      Map<String, Object> parentValues) {
    return resolveSQLDefaultWithOutcome(defaultExpr, vars, conn, windowId, adColumn, parentValues,
        parentValues != null && !parentValues.isEmpty()).getValue();
  }

  /**
   * Same resolution as {@link #resolveSQLDefault}, but also reports WHY a null came back
   * (ETP-4918). {@code resolveSQLDefault} above stays byte-for-byte behaviorally identical —
   * it just discards the diagnostic half of this outcome — so every existing caller is
   * unaffected. The one caller that needs the diagnostic (pass 1 of
   * {@code NeoDefaultsService#resolveDefaults}) uses this method directly to turn a silent
   * {@code null} into an actionable {@code metadata.notes} entry instead of a field that
   * simply vanishes from the response.
   *
   * @param parentIdProvided whether the caller's {@code parentId} request parameter was
   *                         actually present, independent of whether {@code parentValues} was
   *                         threaded through for this particular call. Pass 1 of
   *                         {@code resolveDefaults} never threads {@code parentValues} into
   *                         this call at all (a separate, pre-existing gap — not something
   *                         this change fixes), so basing the "you forgot parentId" diagnosis
   *                         on {@code parentValues} emptiness would misreport a query that
   *                         merely returned zero rows while parentId was in fact supplied.
   */
  static SqlDefaultOutcome resolveSQLDefaultWithOutcome(String defaultExpr, VariablesSecureApp vars,
      DalConnectionProvider conn, String windowId, Column adColumn,
      Map<String, Object> parentValues, boolean parentIdProvided) {
    try {
      ArrayList<String> params = new ArrayList<>();
      String sql = parseSQLExpression(defaultExpr, params);
      String missingParentToken = null;

      try (PreparedStatement ps = OBDal.getInstance().getConnection(false).prepareStatement(sql)) {
        int paramIndex = 1;
        for (String parameter : params) {
          String value = null;
          // Non-session params: check parent record values first (e.g. @M_Warehouse_ID@, @AD_Client_ID@)
          if (parentValues != null && !parentValues.isEmpty() && !parameter.startsWith("#")) {
            Object pv = parentValues.get(parameter.toUpperCase());
            if (pv != null) {
              value = String.valueOf(pv);
              log.debug("[resolveSQLDefault] param @{}@ from parentValues: {}", parameter, value);
            }
          }
          if (value == null || value.isEmpty()) {
            value = Utility.getContext(conn, vars, parameter, windowId);
          }
          // Token needed a parent value, the request never supplied parentId, and session
          // context could not supply one either: this is the "forgot parentId" case, distinct
          // from "the query legitimately matched nothing" below.
          if (missingParentToken == null && !parameter.startsWith("#")
              && (value == null || value.isEmpty()) && !parentIdProvided) {
            missingParentToken = parameter;
          }
          ps.setObject(paramIndex++, value);
        }

        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return SqlDefaultOutcome.resolved(rs.getString(1));
          }
        }
      }
      return missingParentToken != null
          ? SqlDefaultOutcome.missingParentToken(missingParentToken)
          : SqlDefaultOutcome.zeroRows();
    } catch (Exception e) {
      // adColumn is null when resolving a tab auxiliary input's @SQL= code (which is
      // column-independent), so guard the dereference before logging.
      log.debug("Could not resolve SQL default for column {}: {}",
          adColumn != null ? adColumn.getDBColumnName() : "<auxiliary-input>", e.getMessage());
      return SqlDefaultOutcome.unresolved();
    }
  }

  /**
   * Diagnostic outcome of a {@code @SQL=} default resolution attempt (ETP-4918). Carries just
   * enough context for a caller to explain a {@code null} value — which of the two known-cause
   * cases applies, if either — without {@link #resolveSQLDefaultWithOutcome} needing to know
   * anything about the wire response format its caller builds.
   */
  static final class SqlDefaultOutcome {
    private final String value;
    private final String missingParentToken;
    private final boolean zeroRows;

    private SqlDefaultOutcome(String value, String missingParentToken, boolean zeroRows) {
      this.value = value;
      this.missingParentToken = missingParentToken;
      this.zeroRows = zeroRows;
    }

    private static SqlDefaultOutcome resolved(String value) {
      return new SqlDefaultOutcome(value, null, false);
    }

    private static SqlDefaultOutcome missingParentToken(String token) {
      return new SqlDefaultOutcome(null, token, false);
    }

    private static SqlDefaultOutcome zeroRows() {
      return new SqlDefaultOutcome(null, null, true);
    }

    private static SqlDefaultOutcome unresolved() {
      return new SqlDefaultOutcome(null, null, false);
    }

    String getValue() {
      return value;
    }

    /** Non-null only when a non-session @token@ resolved to nothing AND the request's
     *  {@code parentId} was never supplied — i.e. the caller almost certainly forgot it. */
    String getMissingParentToken() {
      return missingParentToken;
    }

    /** True when the query executed cleanly but matched zero rows, and no parent token was
     *  the likely cause — the tenant simply has no such record for the current context. */
    boolean isZeroRows() {
      return zeroRows;
    }
  }

  /**
   * Parse a @SQL= expression, extracting parameter names and replacing @param@ tokens with ?.
   * Simplified version of UIDefinition.parseSQL adapted for NEO context.
   *
   * Input: "@SQL=SELECT name FROM ad_org WHERE ad_org_id = '@#AD_Org_ID@'"
   * Output SQL: "SELECT name FROM ad_org WHERE ad_org_id = ?"
   * Output params: ["#AD_Org_ID"]
   */
  static String parseSQLExpression(String expression, ArrayList<String> paramNames) {
    if (expression == null || expression.trim().isEmpty()) {
      return "";
    }

    String value = expression;

    // Remove @SQL= prefix
    int sqlStart = value.indexOf("@SQL=");
    if (sqlStart >= 0) {
      value = value.substring(sqlStart + 5);
    }

    StringBuilder sqlOut = new StringBuilder();
    int i = value.indexOf("@");

    while (i != -1) {
      // Append everything before the @
      String before = value.substring(0, i);
      // Strip trailing quote if parameter was quoted in SQL (e.g., '@param@')
      if (before.endsWith("'")) {
        before = before.substring(0, before.length() - 1);
      }
      sqlOut.append(before);

      value = value.substring(i + 1);
      int j = value.indexOf("@");
      if (j < 0) {
        // No closing @ — append remaining and stop
        sqlOut.append(value);
        break;
      }

      // Extract token name
      String token = value.substring(0, j);
      paramNames.add(token);
      sqlOut.append("?");

      value = value.substring(j + 1);
      // Strip leading quote after closing @ (e.g., '@param@')
      if (value.startsWith("'")) {
        value = value.substring(1);
      }
      i = value.indexOf("@");
    }

    sqlOut.append(value);
    return sqlOut.toString();
  }

  /**
   * Read the DB-level column DEFAULT from {@code information_schema.columns}.
   * Used as a last-resort fallback when {@code AD_Column.DefaultValue} is null/empty and
   * no preference or doctype default can be resolved.
   */
  static String resolveDbColumnDefault(String tableName, String columnName) {
    try {
      String sql = "SELECT column_default FROM information_schema.columns "
          + "WHERE LOWER(table_name) = LOWER(?) AND LOWER(column_name) = LOWER(?)";
      try (PreparedStatement ps =
          OBDal.getInstance().getConnection(false).prepareStatement(sql)) {
        ps.setString(1, tableName);
        ps.setString(2, columnName);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            String colDefault = rs.getString(1);
            if (colDefault == null || colDefault.isEmpty()) {
              return null;
            }
            if (colDefault.startsWith("'")) {
              int endQuote = colDefault.indexOf("'", 1);
              if (endQuote > 0) {
                return colDefault.substring(1, endQuote);
              }
            }
            String stripped = colDefault.split("::")[0].trim();
            if (!stripped.isEmpty()) {
              return stripped;
            }
          }
        }
      }
    } catch (SQLException e) {
      log.debug("Could not read DB-level column default for {}.{}: {}",
          tableName, columnName, e.getMessage());
    }
    return null;
  }

  /**
   * Returns the ID of the first active non-system organization for the given client.
   * Used when the session context org is "0" (the "*" all-orgs pseudo-org) so that
   * mandatory FK defaults like AD_Org_ID resolve to a real org rather than "0",
   * which OBDal rejects for business documents.
   *
   * A role with access to "*" has implicit access to all orgs, so using any active
   * org of the client is safe.
   *
   * @param clientId the AD_Client_ID of the current session
   * @return the first org ID ordered by name, or null if none found
   */
  static String resolveFirstOrgForClient(String clientId) {
    try {
      String sql = "SELECT AD_Org_ID FROM AD_Org"
          + " WHERE AD_Client_ID = ? AND IsActive = 'Y' AND AD_Org_ID != '0'"
          + " ORDER BY Name LIMIT 1";
      try (PreparedStatement ps = OBDal.getInstance().getConnection(false).prepareStatement(sql)) {
        ps.setString(1, clientId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return rs.getString(1);
          }
        }
      }
    } catch (Exception e) {
      log.debug("Could not resolve first org for client {}: {}", clientId, e.getMessage());
    }
    return null;
  }
}
