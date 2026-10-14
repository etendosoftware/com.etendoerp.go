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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;

/**
 * Keeps an {@code @SQL=} foreign-key default from resolving to another tenant's row (ETP-5682).
 *
 * <p>Some core {@code AD_Column} defaults query a client-scoped table without filtering by
 * {@code AD_Client_ID}. {@code GL_Journal.C_DocType_ID} is the one that bit:
 * {@code SELECT C_DocType_ID FROM C_DocType WHERE DocBaseType='GLJ' AND AD_ISORGINCLUDED(...)}.
 * Every tenant keeps its GLJ document type in org {@code 0}, so the query matches the document
 * type of every client and the first row is arbitrary. Classic hides this behind the combo's
 * client-access filter; NEO evaluates the SQL as-is, so the journal took another tenant's
 * document type and consumed that tenant's sequence, which ended in DocumentNo collisions.</p>
 *
 * <p>The rule is structural and names no entity: when the column is a foreign key to a
 * client-enabled table, a candidate row is acceptable only if it belongs to the current client
 * or to client {@code 0}. The query's own order is kept, so the first acceptable row wins. A
 * candidate that cannot be found by its primary key is accepted as before (the guard only
 * rejects rows it can prove are foreign), and a lookup that fails falls back to the first row.
 * One limit on that fallback: on PostgreSQL a lookup SELECT that fails with an SQL error also
 * aborts the surrounding transaction, exactly as a failure of the default query itself would. The
 * lookup only reads the target's primary key and {@code AD_Client_ID}, with names taken from the
 * DAL model, so this is not expected in practice.</p>
 */
final class NeoDefaultTenantGuard {

  private static final Logger log = LogManager.getLogger(NeoDefaultTenantGuard.class);

  /** Client {@code 0} (System) rows are shared by every tenant. */
  static final String SYSTEM_CLIENT_ID = "0";

  /**
   * Rows of an {@code @SQL=} default checked per ownership lookup. Every row is read until an
   * acceptable one appears, a batch at a time, so an unscoped default over many tenants (more
   * than 800 sales-order document types in production) still finds the tenant's own row.
   */
  static final int BATCH_SIZE = 200;

  /** What a scan of an {@code @SQL=} default's rows found. */
  static final class Scan {
    static final Scan NO_ROWS = new Scan(false, null);

    /** Whether the default query returned any row at all. */
    final boolean anyRow;
    /** The chosen value, or {@code null} when no row was acceptable. */
    final String value;

    Scan(boolean anyRow, String value) {
      this.anyRow = anyRow;
      this.value = value;
    }
  }

  private final String columnName;
  private final String tableName;
  private final String idColumnName;
  private final String currentClientId;

  private NeoDefaultTenantGuard(String columnName, String tableName, String idColumnName,
      String currentClientId) {
    this.columnName = columnName;
    this.tableName = tableName;
    this.idColumnName = idColumnName;
    this.currentClientId = currentClientId;
  }

  /**
   * The guard for {@code adColumn}, or {@code null} when it does not apply: no column (a tab
   * auxiliary input), no client in context, a System (client {@code 0}) context that may
   * legitimately read every tenant, or a column that is not a primary-key foreign key to a
   * client-enabled table.
   */
  static NeoDefaultTenantGuard forColumn(Column adColumn, String currentClientId) {
    if (adColumn == null || currentClientId == null || currentClientId.isEmpty()
        || SYSTEM_CLIENT_ID.equals(currentClientId)) {
      return null;
    }
    try {
      if (adColumn.getTable() == null) {
        return null;
      }
      Entity entity = ModelProvider.getInstance().getEntityByTableId(adColumn.getTable().getId());
      Property property = entity == null ? null
          : entity.getPropertyByColumnName(adColumn.getDBColumnName(), false);
      Entity target = property == null || property.isPrimitive() ? null
          : property.getTargetEntity();
      if (target == null || !target.isClientEnabled() || target.getIdProperties().size() != 1) {
        return null;
      }
      Property referenced = property.getReferencedProperty();
      if (referenced != null && !referenced.isId()) {
        return null;
      }
      return new NeoDefaultTenantGuard(adColumn.getDBColumnName(), target.getTableName(),
          target.getIdProperties().get(0).getColumnName(), currentClientId);
    } catch (RuntimeException e) {
      log.debug("Tenant guard not applicable to column {}: {}", adColumn.getDBColumnName(),
          e.getMessage());
      return null;
    }
  }

