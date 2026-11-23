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

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CANDIDATES;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DESCRIPTION;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DISCOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_INDEX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_MATCHED_BY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NAME;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NEW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_PRODUCT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_QUANTITY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_SIMILARITY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_STATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_LABEL;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_TAX_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UNIT_PRICE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UNIT_PRINTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.MATCHED_SUGGESTED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_LINKED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.S_NEW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.T_NO_TAX_AT_RATE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.T_PENDING_VENDOR;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.T_RESOLVED;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.T_UNKNOWN;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Matches the lines of an extracted document (ETP-6048): the product of each one, with the rules of
 * the application's review ({@link OcrProductMatch}), and its tax.
 *
 * <p>The tax depends on the vendor's address. While there is none (a new vendor, a vendor without
 * address) the line is {@code pending_vendor}: it is resolved when the vendor exists, at confirm
 * time. Taxes are asked once per printed rate.</p>
 */
public final class InboxLineMatcher {

  private static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(100);
  private static final int PERCENT_SCALE = 4;

  private final OcrLookups lookups;
  private final InboxNewProductDefaults newProductDefaults;
  private final Map<String, TaxAnswer> taxByRate = new HashMap<>();

  InboxLineMatcher(OcrLookups lookups) {
    this(lookups, null);
  }

  InboxLineMatcher(OcrLookups lookups, String organizationId) {
    this.lookups = lookups;
    this.newProductDefaults = new InboxNewProductDefaults(lookups, organizationId);
  }

  /**
   * Matches every line.
   *
   * @param items       the sanitized {@code line_items}, may be {@code null}
   * @param locationId  the vendor's address, {@code null} while unknown (taxes stay pending)
   * @param invoiceDate the invoice date as ISO, or {@code null} for today
   * @return the lines of the inbox document
   * @throws JSONException when an object cannot be built
   */
  JSONArray review(JSONArray items, String locationId, String invoiceDate) throws JSONException {
    JSONArray out = new JSONArray();
    if (items == null || items.length() == 0) {
      return out;
    }
    List<String> descriptions = new ArrayList<>();
    for (int i = 0; i < items.length(); i++) {
      descriptions.add(OcrJson.str(items.optJSONObject(i), K_DESCRIPTION));
    }
    List<List<Candidate>> candidates = lookups.productCandidates(descriptions);
    for (int i = 0; i < items.length(); i++) {
      List<Candidate> forLine = i < candidates.size() ? candidates.get(i) : List.of();
      out.put(line(i, items.optJSONObject(i), forLine, locationId, invoiceDate));
    }
    return out;
  }

  private JSONObject line(int index, JSONObject item, List<Candidate> candidates,
      String locationId, String invoiceDate) throws JSONException {
    BigDecimal rate = OcrJson.decimal(item, "tax_rate");
    JSONObject line = new JSONObject().put(K_INDEX, index)
        .put(K_DESCRIPTION, OcrJson.str(item, K_DESCRIPTION))
        .put(K_QUANTITY, number(OcrJson.decimal(item, "quantity")))
        .put(K_UNIT_PRICE, number(OcrJson.decimal(item, "unit_price")))
        .put(K_TAX_RATE, number(rate)).put(K_TAX_LABEL, OcrJson.str(item, "tax_label"));
    BigDecimal discount = discountPercent(OcrJson.decimal(item, "discount_percent"),
        OcrJson.decimal(item, "discount_amount"), OcrJson.decimal(item, "quantity"),
        OcrJson.decimal(item, "unit_price"));
    if (discount != null) {
      line.put(K_DISCOUNT, discount.doubleValue());
    }
    String printedUnit = OcrJson.str(item, "unit");
    if (OcrJson.filled(printedUnit)) {
      line.put(K_UNIT_PRINTED, printedUnit);
    }
    line.put(K_PRODUCT, productWithDefaults(OcrJson.str(item, K_DESCRIPTION), candidates,
        printedUnit));
    line.put(K_TAX, tax(rate, locationId, invoiceDate));
    return line;
  }

  /**
   * The product of a line; when it ends up {@code new}, its {@code new} block is pre-filled
   * (ETP-6051) with the unit and the category it would be created with, each tagged by source. A
   * linked or ambiguous product gets none: a linked one keeps its own unit and category, and the
   * printed unit is never applied to it.
   */
  private JSONObject productWithDefaults(String description, List<Candidate> candidates,
      String printedUnit) throws JSONException {
    JSONObject product = product(description, candidates);
    if (S_NEW.equals(product.optString(K_STATE))) {
      JSONObject draft = newProductDefaults.draftFor(printedUnit);
      if (draft.length() > 0) {
        product.put(K_NEW, draft);
      }
    }
    return product;
  }

