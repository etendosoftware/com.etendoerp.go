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
      log.error("ContactsLocationAddressHandler error in {}", method, e);
      return NeoResponse.error(500, "Location handler error: " + e.getMessage());
    }
    // GET list and DELETE fall through to default CRUD
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext ctx) {
    // Enrich GET-by-ID response with C_Location fields so the modal can pre-populate the form
    if (!"GET".equals(ctx.getHttpMethod()) || ctx.getRecordId() == null) {
      return null;
    }
    try {
      return enrichWithLocationData(ctx);
    } catch (Exception e) {
      log.error("ContactsLocationAddressHandler error enriching GET by ID response", e);
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
      bpLoc.setShipToAddress(Boolean.TRUE);
      bpLoc.setInvoiceToAddress(Boolean.TRUE);
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

      OBDal.getInstance().flush();

      return wrapRecord(buildRecord(bpLoc, geoLoc), 200);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ------------------------------------------------------------------ enrich GET

  private NeoResponse enrichWithLocationData(NeoContext ctx) throws Exception {
    NeoResponse previous = ctx.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    JSONObject body = previous.getBody();
    JSONObject responseWrapper = body.optJSONObject("response");
    if (responseWrapper == null) {
      return null;
    }
    JSONArray dataArr = responseWrapper.optJSONArray("data");
    if (dataArr == null || dataArr.length() == 0) {
      return null;
    }
    JSONObject record = dataArr.getJSONObject(0);
    String geoLocId = nullIfEmpty(record.optString("locationAddress", null));
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
      putGeoLocFields(record, geoLoc);
      return NeoResponse.ok(body);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ------------------------------------------------------------------ shared helpers

  private static void applyGeoLocFields(JSONObject body,
      org.openbravo.model.common.geography.Location geoLoc) throws Exception {
    geoLoc.setAddressLine1(nullIfEmpty(body.optString("addressLine1", null)));
    geoLoc.setAddressLine2(nullIfEmpty(body.optString("addressLine2", null)));
    geoLoc.setCityName(nullIfEmpty(body.optString("cityName", null)));
    geoLoc.setPostalCode(nullIfEmpty(body.optString("postalCode", null)));

    String countryId = nullIfEmpty(body.optString("country", null));
    if (countryId != null) {
      Country country = OBDal.getInstance().get(Country.class, countryId);
      if (country != null) {
        geoLoc.setCountry(country);
      }
    }

    String regionId = nullIfEmpty(body.optString("region", null));
    if (body.has("region")) {
      if (regionId != null) {
        Region region = OBDal.getInstance().get(Region.class, regionId);
        geoLoc.setRegion(region);
      } else {
        geoLoc.setRegion(null);
      }
    }
  }

  private static void putGeoLocFields(JSONObject record,
      org.openbravo.model.common.geography.Location geoLoc) throws Exception {
    record.put("addressLine1", geoLoc.getAddressLine1() != null ? geoLoc.getAddressLine1() : JSONObject.NULL);
    record.put("addressLine2", geoLoc.getAddressLine2() != null ? geoLoc.getAddressLine2() : JSONObject.NULL);
    record.put("cityName",     geoLoc.getCityName()     != null ? geoLoc.getCityName()     : JSONObject.NULL);
    record.put("postalCode",   geoLoc.getPostalCode()   != null ? geoLoc.getPostalCode()   : JSONObject.NULL);

    if (geoLoc.getCountry() != null) {
      record.put("country",             geoLoc.getCountry().getId());
      record.put("country$_identifier", geoLoc.getCountry().getName());
    } else {
      record.put("country",             JSONObject.NULL);
      record.put("country$_identifier", JSONObject.NULL);
    }
    if (geoLoc.getRegion() != null) {
      record.put("region",             geoLoc.getRegion().getId());
      record.put("region$_identifier", geoLoc.getRegion().getName());
    } else {
      record.put("region",             JSONObject.NULL);
      record.put("region$_identifier", JSONObject.NULL);
    }
  }

  private static JSONObject buildRecord(org.openbravo.model.common.businesspartner.Location bpLoc,
      org.openbravo.model.common.geography.Location geoLoc) throws Exception {
    JSONObject record = new JSONObject();
    record.put("id",             bpLoc.getId());
    record.put("locationAddress", geoLoc.getId());
    record.put("name",           bpLoc.getName() != null ? bpLoc.getName() : JSONObject.NULL);
    record.put("shipToAddress",  Boolean.TRUE.equals(bpLoc.isShipToAddress())   ? "Y" : "N");
    record.put("invoiceToAddress", Boolean.TRUE.equals(bpLoc.isInvoiceToAddress()) ? "Y" : "N");
    putGeoLocFields(record, geoLoc);
    return record;
  }

  private static NeoResponse wrapRecord(JSONObject record, int httpStatus) throws Exception {
    JSONArray dataArr = new JSONArray();
    dataArr.put(record);
    JSONObject responseData = new JSONObject();
    responseData.put("status", 0);
    responseData.put("data", dataArr);
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", responseData);
    return new NeoResponse(httpStatus, wrapper);
  }

  private static String nullIfEmpty(String s) {
    return (s == null || s.isEmpty() || "null".equals(s)) ? null : s;
  }

  private static String str(JSONObject body, String key, String fallback) {
    String v = nullIfEmpty(body.optString(key, null));
    return v != null ? v : fallback;
  }
}
