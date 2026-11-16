/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.module.aeat303.es.presentation.AEAT303SubmissionService;

/**
 * The append-only log of every attempt against the AEAT ({@code ETGO_Fiscal_AEAT_Attempt},
 * ETP-5716): one row per direct presentation, test-mode validation or pre-download file
 * validation that actually tried to call the AEAT — answered or not.
 *
 * <p><b>Own connection, own commit.</b> The row is inserted through a connection of its own
 * ({@link SessionHandler#getNewConnection()}) and committed at once, so it survives whatever the
 * business transaction does next: a connection failure answered with a 502, a best-effort commit
 * that fails, a rollback. It is never updated or deleted, and it has no foreign key to the
 * declaration (a draft can be deleted; its attempts stay) — model, exercise and period are on
 * the row.
 *
 * <p><b>Best-effort.</b> {@link #record} never throws: a failure is logged and the attempt goes
 * on.
 *
 * <p><b>Codes only.</b> {@code Error_Codes} / {@code Warning_Codes} hold the AEAT codes
 * ({@link FiscalAeatValidationOutcome#loggedErrorCodes()} /
 * {@link FiscalAeatValidationOutcome#loggedWarningCodes()}), never their text, which can carry
 * fiscal data.
 *
 * <p>Not exposed anywhere (no window, no NEO spec, no MCP): it is read by SQL. Reference:
 * {@code docs/aeat-attempt-log.md}.
 */
class FiscalAeatAttemptLog {

  /** {@code Environment}: a presentation sent to the AEAT production service — a real filing. */
  static final String ENV_PRODUCTION = "production";
  /**
   * {@code Environment}: a presentation routed elsewhere (the AEAT pre-production host forced by
   * {@code ETSG_ForceTestMode}, or another AEAT endpoint set by preference) — not a real filing.
   */
  static final String ENV_PREPRODUCTION = "preproduction";
  /** {@code Environment}: the validation service (ServValiDos); nothing is ever filed. */
  static final String ENV_VALIDATION = "validation";
  /**
   * {@code Environment}: the attempt went to a host that is not the AEAT's (an endpoint
   * preference pointing at a stub or a proxy) — not an AEAT verdict.
   */
  static final String ENV_OVERRIDE = "override";
  /** {@code Environment}: the endpoint could not be resolved. */
  static final String ENV_UNKNOWN = "unknown";

  /** PostgreSQL SQLState of "undefined column": the only failure the insert is retried for. */
  static final String SQLSTATE_UNDEFINED_COLUMN = "42703";

  private static final int LEN_TEXT = 200;
  private static final int LEN_DECL = 32;
  private static final int LEN_ERROR_CODES = 2000;
  private static final String CODE_SEPARATOR = ",";

  private static final String INSERT_HEAD = "INSERT INTO etgo_fiscal_aeat_attempt"
      + " (etgo_fiscal_aeat_attempt_id, ad_client_id, ad_org_id, isactive, created, createdby,"
      + " updated, updatedby, model, fiscal_year, period, fiscal_decl_uuid, attempt_path,"
      + " istestmode, environment, aeat_host, outcome, error_count, error_codes";
  private static final String INSERT_VALUES =
      ") VALUES (get_uuid(), ?, ?, 'Y', now(), ?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?";
  static final String INSERT_SQL = INSERT_HEAD + ", warning_count, warning_codes" + INSERT_VALUES
      + ", ?, ?)";
  /**
   * The row without the warning columns: the fallback for a database whose table predates them
   * (the dictionary change not applied yet), so the attempt is still recorded there.
   */
  static final String INSERT_SQL_NO_WARNINGS = INSERT_HEAD + INSERT_VALUES + ")";

  /** What identifies the declaration of an attempt; kept on the row, no foreign key. */
  record Declaration(String model, int year, String period, String declId, String orgId) {
  }

  /**
   * Stores one attempt. Never throws.
   *
   * @param declaration the declaration the attempt was about
   * @param outcome     the attempt: path, test mode, outcome and AEAT error codes
   * @param endpoint    the AEAT endpoint the attempt went to ({@link #endpointOf}); may be null
   */
  void recordAttempt(Declaration declaration, FiscalAeatValidationOutcome outcome,
      String endpoint) {
    try {
      OBContext ctx = OBContext.getOBContext();
      String userId = ctx.getUser().getId();
      String clientId = ctx.getCurrentClient().getId();
      insertWithLegacyFallback(clientId, userId, declaration, outcome, endpoint);
    } catch (Exception e) {
      AbstractFiscalHandler.log.error("Could not record the AEAT attempt of declaration "
          + declaration.declId() + " (model " + declaration.model() + ", " + declaration.year()
          + ")", e);
    }
  }

  /**
   * Inserts the row; on a table that predates the warning columns (SQLState
   * {@value #SQLSTATE_UNDEFINED_COLUMN}) inserts it once more without them. Any other failure is
   * thrown to {@link #recordAttempt}, which logs it.
   */
  private void insertWithLegacyFallback(String clientId, String userId, Declaration declaration,
      FiscalAeatValidationOutcome outcome, String endpoint) throws SQLException {
    try {
      insert(INSERT_SQL, true, clientId, userId, declaration, outcome, endpoint);
    } catch (SQLException e) {
      if (!SQLSTATE_UNDEFINED_COLUMN.equals(e.getSQLState())) {
        throw e;
      }
      AbstractFiscalHandler.log.warn("AEAT attempt log: retrying without the warning columns ("
          + e.getMessage() + ")");
      insert(INSERT_SQL_NO_WARNINGS, false, clientId, userId, declaration, outcome, endpoint);
    }
  }

