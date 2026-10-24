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

package com.etendoerp.go.usage.quota;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;

/**
 * ETP-5051 — the quota check of the NEO write path, structural and driven by catalog data only:
 * no entity or spec name appears here or in its callers.
 *
 * <p>For a row of entity {@code E} created by a request of client {@code C}:
 * <ol>
 *   <li>{@code C}'s entry in the {@link QuotaClientCache} names the {@code block} quotas of
 *       {@code C}'s plan whose declarative resource counts {@code E} — none, and the check ends
 *       with no query at all, whatever other clients' plans cap. The entry is read once, before
 *       the insert ({@link #entryIfChecked}), and the same entry is checked after it;</li>
 *   <li>each such resource's HQL restriction and the current period's day bounds are tested
 *       against the new row ({@link CreatedRowMatcher}): a row dated outside the counted window
 *       does not consume this period and is let through;</li>
 *   <li>the {@link QuotaEvaluator} takes the lock (live), reads the consumption before the create
 *       and decides.</li>
 * </ol>
 * The client is the request's {@link OBContext} client. The cache entry is built on the request's
 * own session; a failure there propagates to the caller, which fails the create closed.
 *
 * <p><b>What it cannot see:</b> a resource that grows through a state change rather than a create
 * — {@code POSTED_SALES_INVOICES} grows when an invoice is posted, and the row is created
 * unposted — does not match its restriction at create time, so it is never refused here. Accepted
 * (Martin Taal, 2026-10-24); such a quota is reported on environment entry, on the environment
 * list and on the account page.
 */
public class QuotaWriteGuard {

  private static final Logger log = LogManager.getLogger(QuotaWriteGuard.class);

  private final QuotaClientCache cache;
  private final CreatedRowMatcher matcher;
  private final QuotaEvaluator evaluator;
  private final Supplier<String> currentClient;

  /**
   * A guard over explicit seams.
   *
   * @param cache each client's block quotas, by counted entity
   * @param matcher tests a created row against a resource's restriction and the period
   * @param evaluator decides
   * @param currentClient the client of the running request, or null when there is none
   */
  public QuotaWriteGuard(QuotaClientCache cache, CreatedRowMatcher matcher,
      QuotaEvaluator evaluator, Supplier<String> currentClient) {
    this.cache = cache;
    this.matcher = matcher;
    this.evaluator = evaluator;
    this.currentClient = currentClient;
  }

  /**
   * The shared guard; its cache is per JVM.
   *
   * @return the shared instance
   */
  public static QuotaWriteGuard getDefault() {
    return DefaultHolder.INSTANCE;
  }

  /** Built on first use, so loading this class wires no service. */
  private static final class DefaultHolder {
    // Monotonic: a wall-clock step back must not keep an entry alive, nor a step forward drop all.
    private static final QuotaWriteGuard INSTANCE = new QuotaWriteGuard(
        new QuotaClientCache(new DalQuotaLookup(), () -> System.nanoTime() / 1_000_000L),
        CreatedRowMatcher.dal(), new QuotaEvaluator(), QuotaWriteGuard::contextClient);
  }

  /** @return the client of the request's {@link OBContext}, or null */
  static String contextClient() {
    OBContext context = OBContext.getOBContext();
    return context == null || context.getCurrentClient() == null ? null
        : context.getCurrentClient().getId();
  }

  /**
   * The request's client's entry when its creates of this entity must be checked. A map lookup,
   * plus building the entry on the request's session once per {@link QuotaClientCache#TTL_MS}.
   * Callers ask before the insert, to decide how to run it, and hand the entry back to
   * {@link #refusalFor} after it.
   *
   * @param entityName the DAL entity name
   * @return the entry when a {@code block} quota of the request's client counts this entity, else
   *     empty
   * @throws RuntimeException when the client's entry could not be built
   */
  public Optional<QuotaClientCache.Entry> entryIfChecked(String entityName) {
    String clientId = currentClient.get();
    if (clientId == null) {
      return Optional.empty();
    }
    QuotaClientCache.Entry entry = cache.entryFor(clientId);
    return entry.blockQuotasFor(entityName).isEmpty() ? Optional.empty() : Optional.of(entry);
  }

  /**
   * Checks created rows, after the insert and before the commit, against the entry read before it.
   *
   * @param entry the request's client's entry, from {@link #entryIfChecked}
   * @param entityName the DAL entity name of the rows
   * @param recordIds the ids of the created rows
   * @return the first DENY evaluation, or empty when every row may stand
   */
  public Optional<QuotaEvaluation> refusalFor(QuotaClientCache.Entry entry, String entityName,
      Collection<String> recordIds) {
    if (entry == null || recordIds == null || recordIds.isEmpty()) {
      return Optional.empty();
    }
    List<QuotaDefinition> quotas = entry.blockQuotasFor(entityName);
    TenantQuotas tenant = entry.getTenant();
    QuotaPeriod period = quotas.isEmpty() ? null : evaluator.writeCheckPeriod(tenant);
    if (period == null) {
      return Optional.empty();
    }
    for (QuotaDefinition quota : quotas) {
      Optional<QuotaEvaluation> refusal = refusalForQuota(tenant, quota, period, entityName,
          recordIds);
      if (refusal.isPresent()) {
        return refusal;
      }
    }
    return Optional.empty();
  }

  /**
   * The created rows against one quota, charged one after the other on top of the consumption
   * before the create. That consumption is read once for the quota — lazily, on the first row the
   * quota counts in the window, so a create none of whose rows count (a backdated row) reads none.
   */
  private Optional<QuotaEvaluation> refusalForQuota(TenantQuotas tenant, QuotaDefinition quota,
      QuotaPeriod period, String entityName, Collection<String> recordIds) {
    Long before = null;
    long charged = 0L;
    for (String recordId : recordIds) {
      if (countsInWindow(tenant, quota, period, entityName, recordId)) {
        if (before == null) {
          before = evaluator.consumptionBeforeCreate(tenant, quota, period, recordIds);
        }
        Optional<QuotaEvaluation> refusal =
            evaluator.refusalForCreate(tenant, quota, period, before + charged);
        if (refusal.isPresent()) {
          return refusal;
        }
        charged++;
      }
    }
    return Optional.empty();
  }

  /** Whether the quota's resource counts the row in the period, for the request's own client. */
  private boolean countsInWindow(TenantQuotas tenant, QuotaDefinition quota, QuotaPeriod period,
      String entityName, String recordId) {
    String rowClient = matcher.tenantIfCounted(quota, recordId, period);
    if (rowClient == null) {
      return false;
    }
    if (rowClient.equals(tenant.getClientId())) {
      return true;
    }
    // NEO creates rows in the request's client; another client here is not ours to measure.
    log.warn("[QUOTA] A created {} row belongs to client {}, not to the request's client {}; not "
        + "quota-checked", entityName, rowClient, tenant.getClientId());
    return false;
  }
}
