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
package com.etendoerp.go.schemaforge.util;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.openbravo.dal.service.OBDal;

/**
 * Shared helper for {@code AD_User.EM_ETGO_Is_Owner} (ETP-4830) — the tenant-owner protection
 * flag. Marks the ONE {@code AD_User} that completed self-service onboarding/registration for a
 * NEW client as that client's owner, and is later read by both write paths that must block anyone
 * else from touching the owner's record ({@code UserRoleAssignmentHandler}'s generic {@code
 * AD_User} PUT/PATCH guard, and {@code UserRoleCompositionService}'s role-reassignment guard).
 *
 * <p><b>Read/written via native SQL, never a DAL getter/setter</b> — same reasoning {@code
 * SFWindowAccessMap#resolveShowAccountingFields} documents for {@code
 * AD_Role.EM_ETGO_Show_Acct_Fields}: this extension column was added straight to the physical
 * table via the {@code /etendo:alter-db} webhook mechanism and is not mapped as a typed entity
 * property, so a native query is the safe, immediately-functional way to read/write it without
 * requiring an entity-model regeneration first.</p>
 *
 * <p><b>Existing tenants (before this column existed) all read back {@code false}</b> — the
 * physical column was added {@code NOT NULL DEFAULT 'N'}, so every pre-existing row was backfilled
 * to {@code 'N'} automatically. Every enforcement check gated on {@link #isOwner(String)} is
 * therefore a guaranteed no-op for those tenants until a separate, human-reviewed backfill
 * data-fix (Remedy's domain, {@code cli/src/data-fixes/} in {@code etendo_schema_forge}) assigns a
 * retroactive owner — this class intentionally does not attempt that heuristic itself.</p>
 */
public final class OwnerSupport {

  private static final Logger log = LogManager.getLogger(OwnerSupport.class);

  private static final String COLUMN_IS_OWNER = "em_etgo_is_owner";

  private OwnerSupport() {
    // static helper
  }

  /**
   * Whether {@code userId} is currently flagged as its client's owner.
   *
   * @param userId the {@code AD_User_ID} to check
   * @return {@code true} only when the column reads {@code 'Y'}; {@code false} for a missing
   *     user, an unset/{@code 'N'} column, or a {@code null}/blank {@code userId}
   */
  public static boolean isOwner(String userId) {
    if (userId == null || userId.isBlank()) {
      return false;
    }
    Session session = OBDal.getInstance().getSession();
    // Result type is deliberately Object, not String — see SFWindowAccessMap's identical
    // native-query javadoc: Hibernate maps a PostgreSQL char(1) column to Character for a plain
    // scalar native query (no explicit addScalar type), and a generics-erased
    // List<String>.get(0) throws ClassCastException on any real row.
    NativeQuery<Object> query = session.createNativeQuery(
        "SELECT " + COLUMN_IS_OWNER + " FROM ad_user WHERE ad_user_id = :userId");
    query.setParameter("userId", userId);
    List<Object> results = query.getResultList();
    return !results.isEmpty() && results.get(0) != null && "Y".equals(results.get(0).toString());
  }

  /**
   * Whether {@code clientId} already has ANY user flagged as owner — used to keep tenant
   * provisioning idempotent (a resumed/retried onboarding call must never try to re-assign or
   * move ownership once it has already been set for that client).
   *
   * @param clientId the {@code AD_Client_ID} to check
   * @return {@code true} when at least one {@code AD_User} of this client has the column set to
   *     {@code 'Y'}; {@code false} otherwise (including a {@code null}/blank {@code clientId})
   */
  public static boolean clientHasOwner(String clientId) {
    if (clientId == null || clientId.isBlank()) {
      return false;
    }
    Session session = OBDal.getInstance().getSession();
    NativeQuery<Object> query = session.createNativeQuery(
        "SELECT 1 FROM ad_user WHERE ad_client_id = :clientId AND " + COLUMN_IS_OWNER
            + " = 'Y' LIMIT 1");
    query.setParameter("clientId", clientId);
    return !query.getResultList().isEmpty();
  }

  /**
   * Flags {@code userId} as {@code clientId}'s owner, UNLESS the client already has one — the
   * one-owner-per-client invariant (ETP-4830). A no-op (never overwrites, never moves ownership)
   * when a resumed/retried tenant-provisioning call finds an owner already set, so this is safe
   * to call unconditionally on every onboarding pass rather than only on the very first one.
   *
   * <p><b>Single atomic UPDATE — not a check-then-update.</b> The {@code ad_client_id} match and
   * the "no owner yet" check both live inside the same {@code UPDATE ... WHERE ...} statement
   * (via a correlated {@code NOT EXISTS} subquery), instead of a separate {@link
   * #clientHasOwner(String)} pre-check followed by an unscoped {@code UPDATE}. This closes two
   * bugs the old two-statement version had: (a) the old {@code UPDATE} filtered only by {@code
   * ad_user_id}, so a caller bug elsewhere could flag a user belonging to a DIFFERENT client as
   * this client's owner; (b) the check and the update were not atomic, so two concurrent
   * provisioning calls for the same client could both pass the check before either committed,
   * marking two or more owners for the same client.
   *
   * @param clientId the {@code AD_Client_ID} the owner is being assigned for
   * @param userId the {@code AD_User_ID} to flag as owner
   */
  public static void markAsOwnerIfNoneExists(String clientId, String userId) {
    if (userId == null || userId.isBlank() || clientId == null || clientId.isBlank()) {
      log.warn("OwnerSupport.markAsOwnerIfNoneExists: missing clientId/userId (client={}, user={})",
          clientId, userId);
      return;
    }
    Session session = OBDal.getInstance().getSession();
    NativeQuery<?> update = session.createNativeQuery(
        "UPDATE ad_user SET " + COLUMN_IS_OWNER + " = 'Y' "
            + "WHERE ad_user_id = :userId AND ad_client_id = :clientId AND NOT EXISTS ("
            + "SELECT 1 FROM ad_user u2 WHERE u2.ad_client_id = :clientId AND u2."
            + COLUMN_IS_OWNER + " = 'Y')");
    update.setParameter("userId", userId);
    update.setParameter("clientId", clientId);
    int updated = update.executeUpdate();
    if (updated == 0) {
      // Zero rows affected means either: no AD_User row matches userId for this clientId, OR the
      // client already has an owner — the single atomic statement above cannot distinguish the
      // two, and re-querying separately here would reintroduce the very race this fix removes.
      log.warn("OwnerSupport.markAsOwnerIfNoneExists: no row updated for user {} in client {} — "
          + "either the user does not belong to this client, or the client already has an owner",
          userId, clientId);
      return;
    }
    log.info("Flagged user {} as owner of client {}", userId, clientId);
  }
}