  /**
   * Reads the default's rows in query order, {@link #BATCH_SIZE} at a time, and picks the first
   * one that belongs to the current client or to client {@code 0}; a row whose primary key is
   * not found is accepted too. Stops at the first acceptable row, so the usual case is one batch
   * and one lookup. {@code null} values are never chosen. When a lookup fails, the very first
   * row is returned, as without the guard. Logs one WARN with the total rejected.
   */
  Scan scan(ResultSet rows) throws SQLException {
    String firstRow = null;
    boolean anyRow = false;
    int rejected = 0;
    List<String> batch = readBatch(rows);
    // A short batch means the result set is exhausted: do not call next() on it again.
    boolean more = batch.size() == BATCH_SIZE;
    while (!batch.isEmpty()) {
      if (!anyRow) {
        anyRow = true;
        firstRow = batch.get(0);
      }
      List<String> ids = nonNullValues(batch);
      if (!ids.isEmpty()) {
        Optional<Map<String, String>> lookup = loadClients(ids);
        if (lookup.isEmpty()) {
          logRejected(rejected);
          return new Scan(true, firstRow);
        }
        int accepted = indexOfAcceptable(ids, lookup.get());
        if (accepted >= 0) {
          logRejected(rejected + accepted);
          return new Scan(true, ids.get(accepted));
        }
        rejected += ids.size();
      }
      batch = more ? readBatch(rows) : List.of();
      more = batch.size() == BATCH_SIZE;
    }
    logRejected(rejected);
    return anyRow ? new Scan(true, null) : Scan.NO_ROWS;
  }

  /** Up to {@link #BATCH_SIZE} values of the next rows; empty when there are none left. */
  private static List<String> readBatch(ResultSet rows) throws SQLException {
    List<String> batch = new ArrayList<>(BATCH_SIZE);
    while (batch.size() < BATCH_SIZE && rows.next()) {
      batch.add(rows.getString(1));
    }
    return batch;
  }

  private static List<String> nonNullValues(List<String> batch) {
    List<String> ids = new ArrayList<>(batch.size());
    for (String candidate : batch) {
      if (candidate != null) {
        ids.add(candidate);
      }
    }
    return ids;
  }

  /** Position of the first acceptable candidate, or {@code -1} when every one is rejected. */
  private int indexOfAcceptable(List<String> ids, Map<String, String> clientById) {
    for (int i = 0; i < ids.size(); i++) {
      if (isAcceptable(clientById.get(ids.get(i)))) {
        return i;
      }
    }
    return -1;
  }

  /** A row is acceptable when it is unknown, the current client's, or client 0's. */
  private boolean isAcceptable(String owner) {
    return owner == null || currentClientId.equals(owner) || SYSTEM_CLIENT_ID.equals(owner);
  }

  private void logRejected(int rejected) {
    if (rejected > 0) {
      // Never log the values themselves: they are another tenant's ids.
      log.warn("@SQL= default of column {} matched {} row(s) of {} owned by another client;"
          + " skipped them", columnName, rejected, tableName);
    }
  }

  /** id → AD_Client_ID for the candidates, or empty when the lookup fails. */
  private Optional<Map<String, String>> loadClients(List<String> candidates) {
    StringBuilder placeholders = new StringBuilder();
    for (int i = 0; i < candidates.size(); i++) {
      placeholders.append(i == 0 ? "?" : ",?");
    }
    // Table and column names come from the DAL model, never from request input.
    String sql = "SELECT " + idColumnName + ", AD_Client_ID FROM " + tableName + " WHERE "
        + idColumnName + " IN (" + placeholders + ")";
    try (PreparedStatement ps = OBDal.getInstance().getConnection(false).prepareStatement(sql)) {
      for (int i = 0; i < candidates.size(); i++) {
        ps.setString(i + 1, candidates.get(i));
      }
      Map<String, String> clientById = new HashMap<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          clientById.put(rs.getString(1), rs.getString(2));
        }
      }
      return Optional.of(clientById);
    } catch (SQLException | RuntimeException e) {
      log.debug("Tenant guard lookup failed for column {}: {}", columnName, e.getMessage());
      return Optional.empty();
    }
  }
}
