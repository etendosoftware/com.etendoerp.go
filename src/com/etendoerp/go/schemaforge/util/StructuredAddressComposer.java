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
package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.etendoerp.go.featureflags.FeatureFlagContext;
import com.etendoerp.go.featureflags.GoFeatureFlags;

/**
 * Composes {@code C_Location.Address1}/{@code Address2} from the structured SEPA address fields
 * (ETP-5806): Street Name, Building Number and Building Name.
 *
 * <p><b>This ports {@code LocationSEPA.java::fillAddressLines} EXACTLY</b> (Classic,
 * {@code org.openbravo.sepa.iso20022}, branch {@code epic/ETP-3504}, lines 146-180) — not the
 * simplified "Line1=Street+Number / Line2=BuildingName" rule described in the ticket text or the
 * design mockup. The acceptance criterion "an address created in Etendo Go looks the same in
 * Etendo Classic, and vice versa" requires real parity: the simplified rule would diverge from
 * Classic whenever the building name fits on the same 60-character line as street+number.
 *
 * <p>Used by both {@code ContactsLocationAddressHandler} and {@code WarehouseLocationHandler} so
 * the two entities that wrap a {@code C_Location} compute the exact same lines, on every channel
 * (REST and MCP reach the same handler code, so there is nothing channel-specific to port twice).
 *
 * <p><b>Gating is per-account (ETP-5806 staged rollout).</b> {@link #isEnabled(String)} takes the
 * {@code ETGO_ACCOUNT} email to evaluate the flag for; there is no account-less overload, by
 * design. Validation, persistence and MCP-schema exposure therefore resolve the flag for the same
 * account the capability signal the UI reads was resolved for, so a targeted account can never see
 * the structured fields in the UI while the backend silently drops what it fills in.
 */
public final class StructuredAddressComposer {

  /** Length of {@code C_Location.Address1}/{@code Address2} — NOT the 70 of EM_OBSEPA_StrtNm. */
  private static final int ADDRESS_LINE_MAX_LENGTH = 60;

  /**
   * Field lengths of the 3 structured SEPA columns themselves ({@code org.openbravo.sepa.iso20022}'s
   * {@code AD_COLUMN.FIELDLENGTH} for {@code EM_OBSEPA_StrtNm}/{@code BldgNb}/{@code BldgNm}:
   * VARCHAR(70)/(16)/(35) respectively). ETP-5806 follow-up: neither the UI nor this handler
   * enforced them before a value was written, so a value longer than the DB column reached
   * {@code OBDal.flush()} as a raw data-truncation SQL exception — surfaced to the caller as an
   * opaque 500 with no indication of which field or why. {@link #fieldsExceedingMaxLength} turns
   * that into a clean 400 before the write is attempted, on both create and update, for both
   * handlers that call into this composer.
   */
  private static final int STREET_NAME_MAX_LENGTH = 70;
  private static final int BUILDING_NUMBER_MAX_LENGTH = 16;
  private static final int BUILDING_NAME_MAX_LENGTH = 35;

  private StructuredAddressComposer() {
  }

  /**
   * Whether the {@code structured-address-for-sepa} backend-only flag (ETP-5806, TTL 2026-11-15)
   * is on <b>for the given account</b>.
   *
   * <p>The 3 call sites below — {@link #missingMandatoryFields}, {@link #fieldsExceedingMaxLength}
   * and {@link #composeAddressLines} — are each only meaningful once the structured fields exist
   * on the wire; every handler that calls into this composer MUST check this first and, when
   * {@code false}, skip straight to the pre-ETP-5806 legacy path (country-only mandatoriness,
   * {@code addressLine1}/{@code addressLine2} passed through as given) rather than calling any of
   * them. Centralized here rather than duplicated per handler because the composer is already the
   * one place {@code ContactsLocationAddressHandler}, {@code WarehouseLocationHandler} and {@code
   * BpLocationHandler} share for structured-address-for-sepa logic.
   *
   * <p><b>The account email is a parameter, not resolved here.</b> Each caller knows where its own
   * account comes from and nothing else does: a NEO/MCP request resolves the authenticated user's
   * account with {@code GoAccountResolver.currentAccountEmail()}, while the (pre-tenant)
   * onboarding path already has the account in hand and runs under an admin execution context
   * where no caller identity is recoverable. Resolving implicitly inside this method would be
   * wrong for the second group and would hide a DB lookup behind what reads as a constant. Same
   * explicit-at-the-call-site shape the capability signal uses ({@code SFWindowAccessMap},
   * {@code EtendoGoJwtServlet#buildSessionCapabilities}), so the backend's write/read gating and
   * the signal the UI renders from can never disagree for the same account.
   *
   * @param accountEmail the {@code ETGO_ACCOUNT} email to target the evaluation at; {@code null}
   *     or blank yields an untargeted evaluation ({@link FeatureFlagContext#forAccount(String)}),
   *     which every provider must treat as "no targeting key", never as "allow"
   * @return {@code true} when the flag resolves on for that account, {@code false} otherwise —
   *     including whenever it cannot be resolved at all, since the off state is the safe one
   */
  public static boolean isEnabled(String accountEmail) {
    return GoFeatureFlags.isEnabled(GoFeatureFlags.FLAG_STRUCTURED_ADDRESS_FOR_SEPA,
        FeatureFlagContext.forAccount(accountEmail));
  }

