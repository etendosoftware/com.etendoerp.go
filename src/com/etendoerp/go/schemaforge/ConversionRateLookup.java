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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.security.OrganizationStructureProvider;
import org.openbravo.dal.service.OBDal;

/**
 * ETP-6365: the one rule Etendo GO applies to pick a general {@code C_Conversion_Rate} for a
 * document day. Shared code: it names no window and no entity, only the conversion-rate table.
 *
 * <p><b>The rule.</b> For a document dated on day {@code D}, among the ACTIVE rows of the pair
 * (from → to) whose range touches that day — {@code ValidFrom < D+1 00:00} and
 * {@code ValidTo >= D 00:00} (an empty end is open) — the client's own row wins over the System
 * ({@code 0}) one, then the latest {@code ValidFrom}. That is "the last range of the day": since
 * ETP-6365 a manual range starts at a minute (a rate entered at 09:00 closes the previous one at
 * 08:59), so two ranges can share a day and the one in force at the end of it is the one a
 * document of that day takes.</p>
 *
 * <p><b>Why it returns what the old lookups returned on existing data.</b> Every row written before
 * ETP-6365 starts and ends at 00:00 (the columns were Date). For such a row,
 * {@code ValidFrom < D+1 00:00} is exactly {@code ValidFrom <= D 00:00}, and
 * {@code ValidTo >= D 00:00} is unchanged — the old predicate. Core's overlap check (20504) keeps
 * one row per client, pair and day, so "latest {@code ValidFrom}" picks among at most the client
 * row and the System row, the same order the old queries used (client first). Only data with a
 * time of day can make the new rule differ, and that is the point of it.</p>
 *
 * <p><b>Two organization scopes, as the callers had them.</b> {@link #multiplyRate} admits a fixed
 * set of organizations (the NEO document lookups admit {@code 0} and the document organization,
 * all at once); {@link #orgTreeMultiplyRate} walks the organization tree from the document
 * organization up to {@code 0} and stops at the first level that has a row, exactly as core's
 * {@code FinancialUtils.getConversionRate} does, for the callers that used that method.</p>
 */
public final class ConversionRateLookup {

  private static final Logger log = LogManager.getLogger(ConversionRateLookup.class);

  /** What core's trigger stores for an empty end, used for a row that still has none. */
  public static final String OPEN_END_SQL = "TO_DATE('31-12-9999', 'DD-MM-YYYY')";

  /**
   * The day predicate over alias {@code cr}; bind it with {@link #bindDay}: the start of the next
   * day, then the start of the day.
   */
  public static final String DAY_PREDICATE = " AND cr.validfrom < ?"
      + " AND COALESCE(cr.validto, " + OPEN_END_SQL + ") >= ?";

  /** Sort key over alias {@code cr}: 0 for a client row, 1 for a System row. */
  public static final String SYSTEM_LAST = "CASE WHEN cr.ad_client_id = '0' THEN 1 ELSE 0 END";

  private static final String SYSTEM_ORG = "0";

  private static final String RATE_SQL = "SELECT cr.multiplyrate FROM c_conversion_rate cr"
      + " WHERE cr.c_currency_id = ? AND cr.c_currency_id_to = ? AND cr.isactive = 'Y'"
      + " AND cr.ad_client_id IN ('0', ?) AND cr.ad_org_id = ANY(?)" + DAY_PREDICATE
      + " ORDER BY " + SYSTEM_LAST + ", cr.validfrom DESC LIMIT 1";

  private ConversionRateLookup() {
  }

  /**
   * Binds {@link #DAY_PREDICATE} for {@code day}.
   *
   * @param ps    the statement
   * @param index the index of the first of the two placeholders
   * @param day   the document day
   * @return the index after the two placeholders
   * @throws SQLException if binding fails
   */
  public static int bindDay(PreparedStatement ps, int index, LocalDate day) throws SQLException {
    ps.setTimestamp(index, Timestamp.valueOf(day.plusDays(1).atStartOfDay()));
    ps.setTimestamp(index + 1, Timestamp.valueOf(day.atStartOfDay()));
    return index + 2;
  }

