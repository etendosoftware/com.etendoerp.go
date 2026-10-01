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

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.client.kernel.ComponentProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.roles.TemplateAccessPropagationService;
import com.etendoerp.go.roles.TemplateAccessPropagationService.ProcessGrantDiagnostic;
import com.etendoerp.go.roles.TemplateAccessPropagationService.SweepCounts;
import com.etendoerp.go.roles.UserRoleWriteLock;

/**
 * ETP-5565 — keeps the personal roles aligned with the system template roles they inherit, on
 * the live database: at startup and then every {@value #PERIOD_MINUTES} minutes.
 *
 * <p><b>Why.</b> {@code EnsureSystemRoleTemplatesScript} maintains the four templates with plain
 * JDBC, so core's inheritance propagation (Hibernate events only) never copies a template change
 * to the personal roles composed before it. On production the script runs on a deploy clone and
 * the delta carries system-client rows only, so personal roles can only be realigned here. The
 * deploy applies the delta before the new tasks start, so the first startup of a release already
 * sees the new templates.</p>
 *
 * <p><b>Each tick</b> (startup included):
 * <ol>
 *   <li>Fingerprint every template (one query) and compare with {@code ETGO_TPL_ROLE_SYNC}. Also
 *       look for personal roles with inactive or source-less inherited copies (left by a
 *       composition that bypassed the Etendo GO hook, e.g. Etendo Classic's Role window) and for
 *       inactive template rows past the purge grace period. Nothing to do → close the read
 *       transaction and return. This is the steady state: a few cheap queries, no writes.</li>
 *   <li>Otherwise take the lease ({@link TemplateRoleSyncStore}); if another task holds it,
 *       return. Re-read under the lease, since that task may have just finished.</li>
 *   <li>Purge the templates' inactive rows older than {@value #PURGE_GRACE_DAYS} days.</li>
 *   <li>Sweep the personal roles that inherit a changed template plus the roles with stale
 *       copies, {@value #CHUNK_SIZE} per transaction, each chunk holding its owners' {@link
 *       UserRoleWriteLock}s (taken in id order) so it never races a role save. A failed chunk is
 *       logged and skipped; the others still run.</li>
 *   <li>Only if every chunk succeeded, store the new fingerprints, so a failure is retried by the
 *       next tick. Release the lease (also on failure, so the retry can take it).</li>
 *   <li>When fingerprints changed, log the classification of the changed templates' process
 *       grants ({@link TemplateAccessPropagationService#diagnoseProcessGrants}); readable on
 *       production through the ECS log group.</li>
 * </ol>
 * The first startup after this class ships finds the table empty, treats every template as
 * changed and heals all existing drift: no data-fix needed.</p>
 *
 * <p>Never blocks or breaks startup: the first tick runs on {@link SessionAwareStartup}'s daemon
 * worker, later ticks on a single daemon scheduler thread, and every failure is logged and rolled
 * back. Every tick closes its transaction on every path, so no pooled connection is left idle in
 * transaction between ticks.</p>
 */
@ApplicationScoped
@ComponentProvider.Qualifier(TemplateRoleAccessStartup.QUALIFIER)
public class TemplateRoleAccessStartup extends SessionAwareStartup {

  static final String QUALIFIER = "com.etendoerp.go.startup.TemplateRoleAccessStartup";

  static final long PERIOD_MINUTES = 10L;
  static final int CHUNK_SIZE = 100;
  static final int PURGE_GRACE_DAYS = 7;

  private static final Logger log = LogManager.getLogger(TemplateRoleAccessStartup.class);
  private static final String NAME = "TemplateRoleAccessStartup";

  private final TemplateAccessPropagationService propagation;
  private final TemplateRoleSyncStore store;
  private final UserRoleWriteLock writeLock;
  private final String holder;
  private ScheduledExecutorService scheduler;

  /** What one tick did, for the summary log line and the tests. */
  static final class TickResult {
    private boolean leaseBusy;
    private int changedTemplates;
    private int rolesSwept;
    private int failedChunks;
    private int purged;
    private final SweepCounts counts = new SweepCounts();

    boolean isLeaseBusy() {
      return leaseBusy;
    }

    int getChangedTemplates() {
      return changedTemplates;
    }

    int getRolesSwept() {
      return rolesSwept;
    }

    int getFailedChunks() {
      return failedChunks;
    }

    int getPurged() {
      return purged;
    }

    SweepCounts getCounts() {
      return counts;
    }

