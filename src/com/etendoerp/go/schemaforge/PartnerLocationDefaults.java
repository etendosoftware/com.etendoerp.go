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

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.Location;

/**
 * Defaults a business-partner location field of a document header on create, when the partner has
 * exactly one eligible location (ETP-5703).
 *
 * <p><b>A plain utility, called explicitly by each customization.</b> It names no entity: the caller
 * says which body key to fill and which {@link Location} flag makes a location eligible — the flag
 * the column's own AD validation rule requires. {@code C_Invoice.C_BPartner_Location_ID} and
 * {@code C_Order.BillTo_ID} use rule "C_BPartner Location - Bill To" ({@code IsBillTo='Y'});
 * {@code C_Order.C_BPartner_Location_ID} uses "C_BPartner Location - Ship To" ({@code IsShipTo='Y'}).
 *
 * <p><b>Abstains</b> — leaving the body untouched — unless: the request is a CRUD {@code POST}
 * create; the target key is absent, null, blank, {@code "null"} or {@code "0"}; the partner is a
 * usable id (not blank, not a batch {@code $ref:}); and exactly one active location of the partner
 * carries the flag. With several the choice is the caller's: a plausible wrong address is worse
 * than a precise 422. Reads in the caller's own DAL scope, never admin mode: a location this role
 * cannot read never becomes an address of a document it writes.
 *
 * <p>Total: never throws.
 */
final class PartnerLocationDefaults {

  private static final Logger log = LogManager.getLogger(PartnerLocationDefaults.class);
  static final String FIELD_BUSINESS_PARTNER = "businessPartner";

  private PartnerLocationDefaults() {
  }

  /**
   * Fills {@code targetKey} with the partner's only active location flagged by
   * {@code locationFlagProperty} (a {@link Location} boolean property such as
   * {@link Location#PROPERTY_INVOICETOADDRESS} or {@link Location#PROPERTY_SHIPTOADDRESS}).
   *
   * @return {@code true} when the body was filled
   */
  static boolean fillIfMissing(NeoContext context, String targetKey, String locationFlagProperty) {
    if (context == null || !NeoEndpointType.CRUD.equals(context.getEndpointType())
        || !"POST".equals(context.getHttpMethod()) || context.getRecordId() != null) {
      return false;
    }
    JSONObject body = context.getRequestBody();
    if (body == null || hasUsableValue(body, targetKey)) {
      return false;
    }
    String partnerId = usableValue(body, FIELD_BUSINESS_PARTNER);
    if (partnerId == null) {
      return false;
    }
    try {
      String locationId = findSingleLocation(partnerId, locationFlagProperty);
      if (locationId == null) {
        return false;
      }
      body.put(targetKey, locationId);
      log.debug("[ETP-5703] Defaulted {} {} from business partner {}", targetKey, locationId,
          partnerId);
      return true;
    } catch (Exception e) {
      log.debug("[ETP-5703] {} default skipped for partner {}: {}", targetKey, partnerId,
          e.getMessage());
      return false;
    }
  }

  /**
   * @return the id of the partner's only active location flagged by {@code locationFlagProperty},
   *     or {@code null} when it has none or more than one
   */
  static String findSingleLocation(String partnerId, String locationFlagProperty) {
    OBCriteria<Location> crit = OBDal.getInstance().createCriteria(Location.class);
    crit.add(Restrictions.eq(Location.PROPERTY_BUSINESSPARTNER + ".id", partnerId));
    crit.add(Restrictions.eq(locationFlagProperty, true));
    crit.add(Restrictions.eq(Location.PROPERTY_ACTIVE, true));
    crit.setMaxResults(2);
    List<Location> locations = crit.list();
    return locations.size() == 1 ? locations.get(0).getId() : null;
  }

  /** {@code true} when {@code key} holds a real value: not absent, null, blank, "null" or "0". */
  static boolean hasUsableValue(JSONObject body, String key) {
    if (!body.has(key) || body.isNull(key)) {
      return false;
    }
    String value = body.optString(key, "").trim();
    return !value.isEmpty() && !"null".equals(value) && !"0".equals(value);
  }

  /** The id held under {@code key}, or {@code null} when unusable or still a batch reference. */
  static String usableValue(JSONObject body, String key) {
    if (!hasUsableValue(body, key)) {
      return null;
    }
    String value = body.optString(key, "").trim();
    return value.startsWith(BatchService.REF_PREFIX) ? null : value;
  }
}
