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
import org.openbravo.model.common.geography.Country;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.common.geography.Region;

/**
 * NeoHandler for the tab-less {@code location} entity in the {@code warehouse} spec.
 *
 * <p>Manages a plain C_Location (physical address) with NO C_BPartner_Location link, so the
 * Warehouse window's {@code Location / Address} field can create and edit its address inline
 * (ETP-4526). The frontend {@code LocationEditorModal} (saveMode="location") calls:
 *
 * <ul>
 *   <li>POST /warehouse/location        — creates a C_Location, returns its id + display name</li>
 *   <li>PUT  /warehouse/location/{id}   — updates the C_Location by id</li>
 *   <li>GET  /warehouse/location/{id}   — returns the address fields to pre-populate the modal</li>
 * </ul>
 *
 * <p>Country/Region pickers are served generically by {@link NeoSelectorService} from the
 * entity's ETGO_SF_FIELD rows (C_Country_ID / C_Region_ID) — no code here.
 *
 * <p>The entity has no AD_Tab, so there is no generic-CRUD fallback: this handler fully owns
 * POST, PUT and GET-by-id. Any other method returns 405.
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'warehouseLocationHandler'} on the ETGO_SF_ENTITY
 * record for {@code location} in the {@code warehouse} spec. Uses {@code @Named} only (never a
 * normal CDI scope), so handler discovery via {@code @Named} on the concrete class works.
 */
