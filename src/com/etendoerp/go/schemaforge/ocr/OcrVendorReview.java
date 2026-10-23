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

package com.etendoerp.go.schemaforge.ocr;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Address;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.AddressState;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Duplicates;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Party;

/**
 * The vendor part of the review (ETP-5715), mirroring what the application's review step does with
 * the supplier of a scanned invoice: find the vendor <b>by name only</b>, check it has an address,
 * and warn when another invoice already carries the same supplier number.
 *
 * <p>The match rule is the application's, not a better one: among the vendor selector's rows an
 * exact (trimmed, case-insensitive) name that is unique wins; otherwise a contains-search limited to
 * two rows counts only when it returns exactly one. The result is a SUGGESTION: the user decides, and
 * a vendor the user chose (passed in) is used as is.</p>
 */
final class OcrVendorReview {

  /** Most candidate vendors listed to the user. */
  static final int MAX_CANDIDATES = 10;
  private static final int SEARCH_LIMIT = 20;
  private static final int CONTAINS_LIMIT = 2;

  private static final String KEY_ID = "id";
  private static final String KEY_NAME = "name";
  private static final String KEY_TYPE = "type";
  private static final String KEY_BLOCKING = "blocking";
  private static final String KEY_OPTIONS = "options";
  private static final String KEY_VENDOR = "vendor";

  /** A vendor proposed by the name match, and the rule that found it. */
  private static final class Suggestion {
    private final Party party;
    private final String matchedBy;

    private Suggestion(Party party, String matchedBy) {
      this.party = party;
      this.matchedBy = matchedBy;
    }
  }

  private final OcrLookups lookups;
  private final List<JSONObject> decisions = new ArrayList<>();
  private final List<JSONObject> notices = new ArrayList<>();
  private Party effective;
  private Address address = new Address(AddressState.UNKNOWN, null);

  OcrVendorReview(OcrLookups lookups) {
    this.lookups = lookups;
  }

  /** The decisions this part raised, in the order they are asked. */
  List<JSONObject> decisions() {
    return decisions;
  }

  /** The notices this part raised. */
  List<JSONObject> notices() {
    return notices;
  }

  /** The vendor the rest of the review is computed for: the user's, else the suggestion, else null. */
  Party effectiveVendor() {
    return effective;
  }

  /** The address of the effective vendor. */
  Address address() {
    return address;
  }

  /**
   * Runs the vendor review.
   *
   * @param extraction the sanitized extraction
   * @param chosen     the vendor the user already chose, or {@code null}
   * @throws JSONException when a body cannot be built
   */
  void review(JSONObject extraction, Party chosen) throws JSONException {
    String name = OcrJson.str(extraction, "vendor_name");
    List<Party> rows = chosen == null && OcrJson.filled(name)
        ? lookups.searchVendors(name, SEARCH_LIMIT) : List.of();
    Suggestion suggestion = chosen != null ? null : suggest(name, rows);
    effective = chosen;
    if (effective == null && suggestion != null) {
      effective = suggestion.party;
    }
    decisions.add(vendorDecision(extraction, chosen, suggestion, rows));
    if (effective == null) {
      return;
    }
    address = lookups.vendorAddress(effective.id());
    addAddressFindings();
    addDuplicateFindings(OcrJson.str(extraction, "document_no"), chosen == null);
  }

  private Suggestion suggest(String name, List<Party> rows) {
    String wanted = OcrProductMatch.normalize(name);
    List<Party> exact = rows.stream()
        .filter(row -> OcrProductMatch.normalize(row.name()).equals(wanted))
        .collect(Collectors.toList());
    if (exact.size() == 1) {
      return new Suggestion(exact.get(0), "exact");
    }
    List<Party> few = OcrJson.filled(name) ? lookups.searchVendors(name, CONTAINS_LIMIT) : List.of();
    return few.size() == 1 ? new Suggestion(few.get(0), "contains") : null;
  }

