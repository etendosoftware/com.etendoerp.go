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
import org.openbravo.model.common.order.Order;

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
 * than a precise 422. The MCP call runs under {@code OBContext.setAdminMode()}, which skips only
 * the entity access check; the {@code OBCriteria} here keep the readable-client and
 * readable-organization filters on, so a location this role cannot read (another tenant's, when a
 * foreign partner id is sent) is never found and never becomes an address of a document it writes.
 * Never disable those filters in these lookups.
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
   * Ship-to then bill-to default of an order-like header (ETP-5703): fills {@code shipToKey} with the
   * partner's only location flagged {@code shipToFlag} and, only when that happened, resolves
   * {@code billToKey} with {@link #alignBillTo}. A caller that sent the ship-to address (the SPA)
   * is untouched, bill-to included.
   */
  static void fillShipToAndBillTo(NeoContext context, String shipToKey, String shipToFlag,
      String billToKey, String billToFlag) {
    if (!fillIfMissing(context, shipToKey, shipToFlag)) {
      return;
    }
    try {
      alignBillTo(context.getRequestBody(), shipToKey, billToKey, billToFlag);
    } catch (Exception e) {
      log.debug("[ETP-5703] {} alignment skipped: {}", billToKey, e.getMessage());
    }
  }

  /**
   * ETP-5703 / ETP-5852: the order headers' case of {@link #fillShipToAndBillTo}. A create that
   * names a business partner but no address gets the partner's only ship-to location — the AD rule
   * of {@code C_Order.C_BPartner_Location_ID} is "C_BPartner Location - Ship To" — and, only then,
   * a bill-to for {@code BillTo_ID} that is never left empty (see {@link #alignBillTo}). This is
   * what the form does: picking the partner selects the address when its selector offers exactly
   * one. With several it abstains and the mandatory-field check names the field.
   *
   * @param context the request context; only a CRUD {@code POST} create body is touched
   */
  static void fillOrderLocations(NeoContext context) {
    fillShipToAndBillTo(context, Order.PROPERTY_PARTNERADDRESS, Location.PROPERTY_SHIPTOADDRESS,
        Order.PROPERTY_INVOICEADDRESS, Location.PROPERTY_INVOICETOADDRESS);
  }

  /**
   * Resolves the bill-to of a header whose ship-to the server just filled. Never leaves it empty
   * when a ship-to exists: a null bill-to is copied by {@code C_INVOICE_CREATE} into the NOT NULL
   * {@code C_Invoice.C_BPartner_Location_ID}, so the document could never be invoiced.
   *
   * <ul>
   *   <li>Ship-to also flagged bill-to: the bill-to is the ship-to — unless the body already holds
   *       a different bill-to that is NOT the partner's oldest bill-to location. The oldest one is
   *       exactly what {@code McpBillToInjector} derives on {@code etendo_batch} before this pre-hook
   *       runs (it sees no ship-to yet), and the body carries no marker of who set the value, so
   *       that value is treated as server-derived and replaced; any other value is the caller's and
   *       kept. Limitation (accepted): a caller who deliberately sends the oldest bill-to together
   *       with no ship-to gets the ship-to instead — the two are then both valid bill-to addresses
   *       of the partner.</li>
   *   <li>Ship-to not flagged bill-to: a bill-to already in the body is kept; otherwise the
   *       partner's only bill-to location, else the ship-to itself — the fallback core's
   *       {@code SL_Order_Product} and {@code McpBillToInjector} use.</li>
   * </ul>
   */
  static void alignBillTo(JSONObject body, String shipToKey, String billToKey, String billToFlag)
      throws org.codehaus.jettison.json.JSONException {
    String shipToId = usableValue(body, shipToKey);
    String partnerId = usableValue(body, FIELD_BUSINESS_PARTNER);
    if (shipToId == null || partnerId == null) {
      return;
    }
    if (hasUsableValue(body, billToKey) && usableValue(body, billToKey) == null) {
      return; // a batch $ref: placeholder — never second-guessed
    }
    String current = usableValue(body, billToKey);
    Location shipTo = OBDal.getInstance().get(Location.class, shipToId);
    boolean shipToIsBillTo = shipTo != null && Boolean.TRUE.equals(shipTo.get(billToFlag));
    if (shipToIsBillTo) {
      if (current == null || (!current.equals(shipToId)
          && current.equals(findOldestLocation(partnerId, billToFlag)))) {
        body.put(billToKey, shipToId);
      }
      return;
    }
    if (current != null) {
      return;
    }
    String single = findSingleLocation(partnerId, billToFlag);
    body.put(billToKey, single != null ? single : shipToId);
  }

  /** The partner's oldest active location flagged {@code flag} — McpBillToInjector's choice. */
  static String findOldestLocation(String partnerId, String flag) {
    OBCriteria<Location> crit = OBDal.getInstance().createCriteria(Location.class);
    crit.add(Restrictions.eq(Location.PROPERTY_BUSINESSPARTNER + ".id", partnerId));
    crit.add(Restrictions.eq(flag, true));
    crit.add(Restrictions.eq(Location.PROPERTY_ACTIVE, true));
    crit.addOrderBy(Location.PROPERTY_CREATIONDATE, true);
    crit.setMaxResults(1);
    List<Location> locations = crit.list();
    return locations.isEmpty() ? null : locations.get(0).getId();
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
