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
 * rejects rows it can prove are foreign), and any failure here falls back to the first row, so
 * the guard can never make a default worse than it was.</p>
 */
final class NeoDefaultTenantGuard {

  private static final Logger log = LogManager.getLogger(NeoDefaultTenantGuard.class);

  /** Client {@code 0} (System) rows are shared by every tenant. */
  static final String SYSTEM_CLIENT_ID = "0";

  /** Upper bound on rows read from an {@code @SQL=} default when the guard is active. */
  static final int MAX_CANDIDATES = 200;

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
   * The first candidate, in query order, that belongs to the current client or to client
   * {@code 0}; {@code null} when every candidate belongs to another tenant (or there are none).
   */
  String firstSameTenant(List<String> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return null;
    }
    Optional<Map<String, String>> lookup = loadClients(candidates);
    if (lookup.isEmpty()) {
      return candidates.get(0);
    }
    Map<String, String> clientById = lookup.get();
    int rejected = 0;
    for (String candidate : candidates) {
      if (candidate == null) {
        continue;
      }
      String owner = clientById.get(candidate);
      if (owner == null || currentClientId.equals(owner) || SYSTEM_CLIENT_ID.equals(owner)) {
        logRejected(rejected);
        return candidate;
      }
      rejected++;
    }
    logRejected(rejected);
    return null;
  }

  private void logRejected(int rejected) {
    if (rejected > 0) {
      // Never log the values themselves: they are another tenant's ids.
      log.warn("@SQL= default of column {} matched {} row(s) of {} owned by another client;"
          + " skipped them (ETP-5682)", columnName, rejected, tableName);
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
