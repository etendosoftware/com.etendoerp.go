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

import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.plm.Product;

/**
 * ETP-5680: the product-type rules {@link ProductDefaultsHandler} applies on an update and to the
 * {@code /defaults} payload, kept apart so the handler stays within the class-size limit. Product
 * specific by design: it names {@code productType}, {@code stocked}, {@code returnable} and
 * {@code taxCategory}, and is reached only from that handler.
 */
final class ProductTypeRules {

  private static final Logger log = LogManager.getLogger(ProductTypeRules.class);

  private ProductTypeRules() {
  }

  /**
   * ETP-5680: the product-type rules on an update (REST PATCH/PUT, MCP {@code etendo_update}).
   *
   * <ul>
   *   <li>The effective type is the body's {@code productType}, else the persisted product's (read
   *       from the record id), so a request that edits only {@code weight} on a Service is
   *       enforced too. A non-stockable effective type forces {@code stocked} and
   *       {@code returnable} to {@code false}.</li>
   *   <li>A REAL type change (valid body type different from the persisted one) with no
   *       {@code taxCategory} key in the body sets the type's named category, as a create does. An
   *       explicit {@code taxCategory} always wins; an unchanged type never touches it (saving
   *       never recalculates); a category that does not resolve leaves the body untouched.</li>
   * </ul>
   * Any failure of the persisted lookup leaves the body as received.
   *
   * @param body                the update body, mutated in place
   * @param recordId            the record being updated; may be {@code null}
   * @param clientId            the client to scope the category lookup to; may be {@code null}
   * @param persistedTypeReader reads the persisted type of a record id, or {@code null} if unknown
   * @param taxCategoryResolver resolves (type, client) to a tax category id, or {@code null}
   * @throws JSONException if the body cannot be written
   */
  static void applyOnUpdate(JSONObject body, String recordId, String clientId,
      UnaryOperator<String> persistedTypeReader,
      BiFunction<String, String, String> taxCategoryResolver) throws JSONException {
    String bodyType = StringUtils.trimToNull(body.optString(ProductDefaultsHandler.FIELD_PRODUCT_TYPE, null));
    boolean typeIsValid = bodyType != null && ProductDefaultsHandler.PRODUCT_TYPES.contains(bodyType);
    boolean taxNeedsComparison = typeIsValid && !body.has(ProductDefaultsHandler.FIELD_TAX_CATEGORY);
    String persisted = (bodyType == null || taxNeedsComparison)
        ? persistedTypeReader.apply(recordId) : null;
    forceNonStockable(body, bodyType != null ? bodyType : persisted);
    if (taxNeedsComparison && persisted != null && !bodyType.equals(persisted)) {
      String id = taxCategoryResolver.apply(bodyType, clientId);
      if (id != null) {
        body.put(ProductDefaultsHandler.FIELD_TAX_CATEGORY, id);
      }
    }
  }

  /**
   * The persisted {@code productType} of a product, or {@code null} when there is no record id, the
   * product is not found or the read fails — the caller then treats the type as unknown.
   *
   * @param recordId the product id; may be blank
   * @return the persisted type, or {@code null} when unknown
   */
  static String readPersistedType(String recordId) {
    if (StringUtils.isBlank(recordId)) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      Product product = OBDal.getInstance().get(Product.class, recordId);
      return product != null ? StringUtils.trimToNull(product.getProductType()) : null;
    } catch (Exception e) {
      log.debug("Could not read the persisted product type of {}: {}", recordId, e.getMessage());
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * ETP-5680: the {@code stocked} / {@code returnable} defaults follow the product type, as the
   * create path already enforces. The generic resolver answers the column defaults
   * ({@code IsStocked='Y'}, {@code Returnable='Y'}, i.e. Item) whatever type is asked for, so for a
   * non-stockable type both are overridden with {@code false}. Runs after the tax-category step,
   * which has already set {@code defaults.productType} to the requested type when it is valid, so
   * that value is the effective type; every other type is left as the generic resolver answered.
   *
   * @param defaults the {@code /defaults} payload, mutated in place
   * @throws JSONException if the payload cannot be written
   */
  static void forceNonStockableDefaults(JSONObject defaults) throws JSONException {
    forceNonStockable(defaults,
        StringUtils.trimToNull(defaults.optString(ProductDefaultsHandler.FIELD_PRODUCT_TYPE, null)));
  }

  /**
   * Puts a category id (and its {@code $_identifier} companion when known) under {@code key}.
   *
   * @param defaults   the payload, mutated in place
   * @param key        the property to write
   * @param id         the category id
   * @param identifier the display value, or {@code null}
   * @throws JSONException if the payload cannot be written
   */
  static void putCategory(JSONObject defaults, String key, String id, String identifier)
      throws JSONException {
    defaults.put(key, id);
    if (identifier != null) {
      defaults.put(key + ProductDefaultsHandler.IDENTIFIER_SUFFIX, identifier);
    }
  }

  private static void forceNonStockable(JSONObject target, String type) throws JSONException {
    if (type != null && ProductDefaultsHandler.NON_STOCKABLE_PRODUCT_TYPES.contains(type)) {
      target.put(ProductDefaultsHandler.FIELD_STOCKED, false);
      target.put(ProductDefaultsHandler.FIELD_RETURNABLE, false);
    }
  }
}