  /**
   * The multiply rate of the direct row {@code fromId → toId} for {@code day}, the client's rows and
   * the System's admitted, any of {@code orgIds} admitted alike.
   *
   * @param conn     the connection
   * @param fromId   the source {@code C_Currency_ID}
   * @param toId     the target {@code C_Currency_ID}
   * @param clientId the client whose rows are admitted next to System's
   * @param orgIds   the organizations whose rows are admitted
   * @param day      the document day
   * @return the multiply rate, or {@code null} when no row applies
   * @throws SQLException if the query fails
   */
  public static BigDecimal multiplyRate(Connection conn, String fromId, String toId,
      String clientId, List<String> orgIds, LocalDate day) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(RATE_SQL)) {
      ps.setString(1, fromId);
      ps.setString(2, toId);
      ps.setString(3, clientId);
      ps.setArray(4, conn.createArrayOf("varchar", orgIds.toArray(new String[0])));
      bindDay(ps, 5, day);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getBigDecimal(1) : null;
      }
    }
  }

  /**
   * The multiply rate of the direct row {@code fromId → toId} for {@code day}, searched on
   * {@code orgId} and then on each parent organization up to {@code 0}, the first level with a row
   * winning — the organization walk of core's {@code FinancialUtils.getConversionRate}.
   *
   * @param fromId   the source {@code C_Currency_ID}
   * @param toId     the target {@code C_Currency_ID}
   * @param orgId    the document organization, where the walk starts
   * @param clientId the client whose rows are admitted next to System's
   * @param day      the document day
   * @return the multiply rate, or {@code null} when no level has a row
   * @throws SQLException if the query fails
   */
  public static BigDecimal orgTreeMultiplyRate(String fromId, String toId, String orgId,
      String clientId, LocalDate day) throws SQLException {
    Connection conn = OBDal.getInstance().getConnection();
    for (String level : orgPath(orgId, clientId)) {
      BigDecimal rate = multiplyRate(conn, fromId, toId, clientId,
          Collections.singletonList(level), day);
      if (rate != null) {
        return rate;
      }
    }
    return null;
  }

  /**
   * {@link #orgTreeMultiplyRate} for a document {@link Date} (only its day is read), answering
   * {@code null} — after logging it — on a failure, as core's {@code FinancialUtils
   * .getConversionRate} did for the callers that used it. A zero rate is answered as is.
   *
   * @param date     the document date; only its day is read
   * @param fromId   the source {@code C_Currency_ID}
   * @param toId     the target {@code C_Currency_ID}
   * @param orgId    the document organization, where the walk starts
   * @param clientId the client whose rows are admitted next to System's
   * @return the multiply rate, or {@code null} when none applies or the lookup failed
   */
  public static BigDecimal orgTreeMultiplyRateOrNull(Date date, String fromId, String toId,
      String orgId, String clientId) {
    if (date == null || fromId == null || toId == null || orgId == null || clientId == null) {
      return null;
    }
    try {
      LocalDate day = new Timestamp(date.getTime()).toLocalDateTime().toLocalDate();
      return orgTreeMultiplyRate(fromId, toId, orgId, clientId, day);
    } catch (SQLException | RuntimeException e) {
      log.error("Exception calculating conversion rate {} -> {}", fromId, toId, e);
      return null;
    }
  }

  /** {@code orgId}, then its parents, ending at {@code 0}. */
  static List<String> orgPath(String orgId, String clientId) {
    List<String> path = new ArrayList<>();
    OrganizationStructureProvider tree = OBContext.getOBContext()
        .getOrganizationStructureProvider(clientId);
    String current = orgId;
    while (current != null && !path.contains(current)) {
      path.add(current);
      if (SYSTEM_ORG.equals(current)) {
        break;
      }
      current = tree.getParentOrg(current);
    }
    if (!path.contains(SYSTEM_ORG)) {
      path.add(SYSTEM_ORG);
    }
    return path;
  }
}
