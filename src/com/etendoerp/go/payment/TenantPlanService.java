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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.go.schemaforge.data.Subscription;

/**
 * Records and reads the commercial plan of a tenant.
 *
 * <p><b>There is one source of truth, plus a safety net.</b> Since ETP-5046 the source of truth
 * for "is this tenant paying" is its open {@code ETGO_SUBSCRIPTION} row, and {@link #resolvePlan}
 * reads that first, falling back to the preference only when there is no subscription at all (see
 * the transitional note below). {@link #markProductive} no longer writes a parallel truth: the
 * paid-upgrade path calls it <em>only</em> when the subscription write failed — which is exactly
 * the case {@link TenantPlanPreferenceFallback} exists to cover.
 *
 * <p><b>The marker is retired PER TENANT, not fleet-wide.</b> A tenant leaves the transitional
 * state the moment it gains a live subscription: the R37 backfill data-fix removes its
 * {@value #PREFERENCE_ATTRIBUTE} row in the same transaction as the backfilled subscription, and
 * the runtime path does the same through {@link #retireProductivePreference} right after a
 * successful subscription write. That makes the cutover a per-tenant state transition with an
 * OBSERVABLE end condition instead of a judgement call:
 *
 * <pre>select count(*) from ad_preference where attribute = 'ETGO_TenantPlan';</pre>
 *
 * When that reaches 0 — and the fallback's WARN lines stop appearing — Phase F can delete
 * {@link #markProductive}, {@link #retireProductivePreference}, {@link #PREFERENCE_ATTRIBUTE} and
 * {@link TenantPlanPreferenceFallback} safely. Until then, the rows that remain ARE the worklist.
 *
 * <p>Absence of a subscription means {@value #PLAN_FREE}: every tenant provisioned before this
 * feature, and every first (unpaid) tenant, reads back as free without a migration — <em>unless</em>
 * the transitional preference fallback below still answers for it.
 *
 * <p><b>TRANSITIONAL — ETP-5046 cutover only.</b> Until the R37 backfill has run, a tenant with no
 * open subscription is checked against the retired preference through
 * {@link TenantPlanPreferenceFallback}. Grep marker for the whole deletion:
 * {@code ETP-5046-TRANSITIONAL-FALLBACK}. Delete it in Phase F together with
 * {@link #markProductive}, {@link #retireProductivePreference} and
 * {@link #PREFERENCE_ATTRIBUTE}.
 */
public class TenantPlanService {

  private static final Logger log = LogManager.getLogger(TenantPlanService.class);

  /** AD_Preference attribute holding the tenant plan. */
  public static final String PREFERENCE_ATTRIBUTE = "ETGO_TenantPlan";

  /** Default plan: the tenant was provisioned without payment. */
  public static final String PLAN_FREE = "free";

  /** The tenant was provisioned through the paid upgrade flow. */
  public static final String PLAN_PRODUCTIVE = "productive";

  /**
   * Reads the subscription rows {@link #resolvePlan} answers from. Package-visible and assigned
   * rather than injected so a unit test can substitute a double, matching how
   * {@code PlanPriceDerivationHandler} holds its provider gateway.
   */
  SubscriptionService subscriptionService = new SubscriptionService();

  /**
   * TRANSITIONAL (ETP-5046-TRANSITIONAL-FALLBACK) — answers for tenants the R37 backfill has not
   * reached yet. Delete this field in Phase F with {@link #markProductive} and
   * {@link #PREFERENCE_ATTRIBUTE}. Package-visible for the same reason as
   * {@link #subscriptionService}.
   */
  TenantPlanPreferenceFallback preferenceFallback = new TenantPlanPreferenceFallback();

