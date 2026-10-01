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
package com.etendoerp.go.startup;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.roles.TemplateAccessPropagationService;
import com.etendoerp.go.roles.TemplateAccessPropagationService.SweepCounts;
import com.etendoerp.go.roles.UserRoleWriteLock;

/**
 * Unit tests for {@link TemplateRoleAccessStartup}'s tick: when it sweeps, under which lease, in
 * which chunks, and when it advances the stored fingerprints. The SQL itself is covered by {@code
 * TemplateAccessPropagationServiceIntegrationTest}.
 */
class TemplateRoleAccessStartupTest {

  private static final String HOLDER = "test-holder";
  private static final String FINANCE = "FINANCE";
  private static final String SALES = "SALES";

  private TemplateAccessPropagationService propagation;
  private TemplateRoleSyncStore store;
  private UserRoleWriteLock writeLock;
  private TemplateRoleAccessStartup startup;
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<OBContext> obContextMock;

  @BeforeEach
  void setUp() {
    propagation = mock(TemplateAccessPropagationService.class);
    store = mock(TemplateRoleSyncStore.class);
    writeLock = mock(UserRoleWriteLock.class);
    startup = new TemplateRoleAccessStartup(propagation, store, writeLock, HOLDER);
    obDalMock = mockStatic(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(mock(OBDal.class));
    obContextMock = mockStatic(OBContext.class);

    Map<String, String> fingerprints = new LinkedHashMap<>();
    fingerprints.put(FINANCE, "f1");
    fingerprints.put(SALES, "s1");
    when(propagation.fingerprints()).thenReturn(fingerprints);
    when(propagation.rolesWithStaleCopies()).thenReturn(Collections.emptyList());
    when(propagation.rolesInheriting(anyCollection())).thenReturn(Collections.emptyList());
    when(propagation.sweepRoles(anyCollection())).thenReturn(new SweepCounts());
    when(store.acquireLease(HOLDER)).thenReturn(true);
    when(store.renewLease(HOLDER)).thenReturn(true);
  }

  @AfterEach
  void tearDown() {
    obDalMock.close();
    obContextMock.close();
  }

  private void stored(String fingerprintFinance, String fingerprintSales, int version) {
    Map<String, TemplateRoleSyncStore.Stored> stored = new LinkedHashMap<>();
    stored.put(FINANCE, new TemplateRoleSyncStore.Stored(fingerprintFinance, version));
    stored.put(SALES, new TemplateRoleSyncStore.Stored(fingerprintSales, version));
    when(store.readFingerprints()).thenReturn(stored);
  }

  @Test
  void unchangedTemplatesTakeNoLeaseAndSweepNothing() {
    stored("f1", "s1", TemplateAccessPropagationService.ALGO_VERSION);

    TemplateRoleAccessStartup.TickResult result = startup.tick(true);

    assertFalse(result.didWork());
    verify(store, never()).acquireLease(anyString());
    verify(propagation, never()).sweepRoles(anyCollection());
  }

  @Test
  void emptySyncTableSweepsTheInheritorsOfEveryTemplateAndStoresFingerprints() {
    when(store.readFingerprints()).thenReturn(Collections.emptyMap());
    when(propagation.rolesInheriting(anyCollection())).thenReturn(List.of("P1", "P2"));

    TemplateRoleAccessStartup.TickResult result = startup.tick(true);

    assertEquals(2, result.getChangedTemplates());
    assertEquals(2, result.getRolesSwept());
    verify(propagation).sweepRoles(List.of("P1", "P2"));
    verify(store).storeFingerprint(eq(FINANCE), eq("f1"), anyInt(), anyInt(), anyInt());
    verify(store).storeFingerprint(eq(SALES), eq("s1"), anyInt(), anyInt(), anyInt());
    verify(store).releaseLease(eq(HOLDER), isNull());
  }

  @Test
  void onlyTheChangedTemplateIsSwept() {
    stored("f1", "old", TemplateAccessPropagationService.ALGO_VERSION);

    startup.tick(false);

    verify(propagation).rolesInheriting(Collections.singleton(SALES));
    verify(store).storeFingerprint(eq(SALES), eq("s1"), anyInt(), anyInt(), anyInt());
    verify(store, never()).storeFingerprint(eq(FINANCE), anyString(), anyInt(), anyInt(),
        anyInt());
  }

  @Test
  void fingerprintStoredByANewerRuleVersionCountsAsUnchanged() {
    stored("other", "other", TemplateAccessPropagationService.ALGO_VERSION + 1);

    assertFalse(startup.tick(false).didWork());
    verify(store, never()).acquireLease(anyString());
  }

  @Test
  void leaseHeldElsewhereSkipsTheTick() {
    when(store.readFingerprints()).thenReturn(Collections.emptyMap());
    when(store.acquireLease(HOLDER)).thenReturn(false);

    assertTrue(startup.tick(true).isLeaseBusy());
    verify(propagation, never()).sweepRoles(anyCollection());
    verify(store, never()).releaseLease(anyString(), any());
  }

  @Test
  void failedChunkKeepsTheOldFingerprintsAndReleasesTheLeaseWithAnError() {
    when(store.readFingerprints()).thenReturn(Collections.emptyMap());
    List<String> roles = new ArrayList<>();
    for (int i = 0; i < TemplateRoleAccessStartup.CHUNK_SIZE * 2 + 5; i++) {
      roles.add(String.format("R%04d", i));
    }
    when(propagation.rolesInheriting(anyCollection())).thenReturn(roles);
    when(propagation.sweepRoles(anyCollection())).thenReturn(new SweepCounts())
        .thenThrow(new IllegalStateException("lock timeout"))
        .thenReturn(new SweepCounts());

    TemplateRoleAccessStartup.TickResult result = startup.tick(false);

    assertEquals(1, result.getFailedChunks());
    verify(propagation, times(3)).sweepRoles(anyCollection());
    verify(store, times(3)).renewLease(HOLDER);
    verify(store, never()).storeFingerprint(anyString(), anyString(), anyInt(), anyInt(),
        anyInt());
    verify(store).releaseLease(eq(HOLDER), eq("1 sweep chunk(s) failed; see the application log"));
  }

  @Test
  void staleCopiesAreSweptWithoutAFingerprintChange() {
    stored("f1", "s1", TemplateAccessPropagationService.ALGO_VERSION);
    when(propagation.rolesWithStaleCopies()).thenReturn(List.of("P9"));

    TemplateRoleAccessStartup.TickResult result = startup.tick(false);

    assertEquals(1, result.getRolesSwept());
    verify(propagation).sweepRoles(List.of("P9"));
    verify(store, never()).storeFingerprint(anyString(), anyString(), anyInt(), anyInt(),
        anyInt());
  }

  @Test
  void purgeRunsUnderTheLeaseBeforeTheSweep() {
    stored("f1", "s1", TemplateAccessPropagationService.ALGO_VERSION);
    when(propagation.hasPurgeableTemplateRows(TemplateRoleAccessStartup.PURGE_GRACE_DAYS))
        .thenReturn(true);
    when(propagation.purgeInactiveTemplateRows(TemplateRoleAccessStartup.PURGE_GRACE_DAYS))
        .thenReturn(3);

    assertEquals(3, startup.tick(false).getPurged());
    InOrder order = inOrder(store, propagation);
    order.verify(store).acquireLease(HOLDER);
    order.verify(propagation).purgeInactiveTemplateRows(TemplateRoleAccessStartup.PURGE_GRACE_DAYS);
  }

  @Test
  void ownersAreLockedBeforeTheChunkIsSwept() {
    stored("f1", "s1", TemplateAccessPropagationService.ALGO_VERSION);
    when(propagation.rolesWithStaleCopies()).thenReturn(List.of("P1"));
    when(propagation.ownersOf(anyCollection())).thenReturn(List.of("U1", "U2"));

    startup.tick(false);

    InOrder order = inOrder(writeLock, propagation);
    order.verify(writeLock).acquire("U1");
    order.verify(writeLock).acquire("U2");
    order.verify(propagation).sweepRoles(List.of("P1"));
  }

  @Test
  void tickSafelyNeverThrows() {
    when(propagation.fingerprints()).thenThrow(new IllegalStateException("db down"));

    assertDoesNotThrow(() -> startup.tickSafely(true));
  }
}
