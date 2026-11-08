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
 * All portions are Copyright (C) 2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */
package com.etendoerp.go.supportaccess;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * ETP-5351 (T2) — creates, reactivates and realigns the "Soporte Etendo" user of a tenant.
 *
 * <p>One user per tenant, with a fixed id ({@link SupportAccessGuard#supportUserIdFor(String)}).
 * That is what makes {@link #findOrCreate} idempotent and safe for two operators at once: both
 * try the same {@code INSERT ... ON CONFLICT DO NOTHING}; the database lets one in and the other
 * finds the row already there (it waits for the first transaction, then sees it), so there is no
 * exception, no broken transaction and never a second user. The unique index
 * {@code EM_ETGO_USER_SUPPORT_UQ} backs the same rule from the flag side.</p>
 *
 * <p>The user has <b>no password</b> ({@code PASSWORD} null: the core login refuses such a user),
 * no email (so no platform account ever owns it), a username no account email can match, the
 * client admin role as its only role, and the admin's organization and warehouse. It is never the
 * owner: {@code EM_ETGO_IS_OWNER} is forced to {@code 'N'} and {@code OwnerSupport} skips it.</p>
 */
public class SupportUserProvisioner {

  private static final Logger log = LogManager.getLogger(SupportUserProvisioner.class);

  /** Display name of the support user (approved by product, ETP-5351). */
  public static final String SUPPORT_USER_NAME = "Soporte Etendo";
  private static final String USERNAME_PREFIX = "etgo-support-";

  private final SupportUserStore store;

  /** Production wiring. */
  public SupportUserProvisioner() {
    this(new JdbcSupportUserStore());
  }

  /**
   * Explicit wiring, for tests.
   *
   * @param store the user persistence
   */
  public SupportUserProvisioner(SupportUserStore store) {
    this.store = store;
  }

  /**
   * Returns the tenant's support user, creating it, reactivating it or realigning its role and
   * defaults as needed. Does not commit.
   *
   * @param clientId the tenant
   * @return the support user and the context it acts with
   * @throws SupportAccessException {@code SUPPORT_NO_ADMIN_ROLE} when the tenant has no admin role
   *     or no business organization
   * @throws IllegalStateException  when the fixed-id user cannot be created because another user
   *     of the tenant already carries the support flag
   */
  public SupportUserContext findOrCreate(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      throw new IllegalArgumentException("clientId is required");
    }
    String roleId = store.findClientAdminRoleId(clientId);
    if (roleId == null) {
      throw new SupportAccessException(SupportAccessException.CODE_NO_ADMIN_ROLE,
          "The tenant has no active client admin role");
    }
    SupportUserContext reference = store.findReferenceContext(clientId, roleId);
    if (reference == null || StringUtils.isBlank(reference.getOrgId())) {
      throw new SupportAccessException(SupportAccessException.CODE_NO_ADMIN_ROLE,
          "The tenant has no organization the admin role can work in");
    }
    SupportUserContext context = new SupportUserContext(clientId,
        SupportAccessGuard.supportUserIdFor(clientId), roleId, reference.getOrgId(),
        reference.getWarehouseId(), reference.getLanguage());

    boolean created = store.insertUser(context, SUPPORT_USER_NAME, usernameFor(clientId));
    if (!store.alignUser(context)) {
      throw new IllegalStateException(
          "Another user of client " + clientId + " already carries the support flag");
    }
    store.ensureSingleRole(context);
    if (created) {
      log.info("Created support user {} for client {}", context.getUserId(), clientId);
    }
    return context;
  }

  /**
   * The login name of a tenant's support user. Contains no {@code @}, so no platform account
   * email (nor its {@code email+suffix} variants) can ever match it.
   *
   * @param clientId the tenant
   * @return the username
   */
  public static String usernameFor(String clientId) {
    return USERNAME_PREFIX + StringUtils.lowerCase(clientId);
  }
}