  /**
   * TRANSITIONAL (ETP-5046-TRANSITIONAL-FALLBACK) — writes the retired {@value #PREFERENCE_ATTRIBUTE}
   * marker for a tenant. Called only after a payment was approved and the client exists.
   *
   * <p><b>This is now the SAFETY NET, not a second source of truth.</b> The paid-upgrade path
   * ({@code EtendoGoJwtServlet#applyPaidUpgradeSideEffects}) calls this only when opening the
   * tenant's subscription row FAILED. In that one case the preference is the only record that the
   * tenant paid, and {@link TenantPlanPreferenceFallback} is the only thing that will read it back
   * — which is precisely what the fallback exists for. On the success path the preference is not
   * written at all; it is {@link #retireProductivePreference retired} instead.
   *
   * <p>Best-effort by design: the plan marker is commercial metadata, not part of the tenant's
   * functional provisioning, so a failure here is logged rather than allowed to roll back an
   * otherwise complete environment. It is <em>reported</em> to the caller all the same — an
   * environment that was paid for and could not be marked is the exact state ETP-4966 was reported
   * as, and a caller that cannot tell success from failure cannot say so.
   *
   * @param clientId the AD_Client just created
   * @param organizationId the client's {@code *} organization, used as the preference's visibility
   *     scope; may be null
   * @return {@code true} when the marker was written, {@code false} when it could not be
   */
  public boolean markProductive(String clientId, String organizationId) {
    if (StringUtils.isBlank(clientId)) {
      log.warn("Could not mark plan: no tenant was given");
      return false;
    }
    try {
      Client client = OBDal.getInstance().get(Client.class, clientId);
      if (client == null) {
        log.warn("Could not mark plan: client {} not found", clientId);
        return false;
      }
      Organization organization = StringUtils.isBlank(organizationId)
          ? null
          : OBDal.getInstance().get(Organization.class, organizationId);
      // isListProperty=false is what makes Openbravo store the key in AD_Preference.Attribute,
      // which is the column resolvePlan queries. The two must not drift apart: a key written to
      // AD_Preference.Property instead would never be found, and every paid tenant would read
      // back as free with nothing reporting it.
      Preferences.setPreferenceValue(PREFERENCE_ATTRIBUTE, PLAN_PRODUCTIVE, false, client,
          organization, null, null, null, null);
      log.info("Tenant {} marked as plan '{}'", clientId, PLAN_PRODUCTIVE);
      return true;
    } catch (RuntimeException e) {
      log.error("Could not mark tenant {} as plan '{}'", clientId, PLAN_PRODUCTIVE, e);
      return false;
    }
  }

