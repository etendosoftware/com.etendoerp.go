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
import org.hibernate.criterion.Restrictions;
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
}
