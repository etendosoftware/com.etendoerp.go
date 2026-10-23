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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.ocr.OcrLookups.Candidate;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.Tax;
import com.etendoerp.go.schemaforge.ocr.OcrLookups.TaxAnswer;

/**
 * The lines part of the review (ETP-5715): for each line, which product it is and which tax it takes,
 * the two things the application's lines review settles.
 *
 * <p><b>Products</b> come from the similarity search ({@link OcrProductMatch}): one same-name product
 * is an {@code auto_matched} proposal, anything else is only a suggestion, and nothing similar is
 * {@code none}. Each line also offers {@code skip}, because the server rejects a line without a
 * product.</p>
 *
 * <p><b>Taxes</b> are never searched by label. A printed rate is resolved by the server's
 * default-tax rule for the vendor's address (one lookup per distinct rate); a line with no printed
 * rate is left without a tax, so the default of the form applies (<i>no_rate</i>, informative, not a
 * question); a rate no tax matches is reported as <i>no_tax_at_rate</i>.</p>
 */
final class OcrLineReview {

  private static final String KEY_ID = "id";
  private static final String KEY_TYPE = "type";
  private static final String KEY_STATE = "state";
  private static final String KEY_RATE = "rate";
  private static final String KEY_INDEX = "lineIndex";
  private static final String KEY_BLOCKING = "blocking";
  private static final String KEY_NAME = "name";
  private static final String KEY_OPTIONS = "options";
  private static final String KEY_DESCRIPTION = "description";
  private static final String KEY_QUANTITY = "quantity";
  private static final String KEY_EXPLANATION = "explanation";
  private static final String KEY_TAX = "tax";
  private static final String KEY_CANDIDATES = "candidates";
  private static final String STATE_RESOLVED = "resolved";

  private final OcrLookups lookups;
  private final String spec;
  private final JSONArray documentLines = new JSONArray();
  private final List<JSONObject> decisions = new ArrayList<>();
  private final List<JSONObject> notices = new ArrayList<>();
  private final List<JSONObject> operations = new ArrayList<>();
  private final Map<String, TaxAnswer> taxByRate = new HashMap<>();
  private boolean vendorChosen;

  OcrLineReview(OcrLookups lookups, String spec) {
    this.lookups = lookups;
    this.spec = spec;
  }

  JSONArray documentLines() {
    return documentLines;
  }

  List<JSONObject> decisions() {
    return decisions;
  }

  List<JSONObject> notices() {
    return notices;
  }

  List<JSONObject> operations() {
    return operations;
  }

  /**
   * Reviews every line.
   *
   * @param lines       the sanitized {@code line_items}
   * @param locationId  the vendor's address, {@code null} when unknown (taxes stay pending)
   * @param invoiceDate the detected date as ISO, or {@code null}
   * @param vendorChosen whether the user already chose the vendor: while it is only a suggestion the
   *                     taxes resolved for its address are provisional and the template does not
   *                     carry them
   * @throws JSONException when a body cannot be built
   */
  void review(JSONArray lines, String locationId, String invoiceDate, boolean vendorChosen)
      throws JSONException {
    this.vendorChosen = vendorChosen;
    List<JSONObject> items = new ArrayList<>();
    List<String> descriptions = new ArrayList<>();
    for (int i = 0; i < lines.length(); i++) {
      JSONObject item = lines.optJSONObject(i);
      items.add(item);
      descriptions.add(OcrJson.str(item, KEY_DESCRIPTION));
    }
    if (items.isEmpty()) {
      notices.add(OcrJson.notice("no_lines", "No line items were read from the document."));
      return;
    }
    List<List<Candidate>> candidates = lookups.productCandidates(descriptions);
    for (int i = 0; i < items.size(); i++) {
      List<Candidate> forLine = i < candidates.size() ? candidates.get(i) : List.of();
      reviewLine(i, items.get(i), forLine, locationId, invoiceDate);
    }
  }

  private void reviewLine(int index, JSONObject item, List<Candidate> candidates,
      String locationId, String invoiceDate) throws JSONException {
    OcrProductMatch match = OcrProductMatch.classify(OcrJson.str(item, KEY_DESCRIPTION), candidates);
    JSONObject taxDecision = taxDecision(index, OcrJson.decimal(item, "tax_rate"), locationId,
        invoiceDate);
    decisions.add(productDecision(index, match, candidates));
    decisions.add(taxDecision);
    documentLines.put(documentLine(index, item, taxDecision));
    String taxId = STATE_RESOLVED.equals(taxDecision.getString(KEY_STATE))
        ? taxDecision.getJSONObject("tax").getString(KEY_ID) : null;
    if (taxId != null && !vendorChosen) {
      taxId = "{tax of line " + index + ": call again with vendor_id once the user chose the vendor}";
    }
    String product = match.state() == OcrProductMatch.State.AUTO_MATCHED
        ? match.chosen().id() : "{decision line_" + index + "_product}";
    operations.add(OcrBatchTemplate.line(spec, index, product,
        new BigDecimal[] { OcrJson.decimal(item, KEY_QUANTITY), OcrJson.decimal(item, "unit_price") },
        taxId));
  }