  /**
   * TRANSITIONAL (ETP-5046-TRANSITIONAL-FALLBACK) — retires the legacy {@value #PREFERENCE_ATTRIBUTE}
   * marker of one tenant. Delete this method in Phase F together with {@link #markProductive},
   * {@link #PREFERENCE_ATTRIBUTE} and {@link TenantPlanPreferenceFallback}.
   *
   * <p>Called right after the tenant's subscription row has been opened successfully, so that a
   * newly paid tenant lands directly in the post-cutover state instead of carrying two answers to
   * the same question. It is the runtime twin of statement 3 of the R37 backfill data-fix
   * ({@code 20260924T150000Z__R37-tenant-subscription-backfill.sql}), which does the same thing for
   * tenants that predate the subscription model — same policy, same scoping, so the fleet converges
   * from both ends.
   *
   * <p><b>Scoped by {@code VISIBLEAT_CLIENT_ID}, never by {@code AD_CLIENT_ID}.</b>
   * {@code Preferences.setPreferenceValue} stores the row at {@code AD_CLIENT_ID = '0'} and encodes
   * the tenant it is about in {@code VISIBLEAT_CLIENT_ID} only, so a query filtered on
   * {@code AD_CLIENT_ID} matches ZERO rows for every tenant — silently. That mistake has already
   * nearly shipped twice on this ticket; the query below mirrors
   * {@link TenantPlanPreferenceFallback#isProductive} verbatim and a spec pins the column.
   *
   * <p><b>Every matching row goes, whatever its value or active flag.</b> Once the tenant has an
   * open subscription, any surviving marker is a second answer to a question that now has one
   * authority. A leftover inactive or non-{@value #PLAN_PRODUCTIVE} row would also keep the
   * cutover's end-condition count above zero forever, which is the one property that makes the
   * per-tenant design worth having.
   *
   * <p><b>Never throws.</b> Failing to retire the marker is harmless — the fallback simply keeps
   * answering for that tenant and the R37 fix retires it later — so it must never be able to fail
   * an upgrade that has already been paid for. It is logged loudly all the same.
   *
   * @param clientId {@code AD_CLIENT_ID} of the tenant, may be null or blank
   * @return {@code true} when at least one marker row was removed, {@code false} when there was
   *     nothing to remove or the attempt failed
   */
  public boolean retireProductivePreference(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      log.warn("Could not retire the {} preference: no tenant was given", PREFERENCE_ATTRIBUTE);
      return false;
    }
    try {
      OBQuery<Preference> query = OBDal.getInstance().createQuery(Preference.class,
          "as pref where pref." + Preference.PROPERTY_ATTRIBUTE + " = :attribute"
              + " and pref." + Preference.PROPERTY_VISIBLEATCLIENT + ".id = :clientId");
      query.setNamedParameter("attribute", PREFERENCE_ATTRIBUTE);
      query.setNamedParameter("clientId", StringUtils.trimToEmpty(clientId));
      query.setFilterOnReadableClients(false);
      query.setFilterOnReadableOrganization(false);
      List<Preference> stale = query.list();
      if (stale.isEmpty()) {
        return false;
      }
      for (Preference preference : stale) {
        OBDal.getInstance().remove(preference);
      }
      OBDal.getInstance().flush();
      log.info("ETP-5046-TRANSITIONAL-FALLBACK: retired {} {} preference row(s) of tenant {}; its"
          + " open subscription is now its only plan record", stale.size(), PREFERENCE_ATTRIBUTE,
          clientId);
      return true;
    } catch (RuntimeException e) {
      log.error("ETP-5046-TRANSITIONAL-FALLBACK: could not retire the {} preference of tenant {};"
          + " the transitional fallback will keep answering for it until the R37 backfill runs",
          PREFERENCE_ATTRIBUTE, clientId, e);
      return false;
    }
  }

  /**
   * Resolves the plan of a tenant.
   *
   * <p><b>Productive means an open subscription row exists. Full stop.</b> It does NOT mean the
   * tenant's plan carries a provider price id. The grandfathered {@code legacy-productive} plan
   * has none by design — it predates provider billing and exists so backfilled tenants have a
   * plan catalog row to point at — so keying this on a price id would flip every backfilled tenant to
   * free the instant it shipped, silently. That is the most dangerous possible mis-implementation
   * of this feature; do not reintroduce it.
   *
   * <p>Three properties are contractual and callers depend on all of them: this method is
   * <b>never null, never throws, and degrades to {@value #PLAN_FREE}</b>. {@code
   * OnboardingForceTestModeService} compares the result to {@value #PLAN_FREE} with no null guard,
   * and the environment list renders it directly.
   *
   * <p><b>TRANSITIONAL (ETP-5046-TRANSITIONAL-FALLBACK).</b> A tenant with <em>no</em> open
   * subscription is then checked against the retired {@value #PREFERENCE_ATTRIBUTE} preference, so
   * a tenant the R37 backfill has not reached yet does not silently lose its paid plan between the
   * deploy and the backfill. A tenant that <em>has</em> an open row never reaches the fallback, so
   * the normal case costs no extra query. Delete the fallback in Phase F together with
   * {@link #markProductive} and {@link #PREFERENCE_ATTRIBUTE}.
   *
   * @param clientId the AD_Client to inspect
   * @return {@value #PLAN_PRODUCTIVE} when the tenant has an open subscription in a paying status
   *     or (transitionally) still carries the retired productive preference, otherwise
   *     {@value #PLAN_FREE}
   */
  public String resolvePlan(String clientId) {
    if (StringUtils.isBlank(clientId)) {
      return PLAN_FREE;
    }
    try {
      Optional<Subscription> openSubscription = subscriptionService.findOpen(clientId);
      if (openSubscription.isPresent()) {
        return legacyPlanForStatus(openSubscription.get().getSubscriptionStatus());
      }
      // ETP-5046-TRANSITIONAL-FALLBACK — only reached when there is no open subscription at all.
      return preferenceFallback.isProductive(clientId) ? PLAN_PRODUCTIVE : PLAN_FREE;
    } catch (RuntimeException e) {
      log.warn("Could not resolve plan for tenant {}, assuming '{}'", clientId, PLAN_FREE, e);
      return PLAN_FREE;
    }
  }

  /**
   * Maps a subscription status onto the coarse {@code "free" | "productive"} vocabulary the
   * environment list and the feature flags have used since ETP-4686.
   *
   * <p>{@code past_due} is productive on purpose: an invoice has failed but the subscription is
   * still live and Stripe is still retrying, so cutting the tenant's access off mid-dunning would
   * punish a customer whose card merely expired. {@code canceled}, a status this method does not
   * name, and no subscription at all all read as free.
   *
   * @param status the subscription status, may be null or blank
   * @return {@value #PLAN_PRODUCTIVE} or {@value #PLAN_FREE}, never null
   */
  public static String legacyPlanForStatus(String status) {
    String normalized = StringUtils.trimToEmpty(status).toLowerCase(Locale.ROOT);
    return SubscriptionService.STATUS_ACTIVE.equals(normalized)
        || SubscriptionService.STATUS_PAST_DUE.equals(normalized) ? PLAN_PRODUCTIVE : PLAN_FREE;
  }
}
