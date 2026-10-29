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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link SupportUserStore}: the insert behaves like {@code INSERT ... ON CONFLICT DO
 * NOTHING} on the primary key, atomically, so concurrent provisioning can be exercised. Test-only.
 */
class InMemorySupportUserStore implements SupportUserStore {

  /** One user row: the context it was last aligned to, and whether it is active. */
  static final class UserRow {
    volatile SupportUserContext context;
    volatile boolean active = true;
    volatile boolean supportFlag = true;
    volatile boolean ownerFlag;
    volatile String username;
    volatile String roleAssignment;
  }

  final Map<String, UserRow> users = new ConcurrentHashMap<>();
  final Map<String, String> adminRoles = new ConcurrentHashMap<>();
  final Map<String, SupportUserContext> references = new ConcurrentHashMap<>();
  final AtomicInteger successfulInserts = new AtomicInteger();
  /** Simulates another user of the client already carrying the support flag. */
  volatile boolean flagTakenByAnotherUser;

  @Override
  public String findClientAdminRoleId(String clientId) {
    return adminRoles.get(clientId);
  }

  @Override
  public SupportUserContext findReferenceContext(String clientId, String adminRoleId) {
    return references.get(clientId);
  }

  @Override
  public boolean insertUser(SupportUserContext context, String name, String username) {
    if (flagTakenByAnotherUser) {
      return false;
    }
    UserRow row = new UserRow();
    row.context = context;
    row.username = username;
    boolean inserted = users.putIfAbsent(context.getUserId(), row) == null;
    if (inserted) {
      successfulInserts.incrementAndGet();
    }
    return inserted;
  }

  @Override
  public boolean alignUser(SupportUserContext context) {
    UserRow row = users.get(context.getUserId());
    if (row == null) {
      return false;
    }
    row.context = context;
    row.active = true;
    row.supportFlag = true;
    row.ownerFlag = false;
    return true;
  }

  @Override
  public void ensureSingleRole(SupportUserContext context) {
    users.get(context.getUserId()).roleAssignment = context.getRoleId();
  }
}