  private JSONObject productDecision(int index, OcrProductMatch match, List<Candidate> candidates)
      throws JSONException {
    JSONObject decision = new JSONObject().put(KEY_ID, "line_" + index + "_product")
        .put(KEY_TYPE, "product").put(KEY_INDEX, index).put(KEY_STATE, match.state().code())
        .put(KEY_BLOCKING, match.state() != OcrProductMatch.State.AUTO_MATCHED);
    if (match.chosen() != null) {
      decision.put(match.state() == OcrProductMatch.State.AUTO_MATCHED ? "match" : "suggestion",
          candidate(match.chosen()));
    }
    JSONArray list = new JSONArray();
    for (Candidate candidate : candidates) {
      list.put(candidate(candidate));
    }
    decision.put(KEY_CANDIDATES, list);
    JSONArray options = new JSONArray();
    if (match.state() == OcrProductMatch.State.SUGGESTED) {
      options.put("use_suggestion");
    } else if (match.state() == OcrProductMatch.State.AUTO_MATCHED) {
      options.put("use_match");
    }
    options.put("pick").put("create").put("skip");
    return decision.put(KEY_OPTIONS, options);
  }

  private static JSONObject candidate(Candidate candidate) throws JSONException {
    JSONObject out = new JSONObject().put(KEY_ID, candidate.id()).put(KEY_NAME, candidate.name());
    if (candidate.similarity() != null) {
      out.put("similarity", candidate.similarity());
    }
    return out;
  }

  private JSONObject taxDecision(int index, BigDecimal rate, String locationId, String invoiceDate)
      throws JSONException {
    JSONObject decision = new JSONObject().put(KEY_ID, "line_" + index + "_tax")
        .put(KEY_TYPE, "tax").put(KEY_INDEX, index).put(KEY_BLOCKING, false);
    if (rate == null) {
      return decision.put(KEY_STATE, "no_rate").put(KEY_EXPLANATION, "No tax rate is printed on "
          + "this line: the tax is left empty and the application's default for the product "
          + "applies when the invoice is created.");
    }
    if (locationId == null) {
      return decision.put(KEY_STATE, "pending_vendor").put(KEY_EXPLANATION, "The tax depends on "
          + "the vendor's address: it is resolved once a vendor is chosen (call again with "
          + "vendor_id).");
    }
    TaxAnswer answer = taxByRate.computeIfAbsent(rate.stripTrailingZeros().toPlainString(),
        key -> lookups.defaultTax(locationId, invoiceDate, rate));
    if (!answer.known()) {
      return decision.put(KEY_STATE, "unknown");
    }
    if (answer.chosen() == null) {
      return decision.put(KEY_STATE, "no_tax_at_rate").put(KEY_CANDIDATES, taxes(answer.candidates()))
          .put(KEY_EXPLANATION, "No purchase tax has the printed rate. The tax is left empty and the "
              + "default applies; ask the user whether to pick one.");
    }
    decision.put(KEY_STATE, STATE_RESOLVED).put(KEY_TAX, tax(answer.chosen()))
        .put(KEY_CANDIDATES, taxes(answer.candidates()));
    return vendorChosen ? decision : decision.put("basedOnSuggestedVendor", true);
  }

  private static JSONArray taxes(List<Tax> taxes) throws JSONException {
    JSONArray out = new JSONArray();
    for (Tax tax : taxes) {
      out.put(tax(tax));
    }
    return out;
  }

  private static JSONObject tax(Tax tax) throws JSONException {
    return new JSONObject().put(KEY_ID, tax.id()).put(KEY_NAME, tax.name())
        .put(KEY_RATE, tax.rate() == null ? JSONObject.NULL : tax.rate().doubleValue());
  }

  private static JSONObject documentLine(int index, JSONObject item, JSONObject taxDecision)
      throws JSONException {
    JSONObject tax = new JSONObject().put(KEY_STATE, taxDecision.getString(KEY_STATE));
    if (taxDecision.has(KEY_TAX)) {
      tax.put(KEY_TAX, taxDecision.getJSONObject(KEY_TAX));
    }
    return new JSONObject().put("index", index)
        .put(KEY_DESCRIPTION, OcrJson.str(item, KEY_DESCRIPTION))
        .put(KEY_QUANTITY, valueOrNull(item, KEY_QUANTITY))
        .put("unitPrice", valueOrNull(item, "unit_price"))
        .put("taxLabel", OcrJson.str(item, "tax_label"))
        .put("taxRate", valueOrNull(item, "tax_rate"))
        .put(KEY_TAX, tax);
  }

  private static Object valueOrNull(JSONObject item, String key) {
    BigDecimal value = OcrJson.decimal(item, key);
    return value == null ? JSONObject.NULL : value.doubleValue();
  }
}
