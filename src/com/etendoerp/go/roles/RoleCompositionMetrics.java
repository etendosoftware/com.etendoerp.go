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
package com.etendoerp.go.roles;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * ETP-5503 — counters and stage timings for ONE {@link
 * RoleInheritanceReconciliationService#reconcileInheritances} call, reported as a single INFO
 * summary line instead of one INFO line per access row.
 *
 * <p>The overlap guards run as CDI event observers, deep inside core's own propagation, so they
 * cannot return anything to the service that triggered them. They report into the instance bound
 * to the current thread instead (one request's transaction per thread, the same assumption {@code
 * TemplateRemovalTracker} relies on). With no instance bound — a guard fired from any other path,
 * e.g. an admin editing a template in Etendo Classic — every static call is a no-op.
 *
 * <p>Deliberately NOT Hibernate {@code Statistics}: those counters are off by default and are
 * SessionFactory-global, so turning them on in production costs every request and a per-call
 * delta would include other users' concurrent statements.
 */
final class RoleCompositionMetrics implements AutoCloseable {

  private static final ThreadLocal<RoleCompositionMetrics> CURRENT = new ThreadLocal<>();

  private final long startNanos = System.nanoTime();
  private final Map<String, Long> stageNanos = new LinkedHashMap<>();
  private int prevented;
  private int skipped;
  private int copied;
  private int widened;
  private int repointed;

  private RoleCompositionMetrics() {
    // created only through start(), which binds it to the current thread
  }

  /**
   * Binds a fresh instance to the current thread, replacing any leftover one. Use it in a
   * try-with-resources block so {@link #close()} always unbinds it.
   */
  static RoleCompositionMetrics start() {
    RoleCompositionMetrics metrics = new RoleCompositionMetrics();
    CURRENT.set(metrics);
    return metrics;
  }

  /** Rows the add-path guard deleted so core recreates them through its CREATE path. */
  static void addPrevented(int count) {
    RoleCompositionMetrics metrics = CURRENT.get();
    if (metrics != null) {
      metrics.prevented += count;
    }
  }

  /**
   * Rows the add-path guard left in place because a higher-precedence template of the same call
   * already sourced them (ETP-5507, {@code HigherPrecedenceSkip}).
   */
  static void addSkipped(int count) {
    RoleCompositionMetrics metrics = CURRENT.get();
    if (metrics != null) {
      metrics.skipped += count;
    }
  }

  /** One template-derived row created on a non-template role. */
  static void addCopied() {
    RoleCompositionMetrics metrics = CURRENT.get();
    if (metrics != null) {
      metrics.copied++;
    }
  }

  /** One freshly-created row widened to full access (most-permissive-wins). */
  static void addWidened() {
    RoleCompositionMetrics metrics = CURRENT.get();
    if (metrics != null) {
      metrics.widened++;
    }
  }

  /** One existing row corrected in place (remove path or template grant update). */
  static void addRepointed() {
    RoleCompositionMetrics metrics = CURRENT.get();
    if (metrics != null) {
      metrics.repointed++;
    }
  }

  /** Adds {@code nanos} to the named stage of the instance bound to this thread, if any. */
  static void addStageTime(String stage, long nanos) {
    RoleCompositionMetrics metrics = CURRENT.get();
    if (metrics != null) {
      metrics.stageNanos.merge(stage, nanos, Long::sum);
    }
  }

  int getPrevented() {
    return prevented;
  }

  int getSkipped() {
    return skipped;
  }

  int getCopied() {
    return copied;
  }

  /**
   * One-line summary: counters, then every recorded stage in milliseconds, then the total since
   * {@link #start()}. Stages can nest (the guards' {@code guard} time is part of {@code save}), so
   * they do not add up to the total.
   */
  String summary() {
    StringJoiner stages = new StringJoiner(", ", "{", "}");
    for (Map.Entry<String, Long> stage : stageNanos.entrySet()) {
      stages.add(stage.getKey() + "=" + toMillis(stage.getValue()));
    }
    return "prevented=" + prevented + " skipped=" + skipped + " copied=" + copied
        + " widened=" + widened + " repointed=" + repointed + " stagesMs=" + stages + " totalMs="
        + toMillis(System.nanoTime() - startNanos);
  }

  private static long toMillis(long nanos) {
    return nanos / 1_000_000L;
  }

  @Override
  public void close() {
    if (CURRENT.get() == this) {
      CURRENT.remove();
    }
  }
}