  /** The two computed address lines. Either may be {@code null} when empty. */
  public static final class ComposedAddress {
    private final String address1;
    private final String address2;

    private ComposedAddress(String address1, String address2) {
      this.address1 = address1;
      this.address2 = address2;
    }

    public String getAddress1() {
      return address1;
    }

    public String getAddress2() {
      return address2;
    }
  }

  /**
   * Composes Address1/Address2 from the structured fields, replicating
   * {@code LocationSEPA.fillAddressLines} line for line.
   *
   * <p>Separators: {@code ", "} between street and number; {@code " - "} (space-dash-space)
   * between street+number and building name in Line 1. {@code append} only inserts a separator
   * when both sides are non-blank.
   *
   * <p><b>ETP-5806 CP-6 (QA finding) — lines split only on whole field/separator boundaries, never
   * mid-value.</b> Before this, the overflow branch below cut the raw street with
   * {@code StringUtils.left(street, ADDRESS_LINE_MAX_LENGTH)} — a hard {@code .substring(0, 60)}
   * that truncates a street up to 70 chars ({@link #STREET_NAME_MAX_LENGTH}) mid-word rather than
   * dropping the whole field to the next line. {@code Street} is the only one of the 3 fields that
   * can exceed {@link #ADDRESS_LINE_MAX_LENGTH} on its own, so {@code address1} may legitimately
   * come back longer than 60 chars in that case — that is the field kept whole, not lossily cut.
   * The {@code name} truncation in the middle branch and the {@code " - name"} truncation in the
   * overflow branch below were already unreachable in practice ({@link #BUILDING_NAME_MAX_LENGTH}
   * is 35 and {@code "Number - Name"} is at most 16 + 3 + 35 = 54 chars, both under 60), so dropping
   * them changes no real output — but they are gone on principle, matching
   * {@code locationAddress.js::composeAddressLines} in {@code schema_forge_core}'s
   * {@code app-shell-core}, which this method mirrors exactly and must stay in lockstep with.
   *
   * @param streetName     Street Name ({@code EM_OBSEPA_StrtNm}), may be {@code null}/blank
   * @param buildingNumber Building Number ({@code EM_OBSEPA_BldgNb}), may be {@code null}/blank
   * @param buildingName   Building Name ({@code EM_OBSEPA_BldgNm}), may be {@code null}/blank
   * @return the composed lines; both {@code null} when all three inputs are blank
   */
  public static ComposedAddress composeAddressLines(String streetName, String buildingNumber,
      String buildingName) {
    String street = StringUtils.trimToEmpty(streetName);
    String number = StringUtils.trimToEmpty(buildingNumber);
    String name = StringUtils.trimToEmpty(buildingName);

    String streetAndNumber = append(street, ", ", number);
    String wholeAddress = append(streetAndNumber, " - ", name);

    if (wholeAddress.length() <= ADDRESS_LINE_MAX_LENGTH) {
      // Everything fits on one line.
      return new ComposedAddress(emptyToNull(wholeAddress), null);
    }
    if (streetAndNumber.length() <= ADDRESS_LINE_MAX_LENGTH) {
      return new ComposedAddress(emptyToNull(streetAndNumber), emptyToNull(name));
    }
    // Only a street longer than the line itself overflows here — kept whole, never cut mid-value.
    return new ComposedAddress(emptyToNull(street), emptyToNull(append(number, " - ", name)));
  }