  /**
   * The line discount as the percent GO stores (ETP-6050), computed here and never by the model.
   * The printed percent wins when it is in (0, 100]; otherwise the printed amount is converted
   * ({@code amount / (quantity x unitPrice) x 100}, scale 4, HALF_UP) when it is in
   * (0, quantity x unitPrice]. Anything else (zero, negative, above 100, an amount over the gross,
   * a line without quantity or price) is ignored: no discount.
   *
   * @param percent   the printed percentage, may be {@code null}
   * @param amount    the printed amount, may be {@code null}
   * @param quantity  the line quantity, may be {@code null}
   * @param unitPrice the price before the discount, may be {@code null}
   * @return the percent with four decimals, {@code null} for no discount
   */
  static BigDecimal discountPercent(BigDecimal percent, BigDecimal amount, BigDecimal quantity,
      BigDecimal unitPrice) {
    if (percent != null && percent.signum() > 0 && percent.compareTo(MAX_PERCENT) <= 0) {
      return nonZero(percent.setScale(PERCENT_SCALE, RoundingMode.HALF_UP));
    }
    if (amount == null || amount.signum() <= 0 || quantity == null || unitPrice == null) {
      return null;
    }
    BigDecimal gross = quantity.multiply(unitPrice);
    if (gross.signum() <= 0 || amount.compareTo(gross) > 0) {
      return null;
    }
    return nonZero(amount.multiply(MAX_PERCENT).divide(gross, PERCENT_SCALE,
        RoundingMode.HALF_UP));
  }

  private static BigDecimal nonZero(BigDecimal value) {
    return value.signum() > 0 ? value : null;
  }

  /** A number for the document, {@code null} when absent. */
  static Object number(BigDecimal value) {
    return value == null ? JSONObject.NULL : value.doubleValue();
  }

  private static JSONObject product(String description, List<Candidate> candidates)
      throws JSONException {
    OcrProductMatch match = OcrProductMatch.classify(description, candidates);
    JSONObject out = new JSONObject();
    switch (match.state()) {
      case AUTO_MATCHED:
        out.put(K_STATE, S_LINKED).put(K_ID, match.chosen().id()).put(K_NAME, match.chosen().name())
            .put(K_MATCHED_BY, matchedBy(match, description));
        break;
      case SUGGESTED:
        // ETP-6051: the best of several doubtful candidates is chosen for the user, so the line is
        // complete and the review shows it collapsed; the candidates stay to pick another one.
        out.put(K_STATE, S_LINKED).put(K_ID, match.chosen().id()).put(K_NAME, match.chosen().name())
            .put(K_MATCHED_BY, MATCHED_SUGGESTED);
        break;
      default:
        out.put(K_STATE, S_NEW).put(K_ID, JSONObject.NULL);
        break;
    }
    return out.put(K_CANDIDATES, candidatesOf(candidates));
  }

  /** {@code exact} when the matched product has the very name of the line, else {@code similarity}. */
  private static String matchedBy(OcrProductMatch match, String description) {
    boolean exact = OcrProductMatch.normalizeProductName(match.chosen().name())
        .equals(OcrProductMatch.normalizeProductName(description));
    return exact ? "exact" : K_SIMILARITY;
  }

  private static JSONArray candidatesOf(List<Candidate> candidates) throws JSONException {
    JSONArray out = new JSONArray();
    for (Candidate candidate : candidates) {
      JSONObject json = new JSONObject().put(K_ID, candidate.id()).put(K_NAME, candidate.name());
      if (candidate.similarity() != null) {
        json.put(K_SIMILARITY, candidate.similarity());
      }
      out.put(json);
    }
    return out;
  }

  private JSONObject tax(BigDecimal rate, String locationId, String invoiceDate)
      throws JSONException {
    return taxState(lookups, taxByRate, rate, locationId, invoiceDate);
  }

  /**
   * The {@code tax} block of a line: resolved, without a tax at that rate, unknown, or pending the
   * vendor's address (ETP-6050: shared by the worker and by the review's edits, so both build it
   * the very same way). The tax is chosen by rate only.
   *
   * @param lookups     the lookups of the document type
   * @param taxByRate   the answers already asked, by rate; filled here (one question per rate)
   * @param rate        the line's rate, {@code null} when it prints none
   * @param locationId  the vendor's address, {@code null} while unknown (the tax stays pending)
   * @param invoiceDate the invoice date as ISO, or {@code null} for today
   * @return the {@code tax} block
   * @throws JSONException when an object cannot be built
   */
  public static JSONObject taxState(OcrLookups lookups, Map<String, TaxAnswer> taxByRate,
      BigDecimal rate, String locationId, String invoiceDate) throws JSONException {
    if (locationId == null) {
      return new JSONObject().put(K_STATE, T_PENDING_VENDOR);
    }
    String key = rate == null ? "" : rate.stripTrailingZeros().toPlainString();
    TaxAnswer answer = taxByRate.computeIfAbsent(key,
        k -> lookups.defaultTax(locationId, invoiceDate, rate));
    if (!answer.known()) {
      return new JSONObject().put(K_STATE, T_UNKNOWN);
    }
    if (answer.chosen() == null) {
      return new JSONObject().put(K_STATE, T_NO_TAX_AT_RATE)
          .put(K_CANDIDATES, taxes(answer.candidates()));
    }
    return taxJson(answer.chosen(), T_RESOLVED).put(K_CANDIDATES, taxes(answer.candidates()));
  }

  private static JSONObject taxJson(Tax tax, String state) throws JSONException {
    JSONObject json = new JSONObject().put(K_STATE, state).put(K_ID, tax.id())
        .put(K_NAME, tax.name());
    return json.put(K_RATE, number(tax.rate()));
  }

  private static JSONArray taxes(List<Tax> taxes) throws JSONException {
    JSONArray out = new JSONArray();
    for (Tax tax : taxes) {
      out.put(new JSONObject().put(K_ID, tax.id()).put(K_NAME, tax.name())
          .put(K_RATE, number(tax.rate())));
    }
    return out;
  }
}