  private void insert(String sql, boolean withWarnings, String clientId, String userId,
      Declaration declaration, FiscalAeatValidationOutcome outcome, String endpoint)
      throws SQLException {
    List<String> codes = outcome.loggedErrorCodes();
    try (Connection connection = openConnection();
        PreparedStatement ps = connection.prepareStatement(sql)) {
      int i = 0;
      ps.setString(++i, clientId);
      ps.setString(++i, declaration.orgId());
      ps.setString(++i, userId);
      ps.setString(++i, userId);
      ps.setString(++i, truncate(declaration.model(), LEN_TEXT));
      ps.setInt(++i, declaration.year());
      ps.setString(++i, truncate(declaration.period(), LEN_TEXT));
      ps.setString(++i, truncate(declaration.declId(), LEN_DECL));
      ps.setString(++i, outcome.path().key());
      ps.setString(++i, outcome.testMode() ? "Y" : "N");
      ps.setString(++i, environmentOf(outcome, endpoint));
      ps.setString(++i, truncate(hostOf(endpoint), LEN_TEXT));
      ps.setString(++i, outcome.outcome().key());
      ps.setInt(++i, codes.size());
      ps.setString(++i, joinCodes(codes));
      if (withWarnings) {
        List<String> warningCodes = outcome.loggedWarningCodes();
        ps.setInt(++i, warningCodes.size());
        ps.setString(++i, joinCodes(warningCodes));
      }
      try {
        ps.executeUpdate();
        if (!connection.getAutoCommit()) {
          connection.commit();
        }
      } catch (SQLException e) {
        rollbackQuietly(connection);
        throw e;
      }
    }
  }

  /** A failed insert must not go back to the pool inside an aborted transaction. */
  private static void rollbackQuietly(Connection connection) {
    try {
      if (!connection.getAutoCommit()) {
        connection.rollback();
      }
    } catch (SQLException e) {
      AbstractFiscalHandler.log.warn("AEAT attempt log: rollback failed: " + e.getMessage());
    }
  }

  /** A connection of its own, independent of the DAL session. Overridable in tests. */
  Connection openConnection() throws SQLException {
    return SessionHandler.getInstance().getNewConnection();
  }

  /**
   * The endpoint an attempt goes to: the validation service in test mode, else the production
   * one — which {@code ETSG_ForceTestMode} silently reroutes to the AEAT pre-production host.
   * Null when it cannot be resolved; never throws.
   */
  static String endpointOf(AEAT303SubmissionService service, boolean testMode) {
    try {
      return testMode ? service.getValidationEndpoint() : service.getProductionEndpoint();
    } catch (Exception e) {
      AbstractFiscalHandler.log.warn("Could not resolve the AEAT endpoint of an attempt: "
          + e.getMessage());
      return null;
    }
  }

  /** See the {@code ENV_*} constants. */
  static String environmentOf(FiscalAeatValidationOutcome outcome, String endpoint) {
    if (StringUtils.isBlank(endpoint)) {
      return outcome.testMode() ? ENV_VALIDATION : ENV_UNKNOWN;
    }
    String host = hostOf(endpoint);
    if (host == null) {
      return ENV_UNKNOWN; // a malformed endpoint: nothing can be said about where it went
    }
    if (!isAeatHost(host)) {
      return ENV_OVERRIDE;
    }
    if (outcome.testMode()) {
      return ENV_VALIDATION;
    }
    return sameHostAndPath(AEAT303SubmissionService.DEFAULT_PRODUCTION_ENDPOINT, endpoint)
        ? ENV_PRODUCTION : ENV_PREPRODUCTION;
  }

  /** Same host (any case) and path (a trailing slash aside); scheme, port and query ignored. */
  static boolean sameHostAndPath(String a, String b) {
    try {
      URI ua = URI.create(a.trim());
      URI ub = URI.create(b.trim());
      return ua.getHost() != null && ua.getHost().equalsIgnoreCase(ub.getHost())
          && StringUtils.removeEnd(StringUtils.defaultString(ua.getPath()), "/")
              .equals(StringUtils.removeEnd(StringUtils.defaultString(ub.getPath()), "/"));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** True for a host of the AEAT ({@code *.aeat.es}, {@code *.agenciatributaria.gob.es}). */
  static boolean isAeatHost(String host) {
    String h = StringUtils.lowerCase(StringUtils.trimToEmpty(host));
    return h.endsWith(".aeat.es") || h.endsWith(".agenciatributaria.gob.es");
  }

  /** The host of {@code endpoint}, or null when there is none to read. */
  static String hostOf(String endpoint) {
    if (StringUtils.isBlank(endpoint)) {
      return null;
    }
    try {
      return URI.create(endpoint.trim()).getHost();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** The codes, comma-separated, cut at a whole code when they exceed the column. */
  static String joinCodes(List<String> codes) {
    StringBuilder sb = new StringBuilder();
    for (String code : codes) {
      int extra = code.length() + (sb.length() > 0 ? CODE_SEPARATOR.length() : 0);
      if (sb.length() + extra > LEN_ERROR_CODES) {
        break;
      }
      if (sb.length() > 0) {
        sb.append(CODE_SEPARATOR);
      }
      sb.append(code);
    }
    return sb.length() > 0 ? sb.toString() : null;
  }

  private static String truncate(String value, int max) {
    String v = StringUtils.trimToNull(value);
    return v == null || v.length() <= max ? v : v.substring(0, max);
  }
}
