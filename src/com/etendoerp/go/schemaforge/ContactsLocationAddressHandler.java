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

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Region;

/**
 * NeoHandler for the {@code locationAddress} entity in the {@code contacts} spec.
 *
 * <p>Manages C_Location (address data) and C_BPartner_Location (the BP–address link) atomically
 * so that users can create, edit and read addresses from the Contacts window without requiring
 * explicit AD_Window_Access to the standalone Location window (AD_Window_ID = 121).
 * This replicates Classic Etendo behaviour where address child-tabs operate under the parent
 * Business Partner window's security context.
 *
 * <ul>
 *   <li>POST /contacts/locationAddress?parentId={bpId} — creates C_Location + C_BPartner_Location</li>
 *   <li>PUT  /contacts/locationAddress/{id}            — updates both records via the FK</li>
 *   <li>GET  /contacts/locationAddress/{id}            — default fetch enriched with C_Location data</li>
 *   <li>GET  /contacts/locationAddress + DELETE        — fall through to default CRUD unchanged</li>
 * </ul>
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'contactsLocationAddressHandler'} on the
 * ETGO_SF_ENTITY record for {@code locationAddress} in the {@code contacts} spec.
 */
@Named("contactsLocationAddressHandler")
public class ContactsLocationAddressHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(ContactsLocationAddressHandler.class);
  private static final String FIELD_SHIP_TO_ADDRESS = "shipToAddress";
  private static final String FIELD_INVOICE_TO_ADDRESS = "invoiceToAddress";
  private static final String FIELD_COUNTRY = "country";
  private static final String FIELD_REGION = "region";
  private static final String FIELD_RESPONSE = "response";
  private static final String FIELD_DATA = "data";

  @Override
  public NeoResponse handle(NeoContext ctx) {
    String method = ctx.getHttpMethod();
    String recordId = ctx.getRecordId();
    try {
      if ("POST".equals(method) && recordId == null) {
        return handleCreate(ctx);
      }
      if ("PUT".equals(method) && recordId != null) {
        return handleUpdate(ctx);
      }
    } catch (Exception e) {
      SessionHandler.getInstance().rollback();
      log.error("ContactsLocationAddressHandler error in {}", method, e);
      return NeoResponse.error(500, "Location handler error: " + e.getMessage());
    }
    // GET list and DELETE fall through to default CRUD
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext ctx) {
    if (!"GET".equals(ctx.getHttpMethod())) {
      return null;
    }
    try {
      if (ctx.getRecordId() != null) {
        // Enrich GET-by-ID response with C_Location fields so the modal can pre-populate the form
        return enrichWithLocationData(ctx);
      }
      // Enrich GET list: replace stale "Location" names with a computed display name
      return enrichListDisplayNames(ctx);
    } catch (Exception e) {
      log.error("ContactsLocationAddressHandler afterHandle error", e);
      return null;
    }
  }

  // ------------------------------------------------------------------ create

  private NeoResponse handleCreate(NeoContext ctx) throws Exception {
    JSONObject body = ctx.getRequestBody();
    String bpId = ctx.getQueryParams().get("parentId");
    if (bpId == null || bpId.isEmpty()) {
      return NeoResponse.error(400, "Missing parentId (Business Partner ID)");
    }

    OBContext.setAdminMode(true);
    try {
      BusinessPartner bp = OBDal.getInstance().get(BusinessPartner.class, bpId);
      if (bp == null) {
        return NeoResponse.error(404, "Business Partner not found: " + bpId);
      }

      // Create C_Location (physical address)
      org.openbravo.model.common.geography.Location geoLoc =
          OBProvider.getInstance().get(org.openbravo.model.common.geography.Location.class);
      geoLoc.setClient(bp.getClient());
      geoLoc.setOrganization(bp.getOrganization());
      geoLoc.setActive(Boolean.TRUE);
      applyGeoLocFields(body, geoLoc);
      OBDal.getInstance().save(geoLoc);

      // Create C_BPartner_Location (BP–address link)
      org.openbravo.model.common.businesspartner.Location bpLoc =
          OBProvider.getInstance().get(org.openbravo.model.common.businesspartner.Location.class);
      bpLoc.setClient(bp.getClient());
      bpLoc.setOrganization(bp.getOrganization());
      bpLoc.setActive(Boolean.TRUE);
      bpLoc.setBusinessPartner(bp);
      bpLoc.setLocationAddress(geoLoc);
      bpLoc.setName(str(body, "name", "."));
      bpLoc.setShipToAddress(boolField(body, FIELD_SHIP_TO_ADDRESS, true));
      bpLoc.setInvoiceToAddress(boolField(body, FIELD_INVOICE_TO_ADDRESS, true));
      bpLoc.setPayFromAddress(Boolean.TRUE);
      bpLoc.setRemitToAddress(Boolean.TRUE);
      OBDal.getInstance().save(bpLoc);

      OBDal.getInstance().flush();

      return wrapRecord(buildRecord(bpLoc, geoLoc), 201);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ------------------------------------------------------------------ update

  private NeoResponse handleUpdate(NeoContext ctx) throws Exception {
    JSONObject body = ctx.getRequestBody();
    String bplId = ctx.getRecordId();

    OBContext.setAdminMode(true);
    try {
      org.openbravo.model.common.businesspartner.Location bpLoc =
          OBDal.getInstance().get(org.openbravo.model.common.businesspartner.Location.class, bplId);
      if (bpLoc == null) {
        return NeoResponse.error(404, "BPartner Location not found: " + bplId);
      }

      org.openbravo.model.common.geography.Location geoLoc = bpLoc.getLocationAddress();
      if (geoLoc == null) {
        return NeoResponse.error(500, "BPartner Location has no linked C_Location: " + bplId);
      }

      applyGeoLocFields(body, geoLoc);

      String nameVal = nullIfEmpty(body.optString("name", null));
      if (nameVal != null) {
        bpLoc.setName(nameVal);
      }
      if (body.has(FIELD_SHIP_TO_ADDRESS)) {
        bpLoc.setShipToAddress(boolField(body, FIELD_SHIP_TO_ADDRESS,
            Boolean.TRUE.equals(bpLoc.isShipToAddress())));
      }
      if (body.has(FIELD_INVOICE_TO_ADDRESS)) {
        bpLoc.setInvoiceToAddress(boolField(body, FIELD_INVOICE_TO_ADDRESS,
            Boolean.TRUE.equals(bpLoc.isInvoiceToAddress())));
      }

      OBDal.getInstance().flush();

      return wrapRecord(buildRecord(bpLoc, geoLoc), 200);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ------------------------------------------------------------------ enrich GET

  private NeoResponse enrichWithLocationData(NeoContext ctx) throws Exception {
    JSONArray dataArr = extractDataArray(ctx);
    if (dataArr == null) {
      return null;
    }
    JSONObject locationJson = dataArr.getJSONObject(0);
    String geoLocId = nullIfEmpty(locationJson.optString("locationAddress", null));
    if (geoLocId == null) {
      return null;
    }

    OBContext.setAdminMode(true);
    try {
      org.openbravo.model.common.geography.Location geoLoc =
          OBDal.getInstance().get(org.openbravo.model.common.geography.Location.class, geoLocId);
      if (geoLoc == null) {
        return null;
      }
      putGeoLocFields(locationJson, geoLoc);
      return NeoResponse.ok(ctx.getPreviousResult().getBody());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ------------------------------------------------------------------ enrich GET list

  private NeoResponse enrichListDisplayNames(NeoContext ctx) throws Exception {
    JSONArray dataArr = extractDataArray(ctx);
    if (dataArr == null) {
      return null;
    }

    OBContext.setAdminMode(true);
    try {
      boolean modified = false;
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        String name = rec.optString("name", "");
        if (!"Location".equals(name) && !name.isEmpty()) {
          continue;
        }
        String bplId = nullIfEmpty(rec.optString("id", null));
        if (bplId == null) {
          continue;
        }
        org.openbravo.model.common.businesspartner.Location bpLoc =
            OBDal.getInstance().get(org.openbravo.model.common.businesspartner.Location.class, bplId);
        if (bpLoc == null || bpLoc.getLocationAddress() == null) {
          continue;
        }
        String computed = buildDisplayName(bpLoc.getLocationAddress());
        if (computed != null && !computed.equals(name)) {
          rec.put("name", computed);
          modified = true;
        }
      }
      return modified ? NeoResponse.ok(ctx.getPreviousResult().getBody()) : null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String buildDisplayName(org.openbravo.model.common.geography.Location geoLoc) {
    String result = joinNonNull(nullIfEmpty(geoLoc.getCityName()), nullIfEmpty(geoLoc.getAddressLine1()));
    if (result != null) {
      return result;
    }
    String region = geoLoc.getRegion() != null ? nullIfEmpty(geoLoc.getRegion().getName()) : null;
    String country = geoLoc.getCountry() != null ? nullIfEmpty(geoLoc.getCountry().getName()) : null;
    return joinNonNull(region, country);
  }

  // ------------------------------------------------------------------ shared helpers

  private static JSONArray extractDataArray(NeoContext ctx) {
    NeoResponse previous = ctx.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    JSONObject responseWrapper = previous.getBody().optJSONObject(FIELD_RESPONSE);
    if (responseWrapper == null) {
      return null;
    }
    JSONArray dataArr = responseWrapper.optJSONArray(FIELD_DATA);
    return (dataArr == null || dataArr.length() == 0) ? null : dataArr;
  }

  private static void applyGeoLocFields(JSONObject body,
      org.openbravo.model.common.geography.Location geoLoc) throws Exception {
    geoLoc.setAddressLine1(nullIfEmpty(body.optString("addressLine1", null)));
    geoLoc.setAddressLine2(nullIfEmpty(body.optString("addressLine2", null)));
    geoLoc.setCityName(nullIfEmpty(body.optString("cityName", null)));
    geoLoc.setPostalCode(nullIfEmpty(body.optString("postalCode", null)));

    String countryId = nullIfEmpty(body.optString(FIELD_COUNTRY, null));
    if (countryId != null) {
      Country country = OBDal.getInstance().get(Country.class, countryId);
      if (country != null) {
        geoLoc.setCountry(country);
      }
    }

    String regionId = nullIfEmpty(body.optString(FIELD_REGION, null));
    if (body.has(FIELD_REGION)) {
      if (regionId != null) {
        Region region = OBDal.getInstance().get(Region.class, regionId);
        geoLoc.setRegion(region);
      } else {
        geoLoc.setRegion(null);
      }
    }
  }

  private static void putGeoLocFields(JSONObject locationJson,
      org.openbravo.model.common.geography.Location geoLoc) throws Exception {
    locationJson.put("addressLine1", geoLoc.getAddressLine1() != null ? geoLoc.getAddressLine1() : JSONObject.NULL);
    locationJson.put("addressLine2", geoLoc.getAddressLine2() != null ? geoLoc.getAddressLine2() : JSONObject.NULL);
    locationJson.put("cityName",     geoLoc.getCityName()     != null ? geoLoc.getCityName()     : JSONObject.NULL);
    locationJson.put("postalCode",   geoLoc.getPostalCode()   != null ? geoLoc.getPostalCode()   : JSONObject.NULL);

    if (geoLoc.getCountry() != null) {
      locationJson.put(FIELD_COUNTRY,          geoLoc.getCountry().getId());
      locationJson.put("country$_identifier",  geoLoc.getCountry().getName());
    } else {
      locationJson.put(FIELD_COUNTRY,          JSONObject.NULL);
      locationJson.put("country$_identifier",  JSONObject.NULL);
    }
    if (geoLoc.getRegion() != null) {
      locationJson.put(FIELD_REGION,           geoLoc.getRegion().getId());
      locationJson.put("region$_identifier",   geoLoc.getRegion().getName());
    } else {
      locationJson.put(FIELD_REGION,           JSONObject.NULL);
      locationJson.put("region$_identifier",   JSONObject.NULL);
    }
  }

  private static JSONObject buildRecord(org.openbravo.model.common.businesspartner.Location bpLoc,
      org.openbravo.model.common.geography.Location geoLoc) throws Exception {
    JSONObject locationJson = new JSONObject();
    locationJson.put("id",              bpLoc.getId());
    locationJson.put("locationAddress", geoLoc.getId());
    locationJson.put("name",            bpLoc.getName() != null ? bpLoc.getName() : JSONObject.NULL);
    locationJson.put(FIELD_SHIP_TO_ADDRESS,    Boolean.TRUE.equals(bpLoc.isShipToAddress()) ? "Y" : "N");
    locationJson.put(FIELD_INVOICE_TO_ADDRESS, Boolean.TRUE.equals(bpLoc.isInvoiceToAddress()) ? "Y" : "N");
    putGeoLocFields(locationJson, geoLoc);
    return locationJson;
  }

  private static NeoResponse wrapRecord(JSONObject locationJson, int httpStatus) throws Exception {
    JSONArray dataArr = new JSONArray();
    dataArr.put(locationJson);
    JSONObject responseData = new JSONObject();
    responseData.put("status", 0);
    responseData.put(FIELD_DATA, dataArr);
    JSONObject wrapper = new JSONObject();
    wrapper.put(FIELD_RESPONSE, responseData);
    return new NeoResponse(httpStatus, wrapper);
  }

  private static String joinNonNull(String... parts) {
    StringBuilder sb = new StringBuilder();
    for (String part : parts) {
      if (part != null) {
        if (sb.length() > 0) {
          sb.append(", ");
        }
        sb.append(part);
      }
    }
    return sb.length() > 0 ? sb.toString() : null;
  }

  private static String nullIfEmpty(String s) {
    return (s == null || s.isEmpty() || "null".equals(s)) ? null : s;
  }

  private static boolean boolField(JSONObject body, String key, boolean defaultVal) {
    if (!body.has(key)) return defaultVal;
    String v = body.optString(key, "");
    return "Y".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v);
  }

  private static String str(JSONObject body, String key, String fallback) {
    String v = nullIfEmpty(body.optString(key, null));
    return v != null ? v : fallback;
  }
}
