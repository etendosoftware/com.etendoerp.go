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

import org.openbravo.dal.core.OBContext;

/**
 * ETP-5051 — the quota check of the NEO write path, structural and driven by catalog data only:
 * no entity or spec name appears here or in its callers.
 *
 * <p>For a row of entity {@code E} created by a request of client {@code C}:
 * <ol>
 *   <li>{@code C}'s entry in the {@link QuotaClientCache} names the {@code block} quotas of
 *       {@code C}'s plan whose declarative resource counts {@code E} — none, and the check ends
 *       with no query at all, whatever other clients' plans cap;</li>
 *   <li>each such resource's HQL restriction is tested against the new row
 *       ({@link CreatedRowMatcher}), which also names the row's tenant;</li>
 *   <li>the {@link QuotaEvaluator} takes the lock (live), reads the consumption and decides.</li>
 * </ol>
 * The client is the request's {@link OBContext} client. The cache entry is built on the request's
 * own session; a failure there propagates to the caller, which fails the create closed.
 *
 * <p><b>What it cannot see:</b> a resource that grows through a state change rather than a create
 * — {@code POSTED_SALES_INVOICES} grows when an invoice is posted, and the row is created
 * unposted — does not match its restriction at create time, so it is never refused here. Accepted
 * (Martin Taal, 2026-10-24); such a quota is reported at login and on the account page.
 */
public class QuotaWriteGuard {

  private final QuotaClientCache cache;
  private final CreatedRowMatcher matcher;
  private final QuotaEvaluator evaluator;
  private final Supplier<String> currentClient;

  /**
   * A guard over explicit seams.
   *
   * @param cache each client's block quotas, by counted entity
   * @param matcher tests a created row against a resource's restriction
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
    private static final QuotaWriteGuard INSTANCE = new QuotaWriteGuard(
        new QuotaClientCache(new DalQuotaLookup(), System::currentTimeMillis),
        CreatedRowMatcher.dal(), new QuotaEvaluator(), QuotaWriteGuard::contextClient);
  }

  /** @return the client of the request's {@link OBContext}, or null */
  static String contextClient() {
    OBContext context = OBContext.getOBContext();
    return context == null || context.getCurrentClient() == null ? null
        : context.getCurrentClient().getId();
  }

  /**
   * Whether this request's creates of this entity must be checked. A map lookup, plus building the
   * client's entry on the request's session once per {@link QuotaClientCache#TTL_MS}. Callers ask
   * before the insert, to decide how to run it.
   *
   * @param entityName the DAL entity name
   * @return {@code true} when a {@code block} quota of the request's client counts this entity
   * @throws RuntimeException when the client's entry could not be built
   */
  public boolean appliesTo(String entityName) {
    String clientId = currentClient.get();
    return clientId != null && !cache.entryFor(clientId).blockQuotasFor(entityName).isEmpty();
  }

  /**
   * Checks created rows, after the insert and before the commit.
   *
   * @param entityName the DAL entity name of the rows
   * @param recordIds the ids of the created rows
   * @return the first DENY evaluation, or empty when every row may stand
   */
  public Optional<QuotaEvaluation> refusalFor(String entityName, Collection<String> recordIds) {
    String clientId = currentClient.get();
    if (clientId == null || recordIds == null || recordIds.isEmpty()) {
      return Optional.empty();
    }
    QuotaClientCache.Entry entry = cache.entryFor(clientId);
    List<QuotaDefinition> quotas = entry.blockQuotasFor(entityName);
    for (String recordId : recordIds) {
      for (QuotaDefinition quota : quotas) {
        String rowClient = matcher.tenantIfCounted(quota, recordId);
        if (rowClient == null) {
          continue;
        }
        // A row created into another client is measured against that client's own quotas.
        TenantQuotas tenant = rowClient.equals(clientId) ? entry.getTenant()
            : cache.entryFor(rowClient).getTenant();
        Optional<QuotaEvaluation> refusal =
            evaluator.refusalForCreate(tenant, quota.getResourceId());
        if (refusal.isPresent()) {
          return refusal;
        }
      }
    }
    return Optional.empty();
  }
}
