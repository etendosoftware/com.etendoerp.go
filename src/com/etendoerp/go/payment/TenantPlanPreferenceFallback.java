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

package com.etendoerp.go.payment;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

/**
 * TRANSITIONAL — ETP-5046 cutover only. Delete in Phase F together with
 * {@link TenantPlanService#markProductive} and {@link TenantPlanService#PREFERENCE_ATTRIBUTE}.
 *
 * <p>Grep marker for the whole deletion: <b>{@code ETP-5046-TRANSITIONAL-FALLBACK}</b>. Every
 * piece of this fallback — this class, its call sites in {@link TenantPlanService} and
 * {@link EnvironmentPlanCache}, and its specs — carries that string, so one grep finds all of it.
 *
 * <h3>Why this exists</h3>
 * ETP-5046 moved the source of truth for "is this tenant paying" from the retired
 * {@code ETGO_TenantPlan} {@code AD_Preference} marker to the tenant's open
 * {@code ETGO_SUBSCRIPTION} row. The preference is still <em>written</em> by
 * {@link TenantPlanService#markProductive}, but it is no longer the thing that is read. Between
 * deploying that change and running the R37 backfill data-fix, every tenant provisioned before the
 * deploy has a preference and no subscription row, so it would resolve to
 * {@link TenantPlanService#PLAN_FREE} — paid features off, wrong environment ordering, and, once
 * ETP-5047's enforcement lands, denied login.
 *
 * <p>The backfill cannot simply be made automatic: it is deliberately gated on a human
 * re-verifying that production Stripe checkout has not gone live, because if it has, a real paying
 * cohort exists that the backfill would orphan. So the read side tolerates the gap instead, and
 * this class is that tolerance.
 *
 * <h3>The preference is NOT scoped by {@code AD_CLIENT_ID}</h3>
 * {@code Preferences.setPreferenceValue} stores the row at {@code AD_CLIENT_ID = '0'} and encodes
 * the tenant it is about in <b>{@code VISIBLEAT_CLIENT_ID}</b> only. Filtering on
 * {@code AD_CLIENT_ID} therefore matches <em>zero</em> rows for every tenant — a mistake that
 * nearly shipped in the R37 backfill SQL. The queries below match
 * {@link Preference#PROPERTY_VISIBLEATCLIENT}, exactly as the pre-ETP-5046
 * {@code TenantPlanService#resolvePlan} did, and a spec pins that column literally.
 *
 * <h3>Observability is the point</h3>
 * Every resolution served by this fallback is logged at {@code WARN}, naming the tenant. A silent
 * fallback would let the backfill be forgotten forever; the log is the signal that says when it is
 * safe to delete this class — when the line stops appearing, every tenant has a subscription row.
 * One line per resolution, never per comparison: the environment list reads a tenant's plan many
 * times per render, so the log lives at query time, not at read time.
 */
public class TenantPlanPreferenceFallback {

  private static final Logger log = LogManager.getLogger(TenantPlanPreferenceFallback.class);

  private static final String PARAM_ATTRIBUTE = "attribute";
  private static final String PARAM_CLIENT_ID = "clientId";
  private static final String PARAM_CLIENT_IDS = "clientIds";

  /** Emitted once per tenant actually served by the fallback. Carries the grep marker. */
  private static final String FALLBACK_WARNING =
      "ETP-5046-TRANSITIONAL-FALLBACK: tenant {} has no open ETGO_SUBSCRIPTION row; resolving it as"
          + " '{}' from the retired {} preference. The ETP-5046 backfill has not run for this"
          + " tenant — run it and this line disappears.";

  private static final String ACTIVE_PREFERENCE_PREDICATE =
      " and pref." + Preference.PROPERTY_ACTIVE + " = true";