  /** The fallback value used for Building Number when it is blank and Street Name is present. */
  private static final String EMPTY_BUILDING_NUMBER_FALLBACK = "s/n";

  /**
   * The effective Building Number to validate and persist (ETP-5806 follow-up, QA finding): when
   * {@code buildingNumber} is blank but {@code streetName} is present, the field is not treated as
   * missing — it falls back to the literal {@code "s/n"} ("sin número"), the same convention the
   * frontend ({@code LocationEditorModal.jsx}, onboarding {@code api.js}) already applies before
   * ever reaching this handler.
   *
   * <p>This closes the gap for callers that do NOT go through the frontend — an MCP {@code
   * etendo_create}/{@code etendo_update} call, or a direct API integration — which previously got
   * a {@code 400 Missing required field: buildingNumber} even though the product convention is to
   * accept the omission and fill in {@code "s/n"}. Centralized here, instead of duplicated per
   * handler, for the same reason the frontend centralized its own copy in one constant rather than
   * re-implementing the rule at each call site.
   *
   * <p>When {@code streetName} itself is blank, no fallback is applied: the raw (likely blank)
   * {@code buildingNumber} is returned unchanged, so {@link #missingMandatoryFields} still reports
   * both {@code streetName} and {@code buildingNumber} as missing in that case — there is nothing
   * to anchor a "no building number" convention to without a street.
   *
   * @param streetName     Street Name ({@code EM_OBSEPA_StrtNm}), may be {@code null}/blank
   * @param buildingNumber Building Number ({@code EM_OBSEPA_BldgNb}), may be {@code null}/blank
   * @return {@code buildingNumber} trimmed when non-blank; {@code "s/n"} when blank and {@code
   *     streetName} is non-blank; the original (blank/{@code null}) {@code buildingNumber}
   *     otherwise
   */
  public static String effectiveBuildingNumber(String streetName, String buildingNumber) {
    if (StringUtils.isNotBlank(buildingNumber)) {
      return StringUtils.trim(buildingNumber);
    }
    if (StringUtils.isNotBlank(streetName)) {
      return EMPTY_BUILDING_NUMBER_FALLBACK;
    }
    return buildingNumber;
  }

  /**
   * The structured-address-for-sepa mandatory fields (ETP-5806 product decision: Street, City and
   * Country are mandatory in Etendo Go, stricter than Classic's optional SEPA fields) that are
   * missing from the given values.
   *
   * <p>Building Number is <b>not</b> independently mandatory: callers MUST pass the result of
   * {@link #effectiveBuildingNumber} as {@code buildingNumber}, not the raw payload value, so a
   * blank Building Number with a present Street Name is never reported as missing — it resolves to
   * the {@code "s/n"} fallback instead (ETP-5806 follow-up). A caller that passes the raw value
   * would see this method still require an explicit Building Number, which is no longer the rule
   * on any channel (REST, MCP or UI).
   *
   * @param streetName     Street Name ({@code EM_OBSEPA_StrtNm}), may be {@code null}/blank
   * @param buildingNumber the <b>effective</b> Building Number — see {@link
   *     #effectiveBuildingNumber} — may be {@code null}/blank only when {@code streetName} is
   *     itself blank
   * @param cityName       City Name, may be {@code null}/blank
   * @param countryId      Country id, may be {@code null}/blank
   * @return the missing field names (payload keys), in a stable order; empty when none are missing
   */
  public static List<String> missingMandatoryFields(String streetName, String buildingNumber,
      String cityName, String countryId) {
    List<String> missing = new ArrayList<>();
    if (StringUtils.isBlank(streetName)) {
      missing.add("streetName");
    }
    if (StringUtils.isBlank(buildingNumber)) {
      missing.add("buildingNumber");
    }
    if (StringUtils.isBlank(cityName)) {
      missing.add("cityName");
    }
    if (StringUtils.isBlank(countryId)) {
      missing.add("country");
    }
    return missing;
  }

