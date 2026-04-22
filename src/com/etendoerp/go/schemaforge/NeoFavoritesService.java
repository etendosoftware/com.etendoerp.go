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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.erpCommon.utility.PropertyException;
import org.openbravo.erpCommon.utility.PropertyNotFoundException;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.ad.system.Client;

/**
 * Persists navigator favorites per user via AD_PREFERENCE.
 *
 * Property key: {@code ETGO_NavigatorFavorites}
 * Value format: JSON array of {@code {"name":"route","label":"Display Name"}} objects.
 *
 * Exposed at:
 *   GET /sws/neo/favorites — returns the current user's favorites JSON array
 *   PUT /sws/neo/favorites — replaces the current user's favorites with the given JSON array
 */
class NeoFavoritesService {

  private static final Logger log = LogManager.getLogger(NeoFavoritesService.class);
  private static final String PREF_KEY = "ETGO_NavigatorFavorites";
  private static final String EMPTY_ARRAY = "[]";

  private NeoFavoritesService() {
  }

  /**
   * Returns the favorites JSON array for the current OBContext user.
   * Returns {@code "[]"} when no preference has been stored yet.
   */
  static String getFavoritesJson() {
    OBContext ctx = OBContext.getOBContext();
    try {
      return Preferences.getPreferenceValue(
          PREF_KEY, false,
          ctx.getCurrentClient(), ctx.getCurrentOrganization(),
          ctx.getUser(), ctx.getRole(), null);
    } catch (PropertyNotFoundException e) {
      return EMPTY_ARRAY;
    } catch (PropertyException e) {
      String userId = (ctx.getUser() != null) ? ctx.getUser().getId() : "0";
      log.warn("Could not read {} for user {}: {}", PREF_KEY, userId, e.getMessage());
      return EMPTY_ARRAY;
    }
  }

  /**
   * Upserts the favorites preference for the current OBContext user.
   *
   * @param json JSON array string (e.g. {@code [{"name":"sales-order","label":"Sales Order"}]})
   */
  static void saveFavoritesJson(String json) {
    OBContext ctx = OBContext.getOBContext();
    Client client = ctx.getCurrentClient();
    Organization org = ctx.getCurrentOrganization();
    User user = ctx.getUser();
    Preferences.setPreferenceValue(PREF_KEY, json, false, client, org, user, null, null, null);
  }
}
