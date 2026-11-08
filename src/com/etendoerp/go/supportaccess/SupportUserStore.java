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

/**
 * ETP-5351 — persistence port of {@link SupportUserProvisioner}. Kept behind an interface so the
 * provisioning decisions are unit-testable; {@link JdbcSupportUserStore} is the production one.
 */
public interface SupportUserStore {

  /**
   * The tenant's active client-admin role ({@code is_client_admin = 'Y'}).
   *
   * @param clientId the tenant
   * @return the role id, or {@code null} when the tenant has none
   */
  String findClientAdminRoleId(String clientId);

  /**
   * The organization, warehouse and language the tenant's admin works with: the owner's when the
   * owner holds the admin role, else another active admin's, else the first business
   * organization and its first active warehouse.
   *
   * @param clientId    the tenant
   * @param adminRoleId the tenant's client-admin role
   * @return a context without user id, or {@code null} when the tenant has no business
   *     organization
   */
  SupportUserContext findReferenceContext(String clientId, String adminRoleId);

  /**
   * Inserts the support user. Never throws on a uniqueness conflict.
   *
   * @param context  the user id, client, role, organization, warehouse and language
   * @param name     the display name
   * @param username the login name; it can never log in (no password)
   * @return {@code false} when a unique constraint refused it (the user already exists)
   */
  boolean insertUser(SupportUserContext context, String name, String username);

  /**
   * Re-asserts everything the support user must be: active, flagged support, not owner, no
   * password, and the given defaults.
   *
   * @param context the expected state
   * @return {@code false} when no such user exists in that client
   */
  boolean alignUser(SupportUserContext context);

  /**
   * Leaves the user with exactly one active role assignment: the given role.
   *
   * @param context the user, client and role
   */
  void ensureSingleRole(SupportUserContext context);
}
