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
package com.etendoerp.go.mcp;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.financial.FinancialUtils;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.ProductPrice;

import com.etendoerp.go.schemaforge.BatchService;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Fills the unit price of a commercial line the agent created without one, resolving it from the
 * parent document's price list — MCP write path only.
 *
 * <p><b>Why this exists (ETP-5184 D-7 / section 14.8).</b> {@code SL_Order_Product} does not read
 * the price out of the database: it reads it from the selector's <em>auxiliary values</em>
 * ({@code inpmProductId_PSTD} / {@code _PLIST} / {@code _PLIM}), which in Classic the form carries
 * over from the product picker. On the NEO create path those aux values are resolved by
 * {@code SelectorAuxResolver#loadEntityForAux}, which queries the selector's
 * price-list-and-warehouse view filtering by product id alone —
 * {@code from ProductByPriceAndWarehouse where product.id = :val} with {@code setMaxResults(1)},
 * no {@code ORDER BY} and no price-list constraint. It therefore answers with an arbitrary row, or
 * with none at all, and the callout ends up publishing a price of 0.
 *
 * <p>The product selector's own AD where clause does not narrow by price list either (only
 * org/client natural tree plus the active flags), so going through the regular selector query
 * would be the same non-determinism by a longer route. The price of a line is defined by the
 * <em>parent document's</em> price list at the document's date, which is what
 * {@link FinancialUtils#getProductPrice} resolves — the same call, with the same getters, that
 * core's own {@code SRMOPickEditLines} makes for exactly this case.
 *
 * <p><b>Deliberately MCP-only.</b> The defect lives in the shared
 * {@code schemaforge} selector-aux path, which the React frontend and the REST
 * {@code /sws/neo/*} endpoints also use. Fixing it there would change behaviour for both, so by
 * decision this compensation sits in the MCP write path and those two callers keep the existing
 * behaviour. Anything that reaches {@code NeoCrudHandler} without passing through
 * {@code McpToolRouter#handleCreate} — including each {@code neo_batch} operation, which
 * {@code BatchService} dispatches straight into the shared create path — is NOT covered.
 */
final class McpLinePriceInjector {

  private static final String FIELD_PRODUCT = "product";
  private static final String FIELD_UNIT_PRICE = "unitPrice";
  private static final String FIELD_LIST_PRICE = "listPrice";
  private static final String FIELD_PRICE_LIMIT = "priceLimit";
  private static final String PROP_PRICE_LIST = "priceList";
  private static final String PROP_SALES_TRANSACTION = "salesTransaction";

  /**
   * Date properties to try on the parent document, in order. A sales/purchase order carries
   * {@code orderDate}, an invoice {@code invoiceDate}; {@code accountingDate} is the last resort
   * so a document type that names its date differently still resolves a valid price list version
   * instead of silently skipping.
   */
  private static final String[] PARENT_DATE_PROPERTIES = {
      "orderDate", "invoiceDate", "accountingDate"
  };

  private McpLinePriceInjector() {
  }

  /**
   * Resolves and writes {@code unitPrice}, {@code listPrice} and {@code priceLimit} when the agent
   * did not supply a price of its own.
   *
   * <p>Abstains — leaving the body untouched — whenever the price cannot be established with
   * certainty: no product, no resolvable parent document, no price list on it, or no price for the
   * product in that list at that date. Abstaining keeps the pre-existing behaviour (a price of 0
   * the agent can correct) rather than substituting a guess, which is the point: a wrong price
   * looks plausible and a zero does not.
   *
   * @param body             the create body, keyed by canonical DAL property name; mutated in place
   * @param dalEntity        the DAL entity being created; used to tell a commercial line from any
   *                         other child record, and to resolve the parent reference
   * @param sfEntity         the SF entity, used to resolve which field points at the parent
   * @param agentProvided    field names present in the body as submitted by the agent, BEFORE any
   *                         defaults pass ran — the only reliable witness that the agent chose a
   *                         price itself
   * @param log              the caller's logger, so these lines land in the MCP request's context
   */
  static void injectIfMissing(JSONObject body, Entity dalEntity, SFEntity sfEntity,
      Set<String> agentProvided, Logger log) {
    try {
      if (!isEligible(body, dalEntity, agentProvided)) {
        return;
      }
      BaseOBObject parent = resolveParentDocument(body, dalEntity, sfEntity);
      if (parent == null) {
        log.debug("[MCP-LINE-PRICE] No parent document resolved for {} — price left untouched",
            dalEntity.getName());
        return;
      }
      PriceList priceList = readPriceList(parent);
      if (priceList == null) {
        log.debug("[MCP-LINE-PRICE] Parent {} has no price list — price left untouched",
            parent.getEntityName());
        return;
      }
      if (Boolean.TRUE.equals(priceList.isPriceIncludesTax())) {
        // A tax-included price list stores the GROSS price in standardPrice, so writing it to the
        // net unitPrice would be wrong by the tax rate. Deriving the net side here would duplicate
        // the gross/net arithmetic SL_Order_Product owns, so abstain instead and let the agent set
        // the price explicitly. Logged rather than silent, because the price will come out 0.
        log.debug("[MCP-LINE-PRICE] Price list {} includes tax — net price not derived here",
            priceList.getIdentifier());
        return;
      }
      Date documentDate = readDocumentDate(parent);
      if (documentDate == null) {
        log.debug("[MCP-LINE-PRICE] Parent {} exposes no usable document date — price left untouched",
            parent.getEntityName());
        return;
      }
      applyResolvedPrice(body, dalEntity, parent, priceList, documentDate, log);
    } catch (Exception e) {
      // Never fail a create over a convenience default: without this the agent keeps the
      // pre-existing behaviour, with it the whole write would 500.
      log.debug("[MCP-LINE-PRICE] Price injection skipped: {}", e.getMessage());
    }
  }

  /**
   * @return {@code true} when this is a commercial line whose price the agent left for the server
   *         to resolve.
   */
  private static boolean isEligible(JSONObject body, Entity dalEntity, Set<String> agentProvided)
      throws JSONException {
    if (body == null || dalEntity == null) {
      return false;
    }
    // Only entities that actually carry a unit price — this must not fire on unrelated child tabs.
    if (!dalEntity.hasProperty(FIELD_UNIT_PRICE) || !dalEntity.hasProperty(FIELD_LIST_PRICE)) {
      return false;
    }
    String productId = body.optString(FIELD_PRODUCT, "");
    if (StringUtils.isBlank(productId)) {
      return false;
    }
    // A "$ref:<opId>" placeholder is not an id yet: the op it points at has not run.
    if (productId.startsWith(BatchService.REF_PREFIX)) {
      return false;
    }
    // The agent's own price wins, exactly as userProvided protects it from the callout cascade.
    if (agentProvided != null
        && (agentProvided.contains(FIELD_UNIT_PRICE) || agentProvided.contains(FIELD_LIST_PRICE))) {
      return false;
    }
    return !hasNonZeroPrice(body);
  }

  /**
   * @return {@code true} when the body already carries a real (non-zero) unit price, whatever put
   *         it there. A zero is what this injector exists to replace, so it does not count.
   */
  private static boolean hasNonZeroPrice(JSONObject body) {
    double unitPrice = body.optDouble(FIELD_UNIT_PRICE, 0d);
    return !Double.isNaN(unitPrice) && Double.compare(unitPrice, 0d) != 0;
  }

  /**
   * Loads the parent document referenced by the line's link-to-parent field.
   *
   * <p>Uses {@link McpParentScope} so the field is the same one the rest of the MCP write path
   * resolves, rather than a second guess at which column points at the parent.
   */
  private static BaseOBObject resolveParentDocument(JSONObject body, Entity dalEntity,
      SFEntity sfEntity) {
    String parentField = McpParentScope.forEntity(sfEntity).getParentField();
    if (StringUtils.isBlank(parentField) || !dalEntity.hasProperty(parentField)) {
      return null;
    }
    String parentId = body.optString(parentField, "");
    if (StringUtils.isBlank(parentId)
        || parentId.startsWith(BatchService.REF_PREFIX)) {
      return null;
    }
    Property parentProperty = dalEntity.getProperty(parentField, false);
    if (parentProperty == null || parentProperty.getTargetEntity() == null) {
      return null;
    }
    Object parent = OBDal.getInstance()
        .get(parentProperty.getTargetEntity().getName(), parentId);
    return parent instanceof BaseOBObject ? (BaseOBObject) parent : null;
  }

  private static PriceList readPriceList(BaseOBObject parent) {
    Object value = readProperty(parent, PROP_PRICE_LIST);
    return value instanceof PriceList ? (PriceList) value : null;
  }

  /**
   * @return the first of {@link #PARENT_DATE_PROPERTIES} the parent actually exposes, or
   *         {@code null} when it exposes none.
   */
  private static Date readDocumentDate(BaseOBObject parent) {
    for (String property : PARENT_DATE_PROPERTIES) {
      Object value = readProperty(parent, property);
      if (value instanceof Date) {
        return (Date) value;
      }
    }
    return null;
  }

  /**
   * Reads a property by name, tolerating its absence.
   *
   * <p>The parent may be any document entity, so a missing property is an expected outcome and not
   * an error: {@code hasProperty} is checked first so no exception is raised for the normal case.
   */
  private static Object readProperty(BaseOBObject parent, String propertyName) {
    try {
      if (!parent.getEntity().hasProperty(propertyName)) {
        return null;
      }
      return parent.get(propertyName);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Resolves the price through core and writes it into the body.
   *
   * <p>{@code throwException=false} makes {@link FinancialUtils#getProductPrice} answer
   * {@code null} for a product absent from the list, which is the abstain case rather than a
   * failure — the same outcome core's {@code SRMOPickEditLines} reaches by catching
   * {@code OBException} and defaulting to zero.
   */
  private static void applyResolvedPrice(JSONObject body, Entity lineEntity, BaseOBObject parent,
      PriceList priceList, Date documentDate, Logger log) throws JSONException {
    Product product = OBDal.getInstance()
        .get(Product.class, body.optString(FIELD_PRODUCT, ""));
    if (product == null) {
      return;
    }
    boolean salesTransaction = Boolean.TRUE.equals(readProperty(parent, PROP_SALES_TRANSACTION));
    ProductPrice productPrice = FinancialUtils.getProductPrice(product, documentDate,
        salesTransaction, priceList, false);
    if (productPrice == null) {
      log.debug("[MCP-LINE-PRICE] Product {} has no price in list {} at {} — price left untouched",
          product.getIdentifier(), priceList.getIdentifier(), documentDate);
      return;
    }
    putIfDeclared(body, lineEntity, FIELD_UNIT_PRICE, productPrice.getStandardPrice());
    putIfDeclared(body, lineEntity, FIELD_LIST_PRICE, productPrice.getListPrice());
    putIfDeclared(body, lineEntity, FIELD_PRICE_LIMIT, productPrice.getPriceLimit());
    log.debug("[MCP-LINE-PRICE] Resolved {} from list {}: unitPrice={} listPrice={}",
        product.getIdentifier(), priceList.getIdentifier(), productPrice.getStandardPrice(),
        productPrice.getListPrice());
  }

  /**
   * Writes one price field, skipping a null amount and any field the line does not declare.
   *
   * <p>{@code priceLimit} in particular is not present on every line entity, and writing a field
   * the DAL entity does not declare would fail the insert.
   */
  private static void putIfDeclared(JSONObject body, Entity lineEntity, String field,
      BigDecimal amount) throws JSONException {
    if (amount == null || lineEntity == null || !lineEntity.hasProperty(field)) {
      return;
    }
    body.put(field, amount);
  }
}
