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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.plm.Product;

import com.etendoerp.go.schemaforge.DefaultTaxResolver.Determination;
import com.etendoerp.go.schemaforge.DefaultTaxResolver.Result;
import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.util.NeoActionContract.Param;

/**
 * Customization of purchase-invoice/lines: everything {@link InvoiceLineHandler} does, plus
 * (ETP-5710) the discounted price and the amounts of a written line — {@code lineNetAmount} and
 * {@code grossAmount} — and their recalculation on update, which nothing on the server derived for
 * a purchase line (a line an agent wrote persisted {@code LineNetAmt 0}, so the header total, the
 * tax rows and the payment plan came out wrong). The rules live in
 * {@link InvoiceLineAmountSupport}, shared with {@link SalesInvoiceLineHandler}. And (ETP-5585) the
 * {@code defaultTax} read action — the tax a line should default to when only the vendor address,
 * the date, an extracted percentage and maybe a product are known (the OCR invoice flow).
 *
 * <pre>POST /sws/neo/purchase-invoice/lines/new/action/defaultTax</pre>
 *
 * <p>The record id is always {@code new}, as for {@link CurrencyOptionsHandler}. It is a POST
 * because the action hook context only carries the request body; the action never persists
 * anything. The algorithm lives in {@link DefaultTaxResolver}; this class only validates the call
 * against its contract, checks tenant ownership of the ids it was handed and shapes the response.
 * Org and client always come from the session, never from the body.</p>
 *
 * <p><b>Binding.</b> Resolved by {@link NeoExtension} on every channel, ahead of the row's
 * {@code Java_Qualifier} ({@code NeoExtensionDispatcher.resolve} asks {@code NeoExtensionIndex}
 * first and falls back to the qualifier only on a miss). The row still carries
 * {@code invoiceLineHandler}, on purpose and exactly as {@link SalesInvoiceLineHandler}'s does:
 * several readers look the qualifier up directly instead of going through the dispatcher, and they
 * keep resolving the parent class, whose behaviour this class inherits unchanged. The price is the
 * ERROR line {@code NeoExtensionIndex.warnOnQualifierDisagreement} logs on each resolution; see
 * {@code neo-headless.md} §4.12.20. No {@code @Named}: the parent's name is not inherited and a
 * second bean carrying it would make it ambiguous.
 */
@NeoExtension(spec = "purchase-invoice", entity = "lines")
public class PurchaseInvoiceLineHandler extends InvoiceLineHandler {

  private static final Logger log = LogManager.getLogger(PurchaseInvoiceLineHandler.class);

  static final String ACTION_DEFAULT_TAX = "defaultTax";
  static final String PARAM_PARTNER_ADDRESS_ID = "partnerAddressId";
  static final String PARAM_INVOICE_DATE = "invoiceDate";
  static final String PARAM_RATE = "rate";
  static final String PARAM_PRODUCT_ID = "productId";

  private static final String MSG_RECORD_NOT_FOUND = "Record not found";
  private static final String KEY_TAX_ID = "taxId";
  private static final String KEY_IDENTIFIER = "identifier";

  /** The declared contract of {@code defaultTax}: a read, so nothing is persisted. */
  static final NeoActionContract DEFAULT_TAX = NeoActionContract.read(ACTION_DEFAULT_TAX,
      "Returns the tax a purchase invoice line should default to: {taxId, identifier, rate, "
          + "taxCategoryId, taxCategory, summary, source, candidates}. Without rate it is the "
          + "product's tax (source PRODUCT_CATEGORY) or the client's default category tax "
          + "(DEFAULT_CATEGORY); with rate, the tax of that percentage that best matches the "
          + "vendor address, date and product (RATE_MATCH, or one of the former two when they "
          + "carry that rate). taxId is null with source NO_MATCH (no tax has that rate) or "
          + "NO_CONTEXT (nothing to default to). candidates are the other taxes of the same rate.",
      Param.required(PARAM_PARTNER_ADDRESS_ID, NeoActionContract.TYPE_STRING,
          "the vendor's business partner location id"),
      Param.optional(PARAM_INVOICE_DATE, NeoActionContract.TYPE_DATE,
          "yyyy-MM-dd; today when omitted"),
      Param.optional(PARAM_RATE, NeoActionContract.TYPE_NUMBER,
          "the extracted tax percentage, e.g. 21"),
      Param.optional(PARAM_PRODUCT_ID, NeoActionContract.TYPE_STRING, "the product id"))
      .withIdDescription("always 'new'");

  private static final Map<String, NeoActionContract> CONTRACTS =
      Collections.singletonMap(ACTION_DEFAULT_TAX, DEFAULT_TAX);

  @Override
  public Map<String, NeoActionContract> actionContracts() {
    return CONTRACTS;
  }

  /**
   * Serves {@code defaultTax} directly. Anything else runs the parent's pre-hook first — a
   * response from it still short-circuits — then prices and derives the amounts of a created line,
   * or recalculates those of an updated one. The order matters: on a return invoice the parent
   * negates the quantity, and the amounts are derived from the negated one.
   */
  @Override
  public NeoResponse handle(NeoContext context) {
    if (NeoEndpointType.ACTION.equals(context.getEndpointType())
        && ACTION_DEFAULT_TAX.equals(context.getFieldName())) {
      return defaultTax(context);
    }
    NeoResponse response = super.handle(context);
    if (response != null) {
      return response;
    }
    return InvoiceLineAmountSupport.deriveAmountsOnWrite(context);
  }