  /**
   * TRANSITIONAL (ETP-5046-TRANSITIONAL-FALLBACK) — whether one tenant carries the retired
   * productive marker.
   *
   * <p>Never throws: a failure here must not change {@code resolvePlan}'s contractual "never null,
   * never throws, degrades to free" behaviour, so a broken query reads back as "not productive".
   *
   * @param clientId {@code AD_CLIENT_ID} of the tenant, may be null or blank
   * @return true when an active {@value TenantPlanService#PREFERENCE_ATTRIBUTE} preference visible
   *     at that tenant holds {@value TenantPlanService#PLAN_PRODUCTIVE}
   */
  public boolean isProductive(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      return false;
    }
    try {
      // Recovered verbatim from the pre-ETP-5046 TenantPlanService#resolvePlan: the tenant is in
      // VISIBLEAT_CLIENT_ID, never AD_CLIENT_ID (the row itself lives at client '0').
      OBQuery<Preference> query = OBDal.getInstance().createQuery(Preference.class,
          "as pref where pref." + Preference.PROPERTY_ATTRIBUTE + " = :" + PARAM_ATTRIBUTE
              + " and pref." + Preference.PROPERTY_VISIBLEATCLIENT + ".id = :" + PARAM_CLIENT_ID
              + ACTIVE_PREFERENCE_PREDICATE);
      query.setNamedParameter(PARAM_ATTRIBUTE, TenantPlanService.PREFERENCE_ATTRIBUTE);
      query.setNamedParameter(PARAM_CLIENT_ID, StringUtils.trimToEmpty(clientId));
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      query.setMaxResult(1);
      Preference preference = query.uniqueResult();
      if (preference == null || !holdsProductive(preference)) {
        return false;
      }
      warnFallbackFired(clientId);
      return true;
    } catch (RuntimeException e) {
      log.warn("ETP-5046-TRANSITIONAL-FALLBACK: could not read the {} preference of tenant {};"
          + " treating it as not productive", TenantPlanService.PREFERENCE_ATTRIBUTE, clientId, e);
      return false;
    }
  }

  /**
   * TRANSITIONAL (ETP-5046-TRANSITIONAL-FALLBACK) — which of the given tenants carry the retired
   * productive marker, in <b>one</b> query.
   *
   * <p>The bulk path exists so the environment list keeps issuing a constant number of queries per
   * render. Looping {@link #isProductive} here would reintroduce exactly the N+1 that
   * {@link EnvironmentPlanCache} was built to remove. Callers pass only the ids that had no open
   * subscription, and an empty collection issues no query at all.
   *
   * <p>Deliberately not chunked, unlike {@link SubscriptionService#findOpenForClients}: the caller
   * is the per-account environment list, whose id set is a handful of tenants, and this class is
   * deleted in Phase F rather than grown.
   *
   * <p>Never throws, for the same reason as {@link #isProductive}: the cache degrades to free.
   *
   * @param clientIds tenant ids to inspect; null, blank and duplicate entries are ignored
   * @return the subset that reads back as {@value TenantPlanService#PLAN_PRODUCTIVE}, never null
   */
  public Set<String> productiveAmong(Collection<String> clientIds) {
    Set<String> ids = normalize(clientIds);
    if (ids.isEmpty()) {
      return Collections.emptySet();
    }
    try {
      OBQuery<Preference> query = OBDal.getInstance().createQuery(Preference.class,
          "as pref where pref." + Preference.PROPERTY_ATTRIBUTE + " = :" + PARAM_ATTRIBUTE
              + " and pref." + Preference.PROPERTY_VISIBLEATCLIENT + ".id in (:"
              + PARAM_CLIENT_IDS + ")" + ACTIVE_PREFERENCE_PREDICATE);
      query.setNamedParameter(PARAM_ATTRIBUTE, TenantPlanService.PREFERENCE_ATTRIBUTE);
      query.setNamedParameter(PARAM_CLIENT_IDS, List.copyOf(ids));
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      Set<String> productive = new LinkedHashSet<>();
      for (Preference preference : query.list()) {
        String tenantId = visibleAtClientId(preference);
        if (tenantId != null && ids.contains(tenantId) && holdsProductive(preference)
            && productive.add(tenantId)) {
          warnFallbackFired(tenantId);
        }
      }
      return productive;
    } catch (RuntimeException e) {
      log.warn("ETP-5046-TRANSITIONAL-FALLBACK: could not read the {} preferences of {} tenants;"
              + " treating them as not productive", TenantPlanService.PREFERENCE_ATTRIBUTE,
          ids.size(), e);
      return Collections.emptySet();
    }
  }

  private static Set<String> normalize(Collection<String> clientIds) {
    Set<String> ids = new LinkedHashSet<>();
    if (clientIds == null) {
      return ids;
    }
    for (String clientId : clientIds) {
      if (StringUtils.isNotBlank(clientId)) {
        ids.add(StringUtils.trimToEmpty(clientId));
      }
    }
    return ids;
  }

  private static String visibleAtClientId(Preference preference) {
    if (preference == null) {
      return null;
    }
    Client tenant = preference.getVisibleAtClient();
    return tenant == null ? null : tenant.getId();
  }

  /**
   * Whether the preference's VALUE column holds the productive marker, trimmed and case
   * insensitively — matching how the pre-ETP-5046 read compared it.
   */
  private static boolean holdsProductive(Preference preference) {
    return TenantPlanService.PLAN_PRODUCTIVE
        .equalsIgnoreCase(StringUtils.trimToEmpty(preference.getSearchKey()));
  }

  private static void warnFallbackFired(String clientId) {
    log.warn(FALLBACK_WARNING, clientId, TenantPlanService.PLAN_PRODUCTIVE,
        TenantPlanService.PREFERENCE_ATTRIBUTE);
  }
}