  private JSONObject vendorDecision(JSONObject extraction, Party chosen,
      Suggestion suggestion, List<Party> rows) throws JSONException {
    JSONObject decision = new JSONObject();
    decision.put(KEY_ID, KEY_VENDOR);
    decision.put(KEY_TYPE, KEY_VENDOR);
    decision.put(KEY_BLOCKING, chosen == null);
    if (chosen != null) {
      decision.put("status", "selected");
      decision.put("selected", party(chosen));
      return decision;
    }
    decision.put("status", suggestion == null ? "none" : "suggested");
    if (suggestion != null) {
      decision.put("suggestion", party(suggestion.party).put("matchedBy", suggestion.matchedBy));
    }
    JSONArray candidates = new JSONArray();
    rows.stream().limit(MAX_CANDIDATES).forEach(row -> candidates.put(partyQuietly(row)));
    decision.put("candidates", candidates);
    decision.put(KEY_OPTIONS, suggestion == null ? options("choose_other", "create_new")
        : options("use_suggestion", "choose_other", "create_new"));
    decision.put("createPrefill", createPrefill(extraction));
    decision.put("afterChoosing", "Once the user has chosen or created the vendor, call this tool "
        + "again with vendor_id: the address, duplicates and taxes are recomputed for that vendor "
        + "without reading the PDF again.");
    return decision;
  }

  private static JSONObject partyQuietly(Party party) {
    try {
      return party(party);
    } catch (JSONException e) {
      return new JSONObject();
    }
  }

  private static JSONObject party(Party party) throws JSONException {
    return new JSONObject().put(KEY_ID, party.id()).put(KEY_NAME, party.name());
  }

  private static JSONArray options(String... values) {
    JSONArray out = new JSONArray();
    for (String value : values) {
      out.put(value);
    }
    return out;
  }

  /** What the "create contact" form would be prefilled with — read from the document, so untrusted. */
  private static JSONObject createPrefill(JSONObject extraction) throws JSONException {
    return new JSONObject().put("untrusted", true)
        .put(KEY_NAME, OcrJson.str(extraction, "vendor_name"))
        .put("taxId", OcrJson.str(extraction, "tax_id"))
        .put("address", OcrJson.str(extraction, "vendor_address"))
        .put("postalCode", OcrJson.str(extraction, "vendor_postal_code"))
        .put("city", OcrJson.str(extraction, "vendor_city"))
        .put("country", OcrJson.str(extraction, "vendor_country"))
        .put("email", OcrJson.str(extraction, "vendor_email"))
        .put("phone", OcrJson.str(extraction, "vendor_phone"));
  }

  private void addAddressFindings() throws JSONException {
    if (address.state() == AddressState.UNKNOWN) {
      notices.add(OcrJson.notice("vendor_address_unknown", "The vendor's address could not be checked. "
          + "The invoice needs one: confirm it before creating."));
    } else if (address.state() == AddressState.MISSING) {
      decisions.add(new JSONObject().put(KEY_ID, "vendor_address_missing")
          .put(KEY_TYPE, "vendor_address").put(KEY_BLOCKING, true)
          .put(KEY_VENDOR, party(effective))
          .put(KEY_OPTIONS, options("add_address_then_recheck", "choose_other_vendor"))
          .put("explanation", "This vendor has no address, and a purchase invoice cannot be saved "
              + "without one. Add an address to the vendor (or choose another vendor) and call this "
              + "tool again with vendor_id."));
    }
  }

  private void addDuplicateFindings(String documentNo, boolean vendorIsSuggestion)
      throws JSONException {
    if (!OcrJson.filled(documentNo)) {
      return;
    }
    Duplicates found = lookups.duplicates(effective.id(), documentNo);
    if (!found.known()) {
      notices.add(OcrJson.notice("duplicate_check_unknown", "Whether this supplier number was already "
          + "invoiced could not be checked."));
      return;
    }
    if (found.documents().isEmpty()) {
      return;
    }
    JSONArray invoices = new JSONArray();
    for (OcrLookups.Duplicate duplicate : found.documents()) {
      invoices.put(new JSONObject().put(KEY_ID, duplicate.id())
          .put("documentNo", duplicate.documentNo() == null ? JSONObject.NULL
              : duplicate.documentNo()));
    }
    decisions.add(new JSONObject().put(KEY_ID, "duplicate_invoice").put(KEY_TYPE, "warning")
        .put(KEY_BLOCKING, false).put("invoices", invoices)
        .put("basedOnSuggestedVendor", vendorIsSuggestion)
        .put("explanation", "Invoices of this vendor already carry the same supplier number (not "
            + "voided ones). Show them to the user and ask whether to create another."));
  }

}
