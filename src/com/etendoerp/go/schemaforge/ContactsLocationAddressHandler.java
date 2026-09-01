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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Named;

import org.openbravo.module.bptaxidkey.ViesService;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.core.SessionHandler;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBMessageUtils;
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
  /** Free-text region, resolved server-side against the payload's own country. See {@link #resolveRegionByName}. */
  private static final String FIELD_REGION_NAME = "regionName";
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

    // Capture pre-save key and country before any OBDal saves
    String preSaveKey = queryBPKey(bpId);
    String countryId = nullIfEmpty(body.optString(FIELD_COUNTRY, null));

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

      // Build the response record and optionally inject a tax-key warning message
      JSONObject locationJson = buildRecord(bpLoc, geoLoc);
      checkAndAutoSetTaxKey(locationJson, bpId, countryId, preSaveKey);
      return wrapRecord(locationJson, 201);
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

      // Capture country and pre-save key before applying changes and flushing
      String countryId = nullIfEmpty(body.optString(FIELD_COUNTRY, null));
      String bpId = bpLoc.getBusinessPartner() != null ? bpLoc.getBusinessPartner().getId() : null;
      String preSaveKey = (countryId != null && bpId != null) ? queryBPKey(bpId) : null;

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

      // Build the response record and optionally inject a tax-key warning message
      JSONObject locationJson = buildRecord(bpLoc, geoLoc);
      checkAndAutoSetTaxKey(locationJson, bpId, countryId, preSaveKey);
      return wrapRecord(locationJson, 200);
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
    // ETP-5022: getIdentifier() translates, getName() does not — see the note on
    // country$_identifier below. Safe here because this name is computed for the response
    // only and never written back to the record.
    String country = geoLoc.getCountry() != null ? nullIfEmpty(geoLoc.getCountry().getIdentifier()) : null;
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

    // An explicit id still wins — every existing caller (the Contacts address form, which picks
    // from a selector) sends one and is untouched. `regionName` is the import's entry point.
    String regionId = nullIfEmpty(body.optString(FIELD_REGION, null));
    // trimToNull, not nullIfEmpty: a `regionName` of "   " is visually empty to whoever typed
    // it in a spreadsheet, and nullIfEmpty only rejects "". Left untrimmed it reached
    // resolveRegionByName, which answered null for a blank name, and the region was CLEARED —
    // so a whitespace cell in a re-imported file would erase a province already on the record.
    String regionName = StringUtils.trimToNull(nullIfEmpty(body.optString(FIELD_REGION_NAME, null)));
    if (regionId != null) {
      geoLoc.setRegion(OBDal.getInstance().get(Region.class, regionId));
    } else if (regionName != null) {
      geoLoc.setRegion(resolveRegionByName(regionName, geoLoc.getCountry()));
    } else if (body.has(FIELD_REGION)) {
      // Only the id field clears. `regionName` is set-if-provided: a blank one means "this file
      // says nothing about the province", never "erase it". Clearing stays an explicit
      // `region: null`, which is what the Location modal's selector sends.
      geoLoc.setRegion(null);
    }
  }

  /**
   * A region-resolution failure, phrased the same way whatever the cause.
   *
   * <p>The message is what the import prints on the failing row, so all three refusals name the
   * offending value the same way. One method rather than three assembled strings: the shared
   * prefix would otherwise be spelled out at each throw, and a reworded one would silently
   * disagree with its siblings.
   */
  private static OBException regionFailure(String shown, String detail) {
    return new OBException("The region \"" + shown + "\" " + detail);
  }

  /**
   * Resolves a free-text region name to a {@link Region} of {@code country}.
   *
   * <p>ETP-4997. Before this the CSV/xlsx import resolved the region in the browser and then
   * dropped it in silence. Region names collide across countries ("Córdoba" is both Spanish and
   * Argentine), so the browser-side resolver scoped its candidates by asking
   * {@code GET /sws/neo/contacts/region} for each one's country — an endpoint that does not
   * exist, because no NEO spec exposes a region entity. Every call 404'd, every candidate was
   * filtered out, and the descriptor's {@code if (status === 'auto-resolved')} guard skipped the
   * field with no error at all: the address was created with street, city, postal code and
   * country, and no province, and nothing told the user. Resolving here needs no new endpoint —
   * the country is already in the same payload, and it is the only scope the lookup ever needed.
   *
   * <p>Names are compared trimmed, accent-folded and upper-cased, and that is required by the
   * seed data rather than a nicety: a stock instance carries the 52 Spanish provinces TWICE —
   * once at System level ({@code AD_Client_ID = '0'}) and once for the tenant, the tenant copy
   * with a trailing space ("MADRID "). Both are active and both readable, so an exact match finds
   * two rows and a fuzzy match rates them equally plausible — the second, independent reason the
   * browser could not decide. The tenant's own row wins over the System one, which is how Etendo
   * treats every client-overridable master record; only a real ambiguity inside a single client
   * is refused. Folding accents also lets a hand-typed "Alava" or "A Coruna" match, which the
   * fuzzy search used to absorb and an exact comparison would not.
   *
   * <p>Refuses loudly instead of degrading. A row whose province cannot be resolved now fails
   * with a message naming the region and the country, where before it imported an address that
   * was quietly missing a field — the failure the user can see and fix is worth more than the
   * one they cannot.
   */
  private static Region resolveRegionByName(String rawName, Country country) {
    String wanted = normalizeRegionName(rawName);
    if (wanted == null) {
      return null;
    }
    String shown = rawName.trim();
    if (country == null) {
      throw regionFailure(shown,
          "cannot be resolved without a country: region names are not unique across countries.");
    }
    List<Region> matches = new ArrayList<>();
    for (Region region : OBDal.getInstance()
        .createQuery(Region.class, "country.id = :countryId")
        .setNamedParameter("countryId", country.getId())
        .list()) {
      if (wanted.equals(normalizeRegionName(region.getName()))) {
        matches.add(region);
      }
    }
    if (matches.isEmpty()) {
      throw regionFailure(shown, "does not exist in " + country.getName() + ".");
    }
    if (matches.size() > 1) {
      matches = preferOwnClient(matches);
    }
    if (matches.size() > 1) {
      throw regionFailure(shown, "matches " + matches.size() + " records in "
          + country.getName() + ", so it is ambiguous.");
    }
    return matches.get(0);
  }

  /**
   * Narrows equally-named regions to the ones belonging to the session's own client, when there
   * are any. This is what disambiguates the System copy from the tenant's own.
   */
  private static List<Region> preferOwnClient(List<Region> matches) {
    String currentClientId = OBContext.getOBContext().getCurrentClient().getId();
    List<Region> ownClient = new ArrayList<>();
    for (Region region : matches) {
      if (region.getClient() != null && currentClientId.equals(region.getClient().getId())) {
        ownClient.add(region);
      }
    }
    return ownClient.isEmpty() ? matches : ownClient;
  }

  /**
   * Trimmed, accent-folded, upper-cased region name — {@code null} for a blank one.
   *
   * <p>NFD decomposition splits "Á" into "A" + a combining acute, which the mark class then
   * removes; upper-casing with {@code Locale.ROOT} keeps the result independent of the server's
   * default locale.
   */
  private static String normalizeRegionName(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return Normalizer.normalize(trimmed, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toUpperCase(Locale.ROOT);
  }

  private static void putGeoLocFields(JSONObject locationJson,
      org.openbravo.model.common.geography.Location geoLoc) throws Exception {
    locationJson.put("addressLine1", geoLoc.getAddressLine1() != null ? geoLoc.getAddressLine1() : JSONObject.NULL);
    locationJson.put("addressLine2", geoLoc.getAddressLine2() != null ? geoLoc.getAddressLine2() : JSONObject.NULL);
    locationJson.put("cityName",     geoLoc.getCityName()     != null ? geoLoc.getCityName()     : JSONObject.NULL);
    locationJson.put("postalCode",   geoLoc.getPostalCode()   != null ? geoLoc.getPostalCode()   : JSONObject.NULL);

    if (geoLoc.getCountry() != null) {
      locationJson.put(FIELD_COUNTRY,          geoLoc.getCountry().getId());
      // ETP-5022: getIdentifier(), NOT getName(). getName() is the plain Hibernate getter —
      // it calls get(prop) with no language, so it never consults C_Country_Trl and returns
      // the base-language name ("Spain") even when the request carries Accept-Language: es_ES.
      // getIdentifier() goes through IdentifierProvider, which passes the record id and so
      // resolves the translation. Country's identifier is a single column (Name), so the text
      // shown is unchanged apart from being translated.
      // Region is deliberately left on getName(): C_Region has no _Trl table, so there is
      // nothing to translate and the two calls would be equivalent.
      locationJson.put("country$_identifier",  geoLoc.getCountry().getIdentifier());
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

  // ------------------------------------------------------------------ tax key helpers

  /**
   * Reads the current {@code em_obtik_tax_id_key} value for the given BP via JDBC.
   * Called BEFORE flush to capture the pre-save state.
   */
  private static String queryBPKey(String bpId) {
    if (bpId == null || bpId.isEmpty()) {
      return null;
    }
    try {
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT em_obtik_tax_id_key FROM c_bpartner WHERE c_bpartner_id = ?")) {
        ps.setString(1, bpId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString("em_obtik_tax_id_key") : null;
        }
      }
    } catch (Exception e) {
      log.warn("ContactsLocationAddressHandler: could not query BP key for bpId={}: {}", bpId, e.getMessage());
      return null;
    }
  }

  /**
   * Checks whether the BP qualifies for auto-promotion to key='2' (NOI) and, if so,
   * performs the JDBC update, calls VIES, and injects a warning message into the response.
   *
   * <p>Conditions (all must be true):
   * <ol>
   *   <li>{@code countryId} is non-null (a country was included in the request).</li>
   *   <li>{@code preSaveKey} was not already {@code '2'}.</li>
   *   <li>The country has {@code em_eucntry_iseucountry='Y'} and {@code countrycode != 'ES'}.</li>
   *   <li>The BP's {@code taxid} starts with a 2-char prefix that maps to an EU country (not ES).</li>
   * </ol>
   *
   * <p>Called AFTER flush. The observer ({@link TaxIDKeyAutoSetObserver}) may also fire for
   * Classic; this handler is the authoritative path for Go and makes the mutation idempotent.
   */
  private static void checkAndAutoSetTaxKey(JSONObject locationJson, String bpId,
      String countryId, String preSaveKey) {
    if (bpId == null || bpId.isEmpty() || countryId == null || "2".equals(preSaveKey)) {
      return;
    }
    try {
      Connection conn = OBDal.getInstance().getConnection();

      // Check 1: Is the country EU and not Spain?
      boolean isEuNotEs = false;
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT em_eucntry_iseucountry, countrycode FROM c_country WHERE c_country_id = ?")) {
        ps.setString(1, countryId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            isEuNotEs = "Y".equalsIgnoreCase(rs.getString("em_eucntry_iseucountry"))
                && !"ES".equalsIgnoreCase(rs.getString("countrycode"));
          }
        }
      }
      if (!isEuNotEs) {
        return;
      }

      // Check 2: Get BP taxId
      String taxId = null;
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT taxid FROM c_bpartner WHERE c_bpartner_id = ?")) {
        ps.setString(1, bpId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            taxId = rs.getString("taxid");
          }
        }
      }
      if (taxId == null || taxId.length() < 2) {
        return;
      }

      // Check 3: taxId prefix maps to an EU country (not ES)
      int prefixMatchCount = 0;
      try (PreparedStatement ps = conn.prepareStatement(
          "SELECT COUNT(*) FROM c_country"
          + " WHERE UPPER(countrycode) = UPPER(SUBSTRING(?, 1, 2))"
          + " AND em_eucntry_iseucountry = 'Y'"
          + " AND UPPER(countrycode) != 'ES'")) {
        ps.setString(1, taxId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            prefixMatchCount = rs.getInt(1);
          }
        }
      }
      if (prefixMatchCount == 0) {
        return;
      }

      // All conditions met — set key to '2' (NOI)
      try (PreparedStatement ps = conn.prepareStatement(
          "UPDATE c_bpartner SET em_obtik_tax_id_key = '2' WHERE c_bpartner_id = ?")) {
        ps.setString(1, bpId);
        ps.executeUpdate();
      }

      // Call VIES and persist the result
      ViesService.ViesResult vies = ViesService.checkVat(taxId);
      String viesStatus = null;
      if (!ViesService.STATUS_PENDING.equals(vies.status)) {
        viesStatus = vies.status;
        try (PreparedStatement ps = conn.prepareStatement(
            "UPDATE c_bpartner SET em_obtik_viesstatus = ? WHERE c_bpartner_id = ?")) {
          ps.setString(1, viesStatus);
          ps.setString(2, bpId);
          ps.executeUpdate();
        }
      }

      // Inject warning message into the response
      String viesLabel;
      if ("V".equals(viesStatus)) {
        viesLabel = OBMessageUtils.messageBD("OBTIK_ViesStatusValid");
      } else if ("I".equals(viesStatus)) {
        viesLabel = OBMessageUtils.messageBD("OBTIK_ViesStatusInvalid");
      } else {
        viesLabel = OBMessageUtils.messageBD("OBTIK_ViesStatusUnverified");
      }

      JSONObject msg = new JSONObject();
      msg.put("type", "warning");
      msg.put("title", OBMessageUtils.messageBD("OBTIK_TaxKeyAutoSetTitle"));
      msg.put("text", OBMessageUtils.messageBD("OBTIK_TaxKeyAutoSetText") + viesLabel + ".");

      JSONArray messages = new JSONArray();
      messages.put(msg);
      locationJson.put("messages", messages);

    } catch (Exception e) {
      log.warn("ContactsLocationAddressHandler: error in checkAndAutoSetTaxKey for bpId={}: {}", bpId, e.getMessage());
    }
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
