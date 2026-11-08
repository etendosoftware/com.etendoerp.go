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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SupportUserProvisioner} (ETP-5351, T2) over an in-memory store.
 */
class SupportUserProvisionerTest {

  private static final String CLIENT = "4028E6C72959682B01295A070852010D";
  private static final String ADMIN_ROLE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  private static final String ORG = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
  private static final String WAREHOUSE = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";

  private InMemorySupportUserStore store;
  private SupportUserProvisioner provisioner;

  @BeforeEach
  void setUp() {
    store = new InMemorySupportUserStore();
    store.adminRoles.put(CLIENT, ADMIN_ROLE);
    store.references.put(CLIENT,
        new SupportUserContext(CLIENT, null, ADMIN_ROLE, ORG, WAREHOUSE, "es_ES"));
    provisioner = new SupportUserProvisioner(store);
  }

  @Test
  void createsTheSupportUserWithTheAdminRoleAndTheAdminDefaults() {
    SupportUserContext context = provisioner.findOrCreate(CLIENT);

    assertEquals(SupportAccessGuard.supportUserIdFor(CLIENT), context.getUserId());
    assertEquals(ADMIN_ROLE, context.getRoleId());
    assertEquals(ORG, context.getOrgId());
    assertEquals(WAREHOUSE, context.getWarehouseId());
    InMemorySupportUserStore.UserRow row = store.users.get(context.getUserId());
    assertTrue(row.supportFlag);
    assertFalse(row.ownerFlag, "the support user must never be the owner");
    assertEquals(ADMIN_ROLE, row.roleAssignment);
    assertFalse(row.username.contains("@"), "no account email may match the username");
  }

  @Test
  void isIdempotentAndReactivatesAnInactiveUser() {
    SupportUserContext first = provisioner.findOrCreate(CLIENT);
    store.users.get(first.getUserId()).active = false;

    SupportUserContext second = provisioner.findOrCreate(CLIENT);

    assertEquals(first.getUserId(), second.getUserId());
    assertEquals(1, store.successfulInserts.get());
    assertTrue(store.users.get(second.getUserId()).active);
  }

  @Test
  void realignsTheRoleWhenTheAdminRoleChanged() {
    provisioner.findOrCreate(CLIENT);
    String newAdminRole = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD";
    store.adminRoles.put(CLIENT, newAdminRole);

    SupportUserContext context = provisioner.findOrCreate(CLIENT);

    assertEquals(newAdminRole, context.getRoleId());
    assertEquals(newAdminRole, store.users.get(context.getUserId()).roleAssignment);
  }

  @Test
  void twoConcurrentCallsCreateASingleUser() throws Exception {
    int callers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<SupportUserContext>> results = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        Callable<SupportUserContext> call = () -> {
          start.await();
          return provisioner.findOrCreate(CLIENT);
        };
        results.add(pool.submit(call));
      }
      start.countDown();
      Set<String> ids = new HashSet<>();
      for (Future<SupportUserContext> result : results) {
        ids.add(result.get(10, TimeUnit.SECONDS).getUserId());
      }
      assertEquals(1, ids.size());
      assertEquals(1, store.successfulInserts.get());
      assertEquals(1, store.users.size());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void refusesATenantWithoutAdminRole() {
    store.adminRoles.clear();
    SupportAccessException e = assertThrows(SupportAccessException.class,
        () -> provisioner.findOrCreate(CLIENT));
    assertEquals(SupportAccessException.CODE_NO_ADMIN_ROLE, e.getCode());
  }

  @Test
  void refusesATenantWithoutBusinessOrganization() {
    store.references.clear();
    assertThrows(SupportAccessException.class, () -> provisioner.findOrCreate(CLIENT));
  }

  @Test
  void failsClosedWhenAnotherUserAlreadyCarriesTheFlag() {
    store.flagTakenByAnotherUser = true;
    assertThrows(IllegalStateException.class, () -> provisioner.findOrCreate(CLIENT));
  }
}
