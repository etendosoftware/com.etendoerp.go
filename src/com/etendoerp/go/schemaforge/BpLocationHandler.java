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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.geography.Location;

import com.etendoerp.go.schemaforge.util.StructuredAddressComposer;
import com.etendoerp.go.schemaforge.util.StructuredAddressComposer.ComposedAddress;

/**
 * NeoHandler for the {@code bpLocation} entity of the {@code bp-location} spec (table
 * {@code C_Location}, AD Window 121 "Location" — inactive in the menu, but its NEO Headless API is
 * served regardless of menu visibility, confirmed reachable via MCP: {@code etendo_create} /
 * {@code etendo_update} / {@code etendo_delete} are all enabled).
 *
 * <p><b>ETP-5806 follow-up.</b> Every other address surface in this ticket (Contacts, Organization
 * via {@code WarehouseLocationHandler}, Warehouse itself) adds the 3 structured SEPA fields
 * ({@code streetName}/{@code buildingNumber}/{@code buildingName}) and the same mandatory-together
 * validation. {@code bp-location} is a direct, un-wrapped mapping onto {@code C_Location} driven
 * entirely by generic CRUD (unlike the others, it has an {@code AD_Tab} and no prior dedicated
 * handler) — found to accept plain-text {@code addressLine1}/{@code addressLine2} writes with none
 * of this ticket's rules applied, letting an MCP agent bypass them entirely through this window.
 *
 * <p><b>Why this intercepts rather than reconfiguring ETGO_SF_FIELD.</b> The 3 raw AD columns
 * ({@code EM_OBSEPA_StrtNm}/{@code BldgNb}/{@code BldgNm}) have no {@code ETGO_SF_FIELD} row for
 * this entity (confirmed live: {@code etendo_get} reports them as {@code unknownFields} even
 * though {@code etendo_schema} lists them — the schema walks every active {@code AD_Column} of the
 * table regardless of curation, but {@link NeoFieldFilter} only treats a column as included/
 * writable once a curated row exists). Adding that curation safely requires a push-to-neo run,
 * which found the live {@code ETGO_SF_ENTITY.Name = 'bpLocation'} row to be a hand-populated
 * pipeline orphan (see {@code artifacts/bp-location/}) — not something to risk rewriting as part of
 * this fix. This handler instead owns the 3 fields and the computed {@code Address1}/{@code
 * Address2} lines directly via DAL, the same way {@link AbstractInvoiceHeaderHandler} persists a
 * read-only computed value that {@code NeoFieldFilter#filterWriteRequest} would otherwise strip: a
 * pre-hook validates and remembers the effective values, a post-hook writes them once the base
 * record exists (POST) or has been updated (PUT), and enriches every GET so a caller sees them
 * under the SAME names the other 3 handlers use ({@code streetName}/{@code buildingNumber}/
 * {@code buildingName}) rather than the raw DAL property names
 * ({@code oBSEPAStreetName}/{@code oBSEPABuildingNumber}/{@code oBSEPABuildingName}) — see
 * {@code AddressVirtualSelectorPolicy#PUBLISHED_NAME_OVERRIDES} for the matching
 * {@code etendo_schema} fix.
 *
 * <p>Country/Region/City/Postal and the base record lifecycle (id generation, client/org, active
 * flag) are left to the generic CRUD fallthrough (this handler returns {@code null} for those)
 * exactly as before this change — only the structured-address-for-sepa slice is intercepted.
 *
 * <p>{@code @NeoExtension} only, no {@code @Named}: a brand-new handler has nothing to migrate and
 * CLAUDE.md's NeoHandler Pattern section asks for the annotation-only binding on new code (see
 * {@code SalesOrderLineHandler} for the same choice).
 */
