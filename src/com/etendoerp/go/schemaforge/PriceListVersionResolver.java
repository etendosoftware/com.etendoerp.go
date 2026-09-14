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

package com.etendoerp.go.schemaforge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListVersion;

/**
 * Single source of truth for resolving the unique {@link PriceListVersion} of a
 * {@link PriceList} in the simplified Etendo Go interface.
 *
 * <p>In Etendo Go, a price list is expected to have exactly one version:
 * {@link PriceListEventHandler} auto-creates the first version on price list
 * insert, and the GO UI does not expose any way to create additional ones.
 * The invariant is not hard-enforced at the persistence layer so that Etendo
 * Classic / Enterprise users keep the ability to manage multiple versions
 * natively. This class exposes the GO-side semantic ("give me the version of
 * this price list") and logs a warning if it ever finds more than one.
 */
public final class PriceListVersionResolver {

  private static final Logger log = LogManager.getLogger(PriceListVersionResolver.class);

  /** The shared/System organisation every tenant falls back to. */
  private static final String SHARED_ORG = "0";

  private PriceListVersionResolver() {
  }

  /**
   * Resolves the single {@link PriceListVersion} associated with the given
   * {@link PriceList}, or {@code null} if none exists yet.
   *
   * <p>Logs a warning and returns the first match if more than one version is
   * found (which would indicate the invariant is broken).
   *
   * @param priceList the price list to resolve the version for
   * @return the unique version, or {@code null} if the price list has no version
   */
  public static PriceListVersion findSingleVersion(PriceList priceList) {
    if (priceList == null) {
      return null;
    }
    OBCriteria<PriceListVersion> crit = OBDal.getInstance()
        .createCriteria(PriceListVersion.class);
    crit.add(Restrictions.eq(PriceListVersion.PROPERTY_PRICELIST, priceList));
    List<PriceListVersion> results = crit.list();
    if (results.isEmpty()) {
      return null;
    }
    if (results.size() > 1) {
      log.warn("Price list '{}' has {} versions; Etendo Go expects exactly one. "
          + "Returning the first. This is normal if the list was edited in Classic.",
          priceList.getName(), results.size());
    }
    return results.get(0);
  }

  /**
   * Convenience overload that returns the version id (or {@code null}).
   *
   * @param priceList the price list to resolve the version id for
   * @return the version id, or {@code null} if the price list has no version
   */
  public static String findSingleVersionId(PriceList priceList) {
    PriceListVersion version = findSingleVersion(priceList);
    return version != null ? version.getId() : null;
  }

  /**
   * Resolves versions for a batch of price list ids in a single query, useful
   * for list GET responses to avoid N+1.
   *
   * @param priceListIds collection of {@code M_PriceList_ID} values to look up
   * @return map of {@code priceListId → versionId}; price lists without a version
   *     are absent from the map
   */
  public static Map<String, String> findSingleVersionIds(List<String> priceListIds) {
    Map<String, String> result = new HashMap<>();
    if (priceListIds == null || priceListIds.isEmpty()) {
      return result;
    }
    OBCriteria<PriceListVersion> crit = OBDal.getInstance()
        .createCriteria(PriceListVersion.class);
    crit.createAlias(PriceListVersion.PROPERTY_PRICELIST, "pl");
    crit.add(Restrictions.in("pl.id", priceListIds));
    List<PriceListVersion> versions = crit.list();
    for (PriceListVersion v : versions) {
      String plId = v.getPriceList().getId();
      // Keep the first one if duplicates ever occur; the warning is logged on direct lookups.
      result.putIfAbsent(plId, v.getId());
    }
    return result;
  }

  /**
   * Resolves the {@link PriceListVersion} of the tenant's <em>default</em> price list for one
   * direction (sales or purchase), or {@code null} when the tenant has none.
   *
   * <p>This is the single place that answers "which tariff does Etendo Go use when nobody said
   * otherwise". It is deliberately biased towards the explicit {@code M_PriceList.IsDefault} flag:
   * a human ticking "default" on a price list is a stronger statement than the organisation a
   * version happens to live in, so a default list in the shared organisation wins over a
   * non-default one in the current organisation. Only when NO direction-matching list is flagged
   * at all does it fall back to the previous behaviour (most recent {@code ValidFromDate}), so a
   * tenant that never set the flag keeps working exactly as before.
   *
   * <p>Organisation precedence within each pass is org-specific first, then the shared
   * organisation {@code '0'} — the same COALESCE semantics
   * {@code ProductDefaultsHandler#resolveDefaultId} uses for the other tenant-wide defaults.
   *
   * @param obContext the context whose client/organisation scope the lookup runs in
   * @param salesPriceList {@code true} for the sales tariff, {@code false} for the purchase one
   * @return the version id of the default price list, or {@code null} if there is none
   */
  public static String resolveDefaultVersionId(OBContext obContext, boolean salesPriceList) {
    if (obContext == null || obContext.getCurrentClient() == null) {
      return null;
    }
    String clientId = obContext.getCurrentClient().getId();
    String orgId = obContext.getCurrentOrganization() != null
        ? obContext.getCurrentOrganization().getId() : SHARED_ORG;
    String[] orgsToTry = SHARED_ORG.equals(orgId)
        ? new String[] { SHARED_ORG }
        : new String[] { orgId, SHARED_ORG };

    try {
      OBContext.setAdminMode();
      try {
        // Pass 1: honour the IsDefault flag. Pass 2: any direction-matching list.
        for (boolean requireDefault : new boolean[] { true, false }) {
          for (String targetOrg : orgsToTry) {
            String versionId = queryVersionId(clientId, targetOrg, salesPriceList, requireDefault);
            if (versionId != null) {
              return versionId;
            }
          }
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("Could not resolve the default {} price list version: {}",
          salesPriceList ? "sales" : "purchase", e.getMessage());
    }
    return null;
  }

  /**
   * Single criteria query behind {@link #resolveDefaultVersionId(OBContext, boolean)}.
   *
   * @param clientId the client to scope the lookup to
   * @param orgId the organisation to scope the lookup to
   * @param salesPriceList {@code true} for the sales tariff, {@code false} for the purchase one
   * @param requireDefault when {@code true}, only price lists flagged as default match
   * @return the newest matching version id, or {@code null} when nothing matches
   */
  private static String queryVersionId(String clientId, String orgId, boolean salesPriceList,
      boolean requireDefault) {
    OBCriteria<PriceListVersion> crit = OBDal.getInstance().createCriteria(PriceListVersion.class);
    crit.add(Restrictions.eq(PriceListVersion.PROPERTY_CLIENT + ".id", clientId));
    crit.add(Restrictions.eq(PriceListVersion.PROPERTY_ORGANIZATION + ".id", orgId));
    crit.add(Restrictions.eq(PriceListVersion.PROPERTY_ACTIVE, true));
    crit.createAlias(PriceListVersion.PROPERTY_PRICELIST, "pl");
    crit.add(Restrictions.eq("pl." + PriceList.PROPERTY_ACTIVE, true));
    crit.add(Restrictions.eq("pl." + PriceList.PROPERTY_SALESPRICELIST, salesPriceList));
    if (requireDefault) {
      crit.add(Restrictions.eq("pl." + PriceList.PROPERTY_DEFAULT, true));
    }
    crit.addOrder(Order.desc(PriceListVersion.PROPERTY_VALIDFROMDATE));
    crit.setMaxResults(1);
    List<PriceListVersion> results = crit.list();
    return results.isEmpty() ? null : results.get(0).getId();
  }
}