@Named("warehouseLocationHandler")
public class WarehouseLocationHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(WarehouseLocationHandler.class);
  private static final String FIELD_COUNTRY = "country";
  private static final String FIELD_REGION = "region";
  private static final String FIELD_RESPONSE = "response";
  private static final String FIELD_DATA = "data";

  @Override
  public NeoResponse handle(NeoContext ctx) {
    // This handler is invoked as a pre-hook for EVERY sub-endpoint on the entity, not just
    // plain CRUD (selectors, callout, defaults, evaluate-display all route through the same
    // Java_Qualifier). Only take over the record-level CRUD path; let every other endpoint
    // type fall through to its generic default (e.g. NeoSelectorService for country/region).
    if (ctx.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    String method = ctx.getHttpMethod();
    String recordId = ctx.getRecordId();
    try {
      if ("POST".equals(method) && recordId == null) {
        return handleCreate(ctx);
      }
      if ("PUT".equals(method) && recordId != null) {
        return handleUpdate(ctx);
      }
      if ("GET".equals(method) && recordId != null) {
        return handleGetById(recordId);
      }
    } catch (Exception e) {
      SessionHandler.getInstance().rollback();
      log.error("WarehouseLocationHandler error in {}", method, e);
      return NeoResponse.error(500, "Location handler error: " + e.getMessage());
    }
    // The entity is tab-less: there is no default CRUD to fall through to for anything else
    // (GET list, DELETE) on the CRUD path itself.
    return NeoResponse.error(405, "Unsupported operation on location: " + method);
  }

  // ------------------------------------------------------------------ create

  private NeoResponse handleCreate(NeoContext ctx) throws Exception {
    JSONObject body = ctx.getRequestBody();
    OBContext obCtx = ctx.getObContext();

    OBContext.setAdminMode(true);
    try {
      Location geoLoc = OBProvider.getInstance().get(Location.class);
      geoLoc.setClient(OBDal.getInstance().get(
          org.openbravo.model.ad.system.Client.class, obCtx.getCurrentClient().getId()));
      geoLoc.setOrganization(OBDal.getInstance().get(
          org.openbravo.model.common.enterprise.Organization.class, obCtx.getCurrentOrganization().getId()));
      geoLoc.setActive(Boolean.TRUE);
      applyGeoLocFields(body, geoLoc);
      OBDal.getInstance().save(geoLoc);
      OBDal.getInstance().flush();

      return wrapRecord(buildRecord(geoLoc), 201);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ------------------------------------------------------------------ update

  private NeoResponse handleUpdate(NeoContext ctx) throws Exception {
    JSONObject body = ctx.getRequestBody();
    String locId = ctx.getRecordId();

    OBContext.setAdminMode(true);
    try {
      Location geoLoc = OBDal.getInstance().get(Location.class, locId);
      if (geoLoc == null) {
        return NeoResponse.error(404, "Location not found: " + locId);
      }
      applyGeoLocFields(body, geoLoc);
      OBDal.getInstance().flush();

      return wrapRecord(buildRecord(geoLoc), 200);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ------------------------------------------------------------------ get by id

  private NeoResponse handleGetById(String locId) throws Exception {
    OBContext.setAdminMode(true);
    try {
      Location geoLoc = OBDal.getInstance().get(Location.class, locId);
      if (geoLoc == null) {
        return NeoResponse.error(404, "Location not found: " + locId);
      }
      return wrapRecord(buildRecord(geoLoc), 200);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  // ------------------------------------------------------------------ field mapping

  private static void applyGeoLocFields(JSONObject body, Location geoLoc) {
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

    if (body.has(FIELD_REGION)) {
      String regionId = nullIfEmpty(body.optString(FIELD_REGION, null));
      geoLoc.setRegion(regionId != null ? OBDal.getInstance().get(Region.class, regionId) : null);
    }
  }

  private static JSONObject buildRecord(Location geoLoc) throws Exception {
    JSONObject json = new JSONObject();
    json.put("id", geoLoc.getId());
    json.put("name", buildDisplayName(geoLoc));
    json.put("addressLine1", orNull(geoLoc.getAddressLine1()));
    json.put("addressLine2", orNull(geoLoc.getAddressLine2()));
    json.put("cityName", orNull(geoLoc.getCityName()));
    json.put("postalCode", orNull(geoLoc.getPostalCode()));
    if (geoLoc.getCountry() != null) {
      json.put(FIELD_COUNTRY, geoLoc.getCountry().getId());
      // ETP-5022: getIdentifier(), NOT getName(). getName() is the plain Hibernate getter —
      // it calls get(prop) with no language, so it never consults C_Country_Trl and returns
      // the base-language name ("Spain") even when the request carries Accept-Language: es_ES.
      // getIdentifier() goes through IdentifierProvider, which passes the record id and so
      // resolves the translation. Country's identifier is a single column (Name), so the text
      // shown is unchanged apart from being translated.
      // Region is deliberately left on getName(): C_Region has no _Trl table, so there is
      // nothing to translate and the two calls would be equivalent.
      json.put("country$_identifier", geoLoc.getCountry().getIdentifier());
    } else {
      json.put(FIELD_COUNTRY, JSONObject.NULL);
      json.put("country$_identifier", JSONObject.NULL);
    }
    if (geoLoc.getRegion() != null) {
      json.put(FIELD_REGION, geoLoc.getRegion().getId());
      json.put("region$_identifier", geoLoc.getRegion().getName());
    } else {
      json.put(FIELD_REGION, JSONObject.NULL);
      json.put("region$_identifier", JSONObject.NULL);
    }
    return json;
  }

  private static NeoResponse wrapRecord(JSONObject json, int httpStatus) throws Exception {
    JSONArray dataArr = new JSONArray();
    dataArr.put(json);
    JSONObject responseData = new JSONObject();
    responseData.put("status", 0);
    responseData.put(FIELD_DATA, dataArr);
    JSONObject wrapper = new JSONObject();
    wrapper.put(FIELD_RESPONSE, responseData);
    return new NeoResponse(httpStatus, wrapper);
  }

  // ------------------------------------------------------------------ helpers

  private static String buildDisplayName(Location geoLoc) {
    String result = joinNonNull(nullIfEmpty(geoLoc.getCityName()), nullIfEmpty(geoLoc.getAddressLine1()));
    if (result != null) {
      return result;
    }
    String region = geoLoc.getRegion() != null ? nullIfEmpty(geoLoc.getRegion().getName()) : null;
    String country = geoLoc.getCountry() != null ? nullIfEmpty(geoLoc.getCountry().getName()) : null;
    result = joinNonNull(region, country);
    return result != null ? result : "Location";
  }

  private static Object orNull(String s) {
    return s != null ? s : JSONObject.NULL;
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
}