  /**
   * The structured-address-for-sepa fields whose value exceeds the DB column's own length — see the
   * constants' doc comment above for why this check exists. A blank/{@code null} value is never
   * "too long" regardless of length (that is {@link #missingMandatoryFields}'s concern).
   *
   * @param streetName     Street Name ({@code EM_OBSEPA_StrtNm}), may be {@code null}/blank
   * @param buildingNumber Building Number ({@code EM_OBSEPA_BldgNb}), may be {@code null}/blank
   * @param buildingName   Building Name ({@code EM_OBSEPA_BldgNm}), may be {@code null}/blank
   * @return the offending field names (payload keys), in a stable order; empty when none are too long
   */
  public static List<String> fieldsExceedingMaxLength(String streetName, String buildingNumber,
      String buildingName) {
    List<String> tooLong = new ArrayList<>();
    if (streetName != null && streetName.length() > STREET_NAME_MAX_LENGTH) {
      tooLong.add("streetName");
    }
    if (buildingNumber != null && buildingNumber.length() > BUILDING_NUMBER_MAX_LENGTH) {
      tooLong.add("buildingNumber");
    }
    if (buildingName != null && buildingName.length() > BUILDING_NAME_MAX_LENGTH) {
      tooLong.add("buildingName");
    }
    return tooLong;
  }

  /**
   * ETP-5806 CP-12b (QA finding) — whether an update's write path should merge the stored record
   * into the body before validating mandatory fields: always for a PATCH, and for a PUT whenever
   * the body alone (with the flag on) cannot satisfy them on its own.
   *
   * <p>Kept here, not in the calling handler, specifically so the handler's own update method
   * stays a straight sequence of calls with no extra branching of its own — {@code
   * ContactsLocationAddressHandler#handleUpdate} already carries plenty of control flow, and
   * every {@code &&}/{@code ||} added to it directly raises its own Cognitive Complexity. The
   * branching that decides this one boolean belongs in a single-purpose method instead, the same
   * way {@link #missingMandatoryFields} and {@link #fieldsExceedingMaxLength} already centralize
   * the other mandatory-field decisions this composer owns.
   *
   * <p>MCP's {@code etendo_update} always maps to HTTP PUT ({@code McpToolRouter#handleUpdate}),
   * so gating the merge purely on PATCH vs PUT meant a single-field MCP edit (e.g. only {@code
   * buildingName}) reached validation with the rest of the structured fields genuinely absent
   * from the body and a clean 400, even though they already existed on the stored record. A true
   * full-replace PUT (the UI form, a REST client resending the complete address) is unaffected:
   * it already satisfies every mandatory field, so this returns {@code false} and the merge is
   * skipped exactly as before.
   *
   * @param httpMethod               the request's HTTP method
   * @param structuredAddressEnabled whether {@code structured-address-for-sepa} is on for the
   *                                 caller's account; with it off, only a PATCH merges
   * @param streetName               the raw body value, before any merge — see
   *                                 {@code ContactsLocationAddressHandler#readAlias}
   * @param buildingNumber           the raw body value, before any merge
   * @param cityName                 the raw body value, before any merge
   * @param countryId                the raw body value, before any merge — NOT falling back to
   *                                 the stored record's own country, unlike
   *                                 {@code effectiveCountryForValidation}: this method asks
   *                                 whether the body is self-sufficient, so a country the body
   *                                 never mentions must count as missing here
   * @return {@code true} when the stored record should be merged into the body first
   */
  public static boolean requiresMergeOnUpdate(String httpMethod, boolean structuredAddressEnabled,
      String streetName, String buildingNumber, String cityName, String countryId) {
    if ("PATCH".equals(httpMethod)) {
      return true;
    }
    if (!structuredAddressEnabled) {
      return false;
    }
    return !missingMandatoryFields(streetName, effectiveBuildingNumber(streetName, buildingNumber),
        cityName, countryId).isEmpty();
  }

  private static String append(String base, String separator, String part) {
    if (base.isEmpty()) {
      return part;
    }
    if (part.isEmpty()) {
      return base;
    }
    return base + separator + part;
  }

  private static String emptyToNull(String value) {
    return (value == null || value.isEmpty()) ? null : value;
  }
}
