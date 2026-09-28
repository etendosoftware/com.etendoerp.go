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
package com.etendoerp.go.onboarding.pool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;

/**
 * JDBC access to {@code ETGO_TENANT_POOL} (ETP-5389).
 *
 * <p>Plain SQL on the DAL connection rather than a generated entity: the claim needs
 * {@code FOR UPDATE SKIP LOCKED}, which HQL cannot express, and it must run inside the onboarding
 * request's own transaction so that a failed onboarding rolls the claim back and the tenant returns
 * to READY. PostgreSQL only, like the rest of this module.
 *
 * <p>No method commits except {@link #commit()}: the caller decides the transaction boundary.
 */
public class TenantPoolStore {

  public static final String STATUS_PROVISIONING = "PROVISIONING";
  public static final String STATUS_READY = "READY";
  public static final String STATUS_CLAIMED = "CLAIMED";
  public static final String STATUS_FAILED = "FAILED";
  public static final String STATUS_STALE = "STALE";

  private static final int ERROR_MAX_LENGTH = 2000;
  private static final String SYSTEM_USER = "100";
  private static final String SQL_UPDATE_STATUS = "UPDATE etgo_tenant_pool SET status = '";

  private static final String SQL_COUNT = "SELECT count(*) FROM etgo_tenant_pool"
      + " WHERE status = ? AND isactive = 'Y'";
  private static final String SQL_COUNT_READY = SQL_COUNT + " AND provisioning_version = ?";
  private static final String SQL_RETIRE_STALE = "UPDATE etgo_tenant_pool"
      + " SET status = '" + STATUS_STALE + "', updated = now(), updatedby = '" + SYSTEM_USER + "'"
      + " WHERE status = '" + STATUS_READY + "' AND (provisioning_version <> ? OR created < ?)";
  private static final String SQL_EXPIRE_PROVISIONING = "UPDATE etgo_tenant_pool"
      + " SET status = '" + STATUS_FAILED + "', error_message = 'Provisioning lease expired',"
      + " updated = now(), updatedby = '" + SYSTEM_USER + "'"
      + " WHERE status = '" + STATUS_PROVISIONING + "' AND created < ?";
  private static final String SQL_INSERT = "INSERT INTO etgo_tenant_pool (etgo_tenant_pool_id,"
      + " ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby, status,"
      + " provisioning_version) VALUES (get_uuid(), '0', '0', 'Y', now(), '" + SYSTEM_USER + "',"
      + " now(), '" + SYSTEM_USER + "', '" + STATUS_PROVISIONING + "', ?)"
      + " RETURNING etgo_tenant_pool_id";
  private static final String SQL_MARK_READY = SQL_UPDATE_STATUS
      + STATUS_READY + "', pool_client_id = ?, updated = now() WHERE etgo_tenant_pool_id = ?"
      + " AND status = '" + STATUS_PROVISIONING + "'";
  private static final String SQL_MARK_FAILED = SQL_UPDATE_STATUS
      + STATUS_FAILED + "', pool_client_id = coalesce(CAST(? AS VARCHAR), pool_client_id),"
      + " error_message = ?, updated = now() WHERE etgo_tenant_pool_id = ?";
  /**
   * The claim. The inner {@code SKIP LOCKED} is what makes it safe under concurrency: a READY row
   * another onboarding already locked is skipped rather than waited for, so two concurrent signups
   * always get two different tenants (or one of them falls back to the classic path). The row lock
   * is held until the onboarding transaction ends, and a rollback returns the row to READY.
   */
  private static final String SQL_CLAIM = SQL_UPDATE_STATUS + STATUS_CLAIMED
      + "', claimed_at = now(), updated = now()"
      + " WHERE etgo_tenant_pool_id = (SELECT etgo_tenant_pool_id FROM etgo_tenant_pool"
      + "   WHERE status = '" + STATUS_READY + "' AND isactive = 'Y'"
      + "   AND provisioning_version = ? AND pool_client_id IS NOT NULL"
      + "   ORDER BY created LIMIT 1 FOR UPDATE SKIP LOCKED)"
      + " RETURNING etgo_tenant_pool_id, pool_client_id";