  private NeoResponse defaultTax(NeoContext context) {
    if (!"POST".equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          "defaultTax only supports POST");
    }
    JSONObject body = context.getRequestBody() != null ? context.getRequestBody()
        : new JSONObject();
    NeoResponse invalid = NeoActionContract.validate(CONTRACTS, ACTION_DEFAULT_TAX, body);
    if (invalid != null) {
      return invalid;
    }
    try {
      return resolveAndRespond(body);
    } catch (InvalidParameterException e) {
      return NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE, e.getMessage());
    } catch (JSONException | RuntimeException e) {
      log.error("[ETP-5585] defaultTax failed: {}", e.getMessage(), e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Internal error resolving the default tax");
    }
  }

  private NeoResponse resolveAndRespond(JSONObject body) throws JSONException {
    String addressId = body.optString(PARAM_PARTNER_ADDRESS_ID, null);
    String productId = body.isNull(PARAM_PRODUCT_ID) ? null
        : StringUtils.trimToNull(body.optString(PARAM_PRODUCT_ID, null));
    Date date = parseDate(body);
    BigDecimal rate = parseRate(body);

    Result result = resolveOwned(addressId, productId, date, rate);
    if (result == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND, MSG_RECORD_NOT_FOUND);
    }
    JSONObject wrapper = new JSONObject();
    wrapper.put("response", new JSONObject().put("data", toJson(result)));
    return NeoResponse.ok(wrapper);
  }

  /**
   * The default tax for a vendor address, after checking the address (and the product, when given)
   * belong to the session's tenant — the lookup {@code defaultTax} serves, shared with the OCR
   * review (ETP-5715) so both answer with the very same rule.
   *
   * @param addressId the vendor's business partner location id
   * @param productId the product id, or {@code null}
   * @param date      the document date
   * @param rate      the printed percentage, or {@code null}
   * @return the resolution, or {@code null} when the address or the product is not the tenant's
   */
  static Result resolveOwned(String addressId, String productId, Date date, BigDecimal rate) {
    OBContext.setAdminMode(true);
    try {
      boolean foreignProduct = productId != null
          && NeoActionRecordGuard.loadOwned(Product.class, productId) == null;
      if (NeoActionRecordGuard.loadOwned(Location.class, addressId) == null || foreignProduct) {
        return null;
      }
    } finally {
      OBContext.restorePreviousMode();
    }
    OBContext session = OBContext.getOBContext();
    return DefaultTaxResolver.resolve(session.getCurrentOrganization().getId(),
        session.getCurrentClient().getId(), addressId, date, rate, productId, false);
  }

  private static Date parseDate(JSONObject body) {
    String raw = body.isNull(PARAM_INVOICE_DATE) ? null : body.optString(PARAM_INVOICE_DATE, null);
    if (StringUtils.isBlank(raw)) {
      return new Date();
    }
    try {
      // Start of day in the server timezone, matching c_gettax's timestamp cast of the date.
      return Date.from(LocalDate.parse(raw.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant());
    } catch (DateTimeParseException e) {
      throw new InvalidParameterException("Parameter invoiceDate is not a valid yyyy-MM-dd date");
    }
  }

  private static BigDecimal parseRate(JSONObject body) {
    if (!body.has(PARAM_RATE) || body.isNull(PARAM_RATE)) {
      return null;
    }
    String raw = String.valueOf(body.opt(PARAM_RATE)).trim();
    if (raw.isEmpty()) {
      return null;
    }
    try {
      return new BigDecimal(raw);
    } catch (NumberFormatException e) {
      throw new InvalidParameterException("Parameter rate is not a number");
    }
  }

  static JSONObject toJson(Result result) throws JSONException {
    JSONObject out = describe(result.chosen());
    out.put("source", result.source());
    JSONArray candidates = new JSONArray();
    for (Determination d : result.candidates()) {
      candidates.put(describe(d));
    }
    out.put("candidates", candidates);
    return out;
  }

  /** The tax fields of one determination; {@code null} fields for no determination. */
  private static JSONObject describe(Determination d) throws JSONException {
    JSONObject o = new JSONObject();
    o.put(KEY_TAX_ID, d != null ? d.taxId() : JSONObject.NULL);
    o.put(KEY_IDENTIFIER, d != null ? d.taxName() : JSONObject.NULL);
    o.put(PARAM_RATE, d != null && d.rate() != null ? d.rate().doubleValue() : JSONObject.NULL);
    o.put("taxCategoryId", d != null ? d.categoryId() : JSONObject.NULL);
    o.put("taxCategory", d != null ? d.categoryName() : JSONObject.NULL);
    o.put("summary", d != null && d.summary());
    return o;
  }

  /** A parameter that passed the declared shape but is not a real date or number. */
  private static final class InvalidParameterException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidParameterException(String message) {
      super(message);
    }
  }
}
