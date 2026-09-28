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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** ETP-5389 — top-up logic of the tenant pool filler, against an in-memory store. */
public class TenantPoolFillerTest {

  private static final String VERSION = "rev@cut";
  private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

  @Test
  public void topsUpToTargetSizeProvisioningOneTenantPerMissingSlot() {
    FakeTenantPoolStore store = new FakeTenantPoolStore();
    store.ready = 1;
    List<String> names = new ArrayList<>();
    TenantPoolFiller filler = new TenantPoolFiller(store, name -> {
      names.add(name);
      return TenantPoolFiller.ProvisionOutcome.ok("CLIENT-" + names.size());
    });

    TenantPoolFiller.FillReport report = filler.fill(3, VERSION, NOW);

    assertFalse(report.skipped());
    assertEquals(1, report.readyBefore());
    assertEquals(2, report.created());
    assertEquals(0, report.failed());
    assertEquals(List.of("POOL-ROW1", "POOL-ROW2"), names);
    assertEquals(TenantPoolStore.STATUS_READY, store.statusByRow.get("ROW1"));
    assertEquals("CLIENT-2", store.clientByRow.get("ROW2"));
    assertTrue(store.calls.contains("countReady:" + VERSION));
  }

  @Test
  public void fullPoolProvisionsNothing() {
    FakeTenantPoolStore store = new FakeTenantPoolStore();
    store.ready = 3;
    TenantPoolFiller filler = new TenantPoolFiller(store, name -> {
      throw new AssertionError("must not provision");
    });

    TenantPoolFiller.FillReport report = filler.fill(3, VERSION, NOW);

    assertEquals(0, report.created());
    assertTrue(store.statusByRow.isEmpty());
  }

  @Test
  public void rowsStillProvisioningCountAgainstTheDeficit() {
    FakeTenantPoolStore store = new FakeTenantPoolStore();
    store.ready = 1;
    store.provisioning = 1;
    List<String> names = new ArrayList<>();
    TenantPoolFiller filler = new TenantPoolFiller(store, name -> {
      names.add(name);
      return TenantPoolFiller.ProvisionOutcome.ok("C");
    });

    filler.fill(3, VERSION, NOW);

    assertEquals(1, names.size());
  }

  @Test
  public void retiresStaleAndExpiredRowsBeforeCounting() {
    FakeTenantPoolStore store = new FakeTenantPoolStore();
    store.ready = 3;
    store.retiredStale = 2;
    store.expired = 1;
    TenantPoolFiller filler = new TenantPoolFiller(store,
        name -> TenantPoolFiller.ProvisionOutcome.ok("C"));

    TenantPoolFiller.FillReport report = filler.fill(3, VERSION, NOW);

    assertEquals(3, report.retired());
    assertTrue(store.calls.indexOf("retireStale:" + VERSION)
        < store.calls.indexOf("countReady:" + VERSION));
    assertTrue(store.calls.indexOf("expireProvisioning")
        < store.calls.indexOf("countReady:" + VERSION));
  }

  @Test
  public void failureMarksTheRowFailedAfterRollbackAndStopsTheRun() {
    FakeTenantPoolStore store = new FakeTenantPoolStore();
    List<String> names = new ArrayList<>();
    TenantPoolFiller filler = new TenantPoolFiller(store, name -> {
      names.add(name);
      return TenantPoolFiller.ProvisionOutcome.failed("HALF-BUILT", "dataset: broken import");
    });

    TenantPoolFiller.FillReport report = filler.fill(3, VERSION, NOW);

    assertEquals(1, names.size());
    assertEquals(0, report.created());
    assertEquals(1, report.failed());
    assertEquals(TenantPoolStore.STATUS_FAILED, store.statusByRow.get("ROW1"));
    assertEquals("HALF-BUILT", store.clientByRow.get("ROW1"));
    assertEquals("dataset: broken import", store.errorByRow.get("ROW1"));
    assertTrue(store.calls.indexOf("rollback") < store.calls.indexOf("failed:ROW1"));
    assertEquals("commit", store.calls.get(store.calls.size() - 1));
  }

  @Test
  public void aFailedRunDoesNotBlockTheNextOne() {
    FakeTenantPoolStore store = new FakeTenantPoolStore();
    new TenantPoolFiller(store,
        name -> TenantPoolFiller.ProvisionOutcome.failed(null, "boom")).fill(1, VERSION, NOW);

    TenantPoolFiller.FillReport second = new TenantPoolFiller(store,
        name -> TenantPoolFiller.ProvisionOutcome.ok("C")).fill(1, VERSION, NOW);

    assertEquals(1, second.created());
    assertEquals(TenantPoolStore.STATUS_FAILED, store.statusByRow.get("ROW1"));
    assertEquals(TenantPoolStore.STATUS_READY, store.statusByRow.get("ROW2"));
  }

  @Test
  public void aThrowingProvisionerIsRecordedAsFailedNotLeftProvisioning() {
    FakeTenantPoolStore store = new FakeTenantPoolStore();
    TenantPoolFiller filler = new TenantPoolFiller(store, name -> {
      throw new IllegalStateException("unexpected");
    });

    TenantPoolFiller.FillReport report = filler.fill(1, VERSION, NOW);

    assertEquals(1, report.failed());
    assertEquals(TenantPoolStore.STATUS_FAILED, store.statusByRow.get("ROW1"));
    assertEquals("unexpected", store.errorByRow.get("ROW1"));
  }

  @Test
  public void aSecondConcurrentFillIsSkipped() throws Exception {
    FakeTenantPoolStore store = new FakeTenantPoolStore();
    CountDownLatch provisioning = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    TenantPoolFiller slow = new TenantPoolFiller(store, name -> {
      provisioning.countDown();
      try {
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return TenantPoolFiller.ProvisionOutcome.ok("C");
    });
    AtomicReference<TenantPoolFiller.FillReport> first = new AtomicReference<>();
    Thread runner = new Thread(() -> first.set(slow.fill(1, VERSION, NOW)));
    runner.start();
    assertTrue(provisioning.await(10, TimeUnit.SECONDS));

    TenantPoolFiller.FillReport second = new TenantPoolFiller(new FakeTenantPoolStore(),
        name -> {
          throw new AssertionError("must not provision while another fill runs");
        }).fill(1, VERSION, NOW);

    release.countDown();
    runner.join(10_000);
    assertTrue(second.skipped());
    assertFalse(first.get().skipped());
    assertEquals(1, first.get().created());
  }
}
