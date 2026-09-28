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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Tops the tenant pool up to its configured size (ETP-5389).
 *
 * <p>One run: retire READY tenants built by another provisioning version or past their maximum
 * age, fail PROVISIONING rows whose lease expired (a JVM that died mid-run), then provision the
 * deficit one tenant at a time. Every pool row write is committed on its own, so a tenant that
 * failed half-way is recorded as FAILED and never blocks the next run. The run stops at the first
 * failure: a systematic problem costs one failed tenant per run, not one per missing slot.
 *
 * <p>Never two at once. Within a JVM a static guard answers a second concurrent call with
 * {@link FillReport#skipped()}; across the cluster the filler's {@code AD_Process} is declared
 * {@code PREVENTCONCURRENT='Y'}, which is what stops two scheduler nodes from both running it.
 * Rows still PROVISIONING count against the deficit for the same reason.
 */
public class TenantPoolFiller {

  private static final Logger log = LogManager.getLogger(TenantPoolFiller.class);
  private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

  /** Builds one pooled tenant. */
  @FunctionalInterface
  public interface Provisioner {
    /**
     * Provisions and commits one tenant named {@code placeholderName}. Never throws: failures come
     * back as an unsuccessful outcome, carrying the client id when one was already created.
     *
     * @param placeholderName placeholder client name
     * @return the provisioning outcome
     */
    ProvisionOutcome provision(String placeholderName);
  }

  /** What one provisioning attempt produced. */
  public record ProvisionOutcome(boolean success, String clientId, String error) {
    /**
     * Creates a successful provisioning outcome.
     *
     * @param clientId id of the provisioned client
     * @return successful provisioning outcome
     */
    public static ProvisionOutcome ok(String clientId) {
      return new ProvisionOutcome(true, clientId, null);
    }

    /**
     * Creates a failed provisioning outcome.
     *
     * @param clientId id of a partially provisioned client, if available
     * @param error failure description
     * @return failed provisioning outcome
     */
    public static ProvisionOutcome failed(String clientId, String error) {
      return new ProvisionOutcome(false, clientId, error);
    }
  }

  /** What one fill run did. */
  public record FillReport(boolean skipped, int readyBefore, int created, int failed,
      int retired) {
    static FillReport skippedRun() {
      return new FillReport(true, 0, 0, 0, 0);
    }

    @Override
    public String toString() {
      return skipped ? "skipped (a fill is already running)"
          : "ready before=" + readyBefore + ", created=" + created + ", failed=" + failed
              + ", retired=" + retired;
    }
  }

  private final TenantPoolStore store;
  private final Provisioner provisioner;

  /**
   * Creates a filler backed by the supplied store and provisioner.
   *
   * @param store persistence service for pool rows
   * @param provisioner service that creates and configures one tenant
   */
  public TenantPoolFiller(TenantPoolStore store, Provisioner provisioner) {
    this.store = store;
    this.provisioner = provisioner;
  }

  /**
   * Fills the pool until the requested number of READY tenants is available.
   *
   * @param targetSize how many READY tenants to keep
   * @param version the provisioning version tenants are built with now
   * @param now the current instant
   * @return what the run did
   */
  public FillReport fill(int targetSize, String version, Instant now) {
    if (!RUNNING.compareAndSet(false, true)) {
      log.info("Tenant pool fill skipped: another fill is still running in this JVM");
      return FillReport.skippedRun();
    }
    try {
      int retired = store.retireStale(version,
          now.minus(Duration.ofHours(TenantPoolConfig.maxAgeHours())));
      retired += store.expireProvisioning(
          now.minus(Duration.ofMinutes(TenantPoolConfig.leaseMinutes())));
      int ready = store.countReady(version);
      int inFlight = store.countProvisioning();
      store.commit();
      int deficit = targetSize - ready - inFlight;
      int created = 0;
      int failed = 0;
      for (int i = 0; i < deficit; i++) {
        if (provisionOne(version)) {
          created++;
        } else {
          failed++;
          break;
        }
      }
      FillReport report = new FillReport(false, ready, created, failed, retired);
      log.info("Tenant pool fill: {}", report);
      return report;
    } finally {
      RUNNING.set(false);
    }
  }

  private boolean provisionOne(String version) {
    String poolRowId = store.insertProvisioning(version);
    store.commit();
    ProvisionOutcome outcome;
    try {
      outcome = provisioner.provision(TenantPoolConfig.PLACEHOLDER_PREFIX + poolRowId);
    } catch (RuntimeException e) {
      // The provisioner contract says it does not throw; a pool row must still never be left
      // PROVISIONING because one did.
      log.error("Tenant pool provisioner threw for pool row {}", poolRowId, e);
      outcome = ProvisionOutcome.failed(null, e.getMessage());
    }
    if (outcome.success()) {
      store.markReady(poolRowId, outcome.clientId());
      store.commit();
      return true;
    }
    store.rollback();
    store.markFailed(poolRowId, outcome.clientId(), outcome.error());
    store.commit();
    log.warn("Tenant pool row {} failed (client {}): {}", poolRowId, outcome.clientId(),
        outcome.error());
    return false;
  }
}
