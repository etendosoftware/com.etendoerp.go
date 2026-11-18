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

package com.etendoerp.go.schemaforge;

import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_CATEGORY_ID;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DESCRIPTION;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_DISCOUNT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NAME;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_NEW;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_PRODUCT;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_QUANTITY;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UNIT_PRICE;
import static com.etendoerp.go.schemaforge.ocr.InboxDocument.K_UOM_ID;

import java.math.BigDecimal;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.ocr.InvoiceInboxTotals;

/**
 * The bodies of the records a confirmed inbox line is created with (ETP-6051): the purchase invoice
 * line and, for a line whose product does not exist yet, the product. Pure.
 *
 * <p>The line carries the PRINTED price as {@code listPrice}, the discount percent as
 * {@code etgoDiscount} and the net unit price {@code unit6 = round6(price x (1 - d/100))} as
 * {@code unitPrice}: the model of {@link InvoiceInboxTotals}, which GO applies to a line itself
 * ({@code InvoiceLineAmountSupport.applyDiscount} leaves a line whose two prices differ as sent), so
 * the invoice total is the one the review panel showed. The tax is always an id, never a rate.</p>
 */
final class InvoiceInboxConfirmLines {

  private InvoiceInboxConfirmLines() {
  }

  /**
   * The body of one invoice line.
   *
   * @param line      the line of the document
   * @param productId the product of the line (existing or just created)
   * @param taxId     the tax, {@code null} to let the line's own tax determination decide
   * @return the body
   * @throws JSONException when the object cannot be built
   */
  static JSONObject lineBody(JSONObject line, String productId, String taxId)
      throws JSONException {
    JSONObject body = new JSONObject().put("product", productId);
    String description = line.optString(K_DESCRIPTION, "").trim();
    if (!description.isEmpty()) {
      body.put("description", description);
    }
    BigDecimal quantity = decimal(line, K_QUANTITY);
    BigDecimal price = decimal(line, K_UNIT_PRICE);
    if (quantity != null) {
      body.put("invoicedQuantity", quantity);
    }
    if (price != null) {
      BigDecimal discount = discountOf(line);
      body.put("listPrice", price);
      body.put("unitPrice", InvoiceInboxTotals.unit6(price, discount));
      if (discount.signum() > 0) {
        body.put("etgoDiscount", discount);
      }
    }
    if (StringUtils.isNotBlank(taxId)) {
      body.put("tax", taxId);
    }
    return body;
  }

  /**
   * The body of the product created for a line: the name of the line unless the user chose another,
   * the category and the unit the user (or the default) chose, and the tax category of the line's
   * tax when known. Everything else comes from the product defaults of the create pipeline.
   *
   * @param line        the line of the document, its product in state {@code new}
   * @param taxCategory the tax category of the line's tax, {@code null} to take the default
   * @param searchKey   the next number of the product's document sequence (the column is mandatory
   *                    and the create pipeline does not assign it)
   * @return the body
   * @throws JSONException when the object cannot be built
   */
  static JSONObject productBody(JSONObject line, String taxCategory, String searchKey)
      throws JSONException {
    JSONObject draft = line.optJSONObject(K_PRODUCT).optJSONObject(K_NEW);
    String name = StringUtils.defaultIfBlank(text(draft, K_NAME), text(line, K_DESCRIPTION));
    JSONObject body = new JSONObject().put("name", name)
        .put("productCategory", text(draft, K_CATEGORY_ID)).put("uOM", text(draft, K_UOM_ID))
        .put("searchKey", searchKey);
    if (StringUtils.isNotBlank(taxCategory)) {
      body.put("taxCategory", taxCategory);
    }
    return body;
  }

  /** The discount percent of the line when valid (0-100), else zero: the rule of the totals. */
  static BigDecimal discountOf(JSONObject line) {
    BigDecimal discount = decimal(line, K_DISCOUNT);
    boolean valid = discount != null && discount.signum() >= 0
        && discount.compareTo(BigDecimal.valueOf(100)) <= 0;
    return valid ? discount : BigDecimal.ZERO;
  }

  private static BigDecimal decimal(JSONObject object, String key) {
    Object value = object == null ? null : object.opt(key);
    return value instanceof Number ? new BigDecimal(value.toString()) : null;
  }

  private static String text(JSONObject object, String key) {
    Object value = object == null ? null : object.opt(key);
    return value instanceof String ? ((String) value).trim() : "";
  }
}