    boolean didWork() {
      return changedTemplates > 0 || rolesSwept > 0 || purged > 0;
    }
  }

  public TemplateRoleAccessStartup() {
    this(new TemplateAccessPropagationService(), new TemplateRoleSyncStore(),
        new UserRoleWriteLock(), ManagementFactory.getRuntimeMXBean().getName() + "/"
            + UUID.randomUUID().toString().substring(0, 8));
  }

  /** Test seam. */
  TemplateRoleAccessStartup(TemplateAccessPropagationService propagation,
      TemplateRoleSyncStore store, UserRoleWriteLock writeLock, String holder) {
    this.propagation = propagation;
    this.store = store;
    this.writeLock = writeLock;
    this.holder = holder;
  }

  @Override
  protected Logger log() {
    return log;
  }

  @Override
  protected String name() {
    return NAME;
  }

  @Override
  protected void runPass() {
    try {
      tickSafely(true);
    } finally {
      schedulePeriodicTicks();
    }
  }

  private synchronized void schedulePeriodicTicks() {
    if (scheduler != null) {
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, NAME + "-tick");
      thread.setDaemon(true);
      return thread;
    });
    scheduler.scheduleWithFixedDelay(() -> tickSafely(false), PERIOD_MINUTES, PERIOD_MINUTES,
        TimeUnit.MINUTES);
  }

  /**
   * Stops the periodic ticks when the application shuts down or the webapp is reloaded, so the
   * scheduler thread does not outlive its classloader.
   */
  @PreDestroy
  synchronized void shutdown() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
  }

  /** Runs one tick and never throws: a failed tick is logged, rolled back and retried later. */
  void tickSafely(boolean startup) {
    try {
      tick(startup);
    } catch (Exception e) {
      log.error("{}: template role propagation failed; retrying in {} min.", NAME,
          PERIOD_MINUTES, e);
      try {
        OBDal.getInstance().rollbackAndClose();
      } catch (Exception rollbackError) {
        log.debug("{}: rollback after failure also failed.", NAME, rollbackError);
      }
    }
  }

  /** Visible for testing: one tick, see the class javadoc. */
  TickResult tick(boolean startup) {
    long startMs = System.currentTimeMillis();
    TickResult result = new TickResult();
    OBContext.setAdminMode(true);
    try {
      boolean hasWork;
      try {
        hasWork = !changedTemplates(propagation.fingerprints()).isEmpty()
            || !propagation.rolesWithStaleCopies().isEmpty()
            || propagation.hasPurgeableTemplateRows(PURGE_GRACE_DAYS);
      } finally {
        OBDal.getInstance().commitAndClose();
      }
      if (!hasWork) {
        if (startup) {
          log.info("{}: templates unchanged, personal roles in sync ({} ms).", NAME,
              System.currentTimeMillis() - startMs);
        }
        return result;
      }
      if (!store.acquireLease(holder)) {
        result.leaseBusy = true;
        log.info("{}: template role propagation running in another task; skipping.", NAME);
        return result;
      }
      String error = null;
      try {
        work(result);
      } catch (RuntimeException e) {
        OBDal.getInstance().rollbackAndClose();
        error = e.getClass().getSimpleName() + ": " + e.getMessage();
        throw e;
      } finally {
        if (error == null && result.failedChunks > 0) {
          error = result.failedChunks + " sweep chunk(s) failed; see the application log";
        }
        store.releaseLease(holder, error);
      }
      log.info("{}: {} template(s) changed, {} personal role(s) swept ({} failed chunk(s)), "
          + "rows removed/updated/inserted {}/{}/{}, {} inactive template row(s) purged, in {} ms.",
          NAME, result.changedTemplates, result.rolesSwept, result.failedChunks,
          result.counts.getRemoved(), result.counts.getUpdated(), result.counts.getInserted(),
          result.purged, System.currentTimeMillis() - startMs);
      return result;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** The tick's work, under the lease. Commits per step. */
  private void work(TickResult result) {
    if (propagation.hasPurgeableTemplateRows(PURGE_GRACE_DAYS)) {
      result.purged = propagation.purgeInactiveTemplateRows(PURGE_GRACE_DAYS);
    }
    Map<String, String> fingerprints = propagation.fingerprints();
    Set<String> changed = changedTemplates(fingerprints);
    Set<String> roles = new LinkedHashSet<>(propagation.rolesInheriting(changed));
    roles.addAll(propagation.rolesWithStaleCopies());
    Map<String, Integer> inheritorsByTemplate = new LinkedHashMap<>();
    for (String templateId : changed) {
      inheritorsByTemplate.put(templateId,
          propagation.rolesInheriting(List.of(templateId)).size());
    }
    if (!changed.isEmpty()) {
      logDiagnostic(changed);
    }
    OBDal.getInstance().commitAndClose();
    result.changedTemplates = changed.size();

    sweep(new ArrayList<>(roles), result);

    if (!changed.isEmpty() && result.failedChunks == 0) {
      for (String templateId : changed) {
        store.storeFingerprint(templateId, fingerprints.get(templateId),
            TemplateAccessPropagationService.ALGO_VERSION, inheritorsByTemplate.get(templateId),
            result.counts.total());
      }
      OBDal.getInstance().commitAndClose();
    } else if (!changed.isEmpty()) {
      log.warn("{}: {} sweep chunk(s) failed; fingerprints not advanced, the next tick retries.",
          NAME, result.failedChunks);
    }
  }

  private void sweep(List<String> roles, TickResult result) {
    for (int start = 0; start < roles.size(); start += CHUNK_SIZE) {
      List<String> chunk = roles.subList(start, Math.min(roles.size(), start + CHUNK_SIZE));
      if (!store.renewLease(holder)) {
        throw new IllegalStateException("Lease lost to another task mid-sweep");
      }
      try {
        for (String ownerId : propagation.ownersOf(chunk)) {
          writeLock.acquire(ownerId);
        }
        result.counts.add(propagation.sweepRoles(chunk));
        OBDal.getInstance().commitAndClose();
        result.rolesSwept += chunk.size();
      } catch (RuntimeException e) {
        OBDal.getInstance().rollbackAndClose();
        result.failedChunks++;
        log.warn("{}: sweep of {} personal role(s) starting at {} failed; continuing.", NAME,
            chunk.size(), chunk.get(0), e);
      }
    }
  }

  /**
   * Templates whose fingerprint differs from the stored one, has none stored, or was stored by an
   * older rule version. A fingerprint stored by a NEWER version counts as unchanged: that task
   * owns it.
   */
  private Set<String> changedTemplates(Map<String, String> fingerprints) {
    Map<String, TemplateRoleSyncStore.Stored> stored = store.readFingerprints();
    Set<String> changed = new LinkedHashSet<>();
    for (Map.Entry<String, String> entry : fingerprints.entrySet()) {
      TemplateRoleSyncStore.Stored previous = stored.get(entry.getKey());
      if (previous == null
          || previous.getAlgoVersion() < TemplateAccessPropagationService.ALGO_VERSION
          || (previous.getAlgoVersion() == TemplateAccessPropagationService.ALGO_VERSION
              && !entry.getValue().equals(previous.getFingerprint()))) {
        changed.add(entry.getKey());
      }
    }
    return changed;
  }

  /**
   * One INFO line per changed template with its process grants per category, plus one line per
   * grant outside "button on a full window" and "standalone in code" (expected: none). Read-only.
   */
  private void logDiagnostic(Collection<String> templateIds) {
    Map<String, Map<String, Integer>> countsByTemplate = new LinkedHashMap<>();
    for (String templateId : templateIds) {
      Map<String, Integer> counts = new LinkedHashMap<>();
      counts.put(ProcessGrantDiagnostic.BUTTON_ON_FULL_WINDOW, 0);
      counts.put(ProcessGrantDiagnostic.STANDALONE, 0);
      counts.put(ProcessGrantDiagnostic.BUTTON_ON_READ_ONLY_WINDOW, 0);
      counts.put(ProcessGrantDiagnostic.UNEXPLAINED, 0);
      countsByTemplate.put(templateId, counts);
    }
    for (ProcessGrantDiagnostic grant : propagation.diagnoseProcessGrants(templateIds)) {
      countsByTemplate.get(grant.getTemplateId()).merge(grant.getCategory(), 1, Integer::sum);
      if (ProcessGrantDiagnostic.UNEXPLAINED.equals(grant.getCategory())
          || ProcessGrantDiagnostic.BUTTON_ON_READ_ONLY_WINDOW.equals(grant.getCategory())) {
        log.info("{}: {} {} grant on template {}: {} ({})", NAME, grant.getCategory(),
            grant.getKind(), grant.getTemplateId(), grant.getProcessId(), grant.getProcessName());
      }
    }
    for (Map.Entry<String, Map<String, Integer>> entry : countsByTemplate.entrySet()) {
      log.info("{}: process grants of template {}: {}", NAME, entry.getKey(), entry.getValue());
    }
  }
}
