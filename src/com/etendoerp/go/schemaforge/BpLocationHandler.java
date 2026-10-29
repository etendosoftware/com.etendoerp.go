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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.geography.Location;

import com.etendoerp.go.common.GoAccountResolver;
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
 * <p><b>Why this intercepts rather than reconfiguring ETGO_SF_FIELD.</b> This handler owns the 3
 * fields and the computed {@code Address1}/{@code Address2} lines directly via DAL, the same way
 * {@link AbstractInvoiceHeaderHandler} persists a read-only computed value that
 * {@code NeoFieldFilter#filterWriteRequest} would otherwise strip: a pre-hook validates and
 * remembers the effective values, a post-hook writes them once the base record exists (POST) or
 * has been updated (PUT/PATCH — see {@link #isUpdateMethod}), and enriches every GET so a caller
 * sees them under the SAME names the other 3 handlers use
 * ({@code streetName}/{@code buildingNumber}/{@code buildingName}) <em>as well as</em> the curated
 * API key the SPA reads ({@code oBSEPAStreetName}/{@code oBSEPABuildingNumber}/
 * {@code oBSEPABuildingName}) — see {@code AddressVirtualSelectorPolicy#PUBLISHED_NAME_OVERRIDES}
 * for the matching {@code etendo_schema} fix. Composition and the mandatory-together rule have to
 * run somewhere, and curation alone cannot express either.
 *
 * <p><b>Both spellings, on the way in as well as on the way out (ETP-5806 follow-up).</b> The
 * curated API key for this entity IS the DAL property name — {@code NeoFieldFilter} registers an
 * alias only when the Java qualifier differs from the property name, and for these 3 rows it does
 * not — so REST and the SPA send {@code oBSEPAStreetName} while MCP sends {@code streetName}.
 * Reading only the published spelling answered a valid REST create with
 * {@code 400 Missing required field(s)} and silently reverted a valid REST update. Every key the
 * flag-off path strips is now a key the flag-on path reads: see {@link #KEYS_STREET_NAME}.
 *
 * <p><b>The 3 columns ARE curated — and that is why the flag-off branch strips rather than just
 * declines (ETP-5806 follow-up).</b> An earlier revision of this javadoc asserted the opposite
 * ("no {@code ETGO_SF_FIELD} row for this entity, confirmed live"), and the flag-off fall-through
 * rested on it. It is false: {@code src-db/database/sourcedata/ETGO_SF_FIELD.xml} carries three
 * committed rows for entity {@code 643B950E1F62480D87DD5D0C2437139A} with {@code ISINCLUDED=Y},
 * {@code VISIBILITY=editable} and {@code ISREADONLY=N}, so {@code update.database} reproduces them
 * on every instance. (A grep for {@code EM_OBSEPA} over that file finds nothing — it references
 * columns by {@code AD_COLUMN_ID} only — which is how the absence was "confirmed".) With a curated
 * editable row, nothing downstream drops a value a caller sends under the DAL property name:
 * {@code NeoFieldFilter#filterWriteRequest} keeps it (it is writable) and the MCP write gate
 * passes it (it is neither excluded nor read-only, and it resolves to a property, so it is not
 * even reported in {@code unknownFields}). Falling through while off therefore persisted the 3
 * fields ungated on BOTH channels — no mandatory check, no length check, and no
 * {@code Address1}/{@code Address2} recomposition, leaving the structured fields and the legacy
 * address lines inconsistent on a {@code C_Location} that contacts, warehouses and organizations
 * share, and that Classic's SEPA remittance reads
 * ({@code SEPAUtil#getBusinessPartnerAddressError}). {@link #stripStructuredAddressKeys} closes
 * that: off means the keys never reach generic CRUD, which is the "ignore, don't reject" contract
 * the flag was always documented to have. De-curating the rows instead would NOT have closed it —
 * an uncurated column is still accepted by the MCP write gate ("a column with no
 * {@code ETGO_SF_FIELD} row is still accepted — only an explicit exclusion is refused",
 * {@code McpToolRouter}), so the hole would have survived on the MCP channel.
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
  /**
   * Every spelling of one logical field a caller can address it by, in precedence order:
   * the PUBLISHED name first, then the DAL property name, then the raw AD column name.
   *
   * <p><b>ETP-5806 follow-up — the flag-on path reads all three, not just the published one.</b>
   * The flag-off path has stripped all three since {@code 7ed80a64d}; the flag-on path read only
   * the published spelling, and the two states were therefore gated on different key sets. That
   * asymmetry was a live defect, not a theoretical one, because the curated API key for this
   * entity <b>is the DAL property name</b>: {@code NeoFieldFilter#processFieldMappings} registers
   * an alias only when the Java qualifier differs from the property name, and for these 3 rows it
   * does not — so {@code artifacts/bp-location/contract.json} publishes
   * {@code "apiKey": "oBSEPAStreetName"} and the generated {@code BpLocationForm.jsx} /
   * {@code BpLocationPage.jsx} send exactly that. Reading only {@code streetName} meant a REST
   * create carrying both structured values was answered
   * {@code 400 Missing required field(s): streetName, buildingNumber}, and a REST update was
   * persisted by generic CRUD and then silently reverted by {@link #persistPendingFields} from the
   * pre-update record, 200 OK and no log line.
   *
   * <p>Which spelling actually arrives depends on the channel, so no single one is "the" wire
   * format: the SPA and REST send the curated key (the DAL property name); an MCP agent sends the
   * published name {@code etendo_schema} advertises
   * ({@code AddressVirtualSelectorPolicy#PUBLISHED_NAME_OVERRIDES}), which
   * {@code mapFieldsToDalProperties} passes through untouched because it resolves to no property;
   * an MCP agent that sends the DAL property or the DB column gets the property name; and
   * {@code /batch} + {@code etendo_batch} run no mapping at all, so whatever the agent typed —
   * including the raw column — reaches the body verbatim.
   */
  private static final List<String> KEYS_STREET_NAME = List.of(
      FIELD_STREET_NAME, Location.PROPERTY_OBSEPASTREETNAME, "EM_OBSEPA_StrtNm");
  private static final List<String> KEYS_BUILDING_NUMBER = List.of(
      FIELD_BUILDING_NUMBER, Location.PROPERTY_OBSEPABUILDINGNUMBER, "EM_OBSEPA_BldgNb");
  private static final List<String> KEYS_BUILDING_NAME = List.of(
      FIELD_BUILDING_NAME, Location.PROPERTY_OBSEPABUILDINGNAME, "EM_OBSEPA_BldgNm");

  /**
   * The two legacy fields the structured-address mandatory rule also reads. Their curated API key,
   * DAL property name and published name all coincide ({@code cityName}/{@code country}), so only
   * the raw AD column is a second spelling — and it is reachable: {@code /batch} and
   * {@code etendo_batch} do not map keys, so {@code {"City": "Madrid", "C_Country_ID": "…"}} is a
   * legitimate batch body that generic CRUD resolves downstream. Without the alias the mandatory
   * check saw neither and answered 400 on a body that carried both.
   */
  private static final List<String> KEYS_CITY_NAME = List.of(FIELD_CITY_NAME, "City");
  private static final List<String> KEYS_COUNTRY = List.of(FIELD_COUNTRY, "C_Country_ID");

  /**
   * Every spelling of the 3 structured SEPA fields a write body can carry, and the only set either
   * flag state acts on: {@link #removeStructuredAddressKeys} strips exactly these while the flag
   * is off, {@link #prepareStructuredAddress} reads exactly these and then strips exactly these
   * while it is on, and {@link #stripStructuredAddressFromResponse} removes exactly these from a
   * read while it is off. One list, so the two states can no longer diverge.
   */
  private static final List<String> STRUCTURED_ADDRESS_WRITE_KEYS = concat(
      KEYS_STREET_NAME, KEYS_BUILDING_NUMBER, KEYS_BUILDING_NAME);

  private static List<String> concat(List<String> a, List<String> b, List<String> c) {
    List<String> all = new ArrayList<>(a.size() + b.size() + c.size());
    all.addAll(a);
    all.addAll(b);
    all.addAll(c);
    return List.copyOf(all);
  }

  /**
   * The SmartClient-style envelope a write body may be wrapped in. {@code NeoFieldFilter#filterBody}
   * unwraps it defensively ("if client sends {@code {"data": {...}}}") and returns the INNER object
   * as the body generic CRUD then filters and persists, so a key that only exists inside the
   * envelope still reaches the row. Anything this handler reads or removes therefore has to look in
   * both places — see {@link #writeSections}.
   */
  private static final String KEY_DATA = "data";

  private static final String METHOD_POST = "POST";
  private static final String METHOD_PUT = "PUT";
  private static final String METHOD_PATCH = "PATCH";
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
    // ETP-5806 — `structured-address-for-sepa` off FOR THIS ACCOUNT: never intercept, but strip
    // the 3 fields from the body first. They ARE curated (editable, not read-only), so generic
    // CRUD would otherwise persist them ungated on both channels — see the class javadoc for why
    // the opposite claim here was wrong. Stripping keeps the flag's write-path contract exactly as
    // documented: a stray value from a stale client is silently ignored, never persisted and never
    // rejected. Resolved against the requesting account (staged rollout) so this path and the
    // capability signal the UI renders from agree. No ordering trap here (unlike
    // SFWindowAccessMap): NeoServlet authenticates before dispatch, and OBContext.setAdminMode
    // only substitutes the admin identity when there is no context at all, so the lookup still
    // sees the real caller.
    if (!StructuredAddressComposer.isEnabled(GoAccountResolver.resolveCurrentAccountEmail())) {
      stripStructuredAddressKeys(context);
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

    boolean isUpdate = isUpdateMethod(method) && recordId != null;
    Location existing = isUpdate ? loadExisting(recordId) : null;
    if (isUpdate && existing == null) {
      return NeoResponse.error(404, "Location not found: " + recordId);
    }

    return prepareStructuredAddress(writeSections(body), existing);
  }

  /**
   * Every object of a write body that can carry the handler's fields: the body itself and, when
   * present, its {@code {"data": {...}}} envelope — both, never one or the other.
   *
   * <p>This is the "mutates one object while persistence reads another" trap (ETP-5806 follow-up).
   * {@code NeoFieldFilter#filterBody} unwraps a {@code data} envelope and returns the inner object,
   * which {@code NeoCrudHandler#executeCreate}/{@code executeUpdate} then persist; so a pre-hook
   * that only looked at the top level saw none of the keys of
   * {@code PUT /sws/neo/bp-location/bpLocation/{id} {"data":{"oBSEPAStreetName":…}}}, removed
   * nothing, and the field was written ungated on REST and, through
   * {@code BatchService#createRecord}, on {@code /batch} and {@code etendo_batch} too. Both shapes
   * are handled in one pass because nothing guarantees only one is present: a body may carry a key
   * at the top level AND inside the envelope, and leaving either behind reopens the hole.
   *
   * @param body the raw request body; {@code null} yields an empty list
   * @return the sections to read from and strip, outermost first
   */
  private static List<JSONObject> writeSections(JSONObject body) {
    if (body == null) {
      return List.of();
    }
    JSONObject wrapped = body.optJSONObject(KEY_DATA);
    return wrapped != null ? List.of(body, wrapped) : List.of(body);
  }

  /**
   * Removes the 3 structured SEPA fields from a write body the handler is about to decline
   * (ETP-5806 follow-up), so generic CRUD never sees them while the flag is off for this account.
   *
   * <p>Mutating the context body is how a pre-hook reaches the persisted payload on both channels:
   * REST filters and persists {@code context.getRequestBody()} itself
   * ({@code NeoCrudHandler#executeUpdate}/{@code executeCreate}), and MCP builds the hook context
   * over the very {@code filteredBody} it goes on to wrap for core ({@code McpToolRouter}). Reads
   * are left alone — a GET has no write body, and {@code afterHandle} already declines to enrich
   * one while off.
   *
   * <p>Every section of the body, not just the top level: a {@code {"data": {...}}} envelope is
   * unwrapped by {@code NeoFieldFilter} and it is the INNER object that gets persisted, so
   * stripping only the outer one left the hole open on REST and on {@code /batch} — see
   * {@link #writeSections}.
   *
   * @param context the CRUD request being declined; a {@code null} or read-only body is a no-op
   */
  private static void stripStructuredAddressKeys(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null || !isCreateOrUpdate(context.getHttpMethod(), context.getRecordId())) {
      return;
    }
    List<String> dropped = removeStructuredAddressKeys(writeSections(body));
    if (!dropped.isEmpty()) {
      log.debug("BpLocationHandler: dropped {} — structured-address-for-sepa is off for this "
          + "account", String.join(", ", dropped));
    }
  }

  /**
   * Removes every spelling of the 3 structured SEPA fields from the given JSON objects.
   *
   * <p>Called from both flag states, which is the point (ETP-5806 follow-up). While OFF it is the
   * "off means off" strip. While ON it runs after {@link #prepareStructuredAddress} has read the
   * values, so that <b>this handler is the only writer of these 3 columns</b>: generic CRUD would
   * otherwise persist the curated editable key on the way in and {@link #persistPendingFields}
   * would overwrite it on the way out, which is the double-write that produced the silent revert
   * when the two steps disagreed about the spelling. With the keys removed there is one write,
   * from one resolved value.
   *
   * @param sections the objects to strip, outermost first
   * @return the keys actually removed, in the order they were found; never {@code null}
   */
  private static List<String> removeStructuredAddressKeys(List<JSONObject> sections) {
    List<String> dropped = new ArrayList<>();
    for (JSONObject section : sections) {
      for (String key : STRUCTURED_ADDRESS_WRITE_KEYS) {
        String present = matchingKey(section, key);
        if (present != null) {
          section.remove(present);
          dropped.add(present);
        }
      }
    }
    return dropped;
  }

  /**
   * The key {@code section} actually carries for {@code alias}, matched exactly first and then
   * ignoring case, or {@code null} when it carries neither.
   *
   * <p>Case-insensitively because AD column resolution is
   * ({@code Entity#getPropertyByColumnName}), so {@code em_obsepa_strtnm} is a key a batch body
   * can legitimately carry and have resolved downstream. JSON itself is case-sensitive, so the
   * caller's own spelling is what gets read or removed — never a normalised one.
   */
  private static String matchingKey(JSONObject section, String alias) {
    if (section.has(alias)) {
      return alias;
    }
    // Jettison's keys() is a raw Iterator; read it as Object and convert, rather than casting to
    // Iterator<String> and taking an unchecked warning for it.
    Iterator<?> keys = section.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      if (key.equalsIgnoreCase(alias)) {
        return key;
      }
    }
    return null;
  }

  /**
   * Whether this method updates an existing record. PUT and PATCH both do: {@code NeoServlet}
   * routes PATCH through the same {@code processRequest}/{@code handleWithHooks} path as PUT, and
   * {@code NeoCrudHandler} runs both through the same {@code executeUpdate}. Leaving PATCH out
   * left the pre-hook a silent no-op on a verb that {@code ETGO_SF_ENTITY} enables for this entity
   * ({@code ISPATCH=Y}) — both while the flag is off (the 3 curated fields reached generic CRUD
   * ungated) and while it is on (no validation, no composition, so the structured fields and the
   * legacy {@code Address1}/{@code Address2} lines drifted apart).
   *
   * <p>No special partial-update handling is needed beyond accepting the verb: {@link #effective}
   * already falls back to the existing record's value for any key the body omits, which IS PATCH
   * semantics, so a legitimate partial body validates against the post-write state rather than
   * being rejected for the fields it does not mention.
   */
  private static boolean isUpdateMethod(String method) {
    return METHOD_PUT.equals(method) || METHOD_PATCH.equals(method);
  }

  private static boolean isCreateOrUpdate(String method, String recordId) {
    boolean isCreate = METHOD_POST.equals(method) && recordId == null;
    boolean isUpdate = isUpdateMethod(method) && recordId != null;
    return isCreate || isUpdate;
  }

  /**
   * Resolves the effective structured-address-for-sepa fields (body value, or {@code existing}'s own value
   * when the body omits the key), validates them, and — when valid — stashes the result on
   * {@link #pendingStreetName} and its siblings for {@link #afterHandle} to persist.
   *
   * <p>Reads from every section of the body ({@link #writeSections}), so the ON path sees exactly
   * the keys the OFF path removes and exactly the keys persistence reads — a {@code data}-wrapped
   * write is validated and composed rather than silently falling back to the existing record.
   *
   * @param sections the body's write sections, outermost first
   * @return the error response when validation fails; {@code null} when the request may proceed
   */
  private NeoResponse prepareStructuredAddress(List<JSONObject> sections, Location existing) {
    String streetName = effective(sections, KEYS_STREET_NAME,
        existing != null ? existing.getOBSEPAStreetName() : null);
    String buildingNumber = effective(sections, KEYS_BUILDING_NUMBER,
        existing != null ? existing.getOBSEPABuildingNumber() : null);
    String buildingName = effective(sections, KEYS_BUILDING_NAME,
        existing != null ? existing.getOBSEPABuildingName() : null);
    String cityName = effective(sections, KEYS_CITY_NAME,
        existing != null ? existing.getCityName() : null);
    String countryId = effective(sections, KEYS_COUNTRY,
        existing != null && existing.getCountry() != null ? existing.getCountry().getId() : null);

    NeoResponse validationError = validateStructuredAddress(
        streetName, buildingNumber, buildingName, cityName, countryId);
    if (validationError != null) {
      return validationError;
    }

    // Read, then remove: from here on this handler is the sole writer of the 3 columns, so generic
    // CRUD cannot persist a value under the curated key that afterHandle then overwrites from a
    // differently-resolved one. See removeStructuredAddressKeys.
    removeStructuredAddressKeys(sections);

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
    // ETP-5806 — off for this account: never enrich a GET with the 3 fields, never run the
    // pending-write persistence (handle() never set it when off, so hasPendingWrite is always
    // false here anyway — belt-and-suspenders), and REMOVE the 3 fields from whatever the generic
    // path is about to answer with. Same per-account evaluation as handle() above, and the same
    // request, so the two agree.
    if (!StructuredAddressComposer.isEnabled(GoAccountResolver.resolveCurrentAccountEmail())) {
      return stripStructuredAddressFromResponse(context);
    }
    String method = context.getHttpMethod();
    NeoResponse previousResult = context.getPreviousResult();
    if (previousResult == null || previousResult.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = previousResult.getBody();
      JSONArray dataArr = dataArrayOf(previousResult);
      if (dataArr == null) {
        return null;
      }

      if (hasPendingWrite && (METHOD_POST.equals(method) || isUpdateMethod(method))) {
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

  /** The {@code response.data} rows of a previous result, or {@code null} when it has none. */
  private static JSONArray dataArrayOf(NeoResponse previousResult) {
    if (previousResult == null || previousResult.getBody() == null) {
      return null;
    }
    JSONObject responseWrapper = previousResult.getBody().optJSONObject("response");
    return responseWrapper == null ? null : responseWrapper.optJSONArray("data");
  }

  /**
   * Removes every spelling of the 3 structured SEPA fields from the rows the generic path is about
   * to answer with, while the flag is off for this account (ETP-5806 follow-up).
   *
   * <p><b>Why a read needs this at all.</b> "Off" has to mean unreadable as well as unwritable, and
   * declining to enrich is not enough on this entity: unlike the three wrapper handlers, the 3
   * columns ARE curated here ({@code ETGO_SF_FIELD}, {@code ISINCLUDED=Y}), so
   * {@code NeoFieldFilter} projects them on its own, under the curated API key
   * ({@code oBSEPAStreetName}…). A {@code GET} therefore returned the structured values to an
   * untargeted account with no handler involvement whatsoever — the exact mirror image of the
   * write hole {@code 7ed80a64d} closed, and invisible for the same reason: a value that is simply
   * present in a response looks like it belongs there.
   *
   * <p>Applies to every CRUD response, not only {@code GET}: a create or update answers with the
   * persisted row through the same projection.
   *
   * @return the trimmed response when something was removed, {@code null} to leave it untouched
   */
  private static NeoResponse stripStructuredAddressFromResponse(NeoContext context) {
    NeoResponse previousResult = context.getPreviousResult();
    JSONArray dataArr = dataArrayOf(previousResult);
    if (dataArr == null) {
      return null;
    }
    List<JSONObject> rows = new ArrayList<>(dataArr.length());
    for (int i = 0; i < dataArr.length(); i++) {
      JSONObject row = dataArr.optJSONObject(i);
      if (row != null) {
        rows.add(row);
      }
    }
    List<String> dropped = removeStructuredAddressKeys(rows);
    if (dropped.isEmpty()) {
      return null;
    }
    log.debug("BpLocationHandler: withheld {} from the response — structured-address-for-sepa is "
        + "off for this account", String.join(", ", dropped));
    return NeoResponse.ok(previousResult.getBody());
  }

  /**
   * Writes the 3 structured fields and the composed address lines directly on the DAL record,
   * then mirrors the final values into the response under the published names.
   *
   * <p>The direct DAL write bypasses {@code NeoFieldFilter}, and all 5 columns do carry curated
   * {@code ETGO_SF_FIELD} rows for this entity (13 in total — see the class javadoc), but for two
   * different reasons: {@code addressLine1}/{@code addressLine2} are curated <b>read-only</b>
   * ({@code ISREADONLY=Y}, {@code VISIBILITY=readOnly}), so the filter strips them from any write
   * body; and the 3 SEPA fields, though curated editable, were removed from the body by
   * {@link #prepareStructuredAddress} once it had read them, precisely so that this write is the
   * only one. Either way the composed value has to be written by this handler.
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
    putStructuredFields(recordJson, pendingStreetName, pendingBuildingNumber, pendingBuildingName);
    recordJson.put(FIELD_ADDRESS_LINE_1, orNull(pendingAddress1));
    recordJson.put(FIELD_ADDRESS_LINE_2, orNull(pendingAddress2));
  }

  /**
   * Adds {@code streetName}/{@code buildingNumber}/{@code buildingName} to every row of a GET
   * (list or by id) response, read directly from the DAL.
   *
   * <p>Not because the filter drops them — with {@code ISINCLUDED=Y} it projects all 3 — but
   * because it projects them under the curated API key ({@code oBSEPAStreetName}…) only. This adds
   * the PUBLISHED aliases the other 3 address handlers and {@code etendo_schema} use, so a caller
   * sees the same value under the spelling its own channel advertises.
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
    putStructuredFields(recordJson, geoLoc.getOBSEPAStreetName(),
        geoLoc.getOBSEPABuildingNumber(), geoLoc.getOBSEPABuildingName());
  }

  /**
   * Writes the 3 structured fields into a response row under BOTH the published name and the
   * curated API key (which on this entity is the DAL property name — see
   * {@link #KEYS_STREET_NAME}).
   *
   * <p>Both, because both are on the wire: an MCP agent reads {@code streetName} from
   * {@code etendo_schema} while the SPA reads {@code oBSEPAStreetName} from the contract. Writing
   * only the published name left the curated key carrying whatever the generic projection had —
   * which, now that {@link #prepareStructuredAddress} strips the key before generic CRUD runs, is
   * the value from before this write (or {@code null} on a create). One value, both spellings.
   */
  private static void putStructuredFields(JSONObject recordJson, String streetName,
      String buildingNumber, String buildingName) throws Exception {
    recordJson.put(FIELD_STREET_NAME, orNull(streetName));
    recordJson.put(FIELD_BUILDING_NUMBER, orNull(buildingNumber));
    recordJson.put(FIELD_BUILDING_NAME, orNull(buildingName));
    recordJson.put(Location.PROPERTY_OBSEPASTREETNAME, orNull(streetName));
    recordJson.put(Location.PROPERTY_OBSEPABUILDINGNUMBER, orNull(buildingNumber));
    recordJson.put(Location.PROPERTY_OBSEPABUILDINGNAME, orNull(buildingName));
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
   * is absent from EVERY section of the body — the usual PUT semantics for an unspecified field.
   *
   * <p>The first section that carries the key wins, so a top-level value takes precedence over one
   * inside a {@code data} envelope. The two shapes are not expected together; this just makes the
   * tie deterministic instead of order-of-iteration luck. Within a section the aliases are tried
   * in declaration order (published name, DAL property name, raw column) for the same reason.
   *
   * @param aliases every spelling of the field, in precedence order — see {@link #KEYS_STREET_NAME}
   */
  private static String effective(List<JSONObject> sections, List<String> aliases,
      String currentValue) {
    for (JSONObject section : sections) {
      for (String alias : aliases) {
        String present = matchingKey(section, alias);
        if (present != null) {
          return nullIfEmpty(section.optString(present, null));
        }
      }
    }
    return currentValue;
  }

  private static Object orNull(String s) {
    return s != null ? s : JSONObject.NULL;
  }

  private static String nullIfEmpty(String s) {
    return (s == null || s.isEmpty() || "null".equals(s)) ? null : s;
  }
}