  /** A claimed pool row: its own id and the tenant it hands out. */
  public record Claim(String poolRowId, String clientId) {
  }

  /**
   * Counts active READY rows built with the requested provisioning version.
   *
   * @param version provisioning version to count
   * @return number of active READY rows for the version
   */
  public int countReady(String version) {
    return count(SQL_COUNT_READY, STATUS_READY, version);
  }

  /**
   * Counts active rows currently being provisioned.
   *
   * @return number of active rows currently being provisioned
   */
  public int countProvisioning() {
    return count(SQL_COUNT, STATUS_PROVISIONING, null);
  }

  /**
   * Retires READY rows built by another provisioning version or older than {@code createdBefore}.
   *
   * @param version current provisioning version
   * @param createdBefore oldest creation instant to keep
   * @return number of rows retired
   */
  public int retireStale(String version, Instant createdBefore) {
    return update(SQL_RETIRE_STALE, version, Timestamp.from(createdBefore));
  }

  /**
   * Fails PROVISIONING rows whose run started before {@code startedBefore}.
   *
   * @param startedBefore lease expiration cutoff
   * @return number of rows expired
   */
  public int expireProvisioning(Instant startedBefore) {
    return update(SQL_EXPIRE_PROVISIONING, Timestamp.from(startedBefore));
  }

  /**
   * Inserts a new PROVISIONING row stamped with {@code version}.
   *
   * @param version provisioning version to store
   * @return the id of the new PROVISIONING row
   */
  public String insertProvisioning(String version) {
    try (PreparedStatement ps = connection().prepareStatement(SQL_INSERT)) {
      ps.setString(1, version);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    } catch (SQLException e) {
      throw new OBException("Could not register a tenant pool row", e);
    }
  }

  /**
   * Marks a provisioning row READY and associates its client.
   *
   * @param poolRowId pool row identifier
   * @param clientId provisioned client identifier
   */
  public void markReady(String poolRowId, String clientId) {
    update(SQL_MARK_READY, clientId, poolRowId);
  }

  /**
   * Marks a pool row FAILED and stores a bounded diagnostic message.
   *
   * @param poolRowId pool row identifier
   * @param clientId partially provisioned client identifier, when available
   * @param error failure diagnostic message
   */
  public void markFailed(String poolRowId, String clientId, String error) {
    update(SQL_MARK_FAILED, clientId,
        StringUtils.abbreviate(StringUtils.defaultIfBlank(error, "Unknown error"),
            ERROR_MAX_LENGTH),
        poolRowId);
  }

  /**
   * Atomically claims the oldest READY tenant of {@code version}, inside the caller's transaction.
   *
   * @param version provisioning version to claim
   * @return the claim, or {@code null} when the pool has nothing to hand out
   */
  public Claim claimReady(String version) {
    try (PreparedStatement ps = connection().prepareStatement(SQL_CLAIM)) {
      ps.setString(1, version);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? new Claim(rs.getString(1), rs.getString(2)) : null;
      }
    } catch (SQLException e) {
      throw new OBException("Could not claim a pooled tenant", e);
    }
  }

  /** Commits the caller's DAL transaction. */
  public void commit() {
    OBDal.getInstance().commitAndClose();
  }

  /** Rolls back the caller's DAL transaction. */
  public void rollback() {
    OBDal.getInstance().rollbackAndClose();
  }

  protected Connection connection() {
    OBDal.getInstance().flush();
    return OBDal.getInstance().getConnection();
  }

  private int count(String sql, String status, String version) {
    try (PreparedStatement ps = connection().prepareStatement(sql)) {
      ps.setString(1, status);
      if (version != null) {
        ps.setString(2, version);
      }
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    } catch (SQLException e) {
      throw new OBException("Could not count the tenant pool", e);
    }
  }

  private int update(String sql, Object... params) {
    try (PreparedStatement ps = connection().prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new OBException("Could not update the tenant pool", e);
    }
  }
}