@NeoExtension(spec = "bp-location", entity = "bpLocation")
public class BpLocationHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(BpLocationHandler.class);

  private static final String FIELD_STREET_NAME = "streetName";
  private static final String FIELD_BUILDING_NUMBER = "buildingNumber";
  private static final String FIELD_BUILDING_NAME = "buildingName";
  private static final String FIELD_ADDRESS_LINE_1 = "addressLine1";
  private static final String FIELD_ADDRESS_LINE_2 = "addressLine2";
  private static final String FIELD_CITY_NAME = "cityName";
  private static final String FIELD_COUNTRY = "country";
  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_GET = "GET";

  // Set by handle() (pre-hook), consumed by afterHandle() (post-hook) of the SAME request. Safe
  // because a NeoHandler instance is resolved fresh per request (no @Named/@ApplicationScoped —
  // see class javadoc), the same pattern AbstractInvoiceHeaderHandler uses for
  // pendingSiiAuthorizationno.
  private boolean hasPendingWrite;
  private String pendingStreetName;
  private String pendingBuildingNumber;
  private String pendingBuildingName;
  private String pendingAddress1;
  private String pendingAddress2;

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context == null || context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    // ETP-5806 — `structured-address-for-sepa` off: never intercept. The 3 fields fall through to
    // generic CRUD, which already drops them (no ETGO_SF_FIELD row for this entity — see class
    // javadoc), so a stray value from a stale client is silently ignored, never persisted and
    // never rejected, exactly as the flag's write-path contract requires.
    if (!StructuredAddressComposer.isEnabled()) {
      return null;
    }
    String method = context.getHttpMethod();
    String recordId = context.getRecordId();
    if (!isCreateOrUpdate(method, recordId)) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }

    boolean isUpdate = METHOD_PUT.equals(method) && recordId != null;
    Location existing = isUpdate ? loadExisting(recordId) : null;
    if (isUpdate && existing == null) {
      return NeoResponse.error(404, "Location not found: " + recordId);
    }

    return prepareStructuredAddress(body, existing);
  }

  private static boolean isCreateOrUpdate(String method, String recordId) {
    boolean isCreate = METHOD_POST.equals(method) && recordId == null;
    boolean isUpdate = METHOD_PUT.equals(method) && recordId != null;
    return isCreate || isUpdate;
  }

  /**
   * Resolves the effective structured-address-for-sepa fields (body value, or {@code existing}'s own value
   * when the body omits the key), validates them, and — when valid — stashes the result on
   * {@link #pendingStreetName} and its siblings for {@link #afterHandle} to persist.
   *
   * @return the error response when validation fails; {@code null} when the request may proceed
   */
  private NeoResponse prepareStructuredAddress(JSONObject body, Location existing) {
    String streetName = effective(body, FIELD_STREET_NAME,
        existing != null ? existing.getOBSEPAStreetName() : null);
    String buildingNumber = effective(body, FIELD_BUILDING_NUMBER,
        existing != null ? existing.getOBSEPABuildingNumber() : null);
    String buildingName = effective(body, FIELD_BUILDING_NAME,
        existing != null ? existing.getOBSEPABuildingName() : null);
    String cityName = effective(body, FIELD_CITY_NAME,
        existing != null ? existing.getCityName() : null);
    String countryId = effective(body, FIELD_COUNTRY,
        existing != null && existing.getCountry() != null ? existing.getCountry().getId() : null);

    NeoResponse validationError = validateStructuredAddress(
        streetName, buildingNumber, buildingName, cityName, countryId);
    if (validationError != null) {
      return validationError;
    }

    ComposedAddress composed = StructuredAddressComposer.composeAddressLines(
        streetName, buildingNumber, buildingName);
    pendingStreetName = streetName;
    pendingBuildingNumber = buildingNumber;
    pendingBuildingName = buildingName;
    pendingAddress1 = composed.getAddress1();
    pendingAddress2 = composed.getAddress2();
    hasPendingWrite = true;

    // Let generic CRUD continue to own id generation, client/org, active flag, country/region/
    // city/postal. Whatever raw addressLine1/addressLine2 the body also carries is harmless here:
    // afterHandle() below overwrites both with the composed value once the base record exists.
    return null;
  }

  /**
   * ETP-5806 — same product decision as Contacts/Warehouse: Street, Number, City and Country
   * are mandatory together in Etendo Go (stricter than Classic's optional SEPA fields).
   */
  private static NeoResponse validateStructuredAddress(String streetName, String buildingNumber,
      String buildingName, String cityName, String countryId) {
    List<String> missing = StructuredAddressComposer.missingMandatoryFields(
        streetName, buildingNumber, cityName, countryId);
    if (!missing.isEmpty()) {
      return NeoResponse.error(400, "Missing required field(s): " + String.join(", ", missing));
    }
    List<String> tooLong = StructuredAddressComposer.fieldsExceedingMaxLength(
        streetName, buildingNumber, buildingName);
    if (!tooLong.isEmpty()) {
      return NeoResponse.error(400, "Field(s) exceed maximum length: " + String.join(", ", tooLong));
    }
    return null;
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (context == null || context.getEndpointType() != NeoEndpointType.CRUD) {
      return null;
    }
    // ETP-5806 — off: never enrich a GET with the 3 fields (they must be omitted from reads, not
    // just unwritable) and never run the pending-write persistence (handle() never set it when
    // off, so hasPendingWrite is always false here anyway — this is belt-and-suspenders).
    if (!StructuredAddressComposer.isEnabled()) {
      return null;
    }
    String method = context.getHttpMethod();
    NeoResponse previousResult = context.getPreviousResult();
    if (previousResult == null || previousResult.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = previousResult.getBody();
      JSONObject responseWrapper = body.optJSONObject("response");
      if (responseWrapper == null) {
        return null;
      }
      JSONArray dataArr = responseWrapper.optJSONArray("data");
      if (dataArr == null) {
        return null;
      }

      if (hasPendingWrite && (METHOD_POST.equals(method) || METHOD_PUT.equals(method))) {
        persistPendingFields(dataArr);
      } else if (METHOD_GET.equals(method)) {
        enrichWithStructuredFields(dataArr);
      } else {
        return null;
      }
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("BpLocationHandler error post-processing {}: {}", method, e.getMessage(), e);
      return NeoResponse.error(500, "Location handler error: " + e.getMessage());
    }
  }

  // ------------------------------------------------------------------ helpers

  /**
   * Writes the 3 structured fields and the composed address lines directly on the DAL record —
   * bypassing {@code NeoFieldFilter}, which has no curated row for any of the 5 (see class
   * javadoc) — then mirrors the final values into the response under the published names.
   */
  private void persistPendingFields(JSONArray dataArr) throws Exception {
    if (dataArr.length() == 0) {
      return;
    }
    JSONObject recordJson = dataArr.getJSONObject(0);
    String id = recordJson.optString("id", null);
    if (id == null) {
      return;
    }
    OBContext.setAdminMode(true);
    try {
      Location geoLoc = OBDal.getInstance().get(Location.class, id);
      if (geoLoc == null) {
        log.warn("BpLocationHandler: record {} vanished before structured-address-for-sepa write", id);
        return;
      }
      geoLoc.setOBSEPAStreetName(pendingStreetName);
      geoLoc.setOBSEPABuildingNumber(pendingBuildingNumber);
      geoLoc.setOBSEPABuildingName(pendingBuildingName);
      geoLoc.setAddressLine1(pendingAddress1);
      geoLoc.setAddressLine2(pendingAddress2);
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
    recordJson.put(FIELD_STREET_NAME, orNull(pendingStreetName));
    recordJson.put(FIELD_BUILDING_NUMBER, orNull(pendingBuildingNumber));
    recordJson.put(FIELD_BUILDING_NAME, orNull(pendingBuildingName));
    recordJson.put(FIELD_ADDRESS_LINE_1, orNull(pendingAddress1));
    recordJson.put(FIELD_ADDRESS_LINE_2, orNull(pendingAddress2));
  }

  /**
   * Adds {@code streetName}/{@code buildingNumber}/{@code buildingName} to every row of a GET
   * (list or by id) response, read directly from the DAL since {@code NeoFieldFilter} drops them.
   */
  private void enrichWithStructuredFields(JSONArray dataArr) throws Exception {
    if (dataArr.length() == 0) {
      return;
    }
    OBContext.setAdminMode(true);
    try {
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject recordJson = dataArr.optJSONObject(i);
        if (recordJson != null) {
          enrichRecord(recordJson);
        }
      }
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Enriches a single GET row with the 3 structured fields; a no-op when they cannot resolve. */
  private void enrichRecord(JSONObject recordJson) throws Exception {
    String id = recordJson.optString("id", null);
    if (id == null) {
      return;
    }
    Location geoLoc = OBDal.getInstance().get(Location.class, id);
    if (geoLoc == null) {
      return;
    }
    recordJson.put(FIELD_STREET_NAME, orNull(geoLoc.getOBSEPAStreetName()));
    recordJson.put(FIELD_BUILDING_NUMBER, orNull(geoLoc.getOBSEPABuildingNumber()));
    recordJson.put(FIELD_BUILDING_NAME, orNull(geoLoc.getOBSEPABuildingName()));
  }

  private Location loadExisting(String recordId) {
    OBContext.setAdminMode(true);
    try {
      return OBDal.getInstance().get(Location.class, recordId);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The value the caller sent for {@code key} when the body carries it (including an explicit
   * {@code null}/blank, which clears it), falling back to {@code currentValue} only when the key
   * is entirely absent from the body — the usual PUT semantics for an unspecified field.
   */
  private static String effective(JSONObject body, String key, String currentValue) {
    if (!body.has(key)) {
      return currentValue;
    }
    return nullIfEmpty(body.optString(key, null));
  }

  private static Object orNull(String s) {
    return s != null ? s : JSONObject.NULL;
  }

  private static String nullIfEmpty(String s) {
    return (s == null || s.isEmpty() || "null".equals(s)) ? null : s;
  }
}
